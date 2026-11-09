package no.bil.launcher

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Notification
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * «Kartlegg stereoen»: alt T'OS kan se om stereoens eget system (apper og deres skjermer/tjenester/meldinger,
 * systemegenskaper, skjerm, lyd, knapper, innstillinger, varsler) – pluss et opptak av hva stereoen selv gjør når
 * brukeren drar ned fra toppen og trykker på knappene. Alt havner i én tekstfil som sendes til Claude, så resten av
 * stereoens originale brukerflate kan byttes ut med T'OS.
 */

/* ======================= Tekst (ren logikk, testes i ShadeSurveyTest) ======================= */

internal object SurveyText {
    /** Én linje, høyst [max] tegn (resten blir «…»). */
    fun clip(s: CharSequence?, max: Int = 80): String {
        if (s == null) return ""
        val one = s.toString().replace(Regex("\\s+"), " ").trim()
        return if (one.length <= max) one else one.take((max - 1).coerceAtLeast(0)).trimEnd() + "…"
    }

    fun header(title: String) = "\n===== $title =====\n"

    fun fileName(at: Long): String = "kartlegging-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(at)) + ".txt"

    /** Over 100 KB sendes som fil (en så lang tekst får ikke plass i en melding). */
    fun shareAsFile(length: Long): Boolean = length > 100_000

    /** Komponentnavn i manifestet kan være korte («.Main» eller «Main»): gjør dem fullstendige. */
    fun fullName(pkg: String, name: String): String = when {
        name.startsWith(".") -> pkg + name
        '.' !in name -> "$pkg.$name"
        else -> name
    }

    /** Kort navn til lista: uten pakkenavnet foran når det er det samme. */
    fun shortName(pkg: String, name: String): String = if (name.startsWith("$pkg.")) name.removePrefix(pkg) else name

    private val carWords = listOf("car", "link", "auto", "carplay", "radio", "navi", "fyt", "syu", "mcu", "canbus", "bluetooth", "btphone",
        "btmusic", "launcher", "dvr", "avm", "tpms", "obd", "szchoiceway", "lsec", "ts.", "hct", "microntek", "mtc", "zlink")

    /**
     * Skal pakken vises i detalj (alle skjermer, tjenester, mottakere og meldingene de tar imot)? Systemapper (unntatt
     * Google sine, som er like på alle stereoer, og selve Android-rammeverket) og apper med bil-preg i navnet.
     */
    fun detailed(pkg: String, system: Boolean, label: String = ""): Boolean {
        if (pkg == "android" || pkg.startsWith("com.google.") || pkg == "com.android.vending") return false
        if (system) return true
        val p = "$pkg $label".lowercase()
        return carWords.any { it in p }
    }

    fun hex(v: Int): String = "0x" + Integer.toHexString(v)

    /** Tid siden opptaket startet: «+  12,3 s». */
    fun stamp(ms: Long): String = String.format(Locale("nb", "NO"), "+%6.1f s", ms.coerceAtLeast(0) / 1000.0)

    private val sourceNames = listOf(
        0x00000101 to "knapper/tastatur", 0x00000201 to "piltaster", 0x00000401 to "spillkontroll", 0x00001002 to "berøringsskjerm",
        0x00002002 to "mus", 0x00004002 to "penn", 0x00010004 to "styrekule", 0x00100008 to "styreflate",
        0x00200000 to "berøringsnavigasjon", 0x00400000 to "dreiehjul", 0x01000010 to "styrespak", 0x02000001 to "HDMI",
        0x04000000 to "sensor",
    )

    /** Hva en inndataenhet er (InputDevice.SOURCE_*), på norsk. */
    fun sources(src: Int): String {
        val names = sourceNames.filter { (flag, _) -> (src and flag) == flag }.map { it.second }
        return (if (names.isEmpty()) "ukjent" else names.joinToString(", ")) + " (${hex(src)})"
    }

    private val windowTypes = mapOf(1 to "app", 2 to "tastatur", 3 to "system", 4 to "tilgjengelighet (T'OS o.l.)", 5 to "delt skjerm-skille",
        6 to "forstørrelse")
    fun windowType(t: Int): String = windowTypes[t] ?: "type $t"

    private val windowChangeNames = listOf(1 to "lagt til", 2 to "fjernet", 4 to "tittel", 8 to "størrelse", 16 to "lag", 32 to "aktiv",
        64 to "fokus", 128 to "tilgj.fokus", 256 to "forelder", 512 to "barn", 1024 to "bilde-i-bilde")
    fun windowChanges(flags: Int): String = windowChangeNames.filter { (f, _) -> (flags and f) != 0 }.joinToString("+") { it.second }.ifEmpty { "?" }

