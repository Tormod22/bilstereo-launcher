package no.bil.launcher

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/* ======================= FM/AM-radioen på FYT-stereoen (radio-modulen i com.syu.ms) ======================= */

/**
 * T'OS sin egen radio på FYT-stereoen (erstatter com.syu.radio sitt skjermbilde). Snakker med radio-modulen (1) via
 * [Fyt] (samme Binder-tilkobling). Fyt.kt sender alle radio-meldinger (u=0–26) hit via [onUpdate].
 *
 * Kodene (FinalRadio.java i github.com/vasyl91/FYT-Launcher-Mod, app/src/main/java/com/syu/ipc/FinalRadio.java – konstantene
 * fra FYT sine egne apper; og github.com/PimpinPumpkin/FytRadio – SyuRadioBridge.kt, RadioController.kt, NOTES.md, prøvd på
 * UIS7870):
 *  - Kommandoer: 0/1 neste/forrige kanal (FYT sin liste), 3/4 trinn opp/ned, 5/6 søk opp/ned, 7 velg kanal, 8 lagre kanal,
 *    9 skann, 11 bånd (−3 FM, −2 AM), 12 område, 13 frekvens (FREQ_DIRECT = 1), 16 RDS, 17 stereo/mono, 18 lokal (LOC),
 *    19 trafikkmeldinger (TA), 20 følg stasjonen (AF), 21 PTY, 22 søk.
 *  - Meldinger: 0 bånd (0/1 = AM1/AM2, 65536–65538 = FM1–FM3 – bekreftet i FYT-Launcher-Mod LauncherRadioWidget.java),
 *    1 frekvens (FM i 10 kHz-enheter på UIS7862 – bekreftet i FYT-Launcher-Mod StockFreq.java og her 8. nov), 2 område,
 *    3 valgt kanal, 4 kanalens frekvens, 5 PTY, 6 AF på/av, 7 TA (trafikkmelding nå), 8 TP (stasjonen har trafikkmeldinger),
 *    9 TA på/av, 13 radiotekst (RT), 14 kanalens navn (kanal-nummer 65536+n = FM, n = AM; tomme navn sett her 8. nov),
 *    15 RDS på/av, 16 [min, maks, trinn, antall], 21 stereo, 22 søk (0 = ingen, 1 auto, 2 fram, 3 tilbake), 23 LOC,
 *    26 stasjonsnavn (PS – bekreftet for UIS7862 i FYT-Launcher-Mod StockRadioSource.java).
 *
 * Det FytRadio fant på sin stereo (NOTES.md): trinn/søk/bånd virker alltid; direkte frekvens [verdi] ble ignorert; «lagre kanal»
 * lagret ikke; «velg kanal» bruker FYT sin egen liste. Derfor:
 *  - T'OS har EGNE forvalg (6 per bank: FM1, FM2, FM3, AM; Prefs «fyt_presets_<bank>»), ikke FYT sin liste.
 *  - Gå til en frekvens: først direkte ([1, verdi] – FinalRadio FREQ_DIRECT; virker det ikke, huskes det), ellers trinn for
 *    trinn med 3/4 til frekvensen stemmer (stereoen melder hver ny frekvens).
 *  - «Søk etter stasjoner»: T'OS søker selv (5) stasjon for stasjon rundt hele båndet, venter på stasjonsnavnet, og lagrer.
 * Signalstyrke: ingen kjent kode – vises ikke. UVERIFISERT her: alt unntatt frekvens/bånd/PS (se kartleggingen).
 */
