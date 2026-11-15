package no.bil.launcher

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Sikkerhetsnettet rundt «Ta over stereoen» og «Full kontroll» (runde r9): ingenting skal kunne la stereoen stå uten
 * startskjerm, uten Play-tjenester eller med ødelagte innstillinger.
 */
class TryggLogicTest {
    private val pkg = "no.bil.launcher"

    /** Verdier fra stereoen sendes i enkle anførselstegn: «$» i en indre klasse og «'» ødelegger ikke kommandoen. */
    @Test fun skalletTolkerIkkeVerdien() {
        assertEquals("'a/b:c/d'", TakeoverLogic.shQuote("a/b:c/d"))
        assertEquals("'com.x/.A\$B'", TakeoverLogic.shQuote("com.x/.A\$B"))
        assertEquals("'it'\\''s'", TakeoverLogic.shQuote("it's"))
        val acc = accessibilityValue("com.syu.x/com.syu.x.Svc\$Inner", "$pkg/$pkg.KeyService")
        val cmd = TakeoverLogic.commands(pkg, acc).single { it.cmd.startsWith("settings put secure enabled_accessibility_services") }.cmd
        assertEquals("settings put secure enabled_accessibility_services 'com.syu.x/com.syu.x.Svc\$Inner:$pkg/$pkg.KeyService'", cmd)
        // Resten av lista er den samme som PC-lista (bare uten «adb shell»)
        val pc = adbSetupCommands(pkg, acc).map { it.cmd.removePrefix("adb shell ") }.filter { "show_navigationbar" !in it && "enabled_accessibility_services" !in it }
        assertEquals(pc, TakeoverLogic.commands(pkg, acc).map { it.cmd }.filter { "enabled_accessibility_services" !in it })
    }

    /** «Angre» setter bare et tall tilbake – aldri noe annet som lå lagret. */
    @Test fun angreBareTall() {
        assertEquals("1", TakeoverLogic.navUndo(null))
        assertEquals("1", TakeoverLogic.navUndo("null"))
        assertEquals("0", TakeoverLogic.navUndo(" 0 "))
        assertEquals("2", TakeoverLogic.navUndo("2"))
        assertEquals("1", TakeoverLogic.navUndo("1; reboot"))
        assertEquals("1", TakeoverLogic.navUndo("forbindelsen brøt"))
    }

    /** Play-tjenester slås bare av når Android først har sagt nei pga. Google-konto (og bare Google). */
    @Test fun googleOmveienBareNaarDetTrengs() {
        assertTrue(TakeoverLogic.googleTrickAllowed(listOf("com.google")))
        assertTrue(TakeoverLogic.googleTrickAllowed(listOf("com.google", "com.google.work")))
        assertTrue(TakeoverLogic.googleTrickAllowed(emptyList()))       // typen kunne ikke leses – Android har allerede sagt «konto»
        assertFalse(TakeoverLogic.googleTrickAllowed(listOf("com.google", "com.whatsapp")))
        // PackageManager: 2 = av, 3 = av (bruker), 4 = av til brukt; 0/1 = på
        assertTrue(TakeoverLogic.gmsDisabled(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER))
        assertTrue(TakeoverLogic.gmsDisabled(PackageManager.COMPONENT_ENABLED_STATE_DISABLED))
        assertFalse(TakeoverLogic.gmsDisabled(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT))
        assertFalse(TakeoverLogic.gmsDisabled(PackageManager.COMPONENT_ENABLED_STATE_ENABLED))
    }

    /** Stereoens hovedstartskjerm (ro.fyt.launcher) og nødstoppens reserve. */
    @Test fun hovedstartskjermOgNoedstopp() {
        assertEquals("com.android.launcher9", OwnerLogic.mainLauncher(null))
        assertEquals("com.android.launcher9", OwnerLogic.mainLauncher(""))
        assertEquals("com.android.launcher4", OwnerLogic.mainLauncher("com.android.launcher4"))
        assertEquals("com.android.launcher24", OwnerLogic.mainLauncher("com.android.launcher24/com.android.launcher24.Launcher"))
        assertEquals("com.android.launcher9", OwnerLogic.mainLauncher("rubbish value"))
        // Reserve: hovedstartskjermen, ellers en annen startskjerm, aldri noe kritisk
        assertEquals("com.android.launcher9", OwnerLogic.escapeHome(listOf("com.android.launcher4", "com.android.launcher9"), "com.android.launcher9"))
        assertEquals("com.android.launcher4", OwnerLogic.escapeHome(listOf("com.android.launcher4"), "com.android.launcher9"))
        assertNull(OwnerLogic.escapeHome(listOf("com.android.settings", "no.bil.launcher"), "com.android.launcher9"))
        // Sikker modus (3 krasj) får prøve først; nødstoppen ved 4
        assertFalse(OwnerLogic.shouldEscape(3))
        assertTrue(OwnerLogic.shouldEscape(4))
        // Bare ekte startskjermer skjules (Unisoc-tilpasningen er ikke en startskjerm)
        assertFalse("com.unisoc.launcher.customization" in OwnerLogic.LAUNCHERS)
        assertEquals(false, OwnerLogic.DEFAULTS[OwnerLogic.HIDE_MAIN])
        assertFalse(OwnerLogic.ESCAPED.startsWith("owner_"))   // skal ikke kjøre Owner.apply av seg selv
    }

