package com.example.testgpslocation.models

/**
 * One location fix.
 *
 * @param timestamp UTC wall-clock time of the fix (ms) — for display and logs only.
 * @param elapsedRealtimeMs monotonic time of the fix (ms since boot) — used for all maths,
 *        so clock changes / time-zone changes cannot break speed checks.
 * @param isGnss true when the fix comes from the satellite receiver (GPS/GNSS provider);
 *        false for network (cell / Wi-Fi) or fused fixes that may not be satellite based.
 */
data class GpsPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long,
    val speedMeterPerSecond: Float = 0f,
    val bearingDegree: Float = 0f,
    val hasSpeed: Boolean = false,
    val hasBearing: Boolean = false,
    val elapsedRealtimeMs: Long = 0L,
    val isGnss: Boolean = true,
    val isMock: Boolean = false,
    val altitudeMeters: Double? = null,
    /** From the GPS provider but with no satellite in use: the phone chip's own estimate. */
    val isChipEstimate: Boolean = false
)
