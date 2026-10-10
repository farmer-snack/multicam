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
import java.util.concurrent.Executors

/**
 * 多摄同步录像：每颗镜头一个 MediaRecorder（SURFACE 源），同步开始/停止。
 * 第一个摄像头同时输出预览 Surface。
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
    private val devices = mutableMapOf<String, CameraDevice>()
    private val sessions = mutableMapOf<String, CameraCaptureSession>()
    private val recorders = mutableMapOf<String, MediaRecorder>()
    private var handler: Handler? = null

    fun start(handler: Handler) {
        this.handler = handler
        cameraIds.forEach { id ->
            try {
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        devices[id] = camera
                        if (devices.size == cameraIds.size) createSessions()
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close(); devices.remove(id); onError("$id 断开")
                    }
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close(); devices.remove(id); onError("$id 错误 $error")
                    }
                }, handler)
            } catch (e: Exception) {
                onError("打开 $id 失败: ${e.message}")
            }
        }
    }

    private fun createSessions() {
        cameraIds.forEachIndexed { idx, id ->
            val device = devices[id] ?: return@forEachIndexed
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            val outFile = File(
                outputFile.parent,
                "${outputFile.nameWithoutExtension}_$idx.mp4"
            )
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
            recorders[id] = recorder

            val outputs = mutableListOf(OutputConfiguration(recorder.surface))
            if (idx == 0 && previewSurface != null) {
                outputs.add(OutputConfiguration(previewSurface))
            }
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                Executors.newSingleThreadExecutor(),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        sessions[id] = s
                        if (sessions.size == cameraIds.size) onReady()
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        onError("会话失败 $id")
                    }
                }
            )
            device.createCaptureSession(config)
        }
    }

    fun startRecording() {
        cameraIds.forEach { id ->
            val recorder = recorders[id] ?: return@forEach
            val device = devices[id] ?: return@forEach
            val session = sessions[id] ?: return@forEach
            try {
                val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(recorder.surface)
                    if (id == cameraIds.firstOrNull() && previewSurface != null) {
                        addTarget(previewSurface)
                    }
                }
                session.setRepeatingRequest(req.build(), null, handler)
                recorder.start()
            } catch (e: Exception) {
                onError("录制失败 $id: ${e.message}")
            }
        }
    }

    fun stopRecording() {
        cameraIds.forEach { id -> runCatching { recorders[id]?.stop() } }
        sessions.values.forEach { runCatching { it.stopRepeating() } }
        sessions.values.forEach { runCatching { it.close() } }
        devices.values.forEach { runCatching { it.close() } }
        recorders.values.forEach { runCatching { it.release() } }
        sessions.clear(); devices.clear(); recorders.clear()
        onStopped(outputFile)
    }
}
