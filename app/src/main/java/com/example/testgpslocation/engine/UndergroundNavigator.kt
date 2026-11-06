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
    val vibration: Double = Double.NaN,
    /** Steps the phone's step detector reported in this window. */
    val steps: Int = 0,
    /** Height change from the barometer (m); NaN without one. */
    val heightMeters: Double = Double.NaN
)

/** The ride so far, saved every few seconds so it can go on if the app is restarted. Not kept between rides. */
data class RideState(
    val routeId: String?,
    val latitude: Double,
    val longitude: Double,
    val distanceAlongLine: Double,
    val direction: Int,
    val directionKnown: Boolean,
    val directionUnconfirmed: Boolean,
    val headingDeg: Double,
    val speedMps: Double,
    val cruiseSpeedMps: Double,
    val uncertaintyMeters: Double,
    val lastStation: String?,
    val savedAtMs: Long,
    val inVehicle: Boolean = true,
    val headingKnown: Boolean = true
)

enum class PositionSource(val label: String) {
    NONE("NONE"),
    /** Satellite fix. */
    GPS("GPS"),
    /** Network location (Google fused: Wi-Fi and mobile cell towers), used when there is no satellite fix. */
    NETWORK("NET"),
    /** GPS provider fix with no satellite in use: the phone chip's own estimate, ranked with network. */
    ESTIMATE("GPS-EST"),
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
    val speedMps: Double = 0.0,
    val headingDeg: Double = 0.0,
    /** False until something has measured the heading; [headingDeg] means nothing before that. */
    val headingKnown: Boolean = false,
    /** Height from the last GPS fix; null without GPS. */
    val gpsAltitudeMeters: Double? = null,
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
    /** Position corrections from matching the gyroscope's turns with the line's curves. */
    val curveCorrections: Int = 0
)

