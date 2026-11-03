package no.bil.launcher

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * T'OS Innstillinger: erstatter stereoens egen innstillingsapp med samme mørke tema.
 * Det Android lar apper endre, endres her direkte. Resten åpner riktig systempanel.
 */
enum class SetCat(val label: String, val icon: ImageVector, val group: String) {
    NET("Wi-Fi og internett", Icons.Default.Wifi, "Stereoen"),
    BT("Bluetooth", Icons.Default.Bluetooth, "Stereoen"),
    SOUND("Lyd", Icons.Default.VolumeUp, "Stereoen"),
    SCREEN("Skjerm", Icons.Default.LightMode, "Stereoen"),
    APPS("Apper", Icons.Default.Apps, "Stereoen"),
    STORAGE("Lagring", Icons.Default.Storage, "Stereoen"),
    TIME("Dato, tid og språk", Icons.Default.Schedule, "Stereoen"),
    ABOUT("Om stereoen", Icons.Default.Info, "Stereoen"),
    MAP("Kart og kjøring", Icons.Default.Map, "T'OS"),
    MUSIC("Musikk og oppstart", Icons.Default.MusicNote, "T'OS"),
    PHONE("Telefon", Icons.Default.Phone, "T'OS"),
    CAR("Bil", Icons.Default.DirectionsCar, "T'OS"),
    KEYS("Knapper", Icons.Default.Tune, "T'OS"),
    BACKUP("Sikkerhetskopi og hjelp", Icons.Default.Backup, "T'OS"),
}

private fun Context.open(i: Intent) = runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
private fun Context.openFirst(vararg actions: String) { for (a in actions) if (open(Intent(a))) return }

@Composable
fun SettingsScreen(onClose: () -> Unit) {
    var cat by remember { mutableStateOf(SetCat.entries.firstOrNull { it.name == Prefs.str("settings_cat") } ?: SetCat.NET) }
    Page("Innstillinger", onClose) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            // Venstre: kategorier
            LazyColumn(Modifier.width(250.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SetCat.entries.groupBy { it.group }.forEach { (group, cats) ->
                    item { Text(group.uppercase(), color = TextMuted, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 6.dp)) }
                    items(cats) { c ->
                        val on = c == cat
                        Row(
                            Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp))
                                .background(if (on) Line else Panel)
                                .clickable { cat = c; Prefs.set("settings_cat", c.name) }.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(c.icon, null, tint = if (on) TextPrimary else TextMuted, modifier = Modifier.size(22.dp))
                            Text(c.label, color = if (on) TextPrimary else TextMuted, fontSize = 15.sp, maxLines = 1)
                        }
                    }
                }
            }
            // Høyre: innholdet
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Text(cat.label, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 12.dp))
                when (cat) {
                    SetCat.NET -> WifiPanel()
                    SetCat.BT -> BtPanel()
                    SetCat.SOUND -> SoundSettings()
                    SetCat.SCREEN -> ScreenSettings()
                    SetCat.APPS -> AppsSettings()
                    SetCat.STORAGE -> StorageSettings()
                    SetCat.TIME -> TimeSettings()
                    SetCat.ABOUT -> AboutSettings()
                    SetCat.MAP -> MapSettings()
                    SetCat.MUSIC -> MusicSettings()
                    SetCat.PHONE -> PhoneSettings()
                    SetCat.CAR -> CarSettings()
                    SetCat.KEYS -> { SettingRow("Knapper på stereoen og rattet", "Lær knappene og velg hva de gjør", onClick = { Nav.screen = Screen.KEYS }) { Chevron() } }
                    SetCat.BACKUP -> BackupSettings()
                }
            }
        }
    }
}

@Composable private fun Chevron() = Icon(Icons.Default.ChevronRight, null, tint = TextMuted)

@Composable
private fun SliderRow(title: String, icon: ImageVector, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = TextMuted)
        Text(title, color = TextPrimary, fontSize = 16.sp, modifier = Modifier.width(150.dp))
        Slider(value, onChange, valueRange = range, modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = TextPrimary, activeTrackColor = TextPrimary, inactiveTrackColor = Line))
    }
    Spacer(Modifier.height(8.dp))
}

/* ---------- Stereoen ---------- */

