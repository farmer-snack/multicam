package com.example.multicam

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * 时光慢门处理器（两个子档）。
 *
 * 与夜景/星空的关键区别：慢门是**累积**而不是平均。
 *  - [Style.LIGHT_TRAIL] 光轨：逐帧取**亮部最大值**（`Core.max`）。
 *    车灯扫过的位置在任意一帧里最亮，取 max 就把整条轨迹"画"到最终图上，
 *    等价于一张长曝光照片。适合夜景车流、光绘。
 *  - [Style.SILK] 丝绢：逐帧**渐进平均**（在线均值，非简单 0.5 混合）。
 *    水流的随机波动被平均掉 → 丝绢感；同时不会像 max 那样把噪点也固化下来。
 *
 * 每帧先经 [ImageAligner] 对齐，避免手持期间轨迹断裂或重影。
 */
object LongExposureProcessor {

    private const val TAG = "LongExposure"

    enum class Style(val label: String) {
        LIGHT_TRAIL("光轨"),
        SILK("丝绢")
    }

    /**
     * 慢门合成。
     *
     * @param frames 连拍帧（建议 8～16 帧）
     * @param style 合成风格（光轨 / 丝绢）
     * @param onProgress 进度回调
     */
    fun merge(
        frames: List<ByteArray>,
        style: Style,
        onProgress: ((Int, String) -> Unit)? = null
    ): ByteArray? {
        if (frames.isEmpty()) return null
        if (frames.size == 1) return frames[0]

        val mats = ArrayList<Mat>(frames.size)
        var acc: Mat? = null
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
            if (mats.size == 1) {
                return encode(mats[0])
            }

            // 修复：原来把 mats[0] 同时当作「配准基准」和「累加目标」。
            // 第 2 帧起基准已被写入合成结果，后续所有帧都在与"已合成的图"做配准，
            // 基准逐帧漂移 —— 光轨被 Core.max 强化后尤其明显，表现为轨迹拖尾/重影。
            // 现改为：ref 恒为第 0 帧（只读），acc 为独立的累加目标。
            val ref = mats[0]
            acc = ref.clone()
            onProgress?.invoke(15, "慢门：对齐第 1/${mats.size} 帧")

            // 丝绢专用：累加和 + 有效帧计数（基准帧全有效，计数起步为 1）
            var sum: Mat? = null
            var validCount: Mat? = null
            if (style == Style.SILK) {
                val s = Mat()
                ref.convertTo(s, CvType.CV_32FC3)
                sum = s
                validCount = Mat.ones(ref.size(), CvType.CV_32FC1)
            }

            for (i in 1 until mats.size) {
                val res = ImageAligner.align(ref, mats[i])
                try {
                    when (style) {
                        // 光轨用 max：黑边像素值 0 不会抬高最大值，天然无害
                        Style.LIGHT_TRAIL -> Core.max(acc, res.aligned, acc)
                        // 【关键修复】丝绢必须把 mask 计入，否则黑边把画面拉到近黑。
                        // 原实现直接丢掉 res.mask、用无条件算术平均：
                        //   acc = acc·(n-1)/n + frame·1/n
                        // 对齐产生的黑边（值 0）每轮都把 acc 乘 (n-1)/n，
                        // 8 帧连乘 Π(k-1)/k = 1/8 → **有效区域被拉黑 87.5%**，
                        // 表现为"丝绢四边发黑且越靠边越黑"（光轨正常，
                        // 正好反证 mask 是必需的）。
                        Style.SILK -> accumulateMeanMasked(
                            sum!!, res.aligned, res.mask, validCount!!
                        )
                    }
                } finally {
                    runCatching { res.aligned.release() }
                    runCatching { res.mask.release() }
                }
                onProgress?.invoke(
                    15 + (i * 70) / mats.size,
                    "慢门：合成 ${i + 1}/${mats.size} 帧"
                )
            }
            if (sum != null) maskedAverage(sum, validCount!!, acc)
            sum?.let { runCatching { it.release() } }
            validCount?.let { runCatching { it.release() } }

            onProgress?.invoke(90, "慢门：生成结果")
            return encode(acc)
        } catch (e: Throwable) {
            AppLogger.e(TAG, "慢门合成异常: ${e.message}", e)
            return frames[0]
        } finally {
            mats.forEach { runCatching { it.release() } }
            acc?.let { runCatching { it.release() } }
        }
    }

    /**
     * 在线均值累加：acc 内的值当前是「前 n-1 帧的均值」，
     * 加入第 n 帧后应变成 `(acc*(n-1) + frame) / n`。
     *
     * 用 `addWeighted(acc, (n-1)/n, frame, 1/n)` 一步完成，
     * 不能写成固定的 `addWeighted(0.5,0.5)`——那是指数平均，帧数越多旧帧权重衰减越快，
     * 会让画面末尾几帧主导、轨迹/丝绢效果不稳定。
     */
    private fun accumulateMean(acc: Mat, frame: Mat, n: Int) {
        val wOld = (n - 1).toDouble() / n.toDouble()
        val wNew = 1.0 / n.toDouble()
        Core.addWeighted(acc, wOld, frame, wNew, 0.0, acc)
    }

    /**
     * 带有效区掩膜的在线均值（增量式）。
     *
     * 对齐后的黑边（纯 0）不能参与平均，否则每轮都把累加值乘 (n-1)/n，
     * 有效区域被逐渐拉黑。
     *
     * 维护两张图：
     *   [sum]    —— 逐像素累加和（仅累加有效区像素）
     *   [validCount] —— 每像素实际累加了几帧
     * 结果 = sum / max(validCount, 1)。
     * 这样无效像素既不进分子也不进分母，且结果与帧数无关（不会因
     * 「某像素只参与了一半的帧」而被系统性压暗）。
     *
     * @param sum         累加和（跨帧累积，原地）
     * @param validCount  有效帧计数（跨帧累积，原地）
     */
    private fun accumulateMeanMasked(
        sum: Mat, frame: Mat, mask: Mat, validCount: Mat
    ) {
        // 有效区掩膜 → 归一化单通道浮点（0 = 无效，1 = 有效）
        val m32 = Mat()
        mask.convertTo(m32, CvType.CV_32FC1, 1.0 / 255.0)

        // 三通道加权：只累加有效像素
        val m3 = Mat()
        Imgproc.cvtColor(m32, m3, Imgproc.COLOR_GRAY2BGR)
        val weighted = Mat()
        Core.multiply(frame, m3, weighted)
        Core.add(sum, weighted, sum)
        weighted.release()
        m3.release()

        // 有效帧计数：+ mask
        Core.add(validCount, m32, validCount)
        m32.release()
    }

    /** 由累加和 + 有效帧计数算出均值（无效像素分母取极小值，结果为 0） */
    private fun maskedAverage(sum: Mat, validCount: Mat, out: Mat) {
        val cnt3 = Mat()
        Imgproc.cvtColor(validCount, cnt3, Imgproc.COLOR_GRAY2BGR)
        val denom = Mat()
        Core.max(cnt3, Scalar(1e-3), denom)
        Core.divide(sum, denom, out)
        cnt3.release()
        denom.release()
    }

    private fun encode(mat: Mat): ByteArray? {
        val bmp = MatUtils.bgrToBitmap(mat)
        return try {
            MatUtils.bitmapToJpeg(bmp, 95)
        } finally {
            bmp.recycle()
        }
    }
}
