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
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * T'OS Innstillinger: erstatter stereoens egen innstillingsapp med samme mørke tema.
 * Alt Android lar apper endre, endres her direkte. Stereoens originale innstillinger finnes bare
 * bak en bekreftelse under Om stereoen → Avansert.
 */
enum class SetCat(val label: String, val icon: ImageVector, val group: String, val words: String) {
    NET("Wi-Fi og internett", Icons.Default.Wifi, "Stereoen", "wifi wlan nett internett hotspot iphone passord"),
    BT("Bluetooth", Icons.Default.Bluetooth, "Stereoen", "bluetooth telefon paring pare obd brikke"),
    SOUND("Lyd", Icons.AutoMirrored.Filled.VolumeUp, "Stereoen", "lyd volum volumlinje equalizer eq bass diskant varsel ringelyd tastelyd alarm"),
    SCREEN("Skjerm", Icons.Default.LightMode, "Stereoen", "skjerm lysstyrke lys natt nattmodus dag ikoner tekst størrelse bilbilde skjermsparer klokke statuslinje nedtrekksmeny varsler sveip"),
    APPS("Apper", Icons.Default.Apps, "Stereoen", "apper app skjul vis startskjerm carplay car link radio kamera systemapper avinstaller siste nylige bakgrunn lukk"),
    STORAGE("Lagring", Icons.Default.Storage, "Stereoen", "lagring plass minne cache tøm kart minnepinne"),
    TIME("Dato, tid og språk", Icons.Default.Schedule, "Stereoen", "dato tid klokke tidssone 24 timer språk tastatur norsk"),
    ABOUT("Om stereoen", Icons.Default.Info, "Stereoen", "om versjon oppdatering oppdater stereo android prosessor minne oppsett tillatelser adb pc avansert"),
    MAP("Kart og kjøring", Icons.Default.Map, "T'OS", "kart navigasjon adresse hjem jobb waze maps fart fartsgrense popup"),
    MUSIC("Musikk og oppstart", Icons.Default.MusicNote, "T'OS", "musikk spotify oppstart sangtittel cover varseltilgang radio fm radiomotor"),
    PHONE("Telefon", Icons.Default.Phone, "T'OS", "telefon favoritter carplay ring samtale"),
    CAR("Bil", Icons.Default.DirectionsCar, "T'OS", "bil batteri spenning obd eu-kontroll service dekk dekkskift tuning tur"),
    KEYS("Knapper", Icons.Default.Tune, "T'OS", "knapper ratt lær tast menu navi"),
    BACKUP("Sikkerhetskopi og hjelp", Icons.Default.Backup, "T'OS", "sikkerhetskopi backup lagre hent fil rapport feil krasj sikker modus kartlegg kartlegging opptak"),
}

/** Kategoriene som passer søket (alle ord må finnes i navnet eller søkeordene). Ren logikk, testes. */
internal fun settingsSearch(query: String): List<SetCat> {
    val nb = Locale("nb", "NO")
    val words = query.lowercase(nb).split(' ', ',').filter { it.isNotBlank() }
    if (words.isEmpty()) return SetCat.entries
    return SetCat.entries.filter { c ->
        val hay = (c.label + " " + c.words).lowercase(nb)
        words.all { it in hay }
    }
}

private fun Context.open(i: Intent) = runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

/** Viser T'OS-melding (ikke Androids). */
private fun say(text: String) = Notify.show(text)

@Composable
fun SettingsScreen(onClose: () -> Unit) {
    var cat by remember { mutableStateOf(SetCat.entries.firstOrNull { it.name == Prefs.str("settings_cat") } ?: SetCat.NET) }
    var query by remember { mutableStateOf("") }
    val shown = remember(query) { settingsSearch(query) }
    Page("Innstillinger", onClose) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            // Venstre: søk + kategorier
            LazyColumn(Modifier.width(250.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    Field(query, "Søk i innstillinger", Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                        query = it
                        // Bare ett treff: vis det med en gang
                        settingsSearch(it).singleOrNull()?.let { c -> cat = c; Prefs.set("settings_cat", c.name) }
                    }
                }
                if (shown.isEmpty()) item { Text("Ingen treff", color = TextMuted, fontSize = 14.sp, modifier = Modifier.padding(12.dp)) }
                shown.groupBy { it.group }.forEach { (group, cats) ->
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
                    SetCat.KEYS -> {
                        SettingRow("Knapper på stereoen og rattet", "Lær knappene og velg hva de gjør", onClick = { Nav.screen = Screen.KEYS }) { Chevron() }
                        SettingRow("Rattknapper", "Lær rattknappene i stereoen (volum, neste, forrige, MODE …)", onClick = { Nav.screen = Screen.STEER }) { Chevron() }
                    }
                    SetCat.BACKUP -> BackupSettings()
                }
            }
        }
    }
}

@Composable private fun Chevron() = Icon(Icons.Default.ChevronRight, null, tint = TextMuted)

