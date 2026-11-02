package no.bil.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Nettradio i T'OS selv (trenger internett, f.eks. fra iPhonen). Kanaler sjekket 2. nov. 2026. */
object NetRadio {
    data class Station(val name: String, val url: String)

    val stations = listOf(
        Station("NRK P1", "https://lyd.nrk.no/nrk_radio_p1_ostlandssendingen_mp3_h"),
        Station("NRK P2", "https://lyd.nrk.no/nrk_radio_p2_mp3_h"),
        Station("NRK P3", "https://lyd.nrk.no/nrk_radio_p3_mp3_h"),
        Station("NRK mP3", "https://lyd.nrk.no/nrk_radio_mp3_mp3_h"),
        Station("Alltid Nyheter", "https://lyd.nrk.no/nrk_radio_alltid_nyheter_mp3_h"),
        Station("P4", "https://p4.p4groupaudio.com/P04_MM"),
        Station("Radio Norge", "https://live-bauerno.sharp-stream.com/radionorge_no_mp3"),
        Station("Radio Rock", "https://live-bauerno.sharp-stream.com/radiorock_no_mp3"),
    )

    var current by mutableStateOf<Station?>(null)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var player: MediaPlayer? = null

    /** Radioen er «valgt» (spiller eller pauset) – da styrer musikk-knappene radioen. */
    val active get() = current != null

    fun play(c: Context, s: Station) {
        stop()
        current = s; buffering = true; error = null
        Prefs.set("radio_last", s.name)
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setOnPreparedListener { it.start(); buffering = false; playing = true }
            setOnErrorListener { _, _, _ -> error = "Fikk ikke spilt ${s.name}. Er det internett?"; buffering = false; playing = false; true }
            runCatching { setDataSource(c, android.net.Uri.parse(s.url)); prepareAsync() }
                .onFailure { error = "Fikk ikke spilt ${s.name}"; buffering = false }
        }
    }

    fun toggle(c: Context) {
        val p = player
        when {
            p == null -> current?.let { play(c, it) }
            playing -> { p.pause(); playing = false }
            else -> { p.start(); playing = true }
        }
    }

    fun step(c: Context, d: Int) {
        val i = stations.indexOf(current).coerceAtLeast(0)
        play(c, stations[(i + d + stations.size) % stations.size])
    }

    fun stop() {
        runCatching { player?.release() }
        player = null; playing = false; buffering = false; current = null
    }
}

/* ======================= Stereoens egen FM/AM-radio ======================= */

/**
 * Speiler stereoens ekte radio. Android har ikke noe åpent radio-API, så to veier:
 *  1. szchoiceway-stereoer: leser «SYS_MEDIA_INFO_JSON» og styrer radioen direkte via MCU-meldinger
 *     (søk, bånd, direkte frekvens).
 *  2. Alle andre: leser frekvens/stasjon fra radio-appens mediaøkt (sangtittel-feltet), og søker med
 *     neste/forrige – slik rattknappene styrer slike apper.
 *
 * Metode og MCU-kommandoer er hentet fra OpenLauncher (https://github.com/dw2lam/openlauncher),
 * MIT-lisens, © dw2lam og bidragsytere. Se docs/TREDJEPART.md.
 */
object HwRadio {
    data class State(val band: String, val freq: String, val station: String?, val canTune: Boolean) {
        val isAm get() = band.equals("AM", true)
    }

    private var mcu by mutableStateOf<State?>(null)
    private var app: Context? = null
    private var observing = false

    fun hasMcu(c: Context): Boolean =
        AppRepository.isInstalled(c, "com.szchoiceway.radio") ||
            runCatching { c.packageManager.getPackageInfo("com.szchoiceway.eventcenter", 0) }.isSuccess ||
            runCatching { android.provider.Settings.Global.getString(c.contentResolver, "SYS_MEDIA_INFO_JSON") != null }.getOrDefault(false)

