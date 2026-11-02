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

enum class Screen { DASH, APPS, BT, KEYS, MUSIC, MAP, NAV, RADIO, CAR, PHONE, SETTINGS, CLOCK }

/** Hvilken skjerm som vises. Kan settes fra knappetjenesten, derfor et globalt objekt. */
object Nav {
    var screen by mutableStateOf(Screen.DASH)
    var quick by mutableStateOf(false)   // hurtigpanelet (sveip ned)
    const val EXTRA = "screen"
}

/* ======================= Knappene på stereoen ======================= */

/** Det en knapp kan gjøre. Rekkefølgen er rekkefølgen i valglista. */
enum class Action(val label: String) {
    HOME("T'OS forside"),
    BACK("Tilbake"),
    NAVI("Kart / navigasjon"),
    MUSIC("Musikk (Spotify)"),
    BT("Bluetooth-side"),
    RADIO("Radio-appen"),
    RADIO_PAGE("Radio-side (nettradio + radio)"),
    PHONE("Telefon-side (favoritter)"),
    CARPLAY("CarPlay"),
    APPS("Apper"),
    PLAY_PAUSE("Spill / pause"),
    NEXT_TRACK("Neste sang"),
    PREV_TRACK("Forrige sang"),
    NAV_HOME("Naviger hjem"),
    MAP_FULL("Kart i fullskjerm"),
    SPLIT("Kart + musikk (delt skjerm)"),
    VOICE("Stemmestyring"),
    CAMERA("Ryggekamera"),
    CAR("Bil og tur"),
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

    fun action(c: Context, k: PanelKey): Action =
        prefs(c).getString("action_${k.name}", null)?.let { n -> Action.entries.firstOrNull { it.name == n } } ?: k.default
    fun setAction(c: Context, k: PanelKey, a: Action) = prefs(c).edit().putString("action_${k.name}", a.name).apply()
    fun longAction(c: Context, k: PanelKey): Action =
        prefs(c).getString("long_${k.name}", null)?.let { n -> Action.entries.firstOrNull { it.name == n } } ?: k.defaultLong()
    fun setLongAction(c: Context, k: PanelKey, a: Action) = prefs(c).edit().putString("long_${k.name}", a.name).apply()

    fun code(c: Context, k: PanelKey): Int = prefs(c).getInt("code_${k.name}", 0)
    fun pkg(c: Context, k: PanelKey): String? = prefs(c).getString("pkg_${k.name}", null)
    fun learnCode(c: Context, k: PanelKey, code: Int) =
        prefs(c).edit().putInt("code_${k.name}", code).remove("pkg_${k.name}").apply()
    fun learnPkg(c: Context, k: PanelKey, pkg: String) =
        prefs(c).edit().putString("pkg_${k.name}", pkg).remove("code_${k.name}").apply()

    fun keyForCode(c: Context, code: Int) = PanelKey.entries.firstOrNull { code(c, it) == code }
    fun keyForPkg(c: Context, pkg: String) = PanelKey.entries.firstOrNull { pkg(c, it) == pkg }

    /** BACK som sender vanlig tilbake-signal lar vi Android ta seg av selv. */
    fun shouldIntercept(c: Context, k: PanelKey, code: Int): Boolean {
        val a = action(c, k)
        if (longAction(c, k) != Action.NONE) return true
        return a != Action.NONE && !(a == Action.BACK && code == KeyEvent.KEYCODE_BACK)
    }

    /** Pakkene en handling åpner – brukes for å ikke sende stereoen i ring. */
    fun targets(c: Context, a: Action): List<String> = when (a) {
        Action.HOME, Action.BT, Action.APPS, Action.RADIO_PAGE, Action.PHONE, Action.MAP_FULL, Action.CAR, Action.QUICK -> listOf(c.packageName)
        Action.NAVI -> Tiles.byPrefix("Nav").candidates
        Action.MUSIC -> Tiles.byPrefix("Mus").candidates
        Action.RADIO -> Tiles.byPrefix("Rad").candidates
        Action.CARPLAY -> Tiles.byPrefix("Car").candidates
        Action.CAMERA -> Tiles.byPrefix("Rygg").candidates
        else -> emptyList()
    }

    fun perform(c: Context, a: Action) {
        when (a) {
            Action.HOME -> show(c, Screen.DASH)
            Action.BT -> show(c, Screen.BT)
            Action.APPS -> show(c, Screen.APPS)
            Action.NAVI -> openMaps(c)
            Action.MUSIC -> openMusic(c)
            Action.RADIO -> AppRepository.launchTile(c, Tiles.byPrefix("Rad"))
            Action.RADIO_PAGE -> show(c, Screen.RADIO)
            Action.PHONE -> show(c, Screen.PHONE)
            Action.CARPLAY -> AppRepository.launchTile(c, Tiles.byPrefix("Car"))
            Action.PLAY_PAUSE -> Media.playPause(c)
            Action.NEXT_TRACK -> Media.next(c)
            Action.PREV_TRACK -> Media.previous(c)
            Action.NAV_HOME -> Navigate.home(c)
            Action.MAP_FULL -> show(c, Screen.MAP)
            Action.CAR -> show(c, Screen.CAR)
            Action.QUICK -> { show(c, Screen.DASH); Nav.quick = true }
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

    /** Musikkappen brukeren har valgt, ellers første som finnes (Spotify først). */
    fun openMusic(c: Context) {
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

    fun show(c: Context, s: Screen) {
        Nav.screen = s
        val i = Intent(c, MainActivity::class.java)
            .putExtra(Nav.EXTRA, s.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { c.startActivity(i) }
    }

    /** Ikke overstyr noe mens det er en samtale (BT-appen viser da samtalebildet). */
    fun inCall(c: Context): Boolean =
        (c.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode != AudioManager.MODE_NORMAL

    fun serviceEnabled(c: Context): Boolean =
        Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("${c.packageName}/") == true
}

/* ======================= «Lær knapp» ======================= */

object Learn {
    var target by mutableStateOf<PanelKey?>(null)
    var message by mutableStateOf<String?>(null)

    fun start(k: PanelKey) { target = k; message = null }
    fun cancel(msg: String? = null) { target = null; message = msg }

    /** Knappen sendte en tastekode. */
    fun offerKey(c: Context, code: Int): Boolean {
        val k = target ?: return false
        Buttons.learnCode(c, k, code)
        cancel("${k.label} er lært (sender tastekode $code).")
        return true
    }

    /** Knappen åpnet en app i stedet. Vi husker appen og går tilbake til Knapper-siden. */
    fun offerPackage(c: Context, pkg: String): Boolean {
        val k = target ?: return false
        Buttons.learnPkg(c, k, pkg)
        cancel("${k.label} er lært (stereoen åpner $pkg).")
        Buttons.show(c, Screen.KEYS)
        return true
    }
}
