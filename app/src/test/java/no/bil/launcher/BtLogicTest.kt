package no.bil.launcher

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bluetooth-tilkobling i lag, stereoens egen BT-app og «kommer lyden ut av stereoen?» (v5.1). */
class BtLogicTest {

    @Test
    fun lagene_slaas_sammen() {
        val bonded = listOf(BtDevice("Jobb", false, address = "AA"), BtDevice("iPhone", false, address = "BB"))
        // Profilen ser iPhonen (samme adresse) → den parede enheten blir tilkoblet, ingen dobbel
        val m = mergeBt(bonded, listOf(BtSeen("iPhone", "BB", "A2DP-mottaker")), null)
        assertEquals(2, m.size)
        assertEquals("iPhone", m.first().name)
        assertTrue(m.first().connected)
        assertEquals("A2DP-mottaker", m.first().via)
        // En enhet bare tilkoblingsmeldingen så, legges til
        val m2 = mergeBt(emptyList(), listOf(BtSeen("Tormods iPhone", "CC", "tilkoblingsmelding")), null)
        assertEquals(listOf("Tormods iPhone"), m2.filter { it.connected }.map { it.name })
        // Ingen ser noe → ingen tilkoblet
        assertTrue(mergeBt(bonded, emptyList(), null).none { it.connected })
    }

    @Test
    fun stereoens_bt_app_brukes_bare_naar_ingen_andre_ser_telefonen() {
        val bonded = listOf(BtDevice("iPhone", false, address = "BB"))
        // Varselet sier «iPhone» → den parede iPhonen blir tilkoblet
        val m = mergeBt(bonded, emptyList(), BtVendorHint("com.syu.bt", "iPhone", "varsel"))
        assertEquals(1, m.size)
        assertTrue(m[0].connected)
        assertTrue(m[0].via.startsWith("stereoens"))
        // Uten navn: «Telefonen»
        val m2 = mergeBt(emptyList(), emptyList(), BtVendorHint("com.syu.bt", null, "mediaøkt"))
        assertEquals("Telefonen", m2.single().name)
        // Android ser allerede telefonen → beviset fra BT-appen legger ikke til en ekstra
        val m3 = mergeBt(bonded, listOf(BtSeen("iPhone", "BB", "A2DP-mottaker")), BtVendorHint("com.syu.bt", null, "mediaøkt"))
        assertEquals(1, m3.count { it.connected })
        // Bare OBD-brikka tilkoblet → BT-appens bevis gjelder fortsatt (OBD er ikke telefonen)
        val m4 = mergeBt(listOf(BtDevice("OBDII", true, address = "OO")), emptyList(), BtVendorHint("com.ts.bt", null, "varsel"))
        assertTrue(m4.any { it.name == "Telefonen" && it.connected })
    }

    @Test
    fun status_sier_aldri_ikke_tilkoblet_naar_stereoen_styrer_bluetooth() {
        val vendor = BtStatus(true, true, listOf(BtDevice("iPhone", false)), vendorPkg = "com.syu.bt")
        assertEquals(BtState.VENDOR, btState(vendor))
        val (head, sub) = btTexts(vendor)
        for (t in listOf(head, sub, btShortText(vendor))) {
            assertFalse(t, t.contains("ikke tilkoblet", true))
            assertFalse(t, t.contains("Ingen telefon", true))
        }
        // Android-Bluetooth av, men stereoens BT-brikke har telefonen → tilkoblet
        val viaVendor = BtStatus(true, false, listOf(BtDevice("Telefonen", true, via = "stereoens BT-app (varsel)")), vendorPkg = "com.syu.bt")
        assertEquals(BtState.CONNECTED, btState(viaVendor))
        assertEquals("Tilkoblet via stereoens Bluetooth", btTexts(viaVendor).second)
        // Bare OBD-brikka tilkoblet er ikke «telefon tilkoblet»
        assertEquals(BtState.ON, btState(BtStatus(true, true, listOf(BtDevice("OBDII", true)))))
        // Uten egen BT-app er «ingen telefon tilkoblet» riktig
        assertEquals("Ingen telefon tilkoblet", btTexts(BtStatus(true, true, emptyList())).first)
        assertEquals(BtState.NONE, btState(BtStatus(false, false, emptyList())))
        assertEquals(BtState.VENDOR, btState(BtStatus(false, false, emptyList(), vendorPkg = "com.ts.bt")))
    }

