package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FYT-radioen: bånd/banker, forvalg (lagring), neste/forrige, stasjonssøket og trinn-innstillingen. */
class FytRadioTest {
    private val L = FytRadioLogic

    @Test fun baandOgBank() {
        assertEquals("FM1", L.fmBank(65536)); assertEquals("FM3", L.fmBank(65538)); assertNull(L.fmBank(0)); assertNull(L.fmBank(65539))
        // AM når frekvensen er AM; ellers brukerens valg, ellers stereoens FM-bank, ellers FM1
        assertEquals("AM", L.bank(false, 65537, "FM2"))
        assertEquals("FM2", L.bank(true, 65536, "FM2"))
        assertEquals("FM3", L.bank(true, 65538, null))
        assertEquals("FM1", L.bank(true, 0, null))
        assertEquals("FM1", L.bank(null, Int.MIN_VALUE, "AM"))
    }

    @Test fun forvalgLagresOgLesesTilbake() {
        val list = listOf(FytRadioLogic.Slot(10170, "NRK P1"), null, FytRadioLogic.Slot(9430, null), null, null, FytRadioLogic.Slot(522, "A;B|C"))
        val s = L.encode(list)
        assertEquals(5, s.count { it == ';' })
        val back = L.decode(s)
        assertEquals(6, back.size)
        assertEquals(FytRadioLogic.Slot(10170, "NRK P1"), back[0])
        assertNull(back[1])
        assertEquals(FytRadioLogic.Slot(9430, null), back[2])
        assertEquals("A B C", back[5]?.name)          // skilletegnene fjernes fra navnet
        // Tomt / ødelagt lagret = 6 tomme plasser (ingen krasj)
        assertEquals(List(6) { null }, L.decode(""))
        assertEquals(List(6) { null }, L.decode("x|y;0|;-5|z"))
        assertEquals("NRK P1", L.clean("  NRK   P1  "))
        assertNull(L.clean("   "))
    }

    @Test fun nesteOgForrigeForvalg() {
        val f = listOf<Int?>(8800, null, 9430, 10170, null, null)
        assertEquals(2, L.nextSlot(f, 8800, up = true))
        assertEquals(3, L.nextSlot(f, 9430, up = true))
        assertEquals(0, L.nextSlot(f, 10170, up = true))   // rundt
        assertEquals(3, L.nextSlot(f, 8800, up = false))
        // Ikke på et forvalg: nærmeste over/under
        assertEquals(2, L.nextSlot(f, 9000, up = true))
        assertEquals(0, L.nextSlot(f, 9000, up = false))
        assertEquals(0, L.nextSlot(f, 10500, up = true))
        assertNull(L.nextSlot(List(6) { null }, 9000, up = true))
        assertNull(L.nextSlot(listOf(9430, null), 9430, up = true))   // bare det ene: søk i stedet
    }

    @Test fun stasjonssoeket() {
        assertFalse(L.scanDone(emptyList(), 8800, 18))
        assertTrue(L.scanDone(listOf(8800, 9430), 8800, 18))       // rundt hele båndet
        assertTrue(L.scanDone((1..18).map { 8700 + it * 10 }, 10000, 18))
        assertTrue(L.scanDone(emptyList(), 0, 18))
        val r = L.defaultRange(true, 9430)
        assertEquals(0f, L.progress(9000, 9000, r), 0.001f)
        assertTrue(L.progress(9000, 10000, r) in 0.45f..0.5f)
        assertTrue(L.progress(9000, 8800, r) > 0.9f)                // gått rundt
        // Fordeling: sortert på frekvens, 6 per bank, alle tre FM-banker byttes ut
        val found = (0 until 8).associate { (10500 - it * 100) to (if (it == 0) "P4" else null) }
        val banks = L.fill(found, fm = true)
        assertEquals(listOf("FM1", "FM2", "FM3"), banks.keys.toList())
        assertEquals(9800, banks["FM1"]!![0]!!.freqRaw)
        assertEquals(10400, banks["FM2"]!![0]!!.freqRaw)
        assertEquals("P4", banks["FM2"]!![1]!!.name)
        assertTrue(banks["FM3"]!!.all { it == null })
        assertEquals(listOf("AM"), L.fill(mapOf(522 to null), fm = false).keys.toList())
    }

    @Test fun trinnOgOmraade() {
        assertTrue(L.stepDone(9430, 9430, 1))
        assertFalse(L.stepDone(9400, 9430, 1))
        assertTrue(L.stepDone(9440, 9430, 1))    // gått forbi (rutenettet passer ikke)
        assertFalse(L.stepDone(9500, 9430, -1))
        // u=16 [min, maks, trinn, antall] (FytRadio NOTES.md: 8750, 10790, 20 på UIS7870)
        val fm = L.range(intArrayOf(8750, 10790, 20, 103))!!
        assertTrue(fm.fm); assertEquals(20, fm.step)
        assertFalse(L.range(intArrayOf(530, 1720, 10, 120))!!.fm)
        assertNull(L.range(intArrayOf(1, 2)))
        assertNull(L.range(intArrayOf(9000, 8000, 10)))
        assertEquals(87_500, L.defaultRange(true, 101_700).min)
    }

    @Test fun tekster() {
        assertEquals("101,7", L.shortFreq(10170))
        assertEquals("94,35", L.shortFreq(9435))
        assertEquals("522", L.shortFreq(522))
        assertEquals("Pop", L.ptyName(10)); assertNull(L.ptyName(0)); assertNull(L.ptyName(99))
        assertEquals("FM2", L.bandName(65537))
        assertEquals("stasjonsnavn", L.updateName(26))
        assertTrue(L.legend().contains("26 stasjonsnavn"))
    }

    @Test fun brukerensFmBankGlemmesBareVedFmTilFm() {
        // Stereoen byttet selv FM1 → FM2: følg stereoen
        assertTrue(L.dropUserBank(65536, 65537))
        // AM → FM (forvalg i FM2 trykket mens radioen stod på AM): FM2 skal fortsatt vises (før: hoppet til FM1)
        assertFalse(L.dropUserBank(0, 65536))
        assertFalse(L.dropUserBank(Int.MIN_VALUE, 65536))
        // Samme bånd igjen, eller til AM
        assertFalse(L.dropUserBank(65536, 65536))
        assertFalse(L.dropUserBank(65537, 0))
    }
}
