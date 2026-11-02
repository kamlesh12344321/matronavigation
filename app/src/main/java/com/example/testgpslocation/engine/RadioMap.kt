package com.example.testgpslocation.engine

import com.example.testgpslocation.sensor.CellScanBuilder
import com.example.testgpslocation.utils.GeoToolbox
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One radio observation window from [com.example.testgpslocation.sensor.RadioScanner].
 *
 * @param signals received power (dBm) per transmitter. Keys: "wifi:<bssid>" for access points,
 *        "cell:…" for mobile cells and "cellho:<from>><to>" for a fresh handover (see
 *        [com.example.testgpslocation.sensor.CellScanBuilder]).
 * @param ranges Wi-Fi RTT (IEEE 802.11mc) distances measured in this window, if any.
 */
data class RadioScan(
    val elapsedMs: Long,
    val signals: Map<String, Int>,
    val ranges: List<RadioRange> = emptyList()
)

/** Wi-Fi round-trip-time distance to the access point [key]. */
data class RadioRange(val key: String, val distanceMeters: Double, val stdDevMeters: Double)

/** Where a transmitter was heard strongest while the position was known: its approximate location. */
data class RadioAnchor(val lat: Double, val lon: Double, val sigmaMeters: Double, val rssi: Double)

/** A place where the phone heard [signals] (mean RSSI per transmitter) while its position was known. */
class RadioFingerprint(
    var lat: Double,
    var lon: Double,
    var sigmaMeters: Double,
    val signals: MutableMap<String, Double>,
    var samples: Int
)

data class FingerprintMatch(val fingerprint: RadioFingerprint, val weight: Double)

/** A position worked out from radio alone (no track), with a 1-sigma accuracy. */
data class RadioFix(val lat: Double, val lon: Double, val accuracyMeters: Double)

/**
 * Self-learnt Wi-Fi and mobile-cell fingerprint database.
 *
 * Google's crowd-sourced Wi-Fi / cell database is not available to apps, so the phone builds
 * its own: every scan taken while the position is known (good GPS, or standing at a station the
 * train was snapped to) is stored as a fingerprint. Underground, a live scan is compared with
 * the stored fingerprints (RSSI distance over the transmitters heard in both) to find where it
 * was most probably taken. Each transmitter also gets an [RadioAnchor] — where it was heard
 * strongest — used for Wi-Fi RTT ranging.
 *
 * Pure Kotlin; not thread safe (use from one thread). Persisted with [serialize] / [parse].
 */
class RadioMap {

    private val fingerprints = mutableListOf<RadioFingerprint>()
    /** Inverted index: transmitter → fingerprints that contain it. */
    private val index = mutableMapOf<String, MutableList<RadioFingerprint>>()
    private val anchors = mutableMapOf<String, RadioAnchor>()

    /** Incremented on every change, so the caller knows when to save. */
    var version = 0L
        private set

    val size: Int get() = fingerprints.size

    fun anchor(key: String): RadioAnchor? = anchors[key]