@Composable
private fun SliderRow(title: String, icon: ImageVector, value: Float, range: ClosedFloatingPointRange<Float>, onDone: () -> Unit = {}, onChange: (Float) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = TextMuted)
        Text(title, color = TextPrimary, fontSize = 16.sp, modifier = Modifier.width(150.dp))
        Slider(value, onChange, valueRange = range, onValueChangeFinished = onDone, modifier = Modifier.weight(1f),
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
        val max = remember { runCatching { am.getStreamMaxVolume(s) }.getOrDefault(15).toFloat() }
        var v by remember { mutableFloatStateOf(runCatching { am.getStreamVolume(s) }.getOrDefault(0).toFloat()) }
        var dragging by remember { mutableStateOf(false) }
        // Følg med når volumet endres med knappene på stereoen eller rattet
        LaunchedEffect(Unit) { while (true) { delay(1500); if (!dragging) v = runCatching { am.getStreamVolume(s) }.getOrDefault(v.toInt()).toFloat() } }
        SliderRow("$title  ${v.toInt()}", icon, v, 0f..max, onDone = { dragging = false }) {
            dragging = true; v = it
            HwVolume.markOwnChange()
            runCatching { am.setStreamVolume(s, it.toInt(), 0) }.onFailure { say("Stereoen tillot ikke å endre «$title» nå") }
            if (s == AudioManager.STREAM_MUSIC) Ui.volume = it.toInt()
        }
    }
    // FYT: det ekte volumet og lydbildet er stereoens (lydmodulen i com.syu.ms), ikke Androids lydstrømmer
    val fyt = FytSound.volumeReady
    if (fyt) {
        var drag by remember { mutableStateOf<Float?>(null) }
        val m = FytSound.max.coerceAtLeast(1)
        val shown = drag ?: (if (FytSound.muted) 0 else FytSound.level).coerceIn(0, m).toFloat()
        SliderRow("Volum  ${shown.roundToInt()}", Icons.AutoMirrored.Filled.VolumeUp, shown, 0f..m.toFloat(), onDone = { drag = null }) {
            drag = it; FytSound.setVolume(it.roundToInt())
        }
        SettingRow("Lyd av", "Demper all lyd fra stereoen", onClick = { if (!FytSound.toggleMute()) say("Stereoen svarte ikke") }) { Toggle(FytSound.muted) }
    }
    if (FytSound.pictureReady) FytSoundPanel()
    if (fyt) Section("Android-apper")
    stream(if (fyt) "Musikk i apper" else "Musikk og radio", Icons.Default.MusicNote, AudioManager.STREAM_MUSIC)
    stream("Samtaler", Icons.Default.Call, AudioManager.STREAM_VOICE_CALL)
    stream("Ringelyd", Icons.Default.RingVolume, AudioManager.STREAM_RING)
    stream("Varsler", Icons.Default.Notifications, AudioManager.STREAM_NOTIFICATION)
    stream("Alarm", Icons.Default.Alarm, AudioManager.STREAM_ALARM)
    stream("Systemlyder", Icons.Default.TouchApp, AudioManager.STREAM_SYSTEM)
    // Stereoens eget lydbilde (over) erstatter Androids equalizer; ellers vises den som før
    if (!FytSound.pictureReady) {
        Section("Equalizer")
        EqPanel()
        Spacer(Modifier.height(8.dp))
    }
    Section("Andre lyder")
    ToggleRow("Volum i T'OS-stil", "tos_volume", true,
        if (Fyt.connected) "Volumhjulet viser T'OS sin volumlinje oppå stereoens eget volumvindu (krever knappestyring)"
        else "Volumknappene viser T'OS sin volumlinje i stedet for stereoens (krever knappestyring)")
    // FYT: stereoens eget volum (0 til et ukjent høyeste tall). Linja regner med 40, eller det høyeste den har sett.
    if (Fyt.connected) ChoiceRow("Stereoens høyeste volum", "fyt_vol_max",
        listOf("0" to "Automatisk (${FytVolume.max})", "30" to "30", "35" to "35", "40" to "40", "45" to "45", "50" to "50", "60" to "60"), "0",
        "Bare for hvor full volumlinja blir. Skru helt opp én gang, så finner T'OS det selv.")
    ToggleRow("Lyd når du er over fartsgrensen", "speed_beep", false, "Ett kort pip når du kjører mer enn 3 km/t over grensen")
    // FYT: stereoens egen tastelyd (pip fra knappene) og navigasjonsmiks – bare når stereoen har meldt dem (FytMain)
    FytMain.beep?.let { on ->
        SettingRow("Pip fra stereoens knapper", "Stereoens egen tastelyd (FYT)", onClick = {
            FytMain.setBeep(!on) { ok -> if (!ok) say("Stereoen svarte ikke – tastelyden er uendret") }
        }) { Toggle(on) }
    }
    FytMain.naviMix?.let { on ->
        SettingRow("Senk musikken når kartappen snakker", "Stereoens navigasjonsmiks (FYT)", onClick = {
            FytMain.setNaviMix(!on) { ok -> if (!ok) say("Stereoen svarte ikke – innstillingen er uendret") }
        }) { Toggle(on) }
    }
    val tick = rememberResumeTick()
    val canWrite = remember(tick) { Settings.System.canWrite(context) }
    if (canWrite) {
        var touch by remember { mutableStateOf(runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED) }.getOrDefault(1) == 1) }
        SettingRow("Tastelyd", "Klikk når du trykker på skjermen", onClick = {
            val nv = !touch
            if (runCatching { Settings.System.putInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, if (nv) 1 else 0) }.getOrDefault(false)) {
                touch = nv
                runCatching { if (nv) am.loadSoundEffects() else am.unloadSoundEffects() }
            } else say("Stereoen tillot ikke å endre tastelyden")
        }) { Toggle(touch) }
    } else SettingRow("Tastelyd", "Krever at T'OS får endre systeminnstillinger (Skjerm → Styr lysstyrken for hele stereoen)")
}

