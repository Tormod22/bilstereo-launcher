package no.bil.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/* ======================= FYT-hovedmodulen (modul 0 i com.syu.ms): lys, rygging, tenning, lysstyrke ======================= */

/**
 * Det stereoens egne innstillinger (com.syu.settings) og hovedmodulen i com.syu.ms vet om bilen: om lysene er på,
 * om bilen rygger, om tenningen (ACC) er på, håndbrekket og skjermens lysstyrke (baklyset styres av MCU-en, ikke Android).
 * T'OS leser det via samme Binder som [Fyt] (hoved-tilbakemeldingene sendes hit fra Fyt.apply).
 *
 * Kodene (FinalMain / FinalSound – samme tall i alle kildene under):
 *  - github.com/liyunkuai/FYTUSBDVR – src/com/syu/module/main/FinalMain.java (med kinesiske forklaringer på hver kode)
 *    og module/sound/FinalSound.java (C_BEEP 8, C_NAVI_MIX 9, U_BEEP 4, U_NAVI_MIX 14 …).
 *  - github.com/vasyl91/FYT-Launcher-Mod – app/src/main/java/com/syu/ipc/data/FinalMain.java (nyere, samme tall + flere),
 *    com/syu/car/CarStates.java (registrerer 0, 4, 12, 3, 50 … og bruker dem), com/android/launcher66/settings/
 *    HeadlightNightMode.java (U_LAMPLET ≠ 0 = lysene på = natt; glemmes ved søvn – etter ACC av er lysene av, og
 *    MCU-en melder dem først når de endres), DayNightBrightness.java (lysstyrken settes med hoved cmd C_BRIGHT_LEVEL 10,
 *    [0–100] – «Settings.System.SCREEN_BRIGHTNESS virker ikke på FYT»; testet der på UIS7862 og UIS7870),
 *    WakeSignals.java (U_ACC_ON 50 kom 1,2 s før skjermen gikk av og 0,1 s før den kom på; U_BLACK_SCREEN 3 = svart skjerm
 *    uten søvn).
 *  - github.com/Altair571/tarmac – docs/HEADUNIT_FYT_DUDU.md (fastvareanalyse UIS7870: U_ACC_ON 50, U_BACKCAR 12,
 *    U_LAMPLET 4 sendes til alle som registrerer seg; kringkasting «com.syu.ms» med int «cmd.acc» 1/0).
 *  - github.com/iamvivekkaushik/Revv – docs/FYT_BLUETOOTH.md (com.syu.us viser ryggebildet når U_BACKCAR ≠ 0).
 * BEKREFTET av andre på FYT-stereoer (ikke på denne ennå): U_LAMPLET 4, U_BACKCAR 12, U_ACC_ON 50, U_BLACK_SCREEN 3,
 * C_BRIGHT_LEVEL 10 [0–100]. BARE I KODELISTENE (formatet gjettes, oppdages mens T'OS kjører): U_HANDBRAKE 7,
 * U_BRIGHT_LEVEL 31 / _DAY 32 / _NIGHT 33, tastelyd (lyd C_BEEP 8 / U_BEEP 4) og navigasjonsmiks (lyd C_NAVI_MIX 9 /
 * U_NAVI_MIX 14). Tastelyd og navigasjonsmiks vises bare når stereoen har meldt 0/1, og T'OS sjekker at den svarer.
 * Klokke-synk, oppstartsvolum og forsinkelse før skjermen slås av: koder finnes (U_CUT_ACC_POWER 25, U_CUTACC_DELAY…71,
 * lyd C_DEF_VOL_ON_BOOT 12), men ingen bruker dem i åpen kode – bare logget i kartleggingen.
 */
internal object FytMainLogic {
    const val U_MCU_ON = 1
    const val U_STANDBY = 2
    const val U_BLACK_SCREEN = 3
    const val U_LAMPLET = 4
    const val U_HANDBRAKE = 7
    const val U_BACKCAR = 12
    const val U_BRIGHT_LEVEL = 31
    const val U_BRIGHT_DAY = 32
    const val U_BRIGHT_NIGHT = 33
    const val U_ACC_ON = 50
    const val C_BRIGHT_LEVEL = 10

