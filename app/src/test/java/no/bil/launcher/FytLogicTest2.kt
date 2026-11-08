package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FYT-stereoen (v5.7): det kartleggingen 8. nov bekreftet – volumet, panelknappene, radioen, nedtrekksmenyen og fanevisningen. */
class FytLogicTest2 {
    private val app = ShadeLogic.TYPE_APPLICATION

    @Test fun volumlinja_dekker_fyt_vinduet() {
        // Opptaket: «Popup Window», pakke com.syu.ms, 298,80 428×62 på 1024×600
        val fyt = WinSnap(app, "Popup Window", Fyt.PKG, 298, 80, 298 + 428, 80 + 62)
        val p = FytVolumeLogic.popupBox(fyt, 1024, 600)
        assertEquals(FytVolumeLogic.Box(298, 80, 428, 62), p)
        val o = FytVolumeLogic.overlayBox(p, 1024, 600, margin = 6, minW = 360, minH = 64)
        assertTrue("T'OS-kortet skal dekke FYT-vinduet helt", o.contains(p))
        assertEquals(440, o.w); assertEquals(74, o.h)
        assertEquals(292, o.x); assertEquals(74, o.y)
        // Uten vinduslista: standardplassen (samme som i kartleggingen)
        assertEquals(FytVolumeLogic.DEFAULT_POPUP, FytVolumeLogic.popupBox(null, 1024, 600))
        // Annen skjerm: skalert, og fortsatt dekket
        val big = FytVolumeLogic.popupBox(null, 1280, 720)
        assertTrue(FytVolumeLogic.overlayBox(big, 1280, 720, 6, 360, 64).contains(big))
        // Holdes innenfor skjermen
        val edge = FytVolumeLogic.overlayBox(FytVolumeLogic.Box(0, 0, 100, 40), 1024, 600, 6, 360, 64)
        assertEquals(0, edge.x); assertEquals(0, edge.y)
    }

    @Test fun hoeyeste_volum_og_tallet() {
        assertEquals(40, FytVolumeLogic.maxVolume(seen = 15, override = 0))
        assertEquals(45, FytVolumeLogic.maxVolume(seen = 45, override = 0))     // høyeste sett vinner
        assertEquals(30, FytVolumeLogic.maxVolume(seen = 15, override = 30))    // brukerens valg
        assertEquals(33, FytVolumeLogic.maxVolume(seen = 33, override = 30))    // men aldri under det vi har sett
        assertEquals(13, FytVolumeLogic.levelFromText(listOf("13")))
        assertEquals(4, FytVolumeLogic.levelFromText(listOf("", " 4 ")))
        assertNull(FytVolumeLogic.levelFromText(listOf("Volume")))
        assertEquals(0.325f, volumeFraction(13, 40, muted = false), 0.001f)
        assertEquals(0f, volumeFraction(13, 40, muted = true), 0.001f)
    }

    @Test fun fyt_vinduet_kjennes_igjen() {
        val fyt = WinSnap(app, "Popup Window", Fyt.PKG, 298, 80, 726, 142)
        assertTrue(FytVolumeLogic.isFytPopup(fyt, 1024, 600, fytRecent = false))
        // Uten pakkenavn: bare med tittelen og like etter en FYT-endring
        val anon = fyt.copy(pkg = null)
        assertTrue(FytVolumeLogic.isFytPopup(anon, 1024, 600, fytRecent = true))
        assertFalse(FytVolumeLogic.isFytPopup(anon, 1024, 600, fytRecent = false))
        // Andre apper, hele skjermen, systemvinduer teller ikke
        assertFalse(FytVolumeLogic.isFytPopup(fyt.copy(pkg = "com.spotify.music"), 1024, 600, true))
        assertFalse(FytVolumeLogic.isFytPopup(WinSnap(app, "T'OS", Fyt.PKG, 0, 0, 1024, 600), 1024, 600, true))
        assertFalse(FytVolumeLogic.isFytPopup(fyt.copy(type = ShadeLogic.TYPE_SYSTEM), 1024, 600, true))
        // Hendelsene fra vinduet: android.view.View «» og android.widget.FrameLayout «13»
        assertTrue(FytVolumeLogic.isPopupEvent(Fyt.PKG, "android.view.View"))
        assertTrue(FytVolumeLogic.isPopupEvent(Fyt.PKG, "android.widget.FrameLayout"))
        assertFalse(FytVolumeLogic.isPopupEvent("com.syu.radio", "android.widget.FrameLayout"))
        assertFalse(FytVolumeLogic.isPopupEvent(Fyt.PKG, "com.syu.ms.SomeActivity"))
    }

