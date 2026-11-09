package no.bil.launcher

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/*
 * «Selvtest av stereoen» (Innstillinger → Sikkerhetskopi og hjelp, ved «Kartlegg stereoen").
 *
 * Mye av FYT-støtten er laget etter offentlige kilder og ikke prøvd på denne stereoen. Selvtesten sjekker selv det
 * som går an uten å ødelegge noe, og viser ✓ / ✗ / ? per del med én linje forklaring:
 *  - bare lesing: kontakt, moduler, meldinger fra hoved/radio/BT/lyd/ratt, lys/rygging/tenning, canbus-id,
 *    nedtrekksmeny/fanevisning sist sett, tillatelser;
 *  - små prøver som settes tilbake med en gang: lysstyrke +5 og tilbake (svarer stereoen med u=31?), volum ett steg
 *    og tilbake (u=2, bare når volumet er 30 eller lavere og ingen samtale), spør etter parede telefoner (BT u=5);
 *  - valgfrie prøver brukeren starter: rattknapp (10 s), lysene (15 s), radio (bytt til radio, trinn opp og ned,
 *    tilbake til forrige kilde).
 * Ingenting lagres i stereoen: forvalg, equalizer og lærte rattknapper røres ikke.
 * Siste resultat lagres i en fil og står i kartleggingen (SystemSurvey).
 */

internal enum class Mark(val sign: String) { OK("✓"), FAIL("✗"), UNKNOWN("?") }

internal data class Check(val id: String, val title: String, val mark: Mark, val detail: String)

/** Det T'OS vet om stereoen akkurat nå. Lages av [FytProbe] (eller av testene). */
internal data class Snapshot(
    val fytPresent: Boolean = false,
    val connected: Boolean = false,
    val status: String = "",
    val modules: List<Int> = emptyList(),
    /** Modul → antall meldinger stereoen har sendt. */
    val messages: Map<Int, Int> = emptyMap(),
    /** Kodene stereoen har sendt minst én gang ([SelfTestLogic.key]). */
    val codesSeen: Set<Int> = emptySet(),
    val appId: Int = -1,
    val radioBand: Int = Int.MIN_VALUE,
    val radioFreq: Int = -1,
    val radioPs: String? = null,
    val phoneState: Int = -1,
    val phoneName: String? = null,
    val volume: Int = -1,
    val muted: Boolean = false,
    val steerSeen: Boolean = false,
    val steerLearned: Int = 0,
    val lights: Boolean? = null,
    val reversing: Boolean = false,
    val acc: Boolean? = null,
    val brightLevel: Int = -1,
    val brightOn: Boolean = true,
    val canId: Int = -1,
    val shadeSeen: String = "",
    val recentsSeen: String = "",
    val keyServiceEnabled: Boolean = false,
    val keyServiceRunning: Boolean = false,
    val notifAccess: Boolean = false,
    val usageAccess: Boolean = false,
    val overlay: Boolean = false,
    val inCall: Boolean = false,
)

/** Ren logikk (testes i SelfTestTest). */
internal object SelfTestLogic {
    const val BRIGHT_TIMEOUT_MS = 2_000L
    const val VOLUME_TIMEOUT_MS = 1_500L
    const val PAIR_TIMEOUT_MS = 4_000L
    const val SOURCE_TIMEOUT_MS = 4_000L
    const val RADIO_STEP_TIMEOUT_MS = 3_000L
    const val STEER_LISTEN_MS = 10_000L
    const val LIGHTS_LISTEN_MS = 15_000L
    /** Volumprøven kjøres bare når volumet er så lavt (ett steg merkes knapt). */
    const val VOLUME_MAX_FOR_TEST = 30
    const val BRIGHT_STEP = 5

    fun key(m: Int, u: Int) = m * 10_000 + u

    /** Rekkefølgen radene vises i (nye rader havner der de hører til, også etter de valgfrie prøvene). */
    val ORDER = listOf("fyt", "modules", "main", "radio", "bt", "sound", "steer", "lights", "reverse", "acc", "canbus",
        "bright", "volume", "pairs", "steer_live", "lights_live", "radio_source", "radio_step", "radio_back",
        "shade", "recents", "access", "notif", "usage", "overlay")

    enum class Echo { WAITING, CONFIRMED, NO_ANSWER }

    /** Har stereoen meldt tilbake akkurat [target]? */
    fun echo(target: Int, now: Int, elapsedMs: Long, timeoutMs: Long): Echo = when {
        now == target -> Echo.CONFIRMED
        elapsedMs >= timeoutMs -> Echo.NO_ANSWER
        else -> Echo.WAITING
    }

    /** Har tallet flyttet seg i retning [dir] (+1/−1) fra [before]? Fastvaren bestemmer hvor stort et steg er. */
    fun stepEcho(before: Int, now: Int, dir: Int, elapsedMs: Long, timeoutMs: Long): Echo = when {
        before >= 0 && now >= 0 && dir != 0 && Integer.signum(now - before) == Integer.signum(dir) -> Echo.CONFIRMED
        elapsedMs >= timeoutMs -> Echo.NO_ANSWER
        else -> Echo.WAITING
    }

