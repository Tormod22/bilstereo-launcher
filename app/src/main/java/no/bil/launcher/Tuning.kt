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
    var elapsed by mutableFloatStateOf(0f)
    var result by mutableStateOf<String?>(null)
    private var startAt = 0L
    private var lastSpeed = 0
    private var lastAt = 0L
    private var candidateAt = 0L          // første måling over START_KMH (0 = ingen)
    private const val START_KMH = 4

    fun best(r: Run): Float = Prefs.float("accel_best_${r.from}_${r.to}", 0f)
    fun last(r: Run): Float = Prefs.float("accel_last_${r.from}_${r.to}", 0f)

    fun arm(r: Run) { armed = r; running = false; elapsed = 0f; result = null; candidateAt = 0L }
    fun cancel() { armed = null; running = false }

    fun onFix(g: Gps) {
        val r = armed ?: run { lastSpeed = g.speedKmh; lastAt = SystemClock.elapsedRealtime(); return }
        val now = SystemClock.elapsedRealtime()
        val v = g.speedKmh
        if (!running) {
            if (r.from == 0) {
                // 0–100: GPS-støy når bilen står kan gi et par km/t. Start først etter 2 målinger på rad over 4 km/t;
                // starttiden er da den første av dem (regnet tilbake til da farten passerte ca. 1 km/t).
                // Den første må komme rett etter en måling under 4 km/t (bilen sto), ellers er det ingen 0–100.
                if (v >= START_KMH && candidateAt != 0L) {
                    startAt = candidateAt; candidateAt = 0L; running = true
                } else if (v >= START_KMH && lastSpeed < START_KMH) {
                    val frac = if (v != lastSpeed) ((1 - lastSpeed).toFloat() / (v - lastSpeed)).coerceIn(0f, 1f) else 0f
                    candidateAt = (lastAt + ((now - lastAt) * frac).toLong()).coerceAtLeast(1L)
                } else candidateAt = 0L
            } else if (lastSpeed < r.from && v >= r.from) {
                // Finn starttidspunktet mellom de to målingene (lineært)
                val frac = if (v != lastSpeed) ((r.from - lastSpeed).toFloat() / (v - lastSpeed)).coerceIn(0f, 1f) else 0f
                startAt = lastAt + ((now - lastAt) * frac).toLong()
                running = true
            }
        } else if (v < 2) {
            // Bilen sto stille likevel (falsk start): vent på en ny start
            running = false; elapsed = 0f; candidateAt = 0L
        } else {
            elapsed = (now - startAt) / 1000f
            if (v >= r.to) {
                val frac = if (v != lastSpeed) ((r.to - lastSpeed).toFloat() / (v - lastSpeed)).coerceIn(0f, 1f) else 1f
                val t = ((lastAt + ((now - lastAt) * frac).toLong()) - startAt) / 1000f
                Prefs.set("accel_last_${r.from}_${r.to}", t)
                if (best(r) == 0f || t < best(r)) Prefs.set("accel_best_${r.from}_${r.to}", t)
                TuneLog.add(r.name, t)
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

/* ======================= Min tuningboks + logg ======================= */

/**
 * Brukerens egen boks: ChipPower CRS (kabel KCR18) – ingen app/Bluetooth, styrken stilles med skruen på boksen.
 * T'OS husker hvilket trinn den står på, og logger hver måling med trinnet, så man ser om justeringen hjelper.
 */
object TuneBox {
    const val DEFAULT_NAME = "ChipPower CRS (KCR18)"
    val name get() = Prefs.str("tune_box", DEFAULT_NAME).ifBlank { DEFAULT_NAME }
    /** 0 = av/original (kontakten ute), 1–10 = trinn på skruen. */
    val level get() = Prefs.int("tune_level", 0).coerceIn(0, 10)
    fun setLevel(v: Int) = Prefs.set("tune_level", v.coerceIn(0, 10))
    fun levelText(v: Int = level) = if (v == 0) "Av (original)" else "Trinn $v"
}

object TuneLog {
    data class Entry(val time: Long, val run: String, val sec: Float, val level: Int)

    fun all(): List<Entry> = parse(Prefs.str("tune_log"))

    fun add(run: String, sec: Float) {
        val list = (all() + Entry(System.currentTimeMillis(), run, sec, TuneBox.level)).takeLast(60)
        Prefs.set("tune_log", format(list))
    }

    fun clear() = Prefs.set("tune_log", "")

    /** Beste tid per trinn for en måling (trinn → sekunder), sortert på trinn. */
    fun bestPerLevel(run: String, list: List<Entry> = all()): List<Pair<Int, Float>> =
        list.filter { it.run == run }.groupBy { it.level }.map { (l, e) -> l to e.minOf { it.sec } }.sortedBy { it.first }

    // Enkelt tekstformat: «tid;måling;sek;trinn» per linje (tåler ødelagte linjer)
    internal fun format(list: List<Entry>) = list.joinToString("\n") { "${it.time};${it.run};${it.sec};${it.level}" }
    internal fun parse(s: String): List<Entry> = s.lines().mapNotNull { l ->
        val p = l.split(';')
        if (p.size != 4) null else runCatching { Entry(p[0].toLong(), p[1], p[2].toFloat(), p[3].toInt()) }.getOrNull()
    }
}

/* ======================= Tuning-siden ======================= */

/** Kjente tuning-apper (tuningbokser med Bluetooth). Brukeren kan velge en annen. */
val tuningApps = listOf("com.racechip.plus")

@Composable
fun TuningScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    val chosen = Prefs.str("tuning_pkg")
    val pkg = remember(chosen) {
        chosen.takeIf { it.isNotBlank() && AppRepository.isInstalled(context, it) }
            ?: tuningApps.firstOrNull { AppRepository.isInstalled(context, it) }
    }
    val label = remember(pkg) {
        pkg?.let { runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull() }
    }

    // Motordata bare mens siden faktisk vises: dekker en annen app T'OS (Maps, Car link), stopper spørringene
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> Engine.watching = true
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> Engine.watching = false
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)   // spiller av ON_START med en gang hvis siden allerede vises
        onDispose { lifecycle.removeObserver(obs); Engine.watching = false }
    }

    Box(Modifier.fillMaxSize()) {
        Page("Tuning og ytelse", onClose, extra = {
            Pill("Velg tuning-app", Icons.Default.Apps) { picking = true }
            Spacer(Modifier.width(12.dp))
        }) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // Venstre: tuningboks + målinger
                Column(Modifier.weight(0.58f).verticalScroll(rememberScrollState())) {
                    // Min tuningboks (ChipPower CRS har ingen app: trinnet føres her for hånd)
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Min tuningboks", color = TextMuted, fontSize = 13.sp)
                        Text(TuneBox.name, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Pill("–", strong = false) { TuneBox.setLevel(TuneBox.level - 1) }
                            Text(TuneBox.levelText(), color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.widthIn(min = 150.dp))
                            Pill("+", strong = false) { TuneBox.setLevel(TuneBox.level + 1) }
                        }
                        Text("Sett trinnet likt skruen på boksen. Hver måling under lagres med trinnet, så du ser hva som virker.",
                            color = TextMuted, fontSize = 13.sp)
                        if (pkg != null) Pill("Åpne ${label ?: "tuning-app"}", Icons.Default.Speed) { AppRepository.launch(context, pkg) }
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
                    when {
                        Hub.gpsNoPermission -> Text("T'OS har ikke lov til å bruke posisjon – målingene trenger GPS. Slå på under Om stereoen → Førstegangsoppsett.", color = TextPrimary, fontSize = 14.sp)
                        Hub.gpsCoarseOnly -> Text("Bare «omtrentlig posisjon» er tillatt – målingene blir feil. Velg «Nøyaktig» under Førstegangsoppsett.", color = TextPrimary, fontSize = 14.sp)
                        Hub.gps == null || Hub.gps?.stale == true -> Text("Venter på GPS … (står bilen i garasje eller tunnel?)", color = TextMuted, fontSize = 14.sp)
                    }
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

                    // Resultat per trinn
                    val log = remember(Prefs.version) { TuneLog.all() }
                    if (log.isEmpty()) {
                        Section("Beste tid per trinn")
                        Text("Ingen målinger ennå. Trykk «Mål» over – resultatet lagres sammen med trinnet på tuningboksen.", color = TextMuted, fontSize = 14.sp)
                    } else {
                        Section("Beste tid per trinn")
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Accel.runs.forEach { r ->
                                val per = TuneLog.bestPerLevel(r.name, log)
                                if (per.isNotEmpty()) {
                                    Text(r.name + " km/t", color = TextMuted, fontSize = 13.sp)
                                    per.forEach { (l, t) ->
                                        Row(Modifier.fillMaxWidth()) {
                                            Text(TuneBox.levelText(l), color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                            Text(Accel.fmt(t), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            var ask by remember { mutableStateOf(false) }
                            LaunchedEffect(ask) { if (ask) { kotlinx.coroutines.delay(4000); ask = false } }
                            Pill(if (ask) "Trykk igjen for å tømme" else "Tøm loggen", Icons.Default.Delete, strong = ask) {
                                if (ask) { TuneLog.clear(); ask = false; Notify.show("Loggen er tømt") } else ask = true
                            }
                        }
                    }

                    // Motordata fra OBD2-brikka (ChipPower CRS har selv ingen utgang)
                    Section("Motordata (OBD2-brikke)")
                    EngineCard()
                }

                // Høyre: hva som finnes, og regler
                Column(
                    Modifier.weight(0.42f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(Card).padding(18.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Slik får Avensisen mer kraft", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Info("Tuningboks (din: ChipPower CRS)", "Sitter på rail-trykksensoren og øker drivstofftrykket litt. Har ingen app – styrken stilles med skruen. Tar du ut kontakten, går bilen som original.")
                    Info("Omprogrammering", "Verksted skriver ny programvare til motorstyringen. Gjøres med spesialutstyr, ikke fra stereoen.")
                    Info("Gasspedalboks", "Raskere respons på gasspedalen, men ikke mer effekt.")
                    Info("Se hva boksen gjør", "Med en OBD2-brikke (Bluetooth, ELM327) viser T'OS turtall, ladetrykk, rail-trykk og temperatur her. Samme brikke gir også batterispenningen.")
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
        if (picking) androidx.activity.compose.BackHandler { picking = false }
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

@Composable
private fun EngineCard() {
    val nb = Locale("nb", "NO")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!CarBattery.obdConnected) {
            Text(CarBattery.obdStatus, color = TextMuted, fontSize = 14.sp)
            Text("Kjøp en OBD2 Bluetooth-brikke (ELM327, f.eks. Vgate iCar Pro), sett den i diagnosekontakten under rattet og par den under Innstillinger → Bluetooth.",
                color = TextMuted, fontSize = 13.sp)
            return@Column
        }
        Engine.problem?.let { Text(it, color = TextPrimary, fontSize = 14.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Gauge("Turtall", Engine.rpm?.let { "$it" }, "o/min", Modifier.weight(1f))
            Gauge("Ladetrykk", Engine.boostBar?.let { String.format(nb, "%.2f", it) }, "bar", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Gauge("Rail-trykk", Engine.railBar?.let { "$it" }, "bar", Modifier.weight(1f))
            Gauge("Kjølevæske", Engine.coolant?.let { "$it" }, "°C", Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Høyeste: " + (Engine.maxBoost?.let { String.format(nb, "%.2f bar ladetrykk", it) } ?: "–") +
                    (Engine.maxRail?.let { " · $it bar rail" } ?: ""),
                color = TextMuted, fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
            var ask by remember { mutableStateOf(false) }
            LaunchedEffect(ask) { if (ask) { kotlinx.coroutines.delay(4000); ask = false } }
            Pill(if (ask) "Trykk igjen" else "Nullstill", strong = ask) { if (ask) { Engine.resetMax(); ask = false } else ask = true }
        }
        if ((Engine.coolant ?: 99) < 70) Text("Vent med måling til motoren er varm (over 70 °C).", color = TextMuted, fontSize = 13.sp)
    }
}

@Composable
private fun Gauge(title: String, value: String?, unit: String, modifier: Modifier) {
    Column(modifier) {
        Text(title, color = TextMuted, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value ?: "–", color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(4.dp))
            Text(unit, color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}