    // Lydmodulen (4)
    const val S_U_BEEP = 4
    const val S_U_NAVI_MIX = 14
    const val S_C_BEEP = 8
    const val S_C_NAVI_MIX = 9

    /** Hovedkoder over 20 som T'OS også ber om (0–20 registreres allerede i Fyt). */
    val SUBSCRIBE: List<Int> = listOf(25, 28, 31, 32, 33, 34, 36, 38, 39, 40, 43, 46, 47, 50, 59, 62, 65, 66, 69, 71, 76, 79, 101)

    /** Laveste lysstyrke T'OS sender (0 kan slå baklyset helt av på noen FYT – da ser sjåføren ingenting). */
    const val MIN_LEVEL = 5

    /** Rygger-tilstanden stoler vi på høyst så lenge (står den fast, blir ikke T'OS borte for alltid). */
    const val REVERSE_MAX_MS = 10 * 60_000L
    /** Etter rygging: la stereoen rydde bort kamerabildet før T'OS gjør noe. */
    const val REVERSE_GRACE_MS = 2_000L

    private val names = mapOf(
        0 to "lydkilde", 1 to "MCU på", 2 to "hvilemodus", 3 to "svart skjerm", 4 to "lys (ILL)", 5 to "start med hvilken som helst knapp",
        6 to "start navigasjon ved oppstart", 7 to "håndbrekk", 8 to "håndbrekk-sjekk på", 9 to "SD1", 10 to "SD2", 11 to "USB",
        12 to "rygger", 13 to "radar vises", 14 to "radar foran venstre", 15 to "radar foran midt-venstre", 16 to "radar foran midt-høyre",
        17 to "radar foran høyre", 18 to "radar bak venstre", 19 to "radar bak midt-venstre", 20 to "radar bak midt-høyre",
        21 to "radar bak høyre", 22 to "radar i ryggebildet", 23 to "ryggelinjer", 24 to "speilvendt ryggebilde", 25 to "strøm ved ACC av",
        26 to "klokke i video", 27 to "radar i ryggebildet på", 28 to "navigasjonsapp", 29 to "lys etter klokka", 31 to "lysstyrke nå",
        32 to "lysstyrke dag", 33 to "lysstyrke natt", 34 to "MCU-versjon", 35 to "MCU-serienummer", 36 to "svart skjerm etter (s)",
        37 to "spillerkommando", 38 to "MCU-strøm (hvile/av)", 39 to "app-synlighet", 40 to "utetemperatur", 41 to "rattvinkel",
        42 to "stemmekommando", 43 to "ARM sover/våkner", 45 to "melding", 46 to "MCU-feilkode", 47 to "ryggekamera-type",
        48 to "MCU-oppdatering", 49 to "ID3-tittel", 50 to "tenning (ACC)", 51 to "panelknapp-type", 52 to "panellys ved oppstart",
        53 to "panellys alltid", 54 to "panellys-farge", 55 to "360-kamera", 56 to "vifte", 57 to "stemmen bruker lyden",
        58 to "speilvendt video", 59 to "MCU ber om video", 60 to "retningsknapp", 61 to "omstart av ARM", 62 to "MCU første start",
        63 to "antall panelknapp-typer", 64 to "bevegelser", 65 to "stereoens eget ryggekamera", 66 to "skjerm av ved ACC av",
        67 to "eCarLink", 68 to "kamera av/på", 69 to "videosignal", 71 to "forsinkelse før skjerm av ved ACC av", 74 to "videospiller",
        75 to "radarstrøm", 76 to "flymodus i hvile", 77 to "berøring", 79 to "stemningslys", 81 to "360-kamera ved rygging",
        83 to "360-kamera i rygging på", 101 to "GPS-fart", 133 to "knapp")
    fun codeName(u: Int): String = names[u].orEmpty()

    private val soundNames = mapOf(4 to "tastelyd", 5 to "oppstartsvolum", 7 to "demp ved rygging", 14 to "navigasjonsmiks",
        15 to "annen lyd under navigasjon %", 16 to "navigasjonsvolum %", 23 to "volum ved rygging (0–10)")
    fun soundName(u: Int): String = soundNames[u].orEmpty()

