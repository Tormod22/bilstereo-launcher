package no.bil.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

fun Context.hasPermission(p: String) =
    ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

/* ---------- GPS: fart og posisjon ---------- */

/**
 * Én posisjon. [fixNanos] er når målingen ble tatt (SystemClock.elapsedRealtimeNanos).
 * [stale] = målingen er for gammel til å vise fart (tunnel, GPS borte, eller gammel «siste kjente posisjon»):
 * posisjonen kan fortsatt brukes til å sentrere kartet, men farten er ukjent ([speed] = null, [speedKmh] = 0).
 */
data class Gps(
    val speedKmh: Int,
    val lat: Double,
    val lon: Double,
    val bearing: Float,
    val fixNanos: Long = 0L,
    val stale: Boolean = false,
    val hasSpeed: Boolean = true,
) {
    /** Farten, eller null når den ikke er kjent (gammel måling eller ingen fart fra GPS-en). */
    val speed: Int? get() = if (stale || !hasSpeed) null else speedKmh
}

/** Hvor lenge en måling regnes som «nå» (fart vises), og hvor gammel «siste kjente posisjon» kan være. */
const val GPS_STALE_NANOS = 3_000_000_000L
const val GPS_LAST_KNOWN_MAX_NANOS = 30_000_000_000L

/** Alderen på en måling i nanosekunder (0 hvis tidspunktet mangler eller er tull). */
internal fun gpsAgeNanos(fixNanos: Long, nowNanos: Long): Long =
    if (fixNanos <= 0L || fixNanos > nowNanos) 0L else nowNanos - fixNanos

@android.annotation.SuppressLint("MissingPermission")   // sjekkes med hasPermission + runCatching
fun gpsFlow(context: Context): Flow<Gps> = callbackFlow {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    fun toGps(loc: Location, stale: Boolean): Gps {
        val adj = 1f + (Prefs.str("speed_adj", "0").toFloatOrNull() ?: 0f) / 100f   // fartsjustering i Innstillinger
        val kmh = if (loc.hasSpeed() && !stale) (loc.speed * 3.6f * adj).toInt() else 0
        val now = SystemClock.elapsedRealtimeNanos()
        val t = loc.elapsedRealtimeNanos.takeIf { it in 1..now } ?: now
        return Gps(kmh, loc.latitude, loc.longitude, loc.bearing, t, stale, loc.hasSpeed())
    }
    // Alle metodene skrives ut: på Android 8–10 krasjer en forkortet versjon når GPS slås av/på
    val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) { trySend(toGps(loc, false)) }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Android < 10")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }
    val fine = context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (fine || coarse) {
        // Bare «omtrentlig posisjon» tillatt: GPS-en kan ikke brukes, nettverksposisjon i stedet
        val providers = if (fine) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER) else listOf(LocationManager.NETWORK_PROVIDER)
        runCatching {
            // Siste kjente posisjon: bare som «nå» hvis den er fersk, ellers bare til å sentrere kartet (uten fart)
            val now = SystemClock.elapsedRealtimeNanos()
            providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.elapsedRealtimeNanos }
                ?.let { loc -> trySend(toGps(loc, gpsAgeNanos(loc.elapsedRealtimeNanos, now) > GPS_LAST_KNOWN_MAX_NANOS || loc.elapsedRealtimeNanos <= 0L)) }
        }
        // Fart og spor fra GPS-en; nettverksposisjon bare når GPS-tillatelse mangler
        runCatching { lm.requestLocationUpdates(providers.first(), 1000L, 0f, listener) }
    }
    awaitClose { lm.removeUpdates(listener) }
}

/* ---------- Kart: mørkegrå fliser fra Esri (World Dark Gray Base), ingen nøkkel ----------
   CARTO krever API-nøkkel nå (viser «API KEY REQUIRED»), derfor byttet. */

object MapTiles {
    private const val TILE = 256
    /** Kartfliser i minnet, maks 60 (trådsikker – hentes fra mange tråder samtidig). */
    private val cache = android.util.LruCache<String, Bitmap>(60)

    data class MapImage(val bitmap: Bitmap, val x0: Int, val y0: Int, val zoom: Int, val cols: Int, val rows: Int) {
        /** Hvor på bildet en posisjon er (0..1). */
        fun pos(lat: Double, lon: Double): Pair<Float, Float> {
            val n = 2.0.pow(zoom)
            val xf = (lon + 180.0) / 360.0 * n
            val latR = Math.toRadians(lat)
            val yf = (1.0 - ln(tan(latR) + 1 / Math.cos(latR)) / PI) / 2.0 * n
            return ((xf - x0) / cols).toFloat() to ((yf - y0) / rows).toFloat()
        }
    }

    /** Lager ett bilde satt sammen av fliser rundt posisjonen (cols x rows fliser, posisjonen i midten). */
    suspend fun around(lat: Double, lon: Double, zoom: Int = 15, cols: Int = 5, rows: Int = 5): MapImage? =
        withContext(Dispatchers.IO) {
            runCatching {
                val n = 2.0.pow(zoom)
                val xf = (lon + 180.0) / 360.0 * n
                val latR = Math.toRadians(lat)
                val yf = (1.0 - ln(tan(latR) + 1 / Math.cos(latR)) / PI) / 2.0 * n
                val x0 = xf.toInt() - cols / 2; val y0 = yf.toInt() - rows / 2   // minst to hele fliser rundt posisjonen
                val out = Bitmap.createBitmap(TILE * cols, TILE * rows, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                var ok = 0
                coroutineScope {
                    (0 until rows).flatMap { r -> (0 until cols).map { c -> r to c } }.map { (r, c) ->
                        async { fetch(zoom, x0 + c, y0 + r)?.let { synchronized(canvas) { canvas.drawBitmap(it, (c * TILE).toFloat(), (r * TILE).toFloat(), null); ok++ } } }
                    }.awaitAll()
                }
                if (ok == 0) null else MapImage(out, x0, y0, zoom, cols, rows)
            }.getOrNull()
        }

    private fun fetch(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        cache.get(key)?.let { return it }
        val url = URL("https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/$z/$y/$x")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "BilstereoLauncher/1.0")
        conn.connectTimeout = 5000; conn.readTimeout = 5000
        return runCatching { BitmapFactory.decodeStream(conn.inputStream) }.getOrNull()?.also {
            cache.put(key, it)
        }
    }
}

