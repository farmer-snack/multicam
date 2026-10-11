package com.example.multicam

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface

class CameraController(
    private val context: Context,
    private val previewSurface: Surface?,
    private val framesPerCamera: Int,
    private val enableAIDenoise: Boolean,
    var colorParams: ColorGradeParams = ColorGradeParams(),
    private val compositionEnabled: Boolean = false,
    private val compositionOverlay: ((CompositionAnalyzer.Suggestion?) -> Unit)? = null,
    private val enableRaw: Boolean = false,
    private val onRawReady: ((ByteArray) -> Unit)? = null,
    private val autoColorEnabled: () -> Boolean = { false },
    private val onStatus: (String) -> Unit,
    private val onProgress: (String) -> Unit,
    private val onPreviewSize: (Size) -> Unit,
    private val onResult: (tag: String, jpeg: ByteArray) -> Unit,
    private val orientationTracker: OrientationTracker? = null,
    private val onTargetReached: ((SceneAdvisor.Advice) -> Unit)? = null,
    private val onError: (String) -> Unit,
    /** 【新增】包围曝光帧回调：由 MainActivity 分派到夜景/星空/慢门/HDR 处理器 */
    private val onBracketFrames: ((List<ByteArray>) -> Unit)? = null
) {
    private val detector = CameraCapabilityDetector(context)

    /**
     * 【关键修复】以下字段全部改为 @Volatile。
     * 它们被三个线程并发读写：
     *   主线程（init / setAutoZoom / colorParams 赋值）
     *   CameraThread（currentZoomProvider、targetReached 里读 autoZoomEnabled）
     *   BackgroundProcessor 单线程池（applyColorGrade 读 colorParams）
     * 普通字段没有跨线程可见性保证，processAsync 的裸 Thread 连 Handler 队列的
     * happens-before 边都没有 → 稳定读到**旧对象**。
     * 现象：拖动色环后立刻拍照，成片用了改动之前的旧色调；自动变焦偶尔不响应。
     */
    @Volatile private var caps: CameraCapabilities? = null
    @Volatile private var mode: CaptureMode = CaptureMode.Unsupported

    var preferredRole: CameraRole? = null
        set(value) { field = value }

    private var lastPreferQuality = true

    @Volatile private var fullResSession: FullResCaptureSession? = null
    @Volatile private var concurrentSession: MultiConcurrentSession? = null

    // 修改 23：自动变焦
    @Volatile var autoZoomEnabled = false
        private set

    /**
     * 【关键修复】原来这里声明了 pendingRestart 并在 setter 里置 true，
     * 但全项目**没有任何一处读取它** —— 纯死代码，而 applyPreferredRole 是
     * 直接调 init() 的。任何新增的 `controller.preferredRole = X` 调用
     * 都会静默无效。删除标志位，让 setter 语义直白。
     */

    // 修改 27：到位后下一帧自动调色标记
    @Volatile var triggerAutoColorOnNextShot = false
        private set

    private val thread = HandlerThread("CameraThread").apply { start() }
    private val handler = Handler(thread.looper)

    fun init(preferQuality: Boolean) {
        lastPreferQuality = preferQuality
        releaseInternal()
        val c = detector.detect()
        caps = c
        onPreviewSize(c.previewSize)
        mode = CaptureStrategy.decide(c, preferQuality, preferredRole)
        onStatus("${modeName(mode)} · ${c.reason}")

        // 修改 27：到位触发（变焦 + 标记自动调色）
        val getYaw: () -> Float = { orientationTracker?.getYaw() ?: 0f }
        val targetReached: ((SceneAdvisor.Advice) -> Unit)? =
            if (onTargetReached != null) { advice ->
                if (advice.targetZoom > 0f && autoZoomEnabled) setZoom(advice.targetZoom)
                if (autoColorEnabled()) triggerAutoColorOnNextShot = true
                onTargetReached.invoke(advice)
            } else null

        when (val m = mode) {
            is CaptureMode.FullResolution -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    fullResSession = FullResCaptureSession(
                        context = context,
                        cameraId = m.cameraId,
                        fullSize = m.size,
                        previewSurface = previewSurface,
                        useMaximumResolution = true,
                        compositionEnabled = compositionEnabled,
                        compositionOverlay = compositionOverlay,
                        orientationTracker = orientationTracker,
                        getYaw = getYaw,
                        onTargetReached = targetReached,
                        enableRaw = enableRaw,
                        onRawReady = onRawReady,
                        currentZoomProvider = { fullResSession?.getZoom() ?: 1f },
                        onZoomRequest = { z -> setZoom(z) },
                        onJpegReady = { bytes -> processSingleAsync(bytes) },
                        onBracketReady = onBracketFrames,
                        onError = { onError(it) },
                        onSessionReady = {
                            onStatus("全像素 ${m.size.width}×${m.size.height}")
                        }
                    ).also { it.start(handler) }
                } else {
                    fullResSession = FullResCaptureSession(
                        context = context,
                        cameraId = m.cameraId,
                        fullSize = m.size,
                        previewSurface = previewSurface,
                        useMaximumResolution = false,
                        compositionEnabled = compositionEnabled,
                        compositionOverlay = compositionOverlay,
                        orientationTracker = orientationTracker,
                        getYaw = getYaw,
                        onTargetReached = targetReached,
                        enableRaw = enableRaw,
                        onRawReady = onRawReady,
                        currentZoomProvider = { fullResSession?.getZoom() ?: 1f },
                        onZoomRequest = { z -> setZoom(z) },
                        onJpegReady = { bytes -> processSingleAsync(bytes) },
                        onBracketReady = onBracketFrames,
                        onError = { onError(it) },
                        onSessionReady = {
                            onStatus("单摄 ${m.size.width}×${m.size.height}")
                        }
                    ).also { it.start(handler) }
                }
            }

            is CaptureMode.MultiConcurrent -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val roleMap = caps?.cameraInfos?.associate { it.id to it.role }
                        ?: emptyMap()
                    concurrentSession = MultiConcurrentSession(
                        context = context,
                        cameraIds = m.cameraIds,
                        size = m.size,
                        previewSurface = previewSurface,
                        framesPerCamera = framesPerCamera,
                        compositionEnabled = compositionEnabled,
                        compositionOverlay = compositionOverlay,
                        orientationTracker = orientationTracker,
                        getYaw = getYaw,
                        onTargetReached = targetReached,
                        currentZoomProvider = { concurrentSession?.getZoom() ?: 1f },
                        onZoomRequest = { z -> setZoom(z) },
                        onAllFrames = { frames -> processAsync(frames, m.cameraIds, roleMap) },
                        onError = { onError(it) },
                        onSessionReady = {
                            val label = if (m.isTriple) "三摄并发" else "双摄并发"
                            onStatus("$label · 连拍 $framesPerCamera 帧")
                        }
                    ).also { it.start(handler) }
                } else onError("Android < 11，不支持多摄并发")
            }

            is CaptureMode.SingleDefault -> {
                fullResSession = FullResCaptureSession(
                    context = context,
                    cameraId = m.cameraId,
                    fullSize = m.size,
                    previewSurface = previewSurface,
                    useMaximumResolution = false,
                    compositionEnabled = compositionEnabled,
                    compositionOverlay = compositionOverlay,
                    enableRaw = enableRaw,
                    onRawReady = onRawReady,
                    currentZoomProvider = { fullResSession?.getZoom() ?: 1f },
                    onZoomRequest = { z -> setZoom(z) },
                    onJpegReady = { bytes -> processSingleAsync(bytes) },
                    onBracketReady = onBracketFrames,
                    onError = { onError(it) },
                    onSessionReady = {
                        onStatus("兜底单摄 ${m.size.width}×${m.size.height}")
                    }
                ).also { it.start(handler) }
            }
            CaptureMode.Unsupported -> onError("无可用模式")
        }

        // 恢复自动变焦状态
        if (autoZoomEnabled) setAutoZoom(true)
    }

    fun applyPreferredRole(role: CameraRole?) {
        preferredRole = role
        init(lastPreferQuality)
    }

    // ---------- 修改 23：变焦 ----------

    fun setZoom(z: Float) {
        when (mode) {
            is CaptureMode.FullResolution, is CaptureMode.SingleDefault ->
                fullResSession?.setZoom(z)
            is CaptureMode.MultiConcurrent -> concurrentSession?.setZoom(z)
            else -> {}
        }
    }

    fun setAutoZoom(enabled: Boolean) {
        autoZoomEnabled = enabled
        when (mode) {
            is CaptureMode.FullResolution, is CaptureMode.SingleDefault ->
                fullResSession?.setAutoZoom(enabled)
            is CaptureMode.MultiConcurrent -> concurrentSession?.setAutoZoom(enabled)
            else -> {}
        }
    }

    // ---------- 手动控制：闪光 / EV / ISO / 快门 / 白平衡 ----------

    /**
     * 最近一次下发的控制参数（含包围计划），供 [multiCaptureTimeoutMs] 估算超时。
     * 与各 session 内的 `controls` 同步更新，只读使用。
     */
    @Volatile private var lastControls: CameraControls? = null

    fun applyControls(c: CameraControls) {
        lastControls = c
        when (mode) {
            is CaptureMode.FullResolution, is CaptureMode.SingleDefault ->
                fullResSession?.applyControls(c)
            is CaptureMode.MultiConcurrent -> concurrentSession?.applyControls(c)
            else -> {}
        }
    }

    /** 当前主摄是否支持闪光灯（供 UI 灰显判断） */
    fun hasFlash(): Boolean = caps?.let { c ->
        runCatching {
            val mgr = context.getSystemService(android.content.Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager
            val id = c.mainCameraId ?: return@let false
            mgr.getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }.getOrDefault(false)
    } ?: false

    /** 依据镜头等效焦距推算的变焦档位（相对主摄） */
    fun availableFocalStops(): List<Float> {
        val c = caps ?: return listOf(1f)
        val mainEq = c.cameraInfos.firstOrNull { it.id == c.mainCameraId }?.equivalentFocal ?: 0f
        if (mainEq <= 0f) return listOf(1f)
        val maxZ = maxZoom()
        return c.cameraInfos
            .mapNotNull { info ->
                if (info.equivalentFocal <= 0f) null
                else (info.equivalentFocal / mainEq).takeIf { it > 1.05f && it <= maxZ + 0.01f }
            }
            .distinctBy { (it * 10).toInt() }
            .sorted()
    }

    /** 主摄可用的变焦上限 */
    fun maxZoom(): Float = caps?.let { c ->
        runCatching {
            val mgr = context.getSystemService(android.content.Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager
            val id = c.mainCameraId ?: return@let 1f
            mgr.getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                ?: 1f
        }.getOrDefault(1f)
    } ?: 1f

    // 修改 27：重置构图聚类 + 陀螺仪起点
    fun resetCompositionTracking() {
        fullResSession?.resetCompositionTracking()
        concurrentSession?.resetCompositionTracking()
    }

    // ---------- 修改 12：对焦/曝光联动 ----------

    fun triggerFocus(rect: android.graphics.Rect, lockAe: Boolean) {
        when (mode) {
            is CaptureMode.FullResolution, is CaptureMode.SingleDefault ->
                fullResSession?.triggerFocus(rect, lockAe)
            is CaptureMode.MultiConcurrent -> concurrentSession?.triggerFocus(rect, lockAe)
            else -> {}
        }
    }

    fun lockAfAe(rect: android.graphics.Rect) {
        when (mode) {
            is CaptureMode.FullResolution, is CaptureMode.SingleDefault ->
                fullResSession?.lockAfAe(rect)
            is CaptureMode.MultiConcurrent -> concurrentSession?.lockAfAe(rect)
            else -> {}
        }
    }

    fun unlockAfAe() {
        when (mode) {
            is CaptureMode.FullResolution, is CaptureMode.SingleDefault ->
                fullResSession?.unlockAfAe()
            is CaptureMode.MultiConcurrent -> concurrentSession?.unlockAfAe()
            else -> {}
        }
    }

    fun setOrientation(orientation: Int) {
        when (mode) {
            is CaptureMode.FullResolution, is CaptureMode.SingleDefault ->
                fullResSession?.setOrientation(orientation)
            else -> {}
        }
    }

    /** 并发摄像头 ID（供多摄录像使用） */
    fun concurrentCameraIds(): List<String> = caps?.concurrentIds ?: emptyList()

    // ---------- 【新增】模式能力查询：供 MainActivity 判断模式可用性 ----------

    /** 最长可用曝光时间（ns），0 表示未知 */
    fun maxExposureNs(): Long = caps?.maxExposureNs ?: 0L

    /** ISO 可选范围 */
    fun isoRange(): IntRange = caps?.isoRange ?: (0..0)

    /** 是否支持长曝光（星空/时光慢门的前提） */
    fun longExposureSupported(): Boolean = caps?.longExposureSupported ?: false

    /** 是否支持深度输出（人像虚化若有深度镜头则更优；本实现为软件虚化，恒可用） */
    fun depthSupported(): Boolean = caps?.depthSupported ?: false

    // ---------- 修改 19：AI 处理后台化 ----------

    /** 单摄（全像素/兜底）路径：原图立存，AI/调色后台出 */
    private fun processSingleAsync(jpegBytes: ByteArray) {
        onResult("raw_original", jpegBytes)
        if (!enableAIDenoise && colorParams.isDefault && !autoColorEnabled()) return

        BackgroundProcessor.submit("单摄 AI") { onProgress ->
            var out = jpegBytes
            if (enableAIDenoise && AIDenoise.isReady()) {
                onProgress(20, "AI 去噪")
                out = aiDenoise(out)
            }
            onProgress(90, "调色")
            out = applyColorGrade(out)
            onProgress(95, "保存")
            onResult("ai_processed", out)
        }
    }

    /**
     * 多摄合成的在途并发上限。
     *
     * 【关键修复】原来每次拍照都 `Thread { }` 新建裸线程，无池化、无背压、
     * 不随 release() 取消。连拍 5~6 张就是 5~6 个线程同时做 OpenCV 配准与合成
     *（每路都要解 Mat、跑 ORB、warpPerspective），CPU 打满 → 界面卡住甚至 ANR；
     * 而且这些线程会一直持有 MainActivity 的 lambda 引用，
     * onDestroy 后 Activity 数秒内无法回收。
     *
     * 现在用信号量限制并发为 1（串行），并复用后台线程池；
     * 同时用 released 标志让生命周期结束后尽快退出。
     */
    private val composeSemaphore = java.util.concurrent.Semaphore(1)
    @Volatile private var released = false

    /** 多摄路径：合成+调色先出，AI 后台出 */
    private fun processAsync(
        frames: Map<String, List<ByteArray>>,
        orderedIds: List<String>,
        roleMap: Map<String, CameraRole>
    ) {
        BackgroundProcessor.submit("多摄合成") { tick ->
            // 生命周期结束后不再做重活
            if (released) return@submit
            // 串行执行：保证同时只有一个多摄合成在跑
            composeSemaphore.acquire()
            try {
                if (released) return@submit
                tick(0, "时序降噪中…")
                val denoised = mutableMapOf<String, ByteArray>()
                for (id in orderedIds) {
                    if (released) return@submit
                    val list = frames[id] ?: continue
                    val res = TemporalDenoiser.denoise(list)
                    if (res != null) {
                        val bmp = MatUtils.bgrToBitmap(res.merged)
                        val bytes = MatUtils.bitmapToJpeg(bmp); bmp.recycle()
                        res.merged.release(); res.mask.release()
                        denoised[id] = bytes
                    } else {
                        denoised[id] = list.firstOrNull() ?: continue
                    }
                }

                tick(40, "像素级配准…")
                val layers = orderedIds.mapNotNull { id ->
                    denoised[id]?.let {
                        ComposeLayer(id, roleMap[id] ?: CameraRole.UNKNOWN, it)
                    }
                }
                if (layers.isEmpty()) { onError("合成为空"); return@submit }

                tick(70, "融合合成…")
                var composed = ImageCompositor.compose(layers)
                composed = applyColorGrade(composed)
                onResult("composed", composed)

                if (enableAIDenoise) {
                    BackgroundProcessor.submit("多摄 AI") { onProgress2 ->
                        var out = composed
                        if (enableAIDenoise && AIDenoise.isReady()) {
                            onProgress2(30, "AI 去噪")
                            out = aiDenoise(out)
                        }
                        onProgress2(95, "保存")
                        onResult("ai_processed", out)
                    }
                }
            } catch (e: Throwable) {
                // 【修复】原来只 catch(Exception)：OutOfMemoryError 继承 Error 不是
                // Exception，会穿透到裸 Thread 顶部直接杀进程（且没有日志）。
                AppLogger.e("CameraController", "处理失败: ${e.message}", e)
                onError("处理失败: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                composeSemaphore.release()
            }
        }
    }

    private fun aiDenoise(jpegBytes: ByteArray): ByteArray {
        return try {
            val bmp = MatUtils.jpegToBitmap(jpegBytes) ?: return jpegBytes
            val mat = MatUtils.bitmapToBgr(bmp)
            bmp.recycle()
            val denoised = AIDenoise.denoise(mat)
            mat.release()
            val outBmp = MatUtils.bgrToBitmap(denoised)
            denoised.release()
            val bytes = MatUtils.bitmapToJpeg(outBmp, 95)
            outBmp.recycle()
            bytes
        } catch (e: Exception) {
            AppLogger.e("CameraController", "AI 去噪失败: ${e.message}", e)
            jpegBytes
        }
    }

    /** 修改 24：自动调色（手动参数优先合并自动结果） */
    private fun applyColorGrade(jpeg: ByteArray): ByteArray {
        var params = colorParams

        if (autoColorEnabled()) {
            try {
                val bmp = MatUtils.jpegToBitmap(jpeg) ?: return jpeg
                val mat = MatUtils.bitmapToBgr(bmp); bmp.recycle()
                val auto = AutoColorAdvisor.analyze(mat)
                mat.release()
                params = mergeParams(params, auto.params)
                AppLogger.d("CameraController", "自动调色: ${auto.label}")
            } catch (e: Exception) {
                AppLogger.w("CameraController", "自动调色失败: ${e.message}")
            }
        }

        if (params.isDefault) return jpeg
        return try {
            val bmp = MatUtils.jpegToBitmap(jpeg) ?: return jpeg
            val mat = MatUtils.bitmapToBgr(bmp); bmp.recycle()
            val graded = ColorGrader.apply(mat, params); mat.release()
            val outBmp = MatUtils.bgrToBitmap(graded); graded.release()
            val bytes = MatUtils.bitmapToJpeg(outBmp, 95); outBmp.recycle()
            bytes
        } catch (e: Exception) {
            AppLogger.e("CameraController", "调色失败: ${e.message}", e); jpeg
        }
    }

    /** 手动参数与自动参数合并：手动优先 */
    private fun mergeParams(
        manual: ColorGradeParams, auto: ColorGradeParams
    ): ColorGradeParams {
        return ColorGradeParams(
            hueShiftX = if (manual.hueShiftX != 0f) manual.hueShiftX else auto.hueShiftX,
            toneShiftY = if (manual.toneShiftY != 0f) manual.toneShiftY else auto.toneShiftY,
            saturation = if (manual.saturation != 1f) manual.saturation else auto.saturation,
            contrast = if (manual.contrast != 1f) manual.contrast else auto.contrast,
            temperature = if (manual.temperature != 0f) manual.temperature else auto.temperature
        )
    }

    fun capture() {
        when (mode) {
            is CaptureMode.FullResolution -> fullResSession?.capture()
            is CaptureMode.MultiConcurrent -> {
                concurrentSession?.capture()
                // 修复：多摄路径原先没有超时兜底。当某颗镜头少回帧（HAL 在
                // AE OFF 下吞帧、并发时被抢占），frameCache 永远凑不齐，onAllFrames
                // 不触发 → UI 永久停在"处理中"，快门也不再响应。
                // 与单摄路径对称，这里安排一次兜底：把已收到的帧交出去降级合成。
                handler.postDelayed({
                    concurrentSession?.flushIfTimeout()
                }, multiCaptureTimeoutMs())
            }
            is CaptureMode.SingleDefault -> fullResSession?.capture()
            else -> onError("无可用模式")
        }
    }

    /**
     * 多摄连拍的兜底超时。
     *
     * 按当前包围计划的逐帧曝光时长累加，再加每帧落盘余量与 3s 常量余量，
     * 与 [FullResCaptureSession] 的估算口径保持一致；非包围时取保守的 6s。
     */
    private fun multiCaptureTimeoutMs(): Long {
        val plan = lastControls?.bracket
        if (plan == null || !plan.isBracketed) return 6000L
        val n = plan.effectiveFrames
        var exposureTotal = 0L
        for (i in 0 until n) {
            exposureTotal += maxOf(plan.shutterAt(i) ?: 0L, 33_333_333L) / 1_000_000L
        }
        return exposureTotal + 1000L * n + 3000L
    }

    /**
     * 彻底释放（Activity onPause / onDestroy / 切录像）。
     *
     * 【关键修复】置 released = true，让后台正在跑的
     * 时序降噪 / 多摄合成 / AI 推理尽快退出。
     * 这些任务会持有 MainActivity 的 lambda 引用并持续做 OpenCV 重活，
     * 不终止的话 Activity 销毁后数秒内无法回收，CPU 也被占满。
     */
    fun release() {
        released = true
        releaseInternal()
        thread.quitSafely()
    }

    private fun releaseInternal() {
        fullResSession?.stop(); fullResSession = null
        concurrentSession?.stop(); concurrentSession = null
    }

    private fun modeName(m: CaptureMode) = when (m) {
        is CaptureMode.FullResolution -> "全像素单摄"
        is CaptureMode.MultiConcurrent -> if (m.isTriple) "三摄并发" else "双摄并发"
        is CaptureMode.SingleDefault -> "兜底单摄"
        CaptureMode.Unsupported -> "不可用"
    }

    /**
     * 采集模式的**类型**标识，供 UI 判断实际用了哪种采集路径。
     *
     * 【新增】此前 UI（MainActivity.badgeText）只能拿到 `currentModeName()` 这个
     * 展示用中文字符串，再用字符串去匹配模式 —— 而展示文案和匹配用的 key
     * 并不一致（返回"三摄并发"/"兜底单摄"，UI 却匹配"多摄并发"/"单摄"），
     * 导致永远走 else 分支，badge 显示错误的"全像素"。
     * 改成传枚举，字符串只管显示，行为不再依赖文案。
     */
    fun currentModeKind(): CameraModeKind = when (mode) {
        is CaptureMode.FullResolution -> CameraModeKind.FULL_RESOLUTION
        is CaptureMode.MultiConcurrent -> CameraModeKind.MULTI_CONCURRENT
        is CaptureMode.SingleDefault -> CameraModeKind.SINGLE_DEFAULT
        CaptureMode.Unsupported -> CameraModeKind.UNSUPPORTED
    }

    /**
     * 当前实际采集模式名（供 UI 如实展示）。
     * 原来 UI 只根据用户意图（preferQuality）显示「全像素」，
     * 设备不支持时会静默降级却不提示 —— 所见非所得。
     */
    fun currentModeName(): String = modeName(mode)

    /** 用户勾选「高像素」但设备/镜头实际不支持全像素时，用于给出明确提示 */
    fun fullResRequestedButUnavailable(): Boolean =
        // 【修复】原来同样拿展示文案比较，改成枚举判断
        lastPreferQuality && currentModeKind() != CameraModeKind.FULL_RESOLUTION
}

/**
 * 实际采集路径的类型标识（供 UI 判断，而非展示）。
 *
 * 【新增】用来替代"用中文字符串当枚举 key"的反模式：
 * 展示文案（`currentModeName()`）会随用户可见性/文案调整而变化，
 * 行为判断必须依赖这个稳定的类型。
 */
enum class CameraModeKind {
    /** 传感器满分辨率单摄 */
    FULL_RESOLUTION,

    /** 多摄并发 */
    MULTI_CONCURRENT,

    /** 能力不足时的兜底单摄 */
    SINGLE_DEFAULT,

    /** 设备无可用后摄 */
    UNSUPPORTED
}
