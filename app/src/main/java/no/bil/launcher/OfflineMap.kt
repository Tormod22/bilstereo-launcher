package no.bil.launcher

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kart uten nett: OpenStreetMap-data for Rogaland (fra Protomaps), klippet ut av GitHub-jobben
 * «Lag kart uten nett» og lagt på release-taggen «kart». Tegnes på stereoen med MapLibre i T'OS-farger.
 * Uten nedlastet kart brukes nettkartet (Esri) som før.
 */
object OfflineMaps {
    const val REGION = "rogaland"
    const val LABEL = "Rogaland"
    private const val BASE = "https://github.com/${Updater.OWNER}/${Updater.REPO}/releases/download/kart"

    var progress by mutableStateOf<Int?>(null)      // null = ingen nedlasting pågår
    var status by mutableStateOf<String?>(null)
    var remoteBytes by mutableStateOf<Long?>(null)
    private var version by mutableIntStateOf(0)

    fun file(c: Context) = File(c.getExternalFilesDir(null) ?: c.filesDir, "$REGION.pmtiles")
    fun ready(c: Context): Boolean { version; return file(c).let { it.exists() && it.length() > 10_000 } }
    fun sizeMb(bytes: Long) = (bytes / 1_048_576).toInt()
    fun downloadedText(c: Context): String {
        val f = file(c)
        return "Lastet ned ${SimpleDateFormat("d. MMM", Locale("nb", "NO")).format(Date(f.lastModified()))} · ${sizeMb(f.length())} MB"
    }

    /** Hvor stort kartet er på GitHub (for å vise før nedlasting). Henter bare 1 byte. */
    fun checkRemote() {
        Hub.scope.launch {
            remoteBytes = withContext(Dispatchers.IO) {
                // GitHub sine nedlastingslenker avviser HEAD (401) – be om 1 byte og les totalen fra Content-Range
                runCatching {
                    val c = Updater.open("$BASE/$REGION.pmtiles", headers = mapOf("Range" to "bytes=0-0"))
                    val total = when (c.responseCode) {
                        206 -> c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
                        200 -> c.contentLengthLong
                        else -> -1L
                    }
                    c.disconnect(); total
                }.getOrNull()?.takeIf { it > 0 }
            }
        }
    }

