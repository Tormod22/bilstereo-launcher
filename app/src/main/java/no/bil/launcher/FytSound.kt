package no.bil.launcher

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/* ======================= Lyden på FYT-stereoen (lydmodulen, modul 4 i com.syu.ms) ======================= */

/**
 * På FYT-stereoen er det ekte volumet og lydbildet (equalizer, balanse/fader, loudness) FYT sitt – ikke Androids
 * lydstrømmer (kartleggingen 8. nov: musikkstrømmen stod på 0/25 mens volumhjulet endret FYT sitt volum 15 → 3).
 * T'OS styrer det her via lydmodulen (4) i com.syu.ms (samme Binder som [Fyt]), i stedet for com.syu.eq.
 *
 * Protokollen (kilder):
 *  - github.com/vasyl91/FYT-Launcher-Mod – app/src/main/java/com/syu/module/sound/FinalSound.java: alle C_/U_-kodene
 *    (C_VOL 0, C_EQ_POINT 1, C_EQ_MODE 2, C_BAL_FADE 3, C_LOUD 5, C_SB 6, C_AMP 7, C_BEEP 8, C_NAVI_MIX 9 …;
 *    U_MODULE_ID 1, U_VOL 2, U_MUTE 3, U_BEEP 4, … U_BAL_FADE 8, U_EQ_POINT 9, U_EQ_MODE 10, U_LOUD 11 …;
 *    VOL_UP −1, VOL_DOWN −2, VOL_MUTE −3, VOL_UNMUTE −4, VOL_MUTE_SWITCH −5, VOL_SHOW_UI −6), EQ_MODE-navnene.
 *  - github.com/cssbruno/cabin (Unlicense) – app/src/main/kotlin/com/cabin/platform/SyuSoundProtocol.kt og
 *    SyuSoundController.kt, «verified Joying 2023-08-31 Equalizer client contracts»: lydbrikke-ID (u=1) 6/7 = 32 bånd
 *    0–24, 11 = 36 bånd 0–20 (+ subwoofer u=26 / c=23, 0–10); u=8 = [balanse, fader] 0–16 → c=3 [balanse, fader];
 *    u=9 = [bånd, verdi] → c=1 [bånd, verdi]; u=10 forhåndsvalg 0–8 → c=2 [n]; u=11 loudness 0/1 → c=5 [n];
 *    volum c=0 [−1/−2/−5] (fastvaren bestemmer steg og grenser). Ukjent brikke-ID → ingen lydbilde-kontroller.
 *  - github.com/PimpinPumpkin/FytRadio – SyuRadioBridge.kt: c=0 [−3] / [−4] = demp / opphev (volumet beholdes).
 * Kartleggingen 8. nov på denne stereoen: lyd u=1 = 6 (→ 32 bånd 0–24), u=2 = volum, u=3 = demping.
 * IKKE bekreftet noe sted: et absolutt volum («sett til 12»). T'OS går derfor i steg (−1/−2) til tallet stemmer.
 * Retningen på balanse (0 = venstre?) og fader (0 = foran?), og hva båndene er i Hz, er ikke kjent.
 * Tastelyd, navigasjonsmiks, oppstartsvolum, forsterker: koder finnes i FinalSound, men formatet er ikke bekreftet –
 * de logges bare i kartleggingen (vises ikke som valg).
 */
internal object FytSoundLogic {
    const val C_VOL = 0
    const val C_EQ_POINT = 1
    const val C_EQ_MODE = 2
    const val C_BAL_FADE = 3
    const val C_LOUD = 5
    const val C_SUB_GAIN = 23

    const val U_MODULE_ID = 1
    const val U_VOL = 2
    const val U_MUTE = 3
    const val U_BAL_FADE = 8
    const val U_EQ_POINT = 9
    const val U_EQ_MODE = 10
    const val U_LOUD = 11
    const val U_SUB_GAIN = 26

