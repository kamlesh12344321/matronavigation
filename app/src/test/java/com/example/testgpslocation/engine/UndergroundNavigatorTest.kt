package com.example.testgpslocation.engine

import com.example.testgpslocation.models.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Simulates a metro ride on a straight north-bound line with stations every ~1.1 km:
 * A (12.90) B (12.91) C (12.92) D (12.93) E (12.94).
 */
class UndergroundNavigatorTest {

    private lateinit var nav: UndergroundNavigator
    private lateinit var route: MetroRoute
    private var clock = 0L

    @Before
    fun setUp() {
        route = MetroRoute(
            "test", "Test Line", "A", "E",
            (0..40).map { TrackPoint(12.90 + it * 0.001, 77.0) },
            listOf("A", "B", "C", "D", "E").mapIndexed { i, n -> MetroStation(n, 12.90 + i * 0.01, 77.0) }
        )
        nav = UndergroundNavigator()
        clock = 1_000_000L
    }

    private fun fix(lat: Double, speed: Float = 10f, bearing: Float = 0f) {
        clock += 1000
        nav.onLocationFix(GpsPoint(lat, 77.0, 5f, clock, speed, bearing, hasSpeed = true, hasBearing = speed > 0f, elapsedRealtimeMs = clock), clock)
    }

    private fun run(state: MotionState, seconds: Double, accelNorth: Double = 0.0) {
        var still = 0L
        repeat((seconds * 10).toInt()) {
            clock += 100
            still = if (state == MotionState.STATIONARY) still + 100 else 0
            nav.onMotion(MotionSample(clock, 0.1, state, still, 0.0, accelNorth))
        }
    }

    /** GPS while riding north out of A, then the tunnel starts. */
    private fun enterTunnelMovingNorth() {
        nav.setRoute(route)
        fix(12.90000); fix(12.90009); fix(12.90018)
        nav.onGpsLost(clock)
    }

    @Test
    fun locksToTrackAndDirectionFromGpsBearing() {
        enterTunnelMovingNorth()
        val s = nav.state()
        assertTrue(s.onTrack)
        assertTrue(s.directionKnown)
        assertEquals("B", s.nextStation)
        assertEquals(PositionSource.TRACK, s.source)
        assertFalse(s.gpsAvailable)
    }

    @Test
    fun advancesAlongTrackWithoutGps() {
        enterTunnelMovingNorth()
        run(MotionState.MOVING, 30.0)
        val s = nav.state()
        assertEquals(77.0, s.longitude, 1e-9) // constrained to the track
        assertTrue("moved north: ${s.latitude}", s.latitude > 12.9025)
        assertTrue(s.distanceSinceGpsLossMeters in 250.0..400.0)
    }

    @Test
    fun stopSnapsToNearestStationAhead() {
        enterTunnelMovingNorth()
        run(MotionState.MOVING, 80.0) // ~880 m, a bit short of B
        run(MotionState.STATIONARY, 6.0)
        val s = nav.state()
        assertEquals("B", s.previousStation)
        assertEquals("C", s.nextStation)
        assertEquals(12.91, s.latitude, 1e-4)
        assertEquals(1, s.stationStopsDetected)
        assertTrue(nav.events.any { it.contains("Stopped at B") })
    }

    @Test
    fun estimateIsHeldAtNextStationUntilTheTrainStops() {
        enterTunnelMovingNorth()
        run(MotionState.MOVING, 115.0) // would overshoot B by ~100 m
        val held = nav.state()
        assertEquals("B", held.nextStation)
        assertTrue(held.distanceToNextMeters!! < 10.0)
        run(MotionState.STATIONARY, 6.0)
        assertEquals("B", nav.state().previousStation)
    }

    @Test
    fun skippedStationIsReleasedAfterLargeOvershoot() {
        enterTunnelMovingNorth()
        run(MotionState.MOVING, 170.0) // far beyond B without stopping
        val s = nav.state()
        assertEquals("C", s.nextStation)
        assertTrue(nav.events.any { it.contains("Passed B") })
    }

    @Test
    fun walkingOnThePlatformDoesNotMoveTheTrain() {
        enterTunnelMovingNorth()
        run(MotionState.MOVING, 90.0)
        run(MotionState.STATIONARY, 10.0) // stopped at a station
        val before = nav.state().latitude
        run(MotionState.WALKING, 60.0)
        assertEquals(before, nav.state().latitude, 1e-9)
    }

    @Test
    fun walkingInsideAMovingTrainKeepsItGoing() {
        enterTunnelMovingNorth()
        val before = nav.state().latitude
        val speed = nav.state().speedMps
        run(MotionState.WALKING, 10.0)
        val moved = (nav.state().latitude - before) * 111_195.0
        assertEquals("kept its speed", speed, nav.state().speedMps, 1e-9)
        assertTrue("moved $moved m", moved > 0.8 * speed * 10.0)
    }

    @Test
    fun departureAccelerationSetsDirectionWhenUnknown() {
        nav.setRoute(route)
        fix(12.92, speed = 0f) // standing at C, no bearing information
        nav.onGpsLost(clock)
        assertFalse(nav.state().directionKnown)

        run(MotionState.STATIONARY, 3.0)
        run(MotionState.MOVING, 5.0, accelNorth = -0.8) // pulling out southbound
        val s = nav.state()
        assertTrue(s.directionKnown)
        assertEquals("B", s.nextStation)
        assertTrue(s.latitude < 12.92)
    }

    @Test
    fun destinationSetsDirectionAndCountsStops() {
        nav.setRoute(route)
        fix(12.92, speed = 0f)
        nav.onGpsLost(clock)
        nav.setDestination(0) // A, southbound
        val s = nav.state()
        assertEquals("A", s.destination)
        assertEquals(2, s.stopsRemaining)
        assertEquals("B", s.nextStation)
    }

    @Test
    fun deadReckoningWithoutRoute() {
        fix(12.90); fix(12.90009)
        nav.onGpsLost(clock)
        run(MotionState.MOVING, 10.0)
        val s = nav.state()
        assertEquals(PositionSource.DEAD_RECKONING, s.source)
        assertTrue(s.latitude > 12.90009)
    }

    @Test
    fun recoveryDriftIsMeasuredWhenGpsReturns() {
        enterTunnelMovingNorth()
        run(MotionState.MOVING, 30.0)
        val estimate = nav.state().latitude
        clock += 1000
        nav.onLocationFix(GpsPoint(estimate + 0.0005, 77.0, 5f, clock, 10f, 0f, hasSpeed = true, hasBearing = true, elapsedRealtimeMs = clock), clock)
        val s = nav.state()
        assertTrue(s.gpsAvailable)
        assertNotNull(s.lastRecoveryDriftMeters)
        assertEquals(55.6, s.lastRecoveryDriftMeters!!, 2.0)
    }

    @Test
    fun implausibleGpsJumpIsRejected() {
        nav.setRoute(route)
        fix(12.90)
        fix(12.95) // 5.5 km in one second
        assertEquals(12.90, nav.state().latitude, 1e-6)
    }
}
