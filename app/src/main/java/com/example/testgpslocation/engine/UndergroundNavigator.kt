package com.example.testgpslocation.engine

import com.example.testgpslocation.models.GpsPoint
import com.example.testgpslocation.utils.GeoToolbox
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class MotionState { STATIONARY, WALKING, MOVING }

/** One processed IMU window (~100 ms) from [com.example.testgpslocation.sensor.SensorHub]. */
data class MotionSample(
    val elapsedMs: Long,
    val dtSec: Double,
    val state: MotionState,
    /** How long the phone has been continuously still (0 when not stationary). */
    val stationaryForMs: Long,
    /** Mean horizontal linear acceleration in world frame (m/s²); 0 when orientation is unknown. */
    val accelEast: Double,
    val accelNorth: Double,
    val pressureRapidChange: Boolean = false
)

enum class PositionSource(val label: String) {
    NONE("NONE"),
    /** Satellite fix. */
    GPS("GPS"),
    /** Cell / Wi-Fi location, used when there is no satellite fix. */
    NETWORK("NET"),
    /** Estimated position constrained to a metro track. */
    TRACK("TRACK"),
    /** Heading + distance estimate without a track. */
    DEAD_RECKONING("DR")
}

data class NavState(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val source: PositionSource = PositionSource.NONE,
    val gpsAvailable: Boolean = false,
    val underground: Boolean = false,
    val speedMps: Double = 0.0,
    val headingDeg: Double = 0.0,
    val motion: MotionState = MotionState.STATIONARY,
    val gpsLostForMs: Long = 0,
    val distanceSinceGpsLossMeters: Double = 0.0,
    /** Estimated 1-sigma position error in metres. */
    val uncertaintyMeters: Double = 0.0,
    val gpsAccuracyMeters: Float? = null,
    val confidence: String = "NONE",
    val status: String = "Waiting for location",
    val lineName: String? = null,
    val onTrack: Boolean = false,
    val directionKnown: Boolean = false,
    val previousStation: String? = null,
    val nextStation: String? = null,
    val distanceToNextMeters: Double? = null,
    val etaNextSec: Int? = null,
    val destination: String? = null,
    val stopsRemaining: Int? = null,
    val stationNames: List<String> = emptyList(),
    val destinationIndex: Int? = null,
    val lastRecoveryDriftMeters: Double? = null,
    val stationStopsDetected: Int = 0,
    val networkCorrections: Int = 0,
    val cruiseSpeedMps: Double = 0.0,
    val mockLocation: Boolean = false
)

/**
 * Position engine for GPS-denied travel (metro tunnels), also used while GPS is fine.
 *
 * Inputs: satellite fixes, network (cell/Wi-Fi) fixes, "GPS lost" signals and IMU motion
 * samples. Output: [state].
 *
 * With GPS: the position is the satellite fix (jump-filtered). The engine keeps track of the
 * metro line, the position along it, the direction of travel and the speed, so it is ready
 * the moment GPS disappears.
 *
 * Without GPS, on a saved metro line (1-D, track constrained):
 *  - the gap between the last fix and the loss detection is dead-reckoned at the last speed;
 *  - distance advances with a speed model: IMU acceleration projected on the track, pulled
 *    toward a cruise speed learnt from GPS and from station-to-station times, zero when the
 *    phone is still (ZUPT); walking does not move the train;
 *  - the estimate is held at the next station until the train stops, then snapped to it:
 *    each stop resets the error;
 *  - network fixes (if any) correct the along-track distance through a 1-D Kalman update
 *    with outlier gating;
 *  - direction comes from the chosen destination, the GPS bearing, or the direction of
 *    acceleration when the train pulls out; at interchanges the same cue picks the line.
 * Without a line: heading + distance dead reckoning, corrected by network fixes.
 *
 * An uncertainty (1-sigma metres) is carried throughout and drives the confidence level.
 * Pure Kotlin (no Android types) so it can be unit tested. Not thread safe: use one thread.
 */
class UndergroundNavigator {

    var route: MetroRoute? = null
        private set
    private var alternatives: List<MetroRoute> = emptyList()

    // Position
    private var lat = 0.0
    private var lon = 0.0
    private var hasPosition = false
    private var source = PositionSource.NONE
    private var uncertainty = 0.0
    private var nowMs = 0L

    // Satellite state
    private var gpsAvailable = false
    private var lastGnssMs = 0L
    private var gpsLostAtMs = 0L
    private var lastAccepted: GpsPoint? = null
    private var rejectStreak = 0
    private var recoveryCandidate: GpsPoint? = null
    private var lastAccuracy: Float? = null
    private var mock = false

