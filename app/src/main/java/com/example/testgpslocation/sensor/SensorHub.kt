package com.example.testgpslocation.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import com.example.testgpslocation.models.GpsPoint
import com.google.android.gms.location.*
import kotlin.math.*

class SensorHub(private val context: Context) : SensorEventListener {

    interface SensorListener {
        fun onGpsUpdate(point: GpsPoint)
        fun onGpsUnavailable()
        fun onMotionUpdate(distance: Double, speed: Double, heading: Float)
        fun onPressureUpdate(pressure: Float, isRapidChange: Boolean)
    }

    private var listener: SensorListener? = null
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    
    private val linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rotationVec = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var locationCallback: LocationCallback? = null
    private var isTracking = false
    private var lastFineLocationTime = 0L
    private var gpsReportedLost = false

    // Motion State
    var velocity = 0.0; private set
    var distance = 0.0; private set
    var heading = 0f; private set
    var isMoving = false; private set

    // 3D Calibration
    private var forwardAxis = doubleArrayOf(0.0, 1.0, 0.0)
    private var isAxisCalibrated = false
    private val accelHistory = mutableListOf<DoubleArray>()
    private var lastGpsSpeed = 0.0
    private var rotationMatrix = FloatArray(9)

    // Pressure Trend
    private var lastPressure = 0f
    private var pressureHistory = mutableListOf<Float>()

    private var lastAccelTimestamp = 0L
    private var movingStartTime = 0L
    private var stopStartTime = 0L

    companion object {
        private const val MAX_SPEED = 30.0
        private const val DECAY = 0.985
        private const val GPS_TIMEOUT_MS = 15000L
        private const val CALIB_HISTORY_SIZE = 50
        private const val PRESSURE_HISTORY_SIZE = 10
        private const val COARSE_ACCURACY_THRESHOLD = 2000f
        private const val FINE_ACCURACY_THRESHOLD = 150f
    }

    fun setListener(l: SensorListener) { listener = l }

    fun start() {
        if (isTracking) return
        startLocationUpdates()
        linearAccel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        rotationVec?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        pressureSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        isTracking = true
    }

