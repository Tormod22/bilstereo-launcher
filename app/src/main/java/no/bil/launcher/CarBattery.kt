package no.bil.launcher

import android.Manifest
import android.annotation.SuppressLint
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var socket: BluetoothSocket? = null
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
        Hub.scope.launch { while (isActive) { stereoVolt = withContext(Dispatchers.IO) { readStereo(c) }; publish(); delay(10_000) } }
        Hub.scope.launch {
            while (isActive) {
                if (enabled || Engine.watching) obdLoop(c)
                // Vent inntil 60 s – men prøv straks igjen hvis Tuning-siden (motordata) åpnes
                val wasWatching = Engine.watching
                for (i in 0 until 60) { if (Engine.watching && !wasWatching) break; delay(1_000) }
            }
        }
    }

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
                socket = dev.createRfcommSocketToServiceRecord(SPP).also { it.connect() }
                listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0").forEach { cmd(it) }
                true
            }.getOrDefault(false)
        }
        if (!ok) { obdStatus = "Fikk ikke kontakt med ${dev.name} (er tenningen på?)"; close(); return }
        obdStatus = "Tilkoblet ${dev.name}"
        while (Hub.scope.isActive && (enabled || Engine.watching)) {
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
    }

    private fun close() {
        runCatching { socket?.close() }; socket = null
        obdVolt = null; publish(); Engine.clear()
    }

    /** Er OBD-brikka tilkoblet akkurat nå? */
    val obdConnected get() = socket != null

    private fun cmd(c: String, waitMs: Long = 3000): String {
        val s = socket ?: error("ingen kontakt")
        s.outputStream.write("$c\r".toByteArray()); s.outputStream.flush()
        val sb = StringBuilder(); val start = System.currentTimeMillis()
        val inp = s.inputStream
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
    var watching by mutableStateOf(false)
    var rpm by mutableStateOf<Int?>(null)
    var coolant by mutableStateOf<Int?>(null)      // °C
    var boostBar by mutableStateOf<Float?>(null)   // overtrykk i bar (MAP − lufttrykk)
    var railBar by mutableStateOf<Int?>(null)      // drivstofftrykk i rail, bar
    var maxBoost by mutableStateOf<Float?>(null)
    var maxRail by mutableStateOf<Int?>(null)
    private var baroKpa = 101
    private var first = true
    private val unsupported = mutableSetOf<String>()

    fun clear() { rpm = null; coolant = null; boostBar = null; railBar = null }
    fun resetMax() { maxBoost = null; maxRail = null }

    /** Leser én runde. [ask] sender en kommando og venter på svar (ms). */
    fun poll(ask: (String, Long) -> String) {
        fun read(pid: String): List<Int>? {
            if (pid in unsupported) return null
            val r = runCatching { ask("01$pid", if (first) 10_000 else 2_000) }.getOrNull()
            first = false
            val b = parsePid(r ?: return null, pid)
            if (b == null && r.contains("NO DATA", true)) unsupported += pid
            return b
        }
        read("33")?.let { baroKpa = it[0].takeIf { v -> v in 60..110 } ?: baroKpa }
        val r = read("0C"); val c = read("05"); val m = read("0B"); val f = read("23")
        val boost = m?.let { ((it[0] - baroKpa) / 100f).coerceAtLeast(0f) }
        val rail = f?.takeIf { it.size >= 2 }?.let { railFromBytes(it[0], it[1]) }
        Hub.scope.launch {
            rpm = r?.takeIf { it.size >= 2 }?.let { (it[0] * 256 + it[1]) / 4 }
            coolant = c?.let { it[0] - 40 }
            boostBar = boost; railBar = rail
            if (boost != null && (maxBoost ?: 0f) < boost) maxBoost = boost
            if (rail != null && (maxRail ?: 0) < rail) maxRail = rail
        }
    }

    /** Rail-trykk (PID 23): (A·256+B)·10 kPa → bar. */
    internal fun railFromBytes(a: Int, b: Int) = (a * 256 + b) * 10 / 100

    /**
     * Finner databytene etter «41 <pid>» i svaret fra ELM327 (med eller uten mellomrom, flere linjer, «SEARCHING...»).
     * Returnerer null hvis svaret ikke inneholder PID-en.
     */
    internal fun parsePid(resp: String, pid: String): List<Int>? {
        val hex = resp.uppercase().lines().joinToString("") { line ->
            line.substringAfter(':', line).filter { it.isDigit() || it in 'A'..'F' }   // «0: 41 0C …» (CAN med linjenummer)
        }
        val i = hex.indexOf("41" + pid.uppercase())
        if (i < 0) return null
        val data = hex.substring(i + 4)
        val bytes = data.chunked(2).filter { it.length == 2 }.mapNotNull { it.toIntOrNull(16) }
        return bytes.take(4).takeIf { it.isNotEmpty() }
    }
}
