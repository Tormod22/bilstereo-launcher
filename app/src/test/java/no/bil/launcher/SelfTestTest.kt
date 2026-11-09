package no.bil.launcher

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import no.bil.launcher.SelfTestLogic.Echo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Selvtesten: ren logikk (ekko med tidsfrist, oppsummering, rader) – ingen stereo trengs. */
class SelfTestTest {
    private fun ok(id: String) = Check(id, id, Mark.OK, "")
    private fun fail(id: String) = Check(id, id, Mark.FAIL, "")
    private fun unk(id: String) = Check(id, id, Mark.UNKNOWN, "")

    @Test fun ekko_maaltallet_tidsfrist() {
        assertEquals(Echo.CONFIRMED, SelfTestLogic.echo(75, 75, 0, 2000))
        assertEquals(Echo.WAITING, SelfTestLogic.echo(75, 70, 1999, 2000))
        assertEquals(Echo.NO_ANSWER, SelfTestLogic.echo(75, 70, 2000, 2000))
        // Riktig tall rett før fristen teller
        assertEquals(Echo.CONFIRMED, SelfTestLogic.echo(75, 75, 5000, 2000))
    }

    @Test fun ekko_steg_i_riktig_retning() {
        assertEquals(Echo.CONFIRMED, SelfTestLogic.stepEcho(14, 15, 1, 0, 1500))
        assertEquals(Echo.CONFIRMED, SelfTestLogic.stepEcho(14, 16, 1, 0, 1500))   // fastvaren kan ta større steg
        assertEquals(Echo.WAITING, SelfTestLogic.stepEcho(14, 13, 1, 100, 1500))    // feil retning er ikke svar
        assertEquals(Echo.WAITING, SelfTestLogic.stepEcho(14, 14, -1, 100, 1500))
        assertEquals(Echo.CONFIRMED, SelfTestLogic.stepEcho(14, 13, -1, 100, 1500))
        assertEquals(Echo.NO_ANSWER, SelfTestLogic.stepEcho(-1, 3, 1, 1500, 1500))  // ukjent før: aldri bekreftet
    }

    @Test fun lysstyrke_maal() {
        assertEquals(75, SelfTestLogic.brightTarget(70))
        assertEquals(100, SelfTestLogic.brightTarget(95))
        assertEquals(93, SelfTestLogic.brightTarget(98))
        assertEquals(FytMainLogic.MIN_LEVEL, SelfTestLogic.brightTarget(0))
        assertEquals(Mark.OK, SelfTestLogic.brightCheck(70, 75, Echo.CONFIRMED, Echo.CONFIRMED).mark)
        assertEquals(Mark.UNKNOWN, SelfTestLogic.brightCheck(70, 75, Echo.CONFIRMED, Echo.NO_ANSWER).mark)
        // Ikke svar er «?» (stereoen kan ha endret lyset uten å melde tilbake)
        assertEquals(Mark.UNKNOWN, SelfTestLogic.brightCheck(70, 75, Echo.NO_ANSWER, Echo.NO_ANSWER).mark)
    }

    @Test fun volum_bare_naar_trygt() {
        assertNull(SelfTestLogic.volumeSkip(14, muted = false, inCall = false))
        assertNull(SelfTestLogic.volumeSkip(30, muted = false, inCall = false))
        assertNotNull(SelfTestLogic.volumeSkip(31, muted = false, inCall = false))
        assertNotNull(SelfTestLogic.volumeSkip(14, muted = false, inCall = true))
        assertNotNull(SelfTestLogic.volumeSkip(14, muted = true, inCall = false))
        assertNotNull(SelfTestLogic.volumeSkip(-1, muted = false, inCall = false))
        assertEquals(1, SelfTestLogic.volumeDir(14, 40))
        assertEquals(-1, SelfTestLogic.volumeDir(40, 40))
        assertEquals(Mark.OK, SelfTestLogic.volumeCheck(14, Echo.CONFIRMED, 15, Echo.CONFIRMED).mark)
        assertEquals(Mark.UNKNOWN, SelfTestLogic.volumeCheck(14, Echo.CONFIRMED, 15, Echo.NO_ANSWER).mark)
        assertEquals(Mark.FAIL, SelfTestLogic.volumeCheck(14, Echo.NO_ANSWER, 14, Echo.CONFIRMED).mark)
    }

