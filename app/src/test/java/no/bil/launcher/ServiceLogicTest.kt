package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tester logikken i knappetjenesten, varseltjenesten, radioen og sikkerhetskopien (uten stereoen). */
class ServiceLogicTest {

    /* ---------- FM-radio eller nettradio? ---------- */

    @Test fun fm_radio_gjettes_på_navnet() {
        assertTrue(isFmRadioPkg("com.ts.radio", ""))
        assertTrue(isFmRadioPkg("com.syu.fmradio", ""))
        assertFalse(isFmRadioPkg("com.spotify.music", ""))
    }

    @Test fun nettradio_apper_er_ikke_fm() {
        assertFalse(isFmRadioPkg("no.nrk.radio", ""))
        assertFalse(isFmRadioPkg("tunein.player", ""))
        assertFalse(isFmRadioPkg("com.jacapps.radiogarden", ""))
        assertFalse(isFmRadioPkg("de.radio.android", ""))
    }

    @Test fun valgt_radio_app_gjelder_alene() {
        assertTrue(isFmRadioPkg("com.hzbhd.tuner2", "com.hzbhd.tuner2"))
        assertFalse(isFmRadioPkg("com.ts.radio", "com.hzbhd.tuner2"))
        assertTrue(isFmRadioPkg("no.nrk.radio", "no.nrk.radio"))   // brukeren bestemmer
    }

    /* ---------- Lære knapper ---------- */

    @Test fun samme_kode_glemmes_på_andre_knapper() {
        val all = mapOf("code_MENU" to 24, "code_NAVI" to 24, "code_MUSIC" to 87, "pkg_RADIO" to "com.ts.radio")
        assertEquals(listOf("code_MENU", "code_NAVI"), learnedConflicts(all, "code_", 24, PanelKey.BT).sorted())
        assertEquals(listOf("code_NAVI"), learnedConflicts(all, "code_", 24, PanelKey.MENU))
        assertEquals(listOf("pkg_RADIO"), learnedConflicts(all, "pkg_", "com.ts.radio", PanelKey.MUSIC))
        assertTrue(learnedConflicts(all, "pkg_", "com.ts.radio", PanelKey.RADIO).isEmpty())
    }

    @Test fun læring_går_ut_på_tid() {
        assertFalse(Learn.expired(now = 20_000, started = 10_000))
        assertTrue(Learn.expired(now = 26_000, started = 10_000))
    }

    /* ---------- Hvilken app er framme ---------- */

    @Test fun omdirigering_bare_når_appen_kommer_fram() {
        val f = FrontTracker()
        assertEquals(FrontState(newlyFront = true, owned = false), f.update("com.ts.radio", launchedByUs = false))
        assertEquals(FrontState(newlyFront = false, owned = false), f.update("com.ts.radio", launchedByUs = false))
        f.update("no.bil.launcher", false)
        assertTrue(f.update("com.ts.radio", false).newlyFront)
    }

    @Test fun app_åpnet_av_tos_eies_til_brukeren_går_ut() {
        val f = FrontTracker()
        assertTrue(f.update("com.ts.radio", launchedByUs = true).owned)
        assertTrue(f.update("com.ts.radio", launchedByUs = false).owned)   // ny skjerm i samme app, lenge etter
        f.update("no.bil.launcher", false)
        assertFalse(f.update("com.ts.radio", launchedByUs = false).owned)  // åpnet av stereoen etterpå
    }

    /* ---------- Samtalevarsel ---------- */

    @Test fun bare_innkommende_ubesvart_samtale_vises() {
        // Android 12+: samtaletype 1 = innkommende, 2 = pågående, 3 = filtrert
        assertTrue(isIncomingCall(1, hasFullScreen = false, ongoing = true, chronometer = false))
        assertFalse(isIncomingCall(2, hasFullScreen = true, ongoing = true, chronometer = true))
        // Eldre: fullskjerm uten tidtaker = ringer; pågående uten fullskjerm eller med tidtaker = besvart/utgående
        assertTrue(isIncomingCall(0, hasFullScreen = true, ongoing = true, chronometer = false))
        assertTrue(isIncomingCall(0, hasFullScreen = true, ongoing = false, chronometer = false))
        assertFalse(isIncomingCall(0, hasFullScreen = false, ongoing = true, chronometer = false))
        assertFalse(isIncomingCall(0, hasFullScreen = true, ongoing = true, chronometer = true))
        assertFalse(isIncomingCall(0, hasFullScreen = false, ongoing = false, chronometer = false))
    }

    /* ---------- Sikkerhetskopi ---------- */

    @Test fun sikkerhetskopi_verdier_sjekkes() {
        assertEquals("abc", backupValue("String", "abc"))
        assertEquals(true, backupValue("Boolean", true))
        assertEquals(42, backupValue("Integer", 42))
        assertEquals(42, backupValue("Integer", 42.0))
        assertEquals(5_000_000_000L, backupValue("Long", 5_000_000_000L))
        assertEquals(7L, backupValue("Long", 7))                       // små tall kommer tilbake som Integer fra JSON
        assertEquals(1.5f, backupValue("Float", 1.5) as Float, 0.0001f)
        assertNull(backupValue("Integer", "42"))
        assertNull(backupValue("Integer", 1.5))
        assertNull(backupValue("Integer", 5_000_000_000L))
        assertNull(backupValue("Boolean", "true"))
        assertNull(backupValue("String", null))
        assertNull(backupValue("HashSet", listOf("a")))
    }

    @Test fun sikkerhetskopi_bytter_ikke_type() {
        assertTrue(backupTypeFits(null, "x"))
        assertTrue(backupTypeFits(3, 4))
        assertFalse(backupTypeFits("3", 4))
        assertFalse(backupTypeFits(3L, 4))
    }
}
