package com.example.testgpslocation.engine

import com.example.testgpslocation.models.GpsPoint
import com.example.testgpslocation.sensor.CellReading
import com.example.testgpslocation.sensor.CellScanBuilder
import com.example.testgpslocation.utils.GeoToolbox
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A 20 km curved metro line and a physics-level ride simulator for GPS-denied tests.
 *
 * Line: 6 km north, a 45° curve (R ≈ 1 km), 6 km north-east, another 45° curve, 6 km east;
 * 17 stations 1.1–1.3 km apart. Truth is the exact position on the polyline.
 *
 * Train: accelerates, cruises, brakes into each stop, dwells. Optional express skips, signal
 * stops in the tunnel, jiggling the phone at platforms, and "false stills" (smooth cruise
 * that the phone's vibration classifier reads as stationary).
 *
 * Phone: world-frame accelerometer = tangential + centripetal acceleration, rotated by a
 * compass error (magnetic disturbance), plus bias and noise. GPS only until the tunnel
 * portal (optional); the watchdog reports the loss 6 s after the last fix. Radio: Wi-Fi access
 * points on platforms, log-distance path loss with noise, scanned every 30 s like RadioScanner;
 * and/or one in-tunnel mobile cell per station, scanned every 5 s like CellScanner.
 */
object TunnelWorld {
    const val M_PER_DEG = 111_195.0
    val stationDistances = listOf(0.0, 1200.0, 2500.0, 3600.0, 4900.0, 6100.0, 7400.0, 8600.0, 9800.0,
        11100.0, 12300.0, 13600.0, 14800.0, 16000.0, 17300.0, 18600.0, 19800.0)
    val names = stationDistances.indices.map { "S$it" }

    /** Fixed cruise speed of the run containing distance [s] (travelling in [dir]): 11–25 m/s. */
    fun segmentCruise(s: Double, dir: Int): Double {
        val i = stationDistances.indexOfLast { it <= s + (if (dir < 0) -0.1 else 0.0) }.coerceAtLeast(0)
        return 11.0 + (i * 7919 % 15)
    }

    val route: MetroRoute by lazy {
        val pts = mutableListOf(TrackPoint(12.90, 77.0))
        var lat = 12.90; var lon = 77.0; var d = 0.0
        while (d < 20_000.0) {
            val bearing = when {
                d < 6000 -> 0.0
                d < 6800 -> 45.0 * (d - 6000) / 800
                d < 13000 -> 45.0
                d < 13800 -> 45.0 + 45.0 * (d - 13000) / 800
                else -> 90.0
            }
            val (nl, no) = GeoToolbox.moveLocation(lat, lon, 25.0, bearing)
            lat = nl; lon = no; d += 25.0
            pts += TrackPoint(lat, lon)
        }
        val bare = MetroRoute("tmp", "tmp", null, null, pts, emptyList())
        MetroRoute("tunnel", "Tunnel Line", names.first(), names.last(), pts,
            stationDistances.mapIndexed { i, s -> bare.pointAt(s).let { MetroStation(names[i], it.lat, it.lon) } })
    }
}

/**
 * Radio along the line: Wi-Fi access points on platforms (log-distance path loss) and/or one
 * in-tunnel mobile cell per station (strongest at the platform, fading 40 dB/km into the tunnel,
 * so the phone hands over near mid-tunnel).
 */
class RadioWorld(route: MetroRoute, val wifi: Boolean = true, val cells: Boolean = false) {
    private data class Tx(val key: String, val lat: Double, val lon: Double, val power: Double, val exponent: Double)
    private val txs = mutableListOf<Tx>()

    init {
        fun place(key: String, along: Double, lateral: Double, power: Double, n: Double) {
            val p = route.pointAt(along.coerceIn(0.0, route.length))
            val b = route.bearingAt(along.coerceIn(0.0, route.length), 1) + 90.0
            val (lat, lon) = GeoToolbox.moveLocation(p.lat, p.lon, lateral, b)
            txs += Tx(key, lat, lon, power, n)
        }
        TunnelWorld.stationDistances.forEachIndexed { i, d ->
            listOf(-40.0, 0.0, 40.0).forEachIndexed { k, off -> place("wifi:ap-$i-$k", d + off, 8.0, -35.0, 2.7) }
        }
    }

    /** Received power of station [i]'s cell at track distance [s], without fading. */
    fun cellPower(i: Int, s: Double): Double = CELL_PEAK_DBM - CELL_FADE_DB_PER_M * abs(s - TunnelWorld.stationDistances[i])

    /**
     * Cells heard at track distance [s]: the serving one (strongest, kept until another is
     * [HANDOVER_HYSTERESIS_DB] stronger) by its global id, the others by PCI. Returns the new serving cell too.
     */
    fun hearCells(s: Double, serving: Int, gauss: () -> Double): Pair<List<CellReading>, Int> {
        val clean = TunnelWorld.stationDistances.indices.map { cellPower(it, s) }
        val best = clean.indices.maxByOrNull { clean[it] }!!
        val now = if (serving < 0 || clean[best] > clean[serving] + HANDOVER_HYSTERESIS_DB) best else serving
        val readings = clean.indices.mapNotNull { i ->
            val dbm = (clean[i] + gauss() * 3.0).roundToInt()
            when {
                dbm < -120 -> null
                i == now -> CellReading("cell:lte:40445:7:${1000 + i}", dbm, registered = true)
                else -> CellReading("cell:lte:pci${100 + i}@1650", dbm, registered = false)
            }
        }
        return readings to now
    }

    fun hear(lat: Double, lon: Double, gauss: () -> Double): Map<String, Int> {
        if (!wifi) return emptyMap()
        val out = HashMap<String, Int>()
        for (t in txs) {
            val d = max(1.0, GeoToolbox.calculateDistance(lat, lon, t.lat, t.lon))
            val rssi = t.power - 10 * t.exponent * log10(d) + gauss() * 3.0
            if (rssi >= -95) out[t.key] = rssi.toInt()
        }
        return out
    }

    companion object {
        const val CELL_PEAK_DBM = -72.0
        const val CELL_FADE_DB_PER_M = 0.04
        const val HANDOVER_HYSTERESIS_DB = 3.0
    }
}

data class SimConfig(
    val cruise: Double = 22.0,
    /**
     * Real lines have a different speed on every run (restrictions, gradients, curves): each
     * segment gets its own fixed cruise speed of 11–25 m/s (the same on every ride).
     */
    val segmentSpeeds: Boolean = false,
    val accel: Double = 1.0,
    val decel: Double = 1.1,
    val dwellSec: Double = 25.0,
    val startStation: Int = 0,
    val endStation: Int = TunnelWorld.stationDistances.size - 1,
    /** Metres of GPS after departure (the tunnel portal); null = no satellite fix during the ride at all. */
    val gpsUntilMeters: Double? = 300.0,
    /** One GPS fix at the station entrance before going underground (when gpsUntilMeters is null). */
    val entranceFix: Boolean = true,
    val compassErrorDeg: Double = 25.0,
    val accelNoise: Double = 0.3,
    val accelBiasEast: Double = 0.03,
    val accelBiasNorth: Double = -0.02,
    val orientationKnown: Boolean = true,
    val skipStations: Set<Int> = emptySet(),
    val signalStopAt: Double? = null,
    val signalStopSec: Double = 40.0,
    /** Inject a 4 s "still" reading every this many seconds of steady cruising. */
    val falseStillEverySec: Double? = null,
    val jiggleAtStations: Boolean = false,
    val radio: RadioWorld? = null,
    val radioMap: RadioMap? = null,
    /** Memory of earlier rides (segment speeds); null = first ride ever. */
    val rideMemory: RideMemory? = null,
    /** Gyroscope turn rate available (noise 0.3 °/s, bias 0.05 °/s). */
    val gyro: Boolean = true,
    val gyroNoiseDps: Double = 0.3,
    val gyroBiasDps: Double = 0.05,
    val setRouteBeforeStart: Boolean = true,
    /** The line the navigator follows instead of the true one (e.g. a route recorded with GPS). */
    val navigatorRoute: MetroRoute? = null,
    val seed: Int = 42
)

data class SimResult(
    val stationsAtStops: List<String>,
    val expectedStations: List<String>,
    val stopErrors: List<Double>,
    val dwellMaxError: Double,
    val movingErrors: List<Double>,
    val speedRmsError: Double,
    val zeroSpeedDwellFraction: Double,
    val directionCorrectAtStops: Int,
    val stopsMeasured: Int,
    val events: List<String>,
    val navigator: UndergroundNavigator,
    /** Longest time the estimated position stayed exactly the same (stops included). */
    val longestRepeatSec: Double = 0.0,
    /** Longest time the estimate stayed the same while the real train was moving. */
    val longestRepeatWhileMovingSec: Double = 0.0
) {
    val maxMovingError get() = movingErrors.maxOrNull() ?: 0.0
    val meanMovingError get() = if (movingErrors.isEmpty()) 0.0 else movingErrors.average()
    fun summary(name: String) = String.format(java.util.Locale.US,
        "SIM %s: stops=%s stopErr(max)=%.1f m dwellErr(max)=%.1f m moving err mean=%.0f max=%.0f m speedRMS=%.2f m/s stillAtDwell=%.0f%% dirOK=%d/%d repeat(max)=%.0f s repeatWhileMoving(max)=%.1f s",
        name, stationsAtStops, stopErrors.maxOrNull() ?: 0.0, dwellMaxError, meanMovingError, maxMovingError, speedRmsError,
        zeroSpeedDwellFraction * 100, directionCorrectAtStops, stopsMeasured, longestRepeatSec, longestRepeatWhileMovingSec)
}

class TunnelSimulator(private val cfg: SimConfig) {
    private val route = TunnelWorld.route
    private val rnd = Random(cfg.seed)
    private fun gauss(): Double { val u = rnd.nextDouble().coerceAtLeast(1e-12); return sqrt(-2 * ln(u)) * cos(2 * Math.PI * rnd.nextDouble()) }

    fun run(): SimResult {
        val nav = UndergroundNavigator()
        cfg.radioMap?.let { nav.radioMap = it }
        cfg.rideMemory?.let { nav.rideMemory = it }
        if (cfg.setRouteBeforeStart) nav.setRoute(cfg.navigatorRoute ?: route)
        val dir = if (cfg.endStation >= cfg.startStation) 1 else -1
        val sd = TunnelWorld.stationDistances

        // Stops in order: stations (not skipped) and the optional signal stop.
        data class Stop(val at: Double, val station: Int?, val dwell: Double)
        val stops = mutableListOf<Stop>()
        var i = cfg.startStation + dir
        while (if (dir > 0) i <= cfg.endStation else i >= cfg.endStation) {
            if (i !in cfg.skipStations) stops += Stop(sd[i], i, cfg.dwellSec)
            i += dir
        }
        cfg.signalStopAt?.let { sig -> stops += Stop(sig, null, cfg.signalStopSec) }
        stops.sortBy { dir * it.at }

        var clock = 1_000_000L
        var s = sd[cfg.startStation]
        var v = 0.0
        var dwellLeft = cfg.dwellSec
        var atStop: Stop? = Stop(s, cfg.startStation, cfg.dwellSec)
        var dwellElapsed = 0.0
        var stopIdx = 0
        var travelled = 0.0
        var stillFor = 0L
        var lastGpsMs = 0L
        var gpsOn = cfg.gpsUntilMeters != null
        var lostReported = false
        var cruiseSeconds = 0.0
        var falseStillLeft = 0.0
        var nextWifiMs = clock + 3_000L
        var nextCellMs = clock + 1_000L
        var servingCell = -1
        val cellBuilder = CellScanBuilder()

        val events = mutableListOf<String>()
        val seen = mutableListOf<String>(); val expected = mutableListOf<String>()
        val stopErrors = mutableListOf<Double>(); val movingErrors = mutableListOf<Double>()
        var dwellMaxErr = 0.0
        var speedSq = 0.0; var speedN = 0
        var dwellZero = 0; var dwellSamples = 0
        var dirOk = 0; var measured = 0
        var sampled = true
        val dt = 0.1
        var lastEstLat = Double.NaN; var lastEstLon = Double.NaN
        var repeatSec = 0.0; var repeatMax = 0.0
        var repeatMovingSec = 0.0; var repeatMovingMax = 0.0

        if (!gpsOn && cfg.entranceFix) {
            // A fix at the station entrance, then down the stairs.
            val p = route.pointAt(s)
            clock += 1000
            nav.onLocationFix(GpsPoint(p.lat, p.lon, 10f, clock, 0f, 0f, hasSpeed = true, elapsedRealtimeMs = clock), clock)
            lastGpsMs = clock
        }

        while (clock < 1_000_000L + 3 * 3_600_000L) {
            clock += 100
            var a = 0.0
            if (dwellLeft > 0) {
                dwellLeft -= dt; dwellElapsed += dt; v = 0.0
                if (dwellLeft <= 0 && stopIdx >= stops.size) break
            } else {
                val stop = stops[stopIdx]
                val cruise = if (cfg.segmentSpeeds) TunnelWorld.segmentCruise(s, dir) else cfg.cruise
                val rem = dir * (stop.at - s)
                val brakeDist = v * v / (2 * cfg.decel)
                a = when {
                    rem <= 0.05 -> 0.0
                    rem <= brakeDist + 0.01 -> -(v * v) / (2 * rem)
                    v < cruise - 0.01 -> min(cfg.accel, (cruise - v) / dt)
                    v > cruise + 0.01 -> -min(cfg.decel, (v - cruise) / dt)
                    else -> 0.0
                }
                v = (v + a * dt).coerceIn(0.0, max(cruise, v))
                s += dir * v * dt
                travelled += v * dt
                if (dir * (stop.at - s) <= 0.05 || (v < 0.05 && a < 0)) {
                    s = stop.at; v = 0.0; a = 0.0
                    atStop = stop; dwellLeft = stop.dwell; dwellElapsed = 0.0; sampled = false
                    stopIdx++
                }
            }
            val truth = route.pointAt(s)

            // GPS until the portal
            if (gpsOn && travelled >= (cfg.gpsUntilMeters ?: 0.0)) gpsOn = false
            if (gpsOn && clock - lastGpsMs >= 1000) {
                val (nl, no) = GeoToolbox.moveLocation(truth.lat, truth.lon, abs(gauss()) * 4.0, rnd.nextDouble() * 360)
                nav.onLocationFix(GpsPoint(nl, no, 5f, clock, v.toFloat(), route.bearingAt(s, dir).toFloat(), hasSpeed = true,
                    hasBearing = v > 1.0, elapsedRealtimeMs = clock, isGnss = true), clock)
                lastGpsMs = clock
            }
            val watchdogRef = if (lastGpsMs == 0L) 1_000_000L else lastGpsMs
            if (!gpsOn && !lostReported && clock - watchdogRef >= 6000) { nav.onGpsLost(clock); lostReported = true }

            // IMU: tangential + centripetal, rotated by the compass error, plus bias and noise.
            val b = route.bearingAt(s, dir)
            val ahead = route.bearingAt((s + dir * 20.0).coerceIn(0.0, route.length), dir)
            var dTheta = ahead - b; if (dTheta > 180) dTheta -= 360; if (dTheta < -180) dTheta += 360
            val lateral = v * v * Math.toRadians(dTheta) / 20.0
            val c = cfg.compassErrorDeg
            val br = Math.toRadians(b + c); val lr = Math.toRadians(b + 90 + c)
            var east = a * sin(br) + lateral * sin(lr) + cfg.accelBiasEast + gauss() * cfg.accelNoise
            var north = a * cos(br) + lateral * cos(lr) + cfg.accelBiasNorth + gauss() * cfg.accelNoise

            // Gyroscope: the train's real turn rate (bearing rate) plus noise and bias.
            val yawTrue = if (v > 0) {
                var dB = route.bearingAt((s + dir * v * dt).coerceIn(0.0, route.length), dir) - b
                if (dB > 180) dB -= 360; if (dB < -180) dB += 360
                dB / dt
            } else 0.0
            var yaw = yawTrue + cfg.gyroBiasDps + gauss() * cfg.gyroNoiseDps
            val dwelling = dwellLeft > 0
            val cruising = !dwelling && a == 0.0 && v > 5.0
            if (cruising) cruiseSeconds += dt else cruiseSeconds = 0.0
            cfg.falseStillEverySec?.let { every ->
                if (cruising && falseStillLeft <= 0 && cruiseSeconds >= every) { falseStillLeft = 4.0; cruiseSeconds = 0.0 }
            }
            val jiggle = cfg.jiggleAtStations && dwelling && dwellElapsed in 3.0..15.0
            val state: MotionState
            if (falseStillLeft > 0) {
                falseStillLeft -= dt
                stillFor += 100
                state = if (stillFor >= 1000) MotionState.STATIONARY else MotionState.MOVING
            } else if (v == 0.0 && !jiggle) {
                stillFor += 100
                state = if (stillFor >= 1000) MotionState.STATIONARY else MotionState.MOVING
            } else {
                stillFor = 0
                state = MotionState.MOVING
                if (jiggle) { east = gauss() * 1.2; north = gauss() * 1.2; yaw = gauss() * 40.0 } // hand motion, zero mean
            }
            nav.onMotion(MotionSample(clock, dt, state, if (state == MotionState.STATIONARY) stillFor else 0, east, north,
                orientationKnown = cfg.orientationKnown, yawRateDps = if (cfg.gyro) yaw else Double.NaN))

            // Radio (only underground: above ground GPS is better and learning uses it)
            cfg.radio?.let { world ->
                if (world.wifi && clock >= nextWifiMs) {
                    nextWifiMs = clock + 30_000
                    val wifi = world.hear(truth.lat, truth.lon, ::gauss)
                    if (wifi.isNotEmpty()) nav.onRadioScan(RadioScan(clock, wifi))
                }
                if (world.cells && clock >= nextCellMs) {
                    nextCellMs = clock + 5_000
                    val (readings, serving) = world.hearCells(s, servingCell, ::gauss)
                    servingCell = serving
                    val signals = cellBuilder.build(readings, clock)
                    if (signals.isNotEmpty()) nav.onRadioScan(RadioScan(clock, signals))
                }
            }
            events += nav.events; nav.events.clear()

            // Metrics, once GPS is gone
            if (!lostReported) continue
            val est = nav.state()
            if (est.source == PositionSource.NONE) continue
            val err = GeoToolbox.calculateDistance(est.latitude, est.longitude, truth.lat, truth.lon)
            if (est.latitude == lastEstLat && est.longitude == lastEstLon) {
                repeatSec += dt; repeatMax = max(repeatMax, repeatSec)
                if (v > 0.5) { repeatMovingSec += dt; repeatMovingMax = max(repeatMovingMax, repeatMovingSec) }
            } else {
                repeatSec = 0.0; repeatMovingSec = 0.0
            }
            lastEstLat = est.latitude; lastEstLon = est.longitude
            if (v > 0) {
                movingErrors += err
                speedSq += (est.speedMps - v) * (est.speedMps - v); speedN++
            }
            val settled = dwellElapsed >= (atStop?.dwell ?: cfg.dwellSec) - 3.0
            if (dwelling && settled) {
                dwellSamples++
                if (est.speedMps == 0.0) dwellZero++
                if (atStop?.station != null && stopIdx > 0) dwellMaxErr = max(dwellMaxErr, err)
            }
            if (dwelling && settled && !sampled) {
                sampled = true
                val stop = atStop
                if (stop?.station != null) {
                    seen += est.previousStation ?: "?"
                    expected += TunnelWorld.names[stop.station]
                    stopErrors += err
                    measured++
                    val nextTruth = stops.getOrNull(stopIdx)?.station ?: stop.station
                    // Next station on the line in the travel direction (skipped ones included).
                    val nextOnLine = TunnelWorld.names.getOrNull(stop.station + dir)
                    if (est.nextStation == nextOnLine || (nextTruth == stop.station && est.nextStation == null)) dirOk++
                }
            }
        }
        return SimResult(seen, expected, stopErrors, dwellMaxErr, movingErrors,
            if (speedN == 0) 0.0 else sqrt(speedSq / speedN),
            if (dwellSamples == 0) 1.0 else dwellZero.toDouble() / dwellSamples,
            dirOk, measured, events, nav, repeatMax, repeatMovingMax)
    }
}