    /** «Av/på»-meldinger (lys, rygging, ACC, håndbrekk): ints[0] ≠ 0 = på (som vasyl91/CarStates). null = ingen verdi. */
    fun flag(ints: IntArray?): Boolean? = ints?.firstOrNull()?.let { it != 0 }

    /** Ekte 0/1 (tastelyd, navigasjonsmiks): andre tall = ukjent format → null (da vises ikke valget). */
    fun binary(ints: IntArray?): Boolean? = when (ints?.firstOrNull()) { 0 -> false; 1 -> true; else -> null }

    /** Lysstyrke 0–100 fra stereoen (andre tall ignoreres). */
    fun level(ints: IntArray?): Int? = ints?.firstOrNull()?.takeIf { it in 0..100 }

    /** Glider (0–1) → nivå T'OS sender (5–100). */
    fun levelFor(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100).roundToInt().coerceIn(MIN_LEVEL, 100)

    /** Natt? «DAY»/«NIGHT» bestemmer; ellers lysene når de er kjent og valget er på; ellers sola. */
    fun night(mode: String, byLights: Boolean, lightsOn: Boolean?, sunNight: Boolean): Boolean = when (mode) {
        "DAY" -> false
        "NIGHT" -> true
        else -> if (byLights && lightsOn != null) lightsOn else sunNight
    }

    enum class Edge { NONE, ON, OFF }

    /** Endring i en av/på-tilstand. Første verdi (nåverdien ved registrering) er ingen endring. */
    fun edge(prev: Boolean?, now: Boolean): Edge = when {
        prev == null || prev == now -> Edge.NONE
        now -> Edge.ON
        else -> Edge.OFF
    }

    /** Skal T'OS holde seg unna (ikke dekke over / ikke omdirigere)? Mens bilen rygger, og litt etterpå. */
    fun stayOut(reversing: Boolean, startedAt: Long, endedAt: Long, now: Long): Boolean =
        if (reversing) now - startedAt < REVERSE_MAX_MS else endedAt != 0L && now - endedAt < REVERSE_GRACE_MS

    /**
     * Nattdemping via stereoen: hva skal sendes nå? [dimmed] = T'OS har allerede dempet. Gir (nivå å sende eller null,
     * ny dimmed). Dag igjen → dagnivået tilbake.
     */
    fun nightStep(want: Boolean, dimmed: Boolean, dayLevel: Int, nightLevel: Int): Pair<Int?, Boolean> = when {
        want && !dimmed -> nightLevel.coerceIn(MIN_LEVEL, 100) to true
        !want && dimmed -> dayLevel.coerceIn(MIN_LEVEL, 100) to false
        else -> null to dimmed
    }

    /** Svarte stereoen på en av/på-endring? (Ny verdi = ønsket.) */
    fun answered(wanted: Boolean, reported: Boolean?): Boolean = reported == wanted
}

object FytMain {
    private val h = Handler(Looper.getMainLooper())
    @Volatile private var started = false
    /** Appens Context (fra Fyt – holdes ikke her). */
    private val app: Context? get() = Fyt.appContext

    /** Lysene (ILL-ledningen) er på ifølge stereoen. null = ikke meldt (eller glemt ved ACC av). */
    var lights by mutableStateOf<Boolean?>(null); private set
    /** Bilen rygger (ryggebildet vises av stereoen). */
    var reversing by mutableStateOf(false); private set
    /** Tenningen (ACC) er på. null = ikke meldt. */
    var acc by mutableStateOf<Boolean?>(null); private set
    /** Håndbrekket er satt (null = ikke meldt). UVERIFISERT – bare vist (til en mulig videolås senere; ingen lås nå). */
    var handbrake by mutableStateOf<Boolean?>(null); private set
    /** Svart skjerm (stereoen er på, skjermen mørk). */
    var blackScreen by mutableStateOf(false); private set
    /** Lysstyrken stereoen melder (0–100), −1 = ikke meldt. */
    var brightLevel by mutableIntStateOf(-1); private set
    var brightDay by mutableIntStateOf(-1); private set
    var brightNight by mutableIntStateOf(-1); private set
    /** Tastelyd / navigasjonsmiks slik stereoen har meldt (null = ikke meldt eller ukjent format). */
    var beep by mutableStateOf<Boolean?>(null); private set
    var naviMix by mutableStateOf<Boolean?>(null); private set

