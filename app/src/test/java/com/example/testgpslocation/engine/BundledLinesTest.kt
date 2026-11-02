package com.example.testgpslocation.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Every city file shipped in the APK (assets/metro) parses with the app's own parser into
 * usable lines with stations — the data the app relies on when there is no network at all.
 */
class BundledLinesTest {

    private val dir = listOf(File("src/main/assets/metro"), File("app/src/main/assets/metro")).firstOrNull { it.isDirectory }
        ?: File("src/main/assets/metro")

    @Test
    fun everyBundledCityParsesIntoUsableLines() {
        val files = dir.listFiles { f -> f.name.endsWith(".json.gz") }?.sortedBy { it.name }.orEmpty()
        assertTrue("no bundled line files in ${dir.absolutePath}", files.isNotEmpty())
        for (f in files) {
            val json = GZIPInputStream(f.inputStream()).bufferedReader().use { it.readText() }
            val routes = OverpassParser.parseAll(json)
            val usable = routes.filter { it.isValid && it.stations.size >= 2 && it.length > 1000.0 }
            println(String.format(java.util.Locale.US, "BUNDLE %-24s %3d lines (%d usable), %6.0f km of track, %4d stations",
                f.name, routes.size, usable.size, routes.sumOf { it.length } / 1000.0, routes.sumOf { it.stations.size }))
            assertTrue("${f.name}: no usable line", usable.isNotEmpty())
            // Stations lie on their line and are in order along it.
            usable.forEach { r -> assertTrue(r.stations.zipWithNext().all { (a, b) -> a.distanceAlongTrack < b.distanceAlongTrack }) }
        }
    }
}
