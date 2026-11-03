package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TuningLogicTest {
    @Test fun obd_svar_tolkes() {
        assertEquals(listOf(0x1A, 0xF8), Engine.parsePid("410C1AF8", "0C"))
        assertEquals(listOf(0x1A, 0xF8), Engine.parsePid("SEARCHING...\r41 0C 1A F8\r", "0C"))
        assertEquals(listOf(0x7B), Engine.parsePid("41 05 7B", "05"))
        assertNull(Engine.parsePid("NO DATA", "23"))
        assertNull(Engine.parsePid("410C1AF8", "05"))
    }

    @Test fun railtrykk_regnes_om() {
        // 0x0F 0xA0 = 4000 → 40 000 kPa = 400 bar
        assertEquals(400, Engine.railFromBytes(0x0F, 0xA0))
    }

    @Test fun logg_lagres_og_leses() {
        val list = listOf(TuneLog.Entry(1L, "0–100", 10.4f, 0), TuneLog.Entry(2L, "0–100", 9.8f, 3), TuneLog.Entry(3L, "0–100", 9.6f, 3))
        val back = TuneLog.parse(TuneLog.format(list) + "\nødelagt linje")
        assertEquals(list, back)
        assertEquals(listOf(0 to 10.4f, 3 to 9.6f), TuneLog.bestPerLevel("0–100", back))
    }
}