@Composable
private fun SoundSettings() {
    val context = LocalContext.current
    val am = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    @Composable fun stream(title: String, icon: ImageVector, s: Int) {
        val max = remember { am.getStreamMaxVolume(s).toFloat() }
        var v by remember { mutableFloatStateOf(am.getStreamVolume(s).toFloat()) }
        SliderRow(title, icon, v, 0f..max) { v = it; runCatching { am.setStreamVolume(s, it.toInt(), 0) } }
    }
    stream("Musikk og radio", Icons.Default.MusicNote, AudioManager.STREAM_MUSIC)
    stream("Samtaler", Icons.Default.Call, AudioManager.STREAM_VOICE_CALL)
    stream("Varsler", Icons.Default.Notifications, AudioManager.STREAM_NOTIFICATION)
    stream("Ringelyd", Icons.Default.RingVolume, AudioManager.STREAM_RING)
    Section("Equalizer")
    EqPanel()
    Spacer(Modifier.height(8.dp))
    ToggleRow("Lyd når du er over fartsgrensen", "speed_beep", false)
    SettingRow("Flere lydvalg", onClick = { context.openFirst(Settings.ACTION_SOUND_SETTINGS) }) { Chevron() }
}

@Composable
private fun ScreenSettings() {
    val context = LocalContext.current
    val canWrite = Settings.System.canWrite(context)
    val pickCar = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Hub.scope.launch {
            // Krymp til høyst 1200 px og lagre som PNG (et stort kamerabilde kunne ellers fått forsiden til å krasje)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(context.cacheDir, "car_in")
                    context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    val bmp = decodeScaled(tmp, 1200) ?: error("ikke et bilde")
                    File(context.filesDir, "car.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    tmp.delete()
                }.isSuccess
            }
            if (ok) Prefs.set("car_image_v", System.currentTimeMillis())
            context.toast(if (ok) "Bilbildet er lagret" else "Fikk ikke lest bildet")
        }
    }
    if (canWrite) {
        var b by remember { mutableFloatStateOf(runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(150).toFloat()) }
        SliderRow("Lysstyrke", Icons.Default.LightMode, b, 5f..255f) {
            b = it
            if (Prefs.float("app_brightness", -1f) >= 0f) Prefs.set("app_brightness", -1f)   // la systemet styre T'OS-vinduet også
            runCatching {
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, it.toInt())
            }
        }
    } else {
        var b by remember { mutableFloatStateOf(Prefs.float("app_brightness", 0.7f).let { if (it < 0) 0.7f else it }) }
        SliderRow("Lysstyrke (T'OS)", Icons.Default.LightMode, b, 0.05f..1f) { b = it; Prefs.set("app_brightness", it) }
        SettingRow("Styr lysstyrken for hele stereoen", "Gi T'OS lov til å endre systeminnstillinger", onClick = {
            context.open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
        }) { Chevron() }
    }
    ChoiceRow("Nattmodus", "night_mode", listOf("AUTO" to "Automatisk (etter sola)", "DAY" to "Alltid dag", "NIGHT" to "Alltid natt"), "AUTO")
    SettingRow("Demp hele stereoen om natten", if (canWrite) "Senker lysstyrken etter solnedgang" else "Krever at T'OS får styre lysstyrken (over)",
        onClick = { if (canWrite) Prefs.set("dim_system", !Prefs.bool("dim_system")) }) { Toggle(Prefs.bool("dim_system") && canWrite) }
    ChoiceRow("App-ikoner", "icon_style", listOf("DARK" to "Mørke (gråtoner)", "COLOR" to "Originale farger"), "DARK")
    ChoiceRow("Tekststørrelse", "text_scale_s", listOf("1.0" to "Normal", "1.1" to "Stor", "1.2" to "Ekstra stor"), "1.0")
    ChoiceRow("Klokke når bilen står stille", "saver_min", listOf("0" to "Av", "1" to "Etter 1 min", "3" to "Etter 3 min", "5" to "Etter 5 min"), "3")
    if (canWrite) {
        val cur = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrDefault(0)
        val opts = listOf(60_000 to "1 min", 300_000 to "5 min", 1_800_000 to "30 min", Int.MAX_VALUE to "Aldri")
        val i = opts.indexOfFirst { it.first == cur }.coerceAtLeast(0)
        var shown by remember { mutableIntStateOf(i) }
        SettingRow("Skjermen slås av etter", "Gjelder andre apper – T'OS holder skjermen på mens den vises", onClick = {
            shown = (shown + 1) % opts.size
            runCatching { Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, opts[shown].first) }
        }) { Text(opts[shown].second, color = TextPrimary, fontSize = 15.sp) }
    }
    SettingRow("Bilbilde", "PNG med svart eller gjennomsiktig bakgrunn", onClick = { pickCar.launch(arrayOf("image/*")) }) {
        if (File(context.filesDir, "car.png").exists()) Text("Fjern", color = TextPrimary, fontSize = 15.sp,
            modifier = Modifier.clickable { File(context.filesDir, "car.png").delete(); Prefs.set("car_image_v", System.currentTimeMillis()) }.padding(8.dp))
    }
}

