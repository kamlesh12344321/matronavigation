package com.example.testgpslocation.engine

import com.example.testgpslocation.models.GpsPoint
import com.example.testgpslocation.utils.GeoToolbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Riding with no metro line saved (a road test, or a line not downloaded), the phone fixed in its
 * box: GPS too poor, GPS and network gone at a stop, pulling away again, turns and speed.
 */
class NoGpsDrivingTest {

    private lateinit var nav: UndergroundNavigator
    private var clock = 0L
    private var lat = 12.90
    private var lon = 77.0

    @Before
    fun setUp() {
        nav = UndergroundNavigator()
        clock = 1_000_000L
        lat = 12.90; lon = 77.0
    }

    private fun fix(speed: Float, bearing: Float, accuracy: Float = 5f, isGnss: Boolean = true) {
        nav.onLocationFix(GpsPoint(lat, lon, accuracy, clock, speed, bearing, hasSpeed = isGnss, hasBearing = isGnss && speed > 0f,
            elapsedRealtimeMs = clock, isGnss = isGnss), clock)
    }

    /** One 100 ms motion sample; the true position moves [speed] along [bearing]. */
    private fun sample(state: MotionState, speed: Double = 0.0, bearing: Double = 0.0, accel: Double = 0.0,
                       yawDps: Double = 0.0, stillForMs: Long = 0, vibration: Double = Double.NaN, steps: Int = 0) {
        clock += 100
        if (speed > 0) GeoToolbox.moveLocation(lat, lon, speed * 0.1, bearing).let { (a, b) -> lat = a; lon = b }
        val b = Math.toRadians(bearing)
        nav.onMotion(MotionSample(clock, 0.1, state, if (state == MotionState.STATIONARY) stillForMs else 0,
            accel * sin(b), accel * cos(b), orientationKnown = true, yawRateDps = yawDps, vibration = vibration, steps = steps))
    }

    /** Driving at [speed] along [bearing] with a GPS fix every second. */
    private fun driveWithGps(seconds: Int, speed: Double, bearing: Double) = repeat(seconds) {
        repeat(10) { sample(MotionState.MOVING, speed, bearing) }
        fix(speed.toFloat(), bearing.toFloat())
    }

    /** Stopped with GPS (speed 0), then GPS and network gone while standing. */
    private fun stopAndLoseEverything(standSec: Int, bearing: Double) {
        driveWithGps(10, 10.0, bearing)
        repeat(3) { repeat(10) { k -> sample(MotionState.STATIONARY, stillForMs = (k + 1) * 100L) }; fix(0f, bearing.toFloat()) }
        nav.onGpsLost(clock)
        repeat(standSec * 10) { k -> sample(MotionState.STATIONARY, stillForMs = 3_000L + (k + 1) * 100L) }
    }

    private fun distanceFromTruth() = GeoToolbox.calculateDistance(nav.currentLat, nav.currentLon, lat, lon)

    /** Drove 30 s with GPS at driving vibration, stopped, then GPS and network gone; stands [standSec]. */
    private fun driveStopAndLoseEverything(standSec: Int) {
        repeat(30) { repeat(10) { sample(MotionState.MOVING, 10.0, 0.0, vibration = 0.4) }; fix(10f, 0f) }
        repeat(3) { repeat(10) { k -> sample(MotionState.STATIONARY, stillForMs = (k + 1) * 100L, vibration = 0.03) }; fix(0f, 0f) }
        nav.onGpsLost(clock)
        repeat(standSec * 10) { k -> sample(MotionState.STATIONARY, stillForMs = 3_000L + (k + 1) * 100L, vibration = 0.03) }
    }

    /** Walking at 1 m/s: a 0.6 m step every 600 ms, the phone shaking; a GPS fix each second when [gps]. */
    private fun walk(seconds: Int, bearing: Double, gps: Boolean) {
        repeat(seconds * 10) { k ->
            sample(MotionState.WALKING, 1.0, bearing, vibration = 1.5, steps = if (k % 6 == 0) 1 else 0)
            if (gps && k % 10 == 9) fix(1f, bearing.toFloat())
        }
    }

    private fun network(accuracy: Float = 30f) =
        nav.onLocationFix(GpsPoint(lat, lon, accuracy, clock, elapsedRealtimeMs = clock, isGnss = false), clock)

