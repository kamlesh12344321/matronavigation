package com.example.testgpslocation.utils

import com.example.testgpslocation.models.GpsPoint
import kotlin.math.*

object GeoToolbox {

    private const val EARTH_RADIUS_METER = 6371000.0

    @JvmStatic
    fun calculateDistance(start: GpsPoint?, end: GpsPoint?): Double {
        if (start == null || end == null) return 0.0
        return calculateDistance(start.latitude, start.longitude, end.latitude, end.longitude)
    }

    @JvmStatic
    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)

        val a = sin(dLat / 2).pow(2) + cos(rLat1) * cos(rLat2) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METER * c
    }

    @JvmStatic
    fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)

        val y = sin(dLon) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    @JvmStatic
    fun bearingDifference(b1: Double, b2: Double): Double {
        val diff = abs(b1 - b2) % 360.0
        return if (diff > 180.0) 360.0 - diff else diff
    }

    @JvmStatic
    fun moveLocation(lat: Double, lon: Double, distanceMeters: Double, bearingDegrees: Double): Pair<Double, Double> {
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lon)
        val bearing = Math.toRadians(bearingDegrees)
        val angularDistance = distanceMeters / EARTH_RADIUS_METER

        val lat2 = asin(sin(lat1) * cos(angularDistance) + cos(lat1) * sin(angularDistance) * cos(bearing))
        val lon2 = lon1 + atan2(sin(bearing) * sin(angularDistance) * cos(lat1), cos(angularDistance) - sin(lat1) * sin(lat2))

        return Math.toDegrees(lat2) to Math.toDegrees(lon2)
    }
}
