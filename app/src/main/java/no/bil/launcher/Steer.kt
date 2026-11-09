package no.bil.launcher

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ======================= Rattknappene ======================= */

/**
 * Rattknappene på FYT-stereoen kommer aldri som Android-tastekoder: rattet → MCU → com.syu.ms, som selv bestemmer hva
 * som skjer (volum, neste/forrige i sin egen spiller eller kilde, svar, stemme …). Brukeren meldte «rattknappene gjør
 * ingenting». Det T'OS gjør:
 *  - lytter på det com.syu.ms sier om knappene (Fyt.kt: hoved u=37 spillerkommando, u=133 knapp, rattmodulen 10 rått)
 *    og viser det på Knapper-siden («Siste rattknapp»), så brukeren ser om trykkene i det hele tatt kommer fram,
 *  - sender neste/forrige/spill-pause videre til musikkappen som spiller (Spotify) når FYT sendte dem til sin egen
 *    spiller (kilde 8 musikkspiller / 2 BT), men lar radioen og BT-musikken være (FYT styrer dem selv),
 *  - gjør Android-appen til FYT sin lydkilde (C_APP_ID 10) når en musikkapp begynner å spille, så FYT ikke lenger
 *    sender rattknappene til sin egen musikkspiller (det var trolig grunnen: etter MUSIC-knappen står kilden på 8),
 *  - lar Ratt 1–4 læres fra FYT-meldingene,
 *  - åpner stereoens eget rattknapp-oppsett («Control», com.syu.steer), der knappene må læres én gang.
 * UVERIFISERT på stereoen: hvilke meldinger rattknappene gir (opptaket 8. nov ga ingen – men da lyttet T'OS ikke på
 * rattmodulen eller u=37/133). Kilder: se SteerLogic.
 */
object Steer {
    private val main = Handler(Looper.getMainLooper())

    /** Siste rattknapp T'OS så (tekst til Knapper-siden), og når (veggklokke, ms). */
    var last by mutableStateOf<String?>(null)
        private set
    var lastAt by mutableLongStateOf(0L)
        private set
    /** Grønn prikk i Rattknapper-kortet et øyeblikk etter hvert trykk. */
    var lit by mutableStateOf(false)
        private set
    @Volatile var count = 0
        private set
    private var seq = 0
    private val dedup = SteerLogic.Dedup()
    private val onePress = SteerLogic.OnePress()
    private var claimedAt = 0L

    /** Har stereoen noen gang (også før denne oppstarten) sendt noe fra rattknappene? */
    val everSeen: Boolean get() = last != null || Prefs.str("steer_seen").isNotEmpty() ||
        FytSteer.learned.values.any { SteerAdc.isLearnedValue(it) }

    private fun record(text: String) {
        last = text; lastAt = System.currentTimeMillis(); count++
        Prefs.setQuiet("steer_seen", SimpleDateFormat("d. MMM HH:mm", Locale.forLanguageTag("nb-NO")).format(Date()) + " – " + text)
        lit = true
        val mine = ++seq
        main.postDelayed({ if (seq == mine) lit = false }, KeyPing.LIT_MS)
    }

