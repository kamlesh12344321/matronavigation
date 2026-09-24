package com.example.testgpslocation.engine

import com.example.testgpslocation.models.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** GPS / network / sensor edge cases on a straight north-bound line A..E (stations every ~1.1 km). */
class EdgeCaseTest {

    private lateinit var nav: UndergroundNavigator
    private lateinit var route: MetroRoute
    private var clock = 0L
    private val mPerDeg = 111_195.0

    @Before
    fun setUp() {
        route = line("north", 77.0, 12.90)
        nav = UndergroundNavigator()
        clock = 1_000_000L
    }

    private fun line(id: String, lon: Double, startLat: Double, names: List<String> = listOf("A", "B", "C", "D", "E")) = MetroRoute(
        id, "Line $id", names.first(), names.last(),
        (0..40).map { TrackPoint(startLat + it * 0.001, lon) },
        names.mapIndexed { i, n -> MetroStation(n, startLat + i * 0.01, lon) }
    )

    private fun gnss(lat: Double, lon: Double = 77.0, speed: Float = 10f, accuracy: Float = 5f, bearing: Float = 0f, dtMs: Long = 1000) {
        clock += dtMs
        nav.onLocationFix(GpsPoint(lat, lon, accuracy, clock, speed, bearing, hasSpeed = true, hasBearing = speed > 0f,
            elapsedRealtimeMs = clock, isGnss = true), clock)
    }

    private fun network(lat: Double, lon: Double = 77.0, accuracy: Float = 80f) {
        clock += 1000
        nav.onLocationFix(GpsPoint(lat, lon, accuracy, clock, elapsedRealtimeMs = clock, isGnss = false), clock)
    }

    private fun run(state: MotionState, seconds: Double, accelNorth: Double = 0.0, accelEast: Double = 0.0) {
        var still = 0L
        repeat((seconds * 10).toInt()) {
            clock += 100
            still = if (state == MotionState.STATIONARY) still + 100 else 0
            nav.onMotion(MotionSample(clock, 0.1, state, still, accelEast, accelNorth))
        }
    }

    private fun rideIntoTunnel() {
        nav.setRoute(route)
        gnss(12.90000); gnss(12.90009); gnss(12.90018)
        nav.onGpsLost(clock)
    }

    @Test
    fun invalidFixesAreIgnored() {
        clock += 1000
        nav.onLocationFix(GpsPoint(0.0, 0.0, 5f, clock, elapsedRealtimeMs = clock), clock)
        nav.onLocationFix(GpsPoint(Double.NaN, 77.0, 5f, clock, elapsedRealtimeMs = clock), clock)
        nav.onLocationFix(GpsPoint(12.9, 77.0, 0f, clock, elapsedRealtimeMs = clock), clock)
        nav.onLocationFix(GpsPoint(12.9, 77.0, 5000f, clock, elapsedRealtimeMs = clock), clock)
        assertEquals(PositionSource.NONE, nav.state().source)
    }

    @Test
    fun networkFixIsIgnoredWhileGpsIsGood() {
        nav.setRoute(route)
        gnss(12.90)
        network(12.905) // 550 m away, would be a big jump
        assertEquals(12.90, nav.state().latitude, 1e-6)
        assertEquals(PositionSource.GPS, nav.state().source)
    }

    @Test
    fun networkLocationCorrectsTrackEstimateUnderground() {
        rideIntoTunnel()
        run(MotionState.MOVING, 40.0) // estimate ~ 470 m
        val before = nav.state()
        val truthLat = 12.90 + 600 / mPerDeg // the train is really at ~600 m
        repeat(5) { network(truthLat, accuracy = 60f) }
        val after = nav.state()
        val errBefore = kotlin.math.abs(before.latitude - truthLat) * mPerDeg
        val errAfter = kotlin.math.abs(after.latitude - truthLat) * mPerDeg
        assertTrue("network should reduce error: $errBefore -> $errAfter", errAfter < errBefore * 0.5)
        assertTrue(after.networkCorrections > 0)
        assertTrue(after.uncertaintyMeters < before.uncertaintyMeters)
        assertEquals(77.0, after.longitude, 1e-9) // still on the track
    }

    @Test
    fun wildNetworkOutlierIsRejected() {
        rideIntoTunnel()
        run(MotionState.MOVING, 20.0)
        val before = nav.state().latitude
        network(12.935, accuracy = 30f) // ~3.8 km ahead with a confident accuracy: impossible
        assertEquals(before, nav.state().latitude, 1e-9)
    }

    @Test
    fun networkFixLocksOntoLineWhenGpsWasNeverAvailable() {
        nav.setRoute(route)
        network(12.9203, 77.0003, accuracy = 50f) // first ever location: network, near station C
        val s = nav.state()
        assertTrue(s.onTrack)
        assertFalse(s.gpsAvailable)
    }

    @Test
    fun gapBetweenLastFixAndLossDetectionIsDeadReckoned() {
        nav.setRoute(route)
        gnss(12.90000, speed = 20f); gnss(12.90018, speed = 20f)
        val lastFixLat = 12.90018
        clock += 6000 // watchdog fires 6 s after the last fix
        nav.onGpsLost(clock)
        val advanced = (nav.state().latitude - lastFixLat) * mPerDeg
        assertEquals(120.0, advanced, 5.0) // 20 m/s * 6 s
    }

