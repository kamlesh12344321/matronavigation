package com.example.testgpslocation.engine

import com.example.testgpslocation.utils.GeoToolbox
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * Test drive (no metro needed): [RECORDING] a route with GPS, [READY] on a recorded route with
 * GPS in use, [TUNNEL] on it with GPS hidden from navigation and kept only as the reference.
 */
enum class TestDriveMode { OFF, RECORDING, READY, TUNNEL }

data class TestDriveState(
    val mode: TestDriveMode = TestDriveMode.OFF,
    val routeName: String? = null,
    val recordedMeters: Double = 0.0,
    val recordedStations: Int = 0,
    /** Estimate vs. real GPS during the tunnel test (metres); null before the first comparison. */
    val lastErrorMeters: Double? = null,
    val meanErrorMeters: Double? = null,
    val maxErrorMeters: Double? = null,
    val comparisons: Int = 0
)

/**
 * Records a test-drive route from GPS on a first trip in any vehicle: the road becomes the line
 * and its stops become stations, so a later trip on the same road can be navigated without GPS
 * exactly like a metro line (no metro needed to test the POC).
 *
 * Stations: the start, the end, every stop of [AUTO_STATION_STOP_MS] or longer, and every place
 * the user marks. Pure Kotlin, one thread.
 */
class TestRouteRecorder {

    private val raw = ArrayList<TrackPoint>()
    private val stops = ArrayList<TrackPoint>()
    private var lastFix: TrackPoint? = null
    private var stopAnchor: TrackPoint? = null
    private var stillSinceMs = 0L
    private var stopRecorded = false

    /** Metres of road recorded so far. */
    var lengthMeters = 0.0
        private set

    /** Stations so far, the start included. */
    val stationCount: Int get() = stops.size + (if (raw.isEmpty()) 0 else 1)

    /** A satellite fix; poor ones are ignored. [speedMps] null when the fix has no speed. */
    fun onFix(lat: Double, lon: Double, accuracy: Float, speedMps: Float?, elapsedMs: Long) {
        if (accuracy <= 0f || accuracy > MAX_ACCURACY_METERS || lat.isNaN() || lon.isNaN()) return
        val p = TrackPoint(lat, lon)
        lastFix = p

        // Standing: slow (or no speed reported) and still within a small circle. Leaving the circle
        // is movement whatever the speed says (some receivers report 0 while moving).
        val anchor = stopAnchor
        val slow = speedMps == null || speedMps < STOP_SPEED_MPS
        val standing = anchor != null && slow && dist(anchor, p) <= STOP_RADIUS_METERS
        if (standing) {
            if (!stopRecorded && elapsedMs - stillSinceMs >= AUTO_STATION_STOP_MS) {
                addStop(anchor!!)
                stopRecorded = true
            }
        } else {
            stopAnchor = p
            stillSinceMs = elapsedMs
            stopRecorded = false
        }

        // The line only grows while moving: GPS wobble at a stop would draw a spike that points
        // the wrong way at the station (and a departure there would be read as reversing).
        val last = raw.lastOrNull()
        if (last == null) raw += p
        else if (!standing && dist(last, p) >= POINT_SPACING_METERS) { lengthMeters += dist(last, p); raw += p }
    }

    /** A station where the user is now. False when there is no fix yet or a station is this close. */
    fun markStation(): Boolean = lastFix?.let { addStop(it) } ?: false

    /** The finished route, or null when it is too short to navigate. */
    fun build(id: String, name: String): MetroRoute? {
        if (raw.size < 2 || lengthMeters < MIN_ROUTE_METERS) return null
        val line = simplify(removeSpikes(raw), SIMPLIFY_TOLERANCE_METERS)
        // A stop where the trip ended is the End station, not another stop.
        val middle = stops.filter { dist(it, raw.last()) >= MetroRoute.MIN_STATION_SPACING_METERS }
        val stations = listOf(MetroStation(START, raw.first().lat, raw.first().lon)) +
            middle.mapIndexed { i, s -> MetroStation("Stop ${i + 1}", s.lat, s.lon) } +
            MetroStation(END, raw.last().lat, raw.last().lon)
        return MetroRoute(id, name, START, END, line, stations).takeIf { it.isValid && it.stations.size >= 2 }
    }

