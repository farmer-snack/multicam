package com.example.multicam

data class ColorGradeParams(
    val hueShiftX: Float = 0f,        // -1..1，负偏青，正偏橙
    val toneShiftY: Float = 0f,       // -1..1，负偏冷暗，正偏暖亮
    val saturation: Float = 1f,       // 0..2
    val contrast: Float = 1f,         // 0.5..1.5
    val temperature: Float = 0f       // -1..1，负偏冷，正偏暖
) {
    val isDefault: Boolean
        get() = hueShiftX == 0f && toneShiftY == 0f &&
                saturation == 1f && contrast == 1f && temperature == 0f
}
