package no.bil.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.launch

/** En installert app som kan startes fra launcheren. */
data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    /** Stereoens egen «skall»-app (original startskjerm, radio, BT, musikk, fabrikkoppsett …) – skjult som standard. */
    val shell: Boolean = false,
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
    )

    /**
     * «Vis systemapper» (Innstillinger → Apper): viser stereoens egne apper som ellers er skjult fordi T'OS gjør
     * det samme (original startskjerm, radio, Bluetooth/telefon, musikk, video, filer, galleri, innstillinger,
     * fabrikk- og oppdateringsapper).
     */
    val showSystem get() = Prefs.bool("show_system_apps", false)

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
        if (app.packageName in packages || labelKeywords.any { app.label.lowercase().contains(it) }) return true
        return app.shell && !showSystem
    }
}

/** Stereoens egne apper som T'OS erstatter (pakkenavn). Gjetninger for vanlige kinesiske stereoer – utvid etter oppsettrapporten. */
internal val stockShellPackages = setOf(
    "com.android.settings", "com.android.launcher", "com.android.launcher2", "com.android.launcher3", "com.android.quicksearchbox",
    "com.ts.radio", "com.syu.radio", "com.android.radio", "com.hzbhd.radio", "com.android.fmradio", "com.caf.fmradio",
    "com.ts.bt", "com.syu.bt", "com.microntek.bluetooth", "com.microntek.btmusic", "com.microntek.radio", "com.microntek.music",
    "com.android.music", "com.android.gallery3d", "com.android.documentsui", "com.android.deskclock", "com.android.soundrecorder",
    "com.ts.music", "com.ts.video", "com.ts.filemanager", "com.ts.launcher", "com.syu.music", "com.syu.video", "com.syu.launcher",
    "com.syu.filemanager", "com.syu.gallery", "com.fyt.launcher", "com.hzbhd.launcher", "com.android.car.launcher",
    // FYT (bekreftet i kartleggingen 8. nov): startskjermene, hjelp, appbutikk, Navi-snarveien, innstillinger, AUX, EQ, TV, bil-innstillinger
    "com.android.launcher4", "com.android.launcher9", "com.android.launcher24", "com.android.launcher35", "com.android.launcher36",
    "com.syu.guide", "com.syu.market", "com.syu.onekeynavi", "com.syu.settings", "com.syu.av", "com.syu.eq", "com.syu.tv",
    "com.syu.canbus", "com.android.traceur",
)

/** Pakkenavn-starter for stereoprodusentens egne hjelpeapper (FYT-knapper, Unisoc/Spreadtrum, Toppal-stemmen). */
internal val stockShellPrefixes = listOf("com.fyt.", "com.sprd.", "com.unisoc.", "com.txznet.")

/** CarPlay/Android Auto-apper (Car link, Zlink …): aldri skjult, og aldri byttet ut med T'OS. */
internal val projectionWords = listOf("carlink", "car link", "carplay", "zlink", "autokit", "android auto", "gearhead", "projection",
    "phonemirror", "cplay")

/** Er dette en CarPlay/Android Auto-app? ([text] = pakkenavn og/eller navn.) */
internal fun isProjectionApp(text: String): Boolean = text.lowercase().let { t -> projectionWords.any { it in t } }

/** Apper som alltid skal vises selv om de er systemapper (CarPlay/Car link, kart, musikk-tjenester, nettleser, Play). */
private val keepWords = projectionWords + listOf(
    "maps", "waze", "spotify", "youtube", "google", "chrome", "browser", "nettleser", "vending", "play store", "netflix", "tidal",
    "deezer", "apple", "amazon", "nrk", "tunein", "dvr", "tpms", "camera", "kamera", "steering", "swc", "ratt")

/** Ord (hele ord i navn eller pakkenavn) som avslører stereoens «skall»-apper. */
private val shellTokens = setOf("launcher", "launcher3", "home", "carhome", "desktop", "mainui", "mainmenu",
    "radio", "fmradio", "fm", "bt", "btphone", "btmusic", "bluetooth", "dialer", "music", "musikk", "video", "videos", "videoplayer",
    "gallery", "galleri", "gallery3d", "photo", "photos", "bilder", "filemanager", "filbehandler", "files", "documentsui",
    "settings", "innstillinger", "carsettings", "factory", "fabrikk", "factorytest", "engineer", "engmode", "aging",
    "update", "updater", "upgrade", "ota", "fota", "oppdatering", "systemupdate", "equalizer", "eq", "avin", "aux", "deskclock",
    "soundrecorder", "quicksearchbox", "usb", "sd")

/** Uttrykk med flere ord i appnavnet. */
private val shellPhrases = listOf("car home", "main menu", "bt music", "bt phone", "file manager", "media player",
    "music player", "video player", "system update", "systemoppdatering", "car settings")

