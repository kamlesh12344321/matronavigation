package com.example.testgpslocation.engine

import com.example.testgpslocation.utils.GeoToolbox
import kotlin.math.cos
import kotlin.math.sqrt

data class TrackPoint(val lat: Double, val lon: Double)

data class MetroStation(
    val name: String,
    val lat: Double,
    val lon: Double,
    /** Distance of the station along the track polyline, filled in by [MetroRoute]. */
    val distanceAlongTrack: Double = 0.0
)

/** Result of projecting a coordinate onto the track polyline. */
data class TrackProjection(
    val distanceAlongTrack: Double,
    /** Perpendicular distance from the coordinate to the track. */
    val offsetMeters: Double
)

/**
 * An immutable metro line: an ordered polyline plus its stations, each positioned by
 * distance along the polyline. All lookups are O(log n) or O(n) at most, so they are cheap
 * enough to run on every sensor tick.
 */
class MetroRoute(
    val id: String,
    val lineName: String,
    val fromStation: String?,
    val toStation: String?,
    points: List<TrackPoint>,
    rawStations: List<MetroStation>
) {
    val points: List<TrackPoint> = points.toList()
    private val cumulative = DoubleArray(this.points.size)
    val length: Double
    val stations: List<MetroStation>

    init {
        for (i in 1 until this.points.size) {
            val a = this.points[i - 1]; val b = this.points[i]
            cumulative[i] = cumulative[i - 1] + GeoToolbox.calculateDistance(a.lat, a.lon, b.lat, b.lon)
        }
        length = if (this.points.isEmpty()) 0.0 else cumulative.last()

        val placed = rawStations.mapNotNull { s ->
            if (this.points.size < 2) return@mapNotNull null
            val p = project(s.lat, s.lon)
            if (p.offsetMeters > MAX_STATION_OFFSET_METERS) null else s.copy(distanceAlongTrack = p.distanceAlongTrack)
        }.sortedBy { it.distanceAlongTrack }

        // Drop duplicates: same name, or two stations practically on top of each other.
        val unique = mutableListOf<MetroStation>()
        for (s in placed) {
            val dup = unique.any { it.name == s.name || kotlin.math.abs(it.distanceAlongTrack - s.distanceAlongTrack) < MIN_STATION_SPACING_METERS }
            if (!dup) unique.add(s)
        }
        stations = unique
    }

    val isValid: Boolean get() = points.size >= 2 && length > 0.0

    fun project(lat: Double, lon: Double): TrackProjection {
        var bestAlong = 0.0
        var bestOffset = Double.MAX_VALUE
        val metersPerDegLat = 111_320.0
        for (i in 0 until points.size - 1) {
            val a = points[i]; val b = points[i + 1]
            val metersPerDegLon = metersPerDegLat * cos(Math.toRadians(a.lat))
            val vx = (b.lon - a.lon) * metersPerDegLon; val vy = (b.lat - a.lat) * metersPerDegLat
            val px = (lon - a.lon) * metersPerDegLon; val py = (lat - a.lat) * metersPerDegLat
            val len2 = vx * vx + vy * vy
            val t = if (len2 == 0.0) 0.0 else ((px * vx + py * vy) / len2).coerceIn(0.0, 1.0)
            val dx = px - t * vx; val dy = py - t * vy
            val offset = sqrt(dx * dx + dy * dy)
            if (offset < bestOffset) {
                bestOffset = offset
                bestAlong = cumulative[i] + t * (cumulative[i + 1] - cumulative[i])
            }
        }
        return TrackProjection(bestAlong, bestOffset)
    }

    fun pointAt(distance: Double): TrackPoint {
        if (points.isEmpty()) return TrackPoint(0.0, 0.0)
        val d = distance.coerceIn(0.0, length)
        val i = segmentIndex(d)
        if (i >= points.size - 1) return points.last()
        val segLen = cumulative[i + 1] - cumulative[i]
        val t = if (segLen == 0.0) 0.0 else (d - cumulative[i]) / segLen
        val a = points[i]; val b = points[i + 1]
        return TrackPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }

    /** Bearing of travel at [distance] when moving in [direction] (+1 = increasing distance). */
    fun bearingAt(distance: Double, direction: Int): Double {
        if (points.size < 2) return 0.0
        val i = segmentIndex(distance.coerceIn(0.0, length)).coerceAtMost(points.size - 2)
        val a = points[i]; val b = points[i + 1]
        val forward = GeoToolbox.calculateBearing(a.lat, a.lon, b.lat, b.lon)
        return if (direction >= 0) forward else (forward + 180.0) % 360.0
    }

    /** Next station strictly ahead of [distance] in [direction]. */
    fun nextStation(distance: Double, direction: Int, tolerance: Double = ON_STATION_TOLERANCE_METERS): MetroStation? =
        if (direction >= 0) stations.firstOrNull { it.distanceAlongTrack > distance + tolerance }
        else stations.lastOrNull { it.distanceAlongTrack < distance - tolerance }

    /** Last station at or behind [distance] in [direction]. */
    fun previousStation(distance: Double, direction: Int): MetroStation? =
        if (direction >= 0) stations.lastOrNull { it.distanceAlongTrack <= distance + ON_STATION_TOLERANCE_METERS }
        else stations.firstOrNull { it.distanceAlongTrack >= distance - ON_STATION_TOLERANCE_METERS }

    fun nearestStation(distance: Double): MetroStation? =
        stations.minByOrNull { kotlin.math.abs(it.distanceAlongTrack - distance) }

    /**
     * Signed change of travel heading (degrees, clockwise positive) when riding from distance
     * [from] to distance [to], in either direction. Curves of the line, used to match the turn
     * the phone's gyroscope measures.
     */
    fun turnBetween(from: Double, to: Double): Double =
        cumulativeTurn[segmentIndex(to.coerceIn(0.0, length)).coerceAtMost(points.size - 2).coerceAtLeast(0)] -
            cumulativeTurn[segmentIndex(from.coerceIn(0.0, length)).coerceAtMost(points.size - 2).coerceAtLeast(0)]

    /** Heading of segment i relative to segment 0, unwrapped; mapping noise (tiny or folded segments) ignored. */
    private val cumulativeTurn: DoubleArray by lazy {
        val out = DoubleArray(kotlin.math.max(1, this.points.size - 1))
        var prevBearing: Double? = null
        var acc = 0.0
        for (i in 0 until this.points.size - 1) {
            val a = this.points[i]; val b = this.points[i + 1]
            if (GeoToolbox.calculateDistance(a.lat, a.lon, b.lat, b.lon) >= MIN_TURN_SEGMENT_METERS) {
                val bearing = GeoToolbox.calculateBearing(a.lat, a.lon, b.lat, b.lon)
                prevBearing?.let { pb ->
                    var d = bearing - pb
                    if (d > 180) d -= 360
                    if (d < -180) d += 360
                    if (kotlin.math.abs(d) <= MAX_VERTEX_TURN_DEG) acc += d
                }
                prevBearing = bearing
            }
            out[i] = acc
        }
        out
    }

    /**
     * Fastest speed that the curves within [lookAhead] metres ahead allow, counting the braking
     * ([brakeRate], m/s²) needed to be slow enough by each of them. A curve's own limit is
     * √([lateralAccel] × radius); on straight track there is no limit (Double.MAX_VALUE).
     */
    fun curveSpeedAhead(distance: Double, direction: Int, lateralAccel: Double, brakeRate: Double, lookAhead: Double): Double {
        var best = Double.MAX_VALUE
        var k = 0.0
        while (k <= lookAhead) {
            val radius = radiusAt(distance + direction * k)
            if (radius < STRAIGHT_RADIUS_METERS) best = minOf(best, sqrt(lateralAccel * radius + 2.0 * brakeRate * k))
            k += CURVE_STEP_METERS
        }
        return best
    }

    /** Slowest curve speed between two distances (either order): what limits a run there. */
    fun curveSpeedBetween(a: Double, b: Double, lateralAccel: Double): Double {
        var best = Double.MAX_VALUE
        var s = minOf(a, b)
        while (s <= maxOf(a, b)) {
            val radius = radiusAt(s)
            if (radius < STRAIGHT_RADIUS_METERS) best = minOf(best, sqrt(lateralAccel * radius))
            s += CURVE_STEP_METERS
        }
        return best
    }

    /** Curve radius (m) at [distance]: the turn between the 30 m of track before it and after it. */
    fun radiusAt(distance: Double): Double {
        if (radii.isEmpty()) return Double.MAX_VALUE
        val i = (distance.coerceIn(0.0, length) / CURVE_STEP_METERS).toInt().coerceIn(0, radii.size - 1)
        return radii[i]
    }

    private val radii: DoubleArray by lazy {
        if (points.size < 2 || length < 2 * CURVE_CHORD_METERS) return@lazy DoubleArray(0)
        DoubleArray((length / CURVE_STEP_METERS).toInt() + 1) { i ->
            val s = i * CURVE_STEP_METERS
            if (s < CURVE_CHORD_METERS || s > length - CURVE_CHORD_METERS) return@DoubleArray Double.MAX_VALUE
            val p0 = pointAt(s - CURVE_CHORD_METERS); val p1 = pointAt(s); val p2 = pointAt(s + CURVE_CHORD_METERS)
            var turn = GeoToolbox.calculateBearing(p1.lat, p1.lon, p2.lat, p2.lon) - GeoToolbox.calculateBearing(p0.lat, p0.lon, p1.lat, p1.lon)
            if (turn > 180) turn -= 360
            if (turn < -180) turn += 360
            val rad = Math.toRadians(kotlin.math.abs(turn))
            if (rad < 1e-6) Double.MAX_VALUE else maxOf(MIN_CURVE_RADIUS_METERS, CURVE_CHORD_METERS / rad)
        }
    }

    private fun segmentIndex(distance: Double): Int {
        var lo = 0; var hi = points.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cumulative[mid] <= distance) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {
        const val MAX_STATION_OFFSET_METERS = 400.0
        const val MIN_STATION_SPACING_METERS = 60.0
        const val ON_STATION_TOLERANCE_METERS = 3.0
        private const val MIN_TURN_SEGMENT_METERS = 2.0
        /** A sharper bend between two mapped segments is a mapping artefact, not a track curve. */
        private const val MAX_VERTEX_TURN_DEG = 100.0
        private const val CURVE_STEP_METERS = 10.0
        private const val CURVE_CHORD_METERS = 30.0
        /** Tighter than this is a mapping kink, not a metro curve. */
        private const val MIN_CURVE_RADIUS_METERS = 60.0
        /** Wider than this is straight track for any metro speed. */
        private const val STRAIGHT_RADIUS_METERS = 3_000.0
    }
}

