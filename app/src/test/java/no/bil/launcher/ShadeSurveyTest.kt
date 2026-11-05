package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nedtrekksmenyen i T'OS-stil (gjenkjenne stereoens meny, varsler i panelet) og teksten i «Kartlegg stereoen». */
class ShadeSurveyTest {
    private val sysui = "com.android.systemui"
    private val w = 1024
    private val h = 600

    @Test fun nedtrekksmenyen_kjennes_igjen_paa_hendelsen() {
        assertTrue(ShadeLogic.isShadeEvent(sysui, "android.widget.FrameLayout", listOf("Notification shade.")))
        assertTrue(ShadeLogic.isShadeEvent(sysui, "android.widget.FrameLayout", listOf("Quick settings")))
        assertTrue(ShadeLogic.isShadeEvent(sysui, null, listOf("Varselpanel")))
        assertTrue(ShadeLogic.isShadeEvent(sysui, null, listOf("Hurtiginnstillinger")))
        assertTrue(ShadeLogic.isShadeEvent(sysui, "com.android.systemui.shade.NotificationShadeWindowView", emptyList()))
        assertTrue(ShadeLogic.isShadeEvent(sysui, "com.android.systemui.qs.QSPanel", emptyList()))
        // Stereoprodusentens egen SystemUI
        assertTrue(ShadeLogic.isShadeEvent("com.fyt.systemui", null, listOf("Notification shade")))
    }

    @Test fun andre_vinduer_er_ikke_nedtrekksmenyen() {
        // Volum, fanevisning, strømmeny og låseskjerm fra SystemUI skal aldri lukkes
        assertFalse(ShadeLogic.isShadeEvent(sysui, "com.android.systemui.volume.VolumeDialogImpl", listOf("Volume")))
        assertFalse(ShadeLogic.isShadeEvent(sysui, "com.android.systemui.recents.RecentsActivity", listOf("Recents")))
        assertFalse(ShadeLogic.isShadeEvent(sysui, "com.android.systemui.globalactions.GlobalActionsDialog", emptyList()))
        assertFalse(ShadeLogic.isShadeEvent(sysui, null, listOf("Lock screen. Notification shade")))
        assertFalse(ShadeLogic.isShadeEvent(sysui, "android.widget.FrameLayout", emptyList()))
        // Vanlige apper (også med samme ord) er aldri SystemUI sin meny
        assertFalse(ShadeLogic.isShadeEvent("com.spotify.music", "com.spotify.QuickSettingsActivity", listOf("Quick settings")))
        assertFalse(ShadeLogic.isShadeEvent(null, null, listOf("Notification shade")))
    }

    @Test fun nedtrekksmenyen_kjennes_igjen_paa_vinduet() {
        val full = WinSnap(ShadeLogic.TYPE_SYSTEM, null, sysui, 0, 0, w, h)
        assertTrue(ShadeLogic.isShadeWindow(full, w, h))
        assertTrue(ShadeLogic.isShadeWindow(full.copy(title = "Notification shade", bottom = 250), w, h))
        assertTrue(ShadeLogic.isShadeWindow(full.copy(title = "NotificationShade", pkg = null), w, h))
        // Statuslinja (smal), et varsel som dukker opp øverst, volumvinduet
        assertFalse(ShadeLogic.isShadeWindow(full.copy(bottom = 32, title = "Status bar"), w, h))
        assertFalse(ShadeLogic.isShadeWindow(full.copy(bottom = 120), w, h))
        assertFalse(ShadeLogic.isShadeWindow(full.copy(title = "Volume"), w, h))
        // Ryggekamera e.l. fra en annen app som legger seg over alt, og et vindu uten kjent pakke og tittel
        assertFalse(ShadeLogic.isShadeWindow(full.copy(pkg = "com.car.camera"), w, h))
        assertFalse(ShadeLogic.isShadeWindow(full.copy(pkg = null), w, h))
        // App-vinduer er aldri menyen
        assertFalse(ShadeLogic.isShadeWindow(full.copy(type = ShadeLogic.TYPE_APPLICATION), w, h))
    }

