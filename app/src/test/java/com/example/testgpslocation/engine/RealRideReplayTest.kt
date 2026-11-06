package com.example.testgpslocation.engine

import com.example.testgpslocation.models.GpsPoint
import com.example.testgpslocation.utils.GeoToolbox
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The real ride of 2026-11-05 on the Green Line, replayed from its log: GPS until the tunnel, then
 * the real network fixes, sensor readings and pressure. The log has no raw acceleration, so only
 * what it does hold can be judged here. Phone held in the hand, passenger seated.
 */
class RealRideReplayTest {

    private val route: MetroRoute by lazy {
        val dir = listOf(File("src/main/assets/metro"), File("app/src/main/assets/metro")).first { it.isDirectory }
        val json = GZIPInputStream(File(dir, "bengaluru.json.gz").inputStream()).bufferedReader().use { it.readText() }
        OverpassParser.parseAll(json).first { it.lineName.contains("Silk Institute -> Madavara") }
    }

    private class Replay(val events: List<String>, val alongAt: Map<Long, Double>, val errorAtGpsReturn: Double, val tunnelErrors: List<Double>, val travelled: Double)

    private fun clockOf(hms: String): Long { // ms into the log, which starts at 12:33:33.970
        val (h, m, s) = hms.split(":").map { it.toDouble() }
        return ((h * 3600 + m * 60 + s) * 1000).toLong() - START_MS
    }

    private fun replay(): Replay {
        val nav = UndergroundNavigator()
        nav.setRoute(route)
        val base = 1_000_000L
        var motionMs = 0L; var stillMs = 0L
        var lastGpsMs = 0L; var lost = false; var gpsEnded = false
        var lastGps: List<String>? = null
        var errorAtReturn = Double.NaN
        val wanted = WATCH.map(::clockOf).toMutableList()
        val moments = MOMENTS.toMutableList()
        val alongAt = LinkedHashMap<Long, Double>()
        val tunnelErrors = mutableListOf<Double>(); var nextScoreMs = clockOf("12:41:13"); var lastAlong = Double.NaN; var travelled = 0.0
        val events = mutableListOf<String>()
        fun drain(ms: Long) {
            nav.events.forEach { events += String.format(java.util.Locale.US, "%6.1f %s", ms / 1000.0, it) }
            nav.events.clear()
            while (wanted.isNotEmpty() && ms >= wanted.first()) alongAt[wanted.removeAt(0)] = route.project(nav.currentLat, nav.currentLon).distanceAlongTrack
            while (moments.isNotEmpty() && ms >= clockOf(moments.first())) {
                val s = nav.state()
                println(String.format(java.util.Locale.US, "REPLAYPOS %s %.6f %.6f %.0f %s", moments.removeAt(0), s.latitude, s.longitude, s.uncertaintyMeters, s.source.label))
            }
            if (ms >= nextScoreMs && ms < clockOf("12:47:13")) {
                nextScoreMs += 1000
                val along = route.project(nav.currentLat, nav.currentLon).distanceAlongTrack
                if (!lastAlong.isNaN()) travelled += kotlin.math.abs(along - lastAlong)
                lastAlong = along
                tunnelErrors += kotlin.math.abs(along - referenceAlong(ms))
            }
        }
        javaClass.getResourceAsStream("/real_ride_green_line.csv")!!.bufferedReader().forEachLine { line ->
            if (line.startsWith("#")) return@forEachLine
            val f = line.split(",")
            val ms = f[1].toLong()
            when (f[0]) {
                "M" -> {
                    val state = when (f[2]) { "stationary" -> MotionState.STATIONARY; "walking" -> MotionState.WALKING; else -> MotionState.MOVING }
                    val height = f[5].toDoubleOrNull() ?: Double.NaN
                    while (motionMs + 100 <= ms) {
                        motionMs += 100
                        stillMs = if (state == MotionState.STATIONARY) stillMs + 100 else 0
                        // The log skips rows while the coordinates stay the same: GPS itself went on.
                        val g = lastGps
                        if (g != null && !gpsEnded && motionMs - lastGpsMs >= 1000) {
                            nav.onLocationFix(GpsPoint(g[2].toDouble(), g[3].toDouble(), g[6].toFloat(), base + motionMs, 0f, 0f, hasSpeed = true,
                                elapsedRealtimeMs = base + motionMs, isGnss = true), base + motionMs)
                            lastGpsMs = motionMs
                        }
                        nav.onMotion(MotionSample(base + motionMs, 0.1, state, stillMs, 0.0, 0.0, orientationKnown = true,
                            yawRateDps = f[4].toDoubleOrNull() ?: Double.NaN, vibration = f[3].toDoubleOrNull() ?: Double.NaN, heightMeters = height))
                        if (!lost && gpsEnded && motionMs - lastGpsMs > 3000) { nav.onGpsLost(base + motionMs); lost = true }
                        drain(motionMs)
                    }
                }
                "G" -> {
                    val lat = f[2].toDouble(); val lon = f[3].toDouble()
                    if (lost && errorAtReturn.isNaN()) errorAtReturn = GeoToolbox.calculateDistance(nav.currentLat, nav.currentLon, lat, lon)
                    nav.onLocationFix(GpsPoint(lat, lon, f[6].toFloat(), base + ms, f[4].toFloat(), f[5].toFloatOrNull() ?: 0f, hasSpeed = true,
                        hasBearing = f[5].isNotEmpty(), elapsedRealtimeMs = base + ms, isGnss = true), base + ms)
                    lastGpsMs = ms
                    lastGps = f
                    gpsEnded = ms in clockOf("12:41:00")..clockOf("12:47:00")
                    drain(ms)
                }
                "N" -> {
                    nav.onLocationFix(GpsPoint(f[2].toDouble(), f[3].toDouble(), f[4].toFloat(), base + ms, elapsedRealtimeMs = base + ms,
                        isGnss = false, isChipEstimate = f[5] == "E"), base + ms)
                    drain(ms)
                }
            }
        }
        return Replay(events, alongAt, errorAtReturn, tunnelErrors, travelled)
    }

