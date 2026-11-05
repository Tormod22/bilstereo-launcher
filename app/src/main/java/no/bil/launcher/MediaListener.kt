package no.bil.launcher

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/* ======================= Det som spiller nå ======================= */

object NowPlaying {
    var title by mutableStateOf<String?>(null)
    var artist by mutableStateOf<String?>(null)
    var art by mutableStateOf<Bitmap?>(null)
    var playing by mutableStateOf(false)
    var pkg by mutableStateOf<String?>(null)
    var durationMs by mutableLongStateOf(0L)
    /** Appen spiller på en annen enhet (Spotify Connect o.l.) – lyden kommer ikke fra stereoen. */
    var remote by mutableStateOf(false)
    private var posMs = 0L
    private var posAt = 0L
    private var speed = 1f
    internal var controller: MediaController? = null

    val hasSession get() = controller != null

    /** Alle apper som har en mediaøkt nå (Spotify, Bluetooth-musikk, radio …). Følges av skjermen. */
    internal var sessions by mutableStateOf<List<MediaController>>(emptyList())
    /** Kilden brukeren valgte selv ([Media.selectSource]). Den vises og styres så lenge den finnes og ingen annen spiller. */
    @Volatile internal var preferredPkg: String? = null

    /** Siste musikkapp som spilte (ikke radio). Gjelder hvis den fortsatt spiller, eller stoppet for under 15 s siden. */
    private var lastMusicPkg: String? = null
    private var lastMusicPlaying = false
    private var lastMusicStoppedAt = 0L
    fun recentMusic(exclude: String): String? = lastMusicPkg?.takeIf {
        it != exclude && (lastMusicPlaying || SystemClock.elapsedRealtime() - lastMusicStoppedAt < 15_000)
    }

    /** Hvor langt i sangen vi er nå (regnes fram fra siste melding fra appen). */
    fun positionMs(): Long =
        if (!playing) posMs else (posMs + ((SystemClock.elapsedRealtime() - posAt) * speed).toLong()).coerceAtMost(durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)

    internal fun read(c: MediaController?) {
        controller = c
        val md = c?.metadata
        title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        pkg = c?.packageName
        remote = isRemote(c)
        val st = c?.playbackState
        playing = isPlayingState(st?.state)
        posMs = st?.position ?: 0L
        posAt = st?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
        speed = st?.playbackSpeed?.takeIf { it > 0 } ?: 1f
        val p = pkg
        if (p != null) Prefs.setQuiet("last_player", p)
        if (p != null && playing && !RadioGuard.looksLikeRadio(p)) { lastMusicPkg = p; lastMusicPlaying = true }
        else if (lastMusicPlaying && (p != lastMusicPkg || !playing)) { lastMusicPlaying = false; lastMusicStoppedAt = SystemClock.elapsedRealtime() }
    }
}

/** Neste sving fra Google Maps / Waze, lest fra varselet deres. */
object NavInfo {
    var title by mutableStateOf<String?>(null)
    var text by mutableStateOf<String?>(null)
    val active get() = title != null
}

/** Innkommende samtale (fra varsel med kategori «samtale»). */
object Call {
    var name by mutableStateOf<String?>(null)
    internal var open: PendingIntent? = null
    /** Varselet samtalen kom fra (så den fjernes når samme varsel blir «pågående» eller forsvinner). */
    internal var key: String? = null
    /** «Svar»/«Avvis»-knappene i telefonappens varsel – da kan T'OS svare uten å vise telefonappen. */
    internal var answerAction: PendingIntent? = null
    internal var declineAction: PendingIntent? = null
    /** Kan T'OS svare/avvise selv (varselet har knapper for det)? */
    var canAnswer by mutableStateOf(false)
        private set

    fun openCall() { runCatching { open?.send() } }

    /** Svar på samtalen med knappen i varselet; finnes den ikke, åpnes telefonappen. */
    fun answer() { val a = answerAction; if (a == null || runCatching { a.send() }.isFailure) openCall() }

