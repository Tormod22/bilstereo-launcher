package no.bil.launcher

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** «Sist kjente tilstand» og oppstartstider – ren logikk. */
class LastStateLogicTest {
    private val L = LastStateLogic

    @Test fun lagresOgLesesIgjen() {
        val s = LastStateLogic.Snap(L.MUSIC, "Moonlight\nSonata \\ 1", "Beethoven", "com.spotify.music", "iPhone", 12.6f, 1_700_000_000_000)
        assertEquals(s, L.decode(L.encode(s)))
        val r = LastStateLogic.Snap(L.RADIO, "NRK P1", null, null, at = 5)
        assertEquals(r, L.decode(L.encode(r)))
    }

    @Test fun odelagtFilKrasjerIkke() {
        assertNull(L.decode(null))
        assertNull(L.decode(""))
        assertNull(L.decode("søppel uten likhetstegn"))
        assertNull(L.decode("kind=music\ntitle=\nat=1"))                 // ingenting å vise
        assertEquals(L.MUSIC, L.decode("kind=ukjent\ntitle=X")!!.kind)   // ukjent type → musikk
        assertNull(L.decode("title=\nvolts=99")?.volts)                  // umulig spenning forkastes (og da er det ingenting)
        assertEquals(12.4f, L.decode("title=\nvolts=12.4")!!.volts)      // bare spenning er nok
    }

    @Test fun flettingBeholderMusikkenTilNoeNyttSpiller() {
        val old = LastStateLogic.Snap(L.MUSIC, "A", "B", "p", volts = 12.5f, at = 100)
        // Kald start: ingen musikk ennå, bare ny spenning → sangen og tiden står
        val m1 = L.merge(old, null, null, 12.7f, now = 999)!!
        assertEquals("A", m1.title); assertEquals(12.7f, m1.volts); assertEquals(100L, m1.at)
        // Samme sang igjen: tiden flyttes ikke (det er fortsatt «sist spilt» fra da)
        assertEquals(100L, L.merge(old, LastStateLogic.Snap(L.MUSIC, "A", "B", "p"), null, null, now = 999)!!.at)
        // Ny sang: ny tid, spenningen fra før står
        val m3 = L.merge(old, LastStateLogic.Snap(L.RADIO, "NRK P1", null, null), "iPhone", null, now = 999)!!
        assertEquals("NRK P1", m3.title); assertEquals(999L, m3.at); assertEquals(12.5f, m3.volts); assertEquals("iPhone", m3.phone)
        assertNull(L.merge(null, null, null, null, now = 1))
    }

    @Test fun merkelapper() {
        val now = 1_700_000_000_000L
        assertEquals("Sist spilt", L.label(LastStateLogic.Snap(L.MUSIC, "A", null, null, at = now - 60_000), now))
        assertEquals("Sist hørt", L.label(LastStateLogic.Snap(L.RADIO, "A", null, null, at = now - 60_000), now))
        assertTrue(L.label(LastStateLogic.Snap(L.MUSIC, "A", null, null, at = now - 3 * 86_400_000L), now).matches(Regex("Sist spilt \\d{1,2}\\.\\d{1,2}\\.")))
        assertEquals("Sist spilt · Beethoven", L.subLine(LastStateLogic.Snap(L.MUSIC, "A", "Beethoven", null, at = now), now))
        assertEquals("Sist hørt", L.subLine(LastStateLogic.Snap(L.NETRADIO, "A", "", null, at = now), now))
        assertEquals(12.6f, L.roundVolts(12.63f))
        assertNull(L.roundVolts(null))
    }

