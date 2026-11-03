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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.testgpslocation.engine.MetroRouteService
import com.example.testgpslocation.engine.MotionState
import com.example.testgpslocation.engine.NavState
import com.example.testgpslocation.engine.NavigationController
import com.example.testgpslocation.engine.PositionSource
import com.example.testgpslocation.engine.TestDriveMode
import com.example.testgpslocation.engine.TestDriveState
import com.example.testgpslocation.map.NavigationOverlay
import com.example.testgpslocation.service.HandyFeed
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
    private lateinit var details: View
    private lateinit var locationText: TextView
    private lateinit var locationLabel: TextView
    private lateinit var lineText: TextView
    private lateinit var currentStationText: TextView
    private lateinit var nextStationText: TextView
    private lateinit var distToNextText: TextView
    private lateinit var metricsText: TextView
    private lateinit var destinationText: TextView
    private lateinit var destinationSpinner: Spinner
    private lateinit var offlineText: TextView
    private lateinit var confidenceVal: TextView
    private lateinit var btnLogging: Button
    private lateinit var logFileText: TextView
    private lateinit var testDriveBar: View
    private lateinit var testDriveText: TextView
    private lateinit var btnTestDrivePrimary: Button
    private lateinit var btnTestDriveSecondary: Button

    private var spinnerStations: List<String> = emptyList()
    private var updatingSpinner = false
    private var tracking = false
    private var cachedLines = 0
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
        details = findViewById(R.id.details)
        locationText = findViewById(R.id.locationText)
        locationLabel = findViewById(R.id.locationLabel)
        lineText = findViewById(R.id.lineText)
        currentStationText = findViewById(R.id.currentStationText)
        nextStationText = findViewById(R.id.nextStationText)
        distToNextText = findViewById(R.id.distToNextText)
        metricsText = findViewById(R.id.metricsText)
        destinationText = findViewById(R.id.destinationText)
        destinationSpinner = findViewById(R.id.destinationSpinner)
        offlineText = findViewById(R.id.offlineText)
        confidenceVal = findViewById(R.id.confidenceVal)
        btnLogging = findViewById(R.id.btnLogging)
        testDriveBar = findViewById(R.id.testDriveBar)
        testDriveText = findViewById(R.id.testDriveText)
        btnTestDrivePrimary = findViewById(R.id.btnTestDrivePrimary)
        btnTestDriveSecondary = findViewById(R.id.btnTestDriveSecondary)

        setupMap()
        logFileText = findViewById(R.id.tvLogFilePath)
        findViewById<TextView>(R.id.legendText).text = legend()
        findViewById<View>(R.id.panel).setOnClickListener { toggleDetails() }
        findViewById<View>(R.id.mapAttribution).setOnClickListener { safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(OSM_COPYRIGHT_URL))) }

        setupSpinner(emptyList())
        destinationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!updatingSpinner) NavigationController.setDestination(if (position == 0) null else position - 1)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        findViewById<Button>(R.id.btnFlipDirection).setOnClickListener { NavigationController.flipDirection() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener(::showMenu)
        btnRecenter.setOnClickListener { setFollowing(true); centreOnPosition(fromButton = true) }
        btnTestDrivePrimary.setOnClickListener { onTestDrivePrimary() }
        btnTestDriveSecondary.setOnClickListener { onTestDriveSecondary() }

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
                launch { NavigationController.mapEvents.collect { overlay.events = it; map.invalidate() } }
                launch { NavigationController.truthTrail.collect { overlay.truthTrail = it; map.invalidate() } }
                launch { NavigationController.testDrive.collect(::renderTestDrive) }
                launch { NavigationController.cachedLines.collect { cachedLines = it } }
                launch { NavigationController.lineStatus.collect { offlineText.text = it } }
                launch { HandyFeed.status.collect { refreshWarnings() } }
                launch {
                    LocationTrackerService.isRunning.collect { running ->
                        btnLogging.setText(if (running) R.string.stop_logging else R.string.start_logging)
                        btnLogging.setTextColor(if (running) STOP_COLOR else START_COLOR)
                        logFileText.text = TelemetryLogger.currentFileName?.let { getString(R.string.log_current_file, TelemetryLogger.FOLDER_DESCRIPTION, it) }
                            ?: getString(R.string.log_new_file_each_start, TelemetryLogger.FOLDER_DESCRIPTION)
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
            menu.findItem(R.id.menuDetails).setTitle(if (details.visibility == View.VISIBLE) R.string.hide_details else R.string.show_details)
            menu.findItem(R.id.menuHandyFeed).isChecked = HandyFeed.isWanted(this@MainActivity)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menuTestDrive -> showTestDriveDialog()
                    R.id.menuDetails -> toggleDetails()
                    R.id.menuClearMap -> {
                        NavigationController.clearMap()
                        Toast.makeText(this@MainActivity, R.string.map_cleared, Toast.LENGTH_SHORT).show()
                    }
                    R.id.menuRefreshLines -> NavigationController.refreshLinesHere()
                    R.id.menuDownloadCity -> NavigationController.downloadCity()
                    R.id.menuHandyFeed -> {
                        HandyFeed.setWanted(this@MainActivity, !item.isChecked)
                        Toast.makeText(this@MainActivity, if (item.isChecked) R.string.handy_feed_off else R.string.handy_feed_on, Toast.LENGTH_LONG).show()
                        refreshWarnings()
                    }
                }
                true
            }
            show()
        }
    }

    // ---- Test drive (no metro needed) ---------------------------------------------------------

    private fun showTestDriveDialog() {
        val routes = NavigationController.savedTestRoutes()
        val labels = listOf(getString(R.string.test_drive_record)) +
            routes.map { String.format(Locale.US, "Drive again: %s (%.1f km, %d stations)", it.lineName, it.length / 1000.0, it.stations.size) } +
            (if (routes.isEmpty()) emptyList() else listOf(getString(R.string.test_drive_delete_all)))
        AlertDialog.Builder(this)
            .setTitle(R.string.test_drive)
            .setItems(labels.toTypedArray()) { _, i ->
                when {
                    i == 0 -> {
                        NavigationController.startRecordingTestRoute()
                        ensureLogging()
                        Toast.makeText(this, R.string.test_drive_record_help, Toast.LENGTH_LONG).show()
                    }
                    i <= routes.size -> {
                        NavigationController.rideTestRoute(routes[i - 1])
                        ensureLogging()
                        Toast.makeText(this, R.string.test_drive_ride_help, Toast.LENGTH_LONG).show()
                    }
                    else -> AlertDialog.Builder(this)
                        .setMessage(R.string.test_drive_delete_confirm)
                        .setPositiveButton(R.string.delete) { _, _ -> NavigationController.deleteTestRoutes() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** A test drive is only useful with its log: start logging if it is not running. */
    private fun ensureLogging() {
        if (!LocationTrackerService.isRunning.value && hasFineLocation()) {
            ContextCompat.startForegroundService(this, Intent(this, LocationTrackerService::class.java))
        }
    }

    private fun renderTestDrive(t: TestDriveState) {
        testDriveBar.visibility = if (t.mode == TestDriveMode.OFF) View.GONE else View.VISIBLE
        when (t.mode) {
            TestDriveMode.OFF -> return
            TestDriveMode.RECORDING -> {
                testDriveText.text = String.format(Locale.US, "● Recording test route · %.2f km · stations: %d", t.recordedMeters / 1000.0, t.recordedStations)
                btnTestDrivePrimary.setText(R.string.mark_station)
                btnTestDriveSecondary.setText(R.string.finish)
            }
            TestDriveMode.READY -> {
                testDriveText.text = String.format(Locale.US, "%s · GPS on", t.routeName ?: "Test route")
                btnTestDrivePrimary.setText(R.string.start_tunnel)
                btnTestDriveSecondary.setText(R.string.end_test)
            }
            TestDriveMode.TUNNEL -> {
                testDriveText.text = if (t.lastErrorMeters == null) "TUNNEL TEST · GPS hidden · waiting for real GPS to compare"
                else String.format(Locale.US, "TUNNEL TEST · GPS hidden · error %.0f m (avg %.0f, max %.0f)",
                    t.lastErrorMeters, t.meanErrorMeters ?: 0.0, t.maxErrorMeters ?: 0.0)
                btnTestDrivePrimary.setText(R.string.end_tunnel)
                btnTestDriveSecondary.setText(R.string.end_test)
            }
        }
    }

    private fun onTestDrivePrimary() {
        when (NavigationController.testDrive.value.mode) {
            TestDriveMode.RECORDING -> if (!NavigationController.markTestStation()) {
                Toast.makeText(this, R.string.test_drive_mark_failed, Toast.LENGTH_SHORT).show()
            }
            TestDriveMode.READY -> if (!NavigationController.startTunnelTest()) {
                Toast.makeText(this, R.string.test_drive_need_position, Toast.LENGTH_SHORT).show()
            }
            TestDriveMode.TUNNEL -> NavigationController.endTunnelTest()
            TestDriveMode.OFF -> {}
        }
    }

    private fun onTestDriveSecondary() {
        when (NavigationController.testDrive.value.mode) {
            TestDriveMode.RECORDING -> if (NavigationController.finishRecordingTestRoute() == null) {
                Toast.makeText(this, R.string.test_drive_too_short, Toast.LENGTH_LONG).show()
            }
            TestDriveMode.READY, TestDriveMode.TUNNEL -> NavigationController.endTestDrive()
            TestDriveMode.OFF -> {}
        }
    }

    private fun toggleDetails() {
        details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    /** What each marker colour means. */
    private fun legend(): CharSequence = SpannableStringBuilder().apply {
        listOf(
            PositionSource.GPS to "GPS", PositionSource.NETWORK to "Network", PositionSource.TRACK to "Track",
            PositionSource.DEAD_RECKONING to "Dead reckoning"
        ).forEach { (source, name) ->
            append("●", ForegroundColorSpan(NavigationOverlay.colorFor(source)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(" $name   ")
        }
        append("◆", ForegroundColorSpan(NavigationOverlay.EVENT_COLOR), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append(" Event   ")
        append("━", ForegroundColorSpan(NavigationOverlay.TRUTH_COLOR), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append(" Real GPS in a tunnel test (tap a marker for details)")
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

        overlay.current = s.takeIf { it.source != PositionSource.NONE }
        if (following || !centredOnce) centreOnPosition(fromButton = false)
        map.invalidate()

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
            if (s.networkCorrections > 0) append(" · ${s.networkCorrections} network fixes")
            if (s.curveCorrections > 0) append(" · ${s.curveCorrections} curve fixes")
        }

        lineText.text = s.lineName?.let { if (s.onTrack) "🚇 $it" else "🚇 $it (not on this line)" }
            ?: if (cachedLines > 0 && s.gpsAvailable) "No metro line within 400 m" else getString(R.string.searching_line)
        currentStationText.text = s.previousStation ?: getString(R.string.dash)
        nextStationText.text = s.nextStation?.let { "→ $it" } ?: getString(R.string.dash)
        showOrHide(distToNextText, when {
            s.distanceToNextMeters == null -> if (s.onTrack) "End of line" else ""
            else -> String.format(Locale.US, "Next stop in %.0f m · ~%s", s.distanceToNextMeters, formatEta(s.etaNextSec ?: 0))
        })
        metricsText.text = buildString {
            append(String.format(Locale.US, "%.0f km/h · %.0f°", s.speedMps * 3.6, s.headingDeg))
            if (!s.gpsAvailable && s.source != PositionSource.NONE) {
                append(String.format(Locale.US, " · %.0f m without GPS", s.distanceSinceGpsLossMeters))
            }
        }
        showOrHide(destinationText, when {
            s.destination == null -> ""
            s.stopsRemaining != null -> "Towards: ${s.destination} · ${s.stopsRemaining} stop(s)"
            else -> "Towards: ${s.destination}" + if (s.directionKnown) "" else " (direction not confirmed — tap Flip if wrong)"
        })
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

    private fun showOrHide(view: TextView, text: String) {
        view.text = text
        view.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
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
            HandyFeed.isWanted(this) && HandyFeed.status.value == HandyFeed.Status.NOT_ALLOWED ->
                Warning("Nemo Handy cannot get the position: in Developer options, set \"Select mock location app\" to Metro Navigator.") {
                    safeStart(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }
            HandyFeed.isWanted(this) && !LocationTrackerService.isRunning.value ->
                Warning("Start logging to send the position to Nemo Handy.", null)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !NotificationManagerCompat.from(this).areNotificationsEnabled() ->
                Warning("Notifications are off: the logging notification will be hidden.") { openNotificationSettings() }
            caps != null && !caps.hasLinearAcceleration && !caps.hasAccelerometer ->
                Warning("This phone has no motion sensors: underground navigation cannot estimate movement.", null)
            caps != null && !caps.hasGnss -> Warning("This phone has no GPS receiver: only network location is available.", null)
            caps != null && !caps.hasRotationVector ->
                Warning("No orientation sensor: pick your destination so the direction of travel is known.", null)
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

    private fun hasAnyLocation() = hasFineLocation() ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        permissionAskedOnce = true
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
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

    private companion object {
        const val PERMISSION_REQUEST_CODE = 3001
        const val LOCATION_SETTINGS_REQUEST_CODE = 3002
        const val CLIENT = "activity"
        const val OSMDROID_PREFS = "osmdroid"
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
