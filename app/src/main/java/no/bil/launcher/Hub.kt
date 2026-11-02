package no.bil.launcher

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Bakgrunnsdata som hele T'OS deler: posisjon, tur, parkering, fartsgrense, nett,
 * stereoens temperatur, ny versjon og påminnelser. Startes én gang fra [TosApp].
 */
object Hub {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: Context

    var gps by mutableStateOf<Gps?>(null)
    var online by mutableStateOf(true)
    var update by mutableStateOf<Updater.Info?>(null)
    var cpuTemp by mutableStateOf<Int?>(null)
    var memText by mutableStateOf("")

    private var gpsJob: Job? = null

    fun start(c: Context) {
        app = c.applicationContext
        Trip.restore()
        startGps()
        watchNetwork()
        CarBattery.start(app)
        HwRadio.start(app)
        Eq.restore()
        scope.launch { while (true) { Theme.update(gps?.lat ?: Prefs.float("park_lat").toDouble().takeIf { it != 0.0 }, gps?.lon ?: Prefs.float("park_lon").toDouble().takeIf { it != 0.0 }); NightBrightness.apply(app); delay(60_000) } }
        scope.launch { while (true) { readSystem(); delay(10_000) } }
        scope.launch {
            while (true) {
                Updater.check()?.let { update = if (it.versionCode > Updater.current(app)) it else null }
                delay(TimeUnit.HOURS.toMillis(6))
            }
        }
    }

    /** Kalles på nytt når brukeren har gitt posisjonstillatelse. */
    fun startGps() {
        if (!app.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        gpsJob?.cancel()
        gpsJob = scope.launch {
            gpsFlow(app).collect { g ->
                val first = gps == null
                gps = g
                Trip.onFix(g)
                Parking.onFix(g)
                SpeedLimit.onFix(g)
                Accel.onFix(g)
                if (first) Theme.update(g.lat, g.lon)
            }
        }
    }

    private fun watchNetwork() {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun check() = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        online = check()
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) { scope.launch { online = true } }
                override fun onLost(n: Network) { scope.launch { online = check() } }
            })
        }
    }

    private suspend fun readSystem() {
        cpuTemp = withContext(Dispatchers.IO) {
            File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty()
                .mapNotNull { runCatching { File(it, "temp").readText().trim().toInt() }.getOrNull() }
                .map { if (it > 1000) it / 1000 else it }
                .filter { it in 1..150 }
                .maxOrNull()
        }
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val used = (mi.totalMem - mi.availMem) / 1_073_741_824.0
        memText = String.format(Locale("nb", "NO"), "%.1f av %.1f GB", used, mi.totalMem / 1_073_741_824.0)
    }

    /** Kompassretning på norsk. */
    fun compass(bearing: Float): String =
        listOf("N", "NØ", "Ø", "SØ", "S", "SV", "V", "NV")[(((bearing % 360) + 360) % 360 / 45f + 0.5f).toInt() % 8]
}

/* ======================= Turmåler ======================= */

object Trip {
    var meters by mutableDoubleStateOf(0.0)
    var movingSec by mutableLongStateOf(0L)
    var maxKmh by mutableIntStateOf(0)
    var startedAt by mutableLongStateOf(System.currentTimeMillis())
    private var last: Gps? = null
    private var lastTime = 0L
    private var lastSave = 0L

    val avgKmh: Int get() = if (movingSec < 30) 0 else (meters / movingSec * 3.6).toInt()

    /** Ny tur hvis det er mer enn 30 min siden sist T'OS så bilen kjøre. */
    fun restore() {
        val savedAt = Prefs.long("trip_saved")
        if (System.currentTimeMillis() - savedAt < TimeUnit.MINUTES.toMillis(30)) {
            meters = Prefs.float("trip_m").toDouble()
            movingSec = Prefs.long("trip_s")
            maxKmh = Prefs.int("trip_max")
            startedAt = Prefs.long("trip_start", System.currentTimeMillis())
        }
    }

    fun reset() {
        meters = 0.0; movingSec = 0; maxKmh = 0; startedAt = System.currentTimeMillis(); last = null
        save()
    }

    fun onFix(g: Gps) {
        val now = SystemClock.elapsedRealtime()
        val prev = last
        if (prev != null && g.speedKmh >= 3) {
            val r = FloatArray(1)
            Location.distanceBetween(prev.lat, prev.lon, g.lat, g.lon, r)
            val dt = (now - lastTime) / 1000
            if (r[0] < 250 && dt in 1..10) { meters += r[0]; movingSec += dt }
        }
        if (g.speedKmh > maxKmh && g.speedKmh < 250) maxKmh = g.speedKmh
        last = g; lastTime = now
        if (now - lastSave > 20_000) { lastSave = now; save() }
    }

