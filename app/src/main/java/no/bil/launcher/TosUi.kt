package no.bil.launcher

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationDisabled
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ======================= Volum ======================= */

/**
 * T'OS sitt volumvindu (i stedet for stereoens eget). Kan kalles fra hvilken som helst tråd,
 * f.eks. fra knappetjenesten når volumknappene brukes. Vises av Root når T'OS er framme.
 */
object VolumeUi {
    var level by mutableIntStateOf(0)
        private set
    var max by mutableIntStateOf(15)
        private set
    var visible by mutableStateOf(false)
        private set

    private const val SHOW_MS = 1600L
    private val main = Handler(Looper.getMainLooper())
    private val hideNow = Runnable { visible = false }

    fun show(level: Int, max: Int) {
        main.post {
            val m = max.coerceAtLeast(1)
            this.max = m
            this.level = level.coerceIn(0, m)
            visible = true
            main.removeCallbacks(hideNow)
            main.postDelayed(hideNow, SHOW_MS)
        }
    }

    fun hide() = main.post { main.removeCallbacks(hideNow); visible = false }
}

/** Andel av full styrke (0–1) som volumlinja skal fylles. */
internal fun volumeFraction(level: Int, max: Int): Float = if (max <= 0) 0f else (level.toFloat() / max).coerceIn(0f, 1f)

/** Volumet i T'OS-stil: mørkt kort, tynn hvit linje og tallet. Kan gjenbrukes i andre vinduer (knappetjenesten). */
@Composable
fun VolumeBar(level: Int, max: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(28.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(28.dp))
            .padding(horizontal = 28.dp, vertical = 18.dp)
            .semantics { contentDescription = "Volum $level av $max" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(if (level <= 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, null,
            tint = TextPrimary, modifier = Modifier.size(32.dp))
        Box(Modifier.width(240.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Line)) {
            Box(Modifier.fillMaxWidth(volumeFraction(level, max)).fillMaxHeight().background(TextPrimary))
        }
        Text("$level", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.widthIn(min = 36.dp))
    }
}

/** Volumvinduet i Root: toner inn og ut. */
@Composable
fun VolumeHost(modifier: Modifier = Modifier) {
    AnimatedVisibility(VolumeUi.visible && !Nav.quick, modifier, enter = fadeIn(), exit = fadeOut()) {
        VolumeBar(VolumeUi.level, VolumeUi.max)
    }
}

/* ======================= Meldinger (Notify) ======================= */

/** Viser [Notify.current] som et mørkt kort. Trykk = lukk. Forsvinner av seg selv etter noen sekunder. */
@Composable
fun NoticeHost(modifier: Modifier = Modifier) {
    val msg = Notify.current
    val last = remember { arrayOfNulls<Notify.Msg>(1) }
    if (msg != null) last[0] = msg
    AnimatedVisibility(msg != null, modifier, enter = fadeIn() + slideInVertically { it / 3 }, exit = fadeOut()) {
        val m = last[0] ?: return@AnimatedVisibility
        Row(
            Modifier.widthIn(max = 640.dp).heightIn(min = 52.dp).clip(RoundedCornerShape(24.dp)).background(Card)
                .border(1.dp, Line, RoundedCornerShape(24.dp))
                .clickable(onClickLabel = "Lukk meldingen") { Notify.dismiss() }
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Info, null, tint = TextMuted, modifier = Modifier.size(20.dp))
            Text(m.text, color = TextPrimary, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/* ======================= Statuslinje ======================= */

/** [VENDOR]: Android ser ingen telefon, men stereoens egen BT-app styrer Bluetooth – kan være tilkoblet. */
enum class BtState { NONE, OFF, ON, CONNECTED, VENDOR }

/** Det T'OS sin statuslinje viser. Bluetooth leses jevnlig av Root. */
object TosStatus {
    var bt by mutableStateOf(BtState.NONE)
}

/** Tilkoblet telefon vinner (også når Androids Bluetooth er av – stereoens egen BT-brikke kan ha den). Testes i BtLogicTest. */
internal fun btState(s: BtStatus): BtState = when {
    s.phone != null -> BtState.CONNECTED
    s.vendorPkg != null -> BtState.VENDOR
    !s.available -> BtState.NONE
    !s.on -> BtState.OFF
    else -> BtState.ON
}

@Composable
private fun StatusIcon(icon: ImageVector, desc: String) =
    Icon(icon, desc, tint = TextMuted, modifier = Modifier.size(18.dp))

/**
 * Små gråtone-ikoner for nett, Bluetooth, GPS og volum.
 * [full] = alt (statuslinja på sidene). Ellers bare det som er verdt å vite (forsiden): uten nett og tilkoblet telefon.
 */
@Composable
fun StatusIcons(full: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when (TosStatus.bt) {
            BtState.CONNECTED -> StatusIcon(Icons.Default.BluetoothConnected, "Telefon tilkoblet")
            BtState.ON -> if (full) StatusIcon(Icons.Default.Bluetooth, "Bluetooth på, ingen tilkoblet")
            BtState.OFF -> if (full) StatusIcon(Icons.Default.BluetoothDisabled, "Bluetooth av")
            BtState.VENDOR -> if (full) StatusIcon(Icons.Default.Bluetooth, "Bluetooth styres av stereoen")
            BtState.NONE -> {}
        }
        if (!Hub.online) StatusIcon(Icons.Default.WifiOff, "Ikke internett")
        else if (full) StatusIcon(Icons.Default.Wifi, "Internett tilkoblet")
        if (full) {
            val g = Hub.gps
            when {
                Hub.gpsNoPermission -> StatusIcon(Icons.Default.LocationDisabled, "Posisjon ikke tillatt")
                g != null && !g.stale -> StatusIcon(Icons.Default.GpsFixed, "GPS")
                else -> StatusIcon(Icons.Default.GpsNotFixed, "Søker GPS")
            }
            val v = Ui.volume
            if (v >= 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusIcon(if (v == 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "Volum $v")
                Text("$v", color = TextMuted, fontSize = 13.sp)
            }
        }
    }
}

/** Slank statuslinje øverst på sidene (ikke forsiden – der viser bilkortet klokka). Trykk = hurtigpanelet. */
@Composable
fun TosStatusBar(modifier: Modifier = Modifier) {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    // Teksten (ikke tidspunktet) er tilstanden: linja tegnes bare på nytt når minuttet skifter
    var time by remember { mutableStateOf(fmt.format(Date())) }
    LaunchedEffect(Unit) { while (true) { Power.awaitUi(); time = fmt.format(Date()); delay(1000L - System.currentTimeMillis() % 1000L) } }
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).clickable(onClickLabel = "Åpne hurtigpanelet") { Nav.quick = true }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("T'OS", color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        StatusIcons(full = true)
        Spacer(Modifier.width(14.dp))
        Text(time, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