    @Test
    fun poorFixAfterOutageNeedsConfirmation() {
        rideIntoTunnel()
        run(MotionState.MOVING, 20.0)
        val est = nav.state().latitude
        gnss(est + 0.0002, accuracy = 40f) // first fix after the tunnel, mediocre accuracy
        assertFalse("one mediocre fix must not declare GPS back", nav.state().gpsAvailable)
        gnss(est + 0.0003, accuracy = 35f) // consistent second fix
        assertTrue(nav.state().gpsAvailable)
    }

    @Test
    fun repeatedConsistentJumpsAreEventuallyAccepted() {
        gnss(12.90, speed = 0f)
        gnss(13.50, speed = 0f); gnss(13.50, speed = 0f)
        assertEquals(12.90, nav.state().latitude, 1e-6)
        gnss(13.50, speed = 0f) // third consistent "jump": the old position was wrong
        assertEquals(13.50, nav.state().latitude, 1e-6)
    }

    @Test
    fun cruiseSpeedIsLearntFromStationToStationTime() {
        nav.setRoute(route)
        gnss(12.90, speed = 0f)
        nav.onGpsLost(clock)
        nav.setDestination(4) // E: northbound
        run(MotionState.STATIONARY, 6.0) // at A
        val before = nav.state().cruiseSpeedMps
        run(MotionState.MOVING, 60.0) // real average 1112 m / 60 s = 18.5 m/s, model is slower
        run(MotionState.STATIONARY, 6.0) // stop at B
        val after = nav.state().cruiseSpeedMps
        assertEquals("B", nav.state().previousStation)
        assertTrue("cruise $before -> $after", after > before + 1.0)
    }

    @Test
    fun interchangePicksTheLineMatchingDepartureDirection() {
        // North-south line (current) and an east-west line crossing at C (12.92, 77.0).
        val eastWest = MetroRoute(
            "ew", "East West Line", "W", "E2",
            (0..40).map { TrackPoint(12.92, 76.98 + it * 0.001) },
            listOf("W" to 76.98, "C-X" to 77.0, "E2" to 77.02).map { (n, lon) -> MetroStation(n, 12.92, lon) }
        )
        nav.setRoute(route)
        nav.setAlternativeRoutes(listOf(eastWest))
        gnss(12.92, speed = 0f)
        nav.onGpsLost(clock)
        run(MotionState.STATIONARY, 3.0)
        run(MotionState.MOVING, 5.0, accelEast = 0.9) // pulls out eastwards
        val s = nav.state()
        assertEquals("East West Line", s.lineName)
        assertTrue(s.directionKnown)
        assertEquals("E2", s.nextStation)
    }

    @Test
    fun noLineUnderNetworkBlendsFreePosition() {
        gnss(12.90); gnss(12.90009)
        nav.onGpsLost(clock)
        run(MotionState.MOVING, 30.0)
        val drifted = nav.state()
        repeat(4) { network(12.9001, accuracy = 50f) }
        val corrected = nav.state()
        assertTrue(kotlin.math.abs(corrected.latitude - 12.9001) < kotlin.math.abs(drifted.latitude - 12.9001))
    }

    @Test
    fun uncertaintyGrowsUndergroundAndResetsAtStations() {
        rideIntoTunnel()
        val start = nav.state().uncertaintyMeters
        run(MotionState.MOVING, 80.0)
        val grown = nav.state().uncertaintyMeters
        run(MotionState.STATIONARY, 6.0)
        val reset = nav.state().uncertaintyMeters
        assertTrue(grown > start)
        assertTrue(reset <= UndergroundNavigator.STATION_SNAP_UNCERTAINTY_METERS)
    }

    @Test
    fun coldStartWithoutSatellitesDoesNotInventMotion() {
        network(12.95, accuracy = 40f) // first location is network-based, no satellite fix yet
        clock += 6000
        nav.onGpsLost(clock) // watchdog: no satellite fix since start
        run(MotionState.MOVING, 30.0) // phone jiggling on a desk
        assertEquals(12.95, nav.state().latitude, 1e-9)
        assertEquals(0.0, nav.state().speedMps, 1e-9)
    }

    @Test
    fun freeDeadReckoningUsesLastGpsSpeedNotMetroCruise() {
        gnss(12.90, speed = 1.5f); gnss(12.900013, speed = 1.5f) // walking pace
        nav.onGpsLost(clock)
        run(MotionState.MOVING, 20.0)
        val moved = (nav.state().latitude - 12.900013) * mPerDeg
        assertTrue("moved $moved m", moved in 5.0..40.0) // ~1.5 m/s fading, not 12 m/s
    }

    @Test
    fun mockLocationsAreFlagged() {
        clock += 1000
        nav.onLocationFix(GpsPoint(12.9, 77.0, 5f, clock, elapsedRealtimeMs = clock, isGnss = true, isMock = true), clock)
        assertTrue(nav.state().mockLocation)
    }
}
