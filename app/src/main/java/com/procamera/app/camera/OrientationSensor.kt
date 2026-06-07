package com.procamera.app.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.roundToInt

/**
 * Listens to the device rotation vector sensor and emits pitch / roll angles in degrees.
 *
 * Pitch  > 0  → tilted toward you (top away)
 * Roll   > 0  → tilted right
 */
class OrientationSensor(context: Context) {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** Invoked on every sensor update with (pitchDeg, rollDeg). */
    var onOrientationChanged: ((pitch: Float, roll: Float) -> Unit)? = null

    private val rotMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            SensorManager.getOrientation(rotMatrix, orientation)
            val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
            val roll  = Math.toDegrees(orientation[2].toDouble()).toFloat()
            onOrientationChanged?.invoke(pitch, roll)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
    }
}
