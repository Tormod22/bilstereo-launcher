package no.bil.launcher

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** «Full kontroll» (Owner.kt): hva som kan skjules, og hva som aldri skal røres. */
class OwnerLogicTest {
    @Test fun aldriKritiskePakker() {
        val critical = listOf(
            "com.syu.ms", "com.syu.ss", "com.syu.us", "com.syu.ps", "com.syu.radio", "com.syu.bt", "com.syu.music",
            "com.syu.carlink", "com.syu.steer", "com.syu.canbus", "com.android.systemui", "com.android.settings",
            "com.android.phone", "com.android.bluetooth", "com.google.android.gms", "android", "no.bil.launcher",
            "com.fyt.hidebutton", "com.android.providers.settings",
        )
        for (p in critical) {
            assertTrue(p, OwnerLogic.isCritical(p))
            assertFalse(p, OwnerLogic.mayHide(p))
        }
        // Selv om noen la dem inn i lista over startskjermer, skjules de ikke
        assertTrue(OwnerLogic.toHide(true, true, critical).isEmpty())
        assertTrue(OwnerLogic.ALL_HIDEABLE.none { OwnerLogic.isCritical(it) })
        // Bare kjente startskjermer – en tilfeldig app skjules aldri
        assertFalse(OwnerLogic.mayHide("com.spotify.music"))
        assertFalse(OwnerLogic.mayHide("com.waze"))
    }

    @Test fun hvaSomSkjules() {
        val installed = listOf("com.android.launcher9", "com.android.launcher4", "com.android.launcher3", "com.spotify.music", "com.syu.ms")
        // Standard: de andre startskjermene, men IKKE stereoens hovedstartskjerm (launcher9) og ikke Quickstep («siste apper»)
        assertEquals(listOf("com.android.launcher4"), OwnerLogic.toHide(true, false, installed))
        assertEquals(listOf("com.android.launcher3"), OwnerLogic.toHide(false, true, installed))
        assertEquals(listOf("com.android.launcher3", "com.android.launcher4"), OwnerLogic.toHide(true, true, installed))
        // Med egen bryter: hovedstartskjermen også
        assertEquals(listOf("com.android.launcher3", "com.android.launcher4", "com.android.launcher9"),
            OwnerLogic.toHide(true, true, installed, "com.android.launcher9", hideMain = true))
        // En annen hovedstartskjerm (ro.fyt.launcher): da er det den som beholdes
        assertEquals(listOf("com.android.launcher9"), OwnerLogic.toHide(true, false, installed, "com.android.launcher4"))
        assertTrue(OwnerLogic.toHide(false, false, installed).isEmpty())
        // Bare det som finnes på stereoen
        assertTrue(OwnerLogic.toHide(true, true, emptyList()).isEmpty())
    }

    @Test fun standardverdier() {
        assertEquals(false, OwnerLogic.DEFAULTS[OwnerLogic.HIDE_RECENTS])
        for (k in listOf(OwnerLogic.SHADE_OFF, OwnerLogic.HIDE_LAUNCHERS, OwnerLogic.HOME, OwnerLogic.GRANT, OwnerLogic.NO_LOCK, OwnerLogic.AUTO_TIME, OwnerLogic.KEEP_SERVICES))
            assertEquals(k, true, OwnerLogic.DEFAULTS[k])
        // Alle bryterne begynner med «owner_» (Prefs.set bruker dem med en gang)
        assertTrue(OwnerLogic.DEFAULTS.keys.all { it.startsWith("owner_") })
    }

    @Test fun tillatelserPerVersjon() {
        val p29 = OwnerLogic.permissions(29)
        assertTrue(p29.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertTrue(p29.contains("android.permission.READ_PHONE_STATE"))
        assertFalse(p29.contains("android.permission.BLUETOOTH_CONNECT"))   // finnes ikke på Android 10
        assertFalse(p29.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(OwnerLogic.permissions(33).contains("android.permission.POST_NOTIFICATIONS"))
        // Bare tillatelser T'OS faktisk ber om i manifestet
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (p in OwnerLogic.permissions(34)) assertTrue(p, manifest.contains("\"$p\""))
    }

    @Test fun oppsettOgDokumentasjon() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".TosAdmin\""))
        assertTrue(manifest.contains("android.permission.BIND_DEVICE_ADMIN"))
        assertTrue(File("src/main/res/xml/device_admin.xml").exists())
        val guide = File("../docs/ADB-OPPSETT.md").readText()
        assertTrue(guide.contains("adb shell dpm set-device-owner no.bil.launcher/.TosAdmin"))
        assertTrue(File("../docs/tos-oppsett.bat").readText().contains("dpm set-device-owner no.bil.launcher/.TosAdmin"))
        assertTrue(File("../docs/BRUKSANVISNING.md").readText().contains("## Full kontroll"))
        assertTrue(adbSetupCommands("no.bil.launcher").any { it.cmd.endsWith("android.permission.WRITE_SECURE_SETTINGS") })
        // Oppstartsbildet ligger i appen
        assertTrue(File("src/main/assets/${Owner.BOOT_ASSET}").length() > 1000)
        // Knappestyringen holdes på uten å slå av stereoens egne tjenester
        assertEquals("a/b:no.bil.launcher/no.bil.launcher.KeyService", accessibilityValue("a/b", "no.bil.launcher/no.bil.launcher.KeyService"))
    }
}

