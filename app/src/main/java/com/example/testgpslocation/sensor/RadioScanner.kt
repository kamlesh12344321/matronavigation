package com.example.testgpslocation.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.testgpslocation.engine.RadioRange
import com.example.testgpslocation.engine.RadioScan

/** What radio positioning this phone can do right now. */
data class RadioCapabilities(
    val wifi: Boolean,
    val wifiScanning: Boolean,
    val rtt: Boolean
)

/**
 * Wi-Fi front end for indoor / underground positioning. All callbacks on the main thread.
 *
 *  - Wi-Fi: requests a scan every [WIFI_SCAN_INTERVAL_MS] (Android allows four per two minutes
 *    in the foreground) and also listens for scans other apps trigger. Results older than
 *    [WIFI_MAX_AGE_MS] are dropped: a throttled request returns the previous, stale list.
 *  - Wi-Fi RTT (Android 9+, 802.11mc access points): distance to responders whose position has
 *    been learnt ([isKnownTransmitter]), tried every [RTT_INTERVAL_MS].
 * Emits a [RadioScan] for each fresh Wi-Fi result and one with the RTT ranges when they arrive.
 * Every part is optional: missing hardware, a disabled radio or a denied permission just
 * leaves that part out.
 */
class RadioScanner(context: Context) {

    interface Listener {
        fun onRadioScan(scan: RadioScan)
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val pm = appContext.packageManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val rttManager: WifiRttManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT))
            appContext.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager
        else null

    var listener: Listener? = null
    /** True when the radio map knows where this transmitter is (worth ranging to). */
    var isKnownTransmitter: (String) -> Boolean = { false }

    var isRunning = false
        private set
    private var receiverRegistered = false
    private var lastWifiResultNanos = 0L
    private var rttBusy = false

    /** 802.11mc responders from the latest scan, with when they were seen. */
    private var responders: List<android.net.wifi.ScanResult> = emptyList()
    private var respondersAtMs = 0L

    val capabilities: RadioCapabilities
        get() = RadioCapabilities(
            wifi = wifiManager != null,
            wifiScanning = wifiManager != null && (safe { wifiManager.isWifiEnabled } == true || safe { wifiManager.isScanAlwaysAvailable } == true),
            rtt = rttManager != null
        )

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onWifiResults()
    }

    private val wifiTicker = object : Runnable {
        override fun run() {
            requestWifiScan()
            handler.postDelayed(this, WIFI_SCAN_INTERVAL_MS)
        }
    }

    private val rttTicker = object : Runnable {
        override fun run() {
            rangeResponders()
            handler.postDelayed(this, RTT_INTERVAL_MS)
        }
    }

    fun start() {
        if (isRunning || wifiManager == null) return
        isRunning = true
        try {
            ContextCompat.registerReceiver(appContext, wifiReceiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi scan receiver unavailable", e)
        }
        handler.post(wifiTicker)
        if (rttManager != null) handler.postDelayed(rttTicker, RTT_INTERVAL_MS)
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        handler.removeCallbacks(wifiTicker)
        handler.removeCallbacks(rttTicker)
        if (receiverRegistered) {
            try { appContext.unregisterReceiver(wifiReceiver) } catch (_: Exception) {}
            receiverRegistered = false
        }
        responders = emptyList()
        rttBusy = false
    }

    // ---- Wi-Fi --------------------------------------------------------------------------------

    @Suppress("DEPRECATION") // startScan is deprecated but remains the only way to request a scan
    private fun requestWifiScan() {
        if (!hasFineLocation()) return
        try { wifiManager?.startScan() } catch (e: Exception) { Log.w(TAG, "Wi-Fi scan request failed", e) }
    }

    @SuppressLint("MissingPermission") // checked in hasFineLocation
    private fun onWifiResults() {
        if (!isRunning || !hasFineLocation()) return
        val results = try { wifiManager?.scanResults } catch (e: SecurityException) { null } ?: return
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val fresh = results.filter { r ->
            val ageMs = (nowNanos / 1000 - r.timestamp) / 1000 // ScanResult.timestamp is µs since boot
            ageMs in 0..WIFI_MAX_AGE_MS && !r.BSSID.isNullOrEmpty()
        }
        if (fresh.isEmpty()) return
        val newest = fresh.maxOf { it.timestamp }
        if (newest == lastWifiResultNanos) return // same scan delivered again
        lastWifiResultNanos = newest

        val signals = HashMap<String, Int>(fresh.size)
        fresh.forEach { signals["wifi:" + it.BSSID.lowercase()] = it.level }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            responders = fresh.filter { it.is80211mcResponder }
            respondersAtMs = SystemClock.elapsedRealtime()
        }
        listener?.onRadioScan(RadioScan(SystemClock.elapsedRealtime(), signals))
    }

    // ---- Wi-Fi RTT ----------------------------------------------------------------------------

    private fun rangeResponders() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) rangeRespondersP()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    @SuppressLint("MissingPermission") // checked in canRange
    private fun rangeRespondersP() {
        val rtt = rttManager ?: return
        if (rttBusy || !canRange() || !rtt.isAvailable) return
        if (SystemClock.elapsedRealtime() - respondersAtMs > RESPONDER_MAX_AGE_MS) return
        val targets = responders.filter { isKnownTransmitter("wifi:" + it.BSSID.lowercase()) }
            .sortedByDescending { it.level }
            .take(RangingRequest.getMaxPeers())
        if (targets.isEmpty()) return
        val request = try { RangingRequest.Builder().addAccessPoints(targets).build() } catch (e: Exception) { return }
        rttBusy = true
        try {
            rtt.startRanging(request, ContextCompat.getMainExecutor(appContext), object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) { rttBusy = false }
                override fun onRangingResults(results: MutableList<RangingResult>) {
                    rttBusy = false
                    if (!isRunning) return
                    val ranges = results.filter { it.status == RangingResult.STATUS_SUCCESS && it.macAddress != null }.map {
                        RadioRange(
                            "wifi:" + it.macAddress.toString().lowercase(),
                            it.distanceMm / 1000.0,
                            (it.distanceStdDevMm / 1000.0).coerceAtLeast(MIN_RTT_STD_METERS)
                        )
                    }.filter { it.distanceMeters >= 0.0 }
                    if (ranges.isNotEmpty()) listener?.onRadioScan(RadioScan(SystemClock.elapsedRealtime(), emptyMap(), ranges))
                }
            })
        } catch (e: Exception) {
            rttBusy = false
            Log.w(TAG, "RTT ranging failed", e)
        }
    }

    private fun canRange(): Boolean = hasFineLocation() &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED)

    // ---- Permissions ----------------------------------------------------------------------------

    private fun hasFineLocation() =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private inline fun <T> safe(block: () -> T): T? = try { block() } catch (_: Exception) { null }

    companion object {
        private const val TAG = "RadioScanner"
        const val WIFI_SCAN_INTERVAL_MS = 30_000L
        const val WIFI_MAX_AGE_MS = 10_000L
        const val RTT_INTERVAL_MS = 2_000L
        const val RESPONDER_MAX_AGE_MS = 60_000L
        const val MIN_RTT_STD_METERS = 1.0
    }
}