@Composable
private fun ScreenSettings() {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val canWrite = remember(tick) { Settings.System.canWrite(context) }
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
            say(if (ok) "Bilbildet er lagret" else "Fikk ikke lest bildet")
        }
    }
    val fytBright = FytMain.brightReady
    if (fytBright) {
        // FYT: baklyset styres av stereoen (MCU-en), ikke av Android – T'OS sender lysstyrken dit (FytMain)
        var b by remember { mutableFloatStateOf(FytMain.fraction()) }
        SliderRow("Lysstyrke", Icons.Default.LightMode, b, 0.05f..1f) {
            b = it
            if (Prefs.float("app_brightness", -1f) >= 0f) Prefs.set("app_brightness", -1f)
            FytMain.setFromSlider(it)
        }
        if (Prefs.bool("dim_system")) {
            var n by remember { mutableFloatStateOf(Prefs.int("fyt_bright_night", 25) / 100f) }
            SliderRow("Lysstyrke om natten", Icons.Default.DarkMode, n, 0.05f..1f) {
                n = it
                val l = FytMainLogic.levelFor(it)
                Prefs.setQuiet("fyt_bright_night", l)
                if (Prefs.bool("fyt_night_dimmed")) FytMain.sendLevel(l)
            }
        }
    }
    if (Fyt.connected) ToggleRow("Lysstyrke via stereoen (FYT)", "fyt_bright", true,
        if (FytMain.brightConfirmed) "Stereoen bekrefter lysstyrken T'OS sender" else "Glideren styrer stereoens eget baklys. Virker den ikke, slå av – da prøver T'OS Androids lysstyrke.")
    if (!fytBright && canWrite) {
        var b by remember { mutableFloatStateOf(runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(150).toFloat()) }
        SliderRow("Lysstyrke", Icons.Default.LightMode, b, 5f..255f) {
            b = it
            if (Prefs.float("app_brightness", -1f) >= 0f) Prefs.set("app_brightness", -1f)   // la systemet styre T'OS-vinduet også
            runCatching {
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, it.toInt())
            }
        }
    } else if (!fytBright) {
        var b by remember { mutableFloatStateOf(Prefs.float("app_brightness", 0.7f).let { if (it < 0) 0.7f else it }) }
        SliderRow("Lysstyrke (T'OS)", Icons.Default.LightMode, b, 0.05f..1f) { b = it; Prefs.set("app_brightness", it) }
        SettingRow("Styr lysstyrken for hele stereoen", "Gi T'OS lov til å endre systeminnstillinger (åpner Androids tillatelsesside én gang – eller bruk PC, se Om stereoen → Førstegangsoppsett)", onClick = {
            context.open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
        }) { Chevron() }
    }
    ToggleRow("Skjul stereoens statuslinje", "hide_system_bars", true, "T'OS viser klokke og status selv. Sveip fra kanten viser linja et øyeblikk.")
    // FYT-stereoer: stereoens egen linje (hjem, klokke, volum, «siste apper») kan skjules i alle apper
    var fytBar by remember { mutableStateOf(FytBars.current(context)) }
    fytBar?.let { cur ->
        var adb by remember { mutableStateOf(false) }
        SettingRow("Skjul stereoens statuslinje overalt (FYT)",
            when {
                adb -> "Android lar ikke T'OS endre dette selv. Med PC: ${if (cur == 0) FytBars.ADB_SHOW else FytBars.ADB_HIDE}" +
                    (if (cur == 0) "" else "  (angre: ${FytBars.ADB_SHOW})")
                cur == 0 -> "Skjult i alle apper. Trykk for å vise den igjen."
                else -> "Linja øverst vises i andre apper. Trykk for å skjule den overalt."
            }, onClick = {
                if (FytBars.set(context, show = cur == 0)) { fytBar = FytBars.current(context); adb = false; say(if (cur == 0) "Statuslinja vises igjen" else "Statuslinja er skjult") }
                else adb = true
            }) { Toggle(cur == 0) }
        // Reserve: stereoens eget oppsett for statuslinja/knappene (HideButton, en FYT-app). Åpnes bare når du trykker.
        if (FytBars.hideButtonInstalled(context)) SettingRow("Åpne FYT HideButton",
            "Stereoens eget oppsett (ikke T'OS) – kan skjule stereoens statuslinje uten PC. Trenger bare gjøres én gang.",
            onClick = { if (!FytBars.openHideButton(context)) say("Fikk ikke åpnet HideButton") }) { Chevron() }
    }
    ToggleRow("Nedtrekksmeny i T'OS-stil", "tos_shade", true,
        "Sveip ned fra toppen viser T'OS sitt panel med varsler, lys og volum i stedet for stereoens")
    if (FytCan.active) ToggleRow("Klima og dører i T'OS-stil", "tos_canbus", true,
        "Når klimaet endres eller en dør åpnes, viser T'OS sitt eget kort oppå stereoens. Ryggeradaren vises alltid som før.")
    // FYT: stereoen vet om lysene er på (ILL-ledningen) – da kan natt følge lysene i stedet for sola
    val lightsKnown = FytMain.lights != null || Prefs.bool("fyt_lights_seen")
    ChoiceRow("Nattmodus", "night_mode", listOf("AUTO" to if (lightsKnown && FytMain.byLights) "Automatisk (lysene på bilen)" else "Automatisk (etter sola)",
        "DAY" to "Alltid dag", "NIGHT" to "Alltid natt"), "AUTO")
    if (lightsKnown) SettingRow("Natt når lysene er på",
        (when (FytMain.lights) { true -> "Lysene er på nå. "; false -> "Lysene er av nå. "; null -> "" }) +
            "Står lysene alltid på om dagen, slå dette av – da avgjør sola.",
        onClick = { Prefs.set("night_lights", !FytMain.byLights); FytMain.onLightsSetting() }) { Toggle(FytMain.byLights) }
    val canDim = canWrite || fytBright
    SettingRow("Demp hele stereoen om natten", if (canDim) "Senker lysstyrken når det er natt" else "Krever at T'OS får styre lysstyrken (over)",
        onClick = { if (canDim) { Prefs.set("dim_system", !Prefs.bool("dim_system")); NightBrightness.apply(context) } }) { Toggle(Prefs.bool("dim_system") && canDim) }
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
    val hasCar = remember(Prefs.long("car_image_v")) { File(context.filesDir, "car.png").exists() }
    SettingRow("Bilbilde", if (hasCar) "Eget bilde er valgt" else "PNG med svart eller gjennomsiktig bakgrunn", onClick = { pickCar.launch(arrayOf("image/*")) }) {
        if (hasCar) Text("Fjern", color = TextPrimary, fontSize = 15.sp,
            modifier = Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                .clickable { File(context.filesDir, "car.png").delete(); Prefs.set("car_image_v", System.currentTimeMillis()) }
                .padding(horizontal = 14.dp, vertical = 14.dp))
    }
}

