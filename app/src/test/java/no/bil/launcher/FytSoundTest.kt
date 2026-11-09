package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lyden på FYT-stereoen (lydmodulen 4): tolking av meldingene og regnestykkene bak glidebryterne. */
class FytSoundTest {
    private fun s(vararg msgs: Pair<Int, IntArray>): FytSoundLogic.State =
        msgs.fold(FytSoundLogic.State()) { st, (u, ints) -> FytSoundLogic.merge(st, u, ints) }

    @Test fun lydbrikke_gir_profil() {
        // Kartleggingen 8. nov: u=1 = 6 → 32 bånd 0–24 (cabin)
        val p = FytSoundLogic.profile(6)!!
        assertEquals(32, p.bands); assertEquals(0..24, p.gain); assertEquals(12, p.center)
        assertEquals(36, FytSoundLogic.profile(11)!!.bands)
        assertTrue(FytSoundLogic.profile(11)!!.sub)
        assertNull(FytSoundLogic.profile(13))
        assertNull(FytSoundLogic.profile(null))
    }

    @Test fun meldinger_tolkes_og_ugyldige_ignoreres() {
        val st = s(1 to intArrayOf(6), 8 to intArrayOf(5, 11), 9 to intArrayOf(0, 14), 9 to intArrayOf(31, 2),
            10 to intArrayOf(2), 11 to intArrayOf(1))
        assertEquals(6, st.moduleId)
        assertEquals(5, st.balance); assertEquals(11, st.fader)
        assertEquals(mapOf(0 to 14, 31 to 2), st.bands)
        assertEquals(2, st.preset); assertEquals(1, st.loud)
        // Utenfor området / feil lengde / bånd som ikke finnes / subwoofer på brikke uten
        val bad = listOf(8 to intArrayOf(5), 8 to intArrayOf(5, 99), 9 to intArrayOf(32, 3), 9 to intArrayOf(1, 25),
            10 to intArrayOf(9), 11 to intArrayOf(2), 26 to intArrayOf(4))
        val after = bad.fold(st) { a, (u, i) -> FytSoundLogic.merge(a, u, i) }
        assertEquals(st, after)
        assertEquals(st, FytSoundLogic.merge(st, 8, null))
    }

    @Test fun bånd_kreves_kjent_brikke_og_ny_brikke_nullstiller() {
        val ukjent = s(1 to intArrayOf(99), 9 to intArrayOf(0, 10))
        assertTrue(ukjent.bands.isEmpty())
        val st = s(1 to intArrayOf(6), 9 to intArrayOf(0, 10), 11 to intArrayOf(0))
        assertEquals(st, FytSoundLogic.merge(st, 1, intArrayOf(6)))           // samme brikke: beholdes
        assertEquals(FytSoundLogic.State(moduleId = 11), FytSoundLogic.merge(st, 1, intArrayOf(11)))
        assertEquals(7, s(1 to intArrayOf(11), 26 to intArrayOf(7)).sub)
    }

    @Test fun bass_mellom_diskant() {
        val g = FytSoundLogic.groups(32)
        assertEquals(listOf(0 until 10, 10 until 22, 22 until 32), g)
        assertEquals(36, FytSoundLogic.groups(36).sumOf { it.count() })
        assertTrue(FytSoundLogic.groups(0).isEmpty())
        val bands = (0 until 32).associateWith { 12 } + (0 to 10) + (1 to 14)
        assertEquals(12, FytSoundLogic.groupLevel(bands, g[0]))
        assertNull(FytSoundLogic.groupLevel(bands - 5, g[0]))                // ett bånd mangler → ikke vis
    }

    @Test fun gruppe_flyttes_med_formen() {
        val g = 0 until 3
        val bands = mapOf(0 to 10, 1 to 12, 2 to 23)
        // Snitt 15 → 17: +2 på alle, det øverste stopper på 24
        assertEquals(mapOf(0 to 12, 1 to 14, 2 to 24), FytSoundLogic.shiftGroup(bands, g, 17, 0..24))
        assertTrue(FytSoundLogic.shiftGroup(bands, g, 15, 0..24).isEmpty())
        assertTrue(FytSoundLogic.shiftGroup(mapOf(0 to 1), g, 5, 0..24).isEmpty())
        // Målet utenfor området klemmes
        assertEquals(mapOf(0 to 0, 1 to 0, 2 to 8), FytSoundLogic.shiftGroup(bands, g, -40, 0..24))
    }

    @Test fun flat_og_tall() {
        val p = FytSoundLogic.profile(6)!!
        val f = FytSoundLogic.flat(mapOf(0 to 12, 1 to 3), p)
        assertEquals(31, f.size); assertEquals(12, f[1]); assertNull(f[0])
        assertEquals("+3", FytSoundLogic.signed(15, 12))
        assertEquals("−2", FytSoundLogic.signed(10, 12))
        assertEquals("0", FytSoundLogic.signed(8, 8))
    }

    @Test fun volum_steg_og_felt() {
        assertEquals(1, FytSoundLogic.stepToward(3, 10))
        assertEquals(-1, FytSoundLogic.stepToward(10, 3))
        assertEquals(0, FytSoundLogic.stepToward(7, 7))
        assertEquals(0, FytSoundLogic.axisFrom(-0.2f)); assertEquals(16, FytSoundLogic.axisFrom(1.5f))
        assertEquals(8, FytSoundLogic.axisFrom(0.5f))
        assertEquals(0.5f, FytSoundLogic.axisFraction(8), 0.001f)
        assertEquals(1f, FytSoundLogic.axisFraction(40), 0.001f)
    }

    @Test fun koder_og_navn() {
        val c = FytSoundLogic.CODES
        assertTrue(listOf(1, 2, 3, 8, 9, 10, 11, 26).all { it in c })
        assertTrue(0 !in c)                                   // spekteret kommer tett – bes ikke om
        assertEquals("balanse/fader", FytSoundLogic.codeName(8))
        assertEquals("ukjent", FytSoundLogic.codeName(40))
        assertEquals(9, FytSoundLogic.PRESETS.size)
        assertNotNull(FytSoundLogic.PRESETS.getOrNull(8))
    }
}