    // Motion
    private var speed = 0.0
    private var cruiseSpeed = DEFAULT_CRUISE_MPS
    private var heading = 0.0
    private var motion = MotionState.STATIONARY
    private var pressureRapid = false

    // Track
    private var onTrack = false
    private var navDist = 0.0
    private var direction = 1
    private var directionKnown = false
    private var destination: MetroStation? = null
    private var heldAt: MetroStation? = null
    private var holdAllowance = 0.0
    private var pendingOvershoot = 0.0
    private var stopHandled = false
    /** True while the train stands at the station it was snapped to (position is known exactly). */
    private var snappedAtStation = false
    private var stationStops = 0
    private var lastTrackProjection: Double? = null
    private var lastStopStation: MetroStation? = null
    private var movingSecondsSinceStop = 0.0

    // Departure detection
    private var watchingDeparture = false
    private var departureAccumE = 0.0
    private var departureAccumN = 0.0
    private var departureTime = 0.0
    private var undirectedDistance = 0.0

    private var distanceSinceLoss = 0.0
    private var recoveryDrift: Double? = null
    private var networkCorrections = 0

    /** Messages worth logging (station stops, GPS lost/recovered, line changes); drained by the caller. */
    val events = mutableListOf<String>()

    val hasFix: Boolean get() = hasPosition
    val currentLat: Double get() = lat
    val currentLon: Double get() = lon
    val isGpsAvailable: Boolean get() = gpsAvailable

    // ---- Configuration from the UI ---------------------------------------------------------

    fun setRoute(newRoute: MetroRoute?) {
        if (newRoute?.id == route?.id) return
        route = newRoute
        destination = null
        clearHold()
        directionKnown = false
        onTrack = false
        lastTrackProjection = null
        lastStopStation = null
        val r = newRoute ?: return
        if (hasPosition) {
            val p = r.project(lat, lon)
            val radius = if (gpsAvailable) LOCK_RADIUS_METERS + (lastAccuracy ?: 0f) else LOST_LOCK_RADIUS_METERS
            if (p.offsetMeters <= radius) {
                navDist = p.distanceAlongTrack
                onTrack = true
                val fix = lastAccepted
                if (gpsAvailable && fix?.hasBearing == true && speed > 2.0) inferDirectionFromBearing(fix.bearingDegree.toDouble())
                if (!gpsAvailable) applyTrackPosition(r)
            }
        }
        events.add("Line loaded: ${r.lineName} (${r.stations.size} stations, ${r.length.toInt()} m)")
    }

    /** Other saved lines close to the current position (used to pick the line at interchanges). */
    fun setAlternativeRoutes(routes: List<MetroRoute>) {
        alternatives = routes
    }

    /** [index] into route.stations, or null for "auto". */
    fun setDestination(index: Int?) {
        val r = route ?: return
        destination = index?.let { r.stations.getOrNull(it) }
        destination?.let { d ->
            if (onTrack && abs(d.distanceAlongTrack - navDist) > DESTINATION_MIN_DISTANCE_METERS) {
                val newDir = if (d.distanceAlongTrack > navDist) 1 else -1
                if (newDir != direction) clearHold()
                direction = newDir
                directionKnown = true
                flushUndirected(r)
            }
        }
    }

    fun flipDirection() {
        direction = -direction
        directionKnown = true
        destination = null
        clearHold()
        route?.let { flushUndirected(it) }
    }

    // ---- Location input ----------------------------------------------------------------------

    /** Satellite or network fix. [elapsedMs] is the caller's monotonic clock. */
    fun onLocationFix(fix: GpsPoint, elapsedMs: Long) {
        nowMs = elapsedMs
        if (fix.accuracy <= 0f || fix.accuracy > MAX_USABLE_ACCURACY_METERS) return
        if (fix.latitude.isNaN() || fix.longitude.isNaN()) return
        if (fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0) return
        if (fix.latitude == 0.0 && fix.longitude == 0.0) return
        if (fix.isGnss && fix.accuracy <= GNSS_GOOD_ACCURACY_METERS) handleGnss(fix) else handleWeak(fix)
    }

    /** Alias kept for callers / tests that pass satellite fixes. */
    fun onGpsFix(fix: GpsPoint, elapsedMs: Long) = onLocationFix(fix, elapsedMs)