    const val VOL_UP = -1
    const val VOL_DOWN = -2
    const val VOL_MUTE = -3
    const val VOL_UNMUTE = -4
    const val VOL_MUTE_SWITCH = -5

    /** Balanse og fader: 0–16, 8 = midten (cabin). */
    val AXIS = 0..16
    const val AXIS_CENTER = 8

    /** Kodene T'OS ber om (u=0 = spekteret – kommer tett og trengs ikke). */
    val CODES: List<Int> = (1..16).toList() + U_SUB_GAIN

    data class Profile(val name: String, val bands: Int, val gain: IntRange, val sub: Boolean) {
        val center get() = (gain.first + gain.last) / 2
    }

    fun profile(moduleId: Int?): Profile? = when (moduleId) {
        6 -> Profile("C32107", 32, 0..24, sub = false)
        7 -> Profile("C7602", 32, 0..24, sub = false)
        11 -> Profile("AKM7604", 36, 0..20, sub = true)
        else -> null
    }

    /** FinalSound.EQ_MODE_*: 0 egen … 8 kino. */
    val PRESETS = listOf("Egen", "Standard", "Rock", "Myk", "Klassisk", "Pop", "Konsertsal", "Jazz", "Kino")

    /** Det stereoen har meldt (bare gyldige verdier). */
    data class State(
        val moduleId: Int? = null,
        val bands: Map<Int, Int> = emptyMap(),
        val balance: Int? = null,
        val fader: Int? = null,
        val preset: Int? = null,
        val loud: Int? = null,
        val sub: Int? = null,
    ) {
        val profile get() = profile(moduleId)
    }

    /** Ny melding fra lydmodulen inn i [s]. Ny brikke-ID nullstiller alt (som cabin). Ugyldige verdier ignoreres. */
    fun merge(s: State, u: Int, ints: IntArray?): State {
        val a = ints ?: return s
        val i0 = a.firstOrNull() ?: return s
        return when (u) {
            U_MODULE_ID -> if (i0 == s.moduleId) s else State(moduleId = i0.takeIf { it in 0..255 })
            U_BAL_FADE -> if (a.size == 2 && a[0] in AXIS && a[1] in AXIS) s.copy(balance = a[0], fader = a[1]) else s
            U_EQ_POINT -> {
                val p = s.profile ?: return s
                if (a.size == 2 && a[0] in 0 until p.bands && a[1] in p.gain) s.copy(bands = s.bands + (a[0] to a[1])) else s
            }
            U_EQ_MODE -> if (a.size == 1 && i0 in PRESETS.indices) s.copy(preset = i0) else s
            U_LOUD -> if (a.size == 1 && i0 in 0..1) s.copy(loud = i0) else s
            U_SUB_GAIN -> if (a.size == 1 && i0 in 0..10 && s.profile?.sub == true) s.copy(sub = i0) else s
            else -> s
        }
    }

    /** Bass / mellomtone / diskant: båndene delt i tre (lave først – vanlig for grafiske equalizere, ikke bekreftet i Hz). */
    fun groups(bands: Int): List<IntRange> {
        if (bands <= 0) return emptyList()
        val a = bands * 5 / 16
        val b = bands * 11 / 16
        return listOf(0 until a, a until b, b until bands)
    }

    /** Snittet av båndene i [g], eller null hvis ett av dem ikke er meldt ennå. */
    fun groupLevel(bands: Map<Int, Int>, g: IntRange): Int? {
        if (g.isEmpty()) return null
        val v = g.map { bands[it] ?: return null }
        return Math.round(v.average()).toInt()
    }

    /** Flytt alle båndene i [g] like mye, så snittet blir [target] (formen på kurven beholdes, innenfor [gain]). */
    fun shiftGroup(bands: Map<Int, Int>, g: IntRange, target: Int, gain: IntRange): Map<Int, Int> {
        val now = groupLevel(bands, g) ?: return emptyMap()
        val d = target.coerceIn(gain) - now
        if (d == 0) return emptyMap()
        return g.associateWith { (bands.getValue(it) + d).coerceIn(gain) }.filter { (k, v) -> bands[k] != v }
    }