    @Test fun oppstartstider() {
        val T = StartTimingLogic
        assertEquals("1,2 s", T.secs(1_234))
        assertEquals("–", T.secs(null))
        val line = T.line("07:42", mapOf(StartTiming.FRAME to 1_200L, StartTiming.MUSIC to 2_800L))
        assertEquals("kl. 07:42 · første bilde 1,2 s · klar – · musikk 2,8 s · stereo – · GPS –", line)
        // Bare de 10 siste beholdes, nyeste nederst
        var text: String? = null
        for (i in 1..12) text = T.append(text, "linje $i")
        val lines = text!!.trim().lines()
        assertEquals(10, lines.size); assertEquals("linje 3", lines.first()); assertEquals("linje 12", lines.last())
        // Ferdig: alt målt (uten FYT trengs ikke stereo), ellers etter 90 s
        val all = mapOf(StartTiming.FRAME to 1L, StartTiming.READY to 1L, StartTiming.MUSIC to 1L, StartTiming.GPS to 1L)
        assertTrue(T.done(all, 5_000, fytPresent = false))
        assertFalse(T.done(all, 5_000, fytPresent = true))
        assertTrue(T.done(emptyMap(), 90_000, fytPresent = true))
    }
}

/** Forsiden ved kald start: sist spilt / sist hørt / sist målt spenning vises før noe ekte er klart. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w1024dp-h600dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LastStateSmokeTest {
    @get:Rule val rule = createComposeRule()
    private val c: Context get() = ApplicationProvider.getApplicationContext()

    @After fun reset() {
        rule.runOnUiThread { NowPlaying.title = null; NowPlaying.artist = null; FytRadio.fake = null; LastState.resetForTest() }
        File(c.filesDir, "sist.txt").delete()
    }

    private fun shot(view: android.view.View?, name: String) {
        val dir = System.getenv("TOS_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        rule.runOnUiThread {
            val v = view ?: return@runOnUiThread
            val bmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
            File(dir).mkdirs()
            java.io.FileOutputStream(File(dir, "$name.png")).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun forsidenViserSistSpiltFraDisk() {
        // Fila fra forrige tur (som T'OS skrev før tenningen gikk av)
        val snap = LastStateLogic.Snap(LastStateLogic.MUSIC, "Moonlight Sonata", "Beethoven", "com.spotify.music", volts = 12.6f, at = System.currentTimeMillis() - 60_000)
        File(c.filesDir, "sist.txt").writeText(LastStateLogic.encode(snap))
        rule.runOnUiThread { NowPlaying.title = null; NowPlaying.artist = null; LastState.resetForTest(); LastState.load(c) }
        assertNotNull("lest fra disk", LastState.restored)

        var view: android.view.View? = null
        rule.mainClock.autoAdvance = false
        rule.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.25f)) {
                Box(Modifier.size(1024.dp, 600.dp).background(Bg).padding(12.dp)) { Dashboard({}, {}) }
            }
        }
        rule.settle(800)
        rule.awaitText("Moonlight Sonata")
        rule.awaitText("Sist spilt · Beethoven")
        rule.awaitDescription("Bilbatteri (sist målt)")
        rule.awaitText(CarBattery.text(12.6f))
        shot(view, "forside_sist")

        // Ekte musikk kommer: den tar over på samme plass, og «Sist spilt» forsvinner
        rule.runOnUiThread { NowPlaying.title = "Clair de Lune"; NowPlaying.artist = "Debussy" }
        rule.awaitText("Clair de Lune")
        rule.awaitUntil("«Sist spilt» borte") { rule.countText("Sist spilt · Beethoven") == 0 }
    }

    @Test fun sistHortRadio() {
        rule.runOnUiThread {
            NowPlaying.title = null
            LastState.restored = LastStateLogic.Snap(LastStateLogic.RADIO, "NRK P1", "101,6 MHz", null, at = System.currentTimeMillis())
        }
        rule.mainClock.autoAdvance = false
        rule.setContent { Box(Modifier.size(1000.dp, 110.dp)) { ActionRow({}, {}, Modifier.size(1000.dp, 110.dp)) } }
        rule.settle(800)
        rule.awaitText("NRK P1")
        rule.awaitText("Sist hørt · 101,6 MHz")
        rule.awaitDescription("Radio")
        // Stereoen melder radioen: ekte data, ikke «Sist hørt» lenger
        rule.runOnUiThread { FytRadio.fake = FytRadio.Fake(10160, "NRK P1", null, stereo = true, onAir = true) }
        rule.awaitUntil("«Sist hørt» borte") { rule.countText("Sist hørt · 101,6 MHz") == 0 }
    }
}