    @Volatile private var reverseAt = 0L
    @Volatile private var reverseEndAt = 0L
    /** Stereoen har svart på en lysstyrke T'OS sendte (U_BRIGHT_LEVEL kom tilbake med samme tall). */
    var brightConfirmed by mutableStateOf(false); private set
    private var lastSentLevel = -1
    private var lastSentAt = 0L
    private var pendingLevel = -1

    /** Liten logg (til kartleggingen): rygging, ACC, lys. */
    private val events = ArrayDeque<String>()

    /**
     * T'OS holder seg unna: ikke legg noe over skjermen og ikke bytt ut apper (rygging + 2 s etterpå). Utløper av seg
     * selv etter 10 min (står «rygger» fast), og gjelder ikke når FYT-tjenesten er borte / FYT er slått av.
     */
    val stayOut: Boolean get() = FytMainLogic.stayOut(reversing && Fyt.connected, reverseAt, reverseEndAt, SystemClock.elapsedRealtime())

    /**
     * Stereoen melder tenningen – da startes «når bilen starter» av ACC, ikke av at skjermen kom på. Bare mens T'OS er
     * koblet til (med FYT slått av / frakoblet kommer det ingen ACC-meldinger, og da må skjermen-på-reserven gjelde).
     */
    val accKnown get() = acc != null && Fyt.connected

    /** Lysstyrken styres via stereoen (FYT tilkoblet og valget på). */
    val brightReady get() = Fyt.connected && Prefs.bool("fyt_bright", true)

    /** Natt følger lysene (valg i Innstillinger → Skjerm, på som standard). */
    val byLights get() = Prefs.bool("night_lights", false)

    /** Natt etter lysene, eller null (lysene ikke kjent / valget av) – da avgjør sola. Brukes av [Theme.update]. */
    fun lightsNight(): Boolean? = if (byLights) lights else null

