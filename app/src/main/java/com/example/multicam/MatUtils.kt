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
        } catch (e: Exception) { null }
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
        val rgba = Mat()
        Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_BGR2RGBA)
        val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bmp)
        rgba.release()
        return bmp
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
