package com.example.testgpslocation

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.testgpslocation.engine.MetroRouteService
import com.example.testgpslocation.engine.NavState
import com.example.testgpslocation.engine.NavigationController
import com.example.testgpslocation.engine.PositionSource
import com.example.testgpslocation.map.NavigationOverlay
import com.example.testgpslocation.sensor.SensorHub
import com.example.testgpslocation.service.LocationTrackerService
import com.example.testgpslocation.utils.NetworkStatus
import com.example.testgpslocation.utils.TelemetryLogger
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import java.util.Locale
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var overlay: NavigationOverlay
    private lateinit var warningBar: View
    private lateinit var warningText: TextView
    private lateinit var btnFixWarning: Button
    private lateinit var statusIndicator: TextView
    private lateinit var networkText: TextView
    private lateinit var btnRecenter: ImageButton
    private lateinit var lineText: TextView
    private lateinit var currentStationText: TextView
    private lateinit var nextStationText: TextView
    private lateinit var distToNextText: TextView
    private lateinit var metricsText: TextView
    private lateinit var destinationText: TextView
    private lateinit var confidenceVal: TextView
    private lateinit var btnLogging: Button
    private lateinit var logFileText: TextView

    private var tracking = false
    private var permissionAskedOnce = false
    private var shownWarning: String? = null

    /** The map keeps the current position in view until the user drags it. */
    private var following = true
    private var centredOnce = false
    private var touchDownX = 0f
    private var touchDownY = 0f

    private class Warning(val text: String, val action: (() -> Unit)?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureMapLibrary()
        setContentView(R.layout.activity_main)
        NavigationController.init(this)

        map = findViewById(R.id.map)
        warningBar = findViewById(R.id.warningBar)
        warningText = findViewById(R.id.warningText)
        btnFixWarning = findViewById(R.id.btnFixWarning)
        statusIndicator = findViewById(R.id.statusIndicator)
        networkText = findViewById(R.id.networkText)
        btnRecenter = findViewById(R.id.btnRecenter)
        lineText = findViewById(R.id.lineText)
        currentStationText = findViewById(R.id.currentStationText)
        nextStationText = findViewById(R.id.nextStationText)
        distToNextText = findViewById(R.id.distToNextText)
        metricsText = findViewById(R.id.metricsText)
        destinationText = findViewById(R.id.destinationText)
        confidenceVal = findViewById(R.id.confidenceVal)
        btnLogging = findViewById(R.id.btnLogging)

        setupMap()
        logFileText = findViewById(R.id.tvLogFilePath)
        findViewById<View>(R.id.mapAttribution).setOnClickListener { safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(OSM_COPYRIGHT_URL))) }

        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener(::showMenu)
        btnRecenter.setOnClickListener { setFollowing(true); centreOnPosition(fromButton = true) }

        btnLogging.setOnClickListener {
            if (LocationTrackerService.isRunning.value) {
                stopService(Intent(this, LocationTrackerService::class.java))
            } else if (!hasFineLocation()) {
                Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
                requestPermissions()
            } else {
                ContextCompat.startForegroundService(this, Intent(this, LocationTrackerService::class.java))
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { NavigationController.state.collect(::render) }
                launch { NavigationController.route.collect { overlay.route = it; map.invalidate() } }
                launch { NavigationController.trail.collect { overlay.trail = it; map.invalidate() } }
                launch { NavigationController.lineStatus.collect { render(NavigationController.state.value) } }
                launch {
                    LocationTrackerService.isRunning.collect { running ->
                        btnLogging.setText(if (running) R.string.stop_logging else R.string.start_logging)
                        btnLogging.setTextColor(if (running) STOP_COLOR else START_COLOR)
                        logFileText.text = TelemetryLogger.currentFileName?.let { getString(R.string.log_current_file, TelemetryLogger.folderDescription(this@MainActivity), it) }
                            ?: getString(R.string.log_new_file_each_start, TelemetryLogger.folderDescription(this@MainActivity))
                        refreshWarnings()
                    }
                }
            }
        }

        if (!hasFineLocation() || (!hasStepPermission() && !stepPermissionAsked())) requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        startTracking()
        NavigationController.refreshStepSensor()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        refreshWarnings()
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
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

    // ---- Map ----------------------------------------------------------------------------------

    private fun configureMapLibrary() {
        val base = File(cacheDir, "osmdroid")
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences(OSMDROID_PREFS, MODE_PRIVATE))
            userAgentValue = MetroRouteService.USER_AGENT
            osmdroidBasePath = base
            osmdroidTileCache = File(base, "tiles")
        }
    }

    private fun setupMap() {
        map.setTileSource(OSM_TILES)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.setTilesScaledToDpi(true)
        map.setMinZoomLevel(MIN_ZOOM)
        map.overlayManager.tilesOverlay.apply {
            // Dark map to match the screen; with no tiles (underground, offline) a plain dark background.
            // The filter inverts the loading colour too, so it is given inverted.
            setColorFilter(TilesOverlay.INVERT_COLORS)
            setLoadingBackgroundColor(MAP_BACKGROUND_INVERTED)
            setLoadingLineColor(MAP_BACKGROUND_INVERTED)
        }
        map.controller.setZoom(MIN_ZOOM)
        overlay = NavigationOverlay(resources.displayMetrics).also {
            it.onMarkerTapped = { text -> Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
            map.overlays.add(it)
        }
        watchMapDrags()
        setFollowing(true)
    }

    @SuppressLint("ClickableViewAccessibility") // only watches for drags; the map still handles every touch
    private fun watchMapDrags() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        map.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { touchDownX = e.x; touchDownY = e.y }
                MotionEvent.ACTION_MOVE ->
                    if (e.pointerCount == 1 && hypot(e.x - touchDownX, e.y - touchDownY) > slop) setFollowing(false)
            }
            false
        }
    }

    private fun setFollowing(on: Boolean) {
        following = on
        btnRecenter.setColorFilter(if (on) FOLLOW_ON_COLOR else Color.WHITE)
    }

    /** [fromButton]: the user asked for it, so also zoom back in from a far-out view. */
    private fun centreOnPosition(fromButton: Boolean) {
        val s = NavigationController.state.value
        if (s.source == PositionSource.NONE) return
        val here = GeoPoint(s.latitude, s.longitude)
        if (!centredOnce || (fromButton && map.zoomLevelDouble < MIN_FOLLOW_ZOOM)) {
            map.controller.setZoom(FOLLOW_ZOOM)
            map.controller.setCenter(here)
            centredOnce = true
        } else if (fromButton) {
            map.controller.animateTo(here)
        } else {
            map.controller.setCenter(here)
        }
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            inflate(R.menu.map_menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menuClearMap -> {
                        NavigationController.clearMap()
                        Toast.makeText(this@MainActivity, R.string.map_cleared, Toast.LENGTH_SHORT).show()
                    }
                    R.id.menuUpdateLines -> NavigationController.downloadCity()
                }
                true
            }
            show()
        }
    }

    // ---- Rendering --------------------------------------------------------------------------

    private fun render(s: NavState) {
        val (label, color) = when {
            s.gpsAvailable -> "GPS ACTIVE" to "#4CAF50"
            s.source != PositionSource.NONE -> "NO GPS · ESTIMATED POSITION" to "#FFB300"
            else -> "WAITING FOR LOCATION" to "#9E9E9E"
        }
        statusIndicator.text = label
        statusIndicator.setTextColor(Color.parseColor(color))
        networkText.text = "Network: ${NetworkStatus.label(this)}"

        overlay.current = s.takeIf { it.source != PositionSource.NONE }
        if (following || !centredOnce) centreOnPosition(fromButton = false)
        map.invalidate()

        // No line yet: what the line data is doing (loading, downloading, none near here).
        lineText.text = s.lineName?.let { if (s.onTrack) "🚇 $it" else "🚇 $it (not on this line)" }
            ?: NavigationController.lineStatus.value
        currentStationText.text = s.previousStation ?: getString(R.string.dash)
        nextStationText.text = s.nextStation?.let { "→ $it" } ?: getString(R.string.dash)
        showOrHide(distToNextText, when {
            s.distanceToNextMeters == null -> if (s.onTrack) "End of line" else ""
            else -> String.format(Locale.US, "Next stop in %.0f m · ~%s", s.distanceToNextMeters, formatEta(s.etaNextSec ?: 0))
        })
        metricsText.text = String.format(Locale.US, "%.0f km/h", s.speedMps * 3.6)
        showOrHide(destinationText, when {
            s.destination == null || !s.directionKnown -> "" // a guess is not shown
            s.stopsRemaining != null -> "Towards: ${s.destination} · ${s.stopsRemaining} stop(s)"
            else -> "Towards: ${s.destination}"
        })
        confidenceVal.text = if (s.source == PositionSource.NONE) "—" else String.format(Locale.US, "±%.0f m", s.uncertaintyMeters)
        confidenceVal.setTextColor(Color.parseColor(when (s.confidence) {
            "HIGH" -> "#4CAF50"; "MEDIUM" -> "#FFD600"; "LOW" -> "#FF9800"; else -> "#F44336"
        }))
        refreshWarnings(s)
    }

    private fun showOrHide(view: TextView, text: String) {
        view.text = text
        view.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
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
            !NavigationController.isNetworkLocationEnabled ->
                Warning("Network location is off: turn on Google Location Accuracy so Wi-Fi and mobile towers can be used.") { resolveLocationSettings() }
            LocationTrackerService.isRunning.value && !isIgnoringBatteryOptimizations() ->
                Warning("Battery optimisation may pause navigation with the screen off.") { openBatterySettings() }
            LocationTrackerService.isRunning.value && isSamsung() && !samsungSleepHandled() ->
                Warning("Samsung can put this app to sleep and stop logging: add it to Settings › Battery › Background usage limits › Never sleeping apps.") {
                    getSharedPreferences(WARNING_PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SAMSUNG_SLEEP, true).apply()
                    safeStart(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
                    refreshWarnings()
                }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !NotificationManagerCompat.from(this).areNotificationsEnabled() ->
                Warning("Notifications are off: the logging notification will be hidden.") { openNotificationSettings() }
            caps != null && !caps.hasLinearAcceleration && !caps.hasAccelerometer ->
                Warning("This phone has no motion sensors: underground navigation cannot estimate movement.", null)
            caps != null && !caps.hasGnss -> Warning("This phone has no GPS receiver: only network location is available.", null)
            s.mockLocation -> Warning("Mock (fake) GPS locations detected; results are not real.", null)
            else -> null
        }
    }

    private fun isSamsung() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /** Android cannot tell whether the app is on that list: asked once, until Fix is tapped. */
    private fun samsungSleepHandled() = getSharedPreferences(WARNING_PREFS, MODE_PRIVATE).getBoolean(KEY_SAMSUNG_SLEEP, false)

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

    /** Android's own one-tap question for this app; the full list of apps where it is not offered. */
    @SuppressLint("BatteryLife") // navigation must keep running with the screen off; the user approves it
    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: ActivityNotFoundException) {
            safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

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

    private fun hasStepPermission() = SensorHub.hasStepPermission(this)

    /** Asked once, so a refusal does not bring the question back on every open. */
    private fun stepPermissionAsked() = getSharedPreferences(WARNING_PREFS, MODE_PRIVATE).getBoolean(KEY_STEP_PERMISSION_ASKED, false)

    private fun hasAnyLocation() = hasFineLocation() ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        permissionAskedOnce = true
        getSharedPreferences(WARNING_PREFS, MODE_PRIVATE).edit().putBoolean(KEY_STEP_PERMISSION_ASKED, true).apply()
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
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

    private companion object {
        const val PERMISSION_REQUEST_CODE = 3001
        const val LOCATION_SETTINGS_REQUEST_CODE = 3002
        const val CLIENT = "activity"
        const val OSMDROID_PREFS = "osmdroid"
        const val WARNING_PREFS = "warnings"
        const val KEY_SAMSUNG_SLEEP = "samsung_sleep_handled"
        const val KEY_STEP_PERMISSION_ASKED = "step_permission_asked"
        const val OSM_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"

        /**
         * OpenStreetMap's standard tiles. osmdroid's built-in source sends "<package>/<version>"
         * as the user agent, which OpenStreetMap blocks for this sample package name; this one
         * sends [MetroRouteService.USER_AGENT] and keeps osmdroid's polite-use limits.
         */
        val OSM_TILES = XYTileSource(
            "OpenStreetMap", 0, 19, 256, ".png", arrayOf("https://tile.openstreetmap.org/"),
            "© OpenStreetMap contributors",
            TileSourcePolicy(2, TileSourcePolicy.FLAG_NO_BULK or TileSourcePolicy.FLAG_NO_PREVENTIVE or TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL)
        )
        const val MIN_ZOOM = 3.0
        /** Zoom when the map first finds the position (a few stations across the screen). */
        const val FOLLOW_ZOOM = 15.0
        /** Recentring from further out than this zooms back in. */
        const val MIN_FOLLOW_ZOOM = 11.0
        /** #0A0A0A after the tile colour inversion. */
        val MAP_BACKGROUND_INVERTED = Color.parseColor("#F5F5F5")
        val FOLLOW_ON_COLOR = Color.parseColor("#00E5FF")
        val START_COLOR = Color.parseColor("#00E5FF")
        val STOP_COLOR = Color.parseColor("#FF8A80")
    }
}