    /** Alle bånd i midten («flat»). Bare de som ikke allerede er der. */
    fun flat(bands: Map<Int, Int>, p: Profile): Map<Int, Int> =
        (0 until p.bands).associateWith { p.center }.filter { (k, v) -> bands[k] != v }

    /** Tall som vises for et bånd/gruppe: «+3», «0», «−2» (midten = 0). */
    fun signed(v: Int, center: Int): String {
        val d = v - center
        return when { d > 0 -> "+$d"; d < 0 -> "−${-d}"; else -> "0" }
    }

    /** Svarte stereoen på steget (volumet endret seg fra [before] til [now])? */
    fun stepAnswered(before: Int, now: Int): Boolean = before >= 0 && now != before

    /** Ett steg mot målet: +1, −1 eller 0 (framme). */
    fun stepToward(level: Int, target: Int): Int = when { target > level -> 1; target < level -> -1; else -> 0 }

    /** Punkt i balanse/fader-feltet (0–1 hver vei) → verdiene 0–16. */
    fun axisFrom(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * AXIS.last).roundToInt()

    fun axisFraction(v: Int): Float = (v.coerceIn(AXIS) - AXIS.first).toFloat() / (AXIS.last - AXIS.first)

    private val names = mapOf(0 to "spekter", 1 to "lydbrikke", 2 to "volum", 3 to "demping", 4 to "tastelyd", 5 to "oppstartsvolum av/på",
        6 to "oppstartsvolum", 7 to "demping ved rygging", 8 to "balanse/fader", 9 to "equalizer-bånd", 10 to "forhåndsvalg",
        11 to "loudness", 12 to "subwoofer (kilde)", 13 to "forsterker", 14 to "navigasjonsmiks", 15 to "annen lyd under navigasjon %",
        16 to "navigasjonsvolum %", 26 to "subwoofer")

    /** Navnet på en lyd-kode (FinalSound.U_*), til kartleggingen. */
    fun codeName(u: Int): String = names[u] ?: "ukjent"
}

object FytSound {
    private val h = Handler(Looper.getMainLooper())

    /** Robolectric-testene (ingen com.syu.ms): lar T'OS vise FYT-delene med falske verdier. */
    @Volatile internal var forTest = false

    internal var state by mutableStateOf(FytSoundLogic.State())
        private set
    /** FYT-volumet (lyd u=2), −1 = ikke meldt. */
    var level by mutableIntStateOf(-1)
        private set
    var muted by mutableStateOf(false)
        private set

    private val live get() = Fyt.connected || forTest
    /** T'OS kan styre FYT-volumet (stereoen har meldt et tall). */
    val volumeReady get() = live && level >= 0
    val max get() = FytVolume.max
    /** Stereoens lydbrikke er en T'OS kjenner (cabin sine bekreftede), og tilkoblet. */
    val pictureReady get() = live && state.profile != null

    /** Alt stereoen har meldt fra lydmodulen (kode → siste verdi), til kartleggingen. */
    private val raw = LinkedHashMap<Int, String>()

    @Volatile private var ownAt = 0L
    /** Endret T'OS selv FYT-volumet nettopp (glidebryter)? Da skal ikke volumlinja over FYT-vinduet sprette opp. */
    fun ownRecently(now: Long = SystemClock.elapsedRealtime()) = ownAt != 0L && now - ownAt < 1200
    private fun markOwn() { ownAt = SystemClock.elapsedRealtime() }

    /** Når hvert felt sist ble endret i T'OS: meldinger fra stereoen like etter tas ikke (de kan være gamle ekko). */
    private val editedAt = HashMap<String, Long>()
    private fun recentEdit(key: String) = editedAt[key]?.let { SystemClock.elapsedRealtime() - it < 700 } ?: false