    @Test
    fun onFootWithoutGpsTheMarkerFollowsTheSteps() {
        walk(30, 0.0, gps = true) // learns this walker's 0.6 m stride
        nav.onGpsLost(clock)
        val startLat = nav.currentLat
        walk(60, 0.0, gps = false)
        val walked = (nav.currentLat - startLat) * 111_195.0
        assertEquals(60.0, walked, 3.0)
        assertEquals(1.0, nav.state().speedMps, 0.1)
        assertEquals(PositionSource.DEAD_RECKONING, nav.state().source)
        assertTrue("off by ${distanceFromTruth()} m", distanceFromTruth() < 6.0)
    }

    @Test
    fun aTurnOnFootIsFollowedHoweverFast() {
        walk(30, 0.0, gps = true)
        nav.onGpsLost(clock)
        walk(20, 0.0, gps = false)
        repeat(10) { sample(MotionState.WALKING, 1.0, 90.0, yawDps = 90.0, vibration = 1.5) } // 90° in a second
        walk(20, 90.0, gps = false)
        assertTrue("off by ${distanceFromTruth()} m", distanceFromTruth() < 6.0)
    }

    @Test
    fun stoppingOnFootStopsTheMarker() {
        walk(30, 0.0, gps = true)
        nav.onGpsLost(clock)
        walk(10, 0.0, gps = false)
        repeat(25) { sample(MotionState.MOVING, vibration = 0.2) } // phone in the hand: never quite still
        val stopLat = nav.currentLat
        repeat(300) { sample(MotionState.MOVING, vibration = 0.2) }
        assertEquals(stopLat, nav.currentLat, 1e-9)
        assertEquals(0.0, nav.state().speedMps, 1e-9)
    }

    @Test
    fun walkingWithoutAKnownDirectionHoldsThePositionAndDoubtsIt() {
        fix(0f, 0f)
        nav.onGpsLost(clock + 3000)
        val startLat = nav.currentLat; val startError = nav.state().uncertaintyMeters
        walk(30, 0.0, gps = false)
        assertEquals(startLat, nav.currentLat, 1e-9)
        assertTrue("error ${nav.state().uncertaintyMeters}", nav.state().uncertaintyMeters >= startError + 30 * 0.7 * 0.9)
    }

    @Test
    fun stepsAreIgnoredInAVehicle() {
        driveStopAndLoseEverything(standSec = 5) // a rough road or someone walking past can look like steps
        val stopLat = nav.currentLat
        repeat(300) { k -> sample(MotionState.MOVING, vibration = 0.2, steps = if (k % 6 == 0) 1 else 0) }
        assertEquals(stopLat, nav.currentLat, 1e-9)
    }

    @Test
    fun aWalkerStaysAWalkerAfterTheAppRestarts() {
        walk(30, 0.0, gps = true)
        nav.onGpsLost(clock)
        walk(5, 0.0, gps = false)
        val saved = RouteJson.readRide(RouteJson.writeRide(nav.rideState(clock)!!)) // through the file, as in the app
        assertFalse(saved.inVehicle)
        nav = UndergroundNavigator()
        nav.resume(saved, null, 2.0, clock)
        val startLat = nav.currentLat
        repeat(100) { sample(MotionState.MOVING, vibration = 0.2) } // no steps: no vehicle model running at 12 m/s
        assertTrue("moved ${(nav.currentLat - startLat) * 111_195.0} m", (nav.currentLat - startLat) * 111_195.0 < 15.0)
    }

    @Test
    fun aWalkerWithNoKnownDirectionStaysPutAfterTheAppRestarts() {
        fix(0f, 0f)
        nav.onGpsLost(clock + 3000)
        val saved = RouteJson.readRide(RouteJson.writeRide(nav.rideState(clock)!!))
        assertFalse(saved.headingKnown)
        nav = UndergroundNavigator()
        nav.resume(saved, null, 2.0, clock)
        val startLat = nav.currentLat
        walk(20, 0.0, gps = false)
        assertEquals(startLat, nav.currentLat, 1e-9)
    }

    @Test
    fun aWalkerWhoStopsJustAsGpsIsLostStaysThere() {
        walk(30, 0.0, gps = true)
        repeat(10) { sample(MotionState.MOVING, vibration = 0.2) }
        fix(1.2f, 0f) // the chip's last reading still says walking
        nav.onGpsLost(clock + 100)
        val stopLat = nav.currentLat
        repeat(600) { sample(MotionState.MOVING, vibration = 0.2) }
        assertEquals(stopLat, nav.currentLat, 1e-9)
    }

