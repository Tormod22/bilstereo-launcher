package no.bil.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/* ---------- Palett: svart, to gråtoner, hvit. Ingen farger. ---------- */
internal val Bg = Color(0xFF000000)
internal val Panel = Color(0xFF111113)
internal val Card = Color(0xFF1C1C1F)
internal val Line = Color(0xFF2A2A2E)
internal val TextPrimary = Color(0xFFF5F5F7)
internal val TextMuted = Color(0xFF86868B)
private val Shape = RoundedCornerShape(28.dp)
private val MapDim = ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(0.6f, 0.6f, 0.6f, 1f) })

class MainActivity : ComponentActivity() {
    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 31) perms += Manifest.permission.BLUETOOTH_CONNECT
        askPermissions.launch(perms.toTypedArray())
        handle(intent)
        setContent { Root() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val s = intent?.getStringExtra(Nav.EXTRA)
        Nav.screen = Screen.entries.firstOrNull { it.name == s } ?: Screen.DASH
    }

    /** Knappene på stereoen når T'OS er framme og knappestyring (tjenesten) ikke er slått på. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (Learn.target != null && event.action == KeyEvent.ACTION_DOWN &&
            code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            Learn.offerKey(this, code); return true
        }
        val key = Buttons.keyForCode(this, code)
        if (key != null && Buttons.shouldIntercept(this, key, code)) {
            if (event.action == KeyEvent.ACTION_UP) Buttons.perform(this, Buttons.action(this, key))
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
fun Root() {
    val home = { Nav.screen = Screen.DASH }
    Box(Modifier.fillMaxSize().background(Bg).padding(14.dp)) {
        if (Nav.screen != Screen.DASH) BackHandler { Learn.cancel(); home() }
        when (Nav.screen) {
            Screen.DASH -> Dashboard(onApps = { Nav.screen = Screen.APPS })
            Screen.APPS -> AppDrawer(home)
            Screen.BT -> BluetoothScreen(home)
            Screen.KEYS -> KeysScreen { Learn.cancel(); home() }
        }
    }
}

/* ======================= Dashbord: tre ting ======================= */

@Composable
fun Dashboard(onApps: () -> Unit) {
    val context = LocalContext.current
    val gps by gpsFlow(context).collectAsState(initial = null)

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CarCard(gps, Modifier.weight(0.5f).fillMaxHeight())
        Column(Modifier.weight(0.5f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            MapCard(gps, Modifier.weight(1f).fillMaxWidth())
            ActionRow(onApps, Modifier.height(96.dp).fillMaxWidth())
        }
    }
}

/* ---------- 1. Bilen: klokke, bilde, fart ---------- */

private fun carImage(context: Context): Int? {
    val id = context.resources.getIdentifier("car_png", "drawable", context.packageName)
    return if (id != 0) id else null
}

@Composable
fun CarCard(gps: Gps?, modifier: Modifier) {
    val context = LocalContext.current
    val img = remember { carImage(context) }
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000) } }
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
    val date = SimpleDateFormat("EEEE d. MMMM", Locale("nb", "NO")).format(now).replaceFirstChar { it.uppercase() }

    Column(modifier.clip(Shape).background(Panel).padding(28.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(time, color = TextPrimary, fontSize = 40.sp, fontWeight = FontWeight.Medium)
                Text(date, color = TextMuted, fontSize = 15.sp)
            }
            Text("Avensis", color = TextMuted, fontSize = 15.sp)
        }

        // Bilbildet – stort og luftig, på en svak lys "scene" under
        Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.5f).align(Alignment.BottomCenter)
                    .background(Brush.radialGradient(listOf(Color(0xFF26262A), Panel)), CircleShape)
            )
            if (img != null) {
                Image(painterResource(img), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.DirectionsCar, null, tint = Line, modifier = Modifier.fillMaxSize(0.55f))
            }
        }

        // Farten – stort tall og én tynn linje
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text("${gps?.speedKmh ?: 0}", color = TextPrimary, fontSize = 76.sp, fontWeight = FontWeight.Medium, lineHeight = 76.sp)
            Spacer(Modifier.width(10.dp))
            Text("km/t", color = TextMuted, fontSize = 18.sp, modifier = Modifier.padding(bottom = 14.dp))
            Spacer(Modifier.weight(1f))
            Text(if (gps == null) "Søker GPS" else "GPS", color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 14.dp))
        }
        SpeedLine(gps?.speedKmh ?: 0)
    }
}

