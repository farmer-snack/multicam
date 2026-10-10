package com.example.multicam

import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

object ImageAligner {

    private const val TAG = "ImageAligner"
    private const val MAX_ORB_FEATURES = 2000
    private const val GOOD_MATCH_DIST = 60f
    private const val MIN_GOOD_MATCHES = 12
    private const val RANSAC_REPROJ = 5.0

    data class AlignResult(val aligned: Mat, val mask: Mat, val success: Boolean)

    fun align(base: Mat, target: Mat): AlignResult {
        var kpBase: MatOfKeyPoint? = null
        var kpTarget: MatOfKeyPoint? = null
        var descBase: Mat? = null
        var descTarget: Mat? = null
        // 修复（2026-10）：h（单应矩阵）与 k（形态学结构元素）原先在 try 作用域内创建，
        // 既没有 release 也没有在 finally 里兜底，每次对齐都会泄漏两块 native 内存。
        // 时序降噪/多摄合成会连续对齐几十次，累积后直接把 native heap 撑爆。
        var h: Mat? = null
        var k: Mat? = null
        try {
            val orb = ORB.create(MAX_ORB_FEATURES)
            kpBase = MatOfKeyPoint(); kpTarget = MatOfKeyPoint()
            descBase = Mat(); descTarget = Mat()

            val grayBase = Mat(); val grayTarget = Mat()
            Imgproc.cvtColor(base, grayBase, Imgproc.COLOR_BGR2GRAY)
            Imgproc.cvtColor(target, grayTarget, Imgproc.COLOR_BGR2GRAY)

            orb.detectAndCompute(grayBase, Mat(), kpBase, descBase)
            orb.detectAndCompute(grayTarget, Mat(), kpTarget, descTarget)
            grayBase.release(); grayTarget.release()

            if (descBase.empty() || descTarget.empty()) {
                AppLogger.w(TAG, "特征点为空，退化到缩放对齐")
                return fallbackResize(base, target)
            }

            val matcher = BFMatcher(Core.NORM_HAMMING, true)
            val matches = MatOfDMatch()
            matcher.match(descTarget, descBase, matches)

            val good = matches.toList()
                .filter { it.distance < GOOD_MATCH_DIST }
                .sortedBy { it.distance }.take(500)

            if (good.size < MIN_GOOD_MATCHES) return fallbackResize(base, target)

            val kpTargetArr = kpTarget.toArray()
            val kpBaseArr = kpBase.toArray()

            val srcPts = MatOfPoint2f(
                *good.map { kpTargetArr[it.queryIdx].pt }.toTypedArray()
            )
            val dstPts = MatOfPoint2f(
                *good.map { kpBaseArr[it.trainIdx].pt }.toTypedArray()
            )

            val hLocal = try {
                Calib3d.findHomography(srcPts, dstPts, Calib3d.RANSAC, RANSAC_REPROJ)
            } finally {
                // 修复（2026-10）：MatOfPoint2f 也是 native Mat，原先从未释放
                srcPts.release(); dstPts.release()
            }
            if (hLocal == null || hLocal.empty()) {
                AppLogger.w(TAG, "单应矩阵计算失败，退化到缩放对齐")
                hLocal?.release()
                return fallbackResize(base, target)
            }
            h = hLocal

            val aligned = Mat()
            Imgproc.warpPerspective(
                target, aligned, hLocal, base.size(),
                Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0)
            )

            val srcMask = Mat(target.size(), CvType.CV_8UC1, Scalar(255.0))
            val dstMask = Mat()
            Imgproc.warpPerspective(
                srcMask, dstMask, hLocal, base.size(),
                Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0)
            )
            srcMask.release()

            val kLocal = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0))
            k = kLocal
            Imgproc.erode(dstMask, dstMask, kLocal)
            Imgproc.threshold(dstMask, dstMask, 128.0, 255.0, Imgproc.THRESH_BINARY)

            val nonZero = Core.countNonZero(dstMask)
            if (nonZero < base.rows() * base.cols() * 0.05) {
                aligned.release(); dstMask.release()
                return fallbackResize(base, target)
            }

            return AlignResult(aligned, dstMask, true)
        } catch (e: Exception) {
            AppLogger.e(TAG, "对齐异常: ${e.message}", e)
            return fallbackResize(base, target)
        } finally {
            kpBase?.release(); kpTarget?.release()
            descBase?.release(); descTarget?.release()
            runCatching { h?.release() }
            runCatching { k?.release() }
        }
    }

    private fun fallbackResize(base: Mat, target: Mat): AlignResult {
        // 修复（2026-10）：target 尺寸为 0 时 scale 会算出 Infinity/NaN，
        // (target.cols() * scale).toInt() 会得到 Int.MAX_VALUE，随后 resize 直接崩。
        if (target.cols() <= 0 || target.rows() <= 0 ||
            base.cols() <= 0 || base.rows() <= 0
        ) {
            val aligned = Mat(base.size(), base.type(), Scalar(0.0, 0.0, 0.0))
            val mask = Mat(base.size(), CvType.CV_8UC1, Scalar(0.0))
            return AlignResult(aligned, mask, false)
        }
        val scale = minOf(
            base.cols().toDouble() / target.cols(),
            base.rows().toDouble() / target.rows()
        )
        if (!scale.isFinite() || scale <= 0.0) {
            val aligned = Mat(base.size(), base.type(), Scalar(0.0, 0.0, 0.0))
            val mask = Mat(base.size(), CvType.CV_8UC1, Scalar(0.0))
            return AlignResult(aligned, mask, false)
        }
        val newW = (target.cols() * scale).toInt().coerceIn(1, base.cols())
        val newH = (target.rows() * scale).toInt().coerceIn(1, base.rows())

        val resized = Mat()
        Imgproc.resize(target, resized, Size(newW.toDouble(), newH.toDouble()))

        val aligned = Mat(base.size(), base.type(), Scalar(0.0, 0.0, 0.0))
        val x = (base.cols() - newW) / 2
        val y = (base.rows() - newH) / 2
        val roi = aligned.submat(y, y + newH, x, x + newW)
        resized.copyTo(roi); roi.release(); resized.release()

        val mask = Mat(base.size(), CvType.CV_8UC1, Scalar(0.0))
        val maskRoi = mask.submat(y, y + newH, x, x + newW)
        maskRoi.setTo(Scalar(255.0)); maskRoi.release()

        return AlignResult(aligned, mask, false)
    }
}
