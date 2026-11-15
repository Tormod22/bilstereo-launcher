package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PC-oppsettet (docs/tos-oppsett.bat, dobbeltklikk-fila) må gjøre det samme som veiledningen
 * (docs/ADB-OPPSETT.md) – så de aldri glir fra hverandre – og være en gyldig Windows-fil.
 */
class PcSetupTest {
    private val batBytes = File("../docs/tos-oppsett.bat").readBytes()
    private val bat = String(batBytes, Charsets.UTF_8)
    private val guide = File("../docs/ADB-OPPSETT.md").readText()

    /** Alle «adb shell pm grant / appops / settings put»-linjer i veiledningen finnes i skriptet. */
    @Test fun samme_kommandoer_som_veiledningen() {
        val lines = guide.lines().map { it.trim() }
            .filter { it.startsWith("adb shell pm grant") || it.startsWith("adb shell appops") || it.startsWith("adb shell settings put") }
            // Knappestyringen lages av skriptet selv (leser lista og legger T'OS til) – eksemplene i veiledningen står ikke ordrett
            .filterNot { "enabled_accessibility_services" in it }
            .distinct()
        assertTrue("Fant for få kommandoer i veiledningen: ${lines.size}", lines.size >= 20)
        for (l in lines) assertTrue("Mangler i tos-oppsett.bat: $l", l in bat)
        // …og T'OS sin egen liste (Førstegangsoppsett → Med PC)
        for (s in adbSetupCommands("no.bil.launcher").drop(1)) assertTrue("Mangler i tos-oppsett.bat: ${s.cmd}", s.cmd in bat)
    }

    /** Full kontroll: Google-tjenestene slås av, enhetseier settes, og tjenestene slås ALLTID på igjen (finally). */
    @Test fun full_kontroll_med_google_omvei() {
        val disable = "adb shell pm disable-user --user 0 com.google.android.gms"
        val dpm = "adb shell dpm set-device-owner no.bil.launcher/.TosAdmin"
        val enable = "adb shell pm enable com.google.android.gms"
        for (c in listOf(disable, dpm, enable)) {
            assertTrue("Mangler i skriptet: $c", c in bat)
            assertTrue("Mangler i veiledningen: $c", c in guide)
        }
        val fc = bat.substring(bat.indexOf("function Invoke-FullControl"))
        val iDisable = fc.indexOf(disable)
        val iFinally = fc.indexOf("finally")
        assertTrue("disable-user må stå i try før finally", iDisable in 0 until iFinally)
        assertTrue("enable må stå i finally", fc.indexOf(enable, iFinally) > iFinally)
        // Et avbrutt forsøk rettes neste gang: skriptet slår Google-tjenestene på igjen før alt annet
        val takeover = bat.substring(bat.indexOf("function Invoke-Takeover"))
        assertTrue(takeover.indexOf("Enable-GmsIfDisabled") < takeover.indexOf("enabled_accessibility_services"))
        // Feilsøking slås av til slutt, og angre finnes
        assertTrue("adb shell settings put global adb_enabled 0" in bat)
        assertTrue("adb shell settings put system show_navigationbar 1" in bat)
        assertTrue("adb shell dumpsys deviceidle whitelist -no.bil.launcher" in bat)
        assertTrue("'angre'" in bat)
    }

    /** Windows-delen: CRLF, ingen BOM, bare ASCII før PowerShell-delen, og bare ett «#>». */
    @Test fun gyldig_windows_fil() {
        assertFalse("Fila må ikke starte med BOM", batBytes.size > 2 && batBytes[0] == 0xEF.toByte())
        assertTrue("Første linje må være <# : (Ledetekst hopper over den, PowerShell ser en kommentar)", bat.startsWith("<# :"))
        assertEquals("Bare ett #> i fila (ellers slutter kommentaren for tidlig)", 1, Regex("#>").findAll(bat).count())
        val batchPart = bat.substringBefore("#>")
        assertTrue("Windows-delen må være ren ASCII", batchPart.all { it.code < 128 })
        assertTrue("CRLF-linjeskift", "\r\n" in bat && bat.replace("\r\n", "").none { it == '\n' })
        assertTrue("TOS_SELF" in batchPart && "ReadAllText(\$env:TOS_SELF" in batchPart)
        assertTrue(File("../.gitattributes").readText().contains("*.bat text eol=crlf"))
    }

    /** Fila kan lastes ned fra «latest»: lastes opp etter APK-en og før version.json. */
    @Test fun lastes_opp_med_bygget() {
        val yml = File("../.github/workflows/build.yml").readText()
        val apk = yml.indexOf("files: bilstereo.apk")
        val bat = yml.indexOf("files: docs/tos-oppsett.bat")
        val version = yml.indexOf("files: version.json")
        assertTrue(apk in 0 until bat)
        assertTrue(bat < version)
        assertTrue("releases/download/latest/tos-oppsett.bat" in guide)
    }
}