    /** Hendelsene opptaket tar med. null = ikke med. */
    fun eventName(type: Int): String? = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "VINDU"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "VINDUER"
        AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "VARSEL"
        else -> null
    }

    /** Ett vindu på én linje (til opptaket). */
    fun window(w: WinSnap): String = buildString {
        append(windowType(w.type)); append(" lag=").append(w.layer)
        w.title?.takeIf { it.isNotBlank() }?.let { append(" «").append(clip(it, 60)).append('»') }
        w.pkg?.let { append(" pakke=").append(it) }
        append(' ').append(w.left).append(',').append(w.top).append(' ').append(w.width).append('x').append(w.height)
        if (w.active) append(" aktiv")
        if (w.focused) append(" fokus")
    }

    val streams = listOf(0 to "samtale", 1 to "system", 2 to "ringing", 3 to "musikk", 4 to "alarm", 5 to "varsel", 6 to "Bluetooth-samtale",
        7 to "system (tvunget)", 8 to "tastetoner", 9 to "tale (TTS)", 10 to "tilgjengelighet", 11 to "assistent", 12 to "ukjent 12", 13 to "ukjent 13")

    private val audioTypes = mapOf(1 to "ørehøyttaler", 2 to "innebygd høyttaler", 3 to "kablet hodesett", 4 to "kablet hodetelefon",
        5 to "linje analog", 6 to "linje digital", 7 to "Bluetooth samtale (SCO)", 8 to "Bluetooth musikk (A2DP)", 9 to "HDMI", 10 to "HDMI ARC",
        11 to "USB-enhet", 12 to "USB-tilbehør", 13 to "dokk", 14 to "FM", 15 to "innebygd mikrofon", 16 to "FM-mottaker", 17 to "TV-mottaker",
        18 to "telefon", 19 to "AUX", 20 to "IP", 21 to "BUS (bilens forsterker)", 22 to "USB-hodesett", 23 to "høreapparat",
        24 to "innebygd høyttaler (sikker)", 25 to "intern omruting", 26 to "BLE-hodesett", 27 to "BLE-høyttaler", 29 to "HDMI eARC", 30 to "BLE-kringkasting")
    fun audioType(t: Int): String = audioTypes[t] ?: "type $t"
}

/* ======================= Selve kartleggingen ======================= */

object SystemSurvey {
    /** Kartleggingen lages nå (tar litt tid – mange apper å gå gjennom). */
    var working by mutableStateOf(false)
        private set
    /** Siste fil som ble laget (kan sendes igjen). */
    var last by mutableStateOf<File?>(null)
        private set

    private const val MAX_CHARS = 6_000_000
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    private fun dir(c: Context): File = c.getExternalFilesDir(null) ?: File(c.filesDir, "kartlegging").apply { mkdirs() }

    /** Finn siste kartlegging fra før (etter omstart av T'OS). */
    fun findLast(c: Context) {
        if (last != null) return
        val f = runCatching { dir(c).listFiles { x -> x.name.startsWith("kartlegging-") && x.name.endsWith(".txt") }?.maxByOrNull { it.lastModified() } }.getOrNull()
        if (f != null) Handler(Looper.getMainLooper()).post { if (last == null) last = f }
    }

    /** Lag kartleggingen i bakgrunnen, lagre den og åpne «Send». [recording] = linjene fra opptaket (eller null). */
    fun runAndShare(c: Context, recording: List<String>?) {
        if (working) return
        working = true
        Hub.scope.launch {
            val f = withContext(Dispatchers.IO) { runCatching { save(c, build(c, recording)) }.getOrNull() }
            working = false
            if (f == null) { Notify.show("Fikk ikke laget kartleggingen"); return@launch }
            last = f
            Notify.show("Kartleggingen er lagret (${(f.length() + 1023) / 1024} KB)")
            share(c, f)
        }
    }

    private fun save(c: Context, text: String): File {
        val d = dir(c)
        // Behold bare de tre nyeste
        d.listFiles { x -> x.name.startsWith("kartlegging-") }?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
        val f = File(d, SurveyText.fileName(System.currentTimeMillis()))
        f.writeText(text)
        return f
    }