object FytRadio {
    // ---- Fra stereoen (u=…) ----
    var bandRaw by mutableIntStateOf(Int.MIN_VALUE); private set
    var area by mutableIntStateOf(-1); private set
    var channel by mutableIntStateOf(-1); private set
    var pty by mutableIntStateOf(-1); private set
    var af by mutableIntStateOf(-1); private set
    var ta by mutableIntStateOf(-1); private set
    var tp by mutableIntStateOf(-1); private set
    var taEnabled by mutableIntStateOf(-1); private set
    var rds by mutableIntStateOf(-1); private set
    var loc by mutableIntStateOf(-1); private set
    var searchState by mutableIntStateOf(0); private set
    /** u=16: [min, maks, trinn, antall] for FM og AM (råverdier). */
    internal var fmRange by mutableStateOf<FytRadioLogic.Range?>(null); private set
    internal var amRange by mutableStateOf<FytRadioLogic.Range?>(null); private set
    /** FYT sin egen kanalliste (u=4: kanal → frekvens). Bare til kartleggingen. */
    @Volatile private var mcuChannels: Map<Int, Int> = emptyMap()
    @Volatile private var freqAt = 0L

    /** Brukerens valgte FM-bank (FM1–FM3) – null = følg stereoen. */
    private var userBank by mutableStateOf<String?>(null)

    // ---- Det T'OS holder på med ----
    data class Scan(val found: Int, val progress: Float, val freqRaw: Int)
    var scan by mutableStateOf<Scan?>(null); private set
    /** Frekvensen T'OS går til nå (0 = ingen). */
    var tuningTo by mutableIntStateOf(0); private set
    private var job: Job? = null
    private var mutedByUs = false
    /** Følger lydkilden mens T'OS søker/stiller inn eller har skrudd av lyden (se [watchSource]). */
    private var watch: Job? = null

    /** Testene: later som stereoen har sendt dette (null = ekte data). */
    internal data class Fake(val freqRaw: Int, val ps: String?, val rt: String?, val stereo: Boolean, val onAir: Boolean)
    internal var fake by mutableStateOf<Fake?>(null)

    // ---- Det skjermen viser ----
    val active get() = fake != null || HwRadio.fyt
    val freqRaw get() = fake?.freqRaw ?: Fyt.radioFreqRaw
    val ps: String? get() { val f = fake; return if (f != null) f.ps else Fyt.radioPs }
    val rt: String? get() { val f = fake; return if (f != null) f.rt else Fyt.radioText }
    val stereo get() = fake?.stereo ?: Fyt.radioStereo
    /** Radioen er stereoens lydkilde nå. */
    val onAir get() = fake?.onAir ?: (HwRadio.fyt && Fyt.radioOnAir)
    val isFm: Boolean? get() = FytLogic.isFm(bandRaw, freqRaw)
    val freqText get() = FytLogic.freqText(bandRaw, freqRaw)
    val unit get() = if (isFm == false) "kHz" else "MHz"
    val searching get() = scan != null || searchState != 0 || Fyt.radioSearching
    /** Banken som vises: AM, eller FM1–FM3 (brukerens valg, ellers det stereoen sier). */
    val bank: String get() = FytRadioLogic.bank(isFm, bandRaw, userBank)
    /** Stasjonsnavnet: PS fra RDS, ellers navnet på et lagret forvalg med samme frekvens. */
    val stationName: String? get() = ps ?: presetsOf(bank).firstOrNull { it?.freqRaw == freqRaw && freqRaw > 0 }?.name
    val ptyName get() = FytRadioLogic.ptyName(pty)
    /** Musikkortet på forsiden når radioen er lydkilden: stasjonen (eller «FM 101,70») og frekvens/radiotekst under. */
    val cardTitle: String get() = stationName ?: ((if (isFm == false) "AM " else "FM ") + freqText)
    val cardSub: String get() = listOfNotNull(if (stationName != null) "$freqText $unit" else null, rt).joinToString("  ·  ")
        .ifEmpty { if (Fyt.muted) "Lyd av" else "Radio" }

    internal fun presetsOf(bank: String): List<FytRadioLogic.Slot?> = FytRadioLogic.decode(Prefs.str(key(bank)))
    private fun key(bank: String) = "fyt_presets_$bank"
    private fun store(bank: String, list: List<FytRadioLogic.Slot?>) = Prefs.set(key(bank), FytRadioLogic.encode(list))

