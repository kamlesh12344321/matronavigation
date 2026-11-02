package com.example.testgpslocation.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Log
import com.example.testgpslocation.models.GpsPoint
import com.example.testgpslocation.sensor.CellScanner
import com.example.testgpslocation.sensor.DeviceCapabilities
import com.example.testgpslocation.sensor.RadioCapabilities
import com.example.testgpslocation.sensor.RadioScanner
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * A position to log and mark on the map: checked at every GPS fix and every 2 s without GPS,
 * kept when it is new (see [PositionDeduper]).
 */
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
    val mock: Boolean,
    /** Tunnel test only: the real GPS position at the same moment and the estimate's distance from it. */
    val truthLatitude: Double? = null,
    val truthLongitude: Double? = null,
    val errorMeters: Double? = null,
    /** What the motion sensors saw (state, vibration, turn rate): for tuning stop and curve detection. */
    val sensors: String? = null
)

data class Satellites(val inView: Int = 0, val usedInFix: Int = 0)

/** A position marker on the map: every listed position, coloured by where it came from. */
data class TrailPoint(
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val source: PositionSource,
    val uncertaintyMeters: Double,
    val speedMps: Double,
    val nextStation: String?
)

/** A navigation event (station stop, GPS lost, line change…) at the position where it happened. */
data class MapEvent(val timestamp: Long, val latitude: Double, val longitude: Double, val text: String)

/**
 * Process-wide owner of the sensors, the navigator and the metro line cache. Runs while at
 * least one client (the visible screen or the logging service) holds it, so navigation keeps
 * going in the background while logging. All state changes happen on the main thread.
 */
@SuppressLint("StaticFieldLeak") // holds the application context only
object NavigationController : SensorHub.Listener, RadioScanner.Listener, CellScanner.Listener {

    private const val TAG = "NavigationController"
    private const val MAX_TRAIL_POINTS = 10_000 // ~2.8 h of 1 s GPS fixes
    /** Fixes arrive about 1 s apart; a strict 1 s gap dropped every fix that came a little early. */
    private const val MIN_GPS_EVENT_GAP_MS = 500L
    private const val TEST_ROUTES_DIR = "test_routes"
    /** A real-GPS reference older than this is not compared with the estimate. */
    private const val TRUTH_MAX_AGE_MS = 3_000L
    private const val TRUTH_TRAIL_SPACING_METERS = 5.0
    private const val MAX_MAP_EVENTS = 300
    private const val LOOKUP_INTERVAL_MS = 10_000L
    private const val LOOKUP_DISTANCE_METERS = 200.0
    private const val INITIAL_BACKOFF_MS = 30_000L
    private const val MAX_BACKOFF_MS = 10 * 60_000L
    private const val RADIO_MAP_FILE = "radio_map.txt"
    private const val RIDE_MEMORY_FILE = "ride_memory.txt"
    private const val RADIO_SAVE_INTERVAL_MS = 60_000L

    private lateinit var appContext: Context
    private lateinit var sensorHub: SensorHub
    private lateinit var radioScanner: RadioScanner
    private lateinit var cellScanner: CellScanner
    private lateinit var routeService: MetroRouteService
    private val navigator = UndergroundNavigator()
    private val errorHandler = CoroutineExceptionHandler { _, e -> Log.e(TAG, "Background task failed", e) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errorHandler)
    private val clients = mutableSetOf<String>()

    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state

    /** Positions to mark on the map, oldest first. */
    private val _trail = MutableStateFlow<List<TrailPoint>>(emptyList())
    val trail: StateFlow<List<TrailPoint>> = _trail

    private val _mapEvents = MutableStateFlow<List<MapEvent>>(emptyList())
    val mapEvents: StateFlow<List<MapEvent>> = _mapEvents

    /** The metro line being followed, for drawing it. */
    private val _route = MutableStateFlow<MetroRoute?>(null)
    val route: StateFlow<MetroRoute?> = _route

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
    val radioCapabilities: RadioCapabilities? get() = if (::radioScanner.isInitialized) radioScanner.capabilities else null

    // Radio map persistence
    private var radioMapLoaded = false
    private var savedRadioVersion = 0L
    private var savedMemoryVersion = 0L
    private var lastRadioSaveMs = 0L
    private var radioSaveJob: Job? = null

