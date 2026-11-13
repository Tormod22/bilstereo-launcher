package no.bil.launcher

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/* ======================= Strøm: ikke tapp bilbatteriet når tenningen er av ======================= */

/**
 * Ren logikk for strømsparingen (testes i PowerLogicTest).
 *
 * Inndata: tenningen (ACC) fra FYT-stereoen (true/false, null = ukjent: ikke FYT, ikke meldt eller frakoblet) og om
 * skjermen er på. Skjermen er alltid med: en tenning som tolkes feil (UVERIFISERT på denne stereoen) skal aldri slå av
 * GPS-en mens skjermen lyser. FYT melder ACC av ca. 1,2 s før skjermen går av (vasyl91/WakeSignals), så det koster lite.
 *
 *  | tenning \ skjerm | på    | av                                                  |
 *  |------------------|-------|-----------------------------------------------------|
 *  | på               | AWAKE | DARK  (kjører med mørk skjerm: GPS/tur går videre)  |
 *  | av               | AWAKE | SLEEP (alt stoppes, nettradioen også) etter 3 s     |
 *  | ukjent           | AWAKE | SLEEP (alt unntatt nettradioen) etter 20 s          |
 */
object PowerLogic {
    enum class Mode { AWAKE, DARK, SLEEP }

    /** Dvale bekreftet av tenningen: kort ventetid (tenningen kan blinke når motoren startes). */
    const val SLEEP_GRACE_ACC_MS = 3_000L
    /** Bare skjermen av (ingen tenning å gå etter): vent litt lenger – skjermen kan være slått av et øyeblikk. */
    const val SLEEP_GRACE_SCREEN_MS = 20_000L
    /**
     * Tenningen VAR på, men stereoen har sluttet å melde den (com.syu.ms startet på nytt, Binder død): T'OS kobler til
     * igjen om noen sekunder. Kjører bilen med mørk skjerm, skal ikke det gi dvale (GPS/tur av) etter 20 s – vent lenger.
     * Slås tenningen av i mellomtiden, kommer det vanligvis som kringkasting (FytMain), og da gjelder 20 s igjen.
     */
    const val SLEEP_GRACE_LOST_ACC_MS = 120_000L
    /** Skjermen må ha vært av så lenge før «våkne» regnes som at bilen startet (som Startup og Splash). */
    const val NEW_TRIP_MS = 120_000L
    /** Tenning av → på raskere enn dette er et blink (motoren startes), ikke en ny tur: ingen ny oppstart. */
    const val ACC_FLICKER_MS = SLEEP_GRACE_ACC_MS

    fun mode(acc: Boolean?, screenOn: Boolean): Mode = when {
        screenOn -> Mode.AWAKE
        acc == true -> Mode.DARK
        else -> Mode.SLEEP
    }

    /**
     * Hvor lenge T'OS venter før [mode] tas i bruk (våkne skjer med en gang, dvale etter en liten pause).
     * [lostAccOn] = tenningen er ikke kjent nå, men var sist meldt PÅ (stereoen er midlertidig frakoblet).
     */
    fun graceMs(acc: Boolean?, screenOn: Boolean, lostAccOn: Boolean = false): Long = when (mode(acc, screenOn)) {
        Mode.SLEEP -> when {
            acc == false -> SLEEP_GRACE_ACC_MS
            lostAccOn -> SLEEP_GRACE_LOST_ACC_MS
            else -> SLEEP_GRACE_SCREEN_MS
        }
        else -> 0L
    }

    /**
     * Venter T'OS allerede på [pending] (frist [dueAt]), og samme modus ønskes nå med ventetid [waitMs]: start ventingen
     * på nytt bare når den nye fristen er tidligere (f.eks. tenningen ble bekreftet av mens T'OS ventet 20 s / 2 min).
     */
    fun reschedule(pending: Mode?, dueAt: Long, want: Mode, waitMs: Long, now: Long): Boolean =
        pending != want || now + waitMs < dueAt