    /** Fra Fyt (hovedtråden): én melding fra radio-modulen. */
    internal fun onUpdate(u: Int, ints: IntArray?, strs: Array<String?>?) {
        val i0 = ints?.firstOrNull()
        val s0 = strs?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        when (u) {
            0 -> if (i0 != null) { if (FytRadioLogic.dropUserBank(bandRaw, i0)) userBank = null; bandRaw = i0 }
            1 -> freqAt = SystemClock.elapsedRealtime()
            2 -> if (i0 != null) area = i0
            3 -> if (i0 != null) channel = i0
            4 -> if (ints != null && ints.size >= 2) mcuChannels = (mcuChannels + (ints[0] to ints[1])).entries.take(60).associate { it.key to it.value }
            5 -> if (i0 != null) pty = i0
            6 -> if (i0 != null) af = i0
            7 -> if (i0 != null) ta = i0
            8 -> if (i0 != null) tp = i0
            9 -> if (i0 != null) taEnabled = i0
            15 -> if (i0 != null) rds = i0
            16 -> FytRadioLogic.range(ints)?.let { r -> if (r.fm) fmRange = r else amRange = r }
            22 -> if (i0 != null) searchState = i0
            23 -> if (i0 != null) loc = i0
            // Stasjonsnavnet kom: gi forvalg med samme frekvens navnet (hvis de ikke har ett)
            26 -> if (s0 != null && scan == null) nameHere(s0)
        }
    }

    private fun nameHere(name: String) {
        val f = Fyt.radioFreqRaw
        if (f <= 0) return
        for (b in FytRadioLogic.BANKS) {
            val list = presetsOf(b)
            if (list.any { it != null && it.freqRaw == f && it.name == null })
                store(b, list.map { if (it != null && it.freqRaw == f && it.name == null) it.copy(name = FytRadioLogic.clean(name)) else it })
        }
    }

    /* ---------- Knappene ---------- */

    private fun stopJob() { job?.cancel(); job = null; tuningTo = 0; scan = null }

    fun seek(up: Boolean) { stopJob(); Fyt.radioSeek(up) }
    fun step(up: Boolean) { stopJob(); Fyt.radioStep(up) }

    /** Velg bank: FM1–FM3 er T'OS sine sider med forvalg (FM-båndet), AM bytter til AM. */
    fun selectBank(b: String) {
        stopJob()
        if (b == "AM") { if (isFm != false) Fyt.radioBand(fm = false); return }
        userBank = b
        if (isFm == false) Fyt.radioBand(fm = true)
    }

    /** Trykk på et forvalg: slå på radioen om nødvendig og gå til frekvensen. */
    fun recall(c: Context, bank: String, slot: Int) {
        val p = presetsOf(bank).getOrNull(slot) ?: run {
            Notify.show("Hold inne for å lagre stasjonen du hører på plass ${slot + 1}"); return
        }
        if (bank != "AM") userBank = bank
        tune(c, p.freqRaw)
    }

    /** Hold inne et forvalg: lagre frekvensen (og stasjonsnavnet) som spiller nå. */
    fun save(bank: String, slot: Int) {
        val f = freqRaw
        val fm = isFm
        if (f <= 0 || fm == null || !onAir) { Notify.show("Start radioen og finn en stasjon først"); return }
        if ((bank == "AM") != (fm == false)) { Notify.show("Bytt til ${if (fm) "FM" else "AM"} for å lagre denne stasjonen"); return }
        val name = ps?.let { FytRadioLogic.clean(it) }
        store(bank, presetsOf(bank).toMutableList().also { it[slot] = FytRadioLogic.Slot(f, name) })
        val label = FytLogic.freqLabel(bandRaw, f)
        Notify.show("Lagret på plass ${slot + 1}: " + (name?.let { "$it ($label)" } ?: label))
    }