    private fun handleGnss(fix: GpsPoint) {
        val prev = lastAccepted
        if (prev != null && !isPlausible(prev, fix)) {
            rejectStreak++
            if (rejectStreak < REJECT_RESET_COUNT) return
            // Several "impossible" fixes in a row: our reference was wrong, not them.
            events.add("GPS position jumped; accepting the new position")
        }
        rejectStreak = 0

        if (!gpsAvailable && gpsLostAtMs > 0 && hasPosition) {
            // First fixes after an outage are often poor: confirm before trusting them.
            val candidate = recoveryCandidate
            val confirmed = fix.accuracy <= RECOVERY_INSTANT_ACCURACY_METERS || (candidate != null &&
                GeoToolbox.calculateDistance(candidate.latitude, candidate.longitude, fix.latitude, fix.longitude) <=
                RECOVERY_CONFIRM_METERS + max(speed, 5.0) * max(0.0, (fix.elapsedRealtimeMs - candidate.elapsedRealtimeMs) / 1000.0))
            if (!confirmed) {
                recoveryCandidate = fix
                handleWeak(fix)
                return
            }
            val drift = GeoToolbox.calculateDistance(lat, lon, fix.latitude, fix.longitude)
            recoveryDrift = drift
            events.add(String.format(Locale.US,
                "GPS recovered after %.0f s without it, %.0f m travelled; estimate was off by %.0f m (predicted ±%.0f m)",
                (nowMs - gpsLostAtMs) / 1000.0, distanceSinceLoss, drift, uncertainty))
        }
        recoveryCandidate = null

        gpsAvailable = true
        snappedAtStation = false
        lastGnssMs = nowMs
        gpsLostAtMs = 0
        distanceSinceLoss = 0.0
        undirectedDistance = 0.0
        clearHold()
        lastAccepted = fix
        lastAccuracy = fix.accuracy
        mock = fix.isMock

        val fixSpeed = if (fix.hasSpeed) fix.speedMeterPerSecond.toDouble() else speed
        speed = fixSpeed.coerceIn(0.0, MAX_SPEED_MPS)
        if (fixSpeed > CRUISE_LEARN_MIN_MPS) cruiseSpeed = (cruiseSpeed * 0.9 + fixSpeed * 0.1).coerceIn(MIN_CRUISE_MPS, MAX_CRUISE_MPS)

        // Suppress stationary jitter.
        val jitter = hasPosition && source == PositionSource.GPS && fixSpeed < 0.5 &&
            GeoToolbox.calculateDistance(lat, lon, fix.latitude, fix.longitude) < STATIONARY_DRIFT_METERS
        if (!jitter) { lat = fix.latitude; lon = fix.longitude }
        hasPosition = true
        source = PositionSource.GPS
        uncertainty = fix.accuracy.toDouble()
        if (fix.hasBearing && fixSpeed > 1.5) heading = fix.bearingDegree.toDouble()

        val r = route ?: return
        val p = r.project(fix.latitude, fix.longitude)
        onTrack = p.offsetMeters <= LOCK_RADIUS_METERS + fix.accuracy
        if (!onTrack) return
        val prevProj = lastTrackProjection
        navDist = p.distanceAlongTrack
        if (destination == null) {
            if (fix.hasBearing && fixSpeed > 2.0) inferDirectionFromBearing(fix.bearingDegree.toDouble())
            else if (prevProj != null && abs(navDist - prevProj) > 20.0) { direction = if (navDist > prevProj) 1 else -1; directionKnown = true }
        }
        if (prevProj == null || abs(navDist - prevProj) > 20.0) lastTrackProjection = navDist
    }