    @Test
    fun walkingIsFollowedEvenWithoutAStepSensor() {
        repeat(300) { k ->
            sample(MotionState.WALKING, 1.0, 0.0, vibration = 1.5)
            if (k % 10 == 9) fix(1f, 0f)
        }
        nav.onGpsLost(clock)
        val startLat = nav.currentLat
        repeat(300) { sample(MotionState.WALKING, 1.0, 0.0, vibration = 1.5) }
        val walked = (nav.currentLat - startLat) * 111_195.0
        assertTrue("walked $walked m of 30", walked in 25.0..45.0)
        repeat(10) { sample(MotionState.MOVING, vibration = 0.2) }
        val stopLat = nav.currentLat
        repeat(300) { sample(MotionState.MOVING, vibration = 0.2) }
        assertTrue("drifted ${(nav.currentLat - stopLat) * 111_195.0} m", abs(nav.currentLat - stopLat) * 111_195.0 < 2.0)
    }

    @Test
    fun aVehicleThatLooksLikeStepsStillShowsItsPullAway() {
        repeat(30) { k -> sample(MotionState.STATIONARY, stillForMs = (k + 1) * 100L) }
        fix(0f, 0f)
        nav.onGpsLost(clock)
        repeat(30) { k -> sample(MotionState.STATIONARY, stillForMs = 3_000L + (k + 1) * 100L) }
        var v = 0.0
        repeat(100) { k -> v += 0.15; sample(MotionState.MOVING, v, 0.0, accel = 1.5, vibration = 0.4, steps = if (k % 6 == 0) 1 else 0) }
        assertTrue("speed ${nav.state().speedMps}", nav.state().speedMps > 5.0)
    }

    @Test
    fun drivingAfterAWalkKeepsItsSpeedWhenGpsIsLost() {
        walk(30, 0.0, gps = true)
        nav.onGpsLost(clock)
        walk(5, 0.0, gps = false)
        driveWithGps(20, 10.0, 0.0)
        nav.onGpsLost(clock)
        repeat(30) { sample(MotionState.MOVING, 10.0, 0.0) }
        assertTrue("speed ${nav.state().speedMps}", nav.state().speedMps > 8.0)
    }

    @Test
    fun aShortDriveStillRestartsFromTheVibration() {
        repeat(10) { repeat(10) { sample(MotionState.MOVING, 10.0, 0.0, vibration = 0.4) }; fix(10f, 0f) } // too short to know the driving level
        repeat(3) { repeat(10) { k -> sample(MotionState.STATIONARY, stillForMs = (k + 1) * 100L, vibration = 0.03) }; fix(0f, 0f) }
        nav.onGpsLost(clock)
        repeat(100) { k -> sample(MotionState.STATIONARY, stillForMs = 3_000L + (k + 1) * 100L, vibration = 0.03) }
        val stopLat = nav.currentLat
        var v = 0.0; var firstMoveSec = -1.0
        repeat(200) { k ->
            v = min(6.0, v + 0.02)
            sample(MotionState.MOVING, v, 0.0, 0.2, vibration = 0.4)
            if (firstMoveSec < 0 && nav.currentLat != stopLat) firstMoveSec = k / 10.0
        }
        assertTrue("first moved after $firstMoveSec s", firstMoveSec in 5.0..12.0)
    }

    @Test
    fun idlingAfterAShortDriveStaysPut() {
        repeat(10) { repeat(10) { sample(MotionState.MOVING, 10.0, 0.0, vibration = 0.4) }; fix(10f, 0f) }
        repeat(3) { repeat(10) { k -> sample(MotionState.STATIONARY, stillForMs = (k + 1) * 100L, vibration = 0.03) }; fix(0f, 0f) }
        nav.onGpsLost(clock)
        repeat(100) { k -> sample(MotionState.STATIONARY, stillForMs = 3_000L + (k + 1) * 100L, vibration = 0.03) }
        val stopLat = nav.currentLat
        repeat(900) { sample(MotionState.MOVING, vibration = 0.1) } // engine idling, phone says moving
        assertEquals(stopLat, nav.currentLat, 1e-9)
    }