    /** Melding fra lydmodulen (Fyt.apply, hovedtråden). */
    fun onUpdate(u: Int, ints: IntArray?) {
        synchronized(raw) {
            val k = if (u == FytSoundLogic.U_EQ_POINT) 9_000 + (ints?.firstOrNull() ?: -1) else u
            raw[k] = ints?.joinToString(",", "[", "]") ?: "null"
        }
        val i0 = ints?.firstOrNull()
        when (u) {
            FytSoundLogic.U_VOL -> if (i0 != null && i0 >= 0) {
                val before = level
                level = i0
                if (live) Ui.volume = if (muted) 0 else i0
                // Neste steg bare når tallet faktisk endret seg: sender stereoen samme tall to ganger (ekko), ville T'OS ellers
                // sendt et nytt steg før det forrige var gjort – og gått forbi målet. Uendret tall: tidsfristen tar det.
                if (target >= 0 && waiting && FytSoundLogic.stepAnswered(before, i0)) { misses = 0; pump() }
                return
            }
            FytSoundLogic.U_MUTE -> if (i0 != null) { muted = i0 != 0; if (live && level >= 0) Ui.volume = if (muted) 0 else level; return }
        }
        val key = when (u) {
            FytSoundLogic.U_BAL_FADE -> "balfade"
            FytSoundLogic.U_EQ_POINT -> "eq${i0 ?: -1}"
            FytSoundLogic.U_EQ_MODE -> "preset"
            FytSoundLogic.U_LOUD -> "loud"
            FytSoundLogic.U_SUB_GAIN -> "sub"
            else -> null
        }
        if (key != null && u != FytSoundLogic.U_MODULE_ID && recentEdit(key)) return
        state = FytSoundLogic.merge(state, u, ints)
    }

    /** Tilkoblingen ble borte / testene rydder. */
    internal fun reset() {
        state = FytSoundLogic.State(); level = -1; muted = false; target = -1; waiting = false
        h.removeCallbacks(timeout); h.removeCallbacks(flush); queue.clear(); editedAt.clear(); ownAt = 0L
        synchronized(raw) { raw.clear() }
    }

    /* ---------- Volum ---------- */

    private var target = -1
    private var firstDir = 0
    private var waiting = false
    private var misses = 0
    private var until = 0L
    private val timeout = Runnable { misses++; pump() }

    /** Ett steg opp/ned (fastvaren bestemmer steget og grensene). */
    fun step(dir: Int): Boolean {
        if (!volumeReady || dir == 0) return false
        markOwn()
        return Fyt.soundCmd(FytSoundLogic.C_VOL, intArrayOf(if (dir > 0) FytSoundLogic.VOL_UP else FytSoundLogic.VOL_DOWN))
    }

    fun toggleMute(): Boolean {
        if (!volumeReady) return false
        markOwn()
        return Fyt.soundCmd(FytSoundLogic.C_VOL, intArrayOf(FytSoundLogic.VOL_MUTE_SWITCH))
    }

    /**
     * Sett volumet til [n]: T'OS går ett steg om gangen og venter på at stereoen melder det nye tallet (høyst 300 ms),
     * til tallet stemmer. Stopper etter 3 steg uten svar, 6 s, eller hvis stereoen hopper forbi målet.
     */
    fun setVolume(n: Int) {
        if (!volumeReady) return
        val t = n.coerceIn(0, max)
        if (t == target) return
        target = t; misses = 0; firstDir = 0
        until = SystemClock.elapsedRealtime() + 6000
        if (muted && t > 0) { markOwn(); Fyt.soundCmd(FytSoundLogic.C_VOL, intArrayOf(FytSoundLogic.VOL_UNMUTE)) }
        if (!waiting) pump()
    }

