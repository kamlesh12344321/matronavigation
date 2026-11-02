package com.example.testgpslocation.engine

import java.io.File

/**
 * Test drive (no metro needed): [RECORDING] a route with GPS, [READY] on a recorded route with
 * GPS in use, [TUNNEL] on it with GPS hidden from navigation and kept only as the reference.
 */
enum class TestDriveMode { OFF, RECORDING, READY, TUNNEL }

data class TestDriveState(
    val mode: TestDriveMode = TestDriveMode.OFF,
    val routeName: String? = null,
    val recordedMeters: Double = 0.0,
    val recordedStations: Int = 0,
    /** Estimate vs. real GPS during the tunnel test (metres); null before the first comparison. */
    val lastErrorMeters: Double? = null,
    val meanErrorMeters: Double? = null,
    val maxErrorMeters: Double? = null,
    val comparisons: Int = 0
)

/** Running mean / max of the tunnel-test error. */
class ErrorStats {
    var count = 0; private set
    var max = 0.0; private set
    var last: Double? = null; private set
    private var sum = 0.0
    val mean: Double? get() = if (count == 0) null else sum / count

    fun add(meters: Double) {
        count++; sum += meters; last = meters
        if (meters > max) max = meters
    }

    fun reset() { count = 0; sum = 0.0; max = 0.0; last = null }
}

/**
 * Test-drive routes saved on the phone, kept apart from the metro lines so a recorded road is
 * never mistaken for a metro line in normal use.
 */
class TestRouteStore(private val dir: File) {

    fun save(route: MetroRoute): Boolean = try {
        if (!dir.exists()) dir.mkdirs()
        val tmp = File(dir, "${route.id}.json.tmp")
        tmp.writeText(RouteJson.write(route))
        tmp.renameTo(File(dir, "${route.id}.json")).also { if (!it) tmp.delete() }
    } catch (e: Exception) {
        false
    }

    /** Newest first. */
    fun list(): List<MetroRoute> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f -> try { RouteJson.read(f.readText()) } catch (e: Exception) { null } }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
