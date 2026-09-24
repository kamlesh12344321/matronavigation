package com.example.testerapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.testgpslocation.utils.TelemetryLogger
import com.example.testerapp.service.LocationTrackerService
import com.google.android.gms.location.*
import java.text.SimpleDateFormat
import java.util.*

class TesterMainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 3001
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private lateinit var tvGpsStatus: TextView
    private lateinit var tvCoordsSpeed: TextView
    private lateinit var tvLogs: TextView
    private lateinit var tvLogFilePath: TextView

    private lateinit var btnStartLogging: Button
    private lateinit var btnStopLogging: Button
    private lateinit var btnClearLogs: Button

    private val logBuilder = StringBuilder()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tester_main)

        tvGpsStatus = findViewById(R.id.tvGpsStatus)
        tvCoordsSpeed = findViewById(R.id.tvCoordsSpeed)
        tvLogs = findViewById(R.id.tvLogs)
        tvLogFilePath = findViewById(R.id.tvLogFilePath)

        btnStartLogging = findViewById(R.id.btnStartLogging)
        btnStopLogging = findViewById(R.id.btnStopLogging)
        btnClearLogs = findViewById(R.id.btnClearLogs)

        // Hide unnecessary simulation-only buttons from layout if present
        findViewById<android.view.View>(R.id.rgModes)?.visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.btnSimulateLocation)?.visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.btnVerifyCache)?.visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.tvActiveMode)?.visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.tvNetworkStatus)?.visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.tvCacheStatus)?.visibility = android.view.View.GONE

        tvLogFilePath.text = "Log File: ${TelemetryLogger.getLogFile(this).absolutePath}"
        appendLog("GPS Tracker App initialized. Ready for normal GPS tracking.")

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val lat = loc.latitude
                val lon = loc.longitude
                val speed = if (loc.hasSpeed()) loc.speed else 0f
                val accuracy = loc.accuracy

                tvGpsStatus.text = "GPS Status: Active (Accuracy: %.1fm)".format(accuracy)
                tvCoordsSpeed.text = "Coordinates & Speed: %.6f, %.6f | %.1f m/s".format(lat, lon, speed)

                appendLog("GPS Update: Lat=%.6f, Lon=%.6f, Spd=%.1fm/s".format(lat, lon, speed))
                TelemetryLogger.log(this@TesterMainActivity, lat, lon, "GPS", speed, "Acquired", "Online")
            }
        }

        btnStartLogging.setOnClickListener {
            if (checkLocationPermission()) {
                val intent = Intent(this, LocationTrackerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(this, intent)
                } else {
                    startService(intent)
                }
                startGpsUpdates()
                Toast.makeText(this, "GPS Background Logging Started", Toast.LENGTH_SHORT).show()
                appendLog("Background GPS telemetry logging started.")
            } else {
                Toast.makeText(this, "Location permission required", Toast.LENGTH_LONG).show()
                requestRequiredPermissions()
            }
        }

        btnStopLogging.setOnClickListener {
            val intent = Intent(this, LocationTrackerService::class.java)
            stopService(intent)
            fusedLocationClient.removeLocationUpdates(locationCallback)
            Toast.makeText(this, "GPS Background Logging Stopped", Toast.LENGTH_SHORT).show()
            appendLog("GPS tracking stopped.")
        }

        btnClearLogs.setOnClickListener {
            logBuilder.clear()
            appendLog("Logs cleared.")
            tvLogs.text = logBuilder.toString()
        }

        requestRequiredPermissions()
    }

    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun startGpsUpdates() {
        if (!checkLocationPermission()) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateIntervalMillis(2000L)
            .build()
        fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    private fun appendLog(message: String) {
        val timestamp = dateFormat.format(Date())
        logBuilder.append("[$timestamp] $message\n")
        if (::tvLogs.isInitialized) {
            tvLogs.text = logBuilder.toString()
        }
    }

    override fun onResume() {
        super.onResume()
        if (checkLocationPermission()) {
            startGpsUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
    }
}
