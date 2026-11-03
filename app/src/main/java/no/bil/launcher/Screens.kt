package no.bil.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/* ---------- Felles topp: tittel + lukk ---------- */

@Composable
private fun PageHeader(title: String, onClose: () -> Unit, extra: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        extra()
        RoundIcon(Icons.Default.Close, 48.dp) { onClose() }
    }
}

/* ======================= Bluetooth-side ======================= */

data class BtDevice(val name: String, val connected: Boolean, val battery: Int = -1, val address: String = "")
data class BtStatus(val available: Boolean, val on: Boolean, val devices: List<BtDevice>)

/**
 * Leser Bluetooth via Android. NB: på mange bilstereoer er telefon-Bluetooth en egen brikke som
 * stereoens egen BT-app styrer – da ser Android ingenting, og vi viser det ærlig.
 */
@SuppressLint("MissingPermission")
fun btStatus(context: Context): BtStatus {
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        ?: return BtStatus(false, false, emptyList())
    if (Build.VERSION.SDK_INT >= 31 && !context.hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
        return BtStatus(true, adapter.isEnabled, emptyList())
    return runCatching {
        val devices = adapter.bondedDevices.orEmpty().map { d ->
            val connected = runCatching { d.javaClass.getMethod("isConnected").invoke(d) as Boolean }.getOrDefault(false)
            // Batteri fra telefonen (skjult Android-funksjon, virker bare der Android selv styrer Bluetooth)
            val battery = runCatching { d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int }.getOrDefault(-1)
            BtDevice(d.name ?: d.address, connected, battery, d.address)
        }.sortedByDescending { it.connected }
        BtStatus(true, adapter.isEnabled, devices)
    }.getOrDefault(BtStatus(true, adapter.isEnabled, emptyList()))
}

@Composable
fun BluetoothScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val status by produceState(initialValue = remember { btStatus(context) }) {
        while (true) { value = btStatus(context); delay(3000) }
    }

    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Panel).padding(24.dp)) {
        PageHeader("Bluetooth", onClose)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {

            // Venstre: status og enheter
            Column(
                Modifier.weight(0.45f).fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(Card).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val connected = status.devices.firstOrNull { it.connected }
                Text(
                    when {
                        !status.available -> "Bluetooth styres av stereoen"
                        !status.on -> "Bluetooth er av"
                        connected != null -> connected.name
                        else -> "Ingen telefon tilkoblet"
                    },
                    color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                )
                Text(
                    when {
                        connected == null -> "Åpne Telefon for å koble til"
                        connected.battery in 0..100 -> "Tilkoblet · batteri ${connected.battery} %"
                        else -> "Tilkoblet"
                    },
                    color = TextMuted, fontSize = 14.sp,
                )
                if (status.devices.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Kjente enheter", color = TextMuted, fontSize = 13.sp)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(status.devices) { d ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(
                                    Modifier.size(10.dp).clip(CircleShape)
                                        .then(if (d.connected) Modifier.background(TextPrimary) else Modifier.border(1.5.dp, TextMuted, CircleShape))
                                )
                                Text(d.name, color = if (d.connected) TextPrimary else TextMuted, fontSize = 16.sp, maxLines = 1)
                            }
                        }
                    }
                } else Spacer(Modifier.weight(1f))

                // Musikk fra telefonen over Bluetooth
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Musikk", color = TextMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    RoundIcon(Icons.Default.SkipPrevious, 44.dp) { Media.previous(context) }
                    RoundIcon(Icons.Default.PlayArrow, 56.dp, outlined = true) { Media.playPause(context) }
                    RoundIcon(Icons.Default.SkipNext, 44.dp) { Media.next(context) }
                }
            }

            // Høyre: fire store valg
            Column(Modifier.weight(0.55f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    BigTile(Icons.Default.Phone, "Telefon", Modifier.weight(1f)) { AppRepository.launchTile(context, Tiles.byPrefix("Tel")) }
                    BigTile(Icons.Default.Headphones, "Bluetooth-musikk", Modifier.weight(1f)) { AppRepository.launchTile(context, Tiles.byPrefix("Blu")) }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    BigTile(Icons.Default.Star, "Favoritter", Modifier.weight(1f)) { Nav.screen = Screen.PHONE }
                    BigTile(Icons.Default.PhoneIphone, "CarPlay", Modifier.weight(1f)) { AppRepository.launchTile(context, Tiles.byPrefix("Car")) }
                    BigTile(Icons.Default.Settings, "Innstillinger", Modifier.weight(1f)) {
                        Prefs.set("settings_cat", "BT"); Nav.screen = Screen.SETTINGS   // T'OS sin egen Bluetooth-side
                    }
                }
            }
        }
    }
}

