package com.example.testgpslocation.engine

import com.example.testgpslocation.models.GpsPoint
import com.example.testgpslocation.utils.GeoToolbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Radio map, track HMM and pedestrian dead reckoning. */
class PositioningComponentsTest {

    private val mPerDeg = 111_195.0
    private fun scan(vararg s: Pair<String, Int>) = RadioScan(0L, mapOf(*s))

    // ---- RadioMap -------------------------------------------------------------------------------

    @Test
    fun learntPlaceIsRecognisedAndLocated() {
        val map = RadioMap()
        map.learn(scan("wifi:a" to -50, "wifi:b" to -60, "wifi:c" to -70), 12.90, 77.0, 10.0)
        map.learn(scan("wifi:d" to -50, "wifi:e" to -60, "wifi:f" to -70), 12.91, 77.0, 10.0)
        val fix = map.locate(scan("wifi:a" to -52, "wifi:b" to -61, "wifi:c" to -73))
        assertNotNull(fix)
        assertTrue(GeoToolbox.calculateDistance(fix!!.lat, fix.lon, 12.90, 77.0) < 5.0)
        assertTrue(fix.accuracyMeters < 60.0)
    }

    @Test
    fun oneWifiAccessPointAloneIsNotEnough() {
        val map = RadioMap()
        map.learn(scan("wifi:a" to -50, "wifi:b" to -60), 12.90, 77.0, 10.0)
        assertTrue(map.match(scan("wifi:a" to -50)).isEmpty())
        assertNull(map.locate(scan("wifi:a" to -50)))
    }

    @Test
    fun nearbyLearningMergesAndStrongerSightingMovesTheAnchor() {
        val map = RadioMap()
        map.learn(scan("wifi:a" to -70, "wifi:b" to -60), 12.90, 77.0, 20.0)
        map.learn(scan("wifi:a" to -72, "wifi:b" to -61), 12.90005, 77.0, 20.0) // 5.5 m away
        assertEquals(1, map.size)
        map.learn(scan("wifi:a" to -45, "wifi:b" to -80), 12.905, 77.0, 10.0)
        assertEquals(12.905, map.anchor("wifi:a")!!.lat, 1e-9)
    }

    @Test
    fun serialiseRoundTripAndBadLinesAreSkipped() {
        val map = RadioMap()
        map.learn(scan("wifi:aa:bb:cc:dd:ee:ff" to -55, "wifi:11:22:33:44:55:66" to -70), 12.9, 77.0, 12.0)
        val text = map.serialize() + "F\tnot-a-number\t1\t1\t1\tx=1\ngarbage line\n"
        val back = RadioMap.parse(text)
        assertEquals(1, back.size)
        assertNotNull(back.anchor("wifi:aa:bb:cc:dd:ee:ff"))
        assertNotNull(back.locate(scan("wifi:aa:bb:cc:dd:ee:ff" to -55, "wifi:11:22:33:44:55:66" to -70)))
    }

    @Test
    fun bluetoothBeaconsSavedByOlderVersionsAreDropped() {
        val text = "# radio map v1\n" +
            "F\t12.9\t77.0\t12.0\t1\twifi:a=-55.0;wifi:b=-70.0;ble:ib:uuid:1:2=-60.0\n" +
            "F\t12.95\t77.0\t8.0\t1\tble:ib:x:1:2=-62.0\n" +
            "A\twifi:a\t12.9\t77.0\t12.0\t-55.0\n" +
            "A\tble:ib:x:1:2\t12.95\t77.0\t8.0\t-62.0\n"
        val map = RadioMap.parse(text)
        assertEquals(1, map.size) // the beacon-only place is gone
        assertNotNull(map.anchor("wifi:a"))
        assertNull(map.anchor("ble:ib:x:1:2"))
        // The loud beacon no longer counts as "missing" against a Wi-Fi-only scan.
        assertEquals(1.0, map.match(scan("wifi:a" to -55, "wifi:b" to -70)).single().weight, 1e-9)
    }

    @Test
    fun keysThatWouldBreakTheFileAreIgnored() {
        val map = RadioMap()
        assertFalse(map.learn(scan("wifi:a=b" to -50, "wifi:c;d" to -50), 12.9, 77.0, 10.0))
    }

    // ---- TrackHmm -----------------------------------------------------------------------------

    private val line = MetroRoute("l", "L", null, null, (0..100).map { TrackPoint(12.90 + it * 50.0 / mPerDeg, 77.0) }, emptyList())

    @Test
    fun hmmCombinesPriorAndObservation() {
        val hmm = TrackHmm(line)
        hmm.reset(1000.0, 200.0)
        hmm.update { d -> kotlin.math.exp(-0.5 * ((d - 1200.0) / 50.0) * ((d - 1200.0) / 50.0)) }
        val b = hmm.estimate()
        assertTrue("mean ${b.distance}", b.distance in 1150.0..1200.0)
        assertTrue(b.sigma < 60.0)
        assertTrue(b.modeMass > 0.9)
    }