    /** Androids «Tillat USB-feilsøking?» (SystemUI) tas aldri for nedtrekksmenyen. */
    @Test fun usbVinduetErIkkeMenyen() {
        val usb = WinSnap(ShadeLogic.TYPE_SYSTEM, "com.android.systemui.usb.UsbDebuggingActivity", "com.android.systemui", 200, 50, 824, 560)
        assertFalse(ShadeLogic.isShadeWindow(usb, 1024, 600))
        assertTrue(ShadeLogic.dialogOpen(listOf(usb)))
        // Selve menyen gjenkjennes fortsatt
        val shade = WinSnap(ShadeLogic.TYPE_SYSTEM, "StatusBar", "com.android.systemui", 0, 0, 1024, 600)
        assertTrue(ShadeLogic.isShadeWindow(shade, 1024, 600))
        assertFalse(ShadeLogic.dialogOpen(listOf(shade)))
    }
}

/** Nødstoppen og Play-tjenester i Robolectric (enhetseier simuleres). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w1024dp-h600dp-land-mdpi")
class TryggSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun start() { rule.mainClock.autoAdvance = false; rule.settle(1_500) }
    @After fun reset() {
        rule.runOnUiThread {
            Prefs.set(OwnerLogic.ESCAPED, null); Prefs.set(OwnerLogic.ESCAPED_WHY, null)
            Prefs.set(TakeoverLogic.GMS_MARK, null); Nav.screen = Screen.DASH
        }
    }

    private fun dpm() = rule.activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun installHome(pkg: String) {
        val ai = ApplicationInfo().apply { packageName = pkg; name = pkg; flags = ApplicationInfo.FLAG_SYSTEM }
        val spm = shadowOf(rule.activity.packageManager)
        spm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = ai })
        val cn = ComponentName(pkg, "$pkg.Launcher")
        spm.addActivityIfNotPresent(cn)
        spm.addIntentFilterForActivity(cn, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) })
    }

    @Test fun noedstoppVedKrasjloekke() {
        val c = rule.activity
        installHome("com.android.launcher9"); installHome("com.android.launcher4")
        // Uten enhetseier: nødstoppen gjør ingenting
        Owner.escape(c, "test")
        assertFalse(Owner.escaped)

        shadowOf(dpm()).setActiveAdmin(Owner.admin(c))
        shadowOf(dpm()).setDeviceOwner(Owner.admin(c))
        Owner.apply(c)
        val a = Owner.admin(c)
        assertTrue(dpm().isApplicationHidden(a, "com.android.launcher4"))

        // T'OS krasjet i en løkke → alt vises igjen, nedtrekksmenyen åpnes, meldingen og «Bruk T'OS igjen» kommer
        rule.runOnUiThread { Owner.escape(c, "4 krasj på 3 min") }
        assertTrue(Owner.escaped)
        assertFalse(dpm().isApplicationHidden(a, "com.android.launcher4"))
        assertFalse(dpm().isApplicationHidden(a, "com.android.launcher9"))
        assertFalse(Owner.shadeLocked)
        assertTrue(Owner.report(c).contains("Nødstopp (T'OS krasjet i løkke): PÅ"))
        rule.runOnUiThread { Prefs.set("settings_cat", SetCat.ABOUT.name); Nav.screen = Screen.SETTINGS }
        rule.settle(1_500)
        rule.awaitText("Nødstopp: stereoens startskjerm er i bruk")
        rule.awaitText("Bruk T'OS igjen")

        // «Bruk T'OS igjen»: som før
        rule.runOnUiThread { Owner.endEscape(c) }
        assertFalse(Owner.escaped)
        assertTrue(dpm().isApplicationHidden(a, "com.android.launcher4"))
        Owner.release(c)
    }

    @Test fun playTjenesterSjekkesUtenAdb() {
        val c = rule.activity
        // Finnes ikke Google på stereoen: ikke «av»
        assertFalse(Takeover.gmsDisabledNow(c))
        val ai = ApplicationInfo().apply { packageName = TakeoverLogic.GMS; name = TakeoverLogic.GMS; flags = ApplicationInfo.FLAG_SYSTEM }
        shadowOf(c.packageManager).installPackage(PackageInfo().apply { packageName = TakeoverLogic.GMS; applicationInfo = ai })
        assertFalse(Takeover.gmsDisabledNow(c))
        c.packageManager.setApplicationEnabledSetting(TakeoverLogic.GMS, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)
        assertTrue(Takeover.gmsDisabledNow(c))
        c.packageManager.setApplicationEnabledSetting(TakeoverLogic.GMS, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0)
        assertFalse(Takeover.gmsDisabledNow(c))
        // Oppdatering mens «Ta over» ikke kjører: ingen sperre
        assertFalse(Takeover.busy)
    }
}
