package no.bil.launcher

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import java.lang.ref.WeakReference
import java.util.Locale

/* ======================= Volum i T'OS-stil ======================= */

/**
 * Lydvolumet slik T'OS viser det – i stedet for stereoens egen volumlinje. Volumknappene fanges av
 * knappestyringen ([KeyService]) og endres her uten at Android viser sitt panel. Skjermen i T'OS leser
 * [level]/[max]/[muted] og viser linja i [SHOW_MS] etter [shownAt]. Når T'OS ikke er framme, viser
 * [TosOverlay] en egen linje over andre apper.
 */
object HwVolume {
    var level by mutableIntStateOf(0)
        private set
    var max by mutableIntStateOf(15)
        private set
    var muted by mutableStateOf(false)
        private set
    /** Lydstrømmen som ble endret (STREAM_MUSIC, eller STREAM_VOICE_CALL under en samtale). */
    var stream by mutableIntStateOf(AudioManager.STREAM_MUSIC)
        private set
    /** Når volumet sist ble endret (elapsedRealtime, 0 = aldri). Linja vises i [SHOW_MS] etter dette. */
    var shownAt by mutableLongStateOf(0L)
        private set

    const val SHOW_MS = 1500L

    /** «T'OS styrer volumknappene» (av = stereoen/Android bestemmer selv). */
    val enabled get() = Prefs.bool("tos_volume", true)

    @Volatile private var ownAt = 0L
    /** Kall dette før T'OS selv endrer volumet (f.eks. med en glidebryter), så linja ikke spretter opp. */
    fun markOwnChange() { ownAt = SystemClock.elapsedRealtime() }
    internal fun ownRecently(now: Long = SystemClock.elapsedRealtime()) = now - ownAt < 700

    private fun am(c: Context) = c.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Lydstrømmen volumknappene skal styre akkurat nå. */
    fun activeStream(c: Context): Int {
        val am = am(c)
        return volumeStream(am.mode, runCatching { am.isMusicActive }.getOrDefault(false))
    }

    /** Kan T'OS styre volumet selv? (Noen stereoer har fast volum i Android og styrer forsterkeren direkte.) */
    fun controllable(c: Context): Boolean = !runCatching { am(c).isVolumeFixed }.getOrDefault(false)

    /**
     * Ett steg opp ([dir] > 0), ned ([dir] < 0) eller lyd av/på ([dir] = 0) – uten Androids volumpanel (flagg 0).
     * Gir false hvis volumet ikke kunne endres.
     */
    fun step(c: Context, dir: Int): Boolean {
        val am = am(c)
        val s = activeStream(c)
        val adj = when {
            dir > 0 -> AudioManager.ADJUST_RAISE
            dir < 0 -> AudioManager.ADJUST_LOWER
            else -> AudioManager.ADJUST_TOGGLE_MUTE
        }
        markOwnChange()
        if (runCatching { am.adjustStreamVolume(s, adj, 0) }.isFailure) return false
        refresh(c, s, show = true)
        return true
    }

    /** Les volumet på nytt (f.eks. etter at volumhjulet på stereoen er brukt), og vis linja hvis [show]. */
    fun refresh(c: Context, s: Int = activeStream(c), show: Boolean = true) {
        val am = am(c)
        level = runCatching { am.getStreamVolume(s) }.getOrDefault(level)
        max = runCatching { am.getStreamMaxVolume(s) }.getOrDefault(max).coerceAtLeast(1)
        muted = runCatching { am.isStreamMute(s) }.getOrDefault(false)
        stream = s
        if (!show) return
        shownAt = SystemClock.elapsedRealtime()
        if (!TosOverlay.appShown) TosOverlay.volume(c, level, max, muted, s == AudioManager.STREAM_VOICE_CALL)
    }
}

/**
 * Hvilken lydstrøm volumknappene styrer: samtalevolum under en samtale, ringevolum mens det ringer, ellers musikk.
 * Holder CarPlay «kommunikasjonsmodus» mens musikk spiller, er det fortsatt musikkvolumet. Testes i ServiceLogicTest3.
 */
internal fun volumeStream(mode: Int, musicActive: Boolean): Int = when (mode) {
    AudioManager.MODE_IN_CALL -> AudioManager.STREAM_VOICE_CALL
    AudioManager.MODE_IN_COMMUNICATION -> if (musicActive) AudioManager.STREAM_MUSIC else AudioManager.STREAM_VOICE_CALL
    AudioManager.MODE_RINGTONE -> AudioManager.STREAM_RING
    else -> AudioManager.STREAM_MUSIC
}

/** Hvor stor del av linja som er fylt (0–1). Testes i ServiceLogicTest3. */
internal fun volumeFraction(level: Int, max: Int, muted: Boolean): Float =
    if (muted || max <= 0) 0f else (level.toFloat() / max).coerceIn(0f, 1f)

