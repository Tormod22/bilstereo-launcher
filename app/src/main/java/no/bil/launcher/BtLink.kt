package no.bil.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/* ======================= Er telefonen koblet til? ======================= */

/**
 * Finner ut om en telefon er koblet til stereoen med Bluetooth – i lag, fordi bilstereoer gjør det ulikt:
 *  1. Androids Bluetooth-profiler: A2DP/Headset og «bil-profilene» A2DP-mottaker, håndfri (HFP-klient) og AVRCP.
 *     (På en bilstereo er stereoen *mottakeren* av musikken, så det er de siste som gjelder.)
 *  2. Parede enheter som Android selv sier er tilkoblet (skjult funksjon).
 *  3. Tilkoblinger T'OS har sett skje (Bluetooth-meldinger fra Android mens T'OS kjører).
 *  4. Stereoens egen Bluetooth-app (mange kinesiske stereoer har en egen BT-brikke som Android ikke ser):
 *     en mediaøkt eller et varsel fra den som sier «Tilkoblet».
 *  5. Ser ingen av disse noe, men stereoen har en egen BT-app, sier T'OS ikke «ikke tilkoblet» – bare at
 *     Bluetooth styres av stereoen.
 * Hendelser (tilkoblet/frakoblet, varsler) oppdaterer [version], så skjermen leser på nytt med én gang.
 */
object BtLink {
    // Profilnumrene (A2DP-mottaker, AVRCP og HFP-klient er skjulte konstanter i Android – derfor tallene)
    const val HEADSET = 1
    const val A2DP = 2
    const val A2DP_SINK = 11
    const val AVRCP_CONTROLLER = 12
    const val HEADSET_CLIENT = 16
    private val profileNames = linkedMapOf(
        A2DP_SINK to "A2DP-mottaker", HEADSET_CLIENT to "Håndfri (HFP-klient)", AVRCP_CONTROLLER to "AVRCP-styring",
        A2DP to "A2DP", HEADSET to "Headset",
    )

    private val proxies = ConcurrentHashMap<Int, BluetoothProfile>()
    private val proxyNote = ConcurrentHashMap<Int, String>()
    private val acl = ConcurrentHashMap<String, String>()          // adresse → navn (tilkoblet nå, sett av T'OS)
    @Volatile private var lastEvent = "ingen"

    /** Varsel fra en Bluetooth-app: nøkkel → (pakke, tittel, tekst). */
    private data class Note(val pkg: String, val title: String?, val text: String?)
    private val notes = ConcurrentHashMap<String, Note>()

    @Volatile private var registered = false
    private val main = Handler(Looper.getMainLooper())

    /** Økes ved hver Bluetooth-hendelse (skjermene leser da status på nytt). */
    var version by mutableIntStateOf(0)
        private set
    /** Siste status (leses av musikk- og telefonsiden uten å spørre Android på hovedtråden). */
    var current by mutableStateOf(BtStatus(false, false, emptyList()))
        private set

    internal fun bump() = main.post { version++ }

    private fun permitted(c: Context) = Build.VERSION.SDK_INT < 31 || c.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun adapter(c: Context): BluetoothAdapter? = runCatching { (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter }.getOrNull()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { runCatching { onEvent(c.applicationContext, i) } }
    }

