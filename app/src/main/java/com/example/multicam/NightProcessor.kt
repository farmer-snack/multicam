package com.example.multicam

/**
 * 夜景模式处理器。
 *
 * 策略：**锁定 AE 后按逐帧 ISO/快门序列连拍 6 帧**（400/800 ISO，120/240/360 ms 快门交错），
 * 全部对齐后做时序加权平均降噪。这样在暗光下能真正延长积分时间，
 * 比单纯 AE 自动曝光 + EV 抖动拿到更多光子。
 * 直接复用 [TemporalDenoiser] 的「ORB 对齐 + 掩膜加权平均」内核（该内核同时被多摄合成路径使用），
 * 因此不需要厂商的多帧堆栈 ISP。
 *
 * 与星空/慢门的区别：夜景的曝光量级最小（百毫秒级），宽容度要求也最低，
 * 是三者中最容易在普通手机上出效果的模式。
 */
object NightProcessor {

    private const val TAG = "NightProcessor"

    /**
     * 夜景合成：多帧对齐叠加降噪。
     *
     * @param frames 连拍得到的 JPEG 列表（至少 2 帧）
     * @param onProgress 进度回调 (0..100, 文案)
     * @return 合成后的 JPEG；全部失败时返回首帧原图
     */
    fun merge(frames: List<ByteArray>, onProgress: ((Int, String) -> Unit)? = null): ByteArray? {
        if (frames.isEmpty()) return null
        if (frames.size == 1) return frames[0]

        onProgress?.invoke(20, "夜景：多帧对齐")

        // 复用时序降噪内核（内部含 ImageAligner 配准 + 有效区掩膜加权平均）
        val res = TemporalDenoiser.denoise(frames)
            ?: run {
                AppLogger.w(TAG, "多帧合成失败，回退首帧")
                return frames[0]
            }

        onProgress?.invoke(80, "夜景：生成结果")
        return try {
            val bmp = MatUtils.bgrToBitmap(res.merged)
            val bytes = MatUtils.bitmapToJpeg(bmp, 95)
            bmp.recycle()
            bytes
        } catch (e: Throwable) {
            AppLogger.e(TAG, "夜景合成异常: ${e.message}", e)
            frames[0]
        } finally {
            runCatching { res.merged.release() }
            runCatching { res.mask.release() }
        }
    }
}