    /** Network fix, or a satellite fix too poor / not yet confirmed to trust fully. */
    private fun handleWeak(fix: GpsPoint) {
        if (gpsAvailable) return // a real satellite fix is better
        if (fix.isMock) mock = true
        val r = max(fix.accuracy.toDouble(), 10.0) * WEAK_ACCURACY_INFLATION

        if (!hasPosition) {
            lat = fix.latitude; lon = fix.longitude
            hasPosition = true
            source = PositionSource.NETWORK
            uncertainty = r
            route?.let { rt ->
                val p = rt.project(lat, lon)
                if (p.offsetMeters <= min(fix.accuracy + 50.0, LOST_LOCK_RADIUS_METERS)) { onTrack = true; navDist = p.distanceAlongTrack }
            }
            return
        }

        val rt = route
        if (rt != null) {
            val p = rt.project(fix.latitude, fix.longitude)
            if (!onTrack) {
                if (p.offsetMeters <= min(fix.accuracy + 50.0, LOST_LOCK_RADIUS_METERS)) {
                    onTrack = true
                    navDist = p.distanceAlongTrack
                    uncertainty = r
                    applyTrackPosition(rt)
                    events.add("Locked to ${rt.lineName} using network location")
                    return
                }
            } else {
                if (snappedAtStation) return // standing at a known platform: better than any network fix
                if (p.offsetMeters > fix.accuracy + WEAK_OFF_TRACK_TOLERANCE_METERS) return // not consistent with the line
                val innovation = p.distanceAlongTrack - navDist
                if (abs(innovation) > WEAK_GATE_SIGMAS * sqrt(uncertainty * uncertainty + r * r)) return // outlier
                val k = (uncertainty * uncertainty) / (uncertainty * uncertainty + r * r)
                navDist = (navDist + k * innovation).coerceIn(0.0, rt.length)
                uncertainty = sqrt(uncertainty * uncertainty * r * r / (uncertainty * uncertainty + r * r))
                heldAt?.let { if (direction * (navDist - it.distanceAlongTrack) > 0) clearHold() }
                networkCorrections++
                applyTrackPosition(rt)
                return
            }
        }

        // Free-space blend (no line): trust network more as our own estimate degrades.
        val d = GeoToolbox.calculateDistance(lat, lon, fix.latitude, fix.longitude)
        if (source != PositionSource.NETWORK && d > WEAK_GATE_SIGMAS * sqrt(uncertainty * uncertainty + r * r)) return
        val k = (uncertainty * uncertainty) / (uncertainty * uncertainty + r * r)
        lat += k * (fix.latitude - lat)
        lon += k * (fix.longitude - lon)
        uncertainty = sqrt(uncertainty * uncertainty * r * r / (uncertainty * uncertainty + r * r))
        networkCorrections++
        if (k >= 0.5) source = PositionSource.NETWORK
    }

    /** No satellite fix for a while (the caller's watchdog decides how long). */
    fun onGpsLost(elapsedMs: Long) {
        nowMs = elapsedMs
        if (!gpsAvailable && gpsLostAtMs != 0L) return
        gpsAvailable = false
        gpsLostAtMs = elapsedMs
        distanceSinceLoss = 0.0
        stopHandled = false
        movingSecondsSinceStop = 0.0
        if (!hasPosition) return
        if (lastGnssMs == 0L && route == null) return // never had a satellite fix (cold start / indoors): nothing to carry forward

        // The watchdog notices the loss some seconds after the last fix: carry the position
        // forward over that gap at the last known speed.
        val gapSec = if (lastGnssMs > 0) ((elapsedMs - lastGnssMs) / 1000.0).coerceIn(0.0, MAX_GAP_SEC) else 0.0
        val r = route
        if (r != null && !onTrack) {
            val p = r.project(lat, lon)
            if (p.offsetMeters <= LOST_LOCK_RADIUS_METERS) { onTrack = true; navDist = p.distanceAlongTrack }
        }
        if (r != null && onTrack) {
            applyTrackPosition(r)
            if (speed < 2.0) {
                // Entering at a station (not already riding): start exactly on it and watch
                // the next departure to learn the direction if it is still unknown.
                r.nearestStation(navDist)?.takeIf { abs(it.distanceAlongTrack - navDist) < STATION_ENTRY_RADIUS_METERS }?.let {
                    navDist = it.distanceAlongTrack
                    lastStopStation = it
                    applyTrackPosition(r)
                }
                startDepartureWatch()
            } else if (gapSec > 0) {
                val gap = speed * gapSec
                distanceSinceLoss += gap
                advanceAlongTrack(r, gap)
            }
            uncertainty = max(uncertainty, MIN_TRACK_UNCERTAINTY_METERS) + gapSec * (TRACK_SPEED_ERROR_FRACTION * speed + TRACK_BASE_ERROR_MPS)
            events.add("GPS lost — tracking along ${r.lineName}")
        } else {
            if (speed > 1.0 && gapSec > 0) {
                val (nLat, nLon) = GeoToolbox.moveLocation(lat, lon, speed * gapSec, heading)
                lat = nLat; lon = nLon
                distanceSinceLoss += speed * gapSec
            }
            source = PositionSource.DEAD_RECKONING
            uncertainty += gapSec * (FREE_SPEED_ERROR_FRACTION * speed + FREE_BASE_ERROR_MPS)
            events.add("GPS lost — dead reckoning (no metro line saved here)")
        }
    }

