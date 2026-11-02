package com.example.testgpslocation.sensor

/**
 * One mobile cell heard by the phone.
 *
 * @param key "cell:<tech>:<plmn>:<area>:<cell id>" when the cell's global identity is known
 *        (the serving cell, sometimes neighbours), else "cell:<tech>:pci<pci>@<channel>"
 *        (neighbours usually report only their physical id and frequency).
 * @param dbm received power: RSRP for LTE / 5G, RSCP for 3G, RSSI for 2G.
 * @param registered true for the cell the phone is attached to (one per SIM).
 */
data class CellReading(val key: String, val dbm: Int, val registered: Boolean)

/**
 * Turns cell readings into radio-map signals. When the serving cell of a network changes (a
 * handover, which on a metro line happens at about the same spot every ride) the scans of the
 * next [handoverWindowMs] also carry "cellho:<from>><to>", a checkpoint the radio map learns
 * like any transmitter. Handovers are tracked per network (PLMN), so two SIMs never fake one.
 * Pure Kotlin, one thread.
 */
class CellScanBuilder(private val handoverWindowMs: Long = HANDOVER_WINDOW_MS) {

    private val servingByNetwork = mutableMapOf<String, String>()
    private val recentHandovers = mutableMapOf<String, Long>()

    /** Serving cell (strongest registered one) of the latest scan, for the log. */
    var servingCell: CellReading? = null
        private set

    fun build(readings: List<CellReading>, nowMs: Long): Map<String, Int> {
        val signals = HashMap<String, Int>()
        readings.forEach { r -> signals[r.key] = maxOf(r.dbm, signals[r.key] ?: Int.MIN_VALUE) }

        val registered = readings.filter { it.registered && networkOf(it.key) != null }
        registered.groupBy { networkOf(it.key)!! }.forEach { (network, cells) ->
            val now = cells.maxByOrNull { it.dbm }!!.key
            val before = servingByNetwork.put(network, now)
            if (before != null && before != now) recentHandovers["$HANDOVER_PREFIX$before>$now"] = nowMs
        }
        servingCell = registered.maxByOrNull { it.dbm }

        recentHandovers.entries.removeAll { nowMs - it.value > handoverWindowMs }
        recentHandovers.keys.forEach { signals[it] = HANDOVER_SIGNAL_DBM }
        return signals
    }

    companion object {
        const val CELL_PREFIX = "cell:"
        const val HANDOVER_PREFIX = "cellho:"
        /** Strength given to a handover marker (it is not a radio signal, just "heard here"). */
        const val HANDOVER_SIGNAL_DBM = -60
        const val HANDOVER_WINDOW_MS = 4_000L

        /** The network (PLMN, e.g. "40445") of a cell with a global identity; null for "pci" keys. */
        fun networkOf(key: String): String? {
            if (!key.startsWith(CELL_PREFIX)) return null
            val parts = key.split(':')
            return if (parts.size == 5) parts[2] else null
        }
    }
}
