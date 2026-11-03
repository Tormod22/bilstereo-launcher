package no.bil.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.widget.Toast

/** En installert app som kan startes fra launcheren. */
data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable,
)

/**
 * En hurtigflis på forsiden. Hver flis har en liste med pakkenavn den prøver i rekkefølge,
 * slik at "Musikk" åpner Spotify hvis det finnes, ellers YouTube Music, ellers innebygd spiller.
 * Rediger denne lista fritt – det er her du tilpasser forsiden.
 */
data class QuickTile(
    val title: String,
    val candidates: List<String>,
    val fallbackIntent: Intent? = null,
)

/** Apper som ikke skal vises i app-skuffen. Legg til pakkenavn her. */
object HiddenApps {
    val packages = setOf(
        "com.android.calculator2",
        "com.google.android.calculator",
        "com.ts.calculator",
        "com.android.settings",          // erstattet av T'OS Innstillinger (finnes under Om stereoen → Avansert)
    )

    /** Apper brukeren har skjult selv (Innstillinger → Apper). */
    fun userHidden(): Set<String> = Prefs.str("hidden_apps").split("\n").filter { it.isNotBlank() }.toSet()
    /** Skjul en synlig app, eller vis en skjult (også de som er skjult som standard, f.eks. kalkulatoren). */
    fun toggle(app: AppEntry) {
        val cur = userHidden().toMutableSet()
        if (isHidden(app)) { cur.remove(app.packageName); Prefs.set("shown_${app.packageName}", true) }
        else { cur.add(app.packageName); Prefs.set("shown_${app.packageName}", false) }
        Prefs.set("hidden_apps", cur.joinToString("\n"))
    }
    val labelKeywords = listOf("kalkulator", "calculator")

    fun isHidden(app: AppEntry): Boolean {
        if (app.packageName in userHidden()) return true
        if (Prefs.bool("shown_${app.packageName}", false)) return false
        return app.packageName in packages || labelKeywords.any { app.label.lowercase().contains(it) }
    }
}

object Tiles {
    fun byPrefix(p: String) = defaults.first { it.title.startsWith(p) }

    val defaults = listOf(
        QuickTile(
            "Navigasjon",
            listOf(
                "com.google.android.apps.maps",
                "com.waze",
                "com.here.app.maps",
                "com.sygic.aura",
            ),
            fallbackIntent = Intent(Intent.ACTION_VIEW).setData(android.net.Uri.parse("geo:0,0")),
        ),
        QuickTile(
            "Musikk",
            listOf(
                "com.spotify.music",
                "com.google.android.apps.youtube.music",
                "com.apple.android.music",
                "com.android.music",
            ),
        ),
        QuickTile(
            "Radio",
            listOf(
                "com.ts.radio",          // vanlig på Unisoc-enheter
                "com.syu.radio",         // FYT/Joying
                "com.android.radio",
                "com.hzbhd.radio",
            ),
        ),
        QuickTile(
            "Telefon",
            listOf(
                "com.ts.bt",             // Bluetooth-telefon på Unisoc
                "com.syu.bt",
                "com.android.dialer",
                "com.google.android.dialer",
            ),
            fallbackIntent = Intent(Intent.ACTION_DIAL),
        ),
        QuickTile(
            "Bluetooth-musikk",
            listOf(                      // gjetninger – sjekk med «adb shell pm list packages» / Lær-knappen
                "com.ts.bt",
                "com.syu.bt",
                "com.microntek.btmusic",
                "com.android.bluetooth",
            ),
        ),
        QuickTile(
            "CarPlay / Android Auto",
            listOf(                      // + søk etter «Car link»/«CarPlay» i navnet (se AppRepository.tilePackage)
                "com.zjinnova.zlink",    // Zlink – det vanligste CarPlay-programmet på disse
                "com.zjinnova.zlink5",
                "com.autokit.carplay",
                "cn.manstep.phonemirrorBox",
                "com.google.android.projection.gearhead",
            ),
        ),
        QuickTile(
            "Ryggekamera",
            listOf(                      // gjetninger – stereoen viser vanligvis kamera selv ved rygging
                "com.ts.camera",
                "com.syu.camera",
                "com.fyt.camera",
                "com.android.camera2",
            ),
        ),
        QuickTile(
            "Innstillinger",
            listOf("com.android.settings"),
            fallbackIntent = Intent(android.provider.Settings.ACTION_SETTINGS),
        ),
    )
}

object AppRepository {

    /** Ord som avslører bil-apper som ikke ligger i app-lista (f.eks. «Car link 2.0»). */
    private val carWords = listOf("carlink", "car link", "carplay", "zlink", "autokit", "android auto", "projection",
        "radio", "bluetooth", "bt music", "btmusic", "camera", "kamera", "avm", "dvr", "video", "music", "tpms", "canbus", "equalizer", "eq")

    /** Hvilken app-type hver flis gjelder, og hva brukeren kan ha valgt selv (Innstillinger). */
    private val tileChoice = mapOf("Mus" to "music_pkg", "Car" to "carplay_pkg", "Rad" to "radio_pkg", "Tel" to "phone_pkg",
        "Rygg" to "camera_pkg", "Blu" to "btmusic_pkg", "Nav" to "nav_app")
    private val tileWords = mapOf(
        "Car" to listOf("carlink", "car link", "carplay", "zlink", "autokit", "cplay"),
        "Rad" to listOf("radio", "fm "),
        "Tel" to listOf("bluetooth", "bt phone", "telefon", "phone", "dialer"),
        "Rygg" to listOf("camera", "kamera", "revers", "avm", "360"),
        "Blu" to listOf("bt music", "btmusic", "a2dp", "bluetooth music", "bluetooth-musikk"),
    )