    // ---- Motion input ----------------------------------------------------------------------

    fun onMotion(sample: MotionSample) {
        nowMs = sample.elapsedMs
        motion = sample.state
        pressureRapid = sample.pressureRapidChange
        if (gpsAvailable || !hasPosition) return
        val dt = sample.dtSec.coerceIn(0.0, 1.0)

        when (sample.state) {
            MotionState.STATIONARY -> {
                speed = 0.0
                startDepartureWatch() // restart the window: the departure begins after the last still moment
                val r = route?.takeIf { onTrack }
                if (r != null && !stopHandled && sample.stationaryForMs >= STATION_STOP_MS) {
                    stopHandled = true
                    handleStop(r)
                }
                return
            }
            MotionState.WALKING -> {
                // Walking on a platform / inside a carriage: the train position does not change.
                if (route == null || !onTrack) snappedAtStation = false
                speed = if (route != null && onTrack) 0.0 else WALKING_SPEED_MPS
                stopHandled = false
            }
            MotionState.MOVING -> {
                stopHandled = false
                snappedAtStation = false
                movingSecondsSinceStop += dt
                if (watchingDeparture) detectDeparture(sample, dt)
                val r = route?.takeIf { onTrack }
                if (r != null) {
                    // On a metro line: IMU acceleration along the track + pull toward cruise speed.
                    val along = if (directionKnown) {
                        val b = Math.toRadians(r.bearingAt(navDist, direction))
                        sample.accelEast * sin(b) + sample.accelNorth * cos(b)
                    } else 0.0
                    speed += along.coerceIn(-MAX_ACCEL_MPS2, MAX_ACCEL_MPS2) * dt
                    val tau = if (directionKnown) CRUISE_PULL_TAU_SEC else CRUISE_PULL_TAU_UNCERTAIN_SEC
                    speed += (cruiseSpeed - speed) * (dt / tau)
                } else {
                    // Off the metro (road tunnel, building): no vehicle model. Keep the last real
                    // GPS speed and let it fade; never invent motion when there was none.
                    speed *= kotlin.math.exp(-dt / FREE_SPEED_DECAY_TAU_SEC)
                }
                speed = speed.coerceIn(0.0, MAX_SPEED_MPS)
            }
        }

        val step = speed * dt
        if (step <= 0.0) return
        distanceSinceLoss += step
        val r = route?.takeIf { onTrack }
        if (r != null) {
            uncertainty += dt * (TRACK_SPEED_ERROR_FRACTION * speed + TRACK_BASE_ERROR_MPS)
            advanceAlongTrack(r, step)
        } else {
            uncertainty += dt * (FREE_SPEED_ERROR_FRACTION * speed + FREE_BASE_ERROR_MPS)
            val (nLat, nLon) = GeoToolbox.moveLocation(lat, lon, step, heading)
            lat = nLat; lon = nLon
            source = PositionSource.DEAD_RECKONING
        }
    }

    private fun advanceAlongTrack(r: MetroRoute, step: Double) {
        if (!directionKnown && watchingDeparture && destination == null) {
            // Which way are we going? Wait for the departure acceleration before moving.
            undirectedDistance += step
            return
        }
        // Hold at the next station until the train stops there, unless we have clearly
        // travelled far past it (train skipping the stop).
        val held = heldAt
        if (held != null) {
            pendingOvershoot += step
            if (pendingOvershoot <= holdAllowance) return
            events.add("Passed ${held.name} without stopping")
            navDist = (held.distanceAlongTrack + direction * pendingOvershoot).coerceIn(0.0, r.length)
            clearHold()
            applyTrackPosition(r)
            return
        }
        var newDist = navDist + direction * step
        val next = r.nextStation(navDist, direction, tolerance = 0.0)
        if (next != null) {
            val beyond = direction * (newDist - next.distanceAlongTrack)
            if (beyond > 0) {
                // Segment = station strictly behind `next` → `next`.
                val prev = r.nextStation(next.distanceAlongTrack, -direction, tolerance = 0.0)
                val segLen = if (prev != null) abs(next.distanceAlongTrack - prev.distanceAlongTrack)
                else abs(next.distanceAlongTrack - (if (direction > 0) 0.0 else r.length))
                heldAt = next
                holdAllowance = max(MIN_OVERSHOOT_METERS, segLen * OVERSHOOT_FRACTION)
                pendingOvershoot = beyond
                newDist = next.distanceAlongTrack - direction * HOLD_MARGIN_METERS
            }
        }
        navDist = newDist.coerceIn(0.0, r.length)
        applyTrackPosition(r)
    }

