package com.example.testgpslocation.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Long-tunnel scenarios with NO satellites and NO network underground (see [TunnelSimulator]):
 * position (lat/lon error in metres against the true position), direction, speed and the
 * stationary / moving decision.
 */
class UndergroundScenarioTest {

    private val allStops = TunnelWorld.names.drop(1)

    private fun ride(name: String, cfg: SimConfig): SimResult =
        TunnelSimulator(cfg).run().also { println(it.summary(name)) }

    // ---- Long tunnel, position and speed --------------------------------------------------------

    @Test
    fun longCurvedTunnel20kmWithoutSatellitesOrNetwork() {
        val r = ride("20km-tunnel", SimConfig())
        assertEquals(allStops, r.stationsAtStops)
        assertTrue("stop errors ${r.stopErrors}", r.stopErrors.all { it < 5.0 })
        assertTrue("max moving error ${r.maxMovingError}", r.maxMovingError < 400.0)
        assertTrue("mean moving error ${r.meanMovingError}", r.meanMovingError < 150.0)
        assertTrue("speed RMS ${r.speedRmsError}", r.speedRmsError < 5.0)
        assertTrue("still at dwell ${r.zeroSpeedDwellFraction}", r.zeroSpeedDwellFraction > 0.98)
        assertEquals(r.stopsMeasured, r.directionCorrectAtStops)
    }

    @Test
    fun positionKeepsMovingWhileTheTrainMoves() {
        // A metro train stops for at most ~30 s, and between stops it moves: the estimate must not
        // repeat one position while it moves (it used to wait up to 46 s at a skipped station).
        val express = setOf(3, 5, 7, 9, 11, 13)
        // Third value: check the station sequence. Not on a first express ride with unknown run
        // speeds: there the odometer can come out ~10 % short and miss a station (needs tuning
        // with real rides; a looser rule would mistake signal stops before platforms for stations).
        val rides = listOf(
            Triple("normal", SimConfig(seed = 61), true),
            Triple("varied", SimConfig(segmentSpeeds = true, seed = 62), true),
            Triple("express", SimConfig(skipStations = express, seed = 63), true),
            Triple("varied-express", SimConfig(segmentSpeeds = true, skipStations = express, seed = 64), false),
            Triple("false-stills", SimConfig(falseStillEverySec = 20.0, seed = 65), true)
        )
        for ((name, cfg, checkStations) in rides) {
            val r = ride("keeps-moving-$name", cfg)
            assertTrue("$name repeats ${r.longestRepeatWhileMovingSec} s while moving", r.longestRepeatWhileMovingSec < 20.0)
            if (!checkStations) continue
            assertEquals(name, allStops.filter { it.removePrefix("S").toInt() !in cfg.skipStations }, r.stationsAtStops)
            if (cfg.skipStations.isNotEmpty()) {
                // Running through a skipped station no longer leaves the estimate behind.
                assertTrue("$name mean ${r.meanMovingError}", r.meanMovingError < 45.0)
                assertTrue("$name max ${r.maxMovingError}", r.maxMovingError < 350.0)
            }
        }
    }

    @Test
    fun slowAndFastTrains() {
        for (cruise in listOf(12.0, 30.0)) {
            val r = ride("cruise-$cruise", SimConfig(cruise = cruise, seed = 3))
            assertEquals("cruise $cruise", allStops, r.stationsAtStops)
            assertTrue("cruise $cruise stop errors ${r.stopErrors}", r.stopErrors.all { it < 5.0 })
        }
    }

    @Test
    fun manySeedsAndCompassErrorsKeepTheStationSequence() {
        repeat(12) { k ->
            val r = TunnelSimulator(SimConfig(seed = 500 + k, compassErrorDeg = (k * 13 % 70).toDouble(), accelNoise = 0.3 + k % 3 * 0.1)).run()
            assertEquals("seed $k", allStops, r.stationsAtStops)
        }
    }

    // ---- Direction ------------------------------------------------------------------------------

