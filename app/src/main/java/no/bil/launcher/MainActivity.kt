package no.bil.launcher

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val Shape = RoundedCornerShape(28.dp)
private val MapDim = ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(0.6f, 0.6f, 0.6f, 1f) })
private val MapDimNight = ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(0.42f, 0.42f, 0.42f, 1f) })

class MainActivity : ComponentActivity() {
    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Hub.startGps() }
    private val downAt = mutableMapOf<Int, Long>()
    private val longDone = mutableSetOf<Int>()
    /** Knapper som nettopp ble lært: slipp-signalet (og gjentak) skal ikke også utløse handlingen. */
    private val swallowUp = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Android 12+ ignorerer spørsmålet om nøyaktig posisjon hvis ikke omtrentlig spørres om samtidig
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 31) perms += Manifest.permission.BLUETOOTH_CONNECT
        askPermissions.launch(perms.toTypedArray())
        handle(intent)
        if (Startup.isFreshBoot(this) && !SafeMode.on) Startup.run(applicationContext)
        setContent { Root() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val s = intent?.getStringExtra(Nav.EXTRA)
        val next = Screen.entries.firstOrNull { it.name == s } ?: Screen.DASH
        // Hjem lukker alt som ligger oppå (hurtigpanel, app-velgere)
        Nav.quick = false; Nav.pick = null; Ui.pickSlot = false
        // Førstegangsoppsettet blir stående til det er fullført
        Nav.screen = if (next == Screen.DASH && !Prefs.bool("setup_done")) Screen.SETUP else next
        // Læring av en knapp gjelder bare mens Knapper-siden er åpen
        if (Nav.screen != Screen.KEYS && Learn.target != null) Learn.cancel()
    }

    /** Knappene på stereoen når T'OS er framme og knappestyring (tjenesten) ikke er slått på. */
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code in swallowUp) {
            if (event.action == KeyEvent.ACTION_UP) { swallowUp -= code; return true }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) return true
            swallowUp -= code   // nytt trykk uten at slipp kom: behandles vanlig
        }
        if (Learn.target != null && event.action == KeyEvent.ACTION_DOWN &&
            code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            // Utløpt læring (offerKey = false): knappen skal virke som vanlig, ikke bli «spist»
            if (Learn.offerKey(this, code)) { swallowUp += code; return true }
        }
        val key = Buttons.keyForCode(this, code)
        if (key != null && Buttons.shouldIntercept(this, key, code)) {
            KeyService.handlePress(this, key, event, downAt, longDone)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/* ======================= Rot: skjermene + det som ligger oppå ======================= */

/** Felles skjermtilstand som må kunne endres utenfra (Hjem-knappen, volumhjulet). */
object Ui {
    /** «Velg app for midtflisa» er åpen. */
    var pickSlot by mutableStateOf(false)
    /** Musikkvolumet akkurat nå (følger volumhjulet). -1 = ukjent. */
    var volume by mutableIntStateOf(-1)
}

@Composable
fun Root() {
    val context = LocalContext.current
    val home = { Nav.screen = Screen.DASH }
    var lastTouch by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var volumeShown by remember { mutableStateOf<Int?>(null) }

    // Nattmodus og lysstyrke for T'OS sitt vindu
    LaunchedEffect(Theme.mode) { Theme.update(Hub.gps?.lat, Hub.gps?.lon) }
    val appBright = Prefs.float("app_brightness", -1f)
    LaunchedEffect(Theme.night, appBright) {
        (context as? Activity)?.window?.let { w ->
            w.attributes = w.attributes.apply {
                screenBrightness = when {
                    // Ikke valgt egen lysstyrke: stereoen bestemmer (også om natten, se NightBrightness)
                    appBright < 0 -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    Theme.night -> min(appBright, 0.25f)
                    else -> appBright
                }
            }
        }
    }

    // Klokke som skjermsparer når bilen står stille og ingen rører skjermen
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            val mins = Prefs.str("saver_min", "3").toIntOrNull() ?: 0
            val still = (Hub.gps?.speedKmh ?: 0) < 3
            // Ikke over neste sving (navigasjon i kø) eller en app-velger som er åpen
            if (mins > 0 && still && Nav.screen == Screen.DASH && !Nav.quick && Nav.pick == null && !Ui.pickSlot &&
                !NavInfo.active && SystemClock.elapsedRealtime() - lastTouch > mins * 60_000L) Nav.screen = Screen.CLOCK
        }
    }

    // Volum: vis et diskret tall når volumhjulet brukes
    DisposableEffect(Unit) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var last = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        Ui.volume = last
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (v != last) {
                    last = v; Ui.volume = v
                    // Hurtigpanelet har sin egen volumglider – ikke legg tallet oppå den
                    if (!Nav.quick) volumeShown = v
                }
            }
        }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, obs)
        onDispose { context.contentResolver.unregisterContentObserver(obs) }
    }
    LaunchedEffect(volumeShown) { if (volumeShown != null) { delay(1500); volumeShown = null } }

    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * Theme.textScale)) {
        Box(
            Modifier.fillMaxSize().background(Bg)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) { awaitPointerEvent(PointerEventPass.Initial); lastTouch = SystemClock.elapsedRealtime() }
                    }
                }
        ) {
            Box(Modifier.fillMaxSize().padding(14.dp)) {
                // BACK på forsiden gjør ingenting (ellers lukkes T'OS og stereoens gamle startskjerm vises)
                BackHandler { if (Nav.screen != Screen.DASH) { Learn.cancel(); home() } }
                when (Nav.screen) {
                    Screen.DASH -> Dashboard(onApps = { Nav.screen = Screen.APPS }, onPickSlot = { Ui.pickSlot = true })
                    Screen.APPS -> AppDrawer(home)
                    Screen.BT -> BluetoothScreen(home)
                    Screen.KEYS -> KeysScreen { Learn.cancel(); home() }
                    Screen.MUSIC -> MusicScreen(home)
                    Screen.MAP -> MapScreen(home)
                    Screen.NAV -> NavScreen(home)
                    Screen.RADIO -> RadioScreen(home)
                    Screen.CAR -> CarScreen(home)
                    Screen.PHONE -> PhoneScreen(home)
                    Screen.SETTINGS -> SettingsScreen(home)
                    Screen.TUNING -> TuningScreen(home)
                    Screen.SETUP -> SetupScreen(home)
                    Screen.CLOCK -> {}
                }
            }
            if (Nav.screen == Screen.CLOCK) ClockScreen(home)
            if (Nav.quick) { BackHandler { Nav.quick = false }; QuickPanel { Nav.quick = false } }
            Nav.pick?.let { (title, key) -> BackHandler { Nav.pick = null }; AppPicker(title, "Automatisk", { Prefs.set(key, it) }) { Nav.pick = null } }
            if (Ui.pickSlot) { BackHandler { Ui.pickSlot = false }; AppPicker("Velg app for midtflisa", "Telefon (standard)", { Prefs.set("slot_pkg", it) }) { Ui.pickSlot = false } }
            if (!Nav.quick) volumeShown?.let { VolumeOverlay(it, Modifier.align(Alignment.Center)) }
            Call.name?.let { CallBanner(it, Modifier.align(Alignment.TopCenter)) }
        }
    }
}

