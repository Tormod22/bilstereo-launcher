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

/* ======================= Felles ======================= */

/** Øker hver gang T'OS kommer tilbake på skjermen (f.eks. fra en Android-tillatelsesside), så status sjekkes på nytt. */
@Composable
fun rememberResumeTick(): Int {
    var t by remember { mutableIntStateOf(0) }
    val lc = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lc) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) t++ }
        lc.addObserver(o)
        onDispose {
            // Lifecycle krever hovedtråden (i røyktestene kan opprydding skje fra testtråden)
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) lc.removeObserver(o)
            else android.os.Handler(android.os.Looper.getMainLooper()).post { lc.removeObserver(o) }
        }
    }
    return t
}

/* ======================= Wi-Fi inne i T'OS ======================= */

/** Hvordan T'OS kobler til et nett (ren logikk, testes i DataLogicTest3). */
internal enum class WifiWay { SUGGEST, LEGACY, ANDROID_DIALOG, ANDROID_SETTINGS, UNSUPPORTED }

/**
 * Velger måten med minst mulig av Androids egne vinduer:
 * Android 10+ → «foreslått nett» (ingen boks når forslag fra T'OS er godkjent én gang);
 * er forslag avslått → Androids lille «Lagre nett»-boks (Android 11+); Android 8–9 → kobler direkte.
 */
internal fun wifiWay(sdk: Int, eap: Boolean, wep: Boolean, owe: Boolean, suggestionsRejected: Boolean): WifiWay = when {
    eap -> WifiWay.ANDROID_SETTINGS                 // brukernavn + sertifikat: bare Android-innstillingene kan det
    wep -> WifiWay.UNSUPPORTED
    sdk < 29 -> if (owe) WifiWay.ANDROID_SETTINGS else WifiWay.LEGACY
    owe && sdk < 30 -> WifiWay.ANDROID_SETTINGS
    suggestionsRejected -> if (sdk >= 30) WifiWay.ANDROID_DIALOG else WifiWay.ANDROID_SETTINGS
    else -> WifiWay.SUGGEST
}

