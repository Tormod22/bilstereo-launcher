package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Samspillet mellom nattens FYT-deler (radio, telefon, rygging, rattknapper, selvtest): én handling per trykk, ingen
 * samtaleskjerm over ryggebildet, radioen slippes når brukeren selv velger musikk.
 */
class IntegrationLogicTest {
    private val next133 = SteerLogic.Event(0, SteerLogic.U_KEY, 87, SteerLogic.Cmd.NEXT)
    private val answer133 = SteerLogic.Event(0, SteerLogic.U_KEY, 5, SteerLogic.Cmd.ANSWER)
    private val hangup133 = SteerLogic.Event(0, SteerLogic.U_KEY, 6, SteerLogic.Cmd.HANGUP)

    @Test fun samtaleskjermen_venter_til_ryggingen_er_ferdig() {
        assertEquals(AutoOpen.OPEN, autoOpenCallScreen(stayOut = false, liveCall = true))
        assertEquals(AutoOpen.WAIT, autoOpenCallScreen(stayOut = true, liveCall = true))
        // Samtalen ble ferdig mens bilen rygget: ingenting åpnes etterpå
        assertEquals(AutoOpen.DROP, autoOpenCallScreen(stayOut = true, liveCall = false))
        assertEquals(AutoOpen.DROP, autoOpenCallScreen(stayOut = false, liveCall = false))
    }

    @Test fun ett_trykk_en_handling_uansett_rekkefolge() {
        // u=133 kom først (T'OS venter), så u=37 med kilde 10: stereoen gir appen trykket – T'OS gjør ingenting
        val p = SteerLogic.OnePress()
        p.handledElsewhere(SteerLogic.Cmd.NEXT, 1_100)
        assertFalse(p.mayAct(SteerLogic.Cmd.NEXT, 1_000))
        // Android-medietasten (spill/pause) og u=37 «spill» er samme trykk
        val q = SteerLogic.OnePress()
        q.handledElsewhere(SteerLogic.Cmd.PLAY_PAUSE, 5_000)
        assertFalse(q.mayAct(SteerLogic.Cmd.PLAY, 5_050))
        assertFalse(q.mayAct(SteerLogic.Cmd.PAUSE, 4_950))
        // Et annet trykk (forrige) eller et senere trykk på samme knapp er ikke stoppet
        assertTrue(q.mayAct(SteerLogic.Cmd.PREV, 5_000))
        assertTrue(q.mayAct(SteerLogic.Cmd.PLAY_PAUSE, 7_000))
        assertTrue(SteerLogic.OnePress().mayAct(SteerLogic.Cmd.NEXT, 0))
        assertTrue(SteerLogic.HOLD_MS in 100..500)
    }

    @Test fun fyt_samtale_besvares_av_stereoen_selv() {
        // FYT-samtale: stereoen gjør svar/legg på selv (lært rattknapp) – T'OS skal ikke sende det en gang til
        assertEquals(SteerLogic.Route.STEREO, SteerLogic.route(answer133, 2, true, null, fytCall = true))
        assertEquals(SteerLogic.Route.STEREO, SteerLogic.route(hangup133, 2, true, null, fytCall = true))
        // Samtale Android vet om (Telecom/varsel): T'OS svarer
        assertEquals(SteerLogic.Route.PHONE, SteerLogic.route(answer133, 10, true, null, fytCall = false))
        assertEquals(SteerLogic.Route.NONE, SteerLogic.route(answer133, 10, false, null, fytCall = false))
        // Neste under samtale: ingenting
        assertEquals(SteerLogic.Route.NONE, SteerLogic.route(next133, 10, true, "com.spotify.music", fytCall = true))
    }

    @Test fun radioen_slippes_bare_naar_brukeren_velger_musikk() {
        // Brukeren valgte Spotify (MUSIC/kildevalget) mens FYT-radioen spilte: Android-appen blir kilden
        assertTrue(SteerLogic.shouldClaim(true, 1, false, "com.spotify.music", explicit = true))
        assertTrue(SteerLogic.shouldClaim(true, 11, false, "com.spotify.music", explicit = true))
        // En app som bare begynte å spille av seg selv tar ikke radioen
        assertFalse(SteerLogic.shouldClaim(true, 1, false, "com.spotify.music"))
        // Aldri under samtale, aldri for FYT sine egne apper, aldri når valget er av
        assertFalse(SteerLogic.shouldClaim(true, 1, true, "com.spotify.music", explicit = true))
        assertFalse(SteerLogic.shouldClaim(true, 1, false, "com.syu.music", explicit = true))
        assertFalse(SteerLogic.shouldClaim(false, 1, false, "com.spotify.music", explicit = true))
        // Allerede Android-kilde: ingenting å gjøre
        assertFalse(SteerLogic.shouldClaim(true, 10, false, "com.spotify.music", explicit = true))
    }

    @Test fun selvtesten_ror_ikke_volumet_naar_bilen_rygger() {
        assertNotNull(SelfTestLogic.volumeSkip(14, muted = false, inCall = false, reversing = true))
        assertNull(SelfTestLogic.volumeSkip(14, muted = false, inCall = false, reversing = false))
        assertNotNull(SelfTestLogic.volumeSkip(14, muted = false, inCall = true))
        assertTrue(SelfTestLogic.CALL_WAIT_MS >= 10 * 60_000L)
    }

    @Test fun natt_og_dag_uten_flimring() {
        // Samme ønske to ganger (minuttløkka + lysene) sender ikke noe nytt
        val (first, dimmed) = FytMainLogic.nightStep(want = true, dimmed = false, dayLevel = 80, nightLevel = 25)
        assertEquals(25, first)
        assertNull(FytMainLogic.nightStep(want = true, dimmed = dimmed, dayLevel = 80, nightLevel = 25).first)
        assertEquals(80, FytMainLogic.nightStep(want = false, dimmed = true, dayLevel = 80, nightLevel = 25).first)
        // Lysene avgjør bare når valget er på og de er meldt (etter ACC av er de ukjent → sola)
        assertTrue(FytMainLogic.night("AUTO", byLights = true, lightsOn = null, sunNight = true))
        assertFalse(FytMainLogic.night("AUTO", byLights = false, lightsOn = true, sunNight = false))
    }
}