    @Test
    fun bluetooth_apper_kjennes_igjen_paa_pakkenavnet() {
        for (p in listOf("com.syu.bt", "com.szchoiceway.btsuite", "com.fyt.btphone", "com.microntek.bluetooth", "com.ts.bt", "com.hzbhd.btmusic"))
            assertTrue(p, isVendorBtPkg(p))
        for (p in listOf("com.spotify.music", "com.android.bluetooth", "com.google.android.bluetooth", "com.android.bluetoothmidiservice",
            "no.bil.launcher", "com.android.dialer", "com.ts.radio", "com.obdeleven.service", "com.android.settings"))
            assertFalse(p, isVendorBtPkg(p))
        // Androids egen Bluetooth-del er en Bluetooth-app (mediaøkt/varsel teller), men ikke «stereoens egen»
        assertTrue(isBtAppPkg("com.android.bluetooth"))
        assertFalse(isBtAppPkg("com.google.android.youtube"))
    }

    @Test
    fun varseltekst_tolkes() {
        assertEquals(true, btNoteConnected("Bluetooth", "Connected to Tormods iPhone"))
        assertEquals(true, btNoteConnected("iPhone", "Tilkoblet"))
        assertEquals(true, btNoteConnected("已连接", "iPhone 13"))
        assertEquals(false, btNoteConnected("Bluetooth", "Disconnected"))
        assertEquals(false, btNoteConnected("Bluetooth", "Not connected"))
        assertEquals(false, btNoteConnected("BT", "Ikke tilkoblet"))
        assertEquals(false, btNoteConnected("Bluetooth", "未连接"))
        assertEquals(false, btNoteConnected("Bluetooth", "Connecting…"))
        assertNull(btNoteConnected("Bluetooth-musikk", "Spiller nå"))
        assertNull(btNoteConnected(null, null))
    }

    @Test
    fun telefonens_navn_fra_varselet() {
        assertEquals("Tormods iPhone", btNoteName("Bluetooth", "Connected to Tormods iPhone"))
        assertEquals("iPhone", btNoteName("iPhone", "Tilkoblet"))
        assertEquals("iPhone 13", btNoteName("已连接: iPhone 13", null))
        assertEquals("iPhone", btNoteName("Koblet til iPhone", ""))
        // Bare generelle ord → ukjent navn («Telefonen» brukes da)
        assertNull(btNoteName("Bluetooth", "Tilkoblet"))
        assertNull(btNoteName("BT Connected", null))
    }

    @Test
    fun mediaokt_fra_bt_appen() {
        assertTrue(btSessionSaysConnected(PlaybackState.STATE_PLAYING, false))
        assertTrue(btSessionSaysConnected(PlaybackState.STATE_PAUSED, false))
        assertTrue(btSessionSaysConnected(PlaybackState.STATE_STOPPED, hasTitle = true))
        assertFalse(btSessionSaysConnected(PlaybackState.STATE_ERROR, hasTitle = true))
        assertFalse(btSessionSaysConnected(PlaybackState.STATE_NONE, false))
        assertFalse(btSessionSaysConnected(null, false))
    }

    @Test
    fun obd_brikke_er_ikke_telefonen() {
        for (n in listOf("OBDII", "OBD2", "V-LINK", "Vgate iCar Pro", "ELM327", "KONNWEI")) assertTrue(n, looksLikeObdName(n))
        for (n in listOf("Tormods iPhone", "Helmut sin telefon", "Galaxy S23")) assertFalse(n, looksLikeObdName(n))
    }

    @Test
    fun melding_naar_musikken_spiller_paa_en_annen_enhet() {
        val sp = remotePlaybackMessage("com.spotify.music", "Spotify")
        assertTrue(sp.startsWith("Spotify spiller på en annen enhet"))
        assertTrue("Enheter-ikonet" in sp)
        val yt = remotePlaybackMessage("com.google.android.apps.youtube.music", "YouTube Music")
        assertTrue(yt.startsWith("YouTube Music spiller på en annen enhet"))
        assertFalse("Enheter-ikonet" in yt)
    }

