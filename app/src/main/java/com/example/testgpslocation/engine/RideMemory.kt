package com.example.testgpslocation.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/**
 * What the phone remembers about each station-to-station run it has ridden (no server, no
 * timetable): how long the train was moving on it. Every run of a real line has its own speed
 * (restrictions, curves, gradients); from the run time and its length the run's cruise speed is
 * worked out, and the next ride on the same run uses that instead of a line-wide guess.
 *
 * Also remembers how strongly the mounted phone vibrates when the vehicle stands vs. moves
 * ([vibration]), so stops are recognised on this phone, mount and train from the first minute.
 *
 * Pure Kotlin; not thread safe. Persisted with [serialize] / [parse].
 */
class RideMemory {

    /**
     * Vibration (spread of the acceleration, m/s²) while the vehicle stands and while it moves,
     * learnt from GPS speed. Between the two lies a stillness threshold that fits this phone in
     * its mount on this vehicle, instead of one fixed number for every phone and train.
     */
    class VibrationProfile {
        /** Geometric means (vibration spans orders of magnitude), NaN until learnt. */
        var stopped = Double.NaN; private set
        var moving = Double.NaN; private set
        var stoppedSamples = 0; private set
        var movingSamples = 0; private set

        internal fun add(vibration: Double, vehicleMoving: Boolean) {
            val v = ln(vibration.coerceAtLeast(MIN_VIBRATION))
            if (vehicleMoving) {
                moving = if (movingSamples == 0) exp(v) else exp(ln(moving) + (v - ln(moving)) / min(movingSamples + 1, MAX_VIBRATION_SAMPLES))
                movingSamples++
            } else {
                stopped = if (stoppedSamples == 0) exp(v) else exp(ln(stopped) + (v - ln(stopped)) / min(stoppedSamples + 1, MAX_VIBRATION_SAMPLES))
                stoppedSamples++
            }
        }

        internal fun set(stopped: Double, stoppedSamples: Int, moving: Double, movingSamples: Int) {
            this.stopped = stopped; this.stoppedSamples = stoppedSamples
            this.moving = moving; this.movingSamples = movingSamples
        }

        /** Below this the vehicle stands; null until both levels are learnt and clearly apart. */
        val stillThreshold: Double?
            get() = if (stoppedSamples >= MIN_VIBRATION_SAMPLES && movingSamples >= MIN_VIBRATION_SAMPLES &&
                moving >= MIN_VIBRATION_SEPARATION * stopped) sqrt(stopped * moving) else null
    }

    val vibration = VibrationProfile()

    class Segment(var meters: Double, var movingSeconds: Double, var samples: Int)

    private val segments = mutableMapOf<String, Segment>()

    var version = 0L
        private set

    val size: Int get() = segments.size

    /** A completed run of [meters] of track that took [movingSeconds] of motion. */
    fun record(routeId: String, from: String, to: String, meters: Double, movingSeconds: Double): Boolean {
        if (meters <= 0.0 || movingSeconds <= 0.0) return false
        if (listOf(routeId, from, to).any { n -> n.any { it == '\t' || it == '\n' } || n.contains(SEP) }) return false
        val avg = meters / movingSeconds
        if (avg !in MIN_AVERAGE_MPS..MAX_AVERAGE_MPS) return false // a long hold or a bad run
        val key = key(routeId, from, to)
        val seg = segments[key]
        if (seg == null) {
            segments[key] = Segment(meters, movingSeconds, 1)
        } else {
            val alpha = 1.0 / min(seg.samples + 1, MAX_AVERAGING_SAMPLES)
            seg.movingSeconds += alpha * (movingSeconds - seg.movingSeconds)
            seg.meters = meters
            seg.samples++
        }
        version++
        return true
    }

    /** One vibration reading while GPS shows the vehicle standing ([vehicleMoving] false) or moving. */
    fun learnVibration(level: Double, vehicleMoving: Boolean) {
        if (level.isNaN() || level < 0.0) return
        vibration.add(level, vehicleMoving)
        if ((vibration.stoppedSamples + vibration.movingSamples) % VIBRATION_SAVE_EVERY == 0) version++
    }