@Composable
private fun VolumeOverlay(v: Int, modifier: Modifier) {
    val context = LocalContext.current
    val max = remember { (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    Row(modifier.clip(RoundedCornerShape(28.dp)).background(Card).padding(horizontal = 28.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(if (v == 0) Icons.Default.VolumeOff else Icons.Default.VolumeUp, null, tint = TextPrimary, modifier = Modifier.size(32.dp))
        Box(Modifier.width(220.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Line)) {
            Box(Modifier.fillMaxWidth(v / max.coerceAtLeast(1).toFloat()).fillMaxHeight().background(TextPrimary))
        }
        Text("$v", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CallBanner(name: String, modifier: Modifier) {
    Row(modifier.padding(top = 20.dp).clip(RoundedCornerShape(28.dp)).background(TextPrimary).clickable { Call.openCall() }
        .padding(horizontal = 28.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(Icons.Default.Call, null, tint = Bg, modifier = Modifier.size(28.dp))
        Text("$name ringer", color = Bg, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 460.dp))
        Text("Trykk for å svare", color = Bg.copy(alpha = 0.6f), fontSize = 15.sp)
    }
}

/* ======================= Dashbord: tre ting ======================= */

@Composable
fun Dashboard(onApps: () -> Unit, onPickSlot: () -> Unit) {
    var drag by remember { mutableStateOf(Offset.Zero) }
    Row(
        Modifier.fillMaxSize().pointerInput(Unit) {
            // Sveip: venstre = kart i fullskjerm, høyre = musikk, ned = hurtigpanel
            detectDragGestures(
                onDragStart = { drag = Offset.Zero },
                onDrag = { _, a -> drag += a },
                onDragEnd = {
                    val (x, y) = drag
                    when {
                        y > 140 && y > kotlin.math.abs(x) -> Nav.quick = true
                        x < -160 && kotlin.math.abs(x) > kotlin.math.abs(y) -> Nav.screen = Screen.MAP
                        x > 160 && kotlin.math.abs(x) > kotlin.math.abs(y) -> Nav.screen = Screen.MUSIC
                    }
                },
            )
        },
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CarCard(Hub.gps, Modifier.weight(0.5f).fillMaxHeight())
        Column(Modifier.weight(0.5f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            MapCard(Hub.gps, Modifier.weight(1f).fillMaxWidth())
            ActionRow(onApps, onPickSlot, Modifier.height(96.dp).fillMaxWidth())
        }
    }
}

/* ---------- 1. Bilen: klokke, bilde, fart ---------- */

@Composable
private fun carBitmap(): androidx.compose.ui.graphics.ImageBitmap? {
    val context = LocalContext.current
    val v = Prefs.long("car_image_v")
    if (SafeMode.on) return null
    // Leses i bakgrunnen, krympet – et stort kamerabilde ville ellers kunnet krasje forsiden ved hver oppstart
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, v) {
        value = withContext(Dispatchers.IO) {
            File(context.filesDir, "car.png").takeIf { it.exists() }?.let { decodeScaled(it, 1200)?.asImageBitmap() }
        }
    }
    return bmp
}

/** Leser et bilde krympet slik at lengste side er høyst [max] piksler. */
fun decodeScaled(f: File, max: Int): android.graphics.Bitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var sample = 1
    while (maxOf(o.outWidth, o.outHeight) / sample > max) sample *= 2
    BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()


/** Én diskret linje under datoen: ny versjon, påminnelse eller svakt batteri. */
@Composable
private fun Notice() {
    val due = Reminders.due().firstOrNull()
    val bv = CarBattery.volt
    val (text, go) = when {
        SafeMode.on -> "Sikker modus etter flere krasj – trykk for å sende feilrapport" to Screen.SETTINGS
        CarBattery.enabled && bv != null && (CarBattery.low || CarBattery.high) -> "${if (CarBattery.low) "Svakt batteri" else "Høy ladespenning"} (${CarBattery.text(bv)})" to Screen.CAR
        due != null -> due.text to Screen.CAR
        Hub.update != null -> "Ny versjon ${Hub.update?.versionName} – trykk for å oppdatere" to Screen.APPS
        else -> return
    }
    // Minst 48 dp høy, så den kan treffes under kjøring
    Row(Modifier.padding(top = 8.dp).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(Card).clickable {
        if (go == Screen.SETTINGS) Prefs.set("settings_cat", "BACKUP")
        Nav.screen = go
    }
        .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Default.Info, null, tint = TextMuted, modifier = Modifier.size(16.dp))
        Text(text, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CarCard(gps: Gps?, modifier: Modifier) {
    val context = LocalContext.current
    val res = remember { context.resources.getIdentifier("car_png", "drawable", context.packageName).takeIf { it != 0 } }
    val picked = carBitmap()
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000) } }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("EEEE d. MMMM", Locale("nb", "NO")) }
    val time = timeFmt.format(now)
    val date = dateFmt.format(now).replaceFirstChar { it.uppercase() }
    val speed = gps?.speedKmh ?: 0

    Column(modifier.clip(Shape).background(Panel).padding(28.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(time, color = TextPrimary, fontSize = 40.sp, fontWeight = FontWeight.Medium)
                Text(date, color = TextMuted, fontSize = 15.sp)
                Notice()
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!Hub.online) Icon(Icons.Default.WifiOff, "Ikke internett", tint = TextMuted, modifier = Modifier.size(18.dp))
                val bv = CarBattery.volt
                if (CarBattery.enabled && bv != null) {
                    Icon(Icons.Default.BatteryChargingFull, "Bilbatteri", tint = TextMuted, modifier = Modifier.size(18.dp))
                    Text(CarBattery.text(bv), color = TextMuted, fontSize = 15.sp)
                    Spacer(Modifier.width(4.dp))
                }
                Text("Avensis", color = TextMuted, fontSize = 15.sp)
            }
        }

        // Bilbildet – stort og luftig, på en svak lys "scene" under. Trykk = Bil og tur.
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp).clip(RoundedCornerShape(20.dp)).clickable { Nav.screen = Screen.CAR },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.5f).align(Alignment.BottomCenter)
                    .background(Brush.radialGradient(listOf(if (Theme.night) Color(0xFF151517) else Color(0xFF26262A), Panel)), CircleShape)
            )
            when {
                picked != null -> Image(picked, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                res != null -> Image(painterResource(res), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                else -> Icon(Icons.Default.DirectionsCar, null, tint = Line, modifier = Modifier.fillMaxSize(0.55f))
            }
        }

        // Farten – stort tall og én tynn linje. Over fartsgrensen: tallet blir hvitt felt.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            val over = SpeedLimit.over
            Text(
                "$speed", color = if (over) Bg else TextPrimary, fontSize = 76.sp, fontWeight = FontWeight.Medium, lineHeight = 76.sp,
                modifier = if (over) Modifier.clip(RoundedCornerShape(16.dp)).background(TextPrimary).padding(horizontal = 12.dp) else Modifier,
            )
            Spacer(Modifier.width(10.dp))
            Text("km/t", color = TextMuted, fontSize = 18.sp, modifier = Modifier.padding(bottom = 14.dp))
            Spacer(Modifier.weight(1f))
            Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (gps == null) "Søker GPS" else if (speed > 3) Hub.compass(gps.bearing) else "GPS", color = TextMuted, fontSize = 14.sp)
                LimitSign(46)
            }
        }
        SpeedLine(speed)
    }
}

