package com.example.multicam

/**
 * 把 ColorGradeParams 转成 512×512 的 RGBA LUT（8×8 网格 × 64 层蓝色切片），
 * 与 GpuPreviewRenderer 的 3D LUT 采样约定一致。
 */
object LutBuilder {

    fun build(p: ColorGradeParams): ByteArray {
        val size = 64
        val tile = 8
        val imgSize = size * tile

        val data = ByteArray(imgSize * imgSize * 4) { 255.toByte() }

        for (b in 0 until size) {
            val tileX = b % tile
            val tileY = b / tile
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val srcR = r / (size - 1f)
                    val srcG = g / (size - 1f)
                    val srcB = b / (size - 1f)

                    val out = transform(srcR, srcG, srcB, p)

                    val px = (tileX * size + r) + (tileY * size + g) * imgSize
                    val idx = px * 4
                    data[idx] = (out[0] * 255).toInt().coerceIn(0, 255).toByte()
                    data[idx + 1] = (out[1] * 255).toInt().coerceIn(0, 255).toByte()
                    data[idx + 2] = (out[2] * 255).toInt().coerceIn(0, 255).toByte()
                    data[idx + 3] = 255.toByte()
                }
            }
        }
        return data
    }

    /** 与 ColorGrader 的 3×4 矩阵同语义（色温→色调→影调→饱和度→对比度） */
    private fun transform(r: Float, g: Float, b: Float, p: ColorGradeParams): FloatArray {
        // 色温：R 升 B 降
        val tr = (r * (1f + p.temperature * 0.1f)).coerceIn(0f, 1f)
        val tb = (b * (1f - p.temperature * 0.1f)).coerceIn(0f, 1f)

        // 色调偏移（R/B 对角相加）
        val hueR = (tr + p.hueShiftX * 0.15f * b).coerceIn(0f, 1f)
        val hueB = (tb - p.hueShiftX * 0.15f * r).coerceIn(0f, 1f)

        // 影调偏移（0-1 空间）
        // 修复（2026-10）：与 [ColorGrader] 的影调强度对齐。
        // ColorGrader 在 0..255 像素尺度上取 `tone * 64.0`，换算到本函数的 0..1 空间
        // 应为 `tone * 64f / 255f`。原实现写的是 `tone * 25f / 255f`，导致同一个
        // toneShiftY 在实时 LUT 预览与最终成片里强度相差约 2.5 倍 —— 所见非所得。
        val tone = p.toneShiftY * 64f / 255f
        val tr2 = (hueR + tone).coerceIn(0f, 1f)
        val tg2 = (g + tone).coerceIn(0f, 1f)
        val tb2 = (hueB + tone).coerceIn(0f, 1f)

        // 饱和度
        val gray = 0.2126f * tr2 + 0.7152f * tg2 + 0.0722f * tb2
        val sr = (gray + (tr2 - gray) * p.saturation).coerceIn(0f, 1f)
        val sg = (gray + (tg2 - gray) * p.saturation).coerceIn(0f, 1f)
        val sb = (gray + (tb2 - gray) * p.saturation).coerceIn(0f, 1f)

        // 对比度
        val cr = ((sr - 0.5f) * p.contrast + 0.5f).coerceIn(0f, 1f)
        val cg = ((sg - 0.5f) * p.contrast + 0.5f).coerceIn(0f, 1f)
        val cb = ((sb - 0.5f) * p.contrast + 0.5f).coerceIn(0f, 1f)

        return floatArrayOf(cr, cg, cb)
    }
}