    @Test fun vinduslista_avgjoer_naar_den_finnes() {
        val app = WinSnap(ShadeLogic.TYPE_APPLICATION, "Maps", "com.google.android.apps.maps", 0, 0, w, h)
        val bar = WinSnap(ShadeLogic.TYPE_SYSTEM, "Status bar", sysui, 0, 0, w, 32)
        val shade = WinSnap(ShadeLogic.TYPE_SYSTEM, null, sysui, 0, 0, w, h)
        // Hendelsen sier «meny», men vinduene viser bare et varsel øverst: ikke menyen
        assertFalse(ShadeLogic.decide(true, listOf(app, bar), w, h))
        assertTrue(ShadeLogic.decide(false, listOf(app, shade), w, h))
        // Uten vindusliste: hendelsen avgjør
        assertTrue(ShadeLogic.decide(true, emptyList(), w, h))
        assertFalse(ShadeLogic.decide(false, null, w, h))
        // Tillatelsesvindu åpent
        assertTrue(ShadeLogic.dialogOpen(listOf(app, app.copy(pkg = "com.google.android.permissioncontroller"))))
        assertFalse(ShadeLogic.dialogOpen(listOf(app, bar)))
    }

    @Test fun reagerer_bare_naar_menyen_kommer_fram() {
        val e = ShadeEdge(resetMs = 2000)
        assertTrue(e.fire(true, 1000, windowsKnown = true))
        assertFalse(e.fire(true, 1100, windowsKnown = true))      // fortsatt nede
        assertFalse(e.fire(false, 1200, windowsKnown = true))     // lukket
        assertTrue(e.fire(true, 1300, windowsKnown = true))       // trukket ned igjen
        // Uten vindusliste: regnes som lukket etter 2 s
        val f = ShadeEdge(resetMs = 2000)
        assertTrue(f.fire(true, 0, windowsKnown = false))
        assertFalse(f.fire(true, 500, windowsKnown = false))
        assertTrue(f.fire(true, 3000, windowsKnown = false))
    }

    @Test fun sveip_ned_fra_toppen_av_tos() {
        assertTrue(ShadeLogic.topEdgePull(startY = 5f, dx = 10f, dy = 60f, edgePx = 28f, pullPx = 48f))
        assertFalse(ShadeLogic.topEdgePull(startY = 100f, dx = 0f, dy = 200f, edgePx = 28f, pullPx = 48f))   // ikke fra kanten
        assertFalse(ShadeLogic.topEdgePull(startY = 5f, dx = 0f, dy = 20f, edgePx = 28f, pullPx = 48f))      // for kort
        assertFalse(ShadeLogic.topEdgePull(startY = 5f, dx = 90f, dy = 60f, edgePx = 28f, pullPx = 48f))     // mest sidelengs
    }

    private fun note(key: String, pkg: String = "com.whatsapp", title: String? = "Ola", at: Long = 1000, ongoing: Boolean = false,
                     clearable: Boolean = true, category: String? = null, media: Boolean = false, summary: Boolean = false) =
        NoteMeta(key, pkg, title, "Hei", at, ongoing, clearable, category, media, summary)

    @Test fun varslene_som_vises_i_panelet() {
        val own = "no.bil.launcher"
        val list = listOf(
            note("a", at = 1000),
            note("b", pkg = "com.android.vending", title = "Oppdateringer", at = 3000),
            note("gps", pkg = own, title = "T'OS bruker posisjon", ongoing = true, clearable = false),
            note("spotify", pkg = "com.spotify.music", media = true, ongoing = true, clearable = false),
            note("maps", pkg = "com.google.android.apps.maps", ongoing = true, clearable = false),
            note("ring", pkg = "com.android.dialer", category = "call"),
            note("tom", title = null).copy(text = null),
            note("sum", summary = true),
            note("usb", pkg = "android", title = "USB-feilsøking er på", at = 5000, ongoing = true, clearable = false),
        )
        val shown = NoteFilter.shown(list, own, MediaListener.navApps)
        // Nyeste først, pågående (kan ikke fjernes) sist; T'OS, musikk, kart, samtale, tomme og gruppeoversikt ute
        assertEquals(listOf("b", "a", "usb"), shown.map { it.key })
        assertEquals(listOf("b", "a"), NoteFilter.clearKeys(shown))
        // En gruppeoversikt alene vises
        assertEquals(listOf("sum"), NoteFilter.shown(listOf(note("sum", summary = true)), own, emptySet()).map { it.key })
    }