@Composable
fun SpeedLine(speed: Int) {
    val f = (speed / 160f).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Line)) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(TextPrimary))
    }
}

/* ---------- 2. Kart: ekte fliser, trykk åpner Google Maps ---------- */

@Composable
fun MapCard(gps: Gps?, modifier: Modifier) {
    val context = LocalContext.current
    var map by remember { mutableStateOf<Bitmap?>(null) }
    var pos by remember { mutableStateOf(0.5f to 0.5f) }

    // hent nye fliser når posisjonen har flyttet seg et stykke
    val key = gps?.let { (it.lat * 200).toInt() to (it.lon * 200).toInt() }
    LaunchedEffect(key) {
        val g = gps ?: return@LaunchedEffect
        MapTiles.around(g.lat, g.lon)?.let { bmp ->
            map = bmp
            MapTiles.lastImage?.let { pos = it.px to it.py }
        }
    }

    Box(modifier.clip(Shape).background(Panel).clickable { openMaps(context) }) {
        val m = map
        if (m != null) {
            val img = remember(m) { m.asImageBitmap() }
            Canvas(Modifier.fillMaxSize()) {
                // Sentrer kartet på posisjonen og skaler så kortet alltid er fylt
                val bw = img.width.toFloat(); val bh = img.height.toFloat()
                val x = bw * pos.first; val y = bh * pos.second
                val s = maxOf(
                    size.width / (2 * minOf(x, bw - x).coerceAtLeast(1f)),
                    size.height / (2 * minOf(y, bh - y).coerceAtLeast(1f)),
                )
                drawImage(
                    img,
                    dstOffset = IntOffset((center.x - x * s).roundToInt(), (center.y - y * s).roundToInt()),
                    dstSize = IntSize((bw * s).roundToInt(), (bh * s).roundToInt()),
                    colorFilter = MapDim,   // litt mørkere, så kartet passer de mørke kortene
                )
                val c = center
                drawCircle(TextPrimary.copy(alpha = 0.18f), 28f, c)
                drawCircle(TextPrimary, 9f, c)
                drawCircle(Panel, 9f, c, style = Stroke(3f))
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (gps == null) "Venter på GPS" else "Laster kart…", color = TextMuted, fontSize = 15.sp)
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(16.dp)
                .clip(RoundedCornerShape(18.dp)).background(Card.copy(alpha = 0.92f))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.Navigation, null, tint = TextPrimary, modifier = Modifier.size(22.dp))
            Text("Navigasjon", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        }
    }
}

fun openMaps(context: Context) {
    val tile = Tiles.byPrefix("Nav")
    val pkg = tile.candidates.firstOrNull { AppRepository.isInstalled(context, it) }
    val intent = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
        ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0"))
    // LAUNCH_ADJACENT = delt skjerm der enheten støtter det, ellers fullskjerm
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
    runCatching { context.startActivity(intent) }
}

/* ---------- 3. Én rad: musikk, telefon, apper ---------- */