    /** Store [scan] as heard at a known position. Returns true if the map changed. */
    fun learn(scan: RadioScan, lat: Double, lon: Double, sigmaMeters: Double): Boolean {
        val live = usable(scan.signals)
        if (live.isEmpty() || sigmaMeters <= 0.0) return false

        val nearest = fingerprints.minByOrNull { GeoToolbox.calculateDistance(it.lat, it.lon, lat, lon) }
        val fp = if (nearest != null && GeoToolbox.calculateDistance(nearest.lat, nearest.lon, lat, lon) <= MERGE_RADIUS_METERS) {
            // Same place again: refine the position and the mean RSSI of each transmitter.
            val w1 = nearest.samples / (nearest.sigmaMeters * nearest.sigmaMeters)
            val w2 = 1.0 / (sigmaMeters * sigmaMeters)
            nearest.lat = (nearest.lat * w1 + lat * w2) / (w1 + w2)
            nearest.lon = (nearest.lon * w1 + lon * w2) / (w1 + w2)
            nearest.sigmaMeters = min(nearest.sigmaMeters, sigmaMeters)
            val alpha = 1.0 / min(nearest.samples + 1, MAX_AVERAGING_SAMPLES)
            live.forEach { (k, rssi) ->
                val old = nearest.signals[k]
                nearest.signals[k] = if (old == null) rssi.toDouble() else old + alpha * (rssi - old)
            }
            nearest.samples++
            nearest
        } else {
            RadioFingerprint(lat, lon, sigmaMeters, live.mapValuesTo(mutableMapOf()) { it.value.toDouble() }, 1)
                .also { fingerprints.add(it) }
        }
        trimSignals(fp)
        fp.signals.keys.forEach { k -> index.getOrPut(k) { mutableListOf() }.let { if (fp !in it) it.add(fp) } }

        live.forEach { (k, rssi) ->
            val a = anchors[k]
            val stronger = a == null || rssi > a.rssi + ANCHOR_RSSI_MARGIN_DB
            val sameButSharper = a != null && kotlin.math.abs(rssi - a.rssi) <= ANCHOR_RSSI_MARGIN_DB && sigmaMeters < a.sigmaMeters
            if (stronger || sameButSharper) anchors[k] = RadioAnchor(lat, lon, sigmaMeters, rssi.toDouble())
        }
        while (fingerprints.size > MAX_FINGERPRINTS) evict()
        version++
        return true
    }

    /** Stored fingerprints that look like [scan], best first. */
    fun match(scan: RadioScan, limit: Int = MAX_MATCHES): List<FingerprintMatch> {
        val live = usable(scan.signals)
        if (live.isEmpty()) return emptyList()
        val candidates = LinkedHashSet<RadioFingerprint>()
        live.keys.forEach { k -> index[k]?.let(candidates::addAll) }

        // Which radios this scan listened to (a cell scan says nothing about Wi-Fi, and back).
        val liveKinds = live.keys.mapTo(HashSet()) { kindOf(it) }
        val out = ArrayList<FingerprintMatch>()
        for (fp in candidates) {
            val commonByKind = IntArray(Kind.values().size)
            var common = 0
            var sumSq = 0.0
            live.forEach { (k, rssi) ->
                val ref = fp.signals[k]
                if (ref == null) {
                    // A handover just happened here; a place where none was ever seen is unlikely.
                    if (kindOf(k) == Kind.HANDOVER) { sumSq += square(rssi - Kind.HANDOVER.floorDbm) }
                    return@forEach
                }
                common++
                commonByKind[kindOf(k).ordinal]++
                sumSq += square(rssi - ref)
            }
            // Enough transmitters in common for at least one kind (two Wi-Fi access points, or one cell).
            if (Kind.values().none { commonByKind[it.ordinal] >= it.minCommon }) continue
            // Absence is evidence too: a transmitter that was loud at this place would be heard
            // if we were there. Count each missing loud one as heard at the noise floor.
            var terms = common + live.keys.count { kindOf(it) == Kind.HANDOVER && it !in fp.signals }
            var comparable = 0
            fp.signals.forEach { (k, ref) ->
                val kind = kindOf(k)
                if (kind !in liveKinds) return@forEach
                comparable++
                if (ref < kind.missingLoudDbm || k in live) return@forEach
                sumSq += square(ref - kind.floorDbm)
                terms++
            }
            val rms = sqrt(sumSq / terms)
            val coverage = common.toDouble() / min(live.size, comparable)
            val weight = exp(-rms * rms / (2 * SIGNAL_SIGMA_DB * SIGNAL_SIGMA_DB)) * coverage
            if (weight >= MIN_MATCH_WEIGHT) out.add(FingerprintMatch(fp, weight))
        }
        out.sortByDescending { it.weight }
        return if (out.size > limit) out.subList(0, limit).toList() else out
    }