/**
 * Er appen stereoens egen «skall»-app som T'OS erstatter? Bare systemapper (forhåndsinstallert) regnes med.
 * Kjente pakkenavn, en startskjerm-app, eller navn/pakkenavn med typiske ord (radio, Bluetooth, musikk, video,
 * filer, galleri, innstillinger, fabrikk, oppdatering …). Car link/CarPlay, kart, Spotify, YouTube osv. vises alltid.
 * Testes i ServiceLogicTest3.
 */
internal fun isStockShellApp(pkg: String, label: String, isSystem: Boolean, isHome: Boolean, ownPkg: String = "no.bil.launcher"): Boolean {
    if (pkg == ownPkg || !isSystem) return false
    val hay = (label + " " + pkg).lowercase()
    if (keepWords.any { it in hay }) return false
    if (pkg in stockShellPackages || isHome || stockShellPrefixes.any { pkg.startsWith(it) }) return true
    // Nettradio-apper (NRK, TuneIn …) er ikke stereoens FM-radio
    if (internetRadioWords.any { it in pkg.lowercase() }) return false
    val tokens = hay.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    if (tokens.any { it in shellTokens }) return true
    val l = label.lowercase()
    return shellPhrases.any { it in l }
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
                "com.syu.music",         // FYT sin musikkspiller (siste valg)
            ),
        ),
        QuickTile(
            "Radio",
            listOf(
                "com.syu.radio",         // FYT (brukerens stereo – bekreftet 8. nov)
                "com.ts.radio",          // vanlig på Unisoc-enheter
                "com.android.radio",
                "com.hzbhd.radio",
            ),
        ),
        QuickTile(
            "Telefon",
            listOf(
                "com.syu.bt",            // FYT (bekreftet 8. nov)
                "com.ts.bt",             // Bluetooth-telefon på Unisoc
                "com.android.dialer",
                "com.google.android.dialer",
            ),
            fallbackIntent = Intent(Intent.ACTION_DIAL),
        ),
        QuickTile(
            "Bluetooth-musikk",
            listOf(                      // com.syu.bt bekreftet (FYT); resten gjetninger
                "com.syu.bt",
                "com.ts.bt",
                "com.microntek.btmusic",
                "com.android.bluetooth",
            ),
        ),
        QuickTile(
            "CarPlay / Android Auto",
            listOf(                      // + søk etter «Car link»/«CarPlay» i navnet (se AppRepository.tilePackage)
                "com.syu.carlink",       // «Car Link 2.0» på FYT (brukerens CarPlay – bekreftet 8. nov)
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

    /** Er appen forhåndsinstallert på stereoen (systemapp, også når den er oppdatert)? */
    fun isSystemApp(ai: android.content.pm.ApplicationInfo?): Boolean =
        ai != null && (ai.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

    /** Pakkene som har en startskjerm (stereoens originale startskjerm m.fl.), uten T'OS. */
    fun homePackages(context: Context): Set<String> = runCatching {
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.filter { it != context.packageName }.toSet()
    }.getOrDefault(emptySet())

    fun installedApps(context: Context, includeHidden: Boolean = false): List<AppEntry> {
        val pm = context.packageManager
        val homes = homePackages(context)
        fun shell(ai: android.content.pm.ApplicationInfo?, pkg: String, label: String) =
            isStockShellApp(pkg, label, isSystemApp(ai), pkg in homes, context.packageName)
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val normal = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                val label = it.loadLabel(pm).toString()
                val pkg = it.activityInfo.packageName
                AppEntry(label = label, packageName = pkg, icon = it.loadIcon(pm), shell = shell(it.activityInfo.applicationInfo, pkg, label))
            }
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
                AppEntry(label, ai.packageName, pm.getApplicationIcon(ai), shell = shell(ai, ai.packageName, label))
            }.toList()
        return (normal + hidden)
            .distinctBy { it.packageName }
            .filterNot { !includeHidden && HiddenApps.isHidden(it) }
            .sortedBy { it.label.lowercase() }
    }

    fun isInstalled(context: Context, packageName: String): Boolean = launchIntent(context, packageName) != null

    /**
     * Apper T'OS selv nettopp åpnet (pakke → tid). Knappestyringen skal ikke sende deg tilbake fra dem.
     * 8 s fordi treg kaldstart (CarPlay, radio) på stereoen kan ta flere sekunder; merket brukes opp når appen
     * kommer fram ([forgetLaunched]), så et senere trykk på stereoens knapp ikke regnes som T'OS sitt.
     */
    private val selfLaunched = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun launchedByUs(pkg: String, withinMs: Long = 8000): Boolean =
        selfLaunched[pkg]?.let { android.os.SystemClock.elapsedRealtime() - it < withinMs } == true

    /** Appen T'OS åpnet har kommet fram – merket trengs ikke lenger. */
    fun forgetLaunched(pkg: String) { selfLaunched.remove(pkg) }

    /** Merk at T'OS selv åpner [pkg] (så KeyService ikke sender brukeren tilbake). */
    fun markLaunched(pkg: String) { selfLaunched[pkg] = android.os.SystemClock.elapsedRealtime() }

    fun launch(context: Context, packageName: String) {
        val intent = launchIntent(context, packageName)
        if (intent != null) {
            markLaunched(packageName)
            RecentApps.record(context, packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure { selfLaunched.remove(packageName); Notify.show("Fikk ikke åpnet appen") }
        } else {
            Notify.show("Fant ikke appen")
        }
    }

    /** Appen en flis åpner: brukerens valg → kjente pakkenavn → søk på navnet (f.eks. «Car link 2.0»). */
    fun tilePackage(context: Context, tile: QuickTile): String? {
        val prefix = tileChoice.keys.firstOrNull { tile.title.startsWith(it) }
        prefix?.let { p -> Prefs.str(tileChoice.getValue(p)).takeIf { it.isNotBlank() && isInstalled(context, it) }?.let { return it } }
        tile.candidates.firstOrNull { isInstalled(context, it) }?.let { return it }
        val words = tileWords[prefix] ?: return null
        // Radio-flisa er stereoens FM/AM-radio: nettradio-apper (NRK Radio, TuneIn …) skal ikke velges
        return findByWords(context, words, skip = if (prefix == "Rad") internetRadioWords else emptyList())
    }

    /**
     * Navnesøk uten ikoner (brukes ved knappetrykk – må gå raskt). Navnene på alle apper leses i bakgrunnen
     * ([warm], ved oppstart av knappetjenesten og første bruk) og leses på nytt når en app installeres/fjernes
     * ([invalidate]) eller etter 10 minutter. Selve svaret huskes ikke, så en app som installeres senere blir funnet.
     */
    private class NameIndex(val at: Long, val entries: List<Pair<String, String>>)   // pakke til «navn pakke» (små bokstaver)
    @Volatile private var nameIndex: NameIndex? = null
    private val warming = java.util.concurrent.atomic.AtomicBoolean(false)
    private val io = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private fun buildIndex(context: Context): NameIndex {
        val pm = context.packageManager
        val list = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
            .filter { it.packageName != context.packageName }
            .map { ai -> ai.packageName to ((runCatching { pm.getApplicationLabel(ai).toString() }.getOrNull() ?: "") + " " + ai.packageName).lowercase() }
        return NameIndex(android.os.SystemClock.elapsedRealtime(), list)
    }

    /** Les app-navnene i bakgrunnen (ved oppstart, og når noe er installert/fjernet). */
    fun warm(context: Context) {
        val app = context.applicationContext
        if (!warming.compareAndSet(false, true)) return
        io.launch {
            try { nameIndex = buildIndex(app) } catch (_: Exception) {} finally { warming.set(false) }
        }
    }

    /** En app er installert, oppdatert eller fjernet: les navnene på nytt (de gamle brukes til de nye er klare). */
    fun invalidate(context: Context) { warm(context) }

    private fun findByWords(context: Context, words: List<String>, skip: List<String> = emptyList()): String? {
        var idx = nameIndex
        if (idx == null) {
            // Første gang (bakgrunnslesingen er ikke ferdig): les nå, én gang. Senere brukes lista som finnes.
            idx = buildIndex(context).also { nameIndex = it }
        } else if (android.os.SystemClock.elapsedRealtime() - idx.at > 600_000) {
            warm(context)
        }
        return idx.entries.firstOrNull { (pkg, hay) -> words.any { it in hay } && skip.none { it in hay } && launchIntent(context, pkg) != null }?.first
    }

    /** Åpner appen for flisa, ellers fallback-intent. */
    fun launchTile(context: Context, tile: QuickTile) {
        val pkg = tilePackage(context, tile)
        when {
            pkg != null -> launch(context, pkg)
            tile.fallbackIntent != null -> runCatching {
                val i = Intent(tile.fallbackIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Appen som svarer (f.eks. stereoens egen kartapp) er også åpnet av T'OS – ikke send brukeren tilbake fra den
                runCatching { i.resolveActivity(context.packageManager)?.packageName }.getOrNull()?.let { markLaunched(it) }
                context.startActivity(i)
            }.onFailure {
                Notify.show("Ingen app for ${tile.title}")
            }
            else -> Notify.show("Ingen app for ${tile.title}. Velg den under Innstillinger → Apper.", long = true)
        }
    }

    /** Ikon for appen flisa åpner. */
    fun tileIcon(context: Context, tile: QuickTile): Drawable? {
        val pkg = tilePackage(context, tile) ?: return null
        return runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
    }
}
