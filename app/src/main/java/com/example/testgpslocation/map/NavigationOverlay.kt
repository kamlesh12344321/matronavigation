package com.example.testgpslocation.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.MotionEvent
import com.example.testgpslocation.engine.MapEvent
import com.example.testgpslocation.engine.MetroRoute
import com.example.testgpslocation.engine.NavState
import com.example.testgpslocation.engine.PositionSource
import com.example.testgpslocation.engine.TrackPoint
import com.example.testgpslocation.engine.TrailPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.util.GeoPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

/**
 * Draws the metro line and its stations, a marker for every position (coloured by its source),
 * navigation events, and the current position with its error circle and direction of travel.
 * Everything comes from data on the phone, so it draws underground with no map tiles at all.
 * Main thread only.
 */
class NavigationOverlay(metrics: DisplayMetrics) : Overlay() {

    var route: MetroRoute? = null
    /** Oldest first. */
    var trail: List<TrailPoint> = emptyList()
    var events: List<MapEvent> = emptyList()
    /** Tunnel test: the real GPS path (hidden from navigation), drawn under the estimate. */
    var truthTrail: List<TrackPoint> = emptyList()
    var current: NavState? = null
    /** Called with a description of a tapped marker. */
    var onMarkerTapped: ((String) -> Unit)? = null

