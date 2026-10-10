package com.example.multicam

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

data class ComposeLayer(
    val id: String,
    val role: CameraRole,
    val jpeg: ByteArray
)

object ImageCompositor {

    private const val TAG = "Compositor"

    fun compose(layers: List<ComposeLayer>): ByteArray {
        if (layers.isEmpty()) return ByteArray(0)
        if (layers.size == 1) return layers[0].jpeg

        val mats = mutableListOf<Mat>()
        try {
            for (l in layers) {
                val bmp = MatUtils.jpegToBitmap(l.jpeg) ?: continue
                val mat = MatUtils.bitmapToBgr(bmp); bmp.recycle()
                mats.add(mat)
            }
            if (mats.isEmpty()) return layers[0].jpeg
            if (mats.size == 1) {
                val bmp = MatUtils.bgrToBitmap(mats[0])
                val bytes = MatUtils.bitmapToJpeg(bmp); bmp.recycle()
                return bytes
            }

            val base = mats[0]
            val roles = layers.map { it.role }

            for (i in 1 until mats.size) {
                val layerRole = roles.getOrElse(i) { CameraRole.UNKNOWN }
                val res = ImageAligner.align(base, mats[i])
                try {
                    blendInPlace(base, res.aligned, res.mask, roles[0], layerRole)
                } finally {
                    res.aligned.release(); res.mask.release()
                }
            }

            val outBmp = MatUtils.bgrToBitmap(base)
            val bytes = MatUtils.bitmapToJpeg(outBmp, 95); outBmp.recycle()
            return bytes
        } catch (e: Exception) {
            AppLogger.e(TAG, "合成异常: ${e.message}", e)
            return layers[0].jpeg
        } finally {
            mats.forEach { runCatching { it.release() } }
        }
    }

    /**
     * 把 layer 以 mask 为权重混合进 base（原地）。
     *
     * 修复（2026-10）：原实现 `Core.multiply(base, invAlpha, basePart)` 中
     * base 是 CV_8UC3、invAlpha 是 CV_32FC3，OpenCV 的 Core.multiply 要求两个
     * Mat 的 type 完全一致，否则抛 `CvException: The operation is neither 'array op array'...`
     * —— 也就是说**只要走多摄合成路径就必然崩溃**（被外层 catch 吞掉后返回首帧原图，
     * 表现为"合成功能完全无效"）。
     *
     * 现在统一在 CV_32FC3 域内做加权，最后再转回 CV_8UC3。
     */
    private fun blendInPlace(
        base: Mat, layer: Mat, mask: Mat,
        baseRole: CameraRole, layerRole: CameraRole
    ) {
        val dist = Mat()
        Imgproc.distanceTransform(mask, dist, Imgproc.DIST_L2, 3)
        Core.normalize(dist, dist, 0.0, 1.0, Core.NORM_MINMAX)

        val w = when {
            baseRole == CameraRole.ULTRA_WIDE && layerRole == CameraRole.TELE -> 0.9
            baseRole == CameraRole.ULTRA_WIDE && layerRole == CameraRole.MAIN -> 1.0
            baseRole == CameraRole.MAIN && layerRole == CameraRole.TELE -> 0.95
            else -> 1.0
        }
        if (w != 1.0) Core.multiply(dist, Scalar(w), dist)

        val alpha = Mat()
        Imgproc.cvtColor(dist, alpha, Imgproc.COLOR_GRAY2BGR)
        dist.release()

        // 统一转到 32F 再算，避免 8U×32F 的类型冲突
        val base32 = Mat(); val layer32 = Mat()
        base.convertTo(base32, CvType.CV_32FC3)
        layer.convertTo(layer32, CvType.CV_32FC3)

        val ones = Mat.ones(alpha.size(), CvType.CV_32FC3)
        val invAlpha = Mat()
        Core.subtract(ones, alpha, invAlpha); ones.release()

        val basePart = Mat(); val layerPart = Mat()
        Core.multiply(base32, invAlpha, basePart)
        Core.multiply(layer32, alpha, layerPart)
        val mixed = Mat()
        Core.add(basePart, layerPart, mixed)

        mixed.convertTo(base, CvType.CV_8UC3)

        mixed.release()
        basePart.release(); layerPart.release()
        base32.release(); layer32.release()
        alpha.release(); invAlpha.release()
    }
}
