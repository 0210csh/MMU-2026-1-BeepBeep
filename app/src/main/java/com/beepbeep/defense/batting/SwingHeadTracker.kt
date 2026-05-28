package com.beepbeep.defense.batting

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.beepbeep.defense.audio.SpatialAudioEngine

class SwingHeadTracker(private val spatialAudio: SpatialAudioEngine) : SensorEventListener {

    private val rotMatrix   = FloatArray(9)
    private val orientation = FloatArray(3)

    var baseAzimuth: Float? = null
    @Volatile var currentHeadingDeg: Float = 0f

    fun reset() { baseAzimuth = null }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
        SensorManager.getOrientation(rotMatrix, orientation)

        val azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
        val isFirstOrientFrame = (baseAzimuth == null)
        if (isFirstOrientFrame) baseAzimuth = azimuthDeg
        var rel = azimuthDeg - (baseAzimuth ?: azimuthDeg)
        while (rel > 180f)  rel -= 360f
        while (rel < -180f) rel += 360f
        currentHeadingDeg = -rel

        spatialAudio.updatePitchRoll(orientation[1], orientation[2])
        if (isFirstOrientFrame) spatialAudio.captureNeutralPitchRoll()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
}
