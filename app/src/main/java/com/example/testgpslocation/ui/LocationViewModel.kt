package com.example.testgpslocation.ui

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.testgpslocation.data.LocationRepository
import com.example.testgpslocation.engine.MetroRouteService
import com.example.testgpslocation.engine.TrackingEngine
import com.example.testgpslocation.models.*
import com.example.testgpslocation.sensor.SensorHub
import com.example.testgpslocation.utils.GeoToolbox
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

class LocationViewModel(application: Application) : AndroidViewModel(application) {

    private val sensorHub = SensorHub(application)
    private val trackingEngine = TrackingEngine(application)
    private val metroService = MetroRouteService(application)
    private val repository = LocationRepository(application)

    // Granular UI states
    private val _currentLocation = MutableStateFlow<UiLocation?>(null)
    val currentLocation = _currentLocation.asLiveData()

    private val _movementHistory = MutableStateFlow<List<String>>(emptyList())
    val movementHistory = _movementHistory.asLiveData()

    private val _liveStats = MutableStateFlow<LiveStats>(LiveStats())
    val liveStats = _liveStats.asLiveData()

    private var activeProfile: TunnelProfile? = null
    private var lastDRDist = 0.0
    private var resolvedPlace = "Not resolved"
    private var lastRouteTime = 0L
    private var lastRouteLat = 0.0
    private var lastRouteLon = 0.0
    private var lastCellTime = 0L
    private var lastListUpdateTime = 0L
    
    private val historyPoints = mutableListOf<String>()

    data class LiveStats(
        val speed: Double = 0.0,
        val heading: Float = 0f,
        val distance: Double = 0.0,
        val confidence: String = "HIGH",
        val label: String = "",
        val currentStation: String = "Scanning...",
        val nextStation: String = "Searching lines...",
        val destination: String = "Unknown",
        val distToNext: Double = 0.0,
        val navActive: Boolean = false,
        val lineName: String? = null
    )

    init {
        sensorHub.setListener(object : SensorHub.SensorListener {
            override fun onGpsUpdate(p: GpsPoint) {
                sensorHub.provideGpsSpeed(p.speedMeterPerSecond.toDouble())
                trackingEngine.onGpsAvailable(p)
                updateCurrentLocation(trackingEngine.trueLat, trackingEngine.trueLon, true, "Active Satellites")
                fetchRoute(p); lockMetro(p)
            }
            override fun onGpsUnavailable() {
                trackingEngine.onGpsUnavailable()
                if (trackingEngine.isInsideTunnel) {
                    trackingEngine.session.entryPoint?.let { entry ->
                        fetchRoute(entry); sensorHub.resetMotion(); sensorHub.setInitialVelocity(trackingEngine.latestGpsPoint?.speedMeterPerSecond?.toDouble() ?: 0.0)
                        activeProfile = repository.findMatchingProfile(entry)
                    }
                    lastDRDist = 0.0
                }
                updateCurrentLocation(trackingEngine.trueLat, trackingEngine.trueLon, false, "Last Known (GPS Lost)")
            }
            override fun onMotionUpdate(dist: Double, speed: Double, heading: Float) {
                trackingEngine.updateMotion(dist, speed, heading)
                val navState = processTracking(dist)
                
                val currentRoute = trackingEngine.route
                val isScanning = currentRoute == null
                val isNearing = currentRoute != null && !trackingEngine.isCalibrated // Simple proxy for nearing vs locked
                
                _liveStats.value = LiveStats(
                    speed = speed,
                    heading = trackingEngine.trueHeading,
                    distance = dist,
                    confidence = trackingEngine.getEstimate().confidence,
                    label = trackingEngine.getEstimate().status,
                    currentStation = when {
                        navState?.valid == true -> navState.nearestStation?.name ?: "Unknown Station"
                        isNearing -> "Nearing ${currentRoute?.lineName ?: "Line"}..."
                        !isScanning -> "Line discovered"
                        else -> "Scanning nearby..."
                    },
                    nextStation = when {
                        navState?.valid == true -> navState.nextStation?.name ?: "Calculating..."
                        !isScanning -> "Ready to lock"
                        else -> "Searching metro lines..."
                    },
                    destination = navState?.destinationName ?: "Calculating...",
                    distToNext = navState?.distanceToNextStationMeters ?: 0.0,
                    navActive = navState?.valid ?: false,
                    lineName = currentRoute?.lineName
                )
            }
            override fun onPressureUpdate(pressure: Float, isRapidChange: Boolean) {
                trackingEngine.updatePressure(isRapidChange)
            }
        })
        viewModelScope.launch {
            while (true) { sensorHub.checkGpsStatus(); delay(2000.milliseconds) }
        }
    }

