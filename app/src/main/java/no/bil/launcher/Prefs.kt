package no.bil.launcher

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlinx.coroutines.launch
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
        SafeMode.check(this)
        // Full kontroll: krasjer T'OS i en løkke, skal stereoen aldri stå uten startskjerm (Owner.escape) – før resten starter
        if (OwnerLogic.shouldEscape(SafeMode.recentCrashes))
            runCatching { Owner.escape(this, "${SafeMode.recentCrashes} krasj på 3 min, versjon ${Updater.current(this)}") }
        // Vakthunder for FYT: lysstyrke som er (nesten) svart rettes før noe annet skjer (FytMain.kt)
        runCatching { FytMain.sanitizeOnStart() }
        Hub.start(this)
        runCatching { FytSafety.noticeOnStart() }
        // «Full kontroll» (bare når T'OS er enhetseier) og knappestyring som holdes på (bare med PC-tillatelse).
        // Ved oppstart i bakgrunnen: som enhetseier er det 15–20 kall til Android (tillatelser, skjulte apper …), og de
        // forsinket første skjermbilde ved hver oppstart. En bryter som endres, virker fortsatt med en gang (sjeldent trykk).
        // Låsen hindrer at de to går samtidig.
        Prefs.onOwnerToggle = { synchronized(ownerLock) { Owner.apply(this) } }
        Hub.scope.launch(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.CoroutineName("Full kontroll")) {
            runCatching { synchronized(ownerLock) { Owner.apply(this@TosApp) } }
        }
        // «Ta over»: ble Google Play-tjenester stående av (avbrutt kjøring)? Slå dem på igjen i bakgrunnen (Takeover.kt)
        runCatching { Takeover.healOnStart(this) }
        if (runCatching { Owner.escaped }.getOrDefault(false)) Hub.scope.launch {
            kotlinx.coroutines.delay(4_000)
            Notify.show("T'OS krasjet flere ganger, så stereoens egen startskjerm brukes nå. Se Innstillinger → Om stereoen.", true)
        }
    }

    private val ownerLock = Any()
}

/**
 * Alle T'OS-innstillinger på ett sted. Å lese en verdi inne i Compose gjør at skjermen
 * tegnes på nytt når verdien endres (via [version]).
 */
object Prefs {
    private lateinit var sp: SharedPreferences
    var version by mutableIntStateOf(0)
        private set

    /** Kalles når en «owner_»-bryter endres («Full kontroll», satt av [TosApp]). */
    var onOwnerToggle: (() -> Unit)? = null
    fun init(c: Context) {
        sp = c.applicationContext.getSharedPreferences("tos", Context.MODE_PRIVATE)
        Store.init(c)
    }