    /** Send filen: som tekst hvis den er liten, ellers som fil (over 100 KB). */
    fun share(c: Context, f: File) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "T'OS kartlegging (${f.name})")
        if (SurveyText.shareAsFile(f.length())) {
            val uri: Uri = runCatching { FileProvider.getUriForFile(c, "${c.packageName}.files", f) }.getOrElse {
                Notify.show("Fikk ikke delt filen"); return
            }
            i.putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            i.clipData = ClipData.newRawUri(f.name, uri)
        } else i.putExtra(Intent.EXTRA_TEXT, runCatching { f.readText() }.getOrDefault(""))
        val chooser = Intent.createChooser(i, "Send kartleggingen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { c.startActivity(chooser) }.isFailure) Notify.show("Fant ingen app å sende med. Filen ligger i ${f.parent}", long = true)
    }

    /** Én del av rapporten. Feiler noe, står feilen der i stedet (resten lages likevel). */
    private inline fun StringBuilder.part(title: String, body: StringBuilder.() -> Unit) {
        append(SurveyText.header(title))
        val before = length
        runCatching { body() }.onFailure { appendLine("(fikk ikke lest: ${it.javaClass.simpleName} ${SurveyText.clip(it.message, 120)})") }
        if (length == before) appendLine("(ingenting)")
    }

    fun build(c: Context, recording: List<String>?): String = buildString {
        val pm = c.packageManager
        appendLine("T'OS kartlegging av stereoen")
        appendLine("Laget: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("T'OS: ${runCatching { pkgInfo(pm, c.packageName, 0)?.versionName }.getOrNull()} (${Updater.current(c)})")
        appendLine("Opptak: ${if (recording == null) "nei (bare kartlegging)" else "${recording.size} linjer (nederst)"}")

        part("Enhet") {
            appendLine("Produsent: ${Build.MANUFACTURER}  Merke: ${Build.BRAND}  Modell: ${Build.MODEL}")
            appendLine("Enhet: ${Build.DEVICE}  Produkt: ${Build.PRODUCT}  Kort: ${Build.BOARD}  Maskinvare: ${Build.HARDWARE}")
            appendLine("Bygg: ${Build.DISPLAY}")
            appendLine("Fingeravtrykk: ${Build.FINGERPRINT}")
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), sikkerhetsoppdatering ${Build.VERSION.SECURITY_PATCH}")
            appendLine("Prosessor: ${Build.SUPPORTED_ABIS.joinToString()}  kjerner=${Runtime.getRuntime().availableProcessors()}")
            if (Build.VERSION.SDK_INT >= 31) appendLine("Brikke: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            appendLine("Sikker modus i T'OS: ${SafeMode.on}")
        }

        part("Systemegenskaper (utvalgte)") { selectedProps().forEach { (k, v) -> appendLine("$k = $v") } }
        part("Alle systemegenskaper T'OS får lese (getprop)") { append(getprop().take(200_000)) }
        part("Skjermer") { displays(c) }
        part("Startskjerm og standardapper") { defaults(c) }
        part("Lyd") { audio(c) }
        part("Knapper og andre inndataenheter") { inputs(c) }
        part("Mediaøkter (hvem som spiller)") {
            (c.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager)
                .getActiveSessions(ComponentName(c, MediaListener::class.java)).forEach { m ->
                    val st = runCatching { m.playbackState?.state }.getOrNull()
                    val type = runCatching { m.playbackInfo?.playbackType }.getOrNull()
                    val title = runCatching { m.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) }.getOrNull()
                    appendLine("${m.packageName}  tilstand=$st  type=$type  «${SurveyText.clip(title)}»")
                }
        }
        part("Varsler nå") {
            val s = TosNotifications.service
            if (s == null) appendLine("(T'OS har ikke varseltilgang)")
            else runCatching { s.activeNotifications?.toList() }.getOrNull().orEmpty().forEach { sbn ->
                val n = sbn.notification
                val title = n?.extras?.getCharSequence(Notification.EXTRA_TITLE)
                appendLine("${sbn.packageName}  kanal=${n?.channelId}  kategori=${n?.category}  pågående=${sbn.isOngoing}  «${SurveyText.clip(title)}»")
            }
        }
        part("Bluetooth") { append(BtLink.report(c)) }
        part("FYT-canbus (bil-data: dører, klima – com.syu.canbus)") { append(FytCan.report()) }
        part("FYT-tjenesten (com.syu.ms: radio, BT-telefon, lydkilde, volum)") {
            append(Fyt.report(c))
            append(FytPhone.report())
            val d = FytKeys.defaults(c)
            appendLine("Knapper T'OS kjenner igjen uten læring (FYT): " + (if (d.isEmpty()) "ingen" else d.entries.joinToString { "${it.key.label} → ${it.value}" }))
            appendLine("FYT sin navigasjonsapp (sys.navi.packagename): ${FytKeys.naviPackage() ?: "ukjent"}")
            appendLine("Kartapper («geo:»): ${NaviKey.geoApps(c).sorted().joinToString().ifEmpty { "ingen" }}")
        }
        part("FYT-radioen (radio-modul 1, T'OS sin radio)") { append(FytRadio.report()) }
        part("FYT-lyd (lydmodulen 4: volum, equalizer, balanse/fader, loudness)") { append(FytSound.report()) }
        part("Selvtest (siste resultat)") { append(SelfTest.lastText(c)) }
        part("FYT-statuslinjen (show_navigationbar)") {
            appendLine("show_navigationbar = ${FytBars.current(c)}  (1 = stereoens linje vises, 0 = skjult)")
            appendLine("navigationbar_config = ${runCatching { Settings.System.getString(c.contentResolver, "navigationbar_config") }.getOrNull()}")
            appendLine("T'OS kan skrive den: ${FytBars.writeTest(c)}")
            appendLine("FYT HideButton (com.fyt.hidebutton) finnes: ${FytBars.hideButtonInstalled(c)}")
        }
        part("Vinduer på skjermen nå") {
            val svc = KeyService.instance
            if (svc == null) appendLine("(knappestyringen kjører ikke)")
            else runCatching { svc.windowSnaps() }.getOrDefault(emptyList()).forEach { appendLine(SurveyText.window(it)) }
        }
        part("Tilgjengelighet og tillatelser") {
            appendLine("Knappestyring (T'OS knapper): ${Buttons.serviceEnabled(c)}, kjører: ${KeyService.instance != null}")
            appendLine("Tilgjengelighetstjenester på: ${Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)}")
            appendLine("Varseltilgang: ${Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners")}")
            appendLine("Vis over andre apper: ${Settings.canDrawOverlays(c)}  Endre systeminnstillinger: ${Settings.System.canWrite(c)}")
            appendLine("Tilgang til appbruk: ${RecentApps.hasUsageAccess(c)}")
            appendLine("Nedtrekksmeny sist sett: ${Prefs.str("shade_seen").ifBlank { "aldri" }}  fanevisning sist sett: ${Prefs.str("recents_seen").ifBlank { "aldri" }}")
        }
        for ((name, uri) in listOf("System" to Settings.System.CONTENT_URI, "Global" to Settings.Global.CONTENT_URI, "Secure" to Settings.Secure.CONTENT_URI)) {
            part("Innstillinger: $name") { settingsTable(c, uri) }
        }
        part("Maskinvarefunksjoner") {
            pm.systemAvailableFeatures.mapNotNull { it.name }.sorted().forEach { appendLine(it) }
            appendLine("Delte biblioteker: ${pm.systemSharedLibraryNames?.sorted()?.joinToString()}")
        }
        part("Apper brukt siste døgn") { usage(c) }

        val apps = runCatching { installed(pm) }.getOrDefault(emptyList()).sortedBy { it.packageName }
        part("Installerte pakker (${apps.size})") {
            appendLine("[system] = fulgte med stereoen, [oppdatert] = systemapp som er oppdatert, start = skjermen app-lista åpner")
            apps.forEach { ai -> appendLine(overviewLine(c, ai)) }
        }
        part("Pakker i detalj") {
            appendLine("A = skjerm (activity), S = tjeneste (service), R = mottaker (receiver), P = innholdsleverandør (provider).")
            appendLine("[lukket] = kan ikke startes av andre apper. ← = meldinger (intent-filter) komponenten tar imot.")
            for (ai in apps) {
                val sys = AppRepository.isSystemApp(ai)
                val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault("")
                if (!SurveyText.detailed(ai.packageName, sys, label)) continue
                if (length > MAX_CHARS) { appendLine("… (stopper her: fila ble for stor)"); break }
                details(c, ai, label)
            }
        }
        if (recording != null) part("Opptak (${recording.size} linjer)") { recording.forEach { appendLine(it) } }
    }

    /* ---------- Delene ---------- */

    private val propKeys = listOf(
        "ro.build.display.id", "ro.build.version.incremental", "ro.build.date", "ro.build.type", "ro.build.tags", "ro.build.flavor",
        "ro.build.product", "ro.build.description", "ro.build.fytmanufacturer", "ro.product.manufacturer", "ro.product.brand",
        "ro.product.model", "ro.product.name", "ro.product.device", "ro.product.board", "ro.product.cpu.abi", "ro.board.platform",
        "ro.hardware", "ro.hardware.chipname", "ro.soc.manufacturer", "ro.soc.model", "ro.boot.hardware", "ro.chipname", "ro.sf.lcd_density",
        "ro.sf.hwrotation", "ro.config.low_ram", "ro.sys.sdcardfs", "ro.vendor.build.fingerprint", "ro.vendor.product.model",
        "ro.fyt.uiid", "ro.fyt.platform", "ro.fyt.customer", "ro.fyt.version", "ro.fyt.mcu", "sys.fyt.platform", "sys.fyt.bluetooth_type",
        "persist.fyt.uiid", "persist.fyt.customer", "persist.fyt.temp.mcu", "ro.syu.uiid", "ro.syu.customer", "ro.syu.version",
        "persist.syu.uiid", "persist.syu.customer", "ro.lsec.app.version", "ro.lsec.customer", "persist.lsec.ui",
        "ro.ts.ui", "ro.ts.customer", "persist.ts.ui", "ro.hct.version", "ro.mtc.version", "ro.product.customize",
        "persist.sys.locale", "persist.sys.timezone", "persist.sys.usb.config", "persist.sys.ui", "persist.sys.launcher",
        "persist.sys.carplay", "persist.sys.zlink", "persist.sys.bt", "persist.sys.radio", "persist.sys.canbus", "persist.sys.car",
        "persist.sys.backcar", "persist.sys.dvr", "persist.sys.navi", "persist.sys.mcu", "sys.boot_completed", "ro.boot.serialno",
        "ro.oem.key1", "ro.telephony.default_network", "ro.bluetooth.name", "persist.bluetooth.name",
    )

    @SuppressLint("PrivateApi")
    private fun selectedProps(): List<Pair<String, String>> {
        val get = runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java) }.getOrNull()
            ?: return listOf("(fikk ikke lest)" to "")
        return propKeys.mapNotNull { k -> runCatching { get.invoke(null, k) as? String }.getOrNull()?.takeIf { it.isNotBlank() }?.let { k to it } }
    }

    private fun getprop(): String = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("getprop"))
        p.inputStream.bufferedReader().use { it.readText() }.also { runCatching { p.destroy() } }
    }.getOrElse { "(getprop virket ikke: ${it.javaClass.simpleName})\n" }

    @Suppress("DEPRECATION")
    private fun StringBuilder.displays(c: Context) {
        val dm = c.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.displays.forEach { d ->
            val p = Point(); d.getRealSize(p)
            val m = DisplayMetrics(); d.getRealMetrics(m)
            appendLine("Skjerm ${d.displayId} «${d.name}»: ${p.x}×${p.y}, ${m.densityDpi} dpi (tetthet ${m.density}), ${d.refreshRate} Hz, " +
                "flagg=${SurveyText.hex(d.flags)}, tilstand=${d.state}")
        }
        val r = c.resources.displayMetrics
        appendLine("T'OS ser: ${r.widthPixels}×${r.heightPixels}, tetthet ${r.density}, tekstskala ${c.resources.configuration.fontScale}, " +
            "smallestWidth ${c.resources.configuration.smallestScreenWidthDp} dp")
    }

    private fun StringBuilder.defaults(c: Context) {
        val pm = c.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val home = pm.resolveActivity(homeIntent, 0)?.activityInfo
        appendLine("Startskjerm nå: ${home?.packageName}/${home?.name}")
        pm.queryIntentActivities(homeIntent, 0).forEach { appendLine("  kan være startskjerm: ${it.activityInfo.packageName}/${it.activityInfo.name} (prioritet ${it.priority})") }
        appendLine("Telefon-app: ${runCatching { (c.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage }.getOrNull()}")
        appendLine("Tastatur: ${Settings.Secure.getString(c.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)}")
        for ((what, intent) in listOf(
            "Musikk" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC),
            "Kart" to Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=Oslo")),
            "Nettleser" to Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            "Kamera" to Intent("android.media.action.IMAGE_CAPTURE"),
            "Innstillinger" to Intent(Settings.ACTION_SETTINGS),
            "Bluetooth-innstillinger" to Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            "Ringe" to Intent(Intent.ACTION_DIAL),
        )) {
            appendLine("$what: " + pm.queryIntentActivities(intent, 0).joinToString { "${it.activityInfo.packageName}/${SurveyText.shortName(it.activityInfo.packageName, it.activityInfo.name)}" })
        }
    }

    private fun StringBuilder.audio(c: Context) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        appendLine("Lydmodus: ${am.mode} (0 = vanlig, 2/3 = samtale)  ringemodus: ${am.ringerMode}  fast volum: ${am.isVolumeFixed}  musikk spiller: ${am.isMusicActive}")
        appendLine("Utgang: ${am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)} Hz, ${am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)} bilder per buffer")
        SurveyText.streams.forEach { (s, name) ->
            runCatching { appendLine("Volum $s $name: ${am.getStreamVolume(s)}/${am.getStreamMaxVolume(s)}${if (am.isStreamMute(s)) " (av)" else ""}") }
        }
        (am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) + am.getDevices(AudioManager.GET_DEVICES_INPUTS)).forEach { d ->
            val addr = if (Build.VERSION.SDK_INT >= 28) " adresse=${d.address}" else ""
            appendLine("${if (d.isSink) "Ut" else "Inn"}: ${SurveyText.audioType(d.type)} «${d.productName}» id=${d.id}$addr")
        }
    }

    private fun StringBuilder.inputs(c: Context) {
        val im = c.getSystemService(Context.INPUT_SERVICE) as InputManager
        val all = (1..KeyEvent.getMaxKeyCode()).toList().toIntArray()
        im.inputDeviceIds.forEach { id ->
            val d = InputDevice.getDevice(id) ?: return@forEach
            val ext = if (Build.VERSION.SDK_INT >= 29) " ekstern=${d.isExternal}" else ""
            appendLine("Enhet $id «${d.name}»: ${SurveyText.sources(d.sources)}, tastatur=${d.keyboardType}, virtuell=${d.isVirtual}$ext, " +
                "leverandør=${SurveyText.hex(d.vendorId)} produkt=${SurveyText.hex(d.productId)}")
            appendLine("  id: ${d.descriptor}")
            val has = runCatching { d.hasKeys(*all) }.getOrNull()
            if (has != null) {
                val keys = all.filterIndexed { i, _ -> has.getOrElse(i) { false } }
                appendLine("  knapper (${keys.size}): " + keys.joinToString { "${KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_")}($it)" })
            }
        }
    }

    private fun StringBuilder.settingsTable(c: Context, uri: Uri) {
        c.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { cur ->
            val rows = ArrayList<String>()
            while (cur.moveToNext()) rows += "${cur.getString(0)} = ${SurveyText.clip(cur.getString(1), 200)}"
            rows.sorted().forEach { appendLine(it) }
        } ?: appendLine("(ingen svar)")
    }

    private fun StringBuilder.usage(c: Context) {
        if (!RecentApps.hasUsageAccess(c)) { appendLine("(T'OS har ikke «Tilgang til appbruk»)"); return }
        val um = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("HH:mm", Locale.US)
        um.queryAndAggregateUsageStats(now - 86_400_000L, now).values.sortedByDescending { it.lastTimeUsed }.take(80).forEach { u ->
            appendLine("${u.packageName}  sist ${fmt.format(Date(u.lastTimeUsed))}  framme ${u.totalTimeInForeground / 1000} s")
        }
    }

    @Suppress("DEPRECATION")
    private fun installed(pm: PackageManager): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= 33) pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0)) else pm.getInstalledApplications(0)

    @Suppress("DEPRECATION")
    private fun pkgInfo(pm: PackageManager, pkg: String, flags: Int): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong())) else pm.getPackageInfo(pkg, flags)
    }.getOrNull()

    private fun overviewLine(c: Context, ai: ApplicationInfo): String {
        val pm = c.packageManager
        val pi = pkgInfo(pm, ai.packageName, 0)
        val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault("")
        val sys = if ((ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0) " [system]" else ""
        val upd = if ((ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) " [oppdatert]" else ""
        val off = if (!ai.enabled) " [slått av]" else ""
        @Suppress("DEPRECATION") val code = if (Build.VERSION.SDK_INT >= 28) pi?.longVersionCode else pi?.versionCode?.toLong()
        val start = pm.getLaunchIntentForPackage(ai.packageName)?.component?.className?.let { " start=${SurveyText.shortName(ai.packageName, it)}" } ?: ""
        return "${ai.packageName} – $label v${pi?.versionName} ($code)$sys$upd$off$start"
    }

    /** Alle skjermer, tjenester, mottakere og leverandører i en pakke, med meldingene de tar imot. */
    private fun StringBuilder.details(c: Context, ai: ApplicationInfo, label: String) {
        val pm = c.packageManager
        val pkg = ai.packageName
        appendLine()
        appendLine("## $pkg – $label")
        appendLine("   fil: ${ai.sourceDir}  uid=${ai.uid}  SDK=${ai.targetSdkVersion}${if (ai.sharedLibraryFiles != null) " delte bibl.=${ai.sharedLibraryFiles?.size}" else ""}")
        val all = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS
        // Store apper kan gi for stort svar på én gang – da hentes hver del for seg
        val pi = pkgInfo(pm, pkg, all)
        fun <T> comps(flag: Int, get: (PackageInfo) -> Array<T>?): Array<T>? = if (pi != null) get(pi) else pkgInfo(pm, pkg, flag)?.let(get)
        val filters = manifestFilters(c, ai)
        val printed = HashSet<String>()
        fun line(kind: String, ci: ComponentInfo, extra: String) {
            printed += ci.name
            val shut = if (!ci.exported) " [lukket]" else ""
            val f = filters[ci.name]?.let { "  ← " + it.joinToString(", ") } ?: ""
            appendLine("   $kind ${SurveyText.shortName(pkg, ci.name)}$shut$extra$f")
        }
        comps(PackageManager.GET_ACTIVITIES) { it.activities }?.forEach { a -> line("A", a, a.permission?.let { " tillatelse=$it" } ?: "") }
        comps(PackageManager.GET_SERVICES) { it.services }?.forEach { s -> line("S", s, s.permission?.let { " tillatelse=$it" } ?: "") }
        comps(PackageManager.GET_RECEIVERS) { it.receivers }?.forEach { r -> line("R", r, r.permission?.let { " tillatelse=$it" } ?: "") }
        comps(PackageManager.GET_PROVIDERS) { it.providers }?.forEach { p -> line("P", p, " adresse=${p.authority}") }
        // Snarveier (activity-alias) og annet manifestet har, men som ikke kom med over
        filters.filterKeys { it !in printed && it != FILTER_PKG_KEY }.forEach { (n, f) -> appendLine("   (alias) ${SurveyText.shortName(pkg, n)}  ← ${f.joinToString(", ")}") }
        filters[FILTER_PKG_KEY]?.let { appendLine("   merk: ${it.joinToString()}") }
        comps(PackageManager.GET_PERMISSIONS) { it.permissions }?.takeIf { it.isNotEmpty() }?.let { perms ->
            appendLine("   egne tillatelser: " + perms.joinToString { it.name })
        }
    }

    private const val FILTER_PKG_KEY = "\u0000merk"

    /**
     * Meldingene (intent-filter) hver komponent tar imot, lest fra pakkens manifest. Android gir ikke disse direkte,
     * men manifestet kan leses fra appens fil. Gir tom liste hvis det ikke går.
     */
    private fun manifestFilters(c: Context, ai: ApplicationInfo): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        val pkg = ai.packageName
        val parser: XmlResourceParser = runCatching {
            c.packageManager.getResourcesForApplication(ai).assets.openXmlResourceParser("AndroidManifest.xml")
        }.getOrNull() ?: return out
        runCatching {
            parser.use { p ->
                var comp: String? = null
                var inFilter = false
                val cur = ArrayList<String>()
                while (true) {
                    val ev = p.next()
                    if (ev == XmlPullParser.END_DOCUMENT) break
                    if (ev == XmlPullParser.START_TAG) when (p.name) {
                        "manifest" -> {
                            val mp = p.getAttributeValue(null, "package")
                            if (mp != null && mp != pkg) { out[FILTER_PKG_KEY] = mutableListOf("manifestet var for $mp – meldinger mangler"); return out }
                            if (p.getAttributeValue(null, "split") != null) out[FILTER_PKG_KEY] = mutableListOf("delt app – noen meldinger kan mangle")
                        }
                        "activity", "activity-alias", "service", "receiver", "provider" ->
                            comp = p.getAttributeValue(ANDROID_NS, "name")?.let { SurveyText.fullName(pkg, it) }
                        "intent-filter" -> { inFilter = true; cur.clear() }
                        "action" -> if (inFilter) p.getAttributeValue(ANDROID_NS, "name")?.let { cur += it }
                        "category" -> if (inFilter) p.getAttributeValue(ANDROID_NS, "name")?.let { cur += "kat:" + it.removePrefix("android.intent.category.") }
                        "data" -> if (inFilter) p.getAttributeValue(ANDROID_NS, "scheme")?.let { cur += "data:$it" }
                    } else if (ev == XmlPullParser.END_TAG) when (p.name) {
                        "intent-filter" -> { inFilter = false; comp?.let { n -> out.getOrPut(n) { mutableListOf() } += cur.toList() } }
                        "activity", "activity-alias", "service", "receiver", "provider" -> comp = null
                    }
                }
            }
        }
        return out
    }
}