    @Test
    fun hmmPredictShiftsAndBlurs() {
        val hmm = TrackHmm(line)
        hmm.reset(1000.0, 20.0)
        hmm.predict(300.0, 100.0)
        val b = hmm.estimate()
        assertEquals(1300.0, b.distance, 15.0)
        assertTrue(b.sigma in 80.0..130.0)
    }

    @Test
    fun ambiguousObservationIsNotReportedUntilItResolves() {
        val hmm = TrackHmm(line)
        hmm.reset(2500.0, 2000.0)
        // Two identical-looking places.
        hmm.update { d -> 0.01 + kotlin.math.exp(-0.5 * ((d - 1000.0) / 40.0) * ((d - 1000.0) / 40.0)) + kotlin.math.exp(-0.5 * ((d - 4000.0) / 40.0) * ((d - 4000.0) / 40.0)) }
        assertTrue(hmm.estimate().modeMass < 0.6)
        hmm.update { d -> 0.01 + kotlin.math.exp(-0.5 * ((d - 4000.0) / 60.0) * ((d - 4000.0) / 60.0)) }
        val b = hmm.estimate()
        assertEquals(4000.0, b.distance, 20.0)
        assertTrue(b.modeMass > 0.9)
    }

    @Test
    fun repeatedStrongEvidenceRecoversFromAWrongPrior() {
        val hmm = TrackHmm(line)
        hmm.reset(500.0, 30.0)
        repeat(6) {
            hmm.predict(0.0, 0.0)
            hmm.update { d -> 0.02 + kotlin.math.exp(-0.5 * ((d - 3500.0) / 40.0) * ((d - 3500.0) / 40.0)) }
        }
        assertEquals(3500.0, hmm.estimate().distance, 30.0)
    }

    @Test
    fun impossibleObservationLeavesBeliefUnchanged() {
        val hmm = TrackHmm(line)
        hmm.reset(1000.0, 50.0)
        assertFalse(hmm.update { 0.0 })
        assertEquals(1000.0, hmm.estimate().distance, 10.0)
    }

    // ---- Navigator + radio ---------------------------------------------------------------------

    @Test
    fun navigatorLearnsWithGpsAndLocatesWithoutIt() {
        val nav = UndergroundNavigator()
        var clock = 1_000_000L
        clock += 1000
        nav.onLocationFix(GpsPoint(12.90, 77.0, 8f, clock, elapsedRealtimeMs = clock), clock)
        nav.onRadioScan(RadioScan(clock + 200, mapOf("wifi:a" to -50, "wifi:b" to -60, "wifi:c" to -65)))
        assertEquals(1, nav.radioMap.size)

        // Somewhere else, fresh start, no satellites: the radio map alone places us.
        val later = UndergroundNavigator().also { it.radioMap = nav.radioMap }
        later.onRadioScan(RadioScan(clock + 5000, mapOf("wifi:a" to -51, "wifi:b" to -62, "wifi:c" to -64)))
        val s = later.state()
        assertEquals(PositionSource.RADIO, s.source)
        assertTrue(GeoToolbox.calculateDistance(s.latitude, s.longitude, 12.90, 77.0) < 5.0)
    }

    @Test
    fun poorGpsIsNotLearnt() {
        val nav = UndergroundNavigator()
        nav.onLocationFix(GpsPoint(12.90, 77.0, 40f, 0L, elapsedRealtimeMs = 1000L), 1000L)
        nav.onRadioScan(RadioScan(1200L, mapOf("wifi:a" to -50, "wifi:b" to -60)))
        assertEquals(0, nav.radioMap.size)
    }

    // ---- Pedestrian dead reckoning -------------------------------------------------------------

    @Test
    fun stepLengthGrowsWithCadence() {
        assertEquals(Pdr.DEFAULT_STEP_METERS, Pdr.stepLength(0.0), 1e-9)
        assertTrue(Pdr.stepLength(2.2) > Pdr.stepLength(1.4))
        assertTrue(Pdr.stepLength(10.0) <= 0.9)
    }

    @Test
    fun headingFromRotationMatrix() {
        // Phone flat, top edge pointing east: device Y axis = world East.
        val flatEast = floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
        assertEquals(90.0, Pdr.headingFromRotation(flatEast)!!, 1e-6)
        // Phone upright facing north: camera (−Z) points north.
        val uprightNorth = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        assertEquals(0.0, Pdr.headingFromRotation(uprightNorth)!!, 1e-6)
    }

