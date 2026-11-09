package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kjernen (forsiden, ikonene, «Siste apper»): feil funnet i gjennomgangen 9. nov. */
class CoreLogicTest {
    private val min = 60_000L

    @Test fun klokka_kommer_ikke_rett_etter_at_du_kom_tilbake_fra_en_annen_app() {
        val now = 100 * min
        // Siste trykk i T'OS for 10 min siden, men T'OS kom fram igjen nå nettopp (du var i Spotify): ingen klokke
        assertFalse(clockSaverDue(3, 0, visible = true, free = true, now = now, lastTouch = now - 10 * min, shownAt = now - 5_000))
        // 3 min etter at T'OS kom fram, uten trykk: klokka
        assertTrue(clockSaverDue(3, 0, visible = true, free = true, now = now, lastTouch = now - 10 * min, shownAt = now - 3 * min - 1))
    }

    @Test fun klokka_bare_naar_t_os_vises_bilen_staar_og_forsiden_er_fri() {
        val now = 100 * min
        val old = now - 10 * min
        assertFalse("T'OS ligger bak en annen app", clockSaverDue(3, 0, visible = false, free = true, now = now, lastTouch = old, shownAt = old))
        assertFalse("bilen kjører", clockSaverDue(3, 50, visible = true, free = true, now = now, lastTouch = old, shownAt = old))
        assertFalse("hurtigpanel/app-velger/navigasjon", clockSaverDue(3, 0, visible = true, free = false, now = now, lastTouch = old, shownAt = old))
        assertFalse("slått av", clockSaverDue(0, 0, visible = true, free = true, now = now, lastTouch = old, shownAt = old))
        assertTrue(clockSaverDue(3, 2, visible = true, free = true, now = now, lastTouch = old, shownAt = old))
        // Nylig trykk teller fortsatt
        assertFalse(clockSaverDue(3, 0, visible = true, free = true, now = now, lastTouch = now - min, shownAt = old))
    }

    @Test fun ikonlageret_rommer_alle_appene_paa_stereoen() {
        val glyph = 128 * 128 * 4
        // Stereoen (4 GB RAM) gir appen minst 256 MB: plass til 300+ ferdige ikoner (før: maks 150, med 200+ apper)
        assertTrue(iconCacheBytes(256L * 1_048_576) / glyph >= 300)
        assertEquals(24 * 1_048_576, iconCacheBytes(2_048L * 1_048_576))
        // Lite minne: aldri mer enn en åttendedel, aldri under 4 MB
        assertEquals(8 * 1_048_576, iconCacheBytes(64L * 1_048_576))
        assertEquals(4 * 1_048_576, iconCacheBytes(16L * 1_048_576))
    }

    @Test fun lukket_app_som_aapnes_igjen_kommer_tilbake_i_siste_apper() {
        val t0 = 1_000_000L
        var own = RecentsLogic.add(emptyList(), "com.spotify.music", t0)
        // Lukket i «Siste apper» 10 s senere: ut av T'OS sin egen liste
        val dismissedAt = t0 + 10_000
        own = RecentsLogic.drop(own, listOf("com.spotify.music"))
        // Åpnet igjen 20 s etter første gang: skal skrives ned (før: hoppet over fordi den sto først for under 30 s siden)
        val t2 = t0 + 20_000
        assertTrue(RecentsLogic.shouldRecord(own, "com.spotify.music", t2))
        own = RecentsLogic.add(own, "com.spotify.music", t2)
        val shown = RecentsLogic.merge(listOf(own), mapOf("com.spotify.music" to dismissedAt), t2, exclude = { false })
        assertEquals(listOf("com.spotify.music"), shown.map { it.pkg })
    }

    @Test fun alle_vanlige_passordtegn_kan_skrives() {
        // Alt et Wi-Fi-passord (ASCII 33–126) kan inneholde, må finnes på et av tekstoppsettene
        val all = KbLayer.entries.flatMap { kbRows(KbKind.TEXT, it, KbShift.OFF) + kbRows(KbKind.TEXT, it, KbShift.ONCE) }
            .flatten().filterIsInstance<KbKey.Ch>().joinToString("") { it.c }
        for (c in '!'..'~') assertTrue("mangler $c", c in all)
        // Og man kommer fram og tilbake mellom sidene
        val layersFromSymbols = kbRows(KbKind.TEXT, KbLayer.SYMBOLS, KbShift.OFF).flatten().filterIsInstance<KbKey.Layer>().map { it.to }
        assertTrue(KbLayer.MORE in layersFromSymbols && KbLayer.LETTERS in layersFromSymbols)
        val layersFromMore = kbRows(KbKind.TEXT, KbLayer.MORE, KbShift.OFF).flatten().filterIsInstance<KbKey.Layer>().map { it.to }
        assertTrue(KbLayer.SYMBOLS in layersFromMore && KbLayer.LETTERS in layersFromMore)
    }

    @Test fun samme_app_rett_etter_seg_selv_skrives_ikke_paa_nytt() {
        val list = RecentsLogic.add(emptyList(), "com.waze", 1_000L)
        assertFalse(RecentsLogic.shouldRecord(list, "com.waze", 20_000L))
        assertTrue(RecentsLogic.shouldRecord(list, "com.waze", 40_000L))
        assertTrue(RecentsLogic.shouldRecord(list, "com.spotify.music", 2_000L))
        assertTrue(RecentsLogic.shouldRecord(emptyList(), "com.waze", 0L))
    }
}
