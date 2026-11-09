package no.bil.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/* ======================= Skjermene i T'OS ======================= */

enum class Screen { DASH, APPS, BT, KEYS, MUSIC, MAP, NAV, RADIO, CAR, PHONE, SETTINGS, CLOCK, TUNING, SETUP, RECENTS, STEER, SELFTEST }

/** Hvilken skjerm som vises. Kan settes fra knappetjenesten, derfor et globalt objekt. */
object Nav {
    var screen by mutableStateOf(Screen.DASH)
    var quick by mutableStateOf(false)   // hurtigpanelet (sveip ned)
    var pick by mutableStateOf<Pair<String, String>?>(null)   // «velg app»: tittel til innstillingsnøkkel
    const val EXTRA = "screen"
    /** Åpne hurtigpanelet med en gang (T'OS ble hentet fram for nedtrekksmenyen). */
    const val EXTRA_QUICK = "quick"
}

/* ======================= Knappene på stereoen ======================= */

/** Det en knapp kan gjøre. Rekkefølgen er rekkefølgen i valglista. */
enum class Action(val label: String) {
    HOME("T'OS forside"),
    BACK("Tilbake"),
    NAVI("Kart / navigasjon"),
    MUSIC("Musikk (Spotify)"),
    BT("Bluetooth-side"),
    RADIO("FM-radio (spiller i T'OS)"),
    RADIO_PAGE("Radio-side (nettradio + radio)"),
    PHONE("Telefon-side (favoritter)"),
    CARPLAY("CarPlay"),
    APPS("Apper"),
    RECENTS("Siste apper"),
    PLAY_PAUSE("Spill / pause"),
    NEXT_TRACK("Neste sang"),
    PREV_TRACK("Forrige sang"),
    NAV_HOME("Naviger hjem"),
    MAP_FULL("Kart i fullskjerm"),
    SPLIT("Kart + musikk (delt skjerm)"),
    VOICE("Stemmestyring"),
    CAMERA("Ryggekamera"),
    CAR("Bil og tur"),
    TUNING("Tuning og ytelse"),
    SETTINGS("Innstillinger"),
    QUICK("Hurtigpanel"),
    NONE("Ingenting (stereoen bestemmer)"),
}

/** De fysiske knappene rundt skjermen, med det de gjør hvis ingenting annet er valgt. */
enum class PanelKey(val label: String, val default: Action) {
    MENU("MENU", Action.HOME),
    BACK("BACK", Action.BACK),
    NAVI("NAVI", Action.NAVI),
    RADIO("RADIO", Action.RADIO_PAGE),   // ikke bestemt ennå – kan endres på Knapper-siden
    MUSIC("MUSIC", Action.MUSIC),
    BT("BT", Action.BT),
    RATT1("Ratt 1", Action.NONE),         // rattknapper: læres på samme måte
    RATT2("Ratt 2", Action.NONE),
    RATT3("Ratt 3", Action.NONE),
    RATT4("Ratt 4", Action.NONE),
}

/** Langt trykk (over 0,6 s): standard for noen knapper. */
fun PanelKey.defaultLong(): Action = when (this) {
    PanelKey.MUSIC -> Action.NEXT_TRACK
    PanelKey.NAVI -> Action.NAV_HOME
    PanelKey.BT -> Action.VOICE
    else -> Action.NONE
}

/**
 * Lagrer per knapp: valgt handling, og det T'OS har lært om knappen –
 * enten tastekoden den sender, eller hvilken app stereoen åpner når den trykkes.
 */
object Buttons {
    private fun prefs(c: Context) = c.getSharedPreferences("knapper", Context.MODE_PRIVATE)

    /** Endres ved hver lagring, så Knapper-siden tegnes på nytt uten å nullstille rullingen. */
    var version by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    private fun changed() { version++ }
    /** Tegn Knapper-siden på nytt etter at innstillingene er byttet ut (f.eks. hentet fra sikkerhetskopi). */
    fun reload() = changed()

    fun action(c: Context, k: PanelKey): Action =
        version.let { _ -> prefs(c) }.getString("action_${k.name}", null)?.let { n -> Action.entries.firstOrNull { it.name == n } } ?: k.default
    fun setAction(c: Context, k: PanelKey, a: Action) { prefs(c).edit().putString("action_${k.name}", a.name).apply(); changed() }
    fun longAction(c: Context, k: PanelKey): Action =
        version.let { _ -> prefs(c) }.getString("long_${k.name}", null)?.let { n -> Action.entries.firstOrNull { it.name == n } } ?: k.defaultLong()
    fun setLongAction(c: Context, k: PanelKey, a: Action) { prefs(c).edit().putString("long_${k.name}", a.name).apply(); changed() }

