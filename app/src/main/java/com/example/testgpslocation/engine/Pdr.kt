package com.example.testgpslocation.engine

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/** Pedestrian dead reckoning: step length and walking direction from the phone's sensors. */
object Pdr {

    const val DEFAULT_STEP_METERS = 0.7
    private const val MIN_STEP_METERS = 0.45
    private const val MAX_STEP_METERS = 0.9
    private const val MIN_HORIZONTAL = 0.3

    /**
     * Step length from walking cadence (steps per second). Faster cadence means longer strides;
     * linear model after Renaudin et al. (2012), clamped to adult walking range.
     */
    fun stepLength(cadenceHz: Double): Double =
        if (cadenceHz <= 0.0 || cadenceHz.isNaN()) DEFAULT_STEP_METERS
        else (0.3 + 0.2 * cadenceHz).coerceIn(MIN_STEP_METERS, MAX_STEP_METERS)

    /**
     * Direction the phone faces (degrees from north) from a row-major device→world
     * (East, North, Up) rotation matrix. Uses the top edge when the phone is held flat and the
     * camera direction when it is held upright; null when neither points horizontally.
     */
    fun headingFromRotation(r: FloatArray): Double? {
        if (r.size < 9) return null
        val topE = r[1].toDouble(); val topN = r[4].toDouble()
        val camE = -r[2].toDouble(); val camN = -r[5].toDouble()
        val topH = hypot(topE, topN); val camH = hypot(camE, camN)
        if (max(topH, camH) < MIN_HORIZONTAL) return null
        val (e, n) = if (topH >= camH) topE to topN else camE to camN
        return (Math.toDegrees(atan2(e, n)) + 360.0) % 360.0
    }
}
