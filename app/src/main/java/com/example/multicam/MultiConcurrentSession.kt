package com.example.multicam

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 多摄并发会话（含修改 12–24 部分）：
 * - 每镜头连拍 N 帧
 * - 对焦/曝光联动（作用于第一颗镜头）
 * - AI 构图实时预览（SceneAdvisor 风景优先 + 自动变焦）
 */
@RequiresApi(Build.VERSION_CODES.R)
class MultiConcurrentSession(
    private val context: Context,
    private val cameraIds: List<String>,
    private val size: Size,
    private val previewSurface: Surface?,
    private val framesPerCamera: Int,
    private val compositionEnabled: Boolean = false,
    private val compositionOverlay: ((CompositionAnalyzer.Suggestion?) -> Unit)? = null,
    private val currentZoomProvider: () -> Float = { 1f },
    private val onZoomRequest: ((Float) -> Unit)? = null,
    private val orientationTracker: OrientationTracker? = null,
    private val getYaw: (() -> Float)? = null,
    private val onTargetReached: ((SceneAdvisor.Advice) -> Unit)? = null,
    private val onAllFrames: (Map<String, List<ByteArray>>) -> Unit,
    private val onError: (String) -> Unit,
    private val onSessionReady: () -> Unit
) {
    private val manager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val devices = mutableMapOf<String, CameraDevice>()
    private val readers = mutableMapOf<String, ImageReader>()
    private val sessions = mutableMapOf<String, CameraCaptureSession>()
    private val frameCache = mutableMapOf<String, MutableList<ByteArray>>()
    private val frameLock = Any()
    private var retryCount = 0
    private val maxRetry = 3
    private var handler: Handler? = null
    private var readyCount = 0

    private var analysisReader: ImageReader? = null
    private var characteristics: CameraCharacteristics? = null

    /**
     * 【关键修复】逐路 characteristics。
     * 原来只有一个共享字段，只在第一个 onOpened 时填一次 → 所有路的 crop region
     * 都套用第一颗相机（超广角）的 active array，长焦必然越界丢帧。
     */
    private val charsById = mutableMapOf<String, CameraCharacteristics>()
    private var lastAnalysisTime = 0L
    private val analysisIntervalMs = 400L
    /**
     * 【关键修复】"主控路" = 主摄，不是 cameraIds[0]。
     *
     * CameraRoleResolver 返回的列表按等效焦距**升序**排，
     * 等效焦距 超广角(~13mm) < 主摄(~25mm) < 长焦(~120mm)，
     * 所以 cameraIds[0] 恒为**超广角**。
     *
     * 原来预览 Surface、变焦、对焦、闪光、EV/ISO 全挂在 cameraIds[0]，
     * 于是多摄模式下：画面明显变广变糊、变焦上限从主摄的 5~10× 掉到超广角的
     * 2~3×、点画面对焦跳到超广角、闪光/EV 只对超广角生效 → 用户以为"多摄坏了"。
     *
     * 现在：预览/控制走主摄（没有主摄就退到列表第一颗），
     * 但**每一颗**仍各自独立采集（crop 已按各自 characteristics 计算）。
     */
    private val primaryId: String by lazy {
        val pick = cameraIds.sortedBy { id ->
            val chars = runCatching {
                manager.getCameraCharacteristics(id)
            }.getOrNull()
            chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.minOrNull()?.let { f ->
                    val sw = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                        ?.width ?: 0f
                    // 等效焦距越大 = 焦段越长 = 越"长"，主摄应排前面
                    if (sw > 0f) -(f * (36f / sw)) else 0f
                } ?: 0f
        }
        // 取"最长焦段但不超过主摄"的一颗：优先等效焦距落在 20~60mm 的
        val main = cameraIds.firstOrNull { eqFocalOf(it)?.let { e -> e in 20f..60f } == true }
        main ?: pick.firstOrNull() ?: firstId
    }

    private fun eqFocalOf(id: String): Float? = runCatching {
        val c = manager.getCameraCharacteristics(id)
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull() ?: return null
        val sw = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 0f
        if (sw > 0f) f * (36f / sw) else null
    }.getOrNull()

    private val firstId: String get() = cameraIds.firstOrNull() ?: ""

    /**
     * 【关键修复】相机/会话 map 的锁。
     * 原来 devices / sessions / readers 是裸 HashMap，跨线程读写：
     * 写方是 CameraThread（onOpened / onDisconnected / onConfigured），
     * 读方是主线程（stop() ← releaseInternal() ← restartCamera/onPause/onDestroy，
     * 以及 capture()）。HashMap 并发写会导致结构损坏，且遍历时 modCount 变化
     * 抛 ConcurrentModificationException —— 这个异常从 closeAll() 一路
     * 穿到无 try 的主线程 restartCamera() → 直接闪退。
     * runCatching 只包住了单个元素的 close()，包不住 values.forEach 的迭代器。
     */
    private val mapLock = Any()

    /**
     * 【关键修复】已停止标志。
     * stop() 里 removeCallbacksAndMessages 清不掉"已投递/已到期"的回调，
     * 迟到的 onOpened 会把相机重新塞回 map 且永不被关闭 → 相机会被永久占用。
     */
    @Volatile private var stopped = false

    /** 对焦取消任务代际号：stop()/重开时自增以作废悬挂的延迟任务 */
    private var focusCancelToken = 0

    // 修改 23：变焦
    private var currentZoom = 1f
    private var autoZoomEnabled = false
    private var maxZoom = 1f

    fun start(handler: Handler) {
        this.handler = handler
        // 【关键修复】重新启用：stop() 把 stopped 置 true，而 applyPreferredRole
        // 会复用同一个 MultiConcurrentSession 实例，不复位就永远不会重开相机。
        stopped = false
        retryCount = 0
        synchronized(frameLock) {
            frameCache.clear()
            cameraIds.forEach { frameCache[it] = mutableListOf() }
        }
        cameraIds.forEach { id ->
            // 【重要】队列容量必须一次给足：start() 发生在 applyControls(plan) 之前，
            // 此刻 controls.bracket 可能是旧计划甚至 NONE，按当时值算会在切到多帧模式时丢帧。
            // 统一取 framesPerCamera 与 MAX_BRACKET_FRAMES 的较大者。
            val q = maxOf(framesPerCamera + 1, MAX_BRACKET_FRAMES)
            val r = ImageReader.newInstance(
                size.width, size.height, ImageFormat.JPEG, q.coerceAtLeast(4)
            )
            r.setOnImageAvailableListener({ reader ->
                // 修复（2026-10）：连拍必须用 acquireNextImage()。
                // acquireLatestImage() 会丢弃队列里所有旧帧、只留最新一帧，
                // ImageReader 的 maxImages=(framesPerCamera+1) 队列很快被"最新帧"占满，
                // 结果是连拍 N 帧实际只拿到 1~2 帧，`onAllFrames` 可能永远凑不齐
                // → 多摄合成路径完全不触发（表现为按下快门后一直停在"处理中"）。
                while (true) {
                    val img = reader.acquireNextImage() ?: break
                    try {
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        handleFrame(id, bytes)
                    } catch (_: Throwable) {
                        // 单帧失败不能让整个 while 循环崩掉
                    } finally {
                        runCatching { img.close() }
                    }
                }
            }, handler)
            synchronized(mapLock) { readers[id] = r }
        }

        if (compositionEnabled) {
            analysisReader?.close()
            analysisReader = ImageReader.newInstance(
                640, 480, ImageFormat.YUV_420_888, 2
            ).apply {
                setOnImageAvailableListener({ r ->
                    handleAnalysisFrame(r)
                }, handler)
            }
        }
        openAll()
    }

    // ---------- 修改 21/23：实时构图分析（SceneAdvisor 风景优先） ----------

    private fun handleAnalysisFrame(r: ImageReader) {
        val now = System.currentTimeMillis()
        if (now - lastAnalysisTime < analysisIntervalMs) {
            r.acquireLatestImage()?.close()
            return
        }
        lastAnalysisTime = now
        val img = r.acquireLatestImage() ?: return
        // 【关键修复】gray 原来在 try 体内 release，回调抛异常就漏一张原生 Mat；
        // 且到位判定无去重 → 每 400ms 重复触发 Toast + setRepeatingRequest。
        var gray: Mat? = null
        try {
            gray = yuvToGray(img)
            val advice = SceneAdvisor.analyze(gray, currentZoomProvider())
            if (advice != null) {
                compositionOverlay?.invoke(toSuggestion(advice))
                // 【关键修复】与单摄同一问题：每 400ms 无条件下发会导致
                // 极限环振荡，且用户手动调焦会被自动拽回。
                if (autoZoomEnabled && advice.targetZoom > 0f) {
                    val cur = currentZoomProvider()
                    if (abs(advice.targetZoom - cur) > 0.15f &&
                        abs(advice.targetZoom - lastAutoZoom) > 0.01f
                    ) {
                        lastAutoZoom = advice.targetZoom
                        onZoomRequest?.invoke(advice.targetZoom)
                    }
                }
                val curYaw = getYaw?.invoke() ?: 0f
                val remain = advice.targetYawDelta - curYaw
                val inPosition = Math.abs(remain) < 5f && advice.targetZoom > 0f
                if (inPosition) {
                    if (!targetReachedLatched) {
                        targetReachedLatched = true
                        onTargetReached?.invoke(advice)
                    }
                } else if (Math.abs(remain) > 12f) {
                    targetReachedLatched = false
                }
            }
        } catch (e: Throwable) {
            AppLogger.w("MultiConcurrent", "分析帧失败: ${e.message}")
        } finally {
            runCatching { gray?.release() }
            runCatching { img.close() }
        }
    }

    /** 到位提示锁存：避免每 400ms 重复触发 */
    @Volatile private var targetReachedLatched = false

    /** 上次自动变焦下发的目标倍率，用于去重、避免极限环振荡 */
    @Volatile private var lastAutoZoom = 0f

    private fun yuvToGray(image: Image): Mat {
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width; val h = image.height
        val yBuf = plane.buffer
        val gray = Mat(h, w, CvType.CV_8UC1)
        try {
            val row = ByteArray(w)
            val data = ByteArray(w * h)
            val src = ByteArray(yBuf.remaining())
            yBuf.get(src)
            var dst = 0
            for (y in 0 until h) {
                val start = y * rowStride
                // 修复：Y plane 末尾 padding 可能不足 rowStride*h，越界会抛异常并泄漏 gray
                if (start + w > src.size) {
                    java.util.Arrays.fill(data, dst, (dst + w).coerceAtMost(data.size), 0)
                    dst += w
                    continue
                }
                if (pixelStride == 1) {
                    System.arraycopy(src, start, data, dst, w)
                    dst += w
                } else {
                    for (x in 0 until w) {
                        val idx = start + x * pixelStride
                        row[x] = if (idx < src.size) src[idx] else 0
                    }
                    System.arraycopy(row, 0, data, dst, w)
                    dst += w
                }
            }
            gray.put(0, 0, data)
            return gray
        } catch (e: Exception) {
            gray.release()
            throw e
        }
    }

    private fun toSuggestion(a: SceneAdvisor.Advice?): CompositionAnalyzer.Suggestion? {
        if (a == null) return null
        return CompositionAnalyzer.Suggestion(
            type = a.type,
            confidence = 0.8f,
            guidePoints = listOfNotNull(a.guideAnchor ?: a.guidePoints.firstOrNull()),
            message = a.message,
            score = a.score,
            tiltDeg = a.horizonAngle,
            subjectX = a.subjectX,
            subjectY = a.subjectY,
            hint = a.hint,
            // ---------- DOKA 风格 AR 引导字段透传 ----------
            targetBox = a.targetBox,
            hasSubject = a.targetBox != null,
            aligned = a.aligned,
            alignProgress = a.alignProgress,
            alignX = a.alignX,
            alignY = a.alignY,
            ruleName = a.ruleName,
            recommendZoom = a.recommendZoom,
            arLines = a.arLines
        )
    }

    // ---------- 变焦（作用于主摄 primaryId） ----------

    fun setZoom(zoom: Float) {
        val id = primaryId
        val device = synchronized(mapLock) { devices[id] } ?: return
        val session = synchronized(mapLock) { sessions[id] } ?: return
        // 【修复】coerceIn 要求 min<=max，极端 HAL 上报 maxDigitalZoom<1 会抛
        // IllegalArgumentException，而原来这行在 try 之外。
        val hi = if (maxZoom >= 1f) maxZoom else 1f
        val z = zoom.coerceIn(1f, hi)
        try {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                previewSurface?.let { addTarget(it) }
                analysisReader?.let { addTarget(it.surface) }
                computeCropRegion(z)?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
                // 【关键修复】原来漏了 applyControlsTo(this)。
                // setRepeatingRequest 是**整体替换**不是增量 patch：这个请求只带
                // surface + crop，于是 AE 模式/闪光灯/EV/AWB/手动 ISO·快门全部回到
                // 默认值。表现为"设好白平衡和闪光灯，一碰变焦就全被清掉"。
                // 与同文件 applyControls() 里的写法对齐。
                applyControlsTo(this)
            }
            session.setRepeatingRequest(req.build(), null, handler)
            currentZoom = z
        } catch (e: Throwable) {
            AppLogger.w("MultiConcurrent", "变焦失败: ${e.message}")
        }
    }

    /**
     * 预览/控制路（primaryId）的 crop region。
     *
     * 【关键修复】原来读共享字段 `characteristics`，它只在第一个 onOpened 时
     * 为**第一颗相机**（超广角）填一次。而预览 Surface 绑在 primaryId（主摄）上，
     * 于是 zoom>1 时拿超广角的 active array 去裁主摄 → 越界 →
     * IllegalArgumentException 被 catch 吞掉 → **多摄模式点变焦预览纹丝不动**。
     */
    private fun computeCropRegion(zoom: Float): Rect? {
        val chars = charsOf(primaryId) ?: return null
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: return null
        val z = if (zoom.isFinite() && zoom >= 1f) zoom else 1f
        val w = (active.width() / z).toInt().coerceIn(1, active.width())
        val h = (active.height() / z).toInt().coerceIn(1, active.height())
        val cx = active.centerX()
        val cy = active.centerY()
        val r = Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        if (r.left < active.left || r.top < active.top ||
            r.right > active.right || r.bottom > active.bottom
        ) return null
        return r
    }

    fun setAutoZoom(enabled: Boolean) { autoZoomEnabled = enabled }
    fun getZoom(): Float = currentZoom

    // 修改 27：重置构图聚类 + 陀螺仪起点
    fun resetCompositionTracking() {
        SceneAdvisor.resetTracking()
        orientationTracker?.reset()
    }

    /** 本轮期望每颗镜头返回的帧数（capture() 时确定，供 handleFrame 凑批判定） */
    @Volatile private var expectedPerCamera = 0

    /**
     * 本轮结果是否已交付。
     *
     * 修复：超时兜底 [flushIfTimeout] 会把 frameCache 清空并把 expectedPerCamera 置 0，
     * 之后迟到的帧进入 [handleFrame] 时 expect 会退化成 1，第一帧就再次凑批触发
     * [onAllFrames] —— 用户会收到两次合成结果（多存一张、进度条二次弹出）。
     * 用此标志保证每轮只交付一次，直到下次 capture() 重置。
     */
    @Volatile private var deliveredThisRound = false

    private fun handleFrame(cameraId: String, bytes: ByteArray) {
        // 修复：凑批阈值原来恒为 framesPerCamera，而 capture() 在包围模式下会改为
        // plan.effectiveFrames（如 HDR 7 帧、星空 4 帧）。期望帧数 > framesPerCamera 时
        // 永远凑不齐 → onAllFrames 不触发，按下快门后一直卡在"处理中"；
        // 期望帧数 < framesPerCamera 时会多收帧而被丢弃/错位。
        // 改为读取 capture() 写入的 expectedPerCamera。
        if (deliveredThisRound) {
            // 本轮已因超时降级交付，忽略迟到帧
            return
        }
        val expect = expectedPerCamera.coerceAtLeast(1)
        val complete: Map<String, List<ByteArray>>? = synchronized(frameLock) {
            val list = frameCache.getOrPut(cameraId) { mutableListOf() }
            if (list.size >= expect) return@synchronized null
            list.add(bytes)
            if (cameraIds.all { (frameCache[it]?.size ?: 0) >= expect }) {
                val copy = frameCache.mapValues { it.value.toList() }
                frameCache.values.forEach { it.clear() }
                copy
            } else null
        }
        if (complete != null) {
            deliveredThisRound = true
            expectedPerCamera = 0
            onAllFrames(complete)
        }
    }

    private fun openAll() {
        // 【关键修复】原来没有"已停止"标志：stop() 里 removeCallbacksAndMessages
        // 只能清掉尚未投递的消息，已经投递/已到期的 onOpened 拦不住
        //（quitSafely 明确会执行所有已到期消息）。迟到的 onOpened 会执行
        // devices[id] = camera，而 closeAll() 早已跑完并清空 map → 这个
        // CameraDevice 再也没人 close() → 新一轮 openCamera 拿到
        // MAX_CAMERAS_IN_USE → 黑屏 + "Camera already in use"，必须杀进程。
        if (stopped) return
        var opened = 0
        var failed = false
        cameraIds.forEach { id ->
            try {
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        // 迟到的回调：立刻关掉这个相机，不让它占住 HAL
                        if (stopped) { runCatching { camera.close() }; return }
                        synchronized(mapLock) { devices[id] = camera }
                        if (characteristics == null) {
                            characteristics = runCatching {
                                manager.getCameraCharacteristics(id)
                            }.getOrNull()
                        }
                        // 【修复】逐路记录，长焦/超广角各有自己的 active array
                        synchronized(mapLock) {
                            if (!charsById.containsKey(id)) {
                                val c = runCatching {
                                    manager.getCameraCharacteristics(id)
                                }.getOrNull()
                                if (c != null) charsById[id] = c
                            }
                        }
                        // 【修复】maxZoom 取该路自己的上限，而不是第一颗（超广角通常只有 2~3×）
                        maxZoom = charsOf(id)
                            ?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                            ?.takeIf { it >= 1f }
                            ?: 1f
                        opened++
                        if (!failed && opened == cameraIds.size) createAllSessions()
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        runCatching { camera.close() }
                        // 【关键修复】必须校验身份。上一代设备的断开回调迟到时，
                        // 原来会无条件 devices.remove(id) —— 而新一代已经 open 好
                        // 并占着同一个 id，那颗设备就被从 map 里摘掉且永不被 close
                        // → 相机被 HAL 永久占用 → MAX_CAMERAS_IN_USE。
                        val stale = synchronized(mapLock) {
                            if (devices[id] === camera) { devices.remove(id); sessions.remove(id); false }
                            else true
                        }
                        if (stale || stopped) return
                        scheduleRetry("摄像头 $id 断开")
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        runCatching { camera.close() }
                        val stale = synchronized(mapLock) {
                            if (devices[id] === camera) { devices.remove(id); sessions.remove(id); false }
                            else true
                        }
                        if (stale || stopped) return
                        failed = true
                        scheduleRetry("摄像头 $id 错误 code=$error")
                    }
                }, handler)
            } catch (e: Throwable) {
                if (stopped) return
                failed = true
                scheduleRetry("打开摄像头 $id 失败: ${e.message}")
            }
        }
    }

    private fun scheduleRetry(reason: String) {
        if (stopped) return
        if (retryCount >= maxRetry) {
            onError("$reason（已重试 $retryCount 次，放弃）"); return
        }
        retryCount++
        AppLogger.w("MultiConcurrent", "重试 $retryCount/$maxRetry：$reason")
        closeAll()
        handler?.postDelayed({ openAll() }, 500L * retryCount)
    }

    private fun createAllSessions() {
        readyCount = 0
        cameraIds.forEach { createOneSession(it) }
    }

    private fun createOneSession(id: String) {
        val device: CameraDevice
        val reader: ImageReader
        synchronized(mapLock) {
            device = devices[id] ?: return
            reader = readers[id] ?: return
        }
        // 【关键修复】原来 Executors.newSingleThreadExecutor() 从不 shutdown，
        // 每次重开相机（模式切换/RAW/构图/高像素开关/前后台）都漏一个线程。
        try {
            val outputs = mutableListOf<OutputConfiguration>()
            outputs.add(OutputConfiguration(reader.surface))
            val isPrimary = (id == primaryId)
            if (isPrimary && previewSurface != null) {
                outputs.add(OutputConfiguration(previewSurface))
            }
            if (isPrimary && analysisReader != null) {
                outputs.add(OutputConfiguration(analysisReader!!.surface))
            }
            val ex = java.util.concurrent.Executors.newSingleThreadExecutor()
            synchronized(mapLock) { sessionExecutors[id] = ex }
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                ex,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        // 迟到的 onConfigured：会话已停，不能再写回并当成 ready
                        if (stopped) { runCatching { s.close() }; return }
                        synchronized(mapLock) { sessions[id] = s }
                        if (id == primaryId && previewSurface != null) {
                            startPreviewLoop(s, device)
                        }
                        readyCount++
                        if (readyCount == cameraIds.size) onSessionReady()
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (stopped) return
                        scheduleRetry("会话配置失败 ($id)")
                    }
                }
            )
            device.createCaptureSession(config)
        } catch (e: Throwable) {
            // 【修复】原来只 catch(Exception)：OutOfMemoryError / UnsatisfiedLinkError
            // 会穿透到 CameraThread 无人接管 → 进程崩溃。
            AppLogger.e("MultiConcurrent", "创建会话异常 $id", e)
            scheduleRetry("创建会话异常 $id: ${e.message}")
        }
    }

    /** 会话回调 Executor：stop() 必须 shutdown，否则每次重开相机漏一个线程 */
    private val sessionExecutors = mutableMapOf<String, java.util.concurrent.ExecutorService>()

    // ---------- 手动控制：闪光 / EV / ISO / 快门 ----------

    @Volatile private var controls: CameraControls = CameraControls()

    fun applyControls(c: CameraControls) {
        controls = c
        val dev = synchronized(mapLock) { devices[primaryId] } ?: return
        val s = synchronized(mapLock) { sessions[primaryId] } ?: return
        val surface = previewSurface ?: return
        try {
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                analysisReader?.let { addTarget(it.surface) }
                // 修复（2026-10）：原实现漏掉了 SCALER_CROP_REGION，
                // 任何一次控制项变更（切闪光/调 EV/改 ISO）都会把数码变焦重置回 1×，
                // 与 setZoom() 的结果互相打架。
                computeCropRegion(currentZoom)?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
                applyControlsTo(this)
            }
            s.setRepeatingRequest(req.build(), null, handler)
        } catch (e: Throwable) {
            AppLogger.w("MultiConcurrent", "应用控制失败: ${e.message}")
        }
    }

    private fun applyControlsTo(
        b: CaptureRequest.Builder,
        frameIndex: Int = 0,
        plan: BracketPlan = BracketPlan.NONE
    ) {
        val c = controls
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        when (c.flashMode) {
            CameraControls.FLASH_ON ->
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            CameraControls.FLASH_AUTO ->
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
            CameraControls.FLASH_TORCH ->
                b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            else -> {}
        }
        // 【包围优先】逐帧显式 ISO + 快门；否则用逐帧 EV / 全局 EV
        val iso = plan.isoAt(frameIndex)
        val shut = plan.shutterAt(frameIndex)
        if (plan.isBracketed && iso != null && shut != null) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shut)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(shut, 33_333_333L))
        } else {
            // 【关键修复】同 FullResCaptureSession：Camera2 的曝光补偿是 1/6 EV 一档，
            // 原来乘 2 导致整个系统只输出标称值的 1/3（UI 显示 +2.0 实际 +0.67EV，
            // HDR 包围退化成 ±0.67EV，合成后动态范围拉不回来）。
            b.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                Math.round(
                    (plan.evAt(frameIndex) ?: c.evCompensation).coerceIn(-2f, 2f) * EV_STEP_PER_UNIT
                ).toInt().coerceIn(-12, 12)
            )
            if (c.isManualExposure) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, c.isoManual)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, c.shutterNs)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(c.shutterNs, 33_333_333L))
            }
        }
        if (c.wbManual) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }
    }

    private fun startPreviewLoop(s: CameraCaptureSession, device: CameraDevice) {
        val surface = previewSurface ?: return
        try {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                .apply {
                    addTarget(surface)
                    // 修复 21：分析流必须进重复请求
                    if (sessions.keys.contains(primaryId)) {
                        analysisReader?.let { addTarget(it.surface) }
                    }
                    applyControlsTo(this)
                }
            s.setRepeatingRequest(req.build(), null, handler)
        } catch (e: Exception) {
            AppLogger.w("MultiConcurrent", "预览启动失败: ${e.message}")
        }
    }

    fun capture() {
        synchronized(frameLock) { frameCache.values.forEach { it.clear() } }
        val plan = controls.bracket
        val frames = if (plan.isBracketed) plan.effectiveFrames else framesPerCamera
        // 先写期望帧数，再发请求：回调可能在任何时刻到达，必须先让 handleFrame 知道阈值。
        deliveredThisRound = false
        expectedPerCamera = frames.coerceAtLeast(1)
        cameraIds.forEach { id ->
            val device = devices[id] ?: return@forEach
            val session = sessions[id] ?: return@forEach
            val reader = readers[id] ?: return@forEach
            try {
                for (i in 0 until frames) {
                    val req = device
                        .createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        .apply {
                            addTarget(reader.surface)
                            set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                            // 【关键修复】原来所有路都用 computeCropRegion()，
                            // 而它读的是 characteristics —— 只在第一个 onOpened 时
                            // 为**第一颗相机**填一次。于是把 camera0（超广角）的
                            // SENSOR_INFO_ACTIVE_ARRAY_SIZE 套到长焦上，
                            // 长焦的 active array 尺寸/宽高比不同 → crop 越界 →
                            // build()/框架抛 IllegalArgumentException →
                            // 被下面的 catch 吞掉 → 该路 0 帧且 UI 完全无感，
                            // 表现为"三摄合成每次都少一路"。
                            // 现在按每路自己的 characteristics 算，越界则退回该路全幅。
                            val crop = computeCropRegionFor(id, currentZoom)
                            if (crop != null) {
                                set(CaptureRequest.SCALER_CROP_REGION, crop)
                            }
                            applyControlsTo(this, i, plan)
                        }
                    val readerSurf = runCatching { reader.surface }.getOrNull()
                    if (readerSurf == null) {
                        AppLogger.e("MultiConcurrent", "拍照失败 $id：reader 已释放")
                        continue
                    }
                    session.capture(req.build(), null, handler)
                }
            } catch (e: Throwable) {
                // 原来只 catch(Exception)：OOM 等 Error 会穿透到 CameraThread 崩溃
                AppLogger.e("MultiConcurrent", "拍照失败 $id: ${e.message}", e)
            }
        }
    }

    /** 各路相机自己的 characteristics（第一路回退到旧的共享字段） */
    private fun charsOf(id: String): CameraCharacteristics? {
        synchronized(mapLock) {
            charsById[id]?.let { return it }
        }
        return if (id == cameraIds.firstOrNull()) characteristics else null
    }

    /**
     * 按指定相机的 active array 计算 crop region。
     * 越界/非法时返回 null，调用方就不设 SCALER_CROP_REGION（用该路全幅）。
     */
