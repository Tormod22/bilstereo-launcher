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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
        Text(label, color = if (strong) Bg else TextPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Text(options[idx].second, color = TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
        Icon(Icons.Default.UnfoldMore, null, tint = TextMuted, modifier = Modifier.padding(start = 6.dp).size(20.dp))
    }
}

/**
 * Tekstfelt med T'OS sitt eget tastatur (Androids tastatur vises aldri). Trykk på feltet = tastaturet kommer opp nederst.
 * [keyboard]: Number/Phone gir talltastatur, Password gir prikker og en «vis»-knapp.
 * [onDone] kjøres av handlingstasten nederst til høyre (merket [doneLabel], ellers «OK»/«Ferdig»).
 */
@Composable
fun Field(
    value: String, hint: String, modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text,
    onDone: (() -> Unit)? = null, doneLabel: String? = null, onChange: (String) -> Unit,
) {
    val owner = remember { Any() }
    val cur = rememberUpdatedState(value)
    val change = rememberUpdatedState(onChange)
    val done = rememberUpdatedState(onDone)
    var shown by remember { mutableStateOf(false) }
    val password = kbIsPassword(keyboard)
    val focused = TosKeyboard.binding?.owner === owner
    // Forsvinner feltet (siden lukkes), forsvinner tastaturet også
    DisposableEffect(owner) { onDispose { TosKeyboard.closeIfOwner(owner) } }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.heightIn(min = 48.dp).clip(shape).background(Line)
            .border(1.5.dp, if (focused) TextPrimary else Line, shape)
            .clickable(onClickLabel = "Skriv") {
                TosKeyboard.open(KbBinding(
                    owner, kbKind(keyboard), kbAutoCap(keyboard), password, hint,
                    doneLabel ?: if (onDone != null) "OK" else "Ferdig",
                    read = { cur.value }, write = { change.value(it) }, done = { done.value?.invoke() },
                    revealed = { shown }, toggle = { shown = !shown },
                ))
            }
            .padding(start = 14.dp, end = if (password) 0.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FieldText(if (password) kbMask(value, shown) else value, hint, focused, Modifier.weight(1f).padding(vertical = 12.dp))
        if (password && value.isNotEmpty()) RoundIcon(if (shown) Icons.Default.VisibilityOff else Icons.Default.Visibility, 48.dp,
            desc = if (shown) "Skjul passordet" else "Vis passordet") { shown = !shown }
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
        Text(value, color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Suppress("UnusedReceiverParameter")
fun Context.toast(msg: String) = Notify.show(msg)

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

// FYT: BT-musikken fra telefonen går via stereoens BT-brikke (ingen mediaøkt i Android) – tittel/artist/tid fra FYT
val musicTitle: String? get() = when {
    NetRadio.active -> NetRadio.current?.name
    fytBtMusicShown -> Fyt.btavTitle ?: "Bluetooth-musikk"
    else -> NowPlaying.title
}
val musicSub: String? get() = when {
    NetRadio.active -> if (NetRadio.buffering) "Kobler til …" else NetRadio.error ?: "Nettradio"
    fytBtMusicShown -> listOfNotNull(Fyt.btavArtist, FytPhone.btavAlbum).joinToString(" · ").ifEmpty { Fyt.phoneName ?: "Telefonen" }
    else -> NowPlaying.artist
}
val musicPlaying: Boolean get() = when {
    NetRadio.active -> NetRadio.playing
    fytBtMusicShown -> Fyt.btavPlaying
    else -> NowPlaying.playing
}

@Composable
fun Progress(modifier: Modifier) {
    var p by remember { mutableFloatStateOf(0f) }
    // FYT-BT-musikk: posisjonen kommer fra stereoen hvert sekund
    if (fytBtMusicShown) { BarOf(modifier, FytPhoneLogic.progress(FytPhone.btavPosMs, FytPhone.btavTotalMs)); return }
    LaunchedEffect(NowPlaying.title, NowPlaying.playing) {
        while (true) {
            val d = NowPlaying.durationMs
            p = if (d > 0 && !NetRadio.active) (NowPlaying.positionMs().toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(1000)
        }
    }
    BarOf(modifier, p)
}

@Composable
private fun BarOf(modifier: Modifier, p: Float) {
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(Line)) {
        Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(TextPrimary))
    }
}

@Composable
fun MusicScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    Page("Musikk", onClose, extra = {
        Pill("Åpne app", Icons.AutoMirrored.Filled.OpenInNew) { Buttons.openMusic(context) }
        Spacer(Modifier.width(12.dp))
    }) {
        // Lydkilder: bytt mellom musikkapp, Bluetooth, FM-radio og nettradio uten å åpne appene
        val sources = Media.sources(context)
        val hasBt = sources.any { it.kind == SourceKind.BLUETOOTH }
        // Stereoens egen BT-app uten mediaøkt: «Bluetooth-lyd fra telefonen» likevel (BT-motoren bytter lydkanal)
        val btExtra = !hasBt && (BtLink.vendorPackagesCached().isNotEmpty() || BtLink.current.phone != null)
        if (sources.size > 1 || btExtra) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val anySelected = sources.any { it.selected }
                sources.forEach { src ->
                    val on = src.selected || (!anySelected && src.playing)
                    val icon = when (src.kind) {
                        SourceKind.MUSIC -> Icons.Default.MusicNote
                        SourceKind.BLUETOOTH -> Icons.Default.Bluetooth
                        SourceKind.RADIO -> Icons.Default.Radio
                        SourceKind.NET_RADIO -> Icons.Default.Language
                    }
                    // Bluetooth kan trykkes igjen (ingen lyd fra telefonen → prøv å få stereoen til å spille den på nytt)
                    Pill(src.label, icon, strong = on) {
                        if (!src.selected || src.kind == SourceKind.BLUETOOTH) Media.selectSource(context, src.pkg)
                    }
                }
                if (btExtra) Pill("Bluetooth-lyd fra telefonen", Icons.Default.Bluetooth) { Media.selectBluetooth(context) }
            }
        }
        // Hvorfor det kan være stille: musikken spiller på en annen enhet, eller lyden skal komme fra telefonen
        val remotePkg = NowPlaying.pkg.takeIf { NowPlaying.remote && NowPlaying.playing }
        val hint = when {
            remotePkg != null -> remotePlaybackMessage(remotePkg, sources.firstOrNull { it.pkg == remotePkg }?.label ?: "Appen")
            sources.any { it.selected && it.kind == SourceKind.BLUETOOTH } -> PHONE_BT_HINT
            else -> null
        }
        if (hint != null) Text(hint, color = if (remotePkg != null) TextPrimary else TextMuted, fontSize = 14.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 10.dp))
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
                    hw.station?.let { Text(it, color = TextMuted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    hw.text?.let { Text(it, color = TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                } else Text("Trykk «Start radio». Frekvensen vises her når radioen spiller.", color = TextMuted, fontSize = 15.sp)
            }
            if (hw != null) {
                if (hw.canStep) RoundIcon(Icons.Default.Remove, 52.dp, desc = "Ett trinn ned") { HwRadio.step(up = false) }
                RoundIcon(Icons.Default.FastRewind, 52.dp, desc = "Søk ned") { HwRadio.seekDown() }
                RoundIcon(Icons.Default.FastForward, 52.dp, desc = "Søk opp") { HwRadio.seekUp() }
                if (hw.canStep) RoundIcon(Icons.Default.Add, 52.dp, desc = "Ett trinn opp") { HwRadio.step(up = true) }
                if (hw.canTune) { Pill("FM") { HwRadio.cycleFm() }; Pill("AM") { HwRadio.am() } }
            }
            // På = T'OS styrer radioen (i bakgrunnen); stereoens radio-app vises ikke
            if (HwRadio.wanted || hw != null) Pill("Stopp", Icons.Default.Stop) { HwRadio.powerOff(context) }
            else Pill("Start radio", Icons.Default.Radio, strong = true) { HwRadio.powerOn(context) }
        }
        // FYT: forvalgte kanaler (bare de stereoen sender frekvens eller navn for)
        val presets = if (HwRadio.fyt) Fyt.radioPresets else emptyList()
        if (presets.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                presets.forEach { p -> Pill(p.label) { Fyt.radioPreset(p.number) } }
            }
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
            // Reserve, lite synlig: stereoens egen radio-app (hvis noe ikke virker fra T'OS)
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Text("Åpne original radio-app", color = TextMuted, fontSize = 13.sp,
                        modifier = Modifier.heightIn(min = 48.dp).clip(Small).clickable { HwRadio.openApp(context) }
                            .padding(horizontal = 14.dp, vertical = 14.dp))
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
            Field(q, "Hvor vil du? Adresse eller sted", Modifier.weight(1f), onDone = go, doneLabel = "Kjør") { q = it }
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
        Text(speedText(Hub.gps), color = TextPrimary, fontSize = 34.sp, fontWeight = FontWeight.Medium)
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

/* ======================= Telefon: tastatur, kontakter, siste og favoritter (punkt 24, 26) ======================= */

data class Contact(val name: String, val number: String)

/** Favoritter lagres som «navn|nummer», én per linje – derfor fjernes | og linjeskift fra det som skrives inn. */
internal fun favText(s: String): String = s.replace(Regex("[|\\r\\n]+"), " ").replace(Regex("\\s+"), " ").trim()

/** Samme nummer (uansett mellomrom, bindestrek og +47/0047 foran) regnes som samme person. */
internal fun sameNumber(a: String, b: String): Boolean = numbersMatch(a, b)

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

    /** Ring – T'OS ringer selv via Bluetooth-telefonen når det går (se [Phone.call]). */
    fun call(ctx: Context, number: String) = Phone.call(ctx, number)
}