    /** Startes én gang fra [Hub.start]: lytter på Bluetooth-hendelser og kobler til profilene. */
    fun start(c: Context) {
        val a = c.applicationContext
        if (!registered) {
            registered = true
            val f = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED); addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                profileActions.forEach { addAction(it) }
            }
            runCatching { ContextCompat.registerReceiver(a, receiver, f, ContextCompat.RECEIVER_EXPORTED) }
        }
        connectProfiles(a)
    }

    private val profileActions = listOf(
        "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED",
        "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED",
        "android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED",
        "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED",
        "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED",
    )

    @SuppressLint("MissingPermission")
    private fun onEvent(c: Context, i: Intent) {
        val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val addr = d?.address
        val name = runCatching { d?.name }.getOrNull() ?: addr.orEmpty()
        val action = i.action ?: return
        when {
            action == BluetoothDevice.ACTION_ACL_CONNECTED -> if (addr != null) acl[addr] = name
            action == BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (addr != null) acl.remove(addr)
            action == BluetoothAdapter.ACTION_STATE_CHANGED -> {
                if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_OFF) acl.clear()
                // Bluetooth ble slått på: profilene kan kobles til nå
                if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) {
                    proxyNote.keys.filter { !proxies.containsKey(it) }.forEach { proxyNote.remove(it) }
                    connectProfiles(c)
                }
            }
            action == BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> if (addr != null) {
                when (i.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1)) {
                    BluetoothAdapter.STATE_CONNECTED -> acl[addr] = name
                    BluetoothAdapter.STATE_DISCONNECTED -> acl.remove(addr)
                }
            }
            action in profileActions -> if (addr != null) {
                when (i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                    BluetoothProfile.STATE_CONNECTED -> acl[addr] = name
                    // Én profil kobles fra (f.eks. bare musikk) – telefonen kan fortsatt være tilkoblet; ACL avgjør
                }
            }
        }
        lastEvent = "${action.substringAfterLast('.')} ${name.ifBlank { "?" }} (${SystemClock.elapsedRealtime() / 1000} s)"
        bump()
    }

    /** Be Android om profilene (svaret kommer senere, på hovedtråden). Profiler stereoen ikke har, gir bare «nei». */
    @SuppressLint("WrongConstant")
    private fun connectProfiles(c: Context) {
        if (!permitted(c)) return
        val ad = adapter(c) ?: return
        for (p in profileNames.keys) {
            val note = proxyNote[p]
            if (proxies.containsKey(p) || note == "venter" || note == "finnes ikke" || note?.startsWith("feil") == true) continue
            val ok = runCatching {
                ad.getProfileProxy(c, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { proxies[profile] = proxy; proxyNote[profile] = "ok"; bump() }
                    override fun onServiceDisconnected(profile: Int) { proxies.remove(profile); proxyNote[profile] = "koblet fra" }
                }, p)
            }
            proxyNote[p] = when {
                ok.isFailure -> "feil: ${ok.exceptionOrNull()?.javaClass?.simpleName}"
                ok.getOrDefault(false) -> if (proxies.containsKey(p)) "ok" else "venter"
                else -> "finnes ikke"
            }
        }
    }

    /** Enheter som er tilkoblet via hver profil (navn, adresse, profil). */
    @SuppressLint("MissingPermission")
    private fun profileDevices(): List<BtSeen> = proxies.entries.flatMap { (p, proxy) ->
        runCatching { proxy.connectedDevices.orEmpty() }.getOrDefault(emptyList()).map { d ->
            BtSeen(runCatching { d.name }.getOrNull() ?: d.address, d.address, profileNames[p] ?: "profil $p")
        }
    }

    /* ---------- Stereoens egen Bluetooth-app ---------- */

    private class Vendors(val at: Long, val list: List<String>)
    @Volatile private var vendors: Vendors? = null

    /** Siste liste over stereoens egne Bluetooth-apper (uten å lese pakkene på nytt – trygt på hovedtråden). */
    fun vendorPackagesCached(): List<String> = vendors?.list.orEmpty()

    /**
     * Stereoens egne Bluetooth-apper (systemapper som heter noe med «bt»/«bluetooth», ikke Androids egen
     * Bluetooth-del), og appene brukeren har valgt for Bluetooth-musikk/telefon. Leses høyst hvert 2. minutt.
     */
    fun vendorPackages(c: Context): List<String> {
        val now = SystemClock.elapsedRealtime()
        vendors?.takeIf { now - it.at < 120_000 }?.let { return it.list }
        val pm = c.packageManager
        val chosen = chosenBtPackages().filter { AppRepository.isInstalled(c, it) || runCatching { pm.getApplicationInfo(it, 0) }.isSuccess }
        val found = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
            .filter { AppRepository.isSystemApp(it) && isVendorBtPkg(it.packageName, c.packageName) }
            .map { it.packageName }
            // Kjente BT-apper først (de vanligste på slike stereoer)
            .sortedBy { if (it in knownBtApps) 0 else 1 }
        val list = (chosen + found).distinct()
        vendors = Vendors(now, list)
        return list
    }

    /** Valgt Bluetooth-musikk/telefon-app (ikke vanlige telefonapper som Androids egen). */
    private fun chosenBtPackages(): List<String> =
        listOf(Prefs.str("btmusic_pkg"), Prefs.str("phone_pkg")).filter { it.isNotBlank() && it !in plainDialers }

    /** Varsel fra en app (fra varseltjenesten). Bare Bluetooth-apper huskes. */
    fun onNotification(pkg: String, key: String, title: String?, text: String?) {
        if (!isBtAppPkg(pkg) && pkg !in chosenBtPackages()) return
        val n = Note(pkg, title, text)
        if (notes[key] == n) return
        notes[key] = n
        bump()
    }

    fun onNotificationRemoved(key: String) { if (notes.remove(key) != null) bump() }

    fun clearNotes() { if (notes.isNotEmpty()) { notes.clear(); bump() } }

    /** Bevis fra stereoens BT-app eller Androids Bluetooth-del: varsel som sier «tilkoblet», eller en mediaøkt i bruk. */
    private fun vendorHint(vendorList: List<String>): BtVendorHint? {
        val okPkg = { p: String -> p in vendorList || p in androidBtPkgs }
        for (n in notes.values) {
            if (!okPkg(n.pkg)) continue
            if (btNoteConnected(n.title, n.text) == true) return BtVendorHint(n.pkg, btNoteName(n.title, n.text), "varsel")
        }
        for (s in NowPlaying.sessions) {
            val p = s.packageName ?: continue
            if (!okPkg(p)) continue
            val st = runCatching { s.playbackState?.state }.getOrNull()
            val title = runCatching { s.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) }.getOrNull()
            if (btSessionSaysConnected(st, !title.isNullOrBlank())) return BtVendorHint(p, null, "mediaøkt")
        }
        return null
    }

    /* ---------- Samlet status ---------- */

    /** Les alle lagene (kall i bakgrunnen – spør Android). Oppdaterer også [current]. */
    @SuppressLint("MissingPermission")
    fun status(c: Context): BtStatus {
        val ad = adapter(c)
        val vendorList = runCatching { vendorPackages(c) }.getOrDefault(emptyList())
        val vendorPkg = vendorList.firstOrNull()
        // FYT-stereoen sier selv at telefonen er koblet til (egen BT-brikke som Android ikke ser)
        val hint = (if (Fyt.phoneConnected == true) BtVendorHint(Fyt.PKG, Fyt.phoneName, "FYT-tjenesten") else null) ?: vendorHint(vendorList)
        val on = runCatching { ad?.isEnabled == true }.getOrDefault(false)
        val perm = permitted(c)
        var bonded = emptyList<BtDevice>()
        var seen = emptyList<BtSeen>()
        if (ad != null && perm) {
            if (proxies.size < profileNames.size) connectProfiles(c)
            bonded = runCatching {
                ad.bondedDevices.orEmpty().map { d ->
                    val connected = runCatching { d.javaClass.getMethod("isConnected").invoke(d) as Boolean }.getOrDefault(false)
                    // Batteri fra telefonen (skjult Android-funksjon, virker bare der Android selv styrer Bluetooth)
                    val battery = runCatching { d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int }.getOrDefault(-1)
                    BtDevice(d.name ?: d.address, connected, battery, d.address, if (connected) "Android" else "")
                }
            }.getOrDefault(emptyList())
            seen = profileDevices() + acl.map { (a, n) -> BtSeen(n.ifBlank { a }, a, "tilkoblingsmelding") }
        }
        val s = BtStatus(ad != null, on, mergeBt(bonded, seen, hint), vendorPkg = vendorPkg, noPermission = ad != null && !perm)
        main.post { current = s }
        return s
    }

    /* ---------- Oppsettrapport ---------- */

    @SuppressLint("MissingPermission")
    fun report(c: Context): String = buildString {
        val ad = adapter(c)
        val perm = permitted(c)
        appendLine("Adapter: ${if (ad == null) "finnes ikke for Android" else "på=${runCatching { ad.isEnabled }.getOrNull()} tilstand=${runCatching { ad.state }.getOrNull()}"}, tillatelse=$perm")
        if (ad != null && perm) {
            appendLine("Stereoens BT-navn: ${runCatching { ad.name }.getOrNull()}")
            appendLine("Parede enheter: " + runCatching {
                ad.bondedDevices.orEmpty().joinToString { d ->
                    val conn = runCatching { d.javaClass.getMethod("isConnected").invoke(d) as Boolean }.getOrNull()
                    "${d.name}(${d.address}, tilkoblet=$conn, klasse=${runCatching { Integer.toHexString(d.bluetoothClass.deviceClass) }.getOrNull()})"
                }.ifEmpty { "ingen" }
            }.getOrDefault("?"))
        }
        profileNames.forEach { (p, name) ->
            val devs = proxies[p]?.let { proxy -> runCatching { proxy.connectedDevices.orEmpty().joinToString { "${it.name}(${it.address})" } }.getOrDefault("?") }
            appendLine("Profil $name ($p): ${proxyNote[p] ?: "ikke spurt"}${if (devs != null) ", tilkoblet: ${devs.ifEmpty { "ingen" }}" else ""}")
        }
        appendLine("Tilkoblinger sett av T'OS: ${acl.entries.joinToString { "${it.value}(${it.key})" }.ifEmpty { "ingen" }}; siste hendelse: $lastEvent")
        val vendorList = runCatching { vendorPackages(c) }.getOrDefault(emptyList())
        appendLine("Stereoens BT-apper: ${vendorList.joinToString().ifEmpty { "ingen funnet" }}")
        vendorList.forEach { p ->
            val acts = runCatching { c.packageManager.getPackageInfo(p, android.content.pm.PackageManager.GET_ACTIVITIES).activities }.getOrNull()
            appendLine("  $p skjermer: " + (acts?.joinToString { it.name.removePrefix(p) + if (it.exported) "" else " (lukket)" } ?: "?"))
            appendLine("  $p tjenester: " + runCatching { c.packageManager.queryIntentServices(Intent().setPackage(p), 0) }
                .getOrDefault(emptyList()).joinToString { it.serviceInfo.name.removePrefix(p) + if (it.serviceInfo.exported) "" else " (lukket)" })
        }
        appendLine("BT-varsler: " + notes.values.joinToString { "${it.pkg}: «${it.title}» / «${it.text}»" }.ifEmpty { "ingen" })
        val sessions = runCatching {
            (c.getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager)
                .getActiveSessions(android.content.ComponentName(c, MediaListener::class.java))
        }.getOrNull()
        if (sessions == null) appendLine("Mediaøkter: (krever varseltilgang)")
        else sessions.forEach { s ->
            val info = runCatching { s.playbackInfo }.getOrNull()
            val type = when (info?.playbackType) { MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE -> "ANNEN ENHET"; MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL -> "lokal"; else -> "?" }
            val title = runCatching { s.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) }.getOrNull()
            appendLine("Mediaøkt ${s.packageName}: tilstand=${runCatching { s.playbackState?.state }.getOrNull()} avspilling=$type " +
                "lydstrøm=${runCatching { info?.audioAttributes?.usage }.getOrNull()} tittel=«$title»")
        }
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        appendLine("Musikkvolum: ${runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()}/${runCatching { am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull()}" +
            " av=${runCatching { am.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrNull()} musikk spiller=${runCatching { am.isMusicActive }.getOrNull()} lydmodus=${runCatching { am.mode }.getOrNull()}")
        appendLine("FM-radio: på=${HwRadio.wanted} motor=${HwRadio.enginePkg} MCU=${HwRadio.mcuActive} viser=${HwRadio.state}")
        appendLine("BT-lyd: motor=${Prefs.bool("bt_engine", true)} sist=${BtAudio.lastAttempt}")
        val st = status(c)
        appendLine("T'OS sier: ${btState(st)} – " + st.devices.joinToString { "${it.name}${if (it.connected) " (tilkoblet via ${it.via})" else ""}" }.ifEmpty { "ingen enheter" })
    }
}

