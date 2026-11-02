package no.bil.launcher

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sjekker siste bygg på GitHub ("latest"-release), laster ned APK og starter installasjon.
 * Krever at repoet er offentlig. Versjonen sammenlignes på versionCode via version.json.
 */
object Updater {
    const val OWNER = "Tormod22"
    const val REPO = "bilstereo-launcher"
    private const val BASE = "https://github.com/$OWNER/$REPO/releases/download/latest"

    data class Info(val versionCode: Int, val versionName: String)

    suspend fun check(): Info? = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject(open("$BASE/version.json").inputStream.bufferedReader().use { it.readText() })
            Info(json.getInt("versionCode"), json.getString("versionName"))
        }.getOrNull()
    }

    fun current(context: Context): Int =
        runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
        }.getOrDefault(0)

    suspend fun downloadAndInstall(context: Context, onProgress: (Int) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "apk").apply { mkdirs() }
                val file = File(dir, "bilstereo.apk")
                val conn = open("$BASE/bilstereo.apk")
                val total = conn.contentLength
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var read: Int; var done = 0L
                        while (input.read(buf).also { read = it } > 0) {
                            out.write(buf, 0, read); done += read
                            if (total > 0) onProgress((done * 100 / total).toInt())
                        }
                    }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }

    internal fun open(url: String): HttpURLConnection {
        var u = URL(url)
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 8000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "BilstereoLauncher")
            val code = c.responseCode
            if (code in 300..399) { u = URL(c.getHeaderField("Location")); c.disconnect() }
            else return c
        }
        throw IllegalStateException("For mange videresendinger")
    }
}