    /** Neste/forrige (musikk-knappene, medietaster): neste forvalg i banken, ellers søk. */
    fun next(up: Boolean) {
        val c = Fyt.appContext
        val list = presetsOf(bank)
        val i = FytRadioLogic.nextSlot(list.map { it?.freqRaw }, freqRaw, up)
        if (i != null && c != null) tune(c, list[i]!!.freqRaw) else seek(up)
    }

    /** Spill/pause på radioen = lyd av/på (å slippe kilden ville startet forrige app igjen – FytRadio NOTES.md). */
    fun toggleMute() {
        val mute = !Fyt.muted
        if (Fyt.moduleCmd(Fyt.M_SOUND, 0, intArrayOf(if (mute) -3 else -4))) {
            mutedByUs = mute
            // Huskes over en omstart: krasjer/startes T'OS på nytt mens lyden er av, skrus den på igjen (FytMain.onConnected)
            Prefs.setQuiet(MUTED_KEY, mute)
            if (mute) watchSource()
        }
    }

    private const val MUTED_KEY = "fyt_tos_muted"

    /** Lyden er av fordi T'OS skrudde den av (også fra en tidligere kjøring). */
    internal val tosMuted: Boolean get() = mutedByUs || runCatching { Prefs.bool(MUTED_KEY) }.getOrDefault(false)

    /**
     * Skru på lyden hvis det var T'OS som skrudde den av (også fra en tidligere kjøring). true = sendt.
     * [force]: også når FYT-funksjonene er slått av (lyden skal aldri bli stående av på grunn av T'OS).
     */
    internal fun releaseTosMute(force: Boolean = false): Boolean {
        if (!tosMuted) return false
        mutedByUs = false; Prefs.setQuiet(MUTED_KEY, false)
        return Fyt.moduleCmd(Fyt.M_SOUND, 0, intArrayOf(-4), force = force)
    }

    /**
     * Bytter stereoen selv lydkilde (MUSIC-/BT-knappen, FYT sin egen app) mens T'OS søker/stiller inn eller har skrudd av
     * lyden, sier ingen fra til T'OS (HwRadio.powerOff/yieldTo kalles ikke). Da ville søket gått videre i blinde og
     * musikken vært stille (dempingen i FYT gjelder alle kilder). Sjekker kilden hvert halve sekund så lenge det trengs.
     */
    private fun watchSource() {
        if (watch?.isActive == true) return
        watch = Hub.scope.launch {
            var wasOn = Fyt.radioOnAir
            while (isActive && (job?.isActive == true || mutedByUs)) {
                delay(500)
                if (Fyt.radioOnAir) wasOn = true
                else if (wasOn) { watch = null; onRadioOff(); return@launch }
            }
            if (watch == coroutineContext.job) watch = null
        }
    }

    /** Radioen slås av / en annen kilde tar over: stopp det T'OS holdt på med, og skru på lyden igjen hvis T'OS tok den. */
    fun onRadioOff() {
        watch?.cancel(); watch = null
        stopJob()
        if (mutedByUs) { mutedByUs = false; Prefs.setQuiet(MUTED_KEY, false); Fyt.moduleCmd(Fyt.M_SOUND, 0, intArrayOf(-4)) }
    }

    private suspend fun ensureOn(c: Context) {
        if (Fyt.radioOnAir) return
        HwRadio.powerOn(c)
        waitFor(2500) { Fyt.radioOnAir && Fyt.radioFreqRaw > 0 }
    }

    /** Gå til frekvensen [target] (råverdi som stereoen sender). */
    fun tune(c: Context, target: Int) {
        stopJob()
        if (target <= 0) return
        tuningTo = target
        job = Hub.scope.launch {
            try {
                ensureOn(c)
                val fm = FytLogic.isFm(Int.MIN_VALUE, target) ?: true
                if (isFm != fm) { Fyt.radioBand(fm); waitFor(2500) { isFm == fm } }
                if (!tuneSteps(target)) Notify.show("Fikk ikke stilt inn ${FytLogic.freqLabel(Int.MIN_VALUE, target)}")
            } finally {
                // Bare når dette fortsatt er jobben: en avbrutt jobb rydder ellers bort en ny innstilling (stopJob har ryddet)
                if (job == coroutineContext.job) { tuningTo = 0; job = null }
            }
        }
        watchSource()
    }

