package com.example.trainbox

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.trainbox.data.SpatialiteDatabaseHelper
import com.example.trainbox.engine.TrainTrackingEngine
import com.example.trainbox.service.LocationTrackerService
import com.example.testgpslocation.utils.TelemetryLogger

class TrainMainActivity : AppCompatActivity(), TrainTrackingEngine.TrainEngineListener {

    private val PERMISSION_REQUEST_CODE = 3002

    private lateinit var trackingEngine: TrainTrackingEngine
    private lateinit var dbHelper: SpatialiteDatabaseHelper

    private lateinit var tvStatus: TextView
    private lateinit var tvTelemetry: TextView
    private lateinit var tvLogFilePath: TextView
    private lateinit var btnSimulateMove: Button
    private lateinit var btnStartLogging: Button
    private lateinit var btnStopLogging: Button

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_train_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvTelemetry = findViewById(R.id.tvTelemetry)
        tvLogFilePath = findViewById(R.id.tvLogFilePath)
        btnSimulateMove = findViewById(R.id.btnSimulateMove)
        btnStartLogging = findViewById(R.id.btnStartLogging)
        btnStopLogging = findViewById(R.id.btnStopLogging)

        dbHelper = SpatialiteDatabaseHelper(this)
        trackingEngine = TrainTrackingEngine(this)
        trackingEngine.setListener(this)

        trackingEngine.initializePosition(12.9716, 77.5946, 90f)

        tvLogFilePath.text = "Path: ${TelemetryLogger.getLogFile(this).absolutePath}"

        btnSimulateMove.setOnClickListener {
            trackingEngine.processSensorData(0.01, 0.5, 0.0, 0.0, System.nanoTime())
            dbHelper.insertTelemetry(System.currentTimeMillis(), trackingEngine.currentLatitude, trackingEngine.currentLongitude, trackingEngine.currentSpeedMps, trackingEngine.isStationStop)
            val count = dbHelper.getTelemetryCount()
            tvStatus.text = "SpatiaLite Telemetry Records: $count"
        }

        btnStartLogging.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val intent = Intent(this, LocationTrackerService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(this, intent)
                } else {
                    startService(intent)
                }
                Toast.makeText(this, "Train Telemetry Logging Started", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Location permission is required to start location tracking service", Toast.LENGTH_LONG).show()
                requestRequiredPermissions()
            }
        }

        btnStopLogging.setOnClickListener {
            val intent = Intent(this, LocationTrackerService::class.java)
            stopService(intent)
            Toast.makeText(this, "Train Telemetry Logging Stopped", Toast.LENGTH_SHORT).show()
        }

        requestRequiredPermissions()
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

    override fun onTelemetryUpdated(distanceMeters: Double, speedMps: Double, isStationStop: Boolean, heading: Float) {
        val text = "Distance: %.2f m\nSpeed: %.2f m/s\nStation Stop (ZUPT): %s\nHeading: %.1f°"
            .format(distanceMeters, speedMps, isStationStop, heading)
        tvTelemetry.text = text
    }

    @SuppressLint("SetTextI18n")
    override fun onStationDetected(stationName: String) {
        tvStatus.text = "Event: $stationName"
    }
}
