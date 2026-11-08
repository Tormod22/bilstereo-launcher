package no.bil.launcher

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rattknappene (v5.8): FYT-meldingene, hvor trykket skal, lydkilden og læringen. */
class SteerLogicTest {
    private fun ints(vararg v: Int) = v

    @Test fun fyt_meldinger_blir_rattknapper() {
        // hoved u=133 = knapp (open-headunit): Android-tastekoden, 576 = stemme
        assertEquals(SteerLogic.Cmd.NEXT, SteerLogic.fromFyt(0, 133, ints(KeyEvent.KEYCODE_MEDIA_NEXT))?.cmd)
        assertEquals(SteerLogic.Cmd.VOICE, SteerLogic.fromFyt(0, 133, ints(576))?.cmd)
        assertEquals("0/133/999", SteerLogic.fromFyt(0, 133, ints(999))?.id)
        assertNull(SteerLogic.fromFyt(0, 133, ints(999))?.cmd)
        // hoved u=37 = spillerkommando (FinalMain.PLAYER_CMD_*)
        assertEquals(SteerLogic.Cmd.NEXT, SteerLogic.fromFyt(0, 37, ints(5))?.cmd)
        assertEquals(SteerLogic.Cmd.PREV, SteerLogic.fromFyt(0, 37, ints(4))?.cmd)
        assertEquals(SteerLogic.Cmd.PLAY_PAUSE, SteerLogic.fromFyt(0, 37, ints(1))?.cmd)
        assertNull(SteerLogic.fromFyt(0, 37, ints(6))?.cmd)            // spol fram brukes ikke
        // rattmodulen: tall og læringstabell (FytSteer), ikke trykk
        assertNull(SteerLogic.fromFyt(10, 2, ints(87)))
        // Ikke knapper: lydkilden, volumet, canbus, tomme meldinger
        assertNull(SteerLogic.fromFyt(0, 0, ints(10)))
        assertNull(SteerLogic.fromFyt(4, 2, ints(12)))
        assertNull(SteerLogic.fromFyt(7, 1000, ints(1)))
        assertNull(SteerLogic.fromFyt(0, 133, null))
        assertTrue(SteerLogic.isEventCode(0, 37)); assertFalse(SteerLogic.isEventCode(10, 2))
        assertFalse(SteerLogic.isEventCode(0, 0)); assertFalse(SteerLogic.isEventCode(7, 1))
    }

    @Test fun neste_går_til_spotify_men_ikke_radio_eller_bt_musikk() {
        val next37 = SteerLogic.Event(0, 37, 5, SteerLogic.Cmd.NEXT)
        val next133 = SteerLogic.Event(0, 133, 87, SteerLogic.Cmd.NEXT)
        // Kilden står på FYT sin musikkspiller (8) etter MUSIC-knappen, Spotify spiller: til Spotify
        assertEquals(SteerLogic.Route.APP, SteerLogic.route(next37, 8, false, "com.spotify.music"))
        assertEquals(SteerLogic.Route.APP, SteerLogic.route(next37, 2, false, "com.spotify.music"))
        // Radio og BT-musikk: stereoen gjør det selv
        assertEquals(SteerLogic.Route.STEREO, SteerLogic.route(next37, 1, false, "com.spotify.music"))
        assertEquals(SteerLogic.Route.STEREO, SteerLogic.route(next133, 11, false, null))
        assertEquals(SteerLogic.Route.STEREO, SteerLogic.route(next37, 3, false, null))
        // FYT sin egen spiller er den T'OS styrer: stereoen gjør det selv (ellers to ganger)
        assertEquals(SteerLogic.Route.STEREO, SteerLogic.route(next37, 8, false, "com.syu.music"))
        // Kilden er allerede Android-appen: u=37 tas av stereoen, u=133 (knapp til appen) av T'OS
        assertEquals(SteerLogic.Route.STEREO, SteerLogic.route(next37, 10, false, "com.spotify.music"))
        assertEquals(SteerLogic.Route.APP, SteerLogic.route(next133, 10, false, "com.spotify.music"))
        // Under en samtale: ikke musikken
        assertEquals(SteerLogic.Route.NONE, SteerLogic.route(next133, 8, true, "com.spotify.music"))
    }

