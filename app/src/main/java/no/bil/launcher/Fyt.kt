package no.bil.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/* ======================= FYT-stereoen (com.syu.ms «MainServer») ======================= */

/**
 * Stereoen er en FYT-stereo (Unisoc UIS7862 / ums512, Android 10). Alt det «ekte» – FM/AM-radioen (MCU), Bluetooth-
 * telefonen (egen BT-brikke som Android ikke ser), BT-musikk, lydkilden og volumet – styres av FYT sin hovedtjeneste
 * `com.syu.ms` (MainServer, uid 1000). Den har en åpen Binder-tjeneste `app.ToolkitService` (handling
 * «com.syu.ms.toolkit» – bekreftet i kartleggingen 8. nov: ikke [lukket]). T'OS snakker med den direkte, med rå
 * Binder-transaksjoner (samme format som FYT sine egne apper), så stereoens egne apper ikke trenger å vises.
 *
 * Protokollen (ikke offentlig dokumentert av FYT; hentet fra åpne prosjekter som har dekompilert FYT-appene):
 *  - IRemoteToolkit («com.syu.ipc.IRemoteToolkit»): transaksjon 1 = getRemoteModule(int modul) → IBinder.
 *  - IRemoteModule («com.syu.ipc.IRemoteModule»): 1 = cmd(int, int[], float[], String[]) (oneway),
 *    2 = get(int, int[], float[], String[]) → ModuleObject (flagg int, så int[], float[], String[]),
 *    3 = register(IModuleCallback, int kode, int 1=send nåverdien) (oneway), 4 = unregister(IModuleCallback, int) (oneway).
 *  - IModuleCallback («com.syu.ipc.IModuleCallback»): 1 = update(int kode, int[], float[], String[]) (oneway).
 *  - Moduler: 0 hoved, 1 radio, 2 BT, 3 DVD, 4 lyd, 5 iPod, 6 TV, 7 canbus, 8 TPMS, 9 DVR, 10 ratt, … 14 can-up, 15 forsterker
 *    (samme rekkefølge som handlingene på com.syu.ms/app.ModuleService i kartleggingen).
 * Kilder:
 *  - github.com/PimpinPumpkin/FytRadio – app/src/main/kotlin/com/fytradio/radio/SyuRadioBridge.kt og RadioController.kt
 *    (rå transact, beskrivere, transaksjonskoder, radio C_/U_-koder, FM-enhet 10 kHz på Unisoc, C_APP_ID for kilde,
 *    lyd C_VOL med −3/−4 = demp/opphev)
 *  - github.com/Pacjonek/fyt-dialer – fyttoys/…/internal/ipc (IRemoteToolkit/IRemoteModule/IModuleCallback.kt) og FytBtAdapter.kt (BT-modul 2, C_DIAL 7 med strs[0]=nummer,
 *    C_REDIAL 8, C_PICKUP 9, C_HANG 10, U_PHONE_NAME 7, U_PHONE_STATE 9, register-flagg 1; testet på UIS7862/UIS7870)
 *  - github.com/vasyl91/FYT-Launcher-Mod – app/src/main/java/com/syu/ipc/{FinalBt,FinalRadio,FinalMainServer,IRemoteModule}.java,
 *    com/syu/module/sound/FinalSound.java (U_VOL 2, U_MUTE 3), com/syu/module/main/FinalMain.java (U_APP_ID 0, APP_ID_*)
 *  - github.com/AxesOfEvil/FYTCanbusMonitor – ModuleCodes.kt (modulnumrene)
 * UVERIFISERT på denne stereoen: alt over er bekreftet av andre på FYT-enheter, men ikke her ennå. Neste kartlegging
 * («FYT-tjenesten» i rapporten) viser hva stereoen faktisk svarer. Alt er pakket inn i try/catch – finnes ikke tjenesten
 * (andre stereoer, testene), gjør dette ingenting.
 */
object Fyt {
    const val PKG = "com.syu.ms"
    const val ACTION = "com.syu.ms.toolkit"
    private const val TOOLKIT = "com.syu.ipc.IRemoteToolkit"
    private const val MODULE = "com.syu.ipc.IRemoteModule"
    private const val CALLBACK = "com.syu.ipc.IModuleCallback"

    const val M_MAIN = 0
    const val M_RADIO = 1
    const val M_BT = 2
    const val M_SOUND = 4
    const val M_CANBUS = 7

