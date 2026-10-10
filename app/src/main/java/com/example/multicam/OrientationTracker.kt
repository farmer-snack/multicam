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
    private var lastRawYaw = 0f
    private var firstReading = true

    var onYawChanged: ((Float) -> Unit)? = null

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
