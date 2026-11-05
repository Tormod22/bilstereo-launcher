package no.bil.launcher

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * «Siste apper» i T'OS-stil. Stereoens egen fanevisning (nylige apper / Overview) er en del av Android
 * (SystemUI / Quickstep) og kan ikke tegnes om av en vanlig app. Knappestyringen (KeyService) ser når den
 * dukker opp, legger et svart teppe over og viser denne skjermen i stedet.
 */

/** En app i «Siste apper»: pakkenavn og når den sist var framme (klokkeslett i ms, System.currentTimeMillis). */
internal data class RecentUse(val pkg: String, val at: Long)

/** Ren logikk for «Siste apper» (testes i RecentsLogicTest). */
internal object RecentsLogic {
    /** Så mange apper huskes og vises. */
    const val MAX = 20
    /** Så langt tilbake vises apper (12 timer). */
    const val WINDOW_MS = 12 * 3_600_000L

    /** Legg [pkg] først i lista (flyttes hvis den finnes fra før). */
    fun add(list: List<RecentUse>, pkg: String, at: Long, max: Int = MAX): List<RecentUse> =
        (listOf(RecentUse(pkg, at)) + list.filter { it.pkg != pkg }).take(max)

    /** «pakke|tid» per linje. Ødelagte linjer hoppes over. */
    fun parse(s: String): List<RecentUse> = s.lineSequence().mapNotNull { l ->
        val p = l.substringBefore('|').trim()
        val t = l.substringAfter('|', "").trim().toLongOrNull()
        if (p.isEmpty() || t == null) null else RecentUse(p, t)
    }.toList()

    fun format(list: List<RecentUse>): String = list.joinToString("\n") { "${it.pkg}|${it.at}" }

    /**
     * Slå sammen kildene (Androids appbruk og T'OS sin egen liste): nyeste tid per app, nyeste først, bare siste
     * [WINDOW_MS], ikke apper som er lukket i T'OS etter at de sist ble brukt ([dismissed]), og ikke [exclude].
     */
    fun merge(sources: List<List<RecentUse>>, dismissed: Map<String, Long>, now: Long, exclude: (String) -> Boolean, max: Int = MAX): List<RecentUse> =
        sources.flatten()
            .groupBy { it.pkg }
            .map { (pkg, uses) -> RecentUse(pkg, uses.maxOf { it.at }) }
            .filter { it.at >= now - WINDOW_MS }
            .filter { u -> dismissed[u.pkg]?.let { u.at > it } ?: true }
            .filterNot { exclude(it.pkg) }
            .sortedByDescending { it.at }
            .take(max)

    /** Ord i pakkenavnet som avslører startskjermer og systemvinduer. */
    private val shellWords = listOf("launcher", "quickstep", "systemui", "recents", "inputmethod", "keyboard")

    /**
     * Hører ikke hjemme i «Siste apper»: T'OS selv, startskjermer, systemvinduer, Android-innstillingene (T'OS har
     * egne), apper som er skjult i app-lista, og stereoens egne «skall»-apper (radio, BT, filer …) som T'OS erstatter.
     */
    fun excluded(pkg: String, ownPkg: String, isHome: Boolean, hidden: Boolean, shell: Boolean): Boolean {
        if (pkg == ownPkg || isHome || hidden || shell) return true
        if (pkg in protectedShellPkgs) return true
        val p = pkg.lowercase()
        return shellWords.any { it in p }
    }

    /**
     * «Lukk alle»: hvilke apper lukkes, og hvilke beholdes. Beholdes: T'OS, CarPlay/Car link (telefonen er koblet
     * til gjennom den) og musikkappen som spiller nå. [apps] = pakkenavn til navn.
     */
    fun closeAllPlan(apps: List<Pair<String, String>>, ownPkg: String, playingPkg: String?): Pair<List<String>, List<String>> {
        val (keep, close) = apps.partition { (pkg, label) -> pkg == ownPkg || pkg == playingPkg || isProjectionApp("$pkg $label") }
        return close.map { it.first } to keep.map { it.first }
    }