    /** Tastekoden knappen er lært med (-1 = ikke lært). */
    fun code(c: Context, k: PanelKey): Int = version.let { _ -> prefs(c) }.getInt("code_${k.name}", -1)
    fun pkg(c: Context, k: PanelKey): String? {
        val p = version.let { _ -> prefs(c) }
        return p.getString("pkg_${k.name}", null) ?: FytKeys.defaultPkg(k, FytKeys.defaults(c), p.all)
    }
    /** Er pakken bare FYT-standarden (ikke lært av brukeren)? Vises som «Åpner Radio (FYT)» på Knapper-siden. */
    fun isFytDefault(c: Context, k: PanelKey): Boolean =
        version.let { _ -> prefs(c) }.getString("pkg_${k.name}", null) == null && pkg(c, k) != null
    /** Lær tastekoden. Hadde en annen knapp samme kode, glemmes den der (ellers ville bare den første virket). */
    fun learnCode(c: Context, k: PanelKey, code: Int) {
        val e = prefs(c).edit().putInt("code_${k.name}", code).remove("pkg_${k.name}").remove("fyt_${k.name}")
        learnedConflicts(prefs(c).all, "code_", code, k).forEach { e.remove(it) }
        e.apply(); changed()
    }
    fun learnPkg(c: Context, k: PanelKey, pkg: String) {
        val e = prefs(c).edit().putString("pkg_${k.name}", pkg).remove("code_${k.name}").remove("fyt_${k.name}")
        learnedConflicts(prefs(c).all, "pkg_", pkg, k).forEach { e.remove(it) }
        e.apply(); changed()
    }

    /** Rattknappen FYT-tjenesten meldte da knappen ble lært («modul/kode/verdi», se SteerLogic), null = ikke lært slik. */
    fun fytId(c: Context, k: PanelKey): String? = version.let { _ -> prefs(c) }.getString("fyt_${k.name}", null)
    fun learnFyt(c: Context, k: PanelKey, id: String) {
        val e = prefs(c).edit().putString("fyt_${k.name}", id).remove("code_${k.name}").remove("pkg_${k.name}")
        learnedConflicts(prefs(c).all, "fyt_", id, k).forEach { e.remove(it) }
        e.apply(); changed()
    }
    fun keyForFyt(c: Context, id: String) = PanelKey.entries.firstOrNull { fytId(c, it) == id }

    /** Kode 0 (ukjent tast) og lavere teller ikke – ellers ville alle ukjente taster blitt tolket som en lært knapp. */
    fun keyForCode(c: Context, code: Int) = if (code <= 0) null else PanelKey.entries.firstOrNull { code(c, it) == code }
    fun keyForPkg(c: Context, pkg: String) = PanelKey.entries.firstOrNull { pkg(c, it) == pkg }

    /**
     * Skal T'OS ta knappen? «Ingenting (stereoen bestemmer)» på kort trykk betyr at stereoen får knappen helt,
     * også langt trykk. BACK som sender vanlig tilbake-signal lar vi Android ta seg av selv.
     */
    fun shouldIntercept(c: Context, k: PanelKey, code: Int): Boolean {
        val a = action(c, k)
        if (a == Action.NONE) return false
        if (longAction(c, k) != Action.NONE) return true
        return !(a == Action.BACK && code == KeyEvent.KEYCODE_BACK)
    }

    /** Pakkene en handling åpner – brukes for å ikke sende stereoen i ring. */
    fun targets(c: Context, a: Action): List<String> {
        fun tile(p: String) = Tiles.byPrefix(p).let { t -> t.candidates + listOfNotNull(AppRepository.tilePackage(c, t)) }
        return when (a) {
            // RADIO: radioen startes i bakgrunnen og T'OS sin radio-side vises (stereoens radio-app tas hånd om i KeyService)
            Action.HOME, Action.BT, Action.APPS, Action.RECENTS, Action.RADIO, Action.RADIO_PAGE, Action.PHONE, Action.MAP_FULL, Action.CAR, Action.QUICK, Action.TUNING, Action.SETTINGS -> listOf(c.packageName)
            Action.NAVI -> tile("Nav") + listOf(Prefs.str("nav_app"))
            // Bare appen T'OS faktisk åpner (ikke hele lista): ellers ville FYT sin musikkspiller (som MUSIC-knappen åpner)
            // regnes som «målet», og knappen ville aldri gått til Spotify
            Action.MUSIC -> listOfNotNull(musicTarget(c))
            Action.CARPLAY -> tile("Car")
            Action.CAMERA -> tile("Rygg")
            // Disse åpner også apper: kartappen (naviger hjem, delt skjerm), musikkappen, CarPlay (Siri) –
            // uten dem kunne stereoen og T'OS sendt hverandre i ring
            Action.NAV_HOME -> tile("Nav") + listOf(Prefs.str("nav_app"), c.packageName)
            Action.SPLIT -> tile("Mus") + tile("Nav") + listOf(Prefs.str("nav_app"))
            Action.VOICE -> tile("Car")
            else -> emptyList()
        }
    }

