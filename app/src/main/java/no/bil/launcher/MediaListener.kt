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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/* ======================= Det som spiller nå ======================= */

object NowPlaying {
    var title by mutableStateOf<String?>(null)
    var artist by mutableStateOf<String?>(null)
    var art by mutableStateOf<Bitmap?>(null)
    var playing by mutableStateOf(false)
    var pkg by mutableStateOf<String?>(null)
    var durationMs by mutableStateOf(0L)
    private var posMs = 0L
    private var posAt = 0L
    private var speed = 1f
    internal var controller: MediaController? = null

    val hasSession get() = controller != null

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
        val st = c?.playbackState
        playing = st?.state == PlaybackState.STATE_PLAYING || st?.state == PlaybackState.STATE_BUFFERING
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
    fun openCall() { runCatching { open?.send() } }
    internal fun clear() { name = null; open = null; key = null }
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

/* ======================= Musikkontroll ======================= */

/** Styrer musikken: nettradio i T'OS, ellers appen som spiller, ellers vanlige mediaknapper. */
object Media {
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
        NowPlaying.controller?.transportControls?.skipToNext() ?: key(c, KeyEvent.KEYCODE_MEDIA_NEXT)
    }
    fun previous(c: Context) {
        if (NetRadio.active) { NetRadio.step(c, -1); return }
        NowPlaying.controller?.transportControls?.skipToPrevious() ?: key(c, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }
}

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
        // Uten varseltilgang får vi ikke vite når sving/samtale er over – ikke vis gamle ting
        NavInfo.title = null; NavInfo.text = null; Call.clear()
    }

    /** Velg appen som spiller; ellers den første som har en økt. */
    private fun pick(list: List<MediaController>) {
        callbacks.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        callbacks.clear()
        controllers = list
        list.forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) { choose() }
                override fun onMetadataChanged(metadata: MediaMetadata?) { choose() }
                override fun onSessionDestroyed() {
                    controllers = controllers.filter { it !== c }
                    callbacks.remove(c)?.let { runCatching { c.unregisterCallback(it) } }
                    choose()
                }
            }
            c.registerCallback(cb); callbacks[c] = cb
        }
        choose()
    }

    private fun choose() {
        val list = controllers
        val c = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        NowPlaying.read(c)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        when {
            sbn.packageName in navApps && sbn.isOngoing -> { NavInfo.title = title; NavInfo.text = text }
            n.category == Notification.CATEGORY_CALL -> {
                val type = if (android.os.Build.VERSION.SDK_INT >= 31) ex.getInt("android.callType", 0) else 0   // Notification.EXTRA_CALL_TYPE (CallStyle, Android 12+)
                val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
                if (isIncomingCall(type, n.fullScreenIntent != null, (n.flags and Notification.FLAG_ONGOING_EVENT) != 0, chrono)) {
                    Call.name = title ?: "Ukjent"; Call.open = n.contentIntent; Call.key = sbn.key
                } else if (Call.key == null || Call.key == sbn.key) {
                    Call.clear()   // samtalen er besvart (pågående) eller er en utgående samtale
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in navApps && sbn.isOngoing) { NavInfo.title = null; NavInfo.text = null }
        if (sbn.key == Call.key || (Call.key == null && sbn.notification?.category == Notification.CATEGORY_CALL)) Call.clear()
    }

    companion object {
        val navApps = setOf("com.google.android.apps.maps", "com.waze")

        fun enabled(c: Context): Boolean =
            Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners")?.contains(c.packageName) == true

        private fun sessionsOf(c: Context, pkg: String): List<MediaController> = runCatching {
            (c.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager)
                .getActiveSessions(ComponentName(c, MediaListener::class.java)).filter { it.packageName == pkg }
        }.getOrDefault(emptyList())

        /** Stopp avspilling i en bestemt app (f.eks. radio-appen som startet av seg selv). */
        fun stopSessionsOf(c: Context, pkg: String) = sessionsOf(c, pkg).forEach { runCatching { it.transportControls.pause(); it.transportControls.stop() } }

        /** Start avspilling igjen i en bestemt app. */
        fun playSessionOf(c: Context, pkg: String) = sessionsOf(c, pkg).firstOrNull()?.let { runCatching { it.transportControls.play() } }
    }
}