    @Test fun tid_paa_varslene() {
        val now = 1_800_000_000_000L
        assertEquals("akkurat nå", NoteFilter.timeText(now, now - 5_000))
        assertEquals("12 min siden", NoteFilter.timeText(now, now - 12 * 60_000))
        assertTrue(NoteFilter.timeText(now, now - 3 * 3_600_000).startsWith("kl. "))
        assertEquals("", NoteFilter.timeText(now, 0))
    }

    @Test fun tekst_i_kartleggingen() {
        assertEquals("kort", SurveyText.clip("  kort \n"))
        assertEquals("a b c", SurveyText.clip("a\n\tb   c"))
        val long = SurveyText.clip("x".repeat(200))
        assertEquals(80, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals("", SurveyText.clip(null))
        assertEquals("kartlegging-2026-11-05-1430.txt", SurveyText.fileName(java.util.GregorianCalendar(2026, 10, 5, 14, 30).timeInMillis))
        assertTrue(SurveyText.shareAsFile(150_000))
        assertFalse(SurveyText.shareAsFile(20_000))
        assertTrue(SurveyText.header("Lyd").contains("===== Lyd ====="))
    }

    @Test fun komponentnavn_og_hvilke_pakker_som_vises_i_detalj() {
        assertEquals("com.fyt.radio.MainActivity", SurveyText.fullName("com.fyt.radio", ".MainActivity"))
        assertEquals("com.fyt.radio.MainActivity", SurveyText.fullName("com.fyt.radio", "MainActivity"))
        assertEquals("com.other.Receiver", SurveyText.fullName("com.fyt.radio", "com.other.Receiver"))
        assertEquals(".MainActivity", SurveyText.shortName("com.fyt.radio", "com.fyt.radio.MainActivity"))
        assertEquals("com.other.X", SurveyText.shortName("com.fyt.radio", "com.other.X"))
        assertTrue(SurveyText.detailed("com.syu.radio", system = true))
        assertTrue(SurveyText.detailed("com.android.systemui", system = true))
        assertFalse(SurveyText.detailed("com.google.android.gms", system = true))
        assertFalse(SurveyText.detailed("android", system = true))
        assertTrue(SurveyText.detailed("com.zjinnova.zlink", system = false))
        assertTrue(SurveyText.detailed("com.example.x", system = false, label = "Car link 2.0"))
        assertFalse(SurveyText.detailed("com.spotify.music", system = false, label = "Spotify"))
    }

    @Test fun inndata_vinduer_og_lyd_paa_norsk() {
        assertEquals("knapper/tastatur (0x101)", SurveyText.sources(0x101))
        assertTrue(SurveyText.sources(0x1002 or 0x101).contains("berøringsskjerm"))
        assertEquals("ukjent (0x0)", SurveyText.sources(0))
        assertEquals("system", SurveyText.windowType(3))
        assertEquals("lagt til+størrelse", SurveyText.windowChanges(1 or 8))
        assertEquals("BUS (bilens forsterker)", SurveyText.audioType(21))
        assertEquals("type 99", SurveyText.audioType(99))
        val line = SurveyText.window(WinSnap(3, "Notification shade", sysui, 0, 0, w, h, layer = 7, active = false, focused = true))
        assertEquals("system lag=7 «Notification shade» pakke=com.android.systemui 0,0 1024x600 fokus", line)
        assertEquals("VINDU", SurveyText.eventName(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertEquals(null, SurveyText.eventName(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED))
        assertTrue(SurveyText.stamp(12_340).trim().startsWith("+"))
    }
}