    @Test fun linja_staar_saa_lenge_fyt_vinduet_finnes() {
        assertFalse(FytVolumeLogic.shouldHide(now = 1500, lastChange = 0, popupPresent = false))
        assertTrue(FytVolumeLogic.shouldHide(now = 2000, lastChange = 0, popupPresent = false))
        assertFalse(FytVolumeLogic.shouldHide(now = 4000, lastChange = 0, popupPresent = true))   // FYT-vinduet står 3–5 s
        assertTrue(FytVolumeLogic.shouldHide(now = 10_000, lastChange = 0, popupPresent = true))  // øvre grense
        assertFalse(FytVolumeLogic.shouldHide(now = 0, lastChange = 500, popupPresent = false))
    }

    @Test fun panelknappene_paa_fyt() {
        val installed = setOf(FytKeys.RADIO, FytKeys.MUSIC, FytKeys.BT, NaviKey.IGO)
        val d = FytKeys.defaultsFor({ it in installed }, navi = NaviKey.IGO, ownPkg = "no.bil.launcher")
        assertEquals(mapOf(PanelKey.RADIO to FytKeys.RADIO, PanelKey.MUSIC to FytKeys.MUSIC, PanelKey.BT to FytKeys.BT, PanelKey.NAVI to NaviKey.IGO), d)
        // Ikke lært: standarden gjelder
        assertEquals(FytKeys.RADIO, FytKeys.defaultPkg(PanelKey.RADIO, d, emptyMap<String, Any>()))
        assertEquals(NaviKey.IGO, FytKeys.defaultPkg(PanelKey.NAVI, d, emptyMap<String, Any>()))
        // Lært av brukeren (app eller tastekode): det lærte gjelder
        assertNull(FytKeys.defaultPkg(PanelKey.RADIO, d, mapOf("pkg_RADIO" to "com.other.radio")))
        assertNull(FytKeys.defaultPkg(PanelKey.MUSIC, d, mapOf("code_MUSIC" to 209)))
        // En annen knapp er lært med samme app: ikke to knapper på én app
        assertNull(FytKeys.defaultPkg(PanelKey.BT, d, mapOf("pkg_MENU" to FytKeys.BT)))
        // Ingen standard for MENU/BACK, og ingenting på andre stereoer
        assertNull(FytKeys.defaultPkg(PanelKey.MENU, d, emptyMap<String, Any>()))
        assertNull(FytKeys.defaultPkg(PanelKey.RADIO, emptyMap(), emptyMap<String, Any>()))
        // FYT sin navigasjonsapp kan ikke være T'OS, og må finnes
        assertFalse(PanelKey.NAVI in FytKeys.defaultsFor({ true }, navi = "no.bil.launcher", ownPkg = "no.bil.launcher"))
        assertFalse(PanelKey.NAVI in FytKeys.defaultsFor({ it != NaviKey.IGO }, navi = NaviKey.IGO, ownPkg = "no.bil.launcher"))
    }

