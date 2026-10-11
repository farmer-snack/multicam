package com.example.multicam

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * HDR 包围曝光处理器。
 *
 * 采集：一次拍摄 7 帧（EV = −2/−1/0/+1/+2/0/+0.5），走 AE 的 EV 补偿（兼容性最好，不碰 ISO/快门）。
 * 多出的两帧（0 与 +0.5）用于补足中间调样本，抑制加权平均在平滑区域产生的噪声。
 * 合成：OpenCV 的 Android 绑定里**没有** Mertens 曝光融合（与全景缺 Stitcher 同一原因），
 * 这里用「中间调加权融合」近似：
 *
 *   权重 w = 1 − |2·g − 1|     （g 为归一化亮度）
 *
 * 即亮度越接近 0.5（中间调）权重大，过曝（g→1）和欠曝（g→0）权重趋近 0。
 * 这样高光只取暗帧里的细节、暗部只取亮帧里的细节，等效于一张宽容度更高的图。
 *
 * 为避免权重全零导致除零，权重下限 clamp 到 1e-3；同时给每帧一个 0.05 的基础权重，
 * 保证极端场景（整幅纯黑/纯白）也不会出现 NaN。
 */
object HdrProcessor {

    private const val TAG = "HdrProcessor"
    private const val BASE_WEIGHT = 0.05

    /**
     * 曝光融合。
     *
     * @param frames EV 包围帧列表（建议 5 帧）
     * @param onProgress 进度回调
     */
    fun merge(frames: List<ByteArray>, onProgress: ((Int, String) -> Unit)? = null): ByteArray? {
        if (frames.isEmpty()) return null
        if (frames.size == 1) return frames[0]

        val mats = ArrayList<Mat>(frames.size)
        try {
            for (b in frames) {
                val bmp = MatUtils.jpegToBitmap(b) ?: continue
                try {
                    mats.add(MatUtils.bitmapToBgr(bmp))
                } finally {
                    bmp.recycle()
                }
            }
            if (mats.isEmpty()) return null
            if (mats.size == 1) return encode(mats[0])

            val base = mats[0]
            val size = base.size()

            val acc = Mat.zeros(size, CvType.CV_32FC3)
            val wSum = Mat.zeros(size, CvType.CV_32FC1)

            try {
                for (i in mats.indices) {
                    onProgress?.invoke(
                        10 + (i * 70) / mats.size,
                        "HDR：融合第 ${i + 1}/${mats.size} 帧"
                    )

                    // 第一帧不需要对齐（它就是基准）
                    val aligned: Mat
                    val validMask: Mat?
                    val needRelease: Boolean
                    if (i == 0) {
                        aligned = mats[0]; needRelease = false; validMask = null
                    } else {
                        val res = ImageAligner.align(base, mats[i])
                        aligned = res.aligned
                        needRelease = true
                        validMask = res.mask
                    }

                    try {
                        val gray = Mat()
                        Imgproc.cvtColor(aligned, gray, Imgproc.COLOR_BGR2GRAY)
                        val g32 = Mat()
                        gray.convertTo(g32, CvType.CV_32FC1, 1.0 / 255.0)
                        gray.release()

                        // w = 1 - |2g - 1|  →  中间调最大
                        // 注意 OpenCV Java 的 subtract 只有 (Mat,Mat,Mat) 与 (Mat,Scalar,Mat)，
                        // 没有 (Scalar,Mat,Mat)，所以要么先构造全 1 Mat，要么改成 1 - w 的两步写法。
                        val w = Mat()
                        Core.absdiff(g32, Scalar(0.5), w)
                        Core.multiply(w, Scalar(2.0), w)
                        val ones = Mat.ones(g32.size(), CvType.CV_32FC1)
                        Core.subtract(ones, w, w)
                        ones.release()
                        g32.release()

                        // 【关键修复】必须把配准掩膜乘进权重。
                        // ImageAligner 对齐失败/视野外的区域是 BORDER_CONSTANT 0，
                        // 那是**纯黑像素**：g=0 → w = 1-|0-1| = 0，只剩 BASE_WEIGHT=0.05。
                        // 原实现没有乘掩膜，于是这些黑像素：
                        //   分子贡献 0 × 0.05 = 0（没内容）
                        //   分母却实打实加了 0.05（有计数）
                        // 7 帧里若有 6 帧在该区域无内容：真实帧 w0=1.046 被除以
                        // 1.046 + 6×0.05 = 1.346 → **该区域亮度掉到 77%**（暗 23%）。
                        // 表现为"HDR 结果整体偏暗，且与清晰区域之间有一圈暗环"。
                        if (validMask != null) {
                            val m32 = Mat()
                            validMask.convertTo(m32, CvType.CV_32FC1, 1.0 / 255.0)
                            // 无效区权重归零，避免黑像素被当成"极暗但有效"的内容
                            Core.multiply(w, m32, w)
                            m32.release()
                        }

                        // 基础权重 + 下限，防除零
                        Core.add(w, Scalar(BASE_WEIGHT), w)

                        // 图像转 32F 并乘上权重（权重是单通道，需要扩成三通道）
                        val a32 = Mat()
                        aligned.convertTo(a32, CvType.CV_32FC3)
                        val w3 = Mat()
                        Imgproc.cvtColor(w, w3, Imgproc.COLOR_GRAY2BGR)
                        val aw = Mat()
                        Core.multiply(a32, w3, aw)

                        Core.add(acc, aw, acc)
                        Core.add(wSum, w, wSum)

                        a32.release(); w3.release(); aw.release(); w.release()
                    } finally {
                        // 【修复】掩膜现在参与运算，必须在这里释放（原来在
                        // align 之后立刻 release，导致权重拿不到有效区信息）
                        if (needRelease) runCatching { aligned.release() }
                        if (validMask != null) runCatching { validMask.release() }
                    }
                }

                onProgress?.invoke(85, "HDR：归一化")

                val out = Mat()
                try {
                    val wSafe = Mat()
                    Core.max(wSum, Scalar(1e-3), wSafe)
                    val w3 = Mat()
                    Imgproc.cvtColor(wSafe, w3, Imgproc.COLOR_GRAY2BGR)
                    val avg = Mat()
                    Core.divide(acc, w3, avg)
                    avg.convertTo(out, CvType.CV_8UC3)
                    avg.release(); w3.release(); wSafe.release()
                    return encode(out)
                } finally {
                    runCatching { out.release() }
                }
            } finally {
                runCatching { acc.release() }
                runCatching { wSum.release() }
            }
        } catch (e: Throwable) {
            AppLogger.e(TAG, "HDR 合成异常: ${e.message}", e)
            return frames[0]
        } finally {
            mats.forEach { runCatching { it.release() } }
        }
    }

    private fun encode(mat: Mat): ByteArray? {
        if (mat.empty()) return null
        val bmp = MatUtils.bgrToBitmap(mat)
        return try {
            MatUtils.bitmapToJpeg(bmp, 95)
        } finally {
            bmp.recycle()
        }
    }
}