@Composable
private fun AppsSettings() {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val hidden = HiddenApps.userHidden()
    var sheet by remember { mutableStateOf<AppEntry?>(null) }
    val apps by produceState(emptyList<AppEntry>(), Prefs.str("hidden_apps"), Prefs.bool("show_system_apps"), tick) {
        value = withContext(Dispatchers.IO) { AppRepository.installedApps(context, includeHidden = true) }
    }
    sheet?.let { a ->
        androidx.activity.compose.BackHandler { sheet = null }
        AppSheet(a, tick) { sheet = null }
        return
    }
    val isHome = remember(tick) {
        context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName == context.packageName
    }
    if (isHome) SettingRow("Startskjerm", "T'OS er startskjermen") { Icon(Icons.Default.CheckCircle, "Klar", tint = TextPrimary) }
    else SettingRow("Startskjerm", "T'OS er ikke startskjerm ennå. Trykk for Androids valg – eller bruk PC (Om stereoen → Førstegangsoppsett)", onClick = {
        context.open(Intent(Settings.ACTION_HOME_SETTINGS)) || context.open(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    }) { Chevron() }
    ToggleRow("Hold T'OS som startskjerm", "keep_home", true, "Viser T'OS i stedet hvis den originale startskjermen dukker opp (krever knappestyring)")
    ToggleRow("Siste apper i T'OS-stil", "tos_recents", true, "Knappen for nylige apper viser T'OS sin egen «Siste apper» i stedet for stereoens (krever knappestyring)")
    ToggleRow("Vis systemapper", "show_system_apps", false, "Viser også stereoens innebygde apper i app-lista")
    Section("Hvilke apper T'OS bruker")
    AppChoiceRow("CarPlay-app (f.eks. Car link 2.0)", "carplay_pkg", "Car")
    AppChoiceRow("Radio-app", "radio_pkg", "Rad")
    AppChoiceRow("Telefon-app (Bluetooth)", "phone_pkg", "Tel")
    AppChoiceRow("Bluetooth-musikk", "btmusic_pkg", "Blu")
    AppChoiceRow("Ryggekamera", "camera_pkg", "Rygg")
    AppChoiceRow("Musikkapp", "music_pkg", "Mus")
    Section("Alle apper (${apps.size}) – trykk for detaljer, «Skjul» fjerner fra app-lista")
    if (apps.isEmpty()) Text("Henter appene …", color = TextMuted, fontSize = 14.sp)
    apps.forEach { a ->
        Row(
            Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(16.dp)).background(Card)
                .clickable { sheet = a }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppIcon(a.icon, 38.dp, a.packageName)
            Text(a.label, color = TextPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1)
            val isHidden = a.packageName in hidden || HiddenApps.isHidden(a)
            Text(if (isHidden) "Vis" else "Skjul", color = TextPrimary, fontSize = 14.sp,
                modifier = Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(Line).clickable { HiddenApps.toggle(a) }
                    .padding(horizontal = 16.dp, vertical = 14.dp))
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** T'OS sin egen app-side (i stedet for Androids «Appinfo»). */
@Composable
private fun AppSheet(a: AppEntry, tick: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    val info = remember(a.packageName, tick) { runCatching { pm.getPackageInfo(a.packageName, 0) }.getOrNull() }
    val ai = info?.applicationInfo
    val system = ai != null && (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
    val updatedSystem = ai != null && (ai.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    val sizeMb by produceState<Long?>(null, info) {
        value = withContext(Dispatchers.IO) {
            runCatching { ((listOf(ai!!.sourceDir) + ai.splitSourceDirs.orEmpty()).sumOf { File(it).length() }) / 1_048_576 }.getOrNull()
        }
    }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(confirmDelete) { if (confirmDelete) { delay(4000); confirmDelete = false } }
    val date = SimpleDateFormat("d. MMMM yyyy", Locale("nb", "NO"))

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 14.dp)) {
        AppIcon(a.icon, 64.dp, a.packageName)
        Column(Modifier.weight(1f)) {
            Text(a.label, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(a.packageName, color = TextMuted, fontSize = 13.sp, maxLines = 1)
        }
        Pill("Tilbake", Icons.Default.ChevronLeft) { onClose() }
    }
    if (info == null) {
        SettingRow("Appen finnes ikke lenger", "Den er avinstallert")
        return
    }
    SettingRow("Versjon", (info.versionName ?: "ukjent") + (if (system) (if (updatedSystem) " · systemapp (oppdatert)" else " · systemapp") else ""))
    SettingRow("Installert", date.format(Date(info.firstInstallTime)) + if (info.lastUpdateTime > info.firstInstallTime + 60_000) " · oppdatert ${date.format(Date(info.lastUpdateTime))}" else "")
    sizeMb?.let { SettingRow("Størrelse på appen", "$it MB (uten appens egne data)") }
    val isHidden = HiddenApps.isHidden(a)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)) {
        Pill("Åpne", Icons.AutoMirrored.Filled.OpenInNew, strong = true) { AppRepository.launch(context, a.packageName) }
        Pill(if (isHidden) "Vis i app-lista" else "Skjul fra app-lista", if (isHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff) { HiddenApps.toggle(a) }
        Pill("Lukk i bakgrunnen", Icons.Default.Close) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            runCatching { am.killBackgroundProcesses(a.packageName) }
            say("${a.label} er lukket hvis den kjørte i bakgrunnen")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!system || updatedSystem) {
            val label = if (updatedSystem) "Fjern oppdateringer" else "Avinstaller"
            Pill(if (confirmDelete) "Trykk igjen: $label" else label, Icons.Default.Delete, strong = confirmDelete) {
                if (!confirmDelete) { confirmDelete = true; return@Pill }
                confirmDelete = false
                // Android viser alltid en egen «Er du sikker?»-boks her (kan ikke unngås for vanlige apper)
                if (!context.open(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.packageName}")))) say("Fikk ikke avinstallert ${a.label}")
            }
        }
        Pill("Avansert (Android)", Icons.Default.Settings) {
            context.open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))
        }
    }
    Text(
        (if (!system || updatedSystem) "Avinstaller: Android spør selv om du er sikker. " else "Systemapper fra stereoen kan ikke avinstalleres, bare skjules. ") +
            "«Avansert» åpner Androids egen side for appen (tillatelser, tøm data, tvungen stopp).",
        color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun StorageSettings() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val resume = rememberResumeTick()
    fun gb(b: Long) = String.format(Locale("nb", "NO"), "%.1f GB", b / 1_073_741_824.0)
    val stat = remember(tick, resume) { runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull() }
    val total = stat?.totalBytes ?: 0L; val free = stat?.availableBytes ?: 0L
    if (total > 0) {
        SettingRow("Brukt ${gb(total - free)} av ${gb(total)}", "${gb(free)} ledig" + if (free < 1_073_741_824L) " – lite plass igjen" else "")
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Line)) {
            Box(Modifier.fillMaxWidth(((total - free).toFloat() / total).coerceIn(0f, 1f)).fillMaxHeight().background(TextPrimary))
        }
        Spacer(Modifier.height(14.dp))
    } else SettingRow("Lagring", "Fikk ikke lest hvor mye plass som er ledig")

    // Minnepinne / minnekort (Android 11+ kan fortelle hvor de ligger)
    val volumes by produceState(emptyList<Triple<String, Long, Long>>(), tick, resume) {
        value = withContext(Dispatchers.IO) { externalVolumes(context) }
    }
    if (volumes.isNotEmpty()) {
        Section("Minnepinne og minnekort")
        volumes.forEach { (name, t, f) -> SettingRow(name, "${gb(f)} ledig av ${gb(t)}") }
    }

    Section("T'OS")
    data class Use(val map: Long, val cache: Long, val files: Long, val apk: Long)
    val use by produceState<Use?>(null, tick) {
        value = withContext(Dispatchers.IO) {
            fun size(f: File?) = f?.takeIf { it.exists() }?.walkBottomUp()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
            val apk = size(File(context.cacheDir, "apk"))
            Use(OfflineMaps.file(context).takeIf { it.exists() }?.length() ?: 0L, size(context.cacheDir) - apk, size(context.filesDir), apk)
        }
    }
    fun mb(b: Long) = if (b < 1_048_576) "under 1 MB" else "${b / 1_048_576} MB"
    val u = use
    if (u == null) Text("Regner ut …", color = TextMuted, fontSize = 14.sp)
    else {
        if (u.map > 0) SettingRow("Kart uten nett (${OfflineMaps.LABEL})", mb(u.map) + " · slettes under Kart og kjøring")
        else SettingRow("Kart uten nett", "Ikke lastet ned")
        SettingRow("T'OS sine data", mb(u.files) + " (innstillinger, bilbilde, logger)")
        var ask by remember { mutableStateOf(false) }
        LaunchedEffect(ask) { if (ask) { delay(4000); ask = false } }
        val clear: () -> Unit = {
            if (!ask) ask = true
            else {
                ask = false
                // «apk» (en oppdatering som lastes ned/installeres) får ligge
                Hub.scope.launch {
                    withContext(Dispatchers.IO) { context.cacheDir.listFiles()?.filter { it.name != "apk" }?.forEach { it.deleteRecursively() } }
                    tick++; say("Midlertidige filer er slettet")
                }
            }
        }
        SettingRow("Midlertidige filer", mb(u.cache) + " · kartbilder og ikoner lages på nytt ved behov", onClick = clear) {
            Pill(if (ask) "Trykk igjen" else "Tøm", strong = ask, onClick = clear)
        }
        if (u.apk > 0 && UpdateState.busy.not()) SettingRow("Gammel oppdateringsfil", mb(u.apk), onClick = {
            Hub.scope.launch { withContext(Dispatchers.IO) { File(context.cacheDir, "apk").deleteRecursively() }; tick++; say("Slettet") }
        }) { Text("Slett", color = TextPrimary, fontSize = 15.sp) }
    }
}

