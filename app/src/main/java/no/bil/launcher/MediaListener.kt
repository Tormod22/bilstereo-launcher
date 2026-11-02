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
        if (pkg != null) Prefs.setQuiet("last_player", pkg!!)
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
    fun openCall() { runCatching { open?.send() } }
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
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list.orEmpty()) }

    override fun onListenerConnected() {
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
        callbacks.forEach { (c, cb) -> c.unregisterCallback(cb) }
        callbacks.clear()
    }

    /** Velg appen som spiller; ellers den første som har en økt. */
    private fun pick(list: List<MediaController>) {
        callbacks.forEach { (c, cb) -> c.unregisterCallback(cb) }
        callbacks.clear()
        list.forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) { choose(list) }
                override fun onMetadataChanged(metadata: MediaMetadata?) { choose(list) }
                override fun onSessionDestroyed() { choose(list - c) }
            }
            c.registerCallback(cb); callbacks[c] = cb
        }
        choose(list)
    }

    private fun choose(list: List<MediaController>) {
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
            n.category == Notification.CATEGORY_CALL -> { Call.name = title ?: "Ukjent"; Call.open = n.contentIntent }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName in navApps && sbn.isOngoing) { NavInfo.title = null; NavInfo.text = null }
        if (sbn.notification?.category == Notification.CATEGORY_CALL) { Call.name = null; Call.open = null }
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