    /**
     * Tenningen på (kant fra av): skal oppstarten (Startup) kjøres? Ikke når den var av kortere enn [ACC_FLICKER_MS]
     * (motoren startes og tenningen blinker). [offAt] = da den gikk av (0 = ukjent, f.eks. T'OS startet på nytt i dvalen).
     */
    fun startupOnAccOn(offAt: Long, now: Long): Boolean = offAt == 0L || (now - offAt).let { it < 0 || it >= ACC_FLICKER_MS }

    /**
     * Endring i tenningen. [prev] = siste verdi i denne kjøringen av T'OS (null = ingen ennå), [saved] = siste verdi
     * lagret (fra før T'OS ble startet på nytt). FYT kan drepe T'OS i dvalen; da er første melding etter at bilen startes
     * «på» med prev = null – uten den lagrede verdien ble det aldri noen oppstart den turen.
     */
    internal fun accEdge(prev: Boolean?, saved: Boolean?, now: Boolean): FytMainLogic.Edge = FytMainLogic.edge(prev ?: saved, now)

    /** Oppstarten (musikk, siste app) kjøres høyst én gang per [NEW_TRIP_MS] (tenning på, skjerm på og kald start kan komme samtidig). */
    fun startupDue(lastRun: Long, now: Long): Boolean = lastRun == 0L || now - lastRun >= NEW_TRIP_MS

    /**
     * Hva som får gå. [visible] = en T'OS-skjerm vises (ikke en annen app framme).
     *  - gps: posisjon (fart, tur, parkering, fartsgrense) – også når en annen app er framme og når skjermen er mørk under kjøring
     *  - obd: OBD-brikka (spenning) – ikke når bilen står med tenningen av (brikka og bilen skal få sove)
     *  - updates: versjonssjekk mot GitHub
     *  - theme: dag/natt + nattdemping hvert minutt
     *  - stereoVolt: stereoens spenning hvert 10. s (vises bare med skjermen på)
     *  - ui: klokker, kartfliser, CPU-temperatur – bare når T'OS faktisk vises
     *  - netRadio: nettradioen får spille (av bare når tenningen er bekreftet av og skjermen er av)
     */
    data class Plan(
        val gps: Boolean, val obd: Boolean, val updates: Boolean, val theme: Boolean,
        val stereoVolt: Boolean, val ui: Boolean, val netRadio: Boolean,
    )

    fun plan(mode: Mode, visible: Boolean, acc: Boolean?): Plan = when (mode) {
        Mode.AWAKE -> Plan(gps = true, obd = true, updates = true, theme = true, stereoVolt = true, ui = visible, netRadio = true)
        Mode.DARK -> Plan(gps = true, obd = true, updates = false, theme = false, stereoVolt = false, ui = false, netRadio = true)
        Mode.SLEEP -> Plan(gps = false, obd = false, updates = false, theme = false, stereoVolt = false, ui = false, netRadio = acc != false)
    }

    /** Skjermen kom på uten at tenningen er kjent: ny tur (Startup) bare når den var av lenge nok. */
    fun wakeIsNewTrip(screenOffAt: Long, now: Long): Boolean = screenOffAt != 0L && now - screenOffAt > NEW_TRIP_MS
}

/**
 * Følger tenningen og skjermen og sier til resten av T'OS hva som får gå ([plan]). Hub (GPS, versjonssjekk, dag/natt),
 * CarBattery (OBD, spenning), kartflisene og klokkene venter på [plan] i stedet for å gå i ring hele natten.
 * Ingen vekkelåser eller alarmer brukes – T'OS holder aldri stereoen våken selv (FLAG_KEEP_SCREEN_ON i MainActivity
 * gjelder bare mens T'OS vises; stereoen slår skjermen av selv ved tenning av).
 */
object Power {
    data class State(val mode: PowerLogic.Mode, val visible: Boolean, val acc: Boolean?) {
        val plan: PowerLogic.Plan get() = PowerLogic.plan(mode, visible, acc)
    }