    /** Lysstyrken prøven setter: +5, eller −5 når den allerede er nesten full. */
    fun brightTarget(before: Int): Int =
        if (before + BRIGHT_STEP <= 100) (before + BRIGHT_STEP).coerceAtLeast(FytMainLogic.MIN_LEVEL) else before - BRIGHT_STEP

    /** Hvorfor volumprøven hoppes over (null = kan kjøres). */
    fun volumeSkip(level: Int, muted: Boolean, inCall: Boolean, reversing: Boolean = false): String? = when {
        level < 0 -> "Stereoen har ikke meldt volumet – prøven hoppes over"
        inCall -> "Samtale pågår – volumet røres ikke"
        reversing -> "Bilen rygger – volumet røres ikke"
        muted -> "Lyden er av – volumet røres ikke"
        level > VOLUME_MAX_FOR_TEST -> "Volumet er høyt ($level) – prøven kjøres bare på $VOLUME_MAX_FOR_TEST eller lavere"
        else -> null
    }

    /** Volumprøven går opp, eller ned når volumet står på det høyeste. */
    fun volumeDir(level: Int, max: Int): Int = if (level >= max) -1 else 1

    fun brightCheck(before: Int, target: Int, there: Echo, back: Echo): Check = when {
        there == Echo.CONFIRMED && back == Echo.CONFIRMED ->
            Check("bright", "Lysstyrke", Mark.OK, "Stereoen gikk fra $before til $target og tilbake – lysstyrken styres fra T'OS")
        there == Echo.CONFIRMED ->
            Check("bright", "Lysstyrke", Mark.UNKNOWN, "Endringen virket, men stereoen meldte ikke tilbake $before – sjekk lysstyrken")
        else -> Check("bright", "Lysstyrke", Mark.UNKNOWN,
            "Stereoen meldte ikke tilbake ($before → $target). Så du at skjermen ble litt lysere et øyeblikk?")
    }

    fun volumeCheck(before: Int, there: Echo, after: Int, back: Echo): Check = when {
        there == Echo.CONFIRMED && back == Echo.CONFIRMED ->
            Check("volume", "Volum fra T'OS", Mark.OK, "Ett steg og tilbake: $before → ${if (after >= 0) after else "?"} → $before")
        there == Echo.CONFIRMED ->
            Check("volume", "Volum fra T'OS", Mark.UNKNOWN, "Steget virket, men volumet kom ikke helt tilbake til $before – sjekk volumet")
        else -> Check("volume", "Volum fra T'OS", Mark.FAIL, "Stereoen svarte ikke på volumsteget (lydmodulen, kode 0)")
    }

    fun pairCheck(answered: Boolean, devices: Int): Check =
        if (answered) Check("pairs", "Parede telefoner", Mark.OK, "Stereoen sendte lista" + if (devices > 0) " ($devices enhet${if (devices == 1) "" else "er"})" else "")
        else Check("pairs", "Parede telefoner", Mark.UNKNOWN, "Ingen svar på spørsmålet om parede telefoner (BT kode 5)")

    /** Et trykk på rattet: tall fra rattmodulen (≠ 50 = ingen knapp) eller en knappekode fra hovedmodulen. */
    fun isSteerSignal(m: Int, u: Int, ints: IntArray?): Boolean {
        val v = ints?.firstOrNull() ?: return false
        return when (m) {
            Fyt.M_STEER -> u == 2 && v in 0..254 && v != SteerAdc.NO_KEY
            Fyt.M_MAIN -> u in SteerLogic.MAIN_EVENT_CODES
            else -> false
        }
    }

    /** Lysene byttet tilstand (eller ble meldt for første gang som på). */
    fun lightsChanged(before: Boolean?, now: Boolean?): Boolean = now != null && now != before && !(before == null && !now)

    /** Hva radioprøven gjør etterpå: null = ingenting (radioen var på), ellers kilden som slås på igjen (0 = radio av). */
    /** Radioprøven venter høyst så lenge på at en samtale blir ferdig før kilden settes tilbake. */
    const val CALL_WAIT_MS = 30 * 60_000L

    fun restoreSource(prev: Int): Int? = when (prev) {
        1, 11 -> null
        -1, 0 -> 0
        else -> prev
    }