    private val dp = metrics.density
    private val geo = GeoPoint(0.0, 0.0)
    private val pt = Point()
    private val prev = Point()
    private val path = Path()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5 * dp; color = LINE_COLOR
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val trailLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; color = TRAIL_LINE_COLOR
    }
    private val truthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * dp; color = TRUTH_COLOR
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * dp }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, metrics)
    }
    private val labelHaloPaint = Paint(labelPaint).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3 * dp
    }

    override fun draw(c: Canvas, pj: Projection) {
        route?.let { drawRoute(c, pj, it) }
        drawTruth(c, pj)
        drawTrail(c, pj)
        drawEvents(c, pj)
        current?.let { drawCurrent(c, pj, it) }
    }

    private fun drawRoute(c: Canvas, pj: Projection, r: MetroRoute) {
        path.reset()
        r.points.forEachIndexed { i, p ->
            toPixels(pj, p.lat, p.lon)
            if (i == 0) path.moveTo(pt.x.toFloat(), pt.y.toFloat()) else path.lineTo(pt.x.toFloat(), pt.y.toFloat())
        }
        c.drawPath(path, linePaint)
        val labels = pj.zoomLevel >= STATION_LABEL_ZOOM
        r.stations.forEach { s ->
            toPixels(pj, s.lat, s.lon)
            dot(c, STATION_COLOR, STATION_RADIUS_DP, Color.BLACK)
            if (labels) {
                val x = pt.x + 8 * dp; val y = pt.y + 4 * dp
                c.drawText(s.name, x, y, labelHaloPaint)
                c.drawText(s.name, x, y, labelPaint)
            }
        }
    }

    private fun drawTruth(c: Canvas, pj: Projection) {
        if (truthTrail.isEmpty()) return
        path.reset()
        truthTrail.forEachIndexed { i, p ->
            toPixels(pj, p.lat, p.lon)
            if (i == 0) path.moveTo(pt.x.toFloat(), pt.y.toFloat()) else path.lineTo(pt.x.toFloat(), pt.y.toFloat())
        }
        c.drawPath(path, truthPaint)
        // A ring where the vehicle really is now.
        outlinePaint.color = TRUTH_COLOR
        outlinePaint.strokeWidth = 2.5f * dp
        c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), CURRENT_RADIUS_DP * dp, outlinePaint)
        outlinePaint.strokeWidth = 1.5f * dp
    }

    private fun drawTrail(c: Canvas, pj: Projection) {
        // A faint line joins the markers in time order, then each marker on top.
        trail.forEachIndexed { i, p ->
            toPixels(pj, p.latitude, p.longitude)
            if (i > 0) c.drawLine(prev.x.toFloat(), prev.y.toFloat(), pt.x.toFloat(), pt.y.toFloat(), trailLinePaint)
            prev.set(pt.x, pt.y)
        }
        trail.forEach { p ->
            toPixels(pj, p.latitude, p.longitude)
            dot(c, colorFor(p.source), TRAIL_RADIUS_DP, MARKER_OUTLINE)
        }
    }

    private fun drawEvents(c: Canvas, pj: Projection) {
        events.forEach { e ->
            toPixels(pj, e.latitude, e.longitude)
            val r = EVENT_RADIUS_DP * dp
            path.reset()
            path.moveTo(pt.x.toFloat(), pt.y - r); path.lineTo(pt.x + r, pt.y.toFloat())
            path.lineTo(pt.x.toFloat(), pt.y + r); path.lineTo(pt.x - r, pt.y.toFloat()); path.close()
            fillPaint.color = EVENT_COLOR
            c.drawPath(path, fillPaint)
            outlinePaint.color = Color.WHITE
            c.drawPath(path, outlinePaint)
        }
    }

    private fun drawCurrent(c: Canvas, pj: Projection, s: NavState) {
        toPixels(pj, s.latitude, s.longitude)
        val color = colorFor(s.source)
        val radius = pj.metersToPixels(s.uncertaintyMeters.toFloat(), s.latitude, pj.zoomLevel)
        if (radius > CURRENT_RADIUS_DP * dp) {
            fillPaint.color = withAlpha(color, 0x30)
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), radius, fillPaint)
            outlinePaint.color = withAlpha(color, 0x90)
            c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), radius, outlinePaint)
        }
        if (s.speedMps > MIN_ARROW_SPEED_MPS) {
            // Direction of travel: a triangle ahead of the marker (north is up).
            c.save()
            c.rotate(s.headingDeg.toFloat(), pt.x.toFloat(), pt.y.toFloat())
            path.reset()
            path.moveTo(pt.x.toFloat(), pt.y - 17 * dp)
            path.lineTo(pt.x + 7 * dp, pt.y - 7 * dp)
            path.lineTo(pt.x - 7 * dp, pt.y - 7 * dp)
            path.close()
            fillPaint.color = color
            c.drawPath(path, fillPaint)
            c.restore()
        }
        dot(c, color, CURRENT_RADIUS_DP, Color.WHITE, outlineWidthDp = 2.5f)
    }

    /** Draws a filled circle at [pt]. */
    private fun dot(c: Canvas, color: Int, radiusDp: Float, outline: Int, outlineWidthDp: Float = 1.5f) {
        fillPaint.color = color
        c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), radiusDp * dp, fillPaint)
        outlinePaint.color = outline
        outlinePaint.strokeWidth = outlineWidthDp * dp
        c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), radiusDp * dp, outlinePaint)
        outlinePaint.strokeWidth = 1.5f * dp
    }

    private fun toPixels(pj: Projection, lat: Double, lon: Double) {
        geo.setCoords(lat, lon)
        pj.toPixels(geo, pt)
    }

    // ---- Taps ---------------------------------------------------------------------------------

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val callback = onMarkerTapped ?: return false
        val pj = mapView.projection
        var best: String? = null
        var bestDist = TAP_RADIUS_DP * dp
        fun consider(lat: Double, lon: Double, describe: () -> String) {
            toPixels(pj, lat, lon)
            val d = hypot(pt.x - e.x, pt.y - e.y)
            if (d <= bestDist) { bestDist = d; best = describe() }
        }
        route?.stations?.forEach { s -> consider(s.lat, s.lon) { "Station: ${s.name}" } }
        trail.forEach { p ->
            consider(p.latitude, p.longitude) {
                String.format(Locale.US, "[%s] %s ±%.0f m · %.0f km/h%s", time(p.timestamp), p.source.label,
                    p.uncertaintyMeters, p.speedMps * 3.6, p.nextStation?.let { " · next: $it" } ?: "")
            }
        }
        events.forEach { ev -> consider(ev.latitude, ev.longitude) { "[${time(ev.timestamp)}] ${ev.text}" } }
        val text = best ?: return false
        callback(text)
        return true
    }

    private fun time(ms: Long) = timeFormat.format(Date(ms))

    companion object {
        const val STATION_LABEL_ZOOM = 14.0
        private const val TRAIL_RADIUS_DP = 4f
        private const val STATION_RADIUS_DP = 5f
        private const val EVENT_RADIUS_DP = 7f
        private const val CURRENT_RADIUS_DP = 8f
        private const val TAP_RADIUS_DP = 20f
        private const val MIN_ARROW_SPEED_MPS = 1.0

        private val LINE_COLOR = Color.parseColor("#B078909C")
        private val TRAIL_LINE_COLOR = Color.parseColor("#55FFFFFF")
        private val STATION_COLOR = Color.WHITE
        private val MARKER_OUTLINE = Color.parseColor("#AA000000")
        val EVENT_COLOR = Color.parseColor("#FFB74D")
        val TRUTH_COLOR = Color.parseColor("#E6FFFFFF")

        /** Marker colour for each position source (also used by the legend). */
        fun colorFor(source: PositionSource): Int = Color.parseColor(when (source) {
            PositionSource.GPS -> "#00E676"
            PositionSource.TRACK -> "#4FC3F7"
            PositionSource.NETWORK -> "#CE93D8"
            PositionSource.RADIO -> "#80CBC4"
            PositionSource.DEAD_RECKONING -> "#FF7043"
            PositionSource.NONE -> "#9E9E9E"
        })

        private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
    }
}