@Composable
fun ActionRow(onApps: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    fun tile(p: String) = Tiles.byPrefix(p)
    val music = tile("Mus")
    val musicIcon = remember { AppRepository.tileIcon(context, music) }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier.weight(1.6f).fillMaxHeight().clip(Shape).background(Panel).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Card)
                    .clickable { AppRepository.launchTile(context, music) },
                contentAlignment = Alignment.Center,
            ) {
                if (musicIcon != null) Image(musicIcon.toBitmap(96, 96).asImageBitmap(), null, Modifier.size(32.dp))
                else Icon(Icons.Default.MusicNote, null, tint = TextPrimary, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.weight(1f))
            RoundIcon(Icons.Default.SkipPrevious, 44.dp) { Media.previous(context) }
            RoundIcon(Icons.Default.PlayArrow, 56.dp, outlined = true) { Media.playPause(context) }
            RoundIcon(Icons.Default.SkipNext, 44.dp) { Media.next(context) }
        }
        ActionTile(Icons.Default.Phone, "Telefon", Modifier.weight(1f)) { AppRepository.launchTile(context, tile("Tel")) }
        ActionTile(Icons.Default.Apps, "Apper", Modifier.weight(1f)) { onApps() }
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
fun RoundIcon(icon: ImageVector, size: Dp, outlined: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .then(if (outlined) Modifier.border(1.5.dp, TextPrimary, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = TextPrimary, modifier = Modifier.size(if (outlined) 30.dp else 26.dp)) }
}

/* ======================= Oppdatering fra GitHub ======================= */

@Composable
fun UpdateButton() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf("Se etter oppdatering") }
    var available by remember { mutableStateOf<Updater.Info?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        Updater.check()?.let {
            if (it.versionCode > Updater.current(context)) { available = it; state = "Oppdater til ${it.versionName}" }
            else state = "Siste versjon"
        }
    }

    Row(
        Modifier.height(48.dp).clip(RoundedCornerShape(24.dp))
            .background(if (available != null) TextPrimary else Card)
            .clickable(enabled = !busy) {
                scope.launch {
                    busy = true
                    val upd = available ?: Updater.check()
                    if (upd == null) state = "Ingen kontakt med GitHub"
                    else if (upd.versionCode <= Updater.current(context)) state = "Siste versjon"
                    else {
                        available = upd
                        val ok = Updater.downloadAndInstall(context) { p -> state = "Laster ned $p %" }
                        state = if (ok) "Installerer…" else "Nedlasting feilet"
                    }
                    busy = false
                }
            }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.Refresh, null, tint = if (available != null) Bg else TextPrimary, modifier = Modifier.size(20.dp))
        Text(state, color = if (available != null) Bg else TextPrimary, fontSize = 14.sp)
    }
}

/* ======================= Velg startskjerm ======================= */

/** Mange bilstereoer skjuler valget av startskjerm. Denne åpner det direkte, eller nærmeste innstilling. */
private fun openHomeSettings(context: Context) {
    val tries = listOf(
        Intent(android.provider.Settings.ACTION_HOME_SETTINGS),
        Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(android.provider.Settings.ACTION_SETTINGS),
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
fun PillButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = TextPrimary, modifier = Modifier.size(20.dp))
        Text(label, color = TextPrimary, fontSize = 14.sp)
    }
}

/* ======================= App-skuff ======================= */

@Composable
fun AppDrawer(onClose: () -> Unit) {
    val context = LocalContext.current
    val apps by produceState(initialValue = emptyList<AppEntry>()) { value = AppRepository.installedApps(context) }

    Column(Modifier.fillMaxSize().clip(Shape).background(Panel).padding(24.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Apper", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            PillButton(Icons.Default.Tune, "Knapper") { Nav.screen = Screen.KEYS }
            Spacer(Modifier.width(12.dp))
            PillButton(Icons.Default.Bluetooth, "Bluetooth") { Nav.screen = Screen.BT }
            Spacer(Modifier.width(12.dp))
            HomeButton()
            Spacer(Modifier.width(12.dp))
            UpdateButton()
            Spacer(Modifier.width(12.dp))
            RoundIcon(Icons.Default.Close, 48.dp) { onClose() }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(apps, key = { it.packageName }) { app ->
                Column(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(Card)
                        .clickable { AppRepository.launch(context, app.packageName) }
                        .padding(14.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(app.icon.toBitmap(96, 96).asImageBitmap(), null, Modifier.size(52.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(app.label, color = TextPrimary, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
}
