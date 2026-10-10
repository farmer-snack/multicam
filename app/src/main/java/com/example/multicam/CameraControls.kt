package com.example.multicam

/**
 * 相机手动控制参数（vivo 专业模式 / 顶栏开关）。
 * 所有字段都有"自动"语义，0 或不设置表示交给相机的自动逻辑。
 */
data class CameraControls(
    val flashMode: Int = FLASH_OFF,              // 0 关、1 开、2 自动、3 常亮
    val evCompensation: Float = 0f,              // -2..+2
    val isoManual: Int = 0,                      // 0 = 自动
    val shutterNs: Long = 0L,                    // 0 = 自动，单位纳秒
    val wbManual: Boolean = false,               // true = 手动色温
    val wbTemperature: Float = 5000f,            // 色温 K
    val bracket: BracketPlan = BracketPlan.NONE  // 逐帧曝光计划（夜景/星空/慢门/HDR 用）
) {
    companion object {
        const val FLASH_OFF = 0
        const val FLASH_ON = 1
        const val FLASH_AUTO = 2
        const val FLASH_TORCH = 3
    }

    val isManualExposure: Boolean get() = isoManual > 0 && shutterNs > 0L
}
