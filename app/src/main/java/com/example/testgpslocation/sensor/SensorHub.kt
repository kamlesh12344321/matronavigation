package com.example.testgpslocation.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.testgpslocation.engine.MotionSample
import com.example.testgpslocation.engine.MotionState
import com.example.testgpslocation.models.GpsPoint
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.max
import kotlin.math.sqrt

/** What this phone can do; shown to the user when something important is missing. */
data class DeviceCapabilities(
    val hasGnss: Boolean,
    val hasPlayServices: Boolean,
    val hasLinearAcceleration: Boolean,
    val hasAccelerometer: Boolean,
    val hasRotationVector: Boolean,
    val hasGyroscope: Boolean,
    val hasBarometer: Boolean
)

/**
 * Location + IMU front end. All callbacks arrive on the main thread.
 *
 * Location sources:
 *  - the satellite receiver (LocationManager GPS provider) → fixes marked isGnss = true;
 *  - Google fused location (cell / Wi-Fi / GPS mix) → isGnss = false, used when there is no
 *    satellite fix (e.g. underground with network);
 *  - the platform network provider as the backup: used while fused sends nothing;
 *  - with no position at all once GPS counts as lost, the phone's last known location.
 * A GPS provider fix with no satellite in use is the chip's own estimate, not a satellite fix
 * (an S24 sent them for 50 s claiming ±5 m): marked isChipEstimate, isGnss = false. See
 * [ChipEstimateCheck].
 * "GPS lost" fires when no satellite fix arrived for [GPS_TIMEOUT_MS]; the navigator also treats
 * a run of only poor fixes as lost.
 *
 * Motion: one [MotionSample] every [SAMPLE_PERIOD_MS] with world-frame acceleration and a
 * stationary / walking / moving classification. Falls back to the raw accelerometer (gravity
 * removed with a low-pass filter) on phones without a linear-acceleration sensor.
 *
 * Barometer: height change since the sensors started, for the log only (position never uses it).
 * Steps: the phone's own step detector, for following someone on foot without GPS.
 */
class SensorHub(context: Context) : SensorEventListener {