    /** Avvis samtalen (gir false hvis telefonappens varsel ikke har en avvis-knapp). */
    fun decline(): Boolean = declineAction?.let { runCatching { it.send() }.isSuccess } == true

    internal fun setActions(n: Notification) {
        val acts = n.actions.orEmpty()
        val titles = acts.map { it.title?.toString().orEmpty() }
        answerAction = acts.getOrNull(callActionIndex(titles, answer = true))?.actionIntent
        declineAction = acts.getOrNull(callActionIndex(titles, answer = false))?.actionIntent
        canAnswer = answerAction != null
    }

    internal fun clear() { name = null; open = null; key = null; answerAction = null; declineAction = null; canAnswer = false }
}

private val answerWords = listOf("answer", "svar", "besvar", "accept", "pick up", "ta imot", "接听", "接受", "接聽")
private val declineWords = listOf("decline", "avvis", "reject", "hang up", "legg på", "end call", "dismiss", "挂断", "拒绝", "拒接", "掛斷")

/** Hvilken knapp i samtalevarselet som svarer ([answer]) eller avviser. -1 = ingen. Testes i ServiceLogicTest3. */
internal fun callActionIndex(titles: List<String>, answer: Boolean): Int {
    val words = if (answer) answerWords else declineWords
    val other = if (answer) declineWords else answerWords
    return titles.indexOfFirst { t -> val l = t.lowercase(); words.any { it in l } && other.none { it in l } }
}

/**
 * Er samtalevarselet en innkommende samtale som ikke er besvart ennå?
 * Android 12+: samtaletypen i varselet. Ellers: et pågående varsel uten fullskjerm-visning er en besvart/utgående
 * samtale, og et varsel med tidtaker er en samtale som er i gang. Innkommende samtaler har fullskjerm-visning
 * (telefonappene merker dem ofte også som «pågående», så det alene avgjør ikke). Testes i ServiceLogicTest.
 */
internal fun isIncomingCall(callType: Int, hasFullScreen: Boolean, ongoing: Boolean, chronometer: Boolean): Boolean {
    if (callType != 0) return callType == 1   // Notification.CallStyle.CALL_TYPE_INCOMING
    if (ongoing && !hasFullScreen) return false
    return hasFullScreen && !chronometer
}

/** Spiller økten på en annen enhet (Spotify Connect, Chromecast …)? */
internal fun isRemote(c: MediaController?): Boolean =
    c != null && runCatching { c.playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE }.getOrDefault(false)

/** Spiller (eller laster/hopper til neste sang – da kommer lyden straks). */
internal fun isPlayingState(state: Int?): Boolean = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING ||
    state == PlaybackState.STATE_SKIPPING_TO_NEXT || state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS ||
    state == PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM || state == PlaybackState.STATE_CONNECTING

/**
 * Hvilken mediaøkt som skal vises: den brukeren valgte (hvis den spiller eller ingen andre spiller), ellers den som
 * nå vises hvis den spiller, ellers første som spiller, ellers den som vises (hvis den finnes), ellers den første.
 * -1 = ingen. Testes i ServiceLogicTest2 og ServiceLogicTest3.
 */
internal fun pickSession(playing: List<Boolean>, currentIndex: Int, preferredIndex: Int = -1): Int {
    if (playing.isEmpty()) return -1
    // Kilden brukeren valgte: vinner når den spiller, eller når ingen andre spiller
    if (preferredIndex in playing.indices && (playing[preferredIndex] || playing.none { it })) return preferredIndex
    if (currentIndex in playing.indices && playing[currentIndex]) return currentIndex
    val first = playing.indexOfFirst { it }
    if (first >= 0) return first
    return if (currentIndex in playing.indices) currentIndex else 0
}

/* ======================= Musikkontroll ======================= */

/** Hva slags lydkilde en app er. */
enum class SourceKind { MUSIC, BLUETOOTH, RADIO, NET_RADIO }

/** En lydkilde musikksiden kan bytte til. [pkg] er pakkenavnet (eller [Media.NET_RADIO] for T'OS sin nettradio). */
data class MediaSource(val pkg: String, val label: String, val kind: SourceKind, val playing: Boolean, val selected: Boolean)