    @Test fun igo_er_en_ekte_kartapp() {
        // NAVI-knappen åpner iGO (com.navngo.igo.javaclient.MainActivity, åpner «geo:») – den skal regnes som kart
        val nav = setOf(NaviKey.MAPS, NaviKey.WAZE, NaviKey.IGO, NaviKey.YANDEX)
        assertEquals(NaviKey.Kind.REAL_NAV, NaviKey.classify(NaviKey.IGO, nav, system = false, label = "iGO Navigation", learned = true))
        assertEquals(NaviKey.Kind.REAL_NAV, NaviKey.classify(NaviKey.IGO, nav, system = true, label = "iGO Navigation", learned = false))
        assertEquals(NaviKey.Kind.REAL_NAV, NaviKey.classify(NaviKey.YANDEX, nav, system = false, label = "Yandex", learned = false))
        assertEquals(NaviKey.Kind.STOCK_NAV, NaviKey.classify("com.syu.onekeynavi", nav, system = true, label = "Navi", learned = false))
        // Popupen tilbyr brukerens kartapp når en annen kartapp kom fram
        val all: (String) -> Boolean = { it in nav }
        assertEquals(NaviKey.MAPS, NaviKey.offerFor(NaviKey.IGO, NaviKey.MAPS, all))
        assertEquals(NaviKey.WAZE, NaviKey.offerFor(NaviKey.IGO, NaviKey.WAZE, all))
        // Brukerens kartapp kom selv: den andre av Waze/Google Maps (som før)
        assertEquals(NaviKey.WAZE, NaviKey.offerFor(NaviKey.MAPS, NaviKey.MAPS, all))
        assertEquals(NaviKey.MAPS, NaviKey.offerFor(NaviKey.WAZE, NaviKey.MAPS, all))
        // iGO er brukerens valg og kom fram: ingen popup
        assertNull(NaviKey.offerFor(NaviKey.IGO, NaviKey.IGO, all))
        // Brukerens valg finnes ikke: ingen popup for iGO, den andre for Maps
        assertNull(NaviKey.offerFor(NaviKey.IGO, "com.borte", all))
        assertNull(NaviKey.offerFor(NaviKey.MAPS, NaviKey.MAPS) { it == NaviKey.MAPS })
        assertEquals("iGO", NaviKey.knownLabel(NaviKey.IGO))
    }

    @Test fun radiofrekvensen_avgjoeres_av_tallet() {
        // Kartleggingen: frekvens 522 med bånd 0 (som ellers betyr FM) – det er AM
        assertEquals(false, FytLogic.isFm(0, 522))
        assertEquals("522 kHz", FytLogic.freqLabel(0, 522))
        assertEquals("87,50 MHz", FytLogic.freqLabel(1, 8750))      // FM i 10 kHz-enheter, uansett bånd
        assertEquals("101,70 MHz", FytLogic.freqLabel(0, 10170))
        assertEquals("107,90 MHz", FytLogic.freqLabel(0, 107_900))  // kHz-varianten
        assertEquals("76,00 MHz", FytLogic.freqLabel(-2, 7600))     // ≥ 6000 er FM
        assertEquals("1602 kHz", FytLogic.freqLabel(-3, 1602))
        assertEquals("–", FytLogic.freqLabel(0, 0))
        assertEquals("–", FytLogic.freqLabel(7, 3000))              // ukjent område og ukjent bånd
    }

    @Test fun forvalgte_kanaler() {
        // Kartleggingen: radio u=14 ints=[0..11] og [65536..65553], tomme navn – vises ikke
        var l = emptyList<FytLogic.Preset>()
        for (i in 0..11) l = FytLogic.withPreset(l, intArrayOf(i), arrayOf(""))
        for (i in 65536..65553) l = FytLogic.withPreset(l, intArrayOf(i), arrayOf(""))
        assertTrue(l.isEmpty())
        assertNull(FytLogic.preset(intArrayOf(3), arrayOf("  ")))
        // Med frekvens eller navn: vises (sortert på plass, samme plass byttes ut, tom igjen = fjernet)
        l = FytLogic.withPreset(l, intArrayOf(65537, 9370), arrayOf(""))
        l = FytLogic.withPreset(l, intArrayOf(2), arrayOf("NRK P1"))
        assertEquals(listOf(2, 65537), l.map { it.slot })
        assertEquals("NRK P1", l[0].label)
        assertEquals("93,70 MHz", l[1].label)
        assertEquals(1, l[1].number)
        l = FytLogic.withPreset(l, intArrayOf(2), arrayOf("P4"))
        assertEquals("P4", l[0].label)
        l = FytLogic.withPreset(l, intArrayOf(2), arrayOf(""))
        assertEquals(listOf(65537), l.map { it.slot })
        assertEquals(l, FytLogic.withPreset(l, null, null))
    }