    // Har en verdi feil type (f.eks. fra en gammel sikkerhetskopi), brukes standardverdien i stedet for å krasje
    private inline fun <T> safe(d: T, get: () -> T): T = try { get() } catch (_: ClassCastException) { d }
    fun str(k: String, d: String = ""): String { version; return safe(d) { sp.getString(k, d) ?: d } }
    fun bool(k: String, d: Boolean = false): Boolean { version; return safe(d) { sp.getBoolean(k, d) } }
    fun int(k: String, d: Int = 0): Int { version; return safe(d) { sp.getInt(k, d) } }
    fun float(k: String, d: Float = 0f): Float { version; return safe(d) { sp.getFloat(k, d) } }
    fun long(k: String, d: Long = 0L): Long { version; return safe(d) { sp.getLong(k, d) } }

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
        bump()
        // Hovedbryteren for FYT: koble til/fra med en gang, og sett bryterne tilbake (FytSafety)
        if (k == FytSafetyLogic.MASTER && v is Boolean) runCatching { FytSafety.onMaster(v) }
        // «Full kontroll» (Owner.kt): bryterne virker med en gang
        if (k.startsWith("owner_") && v is Boolean) runCatching { onOwnerToggle?.invoke() }
    }

    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    /** Skjermene tegnes på nytt. Skjer alltid på hovedtråden (lagring fra en bakgrunnstråd kunne ellers forstyrre tegningen). */
    private fun bump() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) version++ else main.post { version++ }
    }

    /** Lagre uten å tegne skjermen på nytt (brukes for ting som skrives ofte, som posisjon). */
    fun setQuiet(k: String, v: Any) {
        // Ikke skriv til disken når verdien er den samme (kalles ofte)
        val same = safe(false) {
            when (v) {
                is String -> sp.getString(k, null) == v
                is Float -> sp.contains(k) && sp.getFloat(k, 0f) == v
                is Long -> sp.contains(k) && sp.getLong(k, 0L) == v
                is Int -> sp.contains(k) && sp.getInt(k, 0) == v
                is Boolean -> sp.contains(k) && sp.getBoolean(k, false) == v
                else -> false
            }
        }
        if (same) return
        sp.edit().apply {
            when (v) { is String -> putString(k, v); is Float -> putFloat(k, v); is Long -> putLong(k, v); is Int -> putInt(k, v); is Boolean -> putBoolean(k, v) }
        }.apply()
    }

    /**
     * Flere verdier i ÉN skriving til disken (uten å tegne skjermen på nytt). Hver [setQuiet] skriver hele
     * innstillingsfila på nytt; turen lagret fem verdier hvert 20. sekund = fem skrivinger.
     */
    fun setQuietAll(vararg kv: Pair<String, Any>) {
        val e = sp.edit()
        for ((k, v) in kv) when (v) { is String -> e.putString(k, v); is Float -> e.putFloat(k, v); is Long -> e.putLong(k, v); is Int -> e.putInt(k, v); is Boolean -> e.putBoolean(k, v) }
        e.apply()
    }

    /** Leser og fjerner en verdi (uten å tegne skjermen på nytt) – til flytting av gamle verdier. null = fantes ikke. */
    internal fun takeQuiet(k: String): String? {
        if (!sp.contains(k)) return null
        val v = safe(null as String?) { sp.getString(k, null) }
        sp.edit().remove(k).apply()
        return v
    }

    /* ---------- Sikkerhetskopi (lagres som én fil, f.eks. i Google Drive) ---------- */

    fun exportJson(c: Context): String {
        val root = JSONObject()
        for (name in listOf("tos", "knapper")) {
            val o = JSONObject()
            c.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                // Ugyldige tall (NaN/uendelig) kan ikke lagres i fila – hopp over dem i stedet for at hele kopien feiler
                if (!backupExportable(v)) return@forEach
                runCatching { o.put(k, JSONObject().put("t", v?.javaClass?.simpleName ?: "null").put("v", v)) }
            }
            root.put(name, o)
        }
        root.put("laget", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
        root.put("versjon", runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?")
        return root.toString(2)
    }

    /**
     * Hent innstillinger fra sikkerhetskopi. Hele fila sjekkes først – er noe ødelagt, endres ingenting.
     * Verdier som har en annen type enn det som er lagret nå (fra en eldre T'OS), hoppes over.
     */
    fun importJson(c: Context, text: String): Boolean {
        val parsed = runCatching {
            val root = JSONObject(text)
            val out = mutableMapOf<String, Map<String, Any>>()
            for (name in listOf("tos", "knapper")) {
                val o = root.optJSONObject(name) ?: continue
                val m = mutableMapOf<String, Any>()
                for (k in o.keys()) {
                    val item = o.getJSONObject(k)
                    val t = item.getString("t")
                    if (t !in backupTypes) continue                  // ukjent type (f.eks. tom verdi): hopp over
                    m[k] = backupValue(t, item.opt("v")) ?: return false
                }
                out[name] = m
            }
            out
        }.getOrNull() ?: return false
        if (parsed.isEmpty()) return false
        val ok = runCatching {
            for ((name, values) in parsed) {
                val p = c.getSharedPreferences(name, Context.MODE_PRIVATE)
                val existing = p.all
                val e = p.edit()
                for ((k, v) in values) {
                    if (!backupTypeFits(existing[k], v)) continue
                    when (v) {
                        is String -> e.putString(k, v)
                        is Boolean -> e.putBoolean(k, v)
                        is Int -> e.putInt(k, v)
                        is Float -> e.putFloat(k, v)
                        is Long -> e.putLong(k, v)
                    }
                }
                e.apply()
            }
        }.isSuccess
        bump()
        Buttons.reload()
        return ok
    }
}

/** Typene sikkerhetskopien kan inneholde (navnet Java gir dem i [Prefs.exportJson]). */
internal val backupTypes = setOf("String", "Boolean", "Integer", "Float", "Long")

