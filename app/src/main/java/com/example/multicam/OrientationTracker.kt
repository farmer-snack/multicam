package com.example.multicam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * 修改 27：陀螺仪朝向跟踪。
 * 用 TYPE_GAME_ROTATION_VECTOR（不需要磁力计，更稳）累积手机相对起点的 yaw（度），
 * 供构图"到位触发变焦/调色"使用。
 */
class OrientationTracker(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? =
        sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    /** 累积 yaw（相对 start 时刻），单位：度 */
    @Volatile private var yaw = 0f
    // 【修复】这两个原来非 volatile 却与主线程 reset() 并发读写，
    // 交错后 delta 会用到半新半旧的 lastRawYaw，多累加出假的角度增量。
    @Volatile private var lastRawYaw = 0f
    @Volatile private var firstReading = true

    /**
     * 设备自身的 roll 倾角（度）。
     *
     * 【新增】SensorManager.getOrientation 返回的 orientation[2] 就是 roll，
     * 这里原本算出来了却只暴露 yaw，导致 SceneAdvisor 只能拿"画面内容里的
     * 水平线倾角"冒充设备倾角去提示用户转手机（场景线歪 ≠ 设备歪）。
     * 现在把真实的设备 roll 暴露出去。
     */
    @Volatile private var roll = 0f

    var onYawChanged: ((Float) -> Unit)? = null

    /** 设备 roll（左右倾斜）变化回调，参数单位：度 */
    var onRollChanged: ((Float) -> Unit)? = null

    /** 设备当前左右倾斜角（度，正=右侧低），用于水平校正提示 */
    fun getRoll(): Float = roll

    fun start() {
        rotationSensor?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    fun getYaw(): Float = yaw

    fun reset() {
        yaw = 0f
        lastRawYaw = 0f
        firstReading = true
        onYawChanged?.invoke(0f)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val t = event.sensor.type
        if (t != Sensor.TYPE_GAME_ROTATION_VECTOR &&
            t != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val rawYaw = Math.toDegrees(orientation[0].toDouble()).toFloat()
        // orientation[2] 是 roll（左右倾斜），归一化到 -180..180
        var rawRoll = Math.toDegrees(orientation[2].toDouble()).toFloat()
        if (rawRoll > 180f) rawRoll -= 360f
        if (rawRoll < -180f) rawRoll += 360f
        roll = rawRoll
        onRollChanged?.invoke(rawRoll)

        if (firstReading) {
            lastRawYaw = rawYaw
            firstReading = false
            return
        }

        var delta = rawYaw - lastRawYaw
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        yaw += delta
        lastRawYaw = rawYaw

        onYawChanged?.invoke(yaw)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
