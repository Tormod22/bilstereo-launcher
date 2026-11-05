package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Siste apper» i T'OS-stil: gjenkjenne stereoens fanevisning, T'OS sin egen liste, og hva «Lukk alle» beholder. */
class RecentsLogicTest {
    private val own = "no.bil.launcher"

    @Test fun fanevisningen_kjennes_igjen_paa_klassenavn() {
        assertTrue(RecentsLogic.isStockRecents("com.android.launcher3", "com.android.quickstep.RecentsActivity", emptyList(), true, own))
        assertTrue(RecentsLogic.isStockRecents("com.android.systemui", "com.android.systemui.recents.RecentsActivity", emptyList(), false, own))
        // Stereoens egen startskjerm-app med en egen fanevisning
        assertTrue(RecentsLogic.isStockRecents("com.ts.launcher", "com.ts.launcher.OverviewActivity", emptyList(), true, own))
        assertTrue(RecentsLogic.isStockRecents("com.android.quickstep", "com.android.quickstep.fallback.RecentsView", emptyList(), false, own))
    }

    @Test fun fanevisningen_kjennes_igjen_paa_tekst() {
        // Launcher3: oversikten er en tilstand i selve startskjermen (samme klasse som hjemskjermen)
        val home = "com.android.launcher3.uioverrides.QuickstepLauncher"
        assertTrue(RecentsLogic.isStockRecents("com.android.launcher3", home, listOf("No recent items"), true, own))
        assertTrue(RecentsLogic.isStockRecents("com.android.launcher3", home, listOf("Ingen nylige elementer."), true, own))
        assertTrue(RecentsLogic.isStockRecents("com.android.launcher3", home, listOf("Overview"), true, own))
        assertTrue(RecentsLogic.isStockRecents("com.android.systemui", null, listOf("Oversikt"), false, own))
        assertTrue(RecentsLogic.isStockRecents("com.android.launcher3", home, listOf("Recent apps"), true, own))
        // Vanlig hjemskjerm: ikke fanevisningen
        assertFalse(RecentsLogic.isStockRecents("com.android.launcher3", home, listOf("Home"), true, own))
        assertFalse(RecentsLogic.isStockRecents("com.android.launcher3", home, emptyList(), true, own))
    }

    @Test fun vanlige_apper_er_ikke_fanevisningen() {
        // En musikkapp kan ha en skjerm som heter «Recents», og Maps har en «Oversikt»
        assertFalse(RecentsLogic.isStockRecents("com.spotify.music", "com.spotify.music.RecentsActivity", emptyList(), false, own))
        assertFalse(RecentsLogic.isStockRecents("com.google.android.apps.maps", "com.google.MapsActivity", listOf("Oversikt"), false, own))
        // T'OS sin egen «Siste apper»
        assertFalse(RecentsLogic.isStockRecents(own, "$own.MainActivity", listOf("Siste apper"), false, own))
        // Andre SystemUI-vinduer (varsellinja, volum)
        assertFalse(RecentsLogic.isStockRecents("com.android.systemui", "com.android.systemui.statusbar.phone.StatusBar", listOf("Notification shade"), false, own))
        assertFalse(RecentsLogic.isStockRecents("com.android.systemui", "com.android.systemui.recents.RecentsDialog", emptyList(), false, own))
    }

    @Test fun fanelista_er_fortsatt_bare_innom_naar_funksjonen_er_av() {
        // Uendret fra v4.7: uten «Siste apper i T'OS-stil» regnes fanelista som et vindu som bare er innom
        assertTrue(isPassingWindow("com.android.launcher3", "com.android.quickstep.RecentsActivity"))
    }

    @Test fun egen_liste_nyeste_foerst_uten_dobbelt() {
        var l = emptyList<RecentUse>()
        l = RecentsLogic.add(l, "a", 1)
        l = RecentsLogic.add(l, "b", 2)
        l = RecentsLogic.add(l, "a", 3)
        assertEquals(listOf("a", "b"), l.map { it.pkg })
        assertEquals(3L, l.first().at)
        // Bare de nyeste huskes
        var m = emptyList<RecentUse>()
        for (i in 1..10) m = RecentsLogic.add(m, "p$i", i.toLong(), max = 4)
        assertEquals(listOf("p10", "p9", "p8", "p7"), m.map { it.pkg })
        // Lagres og leses tilbake (ødelagte linjer hoppes over)
        assertEquals(l, RecentsLogic.parse(RecentsLogic.format(l)))
        assertEquals(listOf(RecentUse("x", 5)), RecentsLogic.parse("x|5\nødelagt\n|7\ny|ikke-tall"))
        assertTrue(RecentsLogic.parse("").isEmpty())
    }