    /** De automatiske radene som bare leser. */
    fun passive(s: Snapshot): List<Check> = buildList {
        add(when {
            s.connected -> Check("fyt", "Kontakt med stereoen", Mark.OK, "T'OS snakker med stereoens tjeneste (com.syu.ms)")
            s.fytPresent -> Check("fyt", "Kontakt med stereoen", Mark.FAIL, "Stereoen svarer ikke: ${s.status.ifBlank { "ukjent" }}")
            else -> Check("fyt", "Kontakt med stereoen", Mark.FAIL, "Fant ikke com.syu.ms – dette er ikke en FYT-stereo")
        })
        if (s.connected) {
            val want = listOf(Fyt.M_MAIN, Fyt.M_RADIO, Fyt.M_BT, Fyt.M_SOUND, Fyt.M_STEER)
            val missing = want.filter { it !in s.modules }
            add(Check("modules", "Deler av stereoen", if (missing.isEmpty()) Mark.OK else Mark.UNKNOWN,
                if (missing.isEmpty()) "Alle svarer: hoved, radio, BT, lyd og ratt (${s.modules.size} i alt)"
                else "Svarer ikke: " + missing.joinToString { FytLogic.moduleName(it) }))
            val main = s.messages[Fyt.M_MAIN] ?: 0
            add(Check("main", "Hoveddelen", if (main > 0 || s.appId >= 0) Mark.OK else Mark.UNKNOWN,
                if (s.appId >= 0) "Lydkilde nå: ${FytLogic.sourceName(s.appId)}" else "Ingen meldinger ennå"))
            add(if (s.radioFreq > 0) Check("radio", "Radio (bare lest)", Mark.OK,
                FytLogic.freqLabel(s.radioBand, s.radioFreq) + (s.radioPs?.let { " · «$it»" } ?: ""))
            else Check("radio", "Radio (bare lest)", Mark.UNKNOWN, "Stereoen har ikke meldt frekvensen – trykk «Test radio»"))
            add(if (s.phoneState >= 0) Check("bt", "Bluetooth-telefon", Mark.OK,
                if (FytLogic.phoneConnected(s.phoneState) == true) "Tilkoblet" + (s.phoneName?.let { ": $it" } ?: "")
                else "Stereoen sier: ${FytLogic.phoneStateName(s.phoneState).trim('(', ')').ifBlank { "tilstand ${s.phoneState}" }}")
            else Check("bt", "Bluetooth-telefon", Mark.UNKNOWN, "Ingen melding om telefonen fra stereoen"))
            add(if (s.volume >= 0) Check("sound", "Lyd", Mark.OK, "Volum ${s.volume}" + if (s.muted) " (lyden er av)" else "")
            else Check("sound", "Lyd", Mark.UNKNOWN, "Stereoen har ikke meldt volumet"))
            val steer = s.messages[Fyt.M_STEER] ?: 0
            add(Check("steer", "Rattmodulen", if (steer > 0 || s.steerSeen) Mark.OK else Mark.UNKNOWN,
                if (steer > 0 || s.steerSeen) "Svarer" + if (s.steerLearned > 0) " – ${s.steerLearned} knapper lært i stereoen" else " – ingen knapper lært ennå"
                else "Ingen meldinger fra rattmodulen – trykk «Rattknapp»"))
            add(if (s.lights != null) Check("lights", "Lys (ILL)", Mark.OK, "Stereoen sier lysene er ${if (s.lights) "på" else "av"}")
            else Check("lights", "Lys (ILL)", Mark.UNKNOWN, "Ikke meldt – trykk «Lysene» og slå på lysene"))
            add(if (key(Fyt.M_MAIN, FytMainLogic.U_BACKCAR) in s.codesSeen) Check("reverse", "Rygging", Mark.OK,
                if (s.reversing) "Bilen rygger nå" else "Meldt: rygger ikke")
            else Check("reverse", "Rygging", Mark.UNKNOWN, "Ikke meldt ennå (vises når du setter i revers)"))
            add(if (s.acc != null) Check("acc", "Tenning (ACC)", Mark.OK, "Stereoen sier tenningen er ${if (s.acc) "på" else "av"}")
            else Check("acc", "Tenning (ACC)", Mark.UNKNOWN, "Ikke meldt ennå"))
            add(if (s.canId >= 0) {
                val id = FytCanLogic.CanId(s.canId)
                Check("canbus", "Canbus-boks", Mark.OK,
                    if (id.none) "Ingen canbus-boks valgt (rattet er analogt) – id ${s.canId}" else "Bil ${id.car}, protokoll ${id.protocol}")
            } else Check("canbus", "Canbus-boks", Mark.UNKNOWN, "Stereoen har ikke meldt canbus-id"))
        }
        add(if (s.shadeSeen.isNotBlank()) Check("shade", "Nedtrekksmenyen gjenkjent", Mark.OK, "Sist: ${s.shadeSeen.take(60)}")
        else Check("shade", "Nedtrekksmenyen gjenkjent", Mark.UNKNOWN, "Ikke sett ennå – dra ned fra toppen én gang utenfor T'OS"))
        add(if (s.recentsSeen.isNotBlank()) Check("recents", "Fanevisningen gjenkjent", Mark.OK, "Sist: ${s.recentsSeen.take(60)}")
        else Check("recents", "Fanevisningen gjenkjent", Mark.UNKNOWN, "Ikke sett ennå – trykk «siste apper» i stereoens linje én gang"))
        add(when {
            s.keyServiceRunning -> Check("access", "T'OS knapper (tilgjengelighet)", Mark.OK, "På og kjører")
            s.keyServiceEnabled -> Check("access", "T'OS knapper (tilgjengelighet)", Mark.FAIL, "Slått på, men kjører ikke – slå av og på igjen")
            else -> Check("access", "T'OS knapper (tilgjengelighet)", Mark.FAIL, "Av – knappene, volumet og menyen virker ikke uten")
        })
        add(Check("notif", "Varseltilgang", if (s.notifAccess) Mark.OK else Mark.FAIL,
            if (s.notifAccess) "På (sangtittel, varsler)" else "Av – sangtittel og varsler vises ikke"))
        add(Check("usage", "Tilgang til appbruk", if (s.usageAccess) Mark.OK else Mark.UNKNOWN,
            if (s.usageAccess) "På («Siste apper»)" else "Av – «Siste apper» bruker T'OS sin egen liste"))
        add(Check("overlay", "Vis over andre apper", if (s.overlay) Mark.OK else Mark.UNKNOWN,
            if (s.overlay) "På" else "Av – bare en reserve (T'OS knapper gjør jobben)"))
    }