@Composable
private fun AppsSettings() {
    val context = LocalContext.current
    val hidden = HiddenApps.userHidden()
    val apps by produceState(emptyList<AppEntry>(), Prefs.str("hidden_apps")) {
        value = withContext(Dispatchers.IO) { AppRepository.installedApps(context, includeHidden = true) }
    }
    SettingRow("Startskjerm", "Velg T'OS som startskjerm", onClick = {
        context.openFirst(Settings.ACTION_HOME_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)
    }) { Chevron() }
    ToggleRow("Hold T'OS som startskjerm", "keep_home", true, "Viser T'OS i stedet hvis den originale startskjermen dukker opp (krever knappestyring)")
    Section("Hvilke apper T'OS bruker")
    AppChoiceRow("CarPlay-app (f.eks. Car link 2.0)", "carplay_pkg", "Car")
    AppChoiceRow("Radio-app", "radio_pkg", "Rad")
    AppChoiceRow("Telefon-app (Bluetooth)", "phone_pkg", "Tel")
    AppChoiceRow("Bluetooth-musikk", "btmusic_pkg", "Blu")
    AppChoiceRow("Ryggekamera", "camera_pkg", "Rygg")
    AppChoiceRow("Musikkapp", "music_pkg", "Mus")
    Section("Alle apper (${apps.size}) – trykk for detaljer, «Skjul» fjerner fra app-lista")
    apps.forEach { a ->
        Row(
            Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(16.dp)).background(Card)
                .clickable { context.open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}"))) }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppIcon(a.icon, 38.dp, a.packageName)
            Text(a.label, color = TextPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1)
            val isHidden = a.packageName in hidden || HiddenApps.isHidden(a)
            Text(if (isHidden) "Vis" else "Skjul", color = TextPrimary, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Line).clickable { HiddenApps.toggle(a) }.padding(horizontal = 14.dp, vertical = 8.dp))
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun StorageSettings() {
    val context = LocalContext.current
    val stat = remember { StatFs(Environment.getDataDirectory().path) }
    val total = stat.totalBytes; val free = stat.availableBytes
    fun gb(b: Long) = String.format(Locale("nb", "NO"), "%.1f GB", b / 1_073_741_824.0)
    SettingRow("Brukt ${gb(total - free)} av ${gb(total)}", "${gb(free)} ledig")
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Line)) {
        Box(Modifier.fillMaxWidth(((total - free).toFloat() / total).coerceIn(0f, 1f)).fillMaxHeight().background(TextPrimary))
    }
    Spacer(Modifier.height(14.dp))
    val map = OfflineMaps.file(context)
    if (map.exists()) SettingRow("Kart uten nett", "${OfflineMaps.sizeMb(map.length())} MB")
    var tick by remember { mutableIntStateOf(0) }
    val cacheMb by produceState(0, tick) {
        value = withContext(Dispatchers.IO) { (context.cacheDir.walkBottomUp().sumOf { it.length() } / 1_048_576).toInt() }
    }
    SettingRow("Midlertidige filer i T'OS", "$cacheMb MB", onClick = {
        // «apk» (en oppdatering som lastes ned/installeres) får ligge
        Hub.scope.launch(Dispatchers.IO) { context.cacheDir.listFiles()?.filter { it.name != "apk" }?.forEach { it.deleteRecursively() }; tick++ }
        context.toast("Tømt")
    }) { Text("Tøm", color = TextPrimary, fontSize = 15.sp) }
    SettingRow("Lagring i Android", onClick = { context.openFirst(Settings.ACTION_INTERNAL_STORAGE_SETTINGS, Settings.ACTION_SETTINGS) }) { Chevron() }
}

