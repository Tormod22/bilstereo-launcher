package no.bil.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sjekker siste bygg på GitHub («latest»-release), laster ned APK og starter installasjon.
 * Krever at repoet er offentlig. Versjonen sammenlignes på versionCode via version.json.
 */
object Updater {
    const val OWNER = "Tormod22"
    const val REPO = "bilstereo-launcher"
    private const val BASE = "https://github.com/$OWNER/$REPO/releases/download/latest"

    data class Info(val versionCode: Int, val versionName: String)

    /** Hvorfor siste sjekk ikke ga svar, i vanlige ord (null = alt i orden). */
    @Volatile var lastError: String? = null
        private set

    suspend fun check(): Info? = withContext(Dispatchers.IO) {
        runCatching {
            val c = open("$BASE/version.json")
            if (c.responseCode == 404) { c.disconnect(); throw FileNotFoundException() }
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            Info(json.getInt("versionCode"), json.getString("versionName"))
        }.onSuccess { lastError = null }
            .onFailure {
                lastError = when (it) {
                    is FileNotFoundException -> "Ingen versjon på GitHub ennå"
                    is org.json.JSONException -> "Fikk rart svar (innloggingsside på Wi-Fi?)"
                    else -> if (Hub.online) "Ingen kontakt med GitHub" else "Ingen internett"
                }
            }.getOrNull()
    }

    fun current(context: Context): Int =
        runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
        }.getOrDefault(0)

    /** Har T'OS lov til å installere apper? (Android 8+: «Installer ukjente apper».) */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    /** Laster ned og starter installasjon. Returnerer null når installasjonsbildet er åpnet, ellers en feilmelding. */
    suspend fun downloadAndInstall(context: Context, onProgress: (Int) -> Unit): String? =
        withContext(Dispatchers.IO) {
            if (!canInstall(context)) {
                withContext(Dispatchers.Main) { openInstallPermission(context) }
                return@withContext "Gi T'OS lov til å installere apper, og trykk igjen"
            }
            val dir = File(context.cacheDir, "apk").apply { mkdirs() }
            val file = File(dir, "bilstereo.apk")
            runCatching {
                file.delete()
                val conn = open("$BASE/bilstereo.apk")
                if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                var done = 0L
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var read: Int; var lastPct = -1
                        while (input.read(buf).also { read = it } > 0) {
                            out.write(buf, 0, read); done += read
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; withContext(Dispatchers.Main) { onProgress(pct) } }   // bare når tallet endres
                            }
                        }
                    }
                }
                // Hel fil? (Avbrudd eller innloggingsside på Wi-Fi gir halv/feil fil)
                if (total > 0 && done != total) error("ufullstendig")
                if (done < 1_000_000) error("for liten")
                // Samme app og samme signatur? Ellers sier Android bare «App ikke installert».
                apkProblem(context, file)?.let { msg -> file.delete(); return@withContext msg }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                withContext(Dispatchers.Main) { context.startActivity(intent) }
            }.fold(onSuccess = { null }, onFailure = {
                file.delete()
                if (Hub.online) "Nedlastingen ble avbrutt – prøv igjen" else "Ingen internett"
            })
        }

    /**
     * Sjekker den nedlastede fila før installasjon: riktig pakke, nyere versjon og samme nøkkel
     * som T'OS på stereoen. Returnerer en forklaring hvis den ikke kan installeres, ellers null.
     */
    private fun apkProblem(context: Context, file: File): String? = runCatching {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                    else @Suppress("DEPRECATION") android.content.pm.PackageManager.GET_SIGNATURES
        val apk = pm.getPackageArchiveInfo(file.path, flags) ?: return "Fila er ødelagt – prøv igjen"
        if (apk.packageName != context.packageName) return "Feil app i nedlastingen"
        val apkCode = if (Build.VERSION.SDK_INT >= 28) apk.longVersionCode.toInt() else @Suppress("DEPRECATION") apk.versionCode
        if (apkCode <= current(context)) return "Du har allerede nyeste versjon"
        val mine = pm.getPackageInfo(context.packageName, flags)
        if (!sameSigner(signers(mine), signers(apk)))
            return "Ny versjon har en annen nøkkel – installer den én gang via Drive (avinstaller T'OS først)"
        null
    }.getOrNull()   // klarer vi ikke å sjekke, lar vi Android avgjøre

    @Suppress("DEPRECATION")
    private fun signers(pi: android.content.pm.PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) pi.signingInfo?.apkContentsSigners else pi.signatures
        return sigs.orEmpty().map { it.toCharsString() }.toSet()
    }

    /** Ren logikk (testbar): tomme lister = ukjent, da godtar vi. */
    internal fun sameSigner(a: Set<String>, b: Set<String>): Boolean = a.isEmpty() || b.isEmpty() || a == b

    /**
     * Åpner en adresse og følger videresendinger selv (github.com → objects.githubusercontent.com).
     * [method] = "GET"/"HEAD", [headers] f.eks. Range for å fortsette en nedlasting.
     */
    internal fun open(url: String, method: String = "GET", headers: Map<String, String> = emptyMap()): HttpURLConnection {
        var u = URL(url)
        repeat(6) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.requestMethod = method
            c.connectTimeout = 10_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "TOS-BilstereoLauncher")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: error("videresending uten adresse")
                u = URL(u, loc)                      // tåler også relative adresser
                c.disconnect()
            } else return c
        }
        error("For mange videresendinger")
    }
}
