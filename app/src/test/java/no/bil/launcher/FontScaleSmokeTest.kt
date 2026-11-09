package no.bil.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Sidene tegnet som på stereoen: 1024×600, 160 dpi og TEKSTSKALA 1,25 (brukerens innstilling). Fanger krasj i lange
 * norske tekster og tilstander som bare finnes på FYT (førstegangsoppsett med FYT-stegene, radio, rattknapper).
 * Med miljøvariabelen TOS_SHOTS=<mappe> lagres et bilde av hver side (til å se etter tekst som kuttes).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w1024dp-h600dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FontScaleSmokeTest {
    @get:Rule val rule = createComposeRule()

    @After fun reset() {
        rule.runOnUiThread { Nav.screen = Screen.DASH; TosKeyboard.close(); Theme.night = false; FytRadio.fake = null }
    }

    private fun settle() {
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
    }

    private var page: (@Composable () -> Unit)? by androidx.compose.runtime.mutableStateOf<(@Composable () -> Unit)?>(null)

    private var view: android.view.View? = null

    private fun shot(name: String) {
        val dir = System.getenv("TOS_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        rule.runOnUiThread {
            val v = view ?: return@runOnUiThread
            val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
            java.io.File(dir).mkdirs()
            java.io.FileOutputStream(java.io.File(dir, "$name.png")).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun show(name: String, night: Boolean = false, content: @Composable () -> Unit) {
        rule.runOnUiThread { Theme.night = night; page = content }
        settle()
        shot(name + if (night) "_natt" else "")
    }

    @Test fun siderMedStorTekst() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.25f)) {
                Box(Modifier.size(1024.dp, 600.dp).background(Bg).padding(12.dp)) { page?.invoke() }
            }
        }
        for (night in listOf(false, true)) {
            show("oppsett", night) { SetupScreen {} }
            show("musikk", night) { MusicScreen {} }
            show("radio", night) { RadioScreen {} }
            show("telefon", night) { PhoneScreen {} }
            show("rattknapper", night) { SteerScreen {} }
            show("knapper", night) { KeysScreen {} }
            show("bluetooth", night) { BluetoothScreen {} }
            show("bil", night) { CarScreen {} }
            show("tuning", night) { TuningScreen {} }
            show("siste", night) { RecentsScreen {} }
            show("hurtigpanel", night) { QuickPanel(onClose = {}, onGo = {}) }
        }
        // Musikkortet på forsiden følger kilden: FYT-radio = radiosymbol, «Lyd av» i stedet for pause, neste = neste stasjon
        rule.runOnUiThread { FytRadio.fake = FytRadio.Fake(10170, "NRK P1", null, stereo = true, onAir = true) }
        show("musikkort_radio") { ActionRow({}, {}, Modifier.size(1000.dp, 110.dp)) }
        rule.onNodeWithText("NRK P1").assertExists()
        rule.onNodeWithContentDescription("Neste stasjon").assertExists()
        rule.onNodeWithContentDescription("Radio").assertExists()
        rule.onNodeWithContentDescription("Lyd av").assertExists()
        rule.runOnUiThread { FytRadio.fake = null }
        show("musikkort") { ActionRow({}, {}, Modifier.size(1000.dp, 110.dp)) }
        rule.onNodeWithContentDescription("Neste sang").assertExists()
        // Oppsettet: stegene som alltid vises finnes
        show("oppsett2") { SetupScreen {} }
        rule.onNodeWithText("Knappestyring").assertExists()
        rule.onNodeWithText("Varseltilgang").assertExists()
    }
}