/**
 * Viser nettverkene i nærheten og kobler til uten å gå via Android-innstillingene.
 * Android 10+ lar ikke apper koble direkte; T'OS legger nettverket inn som «foreslått»,
 * og stereoen kobler seg til av seg selv (første gang må et varsel «Tillat foreslåtte nett» godkjennes).
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
    var waiting by remember { mutableStateOf<String?>(null) }          // nett T'OS venter på at stereoen kobler til
    var stuck by remember { mutableStateOf<WifiNetworkSuggestion?>(null) }   // koblet ikke til av seg selv: tilby Androids boks
    var stuckSsid by remember { mutableStateOf("") }
    var lastSugg by remember { mutableStateOf<WifiNetworkSuggestion?>(null) }
    // Android 12+: har brukeren godkjent/avslått forslag fra T'OS?
    var approval by remember { mutableIntStateOf(-1) }
    if (Build.VERSION.SDK_INT >= 31) DisposableEffect(Unit) {
        val l = WifiManager.SuggestionUserApprovalStatusListener { st -> approval = st }
        runCatching { wm.addSuggestionUserApprovalStatusListener(context.mainExecutor, l) }
        onDispose { runCatching { wm.removeSuggestionUserApprovalStatusListener(l) } }
    }
    val rejected = Build.VERSION.SDK_INT >= 31 && approval == WifiManager.STATUS_SUGGESTION_APPROVAL_REJECTED_BY_USER
    val nets by produceState(emptyList<ScanResult>()) {
        while (true) {
            runCatching { wm.startScan() }
            // Noen stereoer svarer null i stedet for en tom liste
            value = runCatching { wm.scanResults }.getOrNull().orEmpty()
                .filter { !it.SSID.isNullOrBlank() }.sortedByDescending { it.level }.distinctBy { it.SSID }
            delay(30_000)   // Android tillater bare noen få søk per 2 min
        }
    }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    val current = remember(tick) { runCatching { wm.connectionInfo?.ssid?.trim('"') }.getOrNull()?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } }
    val wifiOn = remember(tick) { runCatching { wm.isWifiEnabled }.getOrDefault(false) }

    // Vent på at stereoen kobler til et foreslått nett; går det ikke på 25 s, tilbys Androids «Lagre»-boks
    LaunchedEffect(waiting) {
        val ssid = waiting ?: return@LaunchedEffect
        repeat(25) {
            delay(1000)
            if (runCatching { wm.connectionInfo?.ssid?.trim('"') }.getOrNull() == ssid) {
                msg = "Tilkoblet «$ssid»"; Prefs.set("wifi_sugg_ok", true); waiting = null; return@LaunchedEffect
            }
        }
        waiting = null
        if (Build.VERSION.SDK_INT >= 30 && lastSugg != null) { stuck = lastSugg; stuckSsid = ssid }
        msg = if (Prefs.bool("wifi_sugg_ok")) "«$ssid» er lagret, men stereoen har ikke koblet til ennå. Den gjør det når nettet er innen rekkevidde."
              else "Har du godkjent varselet «Tillat foreslåtte Wi-Fi-nettverk»? Det ligger øverst på skjermen (trekk ned)."
    }

    fun androidDialog(s: WifiNetworkSuggestion, ssid: String): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val ok = context.go(Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(s)))
        if (ok) msg = "Trykk «Lagre» i Android-boksen for å koble til «$ssid»"
        return ok
    }

    SettingRow(if (Hub.online) "Tilkoblet internett" else "Ikke tilkoblet", current?.let { "Wi-Fi: $it" } ?: if (wifiOn) "Wi-Fi er på" else "Wi-Fi er av") {
        if (!wifiOn) Pill("Slå på", strong = true) {
            // Android 10+ lar ikke vanlige apper slå på Wi-Fi: da vises Androids lille Wi-Fi-panel
            val ok = Build.VERSION.SDK_INT < 29 && runCatching { wm.isWifiEnabled = true; true }.getOrDefault(false)
            if (!ok) {
                context.go(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS))
                msg = "Slå på Wi-Fi i panelet som kommer opp (Android krever det)"
            }
        }
    }
    msg?.let { Text(it, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp)) }
    stuck?.let { s ->
        SettingRow("Koble til «$stuckSsid» nå", "Åpner Androids lille «Lagre nett»-boks", onClick = { androidDialog(s, stuckSsid); stuck = null }) { Chevron() }
    }
    if (rejected) SettingRow("Forslag fra T'OS er avslått", "T'OS bruker derfor Androids «Lagre nett»-boks. Med PC kan det slås på igjen (se docs/ADB-OPPSETT.md).")
    Section("Nettverk i nærheten")
    if (nets.isEmpty()) Text(if (wifiOn) "Søker … (krever at posisjon er tillatt for T'OS)" else "Slå på Wi-Fi for å se nettverk", color = TextMuted, fontSize = 14.sp)
    nets.forEach { n ->
        val eap = "EAP" in n.capabilities                     // bedriftsnett: brukernavn + passord
        val owe = "OWE" in n.capabilities && !eap              // «forbedret åpent» nett: kryptert, men uten passord
        val wep = "WEP" in n.capabilities
        val secured = !owe && listOf("WPA", "WEP", "SAE", "PSK").any { it in n.capabilities }
        val bars = WifiManager.calculateSignalLevel(n.level, 4)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { open = if (open == n.SSID) null else n.SSID; pass = "" }.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(listOf(Icons.Default.SignalWifi0Bar, Icons.Default.NetworkWifi1Bar, Icons.Default.NetworkWifi3Bar, Icons.Default.SignalWifi4Bar)[bars.coerceIn(0, 3)], null, tint = TextMuted)
                Text(n.SSID, color = TextPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1)
                if (n.SSID == current) Text("Tilkoblet", color = TextMuted, fontSize = 13.sp)
                else if (n.SSID == waiting) Text("Kobler til …", color = TextMuted, fontSize = 13.sp)
                if (secured) Icon(Icons.Default.Lock, "Passord", tint = TextMuted, modifier = Modifier.size(18.dp))
            }
            if (open == n.SSID && n.SSID != current) Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (secured && !eap && !wep) Field(pass, "Passord", Modifier.weight(1f), KeyboardType.Password) { pass = it } else Spacer(Modifier.weight(1f))
                Pill("Koble til", strong = true) {
                    val wpa3 = "SAE" in n.capabilities && "PSK" !in n.capabilities   // bare WPA3 (ikke blandet WPA2/WPA3)
                    val way = wifiWay(Build.VERSION.SDK_INT, eap, wep, owe, rejected)
                    if (way != WifiWay.ANDROID_SETTINGS && way != WifiWay.UNSUPPORTED && secured && pass.length !in 8..63) { msg = "Passordet må være 8–63 tegn"; return@Pill }
                    open = null
                    when (way) {
                        WifiWay.ANDROID_SETTINGS -> {
                            msg = if (eap) "«${n.SSID}» krever brukernavn – Android-innstillingene for Wi-Fi åpnes" else "«${n.SSID}» må kobles til i Android-innstillingene"
                            context.go(Intent(Settings.ACTION_WIFI_SETTINGS))
                        }
                        WifiWay.UNSUPPORTED -> msg = "«${n.SSID}» bruker gammel sikkerhet (WEP) som ikke er trygg. Bytt til WPA2 på ruteren."
                        WifiWay.LEGACY -> msg = connectWifiLegacy(wm, n.SSID, pass.takeIf { secured })
                        WifiWay.ANDROID_DIALOG -> {
                            val ok = Build.VERSION.SDK_INT >= 30 && androidDialog(wifiSuggestion(n.SSID, pass.takeIf { secured }, wpa3, owe), n.SSID)
                            if (!ok) msg = "Fikk ikke åpnet Androids Wi-Fi-boks"
                        }
                        WifiWay.SUGGEST -> if (Build.VERSION.SDK_INT >= 29) {
                            val sg = wifiSuggestion(n.SSID, pass.takeIf { secured }, wpa3, owe)
                            lastSugg = sg; stuck = null
                            val r = runCatching { wm.removeNetworkSuggestions(listOf(sg)); wm.addNetworkSuggestions(listOf(sg)) }.getOrDefault(-1)
                            when {
                                r == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS -> {
                                    waiting = n.SSID
                                    runCatching { wm.startScan() }
                                    val approved = Prefs.bool("wifi_sugg_ok") || (Build.VERSION.SDK_INT >= 31 && approval == WifiManager.STATUS_SUGGESTION_APPROVAL_APPROVED_BY_USER)
                                    msg = if (approved) "Kobler til «${n.SSID}» …"
                                          else "«${n.SSID}» er lagt inn. Første gang: godkjenn varselet «Tillat foreslåtte Wi-Fi-nettverk» øverst på skjermen."
                                }
                                // Forslag ikke tillatt (avslått eller fullt): Androids lille boks i stedet
                                androidDialog(sg, n.SSID) -> Unit
                                else -> msg = "Fikk ikke lagt til «${n.SSID}» (kode $r)."
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    // Nett T'OS har lagt inn (Android 11+ kan liste dem)
    if (Build.VERSION.SDK_INT >= 30) {
        val mine = remember(tick / 15) { runCatching { wm.networkSuggestions }.getOrNull().orEmpty() }
        if (mine.isNotEmpty()) {
            var ask by remember { mutableStateOf(false) }
            LaunchedEffect(ask) { if (ask) { delay(4000); ask = false } }
            Section("Lagret av T'OS")
            SettingRow(mine.joinToString { it.ssid ?: "?" }, if (ask) "Stereoen kobler fra disse. Trykk igjen." else "Stereoen kobler seg til disse av seg selv", onClick = {
                if (!ask) { ask = true; return@SettingRow }
                ask = false
                runCatching { wm.removeNetworkSuggestions(mine) }
                msg = "Nettene er fjernet"; tick++
            }) { Text(if (ask) "Trykk igjen" else "Glem alle", color = TextPrimary, fontSize = 14.sp) }
        }
    }
    SettingRow("Internett fra iPhonen", "På iPhonen: Innstillinger → Personlig Wi-Fi-sone → «Tillat andre å koble til». Velg den så i lista over.")
}

@Composable private fun Chevron() = Icon(Icons.Default.ChevronRight, null, tint = TextMuted)

@androidx.annotation.RequiresApi(29)
private fun wifiSuggestion(ssid: String, pass: String?, wpa3: Boolean, owe: Boolean): WifiNetworkSuggestion {
    val b = WifiNetworkSuggestion.Builder().setSsid(ssid)
    // «Forbedret åpent» nett (OWE) må merkes som det – som vanlig åpent nett kobler stereoen aldri til
    if (owe) b.setIsEnhancedOpen(true)
    else if (pass != null) { if (wpa3) b.setWpa3Passphrase(pass) else b.setWpa2Passphrase(pass) }
    return b.build()
}

/** Android 8–9: legger inn og kobler til direkte, uten noen boks. */
@Suppress("DEPRECATION")
private fun connectWifiLegacy(wm: WifiManager, ssid: String, pass: String?): String = runCatching {
    val conf = android.net.wifi.WifiConfiguration().apply {
        SSID = "\"$ssid\""
        if (pass != null) preSharedKey = "\"$pass\"" else allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
    }
    val id = wm.addNetwork(conf)
    check(id != -1)
    wm.enableNetwork(id, true); wm.reconnect()
    "Kobler til «$ssid» …"
}.getOrDefault("Fikk ikke koblet til «$ssid». Sjekk passordet.")