private fun computeCropRegionFor(id: String, zoom: Float): Rect? {
        val chars = charsOf(id) ?: return null
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: return null
        val z = if (zoom.isFinite() && zoom >= 1f) zoom else 1f
        val w = (active.width() / z).toInt().coerceIn(1, active.width())
        val h = (active.height() / z).toInt().coerceIn(1, active.height())
        val cx = active.centerX()
        val cy = active.centerY()
        // 【关键修复】原来这里写成了 Rect(cx - w/2, cx - w/2, cx + w/2, cx + w/2)，
        // top/bottom 误用了 w —— 横向 active array（如 4000×3000）会得到
        // Rect(0,0,4000,4000)，下面的越界检查直接判 null → 裁剪恒失效；
        // 若恰好不越界则是静默裁掉底部整片画面。
        val r = Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        // 必须完整落在 active array 内，否则框架直接拒绝整条请求
        if (r.left < active.left || r.top < active.top ||
            r.right > active.right || r.bottom > active.bottom
        ) return null
        return r
    }

    /** 【新增】超时兜底：把已收到的帧交出去，避免 onAllFrames 永不触发导致卡死 */
    fun flushIfTimeout() {
        val expect = expectedPerCamera
        if (expect <= 0 || deliveredThisRound) return
        val complete: Map<String, List<ByteArray>>? = synchronized(frameLock) {
            if (cameraIds.none { (frameCache[it]?.size ?: 0) > 0 }) return@synchronized null
            val copy = frameCache.mapValues { it.value.toList() }
            frameCache.values.forEach { it.clear() }
            copy
        }
        expectedPerCamera = 0
        deliveredThisRound = true
        if (complete != null) {
            AppLogger.w(
                "MultiConcurrent",
                "多摄连拍超时，已收帧数=" +
                    cameraIds.joinToString(",") { "$it:${complete[it]?.size ?: 0}" } +
                    "（期望 $expect）"
            )
            onAllFrames(complete)
        } else {
            // 【关键修复】零帧时原来静默 return —— 既不 onAllFrames 也不 onError，
            // 而 MainActivity 的 hideProgress() 只挂在 onResult / onError / onStatus 上
            // → 进度遮罩永久停留、快门变哑巴，用户只能切模式恢复。
            // 这里必须显式报错，让 UI 有机会复位。
            AppLogger.e("MultiConcurrent", "多摄连拍一帧都没回来（期望 $expect）")
            onError("多摄连拍失败：未收到任何画面，请重试或切到单摄")
        }
    }

    // ---------- 修改 12：对焦/曝光联动（第一颗镜头） ----------

    fun triggerFocus(focusRect: Rect, lockAe: Boolean) {
        val id = primaryId
        val device = devices[id] ?: return
        val session = sessions[id] ?: return
        val surface = previewSurface ?: return
        try {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                analysisReader?.let { addTarget(it.surface) }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_REGIONS,
                    arrayOf(MeteringRectangle(focusRect, 1)))
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                if (lockAe) {
                    set(CaptureRequest.CONTROL_AE_LOCK, true)
                    set(CaptureRequest.CONTROL_AE_REGIONS,
                        arrayOf(MeteringRectangle(focusRect, 1)))
                }
            }
            session.capture(req.build(), null, handler)

            if (!lockAe) {
                val token = ++focusCancelToken
                handler?.postDelayed({
                    // 修复：延迟任务可能落在 stop()/重建之后，此时 session 已关闭，
                    // 直接复用会抛 IllegalStateException。用代际号作废并复核身份。
                    if (token != focusCancelToken) return@postDelayed
                    val live = sessions[id]
                    if (live == null || live !== session) return@postDelayed
                    runCatching {
                        val cancel = device
                            .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(surface)
                                analysisReader?.let { addTarget(it.surface) }
                                set(CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                set(CaptureRequest.CONTROL_AF_TRIGGER,
                                    CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                            }
                        live.capture(cancel.build(), null, handler)
                    }
                }, 1500L)
            }
        } catch (e: Exception) {
            AppLogger.e("MultiConcurrent", "对焦失败: ${e.message}", e)
        }
    }

    fun lockAfAe(focusRect: Rect) {
        val id = primaryId
        val device = devices[id] ?: return
        val session = sessions[id] ?: return
        val surface = previewSurface ?: return
        try {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                analysisReader?.let { addTarget(it.surface) }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_REGIONS,
                    arrayOf(MeteringRectangle(focusRect, 1)))
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                set(CaptureRequest.CONTROL_AE_LOCK, true)
                set(CaptureRequest.CONTROL_AE_REGIONS,
                    arrayOf(MeteringRectangle(focusRect, 1)))
            }
            session.capture(req.build(), null, handler)
        } catch (e: Exception) {
            AppLogger.e("MultiConcurrent", "锁定 AF/AE 失败: ${e.message}", e)
        }
    }

    fun unlockAfAe() {
        val id = primaryId
        val device = devices[id] ?: return
        val session = sessions[id] ?: return
        val surface = previewSurface ?: return
        try {
            val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                analysisReader?.let { addTarget(it.surface) }
                set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                set(CaptureRequest.CONTROL_AE_LOCK, false)
            }
            session.capture(req.build(), null, handler)
        } catch (e: Exception) {
            AppLogger.e("MultiConcurrent", "解锁失败: ${e.message}", e)
        }
    }

    private fun closeAll() {
        // 【关键修复】先在锁内"摘出"快照并清空 map，再在锁外做 close()。
        // 原来直接在 map 上遍历 close：与 CameraThread 的写入并发时
        // HashMap 迭代器抛 ConcurrentModificationException，且 close() 是
        // 阻塞调用，持有锁做它还会把 CameraThread 卡住。
        val sessionSnap: List<CameraCaptureSession>
        val deviceSnap: List<CameraDevice>
        val execSnap: List<java.util.concurrent.ExecutorService>
        synchronized(mapLock) {
            sessionSnap = sessions.values.toList()
            deviceSnap = devices.values.toList()
            execSnap = sessionExecutors.values.toList()
            sessions.clear()
            devices.clear()
            sessionExecutors.clear()
        }
        // 【关键修复】必须 shutdown 回调 Executor，否则每次重开相机漏一个线程，
        // 切模式几十次后线程数暴涨 → ANR / Thread creation failed。
        execSnap.forEach { runCatching { it.shutdown() } }
        sessionSnap.forEach { runCatching { it.stopRepeating() } }
        sessionSnap.forEach { runCatching { it.close() } }
        deviceSnap.forEach { runCatching { it.close() } }
    }

    fun stop() {
        // 先置标志：任何在途回调看到 stopped 都会直接放掉相机而不复活会话
        stopped = true
        handler?.removeCallbacksAndMessages(null)
        focusCancelToken++
        expectedPerCamera = 0
        closeAll()
        val readerSnap = synchronized(mapLock) {
            val s = readers.values.toList(); readers.clear(); s
        }
        readerSnap.forEach { runCatching { it.close() } }
        runCatching { analysisReader?.close() }
        analysisReader = null
        synchronized(frameLock) { frameCache.values.forEach { it.clear() } }
    }

    private companion object {
        /** 包围曝光最大帧数（HDR 7 帧，留到 9 槽位保险） */
        const val MAX_BRACKET_FRAMES = 9

        /** Camera2 曝光补偿每计数单位 = 1/6 EV（±12 → ±2EV） */
        const val EV_STEP_PER_UNIT = 6f
    }
}