/** Gjør en verdi fra sikkerhetskopien om til riktig type. null = ugyldig verdi. Testes i ServiceLogicTest. */
internal fun backupValue(t: String, v: Any?): Any? = when (t) {
    "String" -> v as? String
    "Boolean" -> v as? Boolean
    "Integer" -> (v as? Number)?.takeIf { it !is Double && it !is Float || it.toDouble() % 1.0 == 0.0 }?.toLong()
        ?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    "Long" -> (v as? Number)?.takeIf { it !is Double && it !is Float || it.toDouble() % 1.0 == 0.0 }?.toLong()
    "Float" -> (v as? Number)?.toFloat()?.takeIf { !it.isNaN() }
    else -> null
}

/** Kan verdien lagres i sikkerhetskopien? (Sett med tekster o.l. og NaN/uendelig kan ikke.) Testes i ServiceLogicTest2. */
internal fun backupExportable(v: Any?): Boolean = when (v) {
    is Float -> v.isFinite()
    is Double -> v.isFinite()
    is String, is Boolean, is Int, is Long -> true
    else -> false
}

/** Ny verdi kan bare erstatte en lagret verdi av samme type (eller lagres der ingenting finnes). */
internal fun backupTypeFits(existing: Any?, new: Any): Boolean = existing == null || existing.javaClass == new.javaClass

/* ---------- Feillogg: krasj lagres, og kan deles fra Innstillinger ---------- */

/**
 * Ny krasj øverst, de forrige under, men fila holdes liten (høyst [max] tegn – en enkelt lang feil kortes ned).
 * Testes i ServiceLogicTest2.
 */
internal fun crashLogAppend(old: String, entry: String, max: Int = 30_000): String {
    val sep = "\n\n===== Eldre krasj =====\n"
    val head = entry.take(max / 2)
    return if (old.isBlank()) head else (head + sep + old).take(max)
}

/**
 * Store lister som skrives ofte, i en egen fil: telefonboken og anropslista fra stereoens Bluetooth (FytPhone).
 * Før lå de i hovedfila med alle innstillingene – med en telefonbok på flere hundre navn ble den fila stor, og den
 * leses inn før første skjermbilde ved HVER oppstart og skrives helt på nytt ved hver lagring (turen hvert 20. s).
 * Flyttes automatisk første gang verdien leses. Er ikke med i sikkerhetskopien (telefonboken hentes fra telefonen).
 */
object Store {
    @Volatile private var sp: SharedPreferences? = null
    fun init(c: Context) { sp = c.applicationContext.getSharedPreferences("tos_lager", Context.MODE_PRIVATE) }

    fun str(k: String): String {
        val s = sp ?: return ""
        s.getString(k, null)?.let { return it }
        // Gammel plass (før 5.14): flytt hit én gang
        val old = runCatching { Prefs.takeQuiet(k) }.getOrNull() ?: return ""
        s.edit().putString(k, old).apply()
        return old
    }

    fun set(k: String, v: String) { sp?.edit()?.putString(k, v)?.apply() }
}

/** Teksten som skrives når T'OS fanget en feil i en bakgrunnsjobb og fortsatte (ren logikk, testes). */
internal fun caughtEntry(at: String, where: String, stack: String): String =
    "$at\nFANGET i $where – T'OS fortsatte (ikke en krasj)\n$stack"

