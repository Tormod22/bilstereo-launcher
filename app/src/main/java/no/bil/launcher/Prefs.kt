package no.bil.launcher

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Starter før alt annet: innstillinger, feillogg og bakgrunnsdata (GPS, nett, osv.). */
class TosApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        CrashLog.install(this)
        Hub.start(this)
    }
}

/**
 * Alle T'OS-innstillinger på ett sted. Å lese en verdi inne i Compose gjør at skjermen
 * tegnes på nytt når verdien endres (via [version]).
 */
object Prefs {
    private lateinit var sp: SharedPreferences
    var version by mutableIntStateOf(0)
        private set

    fun init(c: Context) { sp = c.applicationContext.getSharedPreferences("tos", Context.MODE_PRIVATE) }

    fun str(k: String, d: String = ""): String { version; return sp.getString(k, d) ?: d }
    fun bool(k: String, d: Boolean = false): Boolean { version; return sp.getBoolean(k, d) }
    fun int(k: String, d: Int = 0): Int { version; return sp.getInt(k, d) }
    fun float(k: String, d: Float = 0f): Float { version; return sp.getFloat(k, d) }
    fun long(k: String, d: Long = 0L): Long { version; return sp.getLong(k, d) }

    fun set(k: String, v: Any?) {
        sp.edit().apply {
            when (v) {
                null -> remove(k)
                is String -> putString(k, v)
                is Boolean -> putBoolean(k, v)
                is Int -> putInt(k, v)
                is Float -> putFloat(k, v)
                is Long -> putLong(k, v)
            }
        }.apply()
        version++
    }

    /** Lagre uten å tegne skjermen på nytt (brukes for ting som skrives ofte, som posisjon). */
    fun setQuiet(k: String, v: Any) {
        sp.edit().apply {
            when (v) { is String -> putString(k, v); is Float -> putFloat(k, v); is Long -> putLong(k, v); is Int -> putInt(k, v) }
        }.apply()
    }

    /* ---------- Sikkerhetskopi (lagres som én fil, f.eks. i Google Drive) ---------- */

    fun exportJson(c: Context): String {
        val root = JSONObject()
        for (name in listOf("tos", "knapper")) {
            val o = JSONObject()
            c.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                o.put(k, JSONObject().put("t", v?.javaClass?.simpleName ?: "null").put("v", v))
            }
            root.put(name, o)
        }
        root.put("laget", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
        return root.toString(2)
    }

    fun importJson(c: Context, text: String): Boolean = runCatching {
        val root = JSONObject(text)
        for (name in listOf("tos", "knapper")) {
            val o = root.optJSONObject(name) ?: continue
            val e = c.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            o.keys().forEach { k ->
                val item = o.getJSONObject(k)
                when (item.getString("t")) {
                    "String" -> e.putString(k, item.getString("v"))
                    "Boolean" -> e.putBoolean(k, item.getBoolean("v"))
                    "Integer" -> e.putInt(k, item.getInt("v"))
                    "Float" -> e.putFloat(k, item.getDouble("v").toFloat())
                    "Long" -> e.putLong(k, item.getLong("v"))
                }
            }
            e.apply()
        }
        version++
        true
    }.getOrDefault(false)
}

/* ---------- Feillogg: krasj lagres, og kan deles fra Innstillinger ---------- */

object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "krasj.txt")

    fun install(c: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                file(c).writeText("${Date()}\n${t.name}\n$sw")
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** Tekst med versjon, siste krasj og de siste linjene i loggen til T'OS. */
    fun report(c: Context): String {
        val crash = file(c).takeIf { it.exists() }?.readText() ?: "Ingen krasj lagret."
        val log = runCatching {
            Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "300", "--pid", android.os.Process.myPid().toString()))
                .inputStream.bufferedReader().readText()
        }.getOrDefault("(fikk ikke lest loggen)")
        val pi = runCatching { c.packageManager.getPackageInfo(c.packageName, 0) }.getOrNull()
        return buildString {
            appendLine("T'OS feilrapport")
            appendLine("Versjon: ${pi?.versionName} (${Updater.current(c)})")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Enhet: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / ${android.os.Build.HARDWARE}")
            appendLine(); appendLine("--- Siste krasj ---"); appendLine(crash)
            appendLine(); appendLine("--- Logg ---"); append(log.takeLast(20000))
        }
    }
}
