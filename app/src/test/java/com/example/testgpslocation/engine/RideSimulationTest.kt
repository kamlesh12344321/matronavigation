package com.example.testgpslocation.engine

import com.example.testgpslocation.models.GpsPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * End-to-end simulated metro rides with realistic kinematics and noisy sensors.
 *
 * Train: accelerates at 1.0 m/s², cruises, brakes at 1.0 m/s² into each station, dwells 25 s.
 * IMU: world-frame acceleration rotated by a compass error (magnetic disturbance in trains)
 * plus Gaussian noise. GPS: 1 Hz, 4 m noise, lost at a tunnel portal; the watchdog reports
 * the loss 6 s later. Optional network fixes underground.
 */
class RideSimulationTest {

    private val mPerDeg = 111_195.0
    private val stationDistances = listOf(0.0, 1100.0, 2000.0, 3600.0, 4400.0, 6200.0, 7300.0)
    private val names = listOf("A", "B", "C", "D", "E", "F", "G")
    private val route = MetroRoute(
        "sim", "Sim Line", "A", "G",
        (0..160).map { TrackPoint(12.90 + it * 50.0 / mPerDeg, 77.0) },
        names.mapIndexed { i, n -> MetroStation(n, 12.90 + stationDistances[i] / mPerDeg, 77.0) }
    )

    data class Result(
        val stationsSeen: List<String>,
        val errorsAtStops: List<Double>,
        val maxErrorMoving: Double,
        val events: List<String>
    )

    private fun simulate(
        cruise: Double = 22.0,
        gpsUntilMeters: Double = 300.0,
        startMovingWithGps: Boolean = true,
        compassErrorDeg: Double = 25.0,
        accelNoise: Double = 0.3,
        networkEverySec: Int? = null,
        networkSigma: Double = 80.0,
        seed: Int = 42
    ): Result {
        val rnd = Random(seed)
        fun gauss() = rnd.nextDouble().let { u1 -> sqrtNeg2Ln(u1) * cos(2 * Math.PI * rnd.nextDouble()) }
        val nav = UndergroundNavigator()
        nav.setRoute(route)
        val events = mutableListOf<String>()

        var clock = 1_000_000L
        var s = 0.0
        var v = 0.0
        var target = 1
        var dwellLeft = if (startMovingWithGps) 5.0 else 25.0
        var stillFor = 0L
        var lastGpsMs = 0L
        var gpsOn = true
        var lostReported = false
        var nextNetworkMs = 0L
        var sinceArrival = -1.0
        var sampledThisStop = true
        val seen = mutableListOf<String>()
        val stopErrors = mutableListOf<Double>()
        var maxErr = 0.0
        val dt = 0.1

        // Stationary at A for GPS to settle (only when starting with GPS).
        if (!startMovingWithGps) {
            clock += 1000
            nav.onLocationFix(GpsPoint(12.90, 77.0, 5f, clock, 0f, 0f, hasSpeed = true, elapsedRealtimeMs = clock), clock)
            lastGpsMs = clock
            gpsOn = false // enters the underground station before departure
        }

        while ((target < stationDistances.size || !sampledThisStop) && clock < 1_000_000L + 3_600_000L) {
            clock += (dt * 1000).toLong()
            // --- truth kinematics
            var a = 0.0
            if (dwellLeft > 0) {
                dwellLeft -= dt; v = 0.0
                if (sinceArrival >= 0) sinceArrival += dt
            } else {
                val rem = stationDistances[target] - s
                val brakeDist = v * v / 2.0
                a = when {
                    rem <= 0.05 -> 0.0
                    rem <= brakeDist + 0.01 -> -(v * v) / (2 * rem)
                    v < cruise -> 1.0
                    else -> 0.0
                }
                v = (v + a * dt).coerceAtLeast(0.0).coerceAtMost(cruise)
                s += v * dt
                if (stationDistances[target] - s <= 0.05 || (v < 0.05 && a < 0)) {
                    s = stationDistances[target]; v = 0.0; a = 0.0
                    dwellLeft = 25.0; sinceArrival = 0.0; sampledThisStop = false
                    target++
                }
            }
            val trueLat = 12.90 + s / mPerDeg

            // --- GPS
            if (gpsOn && s >= gpsUntilMeters) gpsOn = false
            if (gpsOn && clock - lastGpsMs >= 1000) {
                val noisyLat = trueLat + gauss() * 4.0 / mPerDeg
                nav.onLocationFix(GpsPoint(noisyLat, 77.0, 5f, clock, v.toFloat(), 0f, hasSpeed = true, hasBearing = v > 1.0,
                    elapsedRealtimeMs = clock, isGnss = true), clock)
                lastGpsMs = clock
            }
            if (!gpsOn && !lostReported && clock - lastGpsMs >= 6000) {
                nav.onGpsLost(clock); lostReported = true
            }
            // --- network (only without GPS)
            if (!gpsOn && networkEverySec != null && clock >= nextNetworkMs) {
                nextNetworkMs = clock + networkEverySec * 1000L
                val nLat = trueLat + gauss() * networkSigma / mPerDeg
                nav.onLocationFix(GpsPoint(nLat, 77.0 + gauss() * 30 / mPerDeg, (networkSigma * 1.2).toFloat(), clock,
                    elapsedRealtimeMs = clock, isGnss = false), clock)
            }

            // --- IMU
            stillFor = if (v == 0.0) stillFor + 100 else 0
            val state = if (v == 0.0 && stillFor >= 1000) MotionState.STATIONARY else MotionState.MOVING
            val c = Math.toRadians(compassErrorDeg)
            val east = a * sin(c) + gauss() * accelNoise
            val north = a * cos(c) + gauss() * accelNoise
            nav.onMotion(MotionSample(clock, dt, state, if (state == MotionState.STATIONARY) stillFor else 0, east, north))
            events += nav.events; nav.events.clear()

            // --- metrics (only once GPS is gone)
            if (lostReported) {
                val est = nav.state()
                val err = abs(est.latitude - trueLat) * mPerDeg
                if (v > 0) maxErr = maxOf(maxErr, err)
                if (sinceArrival >= 8.0 && !sampledThisStop) {
                    sampledThisStop = true
                    seen += est.previousStation ?: "?"
                    stopErrors += err
                }
            }
        }
        return Result(seen, stopErrors, maxErr, events)
    }