    private fun clearHold() {
        heldAt = null
        pendingOvershoot = 0.0
    }

    private fun handleStop(r: MetroRoute) {
        val next = r.nextStation(navDist, direction)
        val prev = r.previousStation(navDist, direction)
        val segmentStart = next?.let { r.nextStation(it.distanceAlongTrack, -direction, tolerance = 0.0) }
        val segLen = if (next != null && segmentStart != null) abs(next.distanceAlongTrack - segmentStart.distanceAlongTrack) else 1000.0
        val aheadWindow = max(STOP_SNAP_AHEAD_MIN_METERS, segLen * STOP_SNAP_AHEAD_FRACTION) + pendingOvershoot
        val candidates = listOfNotNull(
            next?.takeIf { abs(it.distanceAlongTrack - navDist) <= aheadWindow },
            prev?.takeIf { abs(it.distanceAlongTrack - navDist) <= STOP_SNAP_BEHIND_METERS }
        )
        val station = candidates.minByOrNull { abs(it.distanceAlongTrack - navDist) }
        if (station == null) {
            events.add("Train stopped between stations (signal?)")
            return
        }
        // Self-calibration: average moving speed over the last station-to-station run.
        val from = lastStopStation
        if (from != null && from.name != station.name && movingSecondsSinceStop >= MIN_SEGMENT_SECONDS_FOR_LEARNING) {
            val observed = abs(station.distanceAlongTrack - from.distanceAlongTrack) / movingSecondsSinceStop
            if (observed in MIN_CRUISE_MPS..MAX_CRUISE_MPS) {
                cruiseSpeed = (cruiseSpeed * 0.5 + observed * 0.5).coerceIn(MIN_CRUISE_MPS, MAX_CRUISE_MPS)
            }
        }
        navDist = station.distanceAlongTrack
        clearHold()
        snappedAtStation = true
        stationStops++
        lastStopStation = station
        movingSecondsSinceStop = 0.0
        uncertainty = min(uncertainty, STATION_SNAP_UNCERTAINTY_METERS)
        applyTrackPosition(r)
        events.add("Stopped at ${station.name}")
        if (destination?.name == station.name) events.add("Arrived at destination ${station.name}")
    }

    /** When the train pulls out, its acceleration points along the direction of travel. */
    private fun detectDeparture(sample: MotionSample, dt: Double) {
        departureAccumE += sample.accelEast * dt
        departureAccumN += sample.accelNorth * dt
        departureTime += dt
        if (departureTime < DEPARTURE_WINDOW_SEC) return
        watchingDeparture = false
        val meanE = departureAccumE / departureTime
        val meanN = departureAccumN / departureTime
        val strong = sqrt(meanE * meanE + meanN * meanN) >= DEPARTURE_MIN_ACCEL
        val bearing = (Math.toDegrees(atan2(meanE, meanN)) + 360.0) % 360.0

        if (strong && destination == null) maybeSwitchLine(bearing)
        val r = route?.takeIf { onTrack }
        if (r == null) {
            if (strong) heading = bearing
            return
        }
        if (strong && destination == null) { // a chosen destination wins
            val newDir = if (GeoToolbox.bearingDifference(bearing, r.bearingAt(navDist, 1)) <= 90.0) 1 else -1
            if (!directionKnown || newDir != direction) {
                if (newDir != direction) clearHold()
                direction = newDir
                directionKnown = true
                events.add("Departure detected, heading towards ${r.nextStation(navDist, direction)?.name ?: "end of line"}")
            }
        }
        flushUndirected(r)
    }

    /** Apply the distance travelled while the direction was being worked out. */
    private fun flushUndirected(r: MetroRoute) {
        if (undirectedDistance <= 0.0 || !onTrack) return
        val pending = undirectedDistance
        undirectedDistance = 0.0
        val watching = watchingDeparture
        watchingDeparture = false
        advanceAlongTrack(r, pending)
        watchingDeparture = watching
    }