/* ======================= Opptak (knappestyringen fyller det) ======================= */

/**
 * Opptak i 2 minutter: alle vindusendringer, vinduene på skjermen, varsler, knappetrykk og volumendringer.
 * Mens opptaket går, holder T'OS seg helt unna (knappene og stereoens skjermbilder får virke som originalt),
 * så vi ser hva stereoen selv gjør. Et lite T'OS-kort ([RecordGuide]) sier hva brukeren skal gjøre.
 */
object SurveyRecorder {
    const val DURATION_MS = 120_000L
    private const val MAX_LINES = 8000

    var active by mutableStateOf(false)
        private set
    /** Når opptaket stopper av seg selv (elapsedRealtime). */
    @Volatile var endsAt = 0L
        private set
    private var startAt = 0L
    private val lines = ArrayList<String>()
    private var lastWindows = ""
    private val h = Handler(Looper.getMainLooper())
    /** Etter 2 minutter: stopp (uten knappestyringen kan T'OS ikke hentes fram – da bare slutt å ta opp). */
    private val autoStop = Runnable {
        val c = KeyService.instance?.applicationContext
        if (c != null) stop(c) else { active = false; RecordGuide.hide() }
    }

    /** Når brukeren sist trykket «Stopp nå» uten å stoppe (elapsedRealtime, 0 = ikke). */
    private var askedAt = 0L