    @Test
    fun walkingWithoutGpsCountsStepsAlongThePhoneHeading() {
        val nav = UndergroundNavigator()
        var clock = 1_000_000L
        clock += 1000
        nav.onLocationFix(GpsPoint(12.90, 77.0, 5f, clock, 1.2f, 90f, hasSpeed = true, hasBearing = true, elapsedRealtimeMs = clock), clock)
        clock += 6000
        nav.onGpsLost(clock)
        val startLon = nav.state().longitude
        // 100 steps at 1.8 steps/s towards east.
        repeat(556) { i ->
            clock += 100
            val steps = if (i % 5 == 0 || i % 5 == 3) 1 else 0 // ≈ 1.8 steps per second (not exact)
            nav.onMotion(MotionSample(clock, 0.1, MotionState.WALKING, 0, 0.0, 0.0, steps = steps, stepCadenceHz = 1.8, deviceHeadingDeg = 90.0))
        }
        val s = nav.state()
        val movedEast = (s.longitude - startLon) * mPerDeg * kotlin.math.cos(Math.toRadians(12.9))
        val expected = s.pdrSteps * Pdr.stepLength(1.8)
        assertTrue("steps ${s.pdrSteps}", s.pdrSteps in 200..230)
        assertEquals(expected, movedEast, expected * 0.02)
        assertTrue(abs(s.latitude - 12.90) * mPerDeg < 1.0)
    }

    // ---- Ride memory and line curves -----------------------------------------------------------

    @Test
    fun rideMemoryLearnsTheCruiseOfARun() {
        val m = RideMemory()
        // 1200 m in 80 s of motion with 1 m/s² accelerate/brake: D = v·T − v² → v = 20 m/s.
        assertTrue(m.record("L", "A", "B", 1200.0, 80.0))
        val v = m.cruise("L", "A", "B")!!
        assertEquals(20.0, v, 1e-9)
        assertNull(m.cruise("L", "B", "C"))
        assertFalse(m.record("L", "A", "B", 1200.0, 1000.0)) // 1.2 m/s average: a long hold, not a run
        val back = RideMemory.parse(m.serialize() + "S\tbroken\n")
        assertEquals(v, back.cruise("L", "A", "B")!!, 1e-9)
        assertEquals(1, back.size)
    }

    @Test
    fun theStillnessThresholdIsLearntAndSaved() {
        val m = RideMemory()
        repeat(49) { m.learnVibration(0.1, vehicleMoving = false); m.learnVibration(0.4, vehicleMoving = true) }
        assertNull("not enough readings yet", m.vibration.stillThreshold)
        m.learnVibration(0.1, vehicleMoving = false); m.learnVibration(0.4, vehicleMoving = true)
        assertEquals(0.2, m.vibration.stillThreshold!!, 1e-9) // between standing and moving
        assertEquals(0.2, RideMemory.parse(m.serialize()).vibration.stillThreshold!!, 1e-9)
        val close = RideMemory()
        repeat(60) { close.learnVibration(0.10, vehicleMoving = false); close.learnVibration(0.15, vehicleMoving = true) }
        assertNull("too alike to tell apart", close.vibration.stillThreshold)
    }

    @Test
    fun shortRunNeverReachingCruiseGivesItsPeakSpeed() {
        // 400 m in 40 s cannot be a trapezoid with 1 m/s²: triangular peak = sqrt(400).
        assertEquals(20.0, RideMemory.cruiseFromRun(400.0, 30.0), 1e-9)
    }

    // ---- Log / map output -----------------------------------------------------------------------

    @Test
    fun onlyNewPositionsAreLoggedAndMarked() {
        val d = PositionDeduper(maxQuietMs = 30_000L)
        assertEquals(PositionDeduper.Decision.LOG_AND_MARK, d.decide(12.9, 77.6, 0L))
        // Standing at a platform: the same position every 2 s is not repeated...
        for (t in 2_000L..28_000L step 2_000L) assertEquals("t=$t", PositionDeduper.Decision.SKIP, d.decide(12.9, 77.6, t))
        // ...but the log gets a row every 30 s so it never goes quiet, without a new marker.
        assertEquals(PositionDeduper.Decision.LOG, d.decide(12.9, 77.6, 30_000L))
        assertEquals(PositionDeduper.Decision.SKIP, d.decide(12.9, 77.6, 32_000L))
        // Any movement the log can show (6 decimals, ~0.1 m) is a new row and marker.
        assertEquals(PositionDeduper.Decision.LOG_AND_MARK, d.decide(12.900001, 77.6, 34_000L))
        assertEquals(PositionDeduper.Decision.SKIP, d.decide(12.9000012, 77.6, 36_000L)) // same at 6 decimals
        d.reset()
        assertEquals(PositionDeduper.Decision.LOG_AND_MARK, d.decide(12.900001, 77.6, 38_000L))
    }

    @Test
    fun lineCurvesAreMeasuredInBothDirections() {
        val r = TunnelWorld.route
        assertEquals(90.0, r.turnBetween(0.0, r.length), 2.0)
        assertEquals(-90.0, r.turnBetween(r.length, 0.0), 2.0)
        assertEquals(0.0, r.turnBetween(7000.0, 12500.0), 1.0)
        assertEquals(45.0, r.turnBetween(5000.0, 9000.0), 2.0)
    }
}
