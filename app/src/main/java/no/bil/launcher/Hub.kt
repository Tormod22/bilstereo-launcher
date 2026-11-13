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
        Power.start(app)                        // tenning/skjerm: hva som får gå (Power.kt)
        Trip.restore()
        startGps()
        watchNetwork()
        CarBattery.start(app)
        HwRadio.start(app)
        BtLink.start(app)                       // Bluetooth-hendelser og profiler (er telefonen tilkoblet?)
        FytMain.start(app)                      // FYT: tenningen også som kringkasting (reserve)
        Fyt.start(app)                          // FYT-stereoen: radio, BT-telefon, lydkilde direkte (gjør ingenting andre steder)
        Updater.announceIfUpdated(app)          // «Oppdatert til …» etter en oppdatering
        // Equalizeren (lydtjenesten) settes etter at første skjermbilde er tegnet – den kan ta litt tid å koble til
        scope.launch { delay(1500); Eq.restore() }
        scope.launch(Dispatchers.IO) { LocalBackup.autoSave(app) }   // daglig kopi av innstillingene i T'OS-mappa
        // GPS av i dvale (tenning av / skjerm av uten tenning), på igjen når stereoen våkner
        scope.launch { Power.state.collect { s -> if (s.plan.gps) resumeGps() else pauseGps() } }
        // Dag/natt hvert minutt – ikke mens skjermen er av (gjøres med en gang den kommer på igjen)
        scope.launch {
            Power.every(60_000, { it.theme }) {
                Theme.update(gps?.lat ?: Prefs.float("park_lat").toDouble().takeIf { it != 0.0 }, gps?.lon ?: Prefs.float("park_lon").toDouble().takeIf { it != 0.0 })
                NightBrightness.apply(app)
            }
        }
        // CPU-temperatur og minne vises bare i T'OS: les dem bare da
        scope.launch { Power.every(10_000, { it.ui }) { readSystem() } }
        scope.launch {
            var fails = 0
            while (true) {
                Power.await { it.updates }      // ikke spør GitHub mens bilen står med tenningen av
                val info = Updater.check()
                if (info != null) update = if (info.versionCode > Updater.current(app)) info else null
                // Fikk ikke svar (nettet fra iPhonen er sjelden klart når bilen starter): prøv igjen om 1 min,
                // så sjeldnere (2, 4, 8 … maks 60 min) – ikke hvert minutt hele dagen når GitHub ikke har noen versjon
                fails = if (info == null) fails + 1 else 0
                delay(updateRetryMs(fails))
            }
        }
    }

    /** Bare «omtrentlig posisjon» er tillatt (Android 12+): fart og fartsgrense virker dårlig – UI kan be om nøyaktig. */
    var gpsCoarseOnly by mutableStateOf(false)
    /** Ingen posisjonstillatelse i det hele tatt. */
    var gpsNoPermission by mutableStateOf(false)
    private var staleJob: Job? = null
    private var lifecycleHooked = false

    /** Kalles på nytt når brukeren har gitt posisjonstillatelse. */
    fun startGps() {
        val fine = app.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = app.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        gpsCoarseOnly = coarse && !fine
        gpsNoPermission = !fine && !coarse
        if (gpsNoPermission) return
        hookLifecycle()
        // Dvale: GPS-en startes når stereoen våkner (Power → resumeGps)
        if (!Power.plan.gps) return
        GpsService.startIfForeground(app)
        gpsJob?.cancel()
        gpsJob = scope.launch {
            gpsFlow(app).collect { g ->
                val first = gps == null
                gps = g
                if (!g.stale) {
                    Trip.onFix(g)
                    Parking.onFix(g)
                    SpeedLimit.onFix(g)
                    Accel.onFix(g)
                    watchStale()
                }
                if (first) Theme.update(g.lat, g.lon)
            }
        }
    }

    /** GPS-en går ikke (dvale) – startes på nytt av [resumeGps]. */
    private var gpsPaused = false

    private fun resumeGps() {
        if (!gpsPaused) return
        gpsPaused = false
        startGps()
    }

    /** Tenningen av (eller skjermen av uten tenning): GPS-mottakeren og posisjonstjenesten av, ingen fart som står igjen. */
    private fun pauseGps() {
        if (gpsPaused) return
        gpsPaused = true
        gpsJob?.cancel(); gpsJob = null          // awaitClose i gpsFlow: removeUpdates
        staleJob?.cancel(); staleJob = null
        GpsService.stop(app)
        gps?.let { g -> if (!g.stale) gps = g.copy(speedKmh = 0, stale = true) }
        SpeedLimit.over = false
    }

    /**
     * Fart blir «ukjent» når det ikke har kommet ny måling på 3 s (tunnel, GPS-en mistet satellittene).
     * Startes av hver ny måling og stopper seg selv når farten er merket ukjent (ingen sekundtikk uten GPS).
     */
    private fun watchStale() {
        if (staleJob?.isActive == true) return
        staleJob = scope.launch {
            while (true) {
                val g = gps ?: break
                if (g.stale) break
                val age = gpsAgeNanos(g.fixNanos, SystemClock.elapsedRealtimeNanos())
                if (age > GPS_STALE_NANOS) {
                    gps = g.copy(speedKmh = 0, stale = true)
                    SpeedLimit.over = false
                    break
                }
                delay(((GPS_STALE_NANOS - age) / 1_000_000L).coerceAtLeast(100L) + 50L)
            }
        }
    }

    /**
     * Holder posisjonstjenesten (forgrunnstjeneste) i gang: den kan bare startes mens T'OS vises,
     * så den startes hver gang en T'OS-skjerm kommer fram (f.eks. etter at stereoen har startet).
     */
    private fun hookLifecycle() {
        if (lifecycleHooked) return
        val a = app as? android.app.Application ?: return
        lifecycleHooked = true
        a.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                if (!GpsService.running && !gpsNoPermission && Power.plan.gps) GpsService.startIfForeground(app)
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }

    private fun watchNetwork() {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // «På nett» = nettet har internett OG Android har sjekket at det virker (ikke bare Wi-Fi uten innlogging)
        fun usable(caps: NetworkCapabilities?) = caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        fun check() = runCatching { usable(cm.getNetworkCapabilities(cm.activeNetwork)) }.getOrDefault(false)
        online = check()
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) { val ok = usable(caps); scope.launch { online = ok } }
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
    fun compass(bearing: Float): String = compassName(bearing)
}

