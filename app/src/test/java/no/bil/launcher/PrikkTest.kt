package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Prikkstil (v5.12): etiketter med store bokstaver og prikkfonten som følger med. */
class PrikkTest {
    @Test fun etiketterMedStoreBokstaver() {
        assertEquals("SØNDAG 15. NOVEMBER", caps("søndag 15. november"))
        assertEquals("ÆRFUGL ÅS", caps("ærfugl ås"))
        assertEquals("KM/T", caps("km/t"))
    }

    /** Fontfilene finnes, er ekte TrueType-filer, og lisensen følger med (SIL OFL 1.1). */
    @Test fun prikkfontenFinnes() {
        for (name in listOf("doto_bold.ttf", "doto_black.ttf")) {
            val f = File("src/main/res/font/$name")
            assertTrue(name, f.isFile && f.length() > 10_000)
            val head = f.readBytes().copyOfRange(0, 4)
            assertTrue("$name er TrueType", head.contentEquals(byteArrayOf(0, 1, 0, 0)))
        }
        val ofl = File("src/main/assets/licenses/Doto-OFL.txt").readText()
        assertTrue(ofl.contains("SIL Open Font License"))
    }
}
