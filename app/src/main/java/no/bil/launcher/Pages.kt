package no.bil.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/* ======================= Felles byggeklosser ======================= */

private val Round = RoundedCornerShape(28.dp)
private val Small = RoundedCornerShape(18.dp)

@Composable
fun Page(title: String, onClose: () -> Unit, extra: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().clip(Round).background(Panel).padding(24.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            extra()
            RoundIcon(Icons.Default.Close, 48.dp, desc = "Lukk") { onClose() }
        }
        content()
    }
}

@Composable
fun Pill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, strong: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(if (strong) TextPrimary else Line)
            .clickable(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (strong) Bg else TextPrimary, modifier = Modifier.size(20.dp))
        Text(label, color = if (strong) Bg else TextPrimary, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
fun Section(title: String) =
    Text(title.uppercase(), color = TextMuted, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))

@Composable
fun SettingRow(title: String, sub: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(Small).background(Card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 16.sp)
            if (sub != null) Text(sub, color = TextMuted, fontSize = 13.sp)
        }
        trailing()
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
fun Toggle(on: Boolean) {
    Box(
        Modifier.size(52.dp, 30.dp).clip(RoundedCornerShape(15.dp)).background(if (on) TextPrimary else Line).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) Bg else TextMuted)) }
}

@Composable
fun ToggleRow(title: String, key: String, default: Boolean, sub: String? = null) {
    val on = Prefs.bool(key, default)
    SettingRow(title, sub, onClick = { Prefs.set(key, !on) }) { Toggle(on) }
}

/** Trykk for å bla gjennom valgene. */
@Composable
fun ChoiceRow(title: String, key: String, options: List<Pair<String, String>>, default: String, sub: String? = null) {
    val cur = Prefs.str(key, default)
    val idx = options.indexOfFirst { it.first == cur }.coerceAtLeast(0)
    SettingRow(title, sub, onClick = { Prefs.set(key, options[(idx + 1) % options.size].first) }) {
        Text(options[idx].second, color = TextPrimary, fontSize = 15.sp)
        Icon(Icons.Default.UnfoldMore, null, tint = TextMuted, modifier = Modifier.padding(start = 6.dp).size(20.dp))
    }
}