/** Ventetid til neste versjonssjekk etter [fails] mislykkede forsøk på rad (0 = siste gikk bra: 6 timer). */
internal fun updateRetryMs(fails: Int): Long =
    if (fails <= 0) TimeUnit.HOURS.toMillis(6) else 60_000L * (1L shl (fails - 1).coerceAtMost(6)).coerceAtMost(60)

/** Kompassretning på norsk (egen funksjon så den kan testes uten Android). */
fun compassName(bearing: Float): String =
    listOf("N", "NØ", "Ø", "SØ", "S", "SV", "V", "NV")[(((bearing % 360) + 360) % 360 / 45f + 0.5f).toInt() % 8]

/* ======================= Turmåler ======================= */

object Trip {
    var meters by mutableDoubleStateOf(0.0)
    var movingSec by mutableLongStateOf(0L)
    private var movingMs = 0L
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
            movingSec = Prefs.long("trip_s"); movingMs = movingSec * 1000
            maxKmh = Prefs.int("trip_max")
            startedAt = Prefs.long("trip_start", System.currentTimeMillis())
        }
    }

    fun reset() {
        meters = 0.0; movingSec = 0; movingMs = 0; maxKmh = 0; startedAt = System.currentTimeMillis(); last = null
        save()
    }

    fun onFix(g: Gps) {
        val now = SystemClock.elapsedRealtime()
        // Pause på mer enn 30 min (bilen sto, stereoen sov): ny tur, slik som ved oppstart
        if (lastTime != 0L && now - lastTime > TimeUnit.MINUTES.toMillis(30)) reset()
        val prev = last
        if (prev != null && g.speedKmh >= 3) {
            val r = FloatArray(1)
            Location.distanceBetween(prev.lat, prev.lon, g.lat, g.lon, r)
            val dtMs = now - lastTime                      // millisekunder (GPS kommer ca. hvert sekund – 999 ms må telle)
            if (tripCounts(r[0], dtMs)) { meters += r[0]; movingMs += dtMs; movingSec = movingMs / 1000 }
        }
        if (g.speedKmh > maxKmh && g.speedKmh < 250) maxKmh = g.speedKmh
        last = g; lastTime = now
        if (now - lastSave > 20_000) { lastSave = now; save() }
    }

    /** Lagre turen nå (tenningen ble slått av – stereoen kan sove før neste lagring). */
    fun flush() = save()

    private fun save() {
        Prefs.setQuiet("trip_m", meters.toFloat()); Prefs.setQuiet("trip_s", movingSec)
        Prefs.setQuiet("trip_max", maxKmh); Prefs.setQuiet("trip_start", startedAt)
        Prefs.setQuiet("trip_saved", System.currentTimeMillis())
    }
}

