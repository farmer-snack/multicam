package com.example.multicam

/**
 * 把 ColorGradeParams 转成 512×512 的 RGBA LUT（8×8 网格 × 64 层蓝色切片），
 * 与 GpuPreviewRenderer 的 3D LUT 采样约定一致。
 */
object LutBuilder {

    private const val SIZE = 64
    private const val TILE = 8
    private const val IMG_SIZE = SIZE * TILE

    /**
     * 【关键修复】消除 build() 内的逐像素分配。
     * 原来 `transform()` 用 `return floatArrayOf(cr,cg,cb)`，每次调用分配一个
     * 3 元素 FloatArray —— 64³ = 262144 次调用 ≈ **每次 build 8.4MB 垃圾**。
     * 拖动调色滑块/色盘时 onProgressChanged 每帧触发（高刷屏 120Hz），
     * ≈ 1GB/s 垃圾分配率 → GC 抖动、预览掉帧、触摸"粘手"。
     * 现在结果直接写 ByteArray，build() 内零临时对象。
     */

    /**
     * 生成 LUT。
     *
     * 【关键修复】现在与 [ColorGrader.apply] 用**完全相同的算子顺序**：
     *   **sat → temp → hue → (contrast + tone)**
     *
     * 原来的 transform() 顺序是 temp → hue → tone → sat → contrast，
     * 而 ColorGrader 的矩阵连乘 hueM·tempM·satM 实际作用顺序是 sat → temp → hue ——
     * 饱和度与色温/色调不可交换，两者必然不一致，
     * 表现为「实时预览的滤镜效果和按快门存出来的 JPEG 明显不一样」。
     *
     * 注意 hueM / tempM 的交叉项形式与 ColorGrader 完全一致：
     *   tempM: R 通道 ×(1+temp*0.1)，B 通道 ×(1-temp*0.1)
     *   hueM : R 通道 + hueR*0.15*B，B 通道 + hueB*0.15*R（R/B 对角相加）
     * toneOffset 与 ColorGrader 一致取 tone*64.0（0..255 像素尺度）
     */
    fun build(p: ColorGradeParams): ByteArray {
        val out = ByteArray(IMG_SIZE * IMG_SIZE * 4) { 255.toByte() }
        buildInto(p, out)
        return out
    }

    private fun buildInto(p: ColorGradeParams, data: ByteArray) {
        val sat = p.saturation
        val ctr = p.contrast
        val tempK = p.temperature * 0.1f
        val hueR = p.hueShiftX * 0.15f
        val hueB = -p.hueShiftX * 0.15f
        // 与 ColorGrader 一致：0..255 像素尺度
        val tonePx = p.toneShiftY * 64.0f
        val bOff = (0.5f * (1 - ctr) * 255.0f + tonePx)

        // 亮度权重（Rec.709），与 ColorGrader 的 lr/lg/lb 一致
        val lr = 0.2126f
        val lg = 0.7152f
        val lb = 0.0722f

        val tempR = 1f + tempK
        val tempB = 1f - tempK
        // 色调系数：与 ColorGrader 的 hueM * 0.15 完全一致
        val hueRk = hueR
        val hueBk = hueB

        for (bi in 0 until SIZE) {
            val tileX = bi % TILE
            val tileY = bi / TILE
            // 以 0..255 像素尺度运算，与 ColorGrader/Core.transform 同域
            val srcB = bi / (SIZE - 1f) * 255f
            for (gi in 0 until SIZE) {
                val srcG = gi / (SIZE - 1f) * 255f
                for (ri in 0 until SIZE) {
                    val srcR = ri / (SIZE - 1f) * 255f

                    // --- 1. 饱和度（等价 ColorGrader 的 satM）---
                    val lum = lr * srcR + lg * srcG + lb * srcB
                    var vR = lum + (srcR - lum) * sat
                    var vG = lum + (srcG - lum) * sat
                    var vB = lum + (srcB - lum) * sat

                    // --- 2. 色温（等价 tempM 对角缩放）---
                    vR *= tempR
                    vB *= tempB

                    // --- 3. 色调（等价 hueM）---
                    // 【关键】矩阵乘法是**同时**作用：out = M·x，每个输出分量
                    // 都用同一份输入 x。所以 B 的修正项必须用**更新前**的 R，
                    // 不能用刚被加过的 R —— 否则 R↔B 会串行累积，
                    // 强色调时出现明显偏色，且与成片对不上。
                    val rIn = vR
                    val outR = rIn + hueRk * vB
                    val outB = hueBk * rIn + vB
                    vR = outR
                    vB = outB

                    // --- 4. 对比度 + 影调（等价 ColorGrader 的 m*ctr + b）---
                    vR = (vR - 0.5f * 255f) * ctr + bOff
                    vG = (vG - 0.5f * 255f) * ctr + bOff
                    vB = (vB - 0.5f * 255f) * ctr + bOff

                    val px = (tileX * SIZE + ri) + (tileY * SIZE + gi) * IMG_SIZE
                    val idx = px * 4
                    // 【修复】原来用 toInt()：对正数是**向零截断**（127.9 → 127），
                    // 每通道系统性偏暗最多 1；而 OpenCV 的 saturate_cast 是四舍五入。
                    // LUT 整体偏暗会在预览里表现为一层"蒙灰"。
                    data[idx] = Math.round(vR).coerceIn(0, 255).toByte()
                    data[idx + 1] = Math.round(vG).coerceIn(0, 255).toByte()
                    data[idx + 2] = Math.round(vB).coerceIn(0, 255).toByte()
                    data[idx + 3] = 255.toByte()
                }
            }
        }
    }
}
