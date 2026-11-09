package no.bil.launcher

import android.os.Looper
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/*
 * Felles venting for røyktestene (Compose i Robolectric med mainClock.autoAdvance = false).
 *
 * Hvorfor: mye av UI-et henter data i bakgrunnen (produceState + Dispatchers.IO: bruksanvisningen fra assets,
 * applista, bilbildet …) og har animasjoner/forsinkelser. Bakgrunnstrådene går i EKTE tid, mens testklokka bare går
 * når vi flytter den. En fast «settle()» rett før en sjekk blir da et kappløp: på en treg maskin (GitHub Actions)
 * er IO-arbeidet ikke ferdig ennå, og testen feiler selv om T'OS er riktig – og da stopper oppdateringen.
 *
 * [awaitUntil] flytter testklokka (Compose) OG Robolectric-klokka (Handler.postDelayed, Dispatchers.Main/Hub.scope)
 * litt om gangen, gir bakgrunnstrådene ekte tid og sjekker på nytt – til betingelsen holder eller en romslig frist
 * er nådd. Betingelsen er den samme som før, så testene sjekker akkurat det samme; de venter bare lenge nok.
 */
internal object TestWait {
    /** Testklokke-tid vi maksimalt flytter før vi gir opp (romslig – det meste skjer innen ett sekund). */
    const val MAX_VIRTUAL_MS = 20_000L
    /** Minst så mange runder før vi gir opp (hver runde gir bakgrunnstrådene ekte tid). */
    const val MIN_ROUNDS = 50
    /** Minst så lang ekte tid før vi gir opp (treg CI-maskin med full CPU). */
    const val MIN_REAL_MS = 10_000L
    /** Aldri lenger enn dette i ekte tid (sikkerhet mot en test som henger). */
    const val MAX_REAL_MS = 60_000L
    /** Hvor mye testklokka flyttes per runde. Kort nok til å fange ting som bare vises en stund («Trykk igjen»). */
    const val STEP_MS = 100L
    private const val FRAME_MS = 16L
}

/** La UI-et gå [ms] to ganger (som de gamle settle()-ene): animasjoner og forsinkelser i testklokka blir ferdige. */
internal fun ComposeTestRule.settle(ms: Long = 1_500) {
    mainClock.advanceTimeBy(ms); waitForIdle()
    mainClock.advanceTimeBy(ms); waitForIdle()
}

/**
 * Vent til [condition] holder: flytt klokkene litt, gi bakgrunnstrådene ekte tid, la hovedtråden og Compose bli
 * ferdige, sjekk igjen. Feiler (AssertionError med [what]) først når både testklokka har gått [TestWait.MAX_VIRTUAL_MS],
 * minst [TestWait.MIN_ROUNDS] runder og [TestWait.MIN_REAL_MS] ekte tid er brukt – eller etter [TestWait.MAX_REAL_MS].
 */
internal fun ComposeTestRule.awaitUntil(what: String, condition: () -> Boolean) {
    val start = System.nanoTime()
    var virtual = 0L
    var rounds = 0
    while (true) {
        waitForIdle()
        if (condition()) return
        val real = (System.nanoTime() - start) / 1_000_000
        if ((virtual >= TestWait.MAX_VIRTUAL_MS && rounds >= TestWait.MIN_ROUNDS && real >= TestWait.MIN_REAL_MS) ||
            real >= TestWait.MAX_REAL_MS) {
            throw AssertionError("Ventet på $what: kom ikke etter $virtual ms testklokke, $rounds runder og $real ms ekte tid")
        }
        // Ekte tid til Dispatchers.IO-trådene (assets, pakkeliste, bilder) – kort de første rundene
        Thread.sleep(if (rounds < 5) 2 else 10)
        // Etter fristen i testklokka: bare ett bilde om gangen (så Compose fortsatt tegner det IO-arbeidet leverer)
        val step = if (virtual < TestWait.MAX_VIRTUAL_MS) TestWait.STEP_MS else 16L
        mainClock.advanceTimeBy(step)
        // Robolectric-klokka: Handler.postDelayed og delay() på Dispatchers.Main (Hub.scope) går også videre
        shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS)
        virtual += step
        rounds++
    }
}

/** Antall noder med akkurat denne teksten (også inni sammenslåtte noder). */
internal fun ComposeTestRule.countText(text: String): Int = onAllNodesWithText(text).fetchSemanticsNodes().size

/** Vent til teksten finnes, og sjekk så som før at det er nøyaktig én slik node ([onNodeWithText] + assertExists). */
internal fun ComposeTestRule.awaitText(text: String): SemanticsNodeInteraction {
    awaitUntil("teksten «$text»") { countText(text) > 0 }
    return onNodeWithText(text).assertExists()
}

/** Vent til en node med denne beskrivelsen finnes, og sjekk så at det er nøyaktig én. */
internal fun ComposeTestRule.awaitDescription(desc: String): SemanticsNodeInteraction {
    awaitUntil("beskrivelsen «$desc»") { onAllNodesWithContentDescription(desc).fetchSemanticsNodes().isNotEmpty() }
    return onNodeWithContentDescription(desc).assertExists()
}