/**
 * Skal strekningen mellom to målinger telles i turen? Vanlig: ca. hvert sekund, under 250 m.
 * Hull (tunnel uten GPS, opptil 30 min): telles hvis snittfarten over hullet er troverdig (5–200 km/t).
 */
internal fun tripCounts(distM: Float, dtMs: Long): Boolean = when {
    dtMs < 300 -> false
    dtMs <= 10_000 -> distM < 250
    dtMs <= 30 * 60_000L -> { val kmh = distM / (dtMs / 1000.0) * 3.6; kmh in 5.0..200.0 }
    else -> false
}

/* ======================= Parkering ======================= */

/** Husker siste posisjon der bilen sto stille. Vises med QR-kode så iPhonen kan ta den med seg. */
object Parking {
    private var lastSave = 0L
    fun onFix(g: Gps) {
        val now = SystemClock.elapsedRealtime()
        if (g.speedKmh <= 2 && now - lastSave > 15_000) {
            lastSave = now
            // Avstanden må regnes fra den GAMLE plassen før den overskrives (ellers er bilen aldri «flyttet»)
            val r = FloatArray(1)
            if (known) Location.distanceBetween(lat, lon, g.lat, g.lon, r)
            val moved = !known || r[0] > 25
            Prefs.setQuiet("park_lat", g.lat.toFloat()); Prefs.setQuiet("park_lon", g.lon.toFloat())
            // Ny plass: lagres med Prefs.set så parkeringskortet tegnes på nytt (ikke hvert 15. sekund når bilen bare står)
            if (moved) Prefs.set("park_time", System.currentTimeMillis()) else Prefs.setQuiet("park_time", System.currentTimeMillis())
        }
    }
    /**
     * Tenningen ble slått av (FytMain): lagre siste posisjon som parkeringsplassen med en gang, også om bilen ikke har
     * stått stille i 15 s ennå. Siste kjente posisjon brukes selv om GPS-en er «gammel» (den er bare noen sekunder gammel).
     */
    fun saveNow(g: Gps?) {
        if (g == null || (g.lat == 0.0 && g.lon == 0.0)) return
        lastSave = SystemClock.elapsedRealtime()
        Prefs.setQuiet("park_lat", g.lat.toFloat()); Prefs.setQuiet("park_lon", g.lon.toFloat())
        Prefs.set("park_time", System.currentTimeMillis())
    }
    val lat get() = Prefs.float("park_lat").toDouble()
    val lon get() = Prefs.float("park_lon").toDouble()
    val time get() = Prefs.long("park_time")
    val known get() = lat != 0.0 || lon != 0.0
    fun appleMapsUrl() = "https://maps.apple.com/?ll=${"%.6f".format(Locale.US, lat)},${"%.6f".format(Locale.US, lon)}&q=Bilen"
}

/* ======================= Fartsgrense (OpenStreetMap) ======================= */

