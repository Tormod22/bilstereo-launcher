package no.bil.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sjekker siste bygg på GitHub («latest»-release), laster ned APK og installerer den inne i T'OS.
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

    /**
     * Laster ned og installerer. Returnerer teksten som skal vises på knappen, eller null når Androids
     * eget installasjonsbilde er åpnet (reserveløsningen).
     *
     * Vanligvis installeres oppdateringen inne i T'OS (PackageInstaller-økt), uten Androids installasjonsbilde.
     * Første gang må det trykkes «Installer» én gang (T'OS er ikke «installert av» T'OS ennå). Etterpå er T'OS
     * selv registrert som installatør, og Android 12+ lar da oppdateringen gå helt av seg selv.
     */
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
                val newCode = apkVersionCode(context, file)
                // 1) Inne i T'OS (uten Androids installasjonsbilde)
                if (installSession(context, file)) {
                    Prefs.setQuiet("upd_pending", newCode)
                    Prefs.setQuiet("upd_pending_at", System.currentTimeMillis())
                    return@withContext "Installerer … T'OS starter på nytt av seg selv"
                }
                // 2) Reserve: Androids eget installasjonsbilde
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
     * Installerer [file] med Androids PackageInstaller-økt. Svaret kommer til [UpdateReceiver].
     * Returnerer false hvis økten ikke kunne lages (da brukes Androids installasjonsbilde i stedet).
     */
    private fun installSession(context: Context, file: File): Boolean = runCatching {
        val installer = context.packageManager.packageInstaller
        // Rydd bort gamle, uferdige økter (f.eks. der «Installer» aldri ble trykket)
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // Android 12+: ingen spørsmål når T'OS er registrert som den som installerte T'OS
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { s ->
                s.openWrite("tos.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, 256 * 1024) }
                    s.fsync(out)
                }
                val intent = Intent(context, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_STATUS)
                // Android fyller inn svaret i intenten, derfor «mutable» på Android 12+
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
        true
    }.getOrDefault(false)

    /** Svar fra installasjonen (fra [UpdateReceiver], hovedtråden). */
    internal fun onInstallStatus(context: Context, i: Intent) {
        val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Første gang: Android vil ha ett «Installer»-trykk (T'OS er ikke registrert som installatør ennå)
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                val ok = confirm != null && runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                show(if (ok) "Trykk «Installer» én gang – neste gang går det av seg selv" else installStatusText(PackageInstaller.STATUS_FAILURE, null), long = true)
            }
            PackageInstaller.STATUS_SUCCESS -> afterUpdate(context)
            else -> {
                Prefs.set("upd_pending", null); Prefs.set("upd_pending_at", null)
                show(installStatusText(status, i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)), long = true)
            }
        }
    }

    /** T'OS er byttet ut med ny versjon (MY_PACKAGE_REPLACED eller STATUS_SUCCESS). Åpner T'OS igjen. */
    internal fun afterUpdate(context: Context) {
        // Bare når oppdateringen ble startet fra T'OS nå nettopp (ikke når noen installerer med PC/Drive)
        val ours = System.currentTimeMillis() - Prefs.long("upd_pending_at", 0L) < 30 * 60_000L
        announceIfUpdated(context, delayMs = 2_500)
        if (!ours) return
        Prefs.set("upd_pending_at", null)
        // T'OS er startskjermen, så Android viser den uansett igjen. Prøv likevel å åpne den med en gang
        // (lov når knappestyringen eller «vis over andre apper» er slått på).
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
    }

    /** Ved oppstart (fra [Hub.start]): var det en oppdatering som nå er ferdig? Da vises «Oppdatert til …». */
    fun announceIfUpdated(context: Context, delayMs: Long = 3_000) {
        val target = Prefs.int("upd_pending", 0)
        if (target <= 0) return
        when {
            current(context) >= target -> {
                Prefs.set("upd_pending", null)
                Hub.scope.launch(Dispatchers.IO) { File(context.cacheDir, "apk").deleteRecursively() }     // trengs ikke lenger (i bakgrunnen)
                val name = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
                Hub.scope.launch { delay(delayMs); show("Oppdatert til T'OS $name", long = true) }
            }
            // Ble aldri ferdig (avbrutt e.l.) – glem det etter et døgn
            System.currentTimeMillis() - Prefs.long("upd_pending_at", 0L) > 86_400_000L -> {
                Prefs.set("upd_pending", null); Prefs.set("upd_pending_at", null)
            }
        }
    }

    /** Melding både på oppdateringsknappen og som T'OS-melding. */
    private fun show(text: String, long: Boolean) {
        Notify.show(text, long)
        Hub.scope.launch { delay(300); UpdateState.text = text }   // etter at knappen har skrevet sitt
    }

    /** Svarkoden fra installasjonen i vanlige ord (ren logikk, testes i DataLogicTest3). */
    internal fun installStatusText(status: Int, systemMessage: String?): String = when (status) {
        PackageInstaller.STATUS_SUCCESS -> "Oppdatert"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Oppdateringen ble avbrutt"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Stereoen stoppet oppdateringen (Play Protect?). Slå av Play Protect en liten stund og prøv igjen"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "Kan ikke oppdatere: den nye versjonen har en annen nøkkel enn T'OS på stereoen"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Den nye versjonen passer ikke denne stereoen"
        PackageInstaller.STATUS_FAILURE_INVALID -> "Nedlastingen var ødelagt – prøv igjen"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Ikke nok plass på stereoen – tøm under Innstillinger → Lagring"
        else -> "Oppdateringen feilet" + (systemMessage?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: " – prøv igjen")
    }

    private fun apkVersionCode(context: Context, file: File): Int = runCatching {
        val apk = context.packageManager.getPackageArchiveInfo(file.path, 0) ?: return 0
        if (Build.VERSION.SDK_INT >= 28) apk.longVersionCode.toInt() else @Suppress("DEPRECATION") apk.versionCode
    }.getOrDefault(0)

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

/**
 * Får svaret fra installasjonen ([Updater.installSession]) og beskjed når T'OS er byttet ut med ny versjon.
 * Ikke eksportert: bare Android selv (og T'OS) kan sende hit.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STATUS -> Updater.onInstallStatus(context, intent)
            Intent.ACTION_MY_PACKAGE_REPLACED -> Updater.afterUpdate(context)
        }
    }

    companion object { const val ACTION_STATUS = "no.bil.launcher.INSTALL_STATUS" }
}
