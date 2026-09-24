package com.example.testerapp.service

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
import com.example.testgpslocation.utils.GeoToolbox
import com.example.testgpslocation.utils.TelemetryLogger
import com.example.testerapp.TesterMainActivity

class LocationTrackerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var simulationRunnable: Runnable
    private var simLat = 12.9716
    private var simLon = 77.5946

    companion object {
        private const val CHANNEL_ID = "TesterTrackerChannel"
        private const val NOTIFICATION_ID = 2003
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())

        simulationRunnable = object : Runnable {
            override fun run() {
                val coords = GeoToolbox.moveLocation(simLat, simLon, 15.0, 30.0)
                simLat = coords.first
                simLon = coords.second

                val speed = 5.0f
                val gpsStatus = "Acquired"
                val networkStatus = if (isNetworkConnected(this@LocationTrackerService)) "Connected" else "Lost"

                TelemetryLogger.log(this@LocationTrackerService, simLat, simLon, "GPS", speed, gpsStatus, networkStatus)
                handler.postDelayed(this, 3000L)
            }
        }
        handler.post(simulationRunnable)

        TelemetryLogger.logCustom(this, "LocationTrackerService started in :testerapp")
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tester App Telemetry Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val intent = Intent(this, TesterMainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tester App Telemetry Active (:testerapp)")
            .setContentText("Logging simulation telemetry to file...")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
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
        TelemetryLogger.logCustom(this, "LocationTrackerService stopped in :testerapp")
    }
}