object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "krasj.txt")

    /** App-konteksten (svak referanse), så [caught] kan skrive uten å få en Context. */
    @Volatile private var appRef: java.lang.ref.WeakReference<Context>? = null

    /** Antall feil T'OS har fanget i bakgrunnen siden start (til oppsettrapporten). */
    @Volatile var caughtCount = 0; private set

    /**
     * For bakgrunnsjobbene (Hub.scope o.l.): en feil i én jobb skrives i krasj-loggen, men tar ikke ned hele
     * startskjermen. Før krasjet T'OS (svart skjerm et øyeblikk, og etter tre ganger sikker modus) av f.eks. en feil
     * i versjonssjekken eller en FYT-melding. Feil i FYT-koden telles fortsatt for FytSafety (slår av FYT ved neste start).
     */
    val handler = kotlinx.coroutines.CoroutineExceptionHandler { ctx, e ->
        caught(ctx[kotlinx.coroutines.CoroutineName]?.name ?: "bakgrunnsjobb", e)
    }

    fun caught(where: String, e: Throwable) {
        caughtCount++
        val c = appRef?.get() ?: return
        val sw = StringWriter()
        runCatching { e.printStackTrace(PrintWriter(sw)) }
        runCatching { if (FytSafetyLogic.isFytCrash(sw.toString())) SafeMode.recordFytError(c) }
        runCatching {
            val old = file(c).takeIf { it.exists() }?.readText().orEmpty()
            file(c).writeText(crashLogAppend(old, caughtEntry(Date().toString(), where, sw.toString())))
        }
    }

    fun install(c: Context) {
        appRef = java.lang.ref.WeakReference(c.applicationContext)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val sw = StringWriter()
            runCatching { e.printStackTrace(PrintWriter(sw)) }
            // Telleren for sikker modus først – den må lagres selv om loggfila ikke kan skrives.
            // Er det FYT-koden som krasjet, telles det også for seg (FytSafety slår da av FYT-funksjonene).
            runCatching { SafeMode.recordCrash(c, fyt = FytSafetyLogic.isFytCrash(sw.toString())) }
            runCatching {
                val old = file(c).takeIf { it.exists() }?.readText().orEmpty()
                file(c).writeText(crashLogAppend(old, "${Date()}\n${t.name}\n$sw"))
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

/* ---------- Sikker modus: hindrer at en krasj-løkke gjør stereoen ubrukelig ---------- */

/**
 * T'OS er startskjermen – krasjer den hver gang den starter, ser man bare svart skjerm.
 * Tre krasj i løpet av 3 minutter → neste start skjer i sikker modus: uten kart uten nett, uten eget bilbilde og
 * uten oppstartsmusikk (det som er mest sannsynlig å krasje). Forsiden sier fra, og du kan sende feilrapport.
 * Etter 2 minutter uten krasj nullstilles telleren. Sikker modus slås av med én knapp.
 */
/**
 * Krasjet det for under 3 minutter siden? Stereoens klokke kan hoppe (stilles fra GPS/nett etter oppstart):
 * en krasj «i framtiden» teller ikke, ellers kunne sikker modus slått seg på uten grunn. Testes i ServiceLogicTest2.
 */
internal fun recentCrash(now: Long, at: Long): Boolean = at <= now && now - at < 180_000

object SafeMode {
    private fun sp(c: Context) = c.getSharedPreferences("sikker", Context.MODE_PRIVATE)
    var on by androidx.compose.runtime.mutableStateOf(false)
        private set
    /** Krasj de siste 3 minuttene da T'OS startet (brukes av nødstoppen i «Full kontroll», Owner.escape). */
    @Volatile var recentCrashes = 0
        private set

    @android.annotation.SuppressLint("ApplySharedPref")     // med vilje: prosessen dør rett etter
    fun recordCrash(c: Context, fyt: Boolean = false) {
        val now = System.currentTimeMillis()
        val times = (sp(c).getString("t", "") ?: "").split(",").mapNotNull { it.toLongOrNull() }.filter { recentCrash(now, it) } + now
        val old = FytSafetyLogic.parseTimes(sp(c).getString("ft", "")).filter { recentCrash(now, it) }
        val ft = if (fyt) old + now else old
        // commit (ikke apply): prosessen dør rett etter
        sp(c).edit().putString("t", times.joinToString(",")).putString("ft", ft.joinToString(",")).commit()
    }

    /** En feil i FYT-koden som T'OS fanget (ikke en krasj): telles bare for FytSafety, ikke for sikker modus. */
    fun recordFytError(c: Context) {
        val now = System.currentTimeMillis()
        val ft = FytSafetyLogic.parseTimes(sp(c).getString("ft", "")).filter { recentCrash(now, it) } + now
        sp(c).edit().putString("ft", ft.joinToString(",")).apply()
    }

    fun check(c: Context) {
        val now = System.currentTimeMillis()
        val recent = (sp(c).getString("t", "") ?: "").split(",").mapNotNull { it.toLongOrNull() }.count { recentCrash(now, it) }
        recentCrashes = recent
        on = sp(c).getBoolean("on", false) || recent >= 3
        if (on) sp(c).edit().putBoolean("on", true).apply()
        // FYT-koden krasjet 2 ganger på 3 min: slå av stereo-funksjonene før noe kobler til stereoen (FytSafety)
        val ft = FytSafetyLogic.parseTimes(sp(c).getString("ft", ""))
        if (FytSafetyLogic.shouldTrip(ft, now)) {
            runCatching { FytSafety.trip("${ft.size} krasj i FYT-koden") }
            sp(c).edit().remove("ft").apply()
        }
        // Har T'OS gått 2 minutter uten krasj, er løkka brutt
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ sp(c).edit().remove("t").remove("ft").apply() }, 120_000)
    }

    fun turnOff(c: Context) { sp(c).edit().putBoolean("on", false).remove("t").apply(); on = false }
}

