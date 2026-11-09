package no.bil.launcher

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * «Send alt til utvikleren» (Innstillinger → Sikkerhetskopi og hjelp, øverst; også på Selvtest-siden og «Hva er nytt»):
 * ÉN fil med alt utvikleren trenger – versjon, kort sammendrag (FYT, brytere, selvtest), selvtesten, oppsettrapporten,
 * feilrapporten, den korte kartleggingen (uten getprop/pakkelister) og slutten av siste opptak. Brukeren kan skrive
 * «Hva skjedde?» øverst. Før måtte han finne tre ulike knapper.
 *
 * «Hva er nytt»: etter en oppdatering (versionCode endret siden sist) viser forsiden en rolig linje i noen oppstarter;
 * trykk = siden med assets/nytt.md (samme visning som bruksanvisningen). Også i Innstillinger → Om stereoen.
 */

/** Ren logikk (testes i FeedbackLogicTest). */
internal object FeedbackLogic {
    const val PREFIX = "tos-tilbakemelding-"
    /** Så mange linjer av siste opptak tas med. */
    const val REC_LINES = 300
    private const val REC_HEADER = "===== Opptak ("

    fun fileName(at: Long): String = PREFIX + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(at)) + ".txt"
    fun isOurs(name: String): Boolean = name.startsWith(PREFIX) && name.endsWith(".txt")

    /** Linja «N av M virker – …» fra selvtest-siste.txt (andre linje), ellers «ikke kjørt». */
    fun selftestLine(text: String?): String {
        val lines = text.orEmpty().lines()
        return if (lines.firstOrNull()?.startsWith("T'OS selvtest") == true && lines.size > 1 && lines[1].isNotBlank())
            "${lines[1].trim()} (${lines[0].removePrefix("T'OS selvtest").trim()})"
        else "ikke kjørt"
    }

    /** Opptaket i en kartleggingsfil (delen «Opptak (N linjer)»): de siste [max] linjene, null når fila ikke har opptak. */
    fun recordingTail(survey: String, max: Int = REC_LINES): List<String>? {
        val at = survey.indexOf(REC_HEADER)
        if (at < 0) return null
        val bodyStart = survey.indexOf('\n', at).let { if (it < 0) return null else it + 1 }
        val next = survey.indexOf("\n===== ", bodyStart).let { if (it < 0) survey.length else it }
        val lines = survey.substring(bodyStart, next).lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        return if (lines.size <= max) lines else listOf("(de siste $max av ${lines.size} linjer)") + lines.takeLast(max)
    }

    /** Det som står øverst i fila: kort og lett å lese. */
    data class Head(
        val version: String, val code: Int, val made: String, val note: String,
        val android: String, val fytPresent: Boolean, val fytConnected: Boolean, val fytStatus: String,
        val master: Boolean, val toggles: List<Pair<String, Boolean>>, val fytSafe: String,
        val selftest: String, val safeMode: Boolean, val recording: String,
    )

    fun header(h: Head): String = buildString {
        appendLine("T'OS til utvikleren")
        appendLine("T'OS ${h.version} (bygg ${h.code}) · laget ${h.made}")
        appendLine("Stereo: ${h.android}")
        appendLine()
        appendLine("Hva skjedde?")
        appendLine(h.note.trim().ifEmpty { "(ingenting skrevet)" })
        appendLine()
        appendLine("--- Sammendrag ---")
        appendLine("FYT-stereo: " + when {
            !h.fytPresent -> "nei (ingen com.syu.ms)"
            h.fytConnected -> "tilkoblet"
            else -> "ikke tilkoblet (${h.fytStatus})"
        })
        if (h.fytPresent) {
            appendLine("Hovedbryter «Stereo-funksjoner (FYT)»: ${if (h.master) "på" else "AV"}${if (h.fytSafe.isNotEmpty()) " ${h.fytSafe}" else ""}")
            val on = h.toggles.filter { it.second }.joinToString { it.first }
            val off = h.toggles.filterNot { it.second }.joinToString { it.first }
            appendLine("FYT-brytere på: ${on.ifEmpty { "ingen" }}")
            if (off.isNotEmpty()) appendLine("FYT-brytere av: $off")
        }
        appendLine("Selvtest: ${h.selftest}")
        appendLine("Sikker modus: ${if (h.safeMode) "PÅ" else "av"}")
        appendLine("Opptak: ${h.recording}")
    }

    /** Én del av fila. */
    fun section(title: String, body: String): String = SurveyText.header(title) + body.trimEnd() + "\n"

    /** FYT-bryterne med vanlige navn (alle er på som standard, se FytSafetyLogic.FEATURES). */
    val toggleNames = linkedMapOf(
        "fyt_radio" to "Radio", "fyt_bt" to "BT-musikk", "fyt_phone" to "Telefon", "fyt_call_screen" to "Samtaleskjerm",
        "fyt_steer" to "Rattknapper", "fyt_bright" to "Lysstyrke", "tos_canbus" to "Bil-data (canbus)", "tos_volume" to "Volumlinje",
    )
}