    @Test fun volum_stemme_og_telefon() {
        fun e(cmd: SteerLogic.Cmd) = SteerLogic.Event(0, 133, 0, cmd)
        assertEquals(SteerLogic.Route.STEREO, SteerLogic.route(e(SteerLogic.Cmd.VOL_UP), 10, false, null))
        assertEquals(SteerLogic.Route.STEREO, SteerLogic.route(e(SteerLogic.Cmd.MUTE), 10, true, null))
        assertEquals(SteerLogic.Route.STEREO, SteerLogic.route(e(SteerLogic.Cmd.VOICE), 10, false, null))
        assertEquals(SteerLogic.Route.PHONE, SteerLogic.route(e(SteerLogic.Cmd.ANSWER), 2, true, null))
        assertEquals(SteerLogic.Route.PHONE, SteerLogic.route(e(SteerLogic.Cmd.HANGUP), 2, true, null))
        assertEquals(SteerLogic.Route.NONE, SteerLogic.route(e(SteerLogic.Cmd.ANSWER), 2, false, null))
        // Ukjent knapp: bare hvis den er lært
        assertEquals(SteerLogic.Route.NONE, SteerLogic.route(SteerLogic.Event(0, 60, 3, null), 8, false, "com.spotify.music"))
    }

    @Test fun android_appen_blir_lydkilden() {
        // Etter MUSIC-knappen (8) eller BT (2/3) og Spotify spiller: Android-appen (10) blir kilden
        assertTrue(SteerLogic.shouldClaim(true, 8, false, "com.spotify.music"))
        assertTrue(SteerLogic.shouldClaim(true, 2, false, "com.spotify.music"))
        assertTrue(SteerLogic.shouldClaim(true, 3, false, "com.spotify.music"))
        // Ikke: av, samtale, allerede 10, radio (HwRadio tar det), FYT sine egne apper, ukjent app
        assertFalse(SteerLogic.shouldClaim(false, 8, false, "com.spotify.music"))
        assertFalse(SteerLogic.shouldClaim(true, 2, true, "com.spotify.music"))
        assertFalse(SteerLogic.shouldClaim(true, 10, false, "com.spotify.music"))
        assertFalse(SteerLogic.shouldClaim(true, 1, false, "com.spotify.music"))
        assertFalse(SteerLogic.shouldClaim(true, 8, false, "com.syu.music"))
        assertFalse(SteerLogic.shouldClaim(true, 3, false, "com.android.bluetooth"))
        assertFalse(SteerLogic.shouldClaim(true, 8, false, null))
    }

    @Test fun ett_trykk_teller_én_gang() {
        val d = SteerLogic.Dedup(350)
        assertTrue(d.allow("NEXT", 1000))
        assertFalse(d.allow("NEXT", 1200))          // u=37 og u=133 for samme trykk
        assertTrue(d.allow("PREV", 1200))
        assertTrue(d.allow("NEXT", 1400))           // nytt trykk
        assertTrue(d.allow("NEXT", 500))            // klokka gikk bakover: ikke stopp knappen for godt
    }

    @Test fun ratt_rader_læres_fra_fyt() {
        // En annen rad med samme rattknapp glemmes når en ny lærer den
        val all = mapOf("fyt_RATT1" to "0/60/3", "fyt_RATT2" to "0/133/87", "code_MENU" to 21)
        assertEquals(listOf("fyt_RATT1"), learnedConflicts(all, "fyt_", "0/60/3", PanelKey.RATT3))
        assertEquals(emptyList<String>(), learnedConflicts(all, "fyt_", "0/60/3", PanelKey.RATT1))
        // Lært rattknapp → FYT-standarden for panelknappen gjelder ikke lenger
        val d = mapOf(PanelKey.MUSIC to FytKeys.MUSIC)
        assertNull(FytKeys.defaultPkg(PanelKey.MUSIC, d, mapOf("fyt_MUSIC" to "0/133/87")))
        // Tekst på raden og i «Siste rattknapp»
        assertEquals("Ratt-knapp 87", SteerLogic.describeId("0/133/87"))
        assertEquals("Ratt: Neste", SteerLogic.describeId("0/37/5"))
        assertEquals("Retning 3", SteerLogic.describeId("0/60/3"))
        assertEquals("rart", SteerLogic.describeId("rart"))
        assertEquals("Neste (stereoen: knapp 87)", SteerLogic.label(SteerLogic.Event(0, 133, 87, SteerLogic.Cmd.NEXT)))
        assertEquals("Retningsknapp 3", SteerLogic.label(SteerLogic.Event(0, 60, 3, null)))
        assertTrue(SteerLogic.note(0, 37, ints(5))!!.contains("Neste"))
        assertNull(SteerLogic.note(0, 0, ints(10)))
    }

