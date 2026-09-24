package com.example.testgpslocation.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.SystemClock
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat
import com.example.testgpslocation.models.*
import com.example.testgpslocation.utils.GeoToolbox
import kotlin.math.abs

class TrackingEngine(private val context: Context) {

    val session = TunnelSession()
    var isGpsAvailable = false; private set
    var isInsideTunnel = false; private set
    var latestGpsPoint: GpsPoint? = null; private set
    var currentEstimatedPoint: GpsPoint? = null; private set

    // The "True" position and heading
    var trueLat = 0.0; private set
    var trueLon = 0.0; private set
    var trueHeading = 0f; private set

    private var gpsLostStartTime = 0L
    private var movementDetected = false
    private var distanceMeter = 0.0
    private var speedMeterPerSecond = 0.0

    // Advanced Logic State
    private var isStationLocked = false
    private var pistonEffectActive = false
    private val visitedStations = mutableSetOf<String>()
    
    // KPI: Recovery Drift
    var lastRecoveryDrift = 0.0; private set

    // Filtering & Sanity
    private var lastValidGps: GpsPoint? = null
    private val MAX_TRAIN_SPEED_MPS = 35.0
    private val STATION_SNAP_RADIUS = 150.0
    private val DWELL_LOCK_RADIUS = 80.0
    private val STATIONARY_DRIFT_RADIUS = 15.0

    private var dwellSaneCount = 0

    // Calibration
    private var lastCalibPoint: GpsPoint? = null
    var isCalibrated = false; private set

    // Cellular
    private val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val fingerprints = mutableListOf<CellFingerprint>()

    // Metro State
    var route: MetroRoute? = null
        set(v) { field = v; navIdx = -1; navDist = 0.0; navDir = 1 }
    private var navIdx = -1
    private var navDist = 0.0
    private var navDir = 1

    fun onGpsAvailable(p: GpsPoint) {
        if (p.isCoarse) {
            if (latestGpsPoint == null || !isGpsAvailable) latestGpsPoint = p
            return
        }
        if (!isPhysicallyPossible(p)) return

        if (abs(speedMeterPerSecond) < 0.5) {
            val driftDist = GeoToolbox.calculateDistance(trueLat, trueLon, p.latitude, p.longitude)
            if (driftDist < STATIONARY_DRIFT_RADIUS && trueLat != 0.0) return
        }


        isGpsAvailable = true; latestGpsPoint = p; gpsLostStartTime = 0; movementDetected = false
        
        if (session.isActive) {
            // Measure recovery drift KPI before ending tunnel session
            currentEstimatedPoint?.let { est ->
                lastRecoveryDrift = GeoToolbox.calculateDistance(est.latitude, est.longitude, p.latitude, p.longitude)
            }
            session.endTunnel(p)
        }
        
        updateCalibration(p)
        lastValidGps = p
        
        trueLat = p.latitude
        trueLon = p.longitude
        if (abs(speedMeterPerSecond) > 1.5) updateTrueHeading(p.bearingDegree)
    }

    fun onGpsUnavailable() {
        isGpsAvailable = false
        if (gpsLostStartTime == 0L) gpsLostStartTime = SystemClock.elapsedRealtime()
        if (!session.isActive && latestGpsPoint != null) { 
            session.startTunnel(latestGpsPoint!!)
            currentEstimatedPoint = GpsPoint(trueLat, trueLon, 999f, System.currentTimeMillis())
        }
    }

    fun updatePressure(isRapidChange: Boolean) {
        pistonEffectActive = isRapidChange
    }

    fun updateMotion(dist: Double, speed: Double, sensorHeading: Float) {
        speedMeterPerSecond = speedMeterPerSecond * 0.7 + speed * 0.3
        if (abs(speedMeterPerSecond) < 0.2) speedMeterPerSecond = 0.0

        distanceMeter = dist
        if (abs(distanceMeter) >= 10.0) movementDetected = true
        
        val dur = if (gpsLostStartTime == 0L) 0L else SystemClock.elapsedRealtime() - gpsLostStartTime
        isInsideTunnel = session.isActive && !isGpsAvailable && (dur >= 5000L || pistonEffectActive) && movementDetected
        
        if (session.isActive) session.updateSessionData(distanceMeter, speedMeterPerSecond, trueHeading)
        if (abs(speedMeterPerSecond) > 1.0) updateTrueHeading(sensorHeading)

        checkDwellLock()
    }

    private fun updateTrueHeading(rawHeading: Float) {
        val r = route
        if (r == null || navIdx < 0 || navIdx >= r.trackPoints.size - 1) {
            trueHeading = rawHeading
            return
        }
        val trackBearing = GeoToolbox.calculateBearing(r.trackPoints[navIdx].lat, r.trackPoints[navIdx].lon, r.trackPoints[navIdx + 1].lat, r.trackPoints[navIdx + 1].lon).toFloat()
        val diff = GeoToolbox.bearingDifference(rawHeading.toDouble(), trackBearing.toDouble())
        trueHeading = when {
            speedMeterPerSecond > 2.0 && diff < 45.0 -> trackBearing
            diff < 35.0 -> trackBearing
            else -> ((trackBearing * 0.3 + rawHeading * 0.7).toFloat())
        }
    }