    /**
     * «Stopp nå» trykket. Første kartlegging (8. nov) stoppet etter 1,1 s: kortet dukket opp der fingeren var (rett over
     * «Start opptak»-raden), så et trykk nummer to traff «Stopp nå». Nå: kortet ligger nede til venstre, trykk de første
     * 3 sekundene teller ikke, og det må trykkes to ganger. Gir teksten knappen skal vise.
     */
    fun requestStop(c: Context): String? {
        val now = SystemClock.elapsedRealtime()
        return when (RecordStop.tap(now - startAt, if (askedAt == 0L) null else now - askedAt)) {
            RecordStop.Tap.IGNORE -> null
            RecordStop.Tap.ASK -> { askedAt = now; "Trykk igjen for å stoppe" }
            RecordStop.Tap.STOP -> { askedAt = 0L; add("«Stopp nå» trykket"); stop(c); null }
        }
    }

    /** Start opptaket. false = knappestyringen kjører ikke. */
    fun start(): Boolean {
        val svc = KeyService.instance ?: return false
        if (active) return true
        synchronized(lines) { lines.clear() }
        startAt = SystemClock.elapsedRealtime(); endsAt = startAt + DURATION_MS; lastWindows = ""; askedAt = 0L
        active = true
        add("Opptak startet ${Date()}")
        add("FYT-tjenesten: ${Fyt.status}")
        windows(svc)
        RecordGuide.show(svc)
        h.postDelayed(autoStop, DURATION_MS)
        return true
    }

