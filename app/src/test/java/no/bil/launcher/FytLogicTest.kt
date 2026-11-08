package no.bil.launcher

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** FYT-stereoen (v5.6): tolking av meldingene fra com.syu.ms, kjente pakkenavn, nedtrekksmenyen på Android 10, opptaket. */
class FytLogicTest {
    @Test fun frekvens_i_begge_enheter() {
        assertEquals("101,70", FytLogic.freqText(0, 10170))        // Unisoc: 10 kHz-enheter
        assertEquals("101,70", FytLogic.freqText(-3, 101700))      // PX: kHz
        assertEquals("87,50", FytLogic.freqText(65536, 8750))
        assertEquals("1080", FytLogic.freqText(1, 1080))          // AM i kHz
        assertEquals("–", FytLogic.freqText(0, -1))
        assertEquals("–", FytLogic.freqText(5, 0))
        assertEquals(101_700, FytLogic.freqKhz(10170, fm = true))
        assertEquals(10170, FytLogic.rawForFm(101.7f, sample = 10170))
        assertEquals(101_700, FytLogic.rawForFm(101.7f, sample = 101_700))
    }

    @Test fun fm_eller_am() {
        assertEquals(true, FytLogic.isFm(1, 10170))     // frekvensen vinner over båndet
        assertEquals(false, FytLogic.isFm(0, 1080))
        assertEquals(false, FytLogic.isFm(-2, 0))
        assertEquals(true, FytLogic.isFm(-3, 0))
        assertEquals(true, FytLogic.isFm(65537, 0))
        assertNull(FytLogic.isFm(7, 0))
    }

    @Test fun telefonens_tilstand_og_navn() {
        assertEquals(4, FytLogic.phoneStateOf(intArrayOf(4)))
        assertNull(FytLogic.phoneStateOf(intArrayOf(9)))
        assertNull(FytLogic.phoneStateOf(null))
        assertEquals(true, FytLogic.phoneConnected(FytLogic.PHONE_CONNECTED))
        assertEquals(true, FytLogic.phoneConnected(FytLogic.PHONE_TALK))
        assertEquals(false, FytLogic.phoneConnected(FytLogic.PHONE_DISCONNECTED))
        assertNull(FytLogic.phoneConnected(-1))
        assertTrue(FytLogic.isCallState(FytLogic.PHONE_RING))
        assertFalse(FytLogic.isCallState(FytLogic.PHONE_CONNECTED))
        assertEquals("iPhone", FytLogic.phoneName(arrayOf("iPhone")))
        assertEquals("iPhone", FytLogic.phoneName(arrayOf("0", "iPhone")))   // to-telefon-varianten
        assertEquals("12345678", FytLogic.phoneName(arrayOf("12345678", "x")))
        assertNull(FytLogic.phoneName(arrayOf("  ")))
        assertNull(FytLogic.phoneName(null))
    }

    @Test fun meldingene_beskrives_kort() {
        val s = FytLogic.describe(1, 1, intArrayOf(10170), null, null)
        assertEquals("radio(1) u=1 ints=[10170]", s)
        assertEquals("(tomt)", FytLogic.payload(null, null, null))
        assertTrue(FytLogic.payload(IntArray(20), null, arrayOf("a".repeat(100))).let { "…" in it && it.length < 200 })
        assertEquals("(bilradio)", FytLogic.appName(11))
        assertEquals("modul 40", FytLogic.moduleName(40))
    }

    @Test fun kjente_fyt_pakker() {
        assertEquals("com.syu.radio", Tiles.byPrefix("Rad").candidates.first())
        assertEquals("com.syu.bt", Tiles.byPrefix("Tel").candidates.first())
        assertEquals("com.syu.bt", Tiles.byPrefix("Blu").candidates.first())
        assertEquals("com.syu.carlink", Tiles.byPrefix("Car").candidates.first())
        // Skall-apper skjules i app-skuffen …
        for ((p, l) in listOf("com.android.launcher9" to "Launcher", "com.android.launcher36" to "Launcher", "com.syu.guide" to "Operation guide",
            "com.syu.market" to "App Store", "com.fyt.hidebutton" to "HideButton", "com.fyt.screenbutton" to "Touch-Assistant",
            "com.txznet.aipal" to "Toppal+", "com.syu.onekeynavi" to "Navi", "com.syu.settings" to "Settings", "com.syu.eq" to "Equalizer"))
            assertTrue("$p skal skjules", isStockShellApp(p, l, isSystem = true, isHome = false))
        // … men CarPlay, kart og Spotify vises alltid
        for ((p, l) in listOf("com.syu.carlink" to "Car Link 2.0", "com.google.android.apps.maps" to "Maps", "com.waze" to "Waze",
            "com.spotify.music" to "Spotify", "com.nng.igo.primong.igoworld" to "iGO Navigation", "com.syu.steer" to "Control"))
            assertFalse("$p skal vises", isStockShellApp(p, l, isSystem = true, isHome = false))
    }

