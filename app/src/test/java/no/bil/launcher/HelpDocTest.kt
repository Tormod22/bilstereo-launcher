package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Bruksanvisningen (Help.kt): tolken, at kopien i assets er lik docs/BRUKSANVISNING.md, og når oppstartsskjermen vises. */
class HelpDocTest {
    @Test fun tolkerOverskrifterAvsnittPunkterOgSteg() {
        val b = HelpDoc.parse(
            """
            # Tittel

            Første linje
            fortsetter her.

            ## Del
            - punkt **fet**
              fortsatt punkt
            1. steg én
            2. steg to

            ```
            adb shell noe
            ```
            """.trimIndent()
        )
        assertEquals(
            listOf(
                HelpBlock.Title("Tittel"),
                HelpBlock.Para("Første linje fortsetter her."),
                HelpBlock.Section("Del"),
                HelpBlock.Bullet("punkt **fet** fortsatt punkt"),
                HelpBlock.Step(1, "steg én"),
                HelpBlock.Step(2, "steg to"),
                HelpBlock.Code("adb shell noe"),
            ), b
        )
        assertEquals(listOf(2 to "Del"), HelpDoc.sections(b))
        assertEquals(-1, HelpDoc.currentSection(HelpDoc.sections(b), 0))
        assertEquals(0, HelpDoc.currentSection(HelpDoc.sections(b), 5))
    }

    @Test fun fetOgKodeITeksten() {
        val i = HelpDoc.inline("Trykk **Lær** og `adb` – **ikke lukket")
        assertEquals("Trykk Lær og adb – **ikke lukket", i.plain)
        assertEquals(listOf(6..8), i.bold)
        assertEquals(listOf(13..15), i.code)
    }

    /** Testene kjøres fra app-mappa. Kopien i appen skal være nøyaktig lik den i docs. */
    @Test fun kopienIAppenErLikDocs() {
        val docs = File("../docs/BRUKSANVISNING.md")
        val asset = File("src/main/assets/${HelpDoc.ASSET}")
        assertTrue("finner ikke ${docs.absolutePath}", docs.exists())
        assertEquals("Kopier docs/BRUKSANVISNING.md til app/src/main/assets/${HelpDoc.ASSET}", docs.readText(), asset.readText())
        val blocks = HelpDoc.parse(asset.readText())
        val titles = HelpDoc.sections(blocks).map { it.second }
        for (t in listOf("Forsiden", "Rattknappene", "Radio", "Hvis noe oppfører seg rart", "Oppdatere T'OS")) assertTrue(t, t in titles)
    }

    @Test fun oppstartsskjermBareEtterAtSkjermenVarAv() {
        val w = SplashLogic.WAKE_MS
        assertTrue(SplashLogic.afterWake(true, 1_000, 1_000 + w))
        assertFalse(SplashLogic.afterWake(true, 1_000, 1_000 + w - 1))     // bare kort av
        assertFalse(SplashLogic.afterWake(false, 1_000, 1_000 + 10 * w))   // brukte en annen app: hjem uten oppstartsskjerm
        assertFalse(SplashLogic.afterWake(true, 0, 10 * w))               // aldri stoppet
    }
}
