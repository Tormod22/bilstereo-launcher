package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class UiLogicTest {
    @Test fun favorittnavn_renses() {
        assertEquals("Ola Nordmann", favText("Ola|Nordmann"))
        assertEquals("Mor Far", favText(" Mor\nFar\r\n"))
        assertEquals("", favText("|\n|"))
        assertEquals("+47 123 45 678", favText("+47 123 45 678"))
    }

    @Test fun kartzoom_over_16_forstorres() {
        assertEquals(15, tileZoom(15))
        assertEquals(16, tileZoom(16))
        assertEquals(16, tileZoom(18))
        assertEquals(1f, overZoom(15, tileZoom(15)), 0f)
        assertEquals(2f, overZoom(17, tileZoom(17)), 0f)
        assertEquals(4f, overZoom(18, tileZoom(18)), 0f)
        // Gammelt bilde på høyere zoom enn ønsket: aldri forminsket (kortet skal være fylt)
        assertEquals(1f, overZoom(14, 16), 0f)
    }
}
