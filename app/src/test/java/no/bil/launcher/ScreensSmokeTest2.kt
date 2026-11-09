package no.bil.launcher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/** «Siste apper» tegnet alene: med apper (lange navn, uten tilgang til appbruk), tom, og mens den henter. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w1024dp-h600dp-land-mdpi")
class RecentsSmokeTest {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createComposeRule()

    private fun settle() {
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
    }

    @Test fun sisteApperAlleTilstander() {
        rule.mainClock.autoAdvance = false
        val now = System.currentTimeMillis()
        val icon = android.graphics.drawable.ColorDrawable(android.graphics.Color.GRAY)
        val apps = (1..8).map { RecentItem("com.test.app$it", if (it == 1) "Veldig langt appnavn ".repeat(6) else "App $it", icon, now - it * 400_000L) }
        var state by androidx.compose.runtime.mutableStateOf(Triple(apps, true, false))
        var closedAll = false
        rule.setContent {
            RecentsContent(state.first, state.second, state.third, onClose = {}, onOpen = {}, onCloseApp = {}, onCloseAll = { closedAll = true }, onAskAccess = {})
        }
        settle()
        // «Lukk alle» spør først («Trykk igjen»), så lukkes alt
        rule.onNodeWithText("Lukk alle").performClick()
        settle()
        rule.onNodeWithText("Trykk igjen for å lukke alle").performClick()
        settle()
        check(closedAll) { "«Lukk alle» skal lukke etter andre trykk" }
        rule.runOnUiThread { state = Triple(emptyList(), true, true) }
        settle()
        // Tomtilstanden kan komme et par bilder senere (animasjon ut av lista) – vent til den er der
        repeat(10) { if (rule.onAllNodesWithText("Ingen apper i bakgrunnen").fetchSemanticsNodes().isEmpty()) settle() }
        rule.onNodeWithText("Ingen apper i bakgrunnen").assertExists()
        rule.runOnUiThread { state = Triple(emptyList(), false, true) }
        settle()
    }
}

/** T'OS sitt panel (nedtrekksmenyen i T'OS-stil) med varsler: mange og lange, tomme, uten varseltilgang. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w1024dp-h600dp-land-mdpi")
class ShadePanelSmokeTest {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createComposeRule()

    private fun settle() {
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
        rule.mainClock.advanceTimeBy(500); rule.waitForIdle()
    }

    private val long = "Veldig lang varseltekst som aldri tar slutt ".repeat(6)

    private fun notes(n: Int) = (1..n).map {
        val meta = NoteMeta("k$it", "com.test.app$it", if (it == 1) long else "Melding $it", long, System.currentTimeMillis() - it * 600_000L,
            ongoing = it == n, clearable = it != n, category = null, media = false, groupSummary = false)
        NoteUi(meta, if (it == 2) long else "App $it", null, autoCancel = true)
    }

    @After fun reset() { rule.runOnUiThread { TosNotifications.items = emptyList(); TosNotifications.connected = false } }

    @Test fun varslerAlleTilstander() {
        rule.mainClock.autoAdvance = false
        var state by androidx.compose.runtime.mutableStateOf(notes(7) to true)
        var cleared = false
        rule.setContent {
            NotificationsSection(state.first, state.second, onOpen = {}, onDismiss = {}, onClearAll = { cleared = true }, onAskAccess = {})
        }
        settle()
        // «Fjern alle» spør først
        rule.onNodeWithText("Fjern alle").performClick()
        settle()
        rule.onNodeWithText("Trykk igjen for å fjerne alle").performClick()
        settle()
        check(cleared) { "«Fjern alle» skal fjerne etter andre trykk" }
        rule.runOnUiThread { state = emptyList<NoteUi>() to true }
        settle()
        rule.onNodeWithText("Ingen varsler").assertExists()
        rule.runOnUiThread { state = emptyList<NoteUi>() to false }
        settle()
        rule.onNodeWithText("Slå på varseltilgang for å se varslene her").assertExists()
    }

    @Test fun helePanelet() {
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { TosNotifications.items = notes(4); TosNotifications.connected = true }
        rule.setContent { QuickPanel(onClose = {}, onGo = {}) }
        settle()
        rule.onNodeWithText("Varsler").assertExists()
    }
}

/** «Kartlegg stereoen» lages uten å krasje (i testmiljøet mangler det meste – da står det «fikk ikke lest»). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class SurveySmokeTest {
    @Test fun kartleggingenLages() {
        val c = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val text = SystemSurvey.build(c, listOf("+   1,0 s  KNAPP NED  KEYCODE_MENU (82)"))
        for (h in listOf("Enhet", "Skjermer", "Lyd", "Knapper og andre inndataenheter", "Pakker i detalj", "Opptak (1 linjer)"))
            check(text.contains("===== $h")) { "Mangler delen «$h»" }
        check(text.contains("KEYCODE_MENU")) { "Opptaket skal være med" }
    }
}
