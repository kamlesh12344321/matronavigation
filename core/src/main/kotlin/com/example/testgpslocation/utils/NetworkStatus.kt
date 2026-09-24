package com.example.testgpslocation.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

enum class NetworkQuality(val label: String) { NONE("Lost"), WEAK("Weak"), GOOD("Connected") }

object NetworkStatus {
    private const val WEAK_BANDWIDTH_KBPS = 250
    private const val WEAK_CELL_SIGNAL_DBM = -110

    /**
     * NONE: no network or no internet capability.
     * WEAK: connected but not validated (captive portal / no real internet), very low estimated
     *       bandwidth, or very poor cellular signal.
     * GOOD: otherwise.
     */
    fun quality(context: Context): NetworkQuality = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        when {
            caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> NetworkQuality.NONE
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> NetworkQuality.WEAK
            caps.linkDownstreamBandwidthKbps in 1 until WEAK_BANDWIDTH_KBPS -> NetworkQuality.WEAK
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                caps.signalStrength != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED &&
                caps.signalStrength < WEAK_CELL_SIGNAL_DBM -> NetworkQuality.WEAK
            else -> NetworkQuality.GOOD
        }
    } catch (e: SecurityException) {
        NetworkQuality.NONE
    }

    fun isConnected(context: Context) = quality(context) != NetworkQuality.NONE

    fun label(context: Context) = quality(context).label
}
