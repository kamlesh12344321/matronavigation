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
                       yawDps: Double = 0.0, stillForMs: Long = 0, vibration: Double = Double.NaN) {
        clock += 100
        if (speed > 0) GeoToolbox.moveLocation(lat, lon, speed * 0.1, bearing).let { (a, b) -> lat = a; lon = b }
        val b = Math.toRadians(bearing)
        nav.onMotion(MotionSample(clock, 0.1, state, if (state == MotionState.STATIONARY) stillForMs else 0,
            accel * sin(b), accel * cos(b), orientationKnown = true, yawRateDps = yawDps, vibration = vibration))
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
        clock += 1000; fix(0f, 0f, accuracy = 50f, isGnss = false)
        assertEquals("one far fix is an outlier", stuckLat, nav.currentLat, 1e-9)
        repeat(2) { clock += 1000; fix(0f, 0f, accuracy = 50f, isGnss = false) }
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