    @Test fun navi_snarveien_er_stereoens_egen() {
        val nav = setOf(NaviKey.MAPS, NaviKey.WAZE)
        assertEquals(NaviKey.Kind.STOCK_NAV, NaviKey.classify("com.syu.onekeynavi", nav, system = true, label = "Navi", learned = false))
        assertEquals(NaviKey.Kind.REAL_NAV, NaviKey.classify(NaviKey.MAPS, nav, system = true, label = "Maps", learned = false))
    }

    @Test fun nedtrekksmenyen_paa_android_10() {
        assertEquals(ShadeLogic.CloseWay.COLLAPSE_OR_BACK, ShadeLogic.closeWay(29))
        assertEquals(ShadeLogic.CloseWay.DISMISS_SHADE, ShadeLogic.closeWay(31))
        // «StatusBar»-vinduet som har vokst til hele skjermen = menyen, også uten pakkenavn
        assertTrue(ShadeLogic.isShadeWindow(WinSnap(ShadeLogic.TYPE_SYSTEM, "StatusBar", null, 0, 0, 1024, 600), 1024, 600))
        assertFalse(ShadeLogic.isShadeWindow(WinSnap(ShadeLogic.TYPE_SYSTEM, "StatusBar", null, 0, 0, 1024, 24), 1024, 600))
        assertFalse(ShadeLogic.isShadeWindow(WinSnap(ShadeLogic.TYPE_APPLICATION, "StatusBar", null, 0, 0, 1024, 600), 1024, 600))
        // Et varsel som nettopp kom (heads-up) gjør også statuslinja stor
        assertTrue(ShadeLogic.maybeHeadsUp(hint = false, msSinceNotification = 600))
        assertFalse(ShadeLogic.maybeHeadsUp(hint = true, msSinceNotification = 600))
        assertFalse(ShadeLogic.maybeHeadsUp(hint = false, msSinceNotification = 10_000))
    }

    @Test fun opptaket_stopper_ikke_av_et_tilfeldig_trykk() {
        assertEquals(RecordStop.Tap.IGNORE, RecordStop.tap(1100, null))          // kartleggingen 8. nov: stoppet etter 1,1 s
        assertEquals(RecordStop.Tap.ASK, RecordStop.tap(5000, null))
        assertEquals(RecordStop.Tap.STOP, RecordStop.tap(5000, 1500))
        assertEquals(RecordStop.Tap.ASK, RecordStop.tap(20_000, 9000))           // for lenge siden første trykk
        assertEquals(RecordStop.Tap.IGNORE, RecordStop.tap(1000, 500))
    }

    @Test fun adb_linja_for_statuslinja() {
        assertEquals("adb shell settings put system show_navigationbar 0", FytBars.ADB_HIDE)
        assertTrue(adbSetupCommands("no.bil.launcher").any { it.cmd == FytBars.ADB_HIDE && "Valgfritt" in it.what })
    }
}

/** Uten com.syu.ms (testene, andre stereoer) skal FYT-delen ikke gjøre noe – og ikke krasje. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class FytSmokeTest {
    @Test fun utenFytSkjerIngenting() {
        val c = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        Fyt.start(c)
        assertFalse(Fyt.connected)
        assertFalse(Fyt.radioReady || Fyt.btReady)
        assertFalse(Fyt.answer() || Fyt.dial("12345678") || Fyt.radioOn())
        assertTrue("finnes ikke" in Fyt.status)
        assertTrue(Fyt.report(c).contains("FYT-stereo (com.syu.ms finnes): false"))
        assertNull(FytBars.current(c))
        assertEquals("innstillingen finnes ikke", FytBars.writeTest(c))
        assertFalse(HwRadio.fyt)
    }
}
