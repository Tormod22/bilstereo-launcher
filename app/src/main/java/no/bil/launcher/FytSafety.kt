package no.bil.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper

/* ======================= Sikkerhetsnett for FYT-funksjonene ======================= */

/**
 * Det meste av FYT-koden (Fyt.kt, FytRadio, FytSound, FytPhone, FytMain, FytCan, Steer, FytVolume) er laget etter andres
 * dekompilering av FYT-appene og er ikke prøvd på akkurat denne stereoen. En feil der skal aldri gjøre stereoen
 * ubrukelig (T'OS er startskjermen – en krasj-løkke gir svart skjerm). Derfor:
 *  - Hovedbryter «Stereo-funksjoner (FYT)» (Prefs [MASTER], på som standard). Av = Fyt kobler fra com.syu.ms, og alt
 *    som spør om [Fyt.connected] / [Fyt.enabled] oppfører seg som på en stereo uten FYT.
 *  - Krasjer T'OS [TRIP_CRASHES] ganger på 3 minutter med FYT-kode i feilen, slås FYT-funksjonene av ved neste start
 *    (både hovedbryteren og de enkelte bryterne i [FEATURES]) og T'OS sier rolig fra. Slår brukeren hovedbryteren på
 *    igjen, settes bryterne tilbake slik de var.
 * Ren logikk her testes i FytSafetyTest.
 */
internal object FytSafetyLogic {
    const val MASTER = "fyt_all"
    /** Bryterne som slås av etter en FYT-feil (alle er på som standard). */
    val FEATURES = listOf("fyt_radio", "fyt_bt", "fyt_phone", "fyt_call_screen", "fyt_steer", "fyt_bright", "tos_canbus", "tos_volume")
    const val TRIP_CRASHES = 2
    const val NOTICE = "T'OS slo av stereo-funksjonene etter en feil – slå dem på igjen i Innstillinger"

    /** Hovedbryteren – på hvis innstillingene ikke kan leses (testene uten Prefs). */
    inline fun masterOn(read: () -> Boolean): Boolean = try { read() } catch (_: Throwable) { true }

    /**
     * Gjelder krasjen FYT-koden? Ser etter T'OS-klasser i stakken: Fyt*, Steer*, og FYT-delene av knappestyringen
     * (KeyService.checkFytPopup/checkCanPopup o.l.). Andre krasj (kart, bilde …) teller ikke her (vanlig sikker modus).
     */
    fun isFytCrash(trace: String): Boolean {
        if (trace.isBlank()) return false
        return fytFrame.containsMatchIn(trace) || keyServiceFyt.containsMatchIn(trace)
    }
    private val fytFrame = Regex("""at no\.bil\.launcher\.(Fyt|Steer)\w*[.$]""")
    private val keyServiceFyt = Regex("""at no\.bil\.launcher\.KeyService\w*[.$]\w*(Fyt|fyt|Can|Steer|steer)""")

    /** Nok FYT-krasj nylig til å slå av FYT-funksjonene? ([recentCrash]: siste 3 min, ikke «i framtiden».) */
    fun shouldTrip(times: List<Long>, now: Long): Boolean = times.count { recentCrash(now, it) } >= TRIP_CRASHES

    /** Liste lagret som «a,b,c». */
    fun parseTimes(s: String?): List<Long> = s.orEmpty().split(",").mapNotNull { it.trim().toLongOrNull() }
    fun parseKeys(s: String?): List<String> = s.orEmpty().split(",").map { it.trim() }.filter { it in FEATURES }

    /** Hvilke brytere skal slås av (de som er på nå). [on] = bryterens verdi (standard på). */
    fun toDisable(on: (String) -> Boolean): List<String> = FEATURES.filter(on)

    /** Ventetid før neste forsøk på å koble til com.syu.ms: 5 s, 10 s, 20 s … høyst 5 min; null = gi opp (etter 20). */
    fun retryDelay(tries: Int): Long? {
        if (tries < 0 || tries >= 20) return null
        return (5_000L shl minOf(tries, 6)).coerceAtMost(300_000L)
    }

    /** com.syu.ms koblet til så mange ganger i løpet av [STORM_WINDOW_MS]: den starter på nytt hele tiden. */
    const val STORM_WINDOW_MS = 60_000L
    fun isStorm(connectsInWindow: Int): Boolean = connectsInWindow > 5

    /** Lysstyrke T'OS aldri lar stå igjen: under [FytMainLogic.MIN_LEVEL] (nesten svart skjerm) → [SAFE_LEVEL]. */
    const val SAFE_LEVEL = 50
    fun safeLevel(stored: Int): Int? = if (stored in 0 until FytMainLogic.MIN_LEVEL) SAFE_LEVEL else null
}

object FytSafety {
    private val h by lazy { Handler(Looper.getMainLooper()) }
    private const val OFF_KEYS = "fyt_safe_off"
    private const val REASON = "fyt_safe_reason"
    private const val NOTICES = "fyt_safe_notices"

    /** T'OS har slått av FYT-funksjonene etter en feil (og brukeren har ikke slått dem på igjen). */
    val tripped: Boolean get() = runCatching { Prefs.str(REASON).isNotEmpty() && !Fyt.enabled }.getOrDefault(false)

    /**
     * Slå av FYT-funksjonene (kalles fra SafeMode.check ved oppstart, før Hub.start – så Fyt kobler ikke til i det
     * hele tatt). Bryterne som var på huskes, så de kan settes tilbake.
     */
    fun trip(reason: String) {
        val off = FytSafetyLogic.toDisable { Prefs.bool(it, true) }
        val prev = FytSafetyLogic.parseKeys(Prefs.str(OFF_KEYS))
        off.forEach { Prefs.set(it, false) }
        Prefs.set(OFF_KEYS, (prev + off).distinct().joinToString(","))
        Prefs.set(REASON, reason)
        Prefs.set(NOTICES, 0)
        // Settes sist og uten [onMaster] (ingenting kjører ennå)
        Prefs.setQuiet(FytSafetyLogic.MASTER, false)
    }

    /** Hovedbryteren ble endret (Prefs.set). På: sett bryterne tilbake og koble til. Av: koble fra. */
    internal fun onMaster(on: Boolean) {
        if (on) {
            FytSafetyLogic.parseKeys(Prefs.str(OFF_KEYS)).forEach { Prefs.set(it, true) }
            Prefs.set(OFF_KEYS, null); Prefs.set(REASON, null); Prefs.set(NOTICES, null)
            Fyt.appContext?.let { c -> runCatching { Fyt.start(c) } }
        } else {
            // Lyden T'OS skrudde av (radio-pause) skal ikke bli stående av
            runCatching { FytRadio.releaseTosMute(force = true) }
            runCatching { Fyt.stop("slått av i Innstillinger (Stereo-funksjoner (FYT))") }
            runCatching { FytMain.onDisconnected() }
        }
    }

    /** Ved oppstart: si rolig fra (høyst de tre første oppstartene etter at det skjedde). */
    fun noticeOnStart() {
        if (!tripped) return
        val n = Prefs.int(NOTICES, 0)
        if (n >= 3) return
        Prefs.setQuiet(NOTICES, n + 1)
        h.postDelayed({ runCatching { Notify.show(FytSafetyLogic.NOTICE, long = true) } }, 6_000)
    }

    /** Til kartleggingen. */
    fun summary(): String = runCatching {
        val r = Prefs.str(REASON)
        if (r.isEmpty()) "" else "(slått av etter feil: $r; brytere: ${Prefs.str(OFF_KEYS)})"
    }.getOrDefault("")
}