    private suspend fun tuneSteps(target: Int): Boolean {
        if (Fyt.radioFreqRaw == target) return true
        // 1) Direkte (husker om stereoen gjør det)
        if (Prefs.int("fyt_radio_direct", 0) >= 0) {
            Fyt.radioTune(target)
            val ok = waitFor(800) { Fyt.radioFreqRaw == target }
            if (Prefs.int("fyt_radio_direct", 0) == 0 || ok) Prefs.set("fyt_radio_direct", if (ok) 1 else -1)
            if (ok) return true
        }
        // 2) Trinn for trinn (bekreftet av FytRadio): stopp når frekvensen stemmer eller vi har gått forbi
        val first = Fyt.radioFreqRaw
        if (first <= 0) return false
        val dir = Integer.signum(target - first)
        var misses = 0
        repeat(FytRadioLogic.MAX_STEPS) {
            val cur = Fyt.radioFreqRaw
            if (FytRadioLogic.stepDone(cur, target, dir)) return true
            Fyt.radioStep(target > cur)
            if (!waitFor(450) { Fyt.radioFreqRaw != cur }) { if (++misses >= 3) return false } else misses = 0
        }
        return false
    }

    /** «Søk etter stasjoner»: T'OS søker selv rundt hele båndet og lagrer stasjonene i forvalgene (FM: FM1–FM3, AM). */
    fun scanAll(c: Context) {
        if (scan != null) return
        stopJob()
        scan = Scan(0, 0f, freqRaw)
        job = Hub.scope.launch {
            val found = LinkedHashMap<Int, String?>()
            var fm = true
            try {
                ensureOn(c)
                fm = isFm ?: true
                val start = Fyt.radioFreqRaw
                val range = (if (fm) fmRange else amRange) ?: FytRadioLogic.defaultRange(fm, start)
                val max = if (fm) 18 else 6
                var guard = 0
                while (isActive && guard++ < 60) {
                    val before = Fyt.radioFreqRaw
                    Fyt.radioSeek(true)
                    val landed = awaitSeek(before) ?: break
                    if (FytRadioLogic.scanDone(found.keys, landed, max)) break
                    // Vent litt på stasjonsnavnet (RDS kommer etter 1–2 s)
                    waitFor(1800) { Fyt.radioPs != null && Fyt.radioFreqRaw == landed }
                    found[landed] = Fyt.radioPs?.takeIf { Fyt.radioFreqRaw == landed }?.let { FytRadioLogic.clean(it) }
                    scan = Scan(found.size, FytRadioLogic.progress(start, landed, range), landed)
                }
            } finally {
                // Avbrutt (Avbryt, Stopp, annen kilde): forvalgene beholdes som de var. stopJob har da ryddet – og et nytt
                // søk kan allerede ha startet, så scan røres bare når dette fortsatt er jobben.
                val mine = job == coroutineContext.job
                if (mine) { scan = null; job = null }
                if (!isActive || !mine) Unit
                else if (found.isNotEmpty()) {
                    val banks = FytRadioLogic.fill(found, fm)
                    banks.forEach { (b, l) -> store(b, l) }
                    val used = banks.filterValues { l -> l.any { it != null } }.keys
                    Notify.show("Fant ${found.size} stasjoner – lagret på ${used.first()}" + (if (used.size > 1) "–${used.last()}" else ""))
                    val firstBank = banks.keys.first()
                    if (fm) userBank = firstBank
                    banks[firstBank]?.firstOrNull()?.let { s -> job = Hub.scope.launch { tuneSteps(s.freqRaw) } }
                } else Notify.show("Fant ingen stasjoner. Sjekk at antennen står i.")
            }
        }
        watchSource()
    }

    fun cancel() { if (scan != null) Notify.show("Søket er stoppet"); stopJob() }

