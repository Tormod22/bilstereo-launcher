package no.bil.launcher

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tester for «T'OS som hele brukerflaten»: volum, kilder, radio, skall-apper og omdirigering (runde 3). */
class ServiceLogicTest3 {

    @Test fun volumknappene_styrer_riktig_lydstrøm() {
        assertEquals(AudioManager.STREAM_MUSIC, volumeStream(AudioManager.MODE_NORMAL, musicActive = false))
        assertEquals(AudioManager.STREAM_VOICE_CALL, volumeStream(AudioManager.MODE_IN_CALL, musicActive = true))
        assertEquals(AudioManager.STREAM_RING, volumeStream(AudioManager.MODE_RINGTONE, musicActive = false))
        // CarPlay kan holde «kommunikasjonsmodus» mens musikk spiller – da er det fortsatt musikkvolumet
        assertEquals(AudioManager.STREAM_MUSIC, volumeStream(AudioManager.MODE_IN_COMMUNICATION, musicActive = true))
        assertEquals(AudioManager.STREAM_VOICE_CALL, volumeStream(AudioManager.MODE_IN_COMMUNICATION, musicActive = false))
    }

    @Test fun volumlinja_fylles_riktig() {
        assertEquals(0.5f, volumeFraction(15, 30, muted = false), 0.001f)
        assertEquals(0f, volumeFraction(15, 30, muted = true), 0.001f)
        assertEquals(1f, volumeFraction(40, 30, muted = false), 0.001f)
        assertEquals(0f, volumeFraction(5, 0, muted = false), 0.001f)
    }

    @Test fun holdt_volumknapp_gir_jevne_steg() {
        assertTrue(volumeRepeatDue(eventTime = 1000, lastStep = 900))
        assertFalse(volumeRepeatDue(eventTime = 1000, lastStep = 950))
        assertTrue(volumeRepeatDue(eventTime = 100, lastStep = 5000))   // klokka startet på nytt
    }

    @Test fun omdirigering_begrenses_ved_ring() {
        val r = RedirectLimiter(minGapMs = 300, maxInWindow = 3, windowMs = 10_000, pauseMs = 30_000)
        assertTrue(r.allow(1_000))
        assertFalse(r.allow(1_100))          // for tett
        assertTrue(r.allow(2_000))
        assertTrue(r.allow(3_000))
        assertFalse(r.allow(4_000))          // fjerde på 10 s: stereoen og T'OS sender hverandre i ring → pause
        assertFalse(r.allow(20_000))
        assertTrue(r.allow(35_000))          // pausen er over
    }

    @Test fun skallvinduer_kjennes_igjen() {
        assertTrue(isShellWindow("com.ts.launcher", "com.ts.launcher.AllAppsActivity", isHomePkg = true, isSystem = true))
        assertTrue(isShellWindow("com.xy.carhome", "com.xy.carhome.MainActivity", isHomePkg = false, isSystem = true))
        // Radio/BT inne i startskjerm-appen er egne funksjoner
        assertFalse(isShellWindow("com.ts.launcher", "com.ts.launcher.RadioActivity", isHomePkg = true, isSystem = true))
        assertFalse(isShellWindow("com.ts.launcher", "com.ts.launcher.BtPhoneActivity", isHomePkg = true, isSystem = true))
        // Innstillinger, Bluetooth-paring, dialoger og installasjon skal virke
        assertFalse(isShellWindow("com.android.settings", "com.android.settings.MainSettings", isHomePkg = false, isSystem = true))
        assertFalse(isShellWindow("com.android.bluetooth", "com.android.bluetooth.MainActivity", isHomePkg = false, isSystem = true))
        assertFalse(isShellWindow("com.ts.launcher", "android.app.AlertDialog", isHomePkg = true, isSystem = true))
        // Vanlige apper og brukerinstallerte startskjermer røres ikke
        assertFalse(isShellWindow("com.spotify.music", "com.spotify.music.MainActivity", isHomePkg = false, isSystem = true))
        assertFalse(isShellWindow("com.teslacoilsw.launcher", "com.teslacoilsw.launcher.NovaLauncher", isHomePkg = true, isSystem = false))
        // CarPlay-appen er aldri «skall»
        assertFalse(isShellWindow("com.zjinnova.zlink", "com.zjinnova.zlink.main.MainActivity", isHomePkg = true, isSystem = true))
        assertFalse(isShellWindow("com.ts.launcher", null, isHomePkg = true, isSystem = true))
    }

    @Test fun stereoens_egne_apper_skjules() {
        assertTrue(isStockShellApp("com.ts.radio", "Radio", isSystem = true, isHome = false))
        assertTrue(isStockShellApp("com.android.settings", "Innstillinger", isSystem = true, isHome = false))
        assertTrue(isStockShellApp("com.xy.factory", "Fabrikk", isSystem = true, isHome = false))
        assertTrue(isStockShellApp("com.xy.ota", "Systemoppdatering", isSystem = true, isHome = false))
        assertTrue(isStockShellApp("com.xy.media", "Video", isSystem = true, isHome = false))
        assertTrue(isStockShellApp("com.xy.desk", "Car Home", isSystem = true, isHome = false))
        assertTrue(isStockShellApp("com.xy.anything", "Skrivebord", isSystem = true, isHome = true))
        assertTrue(isStockShellApp("com.android.documentsui", "Filer", isSystem = true, isHome = false))
    }

