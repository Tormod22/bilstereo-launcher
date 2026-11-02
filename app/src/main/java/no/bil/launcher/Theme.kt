package no.bil.launcher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/* ---------- Palett: svart, gråtoner, hvit. Ingen farger. Om natten dempes alt. ---------- */

object Theme {
    /** Nattmodus akkurat nå (styres av [update]). */
    var night by mutableStateOf(false)

    /** "AUTO" (etter sola), "DAY" eller "NIGHT". */
    val mode get() = Prefs.str("night_mode", "AUTO")
    val textScale get() = Prefs.str("text_scale_s", "1.0").toFloatOrNull() ?: 1f

    fun update(lat: Double?, lon: Double?) {
        night = when (mode) {
            "DAY" -> false
            "NIGHT" -> true
            else -> Sun.isNight(lat ?: 59.91, lon ?: 10.75, Calendar.getInstance(TimeZone.getTimeZone("UTC")))
        }
    }
}

internal val Bg: Color get() = Color.Black
internal val Panel: Color get() = if (Theme.night) Color(0xFF0A0A0B) else Color(0xFF111113)
internal val Card: Color get() = if (Theme.night) Color(0xFF141416) else Color(0xFF1C1C1F)
internal val Line: Color get() = if (Theme.night) Color(0xFF202023) else Color(0xFF2A2A2E)
internal val TextPrimary: Color get() = if (Theme.night) Color(0xFFB9B9BE) else Color(0xFFF5F5F7)
internal val TextMuted: Color get() = if (Theme.night) Color(0xFF68686D) else Color(0xFF86868B)

/** Soloppgang/solnedgang (vanlig NOAA-formel). Tar hensyn til midnattssol og mørketid. */
object Sun {
    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun norm(x: Double, m: Double) = ((x % m) + m) % m

    /** Returnerer UTC-time (0–24) for soloppgang/-nedgang, Double.NaN = sola går ikke opp, +INF = går ikke ned. */
    fun utcHour(lat: Double, lon: Double, dayOfYear: Int, rising: Boolean): Double {
        val lngHour = lon / 15.0
        val t = dayOfYear + ((if (rising) 6.0 else 18.0) - lngHour) / 24.0
        val m = 0.9856 * t - 3.289
        val l = norm(m + 1.916 * sin(rad(m)) + 0.020 * sin(rad(2 * m)) + 282.634, 360.0)
        var ra = norm(deg(atan(0.91764 * tan(rad(l)))), 360.0)
        ra = (ra + floor(l / 90) * 90 - floor(ra / 90) * 90) / 15.0
        val sinDec = 0.39782 * sin(rad(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(rad(90.833)) - sinDec * sin(rad(lat))) / (cosDec * cos(rad(lat)))
        if (cosH > 1) return Double.NaN                 // mørketid
        if (cosH < -1) return Double.POSITIVE_INFINITY  // midnattssol
        val h = (if (rising) 360 - deg(acos(cosH)) else deg(acos(cosH))) / 15.0
        val tt = h + ra - 0.06571 * t - 6.622
        return norm(tt - lngHour, 24.0)
    }

    fun isNight(lat: Double, lon: Double, utc: Calendar): Boolean {
        val day = utc.get(Calendar.DAY_OF_YEAR)
        val rise = utcHour(lat, lon, day, true)
        val set = utcHour(lat, lon, day, false)
        if (rise.isNaN()) return true
        if (rise.isInfinite()) return false
        val now = utc.get(Calendar.HOUR_OF_DAY) + utc.get(Calendar.MINUTE) / 60.0
        val isDay = if (rise < set) now in rise..set else now >= rise || now <= set
        return !isDay
    }
}