@Composable
fun SpeedLine(speed: Int) {
    val f = (speed / 160f).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Line)) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(TextPrimary))
    }
}

/* ---------- 2. Kart: ekte fliser, zoom etter fart, snur etter kjøreretning ---------- */

/** Esri World_Dark_Gray_Base har fliser bare til zoom 16. */
internal const val MAX_TILE_ZOOM = 16

/** Zoomnivået flisene hentes på. */
internal fun tileZoom(zoom: Int): Int = min(zoom, MAX_TILE_ZOOM)

/** Hvor mye et kartbilde hentet på [imageZoom] må forstørres for å vises på [zoom] (aldri forminsket). */
internal fun overZoom(zoom: Int, imageZoom: Int): Float = (1 shl (zoom - imageZoom).coerceIn(0, 8)).toFloat()

/** Hvilken kartflis (x, y) en posisjon ligger i på zoom [z] (samme regning som MapTiles). */
internal fun tileOf(lat: Double, lon: Double, z: Int): Pair<Int, Int> {
    val n = (1 shl z).toDouble()
    val latR = Math.toRadians(lat.coerceIn(-85.0, 85.0))
    val x = ((lon + 180.0) / 360.0 * n).toInt()
    val y = ((1.0 - kotlin.math.ln(kotlin.math.tan(latR) + 1 / kotlin.math.cos(latR)) / Math.PI) / 2.0 * n).toInt()
    return x to y
}

