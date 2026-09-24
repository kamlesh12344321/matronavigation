package com.example.testgpslocation.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Log
import com.example.testgpslocation.models.GpsPoint
import com.example.testgpslocation.sensor.DeviceCapabilities
import com.example.testgpslocation.sensor.SensorHub
import com.example.testgpslocation.utils.GeoToolbox
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/** A position worth logging / listing: every GPS fix (≥1 s apart) and every 2 s without GPS. */
data class PositionEvent(
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val source: PositionSource,
    val speedMps: Double,
    val gpsAvailable: Boolean,
    val uncertaintyMeters: Double,
    val gpsAccuracyMeters: Float?,
    val satellitesUsed: Int,
    val lineName: String?,
    val nextStation: String?,
    val mock: Boolean
)

data class Satellites(val inView: Int = 0, val usedInFix: Int = 0)

/**
 * Process-wide owner of the sensors, the navigator and the metro line cache. Runs while at
 * least one client (the visible screen or the logging service) holds it, so navigation keeps
 * going in the background while logging. All state changes happen on the main thread.
 */
@SuppressLint("StaticFieldLeak") // holds the application context only
object NavigationController : SensorHub.Listener {

    private const val TAG = "NavigationController"
    private const val MAX_HISTORY = 500
    private const val LOOKUP_INTERVAL_MS = 10_000L
    private const val LOOKUP_DISTANCE_METERS = 200.0
    private const val INITIAL_BACKOFF_MS = 30_000L
    private const val MAX_BACKOFF_MS = 10 * 60_000L

    private lateinit var appContext: Context
    private lateinit var sensorHub: SensorHub
    private lateinit var routeService: MetroRouteService
    private val navigator = UndergroundNavigator()
    private val errorHandler = CoroutineExceptionHandler { _, e -> Log.e(TAG, "Background task failed", e) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errorHandler)
    private val clients = mutableSetOf<String>()

    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history

    private val _positions = MutableSharedFlow<PositionEvent>(extraBufferCapacity = 128)
    val positions: SharedFlow<PositionEvent> = _positions

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val messages: SharedFlow<String> = _messages

    private val _cachedLines = MutableStateFlow(0)
    val cachedLines: StateFlow<Int> = _cachedLines

    private val _lineStatus = MutableStateFlow("Metro line data: checking…")
    val lineStatus: StateFlow<String> = _lineStatus

    private val _satellites = MutableStateFlow(Satellites())
    val satellites: StateFlow<Satellites> = _satellites

    val capabilities: DeviceCapabilities? get() = if (::sensorHub.isInitialized) sensorHub.capabilities else null
    val isLocationEnabled: Boolean get() = !::sensorHub.isInitialized || sensorHub.isLocationEnabled()

    private var lastPublishMs = 0L
    private var lastEventMs = 0L
    private var lastListedLat = 0.0
    private var lastListedLon = 0.0
    private var lastListedMs = 0L

    // Offline lookup throttling
    private var lookupJob: Job? = null
    private var lastLookupMs = 0L
    private var lastLookupLat = 0.0
    private var lastLookupLon = 0.0

