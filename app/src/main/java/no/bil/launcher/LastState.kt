package no.bil.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/*
 * «Klar med en gang» (v5.15): stereoen dreper T'OS når tenningen går av, så T'OS starter kaldt ved hver motorstart.
 * De første sekundene er mediatjenesten, FYT-stereoen og GPS-en ikke klare ennå. I stedet for tomme kort viser
 * forsiden det som var sist (sang, radio, batterispenning) – merket «Sist spilt»/«Sist hørt» – til ekte data kommer.
 * Lagres i en egen liten fil (ikke i innstillingene, som skrives helt og tegner hele UI på nytt).
 *
 * Oppstartstider (StartTiming): fra prosessen startet til første bilde, til forsiden er klar, til musikken (varseltilgang)
 * er koblet til, til stereoen (FYT) svarer og til første GPS-posisjon. De 10 siste står i oppsettrapporten.
 */

/** Ren logikk for «sist kjente tilstand» – testes uten Android. */
internal object LastStateLogic {
    const val MUSIC = "music"
    const val RADIO = "radio"
    const val NETRADIO = "netradio"
    const val BT = "bt"
    private val KINDS = setOf(MUSIC, RADIO, NETRADIO, BT)

    /** Det som var på musikkortet sist (+ telefonnavn og batterispenning). [at] = veggklokke (ms). */
    data class Snap(
        val kind: String, val title: String, val sub: String?, val pkg: String?,
        val phone: String? = null, val volts: Float? = null, val at: Long = 0L,
    ) {
        val isRadio get() = kind == RADIO || kind == NETRADIO
        /** Samme innhold (tid, spenning og telefon teller ikke). */
        fun sameMusic(o: Snap?) = o != null && kind == o.kind && title == o.title && sub == o.sub && pkg == o.pkg
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "")
    private fun unesc(s: String): String {
        val b = StringBuilder(); var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) { b.append(if (s[i + 1] == 'n') '\n' else s[i + 1]); i += 2 } else { b.append(ch); i++ }
        }
        return b.toString()
    }

    fun encode(s: Snap): String = buildString {
        appendLine("v=1")
        appendLine("kind=${esc(s.kind)}")
        appendLine("title=${esc(s.title)}")
        s.sub?.let { appendLine("sub=${esc(it)}") }
        s.pkg?.let { appendLine("pkg=${esc(it)}") }
        s.phone?.let { appendLine("phone=${esc(it)}") }
        s.volts?.let { appendLine("volts=$it") }
        appendLine("at=${s.at}")
    }

    /** Tåler gammel/ødelagt fil: gir null i stedet for å krasje. Uten tittel (bare spenning) blir musikken «ingen». */
    fun decode(text: String?): Snap? {
        if (text.isNullOrBlank()) return null
        val m = HashMap<String, String>()
        text.lineSequence().forEach { line ->
            val i = line.indexOf('=')
            if (i > 0) m[line.substring(0, i)] = unesc(line.substring(i + 1))
        }
        val kind = m["kind"]?.takeIf { it in KINDS } ?: MUSIC
        val title = m["title"].orEmpty()
        val volts = m["volts"]?.toFloatOrNull()?.takeIf { it in 5f..20f }
        if (title.isBlank() && volts == null && m["phone"].isNullOrBlank()) return null
        return Snap(kind, title, m["sub"]?.ifBlank { null }, m["pkg"]?.ifBlank { null }, m["phone"]?.ifBlank { null }, volts,
            m["at"]?.toLongOrNull() ?: 0L)
    }

    /** Ny lagring: det som finnes nå, ellers det som var lagret (musikken forsvinner ikke fordi appen ikke er startet ennå). */
    fun merge(old: Snap?, music: Snap?, phone: String?, volts: Float?, now: Long): Snap? {
        val base = music ?: old?.copy() ?: if (volts != null || phone != null) Snap(MUSIC, "", null, null) else return null
        val at = if (music != null && !music.sameMusic(old)) now else (old?.at?.takeIf { it > 0 } ?: now)
        return base.copy(phone = phone ?: old?.phone, volts = volts ?: old?.volts, at = at)
    }

    /** «Sist spilt» / «Sist hørt», med dato når det er mer enn 12 timer siden. */
    fun label(s: Snap, now: Long): String {
        val word = if (s.isRadio) "Sist hørt" else "Sist spilt"
        if (s.at <= 0L || now - s.at < 12 * 3_600_000L) return word
        return "$word " + SimpleDateFormat("d.M.", Locale("nb", "NO")).format(Date(s.at))
    }

    /** Undertittel på musikkortet når det som vises er lagret: «Sist spilt · artist». */
    fun subLine(s: Snap, now: Long): String = listOfNotNull(label(s, now), s.sub?.takeIf { it.isNotBlank() }).joinToString(" · ")

    /** Spenningen avrundet, så små svingninger (12,61 → 12,63) ikke gir nye skrivinger. */
    fun roundVolts(v: Float?): Float? = v?.let { Math.round(it * 10f) / 10f }
}

