package no.bil.launcher

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import no.bil.launcher.PowerLogic.Mode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Ren logikk for runde r9 «kjernen» (uten Android). */
class KjerneLogicTest {
    @Test fun fangetFeilErMerket() {
        val e = caughtEntry("fre 13. nov", "versjonssjekk", "java.lang.IllegalStateException: x\n\tat a.b")
        assertTrue(e.startsWith("fre 13. nov\n"))
        assertTrue(e.contains("FANGET i versjonssjekk"))
        assertTrue("skal ikke se ut som en krasj", e.contains("ikke en krasj"))
        assertTrue(e.endsWith("at a.b"))
    }
}

/**
 * Kjernen på ekte (Robolectric): en feil i en bakgrunnsjobb tar ikke ned T'OS, telefonboken flyttes ut av hovedfila,
 * turen lagres med én skriving, og skjerm-løkkene står stille når T'OS ikke vises.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class KjerneSmokeTest {
    private val c: Context get() = ApplicationProvider.getApplicationContext()

    @After fun reset() {
        Power.setModeForTest(Mode.AWAKE, visible = true)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun feilIBakgrunnsjobbKrasjerIkke() {
        val before = CrashLog.caughtCount
        // Uten handleren ville dette gått til Thread sin «uncaught»-handler (= krasj, svart skjerm, sikker modus etter 3)
        Hub.scope.launch(CoroutineName("testjobb")) { throw IllegalStateException("prøvefeil") }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before + 1, CrashLog.caughtCount)
        val log = File(c.filesDir, "krasj.txt").readText()
        assertTrue(log, log.contains("FANGET i testjobb"))
        assertTrue(log.contains("prøvefeil"))
        // Hub sin scope lever videre: neste jobb kjøres
        var ran = false
        Hub.scope.launch { ran = true }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ran)
    }

    @Test fun telefonbokenFlyttesUtAvHovedfila() {
        val main = c.getSharedPreferences("tos", Context.MODE_PRIVATE)
        main.edit().putString("fyt_book", "Ola\u001f12345").commit()
        assertEquals("Ola\u001f12345", Store.str("fyt_book"))
        assertFalse("ligger ikke lenger i hovedfila", main.contains("fyt_book"))
        assertEquals("Ola\u001f12345", c.getSharedPreferences("tos_lager", Context.MODE_PRIVATE).getString("fyt_book", null))
        // Lagres og leses i den nye fila
        Store.set("fyt_calls", "x")
        assertEquals("x", Store.str("fyt_calls"))
        assertFalse(main.contains("fyt_calls"))
        assertEquals("", Store.str("finnes_ikke"))
    }

    @Test fun flereVerdierEnSkrivingUtenNyTegning() {
        val v0 = Prefs.version
        Prefs.setQuietAll("k_f" to 1.5f, "k_l" to 7L, "k_i" to 3, "k_s" to "a", "k_b" to true)
        assertEquals(1.5f, Prefs.float("k_f"), 0f)
        assertEquals(7L, Prefs.long("k_l"))
        assertEquals(3, Prefs.int("k_i"))
        assertEquals("a", Prefs.str("k_s"))
        assertTrue(Prefs.bool("k_b"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("skjermen tegnes ikke på nytt", v0, Prefs.version)
    }

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Test fun skjermLoekkerStaarStilleNaarTosErSkjult() {
        Power.setModeForTest(Mode.AWAKE, visible = false)       // Spotify/CarPlay vises
        val job = GlobalScope.async(Dispatchers.Default) { Power.uiDelay(10); true }
        Thread.sleep(300)
        assertFalse("venter så lenge T'OS er skjult", job.isCompleted)
        Power.setModeForTest(Mode.DARK, visible = true)         // skjermen av
        Thread.sleep(100)
        assertFalse(job.isCompleted)
        Power.setModeForTest(Mode.AWAKE, visible = true)        // T'OS vises igjen
        assertTrue(runBlocking { withTimeout(3000) { job.await() } })
    }
}