    /** Oppdateringskodene T'OS ber om per modul (resten logges bare når de kommer). */
    private val subscribe = mapOf(
        M_MAIN to (0..20).toList(),
        // Radio: bånd, frekvens, kanal, PTY, RDS-tekst, stasjonsnavn, RDS av/på, frekvensområde, stereo, søk, PS-tekst
        M_RADIO to listOf(0, 1, 2, 3, 5, 13, 14, 15, 16, 21, 22, 26),
        // BT: ID3 tittel/artist, telefonens navn/nummer/tilstand, ringe-/taletid, BT-musikk spiller, HFP, PBAP
        M_BT to listOf(0, 1, 6, 7, 8, 9, 11, 12, 13, 18, 29, 31),
        // Lyd: volum, demping, modul
        M_SOUND to listOf(1, 2, 3),
    )

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var bound = false
    @Volatile private var toolkit: IBinder? = null
    private val modules = ConcurrentHashMap<Int, IBinder>()
    private val callbacks = ConcurrentHashMap<Int, Binder>()
    private var tries = 0

    /** Koblet til FYT-tjenesten (com.syu.ms). */
    var connected by mutableStateOf(false)
        private set
    /** Til kartleggingen: hva som skjedde med tilkoblingen. */
    @Volatile var status = "ikke prøvd"
        private set

    // ---- Tilstand fra stereoen (oppdateres fra tilbakemeldingene, på hovedtråden) ----
    var appId by mutableIntStateOf(-1); private set
    var radioBandRaw by mutableIntStateOf(Int.MIN_VALUE); private set
    var radioFreqRaw by mutableIntStateOf(-1); private set
    var radioPs by mutableStateOf<String?>(null); private set
    var radioText by mutableStateOf<String?>(null); private set
    var radioStereo by mutableStateOf(false); private set
    var radioSearching by mutableStateOf(false); private set
    var phoneState by mutableIntStateOf(-1); private set
    var phoneName by mutableStateOf<String?>(null); private set
    var phoneNumber by mutableStateOf<String?>(null); private set
    var btavTitle by mutableStateOf<String?>(null); private set
    var btavArtist by mutableStateOf<String?>(null); private set
    var btavPlaying by mutableStateOf(false); private set
    var volume by mutableIntStateOf(-1); private set
    var muted by mutableStateOf(false); private set
    /** Når volumet sist endret seg (elapsedRealtime, 0 = aldri etter første verdi). */
    @Volatile var volumeChangedAt = 0L
        private set

    /** Logg over alt stereoen har sendt (til kartleggingen). */
    private val log = ArrayDeque<String>()
    private val lastByCode = HashMap<String, String>()
    private var startedAt = 0L

    val radioReady get() = connected && modules.containsKey(M_RADIO)
    val btReady get() = connected && modules.containsKey(M_BT)
    /** BT-telefonen er tilkoblet ifølge FYT (null = vet ikke). */
    val phoneConnected: Boolean? get() = FytLogic.phoneConnected(phoneState)
    val ringing get() = phoneState == FytLogic.PHONE_RING
    val inCall get() = phoneState == FytLogic.PHONE_DIAL || phoneState == FytLogic.PHONE_RING || phoneState == FytLogic.PHONE_TALK

    /** Er dette en FYT-stereo (com.syu.ms finnes)? */
    fun present(c: Context): Boolean = runCatching { c.packageManager.getPackageInfo(PKG, 0); true }.getOrDefault(false)

    /** Koble til (kalles fra Hub.start). Gjør ingenting på stereoer uten com.syu.ms. */
    fun start(c: Context) {
        val a = c.applicationContext
        if (bound || connected) return
        if (!present(a)) { status = "com.syu.ms finnes ikke (ikke FYT-stereo)"; return }
        startedAt = SystemClock.elapsedRealtime()
        val i = Intent(ACTION).setPackage(PKG)
        bound = runCatching { a.bindService(i, conn, Context.BIND_AUTO_CREATE) }.getOrElse { status = "bindService feilet: ${it.javaClass.simpleName} ${it.message}"; false }
        if (bound) status = "venter på svar fra com.syu.ms …"
        else {
            if (status.startsWith("venter") || status == "ikke prøvd") status = "bindService ga false (tjenesten tar ikke imot T'OS)"
            retryLater(a)
        }
    }