/**
 * Hva slags kilde [pkg] er. Bluetooth-musikk (AVRCP) kommer fra Androids Bluetooth-del eller stereoens BT-app;
 * FM/AM-radio fra radio-appen. Testes i ServiceLogicTest3.
 */
internal fun sourceKind(pkg: String, radioChosen: String, btChosen: String = ""): SourceKind {
    if (pkg == Media.NET_RADIO) return SourceKind.NET_RADIO
    if (btChosen.isNotBlank() && pkg == btChosen) return SourceKind.BLUETOOTH
    val p = pkg.lowercase()
    val parts = p.split('.')
    if (p == "com.android.bluetooth" || p.endsWith(".btmusic") || "bluetooth" in p || "a2dp" in p || "avrcp" in p ||
        parts.any { it == "bt" || it == "btmusic" || it == "btphone" || it == "bluetoothmusic" }) return SourceKind.BLUETOOTH
    if (isFmRadioPkg(pkg, radioChosen)) return SourceKind.RADIO
    return SourceKind.MUSIC
}

/** Skal økten [pkg] pauses når brukeren bytter kilde til [keep]/[keepKind]? Testes i BtLogicTest. */
internal fun shouldPauseOnSwitch(pkg: String, keep: String?, keepKind: SourceKind?, kind: SourceKind, playing: Boolean, remote: Boolean): Boolean =
    playing && !remote && pkg != keep && !(keepKind != null && keepKind != SourceKind.MUSIC && kind == keepKind)

/** Styrer musikken: nettradio i T'OS, ellers appen som spiller, ellers vanlige mediaknapper. */
object Media {
    /** «Pakkenavnet» til T'OS sin egen nettradio i kildelista. */
    const val NET_RADIO = "tos:nettradio"

    /** Kilder som ikke endrer seg ofte (installerte apper) – leses høyst hvert 30. sekund. */
    private class Fixed(val at: Long, val list: List<Pair<String, SourceKind>>, val labels: Map<String, String>)
    @Volatile private var fixed: Fixed? = null
    private val labels = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun label(c: Context, pkg: String, kind: SourceKind, cache: Map<String, String>): String = when (kind) {
        SourceKind.BLUETOOTH -> "Bluetooth"
        SourceKind.RADIO -> "Radio (FM/AM)"
        SourceKind.NET_RADIO -> "Nettradio"
        SourceKind.MUSIC -> cache[pkg] ?: labels.getOrPut(pkg) {
            runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        }
    }

    /** Bluetooth-musikkilden: valgt app, ellers Androids Bluetooth (A2DP-mottaker), ellers stereoens BT-app. */
    fun bluetoothPackage(c: Context): String? {
        Prefs.str("btmusic_pkg").takeIf { it.isNotBlank() && hasPlayer(c, it) }?.let { return it }
        if (hasPlayer(c, "com.android.bluetooth")) return "com.android.bluetooth"
        Tiles.byPrefix("Blu").candidates.firstOrNull { it != "com.android.bluetooth" && AppRepository.isInstalled(c, it) }?.let { return it }
        // Stereoens egen BT-app (funnet av BtLink i bakgrunnen)
        return BtLink.vendorPackagesCached().firstOrNull()
    }

    /** Kan [pkg] spille uten skjermbilde (mediatjeneste eller medieknapp-mottaker)? */
    private fun hasPlayer(c: Context, pkg: String): Boolean =
        MediaConnect.browserService(c, pkg) != null || MediaConnect.buttonReceivers(c, pkg).isNotEmpty()