    // Download scheduling with exponential backoff
    private var downloadJob: Job? = null
    private var nextDownloadAttemptMs = 0L
    private var downloadBackoffMs = INITIAL_BACKOFF_MS

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        sensorHub = SensorHub(appContext).also { it.listener = this }
        routeService = MetroRouteService(appContext)
        scope.launch { _cachedLines.value = routeService.preload() }
        registerNetworkCallback()
    }

    /** Returns false if tracking could not start (no location permission). */
    fun acquire(client: String): Boolean {
        check(::appContext.isInitialized) { "call init() first" }
        clients.add(client)
        val ok = sensorHub.start()
        if (!ok) clients.remove(client)
        return ok
    }

    fun release(client: String) {
        clients.remove(client)
        if (clients.isEmpty() && ::sensorHub.isInitialized) sensorHub.stop()
    }

    /** Restart sensors (e.g. after a permission was granted) without losing the navigation state. */
    fun restartSensors() {
        if (!::sensorHub.isInitialized || clients.isEmpty()) return
        sensorHub.stop()
        sensorHub.start()
    }

    fun setDestination(index: Int?) { navigator.setDestination(index); drainMessages(); publish(force = true) }
    fun flipDirection() { navigator.flipDirection(); drainMessages(); publish(force = true) }

    /** Download lines around the current position now, even if this area was downloaded before. */
    fun refreshLinesHere() = startDownload(force = true, radius = MetroRouteService.SEARCH_RADIUS_METERS)

    /** Download every metro line within ~30 km (whole city) for offline use. */
    fun downloadCity() = startDownload(force = true, radius = MetroRouteService.CITY_RADIUS_METERS)

    // ---- SensorHub.Listener ------------------------------------------------------------

    override fun onLocationFix(point: GpsPoint) {
        val now = SystemClock.elapsedRealtime()
        navigator.onLocationFix(point, now)
        if (navigator.hasFix) maybeLookupLines(navigator.currentLat, navigator.currentLon, now)
        drainMessages()
        publish(force = true)
        if (navigator.isGpsAvailable && point.isGnss && now - lastEventMs >= 1000L) emitPosition(now)
    }

    override fun onGpsLost(elapsedMs: Long) {
        navigator.onGpsLost(elapsedMs)
        if (navigator.hasFix) maybeLookupLines(navigator.currentLat, navigator.currentLon, elapsedMs, force = true)
        drainMessages()
        publish(force = true)
    }

    override fun onMotionSample(sample: MotionSample) {
        navigator.onMotion(sample)
        drainMessages()
        publish(force = false)
        if (!navigator.isGpsAvailable && navigator.hasFix && sample.elapsedMs - lastEventMs >= 2000L) emitPosition(sample.elapsedMs)
    }

    override fun onSatellites(inView: Int, usedInFix: Int) {
        _satellites.value = Satellites(inView, usedInFix)
    }

    // ---- Metro line data ------------------------------------------------------------------

    /** Offline lookup (cheap, no network): pick the nearest saved line and the alternatives. */
    private fun maybeLookupLines(lat: Double, lon: Double, now: Long, force: Boolean = false) {
        if (lookupJob?.isActive == true) return
        val moved = GeoToolbox.calculateDistance(lastLookupLat, lastLookupLon, lat, lon)
        if (!force && lastLookupMs != 0L && now - lastLookupMs < LOOKUP_INTERVAL_MS && moved < LOOKUP_DISTANCE_METERS) return
        lastLookupMs = now; lastLookupLat = lat; lastLookupLon = lon
        lookupJob = scope.launch {
            val near = routeService.cachedNear(lat, lon)
            navigator.setAlternativeRoutes(near)
            val current = navigator.route
            val currentOffset = current?.project(navigator.currentLat, navigator.currentLon)?.offsetMeters ?: Double.MAX_VALUE
            // Change line only when the current one is no longer near.
            if (near.isNotEmpty() && currentOffset > MetroRouteService.MAX_ROUTE_DISTANCE_METERS) {
                navigator.setRoute(near.first())
                drainMessages()
                publish(force = true)
            }
            updateLineStatus(near.isNotEmpty())
            if (!routeService.isAreaDownloaded(lat, lon)) startDownload(force = false, radius = MetroRouteService.SEARCH_RADIUS_METERS)
        }
    }

    private fun updateLineStatus(nearLine: Boolean) {
        if (downloadJob?.isActive == true) return
        val count = _cachedLines.value
        _lineStatus.value = when {
            nearLine -> "Metro line data: saved on phone ($count lines) — works offline"
            count > 0 -> "Metro line data: $count lines saved; none within 400 m of here"
            else -> _lineStatus.value
        }
    }

    private fun startDownload(force: Boolean, radius: Int) {
        if (!navigator.hasFix) { _lineStatus.value = "Metro line data: waiting for a location fix"; return }
        if (downloadJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now < nextDownloadAttemptMs) return
        val lat = navigator.currentLat; val lon = navigator.currentLon
        _lineStatus.value = if (radius > MetroRouteService.SEARCH_RADIUS_METERS) "Metro line data: downloading the whole city… (can take a few minutes)"
        else "Metro line data: downloading lines near you…"
        downloadJob = scope.launch {
            val result = routeService.download(lat, lon, radius, force)
            _cachedLines.value = routeService.cachedLineCount
            when (result) {
                is DownloadResult.Saved -> {
                    downloadBackoffMs = INITIAL_BACKOFF_MS
                    nextDownloadAttemptMs = 0L
                    _lineStatus.value = "Metro line data: saved ${result.lines} line(s) — works offline"
                    _messages.tryEmit("Saved ${result.lines} metro line(s) for offline use")
                    maybeLookupLines(navigator.currentLat, navigator.currentLon, SystemClock.elapsedRealtime(), force = true)
                }
                DownloadResult.AlreadyDownloaded -> {
                    downloadBackoffMs = INITIAL_BACKOFF_MS
                    updateLineStatus(navigator.route != null)
                }
                DownloadResult.NothingHere -> {
                    downloadBackoffMs = INITIAL_BACKOFF_MS
                    _lineStatus.value = "Metro line data: no metro lines within ${radius / 1000} km of here"
                }
                DownloadResult.NoNetwork -> {
                    // No backoff growth: the network callback retries as soon as a network appears.
                    nextDownloadAttemptMs = SystemClock.elapsedRealtime() + INITIAL_BACKOFF_MS
                    _lineStatus.value = if (_cachedLines.value > 0) "Metro line data: offline — using ${_cachedLines.value} saved line(s)"
                    else "Metro line data: no network — lines will download when online"
                }
                is DownloadResult.Failed -> {
                    nextDownloadAttemptMs = SystemClock.elapsedRealtime() + downloadBackoffMs
                    _lineStatus.value = "Metro line data: download failed (${result.reason}); retrying in ${downloadBackoffMs / 1000} s"
                    downloadBackoffMs = min(downloadBackoffMs * 2, MAX_BACKOFF_MS)
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch {
                        nextDownloadAttemptMs = 0L
                        if (navigator.hasFix && clients.isNotEmpty()) {
                            maybeLookupLines(navigator.currentLat, navigator.currentLon, SystemClock.elapsedRealtime(), force = true)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Network callback unavailable", e)
        }
    }

    // ---- Output ----------------------------------------------------------------------------

    private fun emitPosition(elapsedMs: Long) {
        lastEventMs = elapsedMs
        val s = navigator.state()
        if (s.source == PositionSource.NONE) return
        val event = PositionEvent(
            System.currentTimeMillis(), s.latitude, s.longitude, s.source, s.speedMps, s.gpsAvailable,
            s.uncertaintyMeters, s.gpsAccuracyMeters, _satellites.value.usedInFix, s.lineName, s.nextStation, s.mockLocation
        )
        _positions.tryEmit(event)
        // Keep the on-screen list readable: skip GPS lines while standing still.
        val moved = GeoToolbox.calculateDistance(lastListedLat, lastListedLon, event.latitude, event.longitude)
        if (event.gpsAvailable && moved < 5.0 && elapsedMs - lastListedMs < 10_000L) return
        lastListedLat = event.latitude; lastListedLon = event.longitude; lastListedMs = elapsedMs
        addHistory(String.format(Locale.US, "[%s] %.6f, %.6f  (%s ±%.0f m, %.0f km/h)%s",
            timeFormat.format(Date(event.timestamp)), event.latitude, event.longitude, event.source.label,
            event.uncertaintyMeters, event.speedMps * 3.6, event.nextStation?.let { "  next: $it" } ?: ""))
    }

    private fun drainMessages() {
        if (navigator.events.isEmpty()) return
        navigator.events.forEach { msg ->
            _messages.tryEmit(msg)
            addHistory("[${timeFormat.format(Date())}] ⚑ $msg")
        }
        navigator.events.clear()
    }

    private fun addHistory(line: String) {
        _history.value = (listOf(line) + _history.value).take(MAX_HISTORY)
    }

    private fun publish(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPublishMs < 250L) return
        lastPublishMs = now
        _state.value = navigator.state()
    }
}