    private fun retryLater(a: Context) {
        if (tries++ >= 5) return
        main.postDelayed({ start(a) }, 5000L * tries)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            toolkit = service
            Hub.scope.launchIo { setup(service) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            toolkit = null; modules.clear()
            main.post { connected = false }
            status = "frakoblet (com.syu.ms startet på nytt?) – kobler til igjen av seg selv"
        }
    }

    private fun setup(tk: IBinder?) {
        if (tk == null) { status = "tom Binder fra com.syu.ms"; return }
        val found = ArrayList<Int>()
        for (m in 0..17) {
            val b = getRemoteModule(tk, m) ?: continue
            modules[m] = b; found += m
        }
        status = if (found.isEmpty()) "tilkoblet, men ingen moduler svarte (protokollen kan være annerledes)"
                 else "tilkoblet; moduler: ${found.joinToString { FytLogic.moduleName(it) }}"
        for ((m, codes) in subscribe) {
            val b = modules[m] ?: continue
            val cb = callbacks.getOrPut(m) { callback(m) }
            codes.forEach { register(b, cb, it) }
        }
        main.post { connected = found.isNotEmpty() }
    }

    private fun callback(module: Int) = object : Binder() {
        init { attachInterface(null, CALLBACK) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            runCatching {
                data.enforceInterface(CALLBACK)
                val u = data.readInt()
                val ints = data.createIntArray()
                val flts = data.createFloatArray()
                val strs = data.createStringArray()
                onUpdate(module, u, ints, flts, strs)
            }
            return true
        }
    }

    private fun onUpdate(m: Int, u: Int, ints: IntArray?, flts: FloatArray?, strs: Array<String?>?) {
        val line = FytLogic.describe(m, u, ints, flts, strs)
        synchronized(log) {
            val key = "$m/$u"
            if (lastByCode[key] != line) {
                lastByCode[key] = line
                log.addLast(SurveyText.stamp(SystemClock.elapsedRealtime() - startedAt) + "  " + line)
                while (log.size > 300) log.removeFirst()
            }
        }
        SurveyRecorder.note("FYT  $line")
        main.post { apply(m, u, ints, strs) }
    }

    private fun apply(m: Int, u: Int, ints: IntArray?, strs: Array<String?>?) {
        val i0 = ints?.firstOrNull()
        val s0 = strs?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        when (m) {
            M_MAIN -> if (u == 0 && i0 != null) appId = i0
            M_RADIO -> when (u) {
                0 -> if (i0 != null) radioBandRaw = i0
                1 -> if (i0 != null) { if (i0 != radioFreqRaw) { radioPs = null; radioText = null }; radioFreqRaw = i0; radioSearching = false }
                13 -> radioText = s0
                14, 26 -> if (s0 != null) radioPs = s0
                21 -> if (i0 != null) radioStereo = i0 != 0
                22 -> if (i0 != null) radioSearching = i0 != 0
            }
            M_BT -> when (u) {
                0 -> btavTitle = s0
                1 -> btavArtist = s0
                7 -> phoneName = FytLogic.phoneName(strs)
                8 -> phoneNumber = FytLogic.phoneName(strs)
                9 -> FytLogic.phoneStateOf(ints)?.let { st ->
                    if (st != phoneState) BtLink.bump()
                    phoneState = st
                    if (!FytLogic.isCallState(st)) phoneNumber = null
                }
                13 -> if (i0 != null) btavPlaying = i0 == 1
            }
            M_SOUND -> when (u) {
                2 -> if (i0 != null) {
                    if (volume >= 0 && i0 != volume) volumeChangedAt = SystemClock.elapsedRealtime()
                    volume = i0
                }
                3 -> if (i0 != null) muted = i0 != 0
            }
        }
    }

    /* ---------- Kommandoer ---------- */

