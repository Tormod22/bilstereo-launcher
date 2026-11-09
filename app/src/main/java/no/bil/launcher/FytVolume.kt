package no.bil.launcher

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import java.lang.ref.WeakReference
import java.util.Locale

/* ======================= Volumet på FYT-stereoen ======================= */

/**
 * Kartleggingen 8. nov (opptaket): volumhjulet på FYT-stereoen endrer IKKE Androids lydstrømmer (musikk stod på 0/25 hele
 * tiden), men FYT sitt eget volum (lydmodulen i com.syu.ms, kode 2: 15 → 14 … 3). com.syu.ms viser da sitt eget lille
 * vindu («Popup Window», pakke com.syu.ms, 298,80 428×62 på 1024×600, teksten er tallet, f.eks. «13») i 3–5 s.
 *
 * T'OS legger sin egen volumlinje (mørkt kort, linje og stort tall) akkurat over det vinduet, som et
 * tilgjengelighetsvindu (ligger over alle appvinduer, også FYT sitt). Linja står til 2 s etter siste endring, og minst
 * så lenge FYT-vinduet finnes (følges med vinduslista). Gjelder også når T'OS er framme – FYT-vinduet ligger over T'OS.
 * Bryter: «Volum i T'OS-stil» (tos_volume).
 */
internal object FytVolumeLogic {
    /** Høyeste FYT-volum er ukjent (sett: 15 og nedover). Vanlig på FYT er 40; det høyeste tallet vi har sett, vinner. */
    const val DEFAULT_MAX = 40
    const val HIDE_MS = 2000L
    /** Aldri lenger enn dette etter siste endring (om vinduslista skulle henge). */
    const val MAX_SHOW_MS = 10_000L
    /** Så lenge etter en FYT-endring regnes et ukjent «Popup Window» som FYT sitt. */
    const val RECENT_MS = 2500L
    const val POPUP_TITLE = "popup window"

    /** Der FYT-vinduet lå i kartleggingen (skjerm 1024×600). */
    val DEFAULT_POPUP = Box(298, 80, 428, 62)
    private const val REF_W = 1024
    private const val REF_H = 600

    data class Box(val x: Int, val y: Int, val w: Int, val h: Int) {
        val right get() = x + w
        val bottom get() = y + h
        fun contains(o: Box) = o.x >= x && o.y >= y && o.right <= right && o.bottom <= bottom
    }

    /** Høyeste volum: brukerens valg (minst det vi har sett), ellers 40 eller det høyeste vi har sett. */
    fun maxVolume(seen: Int, override: Int): Int = if (override > 0) maxOf(override, seen) else maxOf(DEFAULT_MAX, seen)

    /** Er dette FYT sitt volumvindu? Lite appvindu fra com.syu.ms (eller uten pakkenavn, med tittelen, like etter en endring). */
    fun isFytPopup(w: WinSnap, screenW: Int, screenH: Int, fytRecent: Boolean): Boolean {
        if (w.type != ShadeLogic.TYPE_APPLICATION || w.width <= 0 || w.height <= 0) return false
        if (screenH > 0 && w.height > screenH * 0.5f) return false
        if (screenW > 0 && w.width >= screenW) return false
        val titled = w.title?.trim()?.lowercase() == POPUP_TITLE
        return when (w.pkg) {
            Fyt.PKG -> true
            null -> titled && fytRecent
            else -> false
        }
    }

    /** Tilgjengelighetshendelse fra FYT-vinduet (pakke com.syu.ms, vanlig visningsklasse – ikke en egen skjerm). */
    fun isPopupEvent(pkg: String?, cls: String?): Boolean = pkg == Fyt.PKG && (cls == null || cls.startsWith("android."))

    /** Tallet FYT-vinduet viser («13»). */
    fun levelFromText(texts: List<String>): Int? = texts.firstNotNullOfOrNull { it.trim().toIntOrNull()?.takeIf { v -> v in 0..100 } }

    /** FYT-vinduet der det er, ellers standardplassen skalert til skjermen. */
    fun popupBox(popup: WinSnap?, screenW: Int, screenH: Int): Box {
        if (popup != null && popup.width > 0 && popup.height > 0) return Box(popup.left, popup.top, popup.width, popup.height)
        if (screenW <= 0 || screenH <= 0 || (screenW == REF_W && screenH == REF_H)) return DEFAULT_POPUP
        val sx = screenW.toFloat() / REF_W; val sy = screenH.toFloat() / REF_H
        val d = DEFAULT_POPUP
        return Box((d.x * sx).toInt(), (d.y * sy).toInt(), (d.w * sx).toInt() + 1, (d.h * sy).toInt() + 1)
    }