    /** En rattknapp-melding fra FYT-tjenesten (på hovedtråden). */
    internal fun onFyt(e: SteerLogic.Event) {
        val now = SystemClock.elapsedRealtime()
        // u=37 med kilden «Android-app»: stereoen gir selv appen trykket (Route.STEREO). Kom u=133 for det samme trykket
        // først, skal T'OS ikke også sende det (rekkefølgen varierer) – se SteerLogic.OnePress.
        val cmd0 = e.cmd
        if (cmd0 != null && e.code == SteerLogic.U_PLAYER_CMD && Fyt.appId == 10) onePress.handledElsewhere(cmd0, now)
        if (!dedup.allow(cmd0?.name ?: e.id, now)) return
        record(SteerLogic.label(e))
        // Test på Rattknapper-siden: neste/forrige lyser
        when (e.cmd) { SteerLogic.Cmd.NEXT -> FytSteer.flash(6); SteerLogic.Cmd.PREV -> FytSteer.flash(5); else -> Unit }
        if (SurveyRecorder.active) return                      // opptaket: bare skriv ned, la stereoen gjøre som før
        val c = Fyt.appContext ?: return
        // I stereoens eget rattknapp-oppsett (eller bilens oppsett): T'OS gjør ingenting – knappene læres der
        if (KeyService.frontPackage in SteerLogic.SETUP_PKGS) return
        if (Learn.target != null && Learn.offerFyt(c, e.id, SteerLogic.label(e))) return
        val inCall = Buttons.inCall(c)
        val k = Buttons.keyForFyt(c, e.id)
        if (k != null) {
            KeyPing.hit(k, SteerLogic.label(e))
            val a = Buttons.action(c, k)
            if (a != Action.NONE && !inCall) Buttons.perform(c, a)
            return
        }
        if (!Prefs.bool("fyt_steer", true)) return
        when (SteerLogic.route(e, Fyt.appId, inCall, NowPlaying.pkg, fytCall = Fyt.btReady && Fyt.inCall)) {
            // Vent litt: kommer det samme trykket også som u=37 (kilde 10) eller som Android-medietast, gjør stereoen/
            // Android det allerede – da gjør T'OS ingenting (ellers to sanger fram)
            SteerLogic.Route.APP -> { val cmd = cmd0 ?: return; main.postDelayed({ if (onePress.mayAct(cmd, now)) toApp(c, cmd) }, SteerLogic.HOLD_MS) }
            SteerLogic.Route.PHONE -> when (e.cmd) {
                SteerLogic.Cmd.ANSWER -> if (Call.canAnswer) Call.answer()
                SteerLogic.Cmd.HANGUP -> if (Fyt.ringing || Call.name != null) Call.decline() else if (Fyt.btReady && Fyt.inCall) Fyt.hangUp()
                else -> Unit
            }
            SteerLogic.Route.STEREO, SteerLogic.Route.NONE -> Unit
        }
    }

    private fun toApp(c: android.content.Context, cmd: SteerLogic.Cmd) {
        when (cmd) {
            SteerLogic.Cmd.NEXT -> Media.next(c)
            SteerLogic.Cmd.PREV -> Media.previous(c)
            SteerLogic.Cmd.PLAY_PAUSE -> Media.playPause(c)
            SteerLogic.Cmd.PLAY -> if (!NowPlaying.playing) Media.play(c)
            SteerLogic.Cmd.PAUSE -> if (NowPlaying.playing) Media.playPause(c)
            else -> Unit
        }
    }

    /**
     * En Android-tastekode som rattknapper ofte sender (neste, forrige, svar …) – til «Siste rattknapp». Knappestyringen
     * lar tasten gå videre til Android (musikkappen får den), så et FYT-trykk for det samme skal ikke også sendes av T'OS.
     */
    fun noteKey(code: Int) {
        val cmd = SteerLogic.androidCmd(code) ?: return
        onePress.handledElsewhere(cmd, SystemClock.elapsedRealtime())
        main.post { record("${cmd.label} (tastekode $code)") }
    }

    /**
     * En Android-musikkapp ([pkg]) skal spille: gjør den til FYT sin lydkilde, så stereoen ikke sender rattknappene til
     * sin egen musikkspiller/BT-side. Høyst hvert 3. sekund. [explicit] = brukeren valgte selv musikken (MUSIC-handlingen,
     * kildevalget): da også fra FYT-radioen, som ellers ville spilt videre under appen (og fått rattknappene).
     */
    fun claimSource(pkg: String?, explicit: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!Fyt.connected || now - claimedAt < 3000) return
        if (!SteerLogic.shouldClaim(Prefs.bool("fyt_steer", true), Fyt.appId, Fyt.inCall, pkg, explicit)) return
        claimedAt = now
        Fyt.androidSource()
    }

    fun setupInstalled(c: Context): Boolean = AppRepository.isInstalled(c, SteerLogic.STEER_PKG)

    /** Åpne stereoens eget rattknapp-oppsett («Control»). T'OS sier fra til knappestyringen at det er T'OS som åpnet det. */
    fun openSetup(c: Context): Boolean {
        if (Learn.target != null) Learn.cancel()
        AppRepository.markLaunched(SteerLogic.STEER_PKG)
        val i = Intent().setClassName(SteerLogic.STEER_PKG, SteerLogic.STEER_CLS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { c.startActivity(i) }.isSuccess) return true
        val l = c.packageManager.getLaunchIntentForPackage(SteerLogic.STEER_PKG)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (l != null && runCatching { c.startActivity(l) }.isSuccess) return true
        Notify.show("Fant ikke stereoens rattknapp-oppsett")
        return false
    }

    /** Til kartleggingen. */
    fun summary(): String {
        val before = Prefs.str("steer_seen")
        return when {
            last != null -> "$count melding(er) siden start, siste: $last"
            before.isNotEmpty() -> "ingen siden start; sist sett $before"
            else -> "aldri sett noe fra rattknappene"
        } + "  (ratt → musikkapp: ${Prefs.bool("fyt_steer", true)})"
    }
}

