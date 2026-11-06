package com.example.testgpslocation.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.testgpslocation.MainActivity
import com.example.testgpslocation.engine.NavigationController
import com.example.testgpslocation.utils.NetworkStatus
import com.example.testgpslocation.utils.TelemetryLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps navigation running with the screen off and writes every
 * position (GPS, track-constrained or dead-reckoned) plus navigation events to the log file.
 */
class LocationTrackerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastNotificationText: String? = null
    private var lastNotificationMs = 0L
    private var started = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        NavigationController.init(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
            }
        } catch (e: Exception) {
            // Location permission revoked, or restarted from the background where a location
            // foreground service is not allowed.
            Log.w(TAG, "Cannot start foreground service", e)
            stopSelf()
            return
        }
        if (!NavigationController.acquire(CLIENT)) {
            Log.w(TAG, "Location permission missing")
            stopSelf()
            return
        }
        // Keep the CPU running with the screen off: motion sensors are only delivered while
        // the CPU is awake, and underground navigation depends on them.
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MetroNavigator:navigation")
            ?.apply { setReferenceCounted(false); acquire(MAX_WAKE_LOCK_MS) }
        started = true
        TelemetryLogger.startSession(this) // every start of logging gets its own file
        isRunning.value = true
        TelemetryLogger.logCustom(this, "Logging started")

        scope.launch {
            NavigationController.positions.collect { p ->
                val extra = buildString {
                    append(String.format(java.util.Locale.US, "Error: ±%.0f m", p.uncertaintyMeters))
                    p.gpsAccuracyMeters?.let { append(String.format(java.util.Locale.US, " | GPS accuracy: %.0f m", it)) }
                    append(" | Satellites used: ").append(p.satellitesUsed)
                    p.altitudeMeters?.let { append(String.format(java.util.Locale.US, " | Altitude: %.1f m", it)) }
                    p.headingDeg?.let { append(String.format(java.util.Locale.US, " | Heading: %.0f°", it)) }
                    p.heightChangeMeters?.let { append(String.format(java.util.Locale.US, " | Height change: %+.1f m", it)) }
                    p.lineName?.let { append(" | Line: ").append(it) }
                    p.nextStation?.let { append(" | Next: ").append(it) }
                    if (p.errorMeters != null && p.truthLatitude != null && p.truthLongitude != null) {
                        append(String.format(java.util.Locale.US, " | TUNNEL TEST real GPS: %.6f, %.6f | Error: %.0f m",
                            p.truthLatitude, p.truthLongitude, p.errorMeters))
                    }
                    if (p.mock) append(" | MOCK LOCATION")
                    p.sensors?.let { append(" | ").append(it) }
                }
                TelemetryLogger.log(
                    this@LocationTrackerService, p.timestamp, p.latitude, p.longitude, p.source.label,
                    p.speedMps.toFloat(), if (p.gpsAvailable) "Acquired" else "Lost", NetworkStatus.label(this@LocationTrackerService),
                    extra
                )
            }
        }
        scope.launch {
            while (isActive) { // an unattended box logs for days: the time limit only guards against a stuck service
                delay(WAKE_LOCK_RENEW_MS)
                wakeLock?.acquire(MAX_WAKE_LOCK_MS)
            }
        }
        scope.launch {
            NavigationController.messages.collect { TelemetryLogger.logCustom(this@LocationTrackerService, "EVENT: $it") }
        }
        scope.launch {
            NavigationController.fixLog.collect { TelemetryLogger.logCustom(this@LocationTrackerService, it) }
        }
        scope.launch {
            NavigationController.state.collect { s ->
                val text = buildString {
                    append(s.status)
                    s.nextStation?.let { append(" · next: ").append(it) }
                    s.distanceToNextMeters?.let { append(String.format(java.util.Locale.US, " (%.0f m)", it)) }
                }
                updateNotification(text)
            }
        }
    }

    private fun updateNotification(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (text == lastNotificationText || now - lastNotificationMs < 3000L) return
        lastNotificationText = text
        lastNotificationMs = now
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Metro navigation & logging", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Metro Navigator logging")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (started) {
            NavigationController.release(CLIENT)
            TelemetryLogger.logCustom(this, "Logging stopped")
            TelemetryLogger.endSession()
        }
        isRunning.value = false
        super.onDestroy()
    }

    companion object {
        /** Whether logging is currently active (for the UI). */
        val isRunning = kotlinx.coroutines.flow.MutableStateFlow(false)

        private const val TAG = "LocationTrackerService"
        private const val CHANNEL_ID = "LocationTrackerChannel"
        private const val NOTIFICATION_ID = 2001
        private const val CLIENT = "service"
        private const val MAX_WAKE_LOCK_MS = 12 * 60 * 60 * 1000L // safety cap: 12 h, renewed while logging
        private const val WAKE_LOCK_RENEW_MS = 60 * 60 * 1000L
    }
}
