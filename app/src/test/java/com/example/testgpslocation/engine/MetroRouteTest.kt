package com.example.testgpslocation.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetroRouteTest {

    private val meterPerDegLat = 111_195.0

    private fun northLine() = MetroRoute(
        id = "test", lineName = "Test Line", fromStation = "A", toStation = "E",
        points = (0..50).map { TrackPoint(12.90 + it * 0.001, 77.0) },
        rawStations = listOf(
            MetroStation("C", 12.92, 77.0), MetroStation("A", 12.90, 77.0),
            MetroStation("B", 12.91, 77.0), MetroStation("A", 12.9001, 77.0), // duplicate name
            MetroStation("Far away", 12.92, 77.1) // ~10 km off the track
        )
    )

    @Test
    fun stationsAreSortedDedupedAndFiltered() {
        val r = northLine()
        assertEquals(listOf("A", "B", "C"), r.stations.map { it.name })
        assertEquals(0.01 * meterPerDegLat, r.stations[1].distanceAlongTrack, 2.0)
    }

    @Test
    fun projectionGivesAlongTrackDistanceAndOffset() {
        val r = northLine()
        val p = r.project(12.915, 77.0005) // ~54 m east of the track
        assertEquals(0.015 * meterPerDegLat, p.distanceAlongTrack, 2.0)
        assertEquals(54.0, p.offsetMeters, 2.0)
    }

    @Test
    fun pointAtRoundTripsWithProjection() {
        val r = northLine()
        val pt = r.pointAt(2345.0)
        assertEquals(2345.0, r.project(pt.lat, pt.lon).distanceAlongTrack, 0.5)
    }

    @Test
    fun nextAndPreviousStationRespectDirection() {
        val r = northLine()
        val mid = 0.015 * meterPerDegLat
        assertEquals("C", r.nextStation(mid, 1)?.name)
        assertEquals("B", r.nextStation(mid, -1)?.name)
        assertEquals("B", r.previousStation(mid, 1)?.name)
        assertNull(r.nextStation(r.stations.last().distanceAlongTrack, 1))
    }

    @Test
    fun bearingAtFollowsDirection() {
        val r = northLine()
        assertEquals(0.0, r.bearingAt(100.0, 1), 0.5)
        assertEquals(180.0, r.bearingAt(100.0, -1), 0.5)
    }

    @Test
    fun stitcherJoinsReversedAndUnorderedWays() {
        val a = listOf(TrackPoint(12.900, 77.0), TrackPoint(12.901, 77.0))
        val b = listOf(TrackPoint(12.902, 77.0), TrackPoint(12.901, 77.0)) // reversed
        val c = listOf(TrackPoint(12.902, 77.0), TrackPoint(12.903, 77.0))
        val d = listOf(TrackPoint(12.899, 77.0), TrackPoint(12.900, 77.0)) // attaches to the head
        val stitched = TrackStitcher.stitch(listOf(a, c, b, d))
        assertEquals(listOf(12.899, 12.900, 12.901, 12.902, 12.903), stitched.map { it.lat })
    }

    @Test
    fun stitcherStopsAtLargeGaps() {
        val a = listOf(TrackPoint(12.900, 77.0), TrackPoint(12.901, 77.0))
        val far = listOf(TrackPoint(13.000, 77.0), TrackPoint(13.001, 77.0))
        assertTrue(TrackStitcher.stitch(listOf(a, far)).all { it.lat < 12.95 })
    }
}