/**
 * Joins way geometries into one continuous polyline.
 *
 * Starts from [startIndex] and greedily attaches the way whose endpoint is closest to either
 * end of the chain (reversing it if needed), as long as the gap is under [maxGapMeters].
 * This fixes ways stored in the opposite direction and ways listed out of order.
 */
object TrackStitcher {
    fun stitch(ways: List<List<TrackPoint>>, startIndex: Int = 0, maxGapMeters: Double = 50.0): List<TrackPoint> {
        val usable = ways.filter { it.size >= 2 }
        if (usable.isEmpty()) return emptyList()
        val start = ways.getOrNull(startIndex)?.takeIf { it.size >= 2 } ?: usable.first()
        val remaining = usable.toMutableList().apply { remove(start) }
        val chain = ArrayDeque(start)

        while (remaining.isNotEmpty()) {
            var bestIdx = -1; var bestGap = Double.MAX_VALUE; var attachToTail = true; var reverse = false
            val head = chain.first(); val tail = chain.last()
            remaining.forEachIndexed { idx, way ->
                val f = way.first(); val l = way.last()
                val options = listOf(
                    Triple(dist(tail, f), true, false),
                    Triple(dist(tail, l), true, true),
                    Triple(dist(head, l), false, false),
                    Triple(dist(head, f), false, true)
                )
                options.forEach { (gap, toTail, rev) ->
                    if (gap < bestGap) { bestGap = gap; bestIdx = idx; attachToTail = toTail; reverse = rev }
                }
            }
            if (bestIdx < 0 || bestGap > maxGapMeters) break
            val way = remaining.removeAt(bestIdx).let { if (reverse) it.reversed() else it }
            if (attachToTail) {
                way.forEach { if (dist(chain.last(), it) > 0.5) chain.addLast(it) }
            } else {
                way.asReversed().forEach { if (dist(chain.first(), it) > 0.5) chain.addFirst(it) }
            }
        }
        return chain.toList()
    }

    private fun dist(a: TrackPoint, b: TrackPoint) = GeoToolbox.calculateDistance(a.lat, a.lon, b.lat, b.lon)
}