    @Test
    fun boardingUndergroundTravellingBackwardsFindsDirectionFromDeparture() {
        // One fix at the entrance of S12, then no GPS at all; the train goes towards S0.
        val r = ride("board-S12-southbound", SimConfig(startStation = 12, endStation = 4, gpsUntilMeters = null))
        assertTrue(r.events.any { it.startsWith("Departure detected") })
        assertEquals((11 downTo 4).map { "S$it" }, r.stationsAtStops)
        assertEquals(r.stopsMeasured, r.directionCorrectAtStops)
    }

    // ---- Stationary / moving decision -----------------------------------------------------------

    @Test
    fun smoothCruiseReadAsStillIsNotTakenForAStop() {
        val r = ride("false-stills", SimConfig(falseStillEverySec = 15.0, seed = 5))
        assertEquals(allStops, r.stationsAtStops)
        val stops = r.events.count { it.startsWith("Stopped at") }
        assertEquals("one stop per station: ${r.events.filter { it.startsWith("Stopped") }}", allStops.size, stops)
        assertTrue("max moving error ${r.maxMovingError}", r.maxMovingError < 400.0)
    }

    @Test
    fun withoutTheBrakingCheckFalseStillsWouldBreakTheRide() {
        // Same ride with the orientation-based checks disabled: shows what the checks buy.
        val checked = ride("false-stills-checked", SimConfig(falseStillEverySec = 15.0, seed = 5))
        val unchecked = ride("false-stills-unchecked", SimConfig(falseStillEverySec = 15.0, seed = 5, orientationKnown = false))
        assertTrue("checked ${checked.meanMovingError} vs unchecked ${unchecked.meanMovingError}",
            checked.meanMovingError < unchecked.meanMovingError)
    }

    @Test
    fun jigglingThePhoneAtThePlatformDoesNotMoveTheTrain() {
        val r = ride("jiggle", SimConfig(jiggleAtStations = true, seed = 9))
        assertEquals(allStops, r.stationsAtStops)
        assertTrue("dwell error ${r.dwellMaxError}", r.dwellMaxError < 10.0)
        assertTrue("still at dwell ${r.zeroSpeedDwellFraction}", r.zeroSpeedDwellFraction > 0.9)
    }

    @Test
    fun signalStopInTheTunnelIsNotTakenForAStation() {
        // Held at a signal 550 m before S3 for 40 s.
        val r = ride("signal-stop", SimConfig(signalStopAt = 3050.0, seed = 13))
        assertTrue(r.events.any { it.startsWith("Train stopped between stations") })
        assertEquals(allStops, r.stationsAtStops)
    }

    @Test
    fun expressSkippingStationsStaysOnSequence() {
        val r = ride("express", SimConfig(skipStations = setOf(3, 7, 11), seed = 17))
        assertEquals(allStops.filter { it !in setOf("S3", "S7", "S11") }, r.stationsAtStops)
        assertTrue("stop errors ${r.stopErrors}", r.stopErrors.all { it < 5.0 })
    }

    // ---- Realistic line: every run has its own speed ------------------------------------------------

    private val express = setOf(3, 5, 7, 9, 11, 13)

    @Test
    fun realisticLineWithDifferentSpeedPerRunKeepsEveryStation() {
        repeat(6) { k ->
            val r = ride("varied-speeds-$k", SimConfig(segmentSpeeds = true, seed = 600 + k))
            assertEquals("seed $k", allStops, r.stationsAtStops)
            assertTrue("seed $k speed RMS ${r.speedRmsError}", r.speedRmsError < 2.5)
            assertTrue("seed $k mean ${r.meanMovingError}", r.meanMovingError < 60.0)
        }
    }

    @Test
    fun phoneWithoutOrientationSensorKeepsEveryStationOnARealisticLine() {
        // No rotation vector: no inertial speed, odometer or braking checks — the simpler model
        // with honest (wider) uncertainty must still get every station right.
        repeat(4) { k ->
            val r = ride("no-orientation-$k", SimConfig(segmentSpeeds = true, orientationKnown = false, seed = 1200 + k))
            assertEquals("seed $k", allStops, r.stationsAtStops)
        }
    }

