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

        /**
         * 【关键修复】统一算子顺序，并让 [LutBuilder] 与本实现完全一致。
         *
         * 矩阵连乘是**右到左**作用：m2 = hueM · tempM · satM
         * 意味着实际顺序是 **sat → temp → hue**（饱和度最先、最后才是色调）。
         * 而原来的 LutBuilder.transform 走的是 temp → hue → tone → sat → contrast，
         * 两者完全不同 —— 饱和度与色温/色调**不可交换**，
         * 于是实时预览（GL LUT）与最终成片（这里）必然不一致，
         * 表现为"预览里看到的颜色和拍出来的不是一回事"。
         *
         * 现在两处统一为：**sat → temp → hue → (contrast + tone)**。
         * LutBuilder 已同步改成同样的顺序（见其 buildInto 的注释）。
         */
        val m1 = multiply3x3(tempM, satM)   // sat 先作用
        val m2 = multiply3x3(hueM, m1)      // 再 temp，最后 hue

        // 应用对比度：out = (in - 0.5) * ctr + 0.5   —— 归一化尺度，需 ×255 换到像素尺度
        for (i in m2.indices) m2[i] *= ctr
        val b = (0.5 * (1 - ctr) * 255.0 + toneOffset).toFloat()

        // 3x4 矩阵：行主序布局，下标 3 / 7 / 11 是「逐通道偏移」槽。
        //
        // 【关键修复】原来是：
        //     m2.copyInto(data)          // m2[0..8] → data[0..8]
        //     data[9] = b; data[10] = b; data[11] = b
        // 偏移列被写到了 9 / 10 / 11，而正确位置是 **3 / 7 / 11**。
        // 后果是双重破坏：
        //   1. data[9]/data[10] 正好落在 m[2][1] / m[2][2] —— 红输出通道的
        //      绿/蓝线性系数被 b 覆盖掉（b=0 时两个系数直接变成 0）；
        //   2. m2[7] 落到了偏移槽 m[1][3]，m2[8] 落到了 m[2][0]。
        // 代入单位矩阵验算：out_R = m2[8]·B + b·G + b·R + b = 1·B + 0 + 0 + 0 = B
        // → **红通道整个变成蓝通道**，肤色发青、天空发绿，是全局性严重偏色。
        //
        // 正确写法：显式按下标摆放，不依赖 copyInto 的连续假设。
        val out = Mat()
        val transform = Mat(3, 4, CvType.CV_32F)
        val data = FloatArray(12)
        data[0] = m2[0]; data[1] = m2[1]; data[2] = m2[2]; data[3] = b
        data[4] = m2[3]; data[5] = m2[4]; data[6] = m2[5]; data[7] = b
        data[8] = m2[6]; data[9] = m2[7]; data[10] = m2[8]; data[11] = b
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
