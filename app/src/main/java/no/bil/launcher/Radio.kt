package no.bil.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    fun play(c: Context, s: Station) = start(c.applicationContext, s, retry = false)

    /** [c] er alltid app-konteksten: spilleren lever lenger enn skjermen som startet den (ellers lekker skjermen). */
    private fun start(c: Context, s: Station, retry: Boolean) {
        stop(c)
        // Nettradio i stedet for FM: slå av stereoens radio, så de ikke spiller oppå hverandre
        if (HwRadio.wanted) HwRadio.powerOff(c, keep = Media.NET_RADIO)   // nettradioen tar lyden selv
        // FM-radioen som stereoen selv startet (RADIO-knappen): bytt stereoens lydkilde til Android, ellers spiller begge
        else if (FytRadio.onAir) runCatching { FytRadio.onRadioOff(); Fyt.androidSource() }
        audio = am(c)
        current = s; buffering = false; error = null; prepared = false; preparedAt = 0L
        Prefs.set("radio_last", s.name)
        // Får vi ikke lyden (f.eks. under en samtale), skal radioen ikke spille oppå
        if (!takeFocus(c)) { error = "Radioen kan ikke spille akkurat nå (samtale?). Prøv igjen etterpå."; abandonFocus(c); return }
        buffering = true
        val mp = MediaPlayer()
        player = mp
        mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        mp.setOnPreparedListener {
            if (player !== it) return@setOnPreparedListener
            prepared = true; buffering = false; preparedAt = android.os.SystemClock.elapsedRealtime()
            runCatching { it.start(); playing = true }
        }
        mp.setOnErrorListener { it, _, _ ->
            // Brudd etter at kanalen har spilt en stund (f.eks. kort uten dekning): koble til på nytt.
            // Feiler selve tilkoblingen, gis det opp (ingen evig prøving uten nett).
            if (player === it) {
                if (shouldReconnect(prepared, android.os.SystemClock.elapsedRealtime() - preparedAt)) start(c, s, retry = true)
                else fail(c, "Fikk ikke spilt ${s.name}. Er det internett?")
            }
            true
        }
        // Strømmen tok slutt (nettbrudd o.l.): prøv én gang til, ellers stopp
        mp.setOnCompletionListener {
            if (player !== it) return@setOnCompletionListener
            if (!retry || shouldReconnect(prepared, android.os.SystemClock.elapsedRealtime() - preparedAt)) start(c, s, retry = true)
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
            playing -> { runCatching { p.pause() }; playing = false; pausedByFocus = false }
            !takeFocus(c) -> error = "Radioen kan ikke spille akkurat nå (samtale?)."
            else -> { runCatching { p.start() }; playing = true; pausedByFocus = false; error = null }
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

/** Koble til på nytt bare når kanalen faktisk spilte i over 10 s før bruddet. Testes i ServiceLogicTest2. */
internal fun shouldReconnect(prepared: Boolean, playedMs: Long): Boolean = prepared && playedMs > 10_000

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
    /** [text]: RDS-radiotekst. [canStep]: frekvensen kan flyttes ett trinn (FYT). */
    data class State(val band: String, val freq: String, val station: String?, val canTune: Boolean,
                     val text: String? = null, val canStep: Boolean = false) {
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
        val a = c.applicationContext
        app = a
        if (observing) return
        observing = true
        // Pakkesjekken gjøres i bakgrunnen, så den ikke forsinker første skjermbilde
        Hub.scope.launch {
            if (!withContext(Dispatchers.IO) { hasMcu(a) }) { observing = false; return@launch }
            mcu = withContext(Dispatchers.IO) { readMcu(a) }
            // App-konteksten (ikke skjermen): observatøren lever så lenge T'OS kjører
            runCatching {
                a.contentResolver.registerContentObserver(
                    android.provider.Settings.Global.getUriFor("SYS_MEDIA_INFO_JSON"), false,
                    object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean) { mcu = readMcu(a) }
                    })
            }
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

    /** FYT-stereo: radioen styres direkte via com.syu.ms (bryter «fyt_radio», Innstillinger → Musikk). */
    val fyt get() = Fyt.radioReady && Prefs.bool("fyt_radio", true)

    /** Radioen ifølge FYT (bare når den er lydkilden, eller slått på i T'OS). */
    private fun fytState(): State? {
        if (!fyt || Fyt.radioFreqRaw <= 0 || !(Fyt.radioOnAir || wanted)) return null
        val fm = Fyt.radioFm ?: return null
        return State(if (fm) "FM" else "AM", FytLogic.freqText(Fyt.radioBandRaw, Fyt.radioFreqRaw), Fyt.radioPs, canTune = true,
            text = Fyt.radioText, canStep = true)
    }

    /** Det radioen viser nå (null = ingen ekte radio funnet). */
    val state: State? get() = fytState() ?: mcu ?: fromSession()
    val active get() = state != null
    /** Radioen styres direkte via MCU (szchoiceway). */
    val mcuActive get() = mcu != null

    private fun send(bytes: ByteArray) {
        val c = app ?: return
        runCatching {
            c.sendBroadcast(android.content.Intent("com.szchoiceway.eventcenter.EventUtils.ACTION_MCU_CMD_EVENT").putExtra("EventUtils.MCU_CMD_DATA", bytes))
        }
    }

    private fun sessionControls() = NowPlaying.controller?.takeIf { c -> c.packageName?.let { isRadioPkg(it) } == true }?.transportControls

    fun seekUp() { if (fyt) Fyt.radioSeek(up = true) else if (mcu != null) send(byteArrayOf(0x02, 0x0f)) else sessionControls()?.skipToNext() }
    fun seekDown() { if (fyt) Fyt.radioSeek(up = false) else if (mcu != null) send(byteArrayOf(0x02, 0x0e)) else sessionControls()?.skipToPrevious() }
    fun cycleFm() { if (fyt) Fyt.radioBand(fm = true) else if (mcu != null) send(byteArrayOf(0x02, 0x1e)) }
    fun am() { if (fyt) Fyt.radioBand(fm = false) else if (mcu != null) send(byteArrayOf(0x02, 0x1f)) }
    /** Ett frekvenstrinn opp/ned (bare FYT). */
    fun step(up: Boolean) { if (fyt) Fyt.radioStep(up) }

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

    /* ---------- Starte radioen i bakgrunnen (uten å vise stereoens radio-app) ---------- */

    /** Brukeren har slått på FM/AM-radioen i T'OS (og ikke slått den av). Da skal radio-appen ikke stoppes. */
    var wanted by mutableStateOf(false)
        private set
    /** Radio-appen T'OS bruker som «radiomotor» (null = ingen funnet). */
    @Volatile var enginePkg: String? = null
        private set
    /** Når T'OS sist startet radio-appen som motor (0 = ikke). Står i oppsettrapporten. */
    var engineAt = 0L
        private set
    /** Når radioen sist ble slått på (elapsedRealtime). */
    private var wantedAt = 0L
    /** Hvor lenge siden radioen ble slått på (stort tall når den ikke er på). */
    fun msSinceOn(): Long = if (wantedAt == 0L) Long.MAX_VALUE else android.os.SystemClock.elapsedRealtime() - wantedAt
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Stereoens FM/AM-radio-app: brukerens valg, ellers kjente pakkenavn, ellers navnesøk. */
    fun radioPackage(c: Context): String? = runCatching { AppRepository.tilePackage(c, Tiles.byPrefix("Rad")) }.getOrNull()

    /** Er [pkg] radio-appen T'OS styrer nå (slått på via T'OS)? Da skal den ikke stoppes eller lukkes. */
    fun isEngine(pkg: String): Boolean = wanted && (pkg == enginePkg || isRadioPkg(pkg))

    /** Spiller radio-appen (ifølge mediaøkten)? */
    private fun sessionPlaying(c: Context, pkg: String?): Boolean =
        pkg != null && MediaListener.sessionsOf(c, pkg).any { isPlayingState(it.playbackState?.state) }

    /**
     * Slå på stereoens FM/AM-radio uten å vise radio-appen. Prøver, i rekkefølge:
     *  1. MCU-melding (szchoiceway-stereoer),
     *  2. radio-appens mediaøkt («spill»),
     *  3. radio-appens medieknapp-mottaker / mediatjeneste / radiotjeneste i bakgrunnen,
     *  4. «radiomotor»: hvis ingenting av dette fikk radioen i gang, startes radio-appen et øyeblikk bak et svart
     *     T'OS-teppe, og T'OS hentes straks fram igjen (radioen fortsetter å spille i bakgrunnen).
     * [showPage]: vis T'OS sin radio-side etterpå.
     */
    fun powerOn(c: Context, showPage: Boolean = false) {
        val a = c.applicationContext
        app = a
        // Brukeren vil ha radioen nå: en ventende «skru av radioen» fra RADIO-knappen skal ikke slå den av igjen
        RadioGuard.cancel()
        handler.removeCallbacksAndMessages(null)
        NetRadio.stop(a)
        wanted = true
        wantedAt = android.os.SystemClock.elapsedRealtime()
        // FYT: radioen slås på direkte i stereoen (lydkilden byttes til radio) – ingen radio-app, ingen radiomotor
        if (fyt && Fyt.radioOn()) {
            enginePkg = null
            if (showPage) Buttons.show(a, Screen.RADIO, instant = true)
            return
        }
        val pkg = radioPackage(a)
        enginePkg = pkg
        val mcuOk = hasMcu(a)
        if (mcuOk) send(byteArrayOf(0x01, 0x01))
        if (pkg == null && !mcuOk) {
            wanted = false
            Notify.show("Fant ingen FM-radio på stereoen. Velg radio-appen under Innstillinger → Apper.", long = true)
            return
        }
        if (showPage) Buttons.show(a, Screen.RADIO, instant = true)
        // Skjermen T'OS skal tilbake til hvis radiomotoren må startes (der brukeren var, f.eks. musikksiden)
        val back = if (!showPage && TosOverlay.appShown) Nav.screen else Screen.RADIO
        val session = pkg?.let { MediaListener.sessionsOf(a, it).firstOrNull() }
        if (session != null) runCatching { session.transportControls.play() }
        else if (pkg != null) startInBackground(a, pkg)
        // MCU-stereoer: radioen styres direkte, motoren trengs ikke
        if (mcuOk || pkg == null) return
        handler.postDelayed({
            if (wanted && !sessionPlaying(a, pkg) && Prefs.bool("radio_engine", true)) runEngine(a, pkg, back)
        }, CHECK_MS)
    }

    /**
     * Stereoen åpnet radio-appen selv (f.eks. med RADIO-knappen) og radioen har startet: behold den, men vis
     * T'OS sin radio-side i stedet for radio-appen.
     */
    fun adopt(c: Context, pkg: String) {
        val a = c.applicationContext
        app = a
        RadioGuard.cancel()
        NetRadio.stop(a)
        wanted = true
        wantedAt = android.os.SystemClock.elapsedRealtime()
        // FYT: stereoen har allerede gjort radioen til lydkilden (kilde 1) – T'OS viser radioen via FYT-tjenesten,
        // uten radio-app og uten radiomotor (radio-appen blir liggende i bakgrunnen og slås ikke av)
        if (fyt) {
            enginePkg = null; engineAt = 0L
            TosOverlay.cover(a)
            Buttons.show(a, Screen.RADIO, instant = true)
            TosOverlay.armUncover()
            return
        }
        enginePkg = pkg
        engineAt = android.os.SystemClock.elapsedRealtime()
        TosOverlay.cover(a)
        Buttons.show(a, Screen.RADIO, instant = true)
        TosOverlay.armUncover()
        handler.postDelayed({ MediaListener.sessionsOf(a, pkg).firstOrNull()?.let { runCatching { it.transportControls.play() } } }, 800)
    }

    /**
     * Slå av FM/AM-radioen (mediaøkt, MCU, lydfokus). Radio-appen lukkes også når den ligger i bakgrunnen.
     * [keep]: kilden brukeren bytter til – den stoppes aldri (noen stereoer har radio og BT-musikk i samme app),
     * og lydfokus-trikset hoppes over (det ville pauset nettopp den kilden).
     */
    fun powerOff(c: Context, keep: String? = null) {
        val a = c.applicationContext
        handler.removeCallbacksAndMessages(null)
        runCatching { FytRadio.onRadioOff() }   // søk/innstilling stopper, lyden på igjen hvis T'OS skrudde den av
        val viaFyt = fyt && enginePkg == null
        val pkg = enginePkg ?: radioPackage(a)
        wanted = false
        engineAt = 0L; wantedAt = 0L
        // FYT: gi lydkilden tilbake (radioen er av); radio-appen er ikke startet og trenger ikke stoppes
        if (viaFyt || (fyt && Fyt.radioOnAir)) { Fyt.radioOff(); if (viaFyt) return }
        if (pkg != null) RadioGuard.silence(a, pkg, resumePkg = null, keep = keep)
        else if (hasMcu(a)) stopMcu()
    }

    /**
     * En annen app (Spotify, YouTube, telefonen over Bluetooth …) begynte å spille mens FM-radioen var slått på i T'OS:
     * radioen slås av uten å ta lydfokus (musikken som startet skal ikke pauses), og på MCU-stereoer gis
     * lydkanalen tilbake til Android – ellers kunne radioen holdt kanalen og musikken blitt stille.
     */
    fun yieldTo(c: Context, musicPkg: String) {
        if (!wanted) return
        val a = c.applicationContext
        handler.removeCallbacksAndMessages(null)
        runCatching { FytRadio.onRadioOff() }   // søk/innstilling stopper, lyden på igjen hvis T'OS skrudde den av
        val viaFyt = fyt && enginePkg == null
        val pkg = enginePkg ?: radioPackage(a)
        wanted = false
        engineAt = 0L; wantedAt = 0L
        // FYT: slipp radioen som lydkilde bare hvis den fortsatt er det (stereoen bytter ofte selv når musikken starter)
        if (fyt && Fyt.radioOnAir) Fyt.radioOff()
        if (viaFyt) return
        if (NowPlaying.preferredPkg == pkg) NowPlaying.preferredPkg = null
        if (pkg != null && pkg != musicPkg) RadioGuard.silence(a, pkg, resumePkg = null, keep = musicPkg)
        else if (hasMcu(a)) stopMcu()
    }

    /** Medieknapp «spill» rett til radio-appen, og mediatjenesten/radiotjenesten dens – alt uten skjermbilde. */
    private fun startInBackground(a: Context, pkg: String) {
        MediaConnect.mediaButton(a, pkg, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
        if (MediaConnect.play(a, pkg)) return
        val pm = a.packageManager
        runCatching { pm.queryIntentServices(android.content.Intent().setPackage(pkg), 0) }.getOrDefault(emptyList())
            .mapNotNull { it.serviceInfo }
            .filter { it.exported && looksLikeTunerService(it.name) }
            .forEach { si -> runCatching { a.startService(android.content.Intent().setClassName(si.packageName, si.name)) } }
    }

    /**
     * Radiomotor: start radio-appen bak et svart T'OS-teppe og hent T'OS fram igjen. Appen merkes som åpnet av T'OS,
     * så knappestyringen ikke sender deg et annet sted eller stopper radioen ([isEngine]).
     */
    private fun runEngine(a: Context, pkg: String, back: Screen) {
        val i = AppRepository.launchIntent(a, pkg) ?: return
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION or
            android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        AppRepository.markLaunched(pkg)
        engineAt = android.os.SystemClock.elapsedRealtime()
        TosOverlay.cover(a, "Starter radio …", ENGINE_SHOW_MS + 900)
        if (runCatching { a.startActivity(i) }.isFailure) {
            TosOverlay.uncover(); engineAt = 0L
            Notify.show("Fikk ikke startet radioen")
            return
        }
        handler.postDelayed({
            Buttons.show(a, back, instant = true)
            TosOverlay.armUncover()
        }, ENGINE_SHOW_MS)
        // Noen radio-apper venter på «spill» før de slår på mottakeren
        handler.postDelayed({ MediaListener.sessionsOf(a, pkg).firstOrNull()?.let { runCatching { it.transportControls.play() } } }, ENGINE_SHOW_MS + 600)
    }

    /**
     * Reserve: åpne stereoens egen radio-app (med skjermbildet). Bare når brukeren ber om det
     * (f.eks. «Åpne original radio-app») – vanligvis styres radioen fra T'OS.
     */
    fun openApp(c: Context) {
        RadioGuard.cancel()
        if (app == null) app = c.applicationContext
        if (hasMcu(c)) send(byteArrayOf(0x01, 0x01))
        NetRadio.stop(c)
        wanted = true
        wantedAt = android.os.SystemClock.elapsedRealtime()
        enginePkg = radioPackage(c)
        AppRepository.launchTile(c, Tiles.byPrefix("Rad"))
        sessionControls()?.play()
    }

    /** Start radioen (i bakgrunnen) og vis T'OS sin radio-side. Brukt av «Start radio» på radio-siden. */
    fun open(c: Context) = powerOn(c, showPage = false)

    private const val CHECK_MS = 1800L
    private const val ENGINE_SHOW_MS = 900L
}

/** Tjenester i radio-appen som ser ut som selve mottakeren (startes i bakgrunnen). Testes i ServiceLogicTest3. */
internal fun looksLikeTunerService(cls: String): Boolean {
    val n = cls.substringAfterLast('.').lowercase()
    if ("browser" in n || "notification" in n || "widget" in n) return false
    return "radio" in n || "tuner" in n || n.startsWith("fm") || "fmservice" in n || "fmplay" in n
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

    /** Avbryt en ventende stopp (brukeren åpnet radioen selv like etterpå). */
    fun cancel() = handler.removeCallbacksAndMessages(null)

    /**
     * [mayClose]: appen kan lukkes helt (bare når den ser ut som en radio-app og ikke er stereoens startskjerm –
     * på noen stereoer ligger radioen inne i hovedappen, og den skal ikke lukkes).
     */
    fun silence(c: Context, pkg: String, resumePkg: String?, mayClose: Boolean = looksLikeRadio(pkg), keep: String? = null) {
        cancel()
        val app = c.applicationContext
        val am = app.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            .build()
        // Har radio-appen også BT-musikk/musikk (samme app), og det er den brukeren bytter til: ikke stopp den
        val stopSessions = pkg != keep
        fun hush() {
            if (stopSessions) MediaListener.stopSessionsOf(app, pkg)
            if (HwRadio.hasMcu(app)) HwRadio.stopMcu()
        }
        hush()
        // Lydfokus-trikset (Spotify o.l. ville pauset): ikke når T'OS sin nettradio spiller, når brukeren bytter til
        // en annen kilde, eller når en annen app (ikke radio) allerede spiller – den skal fortsette
        val othersPlaying = NowPlaying.sessions.any { s ->
            val p = s.packageName
            p != null && p != pkg && !looksLikeRadio(p) && isPlayingState(runCatching { s.playbackState?.state }.getOrNull())
        }
        val grabbed = !NetRadio.active && keep == null && !othersPlaying &&
            runCatching { am.requestAudioFocus(req) }.getOrDefault(0) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        // Radio-appen kan bruke litt tid på å starte – gjenta etter at den er ferdig
        handler.postDelayed({ hush() }, 600)
        handler.postDelayed({
            hush()
            if (grabbed) runCatching { am.abandonAudioFocusRequest(req) }
            if (mayClose && stopSessions) runCatching { (app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).killBackgroundProcesses(pkg) }
            if (resumePkg != null && !NetRadio.active) MediaListener.playSessionOf(app, resumePkg)
        }, 1500)
    }
}

/** Nettradio-apper som har «radio» i navnet, men ikke er stereoens FM/AM-radio (skal ikke stoppes). */
internal val internetRadioWords = listOf("nrk", "tunein", "radiogarden", "radio.garden", "garden", "radioplayer", "iheart",
    "de.radio", "radionet", "radio.net", "streema", "simpleradio", "audials", "podcast", "spotify", "bbc", "myradio", "onlineradio",
    "internetradio", "webradio", "p4", "bauer", "sharpstream")

private val systemRadioWords = listOf("radioconfig", "telephony", "radiointerface", "hardware.radio", "qti.radio")

/**
 * Er [pkg] stereoens egen FM/AM-radio? Har brukeren valgt radio-app ([chosen]), gjelder bare den.
 * Ellers gjettes det på navnet, men kjente nettradio-apper (NRK Radio, TuneIn …) regnes ikke med. Testes i ServiceLogicTest.
 */
internal fun isFmRadioPkg(pkg: String, chosen: String): Boolean {
    if (chosen.isNotBlank()) return pkg == chosen
    val p = pkg.lowercase()
    if (internetRadioWords.any { it in p }) return false
    // Telefon-/mobilnett-deler av Android heter også ofte «radio» (f.eks. radioconfig) – de er ikke FM-radioen
    if (systemRadioWords.any { it in p }) return false
    return "radio" in p || "fmradio" in p || "tuner" in p || p.endsWith(".fm") || ".fm." in p
}