    private fun fixedSources(c: Context): Fixed {
        val now = SystemClock.elapsedRealtime()
        fixed?.takeIf { now - it.at < 30_000 }?.let { return it }
        val list = mutableListOf<Pair<String, SourceKind>>()
        val music = Prefs.str("music_pkg").takeIf { it.isNotBlank() && AppRepository.isInstalled(c, it) }
            ?: runCatching { AppRepository.tilePackage(c, Tiles.byPrefix("Mus")) }.getOrNull()
        music?.let { list += it to SourceKind.MUSIC }
        runCatching { bluetoothPackage(c) }.getOrNull()?.let { list += it to SourceKind.BLUETOOTH }
        val radio = HwRadio.radioPackage(c)
        if (radio != null) list += radio to SourceKind.RADIO
        else if (HwRadio.hasMcu(c)) list += "com.szchoiceway.radio" to SourceKind.RADIO
        val labels = list.associate { (p, k) -> p to label(c, p, k, emptyMap()) }
        return Fixed(now, list, labels).also { fixed = it }
    }

    /**
     * Lydkildene musikksiden kan bytte mellom: apper som har mediaøkt nå, valgt musikkapp (Spotify),
     * Bluetooth-musikk, stereoens FM/AM-radio og T'OS sin nettradio. Leser Compose-tilstand, så lista
     * oppdateres av seg selv på skjermen. Den som styres nå har [MediaSource.selected].
     */
    fun sources(c: Context): List<MediaSource> {
        val radioChosen = Prefs.str("radio_pkg")
        val btChosen = Prefs.str("btmusic_pkg")
        val f = fixedSources(c)
        val out = linkedMapOf<String, MediaSource>()
        val current = currentSource()
        NowPlaying.sessions.forEach { s ->
            val p = s.packageName ?: return@forEach
            if (p == c.packageName || p in out) return@forEach
            val k = sourceKind(p, radioChosen, btChosen)
            out[p] = MediaSource(p, label(c, p, k, f.labels), k, isPlayingState(s.playbackState?.state), p == current)
        }
        f.list.forEach { (p, k) ->
            if (p in out || out.values.any { it.kind == k && k != SourceKind.MUSIC }) return@forEach
            val playing = k == SourceKind.RADIO && HwRadio.wanted
            out[p] = MediaSource(p, label(c, p, k, f.labels), k, playing, p == current || (k == SourceKind.RADIO && current == RADIO_MARK))
        }
        out[NET_RADIO] = MediaSource(NET_RADIO, "Nettradio", SourceKind.NET_RADIO, NetRadio.playing, current == NET_RADIO)
        return out.values.toList()
    }

    private const val RADIO_MARK = "tos:fm"

    /** Pakken (eller [NET_RADIO]) som musikk-knappene styrer nå. */
    fun currentSource(): String? = when {
        NetRadio.active -> NET_RADIO
        HwRadio.wanted && (NowPlaying.pkg == null || NowPlaying.pkg == HwRadio.enginePkg) -> HwRadio.enginePkg ?: RADIO_MARK
        else -> NowPlaying.pkg
    }

    /**
     * Bytt lydkilde uten å åpne appen: [pkg] er et pakkenavn fra [sources] (Spotify, Bluetooth, radio-appen)
     * eller [NET_RADIO]. Den andre kilden pauses. Bluetooth-musikk startes via Androids Bluetooth-mediatjeneste;
     * finnes ingen økt ennå, bes brukeren starte musikken på telefonen.
     */
    fun selectSource(c: Context, pkg: String) {
        val a = c.applicationContext
        val kind = sourceKind(pkg, Prefs.str("radio_pkg"), Prefs.str("btmusic_pkg"))
        when {
            kind == SourceKind.NET_RADIO -> {
                pauseOthers(keep = null)
                NetRadio.play(a, NetRadio.current ?: NetRadio.last() ?: NetRadio.stations.first())
            }
            kind == SourceKind.RADIO || pkg == RADIO_MARK -> {
                pauseOthers(keep = pkg)
                if (pkg != RADIO_MARK) NowPlaying.preferredPkg = pkg
                HwRadio.powerOn(a)
            }
            kind == SourceKind.BLUETOOTH -> selectBluetooth(a, pkg)
            else -> {
                NetRadio.stop(a)
                NowPlaying.preferredPkg = pkg
                if (HwRadio.wanted) HwRadio.powerOff(a, keep = pkg)
                pauseOthers(keep = pkg)
                val s = NowPlaying.sessions.firstOrNull { it.packageName == pkg }
                if (s != null) {
                    NowPlaying.read(s)
                    runCatching { s.transportControls.play() }
                    return
                }
                val failMsg = "Fikk ikke startet musikken. Trykk «Åpne app»."
                val viaBrowser = MediaConnect.play(a, pkg)
                if (!viaBrowser && !MediaConnect.mediaButton(a, pkg, KeyEvent.KEYCODE_MEDIA_PLAY)) { Notify.show(failMsg, long = true); return }
                // Spiller den ikke etter 3 s: prøv medieknappen (hvis ikke brukt), ellers si fra
                main.postDelayed({
                    if (NowPlaying.preferredPkg != pkg) return@postDelayed
                    if (NowPlaying.sessions.any { it.packageName == pkg && isPlayingState(it.playbackState?.state) }) return@postDelayed
                    if (!(viaBrowser && MediaConnect.mediaButton(a, pkg, KeyEvent.KEYCODE_MEDIA_PLAY))) Notify.show(failMsg, long = true)
                }, 3000)
            }
        }
    }