    private val h by lazy { Handler(Looper.getMainLooper()) }
    private val _state = MutableStateFlow(State(PowerLogic.Mode.AWAKE, visible = true, acc = null))   // før start(): som før (alt går)
    val state: StateFlow<State> get() = _state
    val plan: PowerLogic.Plan get() = _state.value.plan
    val mode: PowerLogic.Mode get() = _state.value.mode

    /** Appens Context (svak referanse: ingen «static field leak»). */
    @Volatile private var appRef: java.lang.ref.WeakReference<Context>? = null
    private val app: Context? get() = appRef?.get()
    @Volatile private var started = false
    @Volatile var screenOn = true; private set
    @Volatile private var screenOffAt = 0L
    private var shown = 0                        // T'OS-aktiviteter som vises (onStart/onStop)
    private var pending: PowerLogic.Mode? = null
    private var pendingDue = 0L                  // SystemClock.uptimeMillis() da [pending] tas i bruk
    private val events = ArrayDeque<String>()

    private val apply = Runnable { pending?.let { set(it) }; pending = null }

    fun start(c: Context) {
        if (started) return
        started = true
        val a = c.applicationContext
        appRef = java.lang.ref.WeakReference(a)
        screenOn = runCatching { (a.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive }.getOrDefault(true)
        // Skjermen på/av: reserven når stereoen ikke melder tenningen (og den andre halvdelen av tabellen i PowerLogic)
        runCatching {
            ContextCompat.registerReceiver(a, object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, i: Intent) {
                    when (i.action) {
                        Intent.ACTION_SCREEN_OFF -> onScreen(false)
                        Intent.ACTION_SCREEN_ON -> onScreen(true)
                    }
                }
            }, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        (a as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { shown++; refresh() }
            override fun onActivityStopped(activity: Activity) { shown = (shown - 1).coerceAtLeast(0); refresh() }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        refresh()
    }

    private fun onScreen(on: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (on == screenOn) return
        screenOn = on
        if (!on) { screenOffAt = now; note("skjerm av"); refresh(); return }
        note("skjerm på")
        // Etter dvale: koble til stereoen igjen hvis T'OS hadde gitt opp (com.syu.ms kan ha startet på nytt)
        runCatching { Fyt.onWake() }
        refresh()
        // FYT melder tenningen: da starter FytMain oppstarten (skjermen på er ikke nødvendigvis at bilen startet)
        if (!FytMain.accKnown && PowerLogic.wakeIsNewTrip(screenOffAt, now)) app?.let { runCatching { Startup.run(it) } }
    }

    /** Tenningen, tilkoblingen til stereoen, skjermen eller T'OS-vinduet er endret: regn ut på nytt (hovedtråden). */
    fun refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) { h.post { refresh() }; return }
        // Gikk SCREEN_ON tapt (mottakeren ble ikke registrert, T'OS startet midt i et bytte): spør Android. Aldri fast i dvale
        // med skjermen på – onScreen kaller refresh igjen
        if (!screenOn && interactive()) { onScreen(true); return }
        val acc = runCatching { if (FytMain.accKnown) FytMain.acc else null }.getOrNull()
        val lostAccOn = acc == null && runCatching { FytMain.acc == true && Fyt.enabled }.getOrDefault(false)
        val want = PowerLogic.mode(acc, screenOn)
        val cur = _state.value
        // Synlighet og tenning oppdateres med en gang; selve modusen følger reglene for ventetid
        if (cur.visible != (shown > 0) || cur.acc != acc) _state.value = cur.copy(visible = shown > 0, acc = acc)
        if (want == _state.value.mode) { h.removeCallbacks(apply); pending = null; return }
        val wait = PowerLogic.graceMs(acc, screenOn, lostAccOn)
        val now = SystemClock.uptimeMillis()
        // Venter allerede: ikke start ventetiden på nytt – bare kort den ned (tenningen bekreftet av)
        if (!PowerLogic.reschedule(pending, pendingDue, want, wait, now)) return
        h.removeCallbacks(apply)
        if (wait <= 0L) { pending = null; set(want) } else { pending = want; pendingDue = now + wait; h.postDelayed(apply, wait) }
    }

