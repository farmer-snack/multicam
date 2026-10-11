package com.example.multicam

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

object TemporalDenoiser {

    private const val TAG = "TemporalDenoiser"

    data class DenoiseResult(val merged: Mat, val mask: Mat, val success: Boolean)

    fun denoise(frames: List<ByteArray>, maxDim: Int = 2048): DenoiseResult? {
        if (frames.isEmpty()) return null

        if (frames.size == 1) {
            val bmp = MatUtils.jpegToBitmap(frames[0]) ?: return null
            val mat = MatUtils.bitmapToBgr(bmp); bmp.recycle()
            val mask = Mat(mat.size(), CvType.CV_8UC1, Scalar(255.0))
            return DenoiseResult(mat, mask, true)
        }

        val mats = mutableListOf<Mat>()
        var transferred = false
        try {
            for (b in frames) {
                val bmp = MatUtils.jpegToBitmap(b) ?: continue
                val mat = MatUtils.bitmapToBgr(bmp); bmp.recycle()
                val scaled = downscaleIfNeeded(mat, maxDim)
                if (scaled != mat) mat.release()
                mats.add(scaled)
            }
            if (mats.isEmpty()) return null
            if (mats.size == 1) {
                val single = mats.removeAt(0)
                transferred = true
                val mask = Mat(single.size(), CvType.CV_8UC1, Scalar(255.0))
                return DenoiseResult(single, mask, true)
            }

            val ref = mats[0]
            val refSize = ref.size()

            val sum = Mat.zeros(refSize, CvType.CV_32FC3)
            val weight = Mat.zeros(refSize, CvType.CV_32FC1)

            val ref32 = Mat(); ref.convertTo(ref32, CvType.CV_32FC3)
            Core.add(sum, ref32, sum); ref32.release()
            weight.setTo(Scalar(1.0))

            for (i in 1 until mats.size) {
                val res = ImageAligner.align(ref, mats[i])
                try {
                    val w = if (res.success) 1.0f else 0.35f
                    val aligned32 = Mat()
                    res.aligned.convertTo(aligned32, CvType.CV_32FC3)
                    val weighted = Mat()
                    Core.multiply(aligned32,
                        Scalar(w.toDouble(), w.toDouble(), w.toDouble()), weighted)
                    Core.add(sum, weighted, sum)
                    aligned32.release(); weighted.release()

                    // 修复（2026-10）：mask 是 CV_8UC1 的 0/255，直接 convertTo 到
                    // CV_32FC1 得到的是 0/255，不是 0/1。原实现没做 1/255 归一化，
                    // 于是每帧权重被放大 255 倍，参考帧（weight 初始为 1.0）相对权重
                    // 被压到可忽略，帧间平均近似"只取了最后一帧"——降噪完全失效且
                    // 会引入拖影。这里显式归一化到 0..1。
                    val mask32 = Mat()
                    res.mask.convertTo(mask32, CvType.CV_32FC1, 1.0 / 255.0)
                    Core.multiply(mask32, Scalar(w.toDouble()), mask32)
                    Core.add(weight, mask32, weight)
                    mask32.release()
                } finally {
                    res.aligned.release(); res.mask.release()
                }
            }

            val wSafe = Mat()
            Core.max(weight, Scalar(1e-6), wSafe)
            val w3 = Mat()
            Imgproc.cvtColor(wSafe, w3, Imgproc.COLOR_GRAY2BGR)
            val avg = Mat()
            Core.divide(sum, w3, avg)
            val merged8 = Mat()
            avg.convertTo(merged8, CvType.CV_8UC3)

            val validMask = Mat()
            Imgproc.threshold(wSafe, validMask, 0.5, 255.0, Imgproc.THRESH_BINARY)
            validMask.convertTo(validMask, CvType.CV_8UC1)

            sum.release(); weight.release(); wSafe.release()
            w3.release(); avg.release()

            return DenoiseResult(merged8, validMask, true)
        } catch (e: Throwable) {
            AppLogger.e(TAG, "时序降噪失败: ${e.message}", e); return null
        } finally {
            if (!transferred) mats.forEach { runCatching { it.release() } }
        }
    }

    private fun downscaleIfNeeded(src: Mat, maxDim: Int): Mat {
        val m = maxOf(src.cols(), src.rows())
        if (m <= maxDim) return src
        val scale = maxDim.toDouble() / m
        val out = Mat()
        // 【关键修复】缩小必须用 INTER_AREA（盒式平均），默认的 INTER_LINEAR
        // 只取 4 个相邻像素做双线性，对 4032→2048 这种整数倍缩小来说是**欠采样**：
        // 树叶/砖墙/发丝产生混叠（摩尔纹），传感器噪声也被同样地点采样进来。
        // 后果是降噪的输入本身已带采样误差，降噪效果被锁死、边缘出白边锯齿。
        Imgproc.resize(
            src, out,
            org.opencv.core.Size(src.cols() * scale, src.rows() * scale),
            0.0, 0.0, Imgproc.INTER_AREA
        )
        return out
    }
}