    /**
     * Lyd fra telefonen over Bluetooth («Bluetooth-lyd fra telefonen»): andre kilder pauses (ikke telefonens egne
     * Bluetooth-økter), FM-radio og nettradio slås av, og Bluetooth-lyden startes ([BtAudio]).
     */
    fun selectBluetooth(c: Context, pkg: String? = null) {
        val a = c.applicationContext
        NetRadio.stop(a)
        val p = pkg ?: runCatching { bluetoothPackage(a) }.getOrNull()
        NowPlaying.preferredPkg = p
        if (HwRadio.wanted) HwRadio.powerOff(a, keep = p)
        pauseOthers(keep = p, keepKind = SourceKind.BLUETOOTH)
        BtAudio.activate(a, p)
    }

    /**
     * Pause det som spiller i andre apper (så to kilder ikke spiller samtidig). Beholdes: [keep], alle økter av typen
     * [keepKind] (f.eks. alle Bluetooth-økter når telefonen er valgt), og økter som spiller på en annen enhet
     * (Spotify Connect – pause der ville stoppet musikken på telefonen).
     */
    private fun pauseOthers(keep: String?, keepKind: SourceKind? = null) {
        val radio = Prefs.str("radio_pkg"); val bt = Prefs.str("btmusic_pkg")
        NowPlaying.sessions.forEach { s ->
            val p = s.packageName ?: return@forEach
            if (shouldPauseOnSwitch(p, keep, keepKind, sourceKind(p, radio, bt), isPlayingState(s.playbackState?.state), isRemote(s)))
                runCatching { s.transportControls.pause() }
        }
    }

    /** Neste/forrige på FM-radioen når den styres via MCU (uten mediaøkt). */
    private fun fmWithoutSession() = HwRadio.wanted && HwRadio.mcuActive && NowPlaying.pkg != HwRadio.enginePkg

