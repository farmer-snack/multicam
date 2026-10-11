package com.example.multicam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

object MatUtils {

    private const val MAX_DIM = 4096

    /**
     * Bitmap 像素数上限（ARGB_8888 下 ×4 字节）。
     * 40MP ≈ 160MB，超过绝大多数设备的可用堆，再高必然 OOM。
     * 取 4000 万像素作为硬门槛，宁可明确报错也不要静默失败。
     */
    private const val MAX_BITMAP_PIXELS = 40_000_000L

    /**
     * JPEG 字节 → Bitmap。
     *
     * 【关键修复】OutOfMemoryError 必须**重抛**，不能吞。
     * 本函数的调用方（HdrProcessor / LongExposureProcessor / TemporalDenoiser /
     * ImageCompositor / PanoramaStitcher）全都用 `?: continue` 处理 null，
     * 一旦把 OOM 静默降级成 null，夜景 6 帧掉 5 帧时会「成功」用 1 帧出片，
     * 用户看到的却是"HDR 好像没生效"。OOM 应当上抛，由上层统一降级/报错。
     */
    fun jpegToBitmap(bytes: ByteArray): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / sample > MAX_DIM ||
                bounds.outHeight / sample > MAX_DIM
            ) sample *= 2

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: return null
            applyExif(bmp, readOrientation(bytes))
        } catch (e: OutOfMemoryError) {
            // 解码 12MP JPEG 本身就可能 OOM，这属于"输入太大"而非"文件损坏"，
            // 静默变 null 会让上层用残缺的帧集继续出片。
            AppLogger.e("MatUtils", "解码 OOM（${bytes.size / 1024}KB），上抛由上层降级", e)
            throw e
        } catch (e: Exception) {
            AppLogger.w("MatUtils", "JPEG 解码失败: ${e.message}")
            null
        }
    }

    fun bitmapToBgr(bmp: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        return bgr
    }

    fun bgrToBitmap(mat: Mat): Bitmap {
        // 【修复】原来没有尺寸守卫。ARGB_8888 Bitmap 每像素 4 字节，
        // 一张 4728×3548 的图 = 67MB；与其它 Mat 同时存活时极易 OOM，
        // 而异常被上层的 catch(Exception) 吞掉 → 用户转圈几十秒后拿到原图，
        // 且没有任何报错。超过阈值时主动抛，让上层明确降级。
        val px = mat.cols().toLong() * mat.rows()
        require(px <= MAX_BITMAP_PIXELS) {
            "位图过大 ${mat.cols()}x${mat.rows()}（${px / 10000} 万像素）"
        }
        val rgba = Mat()
        try {
            Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_BGR2RGBA)
            val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgba, bmp)
            return bmp
        } finally {
            rgba.release()
        }
    }

    fun bitmapToJpeg(bmp: Bitmap, quality: Int = 95): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private fun readOrientation(bytes: ByteArray): Int = try {
        val input: InputStream = ByteArrayInputStream(bytes)
        val ei = ExifInterface(input)
        ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }

    private fun applyExif(bitmap: Bitmap, orientation: Int): Bitmap {
        if (orientation == ExifInterface.ORIENTATION_NORMAL) return bitmap
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val m = Matrix()
        // 修复（2026-10）：Matrix 的 pivot 默认在原点 (0,0)。
        // 原实现 ORIENTATION_FLIP_HORIZONTAL 只做 postScale(-1f, 1f)，
        // 相当于把图像翻到负坐标区域，createBitmap 后得到的是空白/错位图。
        // 每次翻转都需要用 preTranslate/postTranslate 把图像平移回可见区间。
        // 这里按 Exif 标准定义逐一构造，等价于 AndroidX 推荐的完整变换。
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f, w / 2f, h / 2f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f, w / 2f, h / 2f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                // 转置 = 沿主对角线镜像：先水平翻转再旋转 90°
                m.postScale(-1f, 1f, w / 2f, h / 2f)
                m.postRotate(90f, w / 2f, h / 2f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                // 反转置 = 沿副对角线镜像：先水平翻转再旋转 270°
                m.postScale(-1f, 1f, w / 2f, h / 2f)
                m.postRotate(270f, w / 2f, h / 2f)
            }
            else -> return bitmap
        }
        return try {
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, m, true
            )
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (e: Exception) { bitmap }
    }
}