    /** Meldingen etter «Lukk alle»: hvor mange som ble lukket, og hvilke som fikk være (med navn). */
    fun closeAllMessage(closed: Int, keptLabels: List<String>): String {
        val first = when (closed) {
            0 -> "Ingen apper å lukke"
            1 -> "Lukket 1 app"
            else -> "Lukket $closed apper"
        }
        if (keptLabels.isEmpty()) return first
        val names = if (keptLabels.size == 1) keptLabels[0]
        else keptLabels.dropLast(1).joinToString(", ") + " og " + keptLabels.last()
        return "$first. Beholdt $names."
    }

    /** «akkurat nå», «5 min siden», «2 t siden». */
    fun ago(now: Long, at: Long): String {
        val d = (now - at).coerceAtLeast(0L)
        return when {
            d < 60_000 -> "akkurat nå"
            d < 3_600_000 -> "${d / 60_000} min siden"
            d < 86_400_000 -> "${d / 3_600_000} t siden"
            else -> "over et døgn siden"
        }
    }

    /** Fullstendige klassenavn på Androids fanevisning (nylige apper). */
    private val recentsClasses = setOf(
        "com.android.quickstep.RecentsActivity",
        "com.android.systemui.recents.RecentsActivity",
        "com.android.systemui.recents.RecentsImpl",
        "com.android.launcher3.uioverrides.RecentsActivity",
        "com.android.quickstep.fallback.RecentsActivity",
    )
    /** Tekst (vindustittel/beskrivelse) som fanevisningen sender. Små bokstaver. */
    private val recentsPhrases = listOf("no recent items", "ingen nylige elementer", "ingen nylige apper", "recent apps", "nylige apper",
        "recent items", "nylige elementer")
    private val recentsWords = setOf("overview", "oversikt", "recents", "recent", "nylige")

    /**
     * Er vinduet som kom fram Androids fanevisning (nylige apper)? Klassenavn som RecentsActivity/Overview, eller
     * tekst som «No recent items»/«Ingen nylige elementer»/«Overview» – men bare fra startskjermen, SystemUI eller
     * pakker med «launcher/quickstep/recents» i navnet (en musikkapp kan godt ha en skjerm som heter «Recents»).
     */
    fun isStockRecents(pkg: String, cls: String?, texts: List<String>, isHomePkg: Boolean, ownPkg: String): Boolean {
        if (pkg == ownPkg) return false
        if (cls != null && cls in recentsClasses) return true
        val p = pkg.lowercase()
        val systemish = isHomePkg || p == "com.android.systemui" || listOf("launcher", "quickstep", "recents", "systemui").any { it in p }
        if (!systemish) return false
        val c = cls?.substringAfterLast('.')?.lowercase().orEmpty()
        // «RecentsActivity», «RecentsView», «OverviewActivity» … (men ikke dialoger o.l. som bare har ordet med)
        if (("recents" in c || "overview" in c) && "dialog" !in c) return true
        return texts.any { raw ->
            val t = raw.trim().lowercase().trimEnd('.', '!')
            t.isNotEmpty() && (recentsPhrases.any { it in t } || t in recentsWords)
        }
    }
}

/** Én app i lista på skjermen. */
data class RecentItem(val pkg: String, val label: String, val icon: Drawable, val at: Long)

/**
 * «Siste apper»: Androids appbruk (UsageStatsManager, krever «Tilgang til appbruk»), og T'OS sin egen liste over
 * apper som har vært framme (knappestyringen) eller som T'OS åpnet. Uten tilgangen vises bare T'OS sin egen liste.
 */
object RecentApps {
    private const val KEY = "recent_apps"
    private const val KEY_DISMISSED = "recent_dismissed"
    /** Øker når lista endres (en app lukket) – skjermen leser den på nytt. */
    var version by mutableIntStateOf(0)
        private set

    @Volatile private var own: List<RecentUse>? = null
    private val lock = Any()

    private fun ownList(): List<RecentUse> = own ?: RecentsLogic.parse(runCatching { Prefs.str(KEY) }.getOrDefault("")).also { own = it }