    fun stop() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
        sensorManager.unregisterListener(this)
        isTracking = false
    }

    fun resetMotion() {
        velocity = 0.0; distance = 0.0; lastAccelTimestamp = 0; movingStartTime = 0; stopStartTime = 0; isMoving = false
    }

    fun setInitialVelocity(v: Double) {
        velocity = v.coerceIn(0.0, MAX_SPEED); isMoving = velocity >= 1.0; if (!isMoving) velocity = 0.0
    }

    fun provideGpsSpeed(speed: Double) {
        if (speed > lastGpsSpeed + 0.5 && accelHistory.isNotEmpty()) calibrateAxis()
        lastGpsSpeed = speed
    }

    fun checkGpsStatus() {
        val now = SystemClock.elapsedRealtime()
        if (lastFineLocationTime == 0L || now - lastFineLocationTime > GPS_TIMEOUT_MS) {
            if (!gpsReportedLost) {
                listener?.onGpsUnavailable()
                gpsReportedLost = true
            }
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000).setMinUpdateIntervalMillis(1000).build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(res: LocationResult) {
                val loc = res.lastLocation ?: return
                val isCoarse = loc.accuracy > FINE_ACCURACY_THRESHOLD
                if (loc.accuracy > COARSE_ACCURACY_THRESHOLD) return

                val now = SystemClock.elapsedRealtime()
                if (isCoarse && now - lastFineLocationTime < 5000) return
                
                if (!isCoarse) {
                    lastFineLocationTime = now
                    gpsReportedLost = false
                }
                
                val point = GpsPoint(loc.latitude, loc.longitude, loc.accuracy, loc.time, if (loc.hasSpeed()) loc.speed else 0f, if (loc.hasBearing()) loc.bearing else 0f, isCoarse)
                listener?.onGpsUpdate(point)
            }
        }
        fusedLocationClient.requestLocationUpdates(req, locationCallback!!, Looper.getMainLooper())
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> handleAccel(event)
            Sensor.TYPE_ROTATION_VECTOR -> handleRotation(event)
            Sensor.TYPE_PRESSURE -> handlePressure(event)
        }
    }

    private fun handleAccel(event: SensorEvent) {
        val ts = event.timestamp
        if (lastAccelTimestamp == 0L) { lastAccelTimestamp = ts; return }
        val dt = (ts - lastAccelTimestamp) / 1_000_000_000.0
        lastAccelTimestamp = ts
        if (dt <= 0 || dt > 1.0) return
        val ax = event.values[0].toDouble(); val ay = event.values[1].toDouble(); val az = event.values[2].toDouble()
        
        // Calibration accumulation using monotonic time for fine location age check
        val fineLocationAge = SystemClock.elapsedRealtime() - lastFineLocationTime
        if (!isAxisCalibrated || fineLocationAge < 10000) {
            accelHistory.add(doubleArrayOf(ax, ay, az))
            if (accelHistory.size > CALIB_HISTORY_SIZE) accelHistory.removeAt(0)
        }

        val mag = sqrt(ax * ax + ay * ay + az * az)
        val signedAccel = if (isAxisCalibrated) projectAccel(ax, ay, az) else mag

        val now = SystemClock.elapsedRealtime()
        if (mag > 0.35) { if (movingStartTime == 0L) movingStartTime = now; if (now - movingStartTime >= 1500) isMoving = true } else movingStartTime = 0
        if (mag < 0.15 && velocity < 0.5) { if (stopStartTime == 0L) stopStartTime = now; if (now - stopStartTime >= 3000) { velocity = 0.0; isMoving = false } } else stopStartTime = 0
        if (isMoving) { 
            velocity = (velocity + signedAccel * dt) * DECAY
            velocity = velocity.coerceIn(-MAX_SPEED, MAX_SPEED)
            distance += abs(velocity) * dt 
        }
        listener?.onMotionUpdate(distance, velocity, heading)
    }

    private fun handleRotation(event: SensorEvent) {
        val oldRotation = rotationMatrix.copyOf()
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        if (isAxisCalibrated && oldRotation[0] != 0f) {
            val dot = rotationMatrix[0]*oldRotation[0] + rotationMatrix[4]*oldRotation[4] + rotationMatrix[8]*oldRotation[8]
            if (dot < 0.96) { isAxisCalibrated = false; accelHistory.clear() }
        }
        updateWorldHeading()
        listener?.onMotionUpdate(distance, velocity, heading)
    }

    private fun handlePressure(event: SensorEvent) {
        val p = event.values[0]
        if (lastPressure == 0f) { lastPressure = p; return }
        val diff = abs(p - lastPressure)
        pressureHistory.add(p)
        if (pressureHistory.size > PRESSURE_HISTORY_SIZE) pressureHistory.removeAt(0)
        val isRapid = diff > 0.4f
        listener?.onPressureUpdate(p, isRapid)
        lastPressure = p
    }

    private fun projectAccel(ax: Double, ay: Double, az: Double) = (ax * forwardAxis[0] + ay * forwardAxis[1] + az * forwardAxis[2])

    private fun calibrateAxis() {
        if (accelHistory.isEmpty()) return
        val avg = doubleArrayOf(0.0, 0.0, 0.0)
        accelHistory.forEach { avg[0] += it[0]; avg[1] += it[1]; avg[2] += it[2] }
        val norm = sqrt(avg[0].pow(2) + avg[1].pow(2) + avg[2].pow(2))
        if (norm > 0.1) {
            forwardAxis[0] = avg[0] / norm; forwardAxis[1] = avg[1] / norm; forwardAxis[2] = avg[2] / norm
            isAxisCalibrated = true
        }
    }

    private fun updateWorldHeading() {
        val worldX = rotationMatrix[0] * forwardAxis[0] + rotationMatrix[1] * forwardAxis[1] + rotationMatrix[2] * forwardAxis[2]
        val worldY = rotationMatrix[3] * forwardAxis[0] + rotationMatrix[4] * forwardAxis[1] + rotationMatrix[5] * forwardAxis[2]
        var h = Math.toDegrees(atan2(worldX, worldY)).toFloat()
        if (h < 0) h += 360f
        heading = h
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