@Composable
fun Field(value: String, hint: String, modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text, onDone: (() -> Unit)? = null, onChange: (String) -> Unit) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(Line).padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text(hint, color = TextMuted, fontSize = 16.sp)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
            cursorBrush = SolidColor(TextPrimary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (onDone != null) ImeAction.Go else ImeAction.Done),
            keyboardActions = KeyboardActions(onGo = { onDone?.invoke() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Tekstfelt som lagres i innstillingene når det endres. */
@Composable
fun PrefField(title: String, key: String, hint: String, keyboard: KeyboardType = KeyboardType.Text) {
    var v by remember { mutableStateOf(Prefs.str(key)) }
    Row(Modifier.fillMaxWidth().clip(Small).background(Card).padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = TextPrimary, fontSize = 16.sp, modifier = Modifier.width(170.dp))
        Field(v, hint, Modifier.weight(1f), keyboard) { v = it; Prefs.set(key, it) }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
fun BigNumber(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(Small).background(Card).padding(16.dp)) {
        Text(value, color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(label, color = TextMuted, fontSize = 13.sp)
    }
}

fun Context.toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

/* ======================= Musikk (punkt 1, 2, 4, 6) ======================= */

@Composable
fun Cover(size: androidx.compose.ui.unit.Dp, radius: androidx.compose.ui.unit.Dp = 14.dp) {
    val context = LocalContext.current
    val art = NowPlaying.art
    val icon = remember(NowPlaying.pkg, Prefs.str("music_pkg")) {
        (NowPlaying.pkg ?: Prefs.str("music_pkg").ifBlank { null })
            ?.let { runCatching { context.packageManager.getApplicationIcon(it) }.getOrNull() }
            ?: AppRepository.tileIcon(context, Tiles.byPrefix("Mus"))
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(Card), contentAlignment = Alignment.Center) {
        when {
            NetRadio.active -> Icon(Icons.Default.Radio, null, tint = TextPrimary, modifier = Modifier.size(size * 0.5f))
            art != null -> Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            icon != null -> AppIcon(icon, size * 0.7f, "cover:" + (NowPlaying.pkg ?: Prefs.str("music_pkg")))
            else -> Icon(Icons.Default.MusicNote, null, tint = TextPrimary, modifier = Modifier.size(size * 0.5f))
        }
    }
}

val musicTitle: String? get() = if (NetRadio.active) NetRadio.current?.name else NowPlaying.title
val musicSub: String? get() = if (NetRadio.active) (if (NetRadio.buffering) "Kobler til …" else NetRadio.error ?: "Nettradio") else NowPlaying.artist
val musicPlaying: Boolean get() = if (NetRadio.active) NetRadio.playing else NowPlaying.playing

@Composable
fun Progress(modifier: Modifier) {
    var p by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(NowPlaying.title, NowPlaying.playing) {
        while (true) {
            val d = NowPlaying.durationMs
            p = if (d > 0 && !NetRadio.active) (NowPlaying.positionMs().toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(1000)
        }
    }
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(Line)) {
        Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(TextPrimary))
    }
}

@Composable
fun MusicScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    Page("Musikk", onClose, extra = {
        Pill("Åpne app", Icons.Default.OpenInNew) { Buttons.openMusic(context) }
        Spacer(Modifier.width(12.dp))
    }) {
      // Coveret krymper når skjermen er lav (stor tekst / 1024×600), så knappene under ikke kuttes
      BoxWithConstraints(Modifier.fillMaxSize()) {
        val coverSize = minOf(300.dp, maxHeight)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(coverSize, 24.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(musicTitle ?: "Ingen musikk spiller", color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(musicSub ?: "", color = TextMuted, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Progress(Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundIcon(Icons.Default.SkipPrevious, 64.dp, desc = "Forrige sang") { Media.previous(context) }
                    RoundIcon(if (musicPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, 84.dp, outlined = true, desc = if (musicPlaying) "Pause" else "Spill") { Media.playPause(context) }
                    RoundIcon(Icons.Default.SkipNext, 64.dp, desc = "Neste sang") { Media.next(context) }
                }
                if (!MediaListener.enabled(context)) {
                    Spacer(Modifier.height(16.dp))
                    Pill("Vis sangtittel og cover (slå på varseltilgang)", Icons.Default.Info) { openNotificationAccess(context) }
                }
            }
        }
      }
    }
}

fun openNotificationAccess(c: Context) {
    runCatching { c.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/* ======================= Radio (punkt 8) ======================= */

@Composable
fun RadioScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    Page("Radio", onClose, extra = {
        if (NetRadio.active) { Pill("Stopp nettradio", Icons.Default.Stop) { NetRadio.stop(context) }; Spacer(Modifier.width(12.dp)) }
    }) {
        // Stereoens ekte FM/AM-radio
        val hw = HwRadio.state
        Row(
            Modifier.fillMaxWidth().clip(Small).background(Card).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("STEREOENS RADIO", color = TextMuted, fontSize = 12.sp, letterSpacing = 1.sp)
                if (hw != null) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(hw.freq, color = TextPrimary, fontSize = 40.sp, fontWeight = FontWeight.Medium)
                        Text(if (hw.isAm) "kHz" else "MHz", color = TextMuted, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
                        Text(hw.band, color = TextMuted, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    hw.station?.let { Text(it, color = TextMuted, fontSize = 15.sp, maxLines = 1) }
                } else Text("Trykk «Start radio». Frekvensen vises her når radioen spiller.", color = TextMuted, fontSize = 15.sp)
            }
            if (hw != null) {
                RoundIcon(Icons.Default.FastRewind, 52.dp, desc = "Søk ned") { HwRadio.seekDown() }
                RoundIcon(Icons.Default.FastForward, 52.dp, desc = "Søk opp") { HwRadio.seekUp() }
                if (hw.canTune) { Pill("FM") { HwRadio.cycleFm() }; Pill("AM") { HwRadio.am() } }
            }
            Pill(if (hw != null) "Åpne" else "Start radio", Icons.Default.Radio, strong = hw == null) { HwRadio.open(context) }
        }
        Section("Nettradio")
        NetRadio.error?.let { Text(it, color = TextMuted, fontSize = 15.sp, modifier = Modifier.padding(bottom = 10.dp)) }
        LazyVerticalGrid(GridCells.Fixed(4), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(NetRadio.stations) { s ->
                val on = NetRadio.current == s
                Column(
                    Modifier.height(120.dp).clip(Small).background(if (on) TextPrimary else Card)
                        .clickable { if (on) NetRadio.toggle(context) else NetRadio.play(context, s) }.padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(s.name, color = if (on) Bg else TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        when { !on -> "Nettradio"; NetRadio.buffering -> "Kobler til …"; NetRadio.playing -> "Spiller"; else -> "Pause" },
                        color = if (on) Bg else TextMuted, fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

/* ======================= Navigasjon (punkt 9, 15, 38) ======================= */

@Composable
fun NavScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var q by remember { mutableStateOf("") }
    Page("Navigasjon", onClose, extra = {
        Pill("Kart + musikk", Icons.Default.VerticalSplit) { Buttons.splitScreen(context) }
        Spacer(Modifier.width(12.dp))
        Pill("Åpne kartappen", Icons.Default.Map) { openMaps(context) }
        Spacer(Modifier.width(12.dp))
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val go = { if (q.isBlank()) context.toast("Skriv hvor du vil først") else Navigate.to(context, q) }
            Field(q, "Hvor vil du? Adresse eller sted", Modifier.weight(1f), onDone = go) { q = it }
            Pill("Kjør", Icons.Default.Navigation, strong = true, onClick = go)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.height(110.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BigTile(Icons.Default.Home, if (Prefs.str("addr_home").isBlank()) "Sett hjem" else "Hjem", Modifier.weight(1f)) { Navigate.home(context) }
            BigTile(Icons.Default.Work, if (Prefs.str("addr_work").isBlank()) "Sett jobb" else "Jobb", Modifier.weight(1f)) { Navigate.work(context) }
        }
        Section("Siste steder")
        val rec = Navigate.recents()
        if (rec.isEmpty()) Text("Steder du navigerer til fra T'OS havner her.", color = TextMuted, fontSize = 15.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rec) { r ->
                Row(Modifier.fillMaxWidth().height(56.dp).clip(Small).background(Card).clickable { Navigate.to(context, r) }.padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.History, null, tint = TextMuted)
                    Text(r, color = TextPrimary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/* ======================= Kart i fullskjerm (punkt 10, 11) ======================= */

@Composable
fun MapScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var zoomOffset by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().clip(Round).background(Panel)) {
        MapView(Hub.gps, Modifier.fillMaxSize(), zoomOffset = zoomOffset)
        Column(Modifier.align(Alignment.TopEnd).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val rot = Prefs.bool("map_rotate", true)
            MapButton(Icons.Default.Close, "Lukk") { onClose() }
            MapButton(Icons.Default.Add, "Zoom inn") { zoomOffset = (zoomOffset + 1).coerceAtMost(3) }
            MapButton(Icons.Default.Remove, "Zoom ut") { zoomOffset = (zoomOffset - 1).coerceAtLeast(-4) }
            MapButton(if (rot) Icons.Default.Explore else Icons.Default.NorthEast, if (rot) "Kartet snur etter kjøreretningen" else "Nord er alltid opp") {
                Prefs.set("map_rotate", !rot)
                context.toast(if (rot) "Nord er alltid opp" else "Kartet snur etter kjøreretningen")
            }
        }
        Row(Modifier.align(Alignment.BottomStart).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Navigasjon", Icons.Default.Navigation) { Nav.screen = Screen.NAV }
            Pill("Åpne kartappen", Icons.Default.Map) { openMaps(context) }
        }
        SpeedBadge(Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}

@Composable
private fun MapButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(CircleShape).background(Card.copy(alpha = 0.92f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = TextPrimary)
    }
}

/** Fart + fartsgrense i et lite felt (kartskjermen). */
@Composable
fun SpeedBadge(modifier: Modifier) {
    Row(modifier.clip(Small).background(Card.copy(alpha = 0.92f)).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${Hub.gps?.speedKmh ?: 0}", color = TextPrimary, fontSize = 34.sp, fontWeight = FontWeight.Medium)
        Text("km/t", color = TextMuted, fontSize = 14.sp)
        LimitSign(40)
    }
}

/** Fartsgrenseskilt i T'OS-stil: hvit ring, ingen rødfarge. Fylt når du er over grensen. */
@Composable
fun LimitSign(sizeDp: Int) {
    val l = SpeedLimit.limit ?: return
    if (!Prefs.bool("speed_limit", true)) return
    val over = SpeedLimit.over
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(if (over) TextPrimary else Panel).border(3.dp, TextPrimary, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text("$l", color = if (over) Bg else TextPrimary, fontSize = (sizeDp * 0.38f).sp, fontWeight = FontWeight.Bold) }
}

/* ======================= Telefon: favoritter og siste (punkt 24, 26) ======================= */

data class Contact(val name: String, val number: String)

/** Favoritter lagres som «navn|nummer», én per linje – derfor fjernes | og linjeskift fra det som skrives inn. */
internal fun favText(s: String): String = s.replace(Regex("[|\\r\\n]+"), " ").replace(Regex("\\s+"), " ").trim()

/** Samme nummer (uansett mellomrom og bindestrek) regnes som samme person. */
internal fun sameNumber(a: String, b: String): Boolean {
    fun d(s: String) = s.filter { it.isDigit() || it == '+' }
    return d(a).isNotEmpty() && d(a) == d(b)
}

/** Lista etter at [c] er lagt til: et nummer som finnes fra før, byttes ut (nytt navn) i stedet for å dukke opp to ganger. */
internal fun withFavorite(all: List<Contact>, c: Contact): List<Contact> {
    val number = favText(c.number)
    if (number.isBlank()) return all
    val clean = Contact(favText(c.name).ifBlank { number }, number)
    return all.filterNot { sameNumber(it.number, number) } + clean
}

object Favorites {
    fun all(): List<Contact> = Prefs.str("favorites").split("\n").mapNotNull {
        val p = it.split("|"); if (p.size == 2 && p[1].isNotBlank()) Contact(p[0], p[1]) else null
    }
    fun add(c: Contact) {
        if (favText(c.number).isBlank()) return
        Prefs.set("favorites", withFavorite(all(), c).joinToString("\n") { "${it.name}|${it.number}" })
    }
    fun remove(c: Contact) = Prefs.set("favorites", (all() - c).joinToString("\n") { "${it.name}|${it.number}" })

    /**
     * Ring: direkte hvis lov, ellers åpnes nummeret i telefonappen.
     * Stereoen har ikke eget SIM – da ringer den via telefonen over Bluetooth, og et vanlig
     * «ring»-kall forsvinner uten at noe skjer. Da åpnes stereoens telefon-app med nummeret.
     */
    fun call(ctx: Context, number: String) {
        val uri = Uri.parse("tel:" + number.filter { it.isDigit() || it == '+' })
        val pm = ctx.packageManager
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val voice = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) && tm?.isVoiceCapable == true
        val tile = Tiles.byPrefix("Tel")
        if (!voice) {
            val pkg = AppRepository.tilePackage(ctx, tile)
            if (pkg != null) {
                AppRepository.markLaunched(pkg)
                val dial = Intent(Intent.ACTION_DIAL, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { ctx.startActivity(dial) }.isSuccess) return
            }
            AppRepository.launchTile(ctx, tile)
            if (pkg != null) ctx.toast("Ring $number fra telefon-appen")
            return
        }
        val direct = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val i = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { i.resolveActivity(pm)?.packageName }.getOrNull()?.let { AppRepository.markLaunched(it) }
        if (runCatching { ctx.startActivity(i) }.isFailure) AppRepository.launchTile(ctx, tile)
    }
}

fun recentCalls(c: Context): List<Contact> {
    if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return emptyList()
    return runCatching {
        c.contentResolver.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER), null, null, CallLog.Calls.DATE + " DESC")
            ?.use { cur ->
                val out = mutableListOf<Contact>()
                while (cur.moveToNext() && out.size < 12) {
                    val num = cur.getString(1) ?: continue
                    out += Contact(cur.getString(0)?.takeIf { it.isNotBlank() } ?: num, num)
                }
                out.distinctBy { it.number }
            }.orEmpty()
    }.getOrDefault(emptyList())
}

@Composable
fun PhoneScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var permTick by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permTick++ }
    val canLog = remember(permTick) { ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED }
    LaunchedEffect(Unit) {
        val need = arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG)
            .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) ask.launch(need.toTypedArray())   // spør bare om det som mangler
    }
    var adding by remember { mutableStateOf(false) }
    // Sletting krever to trykk: første trykk spør «Slette?», andre sletter
    var confirmDel by remember { mutableStateOf<Contact?>(null) }
    LaunchedEffect(confirmDel) { if (confirmDel != null) { delay(4000); confirmDel = null } }
    var name by remember { mutableStateOf("") }
    var num by remember { mutableStateOf("") }
    val recents by produceState(emptyList<Contact>(), permTick) { value = withContext(Dispatchers.IO) { recentCalls(context) } }

    Page("Telefon", onClose, extra = {
        Pill("Åpne telefon-appen", Icons.Default.Dialpad) { AppRepository.launchTile(context, Tiles.byPrefix("Tel")) }
        Spacer(Modifier.width(12.dp))
    }) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(0.6f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("FAVORITTER", color = TextMuted, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                    Text(if (adding) "Avbryt" else "+ Legg til", color = TextPrimary, fontSize = 15.sp,
                        modifier = Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable { adding = !adding; confirmDel = null }.padding(horizontal = 12.dp, vertical = 14.dp))
                }
                if (adding) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                        Field(name, "Navn", Modifier.weight(1f)) { name = it }
                        Field(num, "Nummer", Modifier.weight(1f), KeyboardType.Phone) { num = it }
                        Pill("Lagre", strong = true) {
                            if (favText(num).isBlank()) context.toast("Skriv inn et nummer først")
                            else { Favorites.add(Contact(name.ifBlank { num }, num)); context.toast("Lagret"); name = ""; num = ""; adding = false }
                        }
                    }
                }
                val favs = Favorites.all()
                if (favs.isEmpty() && !adding) Text("Trykk «+ Legg til» for å lagre folk du ringer ofte.", color = TextMuted, fontSize = 15.sp)
                LazyVerticalGrid(GridCells.Fixed(2), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(favs) { f ->
                        Row(Modifier.height(84.dp).clip(Small).background(Card).clickable { Favorites.call(context, f.number) }.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(48.dp).clip(CircleShape).background(Line), contentAlignment = Alignment.Center) {
                                Text(f.name.take(1).uppercase(), color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(f.name, color = TextPrimary, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(f.number, color = TextMuted, fontSize = 13.sp, maxLines = 1)
                            }
                            if (adding) {
                                val ask = confirmDel == f
                                Box(
                                    Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
                                        .background(if (ask) TextPrimary else Line)
                                        .clickable { if (ask) { Favorites.remove(f); confirmDel = null } else confirmDel = f }
                                        .padding(horizontal = 10.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (ask) Text("Slette?", color = Bg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    else Icon(Icons.Default.Delete, "Slett ${f.name}", tint = TextPrimary)
                                }
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(0.4f).fillMaxHeight().clip(Small).background(Card).padding(16.dp)) {
                Text("SISTE ANROP", color = TextMuted, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp))
                if (recents.isEmpty()) Text(
                    if (!canLog) "T'OS har ikke lov til å se anropslisten. Gi tillatelse når den spør, eller under Innstillinger → Apper."
                    else "Stereoen deler ikke anropslisten med T'OS. Den ligger i telefon-appen.",
                    color = TextMuted, fontSize = 14.sp)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(recents) { r ->
                        Text(r.name, color = TextPrimary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).clickable { Favorites.call(context, r.number) }.padding(vertical = 13.dp, horizontal = 6.dp))
                    }
                }
            }
        }
    }
}

/* ======================= Bil og tur (punkt 18–23, 46, 49) ======================= */

fun qr(text: String, size: Int = 360): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    bmp.setPixels(px, 0, size, 0, 0, size, size)   // alt på én gang i stedet for 130 000 enkeltkall
    return bmp
}

@Composable
fun CarScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val km = Trip.meters / 1000
    val mins = Trip.movingSec / 60
    // Nullstilling krever to trykk, så turen ikke slettes ved et uhell
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(confirmReset) { if (confirmReset) { delay(4000); confirmReset = false } }
    Page("Bil og tur", onClose, extra = {
        Pill(if (confirmReset) "Trykk igjen for å nullstille" else "Nullstill tur", Icons.Default.RestartAlt, strong = confirmReset) {
            if (confirmReset) { Trip.reset(); confirmReset = false; context.toast("Turen er nullstilt") } else confirmReset = true
        }
        Spacer(Modifier.width(12.dp))
    }) {
        // Parkeringen lagres i bakgrunnen uten å tegne skjermen på nytt – sjekk den hvert 5. sekund
        var parkTick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { while (true) { delay(5000); parkTick++ } }
        val park = remember(parkTick, Prefs.version) { Triple(Parking.lat, Parking.lon, Parking.time) }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(0.62f).verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigNumber(String.format(Locale("nb", "NO"), "%.1f", km), "km denne turen", Modifier.weight(1f))
                    BigNumber(if (mins >= 60) "${mins / 60} t ${mins % 60} m" else "$mins min", "kjøretid", Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigNumber("${Trip.avgKmh}", "snittfart km/t", Modifier.weight(1f))
                    BigNumber("${Trip.maxKmh}", "høyeste fart km/t", Modifier.weight(1f))
                    BigNumber(Hub.gps?.let { Hub.compass(it.bearing) } ?: "–", "retning", Modifier.weight(1f))
                }

                Section("Bilbatteri")
                val bv = CarBattery.volt
                if (bv != null) SettingRow("${CarBattery.text(bv)} · ${CarBattery.meaning(bv)}", "Målt av ${CarBattery.source}. Over 13 V betyr at dynamoen lader.")
                else SettingRow("Ingen måling ennå", "Plugg inn en OBD2-brikke og par den med stereoen. ${CarBattery.obdStatus}")

                Section("Tuning og ytelse")
                SettingRow("0–100 km/t: ${Accel.fmt(Accel.last(Accel.runs[0]))}", "Beste: ${Accel.fmt(Accel.best(Accel.runs[0]))} · målt med GPS", onClick = { Nav.screen = Screen.TUNING }) {
                    Pill("Åpne", Icons.Default.Speed) { Nav.screen = Screen.TUNING }
                }

                Section("Påminnelser")
                val due = Reminders.due()
                if (due.isEmpty()) Text("Ingenting å huske på nå. Datoer for EU-kontroll og service settes i Innstillinger.", color = TextMuted, fontSize = 14.sp)
                due.forEach { d -> SettingRow(d.text) { Pill("Ferdig") { Reminders.done(d.key) } } }

                Section("Stereoen")
                SettingRow(
                    "Temperatur ${Hub.cpuTemp?.let { "$it °C" } ?: "ukjent"} · minne ${Hub.memText}",
                    if (Hub.online) "Internett: tilkoblet" else "Internett: ikke tilkoblet",
                )
            }

            // Parkering med QR-kode til iPhonen (punkt 20)
            Column(Modifier.weight(0.38f).fillMaxHeight().clip(Small).background(Card).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Hvor står bilen?", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                if (park.first != 0.0 || park.second != 0.0) {
                    val bmp = remember(park.first, park.second) { qr(Parking.appleMapsUrl()) }
                    Image(bmp.asImageBitmap(), null, Modifier.size(200.dp).clip(RoundedCornerShape(12.dp)))
                    Text("Skann med iPhone-kameraet før du går, så får du posisjonen i Kart.", color = TextMuted, fontSize = 13.sp)
                    Text("Sist stilt: " + SimpleDateFormat("d. MMM HH:mm", Locale("nb", "NO")).format(Date(park.third)), color = TextMuted, fontSize = 13.sp)
                } else Text("Ingen posisjon ennå. Den lagres når bilen står stille.", color = TextMuted, fontSize = 14.sp)
            }
        }
    }
}

/* ======================= Klokke (skjermsparer, punkt 32) ======================= */

@Composable
fun ClockScreen(onClose: () -> Unit) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000) } }
    Box(Modifier.fillMaxSize().background(Bg).clickable { onClose() }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), color = TextPrimary.copy(alpha = 0.85f), fontSize = 150.sp, fontWeight = FontWeight.Light)
            Text(SimpleDateFormat("EEEE d. MMMM", Locale("nb", "NO")).format(now).replaceFirstChar { it.uppercase() }, color = TextMuted, fontSize = 22.sp)
            musicTitle?.let { Text(it, color = TextMuted, fontSize = 16.sp, modifier = Modifier.padding(top = 18.dp).widthIn(max = 700.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/* ======================= Hurtigpanel (sveip ned, punkt 40) ======================= */

@Composable
fun QuickPanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val am = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    // Volumet ligger i Ui.volume, som følger volumhjulet (oppdateres i Root)
    LaunchedEffect(Unit) { Ui.volume = am.getStreamVolume(AudioManager.STREAM_MUSIC) }
    // Har T'OS lov, styrer glideren hele stereoen; ellers bare T'OS-vinduet
    val canWrite = remember { Settings.System.canWrite(context) }
    var bright by remember {
        mutableFloatStateOf(
            if (canWrite) runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.6f)
            else Prefs.float("app_brightness", -1f).let { if (it < 0) 0.7f else it })
    }
    val sliderColors = SliderDefaults.colors(thumbColor = TextPrimary, activeTrackColor = TextPrimary, inactiveTrackColor = Line)

    Box(Modifier.fillMaxSize().background(Bg.copy(alpha = 0.6f)).clickable(onClick = onClose)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)).background(Panel)
                // Tar imot trykk på selve panelet, så det ikke lukkes når du bommer litt på en knapp
                .pointerInput(Unit) { detectTapGestures { } }.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Default.LightMode, null, tint = TextMuted)
                Slider(bright, {
                    bright = it
                    if (canWrite) {
                        if (Prefs.float("app_brightness", -1f) >= 0f) Prefs.set("app_brightness", -1f)
                        runCatching {
                            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (it * 255).toInt().coerceIn(5, 255))
                        }
                    } else Prefs.set("app_brightness", it)
                }, valueRange = 0.05f..1f, colors = sliderColors, modifier = Modifier.weight(1f))
                Icon(Icons.Default.VolumeUp, null, tint = TextMuted)
                Slider(Ui.volume.coerceAtLeast(0).toFloat(), {
                    val v = it.roundToInt()
                    if (v != Ui.volume) { Ui.volume = v; am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
                }, valueRange = 0f..maxVol.toFloat(), colors = sliderColors, modifier = Modifier.weight(1f))
            }
            // Rulles sidelengs når teksten er stor, så ingen knapp blir kuttet av
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("Wi-Fi", Icons.Default.Wifi) { onClose(); Prefs.set("settings_cat", "NET"); Nav.screen = Screen.SETTINGS }
                Pill("Bluetooth", Icons.Default.Bluetooth) { onClose(); Nav.screen = Screen.BT }
                Pill(when (Theme.mode) { "DAY" -> "Dag"; "NIGHT" -> "Natt"; else -> "Auto lys" }, Icons.Default.DarkMode) {
                    Prefs.set("night_mode", when (Theme.mode) { "AUTO" -> "NIGHT"; "NIGHT" -> "DAY"; else -> "AUTO" })
                    Theme.update(Hub.gps?.lat, Hub.gps?.lon)
                }
                Pill("Kamera", Icons.Default.Videocam) { onClose(); AppRepository.launchTile(context, Tiles.byPrefix("Rygg")) }
                Pill("Bil og tur", Icons.Default.DirectionsCar) { onClose(); Nav.screen = Screen.CAR }
                Pill("Innstillinger", Icons.Default.Settings) { onClose(); Nav.screen = Screen.SETTINGS }
            }
        }
    }
}