    /** Where the train really was: stops from the pressure and sensors, Majestic also from its Wi-Fi fixes, GPS at both ends. */
    private fun referenceAlong(ms: Long): Double {
        fun station(name: String) = route.stations.first { it.name.startsWith(name) }.distanceAlongTrack
        val kr = station("Krishna"); val ch = station("Chickpete"); val mj = station("Nadaprabhu")
        val before = route.project(12.956509, 77.573601).distanceAlongTrack
        val after = route.project(12.98751826, 77.57196072).distanceAlongTrack
        val points = listOf("12:41:01" to before, "12:41:48" to kr, "12:42:05" to kr, "12:43:02" to ch, "12:43:32" to ch,
            "12:44:53" to mj, "12:45:40" to mj, "12:47:13" to after).map { (t, a) -> clockOf(t) to a }
        val (a, b) = points.zipWithNext().first { (_, q) -> ms <= q.first }
        return a.second + (b.second - a.second) * (ms - a.first) / (b.first - a.first)
    }

    @Test
    fun theRealRide() {
        val r = replay()
        r.events.forEach { println("REPLAY $it") }
        val stations = route.stations.associateBy { it.name }
        r.alongAt.forEach { (ms, a) -> println(String.format(java.util.Locale.US, "REPLAY at %6.1f s: %.0f m along", ms / 1000.0, a)) }
        println("REPLAY stations " + stations.values.filter { it.distanceAlongTrack in 15000.0..20000.0 }.map { it.name to it.distanceAlongTrack.toInt() })
        println(String.format(java.util.Locale.US, "REPLAY error when GPS came back: %.0f m", r.errorAtGpsReturn))
        val sorted = r.tunnelErrors.sorted()
        println(String.format(java.util.Locale.US, "REPLAY tunnel error: median %.0f m, 90%% %.0f m, worst %.0f m, within 200 m %.0f%%, within 500 m %.0f%%, path %.0f m",
            sorted[sorted.size / 2], sorted[sorted.size * 9 / 10], sorted.last(), 100.0 * sorted.count { it <= 200 } / sorted.size, 100.0 * sorted.count { it <= 500 } / sorted.size, r.travelled))
        val stops = listOf("Krishna Rajendra Market", "Chickpete", "Nadaprabhu Kempegowda Station, Majestic (Green Line)")
        stops.forEach { name -> assertTrue("no stop at $name", r.events.any { it.endsWith("Stopped at $name") }) }
        assertTrue("false pass: ${r.events}", r.events.none { it.contains("Passed") })
        stops.zip(r.alongAt.values).forEach { (name, along) ->
            val off = kotlin.math.abs(along - stations.getValue(name).distanceAlongTrack)
            assertTrue("$off m from $name during its stop", off < 100.0)
        }
        assertTrue("${r.errorAtGpsReturn} m off when GPS came back", r.errorAtGpsReturn < 200.0)
        assertTrue("worst tunnel error ${sorted.last()} m", sorted.last() < 500.0)
    }

    private companion object {
        const val START_MS = (12 * 3600 + 33 * 60 + 33) * 1000L + 970L
        /** In each real stop, by the pressure and sensors; Majestic also by its Wi-Fi fixes. */
        val WATCH = listOf("12:41:58", "12:43:25", "12:45:30", "12:47:05")
        /** Moments to compare with the other logs by latitude and longitude. */
        val MOMENTS = listOf("12:41:13", "12:41:58", "12:42:40", "12:43:20", "12:44:10", "12:44:35", "12:45:20", "12:46:20", "12:47:05", "12:47:12")
    }
}