/**
 * Ren logikk for rattknappene. Testes i SteerLogicTest. Kodene:
 *  - hoved u=133, ints[0] = knappen: github.com/andreknieriem/open-headunit – app/src/main/java/com/andrerinas/openheadunit/
 *    connection/carkey/fyt/CarFYTReceiver.kt («based of decompilation of com.syu.steer_HD.apk», testet på UIS7870, skal
 *    gjelde UIS7862). Der sendes verdien videre som Android-tastekode, og 576 = stemmeassistent. De tar «knappefokus» med
 *    `setprop sys.carlink.type 2` (krever root) – uten det er det usikkert om FYT sender u=133 til T'OS.
 *  - hoved u=37 U_PLAYER_CMD (PLAYER_CMD_PLAY 0, PLAYPAUSE 1, PAUSE 2, STOP 3, PREV 4, NEXT 5, FF 6, FB 7),
 *    u=42 U_VA_CMD, u=60 U_MCU_DIEECTION_KEY: github.com/vasyl91/FYT-Launcher-Mod – com/syu/ipc/data/FinalMain.java.
 *  - rattmodulen (10, «com.syu.ms.steer»): læringen av rattknappene i stereoen selv – se SteerAdc og FytSteer.
 *  - canbus (7): koder 0–50 og 1000–1036 logges (som github.com/AxesOfEvil/FYTCanbusMonitor), men regnes ikke som knapper.
 */
internal object SteerLogic {
    const val STEER_PKG = "com.syu.steer"
    const val STEER_CLS = "com.syu.steer.Launch"
    const val CANBUS_PKG = "com.syu.canbus"
    /** Stereoens egne oppsett-apper for ratt og bil: T'OS bytter dem aldri ut og gjør ingenting med knappene der. */
    val SETUP_PKGS = setOf(STEER_PKG, CANBUS_PKG)

    const val M_MAIN = 0
    const val M_STEER = 10
    const val U_PLAYER_CMD = 37
    const val U_VA_CMD = 42
    const val U_DIRECTION_KEY = 60
    const val U_KEY = 133
    const val KEY_ASSISTANT = 576
    /** Koder i hovedmodulen som er hendelser (trykk), ikke tilstand. */
    val MAIN_EVENT_CODES = listOf(U_PLAYER_CMD, U_VA_CMD, U_DIRECTION_KEY, U_KEY)

    enum class Cmd(val label: String) {
        NEXT("Neste"), PREV("Forrige"), PLAY_PAUSE("Spill/pause"), PLAY("Spill"), PAUSE("Pause"),
        VOICE("Stemme"), ANSWER("Svar"), HANGUP("Legg på"), VOL_UP("Volum opp"), VOL_DOWN("Volum ned"), MUTE("Lyd av"),
    }

    /** En rattknapp-melding. [id] lagres når en Ratt-rad læres («modul/kode/verdi»). */
    data class Event(val module: Int, val code: Int, val value: Int, val cmd: Cmd?) {
        val id get() = "$module/$code/$value"
    }

    /** Er dette en hendelse (et trykk) og ikke en tilstand? Hendelser logges hver gang, også like etter hverandre. */
    fun isEventCode(m: Int, u: Int) = m == M_MAIN && u in MAIN_EVENT_CODES

    /** FYT-melding → rattknapp (null = ikke en knapp). */
    fun fromFyt(m: Int, u: Int, ints: IntArray?): Event? {
        val v = ints?.firstOrNull()
        return when {
            m == M_MAIN && u == U_KEY && v != null -> Event(m, u, v, if (v == KEY_ASSISTANT) Cmd.VOICE else androidCmd(v))
            m == M_MAIN && u == U_PLAYER_CMD && v != null -> Event(m, u, v, playerCmd(v))
            m == M_MAIN && u == U_DIRECTION_KEY && v != null -> Event(m, u, v, null)
            // Rattmodulen sender tall fra rattet (ADC) og læringstabellen – det tar FytSteer seg av, det er ikke trykk
            else -> null
        }
    }

    /** FinalMain.PLAYER_CMD_*. Spol fram/tilbake (6/7) brukes ikke. */
    fun playerCmd(v: Int): Cmd? = when (v) {
        0 -> Cmd.PLAY; 1 -> Cmd.PLAY_PAUSE; 2, 3 -> Cmd.PAUSE; 4 -> Cmd.PREV; 5 -> Cmd.NEXT
        else -> null
    }