/** Minnepinner og minnekort som er satt i: navn, total og ledig plass. */
private fun externalVolumes(c: Context): List<Triple<String, Long, Long>> {
    if (Build.VERSION.SDK_INT < 30) return emptyList()
    val sm = c.getSystemService(Context.STORAGE_SERVICE) as? android.os.storage.StorageManager ?: return emptyList()
    return runCatching {
        sm.storageVolumes.filter { !it.isPrimary && it.state == Environment.MEDIA_MOUNTED }.mapNotNull { v ->
            val dir = v.directory ?: return@mapNotNull null
            val st = runCatching { StatFs(dir.path) }.getOrNull() ?: return@mapNotNull null
            Triple(v.getDescription(c) ?: "Minnepinne", st.totalBytes, st.availableBytes)
        }
    }.getOrDefault(emptyList())
}

@Composable
private fun TimeSettings() {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000) } }
    val canWrite = remember(tick) { Settings.System.canWrite(context) }
    var h24 by remember(tick) { mutableStateOf(android.text.format.DateFormat.is24HourFormat(context)) }
    val zone = java.util.TimeZone.getDefault()
    val nb = Locale("nb", "NO")
    SettingRow(SimpleDateFormat(if (h24) "HH:mm" else "h:mm a", nb).format(now), SimpleDateFormat("EEEE d. MMMM yyyy", nb).format(now))
    SettingRow("Tidssone", zone.getDisplayName(zone.inDaylightTime(now), java.util.TimeZone.LONG, nb) + " (${zone.id})" +
        if (zone.id != "Europe/Oslo") " – ikke norsk tid. Kan rettes med PC (se under)." else "")
    val autoTime = remember(tick) { runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME) == 1 }.getOrNull() }
    val autoZone = remember(tick) { runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME_ZONE) == 1 }.getOrNull() }
    SettingRow("Automatisk tid", when (autoTime) { true -> "På – stereoen henter tiden fra nett/GPS"; false -> "Av – klokka kan gå feil"; null -> "Ukjent" })
    SettingRow("Automatisk tidssone", when (autoZone) { true -> "På"; false -> "Av"; null -> "Ukjent" })
    if (canWrite) SettingRow("24-timers klokke", if (h24) "14:30" else "2:30 PM", onClick = {
        val nv = !h24
        if (runCatching { Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, if (nv) "24" else "12") }.getOrDefault(false)) h24 = nv
        else say("Stereoen tillot ikke å endre klokkeformatet")
    }) { Toggle(h24) }
    else SettingRow("24-timers klokke", (if (h24) "På" else "Av") + " · for å endre: gi T'OS lov til å endre systeminnstillinger (Skjerm)")
    if (autoTime == false || autoZone == false || zone.id != "Europe/Oslo")
        SettingRow("Rette tiden uten Android-menyene", "Vanlige apper får ikke stille klokka. Med PC: «adb shell settings put global auto_time 1» og «adb shell settings put global auto_time_zone 1» (se docs/ADB-OPPSETT.md).")
    Section("Språk og tastatur")
    SettingRow("Språk i T'OS", "Norsk (bokmål). T'OS er alltid på norsk.")
    SettingRow("Språk i andre apper", Locale.getDefault().getDisplayName(nb).replaceFirstChar { it.uppercase() })
    SettingRow("Tastatur", "T'OS har sitt eget tastatur på skjermen, med æ, ø og å.")
}

