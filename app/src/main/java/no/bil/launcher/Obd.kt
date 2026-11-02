package no.bil.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * OBD2 via en liten Bluetooth-brikke (ELM327, Vgate, V-LINK o.l.) i bilens diagnosekontakt.
 * Brikka må være paret med stereoen i Bluetooth-innstillingene først.
 * UTESTET: brukeren har ikke brikke ennå.
 */
object Obd {
    var status by mutableStateOf("Ikke tilkoblet")
    var connected by mutableStateOf(false)
    var rpm by mutableStateOf<Int?>(null)
    var coolant by mutableStateOf<Int?>(null)
    var volt by mutableStateOf<Float?>(null)
    var codes by mutableStateOf<List<String>?>(null)

    /** Batterivarsel: under 11,8 V er batteriet svakt. */
    val lowVoltage get() = volt?.let { it in 5f..11.8f } == true

    private var job: Job? = null
    private var socket: BluetoothSocket? = null
    private val SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val names = listOf("OBD", "ELM", "V-LINK", "VLINK", "VGATE", "VEEPEAK", "KONNWEI", "ICAR")

    @SuppressLint("MissingPermission")
    fun connect(c: Context) {
        if (job?.isActive == true) return
        job = Hub.scope.launch {
            if (Build.VERSION.SDK_INT >= 31 && !c.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                status = "Mangler Bluetooth-tillatelse"; return@launch
            }
            val adapter = (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            val dev = adapter?.bondedDevices?.firstOrNull { d -> names.any { (d.name ?: "").uppercase().contains(it) } }
            if (dev == null) { status = "Fant ingen OBD-brikke. Par den i Bluetooth-innstillinger først."; return@launch }
            status = "Kobler til ${dev.name} …"
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    socket = dev.createRfcommSocketToServiceRecord(SPP).also { it.connect() }
                    listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0").forEach { cmd(it) }
                    true
                }.getOrDefault(false)
            }
            if (!ok) { status = "Fikk ikke kontakt med ${dev.name}"; disconnect(); return@launch }
            connected = true; status = "Tilkoblet ${dev.name}"
            while (isActive && connected) {
                val r = withContext(Dispatchers.IO) {
                    runCatching {
                        Triple(
                            bytes(cmd("010C"), "410C")?.let { (it[0] * 256 + it[1]) / 4 },
                            bytes(cmd("0105"), "4105")?.let { it[0] - 40 },
                            cmd("ATRV").filter { it.isDigit() || it == '.' }.toFloatOrNull(),
                        )
                    }
                }
                if (r.isFailure) { status = "Mistet kontakten"; disconnect(); break }
                r.getOrNull()?.let { (a, b, v) -> rpm = a; coolant = b; volt = v }
                delay(1000)
            }
        }
    }

    /** Leser lagrede feilkoder (modus 03). */
    fun readCodes() {
        if (!connected) return
        Hub.scope.launch {
            codes = withContext(Dispatchers.IO) {
                runCatching {
                    val hex = cmd("03").replace(" ", "").substringAfter("43", "")
                    hex.chunked(4).filter { it.length == 4 && it != "0000" }.map { decodeDtc(it) }
                }.getOrNull()
            }
        }
    }

    fun disconnect() {
        connected = false
        runCatching { socket?.close() }
        socket = null; job?.cancel(); job = null
        if (status.startsWith("Tilkoblet")) status = "Ikke tilkoblet"
    }

    private fun cmd(c: String): String {
        val s = socket ?: error("ingen kontakt")
        s.outputStream.write("$c\r".toByteArray()); s.outputStream.flush()
        val sb = StringBuilder()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 3000) {
            val ch = s.inputStream.read()
            if (ch < 0 || ch == '>'.code) break
            sb.append(ch.toChar())
        }
        return sb.toString().replace("SEARCHING...", "").trim()
    }

    private fun bytes(resp: String, header: String): List<Int>? {
        val hex = resp.replace(" ", "").replace("\r", "").replace("\n", "")
        val i = hex.indexOf(header); if (i < 0) return null
        return hex.substring(i + header.length).chunked(2).mapNotNull { it.toIntOrNull(16) }.takeIf { it.isNotEmpty() }
    }

    private fun decodeDtc(h: String): String {
        val first = h[0].digitToInt(16)
        val type = "PCBU"[first shr 2]
        return "$type${first and 3}${h.substring(1)}"
    }
}