    @Test
    fun expressOnRealisticLineUsesTheOdometerAtStops() {
        repeat(4) { k ->
            val r = ride("varied-express-$k", SimConfig(segmentSpeeds = true, skipStations = express, seed = 700 + k))
            assertEquals("seed $k", allStops.filter { it.removePrefix("S").toInt() !in express }, r.stationsAtStops)
        }
    }

    @Test
    fun learntRunsAndWifiImproveExpressRidesOnARealisticLine() {
        val world = RadioWorld(TunnelWorld.route)
        val first = ride("varied-learn", SimConfig(segmentSpeeds = true, radio = world, seed = 801))
        // A run is learnt only when both of its stops were trustworthy snaps.
        assertTrue("runs learnt ${first.navigator.rideMemory.size}", first.navigator.rideMemory.size >= 12)
        val without = ride("varied-express-nothing", SimConfig(segmentSpeeds = true, skipStations = express, seed = 802))
        val with = ride("varied-express-learnt", SimConfig(segmentSpeeds = true, skipStations = express, radio = world,
            rideMemory = RideMemory.parse(first.navigator.rideMemory.serialize()), radioMap = RadioMap.parse(first.navigator.radioMap.serialize()), seed = 802))
        assertEquals(without.expectedStations, with.stationsAtStops)
        assertTrue("with ${with.meanMovingError} vs without ${without.meanMovingError}", with.meanMovingError < without.meanMovingError)
        // Station-only Wi-Fi cannot pin down a run through a skipped station (see above).
        assertTrue("max with ${with.maxMovingError} vs without ${without.maxMovingError}", with.maxMovingError < 1.2 * without.maxMovingError)
    }

    @Test
    fun secondRideWithEverythingLearntStaysAccurate() {
        val world = RadioWorld(TunnelWorld.route)
        repeat(3) { k ->
            val first = TunnelSimulator(SimConfig(segmentSpeeds = true, radio = world, seed = 900 + k)).run()
            val second = ride("second-ride-$k", SimConfig(segmentSpeeds = true, radio = world, seed = 950 + k,
                rideMemory = RideMemory.parse(first.navigator.rideMemory.serialize()), radioMap = RadioMap.parse(first.navigator.radioMap.serialize())))
            assertEquals("seed $k", allStops, second.stationsAtStops)
            assertTrue("seed $k mean ${second.meanMovingError}", second.meanMovingError < 50.0)
            assertTrue("seed $k max ${second.maxMovingError}", second.maxMovingError < 350.0)
        }
    }

    @Test
    fun curveMatchingNeverDisturbsAConsistentEstimate() {
        // With inertial speed the estimate already agrees with every curve: nothing to correct.
        val r = ride("gyro-curves", SimConfig(segmentSpeeds = true, seed = 1001))
        val noGyro = ride("no-gyro", SimConfig(segmentSpeeds = true, seed = 1001, gyro = false))
        assertEquals(allStops, r.stationsAtStops)
        assertEquals(allStops, noGyro.stationsAtStops)
        assertEquals(noGyro.meanMovingError, r.meanMovingError, 1.0)
    }

    @Test
    fun curveMatchingCorrectsAnEstimateThatContradictsTheTurn() {
        // No orientation data (no inertial speed): the model thinks 12 m/s, the train does 25 m/s
        // through the 45° curve at 6.0–6.8 km. The gyroscope's 45° turn cannot happen where the
        // model believes the train is, so the position moves to the nearest place the turn fits.
        val withGyro = curveRide(gyro = true)
        val withoutGyro = curveRide(gyro = false)
        assertTrue("curve corrections ${withGyro.second}", withGyro.second >= 1)
        assertEquals(0, withoutGyro.second)
        assertTrue("error with gyro ${withGyro.first} vs without ${withoutGyro.first}", withGyro.first < withoutGyro.first - 300.0)
    }