    /** Legg nye rader inn (samme id erstattes) i fast rekkefølge. */
    fun merge(old: List<Check>, new: List<Check>): List<Check> {
        val map = LinkedHashMap<String, Check>()
        old.forEach { map[it.id] = it }
        new.forEach { map[it.id] = it }
        return map.values.sortedBy { ORDER.indexOf(it.id).let { i -> if (i < 0) ORDER.size else i } }
    }

    fun count(checks: List<Check>, m: Mark) = checks.count { it.mark == m }

    /** «12 av 15 virker». */
    fun summary(checks: List<Check>): String = "${count(checks, Mark.OK)} av ${checks.size} virker"

    /** Linja under: hvor mange som ikke virker / er usikre. */
    fun summaryDetail(checks: List<Check>): String {
        val f = count(checks, Mark.FAIL); val u = count(checks, Mark.UNKNOWN)
        return listOfNotNull(if (f > 0) "$f virker ikke" else null, if (u > 0) "$u usikre (?)" else null)
            .joinToString(" · ").ifEmpty { if (checks.isEmpty()) "" else "Alt virker" }
    }

    fun report(checks: List<Check>, stamp: String, version: String): String = buildString {
        appendLine("T'OS selvtest $stamp (T'OS $version)")
        appendLine(summary(checks) + summaryDetail(checks).let { if (it.isEmpty()) "" else " – $it" })
        checks.forEach { appendLine("${it.mark.sign} ${it.title}: ${it.detail}") }
    }
}

/** Det selvtesten leser og gjør. Byttes ut i testene (ingen stereo der). */
internal interface SelfProbe {
    fun snapshot(c: Context): Snapshot
    fun bright(): Int
    fun sendBright(level: Int)
    fun volume(): Int
    fun volumeMax(): Int
    fun volumeStep(dir: Int): Boolean
    fun setVolume(n: Int)
    fun pairDevices(): Int
    fun queryPairs(): Boolean
    fun appId(): Int
    fun radioFreq(): Int
    fun radioOn(): Boolean
    fun radioStep(up: Boolean): Boolean
    fun setSource(id: Int): Boolean
    fun lights(): Boolean?
    fun steerKeyAt(): Long
    /** Samtale nå (FYT eller Android) – radioprøven bytter ikke kilde midt i en samtale. */
    fun inCallNow(): Boolean = false
    /** Bilen rygger (stereoen kan dempe lyden da) – volumprøven hoppes over. */
    fun reversingNow(): Boolean = false
}

