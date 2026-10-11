package com.example.multicam

import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc

/**
 * 全景拼接（不依赖 Stitcher 模块）：
 * - 每相邻两帧用 ORB 特征求单应 H（当前帧 → 前一帧）
 * - 累积到第 0 帧空间，按四角外接框建画布
 * - 逐帧透视变换到画布并加权融合
 * 适用于手持缓慢平移扫描；移动幅度过大/特征不足时按比例降级。
 */
object PanoramaStitcher {

    private const val MAX_ORB_FEATURES = 1500
    private const val GOOD_MATCH_DIST = 50f
    private const val MIN_GOOD_MATCHES = 10
    private const val MAX_CANVAS = 9000

    /**
     * @param frames 按扫描顺序排列的 JPEG 字节
     * @return 拼接后的 JPEG，失败返回 null
     */
    fun stitch(frames: List<ByteArray>): ByteArray? {
        if (frames.size < 2) return frames.firstOrNull()
        val mats = mutableListOf<Mat>()
        // 修复：这几个中间 Mat 集合原先都是局部变量、且从不释放（cums 每帧一个 3×3、
        // shift 一个 3×3、transient 若干），统一提升到 try 外便于 finally 收口。
        var cumsRef: MutableList<Mat>? = null
        var transientRef: MutableList<Mat>? = null
        var shiftRef: Mat? = null
        try {
            for (b in frames) {
                val bmp = MatUtils.jpegToBitmap(b) ?: continue
                val mat = MatUtils.bitmapToBgr(bmp); bmp.recycle()
                val scaled = Mat()
                Imgproc.resize(mat, scaled, Size(mat.cols() * 0.5, mat.rows() * 0.5))
                mat.release()
                mats.add(scaled)
            }
            if (mats.size < 2) return null

            val refW = mats[0].cols()
            val refH = mats[0].rows()

            // 逐帧相对单应（当前帧 → 前一帧），再累积到第 0 帧空间
            val cums = mutableListOf<Mat>()
            val transient = mutableListOf<Mat>()   // 需要在本方法结束时统一释放的中间 Mat
            cumsRef = cums
            transientRef = transient
            cums.add(Mat.eye(3, 3, CvType.CV_64F))
            for (i in 1 until mats.size) {
                val h = computeHomography(mats[i - 1], mats[i])
                    ?: Mat.eye(3, 3, CvType.CV_64F)
                // 修复：h 原先是引用计数为 1 的临时 Mat（computeHomography 用 MatOfDouble
                // 转换而来），累计 N 帧后会堆积 N 个 3×3 未释放；统一登记后释放。
                transient.add(h)
                val prev = cums[i - 1]
                val cum = Mat()
                Core.gemm(prev, h, 1.0, Mat(), 0.0, cum)
                cums.add(cum)
            }

            // 画布范围：全部帧四角映射到第 0 帧空间的外接框
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
            for (i in mats.indices) {
                val w = mats[i].cols(); val h = mats[i].rows()
                val src = MatOfPoint2f(
                    Point(0.0, 0.0),
                    Point((w - 1).toDouble(), 0.0),
                    Point((w - 1).toDouble(), (h - 1).toDouble()),
                    Point(0.0, (h - 1).toDouble())
                )
                val dst = MatOfPoint2f()
                Core.perspectiveTransform(src, dst, cums[i])
                for (p in dst.toArray()) {
                    minX = minOf(minX, Math.floor(p.x).toInt())
                    minY = minOf(minY, Math.floor(p.y).toInt())
                    maxX = maxOf(maxX, Math.ceil(p.x).toInt())
                    maxY = maxOf(maxY, Math.ceil(p.y).toInt())
                }
                src.release(); dst.release()
            }
            val offX = -minX
            val offY = -minY
            val canvasW = maxX - minX + 1
            val canvasH = maxY - minY + 1
            if (canvasW <= 0 || canvasH <= 0 ||
                canvasW > MAX_CANVAS || canvasH > MAX_CANVAS
            ) {
                return null
            }

            val shift = Mat.eye(3, 3, CvType.CV_64F)
            shiftRef = shift
            shift.put(0, 2, offX.toDouble())
            shift.put(1, 2, offY.toDouble())

            val canvas = Mat.zeros(canvasH, canvasW, CvType.CV_32FC3)
            val weight = Mat.zeros(canvasH, canvasW, CvType.CV_32FC1)

            for (i in mats.indices) {
                val m = Mat()
                Core.gemm(shift, cums[i], 1.0, Mat(), 0.0, m)

                val warped = Mat()
                Imgproc.warpPerspective(mats[i], warped, m,
                    Size(canvasW.toDouble(), canvasH.toDouble()),
                    Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0))

                val srcMask = Mat(mats[i].size(), CvType.CV_8UC1, Scalar(255.0))
                val dstMask = Mat()
                Imgproc.warpPerspective(srcMask, dstMask, m,
                    Size(canvasW.toDouble(), canvasH.toDouble()),
                    Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0))
                srcMask.release()
                val k = Imgproc.getStructuringElement(
                    Imgproc.MORPH_RECT, Size(9.0, 9.0))
                Imgproc.erode(dstMask, dstMask, k)
                Imgproc.threshold(dstMask, dstMask, 128.0, 255.0, Imgproc.THRESH_BINARY)
                k.release()   // 修复：形态学核每帧都会新建，原先未释放

                val w32 = Mat()
                dstMask.convertTo(w32, CvType.CV_32FC1, 1.0 / 255.0)
                dstMask.release()
                val warped32 = Mat()
                warped.convertTo(warped32, CvType.CV_32FC3)
                warped.release()
                val w3 = Mat()
                Imgproc.cvtColor(w32, w3, Imgproc.COLOR_GRAY2BGR)
                val weighted = Mat()
                Core.multiply(warped32, w3, weighted)
                Core.add(canvas, weighted, canvas)
                Core.add(weight, w32, weight)

                m.release(); w32.release(); warped32.release()
                w3.release(); weighted.release()
            }

