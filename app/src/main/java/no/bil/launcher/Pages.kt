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
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ======================= Felles byggeklosser ======================= */

private val Round = RoundedCornerShape(28.dp)
private val Small = RoundedCornerShape(18.dp)

@Composable
fun Page(title: String, onClose: () -> Unit, extra: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().clip(Round).background(Panel).padding(24.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            extra()
            RoundIcon(Icons.Default.Close, 48.dp) { onClose() }
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
    val icon = remember(NowPlaying.pkg) {
        (NowPlaying.pkg ?: Prefs.str("music_pkg").ifBlank { null })
            ?.let { runCatching { context.packageManager.getApplicationIcon(it) }.getOrNull() }
            ?: AppRepository.tileIcon(context, Tiles.byPrefix("Mus"))
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(Card), contentAlignment = Alignment.Center) {
        when {
            NetRadio.active -> Icon(Icons.Default.Radio, null, tint = TextPrimary, modifier = Modifier.size(size * 0.5f))
            art != null -> Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            icon != null -> AppIcon(icon, size * 0.7f)
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
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(300.dp, 24.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(musicTitle ?: "Ingen musikk spiller", color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(musicSub ?: "", color = TextMuted, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Progress(Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundIcon(Icons.Default.SkipPrevious, 64.dp) { Media.previous(context) }
                    RoundIcon(if (musicPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, 84.dp, outlined = true) { Media.playPause(context) }
                    RoundIcon(Icons.Default.SkipNext, 64.dp) { Media.next(context) }
                }
                if (!MediaListener.enabled(context)) {
                    Spacer(Modifier.height(16.dp))
                    Pill("Vis sangtittel og cover (slå på varseltilgang)", Icons.Default.Info) { openNotificationAccess(context) }
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
        Pill("Stereoens radio", Icons.Default.Radio) { AppRepository.launchTile(context, Tiles.byPrefix("Rad")) }
        Spacer(Modifier.width(12.dp))
        if (NetRadio.active) { Pill("Stopp", Icons.Default.Stop) { NetRadio.stop() }; Spacer(Modifier.width(12.dp)) }
    }) {
        NetRadio.error?.let { Text(it, color = TextMuted, fontSize = 15.sp, modifier = Modifier.padding(bottom = 10.dp)) }
        LazyVerticalGrid(GridCells.Fixed(4), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(NetRadio.stations) { s ->
                val on = NetRadio.current == s
                Column(
                    Modifier.height(120.dp).clip(Small).background(if (on) TextPrimary else Card)
                        .clickable { if (on) NetRadio.toggle(context) else NetRadio.play(context, s) }.padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(s.name, color = if (on) Bg else TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Medium)
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
            Field(q, "Hvor vil du? Adresse eller sted", Modifier.weight(1f), onDone = { Navigate.to(context, q) }) { q = it }
            Pill("Kjør", Icons.Default.Navigation, strong = true) { Navigate.to(context, q) }
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
            MapButton(Icons.Default.Close) { onClose() }
            MapButton(Icons.Default.Add) { zoomOffset = (zoomOffset + 1).coerceAtMost(3) }
            MapButton(Icons.Default.Remove) { zoomOffset = (zoomOffset - 1).coerceAtLeast(-4) }
            MapButton(if (Prefs.bool("map_rotate", true)) Icons.Default.Explore else Icons.Default.NorthEast) { Prefs.set("map_rotate", !Prefs.bool("map_rotate", true)) }
        }
        Row(Modifier.align(Alignment.BottomStart).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Navigasjon", Icons.Default.Navigation) { Nav.screen = Screen.NAV }
            Pill("Åpne kartappen", Icons.Default.Map) { openMaps(context) }
        }
        SpeedBadge(Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}

@Composable
private fun MapButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(CircleShape).background(Card.copy(alpha = 0.92f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = TextPrimary)
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

object Favorites {
    fun all(): List<Contact> = Prefs.str("favorites").split("\n").mapNotNull {
        val p = it.split("|"); if (p.size == 2 && p[1].isNotBlank()) Contact(p[0], p[1]) else null
    }
    fun add(c: Contact) = Prefs.set("favorites", (all() + c).joinToString("\n") { "${it.name}|${it.number}" })
    fun remove(c: Contact) = Prefs.set("favorites", (all() - c).joinToString("\n") { "${it.name}|${it.number}" })

    /** Ring: direkte hvis lov, ellers åpnes nummeret i telefonappen. */
    fun call(ctx: Context, number: String) {
        val uri = Uri.parse("tel:" + number.filter { it.isDigit() || it == '+' })
        val direct = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val i = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(i) }.isFailure) AppRepository.launchTile(ctx, Tiles.byPrefix("Tel"))
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
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) { ask.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG)) }
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var num by remember { mutableStateOf("") }
    val recents by produceState(emptyList<Contact>(), Prefs.version) { value = recentCalls(context) }

    Page("Telefon", onClose, extra = {
        Pill("Åpne telefon-appen", Icons.Default.Dialpad) { AppRepository.launchTile(context, Tiles.byPrefix("Tel")) }
        Spacer(Modifier.width(12.dp))
    }) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(0.6f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("FAVORITTER", color = TextMuted, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                    Text(if (adding) "Avbryt" else "+ Legg til", color = TextPrimary, fontSize = 15.sp, modifier = Modifier.clickable { adding = !adding }.padding(8.dp))
                }
                if (adding) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                        Field(name, "Navn", Modifier.weight(1f)) { name = it }
                        Field(num, "Nummer", Modifier.weight(1f), KeyboardType.Phone) { num = it }
                        Pill("Lagre", strong = true) {
                            if (num.isNotBlank()) { Favorites.add(Contact(name.ifBlank { num }, num)); name = ""; num = ""; adding = false }
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
                            if (adding) Icon(Icons.Default.Delete, null, tint = TextMuted, modifier = Modifier.clickable { Favorites.remove(f) })
                        }
                    }
                }
            }
            Column(Modifier.weight(0.4f).fillMaxHeight().clip(Small).background(Card).padding(16.dp)) {
                Text("SISTE ANROP", color = TextMuted, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp))
                if (recents.isEmpty()) Text("Stereoen deler ikke anropslisten med T'OS. Den ligger i telefon-appen.", color = TextMuted, fontSize = 14.sp)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(recents) { r ->
                        Text(r.name, color = TextPrimary, fontSize = 16.sp, maxLines = 1,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { Favorites.call(context, r.number) }.padding(vertical = 10.dp, horizontal = 6.dp))
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
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bmp
}

@Composable
fun CarScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val km = Trip.meters / 1000
    val mins = Trip.movingSec / 60
    Page("Bil og tur", onClose, extra = {
        Pill("Nullstill tur", Icons.Default.RestartAlt) { Trip.reset() }
        Spacer(Modifier.width(12.dp))
    }) {
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
                if (Parking.known) {
                    val bmp = remember(Parking.lat, Parking.lon) { qr(Parking.appleMapsUrl()) }
                    Image(bmp.asImageBitmap(), null, Modifier.size(200.dp).clip(RoundedCornerShape(12.dp)))
                    Text("Skann med iPhone-kameraet før du går, så får du posisjonen i Kart.", color = TextMuted, fontSize = 13.sp)
                    Text("Sist stilt: " + SimpleDateFormat("d. MMM HH:mm", Locale("nb", "NO")).format(Date(Parking.time)), color = TextMuted, fontSize = 13.sp)
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
            musicTitle?.let { Text(it, color = TextMuted, fontSize = 16.sp, modifier = Modifier.padding(top = 18.dp), maxLines = 1) }
        }
    }
}

/* ======================= Hurtigpanel (sveip ned, punkt 40) ======================= */

@Composable
fun QuickPanel(onClose: () -> Unit) {
    val context = LocalContext.current
    val am = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var vol by remember { mutableFloatStateOf(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    var bright by remember { mutableFloatStateOf(Prefs.float("app_brightness", -1f).let { if (it < 0) 0.7f else it }) }
    val sliderColors = SliderDefaults.colors(thumbColor = TextPrimary, activeTrackColor = TextPrimary, inactiveTrackColor = Line)

    Box(Modifier.fillMaxSize().background(Bg.copy(alpha = 0.6f)).clickable(onClick = onClose)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)).background(Panel)
                .clickable(enabled = false) {}.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Default.LightMode, null, tint = TextMuted)
                Slider(bright, { bright = it; Prefs.set("app_brightness", it) }, valueRange = 0.05f..1f, colors = sliderColors, modifier = Modifier.weight(1f))
                Icon(Icons.Default.VolumeUp, null, tint = TextMuted)
                Slider(vol, { vol = it; am.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0) }, valueRange = 0f..maxVol.toFloat(), colors = sliderColors, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("Wi-Fi", Icons.Default.Wifi) {
                    val i = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
                    runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                Pill("Bluetooth", Icons.Default.Bluetooth) { onClose(); Nav.screen = Screen.BT }
                Pill(when (Theme.mode) { "DAY" -> "Dag"; "NIGHT" -> "Natt"; else -> "Auto lys" }, Icons.Default.DarkMode) {
                    Prefs.set("night_mode", when (Theme.mode) { "AUTO" -> "NIGHT"; "NIGHT" -> "DAY"; else -> "AUTO" })
                    Theme.update(Hub.gps?.lat, Hub.gps?.lon)
                }
                Pill("Kamera", Icons.Default.Videocam) { AppRepository.launchTile(context, Tiles.byPrefix("Rygg")) }
                Pill("Bil og tur", Icons.Default.DirectionsCar) { onClose(); Nav.screen = Screen.CAR }
                Pill("Innstillinger", Icons.Default.Settings) { onClose(); Nav.screen = Screen.SETTINGS }
            }
        }
    }
}