    @Test fun ratt_lys_radio() {
        assertTrue(SelfTestLogic.isSteerSignal(Fyt.M_STEER, 2, intArrayOf(23)))
        assertFalse(SelfTestLogic.isSteerSignal(Fyt.M_STEER, 2, intArrayOf(SteerAdc.NO_KEY)))   // 50 = ingen knapp
        assertFalse(SelfTestLogic.isSteerSignal(Fyt.M_STEER, 3, intArrayOf(0, 23)))
        assertTrue(SelfTestLogic.isSteerSignal(Fyt.M_MAIN, SteerLogic.U_KEY, intArrayOf(5)))
        assertFalse(SelfTestLogic.isSteerSignal(Fyt.M_MAIN, 0, intArrayOf(10)))
        assertTrue(SelfTestLogic.lightsChanged(false, true))
        assertTrue(SelfTestLogic.lightsChanged(true, false))
        assertTrue(SelfTestLogic.lightsChanged(null, true))
        assertFalse(SelfTestLogic.lightsChanged(null, false))   // første melding «av» er ikke et trykk
        assertFalse(SelfTestLogic.lightsChanged(true, null))
        assertNull(SelfTestLogic.restoreSource(1))
        assertEquals(10, SelfTestLogic.restoreSource(10))
        assertEquals(0, SelfTestLogic.restoreSource(-1))
        assertEquals(8, SelfTestLogic.restoreSource(8))
    }

    @Test fun oppsummering_og_rekkefolge() {
        val list = SelfTestLogic.merge(emptyList(), listOf(ok("overlay"), fail("fyt"), unk("radio"), ok("bt")))
        assertEquals(listOf("fyt", "radio", "bt", "overlay"), list.map { it.id })
        assertEquals("2 av 4 virker", SelfTestLogic.summary(list))
        assertEquals("1 virker ikke · 1 usikre (?)", SelfTestLogic.summaryDetail(list))
        // Samme id erstattes; nye havner på sin plass
        val again = SelfTestLogic.merge(list, listOf(ok("fyt"), ok("volume")))
        assertEquals(listOf("fyt", "radio", "bt", "volume", "overlay"), again.map { it.id })
        assertEquals("Alt virker", SelfTestLogic.summaryDetail(listOf(ok("a"))))
        val text = SelfTestLogic.report(again, "9. nov 2026 07:00", "5.8")
        assertTrue(text.contains("4 av 5 virker"))
        assertTrue(text.contains("? radio"))
    }

    @Test fun rader_uten_og_med_stereo() {
        val none = SelfTestLogic.passive(Snapshot())
        assertEquals(Mark.FAIL, none.first { it.id == "fyt" }.mark)
        assertFalse(none.any { it.id == "radio" })   // FYT-radene vises bare med kontakt
        val s = Snapshot(fytPresent = true, connected = true, modules = listOf(0, 1, 2, 4, 7, 10), messages = mapOf(0 to 4, 10 to 2),
            codesSeen = setOf(SelfTestLogic.key(0, FytMainLogic.U_BACKCAR)), appId = 10, radioBand = 65536, radioFreq = 10170,
            radioPs = "NRK P1", phoneState = 2, phoneName = "iPhone", volume = 14, steerLearned = 3, lights = false, acc = true,
            canId = 196608, keyServiceEnabled = true, keyServiceRunning = true, notifAccess = true, usageAccess = true, overlay = true,
            shadeSeen = "com.android.systemui/«Notification shade.»", recentsSeen = "com.android.launcher3/Recents")
        val rows = SelfTestLogic.passive(s)
        assertTrue(rows.joinToString { "${it.id}=${it.mark}" }, rows.all { it.mark == Mark.OK })
        assertTrue(rows.first { it.id == "bt" }.detail.contains("iPhone"))
        assertTrue(rows.first { it.id == "canbus" }.detail.contains("Ingen canbus-boks"))
        val missing = SelfTestLogic.passive(s.copy(modules = listOf(0, 4), radioFreq = -1))
        assertEquals(Mark.UNKNOWN, missing.first { it.id == "modules" }.mark)
        assertEquals(Mark.UNKNOWN, missing.first { it.id == "radio" }.mark)
        assertEquals(Mark.OK, SelfTestLogic.pairCheck(true, 2).mark)
        assertEquals(Mark.UNKNOWN, SelfTestLogic.pairCheck(false, 0).mark)
    }
}