    /**
     * Startbar måte å åpne en app på: vanlig startikon, ellers første «hoved»-skjerm i appen.
     * (Idé fra OpenLauncher: CarPlay-apper på stereoer har ofte ikke startikon.)
     */
    fun launchIntent(context: Context, pkg: String): Intent? {
        val pm = context.packageManager
        return pm.getLaunchIntentForPackage(pkg)
            ?: runCatching {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).setPackage(pkg), 0)
                    .firstOrNull { it.activityInfo.exported }?.activityInfo
                    ?.let { Intent(Intent.ACTION_MAIN).setClassName(it.packageName, it.name) }
            }.getOrNull()
    }

    fun installedApps(context: Context, includeHidden: Boolean = false): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val normal = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { AppEntry(label = it.loadLabel(pm).toString(), packageName = it.activityInfo.packageName, icon = it.loadIcon(pm)) }
        val known = normal.map { it.packageName }.toSet()
        // Bil-apper uten startikon (CarPlay/Car link, radio, kamera …) – ellers usynlige
        val hidden = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
            .asSequence()
            .filter { it.packageName !in known && it.packageName != context.packageName }
            .mapNotNull { ai ->
                val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrNull() ?: return@mapNotNull null
                val hay = (label + " " + ai.packageName).lowercase()
                if (carWords.none { it in hay }) return@mapNotNull null
                if (launchIntent(context, ai.packageName) == null) return@mapNotNull null
                AppEntry(label, ai.packageName, pm.getApplicationIcon(ai))
            }.toList()
        return (normal + hidden)
            .distinctBy { it.packageName }
            .filterNot { !includeHidden && HiddenApps.isHidden(it) }
            .sortedBy { it.label.lowercase() }
    }

    fun isInstalled(context: Context, packageName: String): Boolean = launchIntent(context, packageName) != null

    /** Apper T'OS selv nettopp åpnet (pakke → tid). Knappestyringen skal ikke sende deg tilbake fra dem. */
    private val selfLaunched = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun launchedByUs(pkg: String, withinMs: Long = 3000): Boolean =
        selfLaunched[pkg]?.let { android.os.SystemClock.elapsedRealtime() - it < withinMs } == true

    fun launch(context: Context, packageName: String) {
        selfLaunched[packageName] = android.os.SystemClock.elapsedRealtime()
        val intent = launchIntent(context, packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure { Toast.makeText(context, "Fikk ikke åpnet appen", Toast.LENGTH_SHORT).show() }
        } else {
            Toast.makeText(context, "Fant ikke appen", Toast.LENGTH_SHORT).show()
        }
    }

    /** Appen en flis åpner: brukerens valg → kjente pakkenavn → søk på navnet (f.eks. «Car link 2.0»). */
    fun tilePackage(context: Context, tile: QuickTile): String? {
        val prefix = tileChoice.keys.firstOrNull { tile.title.startsWith(it) }
        prefix?.let { p -> Prefs.str(tileChoice.getValue(p)).takeIf { it.isNotBlank() && isInstalled(context, it) }?.let { return it } }
        tile.candidates.firstOrNull { isInstalled(context, it) }?.let { return it }
        val words = tileWords[prefix] ?: return null
        return findByWords(context, prefix!!, words)
    }

    /** Navnesøk uten ikoner, husket i 5 minutter (brukes ved knappetrykk – må gå raskt). */
    private val wordCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String?>>()
    private fun findByWords(context: Context, key: String, words: List<String>): String? {
        val now = android.os.SystemClock.elapsedRealtime()
        wordCache[key]?.let { (t, pkg) -> if (now - t < 300_000) return pkg }
        val pm = context.packageManager
        val found = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
            .firstOrNull { ai ->
                if (ai.packageName == context.packageName) return@firstOrNull false
                val hay = ((runCatching { pm.getApplicationLabel(ai).toString() }.getOrNull() ?: "") + " " + ai.packageName).lowercase()
                words.any { it in hay } && launchIntent(context, ai.packageName) != null
            }?.packageName
        wordCache[key] = now to found
        return found
    }

    /** Åpner appen for flisa, ellers fallback-intent. */
    fun launchTile(context: Context, tile: QuickTile) {
        val pkg = tilePackage(context, tile)
        when {
            pkg != null -> launch(context, pkg)
            tile.fallbackIntent != null -> runCatching {
                context.startActivity(tile.fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                Toast.makeText(context, "Ingen app for ${tile.title}", Toast.LENGTH_SHORT).show()
            }
            else -> Toast.makeText(context, "Ingen app for ${tile.title}. Velg den under Innstillinger → Apper.", Toast.LENGTH_LONG).show()
        }
    }

    /** Ikon for appen flisa åpner. */
    fun tileIcon(context: Context, tile: QuickTile): Drawable? {
        val pkg = tilePackage(context, tile) ?: return null
        return runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
    }
}
