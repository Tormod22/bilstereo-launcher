package no.bil.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Spenning på bilbatteriet. To kilder, i denne rekkefølgen:
 *  1. OBD2-brikke (ELM327, f.eks. Vgate iCar Pro) – kommandoen ATRV leser spenningen i diagnosekontakten.
 *  2. Stereoen selv – noen stereoer oppgir spenningen de får fra bilen. Godtas bare hvis tallet er troverdig (9–16 V).
 * Begge er UTESTET på brukerens stereo.
 */
object CarBattery {
    var volt by mutableStateOf<Float?>(null)
    var source by mutableStateOf<String?>(null)          // "OBD-brikke" / "stereoen"
    var obdStatus by mutableStateOf("Ingen OBD-brikke funnet")

    private var obdVolt: Float? = null
    private var stereoVolt: Float? = null
    @Volatile private var socket: BluetoothSocket? = null
    private val SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val names = listOf("OBD", "ELM", "V-LINK", "VLINK", "VGATE", "ICAR", "VEEPEAK", "KONNWEI")

    val enabled get() = Prefs.bool("battery_show", true)
    val low get() = volt?.let { it < 11.8f } == true
    val high get() = volt?.let { it > 15.0f } == true

    fun text(v: Float) = String.format(Locale("nb", "NO"), "%.1f V", v)

    /** Hva spenningen betyr, i vanlige ord. */
    fun meaning(v: Float) = when {
        v > 15.0f -> "For høy ladespenning – få sjekket dynamoen"
        v >= 13.2f -> "Lader (motoren går)"
        v >= 12.4f -> "Godt ladet"
        v >= 12.0f -> "Litt lavt"
        else -> "Lavt – bør lades eller sjekkes"
    }

    fun start(c: Context) {
        // Stereoens spenning: bare med skjermen på (den vises ikke ellers) – ingen lesing hele natten
        Hub.scope.launch { Power.every(10_000, { it.stereoVolt }) { stereoVolt = withContext(Dispatchers.IO) { readStereo(c) }; publish() } }
        Hub.scope.launch {
            while (isActive) {
                // Ikke mens bilen står med tenningen av (eller skjermen av uten tenning): brikka og bilen skal få sove
                Power.await { it.obd }
                if (enabled || Engine.watching) obdLoop(c)
                // Vent inntil 60 s – men prøv straks igjen hvis Tuning-siden (motordata) åpnes
                val wasWatching = Engine.watching
                withTimeoutOrNull(60_000) { Engine.watchFlow.first { it && !wasWatching } }
            }
        }
    }

    private fun awake() = Power.plan.obd

    private fun publish() {
        val o = obdVolt; val s = stereoVolt
        volt = o ?: s
        source = when { o != null -> "OBD-brikke"; s != null -> "stereoen"; else -> null }
    }

    /* ---------- Stereoen selv ---------- */

    private fun plausible(v: Float) = v in 9f..16f

    private fun readStereo(c: Context): Float? {
        // a) Strømforsyninger i Linux (mikrovolt)
        File("/sys/class/power_supply").listFiles().orEmpty().forEach { dir ->
            val v = runCatching { File(dir, "voltage_now").readText().trim().toLong() / 1_000_000f }.getOrNull()
            if (v != null && plausible(v)) return v
        }
        // b) Androids batteriinformasjon (millivolt) – på stereoer er dette ofte et påfunnet tall, derfor sjekken
        val mv = runCatching {
            c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        }.getOrNull() ?: -1
        return (mv / 1000f).takeIf { plausible(it) }
    }

    /* ---------- OBD2-brikke (bare spenning) ---------- */