/** Den ekte stereoen (Fyt, FytMain, FytSound, FytPhone …). */
internal object FytProbe : SelfProbe {
    override fun snapshot(c: Context) = Snapshot(
        fytPresent = Fyt.present(c),
        connected = Fyt.connected,
        status = Fyt.status,
        modules = Fyt.moduleIds,
        messages = SelfTest.messageCounts(),
        codesSeen = SelfTest.codesSeen(),
        appId = Fyt.appId,
        radioBand = Fyt.radioBandRaw,
        radioFreq = Fyt.radioFreqRaw,
        radioPs = Fyt.radioPs,
        phoneState = Fyt.phoneState,
        phoneName = Fyt.phoneName,
        volume = FytSound.level.takeIf { it >= 0 } ?: Fyt.volume,
        muted = Fyt.muted,
        steerSeen = FytSteer.seen,
        steerLearned = FytSteer.learned.values.count { SteerAdc.isLearnedValue(it) },
        lights = FytMain.lights,
        reversing = FytMain.reversing,
        acc = FytMain.acc,
        brightLevel = FytMain.brightLevel,
        brightOn = FytMain.brightReady,
        canId = FytCan.canId,
        shadeSeen = Prefs.str("shade_seen"),
        recentsSeen = Prefs.str("recents_seen"),
        keyServiceEnabled = runCatching { Buttons.serviceEnabled(c) }.getOrDefault(false),
        keyServiceRunning = KeyService.instance != null,
        notifAccess = runCatching { MediaListener.enabled(c) }.getOrDefault(false),
        usageAccess = RecentApps.hasUsageAccess(c),
        overlay = runCatching { Settings.canDrawOverlays(c) }.getOrDefault(false),
        inCall = runCatching { Buttons.inCall(c) }.getOrDefault(false) || Fyt.inCall,
    )
    override fun bright() = FytMain.brightLevel
    override fun sendBright(level: Int) = FytMain.sendLevel(level)
    override fun volume() = FytSound.level
    override fun volumeMax() = FytSound.max
    override fun volumeStep(dir: Int) = FytSound.step(dir)
    override fun setVolume(n: Int) = FytSound.setVolume(n)
    override fun pairDevices() = FytPhone.devices.size
    override fun queryPairs() = FytPhone.queryDevices() || Fyt.btCmd(FytPhoneLogic.C_QUERY_PAIR)
    override fun appId() = Fyt.appId
    override fun radioFreq() = Fyt.radioFreqRaw
    override fun radioOn() = Fyt.radioOn()
    override fun radioStep(up: Boolean) = Fyt.radioStep(up)
    override fun setSource(id: Int): Boolean = when (id) {
        0 -> Fyt.radioOff()
        3 -> Fyt.btavOn()
        10 -> Fyt.androidSource()
        else -> Fyt.moduleCmd(Fyt.M_MAIN, 0, intArrayOf(id))
    }
    override fun lights() = FytMain.lights
    override fun steerKeyAt() = Steer.lastAt
    override fun inCallNow() = Fyt.inCall || (Fyt.appContext?.let { c -> runCatching { Buttons.inCall(c) }.getOrDefault(false) } ?: false)
    override fun reversingNow() = FytMain.reversing
}

object SelfTest {
    internal var probe: SelfProbe = FytProbe

    /** Radene (siste kjøring). */
    internal var checks by mutableStateOf<List<Check>>(emptyList())
    /** Hva som testes nå (null = ingenting). */
    var phase by mutableStateOf<String?>(null); private set
    /** Når siste kjøring var ferdig («21:04»). */
    var doneAt by mutableStateOf<String?>(null); private set

    /** En valgfri prøve som venter på brukeren (rattknapp/lys): tittel, hva du skal gjøre, og når den gir opp. */
    data class Live(val title: String, val text: String, val endsAt: Long)
    var live by mutableStateOf<Live?>(null); private set

    private var job: Job? = null
    val busy get() = job?.isActive == true

    /* ---------- Meldinger fra stereoen (Fyt.onUpdate, Binder-tråden) ---------- */

    private val counts = ConcurrentHashMap<Int, Int>()
    private val seen: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    @Volatile private var listenSteer = false
    @Volatile private var steerHit: String? = null

    /** Alt FYT sender kommer innom her (én linje i Fyt.onUpdate). Bare telling – ingen arbeid. */
    fun onFyt(m: Int, u: Int, ints: IntArray?) {
        counts.merge(m, 1, Int::plus)
        seen.add(SelfTestLogic.key(m, u))
        if (listenSteer && steerHit == null && SelfTestLogic.isSteerSignal(m, u, ints)) {
            steerHit = if (m == Fyt.M_STEER) "tall ${ints?.firstOrNull()} fra rattmodulen" else "knappekode fra hovedmodulen (u=$u)"
        }
    }

    internal fun messageCounts(): Map<Int, Int> = HashMap(counts)
    internal fun codesSeen(): Set<Int> = HashSet(seen)

    /* ---------- Kjøring ---------- */

    private val now get() = SystemClock.elapsedRealtime()

    private suspend fun waitEcho(timeoutMs: Long, check: (elapsed: Long) -> SelfTestLogic.Echo): SelfTestLogic.Echo {
        val start = now
        while (true) {
            val e = check(now - start)
            if (e != SelfTestLogic.Echo.WAITING) return e
            delay(100)
        }
    }

    private fun add(vararg c: Check) { checks = SelfTestLogic.merge(checks, c.toList()) }

    /** De automatiske prøvene (åpnes siden, kjøres de; «Kjør på nytt» kjører dem igjen). */
    fun runAuto(c: Context) {
        if (busy) return
        val a = c.applicationContext
        job = Hub.scope.launch {
            try {
                phase = "Leser stereoen …"
                val s = probe.snapshot(a)
                checks = SelfTestLogic.merge(emptyList(), SelfTestLogic.passive(s))
                if (s.connected) {
                    brightTest(s)
                    volumeTest(s)
                    pairTest(s)
                }
                finish(a)
            } finally { phase = null }
        }
    }