/** CallLog.Calls.*_TYPE → kort tekst i «Siste anrop». */
internal fun recentLabel(type: Int): String = when (type) {
    CallLog.Calls.INCOMING_TYPE -> "Inn"
    CallLog.Calls.OUTGOING_TYPE -> "Ut"
    CallLog.Calls.MISSED_TYPE -> "Tapt"
    CallLog.Calls.REJECTED_TYPE -> "Avvist"
    CallLog.Calls.BLOCKED_TYPE -> "Blokkert"
    else -> ""
}

private enum class PhoneTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    FAV("Favoritter", Icons.Default.Star),
    KEYS("Tastatur", Icons.Default.Dialpad),
    CONTACTS("Kontakter", Icons.Default.Contacts),
    RECENT("Siste", Icons.Default.History),
}

@Composable
fun PhoneScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var askedOnce by remember { mutableStateOf(false) }
    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        tick++
        // Android viser ikke spørsmålet igjen etter to «Nei»: forklar hvor det kan slås på
        if (askedOnce && res.values.none { it }) Notify.show("Gi tilgang under Innstillinger → Apper → T'OS → Tillatelser.", long = true)
        askedOnce = true
    }
    val askRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    // Tilbake fra Android (tillatelser, telefon-app, Bluetooth): sjekk alt på nytt
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }

    val missing = remember(tick) { Phone.missingPermissions(context) }
    val isDialer = remember(tick) { Phone.isDialer(context) }
    val roleOk = remember(tick) { Phone.dialerAvailable(context) }
    var canPlace by remember { mutableStateOf(true) }
    LaunchedEffect(tick) { while (true) { canPlace = withContext(Dispatchers.IO) { Phone.canPlaceCalls(context) }; delay(5000) } }
    val androidContacts by produceState<List<Contact>?>(null, tick) { value = withContext(Dispatchers.IO) { Phone.contacts(context) } }
    val androidRecents by produceState<List<RecentCall>?>(null, tick, Phone.inCall) { value = withContext(Dispatchers.IO) { Phone.recents(context) } }
    // FYT-stereoen: Android har verken kontaktene eller anropene (iPhonen er koblet til stereoens BT-brikke) –
    // da brukes telefonboka FYT har sendt og T'OS sin egen liste over FYT-samtalene
    LaunchedEffect(Unit) { FytPhone.ensureLoaded() }
    val fyt = FytPhone.enabled
    val fytContacts = fyt && androidContacts.isNullOrEmpty() && FytPhone.contacts.isNotEmpty()
    val fytRecents = fyt && androidRecents.isNullOrEmpty() && FytPhone.callLog.isNotEmpty()
    val contacts = if (fytContacts) FytPhone.contacts else androidContacts
    val recents = if (fytRecents) FytPhone.callLog else androidRecents

    var tab by remember { mutableStateOf(if (Favorites.all().isEmpty()) PhoneTab.KEYS else PhoneTab.FAV) }
    var number by remember { mutableStateOf("") }
    LaunchedEffect(Phone.pendingDial) { Phone.pendingDial?.let { number = it; tab = PhoneTab.KEYS; Phone.pendingDial = null } }

    Page("Telefon", onClose, extra = {
        if (Phone.inCall) { Pill("Til samtalen", Icons.Default.Call, strong = true) { Phone.openCallScreen(context) }; Spacer(Modifier.width(12.dp)) }
        else if (missing.isEmpty() && !canPlace && !FytPhone.canDial) {
            // Ingen telefon T'OS kan ringe med: reserve til stereoens egen app
            Pill("Stereoens telefon-app", Icons.Default.Dialpad) { Phone.openStereoApp(context) }; Spacer(Modifier.width(12.dp))
        }
    }) {
        // På FYT går samtalene utenom Android: da trengs verken tillatelsene eller telefon-app-rollen for å ringe
        val needRole = !isDialer && roleOk && !fyt
        if ((missing.isNotEmpty() && !fyt) || needRole) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(Small).background(Card).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(if (missing.isNotEmpty()) "T'OS trenger lov til telefonen" else "Vis samtalene i T'OS", color = TextPrimary, fontSize = 16.sp)
                    Text(when {
                        missing.isNotEmpty() && needRole -> "Gi lov til kontakter, anrop og ringing, og gjør T'OS til telefon-app så samtalene vises her."
                        missing.isNotEmpty() -> "Kontakter, siste anrop og ringing. Android spør om hver av dem."
                        else -> "Gjør T'OS til telefon-app, så slipper du stereoens egen samtaleskjerm."
                    }, color = TextMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (missing.isNotEmpty()) Pill("Gi tilgang", strong = true) { askPerms.launch(missing.toTypedArray()) }
                if (needRole) Pill("Gjør T'OS til telefon-app", strong = missing.isEmpty()) {
                    val i = Phone.dialerRoleIntent(context)
                    if (i == null || runCatching { askRole.launch(i) }.isFailure) context.toast("Stereoen lar ikke T'OS bli telefon-app")
                }
            }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.width(176.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Er telefonen koblet til? (sier aldri «ikke tilkoblet» når stereoens egen BT-app styrer)
                Text(btShortText(BtLink.current), color = TextMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                PhoneTab.entries.forEach { t ->
                    val sel = t == tab
                    Row(Modifier.fillMaxWidth().height(64.dp).clip(Small).background(if (sel) TextPrimary else Card).clickable { tab = t }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(t.icon, null, tint = if (sel) Bg else TextPrimary, modifier = Modifier.size(24.dp))
                        Text(t.label, color = if (sel) Bg else TextPrimary, fontSize = 17.sp, maxLines = 1)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (tab) {
                    PhoneTab.KEYS -> Keypad(number, { number = it }, contacts.orEmpty()) { Phone.call(context, it) }
                    PhoneTab.CONTACTS -> ContactList(contacts, Manifest.permission.READ_CONTACTS in missing && !fyt, fyt) { Phone.call(context, it) }
                    PhoneTab.RECENT -> RecentList(recents, contacts.orEmpty(), Manifest.permission.READ_CALL_LOG in missing && !fyt, isDialer || fyt) { Phone.call(context, it) }
                    PhoneTab.FAV -> FavoriteGrid { Phone.call(context, it) }
                }
            }
        }
    }
}

private val KEY_LETTERS = mapOf("2" to "ABC", "3" to "DEF", "4" to "GHI", "5" to "JKL", "6" to "MNO", "7" to "PQRS", "8" to "TUV", "9" to "WXYZ", "0" to "+")

/** T'OS sitt eget tastatur (ikke Androids skjermtastatur). Langt trykk på 0 gir +, langt trykk på ⌫ tømmer. */
@Composable
private fun Keypad(number: String, onChange: (String) -> Unit, contacts: List<Contact>, onCall: (String) -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().height(76.dp).clip(Small).background(Card).padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (number.length > 20) "…" + number.takeLast(19) else number.ifEmpty { "Skriv et nummer" }, color = if (number.isEmpty()) TextMuted else TextPrimary,
                    fontSize = if (number.length > 13) 24.sp else 32.sp, maxLines = 1, modifier = Modifier.weight(1f))
                if (number.isNotEmpty()) Box(
                    Modifier.size(76.dp).tapOrHold("Slett", onLongClick = { onChange("") }) { onChange(appendKey(number, "⌫")) },
                    contentAlignment = Alignment.Center,
                ) { Icon(PhoneIcons.Backspace, "Slett siste", tint = TextPrimary, modifier = Modifier.size(30.dp)) }
            }
            // Forslag fra telefonboka mens man skriver
            val hits = remember(number, contacts) { if (dialable(number).length >= 3) filterContacts(contacts, number).take(3) else emptyList() }
            hits.forEach { c ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).clickable { onCall(c.number) }.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = TextPrimary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.number, color = TextMuted, fontSize = 13.sp, maxLines = 1)
                    }
                    Icon(Icons.Default.Call, "Ring ${c.name}", tint = TextPrimary, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().height(72.dp).clip(Small).background(if (number.isEmpty()) Line else TextPrimary)
                .clickable { if (number.isEmpty()) Unit else onCall(number) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(Icons.Default.Call, null, tint = if (number.isEmpty()) TextMuted else Bg, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text("Ring", color = if (number.isEmpty()) TextMuted else Bg, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
        }
        Column(Modifier.weight(1.1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("123", "456", "789", "*0#").forEach { row ->
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { ch ->
                        val k = ch.toString()
                        Column(
                            Modifier.weight(1f).fillMaxHeight().heightIn(min = 56.dp).clip(Small).background(Card)
                                .tapOrHold(k, onLongClick = if (k == "0") ({ onChange(appendKey(number, "+")) }) else null) { onChange(appendKey(number, k)) },
                            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(k, color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Medium)
                            KEY_LETTERS[k]?.let { Text(it, color = TextMuted, fontSize = 11.sp, letterSpacing = 1.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String, action: Pair<String, () -> Unit>? = null) {
    Column(Modifier.fillMaxWidth().clip(Small).background(Card).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(text, color = TextMuted, fontSize = 15.sp, lineHeight = 21.sp)
        action?.let { (label, go) -> Pill(label) { go() } }
    }
}

@Composable
private fun Initial(name: String) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(Line), contentAlignment = Alignment.Center) {
        Text(letterOf(name), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ContactList(contacts: List<Contact>?, noPermission: Boolean, fyt: Boolean, onCall: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val favs = Favorites.all()
    Column(Modifier.fillMaxSize()) {
        Field(query, "Søk etter navn eller nummer", Modifier.fillMaxWidth().padding(bottom = 10.dp)) { query = it }
        when {
            noPermission -> EmptyNote("Trykk «Gi tilgang» øverst for å se kontaktene.")
            contacts == null -> Text("Henter kontakter …", color = TextMuted, fontSize = 15.sp)
            contacts.isEmpty() && fyt -> EmptyNote(
                if (FytPhone.bookState == 2) "Henter kontaktene fra telefonen …"
                else "Ingen kontakter ennå. Trykk «Hent kontakter», og godta på iPhonen hvis den spør. " +
                    "Står det fortsatt tomt: iPhonen → Innstillinger → Bluetooth → (i) ved stereoen → slå på «Synkroniser kontakter».",
                "Hent kontakter" to { fytDownloadBook() },
            )
            contacts.isEmpty() -> EmptyNote(
                "Fant ingen kontakter. Kontaktene fra telefonen havner ofte bare i stereoens egen Bluetooth-app. " +
                    "På iPhonen: Innstillinger → Bluetooth → trykk (i) ved stereoen → slå på «Synkroniser kontakter». " +
                    "Du kan også lagre folk under Favoritter.",
                "Bluetooth-siden" to { Nav.screen = Screen.BT },
            )
            else -> {
                val shown = remember(query, contacts) { filterContacts(contacts, query) }
                if (shown.isEmpty()) Text("Ingen treff på «$query».", color = TextMuted, fontSize = 15.sp)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(shown.size) { i ->
                        val c = shown[i]
                        val letter = letterOf(c.name)
                        if (query.isBlank() && (i == 0 || letterOf(shown[i - 1].name) != letter))
                            Text(letter, color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp))
                        val fav = favs.firstOrNull { sameNumber(it.number, c.number) }
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp)).background(Card).clickable { onCall(c.number) }.padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Initial(c.name)
                            Column(Modifier.weight(1f)) {
                                Text(c.name, color = TextPrimary, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(c.number, color = TextMuted, fontSize = 13.sp, maxLines = 1)
                            }
                            Box(Modifier.size(64.dp).clickable { if (fav != null) Favorites.remove(fav) else Favorites.add(c) }, contentAlignment = Alignment.Center) {
                                Icon(if (fav != null) Icons.Default.Star else Icons.Default.StarBorder,
                                    if (fav != null) "Fjern ${c.name} fra favoritter" else "Legg ${c.name} til favoritter",
                                    tint = if (fav != null) TextPrimary else TextMuted, modifier = Modifier.size(26.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentList(recents: List<RecentCall>?, contacts: List<Contact>, noPermission: Boolean, isDialer: Boolean, onCall: (String) -> Unit) {
    val now = remember(recents) { System.currentTimeMillis() }
    // Navn fra telefonboka når anropslisten bare har nummeret
    val named = remember(recents, contacts) {
        recents.orEmpty().map { r -> r to (r.name?.takeIf { it.isNotBlank() } ?: contacts.firstOrNull { numbersMatch(it.number, r.number) }?.name) }
    }
    when {
        noPermission -> EmptyNote("Trykk «Gi tilgang» øverst for å se siste anrop.")
        recents == null -> Text("Henter anrop …", color = TextMuted, fontSize = 15.sp)
        recents.isEmpty() -> EmptyNote(
            if (isDialer) "Ingen anrop ennå." else "Ingen anrop her ennå. Stereoens egen telefonapp deler ikke alltid anropslisten – gjør T'OS til telefon-app, så kommer de nye samtalene hit."
        )
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(named) { (r, name) ->
                val missed = r.type == CallLog.Calls.MISSED_TYPE || r.type == CallLog.Calls.REJECTED_TYPE
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp)).background(Card)
                    .clickable(enabled = r.number.isNotBlank()) { onCall(r.number) }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(when (r.type) {
                        CallLog.Calls.OUTGOING_TYPE -> PhoneIcons.Outgoing
                        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> PhoneIcons.Missed
                        else -> PhoneIcons.Incoming
                    }, recentLabel(r.type).ifEmpty { null }, tint = if (missed) TextPrimary else TextMuted, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name ?: r.title, color = TextPrimary, fontSize = 17.sp, fontWeight = if (missed) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val label = recentLabel(r.type)
                        Text(listOfNotNull(label.takeIf { missed }, whenText(r.date, now)).joinToString(" · "), color = TextMuted, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteGrid(onCall: (String) -> Unit) {
    val context = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    // Sletting krever to trykk: første trykk spør «Slette?», andre sletter
    var confirmDel by remember { mutableStateOf<Contact?>(null) }
    LaunchedEffect(confirmDel) { if (confirmDel != null) { delay(4000); confirmDel = null } }
    var name by remember { mutableStateOf("") }
    var num by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("FAVORITTER", color = TextMuted, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            Text(if (adding) "Ferdig" else "+ Legg til / endre", color = TextPrimary, fontSize = 15.sp,
                modifier = Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable { adding = !adding; confirmDel = null }.padding(horizontal = 12.dp, vertical = 14.dp))
        }
        if (adding) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                Field(name, "Navn", Modifier.weight(1f)) { name = it }
                Field(num, "Nummer", Modifier.weight(1f), KeyboardType.Phone) { num = it }
                Pill("Lagre", strong = true) {
                    if (favText(num).isBlank()) context.toast("Skriv inn et nummer først")
                    else { Favorites.add(Contact(name.ifBlank { num }, num)); context.toast("Lagret"); name = ""; num = "" }
                }
            }
        }
        val favs = Favorites.all()
        if (favs.isEmpty() && !adding) EmptyNote("Ingen favoritter ennå. Trykk på stjerna i Kontakter, eller «+ Legg til».")
        LazyVerticalGrid(GridCells.Fixed(2), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(favs) { f ->
                Row(Modifier.height(84.dp).clip(Small).background(Card).clickable { onCall(f.number) }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(Line), contentAlignment = Alignment.Center) {
                        Text(letterOf(f.name), color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
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
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("EEEE d. MMMM", Locale("nb", "NO")) }
    var time by remember { mutableStateOf(timeFmt.format(Date())) }
    var date by remember { mutableStateOf(dateFmt.format(Date()).replaceFirstChar { it.uppercase() }) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = Date()
            time = timeFmt.format(now); date = dateFmt.format(now).replaceFirstChar { it.uppercase() }
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }
    Box(Modifier.fillMaxSize().background(Bg).clickable(onClickLabel = "Tilbake til forsiden") { onClose() }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(time, color = TextPrimary.copy(alpha = 0.85f), fontSize = 150.sp, fontWeight = FontWeight.Light)
            Text(date, color = TextMuted, fontSize = 22.sp)
            musicTitle?.let { Text(it, color = TextMuted, fontSize = 16.sp, modifier = Modifier.padding(top = 18.dp).widthIn(max = 700.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/* ======================= Hurtigpanel (sveip ned, punkt 40) ======================= */

/**
 * Hurtigpanelet (T'OS sin nedtrekksmeny): lys, volum, snarveier og varsler. [onClose] lukker panelet (tilbake dit du var),
 * [onGo] lukker det og viser en T'OS-skjerm (null = noe annet åpnes, f.eks. kameraet eller et varsel).
 * Brukes både inne i T'OS og som vindu over andre apper ([ShadePanel]).
 */
@Composable
fun QuickPanel(onClose: () -> Unit, onGo: (Screen?) -> Unit = { onClose(); if (it != null) Nav.screen = it }) {
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
                }, valueRange = 0.05f..1f, colors = sliderColors, modifier = Modifier.weight(1f).semantics { contentDescription = "Lysstyrke" })
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = TextMuted)
                Slider(Ui.volume.coerceAtLeast(0).toFloat(), {
                    val v = it.roundToInt()
                    if (v != Ui.volume) { Ui.volume = v; HwVolume.markOwnChange(); runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) } }
                }, valueRange = 0f..maxVol.toFloat(), colors = sliderColors, modifier = Modifier.weight(1f).semantics { contentDescription = "Volum" })
            }
            // Rulles sidelengs når teksten er stor, så ingen knapp blir kuttet av
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("Wi-Fi", Icons.Default.Wifi) { Prefs.set("settings_cat", "NET"); onGo(Screen.SETTINGS) }
                Pill("Bluetooth", Icons.Default.Bluetooth) { onGo(Screen.BT) }
                Pill(when (Theme.mode) { "DAY" -> "Dag"; "NIGHT" -> "Natt"; else -> "Auto lys" }, Icons.Default.DarkMode) {
                    Prefs.set("night_mode", when (Theme.mode) { "AUTO" -> "NIGHT"; "NIGHT" -> "DAY"; else -> "AUTO" })
                    Theme.update(Hub.gps?.lat, Hub.gps?.lon)
                }
                Pill("Kamera", Icons.Default.Videocam) { onGo(null); AppRepository.launchTile(context, Tiles.byPrefix("Rygg")) }
                Pill("Bil og tur", Icons.Default.DirectionsCar) { onGo(Screen.CAR) }
                Pill("Innstillinger", Icons.Default.Settings) { onGo(Screen.SETTINGS) }
            }
            // Varslene (i stedet for stereoens nedtrekksmeny)
            NotificationsSection(
                TosNotifications.items, TosNotifications.connected,
                onOpen = { onGo(null); TosNotifications.open(context, it) },
                onDismiss = { TosNotifications.dismiss(it) },
                onClearAll = { TosNotifications.clearAll() },
                onAskAccess = { onGo(null); openNotificationAccess(context) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