    private fun checkDwellLock() {
        if (abs(speedMeterPerSecond) > 0.5) {
            dwellSaneCount++
            if (dwellSaneCount > 5) isStationLocked = false
            return
        }
        dwellSaneCount = 0
        route?.stations?.forEach { station ->
            val cur = findPointAt(navDist) ?: return@forEach
            if (GeoToolbox.calculateDistance(cur.lat, cur.lon, station.lat, station.lon) < DWELL_LOCK_RADIUS) {
                isStationLocked = true
                trueLat = station.lat; trueLon = station.lon
            }
        }
    }

    fun updateEstimation(delta: Double) {
        if (isStationLocked) return 
        val curr = currentEstimatedPoint ?: return
        if (delta == 0.0) return
        val (lat, lon) = GeoToolbox.moveLocation(curr.latitude, curr.longitude, if (speedMeterPerSecond < 0) -delta else delta, trueHeading.toDouble())
        currentEstimatedPoint = GpsPoint(lat, lon, 999f, System.currentTimeMillis(), speedMeterPerSecond.toFloat(), trueHeading)
        trueLat = lat; trueLon = lon
    }

    fun resetEstimation(p: GpsPoint?) { currentEstimatedPoint = p }

    fun correctCellular(dist: Double): Double {
        val id = getCurrentCellId(); val sig = getCurrentSignalDbm()
        if (id == "unknown_cell" || id == "permission_missing") return dist
        val best = fingerprints.filter { it.cellId == id }.minByOrNull { abs(it.signalDbm - sig) }
        if (best != null) return dist * 0.6 + best.distanceFromEntryMeter * 0.4
        fingerprints.add(CellFingerprint(id, sig, dist, System.currentTimeMillis()))
        return dist
    }

    fun correctProfile(dist: Double, profDist: Double): Double = if (profDist <= 0) dist else dist * 0.5 + profDist * 0.5

    fun getEstimate(): TunnelEstimate {
        val dur = if (gpsLostStartTime == 0L) 0L else SystemClock.elapsedRealtime() - gpsLostStartTime
        val status = when {
            isStationLocked -> "Dwell Lock (Station Platform)"
            isInsideTunnel -> "Tunnel tracking"
            isGpsAvailable -> "GPS active"
            else -> "Waiting"
        }
        return TunnelEstimate(isInsideTunnel, distanceMeter, speedMeterPerSecond, trueHeading, dur, calculateConfidence(dur), status)
    }

    fun lockMetro(loc: Location): Boolean {
        val r = route ?: return false
        val best = r.trackPoints.withIndex().minByOrNull { GeoToolbox.calculateDistance(loc.latitude, loc.longitude, it.value.lat, it.value.lon) }
        if (best == null || GeoToolbox.calculateDistance(loc.latitude, loc.longitude, best.value.lat, best.value.lon) > 300.0) return false
        navIdx = best.index; navDist = best.value.distanceFromStartMeters; return true
    }

    fun updateMetroDir(bearing: Double) {
        val r = route ?: return
        if (navIdx < 0 || navIdx >= r.trackPoints.size - 1) return
        val curr = r.trackPoints[navIdx]; val next = r.trackPoints[navIdx + 1]; val prev = if (navIdx > 0) r.trackPoints[navIdx - 1] else null
        val fwdDiff = GeoToolbox.bearingDifference(bearing, GeoToolbox.calculateBearing(curr.lat, curr.lon, next.lat, next.lon))
        val revDiff = prev?.let { GeoToolbox.bearingDifference(bearing, GeoToolbox.calculateBearing(curr.lat, curr.lon, it.lat, it.lon)) } ?: 999.0
        navDir = if (revDiff < fwdDiff) -1 else 1
    }

    fun updateMetroNav(delta: Double): MetroNavigationState {
        if (isStationLocked) return buildMetroState(findPointAt(navDist)!!)
        val r = route ?: return MetroNavigationState.invalid()
        if (navIdx < 0) return MetroNavigationState.invalid()
        val signedDelta = if (speedMeterPerSecond < 0) -delta else delta
        navDist = (navDist + (if (navDir >= 0) signedDelta else -signedDelta)).coerceIn(0.0, r.trackPoints.last().distanceFromStartMeters)
        r.stations.forEach { station ->
            val cur = findPointAt(navDist)
            if (cur != null && GeoToolbox.calculateDistance(cur.lat, cur.lon, station.lat, station.lon) < STATION_SNAP_RADIUS) {
                if (!visitedStations.contains(station.name)) {
                    navDist = r.trackPoints.minBy { GeoToolbox.calculateDistance(it.lat, it.lon, station.lat, station.lon) }.distanceFromStartMeters
                    visitedStations.add(station.name)
                }
            } else if (cur != null && GeoToolbox.calculateDistance(cur.lat, cur.lon, station.lat, station.lon) > STATION_SNAP_RADIUS + 50) {
                visitedStations.remove(station.name)
            }
        }
        val pt = findPointAt(navDist) ?: return MetroNavigationState.invalid()
        navIdx = r.trackPoints.indexOfFirst { it.distanceFromStartMeters >= pt.distanceFromStartMeters }.coerceAtLeast(0)
        trueLat = pt.lat; trueLon = pt.lon
        return buildMetroState(pt)
    }

