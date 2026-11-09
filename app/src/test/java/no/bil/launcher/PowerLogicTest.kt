package no.bil.launcher

import no.bil.launcher.PowerLogic.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Strømsparingen (Power.kt): tenning (på/av/ukjent) × skjerm (på/av) → hva som får gå. Ren logikk. */
class PowerLogicTest {
    private val accs = listOf(true, false, null)

    @Test fun skjermen_paa_er_alltid_vaaken() {
        // Også med «tenning av»: en feiltolket tenning skal aldri slå av GPS-en mens skjermen lyser
        for (a in accs) {
            assertEquals("tenning=$a", Mode.AWAKE, PowerLogic.mode(a, screenOn = true))
            assertEquals(0L, PowerLogic.graceMs(a, screenOn = true))
        }
    }

    @Test fun skjermen_av_avhenger_av_tenningen() {
        assertEquals("kjører med mørk skjerm", Mode.DARK, PowerLogic.mode(true, screenOn = false))
        assertEquals(Mode.SLEEP, PowerLogic.mode(false, screenOn = false))
        assertEquals("ingen tenning kjent: skjermen avgjør", Mode.SLEEP, PowerLogic.mode(null, screenOn = false))
    }

    @Test fun ventetid_foer_dvale() {
        assertEquals(PowerLogic.SLEEP_GRACE_ACC_MS, PowerLogic.graceMs(false, screenOn = false))
        assertEquals(PowerLogic.SLEEP_GRACE_SCREEN_MS, PowerLogic.graceMs(null, screenOn = false))
        assertEquals("mørk skjerm under kjøring: med en gang", 0L, PowerLogic.graceMs(true, screenOn = false))
        assertTrue("bare skjermen: vent lenger enn med bekreftet tenning", PowerLogic.SLEEP_GRACE_SCREEN_MS > PowerLogic.SLEEP_GRACE_ACC_MS)
    }

    @Test fun vaaken_alt_gaar_ui_bare_naar_tos_vises() {
        for (a in accs) {
            val shown = PowerLogic.plan(Mode.AWAKE, visible = true, acc = a)
            assertTrue(shown.gps && shown.obd && shown.updates && shown.theme && shown.stereoVolt && shown.ui && shown.netRadio)
            val behind = PowerLogic.plan(Mode.AWAKE, visible = false, acc = a)
            assertTrue("GPS også når en annen app er framme (tur, fartsgrense)", behind.gps)
            assertFalse("klokker/kartfliser bare når T'OS vises", behind.ui)
        }
    }

    @Test fun moerk_skjerm_under_kjoering() {
        val p = PowerLogic.plan(Mode.DARK, visible = true, acc = true)
        assertTrue("tur og fart går videre", p.gps)
        assertTrue(p.obd)
        assertTrue("musikken spiller videre", p.netRadio)
        assertFalse(p.ui); assertFalse(p.updates); assertFalse(p.theme); assertFalse(p.stereoVolt)
    }

    @Test fun dvale_stopper_alt() {
        for (a in accs) for (v in listOf(true, false)) {
            val p = PowerLogic.plan(Mode.SLEEP, visible = v, acc = a)
            assertFalse(p.gps); assertFalse(p.obd); assertFalse(p.updates); assertFalse(p.theme); assertFalse(p.stereoVolt); assertFalse(p.ui)
        }
        assertFalse("nettradioen av når tenningen er bekreftet av", PowerLogic.plan(Mode.SLEEP, true, acc = false).netRadio)
        assertTrue("bare skjermen av (ingen tenning): musikken får spille", PowerLogic.plan(Mode.SLEEP, true, acc = null).netRadio)
    }

    @Test fun hele_tabellen() {
        // tenning × skjerm → (GPS, OBD, nettradio)
        val want = mapOf(
            Pair(true, true) to Triple(true, true, true),
            Pair(true, false) to Triple(true, true, true),
            Pair(false, true) to Triple(true, true, true),
            Pair(false, false) to Triple(false, false, false),
            Pair(null, true) to Triple(true, true, true),
            Pair(null, false) to Triple(false, false, true),
        )
        for ((k, v) in want) {
            val (acc, screen) = k
            val p = PowerLogic.plan(PowerLogic.mode(acc, screen), visible = true, acc = acc)
            assertEquals("tenning=$acc skjerm=$screen", v, Triple(p.gps, p.obd, p.netRadio))
        }
    }

    @Test fun ny_tur_etter_lang_dvale() {
        assertFalse("aldri vært av", PowerLogic.wakeIsNewTrip(0L, 1_000_000L))
        assertFalse("bare av et øyeblikk", PowerLogic.wakeIsNewTrip(1_000L, 61_000L))
        assertTrue(PowerLogic.wakeIsNewTrip(1_000L, 1_000L + PowerLogic.NEW_TRIP_MS + 1))
    }
}
