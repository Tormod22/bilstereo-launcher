package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataLogicTest2 {
    @Test fun obd_feilsvar_gir_ingen_tall() {
        assertNull(Engine.parsePid("STOPPED", "0C"))
        assertNull(Engine.parsePid("?", "0C"))
        assertNull(Engine.parsePid("CAN ERROR", "0C"))
        assertNull(Engine.parsePid("UNABLE TO CONNECT", "05"))
        assertNull(Engine.parsePid("010C", "0C"))                       // bare ekko
        assertNull(Engine.parsePid("410C", "0C"))                       // ingen databytes
        // Samme linje som SEARCHING, CAN-linjenummer og to styreenheter som svarer
        assertEquals(listOf(0x1A, 0xF8), Engine.parsePid("SEARCHING...41 0C 1A F8", "0C"))
        assertEquals(listOf(0x7B), Engine.parsePid("0: 41 05 7B", "05"))
        assertEquals(listOf(0x7B), Engine.parsePid("41 05 7B\r41 05 7A\r", "05"))
        assertEquals(listOf(0x0F, 0xA0), Engine.parsePid("BUS INIT: ...OK\r41 23 0F A0", "23"))
    }

    @Test fun obd_bil_svarer_ikke() {
        assertTrue(Engine.noEcuReply("SEARCHING...\rUNABLE TO CONNECT"))
        assertTrue(Engine.noEcuReply("CAN ERROR"))
        assertTrue(Engine.noEcuReply("BUS INIT: ...ERROR"))
        assertTrue(Engine.noEcuReply("ERR94"))
        assertFalse(Engine.noEcuReply("NO DATA"))
        assertFalse(Engine.noEcuReply("BUS INIT: ...OK\r41 0C 1A F8"))
        assertFalse(Engine.noEcuReply("41 0C 1A F8"))
    }

    @Test fun obd_ukjent_pid_merkes_bare_naar_bilen_svarer() {
        Engine.onConnect()
        // Bilen sover: alt gir NO DATA – ingenting skal merkes som «støttes ikke»
        repeat(5) { assertEquals("Bilen svarer ikke – er tenningen på?", Engine.read { _, _ -> "NO DATA" }.problem) }
        var asked = mutableListOf<String>()
        Engine.read { c, _ -> asked += c; "NO DATA" }
        assertTrue("0123" in asked)
        // Bilen våkner: turtall svarer, rail-trykk (23) gir NO DATA tre ganger → da prøves 59 i stedet
        repeat(3) { Engine.read { c, _ -> if (c == "010C") "41 0C 1A F8" else "NO DATA" } }
        asked = mutableListOf()
        Engine.read { c, _ -> asked += c; if (c == "010C") "41 0C 1A F8" else "NO DATA" }
        assertFalse("0123" in asked)
        assertTrue("0159" in asked)
        // Ny tilkobling: prøv alt igjen
        Engine.onConnect()
        asked = mutableListOf()
        Engine.read { c, _ -> asked += c; "NO DATA" }
        assertTrue("0123" in asked)
    }

    @Test fun obd_lang_ventetid_til_forste_svar() {
        Engine.onConnect()
        val waits = mutableListOf<Long>()
        val x = Engine.read { c, w -> waits += w; if (c == "010C") "41 0C 1A F8" else "NO DATA" }
        assertEquals(10_000L, waits.first())
        assertEquals(1726, x.rpm)
        assertNull(x.problem)
        waits.clear()
        Engine.read { _, w -> waits += w; "NO DATA" }
        assertTrue(waits.all { it == 2_000L })
    }

    @Test fun fartsgrense_spoer_ikke_for_ofte() {
        assertFalse(SpeedLimit.shouldQuery(130.0, 4_000))       // 100 km/t: 120 m på 4 s – for tidlig
        assertTrue(SpeedLimit.shouldQuery(130.0, 8_000))
        assertFalse(SpeedLimit.shouldQuery(50.0, 20_000))
        assertTrue(SpeedLimit.shouldQuery(50.0, 25_000))
        assertTrue(SpeedLimit.shouldQuery(Double.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test fun versjonssjekk_venter_lenger_ved_feil() {
        assertEquals(6 * 3_600_000L, updateRetryMs(0))
        assertEquals(60_000L, updateRetryMs(1))
        assertEquals(120_000L, updateRetryMs(2))
        assertEquals(480_000L, updateRetryMs(4))
        assertEquals(3_600_000L, updateRetryMs(7))
        assertEquals(3_600_000L, updateRetryMs(500))
    }

    @Test fun pmtiles_hode_sjekkes() {
        assertTrue(OfflineMaps.pmtilesHeaderOk("PMTiles".toByteArray() + byteArrayOf(3)))
        assertFalse(OfflineMaps.pmtilesHeaderOk("<!DOCTYP".toByteArray()))
        assertFalse(OfflineMaps.pmtilesHeaderOk("PMTiles".toByteArray() + byteArrayOf(2)))
        assertFalse(OfflineMaps.pmtilesHeaderOk(ByteArray(3)))
    }
}