    @Test fun viktige_apper_vises_alltid() {
        assertFalse(isStockShellApp("com.zjinnova.zlink", "Car link 2.0", isSystem = true, isHome = true))
        assertFalse(isStockShellApp("com.google.android.apps.maps", "Maps", isSystem = true, isHome = false))
        assertFalse(isStockShellApp("com.google.android.apps.youtube.music", "YouTube Music", isSystem = true, isHome = false))
        assertFalse(isStockShellApp("com.spotify.music", "Spotify", isSystem = true, isHome = false))
        assertFalse(isStockShellApp("com.waze", "Waze", isSystem = false, isHome = false))
        assertFalse(isStockShellApp("com.android.chrome", "Chrome", isSystem = true, isHome = false))
        assertFalse(isStockShellApp("com.android.vending", "Play Butikk", isSystem = true, isHome = false))
        // Brukerinstallerte apper skjules aldri automatisk, heller ikke nettradio
        assertFalse(isStockShellApp("no.nrk.radio", "NRK Radio", isSystem = false, isHome = false))
        assertFalse(isStockShellApp("no.bil.launcher", "T'OS", isSystem = true, isHome = true))
        // «Profiler» inneholder «filer», men er ikke en filbehandler
        assertFalse(isStockShellApp("com.xy.profiles", "Profiler", isSystem = true, isHome = false))
    }

    @Test fun lydkilder_får_riktig_type() {
        assertEquals(SourceKind.BLUETOOTH, sourceKind("com.android.bluetooth", radioChosen = ""))
        assertEquals(SourceKind.BLUETOOTH, sourceKind("com.ts.bt", radioChosen = ""))
        assertEquals(SourceKind.BLUETOOTH, sourceKind("com.microntek.btmusic", radioChosen = ""))
        assertEquals(SourceKind.BLUETOOTH, sourceKind("com.xy.music", radioChosen = "", btChosen = "com.xy.music"))
        assertEquals(SourceKind.RADIO, sourceKind("com.ts.radio", radioChosen = ""))
        assertEquals(SourceKind.MUSIC, sourceKind("com.spotify.music", radioChosen = ""))
        assertEquals(SourceKind.MUSIC, sourceKind("no.nrk.radio", radioChosen = ""))      // nettradio-app, ikke FM
        assertEquals(SourceKind.NET_RADIO, sourceKind(Media.NET_RADIO, radioChosen = ""))
    }

    @Test fun valgt_kilde_vinner_når_ingen_andre_spiller() {
        // Bluetooth (indeks 1) er valgt og ingen spiller: Bluetooth vises
        assertEquals(1, pickSession(listOf(false, false), currentIndex = 0, preferredIndex = 1))
        // Valgt kilde spiller: den vinner
        assertEquals(1, pickSession(listOf(true, true), currentIndex = 0, preferredIndex = 1))
        // En annen app spiller, den valgte ikke: den som spiller vises
        assertEquals(0, pickSession(listOf(true, false), currentIndex = 0, preferredIndex = 1))
        // Uten valgt kilde: som før
        assertEquals(0, pickSession(listOf(true, false), currentIndex = 1))
    }

    @Test fun radiotjenester_kjennes_igjen() {
        assertTrue(looksLikeTunerService("com.ts.radio.RadioService"))
        assertTrue(looksLikeTunerService("com.xy.FmService"))
        assertTrue(looksLikeTunerService("com.xy.TunerHost"))
        assertFalse(looksLikeTunerService("com.ts.radio.RadioMediaBrowserService"))
        assertFalse(looksLikeTunerService("com.ts.radio.UpdateService"))
        assertFalse(looksLikeTunerService("com.ts.radio.RadioWidgetService"))
    }

    @Test fun carplay_apper_kjennes_igjen() {
        assertTrue(isProjectionApp("com.zjinnova.zlink"))
        assertTrue(isProjectionApp("Car link 2.0"))
        assertTrue(isProjectionApp("cn.manstep.phonemirrorBox"))
        assertFalse(isProjectionApp("com.ts.launcher"))
    }
}

class CallActionTest {
    @Test fun svar_og_avvis_finnes_i_varselet() {
        val t = listOf("Avvis", "Svar")
        assertEquals(1, callActionIndex(t, answer = true))
        assertEquals(0, callActionIndex(t, answer = false))
        assertEquals(0, callActionIndex(listOf("Answer", "Decline"), answer = true))
        assertEquals(1, callActionIndex(listOf("Answer", "Decline"), answer = false))
        assertEquals(-1, callActionIndex(listOf("Mute"), answer = true))
        assertEquals(-1, callActionIndex(emptyList(), answer = false))
    }
}
