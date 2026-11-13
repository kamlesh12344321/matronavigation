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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * A position to log and mark on the map: checked at every GPS fix and every second without GPS,
 * kept when it is new (see [PositionDeduper]).
 */
data class PositionEvent(
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val source: PositionSource,
    val speedMps: Double
)

/**
 * Process-wide owner of the sensors, the navigator and the metro line cache. Runs while at
 * least one client (the visible screen or the logging service) holds it, so navigation keeps
 * going in the background while logging. All state changes happen on the main thread.
 */
@SuppressLint("StaticFieldLeak") // holds the application context only
object NavigationController : SensorHub.Listener {

    private const val TAG = "NavigationController"
    private const val MAX_TRAIL_POINTS = 10_000 // ~2.8 h of 1 s GPS fixes
    /** Fixes arrive about 1 s apart; a strict 1 s gap dropped every fix that came a little early. */
    private const val MIN_GPS_EVENT_GAP_MS = 500L
    private const val NO_GPS_ROW_MS = 1_000L
    /** The current ride, to carry on after a restart. */
    private const val RIDE_STATE_FILE = "ride_state.json"
    private const val RIDE_SAVE_INTERVAL_MS = 5_000L
    /** A saved ride older than this says nothing about where the train is now. */
    private const val RIDE_RESUME_MAX_AGE_MS = 5 * 60_000L
    private const val LOOKUP_INTERVAL_MS = 10_000L
    private const val LOOKUP_DISTANCE_METERS = 200.0
    private const val INITIAL_BACKOFF_MS = 30_000L
    private const val MAX_BACKOFF_MS = 10 * 60_000L
    /** Learnt-data files of earlier versions (fingerprints, run speeds): nothing is learnt any more. */
    private val OBSOLETE_FILES = listOf("radio_map.txt", "ride_memory.txt", "radio_map.txt.tmp", "ride_memory.txt.tmp")

    private lateinit var appContext: Context
    private lateinit var sensorHub: SensorHub
    private lateinit var routeService: MetroRouteService
    private val navigator = UndergroundNavigator()
    private val errorHandler = CoroutineExceptionHandler { _, e -> Log.e(TAG, "Background task failed", e) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errorHandler)
    private val clients = mutableSetOf<String>()

    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state

    /** Positions to mark on the map, oldest first. */
    private val _trail = MutableStateFlow<List<PositionEvent>>(emptyList())
    val trail: StateFlow<List<PositionEvent>> = _trail

    /** The metro line being followed, for drawing it. */
    private val _route = MutableStateFlow<MetroRoute?>(null)
    val route: StateFlow<MetroRoute?> = _route

    private val _positions = MutableSharedFlow<PositionEvent>(extraBufferCapacity = 128)
    val positions: SharedFlow<PositionEvent> = _positions

    private val _cachedLines = MutableStateFlow(0)

    private val _lineStatus = MutableStateFlow("Metro line data: checking…")
    val lineStatus: StateFlow<String> = _lineStatus

    val capabilities: DeviceCapabilities? get() = if (::sensorHub.isInitialized) sensorHub.capabilities else null
    val isLocationEnabled: Boolean get() = !::sensorHub.isInitialized || sensorHub.isLocationEnabled()
    val isNetworkLocationEnabled: Boolean get() = !::sensorHub.isInitialized || sensorHub.isNetworkLocationEnabled()

    private var lastRideSaveMs = 0L
    private var rideStateSaved = false
    private var lastPublishMs = 0L
    private var lastEventMs = 0L
    /** Only new positions are logged and marked (plus a row every 30 s while standing). */
    private val deduper = PositionDeduper()

    // Offline lookup throttling
    private var lookupJob: Job? = null
    private var lastLookupMs = 0L
    private var lastLookupLat = 0.0
    private var lastLookupLon = 0.0