    private fun addStop(p: TrackPoint): Boolean {
        val near = (stops + listOfNotNull(raw.firstOrNull())).any { dist(it, p) < MetroRoute.MIN_STATION_SPACING_METERS }
        if (near) return false
        stops += p
        return true
    }

    private fun dist(a: TrackPoint, b: TrackPoint) = GeoToolbox.calculateDistance(a.lat, a.lon, b.lat, b.lon)

    companion object {
        const val START = "Start"
        const val END = "End"
        const val MAX_ACCURACY_METERS = 25f
        const val POINT_SPACING_METERS = 15.0
        const val STOP_SPEED_MPS = 0.7f
        const val STOP_RADIUS_METERS = 12.0
        const val AUTO_STATION_STOP_MS = 15_000L
        const val MIN_ROUTE_METERS = 300.0
        /** GPS wobble of a few metres is not a bend in the road (it would upset curve matching). */
        const val SIMPLIFY_TOLERANCE_METERS = 4.0

        /** A short out-and-back this sharp is a bad fix, not the road. */
        private const val SPIKE_TURN_DEG = 120.0
        private const val SPIKE_MAX_LEG_METERS = 40.0

        /** Drops single points where the line doubles back over a short distance (GPS outliers). */
        fun removeSpikes(points: List<TrackPoint>): List<TrackPoint> {
            if (points.size < 3) return points
            val out = ArrayList<TrackPoint>(points.size)
            out += points[0]
            for (i in 1 until points.size - 1) {
                val a = out.last(); val b = points[i]; val c = points[i + 1]
                val legs = GeoToolbox.calculateDistance(a.lat, a.lon, b.lat, b.lon) <= SPIKE_MAX_LEG_METERS &&
                    GeoToolbox.calculateDistance(b.lat, b.lon, c.lat, c.lon) <= SPIKE_MAX_LEG_METERS
                val turn = GeoToolbox.bearingDifference(
                    GeoToolbox.calculateBearing(a.lat, a.lon, b.lat, b.lon), GeoToolbox.calculateBearing(b.lat, b.lon, c.lat, c.lon))
                if (!(legs && turn > SPIKE_TURN_DEG)) out += b
            }
            out += points.last()
            return out
        }

        /** Douglas–Peucker: keeps the points that matter for the shape within [toleranceMeters]. */
        fun simplify(points: List<TrackPoint>, toleranceMeters: Double): List<TrackPoint> {
            if (points.size <= 2) return points
            val mLat = 111_320.0
            val mLon = mLat * cos(Math.toRadians(points[0].lat))
            val x = DoubleArray(points.size) { (points[it].lon - points[0].lon) * mLon }
            val y = DoubleArray(points.size) { (points[it].lat - points[0].lat) * mLat }
            val keep = BooleanArray(points.size).also { it[0] = true; it[points.size - 1] = true }
            val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to points.size - 1) }
            while (stack.isNotEmpty()) {
                val (a, b) = stack.removeLast()
                val dx = x[b] - x[a]; val dy = y[b] - y[a]
                val len = max(hypot(dx, dy), 1e-9)
                var worst = -1; var worstD = toleranceMeters
                for (i in a + 1 until b) {
                    val d = kotlin.math.abs(dy * (x[i] - x[a]) - dx * (y[i] - y[a])) / len
                    if (d > worstD) { worstD = d; worst = i }
                }
                if (worst >= 0) { keep[worst] = true; stack.add(a to worst); stack.add(worst to b) }
            }
            return points.filterIndexed { i, _ -> keep[i] }
        }
    }
}
