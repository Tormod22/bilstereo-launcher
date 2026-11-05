package no.bil.launcher

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/*
 * «Alle skjermer åpner uten krasj» – røyktester som kjører det ekte Compose-UI-et i Robolectric
 * (en Android-etterligning i JVM-en). Vi har ingen stereo eller emulator i CI, så dette er eneste
 * måten å fange krasj under kjøring på (null-verdier, manglende systemtjenester, feil i Compose).
 *
 * Skjermstørrelsen er som stereoen: 7" liggende (1024×600).
 *
 * Ikke dekket (miljø, ikke feil i T'OS):
 *  - Kartet uten nett (MapLibre) trenger egne ARM-biblioteker som ikke finnes i JVM-en. Det brukes
 *    bare når kartfila er lastet ned, og det er den aldri i testene – da vises det vanlige flis-kartet.
 *  - Nettverk (kartfliser, nettradio, oppdateringssjekk) får ikke svar her; det skal T'OS tåle.
 */

private const val STEREO = "w1024dp-h600dp-land-mdpi"

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = STEREO)
class ScreensSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun start() {
        // Animasjoner, blinkende markør og klokker går for alltid – vi styrer tiden selv
        rule.mainClock.autoAdvance = false
        settle()
    }

    @After fun reset() {
        rule.runOnUiThread {
            Nav.screen = Screen.DASH; Nav.quick = false; Nav.pick = null
            TosKeyboard.close(); Phone.calls = emptyList(); Call.name = null
        }
    }

    private fun settle(ms: Long = 1_500) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    private fun show(s: Screen) {
        rule.runOnUiThread { Nav.screen = s }
        settle()
    }

    private fun night(on: Boolean) {
        rule.runOnUiThread { Prefs.set("night_mode", if (on) "NIGHT" else "DAY"); Theme.night = on }
        settle()
    }

    @Test fun alleSkjermerAapnes_dag() {
        night(false)
        for (s in Screen.entries) show(s)
        show(Screen.DASH)
    }

    @Test fun alleSkjermerAapnes_natt() {
        night(true)
        for (s in Screen.entries) show(s)
        show(Screen.DASH)
    }

    @Test fun alleInnstillingskategorier() {
        for (c in SetCat.entries) {
            show(Screen.DASH)
            // Kategorien leses når siden åpnes (som når hurtigpanelet åpner Wi-Fi direkte)
            rule.runOnUiThread { Prefs.set("settings_cat", c.name) }
            show(Screen.SETTINGS)
        }
        show(Screen.DASH)
    }

    @Test fun hurtigpanel() {
        rule.runOnUiThread { Nav.quick = true }
        settle()
        rule.runOnUiThread { Nav.quick = false }
        settle()
    }

    @Test fun appVelger() {
        rule.runOnUiThread { Nav.pick = "Velg CarPlay-app" to "carplay_app" }
        settle()
        rule.runOnUiThread { Nav.pick = null }
        settle()
    }

    @Test fun tastatur_volum_melding() {
        val kinds = KbKind.entries
        for (k in kinds) {
            var text = ""
            rule.runOnUiThread {
                TosKeyboard.open(KbBinding(this, k, autoCap = true, password = k == KbKind.TEXT, hint = "Søk", doneLabel = "Ferdig",
                    read = { text }, write = { text = it }, done = { TosKeyboard.close() }, revealed = { false }, toggle = {}))
            }
            settle()
        }
        rule.runOnUiThread { TosKeyboard.close(); VolumeUi.show(7, 15); Notify.show("Røyktest") }
        settle(500)
        settle(6_000)   // melding og volumvindu forsvinner av seg selv
    }

    @Test fun samtaleBanner() {
        rule.runOnUiThread {
            Phone.calls = listOf(PhoneCall(1, "+4790000000", "Ola", CallState.RINGING, incoming = true, connectedAt = 0, canHold = true))
        }
        settle()
        rule.runOnUiThread {
            Phone.calls = listOf(PhoneCall(1, "+4790000000", null, CallState.ACTIVE, incoming = true,
                connectedAt = System.currentTimeMillis() - 65_000, canHold = true))
        }
        settle()
        // Innkommende samtale fra varsel (når T'OS ikke er telefon-app)
        rule.runOnUiThread { Phone.calls = emptyList(); Call.name = "Kari" }
        settle()
    }
}

/** Samtaleskjermen (CallActivity) tegnet alene, i alle tilstander. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = STEREO)
class CallScreenSmokeTest {
    @get:Rule val rule = createComposeRule()

    @After fun reset() { rule.runOnUiThread { Phone.calls = emptyList() } }

    @Test fun samtaleskjermAlleTilstander() {
        rule.mainClock.autoAdvance = false
        var hidden = false
        rule.setContent { CallScreen(onHide = { hidden = true }) }
        for (st in CallState.entries) {
            rule.runOnUiThread {
                Phone.calls = listOf(PhoneCall(1, "+4790000000", "Ola Nordmann", st, incoming = st == CallState.RINGING,
                    connectedAt = if (st == CallState.ACTIVE) System.currentTimeMillis() - 5_000 else 0, canHold = true,
                    ended = if (st == CallState.ENDED) "Avvist" else null))
            }
            rule.mainClock.advanceTimeBy(1_000); rule.waitForIdle()
        }
        // To samtaler samtidig (en venter)
        rule.runOnUiThread {
            Phone.calls = listOf(
                PhoneCall(1, "+4790000000", "Ola", CallState.HOLDING, false, System.currentTimeMillis() - 60_000, true),
                PhoneCall(2, "", null, CallState.RINGING, true, 0, true))
        }
        rule.mainClock.advanceTimeBy(1_000); rule.waitForIdle()
        rule.runOnUiThread { Phone.calls = emptyList() }
        rule.mainClock.advanceTimeBy(2_000); rule.waitForIdle()
        check(hidden) { "Samtaleskjermen skal lukke seg når det ikke er noen samtaler" }
    }
}