    /** Venter til søket har landet på en ny stasjon. Gir frekvensen, eller null når ingenting skjer. */
    private suspend fun awaitSeek(before: Int): Int? {
        val t0 = SystemClock.elapsedRealtime()
        var saw = false
        var last = before
        var changedAt = t0
        while (SystemClock.elapsedRealtime() - t0 < 12_000) {
            delay(60)
            val now = SystemClock.elapsedRealtime()
            if (searchState != 0) saw = true
            val f = Fyt.radioFreqRaw
            if (f != last) { last = f; changedAt = now }
            if (saw && searchState == 0 && now - changedAt > 300) return f
            if (!saw && last != before && now - changedAt > 1200) return f
        }
        return null
    }

    private suspend fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        while (!cond()) {
            if (SystemClock.elapsedRealtime() - t0 > ms) return false
            delay(40)
        }
        return true
    }

    // ---- RDS-brytere (bare når stereoen har sagt hva de står på) ----
    fun setTa(on: Boolean) = Fyt.moduleCmd(Fyt.M_RADIO, 19, intArrayOf(if (on) 1 else 0))
    fun setAf(on: Boolean) = Fyt.moduleCmd(Fyt.M_RADIO, 20, intArrayOf(if (on) 1 else 0))
    fun setLoc(on: Boolean) = Fyt.moduleCmd(Fyt.M_RADIO, 18, intArrayOf(if (on) 1 else 0))

    /** Kartleggingen. */
    fun report(): String = buildString {
        appendLine("Radio-kodene (u=… i meldingslista over): " + FytRadioLogic.legend())
        appendLine("Bånd (u=0): $bandRaw → ${FytRadioLogic.bandName(bandRaw)}   T'OS-bank: $bank")
        appendLine("Område (u=2): $area ${FytRadioLogic.areaName(area)}   valgt kanal (u=3): $channel")
        appendLine("PTY (u=5): $pty ${ptyName.orEmpty()}   AF (u=6): $af   TA nå (u=7): $ta   TP (u=8): $tp   TA på (u=9): $taEnabled   RDS (u=15): $rds   LOC (u=23): $loc")
        appendLine("Søk (u=22): $searchState   FM-område (u=16): ${fmRange ?: "–"}   AM-område: ${amRange ?: "–"}")
        appendLine("Direkte frekvens (FREQ_DIRECT) virker: ${when (Prefs.int("fyt_radio_direct", 0)) { 1 -> "ja"; -1 -> "nei (T'OS går trinn for trinn)"; else -> "ikke prøvd" }}")
        appendLine("FYT sin kanalliste (u=4): " + mcuChannels.entries.take(24).joinToString { "${it.key}=${it.value}" }.ifEmpty { "ingenting" })
        appendLine("T'OS sine forvalg: " + FytRadioLogic.BANKS.joinToString("  ") { b ->
            "$b[" + presetsOf(b).joinToString(",") { it?.let { s -> s.freqRaw.toString() + (s.name?.let { n -> "«$n»" } ?: "") } ?: "–" } + "]"
        })
    }
}

/** Ren logikk for radioen. Testes i FytRadioTest. */
internal object FytRadioLogic {
    val BANKS = listOf("FM1", "FM2", "FM3", "AM")
    const val SLOTS = 6
    const val MAX_STEPS = 300

    data class Slot(val freqRaw: Int, val name: String?)
    data class Range(val min: Int, val max: Int, val step: Int, val fm: Boolean) {
        override fun toString() = "$min–$max trinn $step"
    }

    /**
     * Skal brukerens FM-bank glemmes når stereoen melder bånd [new] (før: [old])? Bare når stereoen selv byttet mellom
     * FM1–FM3. Ikke når T'OS bytter fra AM til FM (forvalg i FM2 trykket mens radioen stod på AM) – da skal FM2 vises.
     */
    fun dropUserBank(old: Int, new: Int): Boolean = new != old && fmBank(new) != null && fmBank(old) != null

    /** u=0: 65536–65538 = FM1–FM3 (FinalRadio BAND_FM_INDEX), 0/1 = AM1/AM2. */
    fun fmBank(bandRaw: Int): String? = if (bandRaw in 65536..65538) "FM${bandRaw - 65535}" else null

    fun bandName(bandRaw: Int): String = when {
        bandRaw in 65536..65538 -> "FM${bandRaw - 65535}"
        bandRaw == 0 || bandRaw == 1 -> "AM${bandRaw + 1} (eller FM på noen stereoer)"
        bandRaw == -3 -> "FM"
        bandRaw == -2 -> "AM"
        bandRaw == Int.MIN_VALUE -> "ikke meldt"
        else -> "ukjent"
    }

    /** Banken T'OS viser: AM når radioen er på AM, ellers brukerens FM-bank, ellers stereoens, ellers FM1. */
    fun bank(isFm: Boolean?, bandRaw: Int, userBank: String?): String =
        if (isFm == false) "AM" else userBank?.takeIf { it != "AM" } ?: fmBank(bandRaw) ?: "FM1"

    /** u=2 (FinalRadio AREA_*). Tallene kan være annerledes på denne stereoen – bare til kartleggingen. */
    fun areaName(a: Int): String = when (a) {
        0 -> "(USA?)"; 1 -> "(Latin-Amerika?)"; 2 -> "(Europa/Kina?)"; 3 -> "(OIRT/Øst-Europa?)"; 4 -> "(Japan?)"; else -> ""
    }

    /** u=16: [min, maks, trinn, antall]. FM når maks ≥ 2000 (10 kHz-enheter eller kHz), ellers AM (kHz). */
    fun range(ints: IntArray?): Range? {
        if (ints == null || ints.size < 3) return null
        val (min, max, step) = Triple(ints[0], ints[1], ints[2])
        if (min <= 0 || max <= min) return null
        return Range(min, max, step, fm = max >= 2000)
    }

    /** Når stereoen ikke har sendt u=16: Europa (FM 87,5–108 MHz i samme enhet som [sample], AM 522–1620 kHz). */
    fun defaultRange(fm: Boolean, sample: Int): Range = when {
        !fm -> Range(522, 1620, 9, false)
        sample >= 20_000 -> Range(87_500, 108_000, 100, true)
        else -> Range(8750, 10800, 10, true)
    }

    /** Hvor langt rundt båndet søket har kommet (0–1), fra [start] og oppover (går rundt). */
    fun progress(start: Int, at: Int, r: Range): Float {
        val span = (r.max - r.min).coerceAtLeast(1)
        val d = ((at - start) % span + span) % span
        return (d.toFloat() / span).coerceIn(0f, 1f)
    }

    /** Ferdig: vi er tilbake på en stasjon vi har funnet (rundt hele båndet), eller det er nok. */
    fun scanDone(found: Collection<Int>, landed: Int, max: Int): Boolean = landed <= 0 || landed in found || found.size >= max

    /** Stasjonene (sortert på frekvens) fordelt på bankene: FM 6 i hver av FM1–FM3, AM 6. */
    fun fill(found: Map<Int, String?>, fm: Boolean): LinkedHashMap<String, List<Slot?>> {
        val sorted = found.entries.sortedBy { it.key }.map { Slot(it.key, it.value) }
        val out = LinkedHashMap<String, List<Slot?>>()
        val banks = if (fm) BANKS.take(3) else listOf("AM")
        banks.forEachIndexed { i, b ->
            val part = sorted.drop(i * SLOTS).take(SLOTS)
            out[b] = part + List(SLOTS - part.size) { null }
        }
        return out
    }

    /** Ett trinn til? Ferdig når frekvensen stemmer eller vi har gått forbi (rutenettet passer ikke). */
    fun stepDone(cur: Int, target: Int, dir: Int): Boolean = cur == target || dir == 0 || Integer.signum(target - cur) != dir

    /** Neste/forrige lagrede plass fra [cur]. Står vi ikke på et forvalg: nærmeste over/under i frekvens. null = ingen forvalg. */
    fun nextSlot(freqs: List<Int?>, cur: Int, up: Boolean): Int? {
        val used = freqs.indices.filter { (freqs[it] ?: 0) > 0 }
        if (used.isEmpty()) return null
        val here = used.indexOfFirst { freqs[it] == cur }
        if (here >= 0) {
            if (used.size == 1) return null
            return used[(here + if (up) 1 else used.size - 1) % used.size]
        }
        val byFreq = used.sortedBy { freqs[it]!! }
        return if (up) byFreq.firstOrNull { freqs[it]!! > cur } ?: byFreq.first()
               else byFreq.lastOrNull { freqs[it]!! < cur } ?: byFreq.last()
    }

    /** Navn uten tegnene lagringen bruker, og uten fyll (PS er 8 tegn med mellomrom). */
    fun clean(s: String): String? = s.replace(Regex("[|;\\r\\n]"), " ").replace(Regex("\\s+"), " ").trim().take(24).ifEmpty { null }

    /** Lagring: «10170|NRK P1;;9430|;…» – alltid 6 plasser. */
    fun encode(list: List<Slot?>): String = (0 until SLOTS).joinToString(";") { i ->
        list.getOrNull(i)?.let { "${it.freqRaw}|${it.name?.let(::clean).orEmpty()}" }.orEmpty()
    }

    fun decode(s: String): List<Slot?> {
        val parts = s.split(';')
        return (0 until SLOTS).map { i ->
            val p = parts.getOrNull(i)?.takeIf { it.isNotBlank() } ?: return@map null
            val f = p.substringBefore('|').trim().toIntOrNull()?.takeIf { it > 0 } ?: return@map null
            Slot(f, p.substringAfter('|', "").trim().ifEmpty { null })
        }
    }

    /** Kort tekst på en forvalgsknapp: «101,7» (FM, én desimal når den andre er 0) / «1008». */
    fun shortFreq(raw: Int): String {
        val full = FytLogic.freqText(Int.MIN_VALUE, raw)
        return if (full.length > 3 && full.endsWith("0") && full.contains(',')) full.dropLast(1) else full
    }

    /** RDS-programtype (europeisk liste). null = ingen/ukjent. Gjetning: FYT sender RDS-nummeret som det er (u=5). */
    private val pty = listOf(null, "Nyheter", "Aktualiteter", "Informasjon", "Sport", "Utdanning", "Drama", "Kultur", "Vitenskap",
        "Variert", "Pop", "Rock", "Lett musikk", "Lett klassisk", "Klassisk", "Annen musikk", "Vær", "Økonomi", "Barn", "Samfunn",
        "Religion", "Ring inn", "Reise", "Fritid", "Jazz", "Country", "Norsk musikk", "Gamle hits", "Folkemusikk", "Dokumentar",
        "Alarmtest", "Alarm")
    fun ptyName(code: Int): String? = pty.getOrNull(code)

    /** Navn på radio-modulens meldinger (kartleggingen). */
    private val updateNames = mapOf(0 to "bånd", 1 to "frekvens", 2 to "område", 3 to "valgt kanal", 4 to "kanalens frekvens",
        5 to "PTY", 6 to "AF på/av", 7 to "TA nå", 8 to "TP", 9 to "TA på/av", 10 to "PI-søk", 11 to "TA-søk", 12 to "PTY-søk",
        13 to "radiotekst", 14 to "kanalens navn", 15 to "RDS på/av", 16 to "frekvensområde", 17 to "følsomhet AM",
        18 to "følsomhet FM", 19 to "auto-følsomhet", 20 to "skann", 21 to "stereo", 22 to "søk", 23 to "LOC", 24 to "sortering",
        25 to "antenne", 26 to "stasjonsnavn")
    fun updateName(u: Int): String? = updateNames[u]
    fun legend(): String = updateNames.entries.joinToString { "${it.key} ${it.value}" }
}