    fun perform(c: Context, a: Action) {
        when (a) {
            Action.HOME -> show(c, Screen.DASH)
            Action.BT -> show(c, Screen.BT)
            Action.APPS -> show(c, Screen.APPS)
            Action.RECENTS -> show(c, Screen.RECENTS)
            Action.NAVI -> openMaps(c)
            Action.MUSIC -> openMusic(c)
            // Stereoens FM/AM-radio uten radio-appens skjermbilde (se HwRadio.powerOn)
            Action.RADIO -> HwRadio.powerOn(c, showPage = true)
            Action.RADIO_PAGE -> show(c, Screen.RADIO)
            Action.PHONE -> show(c, Screen.PHONE)
            Action.CARPLAY -> AppRepository.launchTile(c, Tiles.byPrefix("Car"))
            Action.PLAY_PAUSE -> Media.playPause(c)
            Action.NEXT_TRACK -> Media.next(c)
            Action.PREV_TRACK -> Media.previous(c)
            Action.NAV_HOME -> Navigate.home(c)
            Action.MAP_FULL -> show(c, Screen.MAP)
            Action.CAR -> show(c, Screen.CAR)
            Action.TUNING -> show(c, Screen.TUNING)
            Action.SETTINGS -> show(c, Screen.SETTINGS)
            Action.QUICK -> {
                // I T'OS: panelet over skjermen som vises. Over en annen app: T'OS-panelet som vindu over den.
                val svc = KeyService.instance
                if (TosOverlay.appShown) Nav.quick = true
                else if (svc == null || !ShadePanel.show(svc)) show(c, Screen.DASH, quick = true)
            }
            Action.SPLIT -> splitScreen(c)
            Action.VOICE -> voice(c)
            Action.CAMERA -> AppRepository.launchTile(c, Tiles.byPrefix("Rygg"))
            Action.BACK -> when (c) {
                is AccessibilityService -> c.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                is ComponentActivity -> c.onBackPressedDispatcher.onBackPressed()
                else -> Unit
            }
            Action.NONE -> Unit
        }
    }

    /** Appen MUSIC-handlingen åpner: brukerens valg, ellers første som finnes (Spotify først). */
    fun musicTarget(c: Context): String? =
        Prefs.str("music_pkg").takeIf { it.isNotBlank() && AppRepository.isInstalled(c, it) } ?: AppRepository.tilePackage(c, Tiles.byPrefix("Mus"))

    /** Musikkappen brukeren har valgt, ellers første som finnes (Spotify først). */
    fun openMusic(c: Context) {
        // Musikk i stedet for FM-radioen: slå av radioen, så de ikke spiller oppå hverandre
        if (HwRadio.wanted) HwRadio.powerOff(c)
        // FYT: Android-appen blir lydkilden (ellers går rattknappene til stereoens egen musikkspiller, som MUSIC-knappen valgte)
        runCatching { Steer.claimSource(musicTarget(c)) }
        val pkg = Prefs.str("music_pkg")
        if (pkg.isNotBlank() && AppRepository.isInstalled(c, pkg)) AppRepository.launch(c, pkg)
        else AppRepository.launchTile(c, Tiles.byPrefix("Mus"))
    }