    /** Android-tastekoder rattknapper gir på andre stereoer (og trolig i u=133). Volum er med, men styres av stereoen. */
    fun androidCmd(code: Int): Cmd? = when (code) {
        KeyEvent.KEYCODE_MEDIA_NEXT -> Cmd.NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Cmd.PREV
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> Cmd.PLAY_PAUSE
        KeyEvent.KEYCODE_MEDIA_PLAY -> Cmd.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> Cmd.PAUSE
        KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_HEADSETHOOK -> Cmd.ANSWER
        KeyEvent.KEYCODE_ENDCALL -> Cmd.HANGUP
        KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_SEARCH -> Cmd.VOICE
        KeyEvent.KEYCODE_VOLUME_UP -> Cmd.VOL_UP
        KeyEvent.KEYCODE_VOLUME_DOWN -> Cmd.VOL_DOWN
        KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE -> Cmd.MUTE
        else -> null
    }

    /** Hva T'OS gjør med en rattknapp som ikke er lært på en Ratt-rad. */
    enum class Route { APP, PHONE, STEREO, NONE }

    /**
     * [appId] = FYT sin lydkilde (hoved u=0), [nowPkg] = appen T'OS styrer musikken i (NowPlaying).
     *  - volum/demp og stemme: stereoen gjør det selv (FYT viser sitt volum, T'OS legger sin linje over),
     *  - svar/legg på: telefonen, men bare under en samtale,
     *  - neste/forrige/spill: FM/AM-radio (1, 11) og BT-musikk (3) styrer FYT selv. Spiller FYT sin egen app (com.syu.*)
     *    i T'OS, gjør FYT det selv. u=37 med kilden «Android-app» (10): FYT sender trolig selv medieknappen til appen –
     *    T'OS gjør ingenting (ellers to sanger fram). Ellers (kilde 8 musikkspiller, 2 BT-side …): til musikkappen.
     */
    fun route(e: Event, appId: Int, inCall: Boolean, nowPkg: String?, fytCall: Boolean = false): Route {
        val cmd = e.cmd ?: return Route.NONE
        return when (cmd) {
            Cmd.VOL_UP, Cmd.VOL_DOWN, Cmd.MUTE, Cmd.VOICE -> Route.STEREO
            // FYT-samtale: stereoen svarer/legger på selv når rattknappen er lært – T'OS sender ikke det samme en gang til
            // (et ekstra «legg på» kunne ha lagt på en samtale som nettopp ble besvart)
            Cmd.ANSWER, Cmd.HANGUP -> if (!inCall) Route.NONE else if (fytCall) Route.STEREO else Route.PHONE
            else -> when {
                inCall -> Route.NONE
                appId == 1 || appId == 11 || appId == 3 -> Route.STEREO
                nowPkg != null && nowPkg.startsWith("com.syu.") -> Route.STEREO
                e.code == U_PLAYER_CMD && appId == 10 -> Route.STEREO
                else -> Route.APP
            }
        }
    }

    /**
     * Skal T'OS gjøre Android-appen [pkg] til FYT sin lydkilde (C_APP_ID 10)? Bare når FYT står på sin egen spiller eller
     * BT (2 BT-telefon uten samtale, 3 BT-musikk, 8 musikkspiller, 9 videospiller), og aldri for FYT sine egne apper.
     * Radioen tas hånd om av HwRadio.yieldTo.
     */
    fun shouldClaim(enabled: Boolean, appId: Int, inCall: Boolean, pkg: String?, explicit: Boolean = false): Boolean =
        enabled && !inCall && pkg != null && !pkg.startsWith("com.syu.") && pkg != "com.android.bluetooth" &&
            (appId in setOf(2, 3, 8, 9) || (explicit && (appId == 1 || appId == 11)))

    /** Så lenge T'OS venter før det sender neste/forrige til musikkappen (se [OnePress]). */
    const val HOLD_MS = 250L

    /** Neste/forrige/spill-pause regnes som samme trykk uansett hvilken vei de kom (u=37 «spill» vs. Android «spill/pause»). */
    private fun group(c: Cmd): Cmd = when (c) { Cmd.PLAY, Cmd.PAUSE -> Cmd.PLAY_PAUSE; else -> c }