    /** At an interchange the departure direction tells which line the train is on. */
    private fun maybeSwitchLine(bearing: Double) {
        val current = route
        val candidates = (listOfNotNull(current) + alternatives).distinctBy { it.id }
        if (candidates.size < 2) return
        val scored = candidates.mapNotNull { r ->
            val p = r.project(lat, lon)
            if (p.offsetMeters > INTERCHANGE_RADIUS_METERS) return@mapNotNull null
            val fwd = r.bearingAt(p.distanceAlongTrack, 1)
            val diff = min(GeoToolbox.bearingDifference(bearing, fwd), GeoToolbox.bearingDifference(bearing, (fwd + 180.0) % 360.0))
            Triple(r, diff, p)
        }
        val best = scored.minByOrNull { it.second } ?: return
        val currentScore = scored.firstOrNull { it.first.id == current?.id }?.second ?: Double.MAX_VALUE
        if (best.first.id == current?.id || best.second + LINE_SWITCH_MARGIN_DEG >= currentScore) return
        if (current != null && best.first.lineName.substringBefore(" (") == current.lineName.substringBefore(" (")) return // same line, other direction
        route = best.first
        navDist = best.third.distanceAlongTrack
        onTrack = true
        clearHold()
        directionKnown = false
        destination = null
        lastStopStation = best.first.nearestStation(navDist)
        events.add("Switched to ${best.first.lineName} (departure direction matches)")
    }

    private fun startDepartureWatch() {
        watchingDeparture = true
        departureAccumE = 0.0; departureAccumN = 0.0; departureTime = 0.0
    }

    private fun inferDirectionFromBearing(bearing: Double) {
        val r = route ?: return
        direction = if (GeoToolbox.bearingDifference(bearing, r.bearingAt(navDist, 1)) <= 90.0) 1 else -1
        directionKnown = true
    }

    private fun applyTrackPosition(r: MetroRoute) {
        val p = r.pointAt(navDist)
        lat = p.lat; lon = p.lon
        heading = r.bearingAt(navDist, direction)
        source = PositionSource.TRACK
    }

    private fun isPlausible(prev: GpsPoint, fix: GpsPoint): Boolean {
        val dt = (fix.elapsedRealtimeMs - prev.elapsedRealtimeMs) / 1000.0
        if (dt <= 0) return true
        val d = GeoToolbox.calculateDistance(prev.latitude, prev.longitude, fix.latitude, fix.longitude)
        val slack = (prev.accuracy + fix.accuracy).toDouble()
        return (d - slack) / dt <= MAX_PLAUSIBLE_SPEED_MPS
    }

    // ---- Output ------------------------------------------------------------------------------

    fun state(): NavState {
        val r = route
        val lostFor = if (gpsAvailable || gpsLostAtMs == 0L) 0L else nowMs - gpsLostAtMs
        val underground = !gpsAvailable && hasPosition && (lostFor >= UNDERGROUND_AFTER_MS || pressureRapid)
        val trackActive = r != null && onTrack

        val next = if (trackActive) r!!.nextStation(navDist, direction) else null
        val prev = if (trackActive) r!!.previousStation(navDist, direction) else null
        val distNext = next?.let { abs(it.distanceAlongTrack - navDist) }
        val destName = when {
            !trackActive -> null
            destination != null -> destination!!.name
            direction >= 0 -> r!!.toStation ?: r.stations.lastOrNull()?.name
            else -> r!!.fromStation ?: r.stations.firstOrNull()?.name
        }
        val stops = if (trackActive && destination != null) {
            val d = destination!!.distanceAlongTrack
            r!!.stations.count { s ->
                val ahead = direction * (s.distanceAlongTrack - navDist) > MetroRoute.ON_STATION_TOLERANCE_METERS
                val notBeyond = direction * (d - s.distanceAlongTrack) >= -1.0
                ahead && notBeyond
            }
        } else null

        val confidence = when {
            !hasPosition -> "NONE"
            uncertainty <= 30 -> "HIGH"
            uncertainty <= 100 -> "MEDIUM"
            uncertainty <= 300 -> "LOW"
            else -> "VERY_LOW"
        }
        val atStation = trackActive && prev != null && abs(prev.distanceAlongTrack - navDist) < MetroRoute.ON_STATION_TOLERANCE_METERS
        val status = when {
            !hasPosition -> "Waiting for location"
            gpsAvailable && trackActive -> "GPS active · on ${r!!.lineName}"
            gpsAvailable -> "GPS active"
            trackActive && atStation && motion != MotionState.MOVING -> "No GPS · at ${prev!!.name}"
            trackActive -> "No GPS · track navigation" + if (directionKnown) "" else " (direction unknown)"
            source == PositionSource.NETWORK -> "No GPS · network location"
            else -> "No GPS · dead reckoning"
        } + if (!gpsAvailable && hasPosition && networkCorrections > 0) " · network-assisted" else ""

        return NavState(
            latitude = lat, longitude = lon, source = source, gpsAvailable = gpsAvailable, underground = underground,
            speedMps = speed, headingDeg = heading, motion = motion, gpsLostForMs = lostFor,
            distanceSinceGpsLossMeters = distanceSinceLoss, uncertaintyMeters = uncertainty,
            gpsAccuracyMeters = if (gpsAvailable) lastAccuracy else null,
            confidence = confidence, status = status,
            lineName = r?.lineName, onTrack = trackActive, directionKnown = directionKnown,
            previousStation = prev?.name, nextStation = next?.name, distanceToNextMeters = distNext,
            etaNextSec = distNext?.let { (it / max(speed, cruiseSpeed * 0.6)).toInt() },
            destination = destName, stopsRemaining = stops,
            stationNames = r?.stations?.map { it.name } ?: emptyList(),
            destinationIndex = destination?.let { d -> r?.stations?.indexOfFirst { it.name == d.name }?.takeIf { it >= 0 } },
            lastRecoveryDriftMeters = recoveryDrift, stationStopsDetected = stationStops,
            networkCorrections = networkCorrections, cruiseSpeedMps = cruiseSpeed, mockLocation = mock
        )
    }