    @SuppressLint("MissingPermission")
    private suspend fun obdLoop(c: Context) {
        if (Build.VERSION.SDK_INT >= 31 && !c.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            obdStatus = "Mangler Bluetooth-tillatelse"; return
        }
        val adapter = (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        val dev = runCatching { adapter.bondedDevices?.firstOrNull { d -> names.any { (d.name ?: "").uppercase().contains(it) } } }.getOrNull()
        if (dev == null) { obdStatus = "Ingen OBD-brikke paret. Par den under Innstillinger → Bluetooth."; return }
        obdStatus = "Kobler til ${dev.name} …"
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                socket = connect(adapter, dev)
                listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0").forEach { cmd(it) }
                true
            }.getOrDefault(false)
        }
        if (!ok) { obdStatus = "Fikk ikke kontakt med ${dev.name} (er tenningen på?)"; close(); return }
        obdStatus = "Tilkoblet ${dev.name}"
        Engine.onConnect()
        while (Hub.scope.isActive && (enabled || Engine.watching) && awake()) {
            val v = withContext(Dispatchers.IO) { runCatching { cmd("ATRV").filter { it.isDigit() || it == '.' }.toFloatOrNull() } }
            if (v.isFailure) { obdStatus = "Mistet kontakten med ${dev.name}"; break }
            obdVolt = v.getOrNull()?.takeIf { it in 5f..18f }
            publish()
            // Motordata bare mens Tuning-siden er åpen (sparer brikka og bilens styreenhet)
            if (Engine.watching) {
                withContext(Dispatchers.IO) { runCatching { Engine.poll { pid, wait -> cmd(pid, wait) } } }
                delay(700)
            } else {
                Engine.clear()
                delay(5_000)
            }
        }
        close()
        // Ikke la det stå «Tilkoblet» når kontakten er lukket (slått av, skjermen av e.l.)
        if (obdStatus.startsWith("Tilkoblet")) obdStatus = "Ikke tilkoblet nå"
    }

    /**
     * Kobler til brikka. Billige ELM327-kopier godtar ofte bare «usikker» tilkobling, så den prøves også.
     * Søk etter enheter stoppes først (det gjør tilkoblingen treg eller hindrer den).
     */
    @SuppressLint("MissingPermission")
    private fun connect(adapter: BluetoothAdapter, dev: BluetoothDevice): BluetoothSocket {
        runCatching { adapter.cancelDiscovery() }
        var last: Throwable? = null
        for (secure in listOf(true, false)) {
            val s = runCatching { if (secure) dev.createRfcommSocketToServiceRecord(SPP) else dev.createInsecureRfcommSocketToServiceRecord(SPP) }
                .getOrElse { last = it; null } ?: continue
            try { s.connect(); return s } catch (e: Exception) { last = e; runCatching { s.close() } }   // ikke la en halvåpen kontakt ligge
        }
        throw last ?: IllegalStateException("ingen kontakt")
    }

    private fun close() {
        runCatching { socket?.close() }; socket = null
        obdVolt = null; publish(); Engine.clear()
    }

    /** Er OBD-brikka tilkoblet akkurat nå? */
    val obdConnected get() = socket != null

    private fun cmd(c: String, waitMs: Long = 3000): String {
        val s = socket ?: error("ingen kontakt")
        val inp = s.inputStream
        // Rester fra et svar som kom for sent (forrige kommando ga opp), ellers blandes det inn i dette svaret
        while (inp.available() > 0) if (inp.read() < 0) break
        s.outputStream.write("$c\r".toByteArray()); s.outputStream.flush()
        val sb = StringBuilder(); val start = System.currentTimeMillis()
        // Les bare det som har kommet – blir brikka stille (sover), henger ikke T'OS, men gir opp etter 3 s
        while (true) {
            if (System.currentTimeMillis() - start > waitMs) error("brikka svarer ikke")
            if (inp.available() <= 0) { Thread.sleep(20); continue }
            val ch = inp.read()
            if (ch < 0 || ch == '>'.code) break
            sb.append(ch.toChar())
        }
        return sb.toString().trim()
    }
}

/**
 * Motordata fra OBD2-brikka (standard-PID-er). Viser hva tuningboksen gjør: turtall, ladetrykk,
 * rail-trykk (diesel) og kjølevæske. Ikke alle biler svarer på alle – da vises «–».
 * ChipPower CRS selv har ingen utgang; dette leses fra bilens egen motorstyring.
 */
object Engine {
    private var watchingState by mutableStateOf(false)
    /** Tuning-siden (motordata) er åpen. Også som flyt, så OBD-løkka kan vente uten å sjekke hvert sekund. */
    var watching: Boolean
        get() = watchingState
        set(v) { watchingState = v; watchFlow.value = v }
    internal val watchFlow = MutableStateFlow(false)
    var rpm by mutableStateOf<Int?>(null)
    var coolant by mutableStateOf<Int?>(null)      // °C
    var boostBar by mutableStateOf<Float?>(null)   // overtrykk i bar (MAP − lufttrykk)
    var railBar by mutableStateOf<Int?>(null)      // drivstofftrykk i rail, bar
    var maxBoost by mutableStateOf<Float?>(null)
    var maxRail by mutableStateOf<Int?>(null)
    /** Brikka er tilkoblet, men bilens motorstyring svarer ikke (tenningen av e.l.). null = alt i orden. */
    var problem by mutableStateOf<String?>(null)
    private var baroKpa = 101
    private var baroKnown = false
    // ELM327 leter etter bilens protokoll ved første spørsmål etter tilkobling (ATSP0) – det kan ta opptil 10 s
    private var searching = true
    private val unsupported = mutableSetOf<String>()
    private val misses = mutableMapOf<String, Int>()

    fun clear() { rpm = null; coolant = null; boostBar = null; railBar = null; problem = null }
    fun resetMax() { maxBoost = null; maxRail = null }