    private fun save() {
        Prefs.setQuiet("trip_m", meters.toFloat()); Prefs.setQuiet("trip_s", movingSec)
        Prefs.setQuiet("trip_max", maxKmh); Prefs.setQuiet("trip_start", startedAt)
        Prefs.setQuiet("trip_saved", System.currentTimeMillis())
    }
}

/* ======================= Parkering ======================= */

/** Husker siste posisjon der bilen sto stille. Vises med QR-kode så iPhonen kan ta den med seg. */
object Parking {
    private var lastSave = 0L
    fun onFix(g: Gps) {
        val now = SystemClock.elapsedRealtime()
        if (g.speedKmh <= 2 && now - lastSave > 15_000) {
            lastSave = now
            Prefs.setQuiet("park_lat", g.lat.toFloat()); Prefs.setQuiet("park_lon", g.lon.toFloat())
            Prefs.setQuiet("park_time", System.currentTimeMillis())
        }
    }
    val lat get() = Prefs.float("park_lat").toDouble()
    val lon get() = Prefs.float("park_lon").toDouble()
    val time get() = Prefs.long("park_time")
    val known get() = lat != 0.0 || lon != 0.0
    fun appleMapsUrl() = "https://maps.apple.com/?ll=${"%.6f".format(Locale.US, lat)},${"%.6f".format(Locale.US, lon)}&q=Bilen"
}

/* ======================= Fartsgrense (OpenStreetMap) ======================= */

object SpeedLimit {
    var limit by mutableStateOf<Int?>(null)
    var over by mutableStateOf(false)
    private var lastQueryPos: Gps? = null
    private var lastQuery = 0L
    private var busy = false
    private var beeped = false

    private val servers = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )

    fun onFix(g: Gps) {
        val l = limit
        over = Prefs.bool("speed_limit", true) && l != null && g.speedKmh > l + 3
        if (over && !beeped && Prefs.bool("speed_beep", false)) {
            beeped = true
            runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60).startTone(ToneGenerator.TONE_PROP_BEEP2, 300) }
        }
        if (!over) beeped = false

        if (!Prefs.bool("speed_limit", true) || busy || g.speedKmh < 5) return
        val now = SystemClock.elapsedRealtime()
        val prev = lastQueryPos
        val moved = prev?.let { FloatArray(1).also { r -> Location.distanceBetween(it.lat, it.lon, g.lat, g.lon, r) }[0] } ?: Float.MAX_VALUE
        if (moved < 120 && now - lastQuery < 25_000) return
        lastQueryPos = g; lastQuery = now; busy = true
        Hub.scope.launch {
            limit = withContext(Dispatchers.IO) { query(g.lat, g.lon) } ?: limit.takeIf { moved < 400 }
            busy = false
        }
    }

    private fun query(lat: Double, lon: Double): Int? {
        val q = "[out:json][timeout:8];way(around:20,$lat,$lon)[highway][maxspeed];out tags 3;"
        for (s in servers) {
            val res = runCatching {
                val c = URL("$s?data=" + URLEncoder.encode(q, "UTF-8")).openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 9000
                c.setRequestProperty("User-Agent", "TOS-BilstereoLauncher/1.0")
                val els = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("elements")
                (0 until els.length()).firstNotNullOfOrNull { parse(els.getJSONObject(it).getJSONObject("tags").optString("maxspeed")) }
            }
            if (res.isSuccess) return res.getOrNull()
        }
        return null
    }

    private fun parse(s: String): Int? = when {
        s.isBlank() -> null
        s.equals("NO:urban", true) -> 50
        s.equals("NO:rural", true) -> 80
        s.equals("NO:motorway", true) -> 110
        else -> s.takeWhile { it.isDigit() }.toIntOrNull()
    }
}

/* ======================= Demp hele stereoen om natten ======================= */

object NightBrightness {
    private var dimmed = false
    fun apply(c: Context) {
        if (!Prefs.bool("dim_system") || !Settings.System.canWrite(c)) return
        val r = c.contentResolver
        runCatching {
            if (Theme.night && !dimmed) {
                Prefs.setQuiet("day_brightness", Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS, 200))
                Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, 35)
                dimmed = true
            } else if (!Theme.night && dimmed) {
                Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, Prefs.int("day_brightness", 200))
                dimmed = false
            }
        }
    }
}

/* ======================= Påminnelser: EU-kontroll, service, dekkskift ======================= */

object Reminders {
    private val fmt = SimpleDateFormat("d.M.yyyy", Locale("nb", "NO"))

    fun parse(s: String): Date? = runCatching { fmt.parse(s.trim()) }.getOrNull()
    fun format(d: Date): String = fmt.format(d)

    data class Due(val key: String, val text: String)