/* ======================= T'OS sine overlegg over andre apper ======================= */

/**
 * Små T'OS-vinduer over andre apper (CarPlay, kart …), så stereoens egne vinduer ikke trengs:
 *  - volumlinje (mørkt kort øverst, med linje og tall – forsvinner etter 1,5 s),
 *  - korte meldinger fra T'OS ([Notify]) når T'OS ikke er framme,
 *  - «teppe»: svart skjerm et øyeblikk mens T'OS bytter bort fra stereoens egen startskjerm eller radio-app,
 *    så de ikke blinker fram.
 * Tegnes via knappestyringen (tilgjengelighetstjenesten), ellers via «Vis over andre apper». Uten noen av dem vises
 * ingenting (T'OS sin egen skjerm viser volum og meldinger når den er framme).
 * Bruker bare vanlige Android-visninger, så knappestyringen ikke tror at T'OS selv kom fram.
 */
internal object TosOverlay {
    private val h = Handler(Looper.getMainLooper())

    /** Knappestyringen så sist at T'OS sin egen skjerm var framme (reserve hvis [Notify.appVisible] ikke er satt). */
    @Volatile var tosFront = false
    /** Er T'OS sin egen skjerm synlig? Da viser T'OS volum og meldinger selv, og overleggene trengs ikke. */
    val appShown get() = Notify.appVisible || tosFront

    /** Et vindu som er lagt over skjermen. Svak referanse: vinduet holdes av Android så lenge det vises. */
    private class Slot(val wm: WindowManager, view: View) {
        val ref = WeakReference(view)
        fun remove() { ref.get()?.let { v -> runCatching { wm.removeView(v) } } }
    }

    private var vol: Slot? = null
    private var volFill: WeakReference<View>? = null
    private var volNum: WeakReference<TextView>? = null
    private var volTitle: WeakReference<TextView>? = null
    private var notice: Slot? = null
    private var cover: Slot? = null
    private var coverArmed = false
    private val hideVol = Runnable { vol?.remove(); vol = null }
    private val hideNotice = Runnable { notice?.remove(); notice = null }
    private val hideCover = Runnable { cover?.remove(); cover = null; coverArmed = false }

    private const val TRACK_DP = 260f
    private const val TITLE_MUSIC = "Volum"
    private const val TITLE_CALL = "Samtale"
    private const val TITLE_MUTED = "Lyd av"

    /** Hvor vinduet kan tegnes: knappestyringen (ingen egen tillatelse), ellers «Vis over andre apper». */
    internal fun host(c: Context): Pair<Context, Int>? {
        val svc = KeyService.instance
        return when {
            svc != null -> svc to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Settings.canDrawOverlays(c) -> c.applicationContext to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> null
        }
    }

    internal fun dp(c: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics).toInt()

    internal fun card(ctx: Context, radius: Float) = GradientDrawable().apply {
        setColor(Card.copy(alpha = 0.96f).toArgb()); cornerRadius = dp(ctx, radius).toFloat(); setStroke(dp(ctx, 1f), Line.toArgb())
    }

    private fun params(type: Int, w: Int, hgt: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, hgt, type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    )

    private fun add(wm: WindowManager, v: View, lp: WindowManager.LayoutParams): Boolean = runCatching { wm.addView(v, lp) }.isSuccess

    /* ---------- Volum ---------- */

    fun volume(c: Context, level: Int, max: Int, muted: Boolean, call: Boolean) {
        if (FytMain.stayOut) return           // bilen rygger (FYT): ingenting over kamerabildet
        h.post { showVolume(c, level, max, muted, call) }
    }

