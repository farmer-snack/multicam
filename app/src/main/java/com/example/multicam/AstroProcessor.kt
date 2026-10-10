package com.example.multicam

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * 星空模式处理器。
 *
 * 策略：手动长曝光 + 高 ISO 连拍多帧，靠对齐叠加抑噪。
 * 星点场景对 ORB 特征不友好（点状弱纹理），[ImageAligner] 会退化到等比例居中缩放，
 * 这在固定机位（三脚架）下恰是正确行为——所有帧本就对齐，居中缩放不引入偏移。
 *
 * 额外做一步**暗电流/光害基底估计**：长曝光会积累传感器暗电流与城市光害，
 * 表现为整幅偏色灰雾。这里用「逐通道最小值」近似黑电平（天空背景的暗部），
 * 减掉后星点对比度会明显提升，同时做一次轻微的拉伸让银河更明显。
 */
object AstroProcessor {

    private const val TAG = "AstroProcessor"

    /**
     * 星空叠加。
     *
     * @param frames 长曝光连拍帧（建议 4～8 帧）
     * @param stretch 是否做轻度对比拉伸（默认开启，星点更醒目）
     * @param onProgress 进度回调
     */
    fun stack(
        frames: List<ByteArray>,
        stretch: Boolean = true,
        onProgress: ((Int, String) -> Unit)? = null
    ): ByteArray? {
        if (frames.isEmpty()) return null
        if (frames.size == 1) return frames[0]

        onProgress?.invoke(15, "星空：星点对齐")

        val res = TemporalDenoiser.denoise(frames)
            ?: run {
                AppLogger.w(TAG, "叠加失败，回退首帧")
                return frames[0]
            }

        return try {
            onProgress?.invoke(60, "星空：暗电流抑制")
            removeDarkBase(res.merged)

            if (stretch) {
                onProgress?.invoke(75, "星空：对比拉伸")
                stretchContrast(res.merged)
            }

            onProgress?.invoke(90, "星空：生成结果")
            val bmp = MatUtils.bgrToBitmap(res.merged)
            val bytes = MatUtils.bitmapToJpeg(bmp, 95)
            bmp.recycle()
            bytes
        } catch (e: Exception) {
            AppLogger.e(TAG, "星空合成异常: ${e.message}", e)
            frames[0]
        } finally {
            runCatching { res.merged.release() }
            runCatching { res.mask.release() }
        }
    }

    /**
     * 暗电流 / 光害基底抑制：用逐通道的「低分位数」估计天空背景，减掉它。
     *
     * 不能直接用整幅最小值——那会被极个别坏点/热噪点拉偏。
     * 这里用 5% 分位近似（对 8U 图像退化为直方图累积），对天空占多数的星空图更稳。
     */
    private fun removeDarkBase(mat: Mat) {
        try {
            val channels = ArrayList<Mat>(3)
            Core.split(mat, channels)
            try {
                val base = DoubleArray(3)
                for (i in 0 until 3) {
                    base[i] = percentile5(channels[i])
                }
                if (base.any { it > 0.0 }) {
                    Core.subtract(mat, Scalar(base[0], base[1], base[2]), mat)
                }
            } finally {
                channels.forEach { runCatching { it.release() } }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "暗电流估计失败（跳过）: ${e.message}")
        }
    }

    /** 单通道分位像素值（0..255），用直方图累积求 */
    private fun percentile(ch: Mat, p: Double): Double {
        val hist = Mat()
        try {
            Imgproc.calcHist(
                listOf(ch), MatOfInt(0), Mat(), hist,
                MatOfInt(256), MatOfFloat(0f, 256f)
            )
            val total = ch.rows().toDouble() * ch.cols().toDouble()
            val target = total * p.coerceIn(0.0, 1.0)
            var acc = 0.0
            for (v in 0 until 256) {
                acc += hist.get(v, 0)?.get(0) ?: 0.0
                if (acc >= target) return v.toDouble()
            }
            return 255.0
        } catch (e: Exception) {
            // 直方图不可用时退化为 min/max
            val mm = Core.minMaxLoc(ch)
            return if (p < 0.5) mm.minVal else mm.maxVal
        } finally {
            runCatching { hist.release() }
        }
    }

    /** 单通道 5% 分位像素值（天空背景估计） */
    private fun percentile5(ch: Mat): Double = percentile(ch, 0.05)

    /**
     * 轻度对比拉伸：把各通道 [lo, hi] 区间线性映射到 [0, 255]，抑制光害灰雾、提亮星点。
     * lo/hi 取 2% / 99.2% 分位，避免被极值拉伸。
     */
    private fun stretchContrast(mat: Mat) {
        try {
            val split = ArrayList<Mat>(3)
            Core.split(mat, split)
            val parts = ArrayList<Mat>(3)
            try {
                for (i in 0 until 3) {
                    val lo = percentile(split[i], 0.02)
                    val hi = percentile(split[i], 0.992)
                    val range = (hi - lo).coerceAtLeast(1.0)
                    val alpha = 255.0 / range
                    val beta = -lo * alpha
                    val ch = Mat()
                    split[i].convertTo(ch, CvType.CV_8UC1, alpha, beta)
                    parts.add(ch)
                }
                val out = Mat()
                Core.merge(parts, out)
                out.copyTo(mat)
                out.release()
            } finally {
                split.forEach { runCatching { it.release() } }
                parts.forEach { runCatching { it.release() } }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "对比拉伸失败（跳过）: ${e.message}")
        }
    }
}
