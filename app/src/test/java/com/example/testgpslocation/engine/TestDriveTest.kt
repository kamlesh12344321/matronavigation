package com.example.testgpslocation.engine

import com.example.testgpslocation.utils.GeoToolbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** Test drive: a route recorded with GPS on a first trip, navigated without GPS on the next. */
class TestDriveTest {

    private val world = TunnelWorld.route

    /**
     * A first trip along the test line with GPS: 15 m/s between stations, a 25 s stop at each,
     * one fix per second with 3 m noise.
     */
    private fun recordFirstTrip(seed: Int = 7): TestRouteRecorder {
        val rnd = Random(seed)
        fun gauss(): Double { val u = rnd.nextDouble().coerceAtLeast(1e-12); return sqrt(-2 * ln(u)) * cos(2 * Math.PI * rnd.nextDouble()) }
        val rec = TestRouteRecorder()
        var t = 0L
        fun fix(s: Double, speed: Float) {
            val p = world.pointAt(s)
            val (lat, lon) = GeoToolbox.moveLocation(p.lat, p.lon, abs(gauss()) * 3.0, rnd.nextDouble() * 360)
            rec.onFix(lat, lon, 5f, speed, t)
            t += 1000
        }
        val stops = TunnelWorld.stationDistances
        for (i in 1 until stops.size) {
            var s = stops[i - 1]
            while (s < stops[i]) { fix(s, 15f); s += 15.0 }
            repeat(25) { fix(stops[i], 0.1f) }
        }
        return rec
    }

    @Test
    fun aFirstTripBecomesALineWithItsStopsAsStations() {
        val route = recordFirstTrip().build("test_1", "Test route")
        assertNotNull(route)
        route!!
        assertEquals("length ${route.length}", world.length, route.length, 0.02 * world.length)
        // Raw fixes come every 15 m (1300+); straight stretches collapse to a few points.
        assertTrue("simplified to ${route.points.size} points", route.points.size < world.length / 50)
        assertEquals("Start", route.stations.first().name)
        assertEquals("End", route.stations.last().name)
        // Every stop of the trip is a station, within a few metres of where the vehicle stood.
        val stops = route.stations.filter { it.name.startsWith("Stop") }
        assertEquals(TunnelWorld.stationDistances.size - 2, stops.size) // the last stop is the End
        stops.forEach { s ->
            val nearest = TunnelWorld.stationDistances.minOf { d -> world.pointAt(d).let { GeoToolbox.calculateDistance(it.lat, it.lon, s.lat, s.lon) } }
            assertTrue("${s.name} is $nearest m from a real stop", nearest < 15.0)
        }
    }

    @Test
    fun theNextTripIsNavigatedWithoutGpsOnTheRecordedRoute() {
        val recorded = recordFirstTrip().build("test_1", "Test route")!!
        // Second trip: GPS only for the first 300 m, then hidden (the "tunnel"), the whole 20 km.
        val r = TunnelSimulator(SimConfig(navigatorRoute = recorded, seed = 71)).run()
        println(r.summary("test-drive-second-trip"))
        assertEquals("stops measured", TunnelWorld.stationDistances.size - 1, r.stopsMeasured)
        assertTrue("stop errors ${r.stopErrors}", r.stopErrors.all { it < 20.0 })
        assertTrue("mean moving error ${r.meanMovingError}", r.meanMovingError < 40.0)
        assertTrue("repeats while moving ${r.longestRepeatWhileMovingSec}", r.longestRepeatWhileMovingSec < 20.0)
    }

    @Test
    fun markedStationsAndTooShortRoutes() {
        val rec = TestRouteRecorder()
        assertFalse("no fix yet", rec.markStation())
        rec.onFix(12.90, 77.0, 5f, 10f, 0L)
        assertFalse("too close to the start", rec.markStation())
        rec.onFix(12.90 + 200 / 111_195.0, 77.0, 5f, 10f, 20_000L)
        assertTrue(rec.markStation())
        rec.onFix(12.90 + 210 / 111_195.0, 77.0, 5f, 10f, 21_000L)
        assertFalse("within 60 m of the marked one", rec.markStation())
        assertNull("200 m is too short", rec.build("t", "t"))
        rec.onFix(12.90 + 500 / 111_195.0, 77.0, 5f, 10f, 40_000L)
        rec.onFix(12.90 + 520 / 111_195.0, 77.0, 80f, 10f, 41_000L) // poor fix: ignored
        val route = rec.build("t", "t")!!
        assertEquals(listOf("Start", "Stop 1", "End"), route.stations.map { it.name })
        assertEquals(500.0, route.length, 2.0)
    }

    @Test
    fun movingIsRecordedEvenWhenTheReceiverReportsSpeedZero() {
        val rec = TestRouteRecorder()
        repeat(40) { k -> rec.onFix(12.90 + k * 15 / 111_195.0, 77.0, 5f, 0f, k * 1000L) }
        // 585 m driven; the line grows in 15 m steps, so up to one step at the end is not counted.
        assertEquals(585.0, rec.lengthMeters, TestRouteRecorder.POINT_SPACING_METERS + 1.0)
        assertNotNull(rec.build("t", "t"))
    }

    @Test
    fun simplifyKeepsBendsAndDropsWobble() {
        val straight = (0..50).map { TrackPoint(12.90 + it * 10 / 111_195.0, 77.0 + (if (it % 2 == 0) 1.0 else -1.0) / 108_000.0) }
        assertEquals("1 m wobble on a straight road", 2, TestRouteRecorder.simplify(straight, 4.0).size)
        val corner = (0..20).map { TrackPoint(12.90 + it * 10 / 111_195.0, 77.0) } +
            (1..20).map { TrackPoint(12.90 + 200 / 111_195.0, 77.0 + it * 10 / 108_000.0) }
        assertEquals("a right-angle corner", 3, TestRouteRecorder.simplify(corner, 4.0).size)
    }

    @Test
    fun savedRoutesSurviveAndCanBeDeleted() {
        val dir = Files.createTempDirectory("test_routes").toFile()
        try {
            val store = TestRouteStore(dir)
            val route = recordFirstTrip().build("test_42", "Test route 42")!!
            assertTrue(store.save(route))
            val back = store.list().single()
            assertEquals("Test route 42", back.lineName)
            assertEquals(route.stations.map { it.name }, back.stations.map { it.name })
            assertEquals(route.length, back.length, 0.01)
            store.deleteAll()
            assertTrue(store.list().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun errorStatistics() {
        val e = ErrorStats()
        assertNull(e.mean)
        listOf(10.0, 30.0, 20.0).forEach(e::add)
        assertEquals(20.0, e.mean!!, 1e-9)
        assertEquals(30.0, e.max, 1e-9)
        assertEquals(20.0, e.last!!, 1e-9)
        e.reset()
        assertEquals(0, e.count)
    }
}