    fun startTracking() { sensorHub.start() }
    fun stopTracking() { sensorHub.stop() }
    override fun onCleared() { super.onCleared(); stopTracking() }

    private fun updateCurrentLocation(lat: Double, lon: Double, active: Boolean, label: String) {
        if (lat == 0.0) return
        val newLoc = UiLocation(lat, lon, active, label)
        if (_currentLocation.value != newLoc) {
            _currentLocation.value = newLoc
            val dist = if (_currentLocation.value != null) GeoToolbox.calculateDistance(_currentLocation.value!!.latitude, _currentLocation.value!!.longitude, lat, lon) else 999.0
            val time = System.currentTimeMillis() - lastListUpdateTime
            if (!active) {
                if (dist > 1.0 || time > 2000L) {
                    addHistoryEntry(lat, lon, false)
                    lastListUpdateTime = System.currentTimeMillis()
                }
            } else {
                if (dist > 100.0) {
                    addHistoryEntry(lat, lon, true)
                    lastListUpdateTime = System.currentTimeMillis()
                }
            }
        }
    }

    private fun addHistoryEntry(lat: Double, lon: Double, active: Boolean) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val source = if (active) "GPS" else "DR"
        val entry = "[$time] ${String.format(Locale.US, "%.6f, %.6f", lat, lon)} ($source)"
        historyPoints.add(0, entry)
        if (historyPoints.size > 200) historyPoints.removeAt(200)
        _movementHistory.value = historyPoints.toList()
    }

    private fun processTracking(dist: Double): MetroNavigationState? {
        var curDist = if (System.currentTimeMillis() - lastCellTime > 5000L) {
            lastCellTime = System.currentTimeMillis(); trackingEngine.correctCellular(dist)
        } else dist

        activeProfile?.let { prof ->
            val prog = ((System.currentTimeMillis() - trackingEngine.session.tunnelStartTime).toDouble() / prof.durationMillis.toDouble()).coerceAtMost(1.0)
            curDist = trackingEngine.correctProfile(curDist, prof.estimatedDistanceMeter * prog)
        }

        val delta = curDist - lastDRDist
        lastDRDist = curDist

        var navState: MetroNavigationState? = null
        if (trackingEngine.route != null) {
            navState = trackingEngine.updateMetroNav(delta)
            if (navState.valid && navState.currentRoutePoint != null) {
                updateCurrentLocation(trackingEngine.trueLat, trackingEngine.trueLon, trackingEngine.isGpsAvailable, "Track Mapped")
                return navState
            }
        }

        if (!trackingEngine.isGpsAvailable) {
            trackingEngine.updateEstimation(delta)
            trackingEngine.currentEstimatedPoint?.let { est ->
                val snapped = trackingEngine.snapToTrack(Location("dr").apply { latitude = est.latitude; longitude = est.longitude; accuracy = est.accuracy }, trackingEngine.trueHeading.toDouble())
                if (snapped != null) {
                    updateCurrentLocation(snapped.lat, snapped.lon, false, "Track Snapped")
                } else {
                    updateCurrentLocation(trackingEngine.trueLat, trackingEngine.trueLon, false, "Dead Reckoning")
                }
            }
        }
        return navState
    }

    private fun fetchRoute(p: GpsPoint) {
        viewModelScope.launch {
            resolvedPlace = repository.resolvePlace(p.latitude, p.longitude)
            if (trackingEngine.route != null) return@launch
            if (System.currentTimeMillis() - lastRouteTime < 60000L && GeoToolbox.calculateDistance(lastRouteLat, lastRouteLon, p.latitude, p.longitude) < 5000.0) return@launch
            lastRouteTime = System.currentTimeMillis(); lastRouteLat = p.latitude; lastRouteLon = p.longitude
            metroService.getRouteByLocation(p.latitude, p.longitude, 2000)?.let { trackingEngine.route = it }
        }
    }

    private fun lockMetro(p: GpsPoint) {
        if (trackingEngine.route == null) return
        if (trackingEngine.lockMetro(Location("gps").apply { latitude = p.latitude; longitude = p.longitude })) {
            trackingEngine.updateMetroDir(trackingEngine.trueHeading.toDouble())
        }
    }
}