    /**
     * Ett trykk på rattet kan nå T'OS flere veier: u=133 og u=37 (rekkefølgen varierer) og/eller som Android-medietast.
     * Har stereoen/Android allerede gitt appen trykket ([handledElsewhere]) innen [windowMs] fra trykket, skal T'OS ikke
     * gjøre det ([mayAct] = false) – ellers hopper musikken to sanger.
     */
    class OnePress(private val windowMs: Long = 600) {
        private val elsewhere = HashMap<Cmd, Long>()
        fun handledElsewhere(cmd: Cmd, now: Long) { elsewhere[group(cmd)] = now }
        fun mayAct(cmd: Cmd, pressedAt: Long): Boolean {
            val t = elsewhere[group(cmd)] ?: return true
            return kotlin.math.abs(t - pressedAt) >= windowMs
        }
    }

    /** Tekst til Knapper-siden og kartleggingen. */
    fun label(e: Event): String {
        val what = e.cmd?.label
        return when {
            e.module == M_MAIN && e.code == U_KEY -> (what ?: "Knapp") + " (stereoen: knapp ${e.value})"
            e.module == M_MAIN && e.code == U_PLAYER_CMD -> (what ?: "Spillerkommando") + " (stereoen: spiller ${e.value})"
            e.module == M_MAIN && e.code == U_DIRECTION_KEY -> "Retningsknapp ${e.value}"
            else -> what ?: describeId(e.id)
        }
    }

    /** Lært rattknapp («0/133/87») → kort tekst til Ratt-raden. */
    fun describeId(id: String): String {
        val p = id.split('/').mapNotNull { it.toIntOrNull() }
        if (p.size != 3) return id
        return when {
            p[0] == M_MAIN && p[1] == U_KEY -> "Ratt-knapp ${p[2]}"
            p[0] == M_MAIN && p[1] == U_PLAYER_CMD -> "Ratt: ${playerCmd(p[2])?.label ?: "spiller ${p[2]}"}"
            p[0] == M_MAIN && p[1] == U_DIRECTION_KEY -> "Retning ${p[2]}"
            else -> id
        }
    }

    /** Til FYT-loggen: forklaring bak meldingen. */
    fun note(m: Int, u: Int, ints: IntArray?): String? {
        val v = ints?.firstOrNull()
        return when {
            m == M_MAIN && u == U_PLAYER_CMD -> "→ spillerkommando (rattknapp?)" + (v?.let { playerCmd(it) }?.let { ": ${it.label}" } ?: "")
            m == M_MAIN && u == U_KEY -> "→ knapp (rattknapp?)" + (v?.let { if (it == KEY_ASSISTANT) Cmd.VOICE else androidCmd(it) }?.let { ": ${it.label}" } ?: "")
            m == M_MAIN && u == U_VA_CMD -> "→ stemmekommando"
            m == M_MAIN && u == U_DIRECTION_KEY -> "→ retningsknapp"
            m == M_STEER -> SteerAdc.note(u, ints)
            else -> null
        }
    }

    /** Ett trykk kan gi flere meldinger (f.eks. både u=37 og u=133, eller trykk + slipp): samme innen [windowMs] teller én gang. */
    class Dedup(private val windowMs: Long = 350) {
        private val seen = HashMap<String, Long>()
        fun allow(key: String, now: Long): Boolean {
            val last = seen[key]
            if (last != null && now - last in 0 until windowMs) return false
            seen[key] = now
            if (seen.size > 64) seen.entries.removeAll { now - it.value > windowMs }
            return true
        }
    }
}

/* ======================= Læring av rattknappene i stereoen (rattmodulen 10) ======================= */

/**
 * Stereoens egen rattknapp-læring («Control», com.syu.steer) gjort fra T'OS. Brukeren kjørte Control 8. nov: 6 tall øverst
 * som endret seg når han trykket en rattknapp, og alle funksjonene «NULL» – rattknappene var aldri lært i stereoen, så
 * MCU-en gjorde ingenting med dem. Når en knapp er lært her, gjør stereoen den selv (volum, demping, neste/forrige …),
 * også når T'OS ikke kjører.
 *
 * Protokollen (rattmodulen 10 i com.syu.ms, samme Binder som resten av Fyt.kt): github.com/cssbruno/cabin
 * (Unlicense/offentlig eie) – app/src/main/kotlin/com/cabin/platform/SyuSteeringProtocol.kt, SyuSteeringController.kt,
 * SyuSteeringWire.kt og documents/STEERING-MAPPINGS.md, «verified against Joying com.syu.ms 2.23.0711.1001 and its
 * matching steering client» (Joying er også en FYT-stereo). Kommandoer (cmd): 2 [1]/[0] = start/stopp læring (ADC),
 * 1 [plass] = gi tallet som vises nå til funksjonen på plassen, 4 = lagre, 3 = slett alt. Tilbakemeldinger (update):
 * 1 [plass, tall] = lært, 2 [tall] = tallet fra rattet nå (50 = ingen knapp), 3 [kanal 0–5, tall], 4 [0/1] = lærer,
 * 5 [0/1] = MCU-læring finnes, 6 [tast, funksjon]. Plassene: 0 av/på, 1 MODE, 2 demp, 3 volum +, 4 volum −,
 * 5 forrige, 6 neste, 7 navigasjon, 8 telefon, 9 legg på, 10 spill/pause, 11 spol fram, 12 spol tilbake.
 * UVERIFISERT på denne stereoen (UIS7862, Control viser flere funksjoner enn de 13): T'OS viser ✓ først når stereoen
 * selv melder «lært» (1 [plass, tall]), og lagringen bekreftes ikke av stereoen – knappene må prøves etterpå.
 */
