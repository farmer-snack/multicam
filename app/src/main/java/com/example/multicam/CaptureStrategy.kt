package com.example.multicam

import android.util.Size

sealed class CaptureMode {
    data class FullResolution(val cameraId: String, val size: Size) : CaptureMode()
    data class MultiConcurrent(
        val cameraIds: List<String>,
        val size: Size,
        val isTriple: Boolean
    ) : CaptureMode()
    data class SingleDefault(val cameraId: String, val size: Size) : CaptureMode()
    object Unsupported : CaptureMode()
}

object CaptureStrategy {
    fun decide(
        caps: CameraCapabilities,
        preferQuality: Boolean,
        preferredRole: CameraRole? = null
    ): CaptureMode {
        val targetId = if (preferredRole != null) {
            caps.cameraInfos.firstOrNull { it.role == preferredRole }?.id
        } else caps.mainCameraId

        val targetFullRes = targetId?.let { caps.fullResByCameraId[it] }

        if (preferQuality && targetId != null && targetFullRes != null) {
            return CaptureMode.FullResolution(targetId, targetFullRes)
        }

        if (preferredRole != null && targetId != null) {
            return CaptureMode.SingleDefault(targetId, Size(1920, 1080))
        }

        if (caps.concurrentIds.size >= 3 && caps.concurrentMaxSize != null) {
            return CaptureMode.MultiConcurrent(
                caps.concurrentIds.take(3), caps.concurrentMaxSize, isTriple = true
            )
        }
        if (caps.concurrentIds.size >= 2 && caps.concurrentMaxSize != null) {
            return CaptureMode.MultiConcurrent(
                caps.concurrentIds.take(2), caps.concurrentMaxSize, isTriple = false
            )
        }
        return caps.mainCameraId?.let {
            CaptureMode.SingleDefault(it, Size(1920, 1080))
        } ?: CaptureMode.Unsupported
    }
}