/* ---------- Oppsettrapport: alt Claude trenger for å tilpasse T'OS til akkurat denne stereoen ---------- */

object SetupReport {
    fun build(c: Context): String = buildString {
        val pm = c.packageManager
        appendLine("T'OS oppsettrapport")
        appendLine("Versjon: ${runCatching { pm.getPackageInfo(c.packageName, 0).versionName }.getOrNull()} (${Updater.current(c)})")
        appendLine("Stereo: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / ${android.os.Build.HARDWARE} / ${android.os.Build.BOARD}")
        appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        appendLine("Skjerm: ${c.resources.displayMetrics.widthPixels}×${c.resources.displayMetrics.heightPixels}, tetthet ${c.resources.displayMetrics.density}")
        appendLine("Sikker modus: ${SafeMode.on}")
        appendLine()
        appendLine("--- Oppstart (siste ${StartTimingLogic.KEEP}, tid fra T'OS startet) ---")
        appendLine(runCatching { StartTiming.report(c) }.getOrDefault("(kunne ikke leses)"))
        appendLine()
        appendLine("--- Tillatelser ---")
        appendLine("Knappestyring: ${Buttons.serviceEnabled(c)}")
        appendLine("Varseltilgang: ${MediaListener.enabled(c)}")
        appendLine("Vis over andre apper: ${android.provider.Settings.canDrawOverlays(c)}")
        appendLine("Tilgang til appbruk (Siste apper): ${RecentApps.hasUsageAccess(c)}")
        appendLine("Endre systeminnstillinger: ${android.provider.Settings.System.canWrite(c)}")
        appendLine("Installere apper: ${runCatching { pm.canRequestPackageInstalls() }.getOrDefault(false)}")
        appendLine("Posisjon: ${c.hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)}")
        val home = pm.resolveActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0)?.activityInfo
        appendLine("Startskjerm nå: ${home?.packageName}/${home?.name}")
        appendLine("Alle startskjermer: " + pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0)
            .joinToString { it.activityInfo.packageName + "/" + it.activityInfo.name })
        // Skjermene i stereoens startskjerm-app(er): brukes til å kjenne igjen app-liste/hovedmeny som T'OS skal erstatte
        AppRepository.homePackages(c).forEach { hp ->
            val acts = runCatching { pm.getPackageInfo(hp, android.content.pm.PackageManager.GET_ACTIVITIES).activities }.getOrNull()
            appendLine("  $hp: " + (acts?.joinToString { it.name.removePrefix(hp) } ?: "?"))
        }
        appendLine()
        appendLine("--- Knapper ---")
        PanelKey.entries.forEach { k ->
            appendLine("${k.label}: kode=${Buttons.code(c, k)} pakke=${Buttons.pkg(c, k)} kort=${Buttons.action(c, k)} langt=${Buttons.longAction(c, k)}")
        }
        appendLine()
        appendLine("--- Apper T'OS bruker ---")
        listOf("Mus", "Car", "Rad", "Tel", "Blu", "Rygg", "Nav").forEach { p ->
            appendLine("$p: ${runCatching { AppRepository.tilePackage(c, Tiles.byPrefix(p)) }.getOrNull()}")
        }
        appendLine("Musikk som spiller nå: ${NowPlaying.pkg} (${NowPlaying.title})")
        appendLine("Radio-MCU (szchoiceway): ${HwRadio.hasMcu(c)}")
        appendLine("FM-radio: app=${HwRadio.radioPackage(c)} på=${HwRadio.wanted} motor=${HwRadio.enginePkg} (motor brukt: ${HwRadio.engineAt != 0L})")
        val rp = HwRadio.radioPackage(c)
        if (rp != null) {
            appendLine("Radio-appens mediatjeneste: ${MediaConnect.browserService(c, rp)}, medieknapp: ${MediaConnect.buttonReceivers(c, rp)}")
            appendLine("Radio-appens tjenester: " + runCatching { pm.queryIntentServices(android.content.Intent().setPackage(rp), 0) }
                .getOrDefault(emptyList()).joinToString { "${it.serviceInfo.name}${if (it.serviceInfo.exported) "" else " (lukket)"}" })
        }
        appendLine("Bluetooth-musikk: ${runCatching { Media.bluetoothPackage(c) }.getOrNull()}")
        appendLine("Lydkilder: " + runCatching { Media.sources(c).joinToString { "${it.label}=${it.pkg}${if (it.playing) " (spiller)" else ""}" } }.getOrDefault("?"))
        val am = c.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        appendLine("Volum: musikk ${runCatching { am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) }.getOrNull()}/" +
            "${runCatching { am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) }.getOrNull()}, fast volum=${runCatching { am.isVolumeFixed }.getOrNull()}")
        // Alle mediaøkter (hvilken app T'OS styrer når flere spillere er åpne)
        appendLine("Mediaøkter: " + runCatching {
            (c.getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager)
                .getActiveSessions(android.content.ComponentName(c, MediaListener::class.java))
                .joinToString { "${it.packageName}(${it.playbackState?.state})" }
        }.getOrDefault("(krever varseltilgang)"))
        appendLine("Lydmodus: ${runCatching { (c.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).mode }.getOrNull()} (0 = vanlig, 2/3 = samtale)")
        appendLine()
        appendLine("--- Bluetooth og lyd ---")
        append(runCatching { BtLink.report(c) }.getOrElse { "Fikk ikke lest Bluetooth: ${it.javaClass.simpleName}\n" })
        appendLine()
        appendLine("--- GPS ---")
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        appendLine("GPS på: ${runCatching { lm?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) }.getOrNull()}, " +
            "nettverk: ${runCatching { lm?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) }.getOrNull()}")
        appendLine("Kun omtrentlig posisjon: ${Hub.gpsCoarseOnly}, ingen tillatelse: ${Hub.gpsNoPermission}")
        val g = Hub.gps
        appendLine("Siste måling: " + (g?.let { "fart=${it.speed} gammel=${it.stale} har fart=${it.hasSpeed}" } ?: "ingen"))
        appendLine()
        appendLine("--- Bil ---")
        appendLine("OBD2: ${CarBattery.obdStatus}")
        appendLine("Batteri: ${CarBattery.volt?.let { "%.1f V".format(Locale.US, it) } ?: "ukjent"} fra ${CarBattery.source}")
        appendLine()
        appendLine("--- Valg ---")
        // Standardverdi true for de som er på fra start
        val onByDefault = setOf("keep_home", "tos_recents", "tos_shade", "tos_volume", "radio_engine", "hide_stock_radio", "bt_engine")
        listOf("keep_home", "tos_recents", "tos_shade", "carplay_auto", "tos_volume", "radio_engine", "hide_stock_radio", "bt_engine", "show_system_apps")
            .forEach { appendLine("$it: ${Prefs.bool(it, it in onByDefault)}") }
        listOf("music_pkg", "carplay_pkg", "radio_pkg", "phone_pkg", "camera_pkg", "btmusic_pkg", "nav_app", "last_app", "last_player", "recents_seen", "shade_seen")
            .forEach { appendLine("$it: ${Prefs.str(it)}") }
        val pw = c.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        append(runCatching { Owner.report(c) }.getOrDefault(""))
        appendLine("Unntatt batterisparing: ${runCatching { pw?.isIgnoringBatteryOptimizations(c.packageName) }.getOrNull()}")
        appendLine("Fangede feil i bakgrunnen siden start (T'OS fortsatte): ${CrashLog.caughtCount}")
        appendLine("Siste krasj: ${runCatching { File(c.filesDir, "krasj.txt").takeIf { it.exists() }?.useLines { l -> l.take(4).joinToString(" | ") } }.getOrNull() ?: "ingen"}")
        appendLine()
        appendLine("--- Alle installerte pakker ---")
        runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
            .sortedBy { it.packageName }
            .forEach { ai ->
                val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault("")
                val sys = if ((ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) " [system]" else ""
                val start = if (AppRepository.launchIntent(c, ai.packageName) != null) " [kan åpnes]" else ""
                // Versjonen hjelper med å finne riktige kommandoer for stereoens egne apper (radio, CarPlay …)
                val ver = runCatching { pm.getPackageInfo(ai.packageName, 0).versionName }.getOrNull()?.let { " v$it" } ?: ""
                // [skjult]: regnes som stereoens egen app og vises ikke i app-lista (med mindre «Vis systemapper» er på)
                val shell = if (isStockShellApp(ai.packageName, label, AppRepository.isSystemApp(ai), false, c.packageName)) " [skall]" else ""
                appendLine("${ai.packageName} – $label$ver$sys$start$shell")
            }
    }
}
