package com.example.multicam

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
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

            for (i in 1 until mats.size) {
                val res = ImageAligner.align(ref, mats[i])
                try {
                    when (style) {
                        Style.LIGHT_TRAIL -> Core.max(acc, res.aligned, acc)
                        Style.SILK -> accumulateMean(acc, res.aligned, i + 1)
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

            onProgress?.invoke(90, "慢门：生成结果")
            return encode(acc)
        } catch (e: Exception) {
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

    private fun encode(mat: Mat): ByteArray? {
        val bmp = MatUtils.bgrToBitmap(mat)
        return try {
            MatUtils.bitmapToJpeg(bmp, 95)
        } finally {
            bmp.recycle()
        }
    }
}