object Feedback {
    /** Fila lages nå. */
    var working by mutableStateOf(false)
        private set
    /** «Hva skjedde?» – står igjen til fila er laget (også om siden lukkes). */
    var note by mutableStateOf("")
    /** Siste fil (kan sendes igjen). */
    var last by mutableStateOf<File?>(null)
        private set

    /** Lag fila i bakgrunnen, lagre den og åpne «Send» (samme valg som kartleggingen). */
    fun runAndShare(c: Context) {
        if (working) return
        working = true
        val app = c.applicationContext
        val text = note
        Hub.scope.launch {
            val f = withContext(Dispatchers.IO) { runCatching { save(app, build(app, text)) }.getOrNull() }
            working = false
            if (f == null) { Notify.show("Fikk ikke laget filen"); return@launch }
            last = f
            note = ""
            Notify.show("Filen er klar (${(f.length() + 1023) / 1024} KB) – velg hvordan du vil sende den")
            SystemSurvey.share(app, f, subject = "T'OS til utvikleren (${f.name})", title = "Send til utvikleren")
        }
    }

    private fun save(c: Context, text: String): File {
        val d = SystemSurvey.dir(c)
        // Behold bare de tre nyeste
        d.listFiles { x -> FeedbackLogic.isOurs(x.name) }?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
        val f = File(d, FeedbackLogic.fileName(System.currentTimeMillis()))
        f.writeText(text)
        return f
    }

    /** Siste opptak: fra den nyeste kartleggingen som har et (fil og linjer), ellers null. */
    private fun lastRecording(c: Context): Pair<File, List<String>>? = runCatching {
        SystemSurvey.dir(c).listFiles { x -> x.name.startsWith("kartlegging-") && x.name.endsWith(".txt") }.orEmpty()
            .sortedByDescending { it.lastModified() }.asSequence()
            .mapNotNull { f -> FeedbackLogic.recordingTail(f.readText())?.let { f to it } }.firstOrNull()
    }.getOrNull()

    /** Hele fila. Hver del for seg: feiler én, kommer resten likevel med. Kjøres på IO. */
    fun build(c: Context, note: String): String {
        fun safe(get: () -> String): String = runCatching(get).getOrElse { "(fikk ikke lest: ${it.javaClass.simpleName} ${SurveyText.clip(it.message, 120)})" }
        val selftest = safe { SelfTest.lastText(c) }
        val rec = lastRecording(c)
        val fytPresent = runCatching { Fyt.present(c) }.getOrDefault(false)
        val head = FeedbackLogic.Head(
            version = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?",
            code = Updater.current(c),
            made = SimpleDateFormat("d. MMM yyyy HH:mm", Locale.forLanguageTag("nb-NO")).format(Date()),
            note = note,
            android = "${Build.MANUFACTURER} ${Build.MODEL} / ${Build.HARDWARE}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            fytPresent = fytPresent,
            fytConnected = runCatching { Fyt.connected }.getOrDefault(false),
            fytStatus = runCatching { Fyt.status }.getOrDefault("?"),
            master = runCatching { Fyt.enabled }.getOrDefault(true),
            toggles = FeedbackLogic.toggleNames.map { (k, name) -> name to Prefs.bool(k, true) },
            fytSafe = runCatching { FytSafety.summary() }.getOrDefault(""),
            selftest = FeedbackLogic.selftestLine(selftest),
            safeMode = SafeMode.on,
            recording = rec?.let { "${it.first.name} (de siste linjene står nederst)" } ?: "ingen",
        )
        return buildString {
            append(FeedbackLogic.header(head))
            append(FeedbackLogic.section("Selvtest (siste resultat)", selftest))
            append(FeedbackLogic.section("Oppsettrapport", safe { SetupReport.build(c) }))
            append(FeedbackLogic.section("Feilrapport (krasj og logg)", safe { CrashLog.report(c) }))
            append(FeedbackLogic.section("Stereoen (kort kartlegging)", safe { SystemSurvey.build(c, null, compact = true) }))
            if (rec != null) append(FeedbackLogic.section("Siste opptak (${rec.first.name})", rec.second.joinToString("\n")))
        }
    }
}