    /**
     * Laster ned kartet. Tåler avbrudd: blir tenningen slått av midt i, fortsetter neste forsøk der det slapp
     * (fila «.part» beholdes). Sjekker ledig plass, og at fila er hel før det gamle kartet byttes ut.
     */
    fun download(c: Context) {
        if (progress != null) return
        progress = 0; status = null
        Hub.scope.launch {
            val err: String? = withContext(Dispatchers.IO) {
                val target = file(c)
                val part = File(target.path + ".part")
                runCatching {
                    val have = if (part.exists()) part.length() else 0L
                    // If-Range: er kartet på GitHub byttet ut siden forrige forsøk, sender serveren hele den nye fila
                    val tag = Prefs.str("map_etag")
                    val conn = Updater.open("$BASE/$REGION.pmtiles",
                        headers = if (have > 0 && tag.isNotBlank()) mapOf("Range" to "bytes=$have-", "If-Range" to tag) else emptyMap())
                    val code = conn.responseCode
                    if (code == 404) return@withContext "Kartet er ikke laget på GitHub ennå"
                    if (code == 416) { part.delete(); return@withContext "Prøv igjen" }   // .part var allerede for stor/ugyldig
                    if (code != 200 && code != 206) error("HTTP $code")
                    val resume = code == 206
                    if (!resume) {
                        part.delete()
                        (conn.getHeaderField("ETag") ?: conn.getHeaderField("Last-Modified"))?.let { Prefs.setQuiet("map_etag", it) }
                    }
                    val start = if (resume) have else 0L
                    val total = if (conn.contentLengthLong > 0) start + conn.contentLengthLong else -1L
                    val dir = target.parentFile ?: c.filesDir
                    if (total > 0 && dir.usableSpace < (total - start) + 50_000_000L)
                        return@withContext "Ikke nok plass: trenger ${sizeMb(total - start + 50_000_000L)} MB ledig"
                    var done = start
                    conn.inputStream.use { input ->
                        java.io.FileOutputStream(part, resume).use { out ->
                            val buf = ByteArray(256 * 1024); var read: Int; var lastPct = -1
                            while (input.read(buf).also { read = it } > 0) {
                                out.write(buf, 0, read); done += read
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) { lastPct = pct; withContext(Dispatchers.Main) { progress = pct } }
                                }
                            }
                        }
                    }
                    if (total > 0 && part.length() != total) error("ufullstendig")
                    if (part.length() < 10_000) { part.delete(); error("for liten fil") }
                    // Bytt ut trygt: det gamle kartet slettes først når det nye er på plass
                    val old = File(target.path + ".old")
                    old.delete()
                    if (target.exists() && !target.renameTo(old)) error("fikk ikke flyttet gammelt kart")
                    if (!part.renameTo(target)) { old.renameTo(target); error("fikk ikke lagret kartet") }
                    old.delete()
                    null
                }.getOrElse { if (Hub.online) "Nedlastingen stoppet – trykk «Last ned» igjen for å fortsette" else "Ingen internett – fortsetter når du prøver igjen" }
            }
            progress = null
            status = err   // ved suksess vises dato og størrelse i stedet
            version++
        }
    }

    /** Hent nyeste kart på nytt (start fra begynnelsen). */
    fun update(c: Context) { File(file(c).path + ".part").delete(); download(c) }

    fun delete(c: Context) { file(c).delete(); File(file(c).path + ".part").delete(); version++; status = null }

    /** Mørk kartstil i T'OS-farger for Protomaps-skjemaet (v4). */
    fun style(c: Context): String {
        val src = "pmtiles://file://${file(c).absolutePath}"
        val name = """["coalesce",["get","name:nb"],["get","name:no"],["get","name"]]"""
        fun kinds(vararg k: String) = """["in",["get","kind"],["literal",[${k.joinToString(",") { "\"$it\"" }}]]]"""
        fun road(id: String, filter: String, color: String, widths: String, dash: Boolean = false) =
            """{"id":"$id","type":"line","source":"pm","source-layer":"roads","filter":$filter,
               "layout":{"line-cap":"round","line-join":"round"},
               "paint":{"line-color":"$color","line-width":["interpolate",["exponential",1.6],["zoom"],$widths]${if (dash) ""","line-dasharray":[2,2]""" else ""}}}"""
        return """
        {"version":8,
         "glyphs":"asset://fonts/{fontstack}/{range}.pbf",
         "sources":{"pm":{"type":"vector","url":"$src","attribution":"© OpenStreetMap"}},
         "layers":[
          {"id":"bg","type":"background","paint":{"background-color":"#0D0D0F"}},
          {"id":"earth","type":"fill","source":"pm","source-layer":"earth","paint":{"fill-color":"#151517"}},
          {"id":"landcover","type":"fill","source":"pm","source-layer":"landcover","filter":${kinds("forest", "scrub", "grassland", "farmland")},"paint":{"fill-color":"#17191A","fill-opacity":0.7}},
          {"id":"parks","type":"fill","source":"pm","source-layer":"landuse","filter":${kinds("park", "forest", "wood", "nature_reserve", "cemetery", "golf_course", "grass")},"paint":{"fill-color":"#171A19"}},
          {"id":"water","type":"fill","source":"pm","source-layer":"water","paint":{"fill-color":"#08090B"}},
          {"id":"buildings","type":"fill","source":"pm","source-layer":"buildings","minzoom":14,"paint":{"fill-color":"#1E1E21"}},
          ${road("paths", kinds("path"), "#2A2A2E", "13,0.4,16,1.5,18,3", dash = true)},
          ${road("minor", kinds("minor_road", "other"), "#36363B", "12,0.5,16,6,18,16")},
          ${road("major", kinds("major_road"), "#4A4A51", "8,0.6,12,1.6,16,9,18,22")},
          ${road("highway", kinds("highway"), "#5E5E66", "6,0.8,12,2,16,12,18,28")},
          {"id":"road-names","type":"symbol","source":"pm","source-layer":"roads","minzoom":14,"filter":["has","name"],
           "layout":{"symbol-placement":"line","text-field":$name,"text-font":["NotoSansRegular"],"text-size":12},
           "paint":{"text-color":"#8C8C92","text-halo-color":"#0D0D0F","text-halo-width":1.5}},
          {"id":"places","type":"symbol","source":"pm","source-layer":"places","filter":${kinds("locality", "neighbourhood", "macrohood")},
           "layout":{"text-field":$name,"text-font":["NotoSansRegular"],"text-size":["interpolate",["linear"],["zoom"],8,11,14,15]},
           "paint":{"text-color":"#A2A2A8","text-halo-color":"#0D0D0F","text-halo-width":1.5}}
         ]}
        """.trimIndent()
    }
}