/** Siden med en falsk stereo (svarer med en gang) og uten stereo. Skal ikke krasje. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w1024dp-h600dp-land-mdpi")
class SelfTestSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private class FakeProbe : SelfProbe {
        var bright = 70; var vol = 14; var app = 10; var freq = 10170; var lights: Boolean? = false
        override fun snapshot(c: Context) = Snapshot(fytPresent = true, connected = true, modules = listOf(0, 1, 2, 4, 10),
            appId = app, radioFreq = freq, radioBand = 65536, phoneState = 2, phoneName = "iPhone", volume = vol, brightLevel = bright, lights = lights)
        override fun bright() = bright
        override fun sendBright(level: Int) { bright = level }
        override fun volume() = vol
        override fun volumeMax() = 40
        override fun volumeStep(dir: Int): Boolean { vol += dir; return true }
        override fun setVolume(n: Int) { vol = n }
        override fun pairDevices() = 1
        override fun queryPairs() = true
        override fun appId() = app
        override fun radioFreq() = freq
        override fun radioOn(): Boolean { app = 1; return true }
        override fun radioStep(up: Boolean): Boolean { freq += if (up) 5 else -5; return true }
        override fun setSource(id: Int): Boolean { app = id; return true }
        override fun lights() = lights
        override fun steerKeyAt() = 0L
    }

    @Before fun start() { rule.mainClock.autoAdvance = false; rule.runOnUiThread { SelfTest.resetForTest() }; settle() }

    @After fun reset() {
        rule.runOnUiThread { SelfTest.resetForTest(); SelfTest.probe = FytProbe; Nav.screen = Screen.DASH; Prefs.set("night_mode", "DAY"); Theme.night = false }
    }

    private fun settle(ms: Long = 1_500) = rule.settle(ms)

    @Test fun utenStereo() {
        rule.runOnUiThread { Nav.screen = Screen.SELFTEST }
        settle()
        rule.awaitText("Kontakt med stereoen")
        rule.awaitText("Fant ikke com.syu.ms – dette er ikke en FYT-stereo")
    }

    @Test fun medFalskStereo_dagOgNatt() {
        rule.runOnUiThread { SelfTest.probe = FakeProbe(); Nav.screen = Screen.SELFTEST }
        settle()
        rule.awaitText("Radio (bare lest)")
        rule.awaitText("Bluetooth-telefon")
        // Ferdig resultat (uavhengig av hvor langt klokka har kommet i kjøringen)
        rule.runOnUiThread {
            SelfTest.resetForTest()
            SelfTest.checks = SelfTestLogic.merge(emptyList(), listOf(Check("fyt", "Kontakt med stereoen", Mark.OK, "T'OS snakker med stereoen"),
                Check("bright", "Lysstyrke", Mark.UNKNOWN, "Stereoen meldte ikke tilbake"), Check("volume", "Volum fra T'OS", Mark.FAIL, "Ingen svar")))
            Prefs.set("night_mode", "NIGHT"); Theme.night = true
        }
        settle()
        rule.awaitText("1 av 3 virker")
        rule.awaitText("Send resultatet")
        rule.awaitText("Rattknapp (10 s)")
    }
}