    fun start(c: Context) {
        val a = c.applicationContext
        if (started) return
        started = true
        if (!Fyt.present(a)) return
        // Reserve for tenningen (tarmac: com.syu.ms sender «com.syu.ms» med cmd.acc 1/0 til alle)
        runCatching {
            ContextCompat.registerReceiver(a, object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, i: Intent) {
                    if (!i.hasExtra("cmd.acc")) return
                    val v = i.getIntExtra("cmd.acc", -1)
                    if (v == 1) runCatching { Fyt.onWake() }
                    if (v == 0 || v == 1) onAcc(v == 1, "kringkasting")
                }
            }, IntentFilter(Fyt.PKG), ContextCompat.RECEIVER_EXPORTED)
        }
    }

    private fun note(s: String) {
        val line = SurveyText.stamp(SystemClock.elapsedRealtime()) + "  " + s
        synchronized(events) { events.addLast(line); while (events.size > 40) events.removeFirst() }
        SurveyRecorder.note("FYT  $s")
    }

    /** Hovedmodulen (0) sendte noe (Fyt.apply, hovedtråden). */
    internal fun onMain(u: Int, ints: IntArray?) {
        when (u) {
            FytMainLogic.U_LAMPLET -> FytMainLogic.flag(ints)?.let { on ->
                val prev = lights
                lights = on
                if (prev != on) { note("lys ${if (on) "på" else "av"}"); if (byLights) refreshThemeSoon() }
                Prefs.setQuiet("fyt_lights_seen", true)
            }
            FytMainLogic.U_BACKCAR -> FytMainLogic.flag(ints)?.let { on -> onReverse(on) }
            FytMainLogic.U_ACC_ON -> FytMainLogic.flag(ints)?.let { on -> onAcc(on, "hovedmodul") }
            FytMainLogic.U_HANDBRAKE -> FytMainLogic.flag(ints)?.let { handbrake = it }
            FytMainLogic.U_BLACK_SCREEN -> FytMainLogic.flag(ints)?.let { blackScreen = it }
            FytMainLogic.U_BRIGHT_LEVEL -> FytMainLogic.level(ints)?.let { lv ->
                brightLevel = lv
                if (lv == lastSentLevel && !brightConfirmed) { brightConfirmed = true; note("lysstyrke bekreftet av stereoen ($lv)") }
            }
            FytMainLogic.U_BRIGHT_DAY -> FytMainLogic.level(ints)?.let { brightDay = it }
            FytMainLogic.U_BRIGHT_NIGHT -> FytMainLogic.level(ints)?.let { brightNight = it }
        }
    }

    /** Lydmodulen (4): tastelyd og navigasjonsmiks (resten håndteres av FytSound). */
    internal fun onSound(u: Int, ints: IntArray?) {
        when (u) {
            FytMainLogic.S_U_BEEP -> beep = FytMainLogic.binary(ints)
            FytMainLogic.S_U_NAVI_MIX -> naviMix = FytMainLogic.binary(ints)
        }
    }

    private fun onReverse(on: Boolean) {
        if (on == reversing) return
        val now = SystemClock.elapsedRealtime()
        reversing = on
        if (on) {
            reverseAt = now
            note("rygger – T'OS holder seg unna (stereoen viser kameraet)")
            // Ingenting fra T'OS over ryggebildet
            runCatching { FytVolume.hide() }
            runCatching { ShadePanel.hide() }
            runCatching { NaviPopup.hide() }
            runCatching { TosOverlay.hideAll() }
        } else {
            reverseEndAt = now
            note("rygging ferdig")
        }
    }

    /* ---------- Vakthunder (FytSafety) ---------- */

    /** Ved oppstart av T'OS (TosApp): lagret lysstyrke under 5 (nesten svart skjerm) rettes til 50 – og sendes når stereoen svarer. */
    @Volatile private var restoreBright = false
    fun sanitizeOnStart() {
        for (k in listOf("fyt_bright_last", "fyt_bright_day", "fyt_bright_night")) {
            FytSafetyLogic.safeLevel(Prefs.int(k, -1))?.let { Prefs.setQuiet(k, it); restoreBright = true }
        }
    }

    /** Fyt har koblet til com.syu.ms (hovedtråden). */
    internal fun onConnected() {
        if (restoreBright && brightReady) {
            restoreBright = false
            sendLevel(FytSafetyLogic.SAFE_LEVEL); note("lysstyrke var under ${FytMainLogic.MIN_LEVEL} – satt til ${FytSafetyLogic.SAFE_LEVEL}")
        }
        // Lyden ble skrudd av av T'OS (pause på radioen) og T'OS startet på nytt før den ble skrudd på igjen: skru på
        releaseTosMute("T'OS startet")
        runCatching { Power.refresh() }         // tenningen er kjent igjen (hvis stereoen har meldt den)
    }

    /** Tilkoblingen til stereoen er borte: ingenting fra den gamle tilstanden skal holde T'OS passiv. */
    internal fun onDisconnected() {
        if (reversing) { reversing = false; reverseEndAt = 0L; note("rygging glemt (stereoen frakoblet)") }
        h.removeCallbacks(sendPending); pendingLevel = -1
        runCatching { Power.refresh() }         // tenningen er ikke kjent lenger: skjermen avgjør (Power.kt)
    }

    /** Lyden er av fordi T'OS skrudde den av (radioen på pause): skru den på igjen. */
    private fun releaseTosMute(why: String) {
        if (runCatching { FytRadio.releaseTosMute() }.getOrDefault(false)) note("lyd på igjen ($why) – T'OS hadde skrudd den av")
    }

    /** Da tenningen sist gikk av (SystemClock.elapsedRealtime, 0 = ikke i denne kjøringen av T'OS). */
    private var accOffAt = 0L
    private const val ACC_SAVED = "fyt_acc_last"

    private fun onAcc(on: Boolean, source: String) {
        // Ny tenning (av/på): «rygger» fra forrige tur gjelder ikke lenger
        if (acc != null && acc != on && reversing) { reversing = false; reverseEndAt = 0L }
        // Første melding etter at T'OS ble startet på nytt (FYT kan drepe appen i dvalen): sammenlign med lagret tenning
        val saved = if (acc == null) runCatching { when (Prefs.int(ACC_SAVED, -1)) { 0 -> false; 1 -> true; else -> null } }.getOrNull() else null
        val edge = PowerLogic.accEdge(acc, saved, on)
        // Lyden T'OS skrudde av: bare når kommandoen kan sendes – ellers gjør onConnected det (FytRadio glemmer merket
        // selv om kommandoen ikke kom fram, og da ble lyden stående av etter dvalen)
        if (edge == FytMainLogic.Edge.ON && Fyt.connected) releaseTosMute("tenning på")
        val now = SystemClock.elapsedRealtime()
        when (edge) {
            FytMainLogic.Edge.NONE -> { if (acc == null) note("tenning ${if (on) "på" else "av"} ($source, nåverdi)") }
            FytMainLogic.Edge.OFF -> {
                accOffAt = now
                note("tenning av ($source) – lagrer tur og parkering")
                // Før GPS-en stoppes (Power går i dvale først når skjermen er av): siste gode posisjon blir parkeringen
                runCatching { Trip.flush() }
                runCatching { Parking.saveNow(Hub.gps) }
                // Søk/innstilling på radioen skal ikke fortsette i blinde gjennom dvalen (forvalgene røres ikke)
                runCatching { FytRadio.stopForSleep() }
                // Etter ACC av er lysene av; MCU-en melder dem først når de endres (vasyl91) – la sola avgjøre til da
                lights = null
            }
            FytMainLogic.Edge.ON -> {
                // Tenningen blinket (motoren startes): samme tur – ingen ny oppstart midt i musikken
                if (PowerLogic.startupOnAccOn(accOffAt, now)) {
                    note("tenning på ($source) – oppstart")
                    app?.let { a -> runCatching { Startup.run(a) } }
                } else note("tenning blinket ($source) – samme tur")
            }
        }
        acc = on
        runCatching { Prefs.setQuiet(ACC_SAVED, if (on) 1 else 0) }   // skrives bare når den endres
        // Strømsparingen (Power.kt): GPS, OBD, versjonssjekk … av/på etter tenningen
        runCatching { Power.refresh() }
    }

    private val refreshTheme = Runnable {
        val lat = Hub.gps?.lat ?: Parking.lat.takeIf { Parking.known }
        val lon = Hub.gps?.lon ?: Parking.lon.takeIf { Parking.known }
        Theme.update(lat, lon)
        app?.let { runCatching { NightBrightness.apply(it) } }
    }

    /** Lysene har endret seg: bytt dag/natt når det har holdt seg 1,5 s (ikke for hvert blink). */
    private fun refreshThemeSoon() {
        h.removeCallbacks(refreshTheme)
        h.postDelayed(refreshTheme, 1500)
    }

    /** Valget «Natt når lysene er på» ble endret. */
    fun onLightsSetting() { h.removeCallbacks(refreshTheme); h.post(refreshTheme) }

    /**
     * FYT-funksjonene slås av (hovedbryteren): hadde T'OS dempet baklyset for natten, settes dagnivået tilbake først
     * ([force]: sendes selv om bryteren alt er av) – ellers ble skjermen stående mørk til FYT ble slått på igjen.
     */
    internal fun releaseNightDim(force: Boolean) {
        if (!Prefs.bool("fyt_night_dimmed")) return
        val day = Prefs.int("fyt_bright_day", 80).coerceIn(FytMainLogic.MIN_LEVEL, 100)
        Prefs.setQuiet("fyt_night_dimmed", false)
        Fyt.moduleCmd(Fyt.M_MAIN, FytMainLogic.C_BRIGHT_LEVEL, intArrayOf(day), force = force)
        note("lysstyrke tilbake til dagnivå $day (FYT slås av)")
    }

    /* ---------- Lysstyrke (baklyset via MCU-en) ---------- */

    /** Nivået glideren skal vise (0–1). */
    fun fraction(): Float = (if (brightLevel >= 0) brightLevel else Prefs.int("fyt_bright_last", 70)) / 100f

    private val sendPending = Runnable { if (pendingLevel >= 0) { val l = pendingLevel; pendingLevel = -1; sendNow(l) } }

    private fun sendNow(level: Int) {
        // Aldri under 5 (kan slå baklyset helt av på noen FYT – da ser sjåføren ingenting)
        val l = level.coerceIn(FytMainLogic.MIN_LEVEL, 100)
        lastSentLevel = l; lastSentAt = SystemClock.elapsedRealtime()
        Fyt.moduleCmd(Fyt.M_MAIN, FytMainLogic.C_BRIGHT_LEVEL, intArrayOf(l))
    }

    /** Send et nivå (høyst hvert 150. ms mens glideren dras; siste verdi kommer alltid fram). */
    fun sendLevel(level: Int) {
        val l = level.coerceIn(FytMainLogic.MIN_LEVEL, 100)
        val wait = 150 - (SystemClock.elapsedRealtime() - lastSentAt)
        h.removeCallbacks(sendPending)
        if (wait <= 0) { pendingLevel = -1; sendNow(l) } else { pendingLevel = l; h.postDelayed(sendPending, wait) }
    }

    /** Glideren (hurtigpanelet / Innstillinger → Skjerm) ble flyttet. Om natten med demping på: det er nattnivået. */
    fun setFromSlider(fraction: Float) {
        val l = FytMainLogic.levelFor(fraction)
        Prefs.setQuiet("fyt_bright_last", l)
        if (Prefs.bool("fyt_night_dimmed")) Prefs.setQuiet("fyt_bright_night", l) else Prefs.setQuiet("fyt_bright_day", l)
        sendLevel(l)
    }

    /** NightBrightness på FYT: demp om natten / tilbake om dagen via stereoen. */
    internal fun applyNight(want: Boolean) {
        val dimmed = Prefs.bool("fyt_night_dimmed")
        val day = Prefs.int("fyt_bright_day", if (brightLevel >= 0) brightLevel else 80)
        if (want && !dimmed && brightLevel >= 0) Prefs.setQuiet("fyt_bright_day", brightLevel)
        val (send, nowDimmed) = FytMainLogic.nightStep(want, dimmed, day, Prefs.int("fyt_bright_night", 25))
        if (send != null) { sendLevel(send); Prefs.set("fyt_night_dimmed", nowDimmed); note("lysstyrke ${if (nowDimmed) "natt" else "dag"}: $send") }
    }

    /* ---------- Tastelyd og navigasjonsmiks (lydmodulen) ---------- */

    /**
     * Slå av/på og sjekk at stereoen svarer innen 2,5 s ([done] får true/false). Formatet ([0]/[1]) er ikke bekreftet
     * noe sted – derfor sjekken.
     */
    fun setBeep(on: Boolean, done: (Boolean) -> Unit) = setSound(FytMainLogic.S_C_BEEP, on, { beep }, done)
    fun setNaviMix(on: Boolean, done: (Boolean) -> Unit) = setSound(FytMainLogic.S_C_NAVI_MIX, on, { naviMix }, done)

    private fun setSound(code: Int, on: Boolean, read: () -> Boolean?, done: (Boolean) -> Unit) {
        if (!Fyt.soundCmd(code, intArrayOf(if (on) 1 else 0))) { done(false); return }
        h.postDelayed({ done(FytMainLogic.answered(on, read())) }, 2500)
    }

    /* ---------- Kartleggingen ---------- */

    fun report(): String = buildString {
        appendLine("Lys (ILL): ${lights ?: "ikke meldt"}  natt etter lysene=${byLights}  rygger=$reversing  tenning=${acc ?: "ikke meldt"}  " +
            "håndbrekk=${handbrake ?: "ikke meldt"}  svart skjerm=$blackScreen")
        appendLine("Lysstyrke (stereoen): nå=$brightLevel dag=$brightDay natt=$brightNight  T'OS sendte sist=$lastSentLevel  " +
            "bekreftet=$brightConfirmed  styres av T'OS=${brightReady}  nattdempet=${Prefs.bool("fyt_night_dimmed")}")
        appendLine("Tastelyd=${beep ?: "ukjent"}  navigasjonsmiks=${naviMix ?: "ukjent"}")
        val lines = synchronized(events) { events.toList() }
        if (lines.isNotEmpty()) { appendLine("Hendelser:"); lines.forEach { appendLine("  $it") } }
    }
}
