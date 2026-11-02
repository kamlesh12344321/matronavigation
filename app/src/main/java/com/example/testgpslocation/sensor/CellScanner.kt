package com.example.testgpslocation.sensor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.testgpslocation.engine.RadioScan

/**
 * Mobile-network front end: the serving and neighbour cells (4G, 5G, 3G, 2G) with their
 * received power, every [SCAN_INTERVAL_MS]. Metro tunnels are covered by in-tunnel antennas, so
 * which cells are heard, how strongly, and where the phone hands over between them repeat along
 * the line: learnt in the radio map, they place the train with no GPS, no Wi-Fi and no internet.
 * Needs precise location permission and location turned on (Android hides cells otherwise).
 * All callbacks on the main thread.
 */
class CellScanner(context: Context) {

    interface Listener {
        fun onRadioScan(scan: RadioScan)
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val telephony: TelephonyManager? =
        if (appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
            appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        else null
    private val builder = CellScanBuilder()

    var listener: Listener? = null
    var isRunning = false
        private set

    /** The phone can read mobile cells at all (no SIM slot / telephony on tablets). */
    val available: Boolean get() = telephony != null

    /** Strongest serving cell of the latest scan, for the log. */
    val servingCell: CellReading? get() = builder.servingCell

    private val ticker = object : Runnable {
        override fun run() {
            requestCells()
            handler.postDelayed(this, SCAN_INTERVAL_MS)
        }
    }

    fun start() {
        if (isRunning || telephony == null) return
        isRunning = true
        handler.post(ticker)
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        handler.removeCallbacks(ticker)
    }

    @SuppressLint("MissingPermission") // checked in hasFineLocation
    private fun requestCells() {
        val tm = telephony ?: return
        if (!hasFineLocation()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Fresh readings from the modem (getAllCellInfo alone may return old ones).
                tm.requestCellInfoUpdate(ContextCompat.getMainExecutor(appContext), object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) = onCells(cellInfo)
                    override fun onError(errorCode: Int, detail: Throwable?) = onCells(safeAllCellInfo(tm))
                })
            } else {
                onCells(safeAllCellInfo(tm))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cell info unavailable", e)
        }
    }

    @SuppressLint("MissingPermission") // checked by the caller
    private fun safeAllCellInfo(tm: TelephonyManager): List<CellInfo>? =
        try { tm.allCellInfo } catch (e: SecurityException) { null }

    private fun onCells(cells: List<CellInfo>?) {
        if (!isRunning || cells.isNullOrEmpty()) return
        val now = SystemClock.elapsedRealtime()
        val readings = cells.filter { now - ageReferenceMs(it) <= MAX_AGE_MS }.mapNotNull(::readingOf)
        if (readings.isEmpty()) return
        listener?.onRadioScan(RadioScan(now, builder.build(readings, now)))
    }

    /** When the modem measured the cell, on the elapsed-realtime clock (ms). */
    private fun ageReferenceMs(cell: CellInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) cell.timestampMillis
        else @Suppress("DEPRECATION") (cell.timeStamp / 1_000_000L)

    private fun readingOf(cell: CellInfo): CellReading? = try {
        when {
            cell is CellInfoLte -> {
                val id = cell.cellIdentity
                val dbm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) cell.cellSignalStrength.rsrp else cell.cellSignalStrength.dbm
                reading("lte", plmn(id), id.tac, id.ci.toLong(), id.pci, id.earfcn, dbm, cell.isRegistered)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is CellInfoNr -> {
                val id = cell.cellIdentity as CellIdentityNr
                val ss = cell.cellSignalStrength as CellSignalStrengthNr
                val dbm = if (ss.ssRsrp != CellInfo.UNAVAILABLE) ss.ssRsrp else ss.csiRsrp
                reading("nr", plmnOf(id.mccString, id.mncString), id.tac, id.nci, id.pci, id.nrarfcn, dbm, cell.isRegistered)
            }
            cell is CellInfoWcdma -> {
                val id = cell.cellIdentity
                reading("wcdma", plmn(id), id.lac, id.cid.toLong(), id.psc, id.uarfcn, cell.cellSignalStrength.dbm, cell.isRegistered)
            }
            cell is CellInfoGsm -> {
                val id = cell.cellIdentity
                reading("gsm", plmn(id), id.lac, id.cid.toLong(), id.bsic, id.arfcn, cell.cellSignalStrength.dbm, cell.isRegistered)
            }
            else -> null // CDMA / TD-SCDMA: not used on Indian metro networks
        }
    } catch (e: Exception) {
        null // a modem reporting odd values must not stop the scan
    }

    private fun reading(tech: String, plmn: String?, area: Int, cellId: Long, pci: Int, channel: Int, dbm: Int, registered: Boolean): CellReading? {
        if (dbm == UNAVAILABLE || dbm !in MIN_DBM..MAX_DBM) return null
        val globalId = plmn != null && area != UNAVAILABLE && cellId != UNAVAILABLE.toLong() && cellId != UNAVAILABLE_LONG && cellId >= 0
        val key = when {
            globalId -> "${CellScanBuilder.CELL_PREFIX}$tech:$plmn:$area:$cellId"
            pci != UNAVAILABLE && channel != UNAVAILABLE -> "${CellScanBuilder.CELL_PREFIX}$tech:pci$pci@$channel"
            else -> return null
        }
        return CellReading(key, dbm, registered)
    }

    private fun plmn(id: android.telephony.CellIdentityLte): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) plmnOf(id.mccString, id.mncString)
        else @Suppress("DEPRECATION") plmnOf(id.mcc, id.mnc)

    private fun plmn(id: android.telephony.CellIdentityWcdma): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) plmnOf(id.mccString, id.mncString)
        else @Suppress("DEPRECATION") plmnOf(id.mcc, id.mnc)

    private fun plmn(id: android.telephony.CellIdentityGsm): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) plmnOf(id.mccString, id.mncString)
        else @Suppress("DEPRECATION") plmnOf(id.mcc, id.mnc)

    private fun plmnOf(mcc: String?, mnc: String?): String? =
        if (mcc.isNullOrEmpty() || mnc.isNullOrEmpty()) null else mcc + mnc

    private fun plmnOf(mcc: Int, mnc: Int): String? =
        if (mcc == UNAVAILABLE || mnc == UNAVAILABLE || mcc <= 0) null else String.format(java.util.Locale.US, "%03d%02d", mcc, mnc)

    private fun hasFineLocation() =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "CellScanner"
        const val SCAN_INTERVAL_MS = 5_000L
        /** Older readings describe where the train was, not where it is. */
        const val MAX_AGE_MS = 15_000L
        /** Plausible received power (dBm): RSRP / RSCP / RSSI. */
        const val MIN_DBM = -140
        const val MAX_DBM = -30
        private const val UNAVAILABLE = Int.MAX_VALUE // CellInfo.UNAVAILABLE (API 29+)
        private const val UNAVAILABLE_LONG = Long.MAX_VALUE // CellInfo.UNAVAILABLE_LONG
    }
}
