package no.bil.launcher

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Calendar

/** «Send alt til utvikleren» og «Hva er nytt» (Feedback.kt): ren logikk. */
class FeedbackLogicTest {
    @Test fun filnavn() {
        val at = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 9, 14, 5, 0) }.timeInMillis
        assertEquals("tos-tilbakemelding-2026-11-09-1405.txt", FeedbackLogic.fileName(at))
        assertTrue(FeedbackLogic.isOurs(FeedbackLogic.fileName(at)))
        assertFalse(FeedbackLogic.isOurs("kartlegging-2026-11-09-1405.txt"))
        assertFalse(FeedbackLogic.isOurs("tos-tilbakemelding-2026.json"))
    }

    @Test fun kortKartleggingHopperOverDeStoreDelene() {
        for (t in listOf("Alle systemegenskaper T'OS får lese (getprop)", "Installerte pakker (412)", "Pakker i detalj", "Selvtest (siste resultat)"))
            assertFalse(t, SurveyText.inCompact(t))
        for (t in listOf("Strøm (tenning/skjerm: hva T'OS lar gå)", "FYT-tjenesten (com.syu.ms: radio, BT-telefon, lydkilde, volum)",
            "FYT-radioen (radio-modul 1, T'OS sin radio)", "Systemegenskaper (utvalgte)", "Vinduer på skjermen nå", "Bluetooth"))
            assertTrue(t, SurveyText.inCompact(t))
    }

    @Test fun selvtestLinja() {
        assertEquals("ikke kjørt", FeedbackLogic.selftestLine(null))
        assertEquals("ikke kjørt", FeedbackLogic.selftestLine("(selvtesten er ikke kjørt – Innstillinger → …)\n"))
        val r = SelfTestLogic.report(listOf(Check("fyt", "Kontakt med stereoen", Mark.OK, "ok"), Check("bright", "Lysstyrke", Mark.FAIL, "nei")),
            "9. nov. 2026 14:05", "5.10")
        val line = FeedbackLogic.selftestLine(r)
        assertTrue(line, line.startsWith("1 av 2 virker"))
        assertTrue(line, line.contains("9. nov. 2026 14:05"))
    }

    @Test fun opptaketsSiste300Linjer() {
        assertNull(FeedbackLogic.recordingTail("T'OS kartlegging\n===== Enhet =====\nnoe\n"))
        assertNull(FeedbackLogic.recordingTail("===== Opptak (0 linjer) =====\n"))
        val rec = (1..450).joinToString("\n") { "+ $it s linje $it" }
        val survey = "T'OS kartlegging\n\n===== Enhet =====\nFYT\n\n===== Opptak (450 linjer) =====\n$rec\n"
        val tail = FeedbackLogic.recordingTail(survey)!!
        assertEquals(301, tail.size)
        assertEquals("(de siste 300 av 450 linjer)", tail.first())
        assertEquals("+ 151 s linje 151", tail[1])
        assertEquals("+ 450 s linje 450", tail.last())
        // Kort opptak: alt, og en del etter opptaket tas ikke med
        val short = FeedbackLogic.recordingTail("===== Opptak (2 linjer) =====\na\nb\n\n===== Noe annet =====\nc\n")
        assertEquals(listOf("a", "b"), short)
    }

    @Test fun sammendragetØverst() {
        val h = FeedbackLogic.Head("5.10", 1050, "9. nov. 2026 14:05", "  radioen hakket  ", "FYT UIS7862, Android 10 (SDK 29)",
            fytPresent = true, fytConnected = false, fytStatus = "venter", master = true,
            toggles = listOf("Radio" to true, "Telefon" to false), fytSafe = "", selftest = "5 av 7 virker", safeMode = false, recording = "ingen")
        val t = FeedbackLogic.header(h)
        assertTrue(t, t.startsWith("T'OS til utvikleren\nT'OS 5.10 (bygg 1050)"))
        assertTrue(t, t.contains("Hva skjedde?\nradioen hakket\n"))
        assertTrue(t, t.contains("FYT-stereo: ikke tilkoblet (venter)"))
        assertTrue(t, t.contains("FYT-brytere på: Radio"))
        assertTrue(t, t.contains("FYT-brytere av: Telefon"))
        assertTrue(t, t.contains("Selvtest: 5 av 7 virker"))
        val none = FeedbackLogic.header(h.copy(note = "", fytPresent = false))
        assertTrue(none, none.contains("(ingenting skrevet)"))
        assertTrue(none, none.contains("FYT-stereo: nei"))
        assertFalse(none, none.contains("FYT-brytere"))
        // Alle FYT-bryterne har et navn
        assertEquals(FytSafetyLogic.FEATURES.toSet(), FeedbackLogic.toggleNames.keys)
    }

    @Test fun hvaErNyttEtterOppdatering() {
        val n = NewsLogic.SHOW_STARTS
        // Første installasjon: ingenting. Oppdatert fra en versjon uten «Hva er nytt»: vises.
        assertEquals(0, NewsLogic.afterStart(0, 1050, wasUpdated = false, left = 0))
        assertEquals(n, NewsLogic.afterStart(0, 1050, wasUpdated = true, left = 0))
        // Ny versionCode: vises; samme: teller ned til borte
        assertEquals(n, NewsLogic.afterStart(1049, 1050, wasUpdated = true, left = 0))
        assertEquals(n - 1, NewsLogic.afterStart(1050, 1050, wasUpdated = true, left = n))
        assertEquals(0, NewsLogic.afterStart(1050, 1050, wasUpdated = true, left = 1))
        assertEquals(0, NewsLogic.afterStart(1050, 1050, wasUpdated = true, left = 0))
        // Eldre (lokalt bygg over GitHub-bygg): ikke
        assertEquals(0, NewsLogic.afterStart(1050, 32, wasUpdated = true, left = 0))
        assertEquals("5.10", NewsLogic.shortVersion("5.10.123"))
        assertEquals("5.10", NewsLogic.shortVersion("5.10"))
        assertEquals("", NewsLogic.shortVersion(null))
    }

    /** nytt.md: har de siste versjonene og minner om knappen. */
    @Test fun nyttMd() {
        val md = File("src/main/assets/${NewsLogic.ASSET}").readText()
        val titles = HelpDoc.sections(HelpDoc.parse(md)).map { it.second }
        for (v in listOf("T'OS 5.10", "T'OS 5.9", "T'OS 5.8")) assertTrue(v, v in titles)
        // Nyeste del først, og den må høre til versjonen som bygges (husk nytt.md ved hver versjon).
        val gradle = File("build.gradle.kts").readText()
        val version = Regex("""else "([0-9.]+)"""").find(gradle.substringAfter("versionName"))!!.groupValues[1]
        assertEquals("nyeste først = denne versjonen", "T'OS $version", titles.first())
        assertTrue(md.contains("Send alt til utvikleren"))
    }
}

