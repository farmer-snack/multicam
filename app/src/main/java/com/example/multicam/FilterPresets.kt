package com.example.multicam

/**
 * 调色预设（蓝图调色盘风格）。
 * 命名参考 vivo 蓝图调色盘的常见风格关键词，参数为本地 LUT 生成所需。
 */
data class FilterPreset(
    val name: String,
    val params: ColorGradeParams
)

object FilterPresets {

    val all: List<FilterPreset> = listOf(
        FilterPreset("原图", ColorGradeParams()),

        FilterPreset(
            "日光胶片",
            ColorGradeParams(
                hueShiftX = 0.15f,
                toneShiftY = 0.10f,
                saturation = 1.15f,
                contrast = 1.08f,
                temperature = 0.20f
            )
        ),

        FilterPreset(
            "清冷银盐",
            ColorGradeParams(
                hueShiftX = -0.20f,
                toneShiftY = -0.05f,
                saturation = 0.80f,
                contrast = 1.15f,
                temperature = -0.25f
            )
        ),

        FilterPreset(
            "复古暖调",
            ColorGradeParams(
                hueShiftX = 0.25f,
                toneShiftY = 0.05f,
                saturation = 1.05f,
                contrast = 0.95f,
                temperature = 0.35f
            )
        ),

        FilterPreset(
            "青橙",
            ColorGradeParams(
                hueShiftX = 0.30f,
                toneShiftY = 0.0f,
                saturation = 1.20f,
                contrast = 1.10f,
                temperature = 0.0f
            )
        ),

        FilterPreset(
            "胶片灰",
            ColorGradeParams(
                hueShiftX = 0f,
                toneShiftY = -0.10f,
                saturation = 0.85f,
                contrast = 0.90f,
                temperature = 0.05f
            )
        ),

        FilterPreset(
            "鲜艳",
            ColorGradeParams(
                hueShiftX = 0f,
                toneShiftY = 0f,
                saturation = 1.35f,
                contrast = 1.12f,
                temperature = 0f
            )
        ),

        FilterPreset(
            "黑白",
            ColorGradeParams(
                hueShiftX = 0f,
                toneShiftY = 0f,
                saturation = 0f,
                contrast = 1.20f,
                temperature = 0f
            )
        )
    )

    fun byName(name: String): FilterPreset? = all.firstOrNull { it.name == name }

    fun byIndex(i: Int): FilterPreset = all.getOrElse(i) { all[0] }
}