    @Test fun kildene_slaas_sammen() {
        val now = 100_000_000L
        val usage = listOf(RecentUse("spotify", now - 60_000), RecentUse("maps", now - 120_000), RecentUse("gammel", now - RecentsLogic.WINDOW_MS - 1))
        val mine = listOf(RecentUse("maps", now - 10_000), RecentUse("waze", now - 300_000), RecentUse("launcher", now - 5_000))
        val r = RecentsLogic.merge(listOf(usage, mine), dismissed = emptyMap(), now = now, exclude = { it == "launcher" })
        // Nyeste tid per app, nyeste først; over 12 timer gammel og utelatte apper er borte
        assertEquals(listOf("maps", "spotify", "waze"), r.map { it.pkg })
        assertEquals(now - 10_000, r.first().at)
        // Lukket i T'OS etter siste bruk: borte. Brukt igjen etter at den ble lukket: tilbake.
        val r2 = RecentsLogic.merge(listOf(usage, mine), mapOf("spotify" to now - 30_000, "maps" to now - 50_000), now, { it == "launcher" })
        assertEquals(listOf("maps", "waze"), r2.map { it.pkg })
        assertEquals(1, RecentsLogic.merge(listOf(usage, mine), emptyMap(), now, { false }, max = 1).size)
    }

    @Test fun hva_som_ikke_vises() {
        assertTrue(RecentsLogic.excluded(own, own, isHome = false, hidden = false, shell = false))
        assertTrue(RecentsLogic.excluded("com.ts.launcher", own, isHome = true, hidden = false, shell = false))
        assertTrue(RecentsLogic.excluded("com.android.systemui", own, isHome = false, hidden = false, shell = false))
        assertTrue(RecentsLogic.excluded("com.android.settings", own, isHome = false, hidden = false, shell = false))
        assertTrue(RecentsLogic.excluded("com.android.launcher3", own, isHome = false, hidden = false, shell = false))
        assertTrue(RecentsLogic.excluded("com.google.android.calculator", own, isHome = false, hidden = true, shell = false))
        assertTrue(RecentsLogic.excluded("com.ts.radio", own, isHome = false, hidden = false, shell = true))
        assertFalse(RecentsLogic.excluded("com.spotify.music", own, isHome = false, hidden = false, shell = false))
        assertFalse(RecentsLogic.excluded("com.zjinnova.zlink", own, isHome = false, hidden = false, shell = false))
    }

    @Test fun lukk_alle_beholder_tos_carplay_og_musikken_som_spiller() {
        val apps = listOf("com.spotify.music" to "Spotify", "com.waze" to "Waze", "com.zjinnova.zlink" to "Car link 2.0",
            "com.x.y" to "Car link 2.0", "com.google.android.youtube" to "YouTube", own to "T'OS")
        val (close, keep) = RecentsLogic.closeAllPlan(apps, own, playingPkg = "com.spotify.music")
        assertEquals(listOf("com.waze", "com.google.android.youtube"), close)
        assertEquals(listOf("com.spotify.music", "com.zjinnova.zlink", "com.x.y", own), keep)
        // Ingenting spiller: musikkappen lukkes også
        assertTrue("com.spotify.music" in RecentsLogic.closeAllPlan(apps, own, null).first)
    }

    @Test fun meldinger_og_tid() {
        assertEquals("Lukket 3 apper", RecentsLogic.closeAllMessage(3, emptyList()))
        assertEquals("Lukket 1 app. Beholdt Car link 2.0.", RecentsLogic.closeAllMessage(1, listOf("Car link 2.0")))
        assertEquals("Lukket 2 apper. Beholdt Car link 2.0 og Spotify (spiller).",
            RecentsLogic.closeAllMessage(2, listOf("Car link 2.0", "Spotify (spiller)")))
        assertEquals("Ingen apper å lukke. Beholdt A, B og C.", RecentsLogic.closeAllMessage(0, listOf("A", "B", "C")))
        val now = 1_000_000_000L
        assertEquals("akkurat nå", RecentsLogic.ago(now, now - 10_000))
        assertEquals("akkurat nå", RecentsLogic.ago(now, now + 5_000))   // klokka er justert
        assertEquals("5 min siden", RecentsLogic.ago(now, now - 5 * 60_000 - 1_000))
        assertEquals("2 t siden", RecentsLogic.ago(now, now - 2 * 3_600_000 - 60_000))
        assertEquals("over et døgn siden", RecentsLogic.ago(now, now - 90_000_000))
    }

    @Test fun knapp_kan_settes_til_siste_apper() {
        assertEquals("Siste apper", Action.RECENTS.label)
        assertTrue(Screen.RECENTS in Screen.entries)
    }
}