    private suspend fun brightTest(s: Snapshot) {
        if (s.brightLevel < 0) { add(Check("bright", "Lysstyrke", Mark.UNKNOWN, "Stereoen har ikke meldt lysstyrken (u=31) – prøven hoppes over")); return }
        if (!s.brightOn) { add(Check("bright", "Lysstyrke", Mark.UNKNOWN, "Lysstyrke via stereoen er slått av i Innstillinger – prøven hoppes over")); return }
        phase = "Prøver lysstyrken …"
        val before = s.brightLevel
        val target = SelfTestLogic.brightTarget(before)
        var there = SelfTestLogic.Echo.NO_ANSWER
        try {
            probe.sendBright(target)
            there = waitEcho(SelfTestLogic.BRIGHT_TIMEOUT_MS) { SelfTestLogic.echo(target, probe.bright(), it, SelfTestLogic.BRIGHT_TIMEOUT_MS) }
        } finally {
            delay(200)
            probe.sendBright(before)   // alltid tilbake, også om stereoen ikke svarte
        }
        val back = waitEcho(SelfTestLogic.BRIGHT_TIMEOUT_MS) { SelfTestLogic.echo(before, probe.bright(), it, SelfTestLogic.BRIGHT_TIMEOUT_MS) }
        add(SelfTestLogic.brightCheck(before, target, there, back))
    }

    private suspend fun volumeTest(s: Snapshot) {
        val level = probe.volume().takeIf { it >= 0 } ?: s.volume
        SelfTestLogic.volumeSkip(level, s.muted, s.inCall || probe.inCallNow(), probe.reversingNow())?.let { add(Check("volume", "Volum fra T'OS", Mark.UNKNOWN, it)); return }
        phase = "Prøver volumet (ett steg) …"
        val dir = SelfTestLogic.volumeDir(level, probe.volumeMax())
        probe.volumeStep(dir)
        val there = waitEcho(SelfTestLogic.VOLUME_TIMEOUT_MS) { SelfTestLogic.stepEcho(level, probe.volume(), dir, it, SelfTestLogic.VOLUME_TIMEOUT_MS) }
        val after = probe.volume()
        var back = SelfTestLogic.Echo.CONFIRMED
        if (there == SelfTestLogic.Echo.CONFIRMED) {
            probe.volumeStep(-dir)
            back = waitEcho(SelfTestLogic.VOLUME_TIMEOUT_MS) { SelfTestLogic.echo(level, probe.volume(), it, SelfTestLogic.VOLUME_TIMEOUT_MS) }
            if (back != SelfTestLogic.Echo.CONFIRMED) probe.setVolume(level)   // reserve: gå steg for steg til det gamle tallet
        }
        add(SelfTestLogic.volumeCheck(level, there, after, back))
    }

    private suspend fun pairTest(s: Snapshot) {
        if (Fyt.M_BT !in s.modules) return
        phase = "Spør etter parede telefoner …"
        val k = SelfTestLogic.key(Fyt.M_BT, FytPhoneLogic.U_PAIR_LIST)
        val before = counts[Fyt.M_BT] ?: 0
        val hadList = k in seen
        if (!probe.queryPairs()) { add(SelfTestLogic.pairCheck(false, 0)); return }
        val start = now
        var answered = false
        // Svar = kode 5 kom (første gang), eller BT-modulen sendte noe nytt etter spørsmålet og lista finnes
        while (now - start < SelfTestLogic.PAIR_TIMEOUT_MS) {
            if ((!hadList && k in seen) || (hadList && (counts[Fyt.M_BT] ?: 0) > before)) { answered = true; break }
            delay(100)
        }
        add(SelfTestLogic.pairCheck(answered, probe.pairDevices()))
    }

    /** «Trykk en rattknapp nå» (10 s). */
    fun runSteer(c: Context) = interactive(c) {
        val key0 = probe.steerKeyAt()
        steerHit = null; listenSteer = true
        live = Live("Trykk en rattknapp nå", "Hvilken som helst – f.eks. volum opp.", now + SelfTestLogic.STEER_LISTEN_MS)
        try {
            val start = now
            while (now - start < SelfTestLogic.STEER_LISTEN_MS && steerHit == null && probe.steerKeyAt() == key0) delay(100)
        } finally { listenSteer = false; live = null }
        val hit = steerHit ?: if (probe.steerKeyAt() != key0) "rattknapp som medieknapp fra Android" else null
        add(if (hit != null) Check("steer_live", "Rattknapp", Mark.OK, "Stereoen merket trykket: $hit")
            else Check("steer_live", "Rattknapp", Mark.FAIL, "Ingen signal på 10 s – rattknappene er kanskje ikke lært eller koblet til"))
    }

    /** «Slå på lysene nå» (15 s). */
    fun runLights(c: Context) = interactive(c) {
        val before = probe.lights()
        live = Live(if (before == true) "Slå av lysene nå" else "Slå på lysene nå", "Bryteren for lyktene i bilen (parkeringslys holder).",
            now + SelfTestLogic.LIGHTS_LISTEN_MS)
        try {
            val start = now
            while (now - start < SelfTestLogic.LIGHTS_LISTEN_MS && !SelfTestLogic.lightsChanged(before, probe.lights())) delay(100)
        } finally { live = null }
        val after = probe.lights()
        add(if (SelfTestLogic.lightsChanged(before, after)) Check("lights_live", "Lysene", Mark.OK, "Stereoen merket at lysene ble slått ${if (after == true) "på" else "av"}")
            else Check("lights_live", "Lysene", Mark.FAIL, "Stereoen meldte ingen endring på 15 s – lysledningen (ILL) er kanskje ikke koblet til"))
    }