    @Test fun lydkilden_med_navn_i_kartleggingen() {
        assertEquals("Radio", FytLogic.sourceName(1))
        assertEquals("Musikkspiller", FytLogic.sourceName(8))
        assertEquals("Bluetooth (telefon)", FytLogic.sourceName(2))
        assertEquals("99", FytLogic.sourceName(99))
        assertTrue(FytLogic.describe(0, 0, intArrayOf(8), null, null).endsWith("→ kilde: Musikkspiller"))
        assertFalse("→" in FytLogic.describe(4, 2, intArrayOf(12), null, null))
        assertFalse("→" in FytLogic.describe(0, 1, intArrayOf(1), null, null))
    }

    @Test fun nedtrekksmenyen_slik_stereoen_sender_den() {
        val sysui = "com.android.systemui"
        // Nøyaktig det opptaket viste (46,3 s og 46,4 s)
        assertTrue(ShadeLogic.isShadeEvent(sysui, "android.widget.FrameLayout", listOf("Notification shade.")))
        assertTrue(ShadeLogic.isShadeEvent(sysui, "android.widget.FrameLayout",
            listOf("Notification shade.", "21:57", "iPhone", "Eye Comfort", "Data", "Airplane mode", "Off…")))
        // En hurtigknapp med et «farlig» ord stopper det ikke, men låseskjermen gjør
        assertTrue(ShadeLogic.isShadeEvent(sysui, "android.widget.FrameLayout", listOf("Notification shade.", "Power saving")))
        assertFalse(ShadeLogic.isShadeEvent(sysui, null, listOf("Lock screen. Notification shade")))
        // SystemUI-vinduet vokser fra 1024×60 til 1024×600 (uten tittel, pakke com.android.systemui)
        val bar = WinSnap(ShadeLogic.TYPE_SYSTEM, null, sysui, 0, 0, 1024, 60)
        val shade = bar.copy(bottom = 600, active = true, focused = true)
        assertFalse(ShadeLogic.isShadeWindow(bar, 1024, 600))
        assertTrue(ShadeLogic.isShadeWindow(shade, 1024, 600))
        assertTrue(ShadeLogic.decide(eventMatch = true, windows = listOf(shade, WinSnap(app, "T'OS", "no.bil.launcher", 0, 0, 1024, 600)), 1024, 600))
        assertFalse(ShadeLogic.decide(eventMatch = true, windows = listOf(bar, WinSnap(app, "T'OS", "no.bil.launcher", 0, 0, 1024, 600)), 1024, 600))
        // FYT sitt volumvindu (appvindu fra com.syu.ms) er aldri nedtrekksmenyen
        assertFalse(ShadeLogic.isShadeWindow(WinSnap(app, "Popup Window", Fyt.PKG, 298, 80, 726, 142), 1024, 600))
        // Stereoen er Android 10: lukkes med collapsePanels / tilbake
        assertEquals(ShadeLogic.CloseWay.COLLAPSE_OR_BACK, ShadeLogic.closeWay(29))
    }

    @Test fun fanevisningen_slik_stereoen_sender_den() {
        // Opptaket 83,8 s: com.android.launcher3 / com.android.quickstep.RecentsActivity «Quickstep»
        val own = "no.bil.launcher"
        assertTrue(RecentsLogic.isStockRecents("com.android.launcher3", "com.android.quickstep.RecentsActivity", listOf("Quickstep"), true, own))
        assertTrue(RecentsLogic.isStockRecents("com.android.launcher3", "com.android.quickstep.RecentsActivity", listOf("Quickstep"), false, own))
        // Andre FYT-vinduer er ikke fanevisningen
        assertFalse(RecentsLogic.isStockRecents("com.syu.radio", "com.syu.radio.act.ActRadio", listOf("Radio"), false, own))
        assertFalse(RecentsLogic.isStockRecents(Fyt.PKG, "android.widget.FrameLayout", listOf("13"), false, own))
    }
}