/* ======================= Bluetooth inne i T'OS ======================= */

@SuppressLint("MissingPermission")
@Composable
fun BtPanel() {
    val context = LocalContext.current
    val adapter = remember { (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter }
    val status by produceState(initialValue = BtLink.current, Prefs.version, BtLink.version) {
        while (true) { value = withContext(Dispatchers.IO) { btStatus(context) }; delay(3000) }
    }
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
        btShortText(status),
    ) {
        if (adapter != null && !status.on) Pill("Slå på", strong = true) {
            @Suppress("DEPRECATION")
            val direct = Build.VERSION.SDK_INT < 33 && runCatching { adapter.enable() }.getOrDefault(false)
            if (!direct) {
                // Android 13+ lar ikke apper slå på Bluetooth selv – Android viser en liten «Tillat»-boks
                context.go(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                msg = "Trykk «Tillat» i boksen som kommer opp (Android 13 krever det)"
            } else msg = "Slår på Bluetooth …"
        }
    }
    msg?.let { Text(it, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp)) }
    if (Fyt.btReady) {
        // FYT-stereoen: telefonen er koblet til stereoens egen BT-brikke (Android ser den bare som «paret»)
        Section("Stereoens Bluetooth (FYT)")
        FytBtCard(Modifier.padding(bottom = 8.dp))
        ToggleRow("Telefonen i T'OS (FYT)", "fyt_phone", true,
            "Ringe, kontakter og siste anrop via stereoens Bluetooth – stereoens telefon-app trengs ikke. Av = som før.")
        ToggleRow("Samtaleskjermen i T'OS (FYT)", "fyt_call_screen", true,
            "Når det ringer, eller du ringer ut, vises T'OS sin samtaleskjerm i stedet for stereoens egen.")
    }
    if (status.devices.isNotEmpty()) {
        Section("Kjente enheter")
        status.devices.forEach { d ->
            // Styrer stereoens egen BT-app tilkoblingen, vet ikke Android om telefonen er tilkoblet – da bare «Paret»
            val off = if (status.vendorPkg != null) "Paret" else "Ikke tilkoblet"
            SettingRow(d.name, if (d.connected) "Tilkoblet" + (if (d.battery in 0..100) " · ${d.battery} %" else "") else off) {
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
        Pill(if (scanning) "Stopp" else "Søk", Icons.AutoMirrored.Filled.BluetoothSearching, strong = !scanning) {
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
        SettingRow(name, "Trykk for å pare", onClick = {
            msg = "Parer med $name … Android kan vise en kode – trykk «Par» hvis den er lik på begge"
            if (!runCatching { d.createBond() }.getOrDefault(false)) msg = "Fikk ikke startet paring med $name"
        }) { Icon(Icons.Default.Add, "Par", tint = TextMuted) }
    }
    // T'OS sine egne sider (stereoens Bluetooth-app trengs ikke)
    SettingRow("Telefon", "Samtaler, kontakter og siste anrop i T'OS", onClick = { Nav.screen = Screen.PHONE }) {
        Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
    }
    SettingRow("Bluetooth-musikk", "Velg «Bluetooth» øverst på musikksiden", onClick = { Nav.screen = Screen.MUSIC }) {
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
        var none by remember { mutableStateOf(false) }
        SettingRow("Equalizer", if (none) "Fant ingen equalizer på stereoen" else "Stereoen lar ikke apper styre lydbildet. Trykk for stereoens egen equalizer.", onClick = {
            none = !context.go(Intent(android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                .putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                .putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC))
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

/** Én ADB-kommando i oppsettet med PC, og hva den gjør (i vanlige ord). */
internal data class AdbStep(val cmd: String, val what: String)

/** Knappestyringen legges til de tjenestene stereoen alt har på (ikke erstatt dem). Ren logikk, testes. */
internal fun accessibilityValue(existing: String?, ours: String): String {
    val list = existing.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() && it != "null" }
    return if (list.any { it.equals(ours, ignoreCase = true) }) list.joinToString(":") else (list + ours).joinToString(":")
}

/**
 * Alle kommandoene som gir T'OS alle tillatelser med PC, uten at Android-menyene vises på stereoen.
 * Samme liste står i docs/ADB-OPPSETT.md og docs/tos-oppsett.bat (en test passer på at de stemmer).
 * [accessibility] = hele verdien for enabled_accessibility_services (se [accessibilityValue]).
 */
internal fun adbSetupCommands(pkg: String, accessibility: String = "$pkg/$pkg.KeyService"): List<AdbStep> {
    val perms = listOf(
        "ACCESS_FINE_LOCATION" to "posisjon (nøyaktig)", "ACCESS_COARSE_LOCATION" to "posisjon",
        "BLUETOOTH_CONNECT" to "Bluetooth", "BLUETOOTH_SCAN" to "søke etter Bluetooth-enheter",
        "POST_NOTIFICATIONS" to "varsel om at GPS-en er i bruk", "READ_CONTACTS" to "kontakter",
        "READ_CALL_LOG" to "siste anrop", "CALL_PHONE" to "ringe", "READ_PHONE_STATE" to "se når telefonen ringer",
    )
    return listOf(
        AdbStep("adb shell settings put secure enabled_accessibility_services $accessibility", "Knappestyring («T'OS knapper»)"),
        AdbStep("adb shell settings put secure accessibility_enabled 1", "Slår på knappestyringen"),
        AdbStep("adb shell cmd notification allow_listener $pkg/$pkg.MediaListener", "Sangtittel, cover, neste sving og hvem som ringer"),
        AdbStep("adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow", "Vise over andre apper (Waze-popupen)"),
        AdbStep("adb shell appops set $pkg WRITE_SETTINGS allow", "Lysstyrke, tastelyd og 24-timers klokke"),
        AdbStep("adb shell appops set $pkg REQUEST_INSTALL_PACKAGES allow", "Installere oppdateringer av T'OS"),
        AdbStep("adb shell appops set $pkg ACCESS_RESTRICTED_SETTINGS allow", "Android 13: «begrensede innstillinger»"),
        AdbStep("adb shell appops set $pkg GET_USAGE_STATS allow", "Siste apper: se hvilke apper som er brukt"),
    ) + perms.map { (p, what) -> AdbStep("adb shell pm grant $pkg android.permission.$p", what.replaceFirstChar { it.uppercase() }) } + listOf(
        AdbStep("adb shell cmd wifi network-suggestions-set-user-approved $pkg yes", "Wi-Fi uten spørsmål (Android 11+)"),
        AdbStep("adb shell dumpsys deviceidle whitelist +$pkg", "Ikke stoppet av batterisparing"),
        AdbStep("adb shell cmd package set-home-activity $pkg/.MainActivity", "T'OS som startskjerm"),
        AdbStep("adb shell cmd role add-role-holder android.app.role.DIALER $pkg", "T'OS som telefon-app"),
        AdbStep(FytBars.ADB_HIDE, "Valgfritt, bare FYT-stereoer: skjuler stereoens statuslinje i alle apper (angre: samme linje med 1 til slutt)"),
    )
}

/** Stereoens IP-adresse på Wi-Fi (til «adb connect»), eller null. */
private fun localIp(c: Context): String? = runCatching {
    val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }
        ?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress
}.getOrNull()

/** Stegene i førstegangsoppsettet. Hvilke som vises avgjøres av [setupSteps] (ren logikk, testet). */
internal enum class SetupStep { HOME, DIALER, KEYS, NOTES, USAGE, LOCATION, BT, STEER, BAR, SELFTEST, UPDATES, OVERLAY, WRITE, RESTORE }

/**
 * Hvilke steg oppsettet viser. På en FYT-stereo (bekreftet på brukerens LEHX: ekte Android 10) er T'OS allerede startskjerm og
 * telefon-app – de stegene vises bare hvis de mangler. Der trengs ikke «Vis over andre apper» eller «Styre lysstyrken» (lysstyrken går
 * via stereoen), og Bluetooth-tillatelsen finnes bare på Android 12+. I stedet: lær rattknappene, skjul stereoens statuslinje
 * ([barKnown] = innstillingen finnes) og kjør selvtesten.
 */
internal fun setupSteps(fyt: Boolean, sdk: Int, isHome: Boolean, dialerAvailable: Boolean, dialerDone: Boolean, barKnown: Boolean): List<SetupStep> {
    val s = mutableListOf<SetupStep>()
    if (fyt) {
        if (!isHome) s += SetupStep.HOME
        if (dialerAvailable && !dialerDone) s += SetupStep.DIALER
        s += listOf(SetupStep.KEYS, SetupStep.NOTES, SetupStep.USAGE, SetupStep.LOCATION, SetupStep.STEER)
        if (barKnown) s += SetupStep.BAR
        s += listOf(SetupStep.SELFTEST, SetupStep.UPDATES, SetupStep.RESTORE)
    } else {
        s += listOf(SetupStep.LOCATION, SetupStep.HOME, SetupStep.KEYS, SetupStep.NOTES)
        if (sdk >= 31) s += SetupStep.BT
        if (dialerAvailable) s += SetupStep.DIALER
        s += listOf(SetupStep.UPDATES, SetupStep.USAGE, SetupStep.OVERLAY, SetupStep.WRITE, SetupStep.RESTORE)
    }
    return s
}

/** Selvtesten (Screen.SELFTEST) hvis den finnes i denne versjonen, ellers Innstillinger → Sikkerhetskopi og hjelp. */
internal fun openSelfTest() {
    Prefs.set("setup_selftest", true)
    val st = Screen.entries.firstOrNull { it.name == "SELFTEST" }
    if (st != null) Nav.screen = st else { Prefs.set("settings_cat", "BACKUP"); Nav.screen = Screen.SETTINGS }
}

/** Forklarer hver tillatelse og hvorfor T'OS trenger den, med en knapp rett til riktig sted. (Idé fra OpenLauncher.) */
@Composable
fun SetupScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { Hub.startGps(); Prefs.set("perm_v", System.currentTimeMillis()) }
    val dialerRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        Prefs.set("perm_v", System.currentTimeMillis())
    }
    val resume = rememberResumeTick()
    var poll by remember { mutableIntStateOf(0) }
    // Sjekk igjen når du kommer tilbake fra Android, og hvert 1,5 s (så hakene kommer med en gang ved oppsett med PC)
    LaunchedEffect(Unit) { while (true) { delay(1500); poll++ } }
    val tick = resume * 100_000 + poll
    val isHome = remember(tick) {
        context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName == context.packageName
    }
    val btPerms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN) else emptyArray()
    var withPc by remember { mutableStateOf(false) }
    val fyt = remember { Fyt.present(context) }
    val bar = remember(tick) { FytBars.current(context) }
    val dialerAvailable = remember(tick) { Phone.dialerAvailable(context) }
    val dialerDone = remember(tick) { Phone.isDialer(context) && Phone.missingPermissions(context).isEmpty() }
    var barAdb by remember { mutableStateOf(false) }
    val steps = setupSteps(fyt, Build.VERSION.SDK_INT, isHome, dialerAvailable, dialerDone, bar != null)

    @Composable fun Step(icon: ImageVector, title: String, why: String, done: Boolean, optional: Boolean = false, verb: String = "Slå på", action: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(18.dp)).background(Card).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(if (done) Icons.Default.CheckCircle else icon, if (done) "Klar" else null, tint = if (done) TextPrimary else TextMuted, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                Text(title + if (optional) " (valgfritt)" else "", color = TextPrimary, fontSize = 16.sp)
                Text(why, color = TextMuted, fontSize = 13.sp)
            }
            // Ferdige steg kan åpnes igjen (rattknapper/selvtest kan man ville gjøre på nytt)
            if (!done) Pill(verb, strong = !optional) { action() } else Text("✓ Klar", color = TextMuted, fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
    }

    // Lukkes oppsettet med X, kommer det ikke tilbake av seg selv (finnes under Innstillinger → Om stereoen)
    Page("Velkommen til T'OS", { Prefs.set("setup_done", true); onClose() }, extra = { Pill("Ferdig", Icons.Default.Check, strong = true) { Prefs.set("setup_done", true); onClose() }; Spacer(Modifier.width(12.dp)) }) {
        Text(
            (if (fyt && isHome) "T'OS er startskjermen${if (dialerDone) " og telefon-appen" else ""} på stereoen. Gjør stegene under én gang. " else "Slå på det du vil bruke. ") +
                "«Slå på» åpner Androids egen side for akkurat den tillatelsen (det kan ikke unngås). Vil du slippe Android-menyene helt: bruk PC-en nederst.",
            color = TextMuted, fontSize = 15.sp, modifier = Modifier.padding(bottom = 12.dp),
        )
        Column(Modifier.verticalScroll(rememberScrollState())) {
            for (step in steps) when (step) {
                SetupStep.LOCATION -> {
                    val fine = remember(tick) { context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) }
                    val coarseOnly = !fine && context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    Step(Icons.Default.LocationOn, "Posisjon",
                        if (coarseOnly) "Bare «omtrentlig» er tillatt – velg «Nøyaktig» for fart, fartsgrense og turmåler." else "Fart, kart, fartsgrense, nattmodus og turmåler.", fine) {
                        // Android 12+ krever at nøyaktig og omtrentlig spørres om sammen (ellers skjer ingenting).
                        // Android 13+: også lov til å vise det stille varselet «T'OS bruker posisjon».
                        val ask = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 33) ask += Manifest.permission.POST_NOTIFICATIONS
                        askPerms.launch(ask.toTypedArray())
                    }
                }
                SetupStep.HOME -> Step(Icons.Default.Home, "T'OS som startskjerm", "Hjem-knappen og MENU åpner T'OS.", isHome) {
                    context.go(Intent(Settings.ACTION_HOME_SETTINGS)) || context.go(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                }
                SetupStep.KEYS -> Step(Icons.Default.Tune, "Knappestyring",
                    "Knappene på stereoen og rattet, volumlinja og nedtrekksmenyen i T'OS-stil. Slå på «T'OS knapper»." +
                        if (Build.VERSION.SDK_INT >= 33) " Er valget grått: Apper → T'OS → ⋮ → «Tillat begrensede innstillinger»." else "",
                    remember(tick) { Buttons.serviceEnabled(context) }) {
                    context.go(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                SetupStep.NOTES -> Step(Icons.Default.MusicNote, "Varseltilgang", "Sangtittel og cover, varsler i nedtrekksmenyen, neste sving fra kartet og hvem som ringer.",
                    remember(tick) { MediaListener.enabled(context) }) { openNotificationAccess(context) }
                SetupStep.BT -> Step(Icons.Default.Bluetooth, "Bluetooth", "Se tilkoblet telefon, pare enheter, batterispenning fra OBD-brikke.", remember(tick) { btPerms.all { context.hasPermission(it) } }) {
                    askPerms.launch(btPerms)
                }
                SetupStep.DIALER -> Step(Icons.Default.Phone, "T'OS som telefon-app",
                    "Samtaler vises i T'OS i stedet for stereoens egen telefonskjerm. Ring, kontakter og siste anrop inne i T'OS.",
                    dialerDone, optional = true) {
                    val missing = Phone.missingPermissions(context)
                    if (!Phone.isDialer(context)) Phone.dialerRoleIntent(context)?.let { dialerRole.launch(it) }
                    else if (missing.isNotEmpty()) askPerms.launch(missing.toTypedArray())
                }
                SetupStep.UPDATES -> Step(Icons.Default.SystemUpdate, "Installere oppdateringer", "Oppdateringsknappen i T'OS. Første oppdatering ber om ett «Installer»-trykk, senere går det av seg selv.",
                    remember(tick) { Updater.canInstall(context) }, optional = true) { Updater.openInstallPermission(context) }
                SetupStep.USAGE -> Step(Icons.Default.History, "Tilgang til appbruk", "Full liste i «Siste apper». Uten dette viser T'OS bare apper den selv har sett.",
                    remember(tick) { RecentApps.hasUsageAccess(context) }, optional = !fyt) { RecentApps.openUsageAccess(context) }
                SetupStep.STEER -> Step(Icons.Default.TouchApp, "Lær rattknappene",
                    "Rattknappene må læres i stereoen én gang: trykk en flis, hold inne knappen på rattet, og lagre.",
                    FytSteer.learned.values.any { SteerAdc.isLearnedValue(it) }, verb = "Åpne") { Nav.screen = Screen.STEER }
                SetupStep.BAR -> Step(Icons.Default.VisibilityOff, "Skjul stereoens statuslinje",
                    if (barAdb) "Android lar ikke T'OS gjøre dette selv. Med PC: ${FytBars.ADB_HIDE}  (angre: ${FytBars.ADB_SHOW})"
                    else "Linja øverst i andre apper (hjem, klokke, volum). T'OS har sin egen.",
                    bar == 0, optional = true, verb = "Skjul") {
                    when {
                        FytBars.set(context, show = false) -> Prefs.set("perm_v", System.currentTimeMillis())
                        FytBars.hideButtonInstalled(context) && FytBars.openHideButton(context) -> {}
                        else -> { barAdb = true; withPc = true }
                    }
                }
                SetupStep.SELFTEST -> Step(Icons.Default.TaskAlt, "Kjør selvtest",
                    "Sjekker at T'OS får kontakt med stereoen (radio, Bluetooth, lyd, knapper) og sier hva som mangler.",
                    Prefs.bool("setup_selftest"), verb = "Kjør") { openSelfTest() }
                SetupStep.OVERLAY -> Step(Icons.Default.Layers, "Vis over andre apper", "Reserve for Waze-popupen hvis knappestyring er av.", remember(tick) { Settings.canDrawOverlays(context) }, optional = true) {
                    context.go(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                }
                SetupStep.WRITE -> Step(Icons.Default.LightMode, "Styre lysstyrken", "Demper hele stereoen om natten. Også tastelyd og 24-timers klokke.", remember(tick) { Settings.System.canWrite(context) }, optional = true) {
                    context.go(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
                }
                SetupStep.RESTORE -> Step(Icons.Default.Backup, "Hente gamle innstillinger", "Har du lagret innstillingene før, hent dem under Innstillinger → Sikkerhetskopi.", Prefs.bool("setup_restored"), optional = true, verb = "Åpne") {
                    Prefs.set("setup_restored", true); Prefs.set("settings_cat", "BACKUP"); Nav.screen = Screen.SETTINGS
                }
            }

            // Alternativ: alt på én gang fra en PC, uten å se Android-menyene på stereoen
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Icons.Default.Computer, null, tint = TextMuted, modifier = Modifier.size(26.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Med PC (ADB) – uten å se Android-menyene", color = TextPrimary, fontSize = 16.sp)
                        Text("Gir T'OS alle tillatelsene på én gang. Krever en Windows-PC på samme Wi-Fi.", color = TextMuted, fontSize = 13.sp)
                    }
                    Pill(if (withPc) "Skjul" else "Vis", if (withPc) Icons.Default.ExpandLess else Icons.Default.ExpandMore) { withPc = !withPc }
                }
                if (withPc) {
                    val ip = remember(resume) { localIp(context) }
                    val acc = remember(tick) {
                        accessibilityValue(runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) }.getOrNull(),
                            "${context.packageName}/${context.packageName}.KeyService")
                    }
                    val mono = androidx.compose.ui.text.font.FontFamily.Monospace
                    Text("1. Last ned «SDK Platform-Tools for Windows» fra developer.android.com/tools/releases/platform-tools og pakk ut.\n" +
                        "2. På stereoen: slå på utvikleralternativer (trykk 7 ganger på «Versjonsnummer» under Om) og «Trådløs feilsøking» / «ADB over nettverk».\n" +
                        "3. Åpne «Ledetekst» i mappa platform-tools og skriv kommandoene under, én og én. Eller kjør docs/tos-oppsett.bat fra GitHub – den gjør alt.",
                        color = TextMuted, fontSize = 13.sp)
                    Text("Koble til stereoen: adb connect ${ip ?: "<stereoens IP>"}:5555", color = TextPrimary, fontSize = 13.sp, fontFamily = mono)
                    Text("(Android 11+ med «Trådløs feilsøking»: først «adb pair IP:port» med koden som vises på stereoen, så «adb connect» med porten som står der.)",
                        color = TextMuted, fontSize = 12.sp)
                    adbSetupCommands(context.packageName, acc).forEach { st ->
                        Column {
                            Text(st.cmd, color = TextPrimary, fontSize = 12.sp, fontFamily = mono)
                            Text(st.what, color = TextMuted, fontSize = 12.sp)
                        }
                    }
                    Text("En linje som gir feil (f.eks. «Unknown permission» eller «Unknown operation») gjelder en annen Android-versjon og kan overses. " +
                        "Hele veiledningen: github.com/${Updater.OWNER}/${Updater.REPO} → docs → ADB-OPPSETT.md", color = TextMuted, fontSize = 12.sp)
                    Pill("Utvikleralternativer (åpner Android-menyen)", Icons.Default.DeveloperMode) {
                        context.go(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) || context.go(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