/**
 * Hvor langt (i piksler) kartbildet rekker ut fra posisjonen. Så lenge posisjonen er i midtflisa er det
 * alltid minst [half] hele fliser – da brukes det faste tallet, så kartet ikke zoomer inn og ut mens du kjører.
 */
internal fun mapReach(pos: Float, size: Float, half: Float): Float = minOf(pos, size - pos, half).coerceAtLeast(1f)

fun zoomFor(speed: Int): Int =
    if (!Prefs.bool("map_zoom_speed", true)) 15 else when { speed < 35 -> 16; speed < 70 -> 15; speed < 100 -> 14; else -> 13 }

@Composable
fun MapView(gps: Gps?, modifier: Modifier, zoomOffset: Int = 0) {
    val context = LocalContext.current
    val zoom = ((gps?.let { zoomFor(it.speedKmh) } ?: 15) + zoomOffset).coerceIn(11, 18)
    val rotate = Prefs.bool("map_rotate", true) && (gps?.speedKmh ?: 0) > 8
    // Nedlastet kart (uten nett) brukes når det finnes, ellers nettkartet.
    // Fila sjekkes bare når kartet lastes ned/slettes, innstillinger endres, eller hvert 30. sekund – ikke hvert sekund.
    val recheck by produceState(0) { while (true) { delay(30_000); value = value + 1 } }
    val offline by remember(recheck, Prefs.version) { derivedStateOf { OfflineMaps.ready(context) } }
    if (offline && !SafeMode.on) { OfflineMapView(gps, modifier, zoom, rotate); return }
    OnlineMapView(gps, modifier, zoom, rotate)
}

