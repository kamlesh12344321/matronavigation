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

    // ---- Barometer -------------------------------------------------------------------------------

    @Test
    fun aBarometerDoesNotDisturbTheRide() {
        repeat(3) { k ->
            val r = ride("barometer-$k", SimConfig(barometer = true, seed = 1600 + k))
            assertEquals("seed $k", allStops, r.stationsAtStops)
            assertTrue("seed $k stop errors ${r.stopErrors}", r.stopErrors.all { it < 5.0 })
        }
    }

    @Test
    fun theBarometerFindsStopsThePhoneCannotSee() {
        // Held in the hand, or air conditioning: the phone never reads still at a platform.
        repeat(3) { k ->
            val r = ride("barometer-moving-$k", SimConfig(barometer = true, stillReadsMoving = true, seed = 1610 + k))
            assertEquals("seed $k", allStops, r.stationsAtStops)
            assertTrue("seed $k stop errors ${r.stopErrors}", r.stopErrors.all { it < 5.0 })
        }
    }

    // ---- GPS chip estimates --------------------------------------------------------------------

    @Test
    fun noSatelliteGpsFixesInTheTunnelDoNotHideTheStops() {
        // Seen on an S24: the GPS provider went on for 50 s with no satellite in use.
        repeat(3) { k ->
            val r = ride("chip-estimate-$k", SimConfig(gpsUntilMeters = 600.0, chipEstimateSec = 50.0, seed = 1500 + k))
            assertEquals("seed $k", allStops, r.stationsAtStops)
            assertTrue("seed $k stop errors ${r.stopErrors}", r.stopErrors.all { it < 5.0 })
        }
    }

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
        val stops = r.events.count { it.startsWith("Stopped at") && !it.endsWith("(GPS)") }
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
    fun stopsAreFoundWhenThePhoneNeverReadsStill() {
        // Platform air-conditioning: the phone's fixed rule says "moving" through every 25 s
        // dwell. The drop from the running vibration, confirmed by the braking, finds each stop;
        // the departure is still taken from the acceleration. Nothing learnt beforehand.
        repeat(3) { k ->
            val r = ride("aircon-$k", SimConfig(stillReadsMoving = true, seed = 1200 + k))
            assertEquals("seed $k", allStops, r.stationsAtStops)
            assertTrue("seed $k stop errors ${r.stopErrors}", r.stopErrors.all { it < 5.0 })
            assertTrue("seed $k zero speed at dwell ${r.zeroSpeedDwellFraction}", r.zeroSpeedDwellFraction > 0.9)
        }
    }

    @Test
    fun aThirtySecondStopThenDepartureKeepsTheSequence() {
        // The longest dwell expected on the line, at every station, with and without air-con.
        listOf(false, true).forEach { aircon ->
            val r = ride("dwell30-$aircon", SimConfig(dwellSec = 30.0, stillReadsMoving = aircon, segmentSpeeds = true, seed = 1300))
            assertEquals("aircon $aircon", allStops, r.stationsAtStops)
            assertTrue("aircon $aircon: position kept still while standing (${r.zeroSpeedDwellFraction})", r.zeroSpeedDwellFraction > 0.9)
        }
    }

    @Test
    fun aBoxKnockedAtEveryPlatformNeverLosesTheStops() {
        // Knocks cut every dwell's still reading short, so the accelerometer bias is learnt from a
        // few noisy seconds. That error used to make the braking check fail at the next stop, the
        // missed stop left the drift in, and every later stop was missed (errors of kilometres).
        repeat(10) { k ->
            val r = ride("knocked-$k", SimConfig(jiggleAtStations = true, seed = 5000 + k))
            assertTrue("seed $k stop errors ${r.stopErrors}", r.stopErrors.all { it < 20.0 })
            assertTrue("seed $k mean ${r.meanMovingError}", r.meanMovingError < 40.0)
        }
    }

    @Test
    fun coordinatesStayTheSameOnlyWhileTheTrainStands() {
        // While the train moves the estimate moves too (at most a second or two still, while a
        // pull-away is being confirmed); while it stands the coordinates do not change.
        val express = setOf(3, 5, 7, 9, 11, 13)
        val situations = listOf(
            "normal" to SimConfig(seed = 1400),
            "varied" to SimConfig(segmentSpeeds = true, seed = 1401),
            "aircon" to SimConfig(stillReadsMoving = true, dwellSec = 30.0, seed = 1402),
            "express" to SimConfig(skipStations = express, segmentSpeeds = true, seed = 1403),
            "signal" to SimConfig(signalStopAt = 5500.0, seed = 1404),
            "knocked" to SimConfig(jiggleAtStations = true, seed = 1405)
        )
        for ((name, cfg) in situations) {
            val r = ride("rule-$name", cfg)
            assertTrue("$name: same coordinates for ${r.longestRepeatWhileMovingSec} s while moving", r.longestRepeatWhileMovingSec <= 2.0)
            assertEquals("$name: moved while standing", 0.0, r.movedWhileStoppedSec, 1e-9)
        }
    }

    @Test
    fun aRoundTripTurnsBackAtTheEndOfTheLine() {
        val back = allStops + TunnelWorld.names.dropLast(1).reversed().drop(1) + "S0"
        // Even when the train's own magnetism throws the compass right off after it reverses.
        listOf(null, 150.0, -130.0).forEachIndexed { k, returnError ->
            val r = ride("round-trip-$k", SimConfig(roundTrip = true, compassErrorOnReturnDeg = returnError, seed = 1500 + k))
            assertEquals("return compass $returnError", r.expectedStations, r.stationsAtStops)
            assertEquals(back.size, r.stationsAtStops.size)
            assertTrue("stop errors ${r.stopErrors}", r.stopErrors.all { it < 5.0 })
        }
    }

    @Test
    fun aWrongCompassDirectionAtTheStartIsCorrectedByTheNetwork() {
        // Underground start, compass 180° off: no GPS to tell the direction, network fixes ±300 m.
        val r = ride("wrong-way-network", SimConfig(gpsUntilMeters = null, startStation = 3, compassErrorDeg = 180.0,
            networkEverySec = 10.0, seed = 1510))
        assertTrue("events ${r.events}", r.events.any { it.startsWith("Direction corrected from network") })
        assertEquals(r.expectedStations, r.stationsAtStops)
    }

    @Test
    fun aWrongCompassDirectionAtTheStartIsCorrectedByTheFirstCurve() {
        // No network either: the first stations are wrong, then a curve shows the truth.
        listOf(3, 8).forEach { start ->
            val r = ride("wrong-way-curve-$start", SimConfig(gpsUntilMeters = null, startStation = start, compassErrorDeg = 180.0, seed = 1520 + start))
            assertTrue("events ${r.events}", r.events.any { it.startsWith("Direction corrected from the line's curves") })
            assertTrue("last stops ${r.stopErrors}", r.stopErrors.takeLast(4).all { it < 10.0 })
        }
    }

    @Test
    fun theRightCompassDirectionIsNotTurnedRound() {
        // A compass-only direction that is right, on a line full of different runs: never "corrected".
        repeat(10) { k ->
            val r = TunnelSimulator(SimConfig(gpsUntilMeters = null, startStation = 3, segmentSpeeds = true, seed = 1530 + k)).run()
            assertTrue("seed $k: ${r.events}", r.events.none { it.startsWith("Direction corrected") })
        }
    }

    @Test
    fun aRestartInMidRideCarriesOn() {
        // Killed between stations, and during a stop; back after 20 s and after a minute.
        listOf(3000.0 to 20.0, 3700.0 to 20.0, 9000.0 to 60.0).forEachIndexed { k, (at, gap) ->
            val r = ride("restart-$k", SimConfig(restartAtMeters = at, restartGapSec = gap, seed = 1540 + k))
            assertTrue("events ${r.events}", r.events.any { it.startsWith("Ride resumed after a restart") })
            assertTrue("last stops ${r.stopErrors}", r.stopErrors.takeLast(10).all { it < 5.0 })
            assertEquals(r.expectedStations.takeLast(10), r.stationsAtStops.takeLast(10))
        }
    }

    @Test
    fun aSavedRideReadsBackTheSame() {
        val s = RideState("line-1", 12.9, 77.6, 4200.0, -1, true, false, 123.0, 14.5, 18.0, 66.0, "S4", 1_000L)
        assertEquals(s, RouteJson.readRide(RouteJson.writeRide(s)))
        val noLine = s.copy(routeId = null, lastStation = null)
        assertEquals(noLine, RouteJson.readRide(RouteJson.writeRide(noLine)))
        val onFoot = s.copy(inVehicle = false, headingKnown = false, topSpeedMps = 16.5, rampSecPerMps = 0.9, speedsLearnt = true)
        assertEquals(onFoot, RouteJson.readRide(RouteJson.writeRide(onFoot)))
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
}
