package com.example.testgpslocation.engine

import kotlin.math.roundToLong

/**
 * Decides which positions go to the log and the map: every new position, and an unchanged one
 * only every [maxQuietMs] (a train standing at a platform gives one row, not one every 2 s, and
 * the log still never goes quiet for longer than that). "Unchanged" is at the log's precision
 * (6 decimals, about 0.1 m).
 */
class PositionDeduper(private val maxQuietMs: Long = 30_000L) {

    enum class Decision { SKIP, LOG, LOG_AND_MARK }

    private var lastLat = Long.MIN_VALUE
    private var lastLon = Long.MIN_VALUE
    private var lastMs = 0L

    fun decide(lat: Double, lon: Double, elapsedMs: Long): Decision {
        val la = (lat * 1e6).roundToLong()
        val lo = (lon * 1e6).roundToLong()
        val moved = la != lastLat || lo != lastLon
        if (!moved && elapsedMs - lastMs < maxQuietMs) return Decision.SKIP
        lastLat = la; lastLon = lo; lastMs = elapsedMs
        return if (moved) Decision.LOG_AND_MARK else Decision.LOG
    }

    /** The next position is treated as new (e.g. after the map was cleared). */
    fun reset() {
        lastLat = Long.MIN_VALUE
        lastLon = Long.MIN_VALUE
    }
}