    interface Listener {
        fun onLocationFix(point: GpsPoint)
        fun onGpsLost(elapsedMs: Long)
        fun onMotionSample(sample: MotionSample)
        fun onSatellites(inView: Int, usedInFix: Int) {}
        fun onLastKnownFix(point: GpsPoint) {}
    }

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private val linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rawAccel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val pressure = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val hasGnss = try { locationManager.allProviders.contains(LocationManager.GPS_PROVIDER) } catch (_: Exception) { false }
    private val hasPlayServices = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS
    } catch (_: Exception) { false }
    private val fusedClient: FusedLocationProviderClient? =
        if (hasPlayServices) LocationServices.getFusedLocationProviderClient(appContext) else null

    val capabilities = DeviceCapabilities(
        hasGnss = hasGnss,
        hasPlayServices = hasPlayServices,
        hasLinearAcceleration = linearAccel != null,
        hasAccelerometer = rawAccel != null,
        hasRotationVector = rotationVector != null,
        hasGyroscope = gyroscope != null,
        hasBarometer = pressure != null
    )

    var listener: Listener? = null
    var isRunning = false; private set

    private var lastGnssFixMs = 0L
    private var gpsLostReported = false
    private val chipCheck = ChipEstimateCheck()
    private var lastFusedFixMs = 0L
    private var stepSensorOn = false
    private var startedAtMs = 0L

    // Orientation (device -> world)
    private val rotation = FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f }
    private var hasRotation = false
    private val gravity = FloatArray(3)
    private var gravityInitialised = false

    // Current window accumulators
    private var windowStartMs = 0L
    private var sumEast = 0.0
    private var sumNorth = 0.0
    private var windowCount = 0
    private var stepsInWindow = 0

    // Rolling buffers for classification
    private val accelMagnitudes = RingBuffer(ROLLING_SAMPLES)
    private val gyroMagnitudes = RingBuffer(ROLLING_SAMPLES)

    // Logged only, to judge after a ride whether they show stops better
    private val magneticMagnitudes = RingBuffer(ROLLING_SAMPLES)
    private val accelChanges = RingBuffer(ROLLING_SAMPLES)
    private var lastAccelMagnitude = Double.NaN
    /** Spread of the magnetic field over ~2 s (µT): train motors should disturb it, a standing train less. */
    val magneticSpread: Double get() = magneticMagnitudes.std() ?: Double.NaN
    /** Spread of the change from sample to sample (m/s²): rails shake fast, a hand slowly. */
    val fastVibration: Double get() = accelChanges.std() ?: Double.NaN

    private var stillSinceMs = 0L
    // Turn rate about the vertical (gravity) axis: magnetometer independent
    private var yawSum = 0.0
    private var yawCount = 0

    /** Steps since the sensors started. */
    var stepCount = 0; private set

    // Barometer: height change since the sensors started (metres), null without a barometer
    private var startHeightMeters = Float.NaN
    private var lastPressureMs = 0L
    /** Last barometer reading (hPa), for the log. */
    var lastPressureHpa = Float.NaN; private set
    var heightChangeMeters: Double? = null; private set

    private val gpsWatchdog = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val reference = if (lastGnssFixMs == 0L) startedAtMs else lastGnssFixMs
            if (!gpsLostReported && now - reference > GPS_TIMEOUT_MS) {
                gpsLostReported = true
                listener?.onGpsLost(now)
                if (lastGnssFixMs == 0L) offerLastKnown()
            }
            handler.postDelayed(this, 1000L)
        }
    }

    private val gnssListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val estimate = chipCheck.isEstimate(isMock(location), SystemClock.elapsedRealtime())
            deliver(location, isGnss = !estimate, chipEstimate = estimate)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val networkListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // The backup: fused normally gives the same position, more often.
            if (SystemClock.elapsedRealtime() - lastFusedFixMs > FUSED_SILENT_MS) deliver(location, isGnss = false)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            lastFusedFixMs = SystemClock.elapsedRealtime()
            result.lastLocation?.let {
                val echo = chipCheck.estimatesRunning(lastFusedFixMs)
                // Without a satellite receiver, very accurate fused fixes are the best we have.
                deliver(it, isGnss = !echo && !hasGnss && it.accuracy <= NO_GNSS_TRUSTED_ACCURACY, chipEstimate = echo)
            }
        }
    }

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            chipCheck.onSatelliteStatus(used, SystemClock.elapsedRealtime())
            listener?.onSatellites(status.satelliteCount, used)
        }
    }

    fun hasFineLocationPermission() =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasCoarseLocationPermission() =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Returns false when no location permission at all is granted. */
    @SuppressLint("MissingPermission") // checked above each request
    fun start(): Boolean {
        if (isRunning) return true
        val fine = hasFineLocationPermission()
        if (!fine && !hasCoarseLocationPermission()) return false
        startedAtMs = SystemClock.elapsedRealtime()
        gpsLostReported = false
        lastGnssFixMs = 0L
        startHeightMeters = Float.NaN
        heightChangeMeters = null
        chipCheck.reset()
        lastFusedFixMs = 0L
        stepCount = 0
        stepsInWindow = 0

        if (fine && hasGnss) {
            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, GNSS_INTERVAL_MS, 0f, gnssListener, Looper.getMainLooper())
                locationManager.registerGnssStatusCallback(gnssStatusCallback, handler)
            } catch (e: Exception) {
                Log.w(TAG, "Satellite updates unavailable", e)
            }
        }
        val fused = fusedClient
        if (fused != null) {
            val priority = if (fine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
            val request = LocationRequest.Builder(priority, FUSED_INTERVAL_MS).setMinUpdateIntervalMillis(FUSED_INTERVAL_MS / 2).build()
            try {
                fused.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
                    .addOnFailureListener { e -> Log.w(TAG, "Fused location failed", e) }
            } catch (e: Exception) {
                Log.w(TAG, "Fused location unavailable", e)
            }
        }
        startNetworkProvider()

        val accelSensor = linearAccel ?: rawAccel
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        rotationVector?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        pressure?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        ensureStepSensor()
        handler.postDelayed(gpsWatchdog, 1000L)
        isRunning = true
        return true
    }

    /** Also called when the permission may have been granted in Settings while running. */
    fun ensureStepSensor() {
        if (stepSensorOn || !hasStepPermission(appContext)) return
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: return
        stepSensorOn = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
    }

    /** No GPS and nothing else yet: where the phone last was, however it knew. */
    @SuppressLint("MissingPermission")
    private fun offerLastKnown() {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { try { locationManager.getLastKnownLocation(it) } catch (_: Exception) { null } }
            .maxByOrNull { it.elapsedRealtimeNanos }?.let(::deliverLastKnown)
        try { fusedClient?.lastLocation?.addOnSuccessListener { it?.let(::deliverLastKnown) } } catch (_: Exception) {}
    }

    private fun deliverLastKnown(loc: Location) {
        val now = SystemClock.elapsedRealtime()
        if (now - loc.elapsedRealtimeNanos / 1_000_000 > LAST_KNOWN_MAX_AGE_MS) return
        listener?.onLastKnownFix(GpsPoint(loc.latitude, loc.longitude, max(loc.accuracy, LAST_KNOWN_MIN_ACCURACY), loc.time,
            elapsedRealtimeMs = now, isGnss = false, isMock = isMock(loc)))
    }

    @Suppress("DEPRECATION")
    private fun isMock(loc: Location) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) loc.isMock else loc.isFromMockProvider

    @SuppressLint("MissingPermission")
    private fun startNetworkProvider() {
        try {
            if (locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, FUSED_INTERVAL_MS, 0f, networkListener, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network provider unavailable", e)
        }
    }

    fun stop() {
        if (!isRunning) return
        try { locationManager.removeUpdates(gnssListener) } catch (_: Exception) {}
        try { locationManager.removeUpdates(networkListener) } catch (_: Exception) {}
        try { locationManager.unregisterGnssStatusCallback(gnssStatusCallback) } catch (_: Exception) {}
        try { fusedClient?.removeLocationUpdates(fusedCallback) } catch (_: Exception) {}
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(gpsWatchdog)
        isRunning = false
        stepSensorOn = false
        windowStartMs = 0L
        stillSinceMs = 0L
    }

    /** Off, the phone cannot use Wi-Fi or mobile towers at all (Google Location Accuracy). */
    fun isNetworkLocationEnabled(): Boolean = try {
        !locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (_: Exception) { true }

    fun isLocationEnabled(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) locationManager.isLocationEnabled
        else locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (_: Exception) { true }

    private fun deliver(loc: Location, isGnss: Boolean, chipEstimate: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        // Stale fixes (e.g. a cached "last location" delivered late) are ignored.
        val fixElapsed = loc.elapsedRealtimeNanos / 1_000_000
        if (fixElapsed > 0 && now - fixElapsed > STALE_FIX_MS) return
        if (isGnss) {
            lastGnssFixMs = now
            gpsLostReported = false
        }
        listener?.onLocationFix(
            GpsPoint(
                latitude = loc.latitude,
                longitude = loc.longitude,
                // An estimate's claimed accuracy means nothing: it even improved as satellites vanished.
                accuracy = (if (loc.hasAccuracy()) loc.accuracy else 999f).let { if (chipEstimate) max(it, CHIP_ESTIMATE_MIN_ACCURACY) else it },
                timestamp = loc.time,
                speedMeterPerSecond = if (loc.hasSpeed()) loc.speed else 0f,
                bearingDegree = if (loc.hasBearing()) loc.bearing else 0f,
                hasSpeed = loc.hasSpeed(),
                hasBearing = loc.hasBearing(),
                elapsedRealtimeMs = if (fixElapsed > 0) fixElapsed else now,
                isGnss = isGnss,
                isMock = isMock(loc),
                altitudeMeters = if (loc.hasAltitude()) loc.altitude else null,
                isChipEstimate = chipEstimate
            )
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                try {
                    SensorManager.getRotationMatrixFromVector(rotation, event.values)
                    hasRotation = true
                } catch (_: IllegalArgumentException) { /* some devices send 5 values; ignore bad sample */ }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> onAccel(event.values[0], event.values[1], event.values[2])
            Sensor.TYPE_ACCELEROMETER -> {
                // Fallback: remove gravity with a low-pass filter.
                if (!gravityInitialised) { event.values.copyInto(gravity, 0, 0, 3); gravityInitialised = true }
                for (i in 0..2) gravity[i] = GRAVITY_ALPHA * gravity[i] + (1 - GRAVITY_ALPHA) * event.values[i]
                onAccel(event.values[0] - gravity[0], event.values[1] - gravity[1], event.values[2] - gravity[2])
            }
            Sensor.TYPE_GYROSCOPE -> {
                val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
                gyroMagnitudes.add(sqrt((x * x + y * y + z * z).toDouble()))
                if (hasRotation) {
                    // Rotation about world "up" (row 3 of device→world), counter-clockwise positive.
                    yawSum += rotation[6] * x + rotation[7] * y + rotation[8] * z
                    yawCount++
                }
            }
            Sensor.TYPE_PRESSURE -> onPressure(event.values[0])
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
                magneticMagnitudes.add(sqrt((x * x + y * y + z * z).toDouble()))
            }
            Sensor.TYPE_STEP_DETECTOR -> { stepsInWindow++; stepCount++ }
        }
    }

    private fun onAccel(xf: Float, yf: Float, zf: Float) {
        val x = xf.toDouble(); val y = yf.toDouble(); val z = zf.toDouble()
        if (x.isNaN() || y.isNaN() || z.isNaN()) return
        val magnitude = sqrt(x * x + y * y + z * z)
        accelMagnitudes.add(magnitude)
        if (!lastAccelMagnitude.isNaN()) accelChanges.add(magnitude - lastAccelMagnitude)
        lastAccelMagnitude = magnitude
        if (hasRotation) {
            // Rotation matrix maps device coordinates to world (East, North, Up).
            sumEast += rotation[0] * x + rotation[1] * y + rotation[2] * z
            sumNorth += rotation[3] * x + rotation[4] * y + rotation[5] * z
        }
        windowCount++

        val now = SystemClock.elapsedRealtime()
        if (windowStartMs == 0L) { windowStartMs = now; return }
        val elapsed = now - windowStartMs
        if (elapsed < SAMPLE_PERIOD_MS) return

        val state = classify(now)
        val sample = MotionSample(
            elapsedMs = now,
            dtSec = (elapsed / 1000.0).coerceAtMost(1.0), // after a pause (Doze) do not integrate a huge step
            state = state,
            stationaryForMs = if (stillSinceMs == 0L) 0L else now - stillSinceMs,
            accelEast = if (windowCount > 0) sumEast / windowCount else 0.0,
            accelNorth = if (windowCount > 0) sumNorth / windowCount else 0.0,
            orientationKnown = hasRotation,
            // Bearing rate: clockwise positive, degrees per second.
            yawRateDps = if (yawCount > 0) -Math.toDegrees(yawSum / yawCount) else Double.NaN,
            vibration = accelMagnitudes.std() ?: Double.NaN,
            steps = stepsInWindow,
            // An old reading would look like a perfectly steady pressure: only fresh ones count.
            heightMeters = heightChangeMeters?.takeIf { now - lastPressureMs <= PRESSURE_STALE_MS } ?: Double.NaN
        )
        windowStartMs = now; sumEast = 0.0; sumNorth = 0.0; windowCount = 0
        yawSum = 0.0; yawCount = 0; stepsInWindow = 0
        listener?.onMotionSample(sample)
    }

    private fun classify(now: Long): MotionState {
        val accelStd = accelMagnitudes.std()
        if (accelStd == null) { stillSinceMs = 0L; return MotionState.MOVING } // warming up: not enough data yet
        val gyroMean = gyroMagnitudes.mean()
        // Shaking far beyond any vehicle: the phone (or its box) is being carried.
        val walking = accelStd > WALKING_ACCEL_STD
        val stillThreshold = if (linearAccel != null) STILL_ACCEL_STD else STILL_ACCEL_STD_RAW
        // A smooth pull-away is a steady push with little shaking: the mean shows it, the spread does not.
        val still = !walking && accelStd < stillThreshold && accelMagnitudes.mean() < STILL_ACCEL_MEAN &&
            (gyroscope == null || gyroMean < STILL_GYRO_MEAN)
        if (still) { if (stillSinceMs == 0L) stillSinceMs = now } else stillSinceMs = 0L
        return when {
            walking -> MotionState.WALKING
            still && now - stillSinceMs >= STILL_CONFIRM_MS -> MotionState.STATIONARY
            else -> MotionState.MOVING
        }
    }

    private fun onPressure(hPa: Float) {
        if (hPa.isNaN() || hPa <= 0f) return
        val height = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hPa)
        if (startHeightMeters.isNaN()) startHeightMeters = height
        lastPressureMs = SystemClock.elapsedRealtime()
        lastPressureHpa = hPa
        heightChangeMeters = (height - startHeightMeters).toDouble()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private class RingBuffer(private val capacity: Int) {
        private val data = DoubleArray(capacity)
        private var size = 0
        private var next = 0
        fun add(v: Double) { data[next] = v; next = (next + 1) % capacity; if (size < capacity) size++ }
        fun mean(): Double = if (size == 0) 0.0 else (0 until size).sumOf { data[it] } / size
        /** Standard deviation, or null while there are too few samples to judge. */
        fun std(): Double? {
            if (size < MIN_SAMPLES_FOR_STD) return null
            val m = mean()
            return sqrt((0 until size).sumOf { (data[it] - m) * (data[it] - m) } / size)
        }
    }

    companion object {
        fun hasStepPermission(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

        private const val TAG = "SensorHub"
        const val SAMPLE_PERIOD_MS = 100L
        const val GPS_TIMEOUT_MS = 3_000L
        private const val GNSS_INTERVAL_MS = 1_000L
        private const val FUSED_INTERVAL_MS = 2_000L
        /** A fix older than this describes where the phone was, not where it is (a 30 s old fix in a train is 300+ m off). */
        private const val STALE_FIX_MS = 10_000L
        private const val NO_GNSS_TRUSTED_ACCURACY = 25f
        private const val CHIP_ESTIMATE_MIN_ACCURACY = 50f
        private const val PRESSURE_STALE_MS = 2_000L
        /** Fused sent nothing for this long: the network provider takes over. */
        private const val FUSED_SILENT_MS = 10_000L
        private const val LAST_KNOWN_MIN_ACCURACY = 1_000f
        private const val LAST_KNOWN_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        private const val ROLLING_SAMPLES = 100 // ~2 s at SENSOR_DELAY_GAME (50 Hz)
        private const val MIN_SAMPLES_FOR_STD = 10
        private const val WALKING_ACCEL_STD = 1.2
        private const val STILL_ACCEL_STD = 0.06
        private const val STILL_ACCEL_STD_RAW = 0.09 // raw accelerometer is noisier
        private const val STILL_ACCEL_MEAN = 0.3
        private const val STILL_GYRO_MEAN = 0.05
        private const val STILL_CONFIRM_MS = 1_000L
        private const val GRAVITY_ALPHA = 0.9f
    }
}

/**
 * Tells a GPS provider fix from the chip's own estimate: a recent report says no satellite is in
 * use, on a phone that does report them. Google fused repeats those estimates as good network
 * fixes, so while they last fused fixes count as estimates too.
 */
class ChipEstimateCheck {
    private var used = 0
    private var satellitesReported = false // phones that never report them must keep working
    private var statusAtMs = 0L
    private var estimateAtMs = 0L

    fun reset() {
        used = 0; satellitesReported = false; statusAtMs = 0L; estimateAtMs = 0L
    }

    fun onSatelliteStatus(usedInFix: Int, nowMs: Long) {
        used = usedInFix
        statusAtMs = nowMs
        if (usedInFix > 0) satellitesReported = true
    }

    /** For a GPS provider fix. A stale report says nothing, and a mock fix has nothing to do with the real satellites. */
    fun isEstimate(mock: Boolean, nowMs: Long): Boolean {
        val estimate = !mock && satellitesReported && used == 0 && nowMs - statusAtMs <= MAX_AGE_MS
        if (estimate) estimateAtMs = nowMs
        return estimate
    }

    fun estimatesRunning(nowMs: Long) = estimateAtMs != 0L && nowMs - estimateAtMs <= MAX_AGE_MS

    private companion object {
        const val MAX_AGE_MS = 3_000L
    }
}
