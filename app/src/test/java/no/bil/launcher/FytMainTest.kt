package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FYT-hovedmodulen (FytMain): lys → natt, rygging, tenning, lysstyrke, tastelyd. Ren logikk. */
class FytMainTest {
    @Test fun av_paa_meldinger() {
        assertNull(FytMainLogic.flag(null))
        assertNull(FytMainLogic.flag(intArrayOf()))
        assertFalse(FytMainLogic.flag(intArrayOf(0))!!)
        assertTrue(FytMainLogic.flag(intArrayOf(1))!!)
        assertTrue("alt annet enn 0 er på (som vasyl91)", FytMainLogic.flag(intArrayOf(2))!!)
        // Tastelyd/navigasjonsmiks: bare ekte 0/1 – ellers vises ikke valget
        assertEquals(true, FytMainLogic.binary(intArrayOf(1)))
        assertEquals(false, FytMainLogic.binary(intArrayOf(0)))
        assertNull(FytMainLogic.binary(intArrayOf(5)))
        assertNull(FytMainLogic.binary(null))
    }

    @Test fun natt_folger_lysene_naar_de_er_kjent() {
        // Automatisk: lysene avgjør når de er kjent
        assertTrue(FytMainLogic.night("AUTO", byLights = true, lightsOn = true, sunNight = false))
        assertFalse(FytMainLogic.night("AUTO", byLights = true, lightsOn = false, sunNight = true))
        // Ikke meldt ennå / valget av: sola
        assertTrue(FytMainLogic.night("AUTO", byLights = true, lightsOn = null, sunNight = true))
        assertFalse(FytMainLogic.night("AUTO", byLights = false, lightsOn = true, sunNight = false))
        // Alltid dag/natt overstyrer alt
        assertFalse(FytMainLogic.night("DAY", true, true, true))
        assertTrue(FytMainLogic.night("NIGHT", true, false, false))
    }

    @Test fun endringer_i_tenning_og_rygging() {
        assertEquals(FytMainLogic.Edge.NONE, FytMainLogic.edge(null, true))   // nåverdien ved registrering
        assertEquals(FytMainLogic.Edge.NONE, FytMainLogic.edge(true, true))
        assertEquals(FytMainLogic.Edge.OFF, FytMainLogic.edge(true, false))
        assertEquals(FytMainLogic.Edge.ON, FytMainLogic.edge(false, true))
    }

    @Test fun t_os_holder_seg_unna_mens_bilen_rygger() {
        assertTrue(FytMainLogic.stayOut(reversing = true, startedAt = 1_000, endedAt = 0, now = 5_000))
        // Rett etter rygging: litt til
        assertTrue(FytMainLogic.stayOut(false, 1_000, 10_000, 11_000))
        assertFalse(FytMainLogic.stayOut(false, 1_000, 10_000, 10_000 + FytMainLogic.REVERSE_GRACE_MS + 1))
        assertFalse("aldri rygget", FytMainLogic.stayOut(false, 0, 0, 50_000))
        // Står «rygger» fast i over 10 min, blir ikke T'OS borte for alltid
        assertFalse(FytMainLogic.stayOut(true, 0, 0, FytMainLogic.REVERSE_MAX_MS + 1))
    }

    @Test fun lysstyrke_nivaa() {
        assertEquals(100, FytMainLogic.levelFor(1f))
        assertEquals(50, FytMainLogic.levelFor(0.5f))
        assertEquals("aldri helt svart", FytMainLogic.MIN_LEVEL, FytMainLogic.levelFor(0f))
        assertEquals(100, FytMainLogic.levelFor(2f))
        assertEquals(60, FytMainLogic.level(intArrayOf(60)))
        assertNull(FytMainLogic.level(intArrayOf(255)))
        assertNull(FytMainLogic.level(null))
    }

    @Test fun nattdemping_via_stereoen() {
        // Natt, ikke dempet ennå → nattnivået
        assertEquals(25 to true, FytMainLogic.nightStep(want = true, dimmed = false, dayLevel = 80, nightLevel = 25))
        // Dag igjen → dagnivået tilbake
        assertEquals(80 to false, FytMainLogic.nightStep(false, true, 80, 25))
        // Ingen endring → ingenting sendes (brukerens glider får være i fred)
        assertEquals(null to true, FytMainLogic.nightStep(true, true, 80, 25))
        assertEquals(null to false, FytMainLogic.nightStep(false, false, 80, 25))
        // Nattnivå 0 sendes aldri (svart skjerm)
        assertEquals(FytMainLogic.MIN_LEVEL to true, FytMainLogic.nightStep(true, false, 80, 0))
    }

    @Test fun kartleggingen_har_navn_paa_hovedkodene() {
        assertEquals("lys (ILL)", FytMainLogic.codeName(4))
        assertEquals("rygger", FytMainLogic.codeName(12))
        assertEquals("tenning (ACC)", FytMainLogic.codeName(50))
        assertEquals("", FytMainLogic.codeName(9999))
        assertTrue(FytLogic.describe(0, 12, intArrayOf(1), null, null).contains("(rygger)"))
        assertTrue(FytLogic.describe(0, 0, intArrayOf(1), null, null).contains("kilde: Radio"))
        assertTrue(FytLogic.describe(4, 4, intArrayOf(1), null, null).contains("(tastelyd)"))
        assertTrue(FytMainLogic.answered(true, true))
        assertFalse(FytMainLogic.answered(true, null))
        // Alle ekstra hovedkoder er over det Fyt allerede ber om (0–20), og ingen dobbelt
        assertTrue(FytMainLogic.SUBSCRIBE.all { it > 20 })
        assertEquals(FytMainLogic.SUBSCRIBE.size, FytMainLogic.SUBSCRIBE.toSet().size)
    }
}
