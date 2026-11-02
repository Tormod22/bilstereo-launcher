package no.bil.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap

/**
 * App-ikoner i mørkt tema: fargebakgrunnen fjernes og symbolet vises i gråtoner på mørk bunn.
 *  - Android 13+: bruker appens eget ensfargede ikon der det finnes (samme som «tema-ikoner»).
 *  - Ellers: bare forgrunnen av ikonet, i gråtoner.
 *  - Gamle ikoner uten lag: hele ikonet i dempede gråtoner.
 * Kan slås av i Innstillinger («App-ikoner: Originale farger»).
 */
object IconStyle {
    val dark get() = Prefs.str("icon_style", "DARK") == "DARK"

    enum class Kind { MONO, GLYPH, FULL }
    class Prepared(val bitmap: Bitmap, val kind: Kind)

    private val cache = HashMap<Drawable, Prepared>()

    fun prepare(d: Drawable, px: Int = 128): Prepared = cache.getOrPut(d) {
        if (Build.VERSION.SDK_INT >= 33 && d is AdaptiveIconDrawable && d.monochrome != null) {
            Prepared(layer(d.monochrome!!, px), Kind.MONO)
        } else if (d is AdaptiveIconDrawable && d.foreground != null) {
            Prepared(layer(d.foreground, px), Kind.GLYPH)
        } else Prepared(d.toBitmap(px, px), Kind.FULL)
    }

    /** Tegner et lag (108 enheter, synlig del 72 i midten) og beholder den synlige delen. */
    private fun layer(l: Drawable, px: Int): Bitmap {
        val full = (px * 108f / 72f).toInt()
        val big = Bitmap.createBitmap(full, full, Bitmap.Config.ARGB_8888)
        l.setBounds(0, 0, full, full); l.draw(Canvas(big))
        val off = (full - px) / 2
        return Bitmap.createBitmap(big, off, off, px, px)
    }

    private val gray = ColorMatrix().apply { setToSaturation(0f) }
    val grayFilter = ColorFilter.colorMatrix(gray)
    val dimGrayFilter = ColorFilter.colorMatrix(ColorMatrix().apply {
        setToSaturation(0f)
        timesAssign(ColorMatrix().apply { setToScale(0.72f, 0.72f, 0.72f, 1f) })
    })
}

@Composable
fun AppIcon(icon: Drawable, size: Dp) {
    if (!IconStyle.dark) {
        val bmp = remember(icon) { icon.toBitmap(128, 128).asImageBitmap() }
        Image(bmp, null, Modifier.size(size))
        return
    }
    val p = remember(icon) { IconStyle.prepare(icon) }
    val img = remember(p) { p.bitmap.asImageBitmap() }
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.27f)).background(Line), contentAlignment = Alignment.Center) {
        when (p.kind) {
            IconStyle.Kind.MONO -> Image(img, null, Modifier.size(size), colorFilter = ColorFilter.tint(TextPrimary))
            IconStyle.Kind.GLYPH -> Image(img, null, Modifier.size(size), colorFilter = IconStyle.grayFilter)
            IconStyle.Kind.FULL -> Image(img, null, Modifier.size(size * 0.78f).clip(RoundedCornerShape(size * 0.2f)), colorFilter = IconStyle.dimGrayFilter)
        }
    }
}
