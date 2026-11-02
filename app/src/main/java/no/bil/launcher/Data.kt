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

data class Gps(val speedKmh: Int, val lat: Double, val lon: Double, val bearing: Float)

@android.annotation.SuppressLint("MissingPermission")   // sjekkes med hasPermission + runCatching
fun gpsFlow(context: Context): Flow<Gps> = callbackFlow {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val listener = LocationListener { loc: Location ->
        val kmh = if (loc.hasSpeed()) (loc.speed * 3.6f).toInt() else 0
        trySend(Gps(kmh, loc.latitude, loc.longitude, loc.bearing))
    }
    if (context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
        runCatching {
            (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
                ?.let { listener.onLocationChanged(it) }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener)
        }
    }
    awaitClose { lm.removeUpdates(listener) }
}

/* ---------- Kart: mørkegrå fliser fra Esri (World Dark Gray Base), ingen nøkkel ----------
   CARTO krever API-nøkkel nå (viser «API KEY REQUIRED»), derfor byttet. */

object MapTiles {
    private const val TILE = 256
    private val cache = HashMap<String, Bitmap>()

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
        cache[key]?.let { return it }
        val url = URL("https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/$z/$y/$x")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "BilstereoLauncher/1.0")
        conn.connectTimeout = 5000; conn.readTimeout = 5000
        return runCatching { BitmapFactory.decodeStream(conn.inputStream) }.getOrNull()?.also {
            if (cache.size > 150) cache.clear()
            cache[key] = it
        }
    }
}