    private fun pump() {
        waiting = false
        h.removeCallbacks(timeout)
        val dir = FytSoundLogic.stepToward(level, target)
        if (firstDir == 0) firstDir = dir
        if (target < 0 || dir == 0 || dir != firstDir || misses >= 3 || SystemClock.elapsedRealtime() > until) { target = -1; return }
        if (!step(dir)) { target = -1; return }
        waiting = true
        h.postDelayed(timeout, 300)
    }

    /* ---------- Lydbildet ---------- */

    private val queue = LinkedHashMap<String, IntArray>()
    private val codeOf = HashMap<String, Int>()
    private val flush = Runnable {
        val items = queue.entries.map { Triple(it.key, codeOf[it.key] ?: -1, it.value) }
        queue.clear()
        items.forEach { (_, code, ints) -> if (code >= 0) Fyt.soundCmd(code, ints) }
    }

    /** Send (samlet: høyst hvert 90. ms, bare siste verdi per felt – en glidebryter sender mange). */
    private fun send(key: String, code: Int, ints: IntArray) {
        editedAt[key] = SystemClock.elapsedRealtime()
        queue[key] = ints; codeOf[key] = code
        h.removeCallbacks(flush)
        h.postDelayed(flush, 90)
    }

    fun setBalanceFader(balance: Int, fader: Int) {
        val s = state
        if (!pictureReady || s.balance == null || s.fader == null) return
        val b = balance.coerceIn(FytSoundLogic.AXIS); val f = fader.coerceIn(FytSoundLogic.AXIS)
        if (b == s.balance && f == s.fader) return
        state = s.copy(balance = b, fader = f)
        send("balfade", FytSoundLogic.C_BAL_FADE, intArrayOf(b, f))
    }

    fun setPreset(n: Int) {
        if (!pictureReady || state.preset == null || n !in FytSoundLogic.PRESETS.indices) return
        state = state.copy(preset = n)
        send("preset", FytSoundLogic.C_EQ_MODE, intArrayOf(n))
    }

    fun setLoudness(on: Boolean) {
        if (!pictureReady || state.loud == null) return
        val v = if (on) 1 else 0
        state = state.copy(loud = v)
        send("loud", FytSoundLogic.C_LOUD, intArrayOf(v))
    }

    fun setSubwoofer(v: Int) {
        val p = state.profile ?: return
        if (!pictureReady || !p.sub || state.sub == null) return
        state = state.copy(sub = v.coerceIn(0, 10))
        send("sub", FytSoundLogic.C_SUB_GAIN, intArrayOf(v.coerceIn(0, 10)))
    }

    private fun setBands(changes: Map<Int, Int>) {
        if (changes.isEmpty()) return
        state = state.copy(bands = state.bands + changes)
        changes.forEach { (b, v) -> send("eq$b", FytSoundLogic.C_EQ_POINT, intArrayOf(b, v)) }
    }

    /** Bass (0), mellomtone (1) eller diskant (2) til [target] (snittet av båndene i gruppa). */
    fun setGroup(group: Int, target: Int) {
        val p = state.profile ?: return
        if (!pictureReady) return
        val g = FytSoundLogic.groups(p.bands).getOrNull(group) ?: return
        setBands(FytSoundLogic.shiftGroup(state.bands, g, target, p.gain))
    }

    /** Lydbildet i midten: balanse/fader i midten og alle bånd flate (forhåndsvalg og loudness røres ikke). */
    fun resetPicture() {
        val p = state.profile ?: return
        if (!pictureReady) return
        if (state.balance != null) setBalanceFader(FytSoundLogic.AXIS_CENTER, FytSoundLogic.AXIS_CENTER)
        setBands(FytSoundLogic.flat(state.bands, p))
    }

    /* ---------- Kartleggingen ---------- */

