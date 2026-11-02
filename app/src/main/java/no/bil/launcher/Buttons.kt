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

enum class Screen { DASH, APPS, BT, KEYS }

/** Hvilken skjerm som vises. Kan settes fra knappetjenesten, derfor et globalt objekt. */
object Nav {
    var screen by mutableStateOf(Screen.DASH)
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
    PHONE("Telefon"),
    CARPLAY("CarPlay"),
    APPS("Apper"),
    NONE("Ingenting (stereoen bestemmer)"),
}

/** De fysiske knappene rundt skjermen, med det de gjør hvis ingenting annet er valgt. */
enum class PanelKey(val label: String, val default: Action) {
    MENU("MENU", Action.HOME),
    BACK("BACK", Action.BACK),
    NAVI("NAVI", Action.NAVI),
    RADIO("RADIO", Action.RADIO),   // ikke bestemt ennå – kan endres på Knapper-siden
    MUSIC("MUSIC", Action.MUSIC),
    BT("BT", Action.BT),
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
        return a != Action.NONE && !(a == Action.BACK && code == KeyEvent.KEYCODE_BACK)
    }

    /** Pakkene en handling åpner – brukes for å ikke sende stereoen i ring. */
    fun targets(c: Context, a: Action): List<String> = when (a) {
        Action.HOME, Action.BT, Action.APPS -> listOf(c.packageName)
        Action.NAVI -> Tiles.byPrefix("Nav").candidates
        Action.MUSIC -> Tiles.byPrefix("Mus").candidates
        Action.RADIO -> Tiles.byPrefix("Rad").candidates
        Action.PHONE -> Tiles.byPrefix("Tel").candidates
        Action.CARPLAY -> Tiles.byPrefix("Car").candidates
        Action.BACK, Action.NONE -> emptyList()
    }

    fun perform(c: Context, a: Action) {
        when (a) {
            Action.HOME -> show(c, Screen.DASH)
            Action.BT -> show(c, Screen.BT)
            Action.APPS -> show(c, Screen.APPS)
            Action.NAVI -> openMaps(c)
            Action.MUSIC -> AppRepository.launchTile(c, Tiles.byPrefix("Mus"))
            Action.RADIO -> AppRepository.launchTile(c, Tiles.byPrefix("Rad"))
            Action.PHONE -> AppRepository.launchTile(c, Tiles.byPrefix("Tel"))
            Action.CARPLAY -> AppRepository.launchTile(c, Tiles.byPrefix("Car"))
            Action.BACK -> when (c) {
                is AccessibilityService -> c.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                is ComponentActivity -> c.onBackPressedDispatcher.onBackPressed()
                else -> Unit
            }
            Action.NONE -> Unit
        }
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