    private fun cmd(m: Int, code: Int, ints: IntArray? = null, strs: Array<String?>? = null): Boolean {
        val b = modules[m] ?: return false
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(MODULE)
            data.writeInt(code)
            data.writeIntArray(ints)
            data.writeFloatArray(null)
            data.writeStringArray(strs)
            b.transact(IBinder.FIRST_CALL_TRANSACTION, data, null, IBinder.FLAG_ONEWAY)
            SurveyRecorder.note("FYT  T'OS sendte ${FytLogic.moduleName(m)}.cmd($code, ${ints?.toList()}, ${strs?.toList()})")
            true
        } catch (t: Throwable) { false } finally { data.recycle() }
    }

    private fun getRemoteModule(tk: IBinder, m: Int): IBinder? {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(TOOLKIT)
            data.writeInt(m)
            tk.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)
            reply.readException()
            reply.readStrongBinder()
        } catch (t: Throwable) { null } finally { data.recycle(); reply.recycle() }
    }

    private fun register(b: IBinder, cb: Binder, code: Int) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(MODULE)
            data.writeStrongBinder(cb)
            data.writeInt(code)
            data.writeInt(1)            // 1 = send nåverdien med en gang
            b.transact(IBinder.FIRST_CALL_TRANSACTION + 2, data, null, IBinder.FLAG_ONEWAY)
        } catch (_: Throwable) {
        } finally { data.recycle() }
    }

    /** get(kode) på en modul (bare til kartleggingen – FYT svarer ofte bare via tilbakemeldingene). */
    private fun get(m: Int, code: Int): String? {
        val b = modules[m] ?: return null
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(MODULE)
            data.writeInt(code); data.writeIntArray(null); data.writeFloatArray(null); data.writeStringArray(null)
            b.transact(IBinder.FIRST_CALL_TRANSACTION + 1, data, reply, 0)
            reply.readException()
            if (reply.readInt() == 0) "tomt"
            else FytLogic.payload(reply.createIntArray(), reply.createFloatArray(), reply.createStringArray())
        } catch (t: Throwable) { "feil: ${t.javaClass.simpleName}" } finally { data.recycle(); reply.recycle() }
    }

    // Radio (kilde: FytRadio SyuRadioBridge/RadioController; FinalRadio)
    /** Gjør FM/AM-radioen til lydkilden (C_APP_ID: 11 = bilradio på Unisoc, 1 = radio på eldre – begge sendes, som FytRadio). */
    fun radioOn(): Boolean { val a = cmd(M_MAIN, 0, intArrayOf(11)); val b = cmd(M_MAIN, 0, intArrayOf(1)); return a || b }
    /** Slipp radioen som lydkilde (C_APP_ID = 0). */
    fun radioOff(): Boolean = cmd(M_MAIN, 0, intArrayOf(0))
    fun radioSeek(up: Boolean) = cmd(M_RADIO, if (up) 5 else 6)
    fun radioStep(up: Boolean) = cmd(M_RADIO, if (up) 3 else 4)
    fun radioBand(fm: Boolean) = cmd(M_RADIO, 11, intArrayOf(if (fm) -3 else -2))
    fun radioPreset(index: Int) = cmd(M_RADIO, 7, intArrayOf(index))
    fun radioScan() = cmd(M_RADIO, 9)
    /** Direkte frekvens. UVERIFISERT: FytRadio fant at [verdi] alene ble ignorert; FinalRadio har FREQ_DIRECT = 1, så vi sender [1, verdi]. */
    fun radioTune(raw: Int) = cmd(M_RADIO, 13, intArrayOf(1, raw))
    /** Det radioen viser nå (null = vet ikke). */
    val radioFm: Boolean? get() = FytLogic.isFm(radioBandRaw, radioFreqRaw)
    val radioOnAir get() = appId == 1 || appId == 11

    // BT-telefon og BT-musikk (kilde: fyt-dialer FytBtAdapter; FinalBt)
    fun dial(number: String) = cmd(M_BT, 7, strs = arrayOf(number))
    fun answer() = cmd(M_BT, 9)
    fun hangUp() = cmd(M_BT, 10)
    fun reject() = cmd(M_BT, 11).also { cmd(M_BT, 10) }
    fun btavPlayPause() = cmd(M_BT, 2)
    fun btavNext() = cmd(M_BT, 1)
    fun btavPrev() = cmd(M_BT, 0)
    /** BT-musikk som lydkilde (C_APP_ID = 3) og «spill». */
    fun btavOn(): Boolean { val a = cmd(M_MAIN, 0, intArrayOf(3)); val b = cmd(M_BT, 27); return a || b }

    /* ---------- Kartleggingen ---------- */

    fun report(c: Context): String = buildString {
        appendLine("FYT-stereo (com.syu.ms finnes): ${present(c)}")
        appendLine("Tilkobling: $status")
        if (connected) {
            appendLine("Kilde (app-id): $appId ${FytLogic.appName(appId)}")
            appendLine("Radio: bånd=$radioBandRaw frekvens=$radioFreqRaw → ${FytLogic.freqText(radioBandRaw, radioFreqRaw)}  PS=«${radioPs.orEmpty()}»  RT=«${radioText.orEmpty()}»  stereo=$radioStereo")
            appendLine("BT-telefon: tilstand=$phoneState ${FytLogic.phoneStateName(phoneState)}  navn=«${phoneName.orEmpty()}»  nummer=«${phoneNumber.orEmpty()}»")
            appendLine("BT-musikk: spiller=$btavPlaying  «${btavTitle.orEmpty()}» – «${btavArtist.orEmpty()}»")
            appendLine("Volum (lydmodul): $volume  dempet=$muted")
            for (m in listOf(M_MAIN, M_RADIO, M_BT, M_SOUND)) if (modules.containsKey(m))
                appendLine("get(0..3) ${FytLogic.moduleName(m)}: " + (0..3).joinToString("  ") { "$it=${get(m, it)}" })
        }
        val lines = synchronized(log) { log.toList() }
        appendLine("Meldinger fra stereoen (${lines.size}, nyeste nederst):")
        lines.takeLast(200).forEach { appendLine("  $it") }
    }
}

