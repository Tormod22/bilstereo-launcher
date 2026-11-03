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
    private var player by mutableStateOf<MediaPlayer?>(null)   // state: skjermen følger med når radioen starter/stopper
    private var prepared = false
    private var focusReq: android.media.AudioFocusRequest? = null
    private var pausedByFocus = false
    private var audio: android.media.AudioManager? = null   // for å slippe lyden når vi ikke har en Context (lyden tapt)
    private var preparedAt = 0L

    /** Radioen er i bruk (spiller, kobler til eller er pauset) – da styrer musikk-knappene radioen. */
    val active get() = player != null

    /** Siste kanal (for spill-knappen når ingenting er valgt). */
    fun last(): Station? = stations.firstOrNull { it.name == Prefs.str("radio_last") }

    private fun am(c: Context) = c.applicationContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

    /**
     * Be om lyden (Spotify o.l. pauser da), og pause selv ved samtale/navigasjonsstemme.
     * Samme forespørsel brukes om igjen (f.eks. ved fortsett etter pause) – en ny ville gjort at den gamle
     * fikk «lyden tapt» og stoppet radioen.
     */
    private fun takeFocus(c: Context): Boolean {
        val req = focusReq ?: newFocusRequest().also { focusReq = it }
        return runCatching { am(c).requestAudioFocus(req) }.getOrDefault(0) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun newFocusRequest(): android.media.AudioFocusRequest {
        var self: android.media.AudioFocusRequest? = null
        val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { f ->
                // Meldinger til en gammel forespørsel gjelder ikke lenger
                if (self == null || self !== focusReq) return@setOnAudioFocusChangeListener
                val p = player ?: return@setOnAudioFocusChangeListener
                when (f) {
                    android.media.AudioManager.AUDIOFOCUS_LOSS -> stop()
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                        if (prepared && playing) { runCatching { p.pause() }; playing = false; pausedByFocus = true }
                    android.media.AudioManager.AUDIOFOCUS_GAIN ->
                        if (prepared && pausedByFocus) { runCatching { p.start() }; playing = true; pausedByFocus = false }
                }
            }.build()
        self = req
        return req
    }

    private fun abandonFocus(c: Context?) {
        val r = focusReq ?: return
        focusReq = null
        (c?.let { am(it) } ?: audio)?.let { runCatching { it.abandonAudioFocusRequest(r) } }
    }

    fun play(c: Context, s: Station) = start(c, s, retry = false)

    private fun start(c: Context, s: Station, retry: Boolean) {
        stop(c)
        audio = am(c)
        current = s; buffering = true; error = null; prepared = false
        Prefs.set("radio_last", s.name)
        takeFocus(c)
        val mp = MediaPlayer()
        player = mp
        mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        mp.setOnPreparedListener {
            if (player !== it) return@setOnPreparedListener
            prepared = true; buffering = false; preparedAt = android.os.SystemClock.elapsedRealtime()
            runCatching { it.start(); playing = true }
        }
        mp.setOnErrorListener { it, _, _ ->
            if (player === it) fail(c, "Fikk ikke spilt ${s.name}. Er det internett?")
            true
        }
        // Strømmen tok slutt (nettbrudd o.l.): prøv én gang til, ellers stopp
        mp.setOnCompletionListener {
            if (player !== it) return@setOnCompletionListener
            val playedLong = android.os.SystemClock.elapsedRealtime() - preparedAt > 10_000
            if (!retry || playedLong) start(c.applicationContext, s, retry = true)
            else fail(c, "${s.name} stoppet. Er det internett?")
        }
        runCatching { mp.setDataSource(c, android.net.Uri.parse(s.url)); mp.prepareAsync() }
            .onFailure { fail(c, "Fikk ikke spilt ${s.name}") }
    }

    /** Feil: slipp spilleren og lyden, men behold valgt kanal og feilmeldingen. */
    private fun fail(c: Context, msg: String) {
        error = msg
        runCatching { player?.release() }
        player = null; prepared = false; buffering = false; playing = false; pausedByFocus = false
        abandonFocus(c)
    }

    fun toggle(c: Context) {
        val p = player
        when {
            p == null -> (current ?: last() ?: stations.first()).let { play(c, it) }
            !prepared -> stop(c)                                   // trykk mens den kobler til = avbryt
            playing -> { runCatching { p.pause() }; playing = false }
            else -> { takeFocus(c); runCatching { p.start() }; playing = true; pausedByFocus = false }
        }
    }

    fun step(c: Context, d: Int) {
        val i = stations.indexOf(current ?: last()).coerceAtLeast(0)
        play(c, stations[(i + d + stations.size) % stations.size])
    }

    fun stop(c: Context? = null) {
        runCatching { player?.release() }
        player = null; prepared = false; playing = false; buffering = false; current = null; pausedByFocus = false
        abandonFocus(c)
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
    private fun isRadioPkg(pkg: String): Boolean = isFmRadioPkg(pkg, Prefs.str("radio_pkg"))

    private val freqPattern = Regex("""(?:\b(FM\s?\d?|AM)\b)?\s*(\d{2,4}(?:[.,]\d{1,2})?)\s*(MHz|kHz)?""", RegexOption.IGNORE_CASE)

    private fun fromSession(): State? {
        val pkg = NowPlaying.pkg ?: return null
        if (!isRadioPkg(pkg)) return null
        val text = listOfNotNull(NowPlaying.title, NowPlaying.artist).filter { it.isNotBlank() }.joinToString("  ")
        return parseText(text)
    }

    /** Tolker radio-appens tekst («FM1 90.10», «NRK P1 101.7 MHz», «1040 kHz»). Testes i LogicTest. */
    internal fun parseText(text: String): State {
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

    /** Skru av radioen (bare szchoiceway; andre stereoer stoppes via mediaøkt/lydfokus i [RadioGuard]). */
    fun stopMcu() = send(byteArrayOf(0x01, 0x63))

    fun open(c: Context) {
        if (hasMcu(c)) send(byteArrayOf(0x01, 0x01))
        NetRadio.stop(c)
        AppRepository.launchTile(c, Tiles.byPrefix("Rad"))
        sessionControls()?.play()
    }
}

/* ======================= Stoppe radioen som startet av seg selv ======================= */

/**
 * Når RADIO-knappen åpner stereoens radio-app og T'OS bytter til noe annet, har radioen allerede startet.
 * Uten dette blir det bare skurr. T'OS stopper den på alle måtene som finnes:
 *  1. ber radio-appens mediaøkt om pause/stopp,
 *  2. skrur av radioen via MCU på szchoiceway-stereoer,
 *  3. tar lydfokus et øyeblikk (godt oppdragne apper slutter da å spille),
 *  4. lukker radio-appen når den er i bakgrunnen,
 * og starter det som spilte før (f.eks. Spotify) igjen.
 */
object RadioGuard {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun looksLikeRadio(pkg: String): Boolean = isFmRadioPkg(pkg, Prefs.str("radio_pkg"))

    fun silence(c: Context, pkg: String, resumePkg: String?) {
        val app = c.applicationContext
        val am = app.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            .build()
        fun hush() {
            MediaListener.stopSessionsOf(app, pkg)
            if (HwRadio.hasMcu(app)) HwRadio.stopMcu()
        }
        hush()
        // Spiller T'OS sin egen nettradio, skal den ikke miste lyden – da hoppes lydfokus-trikset over
        val grabbed = !NetRadio.active && runCatching { am.requestAudioFocus(req) }.isSuccess
        // Radio-appen kan bruke litt tid på å starte – gjenta etter at den er ferdig
        handler.postDelayed({ hush() }, 600)
        handler.postDelayed({
            hush()
            if (grabbed) runCatching { am.abandonAudioFocusRequest(req) }
            runCatching { (app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).killBackgroundProcesses(pkg) }
            if (resumePkg != null && !NetRadio.active) MediaListener.playSessionOf(app, resumePkg)
        }, 1500)
    }
}

/** Nettradio-apper som har «radio» i navnet, men ikke er stereoens FM/AM-radio (skal ikke stoppes). */
private val internetRadioWords = listOf("nrk", "tunein", "radiogarden", "radio.garden", "garden", "radioplayer", "iheart",
    "de.radio", "radionet", "radio.net", "streema", "simpleradio", "audials", "podcast", "spotify", "bbc", "myradio", "onlineradio",
    "internetradio", "webradio", "p4", "bauer", "sharpstream")

/**
 * Er [pkg] stereoens egen FM/AM-radio? Har brukeren valgt radio-app ([chosen]), gjelder bare den.
 * Ellers gjettes det på navnet, men kjente nettradio-apper (NRK Radio, TuneIn …) regnes ikke med. Testes i ServiceLogicTest.
 */
internal fun isFmRadioPkg(pkg: String, chosen: String): Boolean {
    if (chosen.isNotBlank()) return pkg == chosen
    val p = pkg.lowercase()
    if (internetRadioWords.any { it in p }) return false
    return "radio" in p || "fmradio" in p || "tuner" in p || p.endsWith(".fm") || ".fm." in p
}
