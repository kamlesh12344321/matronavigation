package com.example.testgpslocation.engine

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** The dominant hypothesis of a [TrackHmm]: distance along the line, 1-sigma spread, and its share of the probability. */
data class TrackBelief(val distance: Double, val sigma: Double, val modeMass: Double)

/**
 * Hidden Markov model of the train's position along one metro line.
 *
 * Hidden state: which [cellMeters] slice of the track the train is in. Every state lies on the
 * line, so this is the map constraint: the estimate can never leave the tunnel.
 *  - Transition ([predict]): the navigator's motion model. The belief is shifted by the
 *    distance the navigator moved and blurred by the growth of its uncertainty, with a small
 *    probability of being anywhere, so repeated strong observations can recover from a
 *    gross dead-reckoning error.
 *  - Emission ([update]): the likelihood of a radio observation for each cell (Wi-Fi
 *    fingerprints, Wi-Fi RTT ranges).
 *  - Output ([estimate]): the forward (filtering) posterior's dominant mode. A multi-modal
 *    belief is kept between observations and resolves as more evidence arrives.
 *
 * Pure Kotlin, not thread safe.
 */
class TrackHmm(val route: MetroRoute, private val cellMeters: Double = DEFAULT_CELL_METERS) {

    private val size = max(1, ceil(route.length / cellMeters).toInt() + 1)
    private var belief = DoubleArray(size)
    private var scratch = DoubleArray(size)

    var initialised = false
        private set

    fun cellCenter(i: Int): Double = min(i * cellMeters, route.length)

    /** Start from a Gaussian belief around [mean]. */
    fun reset(mean: Double, sigma: Double) {
        val s = max(sigma, cellMeters)
        for (i in 0 until size) {
            val z = (cellCenter(i) - mean) / s
            belief[i] = exp(-0.5 * z * z)
        }
        if (!normalise(belief)) { belief.fill(0.0); belief[cellOf(mean)] = 1.0 }
        initialised = true
    }

    /** Motion step: move by [shift] metres (signed, increasing distance) and blur by [sigma] metres. */
    fun predict(shift: Double, sigma: Double) {
        if (!initialised) return
        // Shift (mass that would leave the line stays at its end: the train cannot go further).
        if (shift != 0.0) {
            scratch.fill(0.0)
            val s = shift / cellMeters
            for (j in 0 until size) {
                val p = belief[j]
                if (p == 0.0) continue
                val t = (j + s).coerceIn(0.0, (size - 1).toDouble())
                val lo = floor(t).toInt()
                val frac = t - lo
                scratch[lo] += p * (1 - frac)
                if (frac > 0.0 && lo + 1 < size) scratch[lo + 1] += p * frac
            }
            swap()
        }
        // Blur with a Gaussian kernel, renormalised at the ends so no probability is lost.
        val sc = sigma / cellMeters
        if (sc >= MIN_BLUR_CELLS) {
            val half = min(ceil(3 * sc).toInt(), MAX_KERNEL_HALF_WIDTH)
            val kernel = DoubleArray(2 * half + 1) { k -> val z = (k - half) / sc; exp(-0.5 * z * z) }
            scratch.fill(0.0)
            for (j in 0 until size) {
                val p = belief[j]
                if (p < NEGLIGIBLE) continue
                val from = max(0, j - half); val to = min(size - 1, j + half)
                var norm = 0.0
                for (t in from..to) norm += kernel[t - j + half]
                for (t in from..to) scratch[t] += p * kernel[t - j + half] / norm
            }
            swap()
        }
        // Small chance of being anywhere (keeps every hypothesis alive).
        val uniform = TELEPORT_PROBABILITY / size
        for (i in 0 until size) belief[i] = belief[i] * (1 - TELEPORT_PROBABILITY) + uniform
        normalise(belief)
    }

    /**
     * Observation step with a per-position likelihood. Returns false and leaves the belief
     * unchanged when the observation is impossible everywhere (bad data).
     */
    fun update(likelihood: (Double) -> Double): Boolean {
        if (!initialised) return false
        var total = 0.0
        for (i in 0 until size) {
            val l = likelihood(cellCenter(i))
            val v = if (l.isNaN() || l < 0.0) 0.0 else belief[i] * l
            scratch[i] = v
            total += v
        }
        if (total <= 0.0 || total.isNaN() || total.isInfinite()) return false
        for (i in 0 until size) scratch[i] /= total
        swap()
        return true
    }

    /** The most probable position and the spread of the hypothesis around it. */
    fun estimate(): TrackBelief {
        var peak = 0
        for (i in 1 until size) if (belief[i] > belief[peak]) peak = i
        val floorP = belief[peak] * MODE_EDGE_FRACTION
        val valley = belief[peak] * VALLEY_FRACTION
        // Grow the mode down its slopes; stop at the edge or where the belief starts rising
        // again after a valley (another hypothesis), so two places are never merged into one.
        var lo = peak; var hi = peak
        while (lo > 0 && belief[lo - 1] >= floorP && !(belief[lo - 1] > belief[lo] && belief[lo] < valley)) lo--
        while (hi < size - 1 && belief[hi + 1] >= floorP && !(belief[hi + 1] > belief[hi] && belief[hi] < valley)) hi++
        var mass = 0.0; var mean = 0.0
        for (i in lo..hi) { mass += belief[i]; mean += belief[i] * cellCenter(i) }
        mean /= mass
        var variance = 0.0
        for (i in lo..hi) { val d = cellCenter(i) - mean; variance += belief[i] * d * d }
        variance /= mass
        return TrackBelief(mean, sqrt(variance + cellMeters * cellMeters / 12.0), mass)
    }

    private fun cellOf(distance: Double) = (distance / cellMeters).toInt().coerceIn(0, size - 1)

    private fun swap() { val t = belief; belief = scratch; scratch = t }

    private fun normalise(a: DoubleArray): Boolean {
        val total = a.sum()
        if (total <= 0.0 || total.isNaN()) return false
        for (i in a.indices) a[i] /= total
        return true
    }

    companion object {
        const val DEFAULT_CELL_METERS = 10.0
        const val TELEPORT_PROBABILITY = 0.02
        const val MODE_EDGE_FRACTION = 0.05
        const val VALLEY_FRACTION = 0.3
        private const val MIN_BLUR_CELLS = 0.3
        private const val MAX_KERNEL_HALF_WIDTH = 300
        private const val NEGLIGIBLE = 1e-12
    }
}