object SpeedLimit {
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60) }   // én gjenbrukt, ikke ny for hvert pip
    var limit by mutableStateOf<Int?>(null)
    var over by mutableStateOf(false)
    private var lastQueryPos: Gps? = null
    private var lastConfirmedPos: Gps? = null      // der vi sist fikk et sikkert svar (fartsgrense eller «ingen skiltet»)
    private var lastQuery = 0L
    private var busy = false
    private var beeped = false
    /** Server → tidspunkt (elapsedRealtime) den kan spørres igjen etter «for mange forespørsler» (HTTP 429) o.l. */
    private val pausedUntil = mutableMapOf<String, Long>()

    private val servers = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )

    /** Svaret fra OpenStreetMap: fant fartsgrense, veien har ingen, eller fikk ikke svar. */
    internal sealed class Result {
        data class Found(val kmh: Int) : Result()
        object NoneTagged : Result()
        object Failed : Result()
    }

    /** En vei fra Overpass: merkene (tags) og punktene langs veien. */
    internal data class Way(val tags: Map<String, String>, val points: List<Pair<Double, Double>>)

    fun onFix(g: Gps) {
        val l = limit
        over = Prefs.bool("speed_limit", true) && l != null && g.speedKmh > l + 3
        if (over && !beeped && Prefs.bool("speed_beep", false)) {
            beeped = true
            runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 300) }
        }
        if (!over) beeped = false

        if (!Prefs.bool("speed_limit", true) || busy || g.speedKmh < 5 || !Hub.online) return
        val now = SystemClock.elapsedRealtime()
        val prev = lastQueryPos
        val moved = prev?.let { distM(it.lat, it.lon, g.lat, g.lon) } ?: Double.MAX_VALUE
        if (!shouldQuery(moved, if (lastQuery == 0L) Long.MAX_VALUE else now - lastQuery)) return
        lastQueryPos = g; lastQuery = now; busy = true
        val bearing = g.bearing.takeIf { g.speedKmh >= 10 }
        Hub.scope.launch {
            when (val r = withContext(Dispatchers.IO) { query(g.lat, g.lon, bearing) }) {
                is Result.Found -> { limit = r.kmh; lastConfirmedPos = g }
                Result.NoneTagged -> { limit = null; lastConfirmedPos = g }
                // Ingen svar: behold forrige grense bare så lenge vi er nær stedet der den sist ble bekreftet
                Result.Failed -> if ((lastConfirmedPos?.let { distM(it.lat, it.lon, g.lat, g.lon) } ?: Double.MAX_VALUE) >= 400) limit = null
            }
            busy = false
        }
    }

    // Bilveier (ikke gangveier/sykkelveier som ofte ligger rett ved siden av)
    private const val ROADS = "^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|road|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$"

    /**
     * Spør på nytt? Når bilen har kjørt 120 m eller det er gått 25 s – men aldri oftere enn hvert 8. sekund
     * (i 100 km/t ville det ellers blitt et spørsmål hvert 4. sekund, og de gratis serverne stenger oss ute).
     */
    internal fun shouldQuery(movedM: Double, sinceMs: Long): Boolean =
        sinceMs >= 8_000 && (movedM >= 120 || sinceMs >= 25_000)

    private fun query(lat: Double, lon: Double, bearing: Float?): Result {
        val q = "[out:json][timeout:8];way(around:15,$lat,$lon)[highway~\"$ROADS\"];out tags geom 20;"
        for (s in servers) {
            if (SystemClock.elapsedRealtime() < (synchronized(pausedUntil) { pausedUntil[s] } ?: 0L)) continue
            val res = runCatching {
                val c = URL("$s?data=" + URLEncoder.encode(q, "UTF-8")).openConnection() as HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 9000
                c.setRequestProperty("User-Agent", "TOS-BilstereoLauncher/1.0 (github.com/${Updater.OWNER}/${Updater.REPO})")
                val code = c.responseCode
                // For mange forespørsler / opptatt: la denne serveren være i fred i 2 min
                if (code == 429 || code == 503 || code == 504) synchronized(pausedUntil) { pausedUntil[s] = SystemClock.elapsedRealtime() + 120_000 }
                if (code != 200) { c.disconnect(); error("HTTP $code") }
                val els = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("elements")
                val ways = (0 until els.length()).map { i ->
                    val e = els.getJSONObject(i)
                    val t = e.optJSONObject("tags")
                    val tags = t?.keys()?.asSequence()?.associateWith { t.optString(it) }.orEmpty()
                    val geom = e.optJSONArray("geometry")
                    val pts = if (geom == null) emptyList() else (0 until geom.length()).map { geom.getJSONObject(it).let { p -> p.getDouble("lat") to p.getDouble("lon") } }
                    Way(tags, pts)
                }
                pick(ways, lat, lon, bearing)
            }
            if (res.isSuccess) return res.getOrThrow()
        }
        return Result.Failed
    }

    /**
     * Velger veien bilen er på: den nærmeste, men en vei som går i samme retning som bilen (±45°)
     * foretrekkes framfor en sidevei som er litt nærmere. Testes i DataLogicTest.
     */
    internal fun pick(ways: List<Way>, lat: Double, lon: Double, bearing: Float?): Result {
        data class Cand(val way: Way, val dist: Double, val dir: Double?)
        val cands = ways.mapNotNull { w ->
            var best = Double.MAX_VALUE; var dir: Double? = null
            if (w.points.size == 1) best = distM(lat, lon, w.points[0].first, w.points[0].second)
            for (i in 0 until w.points.size - 1) {
                val (aLat, aLon) = w.points[i]; val (bLat, bLon) = w.points[i + 1]
                val d = segmentDistM(lat, lon, aLat, aLon, bLat, bLon)
                if (d < best) { best = d; dir = bearingDeg(aLat, aLon, bLat, bLon) }
            }
            if (best == Double.MAX_VALUE) null else Cand(w, best, dir)
        }
        if (cands.isEmpty()) return Result.NoneTagged
        // Retningsforskjell 0..90° (en vei kan kjøres begge veier)
        fun misfit(c: Cand): Double {
            if (bearing == null || c.dir == null) return 0.0
            val diff = angleDiff(bearing.toDouble(), c.dir)
            return minOf(diff, 180 - diff)
        }
        val chosen = cands.minByOrNull { it.dist + if (misfit(it) > 45) 25.0 else 0.0 }!!
        val tags = chosen.way.tags
        parse(tags["maxspeed"].orEmpty())?.let { return Result.Found(it) }
        // Ulik grense i hver retning (maxspeed:forward / :backward): bruk den som passer kjøreretningen
        if (bearing != null && chosen.dir != null) {
            val forward = angleDiff(bearing.toDouble(), chosen.dir) <= 90
            parse(tags[if (forward) "maxspeed:forward" else "maxspeed:backward"].orEmpty())?.let { return Result.Found(it) }
        }
        return Result.NoneTagged
    }

    private fun angleDiff(a: Double, b: Double): Double { val d = ((a - b) % 360 + 360) % 360; return if (d > 180) 360 - d else d }

    /** Avstand i meter (flat jord – godt nok for noen hundre meter). */
    internal fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val k = 111_320.0
        val x = (lon2 - lon1) * k * kotlin.math.cos(Math.toRadians((lat1 + lat2) / 2)); val y = (lat2 - lat1) * k
        return kotlin.math.sqrt(x * x + y * y)
    }

    /** Korteste avstand fra et punkt til en veibit (linjestykke a–b), i meter. */
    internal fun segmentDistM(lat: Double, lon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val k = 111_320.0; val c = kotlin.math.cos(Math.toRadians(lat))
        val ax = (aLon - lon) * k * c; val ay = (aLat - lat) * k
        val bx = (bLon - lon) * k * c; val by = (bLat - lat) * k
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val px = ax + t * dx; val py = ay + t * dy
        return kotlin.math.sqrt(px * px + py * py)
    }

    /** Retning fra a til b i grader (0 = nord, 90 = øst). */
    internal fun bearingDeg(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val x = (bLon - aLon) * kotlin.math.cos(Math.toRadians((aLat + bLat) / 2)); val y = bLat - aLat
        return (Math.toDegrees(kotlin.math.atan2(x, y)) + 360) % 360
    }

    /** Tolker OSM sin «maxspeed» (f.eks. «80», «NO:urban», «50 km/h»). Testes i LogicTest. */
    internal fun parse(s: String): Int? = when {
        s.isBlank() -> null
        s.equals("NO:urban", true) -> 50
        s.equals("NO:rural", true) -> 80
        s.equals("NO:motorway", true) -> 110
        else -> s.takeWhile { it.isDigit() }.toIntOrNull()
    }
}