    /** Cruise speed of this run learnt from earlier rides, or null if it has never been ridden. */
    fun cruise(routeId: String, from: String, to: String): Double? =
        segments[key(routeId, from, to)]?.let { cruiseFromRun(it.meters, it.movingSeconds) }

    fun serialize(): String = buildString {
        append(HEADER).append('\n')
        segments.forEach { (k, s) ->
            append("S\t").append(k).append('\t').append(s.meters).append('\t').append(s.movingSeconds).append('\t').append(s.samples).append('\n')
        }
        if (vibration.stoppedSamples + vibration.movingSamples > 0) {
            append("V\t").append(vibration.stopped).append('\t').append(vibration.stoppedSamples).append('\t')
                .append(vibration.moving).append('\t').append(vibration.movingSamples).append('\n')
        }
    }

    companion object {
        private const val HEADER = "# ride memory v2"
        private const val SEP = "\u001f" // unit separator: never part of a station or line name
        const val MIN_AVERAGE_MPS = 3.0
        const val MAX_AVERAGE_MPS = 35.0
        const val MAX_AVERAGING_SAMPLES = 5
        /** Typical metro acceleration / braking used to turn a run time into a cruise speed. */
        const val TYPICAL_ACCEL_MPS2 = 1.0
        const val TYPICAL_DECEL_MPS2 = 1.0
        /** Readings (0.1 s each) needed of both kinds before the learnt threshold is used. */
        const val MIN_VIBRATION_SAMPLES = 50
        const val MAX_VIBRATION_SAMPLES = 2000
        /** Moving must vibrate at least this many times more than standing to tell them apart. */
        const val MIN_VIBRATION_SEPARATION = 2.0
        private const val MIN_VIBRATION = 1e-4
        private const val VIBRATION_SAVE_EVERY = 100

        private fun key(routeId: String, from: String, to: String) = routeId + SEP + from + SEP + to

        /**
         * Cruise speed of a trapezoidal run (accelerate, cruise, brake) covering [meters] in
         * [seconds]: solves D = v·T − v²·(1/2a + 1/2b); a short run that never reaches cruise gives
         * the triangular peak speed.
         */
        fun cruiseFromRun(meters: Double, seconds: Double): Double {
            val k = 1.0 / (2 * TYPICAL_ACCEL_MPS2) + 1.0 / (2 * TYPICAL_DECEL_MPS2)
            val disc = seconds * seconds - 4 * k * meters
            val v = if (disc >= 0) (seconds - sqrt(disc)) / (2 * k) else sqrt(meters / k)
            return v.coerceIn(UndergroundNavigator.MIN_CRUISE_MPS, UndergroundNavigator.MAX_CRUISE_MPS)
        }

        fun parse(text: String): RideMemory {
            val m = RideMemory()
            text.lineSequence().forEach { line ->
                val f = line.split('\t')
                if (f.size >= 5 && f[0] == "S" && f[1].count { it.toString() == SEP } == 2) {
                    val meters = f[2].toDoubleOrNull()
                    val secs = f[3].toDoubleOrNull()
                    val n = f[4].toIntOrNull()
                    if (meters != null && secs != null && n != null && meters > 0 && secs > 0 && n > 0 &&
                        meters / secs in MIN_AVERAGE_MPS..MAX_AVERAGE_MPS) m.segments[f[1]] = Segment(meters, secs, n)
                }
                if (f.size >= 5 && f[0] == "V") {
                    val stopped = f[1].toDoubleOrNull(); val ns = f[2].toIntOrNull()
                    val moving = f[3].toDoubleOrNull(); val nm = f[4].toIntOrNull()
                    if (stopped != null && ns != null && moving != null && nm != null && ns >= 0 && nm >= 0) {
                        m.vibration.set(stopped, ns, moving, nm)
                    }
                }
            }
            return m
        }
    }
}
