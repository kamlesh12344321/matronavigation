package com.example.testgpslocation.engine

import com.example.testgpslocation.sensor.ChipEstimateCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Building blocks: which positions reach the log and map, and the line's curves. */
class PositioningComponentsTest {

    // ---- GPS chip estimates ---------------------------------------------------------------------

    @Test
    fun aGpsFixIsAnEstimateOnlyOnARecentReportOfNoSatellites() {
        val c = ChipEstimateCheck()
        assertFalse("no reports at all", c.isEstimate(mock = false, nowMs = 1_000))
        c.onSatelliteStatus(0, 1_000)
        assertFalse("this phone never reported a satellite", c.isEstimate(mock = false, nowMs = 1_000))
        c.onSatelliteStatus(8, 2_000)
        assertFalse(c.isEstimate(mock = false, nowMs = 2_000))
        c.onSatelliteStatus(0, 3_000)
        assertTrue(c.isEstimate(mock = false, nowMs = 3_500))
        assertFalse("a mock fix is not the chip's", c.isEstimate(mock = true, nowMs = 3_500))
        assertFalse("a stale report must not block real GPS", c.isEstimate(mock = false, nowMs = 7_000))
    }

    @Test
    fun fusedFixesCountAsEstimatesOnlyWhileTheChipIsEstimating() {
        val c = ChipEstimateCheck()
        assertFalse(c.estimatesRunning(1_000))
        c.onSatelliteStatus(8, 1_000); c.onSatelliteStatus(0, 2_000)
        c.isEstimate(mock = false, nowMs = 2_000)
        assertTrue(c.estimatesRunning(4_000))
        assertFalse(c.estimatesRunning(6_000))
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