    /** Returns (final position error in metres, curve corrections). */
    private fun curveRide(gyro: Boolean): Pair<Double, Int> {
        val route = TunnelWorld.route
        val nav = UndergroundNavigator()
        nav.setRoute(route)
        var clock = 1_000_000L
        val start = route.pointAt(4900.0)
        clock += 1000
        nav.onLocationFix(com.example.testgpslocation.models.GpsPoint(start.lat, start.lon, 5f, clock, 0f, 0f, hasSpeed = true, elapsedRealtimeMs = clock), clock)
        clock += 6000
        nav.onGpsLost(clock)
        nav.setDestination(TunnelWorld.names.indexOf("S16"))
        var still = 0L
        repeat(80) { clock += 100; still += 100; nav.onMotion(MotionSample(clock, 0.1, MotionState.STATIONARY, still, 0.0, 0.0, yawRateDps = if (gyro) 0.0 else Double.NaN)) }
        assertEquals("S4", nav.state().previousStation)
        var s = 4900.0
        repeat(900) {
            clock += 100
            val before = route.bearingAt(s, 1)
            s += 25.0 * 0.1
            var d = route.bearingAt(s, 1) - before; if (d > 180) d -= 360; if (d < -180) d += 360
            nav.onMotion(MotionSample(clock, 0.1, MotionState.MOVING, 0, 0.0, 0.0, yawRateDps = if (gyro) d / 0.1 else Double.NaN))
        }
        val st = nav.state()
        return abs(route.project(st.latitude, st.longitude).distanceAlongTrack - s) to st.curveCorrections
    }

    @Test
    fun turningThePhoneAtPlatformsDoesNotBreakCurveMatching() {
        // Jiggling at every platform includes fast rotations (±40 °/s) of the phone.
        val r = ride("jiggle-gyro", SimConfig(segmentSpeeds = true, jiggleAtStations = true, seed = 1101))
        assertEquals(allStops, r.stationsAtStops)
        assertTrue("dwell error ${r.dwellMaxError}", r.dwellMaxError < 10.0)
    }

    // ---- No metro line data at all ---------------------------------------------------------------

    @Test
    fun noLineDataStillTracksSpeedStopsAndDistance() {
        // First 6 km of the line are straight north: GPS at the portal, then 4 stations with no line saved.
        val r = ride("no-line-data", SimConfig(endStation = 5, setRouteBeforeStart = false, seed = 41))
        assertTrue("still at dwell ${r.zeroSpeedDwellFraction}", r.zeroSpeedDwellFraction > 0.98)
        assertTrue("speed RMS ${r.speedRmsError}", r.speedRmsError < 5.0)
        // Without a line nothing resets the error, so it grows with distance (~5-10 % of the ride).
        assertTrue("stop errors ${r.stopErrors}", r.stopErrors.last() < 600.0)
        assertEquals(PositionSource.DEAD_RECKONING, r.navigator.state().source)
    }

    @Test
    fun withoutALineTheGyroscopeFollowsCurves() {
        // S4 -> S8 crosses the 45° curve at 6.0-6.8 km with no line saved: only the gyroscope
        // can turn the heading (it used to stay on the last GPS direction, like a road test showed).
        val cfg = SimConfig(startStation = 4, endStation = 8, setRouteBeforeStart = false, seed = 43)
        val gyro = ride("no-line-curve-gyro", cfg)
        val noGyro = ride("no-line-curve-no-gyro", cfg.copy(gyro = false))
        println("gyro mean ${gyro.meanMovingError} max ${gyro.maxMovingError} / without mean ${noGyro.meanMovingError} max ${noGyro.maxMovingError}")
        assertTrue("with ${gyro.maxMovingError} vs without ${noGyro.maxMovingError}", gyro.maxMovingError < 0.5 * noGyro.maxMovingError)
        assertTrue("mean with ${gyro.meanMovingError} vs without ${noGyro.meanMovingError}", gyro.meanMovingError < 0.5 * noGyro.meanMovingError)
    }

    // ---- Wi-Fi ----------------------------------------------------------------------------------