    fun report(): String = buildString {
        val s = state
        appendLine("Lydbrikke (u=1): ${s.moduleId ?: "ikke meldt"}" + (s.profile?.let { " = ${it.name}: ${it.bands} bånd ${it.gain.first}–${it.gain.last}" } ?: " (ukjent for T'OS – lydbildet vises ikke)"))
        appendLine("Volum: $level  dempet=$muted  T'OS styrer: ${if (volumeReady) "ja (steg −1/−2)" else "nei"}")
        appendLine("Balanse/fader: ${s.balance}/${s.fader}  forhåndsvalg: ${s.preset?.let { "$it ${FytSoundLogic.PRESETS.getOrNull(it)}" }}  loudness: ${s.loud}  subwoofer: ${s.sub}")
        s.profile?.let { p ->
            val g = FytSoundLogic.groups(p.bands)
            appendLine("Bånd meldt: ${s.bands.size}/${p.bands}  bass/mellom/diskant (snitt): " + g.joinToString(" / ") { "${FytSoundLogic.groupLevel(s.bands, it)}" })
            if (s.bands.isNotEmpty()) appendLine("Bånd: " + (0 until p.bands).joinToString(" ") { "${s.bands[it] ?: "–"}" })
        }
        val lines = synchronized(raw) { raw.entries.map { it.key to it.value } }
        appendLine("Alle koder fra lydmodulen (${lines.size}):")
        lines.filter { it.first < 9_000 }.sortedBy { it.first }.forEach { (u, v) -> appendLine("  u=$u ${FytSoundLogic.codeName(u)}: $v") }
        val eq = lines.filter { it.first >= 9_000 }
        if (eq.isNotEmpty()) appendLine("  u=9 equalizer-bånd: ${eq.size} meldinger, f.eks. " + eq.take(4).joinToString { it.second })
    }
}

/* ======================= UI ======================= */

/** Glidebryter for FYT-volumet: følger stereoen, men står der fingeren er mens du drar. */
@Composable
fun FytVolumeSlider(colors: SliderColors, modifier: Modifier = Modifier, onValue: (Int) -> Unit = {}) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val m = FytSound.max.coerceAtLeast(1)
    val shown = drag ?: (if (FytSound.muted) 0 else FytSound.level).coerceIn(0, m).toFloat()
    Slider(shown, {
        drag = it
        val v = it.roundToInt()
        onValue(v)
        FytSound.setVolume(v)
    }, onValueChangeFinished = { drag = null }, valueRange = 0f..m.toFloat(), colors = colors,
        modifier = modifier.semantics { contentDescription = "Volum" })
}

/** «Lydbilde» i Innstillinger → Lyd (bare på FYT, og bare det stereoen har meldt verdier for). */
@Composable
fun FytSoundPanel() {
    val s = FytSound.state
    val p = s.profile ?: return
    val colors = SliderDefaults.colors(thumbColor = TextPrimary, activeTrackColor = TextPrimary, inactiveTrackColor = Line)
    val hasAny = s.preset != null || s.loud != null || s.balance != null || s.bands.isNotEmpty() || s.sub != null
    Section("Lydbilde")
    if (!hasAny) { SettingRow("Venter på stereoen …", "Stereoen har ikke sendt lydinnstillingene ennå"); return }

    s.preset?.let { cur ->
        Text("Lydprofil", color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FytSoundLogic.PRESETS.forEachIndexed { i, name -> Pill(name, strong = i == cur) { FytSound.setPreset(i) } }
        }
        Spacer(Modifier.height(12.dp))
    }

    val names = listOf("Bass", "Mellomtone", "Diskant")
    FytSoundLogic.groups(p.bands).forEachIndexed { i, g ->
        val v = FytSoundLogic.groupLevel(s.bands, g) ?: return@forEachIndexed
        BandRow(names[i], v, p.gain, p.center, colors) { FytSound.setGroup(i, it) }
    }

    s.sub?.let { v -> BandRow("Subwoofer", v, 0..10, Int.MIN_VALUE, colors) { FytSound.setSubwoofer(it) } }

    s.loud?.let { v ->
        SettingRow("Loudness", "Mer bass og diskant på lavt volum", onClick = { FytSound.setLoudness(v == 0) }) { Toggle(v == 1) }
    }

    if (s.balance != null && s.fader != null) {
        Text("Balanse og fader", color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp))
        BalanceFaderPad(s.balance, s.fader)
        Spacer(Modifier.height(8.dp))
    }

    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(confirm) { if (confirm) { delay(3000); confirm = false } }
    SettingRow(if (confirm) "Trykk igjen for å nullstille" else "Nullstill lydbildet", "Balanse i midten og flat equalizer", onClick = {
        if (confirm) { FytSound.resetPicture(); confirm = false; Notify.show("Lydbildet er nullstilt") } else confirm = true
    })
}

