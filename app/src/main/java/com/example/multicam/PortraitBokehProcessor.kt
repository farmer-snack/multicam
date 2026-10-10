package com.example.multicam

import android.graphics.RectF
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * 软件人像虚化（无深度图方案）。
 *
 * **重要说明**：这是标准 Camera2 能做到的近似实现，效果上限明显低于 vivo 的蔡司人像
 * （后者依赖厂商私有 ISP + 深度传感 + 蔡司光学模型）。本方案：
 *
 *  1. 用 ML Kit 人脸检测拿到人脸框（[faces]）；
 *  2. 在人脸框上画**外扩椭圆**作为"清晰区"，其余区域做羽化过渡；
 *  3. 检测不到人脸时退化为**中心椭圆**（经典人像构图假设）；
 *  4. 全图高斯模糊后按 mask 做 alpha 混合（清晰主体 / 模糊背景）。
 *
 * 因此边缘过渡是"柔和渐变"而非"精确抠图"，头发丝、眼镜框等细节无法完美分离。
 */
object PortraitBokehProcessor {

    private const val TAG = "PortraitBokeh"

    /**
     * 渲染人像虚化。
     *
     * @param jpeg 原始 JPEG
     * @param faces 人脸框（相对整幅图像归一化前的像素坐标；空则用中心椭圆）
     * @param blurStrength 模糊半径（像素，建议 15～40，内部会强制为奇数）
     * @param onProgress 进度回调
     */
    fun render(
        jpeg: ByteArray,
        faces: List<RectF>,
        blurStrength: Int = 25,
        onProgress: ((Int, String) -> Unit)? = null
    ): ByteArray? {
        var srcRef: Mat? = null
        var maskRef: Mat? = null
        var alphaRef: Mat? = null
        var alpha3Ref: Mat? = null
        var inv3Ref: Mat? = null
        var blurredRef: Mat? = null
        var sharpRef: Mat? = null
        var softRef: Mat? = null
        var sharpPartRef: Mat? = null
        var outRef: Mat? = null
        try {
            onProgress?.invoke(10, "人像：解码")
            val bmp = MatUtils.jpegToBitmap(jpeg) ?: return jpeg
            val src = try {
                MatUtils.bitmapToBgr(bmp)
            } finally {
                bmp.recycle()
            }
            srcRef = src

            val h = src.rows()
            val w = src.cols()
            if (h < 8 || w < 8) return jpeg

            // ---------- 1. 构建清晰区 mask（255 = 清晰） ----------
            onProgress?.invoke(30, "人像：构建景深掩膜")
            val mask = Mat.zeros(h, w, CvType.CV_8UC1)
            maskRef = mask

            // 人脸框需要从「人脸检测所用的位图坐标」映射到当前工作尺寸。
            // MatUtils.jpegToBitmap 有 MAX_DIM=4096 降采样，人脸检测走的是同一条解码路径，
            // 因此这里的比例在大多数情况下是 1:1；仍按实际尺寸做一次归一化以防万一。
            val faceRects = if (faces.isEmpty()) {
                emptyList()
            } else {
                val maxX = faces.maxOf { it.right }.coerceAtLeast(1f)
                val maxY = faces.maxOf { it.bottom }.coerceAtLeast(1f)
                val sx = if (maxX > w * 1.05f) w / maxX else 1f
                val sy = if (maxY > h * 1.05f) h / maxY else 1f
                faces.map {
                    RectF(it.left * sx, it.top * sy, it.right * sx, it.bottom * sy)
                }
            }

            if (faceRects.isNotEmpty()) {
                for (r in faceRects) {
                    val cx = r.centerX().toDouble()
                    val cy = r.centerY().toDouble()
                    // 外扩：人像虚化要保住头肩，不只是脸部
                    val ax = (r.width() * 1.25).toDouble().coerceAtLeast(4.0)
                    val ay = (r.height() * 1.8).toDouble().coerceAtLeast(4.0)
                    Imgproc.ellipse(
                        mask, Point(cx, cy), Size(ax, ay),
                        0.0, 0.0, 360.0, Scalar(255.0), -1
                    )
                }
            } else {
                // 无脸：中心椭圆 + 底部留白（经典人像构图）
                val cx = w / 2.0
                val cy = h * 0.48
                val ax = w * 0.34
                val ay = h * 0.46
                Imgproc.ellipse(
                    mask, Point(cx, cy), Size(ax, ay),
                    0.0, 0.0, 360.0, Scalar(255.0), -1
                )
            }

            // ---------- 2. 羽化 ----------
            val feather = ((minOf(w, h) * 0.06).toInt() or 1).coerceAtLeast(9)
            Imgproc.GaussianBlur(mask, mask, Size(feather.toDouble(), feather.toDouble()), 0.0)

            // ---------- 3. 全图模糊 ----------
            onProgress?.invoke(55, "人像：背景模糊")
            val k = (blurStrength.coerceIn(3, 60) or 1)
            // OpenCV Java 绑定没有 stackBlur，用高斯模糊
            val blurredLocal = Mat()
            Imgproc.GaussianBlur(src, blurredLocal, Size(k.toDouble(), k.toDouble()), 0.0)
            blurredRef = blurredLocal

            // ---------- 4. 按 mask 混合 ----------
            onProgress?.invoke(75, "人像：合成")
            val alphaLocal = Mat()
            mask.convertTo(alphaLocal, CvType.CV_32FC1, 1.0 / 255.0)
            alphaRef = alphaLocal

            val a3 = Mat()
            Imgproc.cvtColor(alphaLocal, a3, Imgproc.COLOR_GRAY2BGR)
            alpha3Ref = a3

            // inv = 1 - alpha（OpenCV Java 无 (Scalar,Mat,Mat)，用 Mat.ones 构造）
            val inv = Mat.ones(a3.size(), CvType.CV_32FC3)
            Core.subtract(inv, a3, inv)
            inv3Ref = inv

            val sharpL = Mat()
            src.convertTo(sharpL, CvType.CV_32FC3)
            sharpRef = sharpL

            val softL = Mat()
            blurredLocal.convertTo(softL, CvType.CV_32FC3)
            softRef = softL

            val sp = Mat()
            Core.multiply(sharpL, a3, sp)
            sharpPartRef = sp

            val sw = Mat()
            Core.multiply(softL, inv, sw)

            val mixed = Mat()
            Core.add(sp, sw, mixed)
            sw.release()

            val outL = Mat()
            mixed.convertTo(outL, CvType.CV_8UC3)
            mixed.release()
            outRef = outL

            onProgress?.invoke(95, "人像：编码")
            val ob = MatUtils.bgrToBitmap(outL)
            return try {
                MatUtils.bitmapToJpeg(ob, 95)
            } finally {
                ob.recycle()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "人像虚化异常: ${e.message}", e)
            return jpeg
        } finally {
            listOf(
                srcRef, maskRef, alphaRef, alpha3Ref, inv3Ref,
                blurredRef, sharpRef, softRef, sharpPartRef, outRef
            ).forEach { m -> runCatching { m?.release() } }
        }
    }
}
