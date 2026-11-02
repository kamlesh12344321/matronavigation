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
    val pressureRapidChange: Boolean = false,
    /** Steps detected in this window, or null when the phone has no step detector (or no permission). */
    val steps: Int? = null,
    /** Recent walking cadence (steps per second), for the step length. */
    val stepCadenceHz: Double = 0.0,
    /** Direction the phone faces (degrees from north), NaN when unknown. */
    val deviceHeadingDeg: Double = Double.NaN,
    /**
     * True when [accelEast]/[accelNorth] are real world-frame values (orientation sensor
     * working). Enables the braking / departure checks on stops; false keeps the simpler model.
     */
    val orientationKnown: Boolean = false,
    /**
     * Turn rate of the phone about the vertical (degrees/s, clockwise = positive, like a
     * bearing), from the gyroscope; NaN when unknown. While the phone rests in the train this is
     * the train's turn rate: it is matched against the curves of the line.
     */
    val yawRateDps: Double = Double.NaN,
    /** Spread of the acceleration over ~2 s (m/s²): road / engine / track vibration; NaN while warming up. */
    val vibration: Double = Double.NaN
)

enum class PositionSource(val label: String) {
    NONE("NONE"),
    /** Satellite fix. */
    GPS("GPS"),
    /** Cell / Wi-Fi location from the platform or Google, used when there is no satellite fix. */
    NETWORK("NET"),
    /** The app's own radio positioning: Wi-Fi and mobile-cell fingerprints, Wi-Fi RTT. */
    RADIO("RADIO"),
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
    val mockLocation: Boolean = false,
    val radioCorrections: Int = 0,
    val radioPlacesLearnt: Int = 0,
    val radioSignalsHeard: Int = 0,
    /** Steps counted by pedestrian dead reckoning since GPS was lost. */
    val pdrSteps: Int = 0,
    /** Position corrections from matching the gyroscope's turns with the line's curves. */
    val curveCorrections: Int = 0,
    /** Station-to-station runs whose speed the phone has learnt. */
    val segmentsLearnt: Int = 0
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
 *    acceleration when the train pulls out; at interchanges the same cue picks the line;
 *  - Wi-Fi and mobile-cell scans are matched against the self-learnt [radioMap] (fingerprints,
 *    handover checkpoints, Wi-Fi RTT ranges) and fused through a [TrackHmm] whose states all lie
 *    on the line, so noisy radio can never pull the position off the track.
 * Without a line: a vehicle speed model (IMU acceleration + learnt cruise speed, zero at stops)
 * along the last known heading when riding, pedestrian dead reckoning (counted steps × step
 * length along the phone's heading) when walking, corrected by network and radio fixes.
 *
 * The radio map is learnt while the position is known: scans taken with a good GPS fix or
 * while standing at a station the train was trustworthily snapped to, and — after a run between
 * two trusted stops — the scans heard inside the tunnel, placed along the run in proportion to
 * the model distance (both ends are known exactly).
 *
 * Also learnt per station-to-station run ([rideMemory]): its cruise speed (from its run time),
 * used as the speed target the next time the train runs it. And the
 * gyroscope's turns are matched with the curves of the line through the same HMM.
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
    /** How much real evidence the cruise speed rests on (GPS speed fixes, timed station runs). */
    private var cruiseEvidence = 0
    private val cruiseLearnt: Boolean get() = cruiseEvidence >= CRUISE_EVIDENCE_NEEDED
    private val speedErrorFraction: Double
        get() = if (cruiseLearnt) TRACK_SPEED_ERROR_FRACTION else TRACK_SPEED_ERROR_FRACTION_UNLEARNT
    /** Speed the error grows with: unlearnt, the true speed may well be the typical cruise even if the model is slower. */
    private val speedForError: Double get() = if (cruiseLearnt) speed else max(speed, cruiseSpeed)
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
    private var holdStartMs = 0L
    private var holdStartSpeed = 0.0
    /** World-frame acceleration available in the latest motion sample (braking can be measured). */
    private var accelMeasured = false
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
    private var departureAbs = 0.0
    private var undirectedDistance = 0.0

    private var distanceSinceLoss = 0.0
    private var recoveryDrift: Double? = null
    private var networkCorrections = 0

    // Vehicle model without a line
    private var inVehicle = false
    private var headingKnown = false
    /** False after a stop until a vehicle-like departure is seen (escalators / jiggling do not count). */
    private var vehicleDeparted = true
    private var walkingSeconds = 0.0

    // Stop / departure verification from integrated acceleration (heading independent)
    /** Recent velocity changes (elapsedMs, dvEast, dvNorth) while not still. */
    private val dvHistory = ArrayDeque<DoubleArray>()
    /**
     * Velocity vector integrated from world-frame acceleration since the last confirmed stop
     * (where the velocity is known to be zero). Curves and compass offset do not matter: the
     * integral of acceleration is the true change of velocity.
     */
    private var velE = 0.0
    private var velN = 0.0
    private var velPeak = 0.0
    private var velKnown = false
    /** Horizontal accelerometer bias (m/s²), learnt while the train stands at a station. */
    private var accBiasE = 0.0
    private var accBiasN = 0.0
    /** Seconds of motion since the integrated velocity was last known to be zero. */
    private var velSeconds = 0.0
    /** 1-sigma speed error after fusing the inertial speed (m/s); NaN when not fused this tick. */
    private var fusedSpeedSigma = Double.NaN

    /**
     * Learnt rotation between the phone's "north" and true north (degrees). Train motors and
     * steel disturb the magnetometer by tens of degrees; comparing measured acceleration with
     * the known direction of travel (GPS, or the track at a departure) estimates it. Stored
     * as an averaged unit vector so that angles average correctly.
     */
    private var compassOffX = 0.0
    private var compassOffY = 0.0
    private val compassOffsetDeg: Double
        get() = if (sqrt(compassOffX * compassOffX + compassOffY * compassOffY) < COMPASS_OFFSET_MIN_CONFIDENCE) 0.0
        else Math.toDegrees(atan2(compassOffY, compassOffX))
    private var lastGnssSpeedForCal = -1.0
    /** Speed of the latest satellite fix (NaN without one), and since when it has been standing. */
    private var gpsSpeedMps = Double.NaN
    private var gpsStillSinceMs = 0L
    /** Without GPS: since when the vibration has been below this phone's learnt stillness threshold. */
    private var quietSinceMs = 0L
    private var gnssAccelSign = 0
    private var gnssCourse = 0.0
    private var lastGnssMsForCal = 0L
    /** Speed when the phone last became still, for the braking check. */
    private var speedAtStill = 0.0
    private var stillAccepted = true
    /** False after an accepted stop until acceleration shows the vehicle really pulled away. */
    private var motionConfirmed = true
    private var unconfirmedMovingSec = 0.0
    private var falseStillsIgnored = 0

    // Pedestrian dead reckoning
    private var pdrSteps = 0

    // Radio positioning
    /** Learnt Wi-Fi / cell map; the caller loads and saves it. */
    var radioMap = RadioMap()
    private var trackHmm: TrackHmm? = null
    private var beliefValid = false
    private var beliefDist = 0.0
    private var beliefUncertainty = 0.0
    private var radioCorrections = 0
    private var radioSignalsHeard = 0
    /** The last station snap was made with a small enough error to be trusted for learning. */
    private var stationSnapTrusted = false
    /** A station the radio saw us pass while moving: not a hold target any more. */
    private var radioPassedStation: String? = null

    // Segment memory and tunnel radio learning
    /** Per-segment run times; the caller loads and saves it. */
    var rideMemory = RideMemory()
    /** Model distance moved since the last station snap (for placing tunnel scans). */
    private var rawModelDist = 0.0
    private var segmentStart: MetroStation? = null
    private var segmentStartTrusted = false
    private var segmentRouteId: String? = null
    /** Radio scans heard on the current run: (scan, seconds of motion since the run began, model distance). */
    private val tunnelScans = ArrayList<Triple<RadioScan, Double, Double>>()
    /** Inertial velocity history of the current run, for placing the scans afterwards (ZUPT smoothing). */
    private val runTimes = ArrayList<Double>()
    private val runVelE = ArrayList<Double>()
    private val runVelN = ArrayList<Double>()
    /** Pieces of the run between zero-velocity updates: (first index, last index, residual E, residual N). */
    private val runPieces = ArrayList<DoubleArray>()
    private var runPieceStart = 0
    private var runSeconds = 0.0
    private var runInertialOk = false
    /** Cruise speed of the current run learnt from earlier rides, if any. */
    private var segmentCruise: Double? = null

    /** Gyroscope turn-rate bias (°/s), learnt while the phone stands still. */
    private var yawBiasDps = 0.0
    /** Whether the latest motion sample had a gyroscope turn rate. */
    private var gyroAvailable = false

    // Curve matching (gyroscope turn vs. line curves)
    private var yawSinceRef = 0.0
    private var curveRefDist = 0.0
    private var curveRefValid = false
    private var curveRefSec = 0.0
    private var lastAppliedYaw = 0.0
    private var yawSettledSec = 0.0
    /** Turn rate smoothed over ~2 s (single gyro readings are noisy). */
    private var yawSmoothed = 0.0
    private var curveCorrections = 0

    private enum class Evidence { RADIO, CURVE }

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
        beliefValid = false
        resetSegment()
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
        beliefValid = false
        resetSegment()
        pdrSteps = 0
        lastGnssMs = nowMs
        vehicleDeparted = true
        motionConfirmed = true
        dvHistory.clear()
        velKnown = false
        gpsLostAtMs = 0
        distanceSinceLoss = 0.0
        undirectedDistance = 0.0
        clearHold()
        lastAccepted = fix
        lastAccuracy = fix.accuracy
        mock = fix.isMock

        val fixSpeed = if (fix.hasSpeed) fix.speedMeterPerSecond.toDouble() else speed
        speed = fixSpeed.coerceIn(0.0, MAX_SPEED_MPS)
        if (fixSpeed >= VEHICLE_MIN_MPS) inVehicle = true else if (fixSpeed < WALKING_MAX_MPS && fix.hasSpeed) inVehicle = false
        if (fixSpeed > CRUISE_LEARN_MIN_MPS) {
            cruiseSpeed = (cruiseSpeed * 0.9 + fixSpeed * 0.1).coerceIn(MIN_CRUISE_MPS, MAX_CRUISE_MPS)
            cruiseEvidence++
        }

        // Every accepted fix is the position: holding the old one while slow froze it (fixes
        // without speed counted as slow), so the log and map repeated one point.
        lat = fix.latitude; lon = fix.longitude
        hasPosition = true
        source = PositionSource.GPS
        uncertainty = fix.accuracy.toDouble()
        if (fix.hasBearing && fixSpeed > 1.5) { heading = fix.bearingDegree.toDouble(); headingKnown = true }
        // Speeding up / braking with a known GPS course: the measured acceleration direction
        // tells the compass error (see onMotion, which uses gnssAccelSign).
        gnssAccelSign = 0
        if (fix.hasSpeed && fix.hasBearing && lastGnssSpeedForCal >= 0 && fixSpeed > 3.0) {
            val dts = (nowMs - lastGnssMsForCal) / 1000.0
            if (dts in 0.5..2.5) {
                val dv = (fixSpeed - lastGnssSpeedForCal) / dts
                gnssAccelSign = if (dv >= COMPASS_CAL_MIN_ACCEL) 1 else if (dv <= -COMPASS_CAL_MIN_ACCEL) -1 else 0
                gnssCourse = fix.bearingDegree.toDouble()
            }
        }
        lastGnssSpeedForCal = if (fix.hasSpeed) fixSpeed else -1.0
        // GPS says whether the vehicle stands or moves: the reference for learning the phone's vibration.
        gpsSpeedMps = if (fix.hasSpeed) fix.speedMeterPerSecond.toDouble() else Double.NaN
        if (fix.hasSpeed && gpsSpeedMps < GPS_STILL_SPEED_MPS) { if (gpsStillSinceMs == 0L) gpsStillSinceMs = nowMs } else gpsStillSinceMs = 0L
        lastGnssMsForCal = nowMs

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

    /**
     * Network fix, the app's own radio fix ([radio] = true), or a satellite fix too poor / not
     * yet confirmed to trust fully.
     */
    private fun handleWeak(fix: GpsPoint, radio: Boolean = false) {
        if (gpsAvailable) return // a real satellite fix is better
        if (fix.isMock) mock = true
        val r = max(fix.accuracy.toDouble(), 10.0) * WEAK_ACCURACY_INFLATION
        val weakSource = if (radio) PositionSource.RADIO else PositionSource.NETWORK
        // A poor satellite fix still measures speed well (Doppler): use it gently.
        if (!radio && fix.isGnss && fix.hasSpeed && fix.accuracy <= WEAK_GNSS_SPEED_MAX_ACCURACY_METERS && motionConfirmed && hasPosition) {
            val trueSpeed = fix.speedMeterPerSecond.toDouble().coerceIn(0.0, MAX_SPEED_MPS)
            speed += WEAK_GNSS_SPEED_WEIGHT * (trueSpeed - speed)
        }

        if (!hasPosition) {
            lat = fix.latitude; lon = fix.longitude
            hasPosition = true
            source = weakSource
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
                    beliefValid = false
                    events.add("Locked to ${rt.lineName} using ${if (radio) "radio (Wi-Fi / cell)" else "network"} location")
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
                if (radio) radioCorrections++ else networkCorrections++
                beliefValid = false
                applyTrackPosition(rt)
                return
            }
        }

        // Free-space blend (no line): trust network more as our own estimate degrades.
        val d = GeoToolbox.calculateDistance(lat, lon, fix.latitude, fix.longitude)
        val followingWeak = source == PositionSource.NETWORK || source == PositionSource.RADIO
        if (!followingWeak && d > WEAK_GATE_SIGMAS * sqrt(uncertainty * uncertainty + r * r)) return
        val k = (uncertainty * uncertainty) / (uncertainty * uncertainty + r * r)
        lat += k * (fix.latitude - lat)
        lon += k * (fix.longitude - lon)
        uncertainty = sqrt(uncertainty * uncertainty * r * r / (uncertainty * uncertainty + r * r))
        if (radio) radioCorrections++ else networkCorrections++
        if (k >= 0.5) source = weakSource
    }