    // Download scheduling with exponential backoff
    private var downloadJob: Job? = null
    private var nextDownloadAttemptMs = 0L
    private var downloadBackoffMs = INITIAL_BACKOFF_MS

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        sensorHub = SensorHub(appContext).also { it.listener = this }
        routeService = MetroRouteService(appContext)
        scope.launch {
            _lineStatus.value = "Metro line data: loading offline lines…"
            _cachedLines.value = routeService.preload()
            _lineStatus.value = if (_cachedLines.value > 0) "Metro line data: ${_cachedLines.value} lines on phone — works offline"
            else "Metro line data: none for this area yet — will download when online"
            resumeRide()
            if (navigator.hasFix) maybeLookupLines(navigator.currentLat, navigator.currentLon, SystemClock.elapsedRealtime(), force = true)
        }
        scope.launch(Dispatchers.IO) { OBSOLETE_FILES.forEach { File(appContext.filesDir, it).delete() } }
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
        if (clients.isEmpty() && ::sensorHub.isInitialized) {
            sensorHub.stop()
        }
    }

    /** Restart sensors (e.g. after a permission was granted) without losing the navigation state. */
    fun restartSensors() {
        if (!::sensorHub.isInitialized || clients.isEmpty()) return
        sensorHub.stop()
        sensorHub.start()
    }

    /** The step permission may have been granted in Settings while the app was away. */
    fun refreshStepSensor() {
        if (::sensorHub.isInitialized && sensorHub.isRunning) sensorHub.ensureStepSensor()
    }

    /** Download every metro line within ~30 km (whole city) now, even if this area was downloaded before. */
    fun downloadCity() = startDownload(force = true, radius = MetroRouteService.CITY_RADIUS_METERS)

    /** Removes the position markers (the log file keeps them). */
    fun clearMap() {
        _trail.value = emptyList()
        deduper.reset() // the current position gets a marker again
    }

    // ---- SensorHub.Listener ------------------------------------------------------------

    override fun onLocationFix(point: GpsPoint) {
        val now = SystemClock.elapsedRealtime()
        navigator.onLocationFix(point, now)
        if (navigator.hasFix) maybeLookupLines(navigator.currentLat, navigator.currentLon, now)
        drainMessages()
        publish(force = true)
        if (navigator.isGpsAvailable && point.isGnss && now - lastEventMs >= MIN_GPS_EVENT_GAP_MS) emitPosition(now)
        if (!navigator.isGpsAvailable && navigator.hasFix && now - lastEventMs >= NO_GPS_ROW_MS) emitPosition(now)
    }

    /** Only as a starting point: anything better that has arrived wins. */
    override fun onLastKnownFix(point: GpsPoint) {
        if (navigator.hasFix) return
        val now = SystemClock.elapsedRealtime()
        navigator.onLocationFix(point, now)
        if (navigator.hasFix) maybeLookupLines(navigator.currentLat, navigator.currentLon, now)
        drainMessages()
        publish(force = true)
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
        if (!navigator.isGpsAvailable && navigator.hasFix && sample.elapsedMs - lastEventMs >= NO_GPS_ROW_MS) emitPosition(sample.elapsedMs)
        if (sample.elapsedMs - lastRideSaveMs >= RIDE_SAVE_INTERVAL_MS) saveRideState(sample.elapsedMs)
    }

    // ---- Carrying on after a restart -------------------------------------------------------

    /** With GPS there is nothing to recover. */
    private fun saveRideState(now: Long) {
        lastRideSaveMs = now
        val file = File(appContext.filesDir, RIDE_STATE_FILE)
        val state = navigator.rideState(System.currentTimeMillis())
        if (state == null) {
            if (rideStateSaved) scope.launch(Dispatchers.IO) { file.delete() }
            rideStateSaved = false
            return
        }
        rideStateSaved = true
        val text = RouteJson.writeRide(state)
        scope.launch(Dispatchers.IO) {
            try {
                val tmp = File(appContext.filesDir, "$RIDE_STATE_FILE.tmp")
                tmp.writeText(text)
                tmp.renameTo(file)
            } catch (e: Exception) {
                Log.w(TAG, "Saving the ride failed", e)
            }
        }
    }

    /** After the app was restarted in the middle of a ride (crash, or killed by Android). */
    private suspend fun resumeRide() {
        val file = File(appContext.filesDir, RIDE_STATE_FILE)
        val saved = withContext(Dispatchers.IO) {
            try { if (file.exists()) RouteJson.readRide(file.readText()) else null } catch (e: Exception) {
                Log.w(TAG, "Saved ride unreadable", e); null
            }
        } ?: return
        val ageMs = System.currentTimeMillis() - saved.savedAtMs
        if (ageMs !in 0L..RIDE_RESUME_MAX_AGE_MS) return
        val near = routeService.cachedNear(saved.latitude, saved.longitude)
        navigator.setAlternativeRoutes(near)
        navigator.resume(saved, near.firstOrNull { it.id == saved.routeId }, ageMs / 1000.0, SystemClock.elapsedRealtime())
        drainMessages()
        publish(force = true)
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
            // Anywhere in the world: the first time we have a position and a network in an area,
            // save the whole current city's metro network, so it is on the phone before going underground.
            if (!routeService.isAreaDownloaded(lat, lon)) startDownload(force = false, radius = MetroRouteService.CITY_RADIUS_METERS)
        }
    }

    private fun updateLineStatus(nearLine: Boolean) {
        if (downloadJob?.isActive == true) return
        val count = _cachedLines.value
        _lineStatus.value = when {
            nearLine -> "Metro line data: saved on phone ($count lines) — works offline"
            count > 0 -> "Metro line data: $count lines on phone; none within 400 m of here"
            else -> _lineStatus.value
        }
    }

    private fun startDownload(force: Boolean, radius: Int) {
        if (!navigator.hasFix) { _lineStatus.value = "Metro line data: waiting for a location fix"; return }
        if (downloadJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now < nextDownloadAttemptMs) return
        val lat = navigator.currentLat; val lon = navigator.currentLon
        _lineStatus.value = if (radius > MetroRouteService.SEARCH_RADIUS_METERS) "Metro line data: downloading lines near you, then the whole city…"
        else "Metro line data: downloading lines near you…"
        downloadJob = scope.launch {
            val result = routeService.download(lat, lon, radius, force)
            _cachedLines.value = routeService.cachedLineCount
            when (result) {
                is DownloadResult.Saved -> {
                    downloadBackoffMs = INITIAL_BACKOFF_MS
                    nextDownloadAttemptMs = 0L
                    _lineStatus.value = if (result.wholeCity) "Metro line data: saved ${result.lines} line(s) — works offline"
                    else "Metro line data: saved ${result.lines} line(s) near you — works offline (whole city not available yet)"
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
        val decision = deduper.decide(s.latitude, s.longitude, elapsedMs)
        if (decision == PositionDeduper.Decision.SKIP) return // same position as the last row
        val event = PositionEvent(System.currentTimeMillis(), s.latitude, s.longitude, s.source, s.speedMps)
        _positions.tryEmit(event)
        if (decision != PositionDeduper.Decision.LOG_AND_MARK) return
        _trail.value = (_trail.value + event).takeLast(MAX_TRAIL_POINTS)
    }

    /** The engine's event notes (stops, departures…) are not shown or logged: drop them. */
    private fun drainMessages() = navigator.events.clear()

    private fun publish(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPublishMs < 250L) return
        lastPublishMs = now
        _state.value = navigator.state()
        _route.value = navigator.route
    }
}

/**
 * Decides which positions go to the log and the map: every new position, and an unchanged one
 * only every [maxQuietMs] (a train standing at a platform gives one row, not one every 2 s, and
 * the log still never goes quiet for longer than that). "Unchanged" is at the log's precision
 * (6 decimals, about 0.1 m).
 */
class PositionDeduper(private val maxQuietMs: Long = 30_000L) {

    enum class Decision { SKIP, LOG, LOG_AND_MARK }

    private var lastLat = Long.MIN_VALUE
    private var lastLon = Long.MIN_VALUE
    private var lastMs = 0L

    fun decide(lat: Double, lon: Double, elapsedMs: Long): Decision {
        val la = (lat * 1e6).roundToLong()
        val lo = (lon * 1e6).roundToLong()
        val moved = la != lastLat || lo != lastLon
        if (!moved && elapsedMs - lastMs < maxQuietMs) return Decision.SKIP
        lastLat = la; lastLon = lo; lastMs = elapsedMs
        return if (moved) Decision.LOG_AND_MARK else Decision.LOG
    }

    /** The next position is treated as new (e.g. after the map was cleared). */
    fun reset() {
        lastLat = Long.MIN_VALUE
        lastLon = Long.MIN_VALUE
    }
}
