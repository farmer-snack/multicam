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
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 全像素/单摄会话（含修改 12–24 部分）：
 * - 对焦/曝光联动（单击对焦+锁AE、长按锁AF/AE）
 * - AI 构图实时预览（640×480 YUV，SceneAdvisor 风景优先 + 自动变焦）
 * - RAW/DNG 输出（DngCreator，独立 RAW 请求 + 独立 TotalCaptureResult）
 * - 变焦（SCALER_CROP_REGION）
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class FullResCaptureSession(
    private val context: Context,
    private val cameraId: String,
    private val fullSize: Size,
    private val previewSurface: Surface?,
    private val useMaximumResolution: Boolean = true,
    private val compositionEnabled: Boolean = false,
    private val compositionOverlay: ((CompositionAnalyzer.Suggestion?) -> Unit)? = null,
    private val enableRaw: Boolean = false,
    private val onRawReady: ((ByteArray) -> Unit)? = null,
    private val currentZoomProvider: () -> Float = { 1f },
    private val onZoomRequest: ((Float) -> Unit)? = null,
    private val orientationTracker: OrientationTracker? = null,
    private val getYaw: (() -> Float)? = null,
    private val onTargetReached: ((SceneAdvisor.Advice) -> Unit)? = null,
    private val onJpegReady: (ByteArray) -> Unit,
    /** 【新增】包围曝光完成回调：一次性拿到 N 帧 JPEG（按曝光顺序） */
    private val onBracketReady: ((List<ByteArray>) -> Unit)? = null,
    private val onError: (String) -> Unit,
    private val onSessionReady: () -> Unit
) {
    private val manager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var analysisReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var characteristics: CameraCharacteristics? = null
    private var retryCount = 0
    private val maxRetry = 3
    private var handler: Handler? = null

    // 修改 21：构图分析节流
    private var lastAnalysisTime = 0L
    private val analysisIntervalMs = 400L

    /** 对焦取消任务代际号：stop()/重开时自增以作废悬挂的延迟任务 */
    private var focusCancelToken = 0

    // 修改 23：变焦
    private var currentZoom = 1f
    private var autoZoomEnabled = false
    private var maxZoom = 1f

    // 修改 15/18：RAW 请求独立保存 result + EXIF 方向
    private var rawEnabled = false
    private var lastCaptureResult: TotalCaptureResult? = null
    private var rawCaptureResult: TotalCaptureResult? = null
    private var lastOrientation: Int = 0

    fun start(handler: Handler) {
        // 【关键修复】复用同一实例重开相机时复位失效标志，
        // 否则 openCamera() 开头的 `if (stopped) return` 会让相机永远开不起来。
        stopped = false
        retryCount = 0
        this.handler = handler
        openCamera()
    }

    private fun openCamera() {
        // 【关键修复】重开前必须先关掉旧 device。
        // 原来 scheduleRetry 直接 openCamera(同一 cameraId)，而此时 device 还开着
        // → Camera2 抛 CAMERA_IN_USE → 被下面的 catch 吞成普通 onError
        // → 用户看到"打开相机失败: Camera in use"，相机停在占用态不可用。
        if (stopped) return
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null
        device = null
        try { manager.openCamera(cameraId, stateCallback, handler) }
        catch (e: SecurityException) { onError("无相机权限") }
        catch (e: Throwable) { onError("打开相机失败: ${e.message}") }
    }

    /**
     * 【关键修复】已停止标志。
     * stop() 的 removeCallbacksAndMessages 清不掉"已投递/已到期"的回调
     * （quitSafely 明确执行所有已到期消息）。迟到的 onOpened 会把相机写进
     * device 字段，而 stop() 早已把它置空 → 这个 CameraDevice 永不被 close
     * → 新一轮 openCamera 拿到 MAX_CAMERAS_IN_USE → 永久黑屏，必须杀进程。
     */
    @Volatile private var stopped = false

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            // 迟到的回调：立刻放掉相机，不让它占住 HAL
            if (stopped) { runCatching { camera.close() }; return }
            device = camera; createSession(camera)
        }
        override fun onDisconnected(camera: CameraDevice) {
            // 【关键修复】校验身份：上一代设备的断开回调迟到时，原来会无条件
            // device = null，把正在用的新设备字段抹掉 → 后续所有 setRepeatingRequest
            // 抛异常，且那颗设备永不被 close。
            if (device !== camera) { runCatching { camera.close() }; return }
            runCatching { camera.close() }; device = null
            if (stopped) return
            scheduleRetry("相机断开")
        }
        override fun onError(camera: CameraDevice, error: Int) {
            if (device !== camera) { runCatching { camera.close() }; return }
            runCatching { camera.close() }; device = null
            if (stopped) return
            scheduleRetry("相机错误 code=$error")
        }
    }

    private fun scheduleRetry(reason: String) {
        if (stopped) return
        if (retryCount >= maxRetry) {
            onError("$reason（已重试 $retryCount 次，放弃）"); return
        }
        retryCount++
        AppLogger.w("FullRes", "重试 $retryCount/$maxRetry：$reason")
        handler?.postDelayed({ openCamera() }, 500L * retryCount)
    }

    private fun createSession(camera: CameraDevice) {
        try {
            characteristics = manager.getCameraCharacteristics(cameraId)
            val chars = characteristics ?: run { onError("无法获取特性"); return }
            maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                ?: 1f

            val map = if (useMaximumResolution &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ) {
                chars.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
                )
            } else {
                chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            } ?: run {
                onError(if (useMaximumResolution) "设备不支持全像素" else "无法获取配置")
                return
            }

            val jpegSize = map.getOutputSizes(ImageFormat.JPEG)
                .maxByOrNull { it.width * it.height }
                ?: run { onError("找不到 JPEG 输出尺寸"); return }

            reader?.close()
            // 【重要】队列容量必须一次性给足最大包围帧数。
            // createSession 发生在 applyControls(plan) 之前，此刻 controls.bracket 可能还是旧计划
            // （甚至 NONE），若按当时的值算容量，切到 HDR(7 帧) 时会在第 3 帧就开始丢帧。
            // 这里统一给到 9（= MAX_BRACKET_FRAMES），非包围场景只用到 1~2，多几个槽位无成本。
            reader = ImageReader.newInstance(
                jpegSize.width, jpegSize.height, ImageFormat.JPEG, MAX_BRACKET_FRAMES
            ).apply {
                setOnImageAvailableListener({ r ->
                    // 修复：原实现用 acquireLatestImage()，在连拍/包围场景下会丢掉中间帧，
                    // 导致 HDR/夜景等拿不到足够帧数。改为 acquireNextImage() 循环排空队列。
                    while (true) {
                        val img = r.acquireNextImage() ?: break
                        try {
                            val buf = img.planes[0].buffer
                            val bytes = ByteArray(buf.remaining())
                            buf.get(bytes)
                            dispatchJpeg(bytes)
                        } catch (e: Exception) {
                            AppLogger.w("FullRes", "读取 JPEG 帧失败: ${e.message}")
                        } finally {
                            img.close()
                        }
                    }
                }, handler)
            }

            val outputs = mutableListOf<OutputConfiguration>()
            previewSurface?.let { outputs.add(OutputConfiguration(it)) }
            outputs.add(OutputConfiguration(reader!!.surface))

            // 构图实时分析：一路 640×480 YUV（SceneAdvisor 风景优先）
            if (compositionEnabled) {
                analysisReader?.close()
                analysisReader = ImageReader.newInstance(
                    640, 480, ImageFormat.YUV_420_888, 2
                ).apply {
                    setOnImageAvailableListener({ r ->
                        handleAnalysisFrame(r)
                    }, handler)
                }
                outputs.add(OutputConfiguration(analysisReader!!.surface))
            }

            // RAW_SENSOR 输出（DngCreator 生成 DNG）
            if (enableRaw && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
                val rawSize = rawSizes?.maxByOrNull { it.width * it.height }
                if (rawSize != null) {
                    rawReader?.close()
                    rawReader = ImageReader.newInstance(
                        rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2
                    ).apply {
                        setOnImageAvailableListener({ r ->
                            r.acquireLatestImage()?.use { img ->
                                val result = rawCaptureResult ?: lastCaptureResult
                                val ch = characteristics
                                if (result == null || ch == null) {
                                    AppLogger.w("FullRes", "DNG 缺少 result/characteristics，跳过")
                                    return@use
                                }
                                writeDng(img, ch, result)?.let { onRawReady?.invoke(it) }
                            }
                        }, handler)
                    }
                    outputs.add(OutputConfiguration(rawReader!!.surface))
                    rawEnabled = true
                } else {
                    AppLogger.w("FullRes", "设备不支持 RAW_SENSOR")
                }
            }

            val ex = java.util.concurrent.Executors.newSingleThreadExecutor()
            synchronized(execLock) { sessionExecutor = ex }
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                ex,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        // 【关键修复】这里原先漏了 stopped 守卫（onOpened/onDisconnected/
                        // onError 都加了，唯独这个没加）。stop() 与 onConfigured 之间没有任何
                        // 同步：会话已配好但设备已被关闭时，下面这行
                        // camera.createCaptureRequest() 会抛 IllegalStateException，
                        // 而异常是抛进 SessionConfiguration 的 ExecutorService ——
                        // ThreadPoolExecutor 不捕获任务异常，直接走
                        // Thread.dispatchUncaughtException **杀进程**。
                        if (stopped) { runCatching { s.close() }; return }
                        runCatching {
                            session = s
                            previewRequestBuilder = camera.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW
                            ).apply {
                                previewSurface?.let { addTarget(it) }
                                // 修复 21：分析流必须进重复请求，否则收不到帧
                                analysisReader?.let { addTarget(it.surface) }
                                applyControlsTo(this)
                            }
                            startPreviewLoop(s)
                            onSessionReady()
                        }.onFailure { e ->
                            AppLogger.e("FullRes", "配置会话后初始化失败", e)
                            runCatching { s.close() }
                        }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (stopped) return
                        scheduleRetry("会话配置失败")
                    }
                }
            )
            camera.createCaptureSession(config)
        } catch (e: Throwable) {
            // 【修复】原来只 catch(Exception)：OOM / UnsatisfiedLinkError 会穿透
            AppLogger.e("FullRes", "创建会话异常", e)
            scheduleRetry("创建会话异常: ${e.message}")
        }
    }

    /** 会话回调 Executor：stop() 必须 shutdown，否则每次重开相机漏一个线程 */
    private val execLock = Any()
    private var sessionExecutor: java.util.concurrent.ExecutorService? = null

    private fun startPreviewLoop(s: CameraCaptureSession) {
        val builder = previewRequestBuilder ?: return
        try { s.setRepeatingRequest(builder.build(), null, handler) }
        catch (e: Exception) { AppLogger.w("FullRes", "预览启动失败: ${e.message}") }
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
        // 【关键修复】原来 gray.release() 在 try 体内，compositionOverlay /
        // onZoomRequest / onTargetReached 任一抛异常就跳到 catch，gray 永不释放。
        // 分析节流是 400ms → 最坏每 400ms 漏一张原生 Mat，跑一小时 ≈ 2.7GB。
        var gray: Mat? = null
        try {
            gray = yuvToGray(img)
            val advice = SceneAdvisor.analyze(gray, currentZoomProvider())
            if (advice != null) {
                compositionOverlay?.invoke(toSuggestion(advice))
                // 【关键修复】自动变焦原来每 400ms 无条件下发，
                // 且没有「用户正在手动调焦」的检测 → 用户手动拉到 10× 后会被
                // 自动变焦慢慢拽回来；配合档位吸附还会形成 1.0↔1.5 极限环振荡。
                // 现在：① 结果必须与当前实际倍率有实质差异才下发；
                //      ② 下发后记住所需倍率，短时间内不再重复下发。
                if (autoZoomEnabled && advice.targetZoom > 0f) {
                    val cur = currentZoomProvider()
                    if (abs(advice.targetZoom - cur) > 0.15f &&
                        abs(advice.targetZoom - lastAutoZoom) > 0.01f
                    ) {
                        lastAutoZoom = advice.targetZoom
                        onZoomRequest?.invoke(advice.targetZoom)
                    }
                }
                // 修改 27：陀螺仪到位判断 —— 手机转到建议位置后触发变焦/调色
                val curYaw = getYaw?.invoke() ?: 0f
                val remain = advice.targetYawDelta - curYaw
                // 【关键修复】原来没有去重/锁存。主体居中时 targetYawDelta 恒为 0，
                // 而 curYaw 开机也是 0 → |0-0|<5 每帧成立 → 每 400ms 弹一次
                // "已到位" Toast 并重建一次 CaptureRequest（预览每 0.4s 顿一下）。
                // 加锁存：一次到位只触发一次，直到条件真的离开再复位。
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
            AppLogger.w("FullRes", "分析帧失败: ${e.message}")
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
                // 修复：部分机型的 Y plane buffer 末尾 padding 小于 rowStride*h，
                // 直接 arraycopy 会抛 ArrayIndexOutOfBounds 且泄漏 gray。
                // 越界时退化为全黑行（构图分析对少量黑行不敏感），保证不崩。
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
            // 异常路径必须释放，否则分析流每帧泄漏一张 Mat
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

    // ---------- 修改 23：变焦 ----------

    fun setZoom(zoom: Float) {
        val camera = device ?: return
        val s = session ?: return
        // 【修复】coerceIn 要求 min<=max，否则直接抛 IllegalArgumentException。
        // 极端 HAL 上报 SCALER_AVAILABLE_MAX_DIGITAL_ZOOM < 1 就会崩，
        // 而原来这行在 try 之外。
        val hi = if (maxZoom >= 1f) maxZoom else 1f
        val z = zoom.coerceIn(1f, hi)
        try {
            // 没有任何 target 时 build() 会抛 IllegalArgumentException（预览永远不出现，
            // 但 capture() 只 add JPEG surface 所以仍能出图 → "黑屏但能存图"）
            if (previewSurface == null && analysisReader == null) return
            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                previewSurface?.let { addTarget(it) }
                analysisReader?.let { addTarget(it.surface) }
                set(CaptureRequest.SCALER_CROP_REGION, computeCropRegion(z))
                // 【关键修复】原来漏了 applyControlsTo(this)。
                // setRepeatingRequest 是**整体替换**不是增量 patch：这个请求只带
                // surface + crop，于是 AE 模式/闪光灯/EV/AWB/手动 ISO·快门全部
                // 回到默认值。触发点极多（点变焦条、自动变焦每 400ms 一次），
                // 表现为"设好白平衡和闪光灯，一碰变焦就全被清掉"。
                applyControlsTo(this)
            }
            s.setRepeatingRequest(req.build(), null, handler)
            currentZoom = z
        } catch (e: Throwable) {
            AppLogger.w("FullRes", "变焦失败: ${e.message}")
        }
    }

    private fun computeCropRegion(zoom: Float): Rect {
        val chars = characteristics ?: return Rect(0, 0, 1, 1)
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: return Rect(0, 0, 1, 1)
        val w = (active.width() / zoom).toInt()
        val h = (active.height() / zoom).toInt()
        val cx = active.centerX()
        val cy = active.centerY()
        return Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    fun setAutoZoom(enabled: Boolean) { autoZoomEnabled = enabled }
    fun getZoom(): Float = currentZoom

    // 修改 27：重置构图聚类 + 陀螺仪起点
    fun resetCompositionTracking() {
        SceneAdvisor.resetTracking()
        orientationTracker?.reset()
    }

    // ---------- 修改 12：对焦/曝光联动 ----------

    fun triggerFocus(focusRect: Rect, lockAe: Boolean) {
        val camera = device ?: return
        val s = session ?: return
        val surface = previewSurface ?: return
        try {
            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
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
            s.capture(req.build(), null, handler)

            if (!lockAe) {
                val token = ++focusCancelToken
                handler?.postDelayed({
                    // 修复：延迟 1.5s 后 session 可能已被 stop()/重建，原实现直接复用
                    // 捕获的 `s` 与 `camera`，会对已关闭的会话发起 capture →
                    // IllegalStateException / CameraAccessException 噪声甚至崩溃。
                    // 用 focusCancelToken 作废过期任务，并复核 session 身份。
                    if (token != focusCancelToken) return@postDelayed
                    val live = session
                    if (live == null || live !== s) return@postDelayed
                    runCatching {
                        val cancel = camera
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
            AppLogger.e("FullRes", "对焦失败: ${e.message}", e)
        }
    }

    fun lockAfAe(focusRect: Rect) {
        val camera = device ?: return
        val s = session ?: return
        val surface = previewSurface ?: return
        try {
            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
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
            s.capture(req.build(), null, handler)
        } catch (e: Exception) {
            AppLogger.e("FullRes", "锁定 AF/AE 失败: ${e.message}", e)
        }
    }

    fun unlockAfAe() {
        val camera = device ?: return
        val s = session ?: return
        val surface = previewSurface ?: return
        try {
            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                analysisReader?.let { addTarget(it.surface) }
                set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                set(CaptureRequest.CONTROL_AE_LOCK, false)
            }
            s.capture(req.build(), null, handler)
        } catch (e: Exception) {
            AppLogger.e("FullRes", "解锁失败: ${e.message}", e)
        }
    }

    // ---------- 手动控制：闪光 / EV / ISO / 快门 ----------

    @Volatile private var controls: CameraControls = CameraControls()

    /** 应用手动控制（闪光、EV、ISO/快门），刷新当前重复请求 */
    fun applyControls(c: CameraControls) {
        controls = c
        val camera = device ?: return
        val s = session ?: return
        try {
            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                previewSurface?.let { addTarget(it) }
                analysisReader?.let { addTarget(it.surface) }
                applyControlsTo(this)
                set(CaptureRequest.SCALER_CROP_REGION, computeCropRegion(currentZoom))
            }
            s.setRepeatingRequest(req.build(), null, handler)
        } catch (e: Exception) {
            AppLogger.w("FullRes", "应用控制失败: ${e.message}")
        }
    }

    /** 把控制参数写入请求构建器 */
    private fun applyControlsTo(
        b: CaptureRequest.Builder,
        frameIndex: Int = 0,
        plan: BracketPlan = BracketPlan.NONE
    ) {
        val c = controls
        // 闪光灯
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        when (c.flashMode) {
            CameraControls.FLASH_ON -> {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            CameraControls.FLASH_AUTO -> {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
            CameraControls.FLASH_TORCH -> {
                b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            }
            else -> {
                b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }
        }

        // 【包围优先】逐帧显式 ISO + 快门（HDR/夜景/星空/时光慢门走这条路径）
        val iso = plan.isoAt(frameIndex)
        val shut = plan.shutterAt(frameIndex)
        if (plan.isBracketed && iso != null && shut != null) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shut)
            // 帧间隔必须 >= 曝光时间，否则相机 HAL 会报错/丢帧
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(shut, 33_333_333L))
        } else {
            // 曝光补偿：包围计划提供了逐帧 EV 就用它，否则用全局 EV
            val ev = (plan.evAt(frameIndex) ?: c.evCompensation).coerceIn(-2f, 2f)
            // 【关键修复】Camera2 的 CONTROL_AE_EXPOSURE_COMPENSATION 以
            // **1/6 EV 为一个步进单位**（-12..+12 对应 ±2EV）。
            // 原来乘 2 而不是乘 6 → 整个系统只输出了标称值的 1/3：
            //   UI 显示 +2.0 实际只有 +0.67EV
            //   HDR 的 evSteps=[-2,-1,0,1,2,0,0.5] 实际只做了 ±0.67EV 包围，
            //   合成后动态范围拉不回来，表现为"HDR 没效果"。
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                Math.round(ev * EV_STEP_PER_UNIT).toInt().coerceIn(-12, 12))

            // 手动 ISO / 快门
            if (c.isManualExposure) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, c.isoManual)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, c.shutterNs)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(c.shutterNs, 33_333_333L))
            }
        }

        // 手动白平衡：需同时关闭 AWB 并切到矩阵模式，增益才会生效
        if (c.wbManual) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            b.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            val k = c.wbTemperature.coerceIn(2000f, 8000f)
            val inv = 5500f / k
            b.set(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                android.hardware.camera2.params.RggbChannelVector(
                    inv, 1f, 1f, 1f / inv
                )
            )
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }
    }

    // ---------- 修改 15/18：RAW/DNG ----------

    fun setOrientation(orientation: Int) {
        lastOrientation = orientation
    }

    private fun writeDng(
        image: Image,
        chars: CameraCharacteristics,
        result: TotalCaptureResult
    ): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        return try {
            val baos = ByteArrayOutputStream()
            val creator = DngCreator(chars, result)
            try {
                if (lastOrientation != 0) creator.setOrientation(lastOrientation)
                creator.setDescription("MultiCam DNG")
                creator.writeImage(baos, image)
            } finally {
                creator.close()
            }
            baos.toByteArray()
        } catch (e: Exception) {
            AppLogger.e("FullRes", "写 DNG 失败: ${e.message}", e)
            null
        }
    }

    /**
     * 修复（2026-10）：加"快门防抖"。
     *
     * 原实现没有任何节流，用户快速连点/长按快门时会在极短时间内提交多组 STILL_CAPTURE
     * 请求，而全像素（尤其 SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION）单次要 1–3 秒：
     *  - 多次 `stopRepeating()` + capture 交错，Camera2 会返回
     *    `CameraAccessException: CAMERA_ERROR` 或直接断流；
     *  - JPEG ImageReader 队列被填满，`acquireNextImage` 拿不到帧，画面卡死在"处理中"。
     *
     * 这里用 @Volatile 标记 + 最小间隔双重保护。间隔按是否全像素区分：
     * 全像素留 900ms，普通单摄 400ms；同时在拍摄期间忽略请求。
     */
    @Volatile private var capturing = false
    @Volatile private var lastCaptureAt = 0L
    private val captureMinIntervalMs get() = if (useMaximumResolution) 900L else 400L

    // ---------- 【新增】包围曝光：多帧聚合 ----------

    /** 本轮包围曝光期望收到的帧数（非包围时为 0） */
    @Volatile private var bracketExpect = 0
    /** 已收到的帧（按曝光顺序） */
    private val bracketBuf = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())

    /**
     * 【新增】JPEG 到达后的分派：
     * - 非包围：沿用旧行为，逐帧 [onJpegReady]（首个 JPEG 到达即解除 capturing，
     *   否则队列满 / 回调丢失时快门会永久卡在"处理中"）
     * - 包围：聚合到 [bracketExpect] 帧后一次性 [onBracketReady]
     *
     * 若设备少回帧（部分机型在 AE OFF 下可能吞帧），由 [capture] 里的超时兜底
     * 用已收到的帧数触发回调，保证不会卡死。
     */
    private fun dispatchJpeg(bytes: ByteArray) {
        // 【关键修复】本轮已超时降级交付 → 迟到的残余帧直接丢弃。
        // 否则这些帧走 expect<=0 的单张路径被逐张存盘，用户相册里会多出
        // 几张满分辨率废图（"HDR 拍 7 帧只回 3 帧 → 多了 4 张废片"）。
        if (droppedRound) { AppLogger.w("FullRes", "丢弃本轮迟到的残余帧"); return }
        val expect = bracketExpect
        if (expect <= 0) {
            // 修复：单张路径原来只在 onCaptureCompleted 里复位 capturing，
            // 若该回调因断流/异常丢失，capturing 会永远为 true → 快门彻底失效。
            capturing = false
            onJpegReady(bytes)
            return
        }
        bracketBuf.add(bytes)
        if (bracketBuf.size >= expect) {
            val frames: List<ByteArray>
            synchronized(bracketBuf) {
                frames = ArrayList(bracketBuf)
                bracketBuf.clear()
            }
            bracketExpect = 0
            capturing = false
            handler?.post { onBracketReady?.invoke(frames) }
        }
    }

    /** 【新增】兜底：超时后把已收到的帧交出去（可能少于期望帧数） */
    private fun flushBracketIfTimeout() {
        if (bracketExpect <= 0) return
        // 【关键修复】代际号校验。同文件 focusCancelToken 已经用过这个套路，
        // 但超时兜底漏了：第 N 拍的兜底任务排在 t0+24s，若用户在星空/慢门
        // 采集期间切模式再拍一轮，第 N 拍的兜底会醒来把**新一轮**的
        // droppedRound 置 true（→ 新轮所有帧被丢弃）、capturing 清掉、
        // bracketBuf 提前交付 → HDR 直接夭折。
        if (captureRound != bracketRound) { AppLogger.d("FullRes", "跳过过期的包围兜底"); return }
        // 【关键修复】原来超时把 bracketExpect 清 0，但那一轮的其余帧还在路上。
        // 它们到达时看到 expect<=0 → 走单张路径 onJpegReady → 存成"原图"，
        // 于是 HDR 拍 7 帧只回 3 帧时，相册里除了合成图还多出 4 张满分辨率废片。
        // 用 droppedRound 标记"本轮已降级交付"，之后的迟到帧直接丢弃。
        droppedRound = true
        val frames: List<ByteArray>
        synchronized(bracketBuf) {
            frames = ArrayList(bracketBuf)
            bracketBuf.clear()
        }
        bracketExpect = 0
        capturing = false
        if (frames.isNotEmpty()) {
            AppLogger.w("FullRes", "包围曝光超时，仅收到 ${frames.size} 帧，降级处理")
            handler?.post { onBracketReady?.invoke(frames) }
        } else {
            // 【修复】原来零帧时 onError，但 UI 的 hideProgress 只挂在少数回调上，
            // 统一补上错误提示，避免进度遮罩永久停留。
            handler?.post { onError("包围曝光未收到任何帧，请重试") }
        }
    }

    /**
     * 【关键修复】本轮已因超时降级交付 → 之后的迟到帧必须丢弃。
     * capture() 开头复位。
     */
    @Volatile private var droppedRound = false

    /**
     * 【关键修复】拍摄代际号。
     * 包围/单张的超时兜底用 postDelayed 排程，而包围模式的超时窗口长达
     * 10~40s（星空 4×4s、慢门 8×2s）。若不给兜底任务绑定代际号，上一轮
     * 排下的兜底会醒来把**新一轮**的状态清掉（droppedRound / capturing /
     * bracketBuf），表现为"连拍第二张只合成出 2~3 帧"或"两组帧交错"。
     * focusCancelToken 已经是这个套路，这里补齐到快门链路上。
     */
    @Volatile private var captureRound = 0L

    /** 排程兜底任务时捕获的代际号 */
    @Volatile private var bracketRound = -1L

    /**
     * 【新增】单张拍照的兜底：若 JPEG 与 onCaptureCompleted 都没来（HAL 丢帧、
     * 会话被抢占等），超时后强制复位 capturing，避免快门永久失效。
     * 已在 [dispatchJpeg] 里复位过则此方法为无操作。
     */
    private fun clearCaptureGuardIfIdle() {
        if (bracketExpect > 0) return
        // 【关键修复】代际号校验：单张兜底排在 t0+8s，第 N+1 拍在 t0+1s 开始后，
        // 这个过期的兜底会把第 N+1 拍的 capturing 清掉 → 用户在成像期间就能再按
        // → 两组 STILL_CAPTURE 交错 stopRepeating/capture，帧序错乱。
        if (captureRound != bracketRound) { AppLogger.d("FullRes", "跳过过期的单张兜底"); return }
        if (capturing) {
            AppLogger.w("FullRes", "单张拍照超时兜底，复位快门状态")
            capturing = false
        }
    }

    fun capture() {
        val camera = device
        val s = session
        val r = reader
        if (camera == null || s == null || r == null) {
            onError("尚未就绪，无法拍照"); return
        }
        val now = System.currentTimeMillis()
        if (capturing || now - lastCaptureAt < captureMinIntervalMs) {
            AppLogger.d("FullRes", "快门防抖：忽略本次请求")
            return
        }
        capturing = true
        lastCaptureAt = now
        // 【关键修复】进入新一轮：递增代际号 + 复位上一轮的"已降级交付"标志。
        // 否则本轮所有帧都会被 dispatchJpeg 当残余帧丢弃 → 一张都存不出来；
        // 上一轮遗留的兜底任务也会误伤本轮状态。
        captureRound++
        droppedRound = false

        val plan = controls.bracket
        val frameCount = if (plan.isBracketed) plan.effectiveFrames else 1
        if (plan.isBracketed) {
            synchronized(bracketBuf) { bracketBuf.clear() }
            bracketExpect = if (onBracketReady != null) frameCount else 0
        } else {
            bracketExpect = 0
        }

        try {
            runCatching { s.stopRepeating() }
            for (i in 0 until frameCount) {
                val req = camera
                    .createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    .apply {
                        addTarget(r.surface)
                        if (useMaximumResolution &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ) {
                            set(CaptureRequest.SENSOR_PIXEL_MODE,
                                CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                        }
                        set(CaptureRequest.JPEG_QUALITY, 98.toByte())
                        // 逐帧应用不同的 ISO/快门/EV
                        applyControlsTo(this, i, plan)
                    }
                val isLast = (i == frameCount - 1)
                s.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        lastCaptureResult = result
                        if (isLast) {
                            // 包围模式下由 dispatchJpeg 在收齐帧后复位，这里只恢复预览
                            if (!plan.isBracketed) {
                                handler?.postDelayed({ startPreviewLoop(session) }, 200L)
                                capturing = false
                            } else {
                                handler?.postDelayed({ startPreviewLoop(session) }, 200L)
                            }
                        }
                    }
                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        if (isLast) {
                            capturing = false
                            // 【关键修复】原来只在 onCaptureCompleted 里恢复预览。
                            // capture() 开头已经 stopRepeating()，而失败路径不恢复
                            // → setRepeatingRequest 永远不再提交 → **永久黑屏**，
                            // 快门还在转圈但什么都不出来，只能杀进程。
                            // 失败也必须把预览拉回来。
                            handler?.postDelayed({ startPreviewLoop(session) }, 200L)
                            scheduleRetry("全像素拍照失败: ${failure.reason}")
                        }
                    }
                }, handler)
            }

            // 【新增】包围曝光超时兜底：保证 UI 不会卡在"处理中"
            // 修复：超时不能按 captureMinIntervalMs 线性估。星空 4 帧 × 最慢 4s 曝光、
            // 时光慢门 8 帧 × 2s 曝光都会远超 900ms×帧数，会误判超时并提前降级。
            // 改为「逐帧曝光时间 + 单帧落盘余量」求和，再留 3s 余量。
            if (plan.isBracketed && onBracketReady != null) {
                var exposureTotal = 0L
                for (i in 0 until frameCount) {
                    val shut = plan.shutterAt(i) ?: 0L
                    exposureTotal += maxOf(shut, 33_333_333L) / 1_000_000L
                }
                val perFrameOverhead = if (useMaximumResolution) 1200L else 500L
                val timeoutMs = exposureTotal + perFrameOverhead * frameCount + 3000L
                // 【关键修复】绑定本轮代际号，防止上一轮遗留的兜底误伤新一轮
                val round = captureRound
                bracketRound = round
                handler?.postDelayed({
                    if (captureRound != round) { AppLogger.d("FullRes", "丢弃过期的包围兜底任务"); return@postDelayed }
                    flushBracketIfTimeout()
                }, timeoutMs)
            } else {
                // 【新增】单张拍照同样加超时兜底：JPEG 落盘 + onCaptureCompleted
                // 若都没回来（断流/被抢占），快门会永久卡住。
                val timeoutMs = if (useMaximumResolution) 8000L else 5000L
                val round = captureRound
                bracketRound = round
                handler?.postDelayed({
                    if (captureRound != round) { AppLogger.d("FullRes", "丢弃过期的单张兜底任务"); return@postDelayed }
                    clearCaptureGuardIfIdle()
                }, timeoutMs)
            }

            // RAW 通道：独立提交，单独保存 result
            if (rawEnabled && rawReader != null) {
                val rawReq = camera
                    .createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    .apply {
                        addTarget(rawReader!!.surface)
                        if (useMaximumResolution &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ) {
                            set(CaptureRequest.SENSOR_PIXEL_MODE,
                                CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
                        }
                    }
                s.capture(rawReq.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) { rawCaptureResult = result }
                }, handler)
            }
        } catch (e: Exception) {
            capturing = false
            bracketExpect = 0
            scheduleRetry("拍照异常: ${e.message}")
        }
    }

    fun stop() {
        // 【关键修复】先置失效标志：removeCallbacksAndMessages 清不掉已投递/已到期
        // 的回调（quitSafely 会执行它们），迟到的 onOpened 会把一个永不被 close 的
        // CameraDevice 写回来 → 相机被永久占用 → 新一轮报"Camera in use" + 永久黑屏。
        stopped = true
        // 修复：stop() 后 callback 仍可能在线程池 handler 上执行并回写状态，
        // 这里先作废所有悬挂任务、复位快门与包围状态，避免重开相机时
        // 因 capturing=true 而"快门点了没反应"。
        handler?.removeCallbacksAndMessages(null)
        // 【关键修复】会话回调 Executor 必须 shutdown。原来它在 createSession 里
        // 内联 newSingleThreadExecutor() 后引用被丢弃，stop() 无从关闭 →
        // 每次重开相机（切模式/开关 RAW/前后台）都漏一个非 daemon 线程，
        // 切几十次后线程数暴涨 → ANR / Thread creation failed。
        synchronized(execLock) { sessionExecutor?.shutdown() }
        focusCancelToken++
        capturing = false
        bracketExpect = 0
        droppedRound = false
        synchronized(bracketBuf) { bracketBuf.clear() }
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        runCatching { analysisReader?.close() }
        runCatching { rawReader?.close() }
        session = null; device = null
        reader = null; analysisReader = null; rawReader = null
    }

    private companion object {
        /** 包围曝光最大帧数（HDR 是 7 帧，留到 9 个槽位保险） */
        const val MAX_BRACKET_FRAMES = 9

        /**
         * Camera2 的 CONTROL_AE_EXPOSURE_COMPENSATION 每个计数单位 = 1/6 EV。
         * 即 ±12 个计数 = ±2EV。UI 与 BracketPlan 里的 EV 都是"真实 EV 值"，
         * 下发前必须乘这个系数。
         */
        const val EV_STEP_PER_UNIT = 6f
    }
}