/**
 * Kartvisning fra det nedlastede kartet. Kameraet følger posisjonen (og kjøreretningen), og posisjonen
 * tegnes midt i kartet av T'OS selv. Kartet tar ikke imot berøring – trykk håndteres av kortet rundt.
 */
@Composable
fun OfflineMapView(gps: Gps?, modifier: Modifier, zoom: Int, rotate: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        // textureMode: kartet følger de runde hjørnene i kortet (vanlig modus tegner oppå alt)
        MapView(context, org.maplibre.android.maps.MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }

    DisposableEffect(lifecycle, mapView) {
        // Hold styr på tilstanden, så MapLibre aldri får start/stopp to ganger (addObserver spiller av START/RESUME selv)
        var started = false; var resumed = false
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> if (!started) { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { mapView.onStop(); started = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        mapView.getMapAsync { m ->
            m.uiSettings.setAllGesturesEnabled(false)
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isCompassEnabled = false
            m.setStyle(Style.Builder().fromJson(OfflineMaps.style(context)))
            map = m
        }
        onDispose {
            lifecycle.removeObserver(obs)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }

    // MapLibre bruker 512-fliser: én zoom lavere gir samme målestokk som nettkartet
    LaunchedEffect(map, gps?.lat, gps?.lon, zoom, rotate) {
        val m = map ?: return@LaunchedEffect
        val g = gps ?: return@LaunchedEffect
        val pos = CameraPosition.Builder()
            .target(LatLng(g.lat, g.lon))
            .zoom(zoom - 1.0)
            .bearing(if (rotate) g.bearing.toDouble() else 0.0)
            .build()
        m.easeCamera(CameraUpdateFactory.newCameraPosition(pos), 900)
    }

    Box(modifier) {
        AndroidView({ mapView }, Modifier.fillMaxSize())
        if (Theme.night) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        if (gps != null) Canvas(Modifier.fillMaxSize()) {
            val c = center
            if (rotate) {
                val p = Path().apply { moveTo(c.x, c.y - 16f); lineTo(c.x + 11f, c.y + 12f); lineTo(c.x, c.y + 6f); lineTo(c.x - 11f, c.y + 12f); close() }
                drawCircle(TextPrimary.copy(alpha = 0.15f), 30f, c)
                drawPath(p, TextPrimary)
            } else {
                drawCircle(TextPrimary.copy(alpha = 0.18f), 28f, c)
                drawCircle(TextPrimary, 9f, c)
                drawCircle(Panel, 9f, c, style = Stroke(3f))
            }
        } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Venter på GPS", color = TextMuted, fontSize = 15.sp)
        }
        Text("© OpenStreetMap", color = Color(0xFF55555A), fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 4.dp))
    }
}
