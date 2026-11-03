package no.bil.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.abs
import kotlin.math.max

/**
 * Mørke app-ikoner for ALLE apper (også YouTube, Spotify osv.):
 *  1. Android 13+: appens eget ensfargede ikon når det finnes («tema-ikon»).
 *  2. Ellers lages et symbol automatisk: ikonets hovedfarge (som oftest bakgrunnen, f.eks. rødt hos YouTube)
 *     fjernes, og alt som skiller seg fra den (f.eks. play-trekanten) blir et lyst symbol.
 *     Har ikonet mange farger (Chrome, Gmail, Maps), blir de fargede delene symbolet og hvitt/svart blir hull.
 *  3. Er ikonet bare én farge (f.eks. en hvit figur), brukes selve formen som symbol.
 * Symbolet tegnes i T'OS-hvitt på mørk grå bunn, og følger nattmodus.
 */
object IconStyle {
    val dark get() = Prefs.str("icon_style", "DARK") == "DARK"
    private const val PX = 128
    /** Ferdige symboler per app (pakkenavn), maks 150 – så minnet ikke fylles opp over tid. */
    private val cache = android.util.LruCache<String, Bitmap>(150)

    /** Ferdig maske: hvit der symbolet er, gjennomsiktig ellers. Farges i Compose. */
    fun glyph(d: Drawable, key: String): Bitmap = cache.get(key) ?: synchronized(this) {
        // Én om gangen: flere ikoner regnes ut på bakgrunnstråder samtidig, og samme Drawable (setBounds/draw)
        // tåler ikke å tegnes fra to tråder på en gang – da kunne ikoner bli ødelagte eller feil størrelse.
        cache.get(key) ?: makeGlyph(d).also { cache.put(key, it) }
    }

    private fun makeGlyph(d: Drawable): Bitmap = run {
        if (Build.VERSION.SDK_INT >= 33 && d is AdaptiveIconDrawable && d.monochrome != null) {
            return@run toMask(layer(d.monochrome!!), alphaOnly = true)
        }
        val full = if (d is AdaptiveIconDrawable) {
            val b = Bitmap.createBitmap(PX, PX, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            d.background?.let { c.drawBitmap(layer(it), 0f, 0f, null) }
            d.foreground?.let { c.drawBitmap(layer(it), 0f, 0f, null) }
            b
        } else d.toBitmap(PX, PX, Bitmap.Config.ARGB_8888)
        toMask(full, alphaOnly = false)
    }

    /** Tegner et lag (108 enheter, synlig del 72 i midten) og beholder den synlige delen. */
    private fun layer(l: Drawable): Bitmap {
        val full = (PX * 108f / 72f).toInt()
        val big = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888)
        l.setBounds(0, 0, full, full); l.draw(Canvas(big))
        val off = (full - PX) / 2
        return Bitmap.createBitmap(big, off, off, PX, PX)
    }

    private fun toMask(src: Bitmap, alphaOnly: Boolean): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)

        if (!alphaOnly) {
            // Hovedfarge: vanligste farge blant tydelige piksler (grovt gruppert)
            val counts = HashMap<Int, Int>()
            var opaque = 0
            for (p in px) {
                if (Color.alpha(p) >= 10) opaque++
                if (Color.alpha(p) > 200) {
                    val k = ((Color.red(p) shr 4) shl 8) or ((Color.green(p) shr 4) shl 4) or (Color.blue(p) shr 4)
                    counts[k] = (counts[k] ?: 0) + 1
                }
            }
            val dom = counts.maxByOrNull { it.value }
            if (dom != null && opaque > 0) {
                val oneColor = dom.value >= opaque * 0.4f
                val dr = ((dom.key shr 8) and 15) * 16 + 8; val dg = ((dom.key shr 4) and 15) * 16 + 8; val db = (dom.key and 15) * 16 + 8
                var covered = 0
                for (i in px.indices) {
                    val p = px[i]; val a = Color.alpha(p)
                    if (a < 10) { out[i] = 0; continue }
                    val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                    val strength = if (oneColor) {
                        // Én hovedfarge (f.eks. rød YouTube): alt som skiller seg fra den blir symbolet
                        val d = max(abs(r - dr), max(abs(g - dg), abs(b - db))) / 255f
                        ((d - 0.18f) / 0.27f).coerceIn(0f, 1f)
                    } else {
                        // Mange farger (f.eks. Chrome): fargede deler blir symbolet, hvitt/grått/svart blir hull
                        val sat = (max(r, max(g, b)) - minOf(r, g, b)) / 255f
                        ((sat - 0.12f) / 0.2f).coerceIn(0f, 1f)
                    }
                    val alpha = (strength * a).toInt()
                    if (alpha > 60) covered++
                    out[i] = Color.argb(alpha, 255, 255, 255)
                }
                // Fant vi et tydelig symbol? Ellers: bruk formen (ensfarget ikon)
                if (covered > opaque * 0.03f && covered < opaque * 0.97f) {
                    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
                }
            }
        }
        for (i in px.indices) out[i] = Color.argb(Color.alpha(px[i]), 255, 255, 255)
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}

/** App-ikon i T'OS-stil. [key] = pakkenavn (brukes for å huske ferdig symbol). Regnes ut i bakgrunnen. */
@Composable
fun AppIcon(icon: Drawable, size: Dp, key: String) {
    if (!IconStyle.dark) {
        val bmp = remember(key) { icon.toBitmap(128, 128).asImageBitmap() }
        Image(bmp, null, Modifier.size(size))
        return
    }
    val img by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, key) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { IconStyle.glyph(icon, key).asImageBitmap() }.getOrNull() }
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.27f)).background(Line), contentAlignment = Alignment.Center) {
        img?.let { Image(it, null, Modifier.size(size * 0.86f), colorFilter = ColorFilter.tint(TextPrimary)) }
    }
}
