package no.bil.launcher

import android.content.Intent
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import no.bil.launcher.PowerLogic.Mode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Strømsparingen sammen med resten (FytMain, Startup, GpsService): én oppstart per tur, dvale aldri under kjøring med
 * kjent tenning, posisjonstjenesten stopper seg selv i dvale (etter startForeground – ellers krasjer Android 10 T'OS).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PowerIntegrationTest {
    @After fun reset() {
        Power.setModeForTest(Mode.AWAKE)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Liten modell av hendelsene rundt én tur, med samme regler som FytMain.onAcc + Startup.run + Power.onScreen.
     * Gir hvor mange ganger oppstarten faktisk kjøres.
     */
    private class Drive(var saved: Boolean? = null) {
        var acc: Boolean? = null
        var offAt = 0L
        var lastRun = 0L
        var runs = 0
        var screenOffAt = 0L
        private fun startup(now: Long) { if (PowerLogic.startupDue(lastRun, now)) { lastRun = now; runs++ } }
        fun accReport(on: Boolean, now: Long) {
            when (PowerLogic.accEdge(acc, saved, on)) {
                FytMainLogic.Edge.OFF -> offAt = now
                FytMainLogic.Edge.ON -> if (PowerLogic.startupOnAccOn(offAt, now)) startup(now)
                FytMainLogic.Edge.NONE -> {}
            }
            acc = on; saved = on
        }
        fun coldStart(now: Long) = startup(now)                    // MainActivity: første start etter oppstart av stereoen
        fun screen(on: Boolean, now: Long, accKnown: Boolean) {
            if (!on) { screenOffAt = now; return }
            if (!accKnown && PowerLogic.wakeIsNewTrip(screenOffAt, now)) startup(now)
        }
    }

    @Test fun kald_start_med_tenning_gir_en_oppstart() {
        // Stereoen startes av tenningen: MainActivity (kald start) og første tenningsmelding (lagret «av») kommer begge
        val d = Drive(saved = false)
        d.coldStart(5_000)
        d.accReport(true, 6_000)
        assertEquals(1, d.runs)
    }

    @Test fun tenningen_blinker_naar_motoren_startes() {
        val d = Drive(saved = false)
        d.accReport(true, 1_000)                          // nøkkelen til ACC
        assertEquals(1, d.runs)
        // Sjåføren sitter 5 min med radioen før motoren startes – blinket skal ikke gi ny oppstart (120 s er gått)
        d.accReport(false, 301_000)
        d.accReport(true, 302_200)
        assertEquals("blink er samme tur", 1, d.runs)
        // Ny tur: av en stund, så på
        d.accReport(false, 400_000)
        d.accReport(true, 900_000)
        assertEquals(2, d.runs)
    }

    @Test fun t_os_drept_i_dvalen_faar_oppstart_ved_neste_tur() {
        // FYT drepte T'OS mens bilen sto; ny prosess, første melding er «på» (nåverdi) – lagret verdi var «av»
        val d = Drive(saved = false)
        d.accReport(true, 2_000)
        assertEquals(1, d.runs)
        // Startet på nytt midt i turen (oppdatering): lagret «på» → ingen ny oppstart
        val e = Drive(saved = true)
        e.accReport(true, 2_000)
        assertEquals(0, e.runs)
        // Ingen lagret verdi (første gang): som før – nåverdien er ingen endring
        val f = Drive(saved = null)
        f.accReport(true, 2_000)
        assertEquals(0, f.runs)
    }

    @Test fun skjermen_paa_og_tenning_paa_samtidig_gir_en_oppstart() {
        // FYT frakoblet etter dvalen (skjermen avgjør), så kobler T'OS til og tenningen meldes som endring
        val d = Drive(saved = false)
        d.acc = false
        d.screen(false, 10_000, accKnown = false)
        d.screen(true, 10_000 + PowerLogic.NEW_TRIP_MS + 1_000, accKnown = false)
        d.accReport(true, 10_000 + PowerLogic.NEW_TRIP_MS + 3_000)
        assertEquals(1, d.runs)
        // Tenningen kjent: skjermen på alene starter ingenting (mørk skjerm under kjøring, så på igjen)
        val e = Drive(saved = true); e.acc = true
        e.screen(false, 0, accKnown = true)
        e.screen(true, 10 * 60_000L, accKnown = true)
        assertEquals(0, e.runs)
    }

    @Test fun ukjent_tenning_skjermen_avgjor() {
        val d = Drive()
        d.screen(false, 1_000, accKnown = false)
        d.screen(true, 30_000, accKnown = false)
        assertEquals("bare av et øyeblikk", 0, d.runs)
        d.screen(false, 40_000, accKnown = false)
        d.screen(true, 40_000 + PowerLogic.NEW_TRIP_MS + 1, accKnown = false)
        assertEquals(1, d.runs)
    }

    @Test fun gps_tjenesten_stopper_seg_selv_i_dvale_etter_startForeground() {
        // Hub.pauseGps kan ikke stoppe en tjeneste som ikke har kommet i gang (krasj på Android 10): den gjør det selv
        Power.setModeForTest(Mode.SLEEP, acc = false)
        val ctl = Robolectric.buildService(GpsService::class.java, Intent()).create()
        val svc = ctl.get()
        svc.onStartCommand(Intent(), 0, 1)
        val sh = shadowOf(svc)
        assertNotNull("startForeground må kalles først (5 s-regelen)", sh.lastForegroundNotification ?: sh.lastForegroundNotificationId.takeIf { it != 0 })
        assertTrue("stoppet seg selv", sh.isStoppedBySelf)
        assertFalse(GpsService.running)
        ctl.destroy()
    }

    @Test fun gps_tjenesten_gaar_naar_vaaken() {
        Power.setModeForTest(Mode.DARK, acc = true)       // kjører med mørk skjerm: posisjonen skal gå
        val ctl = Robolectric.buildService(GpsService::class.java, Intent()).create()
        val svc = ctl.get()
        svc.onStartCommand(Intent(), 0, 1)
        assertFalse(shadowOf(svc).isStoppedBySelf)
        assertTrue(GpsService.running)
        ctl.destroy()
        assertFalse(GpsService.running)
    }
}
