package com.example.testgpslocation.models

data class GpsPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long,
    val speedMeterPerSecond: Float = 0f,
    val bearingDegree: Float = 0f,
    val isCoarse: Boolean = false
)

data class TunnelProfile(
    val entryPoint: GpsPoint,
    val exitPoint: GpsPoint,
    val estimatedDistanceMeter: Double,
    val durationMillis: Long,
    val driftMeter: Double
)

class TunnelSession {
    var entryPoint: GpsPoint? = null
    var exitPoint: GpsPoint? = null
    var tunnelStartTime: Long = 0
    var tunnelEndTime: Long = 0
    var finalEstimatedDistanceMeter: Double = 0.0
    var maxEstimatedSpeedMeterPerSecond: Double = 0.0
    var lastHeadingDegree: Float = 0f

    val isActive: Boolean get() = entryPoint != null && exitPoint == null
    val durationMillis: Long
        get() = when {
            tunnelStartTime == 0L -> 0L
            tunnelEndTime > 0L -> tunnelEndTime - tunnelStartTime
            else -> System.currentTimeMillis() - tunnelStartTime
        }

    fun startTunnel(entry: GpsPoint) {
        entryPoint = entry
        exitPoint = null
        tunnelStartTime = System.currentTimeMillis()
        tunnelEndTime = 0
        finalEstimatedDistanceMeter = 0.0
        maxEstimatedSpeedMeterPerSecond = 0.0
        lastHeadingDegree = 0f
    }

    fun endTunnel(exit: GpsPoint) {
        exitPoint = exit
        tunnelEndTime = System.currentTimeMillis()
    }

    fun updateSessionData(distance: Double, speed: Double, heading: Float) {
        finalEstimatedDistanceMeter = distance
        lastHeadingDegree = heading
        if (speed > maxEstimatedSpeedMeterPerSecond) maxEstimatedSpeedMeterPerSecond = speed
    }
}

data class TunnelEstimate(
    val isInsideTunnel: Boolean,
    val estimatedDistanceFromEntryMeter: Double,
    val estimatedSpeedMeterPerSecond: Double,
    val headingDegree: Float,
    val gpsLostDurationMillis: Long,
    val confidence: String,
    val status: String
)

data class CellFingerprint(
    val cellId: String,
    val signalDbm: Int,
    val distanceFromEntryMeter: Double,
    val timestamp: Long
)

data class MetroPoint(
    val lat: Double,
    val lon: Double,
    var distanceFromStartMeters: Double = 0.0
)

data class MetroStation(
    val name: String,
    val lat: Double,
    val lon: Double
)

class MetroRoute {
    var city: String? = null
    var lineName: String? = null
    var fromStation: String? = null
    var toStation: String? = null
    var createdAtMillis: Long = 0
    val trackPoints = mutableListOf<MetroPoint>()
    val stations = mutableListOf<MetroStation>()

    val isValid: Boolean get() = trackPoints.size >= 2
}

data class MetroNavigationState(
    var valid: Boolean = false,
    var currentRoutePoint: MetroPoint? = null,
    var nearestStation: MetroStation? = null,
    var nextStation: MetroStation? = null,
    var previousStation: MetroStation? = null,
    var destinationName: String? = null,
    var distanceOnRouteMeters: Double = 0.0,
    var distanceToNextStationMeters: Double = 0.0,
    var directionText: String? = null
) {
    companion object {
        fun invalid() = MetroNavigationState(directionText = "Metro navigation unavailable")
    }
}

data class UiLocation(
    val latitude: Double,
    val longitude: Double,
    val isActive: Boolean,
    val label: String
)
