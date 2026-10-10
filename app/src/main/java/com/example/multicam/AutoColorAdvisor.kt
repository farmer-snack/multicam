package com.example.multicam

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc

/**
 * 修改 24：分析画面直方图和白平衡，自动生成 ColorGradeParams。
 */
object AutoColorAdvisor {

    data class Result(
        val params: ColorGradeParams,
        val label: String   // "阴天暖化" / "逆光提亮" / "高饱和降饱和" ...
    )

    /**
     * @param bgr 单帧 BGR 图像
     */
    fun analyze(bgr: Mat): Result {
        // 1. 平均亮度
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val mean = Core.mean(gray)
        val brightness = mean.`val`[0] / 255.0
        gray.release()

        // 2. 平均色温（R 通道均值 - B 通道均值）
        val chans = ArrayList<Mat>()
        Core.split(bgr, chans)
        val rMean = Core.mean(chans[2]).`val`[0] / 255.0
        val bMean = Core.mean(chans[0]).`val`[0] / 255.0
        chans.forEach { it.release() }
        val colorTemp = (rMean - bMean).toFloat() * 2f   // -2..2

        // 3. 饱和度（HSV S 通道均值）
        val hsv = Mat()
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        val sMean = Core.mean(hsv).`val`[1] / 255.0
        hsv.release()

        // 4. 对比度（灰度标准差 / 128）
        val contrast = computeStdDev(bgr) / 128.0

        // 5. 白平衡系数（保留字段，实际用色温补偿）
        var saturation = 1f
        var contrastP = 1f
        var toneShiftY = 0f
        var label = "默认"

        // 亮度补偿
        if (brightness < 0.35) {
            toneShiftY = ((0.35 - brightness) * 1.5).toFloat().coerceAtMost(0.4f)
            label = "逆光提亮"
        } else if (brightness > 0.75) {
            toneShiftY = -((brightness - 0.75) * 0.8).toFloat()
            label = "高光压下"
        }

        // 饱和度补偿
        if (sMean < 0.15) {
            saturation = 1.25f
            if (label == "默认") label = "增饱和"
        } else if (sMean > 0.55) {
            saturation = 0.85f
            if (label == "默认") label = "降饱和"
        }

        // 对比度补偿
        if (contrast < 0.4) {
            contrastP = 1.15f
            if (label == "默认") label = "提对比"
        }

        // 色温补偿：偏冷则加暖，偏暖则加冷
        val temperature = (-colorTemp * 0.3f).coerceIn(-0.4f, 0.4f)

        val params = ColorGradeParams(
            hueShiftX = 0f,
            toneShiftY = toneShiftY,
            saturation = saturation,
            contrast = contrastP,
            temperature = temperature
        )

        return Result(params, label)
    }

    private fun computeStdDev(bgr: Mat): Double {
        val gray = Mat()
        val mean = MatOfDouble()
        val stddev = MatOfDouble()
        try {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            Core.meanStdDev(gray, mean, stddev)
            return stddev.toArray().firstOrNull() ?: 0.0
        } finally {
            // 修复：MatOfDouble（mean/stddev）与 gray 原先都未释放，
            // 自动调色每次分析都会泄漏 3 个 native Mat，长时间使用会持续吃内存。
            gray.release()
            mean.release()
            stddev.release()
        }
    }
}
