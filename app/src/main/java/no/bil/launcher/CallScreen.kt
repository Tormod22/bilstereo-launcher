package no.bil.launcher

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapCalls
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * T'OS sin samtaleskjerm (i stedet for stereoens egen). Åpnes av [TosInCallService] når det ringer,
 * når T'OS ringer ut, og fra banneret. Lukker seg selv litt etter at samtalen er over.
 */
class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { CallScreen(onHide = { finish() }) }
    }

    override fun onResume() { super.onResume(); Phone.callScreenVisible = true }
    override fun onPause() { Phone.callScreenVisible = false; super.onPause() }
}

private val BtnShape = RoundedCornerShape(24.dp)

/** Ikoner som Telefon-siden (Pages.kt) bruker. */
internal object PhoneIcons {
    val Backspace: ImageVector get() = Icons.AutoMirrored.Filled.Backspace
    val Outgoing: ImageVector get() = Icons.AutoMirrored.Filled.CallMade
    val Incoming: ImageVector get() = Icons.AutoMirrored.Filled.CallReceived
    val Missed: ImageVector get() = Icons.AutoMirrored.Filled.CallMissed
}

/** Trykk, og eventuelt langt trykk (tastaturet på Telefon-siden). */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.tapOrHold(label: String?, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier =
    combinedClickable(onClickLabel = label, onLongClick = onLongClick, onClick = onClick)

/** Stor knapp på samtaleskjermen (minst 56 dp høy). [strong] = hvit (hovedvalget), [on] = slått på. */
@Composable
private fun CallButton(label: String, icon: ImageVector, modifier: Modifier = Modifier, height: Dp = 96.dp, strong: Boolean = false, on: Boolean = false, onClick: () -> Unit) {
    val filled = strong || on
    Column(
        modifier.height(height).clip(BtnShape).background(if (filled) TextPrimary else Card).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (filled) Bg else TextPrimary, modifier = Modifier.size(if (height >= 110.dp) 40.dp else 32.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = if (filled) Bg else TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Avatar(title: String, size: Dp) {
    val letter = letterOf(title).takeIf { it != "#" }
    Box(Modifier.size(size).clip(CircleShape).background(Card), contentAlignment = Alignment.Center) {
        if (letter != null) Text(letter, color = TextPrimary, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Medium)
        else Icon(Icons.Default.Person, null, tint = TextMuted, modifier = Modifier.size(size * 0.55f))
    }
}

/** Klokke som tikker (for «Samtale 02:31»). */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    return now
}

@Composable
fun CallScreen(onHide: () -> Unit) {
    val calls = Phone.calls
    val call = Phone.current
    // Ingen samtaler igjen (etter «Avsluttet» i 1,5 s): lukk
    LaunchedEffect(calls.isEmpty()) { if (calls.isEmpty()) { delay(400); if (Phone.calls.isEmpty()) onHide() } }
    val now = rememberNow()
    var keypad by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    LaunchedEffect(call?.id) { keypad = false; typed = "" }

    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * Theme.textScale)) {
        Box(Modifier.fillMaxSize().background(Bg).padding(28.dp)) {
            if (call != null) {
                val others = calls.filter { it.id != call.id && it.state != CallState.ENDED }
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    // Hvem
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                        Avatar(call.title, 112.dp)
                        Spacer(Modifier.height(20.dp))
                        Text(call.title, color = TextPrimary, fontSize = 40.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 46.sp)
                        if (!call.name.isNullOrBlank() && call.number.isNotBlank() && call.number != call.name)
                            Text(call.number, color = TextMuted, fontSize = 20.sp, maxLines = 1)
                        Spacer(Modifier.height(10.dp))
                        Text(callStatusText(call.state, call.connectedAt, now, call.ended), color = if (call.state == CallState.ENDED) TextMuted else TextPrimary, fontSize = 24.sp)
                        others.forEach { o ->
                            Spacer(Modifier.height(18.dp))
                            Row(Modifier.clip(RoundedCornerShape(18.dp)).background(Card).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f, fill = false)) {
                                    Text(o.title, color = TextPrimary, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(callStatusText(o.state, o.connectedAt, now, o.ended), color = TextMuted, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                    // Knapper
                    Column(Modifier.weight(1f).fillMaxHeight().padding(top = 56.dp), verticalArrangement = Arrangement.Center) {
                        when {
                            call.state == CallState.ENDED -> {}
                            call.state == CallState.RINGING -> {
                                if (others.isNotEmpty()) Text("Svarer du, settes den andre samtalen på vent.", color = TextMuted, fontSize = 15.sp, modifier = Modifier.padding(bottom = 12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    CallButton("Avvis", Icons.Default.CallEnd, Modifier.weight(1f), height = 128.dp) { Phone.reject(call.id) }
                                    CallButton("Svar", Icons.Default.Call, Modifier.weight(1f), height = 128.dp, strong = true) { Phone.answer(call.id) }
                                }
                            }
                            keypad -> DtmfPad(call.id, typed, { typed = (typed + it).takeLast(20) }) { keypad = false }
                            else -> ActiveButtons(call, others) { keypad = true }
                        }
                    }
                }
            }
            Row(
                Modifier.align(Alignment.TopEnd).height(56.dp).clip(RoundedCornerShape(28.dp)).background(Line)
                    .clickable(onClick = onHide).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.KeyboardArrowDown, null, tint = TextPrimary, modifier = Modifier.size(24.dp))
                Text("Skjul", color = TextPrimary, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun ActiveButtons(call: PhoneCall, others: List<PhoneCall>, onKeypad: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CallButton(if (Phone.muted) "Mikrofon er av" else "Mikrofon av", if (Phone.muted) Icons.Default.MicOff else Icons.Default.Mic,
                Modifier.weight(1f), on = Phone.muted) { Phone.setMuted(!Phone.muted) }
            CallButton("Tastatur", Icons.Default.Dialpad, Modifier.weight(1f)) { onKeypad() }
            // Lydvalg bare når stereoen faktisk har mer enn én vei for lyden
            if (routesIn(Phone.routes).size > 1)
                CallButton(routeLabel(Phone.route), Icons.AutoMirrored.Filled.VolumeUp, Modifier.weight(1f)) { Phone.setRoute(nextRoute(Phone.route, Phone.routes)) }
        }
        val held = others.firstOrNull { it.state == CallState.HOLDING } ?: call.takeIf { it.state == CallState.HOLDING && others.isNotEmpty() }
        when {
            others.isNotEmpty() && held != null -> CallButton("Bytt samtale", Icons.Default.SwapCalls, Modifier.fillMaxWidth()) { Phone.swap() }
            call.canHold && call.state == CallState.HOLDING -> CallButton("Fortsett samtalen", Icons.Default.PlayArrow, Modifier.fillMaxWidth(), on = true) { Phone.unhold(call.id) }
            call.canHold && call.state == CallState.ACTIVE -> CallButton("Sett på vent", Icons.Default.Pause, Modifier.fillMaxWidth()) { Phone.hold(call.id) }
        }
        CallButton("Legg på", Icons.Default.CallEnd, Modifier.fillMaxWidth(), strong = true) { Phone.hangUp(call.id) }
    }
}

private val DTMF = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")

/** Tastatur under samtalen (tonevalg til f.eks. telefonsvarer og kundeservice). Tonen varer så lenge man holder. */
@Composable
private fun DtmfPad(id: Int, typed: String, onTyped: (String) -> Unit, onClose: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(typed.ifEmpty { " " }, color = TextPrimary, fontSize = 28.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Box(Modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(Line).clickable(onClick = onClose).padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center) { Text("Ferdig", color = TextPrimary, fontSize = 16.sp) }
        }
        DTMF.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(18.dp)).background(Card)
                            .semantics { contentDescription = "Tast $k"; role = Role.Button }
                            .pointerInput(id, k) {
                                detectTapGestures(onPress = {
                                    Phone.dtmfDown(id, k[0]); onTyped(k)
                                    try { tryAwaitRelease() } finally { Phone.dtmfUp(id) }
                                })
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text(k, color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

/**
 * Lite samtalebanner for Root (MainActivity) når samtaleskjermen ikke er framme:
 * `PhoneCallBanner(Modifier.align(Alignment.TopCenter))`. Viser ingenting når det ikke er noen samtale.
 * Trykk på banneret åpner samtaleskjermen; Svar/Avvis/Legg på virker direkte.
 */
@Composable
fun PhoneCallBanner(modifier: Modifier = Modifier) {
    val call = Phone.current ?: return
    if (Phone.callScreenVisible) return
    val context = LocalContext.current
    val now = rememberNow()
    Row(
        modifier.padding(top = 20.dp).clip(RoundedCornerShape(36.dp)).background(TextPrimary)
            .clickable { Phone.openCallScreen(context) }.padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Default.Call, null, tint = Bg, modifier = Modifier.size(28.dp))
        Column(Modifier.widthIn(max = 360.dp)) {
            Text(call.title, color = Bg, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(callStatusText(call.state, call.connectedAt, now, call.ended), color = Bg.copy(alpha = 0.6f), fontSize = 14.sp, maxLines = 1)
        }
        when (call.state) {
            CallState.RINGING -> {
                BannerButton("Avvis", strong = false) { Phone.reject(call.id) }
                BannerButton("Svar", strong = true) { Phone.answer(call.id) }
            }
            CallState.ENDED -> Spacer(Modifier.width(12.dp))
            else -> BannerButton("Legg på", strong = true) { Phone.hangUp(call.id) }
        }
    }
}

@Composable
private fun BannerButton(label: String, strong: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(56.dp).widthIn(min = 96.dp).clip(RoundedCornerShape(28.dp))
            .background(if (strong) Bg else Bg.copy(alpha = 0.1f)).clickable(onClick = onClick).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (strong) TextPrimary else Bg, fontSize = 17.sp, fontWeight = FontWeight.Medium) }
}
