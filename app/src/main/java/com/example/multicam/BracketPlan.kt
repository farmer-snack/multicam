package com.example.multicam

/**
 * 逐帧曝光计划：把"第 i 帧用什么曝光"抽象出来，供夜景 / 星空 / 时光慢门 / HDR 复用。
 *
 * - [evSteps]         相对基准的 EV 偏移（用于 AE 自动曝光模式下的包围，如 HDR）
 * - [isoSteps]        手动 ISO 序列（与 [shutterNsSteps] 配合时走 AE_OFF）
 * - [shutterNsSteps]  手动快门序列，单位纳秒
 * - [frameCount]      实际要采集的帧数（>= 各数组长度时序列循环取值）
 *
 * 三者都为空 = 普通固定曝光连拍（保持旧行为，不影响已有拍摄路径）。
 */
data class BracketPlan(
    val evSteps: FloatArray = FloatArray(0),
    val isoSteps: IntArray = IntArray(0),
    val shutterNsSteps: LongArray = LongArray(0),
    val frameCount: Int = 1
) {
    /** 是否为包围曝光（任一序列非空） */
    val isBracketed: Boolean
        get() = evSteps.isNotEmpty() || isoSteps.isNotEmpty() || shutterNsSteps.isNotEmpty()

    /** 第 i 帧的 EV 偏移；序列为空返回 null（表示沿用全局 controls 的值） */
    fun evAt(i: Int): Float? =
        if (evSteps.isEmpty()) null else evSteps[i % evSteps.size]

    /** 第 i 帧的手动 ISO；序列为空返回 null */
    fun isoAt(i: Int): Int? =
        if (isoSteps.isEmpty()) null else isoSteps[i % isoSteps.size]

    /** 第 i 帧的手动快门（ns）；序列为空返回 null */
    fun shutterAt(i: Int): Long? =
        if (shutterNsSteps.isEmpty()) null else shutterNsSteps[i % shutterNsSteps.size]

    /** 生效帧数（至少 1） */
    val effectiveFrames: Int get() = frameCount.coerceAtLeast(1)

    companion object {
        val NONE = BracketPlan()
    }

    // FloatArray/LongArray/IntArray 是引用类型，data class 默认 equals 按引用比较，这里显式实现
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BracketPlan) return false
        return evSteps.contentEquals(other.evSteps) &&
            isoSteps.contentEquals(other.isoSteps) &&
            shutterNsSteps.contentEquals(other.shutterNsSteps) &&
            frameCount == other.frameCount
    }

    override fun hashCode(): Int {
        var result = evSteps.contentHashCode()
        result = 31 * result + isoSteps.contentHashCode()
        result = 31 * result + shutterNsSteps.contentHashCode()
        result = 31 * result + frameCount
        return result
    }
}
