package no.bil.launcher

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap

/**
 * Liten popup øverst i høyre hjørne når kartappen åpnes: «Åpne heller Waze».
 * Trykk = Waze åpnes i stedet. Ellers forsvinner den etter 5 sekunder.
 * Vises over Google Maps via knappestyringen (tilgjengelighetstjenesten), eller via
 * «Vis over andre apper» hvis den er gitt. Uten noen av dem åpnes bare kartappen som vanlig.
 */
object NaviPopup {
    private const val SHOW_MS = 5000L
    private var current: View? = null
    private var currentWm: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())

    val enabled get() = Prefs.bool("navi_popup", true)

    /** Kan T'OS vise popupen over andre apper akkurat nå? */
    fun possible(c: Context) = KeyService.instance != null || Settings.canDrawOverlays(c)

    fun show(c: Context, label: String, pkg: String) {
        if (!enabled) return
        val svc = KeyService.instance
        val (ctx, type) = when {
            svc != null -> svc to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Settings.canDrawOverlays(c) -> c.applicationContext to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> return
        }
        // Vent litt så popupen havner oppå kartappen som akkurat åpnes
        handler.postDelayed({ add(ctx, type, label, pkg) }, 700)
    }

    private fun dp(c: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics).toInt()

    private fun add(ctx: Context, type: Int, label: String, pkg: String) {
        hide()
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val night = Theme.night
        val bg = if (night) 0xF0141416.toInt() else 0xF01C1C1F.toInt()
        val fg = if (night) 0xFFB9B9BE.toInt() else 0xFFF5F5F7.toInt()
        val muted = if (night) 0xFF68686D.toInt() else 0xFF86868B.toInt()

        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 22f), dp(ctx, 12f))
            background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(ctx, 26f).toFloat(); setStroke(dp(ctx, 1f), 0xFF2A2A2E.toInt()) }
            elevation = dp(ctx, 8f).toFloat()
            // App-ikonet i mørk stil (samme som i T'OS)
            runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull()?.let { icon ->
                addView(ImageView(ctx).apply {
                    val bmp = if (IconStyle.dark) IconStyle.glyph(icon) else icon.toBitmap(96, 96)
                    setImageBitmap(bmp)
                    if (IconStyle.dark) setColorFilter(fg)
                    background = GradientDrawable().apply { setColor(0xFF2A2A2E.toInt()); cornerRadius = dp(ctx, 10f).toFloat() }
                    setPadding(dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f))
                }, LinearLayout.LayoutParams(dp(ctx, 38f), dp(ctx, 38f)).apply { marginEnd = dp(ctx, 12f) })
            }
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply { text = "Åpne heller $label"; setTextColor(fg); textSize = 17f })
                addView(TextView(ctx).apply { text = "Trykk her"; setTextColor(muted); textSize = 13f })
            })
            alpha = 0f
            setOnClickListener {
                hide()
                AppRepository.launch(ctx, pkg)
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(ctx, 24f); y = dp(ctx, 24f)
        }
        if (runCatching { wm.addView(view, lp) }.isFailure) return
        current = view; currentWm = wm
        view.animate().alpha(1f).setDuration(200).start()
        handler.postDelayed({
            if (current === view) view.animate().alpha(0f).setDuration(600).withEndAction { if (current === view) hide() }.start()
        }, SHOW_MS)
    }

    fun hide() {
        val v = current ?: return
        runCatching { currentWm?.removeView(v) }
        current = null; currentWm = null
    }
}
