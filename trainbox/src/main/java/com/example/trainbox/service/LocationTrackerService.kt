package com.example.trainbox.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.example.testgpslocation.utils.TelemetryLogger
import com.example.trainbox.TrainMainActivity
import com.example.trainbox.engine.TrainTrackingEngine

class LocationTrackerService : Service(), TrainTrackingEngine.TrainEngineListener {

    private lateinit var trackingEngine: TrainTrackingEngine
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var simulationRunnable: Runnable

    companion object {
        private const val CHANNEL_ID = "TrainTrackerChannel"
        private const val NOTIFICATION_ID = 2002
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
        
        trackingEngine = TrainTrackingEngine(this)
        trackingEngine.setListener(this)
        trackingEngine.initializePosition(12.9716, 77.5946, 90f)

        simulationRunnable = object : Runnable {
            override fun run() {
                trackingEngine.processSensorData(0.05, 0.4, 0.0, 0.0, System.nanoTime())
                handler.postDelayed(this, 3000L)
            }
        }
        handler.post(simulationRunnable)

        TelemetryLogger.logCustom(this, "LocationTrackerService started in :trainbox")
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Train Telemetry Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val intent = Intent(this, TrainMainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Train Telemetry Active (:trainbox)")
            .setContentText("Logging rail telemetry & ZUPT to file...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onTelemetryUpdated(distanceMeters: Double, speedMps: Double, isStationStop: Boolean, heading: Float) {
        val lat = trackingEngine.currentLatitude
        val lon = trackingEngine.currentLongitude
        val source = "DR"
        val gpsStatus = if (isStationStop) "Acquired (Station Stop)" else "Acquired"
        val networkStatus = if (isNetworkConnected(this)) "Connected" else "Lost"

        TelemetryLogger.log(this, lat, lon, source, speedMps.toFloat(), gpsStatus, networkStatus)
    }

    override fun onStationDetected(stationName: String) {
        TelemetryLogger.logCustom(this, "Train Event: $stationName")
    }

    private fun isNetworkConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(simulationRunnable)
        TelemetryLogger.logCustom(this, "LocationTrackerService stopped in :trainbox")
    }
}