/** Sist kjente tilstand: leses fra disk ved oppstart, lagres når musikk/spenning endrer seg. */
object LastState {
    private const val FILE = "sist.txt"
    private const val COVER = "sist-cover.png"
    /** Det som ble lagret sist T'OS kjørte (null = ingenting). Vises til ekte data kommer. */
    internal var restored by mutableStateOf<LastStateLogic.Snap?>(null)
        internal set
    /** Lite omslagsbilde av siste sang. */
    var cover by mutableStateOf<Bitmap?>(null)
        internal set
    @Volatile private var loaded = false
    @Volatile private var saved: LastStateLogic.Snap? = null

    /** Leser den lille fila (under 1 KB) med en gang, bildet i bakgrunnen. Trygt å kalle flere ganger. */
    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val dir = c.applicationContext.filesDir
        val snap = runCatching { File(dir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()?.let { LastStateLogic.decode(it) }
        saved = snap
        restored = snap
        if (snap != null && snap.kind == LastStateLogic.MUSIC && snap.title.isNotBlank()) Hub.scope.launch(Dispatchers.IO) {
            val bmp = runCatching { File(dir, COVER).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } }.getOrNull()
            if (bmp != null) withContext(Dispatchers.Main) { cover = bmp }
        }
    }

    /** Det musikkortet viser NÅ fra ekte kilder (ikke det lagrede). Null = ingenting ennå. */
    internal fun liveMusic(): LastStateLogic.Snap? = when {
        NetRadio.active -> NetRadio.current?.name?.let { LastStateLogic.Snap(LastStateLogic.NETRADIO, it, "Nettradio", null) }
        FytRadio.onAir -> LastStateLogic.Snap(LastStateLogic.RADIO, FytRadio.cardTitle, FytRadio.cardSub.ifBlank { null }, null)
        fytBtMusicShown -> LastStateLogic.Snap(LastStateLogic.BT, Fyt.btavTitle ?: "Bluetooth-musikk",
            Fyt.btavArtist, null)
        else -> NowPlaying.title?.takeIf { it.isNotBlank() }?.let { LastStateLogic.Snap(LastStateLogic.MUSIC, it, NowPlaying.artist, NowPlaying.pkg) }
    }

    /** Følger med og lagrer (3 s etter siste endring, i bakgrunnen). Startes fra Hub.start. */
    fun watch(c: Context) {
        val app = c.applicationContext
        load(app)
        Hub.scope.launch {
            snapshotFlow { Triple(liveMusic(), LastStateLogic.roundVolts(CarBattery.volt), Fyt.phoneName) }
                .distinctUntilChanged()
                .collectLatest { (music, volts, phone) ->
                    if (music == null && volts == null && phone == null) return@collectLatest
                    delay(3_000)
                    val art = if (music?.kind == LastStateLogic.MUSIC) NowPlaying.art else null
                    withContext(Dispatchers.IO) { save(app, music, phone, volts, art) }
                }
        }
    }

