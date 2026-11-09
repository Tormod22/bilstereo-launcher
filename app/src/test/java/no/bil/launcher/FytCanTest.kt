package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bil-data fra FYT sin canbus-modul (7): id, dører, klima, vinduene som dekkes. */
class FytCanTest {
    @Test fun canbus_id_deles_i_bil_og_protokoll() {
        // Kartleggingen 8. nov: persist.syu.canbusidtype = 196608 = 3 × 65536 + 0
        val id = FytCanLogic.CanId(196608)
        assertEquals(3, id.car); assertEquals(0, id.protocol); assertTrue(id.none)
        // FinalCanbus.CAR_WC1_VW_Sagitar_07_18 = 1966081 = bil 30, protokoll 1
        assertEquals(1, FytCanLogic.CanId(1966081).protocol); assertEquals(30, FytCanLogic.CanId(1966081).car)
        assertTrue(FytCanLogic.idText(196608).contains("ingen canbus-boks"))
        assertEquals("ukjent", FytCanLogic.idText(null))
    }

    @Test fun dorer_0_til_5_og_ikke_null_er_apen() {
        var d = FytCanLogic.Doors()
        assertFalse(d.known)
        d = FytCanLogic.withDoor(d, 1, 1)
        d = FytCanLogic.withDoor(d, 5, 2)
        d = FytCanLogic.withDoor(d, 2, 0)
        assertTrue(d.known); assertTrue(d.anyOpen)
        assertEquals(listOf("venstre fordør", "bakluka"), d.openNames())
        d = FytCanLogic.withDoor(FytCanLogic.withDoor(d, 1, 0), 5, 0)
        assertFalse(d.anyOpen)
        assertEquals(d, FytCanLogic.withDoor(d, 3, null))
        assertTrue(FytCanLogic.isDoorCode(0)); assertFalse(FytCanLogic.isDoorCode(6))
    }

    @Test fun klima_oppsummering_bare_det_bilen_melder() {
        var a = FytCanLogic.Air()
        assertNull(FytCanLogic.airSummary(a))
        a = FytCanLogic.withAir(a, 11, 1)
        a = FytCanLogic.withAir(a, 13, 1)
        a = FytCanLogic.withAir(a, 12, 1)
        a = FytCanLogic.withAir(a, 21, 3)
        assertEquals("A/C · Auto · Resirkulering · Vifte 3", FytCanLogic.airSummary(a))
        assertEquals("Klima av", FytCanLogic.airSummary(FytCanLogic.withAir(a, 10, 0)))
        // Urimelig viftetrinn ignoreres
        assertNull(FytCanLogic.withAir(FytCanLogic.Air(), 21, 99).fan)
    }

    @Test fun temperatur_bare_for_kjent_koding() {
        // WC2 Toyota All (294): tideler °C, −2 LO, −3 HI
        assertEquals("21,5°", FytCanLogic.tempText(215, 294, false))
        assertEquals("LO", FytCanLogic.tempText(-2, 294, false))
        assertEquals("HI", FytCanLogic.tempText(-3, 294, false))
        assertNull(FytCanLogic.tempText(-1, 294, false))
        assertNull(FytCanLogic.tempText(5, 294, false))
        // Ukjent protokoll: ikke gjett
        assertNull(FytCanLogic.tempText(215, 452, false))
        assertNull(FytCanLogic.tempText(null, 294, false))
    }

    @Test fun bare_sma_canbus_vinduer_dekkes() {
        val strip = WinSnap(ShadeLogic.TYPE_APPLICATION, null, FytCanLogic.PKG, 0, 420, 1024, 600)
        assertTrue(FytCanLogic.isCanPopup(strip, 1024, 600))
        val app = WinSnap(ShadeLogic.TYPE_APPLICATION, null, FytCanLogic.PKG, 0, 0, 1024, 600)
        assertFalse(FytCanLogic.isCanPopup(app, 1024, 600))
        val other = WinSnap(ShadeLogic.TYPE_APPLICATION, null, Fyt.PKG, 0, 420, 1024, 600)
        assertFalse(FytCanLogic.isCanPopup(other, 1024, 600))
        assertTrue(FytCanLogic.isPopupEvent(FytCanLogic.PKG, "android.widget.FrameLayout"))
        assertFalse(FytCanLogic.isPopupEvent(FytCanLogic.PKG, "com.syu.carinfo.air.Air_Activity_All"))
        assertFalse(FytCanLogic.isPopupEvent(FytCanLogic.PKG, "android.app.Dialog"))
    }

    @Test fun plassering_og_skjuling() {
        // Uten FYT-vindu: klima nederst over hele bredden, dører midt på
        val a = FytCanLogic.airBox(null, 1024, 600, 96)
        assertEquals(FytVolumeLogic.Box(0, 504, 1024, 96), a)
        val d = FytCanLogic.doorBox(null, 1024, 600, 420, 160)
        assertEquals(1024 / 2, d.x + d.w / 2); assertEquals(600 / 2, d.y + d.h / 2)
        // Med FYT-vindu: dekker det helt
        val p = FytVolumeLogic.Box(100, 450, 800, 150)
        assertTrue(FytCanLogic.airBox(p, 1024, 600, 96).contains(p))
        assertFalse(FytCanLogic.shouldHide(3000, 0, false, FytCanLogic.AIR_MIN_MS))
        assertTrue(FytCanLogic.shouldHide(4000, 0, false, FytCanLogic.AIR_MIN_MS))
        assertFalse(FytCanLogic.shouldHide(8000, 0, true, FytCanLogic.AIR_MIN_MS))
        assertTrue(FytCanLogic.shouldHide(FytCanLogic.MAX_MS, 0, true, FytCanLogic.AIR_MIN_MS))
    }

    @Test fun kodenavn_til_kartleggingen() {
        assertEquals("canbus-id", FytCanLogic.label(1000))
        assertEquals("temp v.", FytCanLogic.label(27))
        assertTrue(FytCanLogic.label(150).contains("per protokoll"))
    }
}
