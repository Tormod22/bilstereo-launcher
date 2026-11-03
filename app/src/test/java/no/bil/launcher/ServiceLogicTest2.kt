package no.bil.launcher

import android.media.session.PlaybackState
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Flere tester for knappetjenesten, mediaøkter, radio, sikkerhetskopi og feilloggen (runde 2). */
class ServiceLogicTest2 {

    @Test fun samtaletaster_går_til_telefonen() {
        assertTrue(passToCall(KeyEvent.KEYCODE_HEADSETHOOK))
        assertTrue(passToCall(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertTrue(passToCall(KeyEvent.KEYCODE_ENDCALL))
        assertFalse(passToCall(KeyEvent.KEYCODE_HOME))
        assertFalse(passToCall(KeyEvent.KEYCODE_F1))
    }

    @Test fun trykkstart_bruker_nedtid_når_den_finnes() {
        assertEquals(1000L, pressStart(downTime = 1000, eventTime = 1700))
        assertEquals(1700L, pressStart(downTime = 0, eventTime = 1700))      // stereoen oppgir ikke nedtid
        assertEquals(1700L, pressStart(downTime = 5000, eventTime = 1700))   // ugyldig nedtid
    }

    @Test fun fanelista_og_tillatelser_er_bare_innom() {
        assertTrue(isPassingWindow("com.android.launcher3", "com.android.quickstep.RecentsActivity"))
        assertTrue(isPassingWindow("com.android.permissioncontroller", "x.GrantPermissionsActivity"))
        assertTrue(isPassingWindow("com.google.android.packageinstaller", null))
        assertFalse(isPassingWindow("com.ts.radio", "com.ts.radio.MainActivity"))
    }

    @Test fun eid_app_beholdes_når_fanelista_var_innom() {
        val f = FrontTracker()
        assertTrue(f.update("com.ts.radio", launchedByUs = true).owned)
        // Fanelista hoppes over i KeyService (isPassingWindow) – trackeren ser den aldri
        assertFalse(f.update("com.ts.radio", launchedByUs = false).newlyFront)
        assertTrue(f.update("com.ts.radio", launchedByUs = false).owned)
    }

    @Test fun mediaøkt_som_spiller_vinner_ellers_beholdes_den_som_vises() {
        assertEquals(-1, pickSession(emptyList(), -1))
        assertEquals(1, pickSession(listOf(false, true), 0))     // en annen spiller nå
        assertEquals(1, pickSession(listOf(false, false), 1))    // ingen spiller: behold den som vises
        assertEquals(0, pickSession(listOf(false, false), -1))
        assertEquals(2, pickSession(listOf(true, false, true), 2)) // begge spiller: behold den som vises
    }

    @Test fun lasting_og_sangbytte_regnes_som_spiller() {
        assertTrue(isPlayingState(PlaybackState.STATE_PLAYING))
        assertTrue(isPlayingState(PlaybackState.STATE_SKIPPING_TO_NEXT))
        assertTrue(isPlayingState(PlaybackState.STATE_BUFFERING))
        assertFalse(isPlayingState(PlaybackState.STATE_PAUSED))
        assertFalse(isPlayingState(null))
    }

    @Test fun nettradio_kobler_til_igjen_bare_etter_ekte_avspilling() {
        assertTrue(shouldReconnect(prepared = true, playedMs = 60_000))
        assertFalse(shouldReconnect(prepared = true, playedMs = 3_000))
        assertFalse(shouldReconnect(prepared = false, playedMs = 60_000))   // fikk aldri koblet til
    }

    @Test fun telefondeler_er_ikke_fm_radio() {
        assertFalse(isFmRadioPkg("com.qualcomm.qti.radioconfiginterface", ""))
        assertFalse(isFmRadioPkg("vendor.qti.hardware.radio.ims", ""))
        assertTrue(isFmRadioPkg("com.ts.radio", ""))
    }

    @Test fun ugyldige_tall_hoppes_over_i_sikkerhetskopien() {
        assertTrue(backupExportable(1.5f))
        assertTrue(backupExportable("tekst"))
        assertTrue(backupExportable(3L))
        assertFalse(backupExportable(Float.NaN))
        assertFalse(backupExportable(Float.POSITIVE_INFINITY))
        assertFalse(backupExportable(setOf("a")))
        assertFalse(backupExportable(null))
    }

    @Test fun feilloggen_holdes_liten_og_nyeste_først() {
        val one = crashLogAppend("", "A")
        assertEquals("A", one)
        val two = crashLogAppend(one, "B")
        assertTrue(two.startsWith("B"))
        assertTrue(two.contains("A"))
        val big = crashLogAppend("x".repeat(50_000), "y".repeat(50_000), max = 1000)
        assertEquals(1000, big.length)
        assertTrue(big.startsWith("y"))
    }

    @Test fun klokkehopp_gir_ikke_sikker_modus() {
        assertTrue(recentCrash(now = 1_000_000, at = 900_000))
        assertFalse(recentCrash(now = 1_000_000, at = 700_000))      // over 3 min siden
        assertFalse(recentCrash(now = 1_000_000, at = 5_000_000))    // «i framtiden» (klokka ble stilt tilbake)
    }
}
