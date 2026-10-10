package com.example.multicam

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

object ColorGrader {

    /**
 * 根据调色盘参数生成色彩矩阵，应用到 BGR Mat。
     *
     * 修复（2026-10）：`p.isDefault` 时**必须返回 input 的副本**。
     * 原实现直接 `return input`，调用方 `CameraController.applyColorGrade` 随后
     * `graded.release()` 会释放掉调用方还在用的 `mat`（甚至可能是上游共享的 Mat），
     * 造成 native 层 use-after-free 崩溃 / 花屏。统一改为返回 clone。
     */
    fun apply(input: Mat, p: ColorGradeParams): Mat {
        if (p.isDefault) return input.clone()

        // 从参数推导 3x3 色彩矩阵
        val hueR = p.hueShiftX
        val hueB = -p.hueShiftX
        val tone = p.toneShiftY
        val sat = p.saturation
        val ctr = p.contrast
        val temp = p.temperature

        // 饱和度矩阵
        val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
        val sr = (1 - sat) * lr
        val sg = (1 - sat) * lg
        val sb = (1 - sat) * lb

        val satM = floatArrayOf(
            sr + sat, sg, sb,
            sr, sg + sat, sb,
            sr, sg, sb + sat
        )

        // 色调偏移（近似：R 和 B 通道轻微对角相加）
        val hueM = floatArrayOf(
            1f, 0f, hueR * 0.15f,
            0f, 1f, 0f,
            hueB * 0.15f, 0f, 1f
        )

        // 色温（R 升 B 降 / 反之）
        val tempR = 1f + temp * 0.1f
        val tempB = 1f - temp * 0.1f
        val tempM = floatArrayOf(
            tempR, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, tempB
        )

        // 影调偏移
        // 修复（2026-10）：Core.transform 的加性项作用在 0..255 的像素尺度上，
        // 原实现 tone*25.0 在 0..255 尺度下只有 0..25/255 ≈ 10% 的可见度，
        // 且与对比度的 0.5（归一化尺度）混用导致影调几乎失效。改为 0..255 尺度基准。
        val toneOffset = tone * 64.0

        // 三个矩阵相乘：hueM * tempM * satM
        val m1 = multiply3x3(hueM, tempM)
        val m2 = multiply3x3(m1, satM)

        // 应用对比度：out = (in - 0.5) * ctr + 0.5   —— 归一化尺度，需 ×255 换到像素尺度
        for (i in m2.indices) m2[i] *= ctr
        val b = (0.5 * (1 - ctr) * 255.0 + toneOffset).toFloat()

        // 3x4 矩阵：前 3 列做色彩线性变换，第 4 列为逐通道偏移
        val out = Mat()
        val transform = Mat(3, 4, CvType.CV_32F)
        val data = FloatArray(12)
        m2.copyInto(data)
        data[9] = b; data[10] = b; data[11] = b
        transform.put(0, 0, data)
        Core.transform(input, out, transform)
        transform.release()
        return out
    }

    private fun multiply3x3(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(9)
        for (i in 0..2) for (j in 0..2) {
            var s = 0f
            for (k in 0..2) s += a[i * 3 + k] * b[k * 3 + j]
            r[i * 3 + j] = s
        }
        return r
    }
}
