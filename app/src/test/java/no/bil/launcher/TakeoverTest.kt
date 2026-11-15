package no.bil.launcher

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import no.bil.launcher.TakeoverLogic.Dpm
import no.bil.launcher.TakeoverLogic.Net
import no.bil.launcher.TakeoverLogic.OwnerPlan
import no.bil.launcher.TakeoverLogic.Result
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** «Ta over stereoen» – ren logikk (ingen ADB i testene). */
class TakeoverLogicTest {
    private val pkg = "no.bil.launcher"

    /** Samme kommandoer som PC-oppsettet, uten «adb shell», og FYT-linja tas for seg selv. */
    @Test fun kommandolista() {
        val acc = accessibilityValue("com.fyt.x/com.fyt.x.S", "$pkg/$pkg.KeyService")
        val cmds = TakeoverLogic.commands(pkg, acc).map { it.cmd }
        assertTrue(cmds.none { it.startsWith("adb ") })
        assertTrue(cmds.none { "show_navigationbar" in it })
        // Verdien kommer fra stereoen og sendes i anførselstegn (skallet skal ikke tolke tegn som «$» i stereoens egne tjenester)
        assertTrue("settings put secure enabled_accessibility_services 'com.fyt.x/com.fyt.x.S:$pkg/$pkg.KeyService'" in cmds)
        for (key in listOf(
            "settings put secure accessibility_enabled 1",
            "cmd notification allow_listener $pkg/$pkg.MediaListener",
            "appops set $pkg GET_USAGE_STATS allow",
            "appops set $pkg SYSTEM_ALERT_WINDOW allow",
            "appops set $pkg REQUEST_INSTALL_PACKAGES allow",
            "dumpsys deviceidle whitelist +$pkg",
            "cmd package set-home-activity $pkg/.MainActivity",
            "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
            "pm grant $pkg android.permission.ACCESS_FINE_LOCATION",
        )) assertTrue(key, key in cmds)
        // Like mange som PC-lista minus FYT-linja (én liste for begge veiene)
        assertEquals(adbSetupCommands(pkg, acc).size - 1, cmds.size)
        assertEquals("dpm set-device-owner $pkg/.TosAdmin", TakeoverLogic.ownerCommand(pkg))
    }

    @Test fun svar_fra_kommandoer() {
        assertEquals(Result.OK, TakeoverLogic.classify(0, ""))
        assertEquals(Result.SKIP, TakeoverLogic.classify(255, "Exception occurred while executing 'grant':\njava.lang.IllegalArgumentException: Unknown permission: android.permission.BLUETOOTH_CONNECT"))
        assertEquals(Result.SKIP, TakeoverLogic.classify(1, "Error: Unknown operation string: ACCESS_RESTRICTED_SETTINGS"))
        assertEquals(Result.SKIP, TakeoverLogic.classify(255, "Unknown command: network-suggestions-set-user-approved"))
        assertEquals(Result.FAIL, TakeoverLogic.classify(255, "java.lang.SecurityException: grantRuntimePermission: Neither user 2000 nor current process"))
        assertEquals(Result.FAIL, TakeoverLogic.classify(1, ""))
    }

    @Test fun enhetseier() {
        assertEquals(Dpm.OK, TakeoverLogic.dpmResult("Success: Device owner set to package ComponentInfo{no.bil.launcher/no.bil.launcher.TosAdmin}\nActive admin set to component {no.bil.launcher/no.bil.launcher.TosAdmin}"))
        assertEquals(Dpm.ACCOUNTS, TakeoverLogic.dpmResult("java.lang.IllegalStateException: Not allowed to set the device owner because there are already some accounts on the device"))
        assertEquals(Dpm.USERS, TakeoverLogic.dpmResult("java.lang.IllegalStateException: Not allowed to set the device owner because there are already several users on the device"))
        assertEquals(Dpm.ALREADY, TakeoverLogic.dpmResult("java.lang.IllegalStateException: Trying to set the device owner, but device owner is already set."))
        assertEquals(Dpm.OTHER, TakeoverLogic.dpmResult("Error: Unknown admin: ComponentInfo{x/y}"))
        assertEquals(Dpm.OTHER, TakeoverLogic.dpmResult(""))
    }

    @Test fun kontoer_og_brukere() {
        val dump = """
            User UserInfo{0:Owner:c13}:
              Accounts: 2
                Account {name=noen@gmail.com, type=com.google}
                Account {name=noen@gmail.com, type=com.google}
              Active Sessions: 0
        """.trimIndent()
        val types = TakeoverLogic.accountTypes(dump)
        assertEquals(listOf("com.google"), types)
        assertTrue(types.none { "@" in it })   // e-postadressen tas aldri med
        assertEquals(OwnerPlan.DIRECT, TakeoverLogic.ownerPlan(emptyList()))
        assertEquals(OwnerPlan.GOOGLE_TRICK, TakeoverLogic.ownerPlan(types))
        assertEquals(OwnerPlan.MANUAL, TakeoverLogic.ownerPlan(listOf("com.google", "com.whatsapp")))
        assertEquals(1, TakeoverLogic.userCount("Users:\n\tUserInfo{0:Owner:c13} running"))
        assertEquals(2, TakeoverLogic.userCount("Users:\n\tUserInfo{0:Owner:c13} running\n\tUserInfo{10:Gjest:14}"))
        assertTrue(TakeoverLogic.gmsEnabled("Package com.google.android.gms new state: enabled"))
        assertFalse(TakeoverLogic.gmsEnabled("Package com.google.android.gms new state: disabled-user"))
        assertFalse(TakeoverLogic.gmsEnabled("forbindelsen brøt (EOFException)"))
    }