object FytSteer {
    private val main = Handler(Looper.getMainLooper())

    /** Rattmodulen har svart (en gyldig tilbakemelding). */
    var seen by mutableStateOf(false); private set
    var detecting by mutableStateOf<Boolean?>(null); private set
    /** Tallet fra rattet nå (u=2) og når det kom (elapsedRealtime). */
    var adc by mutableStateOf<Int?>(null); private set
    @Volatile var adcAt = 0L; private set
    /** De seks tallene (u=3, kanal 0–5) – de Control viser øverst. */
    var channels by mutableStateOf(List<Int?>(6) { null }); private set
    /** Lært i stereoen: plass → tall. */
    var learned by mutableStateOf<Map<Int, Int>>(emptyMap()); private set
    /** Rattet sender tall også utenfor læringen (da kan T'OS se hvilken lært knapp som trykkes). */
    var liveOutside by mutableStateOf(false); private set

    /** Siden «Rattknapper» er åpen (Test: riktig flis lyser). */
    @Volatile var pageOpen = false
    /** Flisa (plassen) som lyser nå, -1 = ingen. */
    var flashSlot by mutableIntStateOf(-1); private set
    private var flashSeq = 0

    // Læringsøkta T'OS har startet
    var sessionActive by mutableStateOf(false); private set
    /** Plassen som venter på en rattknapp, og tallet som ble fanget. */
    var waitingSlot by mutableIntStateOf(-1); private set
    var captured by mutableStateOf<Int?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    /** Stereoen svarte ikke på læringen (da vises reserven: stereoens eget oppsett). */
    var noAnswer by mutableStateOf(false); private set
    private var assignedInSession = 0
    private var waitingSince = 0L
    private var assignSentAt = 0L
    private val sessionTimeout = Runnable { endSession("Læringen stoppet etter ett minutt uten trykk.") }
    private val tick = object : Runnable {
        override fun run() { check(); if (sessionActive) main.postDelayed(this, 250) }
    }

    /** En tilbakemelding fra rattmodulen (hovedtråden, fra Fyt.apply). */
    fun onFeedback(field: Int, ints: IntArray?) {
        val v = ints?.toList() ?: return
        if (!SteerAdc.valid(field, v)) return
        seen = true
        when (field) {
            1 -> {
                learned = SteerAdc.withLearned(learned, v[0], v[1])
                if (v[0] == waitingSlot && captured != null && SteerAdc.isLearnedValue(v[1])) learnedNow(v[0], v[1])
            }
            2 -> {
                adc = v[0]; adcAt = SystemClock.elapsedRealtime()
                if (detecting != true && v[0] != SteerAdc.NO_KEY) liveOutside = true
                if (detecting != true) testPress(v[0])
            }
            3 -> {
                val ch = channels.toMutableList(); ch[v[0]] = v[1]; channels = ch
            }
            4 -> detecting = v[0] == 1
        }
    }

    /** Test: en lært knapp trykkes (tallet passer) – flisa lyser, og grønn prikk. */
    private fun testPress(value: Int) {
        if (!pageOpen) return
        SteerAdc.slotFor(value, learned)?.let { flash(it) }
    }

    /** Lys opp flisa for [slot] et øyeblikk (også fra virkningen: volumet endret seg, neste sang …). */
    fun flash(slot: Int) {
        if (!pageOpen || sessionActive) return
        main.post {
            flashSlot = slot
            val mine = ++flashSeq
            main.postDelayed({ if (flashSeq == mine) flashSlot = -1 }, KeyPing.LIT_MS)
        }
    }