    /** Ny tilkobling: alt bilen «ikke støttet» sist kan ha vært fordi den sov. Begynn på nytt. */
    fun onConnect() { searching = true; unsupported.clear(); misses.clear(); baroKnown = false; baroKpa = 101 }

    /** Én runde med svar (null = ukjent). */
    internal data class Reading(val rpm: Int?, val coolant: Int?, val boost: Float?, val rail: Int?, val problem: String?)

    /** Leser én runde og viser den. [ask] sender en kommando og venter på svar (ms). Kjøres på IO-tråden. */
    fun poll(ask: (String, Long) -> String) {
        val x = read(ask)
        Hub.scope.launch {
            problem = x.problem
            rpm = x.rpm; coolant = x.coolant; boostBar = x.boost; railBar = x.rail
            if (x.boost != null && (maxBoost ?: 0f) < x.boost) maxBoost = x.boost
            if (x.rail != null && (maxRail ?: 0) < x.rail) maxRail = x.rail
        }
    }

    /** Selve spørringen (uten skjermoppdatering, så den kan testes). */
    internal fun read(ask: (String, Long) -> String): Reading {
        val noData = mutableListOf<String>()
        var anyData = false
        var noEcu = false
        fun pid(pid: String): List<Int>? {
            if (pid in unsupported || noEcu) return null           // svarer ikke bilen, spør ikke om resten i denne runden
            val r = runCatching { ask("01$pid", if (searching) 10_000 else 2_000) }.getOrNull() ?: return null
            val b = parsePid(r, pid)
            when {
                b != null -> { anyData = true; searching = false; misses.remove(pid) }
                noEcuReply(r) -> noEcu = true
                r.contains("NO DATA", true) -> noData += pid
            }
            return b
        }
        // Lufttrykket endrer seg lite: les det én gang per tilkobling
        if (!baroKnown) pid("33")?.let { b -> b[0].takeIf { it in 60..110 }?.let { baroKpa = it; baroKnown = true } }
        val r = pid("0C"); val c = pid("05"); val m = pid("0B")
        // Rail-trykk: PID 23 (diesel). Støtter ikke bilen den, prøv 59 (absolutt rail-trykk, samme skala)
        val f = pid("23") ?: if ("23" in unsupported) pid("59") else null
        // «NO DATA» betyr «støttes ikke» bare når motorstyringen svarte på noe annet i samme runde (ellers sover den),
        // og først etter 3 ganger på rad
        if (anyData) noData.forEach { pid -> val n = (misses[pid] ?: 0) + 1; misses[pid] = n; if (n >= 3) unsupported += pid }
        val prob = if (!anyData && (noEcu || noData.isNotEmpty())) "Bilen svarer ikke – er tenningen på?" else null
        val boost = m?.let { ((it[0] - baroKpa) / 100f).coerceAtLeast(0f) }
        val rail = f?.takeIf { it.size >= 2 }?.let { railFromBytes(it[0], it[1]) }
        return Reading(r?.takeIf { it.size >= 2 }?.let { (it[0] * 256 + it[1]) / 4 }, c?.let { it[0] - 40 }, boost, rail, prob)
    }

    /** Rail-trykk (PID 23/59): (A·256+B)·10 kPa → bar. */
    internal fun railFromBytes(a: Int, b: Int) = (a * 256 + b) * 10 / 100

    /** Svar som betyr at brikka ikke får kontakt med bilen (tenningen av, feil protokoll), ikke at PID-en mangler. */
    internal fun noEcuReply(resp: String): Boolean {
        val u = resp.uppercase()
        return "UNABLE TO CONNECT" in u || "CAN ERROR" in u || "BUS ERROR" in u || "BUS BUSY" in u ||
            ("BUS INIT" in u && "ERROR" in u) || Regex("""ERR\d\d""").containsMatchIn(u)
    }

    /**
     * Finner databytene etter «41 <pid>» i svaret fra ELM327 (med eller uten mellomrom, flere linjer, «SEARCHING...»).
     * Bare linjer som er rene sekstallsbytes godtas – feilmeldinger («NO DATA», «STOPPED», «?») og ekko gir null.
     */
    internal fun parsePid(resp: String, pid: String): List<Int>? {
        val want = "41" + pid.uppercase()
        for (raw in resp.uppercase().lines()) {
            // «SEARCHING...41 0C …» på samme linje, og «0: 41 0C …» (CAN med linjenummer)
            val line = raw.substringAfterLast("...").substringAfter(':').filterNot { it.isWhitespace() }
            if (line.length < want.length + 2 || line.any { !(it.isDigit() || it in 'A'..'F') }) continue
            if (!line.startsWith(want)) continue
            return line.substring(want.length).chunked(2).filter { it.length == 2 }.map { it.toInt(16) }.take(4)
        }
        return null
    }
}