    @Test
    fun aPullAwayTooGentleToMeasureIsFoundFromTheVibration() {
        driveStopAndLoseEverything(standSec = 20)
        val stopLat = nav.currentLat
        var v = 0.0; var travelled = 0.0; var firstMoveSec = -1.0
        repeat(600) { k ->
            val accel = if (v < 6.0) 0.2 else 0.0
            v = min(6.0, v + accel * 0.1); travelled += v * 0.1
            sample(MotionState.MOVING, v, 0.0, accel, vibration = 0.4)
            if (firstMoveSec < 0 && nav.currentLat != stopLat) firstMoveSec = k / 10.0
        }
        assertTrue("first moved after $firstMoveSec s", firstMoveSec in 5.0..12.0)
        val marker = (nav.currentLat - stopLat) * 111_195.0
        assertTrue("marker $marker of $travelled m", marker in 0.7 * travelled..1.5 * travelled)
    }

    @Test
    fun idlingBelowDrivingVibrationStaysPut() {
        driveStopAndLoseEverything(standSec = 5)
        val stopLat = nav.currentLat
        repeat(900) { sample(MotionState.MOVING, vibration = 0.2) } // above the drop, below driving level
        assertEquals(stopLat, nav.currentLat, 1e-9)
        assertEquals(0.0, nav.state().speedMps, 1e-9)
    }

    @Test
    fun knocksAtDrivingVibrationDoNotAddUpToARestart() {
        driveStopAndLoseEverything(standSec = 5)
        val stopLat = nav.currentLat
        repeat(4) {
            repeat(60) { sample(MotionState.MOVING, vibration = 0.4) } // 6 s of shaking
            repeat(30) { k -> sample(MotionState.STATIONARY, stillForMs = (k + 1) * 100L, vibration = 0.03) }
        }
        assertEquals(stopLat, nav.currentLat, 1e-9)
    }

    @Test
    fun gpsFixesWithNoSatellitesAreEstimatesNotGps() {
        driveWithGps(10, 10.0, 0.0)
        fun estimate() = nav.onLocationFix(GpsPoint(lat, lon, 50f, clock, 10f, 0f, hasSpeed = true, hasBearing = true,
            elapsedRealtimeMs = clock, isGnss = false, isChipEstimate = true), clock)
        repeat(2) {
            repeat(10) { sample(MotionState.MOVING, 10.0, 0.0) }
            assertEquals("ignored: GPS is working", estimate())
        }
        repeat(10) { repeat(10) { sample(MotionState.MOVING, 10.0, 0.0) }; estimate() }
        assertFalse("no satellites is not GPS", nav.state().gpsAvailable)
        assertEquals(PositionSource.ESTIMATE, nav.state().source)
        assertTrue("off by ${distanceFromTruth()} m", distanceFromTruth() < 40.0)
    }

    @Test
    fun everyNetworkFixSaysWhatBecameOfIt() {
        driveWithGps(5, 10.0, 0.0)
        assertEquals("ignored: GPS is working", network())
        assertEquals("ignored: accuracy too poor", network(accuracy = 3000f))
        stopAndLoseEverything(standSec = 5, bearing = 0.0)
        assertEquals("ignored: stopped", network())
        var v = 0.0
        repeat(80) { v += 0.15; sample(MotionState.MOVING, v, 0.0, accel = 1.5) }
        assertEquals("used", network())
    }