    @Test
    fun bytte_kilde_pauser_ikke_telefonen_eller_annen_enhet() {
        // Bytter til Bluetooth: Spotify på stereoen pauses, men ikke noen Bluetooth-økt (telefonen)
        assertTrue(shouldPauseOnSwitch("com.spotify.music", "com.syu.bt", SourceKind.BLUETOOTH, SourceKind.MUSIC, playing = true, remote = false))
        assertFalse(shouldPauseOnSwitch("com.android.bluetooth", "com.syu.bt", SourceKind.BLUETOOTH, SourceKind.BLUETOOTH, playing = true, remote = false))
        // Spotify Connect (spiller på telefonen) pauses aldri – det ville stoppet musikken på telefonen
        assertFalse(shouldPauseOnSwitch("com.spotify.music", "com.syu.bt", SourceKind.BLUETOOTH, SourceKind.MUSIC, playing = true, remote = true))
        // Valgt kilde og det som ikke spiller pauses ikke
        assertFalse(shouldPauseOnSwitch("com.spotify.music", "com.spotify.music", SourceKind.MUSIC, SourceKind.MUSIC, true, false))
        assertFalse(shouldPauseOnSwitch("com.google.android.youtube", "com.spotify.music", null, SourceKind.MUSIC, false, false))
        // Bytter til Spotify: en annen musikkapp pauses (samme type «musikk» beholdes ikke)
        assertTrue(shouldPauseOnSwitch("com.google.android.youtube", "com.spotify.music", SourceKind.MUSIC, SourceKind.MUSIC, true, false))
    }

    @Test
    fun fm_radio_slipper_kanalen_naar_annen_musikk_starter() {
        assertTrue(shouldYieldRadio(isRadio = false, isEngine = false, radioWanted = true, msSinceOn = 10_000))
        assertFalse(shouldYieldRadio(isRadio = true, isEngine = false, radioWanted = true, msSinceOn = 10_000))
        assertFalse(shouldYieldRadio(isRadio = false, isEngine = true, radioWanted = true, msSinceOn = 10_000))
        assertFalse(shouldYieldRadio(isRadio = false, isEngine = false, radioWanted = false, msSinceOn = 10_000))
        // Rett etter at radioen ble slått på (gamle økter kan melde «spiller» et øyeblikk)
        assertFalse(shouldYieldRadio(isRadio = false, isEngine = false, radioWanted = true, msSinceOn = 1_000))
    }

    @Test
    fun volum_av_naar_musikken_starter() {
        assertNotNull(volumeOffNotice(0, muted = false, fixed = false))
        assertNotNull(volumeOffNotice(8, muted = true, fixed = false))
        assertNull(volumeOffNotice(8, muted = false, fixed = false))
        // Fast volum (stereoen styrer forsterkeren selv): Android-volumet sier ingenting
        assertNull(volumeOffNotice(0, muted = false, fixed = true))
        // Ukjent volum (-1) er ikke «av»
        assertNull(volumeOffNotice(-1, muted = false, fixed = false))
    }

    @Test
    fun bt_musikk_skjerm_og_tjeneste_i_stereoens_app() {
        assertEquals("com.syu.bt.BtMusicActivity",
            pickBtMusicActivity(listOf("com.syu.bt.MainActivity", "com.syu.bt.CallActivity", "com.syu.bt.BtMusicActivity", "com.syu.bt.SettingActivity")))
        assertEquals("com.ts.bt.A2dpActivity", pickBtMusicActivity(listOf("com.ts.bt.MusicListActivity", "com.ts.bt.A2dpActivity")))
        assertNull(pickBtMusicActivity(listOf("com.ts.bt.MainActivity", "com.ts.bt.DialActivity")))
        assertTrue(looksLikeBtMusicService("com.syu.bt.BtMusicService"))
        assertTrue(looksLikeBtMusicService("com.ts.bt.A2dpSinkService"))
        assertFalse(looksLikeBtMusicService("com.ts.bt.NotificationService"))
        assertFalse(looksLikeBtMusicService("com.ts.bt.PhoneService"))
    }
}