    private fun save(c: Context, music: LastStateLogic.Snap?, phone: String?, volts: Float?, art: Bitmap?) {
        val old = saved
        val next = LastStateLogic.merge(old, music, phone, volts, System.currentTimeMillis()) ?: return
        if (next == old) return
        val newSong = music != null && !music.sameMusic(old)
        runCatching {
            val f = File(c.filesDir, FILE)
            val tmp = File(c.filesDir, "$FILE.tmp")
            tmp.writeText(LastStateLogic.encode(next))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
        if (newSong) runCatching {
            val cf = File(c.filesDir, COVER)
            if (art == null || art.isRecycled) cf.delete()
            else {
                // Lite (128 px) – det vises bare i 52 dp på forsiden
                val scale = 128f / maxOf(art.width, art.height).coerceAtLeast(1)
                val small = if (scale < 1f) Bitmap.createScaledBitmap(art, (art.width * scale).toInt().coerceAtLeast(1), (art.height * scale).toInt().coerceAtLeast(1), true) else art
                cf.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        saved = next
    }

    /** Til testene: les fila på nytt neste gang. */
    internal fun resetForTest() { loaded = false; saved = null; restored = null; cover = null }
}

/** Ren logikk for oppstartstidene – testes uten Android. */
internal object StartTimingLogic {
    const val KEEP = 10
    private val NB = Locale("nb", "NO")

    fun secs(ms: Long?): String = ms?.takeIf { it >= 0 }?.let { String.format(NB, "%.1f s", it / 1000.0) } ?: "–"

    /** «kl. 07:42 · første bilde 1,2 s · klar 1,5 s · musikk 2,8 s · stereo 3,1 s · GPS 14,0 s». */
    fun line(clock: String, marks: Map<String, Long>): String = buildString {
        append("kl. $clock")
        for ((key, name) in NAMES) append(" · $name ${secs(marks[key])}")
    }

    /** Legger til en linje og beholder de [keep] siste. */
    fun append(old: String?, line: String, keep: Int = KEEP): String =
        ((old?.lines().orEmpty().filter { it.isNotBlank() }) + line).takeLast(keep).joinToString("\n") + "\n"

    /** Ferdig når alt er målt, eller når det har gått lang nok tid (stereo uten FYT, GPS i garasjen). */
    fun done(marks: Map<String, Long>, elapsedMs: Long, fytPresent: Boolean): Boolean {
        val needed = NAMES.keys.filter { fytPresent || it != StartTiming.FYT }
        return needed.all { it in marks } || elapsedMs >= 90_000
    }

    val NAMES = linkedMapOf(
        StartTiming.FRAME to "første bilde", StartTiming.READY to "klar", StartTiming.MUSIC to "musikk",
        StartTiming.FYT to "stereo", StartTiming.GPS to "GPS",
    )
}

/** Måler oppstarten fra prosessen startet (Android 7+: Process.getStartUptimeMillis). */
object StartTiming {
    const val FRAME = "frame"
    const val READY = "ready"
    const val MUSIC = "music"
    const val FYT = "fyt"
    const val GPS = "gps"
    private const val FILE = "oppstart.txt"
    private val t0 = runCatching { android.os.Process.getStartUptimeMillis() }.getOrDefault(0L)
    private val marks = ConcurrentHashMap<String, Long>()
    @Volatile private var started = false

    /** Første gang [name] skjer etter oppstart (senere ganger teller ikke). */
    fun mark(name: String) {
        if (t0 <= 0L) return
        marks.putIfAbsent(name, SystemClock.uptimeMillis() - t0)
    }

    /** Fra Hub.start: følger FYT og GPS, og skriver én linje når alt er målt (eller etter 90 s). */
    fun start(c: Context) {
        if (started || t0 <= 0L) return
        started = true
        val app = c.applicationContext
        val fytPresent = runCatching { app.packageManager.getPackageInfo("com.syu.ms", 0); true }.getOrDefault(false)
        Hub.scope.launch { snapshotFlow { Fyt.connected }.first { it }; mark(FYT) }
        Hub.scope.launch { snapshotFlow { Hub.gps != null }.first { it }; mark(GPS) }
        Hub.scope.launch {
            withTimeoutOrNull(95_000) {
                while (!StartTimingLogic.done(marks, SystemClock.uptimeMillis() - t0, fytPresent)) delay(1_000)
            }
            val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val line = StartTimingLogic.line(clock, HashMap(marks))
            withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(app.filesDir, FILE)
                    f.writeText(StartTimingLogic.append(f.takeIf { it.exists() }?.readText(), line))
                }
            }
        }
    }

    /** Til oppsettrapporten / «Send alt»: de siste oppstartene, nyeste nederst. */
    fun report(c: Context): String {
        val saved = runCatching { File(c.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()?.trim()
        val now = if (t0 > 0L) "denne: " + StartTimingLogic.line("nå", HashMap(marks)).removePrefix("kl. nå · ") else null
        return listOfNotNull(saved?.ifEmpty { null } ?: "(ingen målt ennå)", now).joinToString("\n")
    }
}