/** Kjør på IO uten å vente (liten hjelper, så Fyt ikke trenger egne coroutine-importer overalt). */
private fun CoroutineScope.launchIo(block: () -> Unit) {
    launch(Dispatchers.IO) { runCatching { block() } }
}

/** Ren logikk for FYT-meldingene. Testes i FytLogicTest. */
internal object FytLogic {
    // FinalBt.PHONE_STATE_*
    const val PHONE_DISCONNECTED = 0
    const val PHONE_LINK = 1
    const val PHONE_CONNECTED = 2
    const val PHONE_DIAL = 3
    const val PHONE_RING = 4
    const val PHONE_TALK = 5
    const val PHONE_PAIR = 6

    private val moduleNames = mapOf(0 to "hoved", 1 to "radio", 2 to "BT", 3 to "DVD", 4 to "lyd", 5 to "iPod", 6 to "TV",
        7 to "canbus", 8 to "TPMS", 9 to "DVR", 10 to "ratt", 11 to "kunde", 12 to "OBD", 13 to "test", 14 to "can-up", 15 to "forsterker",
        16 to "sender", 17 to "g-sensor")
    fun moduleName(m: Int) = moduleNames[m]?.let { "$it($m)" } ?: "modul $m"

    // FinalMain.APP_ID_*
    private val apps = mapOf(0 to "ingen", 1 to "radio", 2 to "BT-telefon", 3 to "BT-musikk", 4 to "DVD", 5 to "AUX", 6 to "TV", 7 to "iPod",
        8 to "lydspiller", 9 to "videospiller", 10 to "Android-app", 11 to "bilradio", 12 to "bil-BT-telefon", 13 to "bil-USB", 14 to "DVR",
        15 to "3G-telefon", 16 to "spesial", 17 to "kunde")
    fun appName(id: Int) = apps[id]?.let { "($it)" }.orEmpty()

    private val states = mapOf(0 to "frakoblet", 1 to "kobler til", 2 to "tilkoblet", 3 to "ringer ut", 4 to "ringer inn", 5 to "samtale", 6 to "parer")
    fun phoneStateName(s: Int) = states[s]?.let { "($it)" }.orEmpty()

    /** U_PHONE_STATE: ints[0] = tilstand 0–6 (ukjente verdier ignoreres, men logges i kartleggingen). */
    fun phoneStateOf(ints: IntArray?): Int? = ints?.firstOrNull()?.takeIf { it in 0..6 }

    fun isCallState(s: Int) = s == PHONE_DIAL || s == PHONE_RING || s == PHONE_TALK

    /** Tilkoblet ifølge FYT: true/false, null = ingen melding ennå. */
    fun phoneConnected(s: Int): Boolean? = when (s) {
        PHONE_CONNECTED, PHONE_DIAL, PHONE_RING, PHONE_TALK -> true
        PHONE_DISCONNECTED, PHONE_LINK, PHONE_PAIR -> false
        else -> null
    }