@Composable
fun BigTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(Card).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = TextPrimary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text(label, color = TextPrimary, fontSize = 16.sp)
    }
}

/* ======================= Knapper-side ======================= */

@Composable
fun ActionPicker(current: Action, modifier: Modifier, prefix: String = "", onPick: (Action) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable { menu = true }.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(prefix + current.label, color = if (current == Action.NONE && prefix.isNotEmpty()) TextMuted else TextPrimary, fontSize = 15.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Default.ArrowDropDown, null, tint = TextMuted)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Action.entries.forEach { a ->
                DropdownMenuItem(text = { Text(a.label) }, onClick = { onPick(a); menu = false })
            }
        }
    }
}

@Composable
fun KeysScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val serviceOn by produceState(initialValue = Buttons.serviceEnabled(context)) {
        while (true) { value = Buttons.serviceEnabled(context); delay(1500) }
    }
    // Gi opp læring etter 10 sekunder
    val target = Learn.target
    LaunchedEffect(target) {
        if (target != null) {
            delay(10_000)
            if (Learn.target == target) Learn.cancel("Fikk ikke noe signal fra ${target.label}. Prøv igjen, og sjekk at knappestyring er på.")
        }
    }

    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Panel).padding(24.dp)) {
        PageHeader("Knapper", onClose) {
            Row(
                Modifier.height(48.dp).clip(RoundedCornerShape(24.dp))
                    .background(if (serviceOn) Card else TextPrimary)
                    .clickable {
                        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(if (serviceOn) Icons.Default.Check else Icons.Default.PowerSettingsNew, null,
                    tint = if (serviceOn) TextPrimary else Bg, modifier = Modifier.size(20.dp))
                Text(if (serviceOn) "Knappestyring er på" else "Slå på knappestyring",
                    color = if (serviceOn) TextPrimary else Bg, fontSize = 14.sp)
            }
            Spacer(Modifier.width(12.dp))
        }

        Text(
            Learn.target?.let { "Trykk ${it.label}-knappen på stereoen nå …" }
                ?: Learn.message
                ?: "Trykk «Lær» og så knappen på stereoen. Velg deretter hva knappen skal gjøre.",
            color = if (Learn.target != null) TextPrimary else TextMuted, fontSize = 15.sp,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        // Lista leser Buttons.version selv, så den oppdateres uten å hoppe til toppen
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(PanelKey.entries.toList(), key = { it.name }) { k -> KeyRow(k) { } }
        }
    }
}

@Composable
private fun KeyRow(k: PanelKey, onChanged: () -> Unit) {
    val context = LocalContext.current
    val action = Buttons.action(context, k)
    val code = Buttons.code(context, k)
    val pkg = Buttons.pkg(context, k)
    val learning = Learn.target == k

    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(18.dp)).background(Card).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(k.label, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(72.dp))

        // Hva knappen gjør: kort trykk og langt trykk (trykk for å endre)
        ActionPicker(action, Modifier.weight(1f)) { Buttons.setAction(context, k, it); onChanged() }
        ActionPicker(Buttons.longAction(context, k), Modifier.weight(0.8f), prefix = "Langt: ") { Buttons.setLongAction(context, k, it); onChanged() }

        Text(
            when {
                learning -> "Venter …"
                code > 0 -> "Kode $code"
                pkg != null -> pkg.substringAfterLast('.').let { "Åpner $it" }
                else -> "Ikke lært"
            },
            color = TextMuted, fontSize = 13.sp, maxLines = 1, modifier = Modifier.widthIn(max = 130.dp),
        )

        Box(
            Modifier.height(44.dp).clip(RoundedCornerShape(22.dp))
                .background(if (learning) TextPrimary else Line)
                .clickable { if (learning) Learn.cancel() else Learn.start(k); onChanged() }
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (learning) "Avbryt" else if (code > 0 || pkg != null) "Lær på nytt" else "Lær",
                color = if (learning) Bg else TextPrimary, fontSize = 14.sp)
        }
    }
}