    fun snapToTrack(loc: Location, bearing: Double?): MetroPoint? {
        val r = route ?: return null
        return r.trackPoints.withIndex().filter { GeoToolbox.calculateDistance(loc.latitude, loc.longitude, it.value.lat, it.value.lon) <= 120.0 }
            .map { it.value to it.index }.minByOrNull { (p, i) ->
                var score = GeoToolbox.calculateDistance(loc.latitude, loc.longitude, p.lat, p.lon)
                if (bearing != null && i < r.trackPoints.size - 1) {
                    val trackBearing = GeoToolbox.calculateBearing(p.lat, p.lon, r.trackPoints[i + 1].lat, r.trackPoints[i + 1].lon)
                    val diff = GeoToolbox.bearingDifference(bearing, trackBearing)
                    score += if (diff > 80.0) 1000.0 else diff * 0.5
                }
                score
            }?.first
    }

    private fun isPhysicallyPossible(p: GpsPoint): Boolean {
        val last = lastValidGps ?: return true
        val timeDiff = (p.timestamp - last.timestamp) / 1000.0
        if (timeDiff <= 0) return true
        return (GeoToolbox.calculateDistance(last.latitude, last.longitude, p.latitude, p.longitude) / timeDiff) <= MAX_TRAIN_SPEED_MPS
    }

    private fun findPointAt(dist: Double): MetroPoint? {
        val r = route ?: return null
        if (dist <= 0) return r.trackPoints.firstOrNull()
        for (i in 1 until r.trackPoints.size) {
            val prev = r.trackPoints[i - 1]; val curr = r.trackPoints[i]
            if (curr.distanceFromStartMeters >= dist) {
                val ratio = if (curr.distanceFromStartMeters == prev.distanceFromStartMeters) 0.0 else (dist - prev.distanceFromStartMeters) / (curr.distanceFromStartMeters - prev.distanceFromStartMeters)
                return MetroPoint(prev.lat + (curr.lat - prev.lat) * ratio, prev.lon + (curr.lon - prev.lon) * ratio, dist)
            }
        }
        return r.trackPoints.lastOrNull()
    }

    private fun buildMetroState(pt: MetroPoint): MetroNavigationState {
        val state = MetroNavigationState(valid = true, currentRoutePoint = pt, distanceOnRouteMeters = navDist)
        val r = route ?: return state
        val stations = r.stations.sortedBy { s -> r.trackPoints.minBy { GeoToolbox.calculateDistance(s.lat, s.lon, it.lat, it.lon) }.distanceFromStartMeters }
        state.nextStation = if (navDir >= 0) stations.firstOrNull { s -> r.trackPoints.minBy { GeoToolbox.calculateDistance(s.lat, s.lon, it.lat, it.lon) }.distanceFromStartMeters > navDist }
                            else stations.lastOrNull { s -> r.trackPoints.minBy { GeoToolbox.calculateDistance(s.lat, s.lon, it.lat, it.lon) }.distanceFromStartMeters < navDist }
        
        state.destinationName = if (navDir >= 0) r.toStation ?: stations.lastOrNull()?.name 
                                else r.fromStation ?: stations.firstOrNull()?.name

        state.nextStation?.let { state.distanceToNextStationMeters = abs(r.trackPoints.minBy { GeoToolbox.calculateDistance(it.lat, it.lon, it.lat, it.lon) }.distanceFromStartMeters - navDist); state.directionText = "Towards ${it.name}" }
        return state
    }

    private fun updateCalibration(p: GpsPoint) {
        val last = lastCalibPoint ?: run { lastCalibPoint = p; return }
        if (GeoToolbox.calculateDistance(last, p) < 8.0) return
        isCalibrated = true; lastCalibPoint = p
    }

    private fun getCurrentCellId(): String {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return "permission_missing"
        val infoList = telephony.allCellInfo ?: return "unknown_cell"
        for (info in infoList) {
            if (info is CellInfoLte) return "LTE_${info.cellIdentity.ci}"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr) return "NR_${info.cellIdentity.toString().hashCode()}"
        }
        return "unknown_cell"
    }

    private fun getCurrentSignalDbm(): Int {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return -999
        val infoList = telephony.allCellInfo ?: return -999
        for (info in infoList) {
            if (info is CellInfoLte) return info.cellSignalStrength.dbm
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is CellInfoNr) return info.cellSignalStrength.dbm
        }
        return -999
    }

    private fun calculateConfidence(duration: Long) = when { !isInsideTunnel -> "HIGH"; duration < 30000 && distanceMeter < 500 -> "MEDIUM"; duration < 90000 && distanceMeter < 1500 -> "LOW"; else -> "VERY_LOW" }
}