    /** «Test radio»: bytt til radio, trinn opp og ned, tilbake til kilden du hadde. */
    fun runRadio(c: Context) = interactive(c) {
        if (probe.snapshot(c).inCall) { add(Check("radio_source", "Radio: bytte kilde", Mark.UNKNOWN, "Samtale pågår – prøv igjen etterpå")); return@interactive }
        val prev = probe.appId()
        val restore = SelfTestLogic.restoreSource(prev)
        try {
            if (restore != null) {
                phase = "Bytter til radio …"
                probe.radioOn()
                val on = waitEcho(SelfTestLogic.SOURCE_TIMEOUT_MS) { e ->
                    val id = probe.appId()
                    if (id == 1 || id == 11) SelfTestLogic.Echo.CONFIRMED else if (e >= SelfTestLogic.SOURCE_TIMEOUT_MS) SelfTestLogic.Echo.NO_ANSWER else SelfTestLogic.Echo.WAITING
                }
                add(if (on == SelfTestLogic.Echo.CONFIRMED) Check("radio_source", "Radio: bytte kilde", Mark.OK, "Stereoen byttet til radio")
                    else Check("radio_source", "Radio: bytte kilde", Mark.FAIL, "Stereoen byttet ikke til radio (kilde ${FytLogic.sourceName(probe.appId())})"))
            } else add(Check("radio_source", "Radio: bytte kilde", Mark.OK, "Radioen var allerede på"))
            delay(600)
            phase = "Trinn opp og ned …"
            val f0 = probe.radioFreq()
            probe.radioStep(true)
            val up = waitEcho(SelfTestLogic.RADIO_STEP_TIMEOUT_MS) { SelfTestLogic.stepEcho(f0, probe.radioFreq(), 1, it, SelfTestLogic.RADIO_STEP_TIMEOUT_MS) }
            val f1 = probe.radioFreq()
            var down = SelfTestLogic.Echo.NO_ANSWER
            if (up == SelfTestLogic.Echo.CONFIRMED) {
                probe.radioStep(false)
                down = waitEcho(SelfTestLogic.RADIO_STEP_TIMEOUT_MS) { SelfTestLogic.echo(f0, probe.radioFreq(), it, SelfTestLogic.RADIO_STEP_TIMEOUT_MS) }
            }
            add(when {
                up == SelfTestLogic.Echo.CONFIRMED && down == SelfTestLogic.Echo.CONFIRMED -> Check("radio_step", "Radio: trinn", Mark.OK,
                    "${FytLogic.freqText(Fyt.radioBandRaw, f0)} → ${FytLogic.freqText(Fyt.radioBandRaw, f1)} → tilbake")
                up == SelfTestLogic.Echo.CONFIRMED -> Check("radio_step", "Radio: trinn", Mark.UNKNOWN, "Trinn opp virket, men ikke tilbake – sjekk frekvensen")
                else -> Check("radio_step", "Radio: trinn", Mark.FAIL, "Frekvensen endret seg ikke (radio kode 3)")
            })
        } finally {
            if (restore != null) {
                // Kom det en samtale mens prøven gikk: ikke bytt kilde midt i den – vent til den er over (høyst 30 min)
                if (probe.inCallNow()) {
                    phase = "Venter til samtalen er ferdig …"
                    val t0 = now
                    while (probe.inCallNow() && now - t0 < SelfTestLogic.CALL_WAIT_MS) delay(500)
                    delay(1500)   // stereoen setter selv tilbake kilden etter samtalen – gi den tid først
                }
                phase = "Tilbake til ${FytLogic.sourceName(prev)} …"
                delay(400)
                probe.setSource(restore)
                val back = waitEcho(SelfTestLogic.SOURCE_TIMEOUT_MS) { e ->
                    val id = probe.appId()
                    if (if (restore == 0) id != 1 && id != 11 else id == prev) SelfTestLogic.Echo.CONFIRMED
                    else if (e >= SelfTestLogic.SOURCE_TIMEOUT_MS) SelfTestLogic.Echo.NO_ANSWER else SelfTestLogic.Echo.WAITING
                }
                add(if (back == SelfTestLogic.Echo.CONFIRMED) Check("radio_back", "Radio: tilbake", Mark.OK, "Tilbake til ${FytLogic.sourceName(prev)}")
                    else Check("radio_back", "Radio: tilbake", Mark.UNKNOWN, "Stereoen meldte ikke at ${FytLogic.sourceName(prev)} kom tilbake – velg kilden selv"))
            }
        }
    }

    private fun interactive(c: Context, block: suspend () -> Unit) {
        if (busy) return
        val a = c.applicationContext
        job = Hub.scope.launch {
            try { block(); finish(a) } finally { phase = null; live = null; listenSteer = false }
        }
    }