    /** Navn/nummer: strs[0], eller strs[1] når strs[0] er telefonnummeret i to-telefon-varianten («0»/«1»). Fra fyt-dialer. */
    fun phoneName(strs: Array<String?>?): String? {
        if (strs.isNullOrEmpty()) return null
        val v = if (strs.size >= 2 && strs[0]?.trim()?.toIntOrNull() in 0..1) strs[1] else strs[0]
        return v?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * FM eller AM? Frekvensen avgjør (FM ≥ 6400 i 10 kHz-enheter eller ≥ 64000 kHz; AM 150–1710 kHz), ellers båndet:
     * −3 = FM, −2 = AM (BAND_SWITCH_*), 65536+ = FM-bånd (BAND_FM_INDEX), 0 = FM / 1 = AM (FytRadio sine funn).
     */
    fun isFm(bandRaw: Int, freqRaw: Int): Boolean? = when {
        freqRaw >= 6400 -> true
        freqRaw in 100..1999 -> false
        bandRaw == -3 || bandRaw >= 65536 -> true
        bandRaw == -2 -> false
        bandRaw == 0 -> true
        bandRaw == 1 -> false
        else -> null
    }

    /** Frekvens i kHz: FM i 10 kHz-enheter (Unisoc, f.eks. 10170) eller kHz (PX, 101700); AM i kHz. */
    fun freqKhz(freqRaw: Int, fm: Boolean): Int = if (fm && freqRaw in 1..19_999) freqRaw * 10 else freqRaw

    /** «101.7 MHz» / «1080 kHz» / «–». */
    fun freqText(bandRaw: Int, freqRaw: Int): String {
        if (freqRaw <= 0) return "–"
        val fm = isFm(bandRaw, freqRaw) ?: return "–"
        val khz = freqKhz(freqRaw, fm)
        return if (fm) String.format(Locale.US, "%.1f", khz / 1000.0) else khz.toString()
    }

    /** MHz-tekst (FM) → råverdi i samme enhet som stereoen sender ([sample] = en frekvens den har sendt; 10 kHz som standard). */
    fun rawForFm(mhz: Float, sample: Int): Int = if (sample >= 20_000) Math.round(mhz * 1000) else Math.round(mhz * 100)

    fun payload(ints: IntArray?, flts: FloatArray?, strs: Array<String?>?): String = buildString {
        ints?.let { append("ints=").append(it.take(12).joinToString(",", "[", if (it.size > 12) ",…]" else "]")) }
        flts?.takeIf { it.isNotEmpty() }?.let { append(" flts=").append(it.take(8).joinToString(",", "[", "]")) }
        strs?.let { s -> append(" strs=").append(s.take(6).joinToString(",", "[", if (s.size > 6) ",…]" else "]") { "«${(it ?: "").take(60)}»" }) }
    }.trim().ifEmpty { "(tomt)" }

    fun describe(m: Int, u: Int, ints: IntArray?, flts: FloatArray?, strs: Array<String?>?) =
        "${moduleName(m)} u=$u ${payload(ints, flts, strs)}"
}

/**
 * FYT sin egen statuslinje (hjem-knapp til venstre; BT/Wi-Fi/klokke/volum/«siste apper»/tilbake til høyre) styres av
 * Settings.System «show_navigationbar» (1 = vises; sett i kartleggingen 8. nov). Det er en innstilling Android bare lar
 * systemapper endre (apper med targetSdk ≥ 23 får IllegalArgumentException), så T'OS prøver, og viser ellers ADB-linja.
 */
object FytBars {
    const val KEY = "show_navigationbar"
    const val ADB_HIDE = "adb shell settings put system show_navigationbar 0"
    const val ADB_SHOW = "adb shell settings put system show_navigationbar 1"

    /** 1 = linja vises, 0 = skjult, null = ikke en FYT-stereo. */
    fun current(c: Context): Int? = runCatching { android.provider.Settings.System.getInt(c.contentResolver, KEY) }.getOrNull()

    /** Prøv å vise/skjule linja. false = Android sa nei (bruk ADB). */
    fun set(c: Context, show: Boolean): Boolean = runCatching {
        android.provider.Settings.System.putInt(c.contentResolver, KEY, if (show) 1 else 0)
    }.getOrDefault(false) && current(c) == (if (show) 1 else 0)

    /** Til kartleggingen: kan T'OS skrive innstillingen? (Skriver samme verdi som før – endrer ingenting.) */
    fun writeTest(c: Context): String {
        val cur = current(c) ?: return "innstillingen finnes ikke"
        if (!android.provider.Settings.System.canWrite(c)) return "nei – T'OS mangler «Endre systeminnstillinger»"
        return runCatching { android.provider.Settings.System.putInt(c.contentResolver, KEY, cur); "ja" }
            .getOrElse { "nei – ${it.javaClass.simpleName}: ${SurveyText.clip(it.message, 100)} (bruk ADB: $ADB_HIDE)" }
    }
}