/* ======================= Demp hele stereoen om natten ======================= */

object NightBrightness {
    /** Lagret i innstillingene, så det overlever at T'OS startes på nytt (f.eks. dempet om kvelden, ny start om morgenen). */
    private var dimmed: Boolean
        get() = Prefs.bool("night_dimmed")
        set(v) = Prefs.set("night_dimmed", v)

    fun apply(c: Context) {
        // FYT: baklyset styres av stereoen (MCU-en), ikke av Android – demp via stereoen (FytMain), uten systemtillatelse
        if (FytMain.brightReady) { FytMain.applyNight(Prefs.bool("dim_system") && Theme.night); return }
        // «Lysstyrke via stereoen» slått av mens stereoen var dempet: sett dagnivået tilbake der først
        if (Fyt.connected && Prefs.bool("fyt_night_dimmed")) FytMain.applyNight(false)
        if (!Settings.System.canWrite(c)) return
        val r = c.contentResolver
        val want = Prefs.bool("dim_system") && Theme.night
        runCatching {
            if (want && !dimmed) {
                // Husk dagens innstilling (bare når T'OS ikke allerede har dempet)
                Prefs.setQuiet("day_brightness", Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS, 200))
                Prefs.setQuiet("day_brightness_mode", Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL))
                Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, 35)
                dimmed = true
            } else if (!want && dimmed) {
                // Dag igjen, eller funksjonen er slått av: sett alt tilbake slik det var
                // Aldri nesten svart (en lagret 0 ville gjort skjermen umulig å se)
                Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, Prefs.int("day_brightness", 200).coerceAtLeast(20))
                Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Prefs.int("day_brightness_mode", Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL))
                dimmed = false
            }
        }
    }
}