    /** Testene: glem alt (og stopp en kjøring som venter). */
    internal fun resetForTest() {
        job?.cancel(); job = null
        checks = emptyList(); phase = null; live = null; doneAt = null; listenSteer = false; steerHit = null
    }

    /** Avbryt en prøve som venter på brukeren (rattknapp/lys). Radioprøven gjør seg ferdig (den setter tilbake kilden). */
    fun cancelLive() { if (live != null) job?.cancel() }

    private suspend fun finish(a: Context) {
        doneAt = SimpleDateFormat("HH:mm", Locale.forLanguageTag("nb-NO")).format(Date())
        val text = text(a)
        withContext(Dispatchers.IO) { runCatching { file(a).writeText(text) } }
    }

    private fun version(c: Context) = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"

    fun text(c: Context): String = SelfTestLogic.report(checks,
        SimpleDateFormat("d. MMM yyyy HH:mm", Locale.forLanguageTag("nb-NO")).format(Date()), version(c))

    private fun file(c: Context) = File(c.filesDir, "selvtest-siste.txt")

    /** Siste resultat (til kartleggingen). */
    fun lastText(c: Context): String = runCatching { file(c).takeIf { it.exists() }?.readText() }.getOrNull()
        ?: "(selvtesten er ikke kjørt – Innstillinger → Sikkerhetskopi og hjelp → Selvtest av stereoen)\n"

    fun share(c: Context) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "T'OS selvtest").putExtra(Intent.EXTRA_TEXT, text(c))
        val chooser = Intent.createChooser(i, "Send resultatet").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { c.startActivity(chooser) }.isFailure) Notify.show("Fant ingen app å sende med")
    }
}

/* ======================= Siden ======================= */

@Composable
fun SelfTestScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { if (SelfTest.checks.isEmpty() && !SelfTest.busy) SelfTest.runAuto(context) }
    val checks = SelfTest.checks
    val busy = SelfTest.phase != null || SelfTest.live != null
    Page("Selvtest av stereoen", onClose, extra = {
        if (checks.isNotEmpty() && !busy) { Pill("Send resultatet", Icons.AutoMirrored.Filled.Send) { SelfTest.share(context) }; Spacer(Modifier.width(10.dp)) }
    }) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(
                Modifier.weight(0.36f).fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(Card).padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val live = SelfTest.live
                if (live != null) LiveStep(live) else {
                    Text(if (checks.isEmpty()) "Tester …" else SelfTestLogic.summary(checks), color = TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Medium)
                    val detail = SelfTestLogic.summaryDetail(checks)
                    if (detail.isNotEmpty()) Text(detail, color = TextMuted, fontSize = 15.sp)
                    Text(SelfTest.phase ?: SelfTest.doneAt?.let { "Ferdig $it" } ?: "", color = TextPrimary, fontSize = 15.sp)
                    if (!busy) Pill("Kjør på nytt", Icons.Default.Refresh) { SelfTest.runAuto(context) }
                }
                Text("Prøv selv", color = TextMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                if (!busy) {
                    Pill("Rattknapp (10 s)") { SelfTest.runSteer(context) }
                    Pill("Lysene (15 s)") { SelfTest.runLights(context) }
                    if (Fyt.radioReady) Pill("Test radio") { SelfTest.runRadio(context) }
                }
                Text("Alt settes tilbake som det var. Ingenting lagres i stereoen – forvalg, lydbilde og lærte rattknapper røres ikke. " +
                    "«Test radio» spiller radio noen sekunder.", color = TextMuted, fontSize = 13.sp)
            }
            Column(Modifier.weight(0.64f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                checks.forEach { CheckRow(it) }
            }
        }
    }
}

@Composable
private fun LiveStep(live: SelfTest.Live) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(live) { while (true) { now = SystemClock.elapsedRealtime(); delay(250) } }
    val left = ((live.endsAt - now + 999) / 1000).coerceAtLeast(0)
    Text(live.title, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    Text(live.text, color = TextMuted, fontSize = 15.sp)
    Text("$left", color = TextPrimary, fontSize = 56.sp, fontWeight = FontWeight.Medium)
    Pill("Avbryt") { SelfTest.cancelLive() }
}

@Composable
private fun CheckRow(c: Check) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(18.dp)).background(Card).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val box = Modifier.size(34.dp).clip(CircleShape)
        Box(
            when (c.mark) {
                Mark.OK -> box.background(TextPrimary)
                Mark.FAIL -> box.border(2.dp, TextPrimary, CircleShape)
                Mark.UNKNOWN -> box.background(Line)
            },
            contentAlignment = Alignment.Center,
        ) {
            Text(c.mark.sign, color = when (c.mark) { Mark.OK -> Bg; Mark.FAIL -> TextPrimary; Mark.UNKNOWN -> TextMuted },
                fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, color = TextPrimary, fontSize = 16.sp)
            Text(c.detail, color = TextMuted, fontSize = 13.sp)
        }
    }
}