    @Test
    fun aWholeDriveGivesNewCoordinatesWhileMovingAndTheSameOnlyWhileStopped() {
        // The rule for the log: while the vehicle moves every row (one every 2 s) is a new
        // position; the same coordinates only while it really stands.
        repeat(10) { repeat(10) { sample(MotionState.MOVING, 10.0, 0.0, vibration = 0.4) }; fix(10f, 0f) }
        nav.onGpsLost(clock)
        data class Phase(val name: String, val seconds: Int, val moving: Boolean, val graceSec: Double, val tick: (Int) -> Unit)
        var v = 10.0; var heading = 0.0; var still = 0L
        fun move(accel: Double = 0.0, yaw: Double = 0.0) {
            v = (v + accel * 0.1).coerceAtLeast(0.0); heading += yaw * 0.1; still = 0
            sample(MotionState.MOVING, v, heading, accel, yaw, vibration = 0.4)
        }
        fun stand(phoneReadsStill: Boolean) {
            v = 0.0; still += 100
            if (phoneReadsStill) sample(MotionState.STATIONARY, stillForMs = still, vibration = 0.03)
            else sample(MotionState.MOVING, vibration = 0.1) // engine idling: the phone says "moving"
        }
        val phases = listOf(
            Phase("cruise north", 20, true, 0.0) { move() },
            Phase("brake", 5, true, 0.0) { move(accel = -2.0) },
            Phase("stand (phone still)", 20, false, 3.0) { stand(true) },
            Phase("pull away", 7, true, 2.5) { move(accel = 1.5) },
            Phase("turn right", 6, true, 0.0) { move(yaw = 15.0) },
            Phase("cruise east", 20, true, 0.0) { move() },
            Phase("brake", 5, true, 0.0) { move(accel = -2.1) },
            Phase("stand (engine idling)", 30, false, 3.0) { stand(false) },
            Phase("pull away", 7, true, 2.5) { move(accel = 1.5) },
            Phase("cruise", 20, true, 0.0) { move() }
        )
        var lastRow: Pair<Double, Double>? = null
        val problems = mutableListOf<String>()
        for (p in phases) {
            for (i in 0 until p.seconds * 10) {
                p.tick(i)
                if ((i + 1) % 20 != 0) continue // a log row every 2 s
                val row = nav.currentLat to nav.currentLon
                val sec = (i + 1) / 10.0
                if (lastRow != null && sec > p.graceSec) {
                    if (p.moving && row == lastRow) problems += "${p.name} at ${sec}s: same coordinates while moving"
                    if (!p.moving && row != lastRow) problems += "${p.name} at ${sec}s: coordinates changed while stopped"
                }
                lastRow = row
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
        assertTrue("off by ${distanceFromTruth()} m after 2.4 km", distanceFromTruth() < 250.0)
    }

    @Test
    fun onlyPoorGpsFixesCountAsGpsLostAndTheEstimateKeepsMoving() {
        driveWithGps(5, 10.0, 0.0)
        // Under a flyover / between tall buildings: fixes keep coming, all ±80 m.
        repeat(20) {
            repeat(10) { sample(MotionState.MOVING, 10.0, 0.0) }
            fix(10f, 0f, accuracy = 80f)
        }
        val s = nav.state()
        assertFalse("GPS must count as lost", s.gpsAvailable)
        assertTrue("events ${nav.events}", nav.events.any { it.startsWith("No good GPS fix") })
        // It used to freeze at the last good fix, 200 m back.
        assertTrue("off by ${distanceFromTruth()} m", distanceFromTruth() < 40.0)
        assertEquals(10.0, s.speedMps, 1.5)
    }

    @Test
    fun gpsLostWhileStandingThenPullingAwayMovesAgain() {
        stopAndLoseEverything(standSec = 30, bearing = 0.0)
        val stopLat = nav.currentLat
        // Engine idling: the phone reads "moving" but nothing accelerates. No motion invented.
        repeat(100) { sample(MotionState.MOVING) }
        assertEquals(stopLat, nav.currentLat, 1e-9)
        // Pulls away north at 1.5 m/s² to 12 m/s, then cruises 20 s. It used to stay at the stop
        // (a stop with GPS made the vehicle "not a vehicle" any more).
        var v = 0.0
        repeat(80) { v += 0.15; sample(MotionState.MOVING, v, 0.0, accel = 1.5) }
        repeat(200) { sample(MotionState.MOVING, v, 0.0) }
        val truthMoved = 0.5 * 1.5 * 8.0 * 8.0 + 12.0 * 20.0
        val moved = (nav.currentLat - stopLat) * 111_195.0
        assertTrue("moved $moved of $truthMoved m", moved > 0.7 * truthMoved)
        assertEquals(12.0, nav.state().speedMps, 3.0)
        assertTrue("off by ${distanceFromTruth()} m", distanceFromTruth() < 0.25 * truthMoved)
    }

    @Test
    fun idlingAtALongRedLightNeverInventsMotion() {
        stopAndLoseEverything(standSec = 5, bearing = 90.0)
        val stopLat = nav.currentLat; val stopLon = nav.currentLon
        repeat(900) { sample(MotionState.MOVING) } // 90 s of engine vibration, no acceleration
        assertEquals(stopLat, nav.currentLat, 1e-9)
        assertEquals(stopLon, nav.currentLon, 1e-9)
        assertEquals(0.0, nav.state().speedMps, 1e-9)
    }

    @Test
    fun aTurnWhilePullingAwayIsFollowedByTheGyroscope() {
        stopAndLoseEverything(standSec = 10, bearing = 0.0)
        // Right turn out of the junction: 90° in 6 s while speeding up, then 20 s east.
        var v = 0.0; var heading = 0.0
        repeat(60) { v += 0.12; heading += 1.5; sample(MotionState.MOVING, v, heading, accel = 1.2, yawDps = 15.0) }
        repeat(200) { sample(MotionState.MOVING, v, 90.0) }
        assertEquals(90.0, nav.state().headingDeg, 10.0)
        val east = (nav.currentLon - 77.0) * 108_000.0
        assertTrue("moved east $east m", east > 120.0)
        assertTrue("off by ${distanceFromTruth()} m", distanceFromTruth() < 60.0)
    }

    @Test
    fun aSlowTurnThatReadsStillIsNotAStopAndNotGyroBias() {
        driveWithGps(10, 6.0, 0.0)
        nav.onGpsLost(clock)
        // Round a roundabout / down a parking ramp: 6 m/s turning 8 °/s, smooth enough for the
        // phone to read "still" for 20 s — longer than the "still for 15 s is a stop" rule.
        var heading = 0.0
        repeat(200) { k -> heading += 0.8; sample(MotionState.STATIONARY, 6.0, heading, yawDps = 8.0, stillForMs = (k + 1) * 100L) }
        repeat(50) { sample(MotionState.MOVING, 6.0, heading) }
        val s = nav.state()
        assertTrue("speed ${s.speedMps}", s.speedMps > 4.0)
        // 200° of turn followed, and no turn rate taken as bias (the heading holds afterwards).
        assertEquals(heading % 360.0, s.headingDeg, 15.0)
        assertTrue("off by ${distanceFromTruth()} m", distanceFromTruth() < 50.0)
    }

    @Test
    fun aBumpyRoadInAVehicleIsNotWalking() {
        driveWithGps(10, 10.0, 0.0)
        nav.onGpsLost(clock)
        val before = nav.currentLat
        // Rough road: shaking strong enough for the phone to call it "walking".
        repeat(100) { sample(MotionState.WALKING, 10.0, 0.0) }
        assertEquals(10.0, nav.state().speedMps, 2.0)
        assertTrue("moved ${(nav.currentLat - before) * 111_195.0} m", (nav.currentLat - before) * 111_195.0 > 80.0)
    }

    @Test
    fun networkFixesThatAgreeWinOverAStandingEstimateThatDrifted() {
        stopAndLoseEverything(standSec = 5, bearing = 0.0)
        val stuckLat = nav.currentLat
        // The vehicle is really 600 m further (say, it moved while the phone was off). Network
        // location says so, ±50 m. The standing estimate's error does not grow, so it used to
        // reject the network for ever.
        GeoToolbox.moveLocation(lat, lon, 600.0, 0.0).let { (a, b) -> lat = a; lon = b }
        clock += 1000; lat += 0.00001; fix(0f, 0f, accuracy = 50f, isGnss = false)
        assertEquals("one far fix is an outlier", stuckLat, nav.currentLat, 1e-9)
        repeat(2) { clock += 1000; lat += 0.00001; fix(0f, 0f, accuracy = 50f, isGnss = false) } // fresh fixes, not one fix repeated
        assertTrue("off by ${distanceFromTruth()} m", distanceFromTruth() < 60.0)
        assertEquals(PositionSource.NETWORK, nav.state().source)
    }

    @Test
    fun networkLocationIsUsedOnlyWhileThereIsNoGoodGps() {
        driveWithGps(5, 10.0, 0.0)
        val gpsLat = nav.currentLat
        clock += 100; nav.onLocationFix(GpsPoint(lat + 0.003, lon, 30f, clock, elapsedRealtimeMs = clock, isGnss = false), clock)
        assertEquals("GPS first", gpsLat, nav.currentLat, 1e-9)
        nav.onGpsLost(clock)
        repeat(3) { clock += 1000; nav.onLocationFix(GpsPoint(lat, lon, 20f, clock, elapsedRealtimeMs = clock, isGnss = false), clock) }
        assertTrue("network next: ${nav.state().networkCorrections}", nav.state().networkCorrections > 0)
    }

    @Test
    fun aJiggledPhoneAtAStopIsNotAPullAway() {
        stopAndLoseEverything(standSec = 5, bearing = 0.0)
        val stopLat = nav.currentLat
        // Someone knocks the box: shaking back and forth, turning both ways.
        repeat(100) { k ->
            val sign = if (k % 4 < 2) 1.0 else -1.0
            sample(MotionState.MOVING, accel = 2.0 * sign, yawDps = 30.0 * sign)
        }
        assertEquals(stopLat, nav.currentLat, 1e-9)
        assertTrue(abs(nav.state().speedMps) < 1e-9)
    }
}