@Composable
private fun OnlineMapView(gps: Gps?, modifier: Modifier, zoom: Int, rotate: Boolean) {
    var map by remember { mutableStateOf<MapTiles.MapImage?>(null) }
    val tz = tileZoom(zoom)
    // Nytt bilde hver gang du kjører inn i en ny flis – da ligger posisjonen alltid i midten av bildet
    val key = gps?.let { val (tx, ty) = tileOf(it.lat, it.lon, tz); Triple(tz, tx, ty) }
    val latest by rememberUpdatedState(gps)
    // Hentes på nytt når posisjon/zoom endres eller nettet kommer tilbake. Feiler det, prøves det igjen
    // hvert 10. sekund; ellers hvert minutt (fliser som allerede er hentet, ligger i minnet).
    LaunchedEffect(key, Hub.online) {
        if (key == null) return@LaunchedEffect
        while (true) {
            val g = latest ?: return@LaunchedEffect
            val img = MapTiles.around(g.lat, g.lon, tz)
            if (img != null) map = img
            delay(if (img == null) 10_000 else 60_000)
        }
    }

    Box(modifier) {
        val m = map
        if (m != null && gps != null) {
            val img = remember(m) { m.bitmap.asImageBitmap() }
            val (px, py) = m.pos(gps.lat, gps.lon)
            val bearing = gps.bearing
            Canvas(Modifier.fillMaxSize()) {
                // Sentrer kartet på posisjonen og skaler så kortet alltid er fylt (også når det er snudd)
                val bw = img.width.toFloat(); val bh = img.height.toFloat()
                val x = bw * px; val y = bh * py
                val mx = mapReach(x, bw, bw * (m.cols / 2) / m.cols); val my = mapReach(y, bh, bh * (m.rows / 2) / m.rows)
                val fill = if (rotate) sqrt(size.width * size.width + size.height * size.height) / 2 / min(mx, my)
                        else maxOf(size.width / (2 * mx), size.height / (2 * my))
                // Over zoom 16 finnes ingen fliser: forstørr bildet i stedet
                val s = fill * overZoom(zoom, m.zoom)
                rotate(if (rotate) -bearing else 0f, center) {
                    drawImage(
                        img,
                        dstOffset = IntOffset((center.x - x * s).roundToInt(), (center.y - y * s).roundToInt()),
                        dstSize = IntSize((bw * s).roundToInt(), (bh * s).roundToInt()),
                        colorFilter = if (Theme.night) MapDimNight else MapDim,
                    )
                }
                val c = center
                if (rotate) {
                    // Pil som peker framover
                    val p = Path().apply { moveTo(c.x, c.y - 16f); lineTo(c.x + 11f, c.y + 12f); lineTo(c.x, c.y + 6f); lineTo(c.x - 11f, c.y + 12f); close() }
                    drawCircle(TextPrimary.copy(alpha = 0.15f), 30f, c)
                    drawPath(p, TextPrimary)
                } else {
                    drawCircle(TextPrimary.copy(alpha = 0.18f), 28f, c)
                    drawCircle(TextPrimary, 9f, c)
                    drawCircle(Panel, 9f, c, style = Stroke(3f))
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (gps == null) "Venter på GPS" else "Laster kart …", color = TextMuted, fontSize = 15.sp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MapCard(gps: Gps?, modifier: Modifier) {
    val context = LocalContext.current
    Box(modifier.clip(Shape).background(Panel)) {
        MapView(gps, Modifier.fillMaxSize())
        // Usynlig lag over kartet tar imot trykk (kartet selv skal ikke dras i)
        Box(Modifier.matchParentSize().combinedClickable(onClick = { openMaps(context) }, onLongClick = { Nav.screen = Screen.MAP }))

        // Neste sving fra Google Maps / Waze
        if (NavInfo.active) {
            Column(Modifier.align(Alignment.TopStart).padding(16.dp).clip(RoundedCornerShape(18.dp)).background(Card.copy(alpha = 0.94f))
                .padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text(NavInfo.title ?: "", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                NavInfo.text?.let { Text(it, color = TextMuted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }

        Row(Modifier.align(Alignment.BottomStart).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MapChip(Icons.Default.Navigation, "Navigasjon") { Nav.screen = Screen.NAV }
            if (Prefs.str("addr_home").isNotBlank()) MapChip(Icons.Default.Home, "Hjem") { Navigate.home(context) }
        }
    }
}

@Composable
private fun MapChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(18.dp)).background(Card.copy(alpha = 0.92f)).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = TextPrimary, modifier = Modifier.size(22.dp))
        Text(label, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

/** Åpner kartappen (Google Maps som standard) og viser «Åpne heller Waze»-popupen i 5 sekunder. */
fun openMaps(context: Context) {
    val maps = "com.google.android.apps.maps"; val waze = "com.waze"
    val tile = Tiles.byPrefix("Nav")
    val chosen = Prefs.str("nav_app", maps).takeIf { it.isNotBlank() && AppRepository.isInstalled(context, it) }
    val pkg = chosen ?: tile.candidates.firstOrNull { AppRepository.isInstalled(context, it) }
    val intent = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
        ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0"))
    // LAUNCH_ADJACENT = delt skjerm der enheten støtter det, ellers fullskjerm
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
    // Si fra til knappestyringen at det er T'OS som åpner kartappen (ellers kan den sende deg tilbake)
    (intent.component?.packageName ?: intent.`package` ?: runCatching { intent.resolveActivity(context.packageManager)?.packageName }.getOrNull() ?: pkg)
        ?.let { AppRepository.markLaunched(it) }
    if (runCatching { context.startActivity(intent) }.isFailure) { context.toast("Fant ingen kartapp på stereoen"); return }
    // Tilby den andre kartappen
    val other = if (pkg == waze) maps else waze
    if (AppRepository.isInstalled(context, other)) NaviPopup.show(context, if (other == waze) "Waze" else "Google Maps", other)
}

/* ---------- 3. Én rad: musikk, telefon (valgfri app), apper ---------- */

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActionRow(onApps: () -> Unit, onPickSlot: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val slotPkg = Prefs.str("slot_pkg")
    val slotApp = remember(slotPkg) {
        slotPkg.takeIf { it.isNotBlank() }?.let { p ->
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() to pm.getApplicationIcon(p)
            }.getOrNull()
        }
    }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // Musikk: cover, tittel, spill/pause, neste, og en tynn linje for hvor langt i sangen
        Box(Modifier.weight(2.6f).fillMaxHeight().clip(Shape).background(Panel)) {
            Row(Modifier.fillMaxSize().padding(start = 18.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = { Nav.screen = Screen.MUSIC }, onLongClick = { Buttons.openMusic(context) })) { Cover(52.dp) }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp).clickable { Nav.screen = Screen.MUSIC }) {
                    Text(musicTitle ?: "Musikk", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(musicSub ?: if (MediaListener.enabled(context)) "Ingenting spiller" else "Trykk for mer", color = TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RoundIcon(if (musicPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, 48.dp, outlined = true, desc = if (musicPlaying) "Pause" else "Spill") { Media.playPause(context) }
                RoundIcon(Icons.Default.SkipNext, 48.dp, desc = "Neste sang") { Media.next(context) }
            }
            Progress(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 28.dp, vertical = 10.dp))
        }
        // Midtflisa: Telefon som standard. Langt trykk = velg en annen app.
        Column(
            Modifier.weight(0.8f).fillMaxHeight().clip(Shape).background(Panel)
                // Valgt app avinstallert? Da viser flisa «Telefon», og da skal den også åpne Telefon
                .combinedClickable(onClick = { if (slotApp != null) AppRepository.launch(context, slotPkg) else Nav.screen = Screen.PHONE }, onLongClick = onPickSlot),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (slotApp != null) AppIcon(slotApp.second, 36.dp, slotPkg)
            else Icon(Icons.Default.Phone, null, tint = TextPrimary, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(6.dp))
            Text(slotApp?.first ?: "Telefon", color = TextMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
        }
        ActionTile(Icons.Default.Apps, "Apper", Modifier.weight(0.8f)) { onApps() }
    }
}

/** Velg en app (midtflisa i bunnraden, tuning-app o.l.). Tom tekst = standard. */
@Composable
fun AppPicker(title: String, defaultLabel: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var loaded by remember { mutableStateOf(false) }
    val apps by produceState(initialValue = emptyList<AppEntry>()) { value = withContext(Dispatchers.IO) { AppRepository.installedApps(context) }; loaded = true }
    // Trykk utenfor lukker; trykk på selve siden skal ikke gå gjennom til forsiden under
    Box(Modifier.fillMaxSize().background(Bg.copy(alpha = 0.7f))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() }
        .padding(40.dp)) {
      Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) {
        Page(title, onClose) {
            Pill(defaultLabel, Icons.Default.Refresh) { onPick(""); onClose() }
            Spacer(Modifier.height(14.dp))
            if (!loaded) Text("Henter appene …", color = TextMuted, fontSize = 15.sp)
            else if (apps.isEmpty()) Text("Fant ingen apper å velge.", color = TextMuted, fontSize = 15.sp)
            LazyVerticalGrid(GridCells.Adaptive(120.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(apps, key = { it.packageName }) { app ->
                    Column(Modifier.clip(RoundedCornerShape(18.dp)).background(Card).clickable { onPick(app.packageName); onClose() }
                        .padding(14.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        AppIcon(app.icon, 44.dp, app.packageName)
                        Spacer(Modifier.height(6.dp))
                        Text(app.label, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
      }
    }
}

@Composable
fun ActionTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.fillMaxHeight().clip(Shape).background(Panel).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = TextPrimary, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = TextMuted, fontSize = 14.sp)
    }
}

@Composable
fun RoundIcon(icon: ImageVector, size: Dp, outlined: Boolean = false, desc: String? = null, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .then(if (outlined) Modifier.border(1.5.dp, TextPrimary, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = TextPrimary, modifier = Modifier.size(if (outlined) size * 0.52f else size * 0.58f)) }
}

/* ======================= Oppdatering fra GitHub ======================= */

/** Oppdateringen holdes her (ikke i knappen), så nedlastingen fortsetter når app-skuffen lukkes. */
object UpdateState {
    var text by mutableStateOf("Se etter oppdatering")
    var available by mutableStateOf<Updater.Info?>(null)
    var busy by mutableStateOf(false)
}

@Composable
fun UpdateButton() {
    val context = LocalContext.current
    val st = UpdateState

    LaunchedEffect(Unit) {
        if (st.busy) return@LaunchedEffect
        val it = Updater.check()
        if (st.busy) return@LaunchedEffect
        if (it == null) st.text = Updater.lastError ?: "Se etter oppdatering"
        else if (it.versionCode > Updater.current(context)) { st.available = it; st.text = "Oppdater til ${it.versionName}" }
        else { st.available = null; st.text = "Siste versjon" }
    }

    Row(
        Modifier.height(48.dp).widthIn(max = 300.dp).clip(RoundedCornerShape(24.dp))
            .background(if (st.available != null) TextPrimary else Card)
            .clickable(enabled = !st.busy) {
                val app = context.applicationContext
                st.busy = true
                Hub.scope.launch {
                    try {
                        val upd = st.available ?: Updater.check()
                        if (upd == null) st.text = Updater.lastError ?: "Ingen kontakt med GitHub"
                        else if (upd.versionCode <= Updater.current(app)) st.text = "Siste versjon"
                        else {
                            st.available = upd
                            val err = Updater.downloadAndInstall(app) { p -> st.text = "Laster ned $p %" }
                            st.text = err ?: "Trykk «Installer» på skjermen"
                        }
                    } finally { st.busy = false }
                }
            }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.Refresh, null, tint = if (st.available != null) Bg else TextPrimary, modifier = Modifier.size(20.dp))
        Text(st.text, color = if (st.available != null) Bg else TextPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/* ======================= Velg startskjerm ======================= */

/** Mange bilstereoer skjuler valget av startskjerm. Denne åpner det direkte, eller nærmeste innstilling. */
private fun openHomeSettings(context: Context) {
    val tries = listOf(
        Intent(Settings.ACTION_HOME_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (i in tries) {
        if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

@Composable
fun HomeButton() {
    val context = LocalContext.current
    PillButton(Icons.Default.Home, "Startskjerm") { openHomeSettings(context) }
}

@Composable
fun PillButton(icon: ImageVector, label: String, onClick: () -> Unit) = Pill(label, icon, onClick = onClick)

/* ======================= App-skuff ======================= */

@Composable
fun AppDrawer(onClose: () -> Unit) {
    val context = LocalContext.current
    val apps by produceState(initialValue = emptyList<AppEntry>(), Prefs.str("hidden_apps")) { value = withContext(Dispatchers.IO) { AppRepository.installedApps(context) } }

    Column(Modifier.fillMaxSize().clip(Shape).background(Panel).padding(24.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Apper", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.width(12.dp))
            // Knappene får plassen som er igjen (kan rulles sidelengs med stor tekst), så X alltid synes
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillButton(Icons.Default.Settings, "Innstillinger") { Nav.screen = Screen.SETTINGS }
                PillButton(Icons.Default.Tune, "Knapper") { Nav.screen = Screen.KEYS }
                HomeButton()
                UpdateButton()
            }
            Spacer(Modifier.width(12.dp))
            RoundIcon(Icons.Default.Close, 48.dp, desc = "Lukk") { onClose() }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // T'OS sine egne sider først
            val own = listOf(
                Triple(Icons.Default.Radio, "Radio", Screen.RADIO), Triple(Icons.Default.Bluetooth, "Bluetooth", Screen.BT),
                Triple(Icons.Default.DirectionsCar, "Bil og tur", Screen.CAR), Triple(Icons.Default.Map, "Kart", Screen.MAP),
                Triple(Icons.Default.Settings, "Innstillinger", Screen.SETTINGS), Triple(Icons.Default.Speed, "Tuning", Screen.TUNING),
            )
            items(own, key = { it.second }) { (icon, label, screen) ->
                Column(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(Card).clickable { Nav.screen = screen }.padding(14.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Line), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = TextPrimary, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(label, color = TextPrimary, fontSize = 13.sp, maxLines = 1)
                }
            }
            items(apps, key = { it.packageName }) { app ->
                Column(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(Card)
                        .clickable { AppRepository.launch(context, app.packageName) }
                        .padding(14.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AppIcon(app.icon, 52.dp, app.packageName)
                    Spacer(Modifier.height(8.dp))
                    Text(app.label, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