/** En tilkoblet enhet ett av lagene så (navn, adresse, hvordan). */
internal data class BtSeen(val name: String, val address: String, val via: String)

/** Bevis fra stereoens egen Bluetooth-app ([name] = telefonens navn hvis varselet sier det). */
internal data class BtVendorHint(val pkg: String, val name: String?, val via: String)

/** Androids egen Bluetooth-del (heter forskjellig på ulike Android-versjoner). */
internal val androidBtPkgs = setOf("com.android.bluetooth", "com.google.android.bluetooth")

/** Kjente Bluetooth-apper på kinesiske bilstereoer (egen BT-brikke). */
internal val knownBtApps = setOf("com.syu.bt", "com.szchoiceway.btsuite", "com.fyt.btphone", "com.microntek.bluetooth",
    "com.microntek.btmusic", "com.ts.bt", "com.hzbhd.bt", "com.syu.btphone", "com.ts.btphone")

/** Vanlige telefonapper (ikke stereoens BT-app, selv om brukeren har valgt dem som telefon). */
internal val plainDialers = setOf("com.android.dialer", "com.google.android.dialer", "no.bil.launcher", "com.android.server.telecom")

private val btNotPkgWords = listOf("midi", "obd", "elm327", "torque", "printer", "beacon", "gatt", "bluetoothle", "settings", "tethering")