    private fun interactive(): Boolean = runCatching {
        (app?.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive == true
    }.getOrDefault(false)

    private fun set(m: PowerLogic.Mode) {
        val prev = _state.value
        if (prev.mode == m) return
        // Dvale: lagre FØR den nye modusen publiseres – Hub stopper GPS-en (og merker farten «ukjent») når den ser SLEEP,
        // og parkeringen skal være siste gode posisjon. Uten tenning fra stereoen (FytMain gjør dette selv ved ACC av).
        if (m == PowerLogic.Mode.SLEEP && prev.acc == null) {
            runCatching { Trip.flush() }
            runCatching { Hub.gps?.takeIf { it.stale || it.speedKmh <= 2 }?.let { Parking.saveNow(it) } }
        }
        _state.value = prev.copy(mode = m)
        note("${prev.mode} → $m")
        if (m == PowerLogic.Mode.SLEEP && !_state.value.plan.netRadio) sleepNetRadio()
    }

    /** Tenningen av og skjermen av: nettradioen skal ikke strømme i en parkert bil. Startes igjen av [Startup] ved neste tur. */
    private fun sleepNetRadio() {
        if (!runCatching { NetRadio.active }.getOrDefault(false)) return
        runCatching { Prefs.setQuiet(NET_RESUME, NetRadio.playing) }
        runCatching { NetRadio.stop(app) }
        note("nettradioen stoppet (tenning av)")
    }

    /** Spilte nettradioen da tenningen ble slått av? (Leses én gang, av Startup.) */
    fun takeNetRadioResume(): Boolean = runCatching {
        val v = Prefs.bool(NET_RESUME); if (v) Prefs.setQuiet(NET_RESUME, false); v
    }.getOrDefault(false)
    private const val NET_RESUME = "power_netradio_resume"

    /** Vent til [want] gjelder (gjør ingenting – heller ingen vekking – i mellomtiden). */
    suspend fun await(want: (PowerLogic.Plan) -> Boolean) { _state.first { want(it.plan) } }

    /** Vent til T'OS vises og skjermen er på (klokker, kartfliser). */
    suspend fun awaitUi() = await { it.ui }

    /**
     * Gjør [body] hvert [periodMs] så lenge [want] gjelder. Slutter det å gjelde, stoppes ventingen; når det gjelder
     * igjen (skjermen på / tenning på), kjøres [body] med en gang.
     */
    suspend fun every(periodMs: Long, want: (PowerLogic.Plan) -> Boolean, body: suspend () -> Unit) {
        while (currentCoroutineContext().isActive) {
            await(want)
            body()
            withTimeoutOrNull(periodMs) { _state.first { !want(it.plan) } }
        }
    }

    /** Bare for tester (Robolectric): sett modusen direkte. */
    @androidx.annotation.VisibleForTesting
    internal fun setModeForTest(m: PowerLogic.Mode, acc: Boolean? = null) { _state.value = _state.value.copy(mode = m, acc = acc) }

    private fun note(s: String) {
        val line = SurveyText.stamp(SystemClock.elapsedRealtime()) + "  " + s
        synchronized(events) { events.addLast(line); while (events.size > 30) events.removeFirst() }
        runCatching { SurveyRecorder.note("STRØM  $s") }
    }

    /** Til kartleggingen. */
    fun report(): String = buildString {
        val s = _state.value
        appendLine("Strøm: ${s.mode}  skjerm=${if (screenOn) "på" else "av"}  tenning=${s.acc ?: "ukjent"}  T'OS vises=${s.visible}  " +
            "venter på=${pending ?: "-"}  plan=${s.plan}")
        val lines = synchronized(events) { events.toList() }
        if (lines.isNotEmpty()) { appendLine("Hendelser:"); lines.forEach { appendLine("  $it") } }
    }
}
