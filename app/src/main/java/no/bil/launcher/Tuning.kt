package no.bil.launcher

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/* ======================= Ytelse: akselerasjon målt med GPS ======================= */

/**
 * Måler 0–100 og 80–120 km/t med GPS, så du kan se forskjellen før og etter tuning.
 * GPS oppdateres én gang i sekundet, så tidene er omtrentlige (ca. ± 0,5 s).
 */
object Accel {
    data class Run(val name: String, val from: Int, val to: Int)
    val runs = listOf(Run("0–100", 0, 100), Run("80–120", 80, 120))

    var armed by mutableStateOf<Run?>(null)
    var running by mutableStateOf(false)
    var elapsed by mutableStateOf(0f)
    var result by mutableStateOf<String?>(null)
    private var startAt = 0L
    private var lastSpeed = 0
    private var lastAt = 0L

    fun best(r: Run): Float = Prefs.float("accel_best_${r.from}_${r.to}", 0f)
    fun last(r: Run): Float = Prefs.float("accel_last_${r.from}_${r.to}", 0f)

    fun arm(r: Run) { armed = r; running = false; elapsed = 0f; result = null }
    fun cancel() { armed = null; running = false }

    fun onFix(g: Gps) {
        val r = armed ?: run { lastSpeed = g.speedKmh; lastAt = SystemClock.elapsedRealtime(); return }
        val now = SystemClock.elapsedRealtime()
        val v = g.speedKmh
        if (!running) {
            val startCond = if (r.from == 0) v >= 2 && lastSpeed < 2 else lastSpeed < r.from && v >= r.from
            if (startCond) {
                // Finn starttidspunktet mellom de to målingene (lineært)
                val frac = if (v != lastSpeed) ((r.from.coerceAtLeast(1) - lastSpeed).toFloat() / (v - lastSpeed)).coerceIn(0f, 1f) else 0f
                startAt = lastAt + ((now - lastAt) * frac).toLong()
                running = true
            }
        } else {
            elapsed = (now - startAt) / 1000f
            if (v >= r.to) {
                val frac = if (v != lastSpeed) ((r.to - lastSpeed).toFloat() / (v - lastSpeed)).coerceIn(0f, 1f) else 1f
                val t = ((lastAt + ((now - lastAt) * frac).toLong()) - startAt) / 1000f
                Prefs.set("accel_last_${r.from}_${r.to}", t)
                if (best(r) == 0f || t < best(r)) Prefs.set("accel_best_${r.from}_${r.to}", t)
                result = String.format(Locale("nb", "NO"), "%s: %.1f s", r.name, t)
                armed = null; running = false
            } else if (v < lastSpeed - 5 || elapsed > 60) {
                result = "Avbrutt – farten falt eller det tok for lang tid"; armed = null; running = false
            }
        }
        lastSpeed = v; lastAt = now
    }

    fun fmt(s: Float) = if (s <= 0f) "–" else String.format(Locale("nb", "NO"), "%.1f s", s)
}

/* ======================= Tuning-siden ======================= */

/** Kjente tuning-apper (tuningbokser med Bluetooth). Brukeren kan velge en annen. */
val tuningApps = listOf("com.racechip.plus")

@Composable
fun TuningScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    val chosen = Prefs.str("tuning_pkg")
    val pkg = chosen.takeIf { it.isNotBlank() && AppRepository.isInstalled(context, it) }
        ?: tuningApps.firstOrNull { AppRepository.isInstalled(context, it) }
    val label = remember(pkg) {
        pkg?.let { runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull() }
    }

    Box(Modifier.fillMaxSize()) {
        Page("Tuning og ytelse", onClose, extra = {
            Pill("Velg tuning-app", Icons.Default.Apps) { picking = true }
            Spacer(Modifier.width(12.dp))
        }) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // Venstre: tuningboks + målinger
                Column(Modifier.weight(0.58f).verticalScroll(rememberScrollState())) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(label ?: "Ingen tuning-app funnet", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (pkg != null) "Velg kjøremodus og effekt i appen til tuningboksen"
                                else "Installer appen til tuningboksen (f.eks. RaceChip) fra Play Butikk, eller trykk «Velg tuning-app».",
                                color = TextMuted, fontSize = 14.sp,
                            )
                        }
                        if (pkg != null) Pill("Åpne", Icons.Default.Speed, strong = true) { AppRepository.launch(context, pkg) }
                    }

                    Section("Akselerasjon (målt med GPS)")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Accel.runs.forEach { r ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(r.name + " km/t", color = TextMuted, fontSize = 13.sp)
                                Text(Accel.fmt(Accel.last(r)), color = TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Medium)
                                Text("Beste: ${Accel.fmt(Accel.best(r))}", color = TextMuted, fontSize = 13.sp)
                                Spacer(Modifier.height(6.dp))
                                val on = Accel.armed == r
                                Pill(if (on) "Avbryt" else "Mål", strong = !on) { if (on) Accel.cancel() else Accel.arm(r) }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    val a = Accel.armed
                    Text(
                        when {
                            a != null && Accel.running -> String.format(Locale("nb", "NO"), "Måler %s … %.1f s", a.name, Accel.elapsed)
                            a != null && a.from == 0 -> "Klar. Stå stille, og kjør når det er trygt og lovlig."
                            a != null -> "Klar. Målingen starter når du passerer ${a.from} km/t."
                            Accel.result != null -> Accel.result!!
                            else -> "Mål før og etter tuning for å se forskjellen. Bruk lukket område – ikke offentlig vei."
                        },
                        color = if (a != null) TextPrimary else TextMuted, fontSize = 15.sp,
                    )
                }

                // Høyre: hva som finnes, og regler
                Column(
                    Modifier.weight(0.42f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(Card).padding(18.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Slik får Avensisen mer kraft", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Info("Tuningboks", "Kobles mellom motor og sensorer. Noen (f.eks. RaceChip GTS Black) har Bluetooth-app der du bytter modus. Størst effekt på diesel (D-4D).")
                    Info("Omprogrammering", "Verksted skriver ny programvare til motorstyringen. Gjøres med spesialutstyr, ikke fra stereoen.")
                    Info("Gasspedalboks", "Raskere respons på gasspedalen, men ikke mer effekt.")
                    Spacer(Modifier.height(4.dp))
                    Text("Før du tuner", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "Statens vegvesen krever at effektøkning godkjennes og føres i vognkortet. Uten det kan forsikringen avvise skader. " +
                            "Si fra til forsikringsselskapet. Les mer på vegvesen.no (søk «chiptuning»).",
                        color = TextMuted, fontSize = 13.sp,
                    )
                }
            }
        }
        if (picking) AppPicker("Velg tuning-app", "Automatisk (RaceChip)", { Prefs.set("tuning_pkg", it) }) { picking = false }
    }
}

@Composable
private fun Info(title: String, text: String) {
    Column {
        Text(title, color = TextPrimary, fontSize = 15.sp)
        Text(text, color = TextMuted, fontSize = 13.sp)
    }
}
