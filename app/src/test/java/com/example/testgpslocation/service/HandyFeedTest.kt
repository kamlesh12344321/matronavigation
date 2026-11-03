package com.example.testgpslocation.service

import com.example.testgpslocation.service.HandyFeed.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the position is sent to Nemo Handy as the phone's location, and when the real GPS is checked for. */
class HandyFeedTest {

    private fun next(current: Phase, forSec: Double, gps: Boolean = false, lostSec: Double = 60.0, sats: Int = 0, position: Boolean = true) =
        HandyFeed.nextPhase(current, (forSec * 1000).toLong(), gps, position, (lostSec * 1000).toLong(), sats)

    @Test
    fun nothingIsSentWhileGpsWorksOrIsOnlyBrief() {
        assertEquals(Phase.OFF, next(Phase.OFF, 100.0, gps = true))
        assertEquals("under a bridge", Phase.OFF, next(Phase.OFF, 100.0, lostSec = 5.0))
        assertEquals("no position yet", Phase.OFF, next(Phase.OFF, 100.0, position = false))
    }

    @Test
    fun sendingStartsAfterTenSecondsWithoutGps() {
        assertEquals(Phase.FEED, next(Phase.OFF, 100.0, lostSec = 10.0))
    }

    @Test
    fun sendingPausesEveryThirtySecondsToLookForRealGps() {
        assertEquals(Phase.FEED, next(Phase.FEED, 29.0))
        assertEquals(Phase.PROBE, next(Phase.FEED, 30.0))
        assertEquals(Phase.PROBE, next(Phase.PROBE, 7.0))
        assertEquals("underground: no satellites, sending again", Phase.FEED, next(Phase.PROBE, 8.0))
    }

    @Test
    fun thePauseGoesOnWhileSatellitesAreBeingPickedUp() {
        assertEquals(Phase.PROBE, next(Phase.PROBE, 15.0, sats = 3))
        assertEquals("but not for ever", Phase.FEED, next(Phase.PROBE, 30.0, sats = 3))
    }

    @Test
    fun realGpsStopsTheFeedAtOnce() {
        assertEquals(Phase.OFF, next(Phase.FEED, 3.0, gps = true))
        assertEquals(Phase.OFF, next(Phase.PROBE, 3.0, gps = true))
    }

    @Test
    fun aTunnelTripFromStartToDaylight() {
        // 5 minutes underground, then out in the open. The real receiver only works while the
        // feed is paused; once it is, it needs 5 s to get a fix (satellites in use meanwhile).
        var phase = Phase.OFF; var since = 0
        var sent = 0; var gps = false; var receiverOnFor = 0; var offAt = -1
        val exitAt = 300
        for (t in 0 until 600) {
            val outside = t >= exitAt
            if (outside && phase != Phase.FEED) receiverOnFor++ else receiverOnFor = 0
            if (outside && receiverOnFor >= 5) gps = true
            val sats = if (outside && phase != Phase.FEED) 6 else 0
            val wanted = HandyFeed.nextPhase(phase, (t - since) * 1000L, gps, true, if (gps) 0L else t * 1000L, sats)
            if (wanted != phase) { phase = wanted; since = t }
            if (phase == Phase.FEED) sent++
            if (gps && phase == Phase.OFF && offAt < 0) offAt = t
        }
        // Underground the position goes out most of the time (paused 8 s in every 38 s).
        assertTrue("sent $sent of $exitAt s", sent >= 0.7 * (exitAt - HandyFeed.START_AFTER_MS / 1000))
        // Out of the tunnel the real GPS takes over within one cycle.
        assertTrue("real GPS back after ${offAt - exitAt} s", offAt in exitAt..(exitAt + 30 + 8 + 5))
    }
}