    @Test
    fun radioMapLearntOnFirstRideCorrectsLaterRides() {
        val world = RadioWorld(TunnelWorld.route)
        val first = ride("radio-learn", SimConfig(radio = world, seed = 21))
        val map = first.navigator.radioMap
        assertTrue("places learnt ${map.size}", map.size >= 16)

        // Express ride: skipped stations are only recognisable by their radio.
        val express = setOf(3, 5, 7, 9, 11, 13)
        val without = ride("express-no-radio", SimConfig(skipStations = express, seed = 22))
        val with = ride("express-radio", SimConfig(skipStations = express, radio = world, radioMap = RadioMap.parse(map.serialize()), seed = 22))
        assertTrue("radio corrections ${with.navigator.state().radioCorrections}", with.navigator.state().radioCorrections > 0)
        assertTrue("with ${with.meanMovingError} vs without ${without.meanMovingError}", with.meanMovingError < without.meanMovingError)
        // Wi-Fi is heard only around stations and scanned every 30 s, so a train running through a
        // skipped station may not hear it at all: the worst case between stops is not reduced
        // (the motion sensors already release the hold at a skipped station, see below).
        assertTrue("max with ${with.maxMovingError} vs without ${without.maxMovingError}", with.maxMovingError < 1.2 * without.maxMovingError)
        assertEquals(allStops.filter { it.removePrefix("S").toInt() !in express }, with.stationsAtStops)
    }

    @Test
    fun coldStartUndergroundWithNoFixAtAllUsesTheRadioMap() {
        val world = RadioWorld(TunnelWorld.route)
        val map = ride("radio-learn-2", SimConfig(radio = world, seed = 31)).navigator.radioMap
        // Never any satellite or network fix: only the learnt radio map knows where we are.
        val r = ride("cold-start-radio", SimConfig(startStation = 8, endStation = 14, gpsUntilMeters = null, entranceFix = false,
            radio = world, radioMap = map, seed = 32))
        assertEquals((9..14).map { "S$it" }, r.stationsAtStops)
        assertTrue("stop errors ${r.stopErrors}", r.stopErrors.all { it < 5.0 })
    }

    // ---- Mobile cells (no Wi-Fi) ----------------------------------------------------------------

    @Test
    fun cellFingerprintsFromAnEarlierRideCorrectAnExpressRide() {
        // No Wi-Fi anywhere: only the in-tunnel mobile cells (one per station, handover near mid-tunnel).
        val world = RadioWorld(TunnelWorld.route, wifi = false, cells = true)
        val first = ride("cell-learn", SimConfig(radio = world, seed = 25))
        val map = first.navigator.radioMap
        assertTrue("places learnt ${map.size}", map.size >= 40)

        val express = setOf(3, 5, 7, 9, 11, 13)
        val without = ride("express-no-cells", SimConfig(skipStations = express, seed = 26))
        val with = ride("express-cells", SimConfig(skipStations = express, radio = world, radioMap = RadioMap.parse(map.serialize()), seed = 26))
        println("cells: with mean ${with.meanMovingError} max ${with.maxMovingError} / without mean ${without.meanMovingError} max ${without.maxMovingError}")
        assertTrue("radio corrections ${with.navigator.state().radioCorrections}", with.navigator.state().radioCorrections > 0)
        assertEquals(allStops.filter { it.removePrefix("S").toInt() !in express }, with.stationsAtStops)
        assertTrue("with ${with.meanMovingError} vs without ${without.meanMovingError}", with.meanMovingError < without.meanMovingError)
        assertTrue("max with ${with.maxMovingError} vs without ${without.maxMovingError}", with.maxMovingError < 1.2 * without.maxMovingError)
    }

    @Test
    fun coldStartUndergroundWithOnlyMobileCells() {
        val world = RadioWorld(TunnelWorld.route, wifi = false, cells = true)
        val map = ride("cell-learn-2", SimConfig(radio = world, seed = 27)).navigator.radioMap
        // Never any GPS or network fix: the learnt cells alone place the train on the line.
        val r = ride("cold-start-cells", SimConfig(startStation = 8, endStation = 14, gpsUntilMeters = null, entranceFix = false,
            radio = world, radioMap = map, seed = 28))
        println("cold start cells: stops ${r.stationsAtStops} errors ${r.stopErrors}")
        assertEquals((9..14).map { "S$it" }, r.stationsAtStops)
        assertTrue("stop errors ${r.stopErrors}", r.stopErrors.all { it < 20.0 })
    }
}