    private fun key(context: Context, code: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    fun playPause(c: Context) {
        if (NetRadio.active) { NetRadio.toggle(c); return }
        val t = NowPlaying.controller?.transportControls
        if (t != null) { if (NowPlaying.playing) t.pause() else t.play() } else key(c, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }
    fun play(c: Context) {
        NowPlaying.controller?.transportControls?.play() ?: key(c, KeyEvent.KEYCODE_MEDIA_PLAY)
    }
    fun next(c: Context) {
        if (NetRadio.active) { NetRadio.step(c, 1); return }
        if (fmWithoutSession()) { HwRadio.seekUp(); return }
        NowPlaying.controller?.transportControls?.skipToNext() ?: key(c, KeyEvent.KEYCODE_MEDIA_NEXT)
    }
    fun previous(c: Context) {
        if (NetRadio.active) { NetRadio.step(c, -1); return }
        if (fmWithoutSession()) { HwRadio.seekDown(); return }
        NowPlaying.controller?.transportControls?.skipToPrevious() ?: key(c, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }
}

/* ======================= Kommer lyden ut av stereoen? ======================= */

/**
 * Når en app begynner å spille, sjekker T'OS at lyden faktisk kommer ut av høyttalerne:
 *  - spiller den på en annen enhet (Spotify Connect: telefonen, en annen stereo …), sies det fra hvordan du velger stereoen,
 *  - har stereoens FM-radio fortsatt lydkanalen (slått på i T'OS), slås den av,
 *  - er musikkvolumet skrudd helt ned eller av, sies det fra.
 * Hver melding høyst én gang i minuttet.
 */
object PlaybackWatch {
    private val shown = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun due(key: String, gapMs: Long = 60_000): Boolean {
        val now = SystemClock.elapsedRealtime()
        val last = shown[key]
        if (last != null && now - last < gapMs) return false
        shown[key] = now
        return true
    }

    private fun label(c: Context, pkg: String): String = runCatching {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    fun started(c: Context, mc: MediaController) {
        val pkg = mc.packageName ?: return
        if (pkg == c.packageName) return
        if (isRemote(mc)) {
            if (due("annen:$pkg")) Notify.show(remotePlaybackMessage(pkg, label(c, pkg)), long = true)
            return
        }
        if (shouldYieldRadio(RadioGuard.looksLikeRadio(pkg), HwRadio.isEngine(pkg), HwRadio.wanted, HwRadio.msSinceOn())) HwRadio.yieldTo(c, pkg)
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val level = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
        val muted = runCatching { am.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrDefault(false)
        val fixed = runCatching { am.isVolumeFixed }.getOrDefault(false)
        volumeOffNotice(level, muted, fixed)?.let { if (due("volum")) Notify.show(it, long = true) }
    }
}

/** Melding når appen spiller på en annen enhet. Testes i BtLogicTest. */
internal fun remotePlaybackMessage(pkg: String, label: String): String =
    if (pkg == "com.spotify.music" || label.equals("Spotify", true))
        "Spotify spiller på en annen enhet. Åpne Spotify → Enheter-ikonet → velg denne stereoen."
    else "$label spiller på en annen enhet. Velg denne stereoen som avspiller i $label."

/** Melding når musikken starter med volumet av (null = volumet er greit, eller stereoen styrer det selv). Testes i BtLogicTest. */
internal fun volumeOffNotice(level: Int, muted: Boolean, fixed: Boolean): String? =
    if (!fixed && (muted || level == 0)) "Volumet er av. Skru opp med volumknappen." else null

/**
 * FM-radioen (slått på i T'OS) skal slippe lydkanalen når en annen app begynner å spille – men ikke i de første
 * sekundene etter at radioen ble slått på (gamle økter kan melde «spiller» et øyeblikk). Testes i BtLogicTest.
 */
internal fun shouldYieldRadio(isRadio: Boolean, isEngine: Boolean, radioWanted: Boolean, msSinceOn: Long): Boolean =
    radioWanted && !isRadio && !isEngine && msSinceOn > 3000

/* ======================= Varseltjenesten ======================= */

/**
 * Tillatelsen «Varseltilgang» gir T'OS lov til å se hva som spiller (tittel, cover),
 * neste sving fra kartappen og hvem som ringer. Ingenting sendes noe sted.
 */
class MediaListener : NotificationListenerService() {
    private var msm: MediaSessionManager? = null
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()
    /** Appene som har mediaøkt nå. Tilbakekallene leser denne, så de aldri bruker en gammel liste. */
    private var controllers: List<MediaController> = emptyList()
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list.orEmpty()) }
    /** Spilte appen sist vi så (pakke → spiller)? For å merke når noe *begynner* å spille ([PlaybackWatch]). */
    private val wasPlaying = mutableMapOf<String, Boolean>()

    override fun onListenerConnected() {
        // Det som var vist før en frakobling kan være utdatert – leses på nytt fra varslene under
        NavInfo.title = null; NavInfo.text = null; Call.clear()
        msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val cn = ComponentName(this, MediaListener::class.java)
        runCatching {
            msm?.addOnActiveSessionsChangedListener(sessionsListener, cn)
            pick(msm?.getActiveSessions(cn).orEmpty())
        }
        runCatching { activeNotifications.forEach { onNotificationPosted(it) } }
    }

    override fun onListenerDisconnected() {
        runCatching { msm?.removeOnActiveSessionsChangedListener(sessionsListener) }
        callbacks.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        callbacks.clear()
        controllers = emptyList()
        NowPlaying.sessions = emptyList()
        wasPlaying.clear()
        BtLink.clearNotes()
        // Ingen gammel spiller igjen: musikkknappene bruker da vanlige mediaknapper i stedet for en død økt
        NowPlaying.read(null)
        // Uten varseltilgang får vi ikke vite når sving/samtale er over – ikke vis gamle ting
        NavInfo.title = null; NavInfo.text = null; Call.clear()
    }

    /** Velg appen som spiller; ellers den første som har en økt. */
    private fun pick(list: List<MediaController>) {
        callbacks.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        callbacks.clear()
        controllers = list
        NowPlaying.sessions = list
        list.forEach { c ->
            watch(c)
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) { watch(c); choose() }
                // Spotify byttet avspiller (Spotify Connect) mens musikken spiller: si fra med en gang
                override fun onAudioInfoChanged(info: MediaController.PlaybackInfo?) {
                    if (isRemote(c) && isPlayingState(runCatching { c.playbackState?.state }.getOrNull()))
                        runCatching { PlaybackWatch.started(applicationContext, c) }
                    choose()
                }
                override fun onMetadataChanged(metadata: MediaMetadata?) { choose() }
                override fun onSessionDestroyed() {
                    c.packageName?.let { wasPlaying.remove(it) }
                    controllers = controllers.filter { it !== c }
                    NowPlaying.sessions = controllers
                    callbacks.remove(c)?.let { runCatching { c.unregisterCallback(it) } }
                    choose()
                }
            }
            c.registerCallback(cb); callbacks[c] = cb
        }
        choose()
    }

    /** Begynte [c] å spille nå? Da sjekkes lyden (annen enhet, volum av, FM-radio som fortsatt har kanalen). */
    private fun watch(c: MediaController) {
        val p = c.packageName ?: return
        val now = isPlayingState(runCatching { c.playbackState?.state }.getOrNull())
        val before = wasPlaying.put(p, now) ?: false
        if (now && !before) runCatching { PlaybackWatch.started(applicationContext, c) }
    }

    /**
     * Spiller flere, eller ingen: den som spiller vinner. Ellers beholdes den vi viste (en sang som bytter eller
     * laster et øyeblikk skal ikke gjøre at en annen app, f.eks. en pauset YouTube, tar over musikkknappene).
     */
    private fun choose() {
        val list = controllers
        val pref = NowPlaying.preferredPkg
        val i = pickSession(list.map { isPlayingState(it.playbackState?.state) }, list.indexOfFirst { it === NowPlaying.controller },
            if (pref == null) -1 else list.indexOfFirst { it.packageName == pref })
        NowPlaying.read(list.getOrNull(i))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        // Stereoens BT-app sier ofte «Tilkoblet: iPhone» i et varsel – det er der T'OS ser telefonen
        runCatching { BtLink.onNotification(sbn.packageName, sbn.key, title, text) }
        when {
            sbn.packageName in navApps && sbn.isOngoing -> { NavInfo.title = title; NavInfo.text = text }
            n.category == Notification.CATEGORY_CALL -> {
                val type = if (android.os.Build.VERSION.SDK_INT >= 31) ex.getInt("android.callType", 0) else 0   // Notification.EXTRA_CALL_TYPE (CallStyle, Android 12+)
                val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
                if (isIncomingCall(type, n.fullScreenIntent != null, (n.flags and Notification.FLAG_ONGOING_EVENT) != 0, chrono)) {
                    Call.name = title ?: "Ukjent"; Call.open = n.contentIntent; Call.key = sbn.key; Call.setActions(n)
                } else if (Call.key == null || Call.key == sbn.key) {
                    Call.clear()   // samtalen er besvart (pågående) eller er en utgående samtale
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        runCatching { BtLink.onNotificationRemoved(sbn.key) }
        if (sbn.packageName in navApps && sbn.isOngoing) { NavInfo.title = null; NavInfo.text = null }
        if (sbn.key == Call.key || (Call.key == null && sbn.notification?.category == Notification.CATEGORY_CALL)) Call.clear()
    }

    companion object {
        val navApps = setOf("com.google.android.apps.maps", "com.waze")

        fun enabled(c: Context): Boolean =
            Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners")?.contains(c.packageName) == true

        /** Mediaøktene til [pkg] akkurat nå (krever varseltilgang). */
        internal fun sessionsOf(c: Context, pkg: String): List<MediaController> = runCatching {
            (c.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager)
                .getActiveSessions(ComponentName(c, MediaListener::class.java)).filter { it.packageName == pkg }
        }.getOrDefault(emptyList())

        /** Stopp avspilling i en bestemt app (f.eks. radio-appen som startet av seg selv). */
        fun stopSessionsOf(c: Context, pkg: String) = sessionsOf(c, pkg).forEach { runCatching { it.transportControls.pause(); it.transportControls.stop() } }

        /** Start avspilling igjen i en bestemt app. */
        fun playSessionOf(c: Context, pkg: String) = sessionsOf(c, pkg).firstOrNull()?.let { runCatching { it.transportControls.play() } }
    }
}

/* ======================= Starte avspilling uten å vise appen ======================= */

/**
 * Snakker med en musikkapp i bakgrunnen, uten å åpne skjermbildet: via appens mediatjeneste (MediaBrowserService –
 * den samme Android Auto bruker; Androids Bluetooth-del har en for Bluetooth-musikk) eller ved å sende
 * medieknappen «spill» rett til appen.
 */
object MediaConnect {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Appens mediatjeneste, hvis den har en (og den er slått på). */
    fun browserService(c: Context, pkg: String): ComponentName? = runCatching {
        c.packageManager.queryIntentServices(android.content.Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0)
            .firstOrNull()?.serviceInfo?.let { ComponentName(it.packageName, it.name) }
    }.getOrNull()

    /** Appens mottakere for medieknapper. */
    fun buttonReceivers(c: Context, pkg: String): List<ComponentName> = runCatching {
        c.packageManager.queryBroadcastReceivers(android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON).setPackage(pkg), 0)
            .mapNotNull { it.activityInfo }.map { ComponentName(it.packageName, it.name) }
    }.getOrDefault(emptyList())

    /** Send en medieknapp (trykk + slipp) rett til appen. Gir false hvis appen ikke tar imot medieknapper. */
    fun mediaButton(c: Context, pkg: String, code: Int): Boolean {
        val rs = buttonReceivers(c, pkg)
        if (rs.isEmpty()) return false
        val now = SystemClock.uptimeMillis()
        for (cn in rs) for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) runCatching {
            c.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON).setComponent(cn)
                .putExtra(android.content.Intent.EXTRA_KEY_EVENT, KeyEvent(now, now, action, code, 0)))
        }
        return true
    }

    /**
     * Koble til appens mediatjeneste og trykk «spill». Gir false hvis appen ikke har en.
     * Tilkoblingen slippes etter 8 s (da har appen startet sin egen mediaøkt, som varseltjenesten ser).
     */
    fun play(c: Context, pkg: String): Boolean {
        val cn = browserService(c, pkg) ?: return false
        val a = c.applicationContext
        main.post {
            var browser: android.media.browse.MediaBrowser? = null
            val cb = object : android.media.browse.MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    val b = browser ?: return
                    runCatching { MediaController(a, b.sessionToken).transportControls.play() }
                    main.postDelayed({ runCatching { b.disconnect() } }, 8000)
                }
                override fun onConnectionFailed() { runCatching { browser?.disconnect() } }
            }
            browser = runCatching { android.media.browse.MediaBrowser(a, cn, cb, null) }.getOrNull()
            runCatching { browser?.connect() }
        }
        return true
    }
}
