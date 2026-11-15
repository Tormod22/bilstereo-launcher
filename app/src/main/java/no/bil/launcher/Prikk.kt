package no.bil.launcher

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/* ---------- Prikkstil (v5.12): små byggeklosser som går igjen på flere sider ---------- */

/**
 * Svakt prikkrutenett bak et kort (som i forslaget). Én liten flis med én prikk gjentas av en shader,
 * så det koster ett tegnekall – ikke tusenvis av sirkler – selv når farten tegnes på nytt hvert sekund.
 */
fun Modifier.dotGrid(step: Dp = 11.dp, radius: Dp = 1.4.dp, alpha: Float = if (Theme.night) 0.05f else 0.075f): Modifier =
    drawWithCache {
        val s = step.toPx().roundToInt().coerceAtLeast(4)
        val tile = ImageBitmap(s, s)
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(tile),
            androidx.compose.ui.geometry.Size(s.toFloat(), s.toFloat())) {
            drawCircle(Color.White.copy(alpha = alpha), radius.toPx(), center)
        }
        val brush = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
        onDrawBehind { drawRect(brush) }
    }

/** Ikon i en tynn, rund ring (bunnraden, som i forslaget). */
@Composable
fun RingIcon(icon: ImageVector, size: Dp = 52.dp, desc: String? = null) {
    Box(Modifier.size(size).border(1.5.dp, Line, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = TextPrimary, modifier = Modifier.size(size * 0.46f))
    }
}

/** Kartet i gråtoner (ingen farge), dempet: [k] = hvor lyst (1 = som flisa). */
internal fun greyMap(k: Float): ColorFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    0.2126f * k, 0.7152f * k, 0.0722f * k, 0f, 0f,
    0.2126f * k, 0.7152f * k, 0.0722f * k, 0f, 0f,
    0.2126f * k, 0.7152f * k, 0.0722f * k, 0f, 0f,
    0f, 0f, 0f, 1f, 0f,
)))