            val wSafe = Mat()
            Core.max(weight, Scalar(1e-6), wSafe)
            val w3c = Mat()
            Imgproc.cvtColor(wSafe, w3c, Imgproc.COLOR_GRAY2BGR)
            val avg = Mat()
            Core.divide(canvas, w3c, avg)
            val out8 = Mat()
            avg.convertTo(out8, CvType.CV_8UC3)

            canvas.release(); weight.release(); wSafe.release()
            w3c.release(); avg.release()

            // 拼接结果没有明显大于单帧 → 视为失败
            if (canvasW <= refW + 20 && canvasH <= refH + 20) {
                out8.release()
                return null
            }

            val bmp = MatUtils.bgrToBitmap(out8)
            out8.release()
val bytes = MatUtils.bitmapToJpeg(bmp, 95)
            bmp.recycle()
            return bytes
        } catch (e: Throwable) {
            // 【修复】原来只 catch(Exception)：全景画布是 CV_32FC3，
            // 每个画布像素 12 字节 + 权重图 4 字节，3 帧典型画布就 ~580MB，
            // OutOfMemoryError（Error 不是 Exception）会穿透到线程池直接杀进程。
            // 现在顶层接住 Throwable，转换成"拼接失败"返回 null，由 UI 提示。
            AppLogger.e("Panorama", "拼接失败: ${e.message}", e)
            return null
        } finally {
            mats.forEach { runCatching { it.release() } }
            // 修复：cums（每帧一个 3×3）、shift 与会话内临时 Mat 原先都不释放。
            // 注意 cums/transient/shift 在 try 块内声明，用可空引用在 finally 收口。
            cumsRef?.forEach { runCatching { it.release() } }
            transientRef?.forEach { runCatching { it.release() } }
            runCatching { shiftRef?.release() }
        }
    }

    /** ORB 特征匹配求 H（target → base 空间），失败返回 null */
    private fun computeHomography(base: Mat, target: Mat): Mat? {
        var kpBase: MatOfKeyPoint? = null
        var kpTarget: MatOfKeyPoint? = null
        var descBase: Mat? = null
        var descTarget: Mat? = null
        // 【关键修复】下面这些原来全部不在 finally 里，异常与早退时都会泄漏：
        // grayBase/grayTarget、matches、srcPts/dstPts、orb、matcher、内联 Mat()。
        // 其中 `return null`（特征不足 / good 不足 / findHomography 失败）
        // 是**全景扫描的常规路径**而非异常路径 —— 平移过快或重复纹理时每帧都命中，
        // 所以这是高频泄漏。单次全景 8 帧就是 16 次 align，每次漏 5+ 个 native 对象。
        var grayBase: Mat? = null
        var grayTarget: Mat? = null
        var matches: MatOfDMatch? = null
        var srcPts: MatOfPoint2f? = null
        var dstPts: MatOfPoint2f? = null
        var orb: ORB? = null
        var matcher: BFMatcher? = null
        return try {
            orb = ORB.create(MAX_ORB_FEATURES)
            kpBase = MatOfKeyPoint(); kpTarget = MatOfKeyPoint()
            descBase = Mat(); descTarget = Mat()
            grayBase = Mat(); grayTarget = Mat()
            Imgproc.cvtColor(base, grayBase, Imgproc.COLOR_BGR2GRAY)
            Imgproc.cvtColor(target, grayTarget, Imgproc.COLOR_BGR2GRAY)
            val emptyMask = Mat()
            try {
                orb.detectAndCompute(grayBase, emptyMask, kpBase, descBase)
                orb.detectAndCompute(grayTarget, emptyMask, kpTarget, descTarget)
            } finally {
                emptyMask.release()
            }
            grayBase.release(); grayBase = null
            grayTarget.release(); grayTarget = null
            if (descBase.empty() || descTarget.empty()) return null

            matcher = BFMatcher(Core.NORM_HAMMING, true)
            matches = MatOfDMatch()
            matcher.match(descTarget, descBase, matches)
            val good = matches.toList()
                .filter { it.distance < GOOD_MATCH_DIST }
                .sortedBy { it.distance }.take(300)
            if (good.size < MIN_GOOD_MATCHES) return null

            val kpA = kpTarget.toArray()
            val kpB = kpBase.toArray()
            srcPts = MatOfPoint2f(
                *good.map { kpA[it.queryIdx].pt }.toTypedArray()
            )
            dstPts = MatOfPoint2f(
                *good.map { kpB[it.trainIdx].pt }.toTypedArray()
            )
            val h = Calib3d.findHomography(srcPts, dstPts, Calib3d.RANSAC, 5.0)
            if (h == null || h.empty()) return null
            // 所有权转移给调用方，finally 里不再释放
            val ret = h
            srcPts = null; dstPts = null; matches = null
            ret
        } catch (e: OutOfMemoryError) {
            // 【修复】OOM 必须上抛：全景画布本来就吃内存，静默退化成"相邻帧"会
            // 产出一张完全错位的废图，用户以为"全景坏了"。
            throw e
        } catch (e: Exception) {
            null
        } finally {
            kpBase?.release(); kpTarget?.release()
            descBase?.release(); descTarget?.release()
            grayBase?.release(); grayTarget?.release()
            runCatching { matches?.release() }
            runCatching { srcPts?.release() }
            runCatching { dstPts?.release() }
            runCatching { matcher?.clear() }
            runCatching { orb?.clear() }
        }
    }
}