@Composable
private fun AboutSettings() {
    val context = LocalContext.current
    val v = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
    SettingRow("T'OS $v", Hub.update?.let { "Ny versjon ${it.versionName} er klar" } ?: Updater.lastError?.let { "Fikk ikke sjekket: $it" } ?: "Du har siste versjon") { UpdateButton() }
    SettingRow("Oppdateringer", "Installeres inne i T'OS. Første gang må du trykke «Installer» én gang; etterpå går det av seg selv (Android 12+).")
    // Hovedbryter for alt T'OS gjør med FYT-stereoen (radio, telefon, lyd, rattknapper, lysstyrke …). Vises også når den er av,
    // ellers kunne man ikke slå den på igjen etter at T'OS slo den av etter en feil.
    if (remember { Fyt.present(context) }) ToggleRow("Stereo-funksjoner (FYT)", "fyt_all", true,
        "Radio, telefon, lyd, rattknapper og lysstyrke styres av T'OS. Slå av hvis noe oppfører seg rart – da gjør stereoen alt selv.")
    SettingRow("Stereo", "${Build.MANUFACTURER} ${Build.MODEL}")
    SettingRow("Android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
    SettingRow("Prosessor", "${Build.HARDWARE} · ${Runtime.getRuntime().availableProcessors()} kjerner · ${Hub.cpuTemp?.let { "$it °C" } ?: "temperatur ukjent"}")
    SettingRow("Minne", Hub.memText)
    SettingRow("Førstegangsoppsett", "Tillatelser – med stereoen eller med PC (uten Android-menyene)", onClick = { Nav.screen = Screen.SETUP }) { Chevron() }
    SettingRow("Takk til", "Radiostyring og app-søk bygger på OpenLauncher (MIT). Kart © OpenStreetMap, Protomaps. Se docs/TREDJEPART.md.")
    Section("Avansert")
    var ask by remember { mutableStateOf(false) }
    LaunchedEffect(ask) { if (ask) { delay(5000); ask = false } }
    SettingRow("Android-innstillinger (avansert)",
        if (ask) "Dette åpner stereoens originale innstillinger. Trykk igjen for å åpne." else "Stereoens originale innstillinger – trengs nesten aldri",
        onClick = {
            if (!ask) { ask = true; return@SettingRow }
            ask = false
            context.open(Intent(Settings.ACTION_SETTINGS))
        }) { if (ask) Pill("Åpne", strong = true) { ask = false; context.open(Intent(Settings.ACTION_SETTINGS)) } else Chevron() }
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
    // Alle kartapper på stereoen (Google Maps, Waze, iGO, Yandex …): de som åpner en «geo:»-adresse
    val navChoices = remember { NaviKey.choices(context) }
    ChoiceRow("Kartapp", "nav_app", navChoices, NaviKey.MAPS,
        "Åpnes av NAVI-knappen og kartet i T'OS. Åpner stereoen en annen kartapp (f.eks. iGO), tilbyr T'OS denne.")
    ToggleRow("NAVI-knappen styres av T'OS", "navi_detect", true,
        "Når stereoen åpner kartet selv: tilbyr den andre kartappen (Waze/Google Maps). Stereoens egen navigasjonsapp byttes ut med kartappen din (krever knappestyring)")
    ToggleRow("Tilby den andre kartappen", "navi_popup", true, "Popup øverst til høyre i 5 sekunder: «Åpne heller Waze»")
    if (Prefs.bool("navi_popup", true) && !NaviPopup.possible(LocalContext.current)) {
        val ctx = LocalContext.current
        SettingRow("Popupen trenger tillatelse", "Slå på knappestyring, eller gi T'OS lov til å vises over andre apper", onClick = {
            ctx.open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        }) { Chevron() }
    }
    ChoiceRow("Fartsjustering", "speed_adj", listOf("0" to "Ingen", "-3" to "−3 %", "-5" to "−5 %", "3" to "+3 %", "5" to "+5 %"), "0",
        "Hvis farten i T'OS ikke stemmer med en fartsmåler du stoler på (idé fra OpenLauncher)")
    ToggleRow("Kartet følger kjøreretningen", "map_rotate", true, "Av: nord er alltid opp. Nesten stillestående bil: nord opp uansett.")
    ToggleRow("Kartet zoomer ut i høy fart", "map_zoom_speed", true, "Du ser lenger fram på motorveien, og flere detaljer i byen")
    ToggleRow("Vis fartsgrense", "speed_limit", true, "Fra OpenStreetMap. Kan mangle eller være feil – skiltene gjelder.")
    ToggleRow("Lyd når du er over fartsgrensen", "speed_beep", false, "Ett kort pip når du kjører mer enn 3 km/t over grensen")
}

@Composable
private fun MusicSettings() {
    val context = LocalContext.current
    SettingRow("Sangtittel og cover", if (MediaListener.enabled(context)) "Slått på" else "Trykk og slå på «T'OS» under varseltilgang", onClick = { openNotificationAccess(context) }) {
        Toggle(MediaListener.enabled(context))
    }
    AppChoiceRow("Musikkapp", "music_pkg", "Mus")
    ToggleRow("Fortsett musikken når bilen starter", "resume_music", true, "Ikke hvis noe annet allerede spiller, f.eks. CarPlay eller radio")
    ToggleRow("Åpne siste app når bilen starter", "resume_last_app", false, "Appen du brukte sist før bilen ble slått av")
    Section("Radio")
    if (Fyt.connected) ToggleRow("FM-radio direkte via stereoen (FYT)", "fyt_radio", true,
        "T'OS styrer stereoens radio selv – stereoens radio-app trengs ikke. Av = som før (radio-appen i bakgrunnen).")
    ToggleRow("FM-radio i bakgrunnen (radiomotor)", "radio_engine", true,
        "Starter stereoens radio et øyeblikk bak T'OS hvis radioen ikke kom i gang av seg selv")
    ToggleRow("Skjul stereoens radio-app", "hide_stock_radio", true, "Viser T'OS sin radio-side når stereoens radio-app dukker opp")
    if (Fyt.connected) {
        Section("Rattknapper")
        ToggleRow("Rattknappene styrer musikkappen (FYT)", "fyt_steer", true,
            "Neste/forrige/pause på rattet går til appen som spiller (f.eks. Spotify), ikke til stereoens egen musikkspiller. " +
                "Av = stereoen bestemmer, som før.")
    }
    Section("Bluetooth-lyd")
    if (Fyt.connected) ToggleRow("Bluetooth-lyd direkte via stereoen (FYT)", "fyt_bt", true,
        "T'OS slår på BT-musikken fra telefonen selv – stereoens BT-app trengs ikke. Av = som før.")
    ToggleRow("Bluetooth-lyd i bakgrunnen (BT-motor)", "bt_engine", true,
        "Starter stereoens Bluetooth-musikk et øyeblikk bak T'OS, så lyden fra telefonen kommer ut av høyttalerne")
}

@Composable
private fun PhoneSettings() {
    ToggleRow("Åpne CarPlay når iPhonen kobles til", "carplay_auto", false, "Viser CarPlay-appen med en gang telefonen er koblet til")
    SettingRow("Favoritter", "${Favorites.all().size} lagret", onClick = { Nav.screen = Screen.PHONE }) { Chevron() }
}

@Composable
private fun CarSettings() {
    ToggleRow("Vis spenning på bilbatteriet", "battery_show", true, "Fra OBD2-brikke, eller fra stereoen hvis den oppgir det")
    SettingRow("OBD2-brikke", CarBattery.obdStatus + (CarBattery.volt?.let { " · ${CarBattery.text(it)} fra ${CarBattery.source}" } ?: ""))
    PrefField("Neste EU-kontroll", "eu_date", "f.eks. 15.3.2027", KeyboardType.Number)
    PrefField("Neste service", "service_date", "f.eks. 1.6.2027", KeyboardType.Number)
    ToggleRow("Minn meg på dekkskift", "tire_remind", true, "Midten av oktober og slutten av mars")
    SettingRow("Tuning og ytelse", "Tuningboks, 0–100-måling og motordata", onClick = { Nav.screen = Screen.TUNING }) { Chevron() }
    SettingRow("Bil og tur", "Turmåler, parkering og påminnelser", onClick = { Nav.screen = Screen.CAR }) { Chevron() }
}

@Composable
private fun BackupSettings() {
    val context = LocalContext.current
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(Prefs.exportJson(context).toByteArray()) }
            say("Innstillingene er lagret")
        }.onFailure { say("Fikk ikke lagret filen") }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }.getOrNull()
            say(if (text != null && Prefs.importJson(context, text)) "Innstillingene er hentet" else "Fant ingen T'OS-innstillinger i filen")
        }
    }
    var tick by remember { mutableIntStateOf(0) }
    val nb = Locale("nb", "NO")
    fun whenText(f: File) = if (f.exists()) SimpleDateFormat("d. MMM 'kl.' HH:mm", nb).format(Date(f.lastModified())) else null

    // I T'OS-mappa: uten Androids filvelger
    Section("På stereoen (T'OS-mappa)")
    val local = remember(tick) { LocalBackup.file(context).let { it to whenText(it) } }
    val auto = remember(tick) { LocalBackup.autoFile(context).let { it to whenText(it) } }
    SettingRow("Lagre til T'OS-mappa", local.second?.let { "Sist lagret $it" } ?: "Rask kopi på stereoen, uten filvelger", onClick = {
        Hub.scope.launch {
            val ok = withContext(Dispatchers.IO) { LocalBackup.save(context) }
            tick++; say(if (ok) "Innstillingene er lagret i T'OS-mappa" else "Fikk ikke lagret")
        }
    }) { Icon(Icons.Default.Save, null, tint = TextMuted) }
    var askRestore by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(askRestore) { if (askRestore != null) { delay(4000); askRestore = null } }
    @Composable fun restoreRow(title: String, f: File, at: String?) {
        if (at == null) return
        val asking = askRestore == f
        SettingRow(title, if (asking) "Dette erstatter innstillingene du har nå. Trykk igjen." else "Fra $at", onClick = {
            if (!asking) { askRestore = f; return@SettingRow }
            askRestore = null
            say(if (LocalBackup.restore(context, f)) "Innstillingene er hentet" else "Fant ingen T'OS-innstillinger i kopien")
        }) { if (asking) Text("Trykk igjen", color = TextPrimary, fontSize = 14.sp) else Chevron() }
    }
    restoreRow("Hent fra T'OS-mappa", local.first, local.second)
    restoreRow("Hent automatisk kopi", auto.first, auto.second)
    Text("T'OS lager også en automatisk kopi én gang i døgnet. Mappa slettes hvis T'OS avinstalleres – lagre til fil (f.eks. Google Drive) for en kopi som tåler det.",
        color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))

    Section("Til fil eller Google Drive")
    SettingRow("Lagre innstillinger til fil", "Androids filvelger åpnes – velg Google Drive for å ha dem trygt", onClick = { exportFile.launch("tos-innstillinger.json") }) { Chevron() }
    SettingRow("Hent innstillinger fra fil", "Androids filvelger åpnes", onClick = { importFile.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Chevron() }

    Section("Hjelp")
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
    SettingRow("Selvtest av stereoen", "T'OS prøver selv radio, lyd, lys, Bluetooth og rattet – og viser hva som virker", onClick = { Nav.screen = Screen.SELFTEST }) { Chevron() }
    SurveyRows()
    SettingRow("Del feilrapport", "Send til deg selv og lim inn i Claude", onClick = {
        Hub.scope.launch {
            val text = withContext(Dispatchers.IO) { CrashLog.report(context) }
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "T'OS feilrapport").putExtra(Intent.EXTRA_TEXT, text)
            context.open(Intent.createChooser(i, "Del feilrapport"))
        }
    }) { Chevron() }
}