    companion object {
        // Speeds (m/s)
        const val DEFAULT_CRUISE_MPS = 12.0
        const val MIN_CRUISE_MPS = 5.0
        const val MAX_CRUISE_MPS = 30.0
        const val CRUISE_LEARN_MIN_MPS = 4.0
        const val MAX_SPEED_MPS = 40.0            // 144 km/h: covers airport / express metros
        const val MAX_PLAUSIBLE_SPEED_MPS = 60.0  // GPS jump filter
        const val MAX_ACCEL_MPS2 = 2.5
        const val WALKING_SPEED_MPS = 1.2
        const val CRUISE_PULL_TAU_SEC = 20.0
        const val CRUISE_PULL_TAU_UNCERTAIN_SEC = 6.0

        // Location handling (m)
        const val MAX_USABLE_ACCURACY_METERS = 2000f
        const val GNSS_GOOD_ACCURACY_METERS = 50f
        const val RECOVERY_INSTANT_ACCURACY_METERS = 15f
        const val RECOVERY_CONFIRM_METERS = 40.0
        const val REJECT_RESET_COUNT = 3
        const val WEAK_ACCURACY_INFLATION = 1.5
        const val WEAK_GATE_SIGMAS = 3.0
        const val WEAK_OFF_TRACK_TOLERANCE_METERS = 150.0
        const val STATIONARY_DRIFT_METERS = 15.0
        const val MAX_GAP_SEC = 15.0

        // Track locking / stations (m)
        const val LOCK_RADIUS_METERS = 60.0
        const val LOST_LOCK_RADIUS_METERS = 250.0
        const val STATION_ENTRY_RADIUS_METERS = 250.0
        const val INTERCHANGE_RADIUS_METERS = 300.0
        const val LINE_SWITCH_MARGIN_DEG = 20.0
        const val STATION_STOP_MS = 5_000L
        const val STOP_SNAP_AHEAD_MIN_METERS = 400.0
        const val STOP_SNAP_AHEAD_FRACTION = 0.6
        const val STOP_SNAP_BEHIND_METERS = 250.0
        const val MIN_OVERSHOOT_METERS = 150.0
        const val OVERSHOOT_FRACTION = 0.5
        const val HOLD_MARGIN_METERS = 5.0
        const val DESTINATION_MIN_DISTANCE_METERS = 30.0
        const val MIN_SEGMENT_SECONDS_FOR_LEARNING = 20.0

        // Departure detection
        const val DEPARTURE_WINDOW_SEC = 4.0
        const val DEPARTURE_MIN_ACCEL = 0.25

        // Uncertainty model (1-sigma growth)
        const val MIN_TRACK_UNCERTAINTY_METERS = 20.0
        const val STATION_SNAP_UNCERTAINTY_METERS = 40.0
        const val TRACK_SPEED_ERROR_FRACTION = 0.15
        const val TRACK_BASE_ERROR_MPS = 0.3
        const val FREE_SPEED_ERROR_FRACTION = 0.3
        const val FREE_BASE_ERROR_MPS = 1.0

        const val UNDERGROUND_AFTER_MS = 5_000L
        const val FREE_SPEED_DECAY_TAU_SEC = 60.0
    }
}