/** Ser [pkg] ut som en Bluetooth-app (stereoens egen eller Androids)? Bare navnet sjekkes. Testes i BtLogicTest. */
internal fun isBtAppPkg(pkg: String): Boolean {
    if (pkg in knownBtApps || pkg in androidBtPkgs) return true
    val p = pkg.lowercase()
    if (btNotPkgWords.any { it in p }) return false
    return p.split('.').drop(1).any { it == "bt" || (it.startsWith("bt") && it.length in 3..12) || "bluetooth" in it || it == "blue" || it.startsWith("bluet") }
}

/** Stereoens egen Bluetooth-app (ikke Androids Bluetooth-del, ikke T'OS). Testes i BtLogicTest. */
internal fun isVendorBtPkg(pkg: String, ownPkg: String = "no.bil.launcher"): Boolean =
    pkg != ownPkg && pkg !in androidBtPkgs && pkg !in plainDialers && isBtAppPkg(pkg)

private val btOffWords = listOf("disconnected", "not connected", "unconnected", "no device", "connecting", "waiting", "searching", "pairing",
    "ikke tilkoblet", "ikke koblet", "frakoblet", "kobler til", "venter", "søker", "未连接", "未連接", "断开", "斷開", "连接中", "nicht verbunden", "getrennt")
