package com.example.multicam

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 多摄同步录像：每颗镜头一个 MediaRecorder（SURFACE 源），同步开始/停止。
 * 预览 Surface 挂在**主摄**上（与拍照路径保持一致）。
 */
@RequiresApi(Build.VERSION_CODES.R)
class MultiVideoSession(
    private val context: Context,
    private val cameraIds: List<String>,
    private val size: Size,
    private val previewSurface: Surface?,
    private val outputFile: File,
    private val onError: (String) -> Unit,
    private val onReady: () -> Unit,
    private val onStopped: (File) -> Unit
) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /**
     * 【关键修复】原实现是三个裸 HashMap，而 openCamera 是异步的：
     * stopRecording() 会 clear() 它们，随后迟到的 onOpened 仍会把相机写回来，
     * 却再也没人 close() → 相机被本进程永久占用 → 切回拍照时
     * openCamera 拿到 CAMERA_IN_USE → 拍照模式也黑屏。必须加锁 + 失效标志。
     */
    private val lock = Any()
    private val devices = mutableMapOf<String, CameraDevice>()
    private val sessions = mutableMapOf<String, CameraCaptureSession>()
    private val recorders = mutableMapOf<String, MediaRecorder>()
    private val sessionExecutors = mutableListOf<ExecutorService>()

    /**
     * 预览/控制路挂在哪颗镜头上。
     *
     * 【关键修复】原来写的是 `cameraIds.lastOrNull()`，并注释说
     * "cameraIds[0] 按焦距升序恒为超广角，所以取最后一个"。
     * 但 `CameraRoleResolver.orderSubset()` 是 **按等效焦距升序**排的
     * （超广 → 主摄 → 长焦），所以：
     *   - 三摄 (uw, main, tele)：last = **长焦** ❌ 预览/曝光/变焦全挂在长焦上
     *   - 双摄 (uw, main)：last = 主摄 ✓（碰巧对）
     * 也就是三摄录像时用户对着长焦取景，却以为在用主摄，
     * 画面亮度/视角与拍照模式完全对不上，切换模式瞬间"视角跳变"。
     *
     * 现在按 CameraRole 精确挑选 MAIN；没有 MAIN 时退回等效焦距最接近
     * 26mm（≈全画幅 1×）的那颗，都拿不到才退回首/末位。
     */
    private val primaryId: String by lazy {
        runCatching {
            val infos = CameraRoleResolver.resolve(manager, cameraIds)
            val main = infos.firstOrNull { it.role == CameraRole.MAIN }?.id
            main
                ?: infos.minByOrNull {
                    kotlin.math.abs((it.equivalentFocal.takeIf { f -> f > 0f } ?: 26f) - 26f)
                }?.id
                ?: cameraIds.lastOrNull()
                ?: cameraIds.firstOrNull()
                ?: ""
        }.getOrElse { cameraIds.lastOrNull() ?: cameraIds.firstOrNull() ?: "" }
    }

    /**
     * 【关键修复】已停止标志。
     * removeCallbacksAndMessages / clear() 都拦不住已投递的 onOpened。
     */
    @Volatile private var stopped = false

    /** 是否真的开始过录制（决定要不要回调 onStopped） */
    @Volatile private var everRecorded = false

    private var handler: Handler? = null

    fun start(handler: Handler) {
        this.handler = handler
        stopped = false
        everRecorded = false
        cameraIds.forEach { id ->
            try {
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        // 迟到的回调：立刻放掉相机，不让它占住 HAL
                        if (stopped) { runCatching { camera.close() }; return }
                        val allOpened = synchronized(lock) {
                            devices[id] = camera
                            devices.size == cameraIds.size
                        }
                        if (allOpened) createSessions()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        runCatching { camera.close() }
                        // 身份校验：上一代的断开回调不能抹掉新一代的设备
                        val stale = synchronized(lock) { devices[id] !== camera }
                        if (stale || stopped) return
                        synchronized(lock) { devices.remove(id); sessions.remove(id) }
                        onError("$id 断开")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        runCatching { camera.close() }
                        val stale = synchronized(lock) { devices[id] !== camera }
                        if (stale || stopped) return
                        synchronized(lock) { devices.remove(id); sessions.remove(id) }
                        onError("$id 错误 $error")
                    }
                }, handler)
            } catch (e: Throwable) {
                if (!stopped) onError("打开 $id 失败: ${e.message}")
            }
        }
    }

    /**
     * 【关键修复】原来整个方法没有任何 try/catch，而它由 onOpened 调用、
     * onOpened 跑在主线程 handler 上 —— `prepare()` 抛 IOException /
     * IllegalArgumentException、`createCaptureSession` 抛 CameraAccessException
     * 时会直接从 StateCallback 逃逸出主线程 = **进程崩溃**。
     * 对比 MultiConcurrentSession.createOneSession 是包了 try 的，这里明显是遗漏。
     */
    private fun createSessions() {
        cameraIds.forEachIndexed { idx, id ->
            val device = synchronized(lock) { devices[id] } ?: return@forEachIndexed
            var recorder: MediaRecorder? = null
            try {
                recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION") MediaRecorder()
                }
                val outFile = File(
                    outputFile.parent,
                    "${outputFile.nameWithoutExtension}_$idx.mp4"
                )
                // 目录可能不存在（外部存储未挂载时 videoDir 会退化）
                outFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                recorder.apply {
                    setVideoSource(MediaRecorder.VideoSource.SURFACE)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                    setVideoSize(size.width, size.height)
                    setVideoFrameRate(30)
                    setVideoEncodingBitRate(8_000_000)
                    setOutputFile(outFile.absolutePath)
                    prepare()
                }
                if (stopped) { runCatching { recorder.release() }; return@forEachIndexed }
                synchronized(lock) { recorders[id] = recorder }

                val outputs = mutableListOf(OutputConfiguration(recorder.surface))
                if (id == primaryId && previewSurface != null) {
                    outputs.add(OutputConfiguration(previewSurface))
                }
                // 【关键修复】Executor 必须持有引用，否则 session 关闭后无从 shutdown，
                // 每进一次录像模式漏一个非 daemon 线程。
                val ex = Executors.newSingleThreadExecutor()
                synchronized(lock) { sessionExecutors.add(ex) }
                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    ex,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (stopped) { runCatching { s.close() }; return }
                            val allReady = synchronized(lock) {
                                sessions[id] = s
                                sessions.size == cameraIds.size
                            }
                            if (allReady) onReady()
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            if (stopped) return
                            onError("会话失败 $id")
                        }
                    }
                )
                device.createCaptureSession(config)
            } catch (e: Throwable) {
                // 异常路径必须释放已 prepare 的 recorder，否则 native 资源泄漏
                runCatching { recorder?.release() }
                synchronized(lock) { recorders.remove(id) }
                AppLogger.e("MultiVideoSession", "创建会话失败 $id", e)
                if (!stopped) onError("配置 $id 失败: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun startRecording() {
        if (stopped) return
        cameraIds.forEach { id ->
            val recorder = synchronized(lock) { recorders[id] } ?: return@forEach
            val device = synchronized(lock) { devices[id] } ?: return@forEach
            val session = synchronized(lock) { sessions[id] } ?: return@forEach
            try {
                val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(recorder.surface)
                    if (id == primaryId && previewSurface != null) {
                        addTarget(previewSurface)
                    }
                }
                session.setRepeatingRequest(req.build(), null, handler)
                recorder.start()
                everRecorded = true
            } catch (e: Throwable) {
                AppLogger.e("MultiVideoSession", "录制失败 $id", e)
                if (!stopped) onError("录制失败 $id: ${e.message}")
            }
        }
    }

    /**
     * 停止并释放。
     *
     * 【关键修复 1】原来无条件 `onStopped(outputFile)`：
     *   - 从录像模式切走（哪怕从没开始录）→ 弹假的「录像已保存」
     *   - 报的文件名 outputFile **从未被创建**（真正落盘的是 `_<idx>.mp4` 分片）
     * 现在只在真正录过、且确实产出了分片文件时才回调，并报真实文件名。
     *
     * 【关键修复 2】原来没有 stopped 标志，迟到的 onOpened/onConfigured
     * 会把相机与 recorder 重新塞回已 clear 的 map → 泄漏且无人能关。
     */
    fun stopRecording() {
        val wasRecording = everRecorded
        stopped = true
        everRecorded = false

        val recSnap: List<MediaRecorder>
        val sessSnap: List<CameraCaptureSession>
        val devSnap: List<CameraDevice>
        val execSnap: List<ExecutorService>
        synchronized(lock) {
            recSnap = recorders.values.toList(); recorders.clear()
            sessSnap = sessions.values.toList(); sessions.clear()
            devSnap = devices.values.toList(); devices.clear()
            execSnap = sessionExecutors.toList(); sessionExecutors.clear()
        }

        // 停止顺序：先停流 → 再 stop recorder（否则 stop 会抛）→ 最后释放
        sessSnap.forEach { runCatching { it.stopRepeating() } }
        if (wasRecording) {
            // 【修复】MediaRecorder.stop() 要 finalize moov + fsync，长视频是秒级阻塞。
            // 调用点全在主线程（切模式 / onPause / 快门），会造成 ANR。
            // 放到后台线程执行，onStopped 也在那里回调。
            Thread({
                recSnap.forEach { r -> runCatching { r.stop() } }
                releaseAll(recSnap, sessSnap, devSnap, execSnap)
                if (wasRecording) {
                    val produced = outputFile.parentFile
                        ?.listFiles { _, n -> n.startsWith(outputFile.nameWithoutExtension) }
                        ?.sortedBy { it.name }
                    if (!produced.isNullOrEmpty()) {
                        onStopped(produced.first())
                    } else {
                        onError("录像未生成文件")
                    }
                }
            }, "VideoStop").apply { isDaemon = true }.start()
        } else {
            releaseAll(recSnap, sessSnap, devSnap, execSnap)
        }
    }

    private fun releaseAll(
        recs: List<MediaRecorder>,
        sess: List<CameraCaptureSession>,
        devs: List<CameraDevice>,
        execs: List<ExecutorService>
    ) {
        execs.forEach { runCatching { it.shutdown() } }
        sess.forEach { runCatching { it.close() } }
        devs.forEach { runCatching { it.close() } }
        recs.forEach { runCatching { it.release() } }
    }
}