    private var lastPublishMs = 0L
    private var lastEventMs = 0L
    private var lastMotion: MotionSample? = null
    /** Only new positions are logged and marked (plus a row every 30 s while standing). */
    private val deduper = PositionDeduper()

    // Test drive (no metro needed): see TestDriveMode
    private lateinit var testRouteStore: TestRouteStore
    private var testMode = TestDriveMode.OFF
    private var recorder: TestRouteRecorder? = null
    private var testRoute: MetroRoute? = null
    /** Tunnel test: the latest real GPS fix (never given to navigation) and when it came. */
    private var truth: GpsPoint? = null
    private var truthReceivedMs = 0L
    private val errors = ErrorStats()

    private val _testDrive = MutableStateFlow(TestDriveState())
    val testDrive: StateFlow<TestDriveState> = _testDrive

    /** Tunnel test: the real GPS path, to draw under the estimate. */
    private val _truthTrail = MutableStateFlow<List<TrackPoint>>(emptyList())
    val truthTrail: StateFlow<List<TrackPoint>> = _truthTrail

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
        radioScanner = RadioScanner(appContext).also {
            it.listener = this
            it.isKnownTransmitter = { key -> navigator.radioMap.anchor(key) != null }
        }
        cellScanner = CellScanner(appContext).also { it.listener = this }
        routeService = MetroRouteService(appContext)
        testRouteStore = TestRouteStore(File(appContext.filesDir, TEST_ROUTES_DIR))
        scope.launch {
            _lineStatus.value = "Metro line data: loading offline lines…"
            _cachedLines.value = routeService.preload()
            _lineStatus.value = if (_cachedLines.value > 0) "Metro line data: ${_cachedLines.value} lines on phone — works offline"
            else "Metro line data: none for this area yet — will download when online"
            if (navigator.hasFix) maybeLookupLines(navigator.currentLat, navigator.currentLon, SystemClock.elapsedRealtime(), force = true)
        }
        scope.launch { loadRadioMap() }
        registerNetworkCallback()
    }

    /** Returns false if tracking could not start (no location permission). */
    fun acquire(client: String): Boolean {
        check(::appContext.isInitialized) { "call init() first" }
        clients.add(client)
        val ok = sensorHub.start()
        if (!ok) clients.remove(client) else { radioScanner.start(); cellScanner.start() }
        return ok
    }

    fun release(client: String) {
        clients.remove(client)
        if (clients.isEmpty() && ::sensorHub.isInitialized) {
            sensorHub.stop()
            radioScanner.stop()
            cellScanner.stop()
            saveRadioMap(force = true)
        }
    }

    /** Restart sensors (e.g. after a permission was granted) without losing the navigation state. */
    fun restartSensors() {
        if (!::sensorHub.isInitialized || clients.isEmpty()) return
        sensorHub.stop()
        radioScanner.stop()
        cellScanner.stop()
        if (sensorHub.start()) { radioScanner.start(); cellScanner.start() }
    }

    fun setDestination(index: Int?) { navigator.setDestination(index); drainMessages(); publish(force = true) }
    fun flipDirection() { navigator.flipDirection(); drainMessages(); publish(force = true) }

    /** Download lines around the current position now, even if this area was downloaded before. */
    fun refreshLinesHere() = startDownload(force = true, radius = MetroRouteService.SEARCH_RADIUS_METERS)

    /** Download every metro line within ~30 km (whole city) for offline use. */
    fun downloadCity() = startDownload(force = true, radius = MetroRouteService.CITY_RADIUS_METERS)

    /** Removes the position and event markers (the log file keeps them). */
    fun clearMap() {
        _trail.value = emptyList()
        _mapEvents.value = emptyList()
        deduper.reset() // the current position gets a marker again
    }

    // ---- Test drive (no metro needed) -------------------------------------------------------

    /** Saved test routes, newest first (small files, read on demand). */
    fun savedTestRoutes(): List<MetroRoute> = if (::testRouteStore.isInitialized) testRouteStore.list() else emptyList()

    fun deleteTestRoutes() {
        if (::testRouteStore.isInitialized) testRouteStore.deleteAll()
    }

    /** First trip: record the road and its stops with GPS. */
    fun startRecordingTestRoute() {
        endTestDrive()
        recorder = TestRouteRecorder()
        testMode = TestDriveMode.RECORDING
        note("Test drive: recording the route with GPS")
        publishTestDrive()
    }

    /** A station where the vehicle is now; false without a fix yet or with a station this close. */
    fun markTestStation(): Boolean = (recorder?.markStation() ?: false).also { if (it) publishTestDrive() }

    /** Saves the recorded route and starts following it; null when it is too short. */
    fun finishRecordingTestRoute(): MetroRoute? {
        val r = recorder ?: return null
        recorder = null
        val name = "Test route " + SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date())
        val route = r.build("test_${System.currentTimeMillis()}", name)
        if (route == null) {
            testMode = TestDriveMode.OFF
            note(String.format(Locale.US, "Test drive: route too short to use (%.0f m, needs %.0f m)", r.lengthMeters, TestRouteRecorder.MIN_ROUTE_METERS))
            publishTestDrive()
            return null
        }
        note(if (testRouteStore.save(route)) String.format(Locale.US, "Test drive: saved %s (%.1f km, %d stations)",
            route.lineName, route.length / 1000.0, route.stations.size) else "Test drive: could not save the route (storage full?)")
        rideTestRoute(route)
        return route
    }

    /** A later trip on a recorded route; GPS is still used until [startTunnelTest]. */
    fun rideTestRoute(route: MetroRoute) {
        endTunnelTest()
        recorder = null
        testRoute = route
        testMode = TestDriveMode.READY
        navigator.setAlternativeRoutes(emptyList()) // a nearby metro line must not take over
        navigator.setRoute(route)
        drainMessages()
        publishTestDrive()
        publish(force = true)
    }

    /** Hides GPS from navigation, as in a tunnel; GPS is kept only to measure the error. */
    fun startTunnelTest(): Boolean {
        if (testMode != TestDriveMode.READY || !navigator.hasFix) return false
        testMode = TestDriveMode.TUNNEL
        errors.reset()
        truth = null
        _truthTrail.value = emptyList()
        navigator.onGpsLost(SystemClock.elapsedRealtime())
        note("Tunnel test started: navigating without GPS")
        publishTestDrive()
        publish(force = true)
        return true
    }

    /** GPS is used again; the next fix reports how far off the estimate was. */
    fun endTunnelTest() {
        if (testMode != TestDriveMode.TUNNEL) return
        testMode = TestDriveMode.READY
        errors.mean?.let {
            note(String.format(Locale.US, "Tunnel test ended: %d comparisons with real GPS, error mean %.0f m, max %.0f m", errors.count, it, errors.max))
        }
        truth = null
        publishTestDrive()
    }

    fun endTestDrive() {
        endTunnelTest()
        val wasActive = testMode != TestDriveMode.OFF
        recorder = null
        testRoute = null
        testMode = TestDriveMode.OFF
        if (wasActive) {
            navigator.setRoute(null)
            note("Test drive ended")
            if (navigator.hasFix) maybeLookupLines(navigator.currentLat, navigator.currentLon, SystemClock.elapsedRealtime(), force = true)
        }
        publishTestDrive()
        publish(force = true)
    }

    private fun recordTruth(point: GpsPoint, now: Long) {
        truth = point
        truthReceivedMs = now
        val last = _truthTrail.value.lastOrNull()
        if (last == null || GeoToolbox.calculateDistance(last.lat, last.lon, point.latitude, point.longitude) >= TRUTH_TRAIL_SPACING_METERS) {
            _truthTrail.value = (_truthTrail.value + TrackPoint(point.latitude, point.longitude)).takeLast(MAX_TRAIL_POINTS)
        }
    }

    private fun publishTestDrive() {
        val r = recorder
        _testDrive.value = TestDriveState(
            mode = testMode,
            routeName = testRoute?.lineName,
            recordedMeters = r?.lengthMeters ?: 0.0,
            recordedStations = r?.stationCount ?: 0,
            lastErrorMeters = errors.last,
            meanErrorMeters = errors.mean,
            maxErrorMeters = if (errors.count > 0) errors.max else null,
            comparisons = errors.count
        )
    }

    /** A message from the controller itself, logged and marked like the navigator's events. */
    private fun note(message: String) {
        navigator.events.add(message)
        drainMessages()
    }

    // ---- SensorHub.Listener ------------------------------------------------------------

    override fun onLocationFix(point: GpsPoint) {
        val now = SystemClock.elapsedRealtime()
        if (point.isGnss) recorder?.let {
            it.onFix(point.latitude, point.longitude, point.accuracy, if (point.hasSpeed) point.speedMeterPerSecond else null, point.elapsedRealtimeMs)
            publishTestDrive()
        }
        if (testMode == TestDriveMode.TUNNEL) {
            // Tunnel test: navigation never sees the fix; it is only the reference for the error.
            if (point.isGnss && !point.isMock) recordTruth(point, now)
            return
        }
        navigator.onLocationFix(point, now)
        if (navigator.hasFix) maybeLookupLines(navigator.currentLat, navigator.currentLon, now)
        drainMessages()
        publish(force = true)
        if (navigator.isGpsAvailable && point.isGnss && now - lastEventMs >= MIN_GPS_EVENT_GAP_MS) emitPosition(now)
    }

    override fun onGpsLost(elapsedMs: Long) {
        navigator.onGpsLost(elapsedMs)
        if (navigator.hasFix) maybeLookupLines(navigator.currentLat, navigator.currentLon, elapsedMs, force = true)
        drainMessages()
        publish(force = true)
    }

    override fun onMotionSample(sample: MotionSample) {
        lastMotion = sample
        navigator.onMotion(sample)
        drainMessages()
        publish(force = false)
        if (!navigator.isGpsAvailable && navigator.hasFix && sample.elapsedMs - lastEventMs >= 2000L) emitPosition(sample.elapsedMs)
    }

    override fun onSatellites(inView: Int, usedInFix: Int) {
        _satellites.value = Satellites(inView, usedInFix)
    }

    // ---- RadioScanner.Listener / CellScanner.Listener ----------------------------------------

    override fun onRadioScan(scan: RadioScan) {
        // A scan learnt before the saved map is loaded would be lost with it, or the small new
        // map would later overwrite the saved one: skip the few scans of the first moments.
        if (!radioMapLoaded) return
        navigator.onRadioScan(scan)
        drainMessages()
        publish(force = false)
        saveRadioMap(force = false)
    }

    // ---- Radio map persistence -------------------------------------------------------------

    private suspend fun loadRadioMap() {
        val file = File(appContext.filesDir, RADIO_MAP_FILE)
        val loaded = withContext(Dispatchers.IO) {
            try { if (file.exists()) RadioMap.parse(file.readText()) else null } catch (e: Exception) {
                Log.w(TAG, "Radio map unreadable, starting a new one", e); null
            }
        }
        // Nothing is learnt before this point (onRadioScan waits for the load).
        if (loaded != null && loaded.size > 0) navigator.radioMap = loaded
        val memFile = File(appContext.filesDir, RIDE_MEMORY_FILE)
        val memory = withContext(Dispatchers.IO) {
            try { if (memFile.exists()) RideMemory.parse(memFile.readText()) else null } catch (e: Exception) {
                Log.w(TAG, "Ride memory unreadable, starting a new one", e); null
            }
        }
        // A memory with only the learnt vibration counts too. Before this point at most a few
        // vibration readings can have been learnt (a run takes minutes), so nothing is lost.
        if (memory != null && navigator.rideMemory.size == 0) navigator.rideMemory = memory
        savedRadioVersion = navigator.radioMap.version
        savedMemoryVersion = navigator.rideMemory.version
        radioMapLoaded = true
    }

    /** Writes the learnt radio map (throttled; atomic temp-file + rename). */
    private fun saveRadioMap(force: Boolean) {
        if (!radioMapLoaded || !::appContext.isInitialized) return
        val map = navigator.radioMap
        val memory = navigator.rideMemory
        val mapChanged = map.version != savedRadioVersion
        val memoryChanged = memory.version != savedMemoryVersion
        if ((!mapChanged && !memoryChanged) || radioSaveJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastRadioSaveMs < RADIO_SAVE_INTERVAL_MS) return
        lastRadioSaveMs = now
        val mapVersion = map.version
        val memoryVersion = memory.version
        // Built on the main thread: the engine's data is only touched there.
        val mapText = if (mapChanged) map.serialize() else null
        val memoryText = if (memoryChanged) memory.serialize() else null
        radioSaveJob = scope.launch {
            val (mapOk, memoryOk) = withContext(Dispatchers.IO) {
                Pair(mapText?.let { writeAtomically(RADIO_MAP_FILE, it) } ?: false,
                    memoryText?.let { writeAtomically(RIDE_MEMORY_FILE, it) } ?: false)
            }
            if (mapOk) savedRadioVersion = mapVersion
            if (memoryOk) savedMemoryVersion = memoryVersion
        }
    }

    /** Temp file + rename, so a crash or full disk never leaves a half-written file. */
    private fun writeAtomically(name: String, text: String): Boolean = try {
        val tmp = File(appContext.filesDir, "$name.tmp")
        tmp.writeText(text)
        tmp.renameTo(File(appContext.filesDir, name)).also { if (!it) tmp.delete() }
    } catch (e: Exception) {
        Log.w(TAG, "Saving $name failed", e); false
    }

    // ---- Metro line data ------------------------------------------------------------------

    /** Offline lookup (cheap, no network): pick the nearest saved line and the alternatives. */
    private fun maybeLookupLines(lat: Double, lon: Double, now: Long, force: Boolean = false) {
        if (testRoute != null) return // a test drive follows its own route
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
                    if (radius > MetroRouteService.SEARCH_RADIUS_METERS) {
                        // A whole city can be too big for a busy server: save the lines around us now,
                        // the city is retried later with back-off.
                        downloadJob = null
                        startDownload(force = true, radius = MetroRouteService.SEARCH_RADIUS_METERS)
                    }
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
        // Tunnel test: compare with the real GPS position of the same moment.
        val reference = truth?.takeIf { testMode == TestDriveMode.TUNNEL && elapsedMs - truthReceivedMs <= TRUTH_MAX_AGE_MS }
        val error = reference?.let { GeoToolbox.calculateDistance(s.latitude, s.longitude, it.latitude, it.longitude) }
        if (error != null) { errors.add(error); publishTestDrive() }
        val decision = deduper.decide(s.latitude, s.longitude, elapsedMs)
        // Same position as the last row: skipped, except in a tunnel test where the error still changes.
        if (decision == PositionDeduper.Decision.SKIP && error == null) return
        val event = PositionEvent(
            System.currentTimeMillis(), s.latitude, s.longitude, s.source, s.speedMps, s.gpsAvailable,
            s.uncertaintyMeters, s.gpsAccuracyMeters, _satellites.value.usedInFix, s.lineName, s.nextStation, s.mockLocation,
            reference?.latitude, reference?.longitude, error,
            sensors = listOfNotNull(
                lastMotion?.let {
                    String.format(Locale.US, "Sensors: %s, vibration %.3f m/s², turn %+.1f °/s",
                        it.state.name.lowercase(), it.vibration, it.yawRateDps)
                },
                // The serving mobile cell: the "coverage detail" the radio map learns underground.
                cellScanner.servingCell?.let { "Cell: ${it.key} ${it.dbm} dBm" }
            ).joinToString(" | ").ifEmpty { null }
        )
        _positions.tryEmit(event)
        if (decision != PositionDeduper.Decision.LOG_AND_MARK) return
        _trail.value = (_trail.value + TrailPoint(event.timestamp, event.latitude, event.longitude, event.source,
            event.uncertaintyMeters, event.speedMps, event.nextStation)).takeLast(MAX_TRAIL_POINTS)
    }

    private fun drainMessages() {
        if (navigator.events.isEmpty()) return
        navigator.events.forEach { _messages.tryEmit(it) }
        // Mark each event on the map where it happened (once the position is known).
        if (navigator.hasFix) {
            val now = System.currentTimeMillis()
            val placed = navigator.events.map { MapEvent(now, navigator.currentLat, navigator.currentLon, it) }
            _mapEvents.value = (_mapEvents.value + placed).takeLast(MAX_MAP_EVENTS)
        }
        navigator.events.clear()
    }

    private fun publish(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPublishMs < 250L) return
        lastPublishMs = now
        _state.value = navigator.state()
        _route.value = navigator.route
    }
}