    /**
     * T'OS-kortet: dekker FYT-vinduet helt ([margin] ekstra på alle kanter, minst [minW]×[minH], sentrert over det),
     * og holdes innenfor skjermen.
     */
    fun overlayBox(popup: Box, screenW: Int, screenH: Int, margin: Int, minW: Int, minH: Int): Box {
        val w0 = maxOf(popup.w + 2 * margin, minW)
        val h0 = maxOf(popup.h + 2 * margin, minH)
        val w = if (screenW > 0) minOf(w0, screenW) else w0
        val h = if (screenH > 0) minOf(h0, screenH) else h0
        var x = popup.x + popup.w / 2 - w / 2
        var y = popup.y + popup.h / 2 - h / 2
        if (screenW > 0) x = x.coerceIn(0, screenW - w)
        if (screenH > 0) y = y.coerceIn(0, screenH - h)
        return Box(x, y, w, h)
    }

    /** Skal linja bort nå? 2 s etter siste endring når FYT-vinduet er borte, og uansett etter [MAX_SHOW_MS]. */
    fun shouldHide(now: Long, lastChange: Long, popupPresent: Boolean): Boolean {
        val since = now - lastChange
        if (since < 0) return false
        return since >= MAX_SHOW_MS || (since >= HIDE_MS && !popupPresent)
    }
}

object FytVolume {
    private val h = Handler(Looper.getMainLooper())

    /** På FYT styrer stereoen volumet selv (volumhjulet → com.syu.ms): T'OS viser bare sin linje over FYT sitt vindu. */
    val active get() = Fyt.connected && Fyt.volume >= 0
    private val enabled get() = Prefs.bool("tos_volume", true)

    var level = -1; private set
    var muted = false; private set
    private var lastChange = 0L
    private var lastFytAt = 0L
    private var popupPresent = false
    private var popup: WinSnap? = null

    private var slot: Pair<WindowManager, WeakReference<View>>? = null
    private var fillRef: WeakReference<View>? = null
    private var trackRef: WeakReference<View>? = null
    private var numRef: WeakReference<TextView>? = null
    private var titleRef: WeakReference<TextView>? = null
    private var shownBox: FytVolumeLogic.Box? = null

    /** Er T'OS-linja over FYT-vinduet framme (KeyService følger da med på vinduslista)? */
    val watching get() = slot != null

    /** Høyeste volum (40, det høyeste vi har sett, eller brukerens valg «fyt_vol_max»). */
    val max: Int get() = FytVolumeLogic.maxVolume(Prefs.int("fyt_vol_seen", 0), Prefs.str("fyt_vol_max", "0").toIntOrNull() ?: 0)

    /** Ny verdi fra FYT sin lydmodul (Fyt.apply, hovedtråden). [changed] = ikke bare første verdi etter tilkobling. */
    fun onFyt(c: Context?, level: Int, muted: Boolean, changed: Boolean) {
        this.level = level; this.muted = muted
        if (level > Prefs.int("fyt_vol_seen", 0)) Prefs.setQuiet("fyt_vol_seen", level)
        if (!changed || c == null) return
        lastFytAt = SystemClock.elapsedRealtime()
        // T'OS sin egen glidebryter (FytSound): ingen ekstra linje. Ligger linja allerede over FYT-vinduet, følger den
        // med; inne i T'OS vises T'OS sitt vanlige volumvindu. Dukker FYT-vinduet opp likevel, dekkes det (onPopupEvent).
        if (FytSound.ownRecently()) {
            if (update()) { lastChange = lastFytAt; KeyService.instance?.checkFytPopup() }
            else if (TosOverlay.appShown && !Nav.quick) VolumeUi.show(if (muted) 0 else level, max)
            return
        }
        show(c)
    }

    /** FYT-vinduet sendte en hendelse (tallet står i teksten). Reserve når FYT-tjenesten ikke svarer. */
    fun onPopupEvent(c: Context, texts: List<String>) {
        popupPresent = true
        val n = FytVolumeLogic.levelFromText(texts) ?: return
        if (Fyt.connected) { if (slot != null || level < 0) return } else level = n
        if (FytSound.ownRecently()) VolumeUi.hide()      // T'OS-linja dekker FYT-vinduet – ikke to linjer
        show(c)
    }

    /** Vinduslista endret seg mens linja vises: finnes FYT-vinduet fortsatt, og hvor er det? */
    internal fun onWindows(snaps: List<WinSnap>?, screenW: Int, screenH: Int) {
        if (snaps == null) return
        val recent = SystemClock.elapsedRealtime() - maxOf(lastFytAt, lastChange) < FytVolumeLogic.RECENT_MS
        val p = snaps.firstOrNull { FytVolumeLogic.isFytPopup(it, screenW, screenH, recent || popupPresent) }
        popupPresent = p != null
        if (p != null && p != popup) { popup = p; reposition() }
    }

