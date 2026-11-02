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
        Hub.scope.launch { while (isActive) { if (enabled) obdLoop(c); delay(60_000) } }
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
                listOf("ATZ", "ATE0", "ATL0").forEach { cmd(it) }
                true
            }.getOrDefault(false)
        }
        if (!ok) { obdStatus = "Fikk ikke kontakt med ${dev.name} (er tenningen på?)"; close(); return }
        obdStatus = "Tilkoblet ${dev.name}"
        while (Hub.scope.isActive && enabled) {
            val v = withContext(Dispatchers.IO) { runCatching { cmd("ATRV").filter { it.isDigit() || it == '.' }.toFloatOrNull() } }
            if (v.isFailure) { obdStatus = "Mistet kontakten med ${dev.name}"; break }
            obdVolt = v.getOrNull()?.takeIf { it in 5f..18f }
            publish()
            delay(5_000)
        }
        close()
    }

    private fun close() {
        runCatching { socket?.close() }; socket = null
        obdVolt = null; publish()
    }

    private fun cmd(c: String): String {
        val s = socket ?: error("ingen kontakt")
        s.outputStream.write("$c\r".toByteArray()); s.outputStream.flush()
        val sb = StringBuilder(); val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 3000) {
            val ch = s.inputStream.read()
            if (ch < 0 || ch == '>'.code) break
            sb.append(ch.toChar())
        }
        return sb.toString().trim()
    }
}