    @Test fun android_tastekoder_og_oppsett_apper() {
        assertEquals(SteerLogic.Cmd.PREV, SteerLogic.androidCmd(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(SteerLogic.Cmd.ANSWER, SteerLogic.androidCmd(KeyEvent.KEYCODE_HEADSETHOOK))
        assertEquals(SteerLogic.Cmd.HANGUP, SteerLogic.androidCmd(KeyEvent.KEYCODE_ENDCALL))
        assertNull(SteerLogic.androidCmd(KeyEvent.KEYCODE_A))
        // Stereoens rattknapp-oppsett og bilens oppsett byttes aldri ut av T'OS
        assertTrue("com.syu.steer" in SteerLogic.SETUP_PKGS && "com.syu.canbus" in SteerLogic.SETUP_PKGS)
        assertFalse(isShellWindow("com.syu.steer", "com.syu.steer.Launch", isHomePkg = false, isSystem = true))
        assertFalse(isShellWindow("com.syu.canbus", "com.syu.canbus.ActivityLauncher", isHomePkg = false, isSystem = true))
    }

    @Test fun tilbakemeldinger_fra_rattmodulen() {
        // Grensene fra cabin (SyuSteeringProtocol.feedback)
        assertTrue(SteerAdc.valid(1, listOf(3, 87))); assertTrue(SteerAdc.valid(1, listOf(3, -1)))
        assertTrue(SteerAdc.valid(2, listOf(211))); assertTrue(SteerAdc.valid(3, listOf(5, 219)))
        assertTrue(SteerAdc.valid(4, listOf(1)))
        assertFalse(SteerAdc.valid(2, listOf(300))); assertFalse(SteerAdc.valid(3, listOf(6, 10)))
        assertFalse(SteerAdc.valid(1, listOf(3))); assertFalse(SteerAdc.valid(9, listOf(1)))
        // «NULL» i Control = ikke lært
        assertFalse(SteerAdc.isLearnedValue(-1)); assertFalse(SteerAdc.isLearnedValue(255)); assertTrue(SteerAdc.isLearnedValue(87))
        // Test: tallet fra rattet → nærmeste lærte knapp
        val learned = mapOf(3 to 30, 4 to 60, 6 to 120, 5 to -1)
        assertEquals(3, SteerAdc.slotFor(33, learned))
        assertEquals(6, SteerAdc.slotFor(118, learned))
        assertNull(SteerAdc.slotFor(90, learned))
        assertNull(SteerAdc.slotFor(SteerAdc.NO_KEY, mapOf(1 to 50)))
        assertEquals("Neste", SteerAdc.labelFor(6)); assertEquals("Bytt kilde", SteerAdc.labelFor(1))
        // Rattet til brukeren: VOL +/−, demp, ▲ neste, ▼ forrige, MODE, DISP
        assertEquals(listOf(3, 4, 2, 6, 5, 1, 7), SteerAdc.main.map { it.slot })
    }

    @Test fun læringen_steg_for_steg() {
        val t0 = 10_000L
        // Ingen tall ennå: vent
        assertEquals(SteerAdc.Step.Wait, SteerAdc.step(t0 + 100, t0, null, 0, null, 0, true))
        // Gammelt tall (fra før flisa ble trykket) eller 50 (ingen knapp) fanges ikke
        assertEquals(SteerAdc.Step.Wait, SteerAdc.step(t0 + 100, t0, 87, t0 - 1, null, 0, true))
        assertEquals(SteerAdc.Step.Wait, SteerAdc.step(t0 + 100, t0, 50, t0 + 50, null, 0, true))
        // Nytt tall: fang det
        assertEquals(SteerAdc.Step.Capture(87), SteerAdc.step(t0 + 100, t0, 87, t0 + 80, null, 0, true))
        // Står det, og stereoen lærer: send
        assertEquals(SteerAdc.Step.Send, SteerAdc.step(t0 + 500, t0, 87, t0 + 450, 87, 0, true))
        // Stereoen sier ikke at den lærer: send ikke (til slutt «ikke svar»)
        assertEquals(SteerAdc.Step.Wait, SteerAdc.step(t0 + 500, t0, 87, t0 + 450, 87, 0, null))
        assertEquals(SteerAdc.Step.NoSignal, SteerAdc.step(t0 + 16_000, t0, null, 0, null, 0, null))
        // Sendt: vent på bekreftelse, gi opp etter 10 s
        assertEquals(SteerAdc.Step.Wait, SteerAdc.step(t0 + 2000, t0, 87, t0 + 450, 87, t0 + 500, true))
        assertEquals(SteerAdc.Step.NoConfirm, SteerAdc.step(t0 + 11_000, t0, 87, t0 + 450, 87, t0 + 500, true))
    }
}