private val btOnWords = listOf("connected", "tilkoblet", "koblet til", "已连接", "已連接", "连接成功", "verbunden", "conectado", "connecté")
private val btGenericNames = setOf("", "bt", "bluetooth", "telefon", "phone", "bt music", "bt-musikk", "bluetooth-musikk", "bluetooth music",
    "蓝牙", "藍牙", "bt phone", "handsfree", "carkit", "a2dp")

/**
 * Sier varselet fra en Bluetooth-app at telefonen er tilkoblet? true = ja, false = frakoblet/kobler til, null = vet ikke.
 * Testes i BtLogicTest.
 */
internal fun btNoteConnected(title: String?, text: String?): Boolean? {
    val all = listOfNotNull(title, text).joinToString(" ").lowercase()
    if (all.isBlank()) return null
    if (btOffWords.any { it in all }) return false
    if (btOnWords.any { it in all }) return true
    return null
}

/** Telefonens navn fra et «tilkoblet»-varsel («Connected to Tormods iPhone», tittel «iPhone» + tekst «Tilkoblet»). */
internal fun btNoteName(title: String?, text: String?): String? {
    val fields = listOf(title.orEmpty().trim(), text.orEmpty().trim())
    fun says(f: String) = btOnWords.firstOrNull { it in f.lowercase() }
    fun clean(s: String): String? {
        val t = s.trim(' ', ':', '-', '|', '·', ',', '.', '(', ')', '：')
            .replace(Regex("^(to|til|med|with|from|fra)\\s+", RegexOption.IGNORE_CASE), "").trim(' ', ':', '-', '|', '·', '"', '«', '»')
        return t.takeIf { it.lowercase() !in btGenericNames && it.length <= 40 }
    }
    for ((i, f) in fields.withIndex()) {
        val w = says(f) ?: continue
        val at = f.lowercase().indexOf(w)
        clean(f.removeRange(at, at + w.length))?.let { return it }
        val other = fields[1 - i]
        if (other.isNotBlank() && says(other) == null && btOffWords.none { it in other.lowercase() }) clean(other)?.let { return it }
    }
    return null
}