/** Kortet under Om stereoen, og at bryterne virker når T'OS er enhetseier (Robolectric). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w1024dp-h600dp-land-mdpi")
class OwnerSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun start() { rule.mainClock.autoAdvance = false; rule.settle(1_500) }

    @After fun reset() { rule.runOnUiThread { Nav.screen = Screen.DASH } }

    private fun dpm() = rule.activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun install(pkg: String) {
        val ai = ApplicationInfo().apply { packageName = pkg; name = pkg; flags = ApplicationInfo.FLAG_SYSTEM }
        shadowOf(rule.activity.packageManager).installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = ai })
    }

    @Test fun kortetUtenOgMedFullKontroll() {
        rule.runOnUiThread { Prefs.set("settings_cat", SetCat.ABOUT.name); Nav.screen = Screen.SETTINGS }
        rule.settle(1_500)
        rule.awaitText("Full kontroll: Av")
        rule.awaitText("Slik slår du det på")
        rule.awaitText("Lagre T'OS-oppstartsbilde")
        // Nå blir T'OS enhetseier (som etter «dpm set-device-owner»)
        rule.runOnUiThread {
            shadowOf(dpm()).setActiveAdmin(Owner.admin(rule.activity))
            shadowOf(dpm()).setDeviceOwner(Owner.admin(rule.activity))
            Prefs.set("settings_cat", SetCat.ABOUT.name)
        }
        rule.settle(1_500)
        rule.awaitText("Full kontroll: På")
        rule.awaitText("Steng Androids nedtrekksmeny")
        rule.awaitText("Slå av full kontroll")
    }

    @Test fun bryterneVirker() {
        val c = rule.activity
        assertFalse(Owner.active(c))
        install("com.android.launcher9"); install("com.android.launcher4"); install("com.android.launcher3"); install("com.syu.ms")
        // Ikke enhetseier: ingenting skjer
        Owner.apply(c)
        assertFalse(shadowOf(dpm()).wasPackageEverHidden("com.android.launcher9"))

        // Som etter «dpm set-device-owner»: enhetseier og aktiv administrator
        shadowOf(dpm()).setActiveAdmin(Owner.admin(c))
        shadowOf(dpm()).setDeviceOwner(Owner.admin(c))
        assertTrue(Owner.active(c))
        rule.runOnUiThread { Prefs.set(OwnerLogic.HIDE_LAUNCHERS, true); Prefs.set(OwnerLogic.HIDE_RECENTS, false) }
        Owner.apply(c)
        val a = Owner.admin(c)
        assertTrue(dpm().isApplicationHidden(a, "com.android.launcher4"))
        assertFalse(dpm().isApplicationHidden(a, "com.android.launcher9"))   // stereoens hovedstartskjerm: bare med egen bryter
        assertFalse(dpm().isApplicationHidden(a, "com.android.launcher3"))   // «siste apper» er av som standard
        assertFalse(shadowOf(dpm()).wasPackageEverHidden("com.syu.ms"))      // FYT-sentralen røres aldri
        assertEquals(DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            dpm().getPermissionGrantState(a, c.packageName, "android.permission.ACCESS_FINE_LOCATION"))
        assertEquals(listOf("com.android.launcher4"), Owner.hiddenNow(c))
        assertTrue(Owner.report(c).contains("Enhetseier (full kontroll): JA"))

        // Bryteren av → vises igjen med en gang
        rule.runOnUiThread { Prefs.set(OwnerLogic.HIDE_LAUNCHERS, false) }
        assertFalse(dpm().isApplicationHidden(a, "com.android.launcher4"))
        rule.runOnUiThread { Prefs.set(OwnerLogic.HIDE_LAUNCHERS, true) }
        assertTrue(dpm().isApplicationHidden(a, "com.android.launcher4"))
        // Hovedstartskjermen også (egen bryter)
        rule.runOnUiThread { Prefs.set(OwnerLogic.HIDE_MAIN, true) }
        assertTrue(dpm().isApplicationHidden(a, "com.android.launcher9"))

        // «Slå av full kontroll»: alt vises igjen, og tillatelsene er ikke lenger låst
        Owner.release(c)
        assertFalse(dpm().isApplicationHidden(a, "com.android.launcher4"))
        assertFalse(dpm().isApplicationHidden(a, "com.android.launcher9"))
        assertEquals(DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT,
            dpm().getPermissionGrantState(a, c.packageName, "android.permission.ACCESS_FINE_LOCATION"))
        rule.runOnUiThread { Prefs.set(OwnerLogic.HIDE_LAUNCHERS, null); Prefs.set(OwnerLogic.HIDE_MAIN, null) }
    }
}