    /** Brukeren trykket flisa for [slot]: start læringen (om nødvendig) og vent på rattknappen. */
    fun learn(slot: Int) {
        if (!Fyt.connected) { message = "Får ikke kontakt med stereoen."; return }
        message = null; captured = null; noAnswer = false
        if (!sessionActive) {
            sessionActive = true; assignedInSession = 0
            Fyt.steerCmd(2, intArrayOf(1))
            main.post(tick)
        }
        waitingSlot = slot
        waitingSince = SystemClock.elapsedRealtime()
        assignSentAt = 0L
        main.removeCallbacks(sessionTimeout); main.postDelayed(sessionTimeout, 60_000)
    }

    /** Avbryt ventingen på én flis (læringen fortsetter). */
    fun cancelWait() { waitingSlot = -1; captured = null; message = null }

    private fun check() {
        val slot = waitingSlot
        if (!sessionActive || slot < 0) return
        val now = SystemClock.elapsedRealtime()
        when (val step = SteerAdc.step(now, waitingSince, adc, adcAt, captured, assignSentAt, detecting)) {
            is SteerAdc.Step.Capture -> { captured = step.value }
            SteerAdc.Step.Send -> {
                assignSentAt = now
                Fyt.steerCmd(1, intArrayOf(slot))
            }
            SteerAdc.Step.NoSignal -> {
                noAnswer = !seen || detecting != true
                message = if (noAnswer) "Stereoen svarte ikke på læringen fra T'OS. Bruk stereoens eget oppsett (knappen under)."
                          else "Fikk ikke noe tall fra rattet. Hold knappen inne litt lenger, og prøv igjen."
                waitingSlot = -1
            }
            SteerAdc.Step.NoConfirm -> {
                message = "Stereoen bekreftet ikke. Prøv igjen, eller bruk stereoens eget oppsett."
                noAnswer = true
                waitingSlot = -1; captured = null
            }
            SteerAdc.Step.Wait -> Unit
        }
    }

    private fun learnedNow(slot: Int, value: Int) {
        assignedInSession++
        waitingSlot = -1; captured = null
        message = "${SteerAdc.labelFor(slot)} er lært (tall $value). Trykk neste flis, eller «Lagre i stereoen»."
        flashSeq++; flashSlot = slot
        val mine = flashSeq
        main.postDelayed({ if (flashSeq == mine) flashSlot = -1 }, KeyPing.LIT_MS)
    }

    /** Lagre det som er lært og stopp læringen. */
    fun save() {
        if (!sessionActive) return
        Fyt.steerCmd(4, IntArray(0))
        Fyt.steerCmd(2, intArrayOf(0))
        stopLocal()
        message = "Lagret i stereoen. Prøv rattknappene nå – riktig flis lyser når du trykker."
    }

    /** Siden lukkes eller læringen står for lenge: lagre det som er lært (ellers bare stopp). */
    fun endSession(msg: String? = null) {
        if (!sessionActive) return
        if (assignedInSession > 0) { Fyt.steerCmd(4, IntArray(0)); Fyt.steerCmd(2, intArrayOf(0)) }
        else Fyt.steerCmd(2, intArrayOf(0))
        stopLocal()
        message = msg ?: if (assignedInSession > 0) "Lagret i stereoen." else null
    }

    private fun stopLocal() {
        sessionActive = false; waitingSlot = -1; captured = null
        main.removeCallbacks(sessionTimeout); main.removeCallbacks(tick)
    }

    /** Slett alt stereoen har lært om rattknappene (bekreftes på siden først). */
    fun clearAll() {
        if (sessionActive) endSession()
        Fyt.steerCmd(3, IntArray(0))
        learned = emptyMap()
        message = "Alle rattknappene er slettet i stereoen."
    }

    /** Til kartleggingen. */
    fun summary(): String =
        "svarer=$seen lærer=$detecting tall=${adc ?: "–"} kanaler=${channels.joinToString(",") { it?.toString() ?: "–" }} " +
            "tall utenfor læring=$liveOutside lært=" +
            (learned.filterValues { SteerAdc.isLearnedValue(it) }.entries.joinToString(", ") { "${SteerAdc.labelFor(it.key)}=${it.value}" }.ifEmpty { "ingen" })
}

/** Ren logikk for læringen i stereoen. Testes i SteerLogicTest. Se FytSteer for kildene. */
internal object SteerAdc {
    /** u=2-tallet når ingen knapp er trykket (cabin: «sentinel 50 is not learnable»). */
    const val NO_KEY = 50
    /** Et fanget tall må være nyere enn så mye (cabin: «ADC readings expire after 3 seconds»). */
    const val FRESH_MS = 3000L
    /** Tallet må ha stått så lenge før det sendes (knappen holdes inne). */
    const val SETTLE_MS = 400L
    const val SIGNAL_TIMEOUT_MS = 15_000L
    const val CONFIRM_TIMEOUT_MS = 10_000L