/** En mediaøkt fra en Bluetooth-app betyr at telefonen er tilkoblet når den spiller/er pauset eller viser en sang. */
internal fun btSessionSaysConnected(state: Int?, hasTitle: Boolean): Boolean = when {
    state == PlaybackState.STATE_ERROR -> false
    isPlayingState(state) || state == PlaybackState.STATE_PAUSED -> true
    else -> hasTitle
}

private val obdName = Regex("\\b(obd\\w*|elm\\s?327|elm|v-?link|vgate|icar\\w*|veepeak|konnwei)\\b", RegexOption.IGNORE_CASE)

/** OBD2-brikka er ikke «telefonen». Testes i BtLogicTest. */
internal fun looksLikeObdName(name: String): Boolean = obdName.containsMatchIn(name)

/**
 * Slår sammen lagene: parede enheter, enheter profilene/tilkoblingsmeldingene så, og – bare når ingenting annet er
 * tilkoblet – beviset fra stereoens BT-app. Tilkoblede først. Testes i BtLogicTest.
 */
internal fun mergeBt(bonded: List<BtDevice>, seen: List<BtSeen>, vendor: BtVendorHint?): List<BtDevice> {
    val out = bonded.toMutableList()
    for (s in seen) {
        val i = out.indexOfFirst { (s.address.isNotEmpty() && it.address.equals(s.address, true)) || it.name.equals(s.name, true) }
        if (i >= 0) { if (!out[i].connected) out[i] = out[i].copy(connected = true, via = s.via) }
        else out += BtDevice(s.name, true, -1, s.address, s.via)
    }
    if (vendor != null && out.none { it.connected && !looksLikeObdName(it.name) }) {
        val i = vendor.name?.let { n -> out.indexOfFirst { it.name.equals(n, true) } } ?: -1
        if (i >= 0) out[i] = out[i].copy(connected = true, via = "stereoens BT-app (${vendor.via})")
        else out += BtDevice(vendor.name ?: "Telefonen", true, -1, "", "stereoens BT-app (${vendor.via})")
    }
    return out.sortedByDescending { it.connected }
}

/** Overskrift og undertekst på Bluetooth-siden. Sier aldri «ikke tilkoblet» når stereoens egen BT-app styrer. */
internal fun btTexts(s: BtStatus): Pair<String, String> {
    val phone = s.phone
    return when {
        phone != null -> phone.name to when {
            phone.battery in 0..100 -> "Tilkoblet · batteri ${phone.battery} %"
            phone.via.startsWith("stereoens") -> "Tilkoblet via stereoens Bluetooth"
            else -> "Tilkoblet"
        }
        s.vendorPkg != null -> "Bluetooth styres av stereoen" to "Sjekk på telefonen at den er koblet til stereoen"
        !s.available -> "Bluetooth styres av stereoen" to "Slå på og par telefonen under Innstillinger"
        !s.on -> "Bluetooth er av" to "Slå på og par telefonen under Innstillinger"
        s.noPermission -> "Bluetooth" to "Gi T'OS lov til Bluetooth for å se telefonen (Innstillinger → Bluetooth)"
        else -> "Ingen telefon tilkoblet" to "Koble til telefonen under Innstillinger"
    }
}

