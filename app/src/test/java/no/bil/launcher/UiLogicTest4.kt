package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLogicTest4 {
    @Test
    fun farten_vises_som_strek_naar_den_er_ukjent() {
        assertEquals("–", speedText(null))
        assertEquals("–", speedText(Gps(50, 59.0, 10.0, 0f, stale = true)))
        assertEquals("–", speedText(Gps(0, 59.0, 10.0, 0f, hasSpeed = false)))
        assertEquals("0", speedText(Gps(0, 59.0, 10.0, 0f)))
        assertEquals("87", speedText(Gps(87, 59.0, 10.0, 0f)))
    }

    @Test
    fun teksten_ved_farten() {
        assertEquals("Søker GPS", gpsCaption(null))
        assertEquals("Mistet GPS", gpsCaption(Gps(0, 59.0, 10.0, 90f, stale = true)))
        assertEquals("GPS", gpsCaption(Gps(2, 59.0, 10.0, 90f)))
        assertEquals("Ø", gpsCaption(Gps(60, 59.0, 10.0, 90f)))
    }

    @Test
    fun musikken_startes_bare_naar_ingenting_annet_spiller() {
        assertTrue(shouldResumeMusic(enabled = true, playing = false, otherAudio = false, inCall = false))
        assertFalse(shouldResumeMusic(enabled = false, playing = false, otherAudio = false, inCall = false))
        assertFalse(shouldResumeMusic(enabled = true, playing = true, otherAudio = false, inCall = false))
        assertFalse(shouldResumeMusic(enabled = true, playing = false, otherAudio = true, inCall = false))   // CarPlay / FM-radio
        assertFalse(shouldResumeMusic(enabled = true, playing = false, otherAudio = false, inCall = true))
    }
}