    /**
     * Position from radio alone: the weighted centre of the best-matching fingerprints.
     * Null if nothing known was heard.
     */
    fun locate(scan: RadioScan): RadioFix? {
        val matches = match(scan, LOCATE_MATCHES).filter { it.weight >= MIN_LOCATE_WEIGHT }
        if (matches.isEmpty()) return null
        val total = matches.sumOf { it.weight }
        val lat = matches.sumOf { it.fingerprint.lat * it.weight } / total
        val lon = matches.sumOf { it.fingerprint.lon * it.weight } / total
        val spread = sqrt(matches.sumOf {
            val d = GeoToolbox.calculateDistance(lat, lon, it.fingerprint.lat, it.fingerprint.lon)
            d * d * it.weight
        } / total)
        val refSigma = matches.sumOf { it.fingerprint.sigmaMeters * it.weight } / total
        return RadioFix(lat, lon, spread + refSigma + FINGERPRINT_SPREAD_METERS)
    }

    // ---- Persistence ------------------------------------------------------------------------

    /** Plain-text form: one "F" line per fingerprint, one "A" line per anchor. */
    fun serialize(): String = buildString(fingerprints.size * 400 + anchors.size * 80) {
        // Plain appends (Double.toString is locale independent): this runs on the main thread.
        append(HEADER).append('\n')
        fingerprints.forEach { fp ->
            append("F\t").append(fp.lat).append('\t').append(fp.lon).append('\t').append(round1(fp.sigmaMeters))
                .append('\t').append(fp.samples).append('\t')
            var first = true
            fp.signals.forEach { (k, v) ->
                if (!first) append(';')
                append(k).append('=').append(round1(v))
                first = false
            }
            append('\n')
        }
        anchors.forEach { (k, a) ->
            append("A\t").append(k).append('\t').append(a.lat).append('\t').append(a.lon).append('\t')
                .append(round1(a.sigmaMeters)).append('\t').append(round1(a.rssi)).append('\n')
        }
    }

    private fun round1(v: Double) = Math.round(v * 10.0) / 10.0

    private fun evict() {
        // Drop the least-confirmed place.
        val victim = fingerprints.minByOrNull { it.samples } ?: return
        fingerprints.remove(victim)
        victim.signals.keys.forEach { k ->
            val list = index[k] ?: return@forEach
            list.remove(victim)
            if (list.isEmpty()) { index.remove(k); anchors.remove(k) }
        }
    }

    /** Keeps the loudest [MAX_SIGNALS_PER_FINGERPRINT] of each kind (cell and Wi-Fi levels differ). */
    private fun trimSignals(fp: RadioFingerprint) {
        if (fp.signals.size <= MAX_SIGNALS_PER_FINGERPRINT) return
        val keep = fp.signals.entries.groupBy { kindOf(it.key) }.values
            .flatMap { group -> group.sortedByDescending { it.value }.take(MAX_SIGNALS_PER_FINGERPRINT) }
            .mapTo(HashSet()) { it.key }
        val dropped = fp.signals.keys.filter { it !in keep }
        dropped.forEach { k ->
            fp.signals.remove(k)
            index[k]?.remove(fp)
        }
    }

    private fun addParsed(fp: RadioFingerprint) {
        fingerprints.add(fp)
        fp.signals.keys.forEach { k -> index.getOrPut(k) { mutableListOf() }.add(fp) }
    }

