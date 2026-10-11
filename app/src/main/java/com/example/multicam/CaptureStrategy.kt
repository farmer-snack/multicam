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
        // 【关键修复】原来 requestedRole 对应镜头不存在时 targetId 直接为 null，
        // 然后静默流到下面的并发/单摄分支 —— 用户明明选了「长焦」却拿到超广角，
        // 且没有任何提示。现在找不到就明确降级为"指定镜头不存在"的说明。
        val requestedId = preferredRole?.let { role ->
            caps.cameraInfos.firstOrNull { it.role == role }?.id
        }
        val requestedMissing = preferredRole != null && requestedId == null

        val targetId = requestedId ?: caps.mainCameraId
        val targetFullRes = targetId?.let { caps.fullResByCameraId[it] }

        // 全像素优先：仅当 preferQuality 且目标镜头确实支持。
        // 【关键修复】原来只要 preferredRole != null 就短路到 SingleDefault(1920×1080)，
        // 排在并发分支之前 —— 用户手动选过镜头之后，多摄并发永远不会被启用。
        if (preferQuality && targetId != null && targetFullRes != null) {
            return CaptureMode.FullResolution(targetId, targetFullRes)
        }

        // 用户显式指定了镜头 → 只用那一颗（不并发），但用**该镜头自己的**
        // 能力上限，而不是硬编码 1080p。
        if (requestedId != null) {
            // 【修复】原来硬编码 SingleDefault(1920,1080)，与该镜头真实能力无关。
            // 现在用该镜头自己的全像素尺寸（若有），否则退回预览尺寸。
            // previewSize 是非空 Size，Elvis 末项不可达，去掉以免误导。
            val size = caps.fullResByCameraId[requestedId] ?: caps.previewSize
            return CaptureMode.SingleDefault(requestedId, size)
        }

        // 指定镜头不存在 → 明确走单摄主摄，不静默启用多摄
        if (requestedMissing) {
            AppLogger.w("CaptureStrategy", "请求的镜头角色 $preferredRole 不存在，回退主摄单摄")
            return caps.mainCameraId?.let {
                CaptureMode.SingleDefault(it, Size(1920, 1080))
            } ?: CaptureMode.Unsupported
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