    /** Stopp (knappen «Stopp nå», eller etter 2 minutter): tilbake til T'OS, lag filen og send den. */
    fun stop(c: Context) {
        if (!active) return
        active = false
        h.removeCallbacks(autoStop)
        add("Opptak stoppet etter ${(SystemClock.elapsedRealtime() - startAt) / 1000} s")
        RecordGuide.hide()
        val rec = synchronized(lines) { lines.toList() }
        Prefs.set("settings_cat", SetCat.BACKUP.name)
        Buttons.show(c, Screen.SETTINGS, instant = true)
        SystemSurvey.runAndShare(c.applicationContext, rec)
    }

    private fun add(s: String) {
        synchronized(lines) {
            when {
                lines.size < MAX_LINES -> lines += SurveyText.stamp(SystemClock.elapsedRealtime() - startAt) + "  " + s
                lines.size == MAX_LINES -> lines += "… (flere linjer kuttet)"
            }
        }
    }

    fun note(s: String) { if (active) add(s) }

    fun event(svc: AccessibilityService, e: AccessibilityEvent) {
        val type = e.eventType
        val name = SurveyText.eventName(type) ?: return
        val texts = runCatching { e.text.mapNotNull { it?.toString() } }.getOrDefault(emptyList()) + listOfNotNull(e.contentDescription?.toString())
        val extra = when (type) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> if (Build.VERSION.SDK_INT >= 28) " endring=${SurveyText.windowChanges(e.windowChanges)}" else ""
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED ->
                (runCatching { e.parcelableData }.getOrNull() as? Notification)?.let { " kanal=${it.channelId} kategori=${it.category}" } ?: ""
            else -> ""
        }
        add("$name  ${e.packageName}  ${e.className}  «${SurveyText.clip(texts.joinToString(" | "))}»  vindu=${e.windowId}$extra")
        if (type != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) windows(svc)
    }

    /** Vinduene på skjermen – bare når de har endret seg siden sist. */
    private fun windows(svc: AccessibilityService) {
        val snaps = runCatching { svc.windowSnaps() }.getOrNull() ?: return
        val text = snaps.joinToString("\n") { "      " + SurveyText.window(it) }
        if (text == lastWindows) return
        lastWindows = text
        add("  vinduer nå (${snaps.size}):\n$text")
    }

    fun key(e: KeyEvent) {
        val action = when (e.action) { KeyEvent.ACTION_DOWN -> "NED"; KeyEvent.ACTION_UP -> "OPP"; else -> "FLERE" }
        val dev = runCatching { InputDevice.getDevice(e.deviceId)?.name }.getOrNull()
        add("KNAPP $action  ${KeyEvent.keyCodeToString(e.keyCode)} (${e.keyCode})  skan=${e.scanCode}  gjentak=${e.repeatCount}  " +
            "enhet=${e.deviceId} «$dev»  kilde=${SurveyText.hex(e.source)}  flagg=${SurveyText.hex(e.flags)}")
    }
}

