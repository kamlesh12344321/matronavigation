package com.example.trainbox.engine

import android.content.Context
import android.os.SystemClock
import com.example.testgpslocation.models.GpsPoint
import com.example.testgpslocation.utils.GeoToolbox
import kotlin.math.*

class TrainTrackingEngine(private val context: Context) {

    interface TrainEngineListener {
        fun onTelemetryUpdated(distanceMeters: Double, speedMps: Double, isStationStop: Boolean, heading: Float)
        fun onStationDetected(stationName: String)
    }

    private var listener: TrainEngineListener? = null

    var currentLatitude: Double = 0.0; private set
    var currentLongitude: Double = 0.0; private set
    var currentSpeedMps: Double = 0.0; private set
    var accumulatedDistanceMeters: Double = 0.0; private set
    var currentHeading: Float = 0f; private set
    var isStationStop: Boolean = false; private set

    private var lastTimestamp: Long = 0L
    private var stationaryDurationMs: Long = 0L
    private var lastStationaryTime: Long = 0L

    // ZUPT parameters
    private val STATIONARY_ACCEL_THRESHOLD = 0.12 // m/s^2 magnitude variance / threshold
    private val STATIONARY_GYRO_THRESHOLD = 0.02 // rad/s
    private val ZUPT_TIME_WINDOW_MS = 1000L // 1 second of stillness triggers ZUPT

    fun setListener(l: TrainEngineListener) {
        listener = l
    }

    fun initializePosition(lat: Double, lon: Double, heading: Float) {
        currentLatitude = lat
        currentLongitude = lon
        currentHeading = heading
        accumulatedDistanceMeters = 0.0
        currentSpeedMps = 0.0
    }

    /**
     * 1D-Constrained Rail Kinematic Dead Reckoning with ZUPT integration
     */
    fun processSensorData(linearAccelX: Double, linearAccelY: Double, linearAccelZ: Double, gyroZ: Double, timestampNs: Long) {
        if (lastTimestamp == 0L) {
            lastTimestamp = timestampNs
            return
        }
        val dt = (timestampNs - lastTimestamp) / 1_000_000_000.0
        lastTimestamp = timestampNs
        if (dt <= 0.0 || dt > 1.0) return

        // 1D Constrained Rail acceleration projection along track heading
        val accelMagnitude = sqrt(linearAccelX * linearAccelX + linearAccelY * linearAccelY + linearAccelZ * linearAccelZ)
        val gyroMagnitude = abs(gyroZ)

        // ZUPT (Zero-Velocity Update) Detection
        if (accelMagnitude < STATIONARY_ACCEL_THRESHOLD && gyroMagnitude < STATIONARY_GYRO_THRESHOLD) {
            if (lastStationaryTime == 0L) lastStationaryTime = SystemClock.elapsedRealtime()
            stationaryDurationMs = SystemClock.elapsedRealtime() - lastStationaryTime
            
            if (stationaryDurationMs >= ZUPT_TIME_WINDOW_MS) {
                // ZUPT Triggered: Zero out velocity and accumulated drift
                currentSpeedMps = 0.0
                isStationStop = true
                listener?.onStationDetected("Stationary / ZUPT Lock")
            }
        } else {
            lastStationaryTime = 0L
            stationaryDurationMs = 0L
            isStationStop = false
        }

        // Kinematic integration along rail direction (1D constrained)
        if (!isStationStop) {
            val forwardAccel = linearAccelY // Assuming Y axis aligned with rail direction
            currentSpeedMps += forwardAccel * dt
            currentSpeedMps = currentSpeedMps.coerceIn(-40.0, 40.0) // Rail speed limits
            
            val distanceStep = abs(currentSpeedMps) * dt
            accumulatedDistanceMeters += distanceStep

            // Update coordinate along track using GeoToolbox moveLocation
            val newCoords = GeoToolbox.moveLocation(currentLatitude, currentLongitude, distanceStep, currentHeading.toDouble())
            currentLatitude = newCoords.first
            currentLongitude = newCoords.second
        } else {
            currentSpeedMps = 0.0
        }

        listener?.onTelemetryUpdated(accumulatedDistanceMeters, currentSpeedMps, isStationStop, currentHeading)
    }

    fun handleGpsCorrection(gpsPoint: GpsPoint) {
        if (isStationStop || gpsPoint.accuracy < 20f) {
            currentLatitude = gpsPoint.latitude
            currentLongitude = gpsPoint.longitude
            if (gpsPoint.bearingDegree != 0f) {
                currentHeading = gpsPoint.bearingDegree
            }
        }
    }
}
