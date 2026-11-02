package com.example.testgpslocation.engine

import com.example.testgpslocation.sensor.CellReading
import com.example.testgpslocation.sensor.CellScanBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mobile-cell fingerprints: scan building, handover markers and matching next to Wi-Fi. */
class CellFingerprintTest {

    private val a = "cell:lte:40445:7:1001"
    private val b = "cell:lte:40445:7:1002"
    private val other = "cell:lte:40410:3:555" // another network (second SIM)
    private fun scan(vararg s: Pair<String, Int>) = RadioScan(0L, mapOf(*s))

    @Test
    fun aServingCellChangeIsAHandoverMarkerForAFewSeconds() {
        val builder = CellScanBuilder(handoverWindowMs = 4_000L)
        builder.build(listOf(CellReading(a, -80, true), CellReading("cell:lte:pci102@1650", -95, false)), 0L)
        val after = builder.build(listOf(CellReading(b, -78, true)), 5_000L)
        assertEquals(CellScanBuilder.HANDOVER_SIGNAL_DBM, after["cellho:$a>$b"])
        assertEquals(b, builder.servingCell?.key)
        assertTrue("still marked 3 s later", builder.build(listOf(CellReading(b, -77, true)), 8_000L).containsKey("cellho:$a>$b"))
        assertFalse("gone after the window", builder.build(listOf(CellReading(b, -77, true)), 10_000L).keys.any { it.startsWith("cellho:") })
    }

    @Test
    fun twoSimsNeverFakeAHandover() {
        val builder = CellScanBuilder()
        builder.build(listOf(CellReading(a, -80, true), CellReading(other, -90, true)), 0L)
        // The other SIM's cell becomes the stronger one: still no serving cell changed on either network.
        val next = builder.build(listOf(CellReading(a, -95, true), CellReading(other, -70, true)), 5_000L)
        assertFalse(next.keys.any { it.startsWith("cellho:") })
        assertEquals(other, builder.servingCell?.key)
        assertNull(CellScanBuilder.networkOf("cell:lte:pci101@1650"))
        assertEquals("40445", CellScanBuilder.networkOf(a))
    }

    @Test
    fun oneCellIsEnoughButOneWifiAccessPointIsNot() {
        val map = RadioMap()
        map.learn(scan(a to -80, "cell:lte:pci102@1650" to -100), 12.90, 77.0, 10.0)
        assertEquals("one cell in common identifies the stretch", 1, map.match(scan(a to -82)).size)
        map.learn(scan("wifi:x" to -50, "wifi:y" to -60), 12.95, 77.0, 10.0)
        assertTrue("one Wi-Fi access point alone says little", map.match(scan("wifi:x" to -50)).isEmpty())
    }

    @Test
    fun aScanOfOneRadioIsNotPenalisedForTheOther() {
        val map = RadioMap()
        // A place learnt with both radios: loud Wi-Fi and a cell.
        map.learn(scan("wifi:x" to -45, "wifi:y" to -50, a to -80), 12.90, 77.0, 10.0)
        // A cell-only scan there must match fully: the missing Wi-Fi was simply not scanned.
        assertEquals(1.0, map.match(scan(a to -80)).single().weight, 1e-9)
        // A Wi-Fi-only scan likewise ignores the cell.
        assertEquals(1.0, map.match(scan("wifi:x" to -45, "wifi:y" to -50)).single().weight, 1e-9)
    }

    @Test
    fun cellLevelsBelowTheWifiFloorAreStillUsed() {
        val map = RadioMap()
        assertTrue("-112 dBm is a normal tunnel RSRP", map.learn(scan(a to -112), 12.90, 77.0, 10.0))
        assertFalse("but not a usable Wi-Fi level", RadioMap().learn(scan("wifi:x" to -112, "wifi:y" to -113), 12.90, 77.0, 10.0))
    }

    @Test
    fun aHandoverPointsToWhereItHappenedBefore() {
        val map = RadioMap()
        val handover = "cellho:$a>$b"
        // Two places with the same cells: before the handover point, and the handover point itself.
        map.learn(scan(a to -90, b to -92), 12.900, 77.0, 10.0)
        map.learn(scan(a to -90, b to -92, handover to CellScanBuilder.HANDOVER_SIGNAL_DBM), 12.902, 77.0, 10.0)
        // A normal scan is not put off by the place's handover marker (not hearing one says nothing)...
        val normal = map.match(scan(a to -90, b to -92))
        assertEquals(2, normal.size)
        assertEquals(normal[0].weight, normal[1].weight, 1e-9)
        // ...but a fresh handover picks the place where it happened before.
        val atHandover = map.match(scan(a to -90, b to -92, handover to CellScanBuilder.HANDOVER_SIGNAL_DBM))
        assertEquals(12.902, atHandover.first().fingerprint.lat, 1e-9)
        assertTrue(atHandover.size == 1 || atHandover[1].weight < 0.5 * atHandover[0].weight)
    }

    @Test
    fun cellFingerprintsSurviveSaving() {
        val map = RadioMap()
        map.learn(scan(a to -88, "cell:nr:pci7@632448" to -101, "cellho:$a>$b" to -60), 12.9, 77.0, 12.0)
        val back = RadioMap.parse(map.serialize())
        assertEquals(1, back.size)
        assertEquals(1, back.match(scan(a to -88)).size)
    }
}
