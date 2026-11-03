package no.bil.launcher

import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLogicTest3 {
    private fun letters(rows: List<List<KbKey>>) = rows.flatten().filterIsInstance<KbKey.Ch>().joinToString("") { it.c }

    @Test
    fun tastaturet_har_norske_bokstaver_og_vanlige_tegn() {
        val small = letters(kbRows(KbKind.TEXT, KbLayer.LETTERS, KbShift.OFF))
        for (c in "qwertyuiopåasdfghjkløæzxcvbnm") assertTrue("mangler $c", c in small)
        val big = letters(kbRows(KbKind.TEXT, KbLayer.LETTERS, KbShift.ONCE))
        for (c in "ÆØÅQ") assertTrue("mangler $c", c in big)
        val sym = letters(kbRows(KbKind.TEXT, KbLayer.SYMBOLS, KbShift.OFF))
        for (c in "0123456789@.-_/:,") assertTrue("mangler $c", c in sym)
        // Alle tekstoppsett har slett, mellomrom og handlingstast
        for (layer in KbLayer.entries) {
            val all = kbRows(KbKind.TEXT, layer, KbShift.OFF).flatten()
            assertTrue(KbKey.Back in all); assertTrue(KbKey.Space in all); assertTrue(KbKey.Done in all)
        }
    }

    @Test
    fun radene_er_like_brede() {
        for (kind in KbKind.entries) for (layer in KbLayer.entries) {
            val widths = kbRows(kind, layer, KbShift.OFF).map { r -> r.sumOf { kbWeight(it, kind, layer).toDouble() } }
            assertTrue("$kind/$layer: $widths", widths.all { it == widths.first() })
        }
    }

    @Test
    fun talltastatur_for_tall_og_telefon() {
        assertEquals(KbKind.NUMBER, kbKind(KeyboardType.Number))
        assertEquals(KbKind.NUMBER, kbKind(KeyboardType.NumberPassword))
        assertEquals(KbKind.PHONE, kbKind(KeyboardType.Phone))
        assertEquals(KbKind.TEXT, kbKind(KeyboardType.Password))
        assertEquals(KbKind.TEXT, kbKind(KeyboardType.Text))
        val num = letters(kbRows(KbKind.NUMBER, KbLayer.LETTERS, KbShift.OFF))
        for (c in "0123456789.") assertTrue("mangler $c", c in num)   // datoer som 15.3.2027
        val tel = letters(kbRows(KbKind.PHONE, KbLayer.LETTERS, KbShift.OFF))
        for (c in "0123456789+") assertTrue("mangler $c", c in tel)
        assertTrue(kbIsPassword(KeyboardType.Password))
        assertFalse(kbIsPassword(KeyboardType.Text))
        assertFalse(kbAutoCap(KeyboardType.Password))
    }

    @Test
    fun skriving_og_sletting() {
        assertEquals("Hei" to 3, kbInsert("He", 2, "i"))
        assertEquals("aXb" to 2, kbInsert("ab", 1, "X"))
        assertEquals("ab" to 2, kbInsert("ab", 99, ""))   // markør utenfor teksten tåles
        assertEquals("He" to 2, kbBackspace("Hei", 3))
        assertEquals("" to 0, kbBackspace("", 0))
        assertEquals("ac" to 1, kbBackspace("abc", 2))
        // Emoji (to deler) slettes i én omgang
        val smile = "a😀"
        assertEquals("a" to 1, kbBackspace(smile, smile.length))
    }

    @Test
    fun shift_og_stor_forbokstav() {
        assertEquals(KbShift.ONCE, kbNextShift(KbShift.OFF))
        assertEquals(KbShift.LOCK, kbNextShift(KbShift.ONCE))
        assertEquals(KbShift.OFF, kbNextShift(KbShift.LOCK))
        // Én stor bokstav slippes etter første bokstav
        assertEquals(KbShift.OFF, kbShiftAfter(KbShift.ONCE, "H", autoCap = true))
        // Caps lock blir stående
        assertEquals(KbShift.LOCK, kbShiftAfter(KbShift.LOCK, "HEI", autoCap = true))
        // Ny setning og tomt felt gir stor forbokstav – men ikke i passord o.l.
        assertEquals(KbShift.ONCE, kbShiftAfter(KbShift.OFF, "Hei. ", autoCap = true))
        assertEquals(KbShift.ONCE, kbShiftAfter(KbShift.OFF, "", autoCap = true))
        assertEquals(KbShift.OFF, kbShiftAfter(KbShift.OFF, "", autoCap = false))
        assertEquals(KbShift.OFF, kbShiftAfter(KbShift.OFF, "Storgata 1", autoCap = true))
    }

    @Test
    fun passord_vises_som_prikker() {
        assertEquals("•••", kbMask("abc", reveal = false))
        assertEquals("abc", kbMask("abc", reveal = true))
        assertEquals("••", kbMask("a😀", reveal = false))
        assertEquals("", kbMask("", reveal = false))
    }

    @Test
    fun volumlinja_og_bluetooth_status() {
        assertEquals(0.5f, volumeFraction(15, 30), 0f)
        assertEquals(1f, volumeFraction(40, 30), 0f)
        assertEquals(0f, volumeFraction(-1, 30), 0f)
        assertEquals(0f, volumeFraction(5, 0), 0f)
        assertEquals(BtState.NONE, btState(BtStatus(false, false, emptyList())))
        assertEquals(BtState.OFF, btState(BtStatus(true, false, emptyList())))
        assertEquals(BtState.ON, btState(BtStatus(true, true, listOf(BtDevice("iPhone", false)))))
        assertEquals(BtState.CONNECTED, btState(BtStatus(true, true, listOf(BtDevice("Jobb", false), BtDevice("iPhone", true)))))
    }
}