    private fun show(c: Context) {
        if (!enabled || SurveyRecorder.active || FytMain.stayOut) return
        lastChange = SystemClock.elapsedRealtime()
        if (!update()) build(c)
        KeyService.instance?.checkFytPopup()
        h.removeCallbacks(tick)
        h.postDelayed(tick, 250)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (slot == null) return
            KeyService.instance?.checkFytPopup()
            if (FytVolumeLogic.shouldHide(SystemClock.elapsedRealtime(), lastChange, popupPresent)) hide()
            else h.postDelayed(this, 250)
        }
    }

    fun hide() {
        h.removeCallbacks(tick)
        slot?.let { (wm, ref) -> ref.get()?.let { v -> runCatching { wm.removeView(v) } } }
        slot = null; shownBox = null; popupPresent = false; popup = null
    }

    private fun title() = if (muted) "Lyd av" else "Volum"
    private fun number() = String.format(Locale.getDefault(), "%d", if (muted) 0 else level.coerceAtLeast(0))
    private fun fraction() = volumeFraction(level, max, muted)

    /** Oppdater linja som vises. false = ingen linje (må lages). */
    private fun update(): Boolean {
        val v = slot?.second?.get() ?: return false
        val fill = fillRef?.get(); val track = trackRef?.get(); val num = numRef?.get(); val t = titleRef?.get()
        if (!v.isAttachedToWindow || fill == null || track == null || num == null || t == null) { hide(); return false }
        t.text = title(); num.text = number()
        setFill(fill, track)
        return true
    }

    private fun setFill(fill: View, track: View) {
        val w = track.width
        if (w <= 0) { track.post { setFill(fill, track) }; return }
        fill.layoutParams = fill.layoutParams.apply { width = (w * fraction()).toInt() }
    }

    private fun boxFor(ctx: Context): FytVolumeLogic.Box {
        val dm = ctx.resources.displayMetrics
        val p = FytVolumeLogic.popupBox(popup, dm.widthPixels, dm.heightPixels)
        return FytVolumeLogic.overlayBox(p, dm.widthPixels, dm.heightPixels, margin = TosOverlay.dp(ctx, 6f),
            minW = TosOverlay.dp(ctx, 360f), minH = TosOverlay.dp(ctx, 64f))
    }

    private fun lp(type: Int, b: FytVolumeLogic.Box) = WindowManager.LayoutParams(
        b.w, b.h, type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = b.x; y = b.y }

    private fun build(c: Context) {
        val (ctx, type) = TosOverlay.host(c) ?: return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val b = boxFor(ctx)
        val fg = TextPrimary.toArgb()
        val dp = { v: Float -> TosOverlay.dp(ctx, v) }
        val tv = TextView(ctx).apply { text = title(); setTextColor(TextMuted.toArgb()); textSize = 15f; maxLines = 1 }
        val fl = View(ctx).apply { background = GradientDrawable().apply { setColor(fg); cornerRadius = dp(4f).toFloat() } }
        val track = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { setColor(Line.toArgb()); cornerRadius = dp(4f).toFloat() }
            addView(fl, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val nv = TextView(ctx).apply { text = number(); setTextColor(fg); textSize = 26f; gravity = Gravity.END; maxLines = 1 }
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setPadding(dp(20f), 0, dp(20f), 0)
            // Helt tett kort (ingen gjennomsiktighet), så FYT-vinduet under ikke synes
            background = GradientDrawable().apply {
                setColor(Card.toArgb()); cornerRadius = minOf(dp(22f), b.h / 2).toFloat(); setStroke(dp(1f), Line.toArgb())
            }
            addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(16f) })
            addView(track, LinearLayout.LayoutParams(0, dp(8f), 1f))
            addView(nv, LinearLayout.LayoutParams(dp(64f), LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        if (runCatching { wm.addView(view, lp(type, b)) }.isFailure) return
        slot = wm to WeakReference(view)
        fillRef = WeakReference(fl); trackRef = WeakReference(track); numRef = WeakReference(nv); titleRef = WeakReference(tv)
        shownBox = b
        setFill(fl, track)
    }

    /** FYT-vinduet ligger et annet sted enn vi trodde: flytt kortet over det. */
    private fun reposition() {
        val (wm, ref) = slot ?: return
        val v = ref.get() ?: return
        val b = boxFor(v.context)
        if (b == shownBox) return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        lp.width = b.w; lp.height = b.h; lp.x = b.x; lp.y = b.y
        if (runCatching { wm.updateViewLayout(v, lp) }.isSuccess) shownBox = b
    }
}
