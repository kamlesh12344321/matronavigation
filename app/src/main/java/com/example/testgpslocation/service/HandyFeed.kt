package com.example.testgpslocation.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.testgpslocation.engine.NavState
import com.example.testgpslocation.engine.PositionSource
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Sends this app's position to other apps — Nemo Handy — while there is no GPS (in a tunnel), as
 * Android mock locations on the GPS provider and on Google's fused provider (whichever Handy
 * reads). Needs this app chosen as the mock location app (Developer options); with GPS working,
 * nothing is mocked and Handy gets the real receiver.
 *
 * While the GPS provider is mocked, Android switches the real receiver off for every app, this
 * one included. So the mock is lifted every [FEED_MS] for at least [PROBE_MIN_MS] to look for
 * real GPS, longer while satellites are being picked up; when GPS is back it stays off.
 * Call [update] about once a second (main thread).
 */
class HandyFeed(context: Context) {

    enum class Phase { OFF, FEED, PROBE }

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val fused: FusedLocationProviderClient? = try {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS)
            LocationServices.getFusedLocationProviderClient(appContext) else null
    } catch (_: Exception) { null }

    private var phase = Phase.OFF
    private var phaseSinceMs = 0L
    private var lastRefusedMs = Long.MIN_VALUE / 2

    /** Called on each change of phase with a line for the log. */
    var onEvent: ((String) -> Unit)? = null

    /** True while this app's positions are being published (the row's position went to Handy). */
    val publishing: Boolean get() = phase == Phase.FEED

    fun update(s: NavState, satellitesUsed: Int, nowMs: Long) {
        if (status.value == Status.OFF) status.value = Status.WAITING
        val wanted = nextPhase(phase, nowMs - phaseSinceMs, s.gpsAvailable, s.source != PositionSource.NONE, s.gpsLostForMs, satellitesUsed)
        if (wanted != phase) {
            if (wanted == Phase.FEED && nowMs - lastRefusedMs < RETRY_REFUSED_MS) return
            when (wanted) {
                Phase.FEED -> if (!startMock()) { lastRefusedMs = nowMs; return }
                else -> if (phase == Phase.FEED) stopMock()
            }
            onEvent?.invoke(when {
                wanted == Phase.FEED && phase == Phase.OFF -> "Handy feed: no GPS, sending this app's position as the phone's location"
                wanted == Phase.FEED -> "Handy feed: still no GPS, sending again"
                wanted == Phase.PROBE -> "Handy feed: paused to check for real GPS"
                else -> "Handy feed: stopped (GPS back or no position)"
            })
            phase = wanted
            phaseSinceMs = nowMs
        }
        if (phase == Phase.FEED) publish(s)
    }

    /** Removes the mock (logging stopped, or the feed switched off). */
    fun shutdown() {
        if (phase == Phase.FEED) stopMock()
        phase = Phase.OFF
        status.value = Status.OFF
    }

    @SuppressLint("MissingPermission") // a mock app needs no location permission to mock
    @Suppress("DEPRECATION") // the ProviderProperties form needs API 31; this one works on all
    private fun startMock(): Boolean = try {
        try { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {} // a leftover from a crash
        locationManager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false, false, true, true, true,
            POWER_HIGH, ACCURACY_FINE)
        locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        fused?.setMockMode(true)
        status.value = Status.SENDING
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "Not the mock location app", e)
        status.value = Status.NOT_ALLOWED
        false
    } catch (e: Exception) {
        Log.w(TAG, "Mock location unavailable", e)
        status.value = Status.NOT_ALLOWED
        false
    }

    private fun stopMock() {
        try { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
        try { fused?.setMockMode(false) } catch (_: Exception) {}
        status.value = Status.WAITING
    }

    private fun publish(s: NavState) {
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = s.latitude
            longitude = s.longitude
            accuracy = s.uncertaintyMeters.toFloat().coerceIn(1f, MAX_ACCURACY_METERS)
            speed = s.speedMps.toFloat()
            bearing = s.headingDeg.toFloat()
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                speedAccuracyMetersPerSecond = 2f
                bearingAccuracyDegrees = 30f
            }
        }
        try { locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location) } catch (e: Exception) { Log.w(TAG, "GPS mock failed", e) }
        try { fused?.setMockLocation(Location(location).apply { provider = "fused" }) } catch (e: Exception) { Log.w(TAG, "Fused mock failed", e) }
    }

    /** For the screen: what the feed is doing. */
    enum class Status { OFF, WAITING, SENDING, NOT_ALLOWED }

    companion object {
        private const val TAG = "HandyFeed"
        /** Criteria.POWER_HIGH and Criteria.ACCURACY_FINE (the class is deprecated, the values are not). */
        private const val POWER_HIGH = 3
        private const val ACCURACY_FINE = 1
        /** Process-wide status for the screen. */
        val status = MutableStateFlow(Status.OFF)
        /** While on, mock fixes the app receives are its own and must not be navigated with. */
        @Volatile var enabled = false

        /** No GPS this long (not just a bridge) before the real receiver is replaced. */
        const val START_AFTER_MS = 10_000L
        /** Feeding this long, then a pause to look for real GPS. */
        const val FEED_MS = 30_000L
        const val PROBE_MIN_MS = 8_000L
        /** A pause goes on this long at most while satellites are being picked up. */
        const val PROBE_MAX_MS = 30_000L
        const val RETRY_REFUSED_MS = 30_000L
        const val MAX_ACCURACY_METERS = 2_000f
        private const val PREFS = "handy_feed"
        private const val KEY_ON = "on"

        /** The user's switch (menu): send the position to Nemo Handy while logging. */
        fun isWanted(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

        fun setWanted(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        }

        /**
         * The phase the feed should be in. [phaseForMs] is how long it has been in [current];
         * [satellitesUsed] is the real receiver's count (only meaningful during a pause).
         */
        fun nextPhase(current: Phase, phaseForMs: Long, gpsAvailable: Boolean, hasPosition: Boolean,
                      gpsLostForMs: Long, satellitesUsed: Int): Phase = when {
            gpsAvailable || !hasPosition || gpsLostForMs < START_AFTER_MS -> Phase.OFF
            current == Phase.OFF -> Phase.FEED
            current == Phase.FEED -> if (phaseForMs >= FEED_MS) Phase.PROBE else Phase.FEED
            phaseForMs < PROBE_MIN_MS -> Phase.PROBE
            satellitesUsed > 0 && phaseForMs < PROBE_MAX_MS -> Phase.PROBE // GPS is coming back: let it
            else -> Phase.FEED
        }
    }
}