    /** En app kom fram (knappestyringen) eller ble åpnet av T'OS. Billig: ingen oppslag, bare lista oppdateres. */
    fun record(c: Context, pkg: String) {
        if (pkg == c.packageName || pkg in protectedShellPkgs) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val cur = ownList()
            // Samme app først i lista nylig: ikke skriv på nytt for hver skjerm inne i appen
            if (cur.firstOrNull()?.let { it.pkg == pkg && now - it.at in 0..30_000 } == true) return
            val next = RecentsLogic.add(cur, pkg, now, max = 30)
            own = next
            runCatching { Prefs.setQuiet(KEY, RecentsLogic.format(next)) }
        }
    }

    private fun dismissed(): Map<String, Long> =
        RecentsLogic.parse(runCatching { Prefs.str(KEY_DISMISSED) }.getOrDefault("")).associate { it.pkg to it.at }

    /** Lukket i T'OS: vises ikke før appen brukes igjen. */
    private fun dismiss(pkgs: Collection<String>) {
        val now = System.currentTimeMillis()
        val cur = dismissed().filterValues { now - it < 2 * RecentsLogic.WINDOW_MS }.toMutableMap()
        pkgs.forEach { cur[it] = now }
        runCatching { Prefs.setQuiet(KEY_DISMISSED, RecentsLogic.format(cur.map { RecentUse(it.key, it.value) })) }
        version++
    }

    /** Har T'OS fått «Tilgang til appbruk» (Innstillinger, eller ADB: appops set … GET_USAGE_STATS allow)? */
    fun hasUsageAccess(c: Context): Boolean = runCatching {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        if (mode == AppOpsManager.MODE_DEFAULT) c.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        else mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Androids side for «Tilgang til appbruk» (kan ikke unngås uten PC). */
    fun openUsageAccess(c: Context) {
        val tries = listOf(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${c.packageName}")),
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
        )
        for (i in tries) if (runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        Notify.show("Fant ikke innstillingen – bruk PC (Førstegangsoppsett → Med PC)", long = true)
    }

    /** Apper som har vært framme de siste 12 timene, ifølge Android. Tom uten tilgang. */
    private fun usage(c: Context, now: Long): List<RecentUse> {
        if (!hasUsageAccess(c)) return emptyList()
        val um = c.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyList()
        val events = runCatching { um.queryEvents(now - RecentsLogic.WINDOW_MS, now) }.getOrNull() ?: return emptyList()
        val resumed = if (Build.VERSION.SDK_INT >= 29) UsageEvents.Event.ACTIVITY_RESUMED else @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_FOREGROUND
        val last = HashMap<String, Long>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(e)) break
            if (e.eventType == resumed) last[e.packageName] = maxOf(last[e.packageName] ?: 0L, e.timeStamp)
        }
        return last.map { RecentUse(it.key, it.value) }
    }

    /** Lista som vises (leses i bakgrunnen: pakkeoppslag og ikoner). */
    fun load(c: Context): List<RecentItem> {
        val pm = c.packageManager
        val now = System.currentTimeMillis()
        val homes = AppRepository.homePackages(c)
        val info = HashMap<String, Pair<String, Drawable>?>()
        fun lookup(pkg: String): Pair<String, Drawable>? = info.getOrPut(pkg) {
            runCatching {
                val ai = pm.getApplicationInfo(pkg, 0)
                if (AppRepository.launchIntent(c, pkg) == null) return@runCatching null
                val label = pm.getApplicationLabel(ai).toString()
                val icon = pm.getApplicationIcon(ai)
                val shell = isStockShellApp(pkg, label, AppRepository.isSystemApp(ai), pkg in homes, c.packageName) || RadioGuard.looksLikeRadio(pkg)
                val hidden = HiddenApps.isHidden(AppEntry(label, pkg, icon, shell))
                if (RecentsLogic.excluded(pkg, c.packageName, pkg in homes, hidden, shell)) null else label to icon
            }.getOrNull()
        }
        val list = RecentsLogic.merge(listOf(usage(c, now), synchronized(lock) { ownList() }), dismissed(), now, exclude = { lookup(it) == null })
        return list.mapNotNull { u -> lookup(u.pkg)?.let { (label, icon) -> RecentItem(u.pkg, label, icon, u.at) } }
    }

    /** Lukk én app i bakgrunnen (som når Android rydder minne). Appen som er framme eller spiller kan starte igjen selv. */
    fun close(c: Context, item: RecentItem) {
        kill(c, item.pkg)
        dismiss(listOf(item.pkg))
        Notify.show("Lukket ${item.label}")
    }

    /** «Lukk alle»: alt unntatt T'OS, CarPlay/Car link og musikken som spiller. Sier fra hvilke som fikk være. */
    fun closeAll(c: Context, items: List<RecentItem>) {
        val playing = NowPlaying.pkg.takeIf { NowPlaying.playing }
        val (close, keep) = RecentsLogic.closeAllPlan(items.map { it.pkg to it.label }, c.packageName, playing)
        close.forEach { kill(c, it) }
        dismiss(close)
        val labels = keep.mapNotNull { p -> items.firstOrNull { it.pkg == p }?.label?.let { if (p == playing) "$it (spiller)" else it } }
        Notify.show(RecentsLogic.closeAllMessage(close.size, labels), long = labels.isNotEmpty())
    }

    private fun kill(c: Context, pkg: String) {
        runCatching { (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg) }
    }
}