/** «Kartlegg stereoen» og opptaket (se SystemSurvey.kt). */
@Composable
private fun SurveyRows() {
    val context = LocalContext.current
    val working = SystemSurvey.working
    // Siste kartlegging fra før (kan sendes igjen)
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { SystemSurvey.findLast(context.applicationContext) } }
    SettingRow("Kartlegg stereoen",
        if (working) "Lager filen … (kan ta et halvt minutt)"
        else "Alt om stereoens egne apper, knapper, lyd og innstillinger i én fil – send den til Claude",
        onClick = { if (!SystemSurvey.working) SystemSurvey.runAndShare(context.applicationContext, null) }) {
        if (working) Text("Vent …", color = TextMuted, fontSize = 14.sp) else Chevron()
    }
    val svcOn = KeyService.instance != null
    SettingRow("Start opptak (2 min)",
        if (svcOn) "Tar opp hva stereoen selv gjør når du drar ned fra toppen og trykker på knappene. Filen sendes etterpå."
        else "Krever at «T'OS knapper» er slått på under Tilgjengelighet",
        onClick = {
            if (SystemSurvey.working || SurveyRecorder.active) return@SettingRow
            if (!SurveyRecorder.start()) say("Slå på «T'OS knapper» under Tilgjengelighet først")
        }) { Icon(Icons.Default.FiberManualRecord, null, tint = TextMuted) }
    SystemSurvey.last?.let { f ->
        SettingRow("Send kartleggingen igjen", "${f.name} · ${(f.length() + 1023) / 1024} KB", onClick = { SystemSurvey.share(context, f) }) {
            Icon(Icons.AutoMirrored.Filled.Send, null, tint = TextMuted)
        }
    }
}