/* ======================= Påminnelser: EU-kontroll, service, dekkskift ======================= */

object Reminders {
    private val fmt = SimpleDateFormat("d.M.yyyy", Locale("nb", "NO")).apply { isLenient = false }

    /** Godtar «15.3.2027», «15.3.27» og «15/3-2027». Ugyldige datoer (31.2) gir null. */
    fun parse(s: String): Date? = runCatching {
        val parts = s.trim().split(Regex("[^0-9]+")).filter { it.isNotBlank() }
        if (parts.size != 3) return null
        val y = parts[2].toInt().let { if (it < 100) 2000 + it else it }
        fmt.parse("${parts[0]}.${parts[1]}.$y")
    }.getOrNull()
    fun format(d: Date): String = fmt.format(d)

    data class Due(val key: String, val text: String)

    /** Det som snart skal gjøres (innen 14 dager) eller er på overtid. */
    fun due(): List<Due> {
        val out = mutableListOf<Due>()
        val today = Calendar.getInstance()
        // Fra midnatt i dag, så «i morgen» blir 1 og ikke 0
        val midnight = (today.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        fun daysTo(d: Date) = Math.round((d.time - midnight.timeInMillis) / 86_400_000.0).toInt()
        fun check(key: String, label: String) {
            val d = parse(Prefs.str(key)) ?: return
            val n = daysTo(d)
            when {
                n < 0 -> out += Due(key, "$label: på overtid")
                n == 0 -> out += Due(key, "$label i dag")
                n == 1 -> out += Due(key, "$label i morgen")
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
            key == "eu_date" -> Prefs.set(key, format(nextEu(parse(Prefs.str(key)), Date())))
            key == "service_date" -> {
                val c = Calendar.getInstance()
                c.add(Calendar.YEAR, 1)
                Prefs.set(key, format(c.time))
            }
            key.startsWith("tire_") -> Prefs.set("tire_done_" + key.removePrefix("tire_"), true)
        }
    }
}

/**
 * Neste EU-kontroll. Tas den inntil 2 måneder før fristen, eller etter fristen, beholdes rytmen:
 * gammel frist + 2 år. Tas den tidligere enn det (eller fristen er ukjent): i dag + 2 år.
 */
internal fun nextEu(oldDeadline: Date?, today: Date): Date {
    val plus2 = { d: Date -> Calendar.getInstance().apply { time = d; add(Calendar.YEAR, 2) }.time }
    if (oldDeadline == null) return plus2(today)
    val windowStart = Calendar.getInstance().apply { time = oldDeadline; add(Calendar.MONTH, -2) }.time
    if (today.before(windowStart)) return plus2(today)
    val next = plus2(oldDeadline)
    return if (next.after(today)) next else plus2(today)   // flere år på overtid: regn fra i dag
}

/* ======================= Ved oppstart: musikk og siste app ======================= */

/**
 * Skal musikken startes igjen når bilen starter? Bare når valget er på, ingenting spiller allerede
 * (heller ikke CarPlay eller FM-radioen – de skal ikke overstyres), og det ikke er en samtale.
 */
internal fun shouldResumeMusic(enabled: Boolean, playing: Boolean, otherAudio: Boolean, inCall: Boolean): Boolean =
    enabled && !playing && !otherAudio && !inCall

object Startup {
    private var lastRun = 0L

    /** Kjøres når stereoen starter (eller våkner etter å ha vært av en stund). */
    fun run(c: Context) {
        val now = SystemClock.elapsedRealtime()
        // Én gang per tur: tenning på (FytMain), skjermen på (Power) og kald start (MainActivity) kan alle komme samtidig
        if (!PowerLogic.startupDue(lastRun, now)) return
        lastRun = now
        Hub.scope.launch {
            delay(4000)
            // Tenningen ble slått av igjen i mellomtiden (vridd om og tilbake): ikke start musikk i en bil som står
            // (lastRun nullstilles, så neste tenning på er en ny oppstart)
            if (FytMain.accKnown && FytMain.acc == false) { lastRun = 0L; return@launch }
            // Nettradioen spilte da tenningen ble slått av (Power stoppet den): start den igjen i stedet for musikkappen
            val netRadio = Power.takeNetRadioResume()
            if (shouldResumeMusic(Prefs.bool("resume_music", true), NowPlaying.playing, otherAudioActive(c), Buttons.inCall(c))) {
                val station = if (netRadio) NetRadio.last() else null
                if (station != null) NetRadio.play(c, station)
                else if (NowPlaying.hasSession) Media.play(c)
                else {
                    val tile = Tiles.byPrefix("Mus")
                    val pkg = Prefs.str("music_pkg").ifBlank { null }
                        ?: tile.candidates.firstOrNull { AppRepository.isInstalled(c, it) }
                    if (pkg != null) {
                        AppRepository.launch(c, pkg)
                        delay(3500)
                        // Musikkappen kan ha startet avspillingen selv – da ville «spill» bare vært unødvendig
                        if (!NowPlaying.playing) Media.play(c)
                        // Tilbake til forsiden bare hvis musikkappen fortsatt er framme (har sjåføren åpnet noe annet, få det være)
                        val front = KeyService.frontPackage
                        if (!Prefs.bool("resume_last_app") && (front == null || front == pkg)) Buttons.show(c, Screen.DASH)
                    }
                }
            }
            if (Prefs.bool("resume_last_app")) {
                delay(1500)
                Prefs.str("last_app").ifBlank { null }?.let { AppRepository.launch(c, it) }
            }
        }
    }

    /** Spiller noe allerede (CarPlay, FM-radio fra stereoen, en annen app)? Da skal T'OS ikke starte musikk over det. */
    private fun otherAudioActive(c: Context): Boolean =
        runCatching { (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive }.getOrDefault(false)

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
        // T'OS åpnet kartappen selv: knappestyringen skal ikke sende brukeren et annet sted
        (intent.`package` ?: if (waze) "com.waze" else null)?.let { AppRepository.markLaunched(it) }
        if (runCatching { c.startActivity(intent) }.isFailure) {
            runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=$q")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    fun recents(): List<String> = Prefs.str("nav_recent").split("\n").filter { it.isNotBlank() }

    fun home(c: Context) = Prefs.str("addr_home").takeIf { it.isNotBlank() }?.let { to(c, it) } ?: run { Prefs.set("settings_cat", "MAP"); Buttons.show(c, Screen.SETTINGS) }
    fun work(c: Context) = Prefs.str("addr_work").takeIf { it.isNotBlank() }?.let { to(c, it) } ?: run { Prefs.set("settings_cat", "MAP"); Buttons.show(c, Screen.SETTINGS) }

    private object Uri { fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20") }
}