@Composable
private fun TimeSettings() {
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000) } }
    SettingRow(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), SimpleDateFormat("EEEE d. MMMM yyyy", Locale("nb", "NO")).format(now))
    SettingRow("Dato og tid", "Tidssone, automatisk tid", onClick = { context.openFirst(Settings.ACTION_DATE_SETTINGS) }) { Chevron() }
    SettingRow("Språk", Locale.getDefault().displayName, onClick = { context.openFirst(Settings.ACTION_LOCALE_SETTINGS) }) { Chevron() }
    SettingRow("Tastatur", onClick = { context.openFirst(Settings.ACTION_INPUT_METHOD_SETTINGS) }) { Chevron() }
}

@Composable
private fun AboutSettings() {
    val context = LocalContext.current
    val v = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
    SettingRow("T'OS $v", Hub.update?.let { "Ny versjon ${it.versionName} er klar" } ?: "Du har siste versjon") { UpdateButton() }
    SettingRow("Stereo", "${Build.MANUFACTURER} ${Build.MODEL}")
    SettingRow("Android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
    SettingRow("Prosessor", "${Build.HARDWARE} · ${Runtime.getRuntime().availableProcessors()} kjerner · ${Hub.cpuTemp?.let { "$it °C" } ?: "temperatur ukjent"}")
    SettingRow("Minne", Hub.memText)
    SettingRow("Førstegangsoppsett", "Gå gjennom tillatelsene på nytt", onClick = { Nav.screen = Screen.SETUP }) { Chevron() }
    SettingRow("Takk til", "Radiostyring og app-søk bygger på OpenLauncher (MIT). Kart © OpenStreetMap, Protomaps. Se docs/TREDJEPART.md.")
    Section("Avansert")
    SettingRow("Android-innstillinger", "Stereoens originale innstillinger", onClick = { context.openFirst(Settings.ACTION_SETTINGS) }) { Chevron() }
    SettingRow("Utvikleralternativer", "For ADB og feilsøking", onClick = { context.openFirst(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_DEVICE_INFO_SETTINGS) }) { Chevron() }
}

/* ---------- T'OS ---------- */

@Composable
private fun MapSettings() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { if (!OfflineMaps.ready(context)) OfflineMaps.checkRemote() }
    val mapReady = OfflineMaps.ready(context)
    SettingRow(
        "Kart uten nett (${OfflineMaps.LABEL})",
        OfflineMaps.progress?.let { "Laster ned … $it %" }
            ?: OfflineMaps.status
            ?: if (mapReady) OfflineMaps.downloadedText(context) + " · brukes på forsiden"
            else "Tegnes på stereoen og virker uten nett." + (OfflineMaps.remoteBytes?.let { " Ca. ${OfflineMaps.sizeMb(it)} MB – bruk Wi-Fi." } ?: ""),
    ) {
        when {
            OfflineMaps.progress != null -> {}
            mapReady -> { Pill("Oppdater") { OfflineMaps.update(context) }; Spacer(Modifier.width(8.dp)); Pill("Slett") { OfflineMaps.delete(context) } }
            else -> Pill("Last ned", Icons.Default.Download, strong = true) { OfflineMaps.download(context) }
        }
    }
    PrefField("Hjem-adresse", "addr_home", "Gate og sted")
    PrefField("Jobb-adresse", "addr_work", "Gate og sted")
    ChoiceRow("Kartapp", "nav_app", listOf("com.google.android.apps.maps" to "Google Maps", "com.waze" to "Waze"), "com.google.android.apps.maps")
    ToggleRow("Tilby den andre kartappen", "navi_popup", true, "Popup øverst til høyre i 5 sekunder: «Åpne heller Waze»")
    if (Prefs.bool("navi_popup", true) && !NaviPopup.possible(LocalContext.current)) {
        val ctx = LocalContext.current
        SettingRow("Popupen trenger tillatelse", "Slå på knappestyring, eller gi T'OS lov til å vises over andre apper", onClick = {
            ctx.open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        }) { Chevron() }
    }
    ChoiceRow("Fartsjustering", "speed_adj", listOf("0" to "Ingen", "-3" to "−3 %", "-5" to "−5 %", "3" to "+3 %", "5" to "+5 %"), "0",
        "Hvis farten i T'OS ikke stemmer med en fartsmåler du stoler på (idé fra OpenLauncher)")
    ToggleRow("Kartet følger kjøreretningen", "map_rotate", true)
    ToggleRow("Kartet zoomer ut i høy fart", "map_zoom_speed", true)
    ToggleRow("Vis fartsgrense", "speed_limit", true, "Fra OpenStreetMap. Kan mangle eller være feil – skiltene gjelder.")
    ToggleRow("Lyd når du er over fartsgrensen", "speed_beep", false)
}

