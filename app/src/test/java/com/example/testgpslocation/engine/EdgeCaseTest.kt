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
    fun stopsAreSeenWithTheStillnessLearntFromGps() {
        // With GPS: 10 s standing (idling engine: vibration 0.10, which the fixed rule calls
        // "moving"), then 10 s driving north at 10 m/s (vibration 0.40). One fix per second.
        fun second(lat: Double, speed: Float, vibration: Double) {
            gnss(lat, speed = speed, dtMs = 100)
            repeat(9) { clock += 100; nav.onMotion(MotionSample(clock, 0.1, MotionState.MOVING, 0, 0.0, 0.0, vibration = vibration)) }
        }
        repeat(10) { second(12.90, 0f, 0.10) }
        repeat(10) { k -> second(12.90 + (k + 1) * 10 / mPerDeg, 10f, 0.40) }
        assertEquals(0.2, nav.rideMemory.vibration.stillThreshold!!, 0.01)

        fun drive(seconds: Double, vibration: Double) = repeat((seconds * 10).toInt()) {
            clock += 100; nav.onMotion(MotionSample(clock, 0.1, MotionState.MOVING, 0, 0.0, 0.0, vibration = vibration))
        }
        nav.onGpsLost(clock)
        drive(5.0, 0.40)
        assertTrue("still driving", nav.state().speedMps > 5.0)
        // Stopped at a light, engine idling: the fixed rule still says "moving", the learnt one does not.
        drive(3.0, 0.10)
        val stopLat = nav.currentLat
        drive(8.0, 0.10)
        assertEquals(0.0, nav.state().speedMps, 1e-9)
        assertEquals(stopLat, nav.currentLat, 1e-9)
    }

    @Test
    fun aPullAwayAfterIdlingIsStillSeenWithoutALine() {
        // Driving east with GPS, then GPS lost and a stop at a traffic light (no line saved).
        repeat(5) { k -> gnss(12.90, lon = 77.0 + k * 10 / 108_000.0, speed = 10f, bearing = 90f) }
        nav.onGpsLost(clock)
        run(MotionState.STATIONARY, 10.0)
        val stopLon = nav.currentLon
        val stopUncertainty = nav.state().uncertaintyMeters
        // Engine vibration at the light reads as "moving" without any acceleration: no departure yet...
        run(MotionState.MOVING, 9.0)
        assertEquals(stopLon, nav.currentLon, 1e-9)
        // ...but the error grows, since the vehicle might be moving unseen.
        assertTrue("uncertainty ${nav.state().uncertaintyMeters}", nav.state().uncertaintyMeters > stopUncertainty + 10.0)
        // Then it really pulls away: still seen after the first window was used up by the idling.
        run(MotionState.MOVING, 6.0, accelEast = 1.2)
        run(MotionState.MOVING, 10.0)
        val movedEast = (nav.currentLon - stopLon) * 108_000.0
        assertTrue("moved $movedEast m east", movedEast > 20.0)
    }

    @Test
    fun everyGpsFixMovesThePositionEvenWithoutSpeed() {
        // Some phones report no speed with their fixes: each fix must still be the position
        // (it used to stay frozen until a fix was 15 m away).
        repeat(6) { i ->
            val lat = 12.90 + i * 3.0 / mPerDeg
            clock += 1000
            nav.onLocationFix(GpsPoint(lat, 77.0, 5f, clock, elapsedRealtimeMs = clock, isGnss = true), clock)
            assertEquals("fix $i", lat, nav.currentLat, 1e-9)
        }
    }

    @Test
    fun slowGpsMovementIsFollowedFixByFix() {
        repeat(6) { i ->
            val lat = 12.90 + i * 2.0 / mPerDeg
            gnss(lat, speed = 0.3f, bearing = 0f)
            assertEquals("fix $i", lat, nav.currentLat, 1e-9)
        }
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