    companion object {
        private const val HEADER = "# radio map v1"

        const val MIN_RSSI = -95
        /** A transmitter this loud at a place would not be missed by a scan taken there. */
        const val MISSING_LOUD_RSSI = -80
        const val MERGE_RADIUS_METERS = 20.0
        const val MAX_FINGERPRINTS = 5000
        const val MAX_SIGNALS_PER_FINGERPRINT = 30
        const val MAX_AVERAGING_SAMPLES = 10
        const val ANCHOR_RSSI_MARGIN_DB = 3.0
        const val MIN_COMMON_WIFI = 2
        const val SIGNAL_SIGMA_DB = 8.0
        const val MIN_MATCH_WEIGHT = 0.05
        const val MAX_MATCHES = 5
        const val LOCATE_MATCHES = 3
        const val MIN_LOCATE_WEIGHT = 0.2
        /** How far from the fingerprint's recorded position the same scan can still be taken. */
        const val FINGERPRINT_SPREAD_METERS = 25.0
        /** Bluetooth beacons saved by earlier versions: no longer scanned, so dropped on load. */
        private const val LEGACY_BLE_PREFIX = "ble:"
        /** Mobile cells: received power down to this is still a usable reading (RSRP / RSCP). */
        const val CELL_MIN_DBM = -130
        const val CELL_MISSING_LOUD_DBM = -100
        const val CELL_FLOOR_DBM = -120

        /** How each kind of transmitter is heard and compared. */
        private enum class Kind(val minDbm: Int, val maxDbm: Int, val missingLoudDbm: Double, val floorDbm: Double, val minCommon: Int) {
            /** One Wi-Fi access point is heard over hundreds of metres: two must agree. */
            WIFI(MIN_RSSI, -1, MISSING_LOUD_RSSI.toDouble(), MIN_RSSI.toDouble(), MIN_COMMON_WIFI),
            /** One mobile cell identifies its stretch of the line. */
            CELL(CELL_MIN_DBM, -30, CELL_MISSING_LOUD_DBM.toDouble(), CELL_FLOOR_DBM.toDouble(), 1),
            /** A serving-cell change happens at one spot; not hearing one says nothing. */
            HANDOVER(CELL_MIN_DBM, -1, Double.POSITIVE_INFINITY, CELL_FLOOR_DBM.toDouble(), 1)
        }

        private fun kindOf(key: String): Kind = when {
            key.startsWith(CellScanBuilder.HANDOVER_PREFIX) -> Kind.HANDOVER
            key.startsWith(CellScanBuilder.CELL_PREFIX) -> Kind.CELL
            else -> Kind.WIFI
        }

        /** A mobile-cell reading or handover marker (as opposed to Wi-Fi). */
        fun isCell(key: String) = kindOf(key) != Kind.WIFI

        private fun square(v: Double) = v * v

        /** In the plausible range for its kind and with a key the text format can hold. */
        private fun usable(signals: Map<String, Int>): Map<String, Int> =
            signals.filter { (k, v) ->
                val kind = kindOf(k)
                v >= kind.minDbm && v <= kind.maxDbm && k.isNotEmpty() && k.none { it == '=' || it == ';' || it == '\t' || it == '\n' }
            }

        /** Reads [serialize] output; malformed lines are skipped. */
        fun parse(text: String): RadioMap {
            val map = RadioMap()
            text.lineSequence().forEach { line ->
                val f = line.split('\t')
                try {
                    when (f.firstOrNull()) {
                        "F" -> if (f.size >= 6) {
                            val signals = mutableMapOf<String, Double>()
                            for (kv in f[5].split(';')) {
                                val i = kv.lastIndexOf('=')
                                if (i <= 0 || kv.startsWith(LEGACY_BLE_PREFIX)) continue
                                kv.substring(i + 1).toDoubleOrNull()?.let { signals[kv.substring(0, i)] = it }
                            }
                            if (signals.isNotEmpty()) {
                                map.addParsed(RadioFingerprint(f[1].toDouble(), f[2].toDouble(), f[3].toDouble(), signals, f[4].toInt()))
                            }
                        }
                        "A" -> if (f.size >= 6 && !f[1].startsWith(LEGACY_BLE_PREFIX)) {
                            map.anchors[f[1]] = RadioAnchor(f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[5].toDouble())
                        }
                    }
                } catch (_: NumberFormatException) { /* skip the bad line */ }
            }
            return map
        }
    }
}