/**
 * Vinduene på skjermen nå (krever «hente vinduer» i knappestyringens oppsett). Pakkenavnet hentes bare for vinduer
 * der [rootOf] (vindustypen) sier ja – det er et spørsmål til appen som eier vinduet.
 */
internal fun AccessibilityService.windowSnaps(rootOf: (Int) -> Boolean = { true }): List<WinSnap> = windows.orEmpty().map { w ->
    val b = android.graphics.Rect(); w.getBoundsInScreen(b)
    val root = if (rootOf(w.type)) runCatching { w.root }.getOrNull() else null
    val pkg = root?.packageName?.toString()
    @Suppress("DEPRECATION") if (root != null && Build.VERSION.SDK_INT < 33) runCatching { root.recycle() }
    WinSnap(w.type, w.title?.toString(), pkg, b.left, b.top, b.right, b.bottom,
        w.layer, w.isActive, w.isFocused)
}

/** Når «Stopp nå» faktisk stopper opptaket. Ren logikk, testes i FytLogicTest. */
internal object RecordStop {
    /** Trykk så tidlig teller ikke (kortet kan ha dukket opp under fingeren). */
    const val ARM_MS = 3000L
    /** Andre trykk må komme innen dette. */
    const val CONFIRM_MS = 4000L
    enum class Tap { IGNORE, ASK, STOP }
    fun tap(sinceStartMs: Long, sinceAskMs: Long?): Tap = when {
        sinceStartMs < ARM_MS -> Tap.IGNORE
        sinceAskMs != null && sinceAskMs in 0..CONFIRM_MS -> Tap.STOP
        else -> Tap.ASK
    }
}