    private fun showVolume(c: Context, level: Int, max: Int, muted: Boolean, call: Boolean) {
        val title = when { muted -> TITLE_MUTED; call -> TITLE_CALL; else -> TITLE_MUSIC }
        val number = String.format(Locale.getDefault(), "%d", if (muted) 0 else level)
        val fraction = volumeFraction(level, max, muted)
        val existing = vol?.ref?.get()
        val fill = volFill?.get(); val num = volNum?.get(); val t = volTitle?.get()
        if (existing != null && existing.isAttachedToWindow && fill != null && num != null && t != null) {
            t.text = title; num.text = number
            fill.layoutParams = fill.layoutParams.apply { width = (dp(existing.context, TRACK_DP) * fraction).toInt() }
        } else {
            vol?.remove(); vol = null
            val (ctx, type) = host(c) ?: return
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val fg = TextPrimary.toArgb()
            val tv = TextView(ctx).apply { text = title; setTextColor(TextMuted.toArgb()); textSize = 15f }
            val fl = View(ctx).apply { background = GradientDrawable().apply { setColor(fg); cornerRadius = dp(ctx, 4f).toFloat() } }
            val track = FrameLayout(ctx).apply {
                background = GradientDrawable().apply { setColor(Line.toArgb()); cornerRadius = dp(ctx, 4f).toFloat() }
                addView(fl, FrameLayout.LayoutParams((dp(ctx, TRACK_DP) * fraction).toInt(), FrameLayout.LayoutParams.MATCH_PARENT))
            }
            val nv = TextView(ctx).apply { text = number; setTextColor(fg); textSize = 22f; gravity = Gravity.END }
            val view = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                setPadding(dp(ctx, 22f), dp(ctx, 14f), dp(ctx, 22f), dp(ctx, 14f))
                background = card(ctx, 22f)
                elevation = dp(ctx, 8f).toFloat()
                addView(tv, LinearLayout.LayoutParams(dp(ctx, 76f), LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(track, LinearLayout.LayoutParams(dp(ctx, TRACK_DP), dp(ctx, 8f)))
                addView(nv, LinearLayout.LayoutParams(dp(ctx, 48f), LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            val lp = params(type, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchable = false).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(ctx, 20f)
            }
            if (!add(wm, view, lp)) return
            vol = Slot(wm, view)
            volFill = WeakReference(fl); volNum = WeakReference(nv); volTitle = WeakReference(tv)
        }
        h.removeCallbacks(hideVol)
        h.postDelayed(hideVol, HwVolume.SHOW_MS)
    }

    /* ---------- Meldinger ---------- */

    fun notice(c: Context, text: String, long: Boolean) {
        if (FytMain.stayOut) return
        h.post {
            notice?.remove(); notice = null
            h.removeCallbacks(hideNotice)
            val (ctx, type) = host(c) ?: return@post
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = TextView(ctx).apply {
                this.text = text
                setTextColor(TextPrimary.toArgb()); textSize = 17f
                maxWidth = dp(ctx, 560f)
                setPadding(dp(ctx, 24f), dp(ctx, 14f), dp(ctx, 24f), dp(ctx, 14f))
                background = card(ctx, 24f)
                elevation = dp(ctx, 8f).toFloat()
                alpha = 0f
            }
            val lp = params(type, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchable = true).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                // Under volumlinja hvis den vises samtidig
                y = dp(ctx, if (vol?.ref?.get() != null) 96f else 20f)
            }
            view.setOnClickListener { hideNoticeNow() }
            if (!add(wm, view, lp)) return@post
            notice = Slot(wm, view)
            view.animate().alpha(1f).setDuration(150).start()
            h.postDelayed(hideNotice, if (long) 5_000 else 2_800)
        }
    }

    fun hideNoticeNow() = h.post { h.removeCallbacks(hideNotice); hideNotice.run() }

    /* ---------- Teppe ---------- */

    /**
     * Svart T'OS-teppe over hele skjermen i høyst [maxMs] (slipper berøring gjennom). Brukes et øyeblikk mens T'OS
     * hentes fram, så stereoens egne skjermbilder ikke synes. Fjernes når T'OS er framme ([armUncover] + [tosShown]).
     */
    fun cover(c: Context, text: String? = null, maxMs: Long = 1200) {
        if (FytMain.stayOut) return
        h.post {
            h.removeCallbacks(hideCover)
            if (cover?.ref?.get() == null) {
                val (ctx, type) = host(c) ?: return@post
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val view = FrameLayout(ctx).apply {
                    setBackgroundColor(Bg.toArgb())
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    if (text != null) addView(TextView(ctx).apply { this.text = text; setTextColor(TextMuted.toArgb()); textSize = 18f },
                        FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                }
                val lp = params(type, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, touchable = false).apply {
                    flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                }
                if (!add(wm, view, lp)) return@post
                cover = Slot(wm, view)
                coverArmed = false
            }
            // Fast øvre grense: et teppe som henger igjen ville gjort skjermen svart
            h.postDelayed(hideCover, maxMs.coerceIn(200, 2500))
        }
    }

    /** Fjern teppet så snart T'OS er framme (neste [tosShown]). */
    fun armUncover() = h.post { coverArmed = true }

    fun uncover() = h.post { h.removeCallbacks(hideCover); hideCover.run() }

    /**
     * T'OS sin skjerm kom fram: T'OS viser volum og meldinger selv, så overleggene fjernes. Teppet tas bort litt
     * etter (T'OS må rekke å tegne seg først).
     */
    fun tosShown() = h.post {
        h.removeCallbacks(hideVol); hideVol.run()
        h.removeCallbacks(hideNotice); hideNotice.run()
        if (coverArmed && cover != null) { h.removeCallbacks(hideCover); h.postDelayed(hideCover, 150) }
    }

    fun hideAll() = h.post {
        h.removeCallbacks(hideVol); h.removeCallbacks(hideNotice); h.removeCallbacks(hideCover)
        hideVol.run(); hideNotice.run(); hideCover.run()
    }
}