/** Kort linje (telefonsiden, kildelista). */
internal fun btShortText(s: BtStatus): String = s.phone?.let { "Tilkoblet: ${it.name}" } ?: when {
    s.vendorPkg != null || !s.available -> "Bluetooth styres av stereoen – sjekk telefonen"
    !s.on -> "Bluetooth er av"
    s.noPermission -> "Bluetooth: T'OS mangler tillatelse"
    else -> "Ingen telefon tilkoblet"
}

/* ======================= Lyd fra telefonen over Bluetooth ======================= */

/**
 * «Bluetooth-lyd fra telefonen»: får stereoen til å spille det telefonen sender.
 *  1. Bluetooth-mediaøkten (telefonen) får «spill»,
 *  2. ellers Bluetooth-appens mediatjeneste/medieknapp,
 *  3. stereoer med egen BT-app bytter ofte lydkanal først når BT-musikken deres er aktiv: da startes BT-musikken
 *     i bakgrunnen (tjeneste), og kom ikke lyden i gang, et øyeblikk bak et svart T'OS-teppe («BT-motor», kan slås av).
 */
object BtAudio {
    private val handler = Handler(Looper.getMainLooper())
    /** Hva som sist ble prøvd (oppsettrapporten). */
    @Volatile var lastAttempt = "aldri"
        private set

    private fun btSessions(): List<MediaController> {
        val radio = Prefs.str("radio_pkg"); val bt = Prefs.str("btmusic_pkg")
        return NowPlaying.sessions.filter { s -> s.packageName?.let { sourceKind(it, radio, bt) == SourceKind.BLUETOOTH } == true }
    }

    private fun btPlaying() = btSessions().any { isPlayingState(runCatching { it.playbackState?.state }.getOrNull()) }

    /** [pkg]: Bluetooth-kilden fra kildelista (null = finn selv). */
    fun activate(c: Context, pkg: String? = null) {
        val a = c.applicationContext
        handler.removeCallbacksAndMessages(null)
        val btPkg = pkg ?: runCatching { Media.bluetoothPackage(a) }.getOrNull()
        val sessions = btSessions()
        var how = ""
        // FYT-stereo: BT-musikken slås på direkte i stereoen (lydkilde BT-musikk + «spill») – uten stereoens BT-app
        if (Fyt.btReady && Prefs.bool("fyt_bt", true) && Fyt.btavOn()) {
            lastAttempt = "FYT-tjenesten (kilde BT-musikk + spill)"
            handler.postDelayed({
                if (!Fyt.btavPlaying && !btPlaying() && Fyt.phoneConnected != true) Notify.show(PHONE_BT_HINT, long = true)
            }, CHECK_MS)
            return
        }
        val s = sessions.firstOrNull { it.packageName == btPkg } ?: sessions.firstOrNull()
        if (s != null) { NowPlaying.read(s); runCatching { s.transportControls.play() }; how += "mediaøkt ${s.packageName}; " }
        else if (btPkg != null) {
            if (MediaConnect.play(a, btPkg)) how += "mediatjeneste; "
            if (MediaConnect.mediaButton(a, btPkg, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)) how += "medieknapp; "
        }
        val vendor = btPkg?.takeIf { it !in androidBtPkgs } ?: BtLink.vendorPackagesCached().firstOrNull()
        if (vendor == null) {
            lastAttempt = how.ifEmpty { "ingen BT-kilde funnet" }
            if (s == null) Notify.show(PHONE_BT_HINT, long = true)
            return
        }
        startServices(a, vendor).takeIf { it > 0 }?.let { how += "$it tjeneste(r) i $vendor; " }
        lastAttempt = how.ifEmpty { "ingenting startet" }
        // Spiller det ikke etter litt: BT-motoren (BT-musikken i stereoens app et øyeblikk, så T'OS igjen)
        val back = if (TosOverlay.appShown) Nav.screen else Screen.MUSIC
        handler.postDelayed({
            if (btPlaying()) return@postDelayed
            if (Prefs.bool("bt_engine", true)) runEngine(a, vendor, back)
            else Notify.show(PHONE_BT_HINT, long = true)
        }, CHECK_MS)
    }