/** Fila og sidene i det ekte UI-et (Robolectric). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w1024dp-h600dp-land-mdpi")
class FeedbackSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun start() { rule.mainClock.autoAdvance = false; settle() }

    @After fun reset() {
        rule.runOnUiThread { Nav.screen = Screen.DASH; Prefs.set("news_left", 0); Feedback.note = "" }
    }

    private fun settle(ms: Long = 1_500) = rule.settle(ms)
    private fun show(s: Screen) { rule.runOnUiThread { Nav.screen = s }; settle() }

    @Test fun fila() {
        val c = rule.activity.applicationContext
        val compact = SystemSurvey.build(c, listOf("+   1,0 s  KNAPP"), compact = true)
        for (h in listOf("Enhet", "Strøm", "FYT-tjenesten", "FYT-radioen", "Opptak (1 linjer)")) assertTrue(h, compact.contains("===== $h"))
        for (h in listOf("Alle systemegenskaper", "Installerte pakker", "Pakker i detalj", "Selvtest")) assertFalse(h, compact.contains("===== $h"))

        val text = Feedback.build(c, "radioen hakket")
        assertTrue(text.startsWith("T'OS til utvikleren"))
        assertTrue(text.contains("radioen hakket"))
        for (h in listOf("Selvtest (siste resultat)", "Oppsettrapport", "Feilrapport (krasj og logg)", "Stereoen (kort kartlegging)"))
            assertTrue(h, text.contains("===== $h"))
        assertFalse(text.contains("===== Pakker i detalj"))
        assertTrue(text.contains("Opptak: ingen"))
    }

    @Test fun radeneOgSidene() {
        // Sikkerhetskopi og hjelp: kortet øverst med «Hva skjedde?»
        rule.runOnUiThread { Prefs.set("settings_cat", SetCat.BACKUP.name) }
        show(Screen.SETTINGS)
        rule.awaitText("Send alt til utvikleren")
        rule.awaitText("Hva skjedde? (valgfritt)")
        // Om stereoen: «Hva er nytt»
        show(Screen.DASH)
        rule.runOnUiThread { Prefs.set("settings_cat", SetCat.ABOUT.name) }
        show(Screen.SETTINGS)
        rule.awaitText("Hva er nytt")
        // Forsiden etter en oppdatering: linja, og siden (som slår den av)
        show(Screen.DASH)
        rule.runOnUiThread { Prefs.set("news_left", NewsLogic.SHOW_STARTS) }
        settle()
        rule.awaitUntil("linja «Hva er nytt» på forsiden") {
            rule.onAllNodesWithText("Oppdatert til T'OS", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        show(Screen.NEWS)
        rule.awaitUntil("nytt.md lest fra assets") { rule.countText("T'OS 5.10") > 0 }
        rule.awaitText("Send alt til utvikleren")
        rule.awaitUntil("linja er borte når siden er åpnet") { !News.visible }
    }
}
