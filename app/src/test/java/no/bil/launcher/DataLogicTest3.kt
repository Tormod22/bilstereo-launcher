package no.bil.launcher

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DataLogicTest3 {
    private val pkg = "no.bil.launcher"

    @Test fun installasjonssvar_paa_norsk() {
        assertEquals("Oppdateringen ble avbrutt", Updater.installStatusText(PackageInstaller.STATUS_FAILURE_ABORTED, null))
        assertTrue("nøkkel" in Updater.installStatusText(PackageInstaller.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE"))
        assertTrue("plass" in Updater.installStatusText(PackageInstaller.STATUS_FAILURE_STORAGE, null))
        assertTrue("Play Protect" in Updater.installStatusText(PackageInstaller.STATUS_FAILURE_BLOCKED, null))
        // Ukjent feil: Androids egen tekst tas med i parentes, ellers «prøv igjen»
        assertEquals("Oppdateringen feilet (noe rart)", Updater.installStatusText(PackageInstaller.STATUS_FAILURE, "noe rart"))
        assertEquals("Oppdateringen feilet – prøv igjen", Updater.installStatusText(PackageInstaller.STATUS_FAILURE, " "))
    }

    @Test fun wifi_velger_minst_mulig_android() {
        // Android 10+: foreslått nett (ingen Android-boks)
        assertEquals(WifiWay.SUGGEST, wifiWay(33, eap = false, wep = false, owe = false, suggestionsRejected = false))
        assertEquals(WifiWay.SUGGEST, wifiWay(29, eap = false, wep = false, owe = false, suggestionsRejected = false))
        // Avslått forslag: Androids lille «Lagre»-boks (11+), ellers Wi-Fi-innstillingene
        assertEquals(WifiWay.ANDROID_DIALOG, wifiWay(31, eap = false, wep = false, owe = false, suggestionsRejected = true))
        assertEquals(WifiWay.ANDROID_SETTINGS, wifiWay(29, eap = false, wep = false, owe = false, suggestionsRejected = true))
        // Android 8–9: direkte
        assertEquals(WifiWay.LEGACY, wifiWay(28, eap = false, wep = false, owe = false, suggestionsRejected = false))
        // Bedriftsnett og WEP
        assertEquals(WifiWay.ANDROID_SETTINGS, wifiWay(33, eap = true, wep = false, owe = false, suggestionsRejected = false))
        assertEquals(WifiWay.UNSUPPORTED, wifiWay(33, eap = false, wep = true, owe = false, suggestionsRejected = false))
        assertEquals(WifiWay.ANDROID_SETTINGS, wifiWay(29, eap = false, wep = false, owe = true, suggestionsRejected = false))
        assertEquals(WifiWay.SUGGEST, wifiWay(30, eap = false, wep = false, owe = true, suggestionsRejected = false))
    }

    @Test fun knappestyring_legges_til_uten_aa_fjerne_stereoens_egne() {
        val ours = "$pkg/$pkg.KeyService"
        assertEquals(ours, accessibilityValue(null, ours))
        assertEquals(ours, accessibilityValue("null", ours))
        assertEquals(ours, accessibilityValue("", ours))
        assertEquals("com.a/com.a.S:$ours", accessibilityValue("com.a/com.a.S", ours))
        assertEquals("com.a/com.a.S:$ours", accessibilityValue("com.a/com.a.S:$ours", ours))   // ikke to ganger
    }

    @Test fun adb_kommandoer_passer_med_manifest_og_veiledning() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("android:name=\".KeyService\"" in manifest)
        assertTrue("android:name=\".MediaListener\"" in manifest)
        assertTrue("android:name=\".MainActivity\"" in manifest)
        val steps = adbSetupCommands(pkg)
        assertTrue(steps.first().cmd.endsWith("enabled_accessibility_services $pkg/$pkg.KeyService"))
        assertTrue(steps.any { it.cmd == "adb shell cmd notification allow_listener $pkg/$pkg.MediaListener" })
        assertTrue(steps.any { it.cmd == "adb shell cmd package set-home-activity $pkg/.MainActivity" })
        // Alle tillatelser som gis med «pm grant» må stå i manifestet (ellers feiler kommandoen)
        steps.filter { "pm grant" in it.cmd }.forEach { s ->
            val perm = s.cmd.substringAfterLast(' ')
            assertTrue("$perm mangler i manifestet", "\"$perm\"" in manifest || perm.endsWith("READ_CONTACTS") || perm.endsWith("READ_PHONE_STATE"))
        }
        // Veiledningen og Windows-skriptet har de samme linjene (knappestyringen lages egen i skriptet)
        val doc = File("../docs/ADB-OPPSETT.md").readText()
        val bat = File("../docs/tos-oppsett.bat").readText()
        steps.drop(1).forEach { s ->
            assertTrue("Mangler i ADB-OPPSETT.md: ${s.cmd}", s.cmd in doc)
            assertTrue("Mangler i tos-oppsett.bat: ${s.cmd}", s.cmd in bat)
        }
        assertTrue("enabled_accessibility_services !NEW!" in bat)
        assertTrue(steps.all { it.what.isNotBlank() })
    }

    @Test fun soek_i_innstillinger() {
        assertEquals(SetCat.entries, settingsSearch(""))
        assertEquals(SetCat.entries, settingsSearch("   "))
        assertEquals(listOf(SetCat.SCREEN), settingsSearch("lysstyrke"))
        assertTrue(SetCat.NET in settingsSearch("WIFI"))
        assertTrue(SetCat.TIME in settingsSearch("klokke 24"))
        assertTrue(SetCat.STORAGE in settingsSearch("tøm"))
        assertTrue(settingsSearch("finnesikke").isEmpty())
    }

    @Test fun automatisk_sikkerhetskopi_en_gang_i_doegnet() {
        val now = 1_700_000_000_000L
        assertTrue(LocalBackup.autoSaveDue(false, 0L, now))
        assertFalse(LocalBackup.autoSaveDue(true, now - 3_600_000L, now))
        assertTrue(LocalBackup.autoSaveDue(true, now - 90_000_000L, now))
        assertTrue(LocalBackup.autoSaveDue(true, now + 90_000_000L, now))   // klokka har gått baklengs
    }
}