    fun start(c: Context) {
        app = c.applicationContext
        if (observing || !hasMcu(c)) return
        observing = true
        mcu = readMcu(c)
        runCatching {
            c.contentResolver.registerContentObserver(
                android.provider.Settings.Global.getUriFor("SYS_MEDIA_INFO_JSON"), false,
                object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) { mcu = readMcu(c) }
                })
        }
    }

    private fun readMcu(c: Context): State? = runCatching {
        val json = android.provider.Settings.Global.getString(c.contentResolver, "SYS_MEDIA_INFO_JSON") ?: return null
        val title = org.json.JSONObject(json).optString("mediaTitle", "").trim()   // f.eks. "FM1 90.10"
        val parts = title.split(Regex("\\s+"))
        if (parts.size < 2) null else State(parts[0], parts[1], null, canTune = true)
    }.getOrNull()

    /** Radio-appen brukeren har valgt, ellers en app som ser ut som radio. */
    private fun isRadioPkg(pkg: String): Boolean {
        val chosen = Prefs.str("radio_pkg")
        if (chosen.isNotBlank()) return pkg == chosen
        val p = pkg.lowercase()
        return "radio" in p || "fmradio" in p || "tuner" in p || p.endsWith(".fm") || ".fm." in p
    }

    private val freqPattern = Regex("""(?:\b(FM\s?\d?|AM)\b)?\s*(\d{2,4}(?:[.,]\d{1,2})?)\s*(MHz|kHz)?""", RegexOption.IGNORE_CASE)

    private fun fromSession(): State? {
        val pkg = NowPlaying.pkg ?: return null
        if (!isRadioPkg(pkg)) return null
        val text = listOfNotNull(NowPlaying.title, NowPlaying.artist).filter { it.isNotBlank() }.joinToString("  ")
        val m = freqPattern.find(text)
        val raw = m?.groupValues?.get(2)?.replace(',', '.')?.takeIf { it.isNotEmpty() }
        val f = raw?.toFloatOrNull()
        val band = m?.groupValues?.get(1)?.replace(" ", "")?.uppercase()?.takeIf { it.isNotEmpty() }
            ?: if (f != null && f in 520f..1710f) "AM" else "FM"
        val station = (if (m != null) text.replace(m.value, "") else text).trim(' ', '-', '|', '/', '•').takeIf { it.isNotBlank() }
        return State(band, raw ?: "–", station, canTune = false)
    }

    /** Det radioen viser nå (null = ingen ekte radio funnet). */
    val state: State? get() = mcu ?: fromSession()
    val active get() = state != null

    private fun send(bytes: ByteArray) {
        val c = app ?: return
        runCatching {
            c.sendBroadcast(android.content.Intent("com.szchoiceway.eventcenter.EventUtils.ACTION_MCU_CMD_EVENT").putExtra("EventUtils.MCU_CMD_DATA", bytes))
        }
    }

    private fun sessionControls() = NowPlaying.controller?.takeIf { c -> c.packageName?.let { isRadioPkg(it) } == true }?.transportControls

    fun seekUp() { if (mcu != null) send(byteArrayOf(0x02, 0x0f)) else sessionControls()?.skipToNext() }
    fun seekDown() { if (mcu != null) send(byteArrayOf(0x02, 0x0e)) else sessionControls()?.skipToPrevious() }
    fun cycleFm() { if (mcu != null) send(byteArrayOf(0x02, 0x1e)) }
    fun am() { if (mcu != null) send(byteArrayOf(0x02, 0x1f)) }

    /** Direkte frekvens (bare szchoiceway). Ramme: 5a a5 0d 91 bånd bank(2) … frekvens(ASCII) sjekksum. */
    fun tune(band: String, freq: Float) {
        val c = app ?: return
        if (!hasMcu(c)) return
        val bandByte = when (band) { "FM3" -> 0x03; "AM" -> 0x04; else -> 0x01 }.toByte()
        val bank = if (band == "FM2") "02" else "01"
        val fs = if (band == "AM") String.format(java.util.Locale.US, "%.0f", freq) else String.format(java.util.Locale.US, "%.1f", freq)
        val frame = byteArrayOf(0x5a, 0xa5.toByte(), 0x0d) +
            byteArrayOf(0x91.toByte(), bandByte, bank[0].code.toByte(), bank[1].code.toByte()) +
            ByteArray(9 - fs.length) + fs.map { it.code.toByte() }.toByteArray()
        val sum = (frame.sumOf { it.toInt() and 0xFF } and 0xFF).toByte()
        send(byteArrayOf(0x0d, 0x08) + frame + byteArrayOf(sum))
    }

    fun open(c: Context) {
        if (hasMcu(c)) send(byteArrayOf(0x01, 0x01))
        NetRadio.stop()
        AppRepository.launchTile(c, Tiles.byPrefix("Rad"))
        sessionControls()?.play()
    }
}