/* ======================= Veiledningen under opptaket ======================= */

/** Lite T'OS-kort nede til venstre over alle apper mens opptaket går: hva du skal gjøre, tid igjen og «Stopp nå». */
internal object RecordGuide {
    private val h = Handler(Looper.getMainLooper())
    private var view: WeakReference<View>? = null
    private var wm: WindowManager? = null
    private var title: WeakReference<TextView>? = null
    private val tick = object : Runnable {
        override fun run() {
            val t = title?.get() ?: return
            val left = ((SurveyRecorder.endsAt - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
            t.text = String.format(Locale.US, "Opptak: %d:%02d igjen", left / 60, left % 60)
            h.postDelayed(this, 1000)
        }
    }

    private const val GUIDE = "1. Dra ned fra toppen av skjermen – én gang, og så én gang til\n" +
        "2. Trykk hver knapp på stereoen: MENU, BACK, NAVI, RADIO, MUSIC og BT\n" +
        "3. Skru volumet opp og ned (knapper og hjul)\n" +
        "4. Trykk «siste apper» (to firkanter øverst til høyre)\n" +
        "5. Trykk knappene på rattet\n" +
        "T'OS holder seg unna mens opptaket går, så stereoens egne bilder vises."

    fun show(svc: AccessibilityService) = h.post {
        if (view?.get() != null) return@post
        val w = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, svc.resources.displayMetrics).toInt()
        val t = TextView(svc).apply { textSize = 18f; setTextColor(TextPrimary.toArgb()); setTypeface(typeface, Typeface.BOLD) }
        val body = TextView(svc).apply { text = GUIDE; textSize = 14f; setTextColor(TextMuted.toArgb()); setLineSpacing(0f, 1.15f) }
        fun button(label: String, strong: Boolean, onClick: () -> Unit) = TextView(svc).apply {
            text = label; textSize = 16f; gravity = Gravity.CENTER; minHeight = dp(48f)
            setPadding(dp(18f), 0, dp(18f), 0)
            setTextColor((if (strong) Bg else TextPrimary).toArgb())
            background = GradientDrawable().apply { setColor((if (strong) TextPrimary else Line).toArgb()); cornerRadius = dp(24f).toFloat() }
            setOnClickListener { onClick() }
        }
        lateinit var toggle: TextView
        toggle = button("Skjul tekst", strong = false) {
            val hidden = body.visibility == View.VISIBLE
            body.visibility = if (hidden) View.GONE else View.VISIBLE
            toggle.text = if (hidden) "Vis tekst" else "Skjul tekst"
        }
        lateinit var stop: TextView
        val stopLabel = "Stopp nå"
        stop = button(stopLabel, strong = true) {
            val ask = SurveyRecorder.requestStop(svc.applicationContext)
            if (ask != null) {
                stop.text = ask
                h.postDelayed({ if (SurveyRecorder.active) stop.text = stopLabel }, RecordStop.CONFIRM_MS)
            }
        }
        val buttons = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10f) })
            addView(stop)
        }
        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setPadding(dp(20f), dp(16f), dp(20f), dp(16f))
            background = GradientDrawable().apply { setColor(Card.copy(alpha = 0.97f).toArgb()); cornerRadius = dp(24f).toFloat(); setStroke(dp(1f), Line.toArgb()) }
            elevation = dp(8f).toFloat()
            addView(t)
            addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
            addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12f) })
        }
        val lp = WindowManager.LayoutParams(
            dp(420f), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.START; x = dp(16f); y = dp(16f) }
        if (runCatching { w.addView(card, lp) }.isFailure) return@post
        view = WeakReference(card); wm = w; title = WeakReference(t)
        h.removeCallbacks(tick); tick.run()
    }

    fun hide() = h.post {
        h.removeCallbacks(tick)
        view?.get()?.let { v -> runCatching { wm?.removeView(v) } }
        view = null; wm = null; title = null
    }
}