    /** En flis på siden: funksjonen i stereoen ([slot]) og det som står på rattet. */
    data class Fn(val slot: Int, val label: String, val wheel: String)

    /** Det brukerens ratt har (Avensis T27: VOL −, demp, VOL +, ▼, ▲, MODE, DISP), og noen valgfrie. */
    val main = listOf(
        Fn(3, "Volum +", "VOL +"), Fn(4, "Volum −", "VOL −"), Fn(2, "Lyd av", "Høyttaler-knappen"),
        Fn(6, "Neste", "▲"), Fn(5, "Forrige", "▼"), Fn(1, "Bytt kilde", "MODE"), Fn(7, "Kart", "DISP"),
    )
    val extra = listOf(Fn(10, "Spill / pause", ""), Fn(8, "Svar", ""), Fn(9, "Legg på", ""))
    private val names = listOf("Av/på", "Bytt kilde", "Lyd av", "Volum +", "Volum −", "Forrige", "Neste", "Kart",
        "Svar", "Legg på", "Spill / pause", "Spol fram", "Spol tilbake")
    fun labelFor(slot: Int) = names.getOrNull(slot) ?: "Plass $slot"

    /** Gyldige tilbakemeldinger (samme grenser som cabin). */
    fun valid(field: Int, v: List<Int>): Boolean = when (field) {
        0 -> v.size == 1 && v[0] in -1..99
        1 -> v.size == 2 && v[0] in 0..99 && v[1] in -1..255
        2 -> v.size == 1 && v[0] in 0..255
        3 -> v.size == 2 && v[0] in 0..5 && v[1] in 0..255
        4, 5 -> v.size == 1 && v[0] in 0..1
        6 -> v.size == 2 && v[0] in 0..54 && v[1] in 1..100
        else -> false
    }

    /** Lært tall (−1 = «NULL», ikke lært; 255 regnes også som tomt). */
    fun isLearnedValue(v: Int) = v in 0..254

    fun withLearned(map: Map<Int, Int>, slot: Int, value: Int): Map<Int, Int> = map + (slot to value)

    /** Hvilken lært knapp gir tallet [value]? Nærmeste innenfor [tolerance], ellers null. */
    fun slotFor(value: Int, learned: Map<Int, Int>, tolerance: Int = 6): Int? {
        if (value == NO_KEY) return null
        return learned.filterValues { isLearnedValue(it) && kotlin.math.abs(it - value) <= tolerance }
            .minByOrNull { kotlin.math.abs(it.value - value) }?.key
    }

    sealed class Step {
        object Wait : Step()
        data class Capture(val value: Int) : Step()
        object Send : Step()
        object NoSignal : Step()
        object NoConfirm : Step()
    }

    /**
     * Hva læringen skal gjøre nå (sjekkes fire ganger i sekundet): [since] = flisa ble trykket, [adc]/[adcAt] = siste tall
     * fra rattet, [captured] = tallet som er fanget, [sentAt] = når tallet ble gitt til funksjonen (0 = ikke ennå).
     * Et tall som kom etter at flisa ble trykket, ikke er 50 og ikke er gammelt, fanges; står det [SETTLE_MS], sendes det.
     */
    fun step(now: Long, since: Long, adc: Int?, adcAt: Long, captured: Int?, sentAt: Long, detecting: Boolean?): Step {
        if (sentAt > 0) return if (now - sentAt > CONFIRM_TIMEOUT_MS) Step.NoConfirm else Step.Wait
        val fresh = adc != null && adc != NO_KEY && adcAt >= since && now - adcAt <= FRESH_MS
        if (fresh && captured != adc) return Step.Capture(adc!!)
        if (fresh && captured == adc && detecting == true && now - since >= SETTLE_MS) return Step.Send
        if (now - since > SIGNAL_TIMEOUT_MS) return Step.NoSignal
        return Step.Wait
    }

    /** Til FYT-loggen. */
    fun note(u: Int, ints: IntArray?): String = when (u) {
        1 -> "→ rattmodulen: lært ${ints?.getOrNull(0)?.let { labelFor(it) } ?: ""}"
        2 -> "→ rattmodulen: tall fra rattet"
        3 -> "→ rattmodulen: kanal"
        4 -> "→ rattmodulen: lærer av/på"
        5 -> "→ rattmodulen: MCU-læring finnes"
        6 -> "→ rattmodulen: MCU-tast"
        else -> "→ rattmodulen"
    }
}
