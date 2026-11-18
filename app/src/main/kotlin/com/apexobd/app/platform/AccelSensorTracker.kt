package com.apexobd.app.platform

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Low-power linear acceleration tracker providing smoothed net G-force (`accel_g`)
 * using Android's fused `TYPE_LINEAR_ACCELERATION` (or high-pass fallback) at ~16Hz.
 */
class AccelSensorTracker(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rawAccelSensor: Sensor? = if (linearSensor == null) sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null

    private val _accelG = MutableStateFlow(0.0)
    val accelG: StateFlow<Double> = _accelG.asStateFlow()

    private val gravity = DoubleArray(3)
    private var smoothedG = 0.0
    private var running = false

    fun start() {
        if (running) return
        val sensor = linearSensor ?: rawAccelSensor ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        running = true
    }

    fun stop() {
        if (!running) return
        sensorManager.unregisterListener(this)
        running = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (ax, ay, az) = if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            Triple(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
        } else {
            val alpha = 0.85
            gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
            gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
            gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]
            Triple(
                event.values[0] - gravity[0],
                event.values[1] - gravity[1],
                event.values[2] - gravity[2]
            )
        }
        // Horizontal magnitude dominates driving accel/braking regardless of phone mount angle
        val horizMs2 = sqrt(ax * ax + ay * ay + az * az * 0.25)
        val rawG = (horizMs2 / 9.80665).coerceIn(0.0, 2.5)
        smoothedG = smoothedG * 0.72 + rawG * 0.28
        _accelG.value = if (smoothedG < 0.015) 0.0 else smoothedG
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
