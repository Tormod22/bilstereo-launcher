package no.bil.launcher

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/*
 * Flere røyktester: forsiden og sidene med «vanskelige» data – veldig lange navn, navigasjon i gang,
 * GPS som forsvinner (tunnel), over fartsgrensen, og stor tekst. Skal ikke krasje.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w1024dp-h600dp-land-mdpi")
class ScreensSmokeTest2 {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val long = "Veldig lang tittel som aldri tar slutt ".repeat(8)

    @Before fun start() {
        rule.mainClock.autoAdvance = false
        settle()
    }

    @After fun reset() {
        rule.runOnUiThread {
            Nav.screen = Screen.DASH
            NowPlaying.title = null; NowPlaying.artist = null; NowPlaying.playing = false
            NavInfo.title = null; NavInfo.text = null
            Hub.gps = null; SpeedLimit.limit = null; SpeedLimit.over = false
            Prefs.set("text_scale_s", "1.0"); Prefs.set("night_mode", "DAY"); Theme.night = false
        }
    }

    private fun settle(ms: Long = 1_500) {
        rule.mainClock.advanceTimeBy(ms); rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms); rule.waitForIdle()
    }

    private fun show(s: Screen) { rule.runOnUiThread { Nav.screen = s }; settle() }

    @Test fun forsidenMedLangeNavnOgNavigasjon() {
        rule.runOnUiThread {
            NowPlaying.title = long; NowPlaying.artist = long; NowPlaying.playing = true
            NavInfo.title = "350 m"; NavInfo.text = long
            Hub.gps = Gps(87, 58.97, 5.73, 120f)
            SpeedLimit.limit = 80; SpeedLimit.over = true
        }
        settle()
        for (s in listOf(Screen.DASH, Screen.MUSIC, Screen.MAP, Screen.NAV, Screen.CAR)) show(s)
        // Natt
        rule.runOnUiThread { Prefs.set("night_mode", "NIGHT"); Theme.night = true }
        show(Screen.DASH)
    }

    @Test fun gpsForsvinnerITunnel() {
        rule.runOnUiThread { Hub.gps = Gps(64, 58.97, 5.73, 10f) }
        show(Screen.DASH)
        rule.runOnUiThread { Hub.gps = Hub.gps?.copy(speedKmh = 0, stale = true) }
        settle()
        show(Screen.MAP)
        show(Screen.CAR)
        rule.runOnUiThread { Hub.gps = null }
        show(Screen.DASH)
    }

    @Test fun storTekst() {
        rule.runOnUiThread { Prefs.set("text_scale_s", "1.3"); NowPlaying.title = long }
        settle()
        for (s in listOf(Screen.DASH, Screen.MUSIC, Screen.PHONE, Screen.SETTINGS, Screen.APPS, Screen.CAR)) show(s)
    }
}
