package no.bil.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.Equalizer
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun Context.go(i: Intent) = runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

/* ======================= Wi-Fi inne i T'OS ======================= */

/**
 * Viser nettverkene i nærheten og kobler til uten å gå via Android-innstillingene.
 * Android 10+ lar ikke apper koble direkte; T'OS legger nettverket inn som «foreslått»,
 * og stereoen kobler seg til av seg selv (første gang må et varsel godkjennes).
 */
@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
@Composable
fun WifiPanel() {
    val context = LocalContext.current
    val wm = remember { context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    var msg by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var pass by remember { mutableStateOf("") }
    val nets by produceState(emptyList<ScanResult>()) {
        while (true) {
            runCatching { wm.startScan() }
            value = runCatching { wm.scanResults }.getOrDefault(emptyList())
                .filter { it.SSID.isNotBlank() }.sortedByDescending { it.level }.distinctBy { it.SSID }
            delay(30_000)   // Android tillater bare noen få søk per 2 min
        }
    }
    val current = runCatching { wm.connectionInfo?.ssid?.trim('"') }.getOrNull()?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }

    SettingRow(if (Hub.online) "Tilkoblet internett" else "Ikke tilkoblet", current?.let { "Wi-Fi: $it" } ?: if (wm.isWifiEnabled) "Wi-Fi er på" else "Wi-Fi er av") {
        if (!wm.isWifiEnabled) Pill("Slå på", strong = true) {
            if (Build.VERSION.SDK_INT < 29) wm.isWifiEnabled = true else context.go(Intent(Settings.Panel.ACTION_WIFI))
        }
    }
    msg?.let { Text(it, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp)) }
    Section("Nettverk i nærheten")
    if (nets.isEmpty()) Text("Søker … (krever at posisjon er tillatt for T'OS)", color = TextMuted, fontSize = 14.sp)
    nets.forEach { n ->
        val eap = "EAP" in n.capabilities                     // bedriftsnett: brukernavn + passord
        val owe = "OWE" in n.capabilities && !eap              // «forbedret åpent» nett: kryptert, men uten passord
        val secured = !owe && listOf("WPA", "WEP", "SAE", "PSK").any { it in n.capabilities }
        val bars = WifiManager.calculateSignalLevel(n.level, 4)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card)) {
            Row(
                Modifier.fillMaxWidth().clickable { open = if (open == n.SSID) null else n.SSID; pass = "" }.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(listOf(Icons.Default.SignalWifi0Bar, Icons.Default.NetworkWifi1Bar, Icons.Default.NetworkWifi3Bar, Icons.Default.SignalWifi4Bar)[bars.coerceIn(0, 3)], null, tint = TextMuted)
                Text(n.SSID, color = TextPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1)
                if (n.SSID == current) Text("Tilkoblet", color = TextMuted, fontSize = 13.sp)
                if (secured) Icon(Icons.Default.Lock, null, tint = TextMuted, modifier = Modifier.size(18.dp))
            }
            if (open == n.SSID && n.SSID != current) Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (secured && !eap) Field(pass, "Passord", Modifier.weight(1f), KeyboardType.Password) { pass = it } else Spacer(Modifier.weight(1f))
                Pill("Koble til", strong = true) {
                    val wep = "WEP" in n.capabilities
                    val wpa3 = "SAE" in n.capabilities && "PSK" !in n.capabilities   // bare WPA3 (ikke blandet WPA2/WPA3)
                    when {
                        eap -> { msg = "Dette nettet krever brukernavn – koble til i Android-innstillinger"; open = null; context.go(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                        wep -> { msg = "«${n.SSID}» bruker gammel sikkerhet (WEP) som T'OS ikke kan koble til. Bruk Android-innstillingene."; open = null }
                        owe && Build.VERSION.SDK_INT < 30 -> { msg = "«${n.SSID}» bruker en sikkerhet (OWE) som T'OS ikke kan koble til her – koble til i Android-innstillinger"; open = null; context.go(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                        secured && pass.length !in 8..63 -> msg = "Passordet må være 8–63 tegn"
                        Build.VERSION.SDK_INT >= 30 -> {
                            // Android 11+: legges inn som brukerens eget nettverk og kobles til med én gang (ett «Lagre»-trykk)
                            val b = WifiNetworkSuggestion.Builder().setSsid(n.SSID)
                            if (owe) b.setIsEnhancedOpen(true)
                            else if (secured) { if (wpa3) b.setWpa3Passphrase(pass) else b.setWpa2Passphrase(pass) }
                            val ok = context.go(Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                                .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(b.build())))
                            msg = if (ok) "Trykk «Lagre» for å koble til «${n.SSID}»" else connectWifi(wm, n.SSID, pass.takeIf { secured }, wpa3, owe)
                            open = null
                        }
                        else -> { msg = connectWifi(wm, n.SSID, pass.takeIf { secured }, wpa3, owe); open = null }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    SettingRow("Internett fra iPhonen", "På iPhonen: Innstillinger → Personlig Wi-Fi-sone → «Tillat andre å koble til». Velg den så i lista over.")
}

@Suppress("DEPRECATION")
private fun connectWifi(wm: WifiManager, ssid: String, pass: String?, wpa3: Boolean, owe: Boolean = false): String {
    return if (Build.VERSION.SDK_INT >= 29) {
        val b = WifiNetworkSuggestion.Builder().setSsid(ssid)
        // «Forbedret åpent» nett (OWE) må merkes som det – som vanlig åpent nett kobler stereoen aldri til
        if (owe) b.setIsEnhancedOpen(true)
        else if (pass != null) { if (wpa3) b.setWpa3Passphrase(pass) else b.setWpa2Passphrase(pass) }
        val r = runCatching { wm.removeNetworkSuggestions(listOf(b.build())); wm.addNetworkSuggestions(listOf(b.build())) }.getOrDefault(-1)
        if (r == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS)
            "«$ssid» er lagt til. Første gang: godkjenn varselet «Tillat foreslåtte Wi-Fi-nettverk». Stereoen kobler seg så til av seg selv."
        else "Fikk ikke lagt til «$ssid» (kode $r)."
    } else runCatching {
        val conf = android.net.wifi.WifiConfiguration().apply {
            SSID = "\"$ssid\""
            if (pass != null) preSharedKey = "\"$pass\"" else allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
        }
        val id = wm.addNetwork(conf)
        wm.enableNetwork(id, true); wm.reconnect()
        "Kobler til «$ssid» …"
    }.getOrDefault("Fikk ikke koblet til «$ssid».")
}

/* ======================= Bluetooth inne i T'OS ======================= */

@SuppressLint("MissingPermission")
@Composable
fun BtPanel() {
    val context = LocalContext.current
    val adapter = remember { (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter }
    val status by produceState(initialValue = remember { btStatus(context) }, Prefs.version) { while (true) { value = btStatus(context); delay(3000) } }
    val found = remember { mutableStateListOf<BluetoothDevice>() }
    var scanning by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    // Når tillatelsen er gitt, starter søket med en gang (ikke nødvendig å trykke igjen)
    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) {
            found.clear()
            scanning = runCatching { adapter?.startDiscovery() == true }.getOrDefault(false)
            if (!scanning) msg = "Fikk ikke startet søket. Er Bluetooth på?"
        } else msg = "T'OS trenger lov til å søke etter Bluetooth-enheter"
    }

    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        if (d != null && found.none { it.address == d.address } && runCatching { d.bondState }.getOrDefault(0) != BluetoothDevice.BOND_BONDED) found += d
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                        msg = when (st) { BluetoothDevice.BOND_BONDED -> "Paret!"; BluetoothDevice.BOND_BONDING -> "Parer … følg eventuell kode på skjermen"; else -> msg }
                        if (st == BluetoothDevice.BOND_BONDED) Prefs.set("bt_v", System.currentTimeMillis())
                    }
                }
            }
        }
        val f = IntentFilter().apply { addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED); addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else context.registerReceiver(r, f)
        onDispose { runCatching { context.unregisterReceiver(r); adapter?.cancelDiscovery() } }
    }

    SettingRow(
        when { adapter == null || !status.available -> "Bluetooth styres av stereoen"; status.on -> "Bluetooth er på"; else -> "Bluetooth er av" },
        status.devices.firstOrNull { it.connected }?.let { "Tilkoblet: ${it.name}" } ?: "Ingen enhet tilkoblet",
    ) {
        if (adapter != null && !status.on) Pill("Slå på", strong = true) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) runCatching { adapter.enable() } else context.go(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }
    msg?.let { Text(it, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp)) }
    if (status.devices.isNotEmpty()) {
        Section("Kjente enheter")
        status.devices.forEach { d ->
            SettingRow(d.name, if (d.connected) "Tilkoblet" + (if (d.battery in 0..100) " · ${d.battery} %" else "") else "Ikke tilkoblet") {
                Text("Glem", color = TextPrimary, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Line).clickable {
                    val ok = runCatching {
                        val dev = adapter?.bondedDevices?.firstOrNull { it.address == d.address } ?: error("ikke funnet")
                        dev.javaClass.getMethod("removeBond").invoke(dev) as Boolean
                    }.getOrDefault(false)
                    Prefs.set("bt_v", System.currentTimeMillis())
                    msg = if (ok) "${d.name} er glemt" else "Fikk ikke glemt ${d.name}. Stereoen tillater det bare i Android-innstillingene."
                }.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
    Section("Par ny enhet")
    SettingRow(if (scanning) "Søker etter enheter …" else "Søk etter enheter", "Telefon, OBD-brikke, tuningboks o.l. Sett enheten i paringsmodus først.") {
        Pill(if (scanning) "Stopp" else "Søk", Icons.Default.BluetoothSearching, strong = !scanning) {
            if (scanning) { runCatching { adapter?.cancelDiscovery() }; scanning = false; return@Pill }
            val need = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            if (need.any { !context.hasPermission(it) }) { askPerms.launch(need); return@Pill }
            found.clear()
            scanning = runCatching { adapter?.startDiscovery() == true }.getOrDefault(false)
            if (!scanning) msg = "Fikk ikke startet søket. Er Bluetooth på?"
        }
    }
    found.forEach { d ->
        val name = runCatching { d.name }.getOrNull() ?: d.address
        SettingRow(name, "Trykk for å pare", onClick = { msg = "Parer med $name …"; runCatching { d.createBond() } }) { Icon(Icons.Default.Add, null, tint = TextMuted) }
    }
    SettingRow("Telefon og Bluetooth-musikk", "Stereoens egen Bluetooth-app (samtaler, kontakter)", onClick = { AppRepository.launchTile(context, Tiles.byPrefix("Tel")) }) {
        Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
    }
}

/* ======================= Equalizer inne i T'OS ======================= */

/** Equalizer på all lyd (økt 0). Mange bilstereoer tillater det; ellers vises stereoens egen. */
object Eq {
    private var eq: Equalizer? = null
    fun get(): Equalizer? = eq ?: runCatching { Equalizer(0, 0) }.getOrNull()?.also { eq = it }

    fun restore() {
        if (!Prefs.bool("eq_on")) return
        val e = get() ?: return
        runCatching {
            for (b in 0 until e.numberOfBands) {
                val v = Prefs.int("eq_band_$b", Int.MIN_VALUE)
                if (v != Int.MIN_VALUE) e.setBandLevel(b.toShort(), v.toShort())
            }
            e.enabled = true
        }
    }
}

@Composable
fun EqPanel() {
    val context = LocalContext.current
    val e = remember { Eq.get() }
    if (e == null) {
        SettingRow("Equalizer", "Stereoen lar ikke apper styre lydbildet. Åpne stereoens egen equalizer.", onClick = {
            val ok = context.go(Intent(android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC))
            if (!ok) context.go(Intent(Settings.ACTION_SOUND_SETTINGS))
        }) { Icon(Icons.Default.ChevronRight, null, tint = TextMuted) }
        return
    }
    var on by remember { mutableStateOf(Prefs.bool("eq_on")) }
    var levels by remember { mutableStateOf((0 until e.numberOfBands).map { runCatching { e.getBandLevel(it.toShort()).toInt() }.getOrDefault(0) }) }
    val range = remember { runCatching { e.bandLevelRange }.getOrNull() ?: shortArrayOf(-1500, 1500) }
    SettingRow("Equalizer", if (on) "På" else "Av", onClick = { on = !on; Prefs.set("eq_on", on); runCatching { e.enabled = on } }) { Toggle(on) }
    if (!on) return
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (0 until runCatching { e.numberOfPresets.toInt() }.getOrDefault(0)).take(6).forEach { p ->
            Pill(runCatching { e.getPresetName(p.toShort()) }.getOrDefault("Forhåndsvalg")) {
                runCatching {
                    e.usePreset(p.toShort())
                    levels = (0 until e.numberOfBands).map { e.getBandLevel(it.toShort()).toInt() }
                    levels.forEachIndexed { i, v -> Prefs.set("eq_band_$i", v) }
                }
            }
        }
    }
    levels.forEachIndexed { i, v ->
        val label = eqLabel(runCatching { e.getCenterFreq(i.toShort()) }.getOrDefault(0))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(horizontal = 18.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(label, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.width(80.dp))
            Slider(v.toFloat(), { nv ->
                levels = levels.toMutableList().also { it[i] = nv.toInt() }
                runCatching { e.setBandLevel(i.toShort(), nv.toInt().toShort()) }
            }, onValueChangeFinished = { Prefs.set("eq_band_$i", levels[i]) },
                valueRange = range[0].toFloat()..range[1].toFloat(), modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = TextPrimary, activeTrackColor = TextPrimary, inactiveTrackColor = Line))
            Text(String.format(java.util.Locale("nb", "NO"), "%+.0f dB", v / 100f), color = TextMuted, fontSize = 13.sp, modifier = Modifier.width(56.dp))
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** Midtfrekvens (milliHz fra Equalizer) som tekst: «60 Hz», «3,6 kHz», «14 kHz». Testes i DataLogicTest. */
internal fun eqLabel(milliHz: Int): String {
    val hz = milliHz / 1000
    if (hz < 1000) return "$hz Hz"
    val tenths = Math.round(hz / 100.0).toInt()   // kHz med én desimal
    return if (tenths % 10 == 0) "${tenths / 10} kHz" else "${tenths / 10},${tenths % 10} kHz"
}

/* ======================= Velg apper for knapper og fliser ======================= */

@Composable
fun AppChoiceRow(title: String, key: String, tilePrefix: String) {
    val context = LocalContext.current
    val chosen = Prefs.str(key)
    val pkg by produceState<String?>(null, chosen) {
        value = withContext(Dispatchers.IO) { AppRepository.tilePackage(context, Tiles.byPrefix(tilePrefix)) }
    }
    val label = remember(pkg) { pkg?.let { runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull() } }
    SettingRow(title, (label ?: "Ikke funnet") + if (Prefs.str(key).isBlank()) " (automatisk)" else "", onClick = { Nav.pick = title to key }) {
        Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
    }
}

/* ======================= Førstegangsoppsett ======================= */

/** Forklarer hver tillatelse og hvorfor T'OS trenger den, med en knapp rett til riktig sted. (Idé fra OpenLauncher.) */
@Composable
fun SetupScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Hub.startGps(); Prefs.set("perm_v", System.currentTimeMillis()) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }   // oppdater haker når du kommer tilbake fra Innstillinger
    val isHome = remember(tick) {
        context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName == context.packageName
    }
    val btPerms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN) else emptyArray()

    @Composable fun Step(icon: ImageVector, title: String, why: String, done: Boolean, optional: Boolean = false, action: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(if (done) Icons.Default.CheckCircle else icon, null, tint = if (done) TextPrimary else TextMuted, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                Text(title + if (optional) " (valgfritt)" else "", color = TextPrimary, fontSize = 16.sp)
                Text(why, color = TextMuted, fontSize = 13.sp)
            }
            if (!done) Pill("Slå på", strong = !optional) { action() } else Text("Klar", color = TextMuted, fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
    }

    // Lukkes oppsettet med X, kommer det ikke tilbake av seg selv (finnes under Innstillinger → Om stereoen)
    Page("Velkommen til T'OS", { Prefs.set("setup_done", true); onClose() }, extra = { Pill("Ferdig", Icons.Default.Check, strong = true) { Prefs.set("setup_done", true); onClose() }; Spacer(Modifier.width(12.dp)) }) {
        Text("Slå på det du vil bruke. Alt kan endres senere under Innstillinger.", color = TextMuted, fontSize = 15.sp, modifier = Modifier.padding(bottom = 12.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val fine = context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            val coarseOnly = !fine && context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            Step(Icons.Default.LocationOn, "Posisjon",
                if (coarseOnly) "Bare «omtrentlig» er tillatt – velg «Nøyaktig» for fart, fartsgrense og turmåler." else "Fart, kart, fartsgrense, nattmodus og turmåler.", fine) {
                // Android 12+ krever at nøyaktig og omtrentlig spørres om sammen (ellers skjer ingenting).
                // Android 13+: også lov til å vise det stille varselet «T'OS bruker posisjon».
                val ask = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) ask += Manifest.permission.POST_NOTIFICATIONS
                askPerms.launch(ask.toTypedArray())
            }
            Step(Icons.Default.Home, "T'OS som startskjerm", "Hjem-knappen og MENU åpner T'OS.", isHome) {
                context.go(Intent(Settings.ACTION_HOME_SETTINGS)) || context.go(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
            Step(Icons.Default.Tune, "Knappestyring", "Knappene på stereoen og rattet, og Waze-popupen. Slå på «T'OS knapper». Er valget grått: Apper → T'OS → ⋮ → «Tillat begrensede innstillinger».", Buttons.serviceEnabled(context)) {
                context.go(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            Step(Icons.Default.MusicNote, "Sangtittel og cover", "Viser hva som spiller, neste sving fra Google Maps og hvem som ringer.", MediaListener.enabled(context)) {
                openNotificationAccess(context)
            }
            if (btPerms.isNotEmpty()) Step(Icons.Default.Bluetooth, "Bluetooth", "Se tilkoblet telefon, pare enheter, batterispenning fra OBD-brikke.", btPerms.all { context.hasPermission(it) }) {
                askPerms.launch(btPerms)
            }
            Step(Icons.Default.Layers, "Vis over andre apper", "Reserve for Waze-popupen hvis knappestyring er av.", Settings.canDrawOverlays(context), optional = true) {
                context.go(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            }
            Step(Icons.Default.LightMode, "Styre lysstyrken", "Demper hele stereoen om natten.", Settings.System.canWrite(context), optional = true) {
                context.go(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
            }
            Step(Icons.Default.Backup, "Hente gamle innstillinger", "Har du lagret innstillingene til fil før, hent dem under Innstillinger → Sikkerhetskopi.", Prefs.bool("setup_restored"), optional = true) {
                Prefs.set("setup_restored", true); Prefs.set("settings_cat", "BACKUP"); Nav.screen = Screen.SETTINGS
            }
        }
    }
}
