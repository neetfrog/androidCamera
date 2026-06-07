package com.procamera.app.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
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

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

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

            val remappedMatrix = FloatArray(9)
            when (windowManager.defaultDisplay.rotation) {
                Surface.ROTATION_0 -> SensorManager.remapCoordinateSystem(
                    rotMatrix,
                    SensorManager.AXIS_X,
                    SensorManager.AXIS_Y,
                    remappedMatrix
                )
                Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                    rotMatrix,
                    SensorManager.AXIS_Y,
                    SensorManager.AXIS_MINUS_X,
                    remappedMatrix
                )
                Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                    rotMatrix,
                    SensorManager.AXIS_MINUS_X,
                    SensorManager.AXIS_MINUS_Y,
                    remappedMatrix
                )
                Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                    rotMatrix,
                    SensorManager.AXIS_MINUS_Y,
                    SensorManager.AXIS_X,
                    remappedMatrix
                )
                else -> System.arraycopy(rotMatrix, 0, remappedMatrix, 0, rotMatrix.size)
            }

            SensorManager.getOrientation(remappedMatrix, orientation)
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