    /** BT-musikk-tjenestene i stereoens BT-app (startes uten skjermbilde). Gir hvor mange som ble startet. */
    private fun startServices(a: Context, pkg: String): Int =
        runCatching { a.packageManager.queryIntentServices(Intent().setPackage(pkg), 0) }.getOrDefault(emptyList())
            .mapNotNull { it.serviceInfo }
            .filter { it.exported && looksLikeBtMusicService(it.name) }
            .count { si -> runCatching { a.startService(Intent().setClassName(si.packageName, si.name)) }.getOrNull() != null }

    private fun runEngine(a: Context, pkg: String, back: Screen) {
        val acts = runCatching { a.packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES).activities }
            .getOrNull().orEmpty().filter { it.exported }.map { it.name }
        val i = pickBtMusicActivity(acts)?.let { Intent().setClassName(pkg, it) } ?: AppRepository.launchIntent(a, pkg)
        if (i == null) { Notify.show(PHONE_BT_HINT, long = true); return }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        AppRepository.markLaunched(pkg)
        TosOverlay.cover(a, "Kobler til Bluetooth-lyd …", ENGINE_SHOW_MS + 900)
        if (runCatching { a.startActivity(i) }.isFailure) {
            TosOverlay.uncover()
            lastAttempt += "BT-motor feilet (${i.component?.className}); "
            Notify.show(PHONE_BT_HINT, long = true)
            return
        }
        lastAttempt += "BT-motor ${i.component?.className ?: pkg}; "
        handler.postDelayed({ Buttons.show(a, back, instant = true); TosOverlay.armUncover() }, ENGINE_SHOW_MS)
        // Etterpå: «spill» til telefonen via BT-appen, og si fra hvis det fortsatt er stille
        handler.postDelayed({
            btSessions().firstOrNull()?.let { runCatching { it.transportControls.play() } }
                ?: MediaConnect.mediaButton(a, pkg, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
        }, ENGINE_SHOW_MS + 600)
        handler.postDelayed({ if (!btPlaying()) Notify.show(PHONE_BT_HINT, long = true) }, ENGINE_SHOW_MS + 4000)
    }

    private const val CHECK_MS = 1800L
    private const val ENGINE_SHOW_MS = 900L
}

/** Råd når lyden fra telefonen ikke kommer. */
internal const val PHONE_BT_HINT = "Ingen lyd fra telefonen? Velg stereoen som lydutgang på iPhonen (Spotify: Enheter-ikonet → Bluetooth)."

/** Tjenester i BT-appen som ser ut som BT-musikk (startes i bakgrunnen). Testes i BtLogicTest. */
internal fun looksLikeBtMusicService(cls: String): Boolean {
    val n = cls.substringAfterLast('.').lowercase()
    if ("notification" in n || "widget" in n || "browser" in n) return false
    return "music" in n || "a2dp" in n || "avrcp" in n || "audio" in n || "media" in n
}

/**
 * Skjermen i BT-appen som er BT-musikk («BtMusicActivity», «A2dpActivity» …), ellers null (da åpnes appen vanlig).
 * Testes i BtLogicTest.
 */
internal fun pickBtMusicActivity(classes: List<String>): String? {
    fun score(cls: String): Int {
        val n = cls.substringAfterLast('.').lowercase()
        if (listOf("setting", "call", "dial", "contact", "phonebook", "pair", "incall", "dialog").any { it in n }) return 0
        return when {
            "btmusic" in n || "bluetoothmusic" in n || "a2dp" in n -> 3
            "music" in n -> 2
            "audio" in n || "media" in n || "player" in n -> 1
            else -> 0
        }
    }
    return classes.filter { score(it) > 0 }.maxByOrNull { score(it) }
}