    /** Det som snart skal gjøres (innen 14 dager) eller er på overtid. */
    fun due(): List<Due> {
        val out = mutableListOf<Due>()
        val today = Calendar.getInstance()
        fun daysTo(d: Date) = TimeUnit.MILLISECONDS.toDays(d.time - today.timeInMillis).toInt()
        fun check(key: String, label: String) {
            val d = parse(Prefs.str(key)) ?: return
            val n = daysTo(d)
            when {
                n < 0 -> out += Due(key, "$label: på overtid")
                n == 0 -> out += Due(key, "$label i dag")
                n <= 14 -> out += Due(key, "$label om $n dager")
            }
        }
        check("eu_date", "EU-kontroll")
        check("service_date", "Service")
        if (Prefs.bool("tire_remind", true)) {
            val y = today.get(Calendar.YEAR)
            val m = today.get(Calendar.MONTH); val d = today.get(Calendar.DAY_OF_MONTH)
            val winter = (m == Calendar.OCTOBER && d >= 15) || (m == Calendar.NOVEMBER && d <= 15)
            val summer = (m == Calendar.APRIL && d <= 30) || (m == Calendar.MARCH && d >= 25)
            if (winter && !Prefs.bool("tire_done_${y}_v")) out += Due("tire_${y}_v", "Tid for vinterdekk")
            if (summer && !Prefs.bool("tire_done_${y}_s")) out += Due("tire_${y}_s", "Tid for sommerdekk")
        }
        return out
    }

    /** «Ferdig»: EU-kontroll flyttes 2 år fram, service 1 år, dekk er gjort for sesongen. */
    fun done(key: String) {
        when {
            key == "eu_date" || key == "service_date" -> {
                val c = Calendar.getInstance()
                c.add(Calendar.YEAR, if (key == "eu_date") 2 else 1)
                Prefs.set(key, format(c.time))
            }
            key.startsWith("tire_") -> Prefs.set("tire_done_" + key.removePrefix("tire_"), true)
        }
    }
}

/* ======================= Ved oppstart: musikk og siste app ======================= */

object Startup {
    private var lastRun = 0L

    /** Kjøres når stereoen starter (eller våkner etter å ha vært av en stund). */
    fun run(c: Context) {
        val now = SystemClock.elapsedRealtime()
        if (lastRun != 0L && now - lastRun < 120_000) return
        lastRun = now
        Hub.scope.launch {
            delay(4000)
            if (Prefs.bool("resume_music", true) && !NowPlaying.playing) {
                if (NowPlaying.hasSession) Media.play(c)
                else {
                    val tile = Tiles.byPrefix("Mus")
                    val pkg = Prefs.str("music_pkg").ifBlank { null }
                        ?: tile.candidates.firstOrNull { AppRepository.isInstalled(c, it) }
                    if (pkg != null) {
                        AppRepository.launch(c, pkg)
                        delay(3500)
                        Media.play(c)
                        if (!Prefs.bool("resume_last_app")) Buttons.show(c, Screen.DASH)
                    }
                }
            }
            if (Prefs.bool("resume_last_app")) {
                delay(1500)
                Prefs.str("last_app").ifBlank { null }?.let { AppRepository.launch(c, it) }
            }
        }
    }

    /** Første gang T'OS starter etter at stereoen er slått på. */
    fun isFreshBoot(c: Context): Boolean {
        val boots = runCatching { Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
        val fresh = if (boots >= 0) boots != Prefs.int("seen_boot", -2) else SystemClock.elapsedRealtime() < 180_000
        if (boots >= 0) Prefs.setQuiet("seen_boot", boots)
        return fresh
    }
}

/* ======================= Navigasjon ======================= */

object Navigate {
    /** Start navigasjon til en adresse i valgt kartapp, og husk stedet. */
    fun to(c: Context, dest: String) {
        val d = dest.trim(); if (d.isBlank()) return
        val recent = recents().filterNot { it.equals(d, true) }.toMutableList()
        recent.add(0, d)
        Prefs.set("nav_recent", recent.take(8).joinToString("\n"))
        val q = Uri.encode(d)
        val waze = Prefs.str("nav_app") == "com.waze"
        val intent = if (waze) Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://waze.com/ul?q=$q&navigate=yes"))
        else Intent(Intent.ACTION_VIEW, android.net.Uri.parse("google.navigation:q=$q")).apply {
            if (AppRepository.isInstalled(c, "com.google.android.apps.maps")) setPackage("com.google.android.apps.maps")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { c.startActivity(intent) }.isFailure) {
            runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=$q")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    fun recents(): List<String> = Prefs.str("nav_recent").split("\n").filter { it.isNotBlank() }

    fun home(c: Context) = Prefs.str("addr_home").takeIf { it.isNotBlank() }?.let { to(c, it) } ?: Buttons.show(c, Screen.SETTINGS)
    fun work(c: Context) = Prefs.str("addr_work").takeIf { it.isNotBlank() }?.let { to(c, it) } ?: Buttons.show(c, Screen.SETTINGS)

    private object Uri { fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20") }
}