/** Glider for en gruppe bånd. Tallet vises med midten som 0 ([center] = Int.MIN_VALUE: vis tallet som det er). */
@Composable
private fun BandRow(title: String, value: Int, range: IntRange, center: Int, colors: SliderColors, onChange: (Int) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val shown = drag ?: value.toFloat()
    val label = if (center == Int.MIN_VALUE) shown.roundToInt().toString() else FytSoundLogic.signed(shown.roundToInt(), center)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(title, color = TextPrimary, fontSize = 16.sp, modifier = Modifier.width(120.dp))
        Slider(shown, { drag = it; onChange(it.roundToInt()) }, onValueChangeFinished = { drag = null },
            valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first - 1).coerceAtLeast(0),
            colors = colors, modifier = Modifier.weight(1f).semantics { contentDescription = title })
        Text(label, color = TextPrimary, fontSize = 16.sp, modifier = Modifier.width(40.dp))
    }
    Spacer(Modifier.height(8.dp))
}

/** Balanse (sidelengs) og fader (opp/ned) i ett felt: trykk eller dra prikken. Retningen er ikke bekreftet på stereoen. */
@Composable
private fun BalanceFaderPad(balance: Int, fader: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(
            Modifier.width(260.dp).height(160.dp).clip(RoundedCornerShape(18.dp)).background(Card).border(1.dp, Line, RoundedCornerShape(18.dp))
                .semantics { contentDescription = "Balanse og fader" },
        ) {
            val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val hPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            val bw = maxWidth; val bh = maxHeight
            fun set(x: Float, y: Float) = FytSound.setBalanceFader(FytSoundLogic.axisFrom(x / wPx), FytSoundLogic.axisFrom(y / hPx))
            Box(Modifier.fillMaxWidth().fillMaxHeight()
                .pointerInput(wPx, hPx) { detectTapGestures { set(it.x, it.y) } }
                .pointerInput(wPx, hPx) { detectDragGestures { c, _ -> set(c.position.x, c.position.y) } }) {
                // Kryss i midten
                Box(Modifier.align(Alignment.Center).width(1.dp).fillMaxHeight().background(Line))
                Box(Modifier.align(Alignment.Center).fillMaxWidth().height(1.dp).background(Line))
                Text("Foran", color = TextMuted, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp))
                Text("Bak", color = TextMuted, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))
                val dot = 22.dp
                Box(Modifier.offset(
                    x = bw * FytSoundLogic.axisFraction(balance) - dot / 2,
                    y = bh * FytSoundLogic.axisFraction(fader) - dot / 2,
                ).size(dot).clip(CircleShape).background(TextPrimary))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Balanse ${FytSoundLogic.signed(balance, FytSoundLogic.AXIS_CENTER)}", color = TextPrimary, fontSize = 15.sp)
            Text("Fader ${FytSoundLogic.signed(fader, FytSoundLogic.AXIS_CENTER)}", color = TextPrimary, fontSize = 15.sp)
            Pill("Midten") { FytSound.setBalanceFader(FytSoundLogic.AXIS_CENTER, FytSoundLogic.AXIS_CENTER) }
        }
    }
}