/** Kortet øverst i Sikkerhetskopi og hjelp: «Hva skjedde?» + én knapp. */
@Composable
fun FeedbackCard() {
    val context = LocalContext.current
    val working = Feedback.working
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card)
            .clickable(onClickLabel = "Send alt til utvikleren") { Feedback.runAndShare(context) }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Send alt til utvikleren", color = TextPrimary, fontSize = 18.sp)
        Text(
            if (working) "Lager filen … (tar noen sekunder)"
            else "Selvtest, oppsett, feil og stereoen i én fil. Skriv gjerne hva som skjedde – det er ikke nødvendig.",
            color = TextMuted, fontSize = 13.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(Feedback.note, "Hva skjedde? (valgfritt)", Modifier.weight(1f)) { Feedback.note = it }
            Pill(if (working) "Vent …" else "Send", Icons.AutoMirrored.Filled.Send, strong = !working) { Feedback.runAndShare(context) }
        }
    }
    Spacer(Modifier.height(8.dp))
    Feedback.last?.let { f ->
        SettingRow("Send siste fil igjen", "${f.name} · ${(f.length() + 1023) / 1024} KB", onClick = {
            SystemSurvey.share(context, f, subject = "T'OS til utvikleren (${f.name})", title = "Send til utvikleren")
        })
    }
}

/* ======================= Hva er nytt ======================= */

/** Ren logikk (testes): når «Hva er nytt» skal vises på forsiden. */
internal object NewsLogic {
    const val ASSET = "nytt.md"
    /** Linja på forsiden står i så mange oppstarter av T'OS (eller til siden er åpnet). Ikke tid: stereoens klokke kan hoppe. */
    const val SHOW_STARTS = 5

    /**
     * Ved hver oppstart: hvor mange oppstarter linja skal stå igjen. [lastSeen] = versionCode ved forrige oppstart
     * (0 = aldri lagret: da bare når T'OS faktisk er oppdatert, ikke ved første installasjon), [left] = det som sto igjen.
     */
    fun afterStart(lastSeen: Int, current: Int, wasUpdated: Boolean, left: Int): Int = when {
        lastSeen <= 0 && wasUpdated -> SHOW_STARTS
        lastSeen in 1 until current -> SHOW_STARTS
        else -> (left - 1).coerceAtLeast(0)
    }

    /** «5.10.123» (GitHub-bygg) → «5.10», som overskriftene i nytt.md. */
    fun shortVersion(name: String?): String = name.orEmpty().split('.').take(2).joinToString(".")
}

object News {
    private const val SEEN = "news_seen_code"
    private const val LEFT = "news_left"

    /** Fra MainActivity.onCreate: er T'OS oppdatert siden sist? */
    fun check(c: Context) = runCatching {
        val cur = Updater.current(c)
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        val left = NewsLogic.afterStart(Prefs.int(SEEN, 0), cur, pi.lastUpdateTime > pi.firstInstallTime, Prefs.int(LEFT, 0))
        Prefs.setQuiet(SEEN, cur)
        Prefs.setQuiet(LEFT, left)
    }

    /** Linja på forsiden (leses i Compose). */
    val visible: Boolean get() = Prefs.int(LEFT, 0) > 0

    fun seen() { if (Prefs.int(LEFT, 0) != 0) Prefs.set(LEFT, 0) }

    fun noticeText(c: Context): String {
        val v = NewsLogic.shortVersion(runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull())
        return "Oppdatert til T'OS $v – trykk for å se hva som er nytt"
    }
}

@Composable
fun NewsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { News.seen() }
    HelpScreen(onClose, title = "Hva er nytt", asset = NewsLogic.ASSET, extra = {
        Pill(if (Feedback.working) "Vent …" else "Send alt til utvikleren", Icons.AutoMirrored.Filled.Send) { Feedback.runAndShare(context) }
        Spacer(Modifier.width(10.dp))
    })
}