/* ======================= Skjermen ======================= */

@Composable
fun RecentsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val resume = rememberResumeTick()
    var loaded by remember { mutableStateOf(false) }
    val items by produceState(emptyList<RecentItem>(), RecentApps.version, resume) {
        value = withContext(Dispatchers.IO) { runCatching { RecentApps.load(context.applicationContext) }.getOrDefault(emptyList()) }
        loaded = true
    }
    val access = remember(resume) { RecentApps.hasUsageAccess(context) }
    RecentsContent(
        items = items, loaded = loaded, usageAccess = access, onClose = onClose,
        onOpen = { AppRepository.launch(context, it.pkg) },
        onCloseApp = { RecentApps.close(context, it) },
        onCloseAll = { RecentApps.closeAll(context, items) },
        onAskAccess = { RecentApps.openUsageAccess(context) },
    )
}

/** Selve innholdet (uten datakilder, så det kan tegnes i røyktestene med påfunne apper). */
@Composable
internal fun RecentsContent(
    items: List<RecentItem>, loaded: Boolean, usageAccess: Boolean, onClose: () -> Unit,
    onOpen: (RecentItem) -> Unit, onCloseApp: (RecentItem) -> Unit, onCloseAll: () -> Unit, onAskAccess: () -> Unit,
) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(4000); armed = false } }
    // «5 min siden» oppdateres av seg selv
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }

    Page("Siste apper", onClose, extra = {
        if (items.isNotEmpty()) {
            Pill(if (armed) "Trykk igjen for å lukke alle" else "Lukk alle", Icons.Default.ClearAll, strong = armed) {
                if (armed) { armed = false; onCloseAll() } else armed = true
            }
            Spacer(Modifier.width(12.dp))
        }
    }) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    !loaded -> Text("Henter appene …", color = TextMuted, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center))
                    items.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.History, null, tint = TextMuted, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Ingen apper i bakgrunnen", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text("Apper du åpner, dukker opp her.", color = TextMuted, fontSize = 15.sp)
                    }
                    else -> LazyRow(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        items(items, key = { it.pkg }) { app -> RecentCard(app, now, { onOpen(app) }, { onCloseApp(app) }) }
                    }
                }
            }
            if (!usageAccess) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(18.dp)).background(Card)
                        .clickable(onClickLabel = "Gi tilgang") { onAskAccess() }.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(Icons.Default.Info, null, tint = TextMuted, modifier = Modifier.size(22.dp))
                    Text("Gi T'OS tilgang til appbruk for en mer komplett liste", color = TextPrimary, fontSize = 15.sp,
                        modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
                }
            }
        }
    }
}

@Composable
private fun RecentCard(app: RecentItem, now: Long, onOpen: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.width(210.dp).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(Card).clickable(onClickLabel = "Åpne") { onOpen() }) {
        Column(Modifier.fillMaxSize().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AppIcon(app.icon, 84.dp, app.pkg)
            Spacer(Modifier.height(16.dp))
            Text(app.label, color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Medium, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text("sist brukt ${RecentsLogic.ago(now, app.at)}", color = TextMuted, fontSize = 14.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { RoundIcon(Icons.Default.Close, 48.dp, desc = "Lukk ${app.label}") { onClose() } }
    }
}