    /** Musikk i vanlig vindu, kartet ved siden av (der stereoen støtter delt skjerm). */
    fun splitScreen(c: Context) {
        openMusic(c)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ openMaps(c) }, 700)
    }

    /** Stemmestyring: Google-assistent hvis den finnes, ellers CarPlay (der Siri bor). */
    fun voice(c: Context) {
        val tries = listOf(Intent(Intent.ACTION_VOICE_COMMAND), Intent("android.intent.action.ASSIST"))
        for (i in tries) if (runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        AppRepository.launchTile(c, Tiles.byPrefix("Car"))
    }

    /**
     * Vis en T'OS-skjerm. [instant]: uten overgangsanimasjon (brukes når T'OS skal dekke over stereoens egne
     * skjermbilder, så de ikke synes under animasjonen).
     */
    fun show(c: Context, s: Screen, instant: Boolean = false, quick: Boolean = false) {
        Nav.screen = s
        val i = Intent(c, MainActivity::class.java)
            .putExtra(Nav.EXTRA, s.name)
            .putExtra(Nav.EXTRA_QUICK, quick)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (instant) i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { c.startActivity(i) }
    }

    /**
     * Ikke overstyr noe mens det er en samtale (BT-appen viser da samtalebildet). FYT-stereoen har egen BT-brikke: der
     * kan Android stå i vanlig lydmodus under samtalen, så FYT sin telefontilstand teller også.
     */
    fun inCall(c: Context): Boolean =
        (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode != AudioManager.MODE_NORMAL || (Fyt.btReady && Fyt.inCall)

    fun serviceEnabled(c: Context): Boolean =
        Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("${c.packageName}/") == true
}

/* ======================= «Lær knapp» ======================= */

/** Andre knappers lagrede verdier ([prefix] + knappnavn) som er lik [value] – de skal glemmes når [keep] lærer den. */
internal fun learnedConflicts(all: Map<String, *>, prefix: String, value: Any, keep: PanelKey): List<String> =
    PanelKey.entries.filter { it != keep }.map { prefix + it.name }.filter { all[it] == value }

object Learn {
    var target by mutableStateOf<PanelKey?>(null)
    var message by mutableStateOf<String?>(null)
    private var startedAt = 0L
    /** Etter så lang tid regnes læringen som glemt (Knapper-siden gir også opp av seg selv). */
    internal const val TIMEOUT_MS = 15_000L

    fun start(k: PanelKey) { target = k; message = null; startedAt = android.os.SystemClock.elapsedRealtime() }
    fun cancel(msg: String? = null) { target = null; message = msg }

    internal fun expired(now: Long, started: Long) = now - started > TIMEOUT_MS

    /** Læringen har stått for lenge (f.eks. Knapper-siden ble lukket): avbryt. Gir true hvis den ble avbrutt. */
    private fun timedOut(): Boolean {
        if (!expired(android.os.SystemClock.elapsedRealtime(), startedAt)) return false
        cancel("Fikk ikke noe signal i tide. Trykk «Lær» og prøv igjen.")
        return true
    }

    /** Knappen sendte en tastekode. Gir false hvis tasten ikke ble brukt til læring (da behandles den vanlig). */
    fun offerKey(c: Context, code: Int): Boolean {
        val k = target ?: return false
        if (timedOut()) return false
        // Tilbake avbryter læringen (BACK-knappen virker som tilbake uten å læres)
        if (code == KeyEvent.KEYCODE_BACK) { cancel("Læring avbrutt."); return true }
        if (code <= 0) { message = "${k.label} sender ingen brukbar tastekode. Prøv knappen igjen, eller la stereoen bestemme."; return true }
        Buttons.learnCode(c, k, code)
        cancel("${k.label} er lært (sender tastekode $code).")
        return true
    }

    /** Rattknappen kom som en melding fra FYT-tjenesten ([id] = «modul/kode/verdi»). */
    fun offerFyt(c: Context, id: String, label: String): Boolean {
        val k = target ?: return false
        if (timedOut()) return false
        Buttons.learnFyt(c, k, id)
        cancel("${k.label} er lært ($label).")
        return true
    }

    /** Knappen åpnet en app i stedet. Vi husker appen og går tilbake til Knapper-siden. */
    fun offerPackage(c: Context, pkg: String): Boolean {
        val k = target ?: return false
        if (timedOut()) return false
        Buttons.learnPkg(c, k, pkg)
        cancel("${k.label} er lært (stereoen åpner $pkg).")
        Buttons.show(c, Screen.KEYS)
        return true
    }
}

/**
 * «Fikk signal»-lampe på Knapper-siden: hver gang T'OS kjenner igjen en knapp på stereoen (tastekode eller
 * appen knappen åpner), lyser en grønn prikk på akkurat den raden et øyeblikk. Da ser man at riktig knapp er lært –
 * og ikke en annen. Ukjente koder vises også, så man ser at knappen sender noe T'OS ikke har lært ennå.
 */
object KeyPing {
    const val LIT_MS = 1500L
    var key by androidx.compose.runtime.mutableStateOf<PanelKey?>(null)
        private set
    /** Hvordan knappen ble kjent igjen: «kode 21» / «åpnet Maps». */
    var how by androidx.compose.runtime.mutableStateOf("")
        private set
    var unknownCode by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var lit by androidx.compose.runtime.mutableStateOf(false)
        private set
    private var seq = 0
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun hit(k: PanelKey, how: String) = main.post { key = k; this.how = how; unknownCode = 0; light() }

    /** En knapp T'OS ikke kjenner (ikke volum, ikke under læring). */
    fun unknown(code: Int) = main.post { if (code > 0) { key = null; how = ""; unknownCode = code; light() } }

    private fun light() {
        lit = true
        val mine = ++seq
        main.postDelayed({ if (seq == mine) lit = false }, LIT_MS)
    }

    /** Rene regler (testbare): hvilke koder skal vises som «ukjent knapp». Volum/tilbake/hjem er vanlige systemknapper. */
    internal fun reportUnknown(code: Int): Boolean = code > 0 && code !in setOf(
        android.view.KeyEvent.KEYCODE_VOLUME_UP, android.view.KeyEvent.KEYCODE_VOLUME_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_MUTE,
        android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_HOME,
    )
}

/**
 * FYT-stereoen (kartleggingen 8. nov, opptaket): panelknappene sender ingen tastekoder – MCU-en ber com.syu.ms åpne en app:
 * RADIO → com.syu.radio (ActRadio, kilde 1), MUSIC → com.syu.music (Activity_All, kilde 8), BT → com.syu.bt (ActBt, kilde 2),
 * NAVI → navigasjonsappen som er valgt i FYT sitt oppsett (systemegenskapen sys.navi.packagename, her iGO).
 * Da trenger brukeren ikke «lære» knappene: T'OS kjenner dem igjen på appen som kom fram. Bare når knappen ikke er lært
 * (verken app eller tastekode), og ingen annen knapp er lært med samme app.
 */
internal object FytKeys {
    const val RADIO = "com.syu.radio"
    const val MUSIC = "com.syu.music"
    const val BT = "com.syu.bt"
    /** FYT sitt valg av navigasjonsapp (NAVI-knappen). */
    private val naviProps = listOf("sys.navi.packagename", "persist.sys.navi.packagename")

    /** Ren logikk (testbar): standardappen for [k], gitt FYT-standardene og det som er lagret («knapper»). */
    fun defaultPkg(k: PanelKey, defaults: Map<PanelKey, String>, stored: Map<String, *>): String? {
        val p = defaults[k] ?: return null
        if (stored["pkg_${k.name}"] != null) return null
        if ((stored["code_${k.name}"] as? Int ?: -1) > 0) return null
        if (stored["fyt_${k.name}"] != null) return null
        if (PanelKey.entries.any { it != k && stored["pkg_${it.name}"] == p }) return null
        return p
    }

    /** FYT-standardene på en FYT-stereo (bare apper som finnes). [navi]: FYT sin navigasjonsapp. */
    fun defaultsFor(installed: (String) -> Boolean, navi: String?, ownPkg: String): Map<PanelKey, String> = buildMap {
        if (installed(RADIO)) put(PanelKey.RADIO, RADIO)
        if (installed(MUSIC)) put(PanelKey.MUSIC, MUSIC)
        if (installed(BT)) put(PanelKey.BT, BT)
        navi?.trim()?.takeIf { it.isNotEmpty() && it != ownPkg && installed(it) }?.let { put(PanelKey.NAVI, it) }
    }

    @Volatile private var cached: Map<PanelKey, String>? = null
    @Volatile private var cachedAt = 0L

    /** Leses én gang per 10. minutt (FYT-oppsettet kan endre navigasjonsappen). Tomt på andre stereoer. */
    fun defaults(c: Context): Map<PanelKey, String> {
        val now = android.os.SystemClock.elapsedRealtime()
        cached?.let { if (now - cachedAt < 600_000) return it }
        val m = runCatching {
            if (!Fyt.present(c)) emptyMap()
            else defaultsFor({ AppRepository.isInstalled(c, it) }, naviPackage(), c.packageName)
        }.getOrDefault(emptyMap())
        cached = m; cachedAt = now
        return m
    }

    @android.annotation.SuppressLint("PrivateApi")   // samme som kartleggingen: SystemProperties finnes på alle Android-versjoner her
    fun naviPackage(): String? = runCatching {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        naviProps.firstNotNullOfOrNull { (get.invoke(null, it) as? String)?.trim()?.takeIf { v -> v.isNotEmpty() } }
    }.getOrNull()
}