    private fun sqrtNeg2Ln(u: Double) = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u.coerceAtLeast(1e-12)))

    private fun report(name: String, r: Result) =
        println("SIM $name: stations=${r.stationsSeen} stopErrors=${r.errorsAtStops.map { it.toInt() }} maxMovingError=${r.maxErrorMoving.toInt()} m")

    @Test
    fun highSpeedTrainEntersTunnelAtSpeed() {
        val r = simulate(cruise = 22.0) // 80 km/h
        report("80kmh-tunnel", r)
        assertEquals(listOf("B", "C", "D", "E", "F", "G"), r.stationsSeen)
        assertTrue(r.errorsAtStops.all { it < 5.0 })
        assertTrue("max error ${r.maxErrorMoving}", r.maxErrorMoving < 800.0)
    }

    @Test
    fun veryFastTrain() {
        val r = simulate(cruise = 30.0) // 108 km/h
        report("108kmh", r)
        assertEquals(listOf("B", "C", "D", "E", "F", "G"), r.stationsSeen)
    }

    @Test
    fun boardingUndergroundWithoutGpsBearing() {
        val r = simulate(startMovingWithGps = false)
        report("board-underground", r)
        assertTrue(r.events.any { it.startsWith("Departure detected") })
        assertEquals(listOf("B", "C", "D", "E", "F", "G"), r.stationsSeen)
    }

    @Test
    fun strongMagneticDisturbance() {
        val r = simulate(compassErrorDeg = 60.0, accelNoise = 0.5, seed = 7)
        report("compass-60deg", r)
        assertEquals(listOf("B", "C", "D", "E", "F", "G"), r.stationsSeen)
    }

    @Test
    fun networkLocationUndergroundReducesError() {
        val without = simulate(seed = 11)
        val with = simulate(networkEverySec = 15, networkSigma = 80.0, seed = 11)
        report("no-network", without)
        report("network-15s", with)
        assertEquals(listOf("B", "C", "D", "E", "F", "G"), with.stationsSeen)
        assertTrue("with ${with.maxErrorMoving} vs without ${without.maxErrorMoving}", with.maxErrorMoving < without.maxErrorMoving)
    }

    @Test
    fun manySeedsNeverLoseTheStationSequence() {
        repeat(20) { seed ->
            val r = simulate(seed = 100 + seed, compassErrorDeg = (seed * 7 % 50).toDouble())
            assertEquals("seed $seed", listOf("B", "C", "D", "E", "F", "G"), r.stationsSeen)
        }
    }
}
