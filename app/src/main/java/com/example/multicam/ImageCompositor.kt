package com.example.multicam

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** 融合边缘羽化半径（像素）：小高斯，消除硬接缝又不破坏内部平坦区 */
private const val FEATHER_K = 3

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
        // 【关键修复】role 必须与 Mat 同步累积。
        // 原来解码失败用 `?: continue` 跳过，mats 会比 layers 短，
        // 但后面仍按 layers 的下标取 roles —— 任一层失败就从这一层起
        // 全部错位：mats[0] 不再是 layers[0]（连 baseRole 都错），
        // 融合权重取错角色（ULTRA_WIDE+TELE=0.9 vs MAIN+TELE=0.95），
        // 且 roles[0] 错配会让底图选错，表现是"同样的场景有时糊有时清晰"。
        val matRoles = mutableListOf<CameraRole>()
        try {
            for (l in layers) {
                val bmp = MatUtils.jpegToBitmap(l.jpeg) ?: continue
                val mat = MatUtils.bitmapToBgr(bmp); bmp.recycle()
                mats.add(mat)
                matRoles.add(l.role)
            }
            if (mats.isEmpty()) return layers[0].jpeg
            if (mats.size == 1) {
                val bmp = MatUtils.bgrToBitmap(mats[0])
                val bytes = MatUtils.bitmapToJpeg(bmp); bmp.recycle()
                return bytes
            }

            val base = mats[0]
            val baseRole = matRoles[0]

            for (i in 1 until mats.size) {
                val res = ImageAligner.align(base, mats[i])
                try {
                    blendInPlace(base, res.aligned, res.mask, baseRole, matRoles[i])
                } finally {
                    res.aligned.release(); res.mask.release()
                }
            }

            val outBmp = MatUtils.bgrToBitmap(base)
            val bytes = MatUtils.bitmapToJpeg(outBmp, 95); outBmp.recycle()
            return bytes
        } catch (e: Throwable) {
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
        val w = when {
            baseRole == CameraRole.ULTRA_WIDE && layerRole == CameraRole.TELE -> 0.9
            baseRole == CameraRole.ULTRA_WIDE && layerRole == CameraRole.MAIN -> 1.0
            baseRole == CameraRole.MAIN && layerRole == CameraRole.TELE -> 0.95
            else -> 1.0
        }

        // 【关键修复】原来这里先 distanceTransform 再 NORM_MINMAX：
        //     Imgproc.distanceTransform(mask, dist, DIST_L2, 3)
        //     Core.normalize(dist, dist, 0.0, 1.0, NORM_MINMAX)
        // distanceTransform 返回的是「到最近背景像素的距离」：掩膜**内部**是
        // 边缘为 0、中心最大 的径向梯度，而不是覆盖度。再做 NORM_MINMAX 把
        // 那个最大值（通常是画面中心的一个点）映射到 1.0，结果是**整层 alpha
        // 在 0~1 之间连续渐变**，只有离边界最远的那一个像素等于 1。
        // 后果：长焦层永远以半透明叠加在超广角底图上（视差差异被抹成重影），
        // 叠加 w=0.9 后峰值贡献被压到 90% 以下 → 长焦细节永远进不来。
        // 极端情况：mask 全 0 或全 255 时 smin==smax，OpenCV 把 scale 置 0，
        // alpha 整幅为 0 → 该层被静默丢弃。
        //
        // 正确做法：mask 本身就是 0/255 的有效区域覆盖度，直接归一化即可，
        // 内部是平坦的 1.0，只在边缘做一次小半径高斯羽化即可。
        val alpha = Mat()
        mask.convertTo(alpha, CvType.CV_32FC3, 1.0 / 255.0)
        if (w != 1.0) Core.multiply(alpha, Scalar(w), alpha)
        // 小半径羽化：消掉硬边造成的接缝，同时不破坏内部的平坦 1.0
        Imgproc.GaussianBlur(
            alpha, alpha,
            Size(FEATHER_K * 2 + 1.0, FEATHER_K * 2 + 1.0), 0.0, 0.0,
            Core.BORDER_REPLICATE
        )

        // 统一转到 32F 再算，避免 8U×32F 的类型冲突
        val base32 = Mat()
        val layer32 = Mat()
        base.convertTo(base32, CvType.CV_32FC3)
        layer.convertTo(layer32, CvType.CV_32FC3)

        val ones = Mat.ones(alpha.size(), CvType.CV_32FC3)
        val invAlpha = Mat()
        Core.subtract(ones, alpha, invAlpha)
        ones.release()

        val basePart = Mat()
        val layerPart = Mat()
        Core.multiply(base32, invAlpha, basePart)
        Core.multiply(layer32, alpha, layerPart)
        val mixed = Mat()
        Core.add(basePart, layerPart, mixed)

        mixed.convertTo(base, CvType.CV_8UC3)

        base32.release()
        layer32.release()
        invAlpha.release()
        basePart.release()
        layerPart.release()
        mixed.release()
        base32.release()
        layer32.release()
        alpha.release()
        invAlpha.release()
    }
}
