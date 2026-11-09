package no.bil.launcher

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Radio-siden og musikkortet med FYT-radio (later som stereoen har sendt data) og uten. Skal ikke krasje. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w1024dp-h600dp-land-mdpi")
class FytRadioSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun start() { rule.mainClock.autoAdvance = false; settle() }

    @After fun reset() {
        rule.runOnUiThread {
            FytRadio.fake = null; Nav.screen = Screen.DASH
            FytRadioLogic.BANKS.forEach { Prefs.set("fyt_presets_$it", null) }
            Prefs.set("night_mode", "DAY"); Theme.night = false
        }
    }

    private fun settle(ms: Long = 1_500) = rule.settle(ms)

    @Test fun radioSidenUtenData() {
        rule.runOnUiThread { Nav.screen = Screen.RADIO }
        settle()
        rule.awaitText("Start radio")
    }

    @Test fun radioSidenMedFytData() {
        rule.runOnUiThread {
            FytRadio.fake = FytRadio.Fake(10170, "NRK P1", "Nyheter hver hele time ".repeat(6), stereo = true, onAir = true)
            Prefs.set("fyt_presets_FM1", FytRadioLogic.encode(listOf(FytRadioLogic.Slot(10170, "NRK P1"), null, FytRadioLogic.Slot(9430, null))))
            Nav.screen = Screen.RADIO
        }
        settle()
        rule.awaitText("101,70")
        rule.awaitText("Søk etter stasjoner").performClick()   // forvalg finnes: må trykkes igjen (ingenting sendes)
        settle()
        rule.awaitText("Trykk igjen – forvalgene byttes ut")
        rule.awaitText("FM2").performClick()
        settle()
        // Natt, og AM-frekvens uten navn
        rule.runOnUiThread { Prefs.set("night_mode", "NIGHT"); Theme.night = true; FytRadio.fake = FytRadio.Fake(522, null, null, false, onAir = true) }
        settle()
        rule.awaitText("522")
        // Forsiden: musikkortet viser radioen
        rule.runOnUiThread { FytRadio.fake = FytRadio.Fake(9430, null, null, false, onAir = true); Nav.screen = Screen.DASH }
        settle()
        rule.awaitText("FM 94,30")
    }
}