    /** No satellite fix for a while (the caller's watchdog decides how long). */
    fun onGpsLost(elapsedMs: Long) {
        nowMs = elapsedMs
        if (!gpsAvailable && gpsLostAtMs != 0L) return
        gpsAvailable = false
        gpsLostAtMs = elapsedMs
        distanceSinceLoss = 0.0
        beliefValid = false
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
            uncertainty = max(uncertainty, MIN_TRACK_UNCERTAINTY_METERS) + gapSec * (speedErrorFraction * speed + TRACK_BASE_ERROR_MPS)
            events.add("GPS lost — tracking along ${r.lineName}")
        } else {
            if (speed > 1.0 && gapSec > 0 && headingKnown) {
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

    fun onMotion(input: MotionSample) {
        val raw = withLearntStillness(input)
        // Remove the accelerometer bias measured while the train stood still.
        val sample = if (raw.orientationKnown) raw.copy(accelEast = raw.accelEast - accBiasE, accelNorth = raw.accelNorth - accBiasN) else raw
        nowMs = sample.elapsedMs
        motion = sample.state
        pressureRapid = sample.pressureRapidChange
        accelMeasured = sample.orientationKnown
        gyroAvailable = !raw.yawRateDps.isNaN()
        if (gyroAvailable && raw.state == MotionState.STATIONARY && raw.stationaryForMs >= BIAS_LEARN_AFTER_STILL_MS) {
            // Standing still (with or without GPS): whatever turn rate is measured is gyroscope bias.
            yawBiasDps += min(1.0, raw.dtSec / GYRO_BIAS_TAU_SEC) * (raw.yawRateDps - yawBiasDps)
        }
        if (gpsAvailable && gnssAccelSign != 0 && sample.orientationKnown) {
            val mag = sqrt(sample.accelEast * sample.accelEast + sample.accelNorth * sample.accelNorth)
            if (mag >= COMPASS_CAL_MIN_ACCEL) {
                val measured = Math.toDegrees(atan2(sample.accelEast, sample.accelNorth))
                val travel = if (gnssAccelSign > 0) gnssCourse else gnssCourse + 180.0
                learnCompassOffset(measured, travel, COMPASS_LEARN_RATE_GPS)
            }
        }
        if (gpsAvailable || !hasPosition) return
        val dt = sample.dtSec.coerceIn(0.0, 1.0)
        var pdrStep: Double? = null
        if (sample.state == MotionState.WALKING) walkingSeconds += dt else walkingSeconds = 0.0

        var state = sample.state
        if (sample.orientationKnown) {
            if (state != MotionState.STATIONARY) {
                recordDv(sample, dt)
                integrateVelocity(sample, dt)
                stillAccepted = false
                speedAtStill = 0.0
            } else if (!stillAccepted) {
                // The phone looks still. Did the vehicle brake to get here, or is it gliding
                // smoothly through the tunnel? A stop from cruise needs a matching Δv.
                if (sample.stationaryForMs <= STILL_VERIFY_GRACE_MS || speedAtStill == 0.0) speedAtStill = max(speedAtStill, speed)
                val braked = if (velKnown) {
                    // Integrated velocity since the last stop: back near zero only if we really stopped.
                    // Allowance grows with the run time: residual bias integrates into velocity.
                    velPeak < STOP_CHECK_MIN_MPS || sqrt(velE * velE + velN * velN) <=
                        maxOf(STOP_RESIDUAL_MPS, STOP_RESIDUAL_FRACTION * velPeak, STOP_RESIDUAL_GROWTH_MPS_PER_SEC * velSeconds)
                } else {
                    speedAtStill < STOP_CHECK_MIN_MPS || brakingEvidence() >= BRAKE_MIN_FRACTION * speedAtStill
                }
                if (braked || sample.stationaryForMs >= LONG_STILL_ACCEPT_MS) {
                    stillAccepted = true
                    speedAtStill = 0.0
                    dvHistory.clear()
                    closeRunPiece() // whatever velocity is left now is drift of this piece
                    velE = 0.0; velN = 0.0; velPeak = 0.0; velKnown = true; velSeconds = 0.0
                } else {
                    if (sample.stationaryForMs <= STILL_VERIFY_GRACE_MS) falseStillsIgnored++
                    state = MotionState.MOVING // keep gliding at the current speed
                    integrateVelocity(sample, dt) // a glide is motion: keep the odometer running
                }
            }
        }

        when (state) {
            MotionState.STATIONARY -> {
                speed = 0.0
                speedAtStill = 0.0
                if (raw.orientationKnown && stillAccepted && sample.stationaryForMs >= BIAS_LEARN_AFTER_STILL_MS) {
                    // Standing still: whatever horizontal acceleration is measured is sensor bias.
                    val k = min(1.0, dt / BIAS_LEARN_TAU_SEC)
                    accBiasE += k * (raw.accelEast - accBiasE)
                    accBiasN += k * (raw.accelNorth - accBiasN)
                }
                if (sample.orientationKnown) { motionConfirmed = false; unconfirmedMovingSec = 0.0 }
                vehicleDeparted = false
                startDepartureWatch() // restart the window: the departure begins after the last still moment
                val r = route?.takeIf { onTrack }
                if (r != null && !stopHandled && sample.stationaryForMs >= STATION_STOP_MS) {
                    stopHandled = true
                    handleStop(r)
                }
                return
            }
            MotionState.WALKING -> {
                stopHandled = false
                // A long walk means the ride is over (a short one is a walk inside the carriage).
                if (walkingSeconds >= WALK_ENDS_RIDE_SEC) inVehicle = false
                if (route != null && onTrack) {
                    // Someone walking on the platform or inside the carriage: footsteps hide the
                    // train's own acceleration, so it keeps the speed it had (0 at a platform,
                    // its running speed in a moving train) until the walking stops.
                } else {
                    // Pedestrian dead reckoning: counted steps × step length along the phone's heading.
                    snappedAtStation = false
                    if (!sample.deviceHeadingDeg.isNaN()) { heading = sample.deviceHeadingDeg; headingKnown = true }
                    val steps = sample.steps
                    if (steps != null) {
                        val stepDist = steps * Pdr.stepLength(sample.stepCadenceHz)
                        pdrStep = stepDist
                        pdrSteps += steps
                        if (dt > 0) speed += (stepDist / dt - speed) * min(1.0, dt / PDR_SPEED_SMOOTHING_SEC)
                    } else {
                        speed = WALKING_SPEED_MPS
                    }
                }
            }
            MotionState.MOVING -> {
                stopHandled = false
                if (sample.state == MotionState.MOVING) snappedAtStation = false
                movingSecondsSinceStop += dt
                if (watchingDeparture) detectDeparture(sample, dt)
                val r = route?.takeIf { onTrack }
                val inRide = r != null || (inVehicle && vehicleDeparted)
                if (!motionConfirmed && inRide) {
                    // After a stop: jiggling the phone is not a departure. Wait until the
                    // acceleration of the last few seconds adds up to a real speed.
                    val dv = recentDv(DEPARTURE_CONFIRM_WINDOW_MS)
                    unconfirmedMovingSec += dt
                    if (dv >= DEPARTURE_CONFIRM_DV_MPS && recentConsistency(DEPARTURE_CONFIRM_WINDOW_MS) >= DEPARTURE_MIN_CONSISTENCY) {
                        motionConfirmed = true
                        speed = dv
                    } else if (unconfirmedMovingSec >= UNCONFIRMED_MOVING_ACCEPT_SEC) {
                        // Moving-type vibration for this long without a departure signal: the
                        // stop was probably a smooth glide mis-read as still. Resume the model.
                        motionConfirmed = true
                        speed = cruiseSpeed * 0.5
                        events.add("Movement resumed without a clear departure (stop was probably misdetected)")
                    } else {
                        speed = 0.0
                    }
                } else if (r != null) {
                    // On a metro line: IMU acceleration along the track + pull toward the cruise
                    // speed of this run (learnt) or of the line, fused with the inertial speed.
                    val along = if (directionKnown) {
                        val b = Math.toRadians(r.bearingAt(navDist, direction) + compassOffsetDeg)
                        sample.accelEast * sin(b) + sample.accelNorth * cos(b)
                    } else 0.0
                    speed += along.coerceIn(-MAX_ACCEL_MPS2, MAX_ACCEL_MPS2) * dt
                    val tau = if (directionKnown) CRUISE_PULL_TAU_SEC else CRUISE_PULL_TAU_UNCERTAIN_SEC
                    speed += ((segmentCruise ?: cruiseSpeed) - speed) * (dt / tau)
                    fuseIntegratedVelocity(dt)
                } else if (inVehicle && vehicleDeparted) {
                    // Riding with no saved line: same speed model along the last known heading.
                    val along = if (headingKnown) {
                        val b = Math.toRadians(heading + compassOffsetDeg)
                        sample.accelEast * sin(b) + sample.accelNorth * cos(b)
                    } else 0.0
                    speed += along.coerceIn(-MAX_ACCEL_MPS2, MAX_ACCEL_MPS2) * dt
                    speed += (cruiseSpeed - speed) * (dt / CRUISE_PULL_TAU_SEC)
                    fuseIntegratedVelocity(dt)
                } else {
                    // Not known to be in a vehicle (building, escalator): keep the last real
                    // speed and let it fade; never invent motion when there was none.
                    speed *= kotlin.math.exp(-dt / FREE_SPEED_DECAY_TAU_SEC)
                }
                speed = speed.coerceIn(0.0, MAX_SPEED_MPS)
            }
        }

        val onLine = route?.takeIf { onTrack }
        if (onLine != null) {
            updateSegmentCruise(onLine)
            updateCurve(onLine, sample, dt)
        } else {
            segmentCruise = null
            if (state == MotionState.MOVING) followTurnWithGyro(sample, dt)
        }
        val step = pdrStep ?: (speed * dt)
        if (step <= 0.0) {
            // Vibration says moving but no movement is modelled (no departure seen yet): the
            // position may be going stale, so the error must grow instead of staying small.
            if (state == MotionState.MOVING && onLine == null) uncertainty += dt * UNMODELLED_MOTION_ERROR_MPS
            return
        }
        distanceSinceLoss += step
        val r = onLine
        if (r != null) {
            rawModelDist += step
            // Position error grows with the speed error: from the inertial fusion when it ran,
            // else from how well the speed of this run is known.
            // (A line-wide cruise is only a rough guess for one run: runs differ a lot.)
            val speedSigma = if (!fusedSpeedSigma.isNaN()) fusedSpeedSigma
                else (if (segmentCruise != null) SEGMENT_SPEED_ERROR_FRACTION else max(speedErrorFraction, MODEL_SPEED_ERROR_FRACTION)) * speedForError
            uncertainty += dt * (speedSigma + TRACK_BASE_ERROR_MPS)
            advanceAlongTrack(r, step)
        } else {
            uncertainty += dt * (FREE_SPEED_ERROR_FRACTION * speed + FREE_BASE_ERROR_MPS)
            val (nLat, nLon) = GeoToolbox.moveLocation(lat, lon, step, heading)
            lat = nLat; lon = nLon
            source = PositionSource.DEAD_RECKONING
        }
    }

    /**
     * With GPS: learn how strongly this phone vibrates while the vehicle stands and while it moves.
     * Without GPS: where the fixed stillness rule says "moving" but the vibration has stayed below
     * the learnt threshold (an idling engine, air conditioning, a stiff mount), the vehicle stands.
     * The braking check in [onMotion] still has to agree before a stop is accepted.
     */
    private fun withLearntStillness(s: MotionSample): MotionSample {
        if (gpsAvailable) {
            quietSinceMs = 0L
            if (s.vibration.isNaN() || gpsSpeedMps.isNaN() || s.elapsedMs - lastGnssMs > GPS_SPEED_MAX_AGE_MS) return s
            when {
                gpsStillSinceMs != 0L && s.elapsedMs - gpsStillSinceMs >= GPS_STILL_CONFIRM_MS -> rideMemory.learnVibration(s.vibration, vehicleMoving = false)
                gpsSpeedMps >= GPS_MOVING_SPEED_MPS -> rideMemory.learnVibration(s.vibration, vehicleMoving = true)
            }
            return s
        }
        val threshold = rideMemory.vibration.stillThreshold
        if (threshold == null || s.vibration.isNaN() || s.state != MotionState.MOVING || s.vibration >= threshold) {
            quietSinceMs = 0L
            return s
        }
        if (quietSinceMs == 0L) quietSinceMs = s.elapsedMs
        val quietFor = s.elapsedMs - quietSinceMs
        return if (quietFor >= QUIET_CONFIRM_MS) s.copy(state = MotionState.STATIONARY, stationaryForMs = quietFor) else s
    }

    /**
     * Speed measurement from inertial navigation: the velocity integrated from world-frame
     * acceleration since the last stop (where it was exactly zero, ZUPT), bias removed. Its error
     * grows with time (residual bias, tilt), the model's with the unknown speed of this run; the
     * two are blended by those errors — right after a departure the measurement dominates, on a
     * long run the learnt speeds take over.
     */
    private fun fuseIntegratedVelocity(dt: Double) {
        fusedSpeedSigma = Double.NaN
        if (!velKnown || !motionConfirmed) return
        val measured = sqrt(velE * velE + velN * velN)
        val sigmaMeas = VEL_MEAS_BASE_MPS + VEL_MEAS_GROWTH_MPS_PER_SEC * velSeconds
        val modelFraction = if (segmentCruise != null) SEGMENT_SPEED_ERROR_FRACTION else MODEL_SPEED_ERROR_FRACTION
        val sigmaModel = modelFraction * max(speed, segmentCruise ?: cruiseSpeed)
        val w = sigmaModel * sigmaModel / (sigmaModel * sigmaModel + sigmaMeas * sigmaMeas)
        speed += w * (measured - speed) * min(1.0, dt / VEL_FUSION_TAU_SEC)
        fusedSpeedSigma = sqrt(sigmaModel * sigmaModel * sigmaMeas * sigmaMeas / (sigmaModel * sigmaModel + sigmaMeas * sigmaMeas))
    }

    private fun recordDv(sample: MotionSample, dt: Double) {
        dvHistory.addLast(doubleArrayOf(sample.elapsedMs.toDouble(), sample.accelEast * dt, sample.accelNorth * dt))
        while (dvHistory.isNotEmpty() && sample.elapsedMs - dvHistory.first()[0] > DV_HISTORY_MS) dvHistory.removeFirst()
    }

    /**
     * Largest velocity change that ends now, over any stretch of the recent history. Summing
     * the vector (not its size) makes sensor noise and hand jiggle cancel out, and does not
     * need the heading, so a compass disturbed by the train's motors does not matter.
     */
    private fun brakingEvidence(): Double {
        var e = 0.0; var n = 0.0; var best = 0.0
        for (i in dvHistory.indices.reversed()) {
            e += dvHistory[i][1]; n += dvHistory[i][2]
            best = max(best, sqrt(e * e + n * n))
        }
        return best
    }

    /**
     * How one-directional the recent acceleration was: |Σ dv| / Σ |dv|. A train pulling out
     * pushes one way (close to 1); jiggling the phone in the hand cancels out (close to 0).
     */
    private fun recentConsistency(windowMs: Long): Double {
        val end = dvHistory.lastOrNull()?.get(0) ?: return 0.0
        var e = 0.0; var n = 0.0; var total = 0.0
        for (i in dvHistory.indices.reversed()) {
            if (end - dvHistory[i][0] > windowMs) break
            val de = dvHistory[i][1]; val dn = dvHistory[i][2]
            e += de; n += dn; total += sqrt(de * de + dn * dn)
        }
        return if (total <= 0.0) 0.0 else sqrt(e * e + n * n) / total
    }

    /** Size of the velocity change over the last [windowMs]. */
    private fun recentDv(windowMs: Long): Double {
        val end = dvHistory.lastOrNull()?.get(0) ?: return 0.0
        var e = 0.0; var n = 0.0
        for (i in dvHistory.indices.reversed()) {
            if (end - dvHistory[i][0] > windowMs) break
            e += dvHistory[i][1]; n += dvHistory[i][2]
        }
        return sqrt(e * e + n * n)
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
            // A train that will stop here is braking by now; one still at speed is running through
            // (express): move on instead of repeating the station position for half a run.
            val passing = accelMeasured && directionKnown && nowMs - holdStartMs >= HOLD_PASS_CHECK_MS &&
                speed >= HOLD_PASS_MIN_SPEED_MPS && speed >= HOLD_PASS_SPEED_FRACTION * holdStartSpeed
            if (pendingOvershoot <= holdAllowance && !passing) return
            events.add("Passed ${held.name} without stopping")
            navDist = (held.distanceAlongTrack + direction * pendingOvershoot).coerceIn(0.0, r.length)
            clearHold()
            applyTrackPosition(r)
            return
        }
        var newDist = navDist + direction * step
        // Never hold at the station the train is pulling out of.
        val first = r.nextStation(navDist, direction, tolerance = 0.0)
        val alreadyLeft = first != null && ((first.name == lastStopStation?.name && movingSecondsSinceStop > 0.0) || first.name == radioPassedStation)
        val next = if (alreadyLeft) r.nextStation(first!!.distanceAlongTrack, direction, tolerance = 0.0) else first
        if (next != null) {
            val beyond = direction * (newDist - next.distanceAlongTrack)
            if (beyond > 0) {
                // Segment = station strictly behind `next` → `next`.
                val prev = r.nextStation(next.distanceAlongTrack, -direction, tolerance = 0.0)
                val segLen = if (prev != null) abs(next.distanceAlongTrack - prev.distanceAlongTrack)
                else abs(next.distanceAlongTrack - (if (direction > 0) 0.0 else r.length))
                heldAt = next
                // Hold only as long as our own error could explain: a confident estimate that
                // runs well past the station means the train went through it (express).
                holdAllowance = max(MIN_OVERSHOOT_METERS, min(segLen * OVERSHOOT_FRACTION, HOLD_SIGMAS * uncertainty))
                pendingOvershoot = beyond
                holdStartMs = nowMs
                holdStartSpeed = speed
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
        val segStartStation = next?.let { r.nextStation(it.distanceAlongTrack, -direction, tolerance = 0.0) }
        val segLen = if (next != null && segStartStation != null) abs(next.distanceAlongTrack - segStartStation.distanceAlongTrack) else 1000.0
        val aheadWindow = max(STOP_SNAP_AHEAD_MIN_METERS, segLen * STOP_SNAP_AHEAD_FRACTION) + pendingOvershoot
        // Radio placed us at a station just before this stop: that is where we are.
        val radioStation = radioPassedStation?.let { name -> r.stations.firstOrNull { it.name == name } }
            ?.takeIf { abs(it.distanceAlongTrack - navDist) <= RADIO_STOP_CONFIRM_METERS }
        val candidates = listOfNotNull(
            next?.takeIf { abs(it.distanceAlongTrack - navDist) <= aheadWindow },
            prev?.takeIf { abs(it.distanceAlongTrack - navDist) <= STOP_SNAP_BEHIND_METERS }
        )
        // Odometer: the drift-corrected inertial distance since the last trusted station says
        // which station (if any) lies that far along. Better than the running estimate, which
        // can lag or lead after express passes and holds.
        val byDistance = stationByRunDistance(r)
        val station = radioStation ?: when (byDistance) {
            null -> candidates.minByOrNull { abs(it.distanceAlongTrack - navDist) }?.takeIf { stationMoreLikelyThanSignalStop(it, segLen) }
            NO_STATION -> null
            else -> byDistance
        }
        if (station == null) {
            events.add("Train stopped between stations (signal?)")
            return
        }
        // A stop placed by the odometer or the radio is a measurement, not a guess.
        val trustedNow = uncertainty <= RADIO_LEARN_MAX_SNAP_UNCERTAINTY_METERS || station === byDistance || station === radioStation
        finishSegment(r, station, trustedNow)
        // Self-calibration: average moving speed over the last station-to-station run.
        val from = lastStopStation
        if (from != null && from.name != station.name && movingSecondsSinceStop >= MIN_SEGMENT_SECONDS_FOR_LEARNING) {
            val observed = abs(station.distanceAlongTrack - from.distanceAlongTrack) / movingSecondsSinceStop
            if (observed in MIN_CRUISE_MPS..MAX_CRUISE_MPS) {
                cruiseSpeed = (cruiseSpeed * 0.5 + observed * 0.5).coerceIn(MIN_CRUISE_MPS, MAX_CRUISE_MPS)
                cruiseEvidence += CRUISE_EVIDENCE_NEEDED
            }
        }
        navDist = station.distanceAlongTrack
        clearHold()
        snappedAtStation = true
        stationSnapTrusted = trustedNow
        radioPassedStation = null
        // The platform is the reference for matching the next curves.
        curveRefValid = true; yawSinceRef = 0.0; lastAppliedYaw = 0.0
        curveRefDist = station.distanceAlongTrack; curveRefSec = 0.0; yawSettledSec = 0.0; yawSmoothed = 0.0
        beliefValid = false
        stationStops++
        lastStopStation = station
        movingSecondsSinceStop = 0.0
        uncertainty = min(uncertainty, STATION_SNAP_UNCERTAINTY_METERS)
        applyTrackPosition(r)
        events.add("Stopped at ${station.name}")
        if (destination?.name == station.name) events.add("Arrived at destination ${station.name}")
    }

    /**
     * The station at the inertial distance travelled since the last trusted stop; [NO_STATION]
     * when none is close enough (a signal stop); null when the odometer is not available.
     */
    private fun stationByRunDistance(r: MetroRoute): MetroStation? {
        val start = segmentStart ?: return null
        if (!directionKnown || !segmentStartTrusted || segmentRouteId != r.id) return null
        val run = smoothedRunDistance() ?: return null
        val travelled = run.second.last()
        if (travelled < MIN_RUN_FOR_ODOMETER_METERS) return null
        val target = start.distanceAlongTrack + direction * travelled
        val best = r.stations.filter { direction * (it.distanceAlongTrack - start.distanceAlongTrack) > MetroRoute.MIN_STATION_SPACING_METERS }
            .minByOrNull { abs(it.distanceAlongTrack - target) } ?: return NO_STATION
        val tolerance = max(ODOMETER_MIN_TOLERANCE_METERS, ODOMETER_TOLERANCE_FRACTION * travelled)
        val odometerMiss = abs(best.distanceAlongTrack - target)
        if (odometerMiss <= tolerance) return best
        // Just outside the odometer's tolerance, but the running estimate reached the same station
        // on its own (not held there, as before a signal stop): two measurements agree.
        val runningAgrees = heldAt == null && abs(best.distanceAlongTrack - navDist) <= ODOMETER_MIN_TOLERANCE_METERS
        return if (runningAgrees && odometerMiss <= 2 * tolerance) best else NO_STATION
    }

    /**
     * Is this stop at [station], or a signal stop somewhere in the tunnel? Compares the
     * probability of the station given our position error (Gaussian) with that of a stop
     * anywhere in the segment (uniform), weighted by how rare signal stops are.
     */
    private fun stationMoreLikelyThanSignalStop(station: MetroStation, segLen: Double): Boolean {
        val sigma = max(uncertainty, MIN_TRACK_UNCERTAINTY_METERS)
        val d = abs(station.distanceAlongTrack - navDist) + if (heldAt?.name == station.name) pendingOvershoot else 0.0
        val pStation = STATION_STOP_PRIOR * kotlin.math.exp(-0.5 * (d / sigma) * (d / sigma)) / (sigma * SQRT_2PI)
        val pSignal = (1 - STATION_STOP_PRIOR) / max(segLen, MIN_SEGMENT_FOR_SIGNAL_METERS)
        return pStation >= pSignal
    }

    /** When the train pulls out, its acceleration points along the direction of travel. */
    private fun detectDeparture(sample: MotionSample, dt: Double) {
        departureAccumE += sample.accelEast * dt
        departureAccumN += sample.accelNorth * dt
        departureAbs += sqrt(sample.accelEast * sample.accelEast + sample.accelNorth * sample.accelNorth) * dt
        departureTime += dt
        if (departureTime < DEPARTURE_WINDOW_SEC) return
        watchingDeparture = false
        val meanE = departureAccumE / departureTime
        val meanN = departureAccumN / departureTime
        val consistency = if (departureAbs > 0) sqrt(departureAccumE * departureAccumE + departureAccumN * departureAccumN) / departureAbs else 0.0
        val strong = sqrt(meanE * meanE + meanN * meanN) >= DEPARTURE_MIN_ACCEL && consistency >= DEPARTURE_MIN_CONSISTENCY
        val measured = (Math.toDegrees(atan2(meanE, meanN)) + 360.0) % 360.0
        val bearing = (measured - compassOffsetDeg + 360.0) % 360.0

        if (strong && destination == null) maybeSwitchLine(bearing)
        if (strong) {
            // Only a vehicle pulls away this hard for this long.
            inVehicle = true
            vehicleDeparted = true
        }
        val r = route?.takeIf { onTrack }
        if (r == null) {
            // The gyroscope keeps the heading through stops; the compass-based departure direction
            // is only needed without it.
            if (strong && (!headingKnown || !gyroAvailable)) { heading = bearing; headingKnown = true }
            // No pull-away seen (the window may have caught engine vibration at a light): keep
            // watching, or the estimate would never move again until the next stop.
            if (!strong && !vehicleDeparted) startDepartureWatch()
            return
        }
        if (strong && destination == null) { // a chosen destination wins
            val diffForward = GeoToolbox.bearingDifference(bearing, r.bearingAt(navDist, 1))
            val newDir = if (diffForward <= 90.0) 1 else -1
            // A known direction only flips on a clearly opposite departure (e.g. the train
            // reversing at a terminus), not on one that is merely rotated by compass error.
            val clearlyOpposite = min(diffForward, 180.0 - diffForward) <= 90.0 - DIRECTION_FLIP_MARGIN_DEG
            if (!directionKnown || (newDir != direction && clearlyOpposite)) {
                if (newDir != direction) clearHold()
                direction = newDir
                directionKnown = true
                events.add("Departure detected, heading towards ${r.nextStation(navDist, direction)?.name ?: "end of line"}")
            }
            if (directionKnown && newDir == direction) {
                learnCompassOffset(measured, r.bearingAt(navDist, direction), COMPASS_LEARN_RATE_DEPARTURE)
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

    private fun learnCompassOffset(measuredDeg: Double, trueDeg: Double, rate: Double) {
        val d = Math.toRadians(measuredDeg - trueDeg)
        compassOffX += rate * (cos(d) - compassOffX)
        compassOffY += rate * (sin(d) - compassOffY)
    }

    /**
     * Without a line, the vehicle's turns come from the gyroscope: the phone turns with the vehicle,
     * so its turn rate about the vertical is the vehicle's (curves, junctions, roundabouts).
     */
    private fun followTurnWithGyro(sample: MotionSample, dt: Double) {
        if (sample.yawRateDps.isNaN() || !headingKnown) return
        val rate = sample.yawRateDps - yawBiasDps
        if (abs(rate) > VEHICLE_MAX_TURN_DPS) return // the phone is being turned in the hand, not the vehicle
        heading = ((heading + rate * dt) % 360.0 + 360.0) % 360.0
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
        beliefValid = false
        resetSegment()
        clearHold()
        directionKnown = false
        destination = null
        lastStopStation = best.first.nearestStation(navDist)
        events.add("Switched to ${best.first.lineName} (departure direction matches)")
    }

    // ---- Radio input -----------------------------------------------------------------------

    /**
     * A Wi-Fi or mobile-cell scan (and any Wi-Fi RTT ranges). Learns the scan when the position is
     * known; otherwise uses it to correct the position.
     */
    fun onRadioScan(scan: RadioScan) {
        nowMs = scan.elapsedMs
        radioSignalsHeard = scan.signals.size
        if (!hasPosition) {
            // Cold start underground: the radio map is the only absolute reference.
            radioMap.locate(scan)?.let { handleWeak(radioFixPoint(it), radio = true) }
            return
        }
        if (gpsAvailable) {
            val acc = lastAccuracy
            if (acc != null && acc <= RADIO_LEARN_GPS_ACCURACY_METERS && nowMs - lastGnssMs <= RADIO_LEARN_MAX_FIX_AGE_MS) {
                radioMap.learn(scan, lat, lon, max(acc.toDouble(), 5.0))
            }
            return
        }
        val r = route?.takeIf { onTrack }
        if (r == null) {
            radioMap.locate(scan)?.let { handleWeak(radioFixPoint(it), radio = true) }
            return
        }
        if (snappedAtStation) {
            // Standing at a platform: learn it, unless the radio clearly says we are elsewhere
            // (the stop was snapped to the wrong station) — then let the evidence build up instead.
            val best = radioMap.match(scan, 1).firstOrNull()
            val conflict = best != null && best.weight >= RADIO_CONFLICT_MIN_WEIGHT &&
                GeoToolbox.calculateDistance(best.fingerprint.lat, best.fingerprint.lon, lat, lon) > RADIO_CONFLICT_METERS
            if (!conflict) {
                if (stationSnapTrusted) radioMap.learn(scan, lat, lon, RADIO_LEARN_STATION_SIGMA_METERS)
                return
            }
        }
        if (!snappedAtStation && segmentStart != null && scan.signals.isNotEmpty() && tunnelScans.size < MAX_TUNNEL_SCANS) {
            tunnelScans.add(Triple(scan, runSeconds, rawModelDist))
        }
        radioLikelihood(r, scan)?.let { l ->
            // Scans close together hear the same transmitters: their errors are strongly correlated.
            // Tempering each one keeps the belief from becoming over-confident; cell scans come
            // six times as often as Wi-Fi ones, so each counts for less.
            val exponent = if (scan.signals.keys.all(RadioMap::isCell)) CELL_EVIDENCE_EXPONENT else RADIO_EVIDENCE_EXPONENT
            trackHmmUpdate(r, { d -> Math.pow(l(d), exponent) }, Evidence.RADIO)
        }
    }

    /**
     * HMM forward step on the line with an observation likelihood (radio or curve); applies the
     * result when it is unambiguous.
     */
    private fun trackHmmUpdate(r: MetroRoute, observation: (Double) -> Double, evidence: Evidence) {
        // Kinematic constraint: a train that has pulled out of a station in a known direction
        // cannot be behind that station. This separates look-alike places before and after a
        // station (radio around a station is symmetric) that no signal comparison can tell apart.
        val departed = lastStopStation?.takeIf { directionKnown && !snappedAtStation && motionConfirmed && segmentRouteId == r.id }
        val likelihood: (Double) -> Double = if (departed == null) observation else { d ->
            if (direction * (d - departed.distanceAlongTrack) < -DEPARTED_STATION_MARGIN_METERS) 0.0 else observation(d)
        }
        var hmm = trackHmm
        if (hmm == null || hmm.route.id != r.id) { hmm = TrackHmm(r); trackHmm = hmm; beliefValid = false }
        if (!beliefValid || !hmm.initialised) {
            hmm.reset(navDist, max(uncertainty, MIN_TRACK_UNCERTAINTY_METERS))
        } else {
            // Transition: what the navigator's motion model did since the last radio update.
            val growth = sqrt(max(0.0, uncertainty * uncertainty - beliefUncertainty * beliefUncertainty))
            hmm.predict(navDist - beliefDist, growth)
        }
        beliefValid = true
        if (hmm.update(likelihood)) {
            val b = hmm.estimate()
            // Apply when it sharpens the estimate, or when it is confident and our estimate is
            // clearly somewhere else (e.g. a stop was snapped to the wrong station).
            val inconsistent = abs(b.distance - navDist) > WEAK_GATE_SIGMAS * max(uncertainty, b.sigma)
            // Small corrections trade one error for another: act only on a clear gain.
            if (b.modeMass >= RADIO_MIN_MODE_MASS && (b.sigma < MIN_SHARPENING * uncertainty || inconsistent)) {
                // Trains do not reverse between stations: a small backward correction while
                // riding is noise (e.g. the platform just left still heard loudly).
                val backward = directionKnown && direction * (b.distance - navDist) < 0
                // (Approaching and leaving a station sound alike on the radio.) Only a correction
                // far outside our own uncertainty may move the train backwards.
                val target = if (backward && abs(b.distance - navDist) <= WEAK_GATE_SIGMAS * max(b.sigma, uncertainty)) navDist else b.distance
                val jump = abs(target - navDist)
                navDist = target.coerceIn(0.0, r.length)
                uncertainty = max(b.sigma, if (evidence == Evidence.RADIO) RADIO_MIN_SIGMA_METERS else CURVE_MIN_SIGMA_METERS)
                clearHold()
                if (jump > RADIO_CONFLICT_METERS) {
                    snappedAtStation = false
                    segmentStart = null // the last snap was probably wrong: do not learn from this run
                }
                if (evidence == Evidence.RADIO && motion != MotionState.STATIONARY && speed > 0.0) {
                    radioPassedStation = r.nearestStation(navDist)
                        ?.takeIf { abs(it.distanceAlongTrack - navDist) <= RADIO_PASS_RADIUS_METERS }?.name ?: radioPassedStation
                }
                if (evidence == Evidence.RADIO) radioCorrections++ else curveCorrections++
                applyTrackPosition(r)
                if (jump >= RADIO_REPORT_JUMP_METERS) {
                    val what = if (evidence == Evidence.RADIO) "Radio (Wi-Fi / cell)" else "Curve matching"
                    events.add(String.format(Locale.US, "%s moved the position %.0f m along %s", what, jump, r.lineName))
                }
            }
        }
        beliefDist = navDist
        beliefUncertainty = uncertainty
    }

    // ---- Segment memory, tunnel learning, curve matching -------------------------------------

    private fun resetSegment() {
        segmentStart = null
        segmentStartTrusted = false
        segmentRouteId = null
        clearRun()
        curveRefValid = false
    }

    private fun clearRun() {
        tunnelScans.clear()
        rawModelDist = 0.0
        runTimes.clear(); runVelE.clear(); runVelN.clear(); runPieces.clear()
        runPieceStart = 0
        runSeconds = 0.0
        runInertialOk = velKnown
    }

    private fun integrateVelocity(sample: MotionSample, dt: Double) {
        if (velKnown) {
            velE += sample.accelEast * dt; velN += sample.accelNorth * dt
            velPeak = max(velPeak, sqrt(velE * velE + velN * velN))
            velSeconds += dt
        }
        recordRunSample(dt)
    }

    private fun recordRunSample(dt: Double) {
        if (segmentStart == null || snappedAtStation) return
        runSeconds += dt
        if (!velKnown) { runInertialOk = false; return }
        if (runTimes.size >= MAX_RUN_SAMPLES) { runInertialOk = false; return }
        runTimes.add(runSeconds); runVelE.add(velE); runVelN.add(velN)
    }

    private fun closeRunPiece() {
        if (segmentStart == null || runTimes.size <= runPieceStart) return
        runPieces.add(doubleArrayOf(runPieceStart.toDouble(), (runTimes.size - 1).toDouble(), velE, velN))
        runPieceStart = runTimes.size
    }

    /**
     * Distance travelled since the run began, as a function of run time, from the inertial
     * velocity with the drift of each piece removed (the velocity must be zero at every stop,
     * so what is left there is drift, spread linearly over the piece). Null if not available.
     */
    private fun smoothedRunDistance(): Pair<DoubleArray, DoubleArray>? {
        closeRunPiece()
        if (!runInertialOk || runTimes.size < 2 || runPieces.isEmpty()) return null
        val n = runTimes.size
        val times = DoubleArray(n) { runTimes[it] }
        val dist = DoubleArray(n)
        var acc = 0.0
        for (p in runPieces) {
            val i0 = p[0].toInt(); val i1 = p[1].toInt()
            val t0 = times[i0]; val span = max(times[i1] - t0, 1e-6)
            for (i in i0..i1) {
                val f = (times[i] - t0) / span
                val e = runVelE[i] - f * p[2]; val nn = runVelN[i] - f * p[3]
                val dt = if (i == 0) times[0] else times[i] - times[i - 1]
                acc += sqrt(e * e + nn * nn) * dt
                dist[i] = acc
            }
        }
        return times to dist
    }

    private fun interpolate(xs: DoubleArray, ys: DoubleArray, x: Double): Double {
        if (x <= xs.first()) return ys.first() * (x / xs.first()).coerceIn(0.0, 1.0)
        if (x >= xs.last()) return ys.last()
        var lo = 0; var hi = xs.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (xs[mid] <= x) lo = mid else hi = mid }
        val f = (x - xs[lo]) / max(xs[hi] - xs[lo], 1e-9)
        return ys[lo] + f * (ys[hi] - ys[lo])
    }

    /** Learnt cruise speed of the run from the last stop to the next station, if this run is known. */
    private fun updateSegmentCruise(r: MetroRoute) {
        val from = segmentStart
        val to = if (from != null && directionKnown && segmentRouteId == r.id) r.nextStation(from.distanceAlongTrack, direction, tolerance = 0.0) else null
        segmentCruise = if (from != null && to != null) rideMemory.cruise(r.id, from.name, to.name) else null
    }

    /**
     * A stop at [station] ends the run that began at the previous trusted stop: remember the
     * run's model scale, and place the radio scans heard on the way along the tunnel.
     */
    private fun finishSegment(r: MetroRoute, station: MetroStation, trustedNow: Boolean) {
        val start = segmentStart
        if (start != null && segmentStartTrusted && trustedNow && segmentRouteId == r.id && start.name != station.name && rawModelDist > 0.0) {
            val trueMeters = abs(station.distanceAlongTrack - start.distanceAlongTrack)
            val dir = if (station.distanceAlongTrack > start.distanceAlongTrack) 1 else -1
            val adjacent = r.nextStation(start.distanceAlongTrack, dir, tolerance = 0.0)?.name == station.name
            if (adjacent) rideMemory.record(r.id, start.name, station.name, trueMeters, movingSecondsSinceStop)
            // Where was each scan taken? Best: the drift-corrected inertial distance (ZUPT
            // smoothing); otherwise in proportion to the model distance, with a wider error.
            val inertial = smoothedRunDistance()?.takeIf { (_, d) -> d.last() / trueMeters in TUNNEL_LEARN_MIN_RATIO..TUNNEL_LEARN_MAX_RATIO }
            val modelOk = rawModelDist / trueMeters in TUNNEL_LEARN_MIN_RATIO..TUNNEL_LEARN_MAX_RATIO
            if (inertial != null || modelOk) {
                tunnelScans.forEach { (scan, atSec, atModel) ->
                    val frac = if (inertial != null) (interpolate(inertial.first, inertial.second, atSec) / inertial.second.last()).coerceIn(0.0, 1.0)
                        else (atModel / rawModelDist).coerceIn(0.0, 1.0)
                    val spreadFraction = if (inertial != null) TUNNEL_LEARN_SIGMA_FRACTION_INERTIAL else TUNNEL_LEARN_SIGMA_FRACTION
                    val d = start.distanceAlongTrack + dir * frac * trueMeters
                    val sigma = TUNNEL_LEARN_BASE_SIGMA_METERS + spreadFraction * min(frac, 1 - frac) * trueMeters
                    val p = r.pointAt(d)
                    radioMap.learn(scan, p.lat, p.lon, sigma)
                }
            }
        }
        clearRun()
        segmentStart = station
        segmentStartTrusted = trustedNow
        segmentRouteId = r.id
    }

    /**
     * Integrates the gyroscope's turn since the last station and, once a turn has settled,
     * matches it with the curves of the line (the turn from the station to each candidate
     * position): an absolute position cue in the middle of the tunnel, with no radio at all.
     */
    private fun updateCurve(r: MetroRoute, sample: MotionSample, dt: Double) {
        val yaw = sample.yawRateDps
        if (yaw.isNaN()) { curveRefValid = false; return }
        if (snappedAtStation) {
            // Standing at a known platform: this is the reference. Turning the phone here is harmless.
            curveRefValid = true; yawSinceRef = 0.0; lastAppliedYaw = 0.0
            curveRefDist = navDist; curveRefSec = 0.0; yawSettledSec = 0.0; yawSmoothed = 0.0
            return
        }
        if (!curveRefValid) return
        if (abs(yaw) > HANDLING_YAW_DPS || sample.state == MotionState.WALKING) {
            curveRefValid = false // the phone was turned in the hand: the reference is lost until the next station
            return
        }
        yawSinceRef += yaw * dt
        curveRefSec += dt
        yawSmoothed += (yaw - yawSmoothed) * min(1.0, dt / CURVE_YAW_SMOOTHING_SEC)
        yawSettledSec = if (abs(yawSmoothed) < CURVE_SETTLED_YAW_DPS) yawSettledSec + dt else 0.0
        if (!directionKnown || yawSettledSec < CURVE_SETTLE_SEC || abs(yawSinceRef - lastAppliedYaw) < CURVE_MIN_TURN_DEG) return
        lastAppliedYaw = yawSinceRef
        val measured = yawSinceRef
        val ref = curveRefDist
        val sigma = CURVE_BASE_SIGMA_DEG + CURVE_TURN_SIGMA_FRACTION * abs(measured) + CURVE_DRIFT_DPS * curveRefSec
        // Consistency check, not a pull: after a curve the evidence is one-sided ("past the
        // curve"), so an estimate that already agrees is left alone; one that contradicts the
        // measured turn moves to the nearest position that agrees with it.
        fun agrees(d: Double) = abs(r.turnBetween(ref, d) - measured) <= CURVE_AGREE_SIGMAS * sigma
        if (agrees(navDist)) return
        val window = WEAK_GATE_SIGMAS * max(uncertainty, MIN_TRACK_UNCERTAINTY_METERS)
        var best: Double? = null
        var d = navDist - window
        while (d <= navDist + window) {
            val c = d.coerceIn(0.0, r.length)
            val ahead = direction * (c - ref) >= 0
            if (ahead && agrees(c) && (best == null || abs(c - navDist) < abs(best - navDist))) best = c
            d += CURVE_SEARCH_STEP_METERS
        }
        val target = best ?: return
        // Trains do not reverse: a backward move only if well outside our uncertainty.
        if (direction * (target - navDist) < 0 && abs(target - navDist) <= WEAK_GATE_SIGMAS * uncertainty) return
        val jump = abs(target - navDist)
        navDist = target
        clearHold()
        curveCorrections++
        applyTrackPosition(r)
        beliefValid = false
        if (jump >= RADIO_REPORT_JUMP_METERS) events.add(String.format(Locale.US, "Curve matching moved the position %.0f m along %s", jump, r.lineName))
    }

    /**
     * Likelihood of [scan] at each distance along [r], or null if nothing known was heard.
     * Fingerprint matches form a mixture (the scan was taken near one of them); each RTT range
     * multiplies in as independent evidence.
     */
    private fun radioLikelihood(r: MetroRoute, scan: RadioScan): ((Double) -> Double)? {
        val parts = mutableListOf<(Double) -> Double>()

        val places = radioMap.match(scan).mapNotNull { m ->
            val p = r.project(m.fingerprint.lat, m.fingerprint.lon)
            if (p.offsetMeters > RADIO_MAX_TRACK_OFFSET_METERS) null
            else Triple(p.distanceAlongTrack, m.fingerprint.sigmaMeters + RadioMap.FINGERPRINT_SPREAD_METERS, m.weight)
        }
        if (places.isNotEmpty()) {
            // Absolute floor: a weak match only nudges, a strong one decides.
            parts += { d -> RADIO_LIKELIHOOD_FLOOR + places.sumOf { (at, s, w) -> w * gauss(d - at, s) } }
        }

        scan.ranges.forEach { range ->
            val a = radioMap.anchor(range.key) ?: return@forEach
            if (r.project(a.lat, a.lon).offsetMeters > RADIO_MAX_TRACK_OFFSET_METERS + range.distanceMeters) return@forEach
            val s = sqrt(range.stdDevMeters * range.stdDevMeters + a.sigmaMeters * a.sigmaMeters + 4.0)
            parts += { d ->
                val pt = r.pointAt(d)
                RADIO_LIKELIHOOD_FLOOR + gauss(GeoToolbox.calculateDistance(pt.lat, pt.lon, a.lat, a.lon) - range.distanceMeters, s)
            }
        }

        if (parts.isEmpty()) return null
        return { d -> parts.fold(1.0) { acc, f -> acc * f(d) } }
    }

    private fun gauss(x: Double, sigma: Double): Double = kotlin.math.exp(-0.5 * (x / sigma) * (x / sigma))

    private fun radioFixPoint(f: RadioFix) = GpsPoint(
        latitude = f.lat, longitude = f.lon, accuracy = f.accuracyMeters.toFloat(),
        timestamp = 0L, elapsedRealtimeMs = nowMs, isGnss = false
    )

    private fun startDepartureWatch() {
        watchingDeparture = true
        departureAccumE = 0.0; departureAccumN = 0.0; departureTime = 0.0; departureAbs = 0.0
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
            source == PositionSource.RADIO -> "No GPS · Wi-Fi / cell location"
            motion == MotionState.WALKING && pdrSteps > 0 -> "No GPS · step counting"
            else -> "No GPS · dead reckoning"
        } + (if (!gpsAvailable && hasPosition && networkCorrections > 0) " · network-assisted" else "") +
            (if (!gpsAvailable && hasPosition && radioCorrections > 0) " · radio-assisted" else "") +
            (if (!gpsAvailable && hasPosition && curveCorrections > 0) " · curve-matched" else "")

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
            networkCorrections = networkCorrections, cruiseSpeedMps = cruiseSpeed, mockLocation = mock,
            radioCorrections = radioCorrections, radioPlacesLearnt = radioMap.size, radioSignalsHeard = radioSignalsHeard,
            pdrSteps = pdrSteps, curveCorrections = curveCorrections, segmentsLearnt = rideMemory.size
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
        const val HOLD_SIGMAS = 2.5
        // Odometer (inertial distance since the last trusted station)
        const val MIN_RUN_FOR_ODOMETER_METERS = 200.0
        const val ODOMETER_MIN_TOLERANCE_METERS = 80.0
        const val ODOMETER_TOLERANCE_FRACTION = 0.1
        private val NO_STATION = MetroStation("\u0000none", 0.0, 0.0)
        const val HOLD_MARGIN_METERS = 5.0
        /** Held at a station this long with no braking (speed kept): the train is running through. */
        const val HOLD_PASS_CHECK_MS = 6_000L
        const val HOLD_PASS_MIN_SPEED_MPS = 8.0
        const val HOLD_PASS_SPEED_FRACTION = 0.85
        const val DESTINATION_MIN_DISTANCE_METERS = 30.0
        const val MIN_SEGMENT_SECONDS_FOR_LEARNING = 20.0
        /** Share of stops in a tunnel that are at a station (the rest: signals, congestion). */
        const val STATION_STOP_PRIOR = 0.85
        const val MIN_SEGMENT_FOR_SIGNAL_METERS = 200.0
        private val SQRT_2PI = sqrt(2 * Math.PI)

        // Departure detection
        const val DEPARTURE_WINDOW_SEC = 4.0
        const val DEPARTURE_MIN_ACCEL = 0.25

        // Uncertainty model (1-sigma growth)
        const val MIN_TRACK_UNCERTAINTY_METERS = 20.0
        const val STATION_SNAP_UNCERTAINTY_METERS = 40.0
        const val TRACK_SPEED_ERROR_FRACTION = 0.15
        /** Until the cruise speed is learnt it is only a typical-metro guess. */
        const val TRACK_SPEED_ERROR_FRACTION_UNLEARNT = 0.5
        const val CRUISE_EVIDENCE_NEEDED = 10
        const val TRACK_BASE_ERROR_MPS = 0.3
        const val FREE_SPEED_ERROR_FRACTION = 0.3
        const val FREE_BASE_ERROR_MPS = 1.0

        const val UNDERGROUND_AFTER_MS = 5_000L
        const val FREE_SPEED_DECAY_TAU_SEC = 60.0

        // Vehicle / pedestrian models without a line
        const val VEHICLE_MIN_MPS = 5.0
        const val WALKING_MAX_MPS = 2.5
        const val WALK_ENDS_RIDE_SEC = 20.0
        const val PDR_SPEED_SMOOTHING_SEC = 2.0

        // Stop / departure verification
        const val DV_HISTORY_MS = 25_000L
        const val UNCONFIRMED_MOVING_ACCEPT_SEC = 20.0
        /** Below this speed a stop needs no braking proof. */
        const val STOP_CHECK_MIN_MPS = 5.0
        /** A real stop shows a velocity change of at least this fraction of the speed. */
        const val BRAKE_MIN_FRACTION = 0.5
        const val STILL_VERIFY_GRACE_MS = 1_500L
        /** Still this long is a stop even without braking proof (sensor trouble). */
        const val LONG_STILL_ACCEPT_MS = 15_000L
        const val DEPARTURE_CONFIRM_WINDOW_MS = 6_000L
        const val DEPARTURE_CONFIRM_DV_MPS = 1.5
        /** |Σ a| / Σ |a| needed to call it a departure (hand jiggle is far lower). */
        const val DEPARTURE_MIN_CONSISTENCY = 0.5
        /** A known direction flips only if the departure is within 90 − this of the opposite way. */
        const val DIRECTION_FLIP_MARGIN_DEG = 45.0
        const val COMPASS_CAL_MIN_ACCEL = 0.4
        const val COMPASS_LEARN_RATE_GPS = 0.02
        const val COMPASS_LEARN_RATE_DEPARTURE = 0.3
        const val COMPASS_OFFSET_MIN_CONFIDENCE = 0.5
        /** After a real stop the integrated velocity is back within this (bias drift). */
        const val STOP_RESIDUAL_MPS = 3.0
        const val STOP_RESIDUAL_FRACTION = 0.35

        // Inertial speed (integrated acceleration since the last stop)
        const val BIAS_LEARN_AFTER_STILL_MS = 2_000L
        /** Long averaging (across stops): the bias changes slowly, the noise averages out. */
        const val BIAS_LEARN_TAU_SEC = 30.0
        const val STOP_RESIDUAL_GROWTH_MPS_PER_SEC = 0.04
        const val VEL_MEAS_BASE_MPS = 0.5
        /** Growth of the integrated-velocity error (residual bias / tilt), m/s per second. */
        const val VEL_MEAS_GROWTH_MPS_PER_SEC = 0.1
        /** Speed of an unknown run vs. the learnt cruise: runs differ a lot on real lines. */
        const val MODEL_SPEED_ERROR_FRACTION = 0.3
        const val VEL_FUSION_TAU_SEC = 1.0

        // Radio positioning
        const val RADIO_LEARN_GPS_ACCURACY_METERS = 20f
        const val RADIO_LEARN_MAX_FIX_AGE_MS = 3_000L
        const val RADIO_LEARN_STATION_SIGMA_METERS = 40.0
        /** A station snap made with more error than this may be the wrong station: do not learn from it. */
        const val RADIO_LEARN_MAX_SNAP_UNCERTAINTY_METERS = 300.0
        const val RADIO_CONFLICT_METERS = 300.0
        const val RADIO_CONFLICT_MIN_WEIGHT = 0.5
        const val RADIO_MAX_TRACK_OFFSET_METERS = 150.0
        const val RADIO_LIKELIHOOD_FLOOR = 0.02
        const val RADIO_MIN_MODE_MASS = 0.6
        const val RADIO_MIN_SIGMA_METERS = 25.0
        const val CURVE_MIN_SIGMA_METERS = 20.0
        /** Likelihood exponent for one radio scan (consecutive scans are correlated). */
        const val RADIO_EVIDENCE_EXPONENT = 0.35
        /** For a mobile-cell scan (every 5 s, versus 30 s for Wi-Fi). */
        const val CELL_EVIDENCE_EXPONENT = 0.25
        /** A correction must shrink the error to below this fraction of the current one. */
        const val MIN_SHARPENING = 0.7
        const val RADIO_REPORT_JUMP_METERS = 150.0
        const val RADIO_PASS_RADIUS_METERS = 80.0
        const val RADIO_STOP_CONFIRM_METERS = 250.0

        // Segment memory and tunnel radio learning
        /** Speed error once the run's own cruise speed is learnt (vs. 0.15 / 0.5). */
        const val SEGMENT_SPEED_ERROR_FRACTION = 0.12
        const val MAX_TUNNEL_SCANS = 400
        /** Model/true distance ratio outside this: the run went wrong, do not learn its tunnel. */
        const val TUNNEL_LEARN_MIN_RATIO = 0.6
        const val TUNNEL_LEARN_MAX_RATIO = 1.6
        const val TUNNEL_LEARN_BASE_SIGMA_METERS = 15.0
        const val TUNNEL_LEARN_SIGMA_FRACTION = 0.1
        /** With drift-corrected inertial placement the error mid-run is much smaller. */
        const val TUNNEL_LEARN_SIGMA_FRACTION_INERTIAL = 0.04
        const val MAX_RUN_SAMPLES = 6000

        // Curve matching
        /** Faster than any train curve: the phone is being turned in the hand. */
        const val HANDLING_YAW_DPS = 15.0
        /** How far behind the station just left the train may still be (platform length / snap error). */
        const val DEPARTED_STATION_MARGIN_METERS = 40.0
        const val CURVE_SETTLED_YAW_DPS = 0.5
        const val CURVE_YAW_SMOOTHING_SEC = 2.0
        const val CURVE_SETTLE_SEC = 3.0
        const val CURVE_MIN_TURN_DEG = 15.0
        const val CURVE_BASE_SIGMA_DEG = 4.0
        const val CURVE_TURN_SIGMA_FRACTION = 0.1
        /** Gyroscope drift allowance (degrees per second since the station). */
        const val CURVE_DRIFT_DPS = 0.05
        const val CURVE_AGREE_SIGMAS = 2.0
        const val CURVE_SEARCH_STEP_METERS = 10.0

        // Dead reckoning without a line
        /** Faster than a vehicle turns (sharp junction ~30 °/s): the phone is being handled. */
        const val VEHICLE_MAX_TURN_DPS = 45.0
        const val GYRO_BIAS_TAU_SEC = 20.0
        /** Error growth while vibration says moving but no movement is modelled (m/s). */
        const val UNMODELLED_MOTION_ERROR_MPS = 2.0

        // Stillness learnt from GPS (vibration of this phone in its mount)
        const val GPS_STILL_SPEED_MPS = 0.3
        const val GPS_STILL_CONFIRM_MS = 3_000L
        const val GPS_MOVING_SPEED_MPS = 4.0
        const val GPS_SPEED_MAX_AGE_MS = 2_000L
        /** Vibration below the learnt threshold this long means standing (like the fixed rule's 1 s). */
        const val QUIET_CONFIRM_MS = 1_000L

        // Weak satellite fixes
        const val WEAK_GNSS_SPEED_MAX_ACCURACY_METERS = 100f
        const val WEAK_GNSS_SPEED_WEIGHT = 0.3
    }
}