    @Test fun port_og_tilstand() {
        assertEquals(5555, TakeoverLogic.tcpPort("", "5555"))
        assertEquals(5555, TakeoverLogic.tcpPort("5555", null))
        assertNull(TakeoverLogic.tcpPort("", "-1", null, "abc"))
        assertEquals(Net.OPEN, TakeoverLogic.net(open = true, adbOn = false))
        assertEquals(Net.ADB_OFF, TakeoverLogic.net(open = false, adbOn = false))
        assertEquals(Net.NO_NETWORK_ADB, TakeoverLogic.net(open = false, adbOn = true))
        assertEquals("1", TakeoverLogic.navUndo(null))
        assertEquals("1", TakeoverLogic.navUndo("null"))
        assertEquals("2", TakeoverLogic.navUndo(" 2 "))
        assertEquals(0, TakeoverLogic.progress(false, false, false, false, false))
        assertEquals(3, TakeoverLogic.progress(true, true, true, false, false))
        assertEquals(5, TakeoverLogic.progress(false, false, false, false, true))
    }

    @Test fun feilmeldinger_paa_norsk() {
        assertTrue("Tillat" in TakeoverLogic.connectError("SocketTimeoutException", "Read timed out"))
        assertTrue("port 5555" in TakeoverLogic.connectError("ConnectException", "Connection refused"))
        assertTrue("sa nei" in TakeoverLogic.connectError("IOException", "Connection closed"))
        assertTrue("Prøv igjen" in TakeoverLogic.connectError("IllegalStateException", null))
    }
}

/** Siden i det ekte UI-et (Robolectric har ingen ADB: alt står på «ikke gjort»). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w1024dp-h600dp-land-mdpi")
class TakeoverSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun start() { rule.mainClock.autoAdvance = false; rule.settle(1_500) }

    @After fun reset() { rule.runOnUiThread { Nav.screen = Screen.DASH } }

    @Test fun ingentingGjort() {
        rule.runOnUiThread { Nav.screen = Screen.TAKEOVER }
        rule.settle(1_500)
        rule.awaitText("Ta over stereoen")
        rule.awaitText("Utviklermodus")
        rule.awaitText("USB-feilsøking")
        rule.awaitText("Kommer av seg selv når punkt 2 er gjort.")
        rule.awaitText("0 AV 5")
        assertFalse(Takeover.connected)
    }

    @Test fun radenIOmStereoen() {
        rule.runOnUiThread { Prefs.set("settings_cat", "ABOUT"); Nav.screen = Screen.SETTINGS }
        rule.settle(1_500)
        rule.awaitText("Ta over stereoen – uten PC")
    }
}

/** v5.16: åpne ADB-porten via stereoens FYT-tjeneste – uten stereo (Robolectric) skjer ingenting og ingenting krasjer. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class TakeoverFytPortTest {
    @Test fun utenStereoSkjerIngenting() {
        assertFalse("ingen FYT-tjeneste her", Fyt.setSystemProperty("service.adb.tcp.port", "5555"))
        assertFalse("tomt navn avvises", Fyt.setSystemProperty("", "5555"))
        assertFalse("tom verdi avvises (stereoen ignorerer den)", Fyt.setSystemProperty("service.adb.tcp.port", ""))
        // Uten tilkoblet FYT-tjeneste prøver ikke T'OS, men forklarer
        Takeover.openPortViaFyt(ApplicationProvider.getApplicationContext())
        assertFalse(Takeover.opening)
        assertTrue(Takeover.message.orEmpty().contains("FYT"))
    }
}

/** v5.17: funn fra stereoens første «Send alt». */
class V517LogicTest {
    @Test fun wifiPaAndroid10ErIkkeFeil() {
        assertEquals(Result.SKIP, TakeoverLogic.classify(255, "Security exception: Uid 2000 does not have access to wifi commands"))
        assertEquals(Result.FAIL, TakeoverLogic.classify(1, "Exception occurred: something else broke"))
    }
    @Test fun loggenUtenStoy() {
        val log = "11-15 23:04:34 I DefaultDispatch: type=1400 audit(0.0:1): avc: denied { read }\n" +
            "11-15 23:04:40 I gralloc : gralloc_unregister_buffer hnd=0x6f\n" +
            "11-15 23:04:41 E AndroidRuntime: FATAL EXCEPTION: main"
        val c = CrashLogLogic.clean(log)
        assertTrue(c.contains("FATAL EXCEPTION"))
        assertFalse(c.contains("avc: denied"))
        assertTrue(c.contains("2 linjer med støy"))
    }
    @Test fun kartbufferStorrelse() {
        assertEquals("1 kB", TileCacheLogic.sizeText(500))
        assertEquals("800 kB", TileCacheLogic.sizeText(800L * 1024))
        assertEquals("12,5 MB", TileCacheLogic.sizeText((12.5 * 1_048_576).toLong()))
    }
}
