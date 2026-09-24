package com.example.testgpslocation

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.testgpslocation.engine.MotionState
import com.example.testgpslocation.engine.NavState
import com.example.testgpslocation.engine.NavigationController
import com.example.testgpslocation.engine.PositionSource
import com.example.testgpslocation.service.LocationTrackerService
import com.example.testgpslocation.utils.NetworkStatus
import com.example.testgpslocation.utils.TelemetryLogger
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var warningBar: View
    private lateinit var warningText: TextView
    private lateinit var btnFixWarning: Button
    private lateinit var statusIndicator: TextView
    private lateinit var networkText: TextView
    private lateinit var locationText: TextView
    private lateinit var locationLabel: TextView
    private lateinit var lineText: TextView
    private lateinit var currentStationText: TextView
    private lateinit var nextStationText: TextView
    private lateinit var distToNextText: TextView
    private lateinit var destinationText: TextView
    private lateinit var destinationSpinner: Spinner
    private lateinit var offlineText: TextView
    private lateinit var speedVal: TextView
    private lateinit var headingVal: TextView
    private lateinit var distanceVal: TextView
    private lateinit var confidenceVal: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val historyAdapter = HistoryAdapter()
    private var spinnerStations: List<String> = emptyList()
    private var updatingSpinner = false
    private var tracking = false
    private var cachedLines = 0
    private var permissionAskedOnce = false
    private var shownWarning: String? = null

    private class Warning(val text: String, val action: (() -> Unit)?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        NavigationController.init(this)

        warningBar = findViewById(R.id.warningBar)
        warningText = findViewById(R.id.warningText)
        btnFixWarning = findViewById(R.id.btnFixWarning)
        statusIndicator = findViewById(R.id.statusIndicator)
        networkText = findViewById(R.id.networkText)
        locationText = findViewById(R.id.locationText)
        locationLabel = findViewById(R.id.locationLabel)
        lineText = findViewById(R.id.lineText)
        currentStationText = findViewById(R.id.currentStationText)
        nextStationText = findViewById(R.id.nextStationText)
        distToNextText = findViewById(R.id.distToNextText)
        destinationText = findViewById(R.id.destinationText)
        destinationSpinner = findViewById(R.id.destinationSpinner)
        offlineText = findViewById(R.id.offlineText)
        speedVal = findViewById(R.id.speedVal)
        headingVal = findViewById(R.id.headingVal)
        distanceVal = findViewById(R.id.distanceVal)
        confidenceVal = findViewById(R.id.confidenceVal)
        btnStart = findViewById(R.id.btnStartLogging)
        btnStop = findViewById(R.id.btnStopLogging)

        val historyList = findViewById<RecyclerView>(R.id.historyList)
        val historyLayout = LinearLayoutManager(this)
        historyList.layoutManager = historyLayout
        historyList.adapter = historyAdapter
        findViewById<TextView>(R.id.tvLogFilePath).text = "Log: ${TelemetryLogger.getLogLocation(this)}"

        setupSpinner(emptyList())
        destinationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!updatingSpinner) NavigationController.setDestination(if (position == 0) null else position - 1)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        findViewById<Button>(R.id.btnFlipDirection).setOnClickListener { NavigationController.flipDirection() }
        findViewById<Button>(R.id.btnRefreshLines).setOnClickListener { NavigationController.refreshLinesHere() }
        findViewById<Button>(R.id.btnDownloadCity).setOnClickListener { NavigationController.downloadCity() }

        btnStart.setOnClickListener {
            if (!hasFineLocation()) {
                Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
                requestPermissions()
                return@setOnClickListener
            }
            ContextCompat.startForegroundService(this, Intent(this, LocationTrackerService::class.java))
        }
        btnStop.setOnClickListener { stopService(Intent(this, LocationTrackerService::class.java)) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { NavigationController.state.collect(::render) }
                launch {
                    NavigationController.history.collect {
                        val atTop = historyLayout.findFirstVisibleItemPosition() <= 0
                        historyAdapter.submit(it)
                        if (atTop) historyList.scrollToPosition(0)
                    }
                }
                launch { NavigationController.cachedLines.collect { cachedLines = it } }
                launch { NavigationController.lineStatus.collect { offlineText.text = it } }
                launch {
                    LocationTrackerService.isRunning.collect { running ->
                        btnStart.isEnabled = !running
                        btnStop.isEnabled = running
                        refreshWarnings()
                    }
                }
            }
        }

        if (!hasFineLocation()) requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        startTracking()
    }

    override fun onResume() {
        super.onResume()
        refreshWarnings()
    }

    override fun onStop() {
        super.onStop()
        if (tracking) {
            NavigationController.release(CLIENT)
            tracking = false
        }
    }

    private fun startTracking() {
        if (!tracking && hasAnyLocation()) tracking = NavigationController.acquire(CLIENT)
    }

    // ---- Rendering --------------------------------------------------------------------------

    private fun render(s: NavState) {
        val (label, color) = when {
            s.gpsAvailable -> "GPS ACTIVE" to "#4CAF50"
            s.source == PositionSource.TRACK -> "NO GPS · UNDERGROUND NAV" to "#FFB300"
            s.source == PositionSource.NETWORK -> "NO GPS · NETWORK LOCATION" to "#FFB300"
            s.source == PositionSource.DEAD_RECKONING -> "NO GPS · DEAD RECKONING" to "#FF7043"
            else -> "WAITING FOR LOCATION" to "#9E9E9E"
        }
        statusIndicator.text = label
        statusIndicator.setTextColor(Color.parseColor(color))
        networkText.text = "Network: ${NetworkStatus.label(this)}"

        locationText.text = if (s.source == PositionSource.NONE) getString(R.string.no_position)
        else String.format(Locale.US, "%.6f, %.6f", s.latitude, s.longitude)
        val sats = NavigationController.satellites.value
        locationLabel.text = buildString {
            append(s.status)
            append(" · ").append(s.source.label)
            s.gpsAccuracyMeters?.let { append(String.format(Locale.US, " · accuracy %.0f m", it)) }
            append(" · satellites ${sats.usedInFix}/${sats.inView}")
            if (!s.gpsAvailable && s.motion != MotionState.MOVING && s.source != PositionSource.NONE) append(" · ").append(s.motion.name.lowercase())
            s.lastRecoveryDriftMeters?.let { append(String.format(Locale.US, " · last GPS-recovery error %.0f m", it)) }
        }

        lineText.text = s.lineName?.let { if (s.onTrack) "🚇 $it" else "🚇 $it (not on this line)" }
            ?: if (cachedLines > 0 && s.gpsAvailable) "No metro line within 400 m" else getString(R.string.searching_line)
        currentStationText.text = s.previousStation ?: getString(R.string.dash)
        nextStationText.text = s.nextStation?.let { "→ $it" } ?: getString(R.string.dash)
        distToNextText.text = when {
            s.distanceToNextMeters == null -> if (s.onTrack) "End of line" else ""
            else -> String.format(Locale.US, "Next stop in %.0f m · ~%s", s.distanceToNextMeters, formatEta(s.etaNextSec ?: 0))
        }
        destinationText.text = when {
            s.destination == null -> ""
            s.stopsRemaining != null -> "Towards: ${s.destination} · ${s.stopsRemaining} stop(s)"
            else -> "Towards: ${s.destination}" + if (s.directionKnown) "" else " (direction not confirmed — tap Flip if wrong)"
        }

        speedVal.text = String.format(Locale.US, "%.0f km/h", s.speedMps * 3.6)
        headingVal.text = String.format(Locale.US, "%.0f°", s.headingDeg)
        distanceVal.text = if (s.gpsAvailable) "—" else String.format(Locale.US, "%.0f m", s.distanceSinceGpsLossMeters)
        confidenceVal.text = if (s.source == PositionSource.NONE) "—" else String.format(Locale.US, "±%.0f m", s.uncertaintyMeters)
        confidenceVal.setTextColor(Color.parseColor(when (s.confidence) {
            "HIGH" -> "#4CAF50"; "MEDIUM" -> "#FFD600"; "LOW" -> "#FF9800"; else -> "#F44336"
        }))

        if (s.stationNames != spinnerStations) setupSpinner(s.stationNames)
        val wanted = (s.destinationIndex ?: -1) + 1
        if (destinationSpinner.selectedItemPosition != wanted && wanted < destinationSpinner.count) {
            updatingSpinner = true
            destinationSpinner.setSelection(wanted, false)
            updatingSpinner = false
        }
        refreshWarnings(s)
    }

    private fun setupSpinner(stations: List<String>) {
        spinnerStations = stations
        updatingSpinner = true
        val items = listOf(getString(R.string.auto_direction)) + stations.map { "Destination: $it" }
        destinationSpinner.adapter = ArrayAdapter(this, R.layout.item_spinner, items)
        destinationSpinner.setSelection(0, false)
        destinationSpinner.isEnabled = stations.isNotEmpty()
        updatingSpinner = false
    }

    private fun formatEta(sec: Int) = if (sec < 60) "${sec}s" else "${sec / 60} min"

    // ---- Warnings -----------------------------------------------------------------------------

    /** Shows the most important problem that stops the app from working well, with a fix. */
    private fun refreshWarnings(s: NavState = NavigationController.state.value) {
        val w = currentWarning(s)
        if (w?.text == shownWarning) return
        shownWarning = w?.text
        warningBar.visibility = if (w == null) View.GONE else View.VISIBLE
        warningText.text = w?.text ?: ""
        btnFixWarning.visibility = if (w?.action != null) View.VISIBLE else View.GONE
        btnFixWarning.setOnClickListener { w?.action?.invoke() }
    }

    private fun currentWarning(s: NavState): Warning? {
        val caps = NavigationController.capabilities
        return when {
            !hasAnyLocation() -> Warning("Location permission is needed to navigate.") {
                if (permissionAskedOnce && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) openAppSettings()
                else requestPermissions()
            }
            !hasFineLocation() -> Warning("Precise location is off, so GPS cannot be used. Allow \"Precise\" location.") { openAppSettings() }
            !NavigationController.isLocationEnabled -> Warning("Location is turned off on this phone.") { resolveLocationSettings() }
            TelemetryLogger.lastError != null -> Warning(TelemetryLogger.lastError!!, null)
            LocationTrackerService.isRunning.value && !isIgnoringBatteryOptimizations() ->
                Warning("Battery optimisation may pause navigation with the screen off.") { openBatterySettings() }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !NotificationManagerCompat.from(this).areNotificationsEnabled() ->
                Warning("Notifications are off: the logging notification will be hidden.") { openNotificationSettings() }
            caps != null && !caps.hasLinearAcceleration && !caps.hasAccelerometer ->
                Warning("This phone has no motion sensors: underground navigation cannot estimate movement.", null)
            caps != null && !caps.hasGnss -> Warning("This phone has no GPS receiver: only network location is available.", null)
            caps != null && !caps.hasRotationVector ->
                Warning("No orientation sensor: pick your destination so the direction of travel is known.", null)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && caps?.hasStepDetector == true &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED ->
                Warning("Allow \"Physical activity\" so walking is not counted as train movement.") { requestPermissions() }
            s.mockLocation -> Warning("Mock (fake) GPS locations detected; results are not real.", null)
            else -> null
        }
    }

    private fun resolveLocationSettings() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
        LocationServices.getSettingsClient(this)
            .checkLocationSettings(LocationSettingsRequest.Builder().addLocationRequest(request).build())
            .addOnFailureListener { e ->
                if (e is ResolvableApiException) {
                    try { e.startResolutionForResult(this, LOCATION_SETTINGS_REQUEST_CODE) } catch (_: Exception) { openLocationSettings() }
                } else openLocationSettings()
            }
            .addOnSuccessListener { refreshWarnings() }
    }

    private fun openLocationSettings() = safeStart(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

    private fun openAppSettings() = safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    private fun openBatterySettings() = safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    private fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        }
        safeStart(intent)
    }

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: ActivityNotFoundException) {}
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true

    // ---- Permissions ------------------------------------------------------------------------

    private fun hasFineLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasAnyLocation() = hasFineLocation() ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        permissionAskedOnce = true
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        requestPermissions(perms.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return
        if (tracking) NavigationController.restartSensors() else startTracking()
        refreshWarnings()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == LOCATION_SETTINGS_REQUEST_CODE) {
            NavigationController.restartSensors()
            refreshWarnings()
        }
    }

    // ---- History list -------------------------------------------------------------------------

    private class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.Holder>() {
        private var items: List<String> = emptyList()

        fun submit(newItems: List<String>) {
            val added = newItems.size - items.size
            val prependedOnly = added > 0 && newItems.size > added && newItems[added] == items.firstOrNull()
            items = newItems
            if (prependedOnly) notifyItemRangeInserted(0, added) else @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false) as TextView)

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val text = items[position]
            holder.text.text = text
            holder.text.setTextColor(Color.parseColor(when {
                text.contains("⚑") -> "#FFB74D"
                text.contains("(GPS") -> "#00E676"
                text.contains("(NET") -> "#CE93D8"
                else -> "#4FC3F7"
            }))
        }

        override fun getItemCount() = items.size

        class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
    }

    private companion object {
        const val PERMISSION_REQUEST_CODE = 3001
        const val LOCATION_SETTINGS_REQUEST_CODE = 3002
        const val CLIENT = "activity"
    }
}
