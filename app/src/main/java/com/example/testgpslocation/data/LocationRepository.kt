package com.example.testgpslocation.data

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import com.example.testgpslocation.models.*
import com.example.testgpslocation.utils.GeoToolbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

class LocationRepository(private val context: Context) {

    private companion object {
        const val TAG = "LocationRepository"
    }

    private val profiles = mutableListOf<TunnelProfile>()

    fun saveProfile(profile: TunnelProfile) {
        profiles.add(profile)
    }

    fun findMatchingProfile(entry: GpsPoint?): TunnelProfile? {
        if (entry == null) return null
        return profiles.filter { GeoToolbox.calculateDistance(entry, it.entryPoint) < 100.0 }
            .minByOrNull { GeoToolbox.calculateDistance(entry, it.entryPoint) }
    }

    suspend fun resolvePlace(latitude: Double, longitude: Double): String = withContext(Dispatchers.IO) {
        val geocoder = Geocoder(context, Locale.getDefault())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) { continuation.resume(formatAddress(addresses)) }
                        override fun onError(errorMessage: String?) { continuation.resume("Not resolved") }
                    })
                }
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                formatAddress(addresses)
            }
        } catch (e: Exception) { "Not resolved" }
    }

    private fun formatAddress(addresses: List<Address>?): String {
        if (addresses.isNullOrEmpty()) return "Not resolved"
        val address = addresses[0]
        val parts = mutableListOf<String>()
        address.locality?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        address.adminArea?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) } ?: address.subAdminArea?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        address.countryName?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        return if (parts.isEmpty()) "Not resolved" else parts.joinToString(", ")
    }
}