/**
 * Position engine for GPS-denied travel (metro tunnels), also used while GPS is fine.
 *
 * Inputs: satellite fixes, network fixes (Google fused: Wi-Fi and cell towers), "GPS lost"
 * signals and IMU motion samples. Output: [state]. Nothing is learnt or kept between rides: every
 * ride starts from the line data and the sensors alone ([rideState] only bridges an app restart).
 *
 * Source order: a good satellite fix is the position. When there has been no good fix for
 * [GOOD_GPS_TIMEOUT_MS] (lost, or too poor), GPS counts as lost: poor satellite fixes and
 * network fixes then correct the dead-reckoned estimate, weighted by their accuracy, and the
 * estimate is carried between them by the sensors.
 *
 * Without GPS, on a saved metro line (1-D, track constrained):
 *  - the gap between the last fix and the loss detection is dead-reckoned at the last speed;
 *  - distance advances with a speed model: IMU acceleration projected on the track, pulled
 *    toward the cruise speed of this ride (from GPS and from station-to-station times), fused
 *    with the velocity integrated since the last stop, zero when the train stands (ZUPT);
 *  - the estimate is held at the next station until the train stops, then snapped to it:
 *    each stop resets the error;
 *  - network fixes (if any) correct the along-track distance through a 1-D Kalman update
 *    with outlier gating;
 *  - direction comes from the chosen destination, the GPS bearing, or the direction of
 *    acceleration when the train pulls out; at interchanges the same cue picks the line;
 *  - the gyroscope's turns are matched with the curves of the line.
 * Without a line: the same speed model along the heading, which the gyroscope turns with the
 * vehicle; zero at stops; corrected by network fixes.
 *
 * Stops: the phone's own still detection, or vibration falling well below the level of the
 * last half-minute of motion; either must be confirmed by braking (the velocity change) unless
 * the phone has no orientation sensor. Departures: a velocity change in one direction, a turn
 * (a standing vehicle does not turn), or moving vibration for [UNCONFIRMED_MOVING_ACCEPT_SEC].
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
    /** When the last good satellite fix was accepted. */
    private var lastGnssMs = 0L
    private var gpsLostAtMs = 0L
    private var lastAccepted: GpsPoint? = null
    private var rejectStreak = 0
    private var recoveryCandidate: GpsPoint? = null
    private var lastAccuracy: Float? = null
    private var lastAltitude: Double? = null
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

    // Track
    private var onTrack = false
    private var navDist = 0.0
    private var direction = 1
    private var directionKnown = false
    /** The direction rests on the compass alone (no GPS, destination or line end): curves and network fixes may overrule it. */
    private var directionUnconfirmed = false
    /** Where the direction was set: if it was wrong, the train went the other way from here. */
    private var directionSetAt = 0.0
    /** Just restarted: seconds of motion still needed before the braking history can prove a stop. */
    private var resumeHistoryMissingSec = 0.0
    /** Network fixes in a row that fit the opposite direction. */
    private var oppositeFixes = 0
    /** Leaving the end of the line: there is only one way to go, whatever the compass says. */
    private var directionCertain = false
    private var destination: MetroStation? = null
    private var heldAt: MetroStation? = null
    private var holdAllowance = 0.0
    private var pendingOvershoot = 0.0
    /** How far the held estimate has crept on (the train is still moving, so its position must too). */
    private var holdCreep = 0.0
    /** How far before the station the slow-down starts. */
    private var holdMargin = HOLD_MARGIN_METERS
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
    /** Standing samples the bias rests on (a plain mean until it is long enough for the slow average). */
    private var accBiasSamples = 0
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
    /**
     * Vibration while moving, averaged over the last half-minute of this ride (NaN until known),
     * and how many seconds of motion it rests on: a stop shows as a clear drop below it.
     */
    private var movingVibration = Double.NaN
    private var movingVibrationSec = 0.0
    /** Since when the vibration has been well below [movingVibration] (0 = not quiet). */
    private var quietSinceMs = 0L
    /** The current still reading comes from the vibration drop, not from the phone's own still detection. */
    private var stillFromVibrationDrop = false
    /** The phone reads still, by its own detection or by the vibration drop (also while GPS works). */
    private var phoneStill = false
    /** Accuracy of the fix the position was last taken from. */
    private var positionAccuracy = Float.MAX_VALUE
    /** Recent bias-corrected turn rates (°/s), to tell a turning vehicle from a standing one. */
    private val recentYaw = ArrayDeque<Double>()
    private var gnssAccelSign = 0
    private var gnssCourse = 0.0
    private var lastGnssMsForCal = 0L
    /** Speed when the phone last became still, for the braking check. */
    private var speedAtStill = 0.0
    private var stillAccepted = true
    /** Without GPS, the vehicle is known to stand (still, and braking confirmed): the coordinates stay. */
    private val standing: Boolean get() = !gpsAvailable && motion == MotionState.STATIONARY && stillAccepted
    /** False after an accepted stop until acceleration shows the vehicle really pulled away. */
    private var motionConfirmed = true
    private var unconfirmedMovingSec = 0.0
    private var drivingVibrationSec = 0.0

    // Barometer: in a tunnel a moving train keeps the pressure changing, a standing one does not
    private val heightHistory = ArrayDeque<DoubleArray>()
    private var pressureSteady = false
    private var pressureSteadySinceMs = 0L
    /** This sample counts as standing because of the pressure, whatever the shaking says (a phone in the hand). */
    private var stillFromPressure = false
    /** The train is standing by the pressure: when that changes, it has left. */
    private var stoppedByPressure = false
    /** How long the pressure has kept changing: a single step (doors) passes, a departure goes on. */
    private var pressureMovingSec = 0.0

    private var lastWeakLat = Double.NaN
    private var lastWeakLon = Double.NaN
    /** Vibration at this ride's stops: the reference when it has not driven long enough to know its driving level. */
    private var standingVibration = Double.NaN

    // On foot: the step detector moves the estimate, one stride per step, learnt from GPS in this walk
    private var strideMeters = DEFAULT_STRIDE_METERS
    private var strideLearnMeters = 0.0
    private var strideLearnSteps = 0
    private var lastStepMs = 0L
    private var stepGapMs = 0L
    /** Steps in a row, without a pause. */
    private var stepRun = 0
    private var onFoot = false
    private val walkingNow: Boolean get() = !inVehicle && lastStepMs != 0L && nowMs - lastStepMs <= STEP_PAUSE_MS

    // Odometer: the run since the last trusted station stop
    private var segmentStart: MetroStation? = null
    private var segmentStartTrusted = false
    private var segmentRouteId: String? = null
    /** Inertial velocity history of the current run, for the drift-corrected odometer (ZUPT smoothing). */
    private val runTimes = ArrayList<Double>()
    private val runVelE = ArrayList<Double>()
    private val runVelN = ArrayList<Double>()
    /** Pieces of the run between zero-velocity updates: (first index, last index, residual E, residual N). */
    private val runPieces = ArrayList<DoubleArray>()
    private var runPieceStart = 0
    private var runSeconds = 0.0
    private var runInertialOk = false

    // Network fixes that disagree with a free (no line) estimate
    private var weakRejects = 0
    private var lastRejectedWeak: GpsPoint? = null

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
    /** How long the turn rate has been too fast for a train (a single reading can be a jolt over points). */
    private var handlingSec = 0.0

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
        resetSegment()
        val r = newRoute ?: return
        if (hasPosition) {
            val p = r.project(lat, lon)
            val radius = if (gpsAvailable) LOCK_RADIUS_METERS + (lastAccuracy ?: 0f) else LOST_LOCK_RADIUS_METERS
            if (p.offsetMeters <= radius && !walkingNow) {
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
                setDirection(newDir)
                flushUndirected(r)
            }
        }
    }

    fun flipDirection() {
        setDirection(-direction)
        destination = null
        clearHold()
        route?.let { flushUndirected(it) }
    }

    /** The ride to save for [resume]; null while GPS works (nothing to recover) or without a position. */
    fun rideState(wallClockMs: Long): RideState? = if (gpsAvailable || !hasPosition) null else RideState(
        route?.takeIf { onTrack }?.id, lat, lon, navDist, direction, directionKnown, directionUnconfirmed,
        heading, speed, cruiseSpeed, uncertainty, lastStopStation?.name, wallClockMs, inVehicle, headingKnown
    )

    /**
     * Goes on from [s] after the app was restarted [gapSec] ago, on its line [r] if that is on the
     * phone. The train may have moved or stopped meanwhile, so the position is carried on at the
     * saved speed, the error grows, and the next stop puts it right. Only GPS beats the saved
     * ride: a network fix that came first is far coarser and knows no line or direction.
     */
    fun resume(s: RideState, r: MetroRoute?, gapSec: Double, elapsedMs: Long) {
        if (gpsAvailable) return
        nowMs = elapsedMs
        lat = s.latitude; lon = s.longitude
        hasPosition = true
        gpsLostAtMs = elapsedMs
        heading = s.headingDeg; headingKnown = s.headingKnown
        speed = s.speedMps
        cruiseSpeed = s.cruiseSpeedMps; cruiseEvidence = CRUISE_EVIDENCE_NEEDED
        inVehicle = s.inVehicle
        motionConfirmed = speed >= STANDING_AT_LOSS_MPS // standing: only a real pull-away moves it
        resumeHistoryMissingSec = DV_HISTORY_MS / 1000.0
        uncertainty = s.uncertaintyMeters + gapSec * max(speed, cruiseSpeed) * RESUME_GAP_ERROR_FRACTION
        val carried = speed * min(gapSec, RESUME_MAX_CARRY_SEC)
        if (r != null) {
            route = r
            onTrack = true
            navDist = s.distanceAlongLine
            direction = s.direction; directionKnown = s.directionKnown; directionUnconfirmed = s.directionUnconfirmed
            directionSetAt = navDist
            lastStopStation = r.stations.firstOrNull { it.name == s.lastStation }
            applyTrackPosition(r)
            if (motionConfirmed) advanceAlongTrack(r, carried)
        } else {
            if (motionConfirmed) GeoToolbox.moveLocation(lat, lon, carried, heading).let { (a, b) -> lat = a; lon = b }
            source = PositionSource.DEAD_RECKONING
        }
        events.add(String.format(Locale.US, "Ride resumed after a restart (%.0f s gap, ±%.0f m)", gapSec, uncertainty))
    }

    // ---- Location input ----------------------------------------------------------------------

    /** Satellite or network fix; [elapsedMs] is the caller's monotonic clock. Says what became of it, for the log. */
    fun onLocationFix(fix: GpsPoint, elapsedMs: Long): String {
        nowMs = elapsedMs
        if (fix.accuracy <= 0f || fix.accuracy > MAX_USABLE_ACCURACY_METERS) return "ignored: accuracy too poor"
        if (fix.latitude.isNaN() || fix.longitude.isNaN()) return "ignored: bad coordinates"
        if (fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0) return "ignored: bad coordinates"
        if (fix.latitude == 0.0 && fix.longitude == 0.0) return "ignored: bad coordinates"
        if (fix.isGnss && fix.accuracy <= GNSS_GOOD_ACCURACY_METERS) {
            handleGnss(fix)
            return "used"
        }
        checkGpsTooPoor()
        return handleWeak(fix)
    }

    /**
     * Satellite fixes keep arriving but none good enough (urban canyon, under a roof, tunnel
     * mouth): the receiver's watchdog never reports "lost", so the engine does, or the
     * position would freeze at the last good fix and the sensors would never take over.
     */
    private fun checkGpsTooPoor() {
        if (!gpsAvailable || lastGnssMs == 0L || nowMs - lastGnssMs <= GOOD_GPS_TIMEOUT_MS) return
        events.add(String.format(Locale.US, "No good GPS fix for %.0f s", (nowMs - lastGnssMs) / 1000.0))
        onGpsLost(nowMs)
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
        onFoot = false
        snappedAtStation = false
        resetSegment()
        lastGnssMs = nowMs
        weakRejects = 0
        motionConfirmed = true
        dvHistory.clear()
        velKnown = false
        gpsLostAtMs = 0
        distanceSinceLoss = 0.0
        undirectedDistance = 0.0
        clearHold()
        lastAccepted = fix
        lastAccuracy = fix.accuracy
        lastAltitude = fix.altitudeMeters
        mock = fix.isMock

        val fixSpeed = if (fix.hasSpeed) fix.speedMeterPerSecond.toDouble() else speed
        speed = fixSpeed.coerceIn(0.0, MAX_SPEED_MPS)
        // A vehicle that stops (traffic light, station) is still a vehicle: only a long walk at
        // walking pace ends the ride (see onMotion).
        if (fixSpeed >= VEHICLE_MIN_MPS) inVehicle = true
        if (fixSpeed > CRUISE_LEARN_MIN_MPS) {
            cruiseSpeed = (cruiseSpeed * 0.9 + fixSpeed * 0.1).coerceIn(MIN_CRUISE_MPS, MAX_CRUISE_MPS)
            cruiseEvidence++
        }

        // Every accepted fix is the position, unless the vehicle really stands: then GPS noise
        // (a few metres) must not move it. That takes both the phone reading still and GPS
        // measuring no speed; slow is not enough, or a slow vehicle's position would freeze.
        if (!standingWithGps(fix)) {
            lat = fix.latitude; lon = fix.longitude
            positionAccuracy = fix.accuracy
        }
        hasPosition = true
        source = PositionSource.GPS
        uncertainty = fix.accuracy.toDouble()
        val minCourseSpeed = if (inVehicle) 1.5 else WALK_COURSE_MIN_MPS // on foot the course is needed at walking pace
        if (fix.hasBearing && fixSpeed > minCourseSpeed) { heading = fix.bearingDegree.toDouble(); headingKnown = true }
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
        lastGnssMsForCal = nowMs

        val r = route ?: return
        val p = r.project(fix.latitude, fix.longitude)
        onTrack = p.offsetMeters <= LOCK_RADIUS_METERS + fix.accuracy
        if (!onTrack) return
        val prevProj = lastTrackProjection
        navDist = p.distanceAlongTrack
        if (destination == null) {
            if (fix.hasBearing && fixSpeed > 2.0) inferDirectionFromBearing(fix.bearingDegree.toDouble())
            else if (prevProj != null && abs(navDist - prevProj) > 20.0 && fixSpeed > DIRECTION_FROM_MOTION_MIN_MPS) setDirection(if (navDist > prevProj) 1 else -1)
        }
        if (prevProj == null || abs(navDist - prevProj) > 20.0) lastTrackProjection = navDist
    }

    /** Hold the coordinates? Not if the fix is clearly better, or too far away to be noise. */
    private fun standingWithGps(fix: GpsPoint): Boolean =
        hasPosition && source == PositionSource.GPS && phoneStill &&
            fix.hasSpeed && fix.speedMeterPerSecond < GPS_STANDING_MPS &&
            fix.accuracy > GPS_BETTER_FIX_FRACTION * positionAccuracy &&
            GeoToolbox.calculateDistance(lat, lon, fix.latitude, fix.longitude) <= max(GPS_NOISE_METERS, 2.0 * fix.accuracy)

    /**
     * Network fix (Google fused: Wi-Fi / cell towers), a GPS fix with no satellites, or a satellite
     * fix too poor / not yet confirmed to trust fully. Second in line after a good satellite fix.
     * Returns what became of it.
     */
    private fun handleWeak(fix: GpsPoint): String {
        // The same coordinates again, usually with a growing accuracy, are an old fix: nothing new.
        val repeated = !fix.isGnss && abs(fix.latitude - lastWeakLat) < SAME_FIX_DEGREES && abs(fix.longitude - lastWeakLon) < SAME_FIX_DEGREES
        if (!fix.isGnss) { lastWeakLat = fix.latitude; lastWeakLon = fix.longitude }
        if (gpsAvailable) return "ignored: GPS is working" // a real satellite fix is better
        if (repeated && hasPosition) return "ignored: repeat of the last fix"
        if (fix.isMock) mock = true
        val weakSource = if (fix.isChipEstimate) PositionSource.ESTIMATE else PositionSource.NETWORK
        // Round accuracies are mobile-cell positions. On the real ride they claimed ±100 m and were 1.2 km off.
        val coarse = !fix.isGnss && !fix.isChipEstimate && fix.accuracy >= 100f && abs(fix.accuracy - 100f * kotlin.math.round(fix.accuracy / 100f)) < 0.5f
        val accuracy = if (coarse) max(fix.accuracy, COARSE_FIX_ACCURACY_METERS) else fix.accuracy
        val r = max(accuracy.toDouble(), 10.0) * WEAK_ACCURACY_INFLATION
        if (fix.isGnss && fix.hasSpeed && fix.accuracy <= WEAK_GNSS_SPEED_MAX_ACCURACY_METERS && hasPosition) {
            // A poor satellite fix still measures speed and course well (Doppler): use them gently.
            val trueSpeed = fix.speedMeterPerSecond.toDouble().coerceIn(0.0, MAX_SPEED_MPS)
            if (motionConfirmed) speed += WEAK_GNSS_SPEED_WEIGHT * (trueSpeed - speed)
            if (fix.hasBearing && trueSpeed >= WEAK_GNSS_COURSE_MIN_MPS && !(route != null && onTrack)) {
                heading = fix.bearingDegree.toDouble(); headingKnown = true
            }
            if (trueSpeed >= VEHICLE_MIN_MPS) inVehicle = true
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
            return "used: first position"
        }

        val rt = route
        if (rt != null) {
            val p = rt.project(fix.latitude, fix.longitude)
            if (!onTrack) {
                if (p.offsetMeters <= min(fix.accuracy + 50.0, LOST_LOCK_RADIUS_METERS) && !walkingNow) {
                    onTrack = true
                    navDist = p.distanceAlongTrack
                    uncertainty = r
                    applyTrackPosition(rt)
                    events.add("Locked to ${rt.lineName} using network location")
                    return "used: locked to the line"
                }
            } else {
                // The chip's guess carries on from the last GPS fix: it knows nothing the line does not.
                if (fix.isChipEstimate) return "ignored: on the line"
                // Standing at a known platform is better than any network fix; standing anywhere,
                // the coordinates stay the same until the train moves (the fix is used then).
                if (p.offsetMeters > accuracy + WEAK_OFF_TRACK_TOLERANCE_METERS) return "ignored: off the line"
                val innovation = p.distanceAlongTrack - navDist
                val stopped = snappedAtStation || standing
                val far = abs(innovation) > WEAK_GATE_SIGMAS * sqrt(uncertainty * uncertainty + r * r)
                if (stopped && !far) return "ignored: stopped"
                if (!stopped && directionUnconfirmed) {
                    // Clearly closer to where the train would be going the other way?
                    val fitsOpposite = abs(p.distanceAlongTrack - (2 * directionSetAt - navDist)) + fix.accuracy < abs(innovation)
                    oppositeFixes = if (fitsOpposite) oppositeFixes + 1 else 0
                    if (oppositeFixes >= WEAK_REJECT_RESET_COUNT) { correctDirection(rt, "network location"); return "used: corrected the direction" }
                }
                if (far) {
                    // One such fix is an outlier; several good ones that agree mean the estimate is wrong.
                    if (coarse || !agreesWithLastRejected(fix)) return "ignored: too far from the estimate"
                    events.add(String.format(Locale.US, "Network location disagreed %d times in a row: estimate was off by %.0f m along the line, moved to it", weakRejects, abs(innovation)))
                    weakRejects = 0
                    lastRejectedWeak = null
                    navDist = p.distanceAlongTrack
                    uncertainty = r
                    clearHold()
                    resetSegment()
                    networkCorrections++
                    applyTrackPosition(rt)
                    return "used: moved to it"
                }
                val k = (uncertainty * uncertainty) / (uncertainty * uncertainty + r * r)
                navDist = (navDist + k * innovation).coerceIn(0.0, rt.length)
                uncertainty = sqrt(uncertainty * uncertainty * r * r / (uncertainty * uncertainty + r * r))
                heldAt?.let { if (direction * (navDist - it.distanceAlongTrack) > 0) clearHold() }
                networkCorrections++
                applyTrackPosition(rt)
                return "used"
            }
        }

        // Free-space blend (no line): trust network more as our own estimate degrades.
        val d = GeoToolbox.calculateDistance(lat, lon, fix.latitude, fix.longitude)
        if (source != weakSource && d > WEAK_GATE_SIGMAS * sqrt(uncertainty * uncertainty + r * r)) {
            // Far from our estimate. One such fix is an outlier; several that agree with each
            // other mean our estimate has drifted (wrong heading, a missed stop or start), and
            // without this it would reject the network for ever while standing (its error does
            // not grow at a stop).
            if (!agreesWithLastRejected(fix)) return "ignored: too far from the estimate"
            events.add(String.format(Locale.US, "Network location disagreed %d times in a row: estimate was off by %.0f m, moved to it", weakRejects, d))
            // (Even while standing: a clear, repeated disagreement means the estimate is wrong.)
            weakRejects = 0
            lastRejectedWeak = null
            lat = fix.latitude; lon = fix.longitude
            uncertainty = r
            source = weakSource
            networkCorrections++
            return "used: moved to it"
        }
        weakRejects = 0
        lastRejectedWeak = null
        if (standing) return "ignored: stopped" // the same coordinates until it moves, not network noise
        val k = (uncertainty * uncertainty) / (uncertainty * uncertainty + r * r)
        lat += k * (fix.latitude - lat)
        lon += k * (fix.longitude - lon)
        uncertainty = sqrt(uncertainty * uncertainty * r * r / (uncertainty * uncertainty + r * r))
        networkCorrections++
        if (k >= 0.5 || fix.isChipEstimate) source = weakSource
        return "used"
    }

    /** Counts [fix] among the rejected ones; true once enough of them in a row agree with each other. */
    private fun agreesWithLastRejected(fix: GpsPoint): Boolean {
        val prev = lastRejectedWeak
        val agrees = prev != null && GeoToolbox.calculateDistance(prev.latitude, prev.longitude, fix.latitude, fix.longitude) <=
            prev.accuracy + fix.accuracy + max(speed, 5.0) * max(0.0, (fix.elapsedRealtimeMs - prev.elapsedRealtimeMs) / 1000.0)
        weakRejects = if (agrees) weakRejects + 1 else 1
        lastRejectedWeak = fix
        return weakRejects >= WEAK_REJECT_RESET_COUNT
    }

    /** No satellite fix for a while (the caller's watchdog decides how long, or [checkGpsTooPoor]). */
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
        if (speed < STANDING_AT_LOSS_MPS && accelMeasured) {
            // Lost while standing (traffic light, platform): the velocity is known to be zero, and
            // only a real pull-away may move the estimate again — not engine or air-conditioning
            // vibration that the phone reads as "moving".
            motionConfirmed = false
            unconfirmedMovingSec = 0.0
            drivingVibrationSec = 0.0
            dvHistory.clear()
            velE = 0.0; velN = 0.0; velPeak = 0.0; velKnown = true; velSeconds = 0.0
            speed = 0.0
            startDepartureWatch()
        }

        // The watchdog notices the loss some seconds after the last fix: carry the position
        // forward over that gap at the last known speed.
        val gapSec = if (lastGnssMs > 0) ((elapsedMs - lastGnssMs) / 1000.0).coerceIn(0.0, MAX_GAP_SEC) else 0.0
        val r = route
        if (walkingNow) onTrack = false // someone on foot beside the line, not a train on it
        if (r != null && !onTrack && !walkingNow) {
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
        nowMs = input.elapsedMs
        val dt = input.dtSec.coerceIn(0.0, 1.0)
        gyroAvailable = !input.yawRateDps.isNaN()
        if (gyroAvailable && input.state == MotionState.STATIONARY && input.stationaryForMs >= BIAS_LEARN_AFTER_STILL_MS &&
            abs(input.yawRateDps - yawBiasDps) <= GYRO_BIAS_MAX_DEVIATION_DPS) {
            // Standing still (with or without GPS): a small turn rate is gyroscope bias. A larger
            // one is a vehicle turning slowly that the still detector got wrong: never learnt.
            yawBiasDps += min(1.0, dt / GYRO_BIAS_TAU_SEC) * (input.yawRateDps - yawBiasDps)
        }
        val turning = vehicleTurning(input)
        checkGpsTooPoor()
        val raw = stateWithPressure(motionStateOf(input, turning, dt))
        // Remove the accelerometer bias measured while the train stood still.
        val sample = if (raw.orientationKnown) raw.copy(accelEast = raw.accelEast - accBiasE, accelNorth = raw.accelNorth - accBiasN) else raw
        motion = sample.state
        accelMeasured = sample.orientationKnown
        if (sample.state == MotionState.WALKING) walkingSeconds += dt else walkingSeconds = 0.0
        if (gpsAvailable && gnssAccelSign != 0 && sample.orientationKnown) {
            val mag = sqrt(sample.accelEast * sample.accelEast + sample.accelNorth * sample.accelNorth)
            if (mag >= COMPASS_CAL_MIN_ACCEL) {
                val measured = Math.toDegrees(atan2(sample.accelEast, sample.accelNorth))
                val travel = if (gnssAccelSign > 0) gnssCourse else gnssCourse + 180.0
                learnCompassOffset(measured, travel, COMPASS_LEARN_RATE_GPS)
            }
        }
        if (sample.steps > 0) noteSteps(sample)
        if (gpsAvailable) {
            // A long walk at walking pace ends the ride; a vehicle that merely stops is still one.
            if (walkingSeconds >= WALK_ENDS_RIDE_SEC && speed < WALKING_MAX_MPS) inVehicle = false
            if (sample.steps > 0) learnStride(sample)
            return
        }
        if (!hasPosition) return
        if (onTrack && walkingNow && stepRun >= STEPS_TO_LEAVE_LINE) { // walking beside the line, not riding it
            onTrack = false
            clearHold()
            events.add("On foot: left ${route?.lineName ?: "the line"}")
        }
        if (!inVehicle && route?.takeIf { onTrack } == null &&
            (sample.steps > 0 || (onFoot && sample.elapsedMs - lastStepMs <= STEP_PAUSE_MS))) {
            followSteps(sample, dt)
            return
        }
        if (onFoot) { onFoot = false; speed = 0.0 } // stopped walking

        var state = sample.state
        if (state != MotionState.STATIONARY) resumeHistoryMissingSec = max(0.0, resumeHistoryMissingSec - dt)
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
                // No departure confirmed since the last stop: the vehicle is still standing there (the
                // motion in between was the box being knocked), so there is nothing to verify.
                val braked = !motionConfirmed || resumeHistoryMissingSec > 0.0 || if (velKnown) {
                    // Integrated velocity since the last stop: back near zero only if we really stopped.
                    // Allowance grows with the run time: residual bias integrates into velocity. After
                    // a long run (or a missed stop) that drift can exceed it: the recent braking, which
                    // drift cannot touch, decides then — or one missed stop would hide every later one.
                    velPeak < STOP_CHECK_MIN_MPS || sqrt(velE * velE + velN * velN) <=
                        maxOf(STOP_RESIDUAL_MPS, STOP_RESIDUAL_FRACTION * velPeak, STOP_RESIDUAL_GROWTH_MPS_PER_SEC * velSeconds) ||
                        brakedToHaltRecently()
                } else {
                    speedAtStill < STOP_CHECK_MIN_MPS || brakingEvidence() >= BRAKE_MIN_FRACTION * speedAtStill
                }
                // The phone's own still detection is accepted after a long time even without braking
                // proof (sensor trouble); a vibration drop never is: a quiet stretch of smooth running
                // looks the same.
                val longStill = !stillFromVibrationDrop && sample.stationaryForMs >= LONG_STILL_ACCEPT_MS
                if (braked || longStill || stillFromPressure) {
                    stillAccepted = true
                    speedAtStill = 0.0
                    dvHistory.clear()
                    closeRunPiece() // whatever velocity is left now is drift of this piece
                    velE = 0.0; velN = 0.0; velPeak = 0.0; velKnown = true; velSeconds = 0.0
                } else {
                    state = MotionState.MOVING // keep gliding at the current speed
                    integrateVelocity(sample, dt) // a glide is motion: keep the odometer running
                }
            }
        }

        when (state) {
            MotionState.STATIONARY -> {
                speed = 0.0
                speedAtStill = 0.0
                if (stillFromPressure) stoppedByPressure = true
                if (!sample.vibration.isNaN()) {
                    standingVibration = if (standingVibration.isNaN()) sample.vibration
                        else standingVibration + (sample.vibration - standingVibration) * min(1.0, dt / STANDING_VIBRATION_TAU_SEC)
                }
                if (raw.orientationKnown && stillAccepted && sample.stationaryForMs >= BIAS_LEARN_AFTER_STILL_MS) {
                    // Standing still (braking confirmed): whatever horizontal acceleration is measured
                    // on average is sensor bias — engine or air-conditioning vibration averages out.
                    // A plain mean at first, shrunk toward zero while it rests on few samples (a few
                    // noisy seconds at the first platform must not set it); later a slow average.
                    accBiasSamples++
                    val k = max(1.0 / (accBiasSamples + BIAS_PRIOR_SAMPLES), min(1.0, dt / BIAS_LEARN_TAU_SEC))
                    accBiasE += k * (raw.accelEast - accBiasE)
                    accBiasN += k * (raw.accelNorth - accBiasN)
                }
                // After a stop only a real departure moves the estimate again. On a line without an
                // orientation sensor there is nothing to measure it with, so the line's model resumes.
                if (sample.orientationKnown || route?.takeIf { onTrack } == null) { motionConfirmed = false; unconfirmedMovingSec = 0.0; drivingVibrationSec = 0.0 }
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
                if (route != null && onTrack) {
                    // Someone walking on the platform or inside the carriage, or the box being
                    // carried: footsteps hide the train's own acceleration, so it keeps the speed it
                    // had (0 at a platform, its running speed in a moving train).
                } else if (inVehicle && (motionConfirmed || confirmDeparture(dt, turning, onLine = false, sample.vibration))) {
                    // Strong vibration in a vehicle (rough road, rattling mount) is not walking:
                    // the vehicle model goes on. A pull-away still needs its velocity change.
                    vehicleSpeedModel(sample, dt)
                } else if (!inVehicle && headingKnown && walkingSeconds >= WALK_WITHOUT_STEPS_SEC) {
                    // Shaking like walking, but no steps reported (no step sensor, or no permission).
                    onFoot = true
                    speed = strideMeters * WALK_CADENCE_HZ
                } else {
                    // On foot, or a vehicle not yet seen pulling away: no motion is invented.
                    speed = 0.0
                }
            }
            MotionState.MOVING -> {
                stopHandled = false
                if (sample.state == MotionState.MOVING) snappedAtStation = false
                movingSecondsSinceStop += dt
                if (watchingDeparture) detectDeparture(sample, dt)
                val r = route?.takeIf { onTrack }
                if (!motionConfirmed && (r != null || inVehicle)) {
                    // After a stop: jiggling the phone or engine vibration is not a departure. On a
                    // line, long moving vibration resumes the model (the stop was probably a glide
                    // mis-read as still, and the stations bound the error); on a road only vibration
                    // at this ride's driving level does, or it would run away at every long red light.
                    if (!confirmDeparture(dt, turning, onLine = r != null, sample.vibration)) speed = 0.0
                } else if (r != null) {
                    // On a metro line: IMU acceleration along the track + pull toward the cruise
                    // speed of this ride, fused with the inertial speed.
                    val along = if (directionKnown) {
                        val b = Math.toRadians(r.bearingAt(navDist, direction) + compassOffsetDeg)
                        sample.accelEast * sin(b) + sample.accelNorth * cos(b)
                    } else 0.0
                    speed += along.coerceIn(-MAX_ACCEL_MPS2, MAX_ACCEL_MPS2) * dt
                    val tau = if (directionKnown) CRUISE_PULL_TAU_SEC else CRUISE_PULL_TAU_UNCERTAIN_SEC
                    speed += (cruiseSpeed - speed) * (dt / tau)
                    fuseIntegratedVelocity(dt)
                } else if (inVehicle) {
                    vehicleSpeedModel(sample, dt)
                } else {
                    // Not known to be in a vehicle (building, escalator): keep the last real speed
                    // and let it fade; never invent motion. With steps seen, no step means stopped.
                    speed = if (lastStepMs != 0L) 0.0 else speed * kotlin.math.exp(-dt / FREE_SPEED_DECAY_TAU_SEC)
                }
            }
        }
        speed = speed.coerceIn(0.0, MAX_SPEED_MPS)

        val onLine = route?.takeIf { onTrack }
        if (onLine != null) updateCurve(onLine, sample, dt) else followTurnWithGyro(sample, dt)
        val step = speed * dt
        if (step <= 0.0) {
            // Vibration says moving but no movement is modelled (no departure seen yet): the
            // position may be going stale, so the error must grow instead of staying small.
            if (onLine == null) uncertainty += dt * UNMODELLED_MOTION_ERROR_MPS
            return
        }
        distanceSinceLoss += step
        if (onLine != null) {
            // Position error grows with the speed error: from the inertial fusion when it ran,
            // else from how well the speed of this run is known (runs differ a lot on real lines).
            val speedSigma = if (!fusedSpeedSigma.isNaN()) fusedSpeedSigma else max(speedErrorFraction, MODEL_SPEED_ERROR_FRACTION) * speedForError
            uncertainty += dt * (speedSigma + TRACK_BASE_ERROR_MPS)
            advanceAlongTrack(onLine, step)
        } else {
            uncertainty += dt * (FREE_SPEED_ERROR_FRACTION * speed + FREE_BASE_ERROR_MPS)
            val (nLat, nLon) = GeoToolbox.moveLocation(lat, lon, step, heading)
            lat = nLat; lon = nLon
            source = PositionSource.DEAD_RECKONING
        }
    }

    private fun noteSteps(sample: MotionSample) {
        stepGapMs = sample.elapsedMs - lastStepMs
        lastStepMs = sample.elapsedMs
        stepRun = if (stepGapMs <= STEP_PAUSE_MS) stepRun + sample.steps else sample.steps
    }

    /** On foot with GPS: this walker's stride, from the GPS speed between steps. */
    private fun learnStride(sample: MotionSample) {
        if (inVehicle || stepGapMs > STEP_PAUSE_MS || speed < STRIDE_LEARN_MIN_MPS) return
        strideLearnMeters += speed * stepGapMs / 1000.0
        strideLearnSteps += sample.steps
        if (strideLearnSteps >= STRIDE_LEARN_STEPS) {
            strideMeters = (strideLearnMeters / strideLearnSteps).coerceIn(MIN_STRIDE_METERS, MAX_STRIDE_METERS)
        }
    }

    /** On foot without GPS, off a line: every step moves the estimate one stride along the heading. */
    private fun followSteps(sample: MotionSample, dt: Double) {
        stillAccepted = false
        if (watchingDeparture) detectDeparture(sample, dt) // vibration can look like steps: a vehicle still shows its push
        followTurnWithGyro(sample, dt, WALK_MAX_TURN_DPS)
        if (sample.steps == 0) return
        val walked = sample.steps * strideMeters
        speed = (walked * 1000.0 / stepGapMs.coerceIn(STEP_MIN_GAP_MS, STEP_PAUSE_MS)).coerceAtMost(WALKING_MAX_MPS)
        if (!onFoot) {
            onFoot = true
            events.add(String.format(Locale.US, "On foot: following steps of %.2f m", strideMeters))
        }
        if (!headingKnown) { // nothing says which way: the position goes stale instead
            uncertainty += walked
            return
        }
        val (nLat, nLon) = GeoToolbox.moveLocation(lat, lon, walked, heading)
        lat = nLat; lon = nLon
        distanceSinceLoss += walked
        uncertainty += walked * STEP_ERROR_FRACTION
        source = PositionSource.DEAD_RECKONING
    }

    /**
     * After a stop: has the vehicle really pulled away? Yes when the acceleration of the last few
     * seconds adds up to a real speed in one direction. Off a line also when it turns (a standing
     * vehicle does not; a train always shows its push). On a line also after moving vibration for
     * [UNCONFIRMED_MOVING_ACCEPT_SEC] without a push (a stop that was misread). On a road, after
     * [VIBRATION_RESTART_SEC] of shaking at the level of this ride's driving: a pull-away too
     * gentle to measure, which an idling engine (far quieter) or a short knock is not. Sets the speed.
     */
    private fun confirmDeparture(dt: Double, turning: Boolean, onLine: Boolean, vibration: Double): Boolean {
        unconfirmedMovingSec += dt
        val drivingLevel = if (movingVibrationSec >= MOVING_VIBRATION_MIN_SEC) VIBRATION_RESTART_FRACTION * movingVibration
            else max(VIBRATION_RESTART_MIN, VIBRATION_RESTART_STANDING_RATIO * standingVibration)
        if (!vibration.isNaN() && !drivingLevel.isNaN()) {
            drivingVibrationSec = if (vibration >= drivingLevel) drivingVibrationSec + dt else max(0.0, drivingVibrationSec - dt)
        }
        val dv = recentDv(DEPARTURE_CONFIRM_WINDOW_MS)
        when {
            dv >= DEPARTURE_CONFIRM_DV_MPS && recentConsistency(DEPARTURE_CONFIRM_WINDOW_MS) >= DEPARTURE_MIN_CONSISTENCY &&
                pushedFor(DEPARTURE_CONFIRM_WINDOW_MS) >= DEPARTURE_MIN_PUSH_MS -> speed = dv
            turning && !onLine -> {
                speed = max(dv, TURN_DEPARTURE_SPEED_MPS)
                events.add("Departure detected from a turn")
            }
            onLine && unconfirmedMovingSec >= UNCONFIRMED_MOVING_ACCEPT_SEC -> {
                // Moving-type vibration for this long without a departure signal: the stop was
                // probably a smooth glide mis-read as still. Resume the model.
                speed = cruiseSpeed * 0.5
                events.add("Movement resumed without a clear departure (stop was probably misdetected)")
            }
            onLine && stoppedByPressure && pressureMovingSec >= PRESSURE_DEPARTED_SEC -> {
                speed = cruiseSpeed * 0.5
                velKnown = false // no push was measured: the integrated speed says nothing here
                events.add("Departure seen from the pressure")
            }
            !onLine && drivingVibrationSec >= VIBRATION_RESTART_SEC -> {
                speed = max(dv, cruiseSpeed * 0.5)
                events.add("Movement resumed: vibration back at driving level")
            }
            else -> return false
        }
        motionConfirmed = true
        stoppedByPressure = false
        inVehicle = true
        return true
    }

    /** Riding with no saved line: IMU acceleration along the heading + pull toward this ride's cruise speed, fused with the inertial speed. */
    private fun vehicleSpeedModel(sample: MotionSample, dt: Double) {
        val along = if (headingKnown) {
            val b = Math.toRadians(heading + compassOffsetDeg)
            sample.accelEast * sin(b) + sample.accelNorth * cos(b)
        } else 0.0
        speed += along.coerceIn(-MAX_ACCEL_MPS2, MAX_ACCEL_MPS2) * dt
        speed += (cruiseSpeed - speed) * (dt / CRUISE_PULL_TAU_SEC)
        fuseIntegratedVelocity(dt)
    }

    /**
     * True while the vehicle is turning: a steady turn one way over the last
     * [TURN_WINDOW_SAMPLES] samples, bias removed. Gyroscope noise averages out, and a hand or a
     * knock turning the phone back and forth is neither steady nor one way.
     */
    private fun vehicleTurning(s: MotionSample): Boolean {
        if (s.yawRateDps.isNaN()) { recentYaw.clear(); return false }
        recentYaw.addLast(s.yawRateDps - yawBiasDps)
        while (recentYaw.size > TURN_WINDOW_SAMPLES) recentYaw.removeFirst()
        if (recentYaw.size < TURN_WINDOW_SAMPLES || recentYaw.any { abs(it) > VEHICLE_MAX_TURN_DPS }) return false
        val mean = recentYaw.average()
        val jitter = sqrt(recentYaw.sumOf { (it - mean) * (it - mean) } / recentYaw.size)
        return abs(mean) >= TURNING_MIN_DPS && jitter <= TURN_MAX_JITTER_DPS &&
            recentYaw.count { it * mean > 0 } >= TURN_MIN_ONE_WAY_FRACTION * recentYaw.size
    }

    /**
     * The phone's still detection uses fixed thresholds: an idling engine, air conditioning or a
     * stiff mount can keep it saying "moving" at a stop. Compared with the vibration of the last
     * half-minute of motion in this ride (nothing is stored between rides), a stop still shows
     * as a clear drop; [onMotion] accepts that reading only with braking proof. And a turning
     * vehicle is never standing, whatever the vibration says.
     */
    private fun motionStateOf(s: MotionSample, turning: Boolean, dt: Double): MotionSample {
        stillFromPressure = false
        stillFromVibrationDrop = false
        phoneStill = s.state == MotionState.STATIONARY && !turning
        if (turning && s.state == MotionState.STATIONARY) {
            quietSinceMs = 0L
            return s.copy(state = MotionState.MOVING, stationaryForMs = 0L)
        }
        val v = s.vibration
        if (!v.isNaN() && s.state == MotionState.MOVING && motionConfirmed && speed >= MOVING_VIBRATION_MIN_SPEED_MPS &&
            (movingVibration.isNaN() || v >= VIBRATION_DROP_FRACTION * movingVibration)) {
            movingVibration = if (movingVibration.isNaN()) v else movingVibration + (v - movingVibration) * min(1.0, dt / MOVING_VIBRATION_TAU_SEC)
            movingVibrationSec += dt
        }
        if (turning || s.state != MotionState.MOVING || v.isNaN() ||
            movingVibrationSec < MOVING_VIBRATION_MIN_SEC || v >= VIBRATION_DROP_FRACTION * movingVibration) {
            quietSinceMs = 0L
            return s
        }
        if (quietSinceMs == 0L) quietSinceMs = s.elapsedMs
        val quietFor = s.elapsedMs - quietSinceMs
        if (quietFor < QUIET_CONFIRM_MS) return s
        phoneStill = true
        if (gpsAvailable || !s.orientationKnown) return s // the stop itself needs no sensors with GPS
        stillFromVibrationDrop = true
        return s.copy(state = MotionState.STATIONARY, stationaryForMs = quietFor)
    }

    /**
     * On a line without GPS, near a station: pressure steady for [PRESSURE_STEADY_MS] means the
     * train stands. A moving train keeps pushing the tunnel air around. On the real ride this
     * showed every stop, while a phone held in the hand never looked still. Open-air running can
     * be steady too, so it counts only near a station.
     */
    private fun stateWithPressure(s: MotionSample): MotionSample {
        if (s.heightMeters.isNaN()) return s
        heightHistory.addLast(doubleArrayOf(s.elapsedMs.toDouble(), s.heightMeters))
        while (s.elapsedMs - heightHistory.first()[0] > PRESSURE_STEADY_MS) heightHistory.removeFirst()
        val spanMs = s.elapsedMs - heightHistory.first()[0]
        val steady = spanMs >= PRESSURE_STEADY_MS * 0.9 &&
            heightHistory.maxOf { it[1] } - heightHistory.minOf { it[1] } <= PRESSURE_STEADY_RANGE_METERS
        if (steady && !pressureSteady) pressureSteadySinceMs = heightHistory.first()[0].toLong()
        pressureSteady = steady
        val recent = heightHistory.filter { s.elapsedMs - it[0] <= PRESSURE_MOVING_WINDOW_MS }
        val changing = recent.maxOf { it[1] } - recent.minOf { it[1] } > PRESSURE_MOVING_RANGE_METERS
        pressureMovingSec = if (changing) pressureMovingSec + s.dtSec else 0.0
        val r = route?.takeIf { onTrack }
        if (gpsAvailable || r == null || s.state == MotionState.WALKING) { stoppedByPressure = false; return s }
        val staying = stoppedByPressure && pressureMovingSec < PRESSURE_DEPARTED_SEC
        if (!steady && !staying) return s
        if (!staying) {
            // Just left a station: air settling behind the train is not another stop there.
            val nearest = listOfNotNull(r.nextStation(navDist, direction, tolerance = 0.0), r.nextStation(navDist, -direction, tolerance = 0.0))
                .filter { it.name != lastStopStation?.name || movingSecondsSinceStop >= PRESSURE_LEFT_STATION_SEC }
                .minOfOrNull { abs(it.distanceAlongTrack - navDist) } ?: return s
            if (nearest > max(PRESSURE_STOP_RADIUS_METERS, min(PRESSURE_STOP_SIGMAS * uncertainty, PRESSURE_STOP_MAX_RADIUS_METERS))) return s
        }
        stillFromPressure = true
        return s.copy(state = MotionState.STATIONARY, stationaryForMs = max(s.stationaryForMs, s.elapsedMs - pressureSteadySinceMs))
    }

    /**
     * Speed measurement from inertial navigation: the velocity integrated from world-frame
     * acceleration since the last stop (where it was exactly zero, ZUPT), bias removed. Its error
     * grows with time (residual bias, tilt), the model's with the unknown speed of this run; the
     * two are blended by those errors — right after a departure the measurement dominates, on a
     * long run the cruise speed takes over.
     */
    private fun fuseIntegratedVelocity(dt: Double) {
        fusedSpeedSigma = Double.NaN
        if (!velKnown || !motionConfirmed) return
        val measured = sqrt(velE * velE + velN * velN)
        val sigmaMeas = VEL_MEAS_BASE_MPS + VEL_MEAS_GROWTH_MPS_PER_SEC * velSeconds
        val sigmaModel = MODEL_SPEED_ERROR_FRACTION * max(speed, cruiseSpeed)
        val w = sigmaModel * sigmaModel / (sigmaModel * sigmaModel + sigmaMeas * sigmaMeas)
        speed += w * (measured - speed) * min(1.0, dt / VEL_FUSION_TAU_SEC)
        fusedSpeedSigma = sqrt(sigmaModel * sigmaModel * sigmaMeas * sigmaMeas / (sigmaModel * sigmaModel + sigmaMeas * sigmaMeas))
    }

    private fun recordDv(sample: MotionSample, dt: Double) {
        dvHistory.addLast(doubleArrayOf(sample.elapsedMs.toDouble(), sample.accelEast * dt, sample.accelNorth * dt, speed))
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
     * Did the vehicle brake to a halt within the recent history? From its fastest moment there
     * (model speed, which inertial drift does not affect) the velocity change since must have
     * removed nearly all of that speed. Slowing to a lower speed, or speeding up, does not count.
     */
    private fun brakedToHaltRecently(): Boolean {
        if (dvHistory.isEmpty()) return false
        var fastest = 0
        for (i in dvHistory.indices) if (dvHistory[i][3] > dvHistory[fastest][3]) fastest = i
        val top = dvHistory[fastest][3]
        if (top < STOP_CHECK_MIN_MPS) return false
        var e = 0.0; var n = 0.0
        for (i in fastest until dvHistory.size) { e += dvHistory[i][1]; n += dvHistory[i][2] }
        return sqrt(e * e + n * n) >= top - max(STOP_RESIDUAL_MPS, STOP_RESIDUAL_FRACTION * top)
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

    /** How long the recent motion history reaches back within [windowMs] (ms): a knock is a short burst. */
    private fun pushedFor(windowMs: Long): Long {
        val end = dvHistory.lastOrNull()?.get(0) ?: return 0L
        val start = dvHistory.firstOrNull { end - it[0] <= windowMs }?.get(0) ?: return 0L
        return (end - start).toLong()
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
        // Reached the next station before the train stopped: slow down there until the train
        // stops (then it is snapped to the station), unless it has clearly travelled far past it
        // (train skipping the stop). Slowed, not frozen: the train is still moving, so the
        // position keeps changing; only a real stop gives the same coordinates.
        val held = heldAt
        if (held != null) {
            pendingOvershoot += step
            // A train that will stop here is braking by now; one still at speed is running through
            // (express): move on instead of creeping past the station for half a run.
            // With a barometer the stop can still be confirmed after the train has come to rest.
            val passCheckMs = if (heightHistory.isEmpty()) HOLD_PASS_CHECK_MS else HOLD_PASS_CHECK_BARO_MS
            val passing = accelMeasured && directionKnown && nowMs - holdStartMs >= passCheckMs &&
                speed >= HOLD_PASS_MIN_SPEED_MPS && speed >= HOLD_PASS_SPEED_FRACTION * holdStartSpeed
            val waitingForPressure = heightHistory.isNotEmpty() && nowMs - holdStartMs < passCheckMs
            if ((pendingOvershoot <= holdAllowance || waitingForPressure) && !passing) {
                holdCreep += step * HOLD_CREEP_FRACTION
                navDist = (held.distanceAlongTrack + direction * (holdCreep - holdMargin)).coerceIn(0.0, r.length)
                applyTrackPosition(r)
                return
            }
            events.add("Passed ${held.name} without stopping")
            navDist = (held.distanceAlongTrack + direction * pendingOvershoot).coerceIn(0.0, r.length)
            clearHold()
            applyTrackPosition(r)
            return
        }
        var newDist = navDist + direction * step
        // Never hold at the station the train is pulling out of.
        val first = r.nextStation(navDist, direction, tolerance = 0.0)
        val alreadyLeft = first != null && first.name == lastStopStation?.name && movingSecondsSinceStop > 0.0
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
                // At the end of the line there may be no track left to creep along: start earlier.
                holdMargin = if (r.nextStation(next.distanceAlongTrack, direction, tolerance = 0.0) == null) LINE_END_HOLD_MARGIN_METERS else HOLD_MARGIN_METERS
                newDist = next.distanceAlongTrack - direction * holdMargin
            }
        }
        navDist = newDist.coerceIn(0.0, r.length)
        applyTrackPosition(r)
    }

    private fun clearHold() {
        heldAt = null
        pendingOvershoot = 0.0
        holdCreep = 0.0
    }

    private fun handleStop(r: MetroRoute) {
        val next = r.nextStation(navDist, direction)
        val prev = r.previousStation(navDist, direction)
        val segStartStation = next?.let { r.nextStation(it.distanceAlongTrack, -direction, tolerance = 0.0) }
        val segLen = if (next != null && segStartStation != null) abs(next.distanceAlongTrack - segStartStation.distanceAlongTrack) else 1000.0
        val aheadWindow = max(STOP_SNAP_AHEAD_MIN_METERS, segLen * STOP_SNAP_AHEAD_FRACTION) + pendingOvershoot
        // Well ahead of the train (after a restart, say) the estimate must still come back to the
        // station, or the error would last for the rest of the ride; but never to the one it left.
        val behindWindow = if (prev?.name == lastStopStation?.name) STOP_SNAP_BEHIND_METERS
            else max(STOP_SNAP_BEHIND_METERS, min(HOLD_SIGMAS * uncertainty, segLen * OVERSHOOT_FRACTION))
        val candidates = listOfNotNull(
            next?.takeIf { abs(it.distanceAlongTrack - navDist) <= aheadWindow },
            prev?.takeIf { abs(it.distanceAlongTrack - navDist) <= behindWindow }
        )
        // Odometer: the drift-corrected inertial distance since the last trusted station says
        // which station (if any) lies that far along. Better than the running estimate, which
        // can lag or lead after express passes and holds.
        val byDistance = stationByRunDistance(r)
        val station = when (byDistance) {
            null -> candidates.minByOrNull { abs(it.distanceAlongTrack - navDist) }?.takeIf { stationMoreLikelyThanSignalStop(it, segLen) }
            NO_STATION -> null
            else -> byDistance
        }
        if (station == null) {
            events.add("Train stopped between stations (signal?)")
            return
        }
        // A stop placed by the odometer is a measurement, not a guess: the next run may use it as its start.
        val trustedNow = uncertainty <= SNAP_TRUST_MAX_UNCERTAINTY_METERS || station === byDistance
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
        // The platform is the reference for matching the next curves.
        curveRefValid = true; yawSinceRef = 0.0; lastAppliedYaw = 0.0
        curveRefDist = station.distanceAlongTrack; curveRefSec = 0.0; yawSettledSec = 0.0; yawSmoothed = 0.0
        stationStops++
        lastStopStation = station
        movingSecondsSinceStop = 0.0
        uncertainty = min(uncertainty, STATION_SNAP_UNCERTAINTY_METERS)
        applyTrackPosition(r)
        events.add("Stopped at ${station.name}")
        if (destination?.name == station.name) events.add("Arrived at destination ${station.name}")
        // The end of the line: the train can only go back, whatever the compass will say.
        directionCertain = false
        if (directionKnown && r.nextStation(station.distanceAlongTrack, direction, tolerance = 0.0) == null) {
            destination = null
            setDirection(-direction)
            directionCertain = true
            applyTrackPosition(r)
            events.add("End of the line: next run back towards ${r.nextStation(navDist, direction, tolerance = 0.0)?.name ?: "end of line"}")
        }
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
        // on its own: two measurements agree. Not if it has been waiting there a while (held with a
        // large overshoot), as when the train stops at a signal short of the platform.
        val justArrived = heldAt == null || (heldAt?.name == best.name && pendingOvershoot <= ODOMETER_MIN_TOLERANCE_METERS)
        val runningAgrees = justArrived && abs(best.distanceAlongTrack - navDist) <= ODOMETER_MIN_TOLERANCE_METERS
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
            if (!motionConfirmed) {
                motionConfirmed = true
                speed = max(speed, sqrt(meanE * meanE + meanN * meanN) * departureTime)
            }
        }
        val r = route?.takeIf { onTrack }
        if (r == null) {
            // The gyroscope keeps the heading through stops; the compass-based departure direction
            // is only needed without it.
            if (strong && (!headingKnown || !gyroAvailable)) { heading = bearing; headingKnown = true }
            // No pull-away seen (the window may have caught engine vibration at a light): keep
            // watching, or the estimate would never move again until the next stop.
            if (!strong && !motionConfirmed) startDepartureWatch()
            return
        }
        if (strong && destination == null) { // a chosen destination wins
            val diffForward = GeoToolbox.bearingDifference(bearing, r.bearingAt(navDist, 1))
            val newDir = if (diffForward <= 90.0) 1 else -1
            val clearlyOpposite = min(diffForward, 180.0 - diffForward) <= 90.0 - DIRECTION_DOUBT_MARGIN_DEG
            if (!directionKnown) {
                setDirection(newDir, confirmed = false)
                events.add("Departure detected, heading towards ${r.nextStation(navDist, direction)?.name ?: "end of line"}")
            } else if (newDir != direction && clearlyOpposite && !directionCertain && !directionUnconfirmed) {
                // The compass alone never turns a known direction round (steel and motors can throw
                // it right off), but the train may really have reversed here: let curves and
                // network fixes decide, counting from this station.
                directionUnconfirmed = true
                directionSetAt = navDist
                oppositeFixes = 0
            }
            // With the direction certain, this push shows the compass error, however large.
            if (directionCertain || (directionKnown && newDir == direction)) {
                learnCompassOffset(measured, r.bearingAt(navDist, direction), COMPASS_LEARN_RATE_DEPARTURE)
            }
        }
        if (strong) directionCertain = false
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
    private fun followTurnWithGyro(sample: MotionSample, dt: Double, maxTurnDps: Double = VEHICLE_MAX_TURN_DPS) {
        if (sample.yawRateDps.isNaN() || !headingKnown) return
        val rate = sample.yawRateDps - yawBiasDps
        if (abs(rate) > maxTurnDps) return // the phone is being turned in the hand, not the vehicle
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
        resetSegment()
        clearHold()
        directionKnown = false
        destination = null
        lastStopStation = best.first.nearestStation(navDist)
        events.add("Switched to ${best.first.lineName} (departure direction matches)")
    }

    // ---- Odometer and curve matching ----------------------------------------------------------

    private fun resetSegment() {
        segmentStart = null
        segmentStartTrusted = false
        segmentRouteId = null
        clearRun()
        curveRefValid = false
    }

    private fun clearRun() {
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

    /** A stop at [station] ends the run that began at the previous stop: the odometer starts again from it. */
    private fun finishSegment(r: MetroRoute, station: MetroStation, trustedNow: Boolean) {
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
        handlingSec = if (abs(yaw) > HANDLING_YAW_DPS) handlingSec + dt else 0.0
        if (handlingSec >= HANDLING_MIN_SEC || sample.state == MotionState.WALKING) {
            curveRefValid = false // the phone was turned in the hand: the reference is lost until the next station
            return
        }
        yawSinceRef += yaw * dt
        curveRefSec += dt
        yawSmoothed += (yaw - yawSmoothed) * min(1.0, dt / CURVE_YAW_SMOOTHING_SEC)
        yawSettledSec = if (abs(yawSmoothed) < CURVE_SETTLED_YAW_DPS) yawSettledSec + dt else 0.0
        // An unconfirmed direction is also checked when the line curves here but the train did not.
        val missedCurve = directionUnconfirmed && abs(r.turnBetween(curveRefDist, navDist) - yawSinceRef) >= CURVE_MIN_TURN_DEG
        if (!directionKnown || yawSettledSec < CURVE_SETTLE_SEC || (abs(yawSinceRef - lastAppliedYaw) < CURVE_MIN_TURN_DEG && !missedCurve)) return
        lastAppliedYaw = yawSinceRef
        val measured = yawSinceRef
        val ref = curveRefDist
        val sigma = CURVE_BASE_SIGMA_DEG + CURVE_TURN_SIGMA_FRACTION * abs(measured) + CURVE_DRIFT_DPS * curveRefSec
        // Consistency check, not a pull: after a curve the evidence is one-sided ("past the
        // curve"), so an estimate that already agrees is left alone; one that contradicts the
        // measured turn moves to the nearest position that agrees with it.
        fun agrees(d: Double) = abs(r.turnBetween(ref, d) - measured) <= CURVE_AGREE_SIGMAS * sigma
        if (agrees(navDist)) {
            if (abs(measured) >= CURVE_MIN_TURN_DEG) directionUnconfirmed = false // a real curve, where the line has one
            return
        }
        val window = WEAK_GATE_SIGMAS * max(uncertainty, MIN_TRACK_UNCERTAINTY_METERS)
        var best: Double? = null
        var d = navDist - window
        while (d <= navDist + window) {
            val c = d.coerceIn(0.0, r.length)
            val ahead = direction * (c - ref) >= 0
            if (ahead && agrees(c) && (best == null || abs(c - navDist) < abs(best - navDist))) best = c
            d += CURVE_SEARCH_STEP_METERS
        }
        if (best == null && directionUnconfirmed && curvesFitOppositeDirection(r, measured, CURVE_AGREE_SIGMAS * sigma, window)) {
            correctDirection(r, "the line's curves")
            return
        }
        val target = best ?: return
        // Trains do not reverse: a backward move only if well outside our uncertainty.
        if (direction * (target - navDist) < 0 && abs(target - navDist) <= WEAK_GATE_SIGMAS * uncertainty) return
        val jump = abs(target - navDist)
        navDist = target
        clearHold()
        curveCorrections++
        applyTrackPosition(r)
        if (jump >= CORRECTION_REPORT_METERS) events.add(String.format(Locale.US, "Curve matching moved the position %.0f m along %s", jump, r.lineName))
    }

    /** Would the [measured] turn fit if the train had gone the other way from where the direction was set? */
    private fun curvesFitOppositeDirection(r: MetroRoute, measured: Double, tolerance: Double, window: Double): Boolean {
        val from = 2 * directionSetAt - curveRefDist
        val at = 2 * directionSetAt - navDist
        var d = at - window
        while (d <= at + window) {
            if (abs(r.turnBetween(from, d.coerceIn(0.0, r.length)) - measured) <= tolerance) return true
            d += CURVE_SEARCH_STEP_METERS
        }
        return false
    }

    private fun startDepartureWatch() {
        watchingDeparture = true
        departureAccumE = 0.0; departureAccumN = 0.0; departureTime = 0.0; departureAbs = 0.0
    }

    private fun inferDirectionFromBearing(bearing: Double) {
        val r = route ?: return
        setDirection(if (GeoToolbox.bearingDifference(bearing, r.bearingAt(navDist, 1)) <= 90.0) 1 else -1)
    }

    /** [confirmed] false: it rests on the compass alone, which steel and motors can throw right off. */
    private fun setDirection(newDirection: Int, confirmed: Boolean = true) {
        direction = newDirection
        directionKnown = true
        directionUnconfirmed = !confirmed
        directionSetAt = navDist
        oppositeFixes = 0
    }

    /** The compass gave the wrong direction: the train really went the other way from where it was set. */
    private fun correctDirection(r: MetroRoute, evidence: String) {
        navDist = (2 * directionSetAt - navDist).coerceIn(0.0, r.length)
        clearHold()
        setDirection(-direction)
        compassOffX = 0.0; compassOffY = 0.0 // learnt from departures in the wrong direction
        resetSegment()
        lastStopStation = null
        applyTrackPosition(r)
        events.add("Direction corrected from $evidence: heading towards ${r.nextStation(navDist, direction)?.name ?: "end of line"}")
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
            source == PositionSource.ESTIMATE -> "No GPS · phone estimate"
            else -> "No GPS · dead reckoning"
        } + (if (!gpsAvailable && hasPosition && networkCorrections > 0) " · network-assisted" else "") +
            (if (!gpsAvailable && hasPosition && curveCorrections > 0) " · curve-matched" else "")

        return NavState(
            latitude = lat, longitude = lon, source = source, gpsAvailable = gpsAvailable,
            speedMps = speed, headingDeg = heading, headingKnown = headingKnown || (trackActive && directionKnown),
            gpsAltitudeMeters = if (gpsAvailable) lastAltitude else null, motion = motion, gpsLostForMs = lostFor,
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
            curveCorrections = curveCorrections
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
        /** No good satellite fix for this long (only poor ones): GPS counts as lost (like SensorHub's watchdog). */
        const val GOOD_GPS_TIMEOUT_MS = 3_000L
        /** A free estimate rejects this many network fixes that agree with each other, then moves to them. */
        const val WEAK_REJECT_RESET_COUNT = 3
        /** Fixes this close are the same fix (about 0.2 m; the log keeps 6 decimals). */
        const val SAME_FIX_DEGREES = 0.000002
        /** What a mobile-cell fix is really worth, whatever it claims. */
        const val COARSE_FIX_ACCURACY_METERS = 1_500f
        /** Pressure steady this long, within this height: the train stands (near a station). */
        const val PRESSURE_STEADY_MS = 10_000L
        const val PRESSURE_STEADY_RANGE_METERS = 0.4
        const val PRESSURE_STOP_RADIUS_METERS = 200.0
        const val PRESSURE_STOP_MAX_RADIUS_METERS = 400.0
        const val PRESSURE_STOP_SIGMAS = 2.0
        /** Height moving by this much within the window, for this long: the train has left. */
        const val PRESSURE_MOVING_WINDOW_MS = 5_000L
        const val PRESSURE_MOVING_RANGE_METERS = 0.3
        const val PRESSURE_DEPARTED_SEC = 8.0
        const val PRESSURE_LEFT_STATION_SEC = 30.0
        /** Below this a jump of the GPS position says nothing about the direction of travel. */
        const val DIRECTION_FROM_MOTION_MIN_MPS = 1.0

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
        const val LINE_END_HOLD_MARGIN_METERS = 40.0
        /** Speed of the held estimate as a share of the model's: slow, but never frozen while moving. */
        const val HOLD_CREEP_FRACTION = 0.15
        /** Held at a station this long with no braking (speed kept): the train is running through. */
        const val HOLD_PASS_CHECK_MS = 6_000L
        const val HOLD_PASS_CHECK_BARO_MS = 30_000L
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

        const val FREE_SPEED_DECAY_TAU_SEC = 60.0

        // Vehicle model without a line
        const val VEHICLE_MIN_MPS = 5.0
        const val WALKING_MAX_MPS = 2.5
        const val WALK_ENDS_RIDE_SEC = 20.0
        const val DEFAULT_STRIDE_METERS = 0.7
        const val MIN_STRIDE_METERS = 0.4
        const val MAX_STRIDE_METERS = 1.0
        const val STRIDE_LEARN_STEPS = 20
        const val STRIDE_LEARN_MIN_MPS = 0.4
        const val WALK_COURSE_MIN_MPS = 0.5
        const val WALK_MAX_TURN_DPS = 360.0
        const val STEP_MIN_GAP_MS = 250L
        const val STEP_PAUSE_MS = 2_000L
        const val STEP_ERROR_FRACTION = 0.15
        /** This many steps in a row on a line, never having been in a vehicle: on foot beside it. */
        const val STEPS_TO_LEAVE_LINE = 10
        /** Walking-type shaking this long without steps: walking anyway, at this cadence. */
        const val WALK_WITHOUT_STEPS_SEC = 3.0
        const val WALK_CADENCE_HZ = 1.6
        /** After a restart: the gap counts as this share of the distance the train could have run. */
        const val RESUME_GAP_ERROR_FRACTION = 0.5
        /** After a restart: the saved speed is carried on for this long at most (it may have stopped since). */
        const val RESUME_MAX_CARRY_SEC = 30.0
        /** GPS lost at this speed or less: the vehicle stands, and must be seen pulling away. */
        const val STANDING_AT_LOSS_MPS = 1.0

        // Stop / departure verification
        const val DV_HISTORY_MS = 25_000L
        const val UNCONFIRMED_MOVING_ACCEPT_SEC = 20.0
        /** Off a line: this long at this fraction of the ride's driving vibration restarts the estimate. */
        const val VIBRATION_RESTART_SEC = 10.0
        const val VIBRATION_RESTART_FRACTION = 0.7
        /** Not driven long enough to know the driving level: this many times the vibration at the stop, and at least the minimum. */
        const val VIBRATION_RESTART_STANDING_RATIO = 5.0
        const val VIBRATION_RESTART_MIN = 0.2
        const val STANDING_VIBRATION_TAU_SEC = 5.0
        /** Speed given to a departure seen only as a turn (pulling out of a junction). */
        const val TURN_DEPARTURE_SPEED_MPS = 3.0
        /** Below this speed a stop needs no braking proof. */
        const val STOP_CHECK_MIN_MPS = 5.0
        /** A real stop shows a velocity change of at least this fraction of the speed. */
        const val BRAKE_MIN_FRACTION = 0.5
        const val STILL_VERIFY_GRACE_MS = 1_500L
        /** Still this long is a stop even without braking proof (sensor trouble). */
        const val LONG_STILL_ACCEPT_MS = 15_000L
        const val DEPARTURE_CONFIRM_WINDOW_MS = 6_000L
        const val DEPARTURE_CONFIRM_DV_MPS = 1.5
        /**
         * |Σ a| / Σ |a| needed to call it a departure: a train or car pushes one way (0.9 and more
         * in simulation); knocks and hand motion go every way, but a short burst reached 0.57.
         */
        const val DEPARTURE_MIN_CONSISTENCY = 0.7
        /** A departure keeps pushing; a knock that happens to push one way lasts about a second. */
        const val DEPARTURE_MIN_PUSH_MS = 2_000L
        /** A departure within 90 − this of the opposite way casts doubt on a known direction. */
        const val DIRECTION_DOUBT_MARGIN_DEG = 45.0
        const val COMPASS_CAL_MIN_ACCEL = 0.4
        const val COMPASS_LEARN_RATE_GPS = 0.02
        const val COMPASS_LEARN_RATE_DEPARTURE = 0.3
        const val COMPASS_OFFSET_MIN_CONFIDENCE = 0.5
        /** After a real stop the integrated velocity is back within this (bias drift). */
        const val STOP_RESIDUAL_MPS = 3.0
        const val STOP_RESIDUAL_FRACTION = 0.35

        // Inertial speed (integrated acceleration since the last stop)
        const val BIAS_LEARN_AFTER_STILL_MS = 2_000L
        /**
         * Long averaging across several stops: the bias changes slowly (temperature), and one
         * platform's worth of noisy samples left enough error to drift several m/s over a long run.
         */
        const val BIAS_LEARN_TAU_SEC = 300.0
        /** The early mean counts as if this many zero samples came first (10 s of standing). */
        const val BIAS_PRIOR_SAMPLES = 100
        const val STOP_RESIDUAL_GROWTH_MPS_PER_SEC = 0.04
        const val VEL_MEAS_BASE_MPS = 0.5
        /** Growth of the integrated-velocity error (residual bias / tilt), m/s per second. */
        const val VEL_MEAS_GROWTH_MPS_PER_SEC = 0.1
        /** Speed of an unknown run vs. the learnt cruise: runs differ a lot on real lines. */
        const val MODEL_SPEED_ERROR_FRACTION = 0.3
        const val VEL_FUSION_TAU_SEC = 1.0

        // Odometer / station snaps
        /** A station snap made with more error than this may be the wrong station: not a trusted start for the odometer. */
        const val SNAP_TRUST_MAX_UNCERTAINTY_METERS = 300.0
        /** Corrections at least this large are reported as events. */
        const val CORRECTION_REPORT_METERS = 150.0
        const val MAX_RUN_SAMPLES = 6000

        // Curve matching
        /** Faster than any train curve: the phone is being turned in the hand. */
        const val HANDLING_YAW_DPS = 15.0
        const val HANDLING_MIN_SEC = 0.3
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
        /** Standing: a turn rate further than this from the bias is a real (slow) turn, not bias. */
        const val GYRO_BIAS_MAX_DEVIATION_DPS = 1.0
        /** Turn rate of a vehicle turning (junction, roundabout, platform curve): it is moving. */
        const val TURNING_MIN_DPS = 3.0
        /** 1.5 s of motion samples (100 ms each). */
        const val TURN_WINDOW_SAMPLES = 15
        const val TURN_MIN_ONE_WAY_FRACTION = 0.8
        /** A vehicle turns smoothly; a turn rate jumping around more than this is the box being handled. */
        const val TURN_MAX_JITTER_DPS = 6.0
        /** Error growth while vibration says moving but no movement is modelled (m/s). */
        const val UNMODELLED_MOTION_ERROR_MPS = 2.0

        // Stops seen as a vibration drop (within this ride; nothing is stored)
        /** A stop: vibration below this fraction of the recent moving level. */
        const val VIBRATION_DROP_FRACTION = 0.4
        const val MOVING_VIBRATION_TAU_SEC = 30.0
        /** Seconds of known motion before the moving level is trusted. */
        const val MOVING_VIBRATION_MIN_SEC = 20.0
        const val MOVING_VIBRATION_MIN_SPEED_MPS = 4.0
        /** Vibration this far down for this long reads as standing (like the phone's own 1 s rule). */
        const val QUIET_CONFIRM_MS = 1_000L

        // Standing with GPS: the coordinates stay
        /** A standing receiver still reports about 0.1 m/s. */
        const val GPS_STANDING_MPS = 0.5
        /** Further from the held position than this (or twice the fix's accuracy) is not noise. */
        const val GPS_NOISE_METERS = 15.0
        /** A fix this much more accurate than the held position replaces it. */
        const val GPS_BETTER_FIX_FRACTION = 0.5

        // Weak satellite fixes
        const val WEAK_GNSS_SPEED_MAX_ACCURACY_METERS = 100f
        const val WEAK_GNSS_SPEED_WEIGHT = 0.3
        /** A poor satellite fix's course is used for the heading above this speed. */
        const val WEAK_GNSS_COURSE_MIN_MPS = 3.0
    }
}