@Composable
private fun MusicSettings() {
    val context = LocalContext.current
    SettingRow("Sangtittel og cover", if (MediaListener.enabled(context)) "Slått på" else "Trykk og slå på «T'OS» under varseltilgang", onClick = { openNotificationAccess(context) }) {
        Toggle(MediaListener.enabled(context))
    }
    AppChoiceRow("Musikkapp", "music_pkg", "Mus")
    ToggleRow("Fortsett musikken når bilen starter", "resume_music", true)
    ToggleRow("Åpne siste app når bilen starter", "resume_last_app", false)
}

@Composable
private fun PhoneSettings() {
    ToggleRow("Åpne CarPlay når iPhonen kobles til", "carplay_auto", false)
    SettingRow("Favoritter", "${Favorites.all().size} lagret", onClick = { Nav.screen = Screen.PHONE }) { Chevron() }
}

@Composable
private fun CarSettings() {
    ToggleRow("Vis spenning på bilbatteriet", "battery_show", true, "Fra OBD2-brikke, eller fra stereoen hvis den oppgir det")
    SettingRow("OBD2-brikke", CarBattery.obdStatus + (CarBattery.volt?.let { " · ${CarBattery.text(it)} fra ${CarBattery.source}" } ?: ""))
    PrefField("Neste EU-kontroll", "eu_date", "f.eks. 15.3.2027", KeyboardType.Number)
    PrefField("Neste service", "service_date", "f.eks. 1.6.2027", KeyboardType.Number)
    ToggleRow("Minn meg på dekkskift", "tire_remind", true, "Midten av oktober og slutten av mars")
    SettingRow("Tuning og ytelse", "Tuningboks-app og 0–100-måling", onClick = { Nav.screen = Screen.TUNING }) { Chevron() }
    SettingRow("Bil og tur", "Turmåler, parkering og påminnelser", onClick = { Nav.screen = Screen.CAR }) { Chevron() }
}

@Composable
private fun BackupSettings() {
    val context = LocalContext.current
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(Prefs.exportJson(context).toByteArray()) }
            context.toast("Innstillingene er lagret")
        }.onFailure { context.toast("Fikk ikke lagret filen") }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }.getOrNull()
            context.toast(if (text != null && Prefs.importJson(context, text)) "Innstillingene er hentet" else "Fant ingen T'OS-innstillinger i filen")
        }
    }
    SettingRow("Lagre innstillinger til fil", "Velg Google Drive for å ha dem trygt", onClick = { exportFile.launch("tos-innstillinger.json") }) { Chevron() }
    SettingRow("Hent innstillinger fra fil", onClick = { importFile.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Chevron() }
    if (SafeMode.on) SettingRow("Sikker modus er på", "T'OS krasjet flere ganger og startet uten kart uten nett, bilbilde og oppstartsmusikk. Send feilrapporten under, og slå av når feilen er rettet.") {
        Pill("Slå av", strong = true) { SafeMode.turnOff(context) }
    }
    SettingRow("Send oppsettrapport", "Apper, knappekoder og tillatelser på denne stereoen – lim inn i Claude, så tilpasses T'OS", onClick = {
        Hub.scope.launch {
            val text = withContext(Dispatchers.IO) { SetupReport.build(context) }
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "T'OS oppsettrapport").putExtra(Intent.EXTRA_TEXT, text)
            context.open(Intent.createChooser(i, "Send oppsettrapport"))
        }
    }) { Chevron() }
    SettingRow("Del feilrapport", "Send til deg selv og lim inn i Claude", onClick = {
        Hub.scope.launch {
            val text = withContext(Dispatchers.IO) { CrashLog.report(context) }
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "T'OS feilrapport").putExtra(Intent.EXTRA_TEXT, text)
            context.open(Intent.createChooser(i, "Del feilrapport"))
        }
    }) { Chevron() }
}
