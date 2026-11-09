package no.bil.launcher

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Sikkerhetsnettet for FYT-funksjonene (FytSafety.kt) og vakthundene i FytMain/FytVolume/FytCan/TosOverlay. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class FytSafetyTest {
    private val fytTrace = """
        java.lang.IllegalStateException: boom
            at no.bil.launcher.FytPhone.onState(FytPhone.kt:360)
            at no.bil.launcher.Fyt.apply(Fyt.kt:280)
            at android.os.Handler.handleCallback(Handler.java:938)
    """.trimIndent()

    @After fun reset() {
        (listOf(FytSafetyLogic.MASTER, "fyt_safe_off", "fyt_safe_reason", "fyt_safe_notices", "fyt_bright_last",
            "fyt_bright_day", "fyt_bright_night", "fyt_tos_muted") + FytSafetyLogic.FEATURES).forEach { Prefs.set(it, null) }
        // Testen endrer Compose-tilstand (Prefs.version) uten en Compose-regel: la Compose få behandlet endringene før
        // neste test, ellers blir senere skjermtester aldri «ferdige» (Compose venter på en melding som ble kastet)
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun fytKrasjKjennesIgjen() {
        assertTrue(FytSafetyLogic.isFytCrash(fytTrace))
        assertTrue(FytSafetyLogic.isFytCrash("x\n\tat no.bil.launcher.SteerLogic.route(Steer.kt:10)"))
        assertTrue(FytSafetyLogic.isFytCrash("x\n\tat no.bil.launcher.FytVolume\$tick\$1.run(FytVolume.kt:180)"))
        assertTrue(FytSafetyLogic.isFytCrash("x\n\tat no.bil.launcher.KeyService.checkFytPopup(KeyService.kt:280)"))
        assertTrue(FytSafetyLogic.isFytCrash("x\n\tat no.bil.launcher.KeyService.checkCanPopup(KeyService.kt:290)"))
        // Andre krasj teller ikke (vanlig sikker modus tar dem)
        assertFalse(FytSafetyLogic.isFytCrash("x\n\tat no.bil.launcher.OfflineMapKt.OfflineMapView(OfflineMap.kt:12)"))
        assertFalse(FytSafetyLogic.isFytCrash("x\n\tat no.bil.launcher.KeyService.onAccessibilityEvent(KeyService.kt:400)"))
        assertFalse(FytSafetyLogic.isFytCrash(""))
    }

    @Test fun toKrasjPaaTreMinutterSlaarAv() {
        val now = 1_000_000_000L
        assertFalse(FytSafetyLogic.shouldTrip(listOf(now - 10_000), now))
        assertTrue(FytSafetyLogic.shouldTrip(listOf(now - 100_000, now - 10_000), now))
        // Gamle krasj (over 3 min) og krasj «i framtiden» (klokka hoppet) teller ikke
        assertFalse(FytSafetyLogic.shouldTrip(listOf(now - 200_000, now - 10_000), now))
        assertFalse(FytSafetyLogic.shouldTrip(listOf(now + 50_000, now - 10_000), now))
        assertEquals(listOf(1L, 2L), FytSafetyLogic.parseTimes("1, 2,x,"))
        assertEquals(listOf("fyt_radio", "tos_canbus"), FytSafetyLogic.parseKeys("fyt_radio,ukjent,tos_canbus"))
    }

    @Test fun nyeForsoekVenterLengerOgGirOpp() {
        assertEquals(5_000L, FytSafetyLogic.retryDelay(0))
        assertEquals(10_000L, FytSafetyLogic.retryDelay(1))
        assertEquals(300_000L, FytSafetyLogic.retryDelay(10))
        assertNull(FytSafetyLogic.retryDelay(20))
        assertNull(FytSafetyLogic.retryDelay(-1))
        assertFalse(FytSafetyLogic.isStorm(5))
        assertTrue(FytSafetyLogic.isStorm(6))
    }

    @Test fun lysstyrkeAldriNesteSvart() {
        assertEquals(50, FytSafetyLogic.safeLevel(0))
        assertEquals(50, FytSafetyLogic.safeLevel(4))
        assertNull(FytSafetyLogic.safeLevel(5))
        assertNull(FytSafetyLogic.safeLevel(-1))          // ikke lagret
        assertEquals(FytMainLogic.MIN_LEVEL, FytMainLogic.levelFor(0f))
        Prefs.set("fyt_bright_night", 2); Prefs.set("fyt_bright_day", 80)
        FytMain.sanitizeOnStart()
        assertEquals(50, Prefs.int("fyt_bright_night", -1))
        assertEquals(80, Prefs.int("fyt_bright_day", -1))
    }

    @Test fun ryggingUtloeper() {
        val t0 = 1_000_000L
        assertTrue(FytMainLogic.stayOut(true, t0, 0L, t0 + 60_000))
        assertFalse(FytMainLogic.stayOut(true, t0, 0L, t0 + FytMainLogic.REVERSE_MAX_MS))
        assertTrue(FytMainLogic.stayOut(false, t0, t0 + 5_000, t0 + 6_000))
        assertFalse(FytMainLogic.stayOut(false, t0, t0 + 5_000, t0 + 8_000))
        // Uten tilkobling til stereoen er T'OS aldri passiv
        assertFalse(FytMain.stayOut)
    }

    @Test fun vinduerHarMaksLevetid() {
        // Volumlinja: forsvinner etter 60 s totalt selv om volumet endres hele tiden og FYT-vinduet står
        assertFalse(FytVolumeLogic.shouldHide(now = 59_000, lastChange = 58_900, popupPresent = true, shownAt = 0))
        assertTrue(FytVolumeLogic.shouldHide(now = 60_000, lastChange = 59_900, popupPresent = true, shownAt = 0))
        assertTrue(FytVolumeLogic.shouldHide(now = 12_000, lastChange = 1_000, popupPresent = true))
        // Klima/dør-kortet likeså
        assertTrue(FytCanLogic.shouldHide(now = 61_000, lastChange = 60_500, popupPresent = true, minMs = 4000, shownAt = 1_000))
        assertFalse(FytCanLogic.shouldHide(now = 30_000, lastChange = 29_000, popupPresent = true, minMs = 4000, shownAt = 1_000))
        // Teppet (svart skjerm): aldri over 4 s totalt, selv om det bes om igjen og igjen
        assertEquals(1200L, TosOverlay.coverWait(now = 10_000, shownAt = 0, maxMs = 1200))
        assertEquals(2500L, TosOverlay.coverWait(now = 10_000, shownAt = 0, maxMs = 99_999))
        assertEquals(1000L, TosOverlay.coverWait(now = 13_000, shownAt = 10_000, maxMs = 1200))
        assertNull(TosOverlay.coverWait(now = 14_000, shownAt = 10_000, maxMs = 1200))
    }

    @Test fun samtaleskjermLoekkeStoppes() {
        val l = RedirectLimiter(minGapMs = 1500, maxInWindow = 4, windowMs = 30_000, pauseMs = 60_000)
        var t = 100_000L
        repeat(4) { assertTrue(l.allow(t)); t += 2_000 }
        assertFalse(l.allow(t))                         // 5. gang på 30 s: pause
        assertFalse(l.allow(t + 30_000))
        assertTrue(l.allow(t + 61_000))
    }

    @Test fun slaarAvOgPaaIgjen() {
        Prefs.set("fyt_steer", false)                    // brukeren hadde selv slått av denne
        FytSafety.trip("test")
        assertFalse(Fyt.enabled)
        assertFalse(Fyt.connected)
        assertTrue(FytSafety.tripped)
        FytSafetyLogic.FEATURES.forEach { assertFalse(it, Prefs.bool(it, true)) }
        assertFalse(Fyt.moduleCmd(Fyt.M_MAIN, 0, intArrayOf(10)))   // ingen kommandoer til stereoen
        // Hovedbryteren på igjen: bryterne tilbake slik de var
        Prefs.set(FytSafetyLogic.MASTER, true)
        assertTrue(Fyt.enabled)
        assertFalse(FytSafety.tripped)
        assertTrue(Prefs.bool("fyt_radio", true))
        assertTrue(Prefs.bool("tos_volume", true))
        assertFalse(Prefs.bool("fyt_steer", true))
    }

    @Test fun sikkerModusTellerFytKrasj() {
        val c = ApplicationProvider.getApplicationContext<Context>()
        c.getSharedPreferences("sikker", Context.MODE_PRIVATE).edit().clear().commit()
        SafeMode.recordCrash(c, fyt = true)
        SafeMode.check(c)
        assertTrue(Fyt.enabled)                          // én krasj: ingenting skjer
        SafeMode.recordCrash(c, fyt = true)
        SafeMode.check(c)
        assertFalse(Fyt.enabled)                         // to: FYT av
        assertFalse(SafeMode.on)                         // vanlig sikker modus først ved 3
        c.getSharedPreferences("sikker", Context.MODE_PRIVATE).edit().clear().commit()
        SafeMode.turnOff(c)
    }

    @Test fun feilIBehandlerKrasjerIkke() {
        val before = Fyt.handlerErrors
        Fyt.guard("test") { error("boom") }
        assertEquals(before + 1, Fyt.handlerErrors)
    }

    @Test fun lydenSkrusPaaIgjenEtterOmstart() {
        Prefs.set("fyt_tos_muted", true)
        // Uten stereo kan kommandoen ikke sendes, men merket fjernes (ikke en evig løkke av forsøk)
        FytRadio.releaseTosMute()
        assertFalse(Prefs.bool("fyt_tos_muted"))
        assertFalse(FytRadio.releaseTosMute())
    }
}
