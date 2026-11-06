package com.example.testgpslocation.engine

import com.example.testgpslocation.models.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

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

    /** Driving north with GPS (vibration 0.40) for 15 s, then GPS lost while still driving for 10 s. */
    private fun driveNorthThenLoseGps() {
        repeat(15) { k -> gnss(12.90 + k * 10 / mPerDeg, speed = 10f, dtMs = 100); repeat(9) { vibrating(0.40) } }
        nav.onGpsLost(clock)
        repeat(100) { vibrating(0.40) }
        assertTrue("still driving", nav.state().speedMps > 5.0)
    }

    /** One motion sample the phone's fixed still rule calls "moving" (orientation known). */
    private fun vibrating(vibration: Double, accelNorth: Double = 0.0) {
        clock += 100
        nav.onMotion(MotionSample(clock, 0.1, MotionState.MOVING, 0, 0.0, accelNorth, orientationKnown = true, vibration = vibration))
    }

    @Test
    fun anIdlingEngineStopIsSeenFromTheVibrationDrop() {
        // Nothing learnt or stored: at the light the engine idles (vibration 0.10), which the
        // phone's fixed rule calls "moving"; it is a clear drop from this ride's 0.40.
        driveNorthThenLoseGps()
        repeat(50) { vibrating(0.40, accelNorth = -2.0) } // braking: 10 m/s to 0 in 5 s
        repeat(30) { vibrating(0.10) }
        val stopLat = nav.currentLat
        repeat(80) { vibrating(0.10) }
        assertEquals(0.0, nav.state().speedMps, 1e-9)
        assertEquals(stopLat, nav.currentLat, 1e-9)
    }

    @Test
    fun aQuietStretchWithoutBrakingIsNotTakenForAStop() {
        // Smooth road: the vibration drops as much as at a stop, but nothing braked.
        driveNorthThenLoseGps()
        val before = nav.currentLat
        repeat(200) { vibrating(0.10) }
        assertTrue("speed ${nav.state().speedMps}", nav.state().speedMps > 5.0)
        assertTrue("moved", (nav.currentLat - before) * mPerDeg > 100.0)
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

    /** Standing, phone still: fixes jump about by ±3 m and report about 0.1 m/s, as real ones do. */
    private fun standWithGps(fixes: Int, lat: Double, lon: Double, phone: () -> Unit) = repeat(fixes) { k ->
        phone()
        val noise = (if (k % 2 == 0) 3.0 else -3.0) / mPerDeg
        gnss(lat + noise, lon = lon - noise, speed = 0.12f, dtMs = 100)
    }

    private fun driveThenStop(): Pair<Double, Double> {
        repeat(30) { k -> repeat(9) { vibrating(0.40) }; gnss(12.90 + k * 10 / mPerDeg, speed = 10f, dtMs = 100) }
        return nav.currentLat to nav.currentLon
    }

    @Test
    fun gpsNoiseDoesNotMoveAStandingVehicle() {
        val (lat, lon) = driveThenStop()
        run(MotionState.STATIONARY, 2.0)
        standWithGps(20, lat, lon) { run(MotionState.STATIONARY, 0.9) }
        assertEquals(lat, nav.currentLat, 1e-9)
        assertEquals(lon, nav.currentLon, 1e-9)
        assertEquals(PositionSource.GPS, nav.state().source)
    }

    @Test
    fun gpsNoiseAlsoStaysOutWhenAirConKeepsThePhoneReadingMoving() {
        val (lat, lon) = driveThenStop()
        repeat(20) { vibrating(0.05) } // standing: well below the running level of 0.40
        standWithGps(20, lat, lon) { repeat(9) { vibrating(0.05) } }
        assertEquals(lat, nav.currentLat, 1e-9)
        assertEquals(lon, nav.currentLon, 1e-9)
    }

    @Test
    fun aStandingVehicleFollowsGpsAgainTheMomentItMoves() {
        val (lat, lon) = driveThenStop()
        run(MotionState.STATIONARY, 2.0)
        standWithGps(5, lat, lon) { run(MotionState.STATIONARY, 0.9) }
        run(MotionState.MOVING, 0.9)
        val movedLat = lat + 2.0 / mPerDeg
        gnss(movedLat, lon = lon, speed = 1.5f, dtMs = 100)
        assertEquals(movedLat, nav.currentLat, 1e-9)
    }

    @Test
    fun aFixTooFarAwayToBeNoiseIsTakenEvenWhileStanding() {
        val (lat, lon) = driveThenStop()
        run(MotionState.STATIONARY, 2.0)
        val farLat = lat + 40.0 / mPerDeg
        gnss(farLat, lon = lon, speed = 0.1f, dtMs = 100)
        assertEquals(farLat, nav.currentLat, 1e-9)
    }

    @Test
    fun aMuchBetterFixReplacesAPoorPositionWhileStanding() {
        run(MotionState.STATIONARY, 2.0)
        gnss(12.90, speed = 0.1f, accuracy = 30f)
        val betterLat = 12.90 + 8.0 / mPerDeg
        gnss(betterLat, speed = 0.1f, accuracy = 4f)
        assertEquals(betterLat, nav.currentLat, 1e-9)
    }

    @Test
    fun aSlowVehicleIsFollowedWhileThePhoneFeelsItMoving() {
        run(MotionState.MOVING, 2.0)
        repeat(6) { i ->
            val lat = 12.90 + i * 0.4 / mPerDeg
            run(MotionState.MOVING, 0.9)
            gnss(lat, speed = 0.4f, dtMs = 100)
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
        clock += 3000 // watchdog fires 3 s after the last fix
        nav.onGpsLost(clock)
        val advanced = (nav.state().latitude - lastFixLat) * mPerDeg
        assertEquals(60.0, advanced, 5.0) // 20 m/s * 3 s
    }

    @Test
    fun stepsInATrainDoNotMoveIt() {
        nav.setRoute(route)
        gnss(12.90000); gnss(12.90009); gnss(12.90018, speed = 6f) // a train, not a walker
        gnss(12.90018, speed = 0f); gnss(12.90018, speed = 0f)
        nav.onGpsLost(clock)
        val startLat = nav.currentLat
        repeat(300) { k ->
            clock += 100
            nav.onMotion(MotionSample(clock, 0.1, MotionState.WALKING, 0, 0.0, 0.0, steps = if (k % 6 == 0) 1 else 0))
        }
        assertEquals(startLat, nav.currentLat, 1e-9)
    }

    /** One 100 ms walking sample, a step every 600 ms. */
    private fun stepping(k: Int) {
        clock += 100
        nav.onMotion(MotionSample(clock, 0.1, MotionState.WALKING, 0, 0.0, 0.0, orientationKnown = true, yawRateDps = 0.0,
            vibration = 1.5, steps = if (k % 6 == 0) 1 else 0))
    }

    @Test
    fun aWalkerBesideALineIsNotPulledOntoIt() {
        nav.setRoute(route)
        val lon = 77.0015 // about 160 m from the line: inside the radius a train is locked from
        var lat = 12.90
        repeat(30) { s ->
            repeat(10) { k -> stepping(s * 10 + k) }
            lat += 1.0 / mPerDeg
            nav.onLocationFix(GpsPoint(lat, lon, 5f, clock, 1f, 0f, hasSpeed = true, hasBearing = true, elapsedRealtimeMs = clock, isGnss = true), clock)
        }
        nav.onGpsLost(clock)
        val startLat = nav.currentLat
        repeat(300) { k -> stepping(k) }
        assertFalse(nav.state().onTrack)
        assertEquals(lon, nav.currentLon, 1e-5)
        assertTrue("walked ${(nav.currentLat - startLat) * mPerDeg} m", (nav.currentLat - startLat) * mPerDeg in 25.0..35.0)
    }

    @Test
    fun aWalkerLockedToALineLeavesItAfterTenSteps() {
        nav.setRoute(route)
        gnss(12.90000, speed = 1f); gnss(12.900009, speed = 1f)
        nav.onGpsLost(clock)
        assertTrue(nav.state().onTrack)
        repeat(100) { k -> stepping(k) }
        assertFalse(nav.state().onTrack)
    }

    /** Riding north through the tunnel at [speed], the phone never reading still, the barometer at [height]. */
    private fun riding(seconds: Double, speed: Double = 0.0, height: (Int) -> Double) {
        repeat((seconds * 10).toInt()) { k ->
            clock += 100
            nav.onMotion(MotionSample(clock, 0.1, MotionState.MOVING, 0, 0.0, 0.0, orientationKnown = true, yawRateDps = 0.0,
                vibration = 0.15, heightMeters = height(k)))
        }
    }

    private fun cellFix(lat: Double, accuracy: Float) = nav.onLocationFix(GpsPoint(lat, 77.0, accuracy, clock, elapsedRealtimeMs = clock, isGnss = false), clock)

    @Test
    fun aMobileCellFixCannotThrowTheEstimateFar() {
        rideIntoTunnel()
        run(MotionState.MOVING, 10.0)
        val before = nav.currentLat
        clock += 1000
        cellFix(before + 1500 / mPerDeg, 100f) // claims ±100 m, like the real ones that were 1.2 km off
        assertTrue("moved ${(nav.currentLat - before) * mPerDeg} m", abs((nav.currentLat - before) * mPerDeg) < 50.0)
    }

    @Test
    fun anOldFixRepeatedSaysNothingNew() {
        rideIntoTunnel()
        run(MotionState.MOVING, 10.0)
        val lat = nav.currentLat + 100 / mPerDeg
        cellFix(lat, 40f)
        clock += 5000
        assertEquals("ignored: repeat of the last fix", cellFix(lat, 240f))
    }

    @Test
    fun goodFixesThatAgreeCorrectTheLine() {
        rideIntoTunnel()
        run(MotionState.MOVING, 10.0)
        val truth = nav.currentLat + 1500 / mPerDeg // the estimate has fallen far behind
        assertEquals("ignored: too far from the estimate", cellFix(truth, 60f))
        clock += 5000
        assertEquals("ignored: too far from the estimate", cellFix(truth + 0.00002, 45f))
        clock += 5000
        assertEquals("used: moved to it", cellFix(truth + 0.00004, 50f))
        assertTrue("off by ${abs(nav.currentLat - truth) * mPerDeg} m", abs(nav.currentLat - truth) * mPerDeg < 20.0)
    }

    @Test
    fun cellFixesThatAgreeDoNotCorrectTheLine() {
        rideIntoTunnel()
        run(MotionState.MOVING, 10.0)
        val before = nav.currentLat
        val wrong = before + 1800 / mPerDeg
        repeat(4) { k -> clock += 5000; cellFix(wrong + k * 0.00002, 100f) }
        assertTrue("moved to the cell", abs(nav.currentLat - wrong) * mPerDeg > 1000.0)
    }

    private fun standingAtStationBThenLeaving() {
        nav.setRoute(route)
        gnss(12.90000); gnss(12.90009); gnss(12.90018)
        nav.onGpsLost(clock)
        val b = route.stations[1]
        var h = 0.0
        while (nav.state().nextStation != b.name || abs(nav.currentLat - 12.91) * mPerDeg > 100.0) riding(1.0) { h += 0.05; h }
    }

    @Test
    fun aSteadyPressureNearAStationIsAStopEvenInTheHand() {
        standingAtStationBThenLeaving()
        riding(15.0) { 2.0 }
        assertTrue("events ${nav.events}", nav.events.any { it == "Stopped at B" })
        assertEquals(0.0, nav.state().speedMps, 1e-9)
    }

    @Test
    fun aStepInThePressureIsNotADeparture() {
        standingAtStationBThenLeaving()
        riding(15.0) { 2.0 }
        riding(15.0) { 3.4 } // doors: one step, then steady again
        assertEquals(0.0, nav.state().speedMps, 1e-9)
    }

    @Test
    fun aPressureThatKeepsChangingIsADeparture() {
        standingAtStationBThenLeaving()
        riding(15.0) { 2.0 }
        riding(12.0) { k -> 2.0 + k * 0.03 }
        assertTrue("events ${nav.events}", nav.events.any { it == "Departure seen from the pressure" })
        assertTrue(nav.state().speedMps > 3.0)
    }

    @Test
    fun aSteadyPressureFarFromAnyStationIsNotAStop() {
        nav.setRoute(route)
        gnss(12.90400, speed = 10f); gnss(12.90409, speed = 10f); gnss(12.90418, speed = 10f) // midway A to B
        nav.onGpsLost(clock)
        riding(12.0) { 2.0 }
        assertTrue("speed ${nav.state().speedMps}", nav.state().speedMps > 5.0)
    }

    @Test
    fun aGpsJumpAtAStopDoesNotTurnTheDirectionRound() {
        nav.setRoute(route)
        gnss(12.90000); gnss(12.90009); gnss(12.90018)
        val next = nav.state().nextStation
        gnss(12.90018, speed = 0f); gnss(12.90018 - 30 / mPerDeg, speed = 0f) // reflected fix, 30 m back
        assertEquals(next, nav.state().nextStation)
    }

    @Test
    fun onlyPoorGpsCountsAsLostAfterThreeSeconds() {
        gnss(12.90000); gnss(12.90009)
        gnss(12.90018, accuracy = 80f, dtMs = 2500)
        assertTrue("2.5 s of poor GPS is not lost yet", nav.state().gpsAvailable)
        gnss(12.90027, accuracy = 80f, dtMs = 1000)
        assertFalse("3.5 s of poor GPS is lost", nav.state().gpsAvailable)
    }

    @Test
    fun gpsAltitudeIsReportedOnlyWhileGpsWorks() {
        clock += 1000
        nav.onLocationFix(GpsPoint(12.90, 77.0, 5f, clock, 10f, 0f, hasSpeed = true, hasBearing = true,
            elapsedRealtimeMs = clock, isGnss = true, altitudeMeters = 912.5), clock)
        assertEquals(912.5, nav.state().gpsAltitudeMeters!!, 0.001)
        nav.onGpsLost(clock + 3000)
        assertEquals(null, nav.state().gpsAltitudeMeters)
    }

    @Test
    fun headingIsKnownOnlyOnceMeasured() {
        gnss(12.90000, speed = 0f)
        assertFalse("standing, no course yet", nav.state().headingKnown)
        gnss(12.90009, speed = 10f, bearing = 45f)
        assertTrue(nav.state().headingKnown)
        assertEquals(45.0, nav.state().headingDeg, 0.001)
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
