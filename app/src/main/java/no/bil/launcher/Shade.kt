package no.bil.launcher

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Nedtrekksmenyen i T'OS-stil. Stereoens egen nedtrekksmeny (varsler og hurtiginnstillinger) er en del av Android
 * (SystemUI) og kan ikke fjernes av en vanlig app. Knappestyringen (KeyService) ser når den trekkes ned, lukker den
 * og viser T'OS sitt panel i stedet: inne i T'OS som hurtigpanelet, ellers som et T'OS-vindu over appen som er åpen.
 */

/* ======================= Gjenkjenne stereoens nedtrekksmeny (ren logikk, testes i ShadeSurveyTest) ======================= */

/** Et vindu på skjermen slik knappestyringen ser det (fra AccessibilityService.getWindows). */
internal data class WinSnap(
    val type: Int, val title: String?, val pkg: String?, val left: Int, val top: Int, val right: Int, val bottom: Int,
    val layer: Int = 0, val active: Boolean = false, val focused: Boolean = false,
) {
    val width get() = right - left
    val height get() = bottom - top
}

internal object ShadeLogic {
    /** AccessibilityWindowInfo.TYPE_APPLICATION / TYPE_SYSTEM (faste tall i Android). */
    const val TYPE_APPLICATION = 1
    const val TYPE_SYSTEM = 3

    /** Tekst (vindustittel) nedtrekksmenyen sender. Små bokstaver. */
    private val phrases = listOf("notification shade", "notifications shade", "quick settings", "notification panel",
        "hurtiginnstillinger", "varselskuff", "varslingsskuff", "varselpanel", "varslingspanel", "varselskuffen", "varselskygge", "varslingsområde",
        "通知栏", "通知面板", "下拉通知", "快捷设置", "快速设置")
    /** Klassenavn / vindusnavn (uten mellomrom) i nedtrekksmenyen. */
    private val classWords = listOf("notificationshade", "notificationpanel", "quicksetting", "qspanel", "qscontainer", "qsfragment",
        "expandednotification", "statusbarexpanded")
    /** Andre SystemUI-vinduer som aldri skal lukkes: volum, fanevisning, strømmeny, skjermbilde, låseskjerm. */
    private val notShade = listOf("volume", "volum", "recent", "overview", "oversikt", "globalaction", "global action", "power", "strøm",
        "screenshot", "skjermbilde", "keyguard", "lock screen", "låseskjerm", "navigation bar", "navigasjonsrad", "navigationbar")

    /** Vindustittelen til statuslinja på Android 10 og eldre (uten mellomrom, små bokstaver). */
    private val statusBarTitles = setOf("statusbar", "notificationshadewindow")

    enum class CloseWay { DISMISS_SHADE, COLLAPSE_OR_BACK }

    /** Uten hint fra menyen selv, og et varsel kom for under 2,5 s siden: trolig et varsel øverst (heads-up), ikke menyen. */
    fun maybeHeadsUp(hint: Boolean, msSinceNotification: Long): Boolean = !hint && msSinceNotification in 0..2500

    /**
     * Hvordan stereoens meny lukkes. Android 12+: egen handling. Eldre (brukerens stereo er Android 10, SDK 29, selv om den
     * sier «Android 16»): StatusBarManager.collapsePanels (skjult, krever EXPAND_STATUS_BAR), ellers tilbake-knappen.
     */
    fun closeWay(sdk: Int): CloseWay = if (sdk >= 31) CloseWay.DISMISS_SHADE else CloseWay.COLLAPSE_OR_BACK

    /** SystemUI (Androids statuslinje/nedtrekksmeny), også stereoprodusentens egen variant. */
    fun isSystemUi(pkg: String?): Boolean {
        val p = pkg?.lowercase() ?: return false
        return p == "com.android.systemui" || "systemui" in p
    }

    private fun squash(s: String) = s.lowercase().replace(" ", "").replace("_", "")

    /** Sier hendelsen (pakke, klasse, tekst) at nedtrekksmenyen kom fram? */
    fun isShadeEvent(pkg: String?, cls: String?, texts: List<String>): Boolean {
        if (!isSystemUi(pkg)) return false
        val c = cls?.lowercase().orEmpty()
        val t = texts.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (notShade.any { w -> w in c || t.any { w in it } }) return false
        if (classWords.any { it in squash(c) }) return true
        return t.any { line -> phrases.any { it in line } || classWords.any { it in squash(line) } }
    }

    /**
     * Er vinduet nedtrekksmenyen? Et systemvindu fra SystemUI med riktig tittel, eller (uten tittel) et SystemUI-vindu som
     * dekker det meste av skjermen. Vinduer fra andre apper (f.eks. ryggekamera som legger seg over alt) teller aldri.
     */
    fun isShadeWindow(w: WinSnap, screenW: Int, screenH: Int): Boolean {
        if (w.type != TYPE_SYSTEM || screenW <= 0 || screenH <= 0) return false
        if (w.pkg != null && !isSystemUi(w.pkg)) return false
        val title = w.title?.lowercase()?.trim().orEmpty()
        if (notShade.any { it in title }) return false
        val titled = phrases.any { it in title } || classWords.any { it in squash(title) }
        if (titled) return w.height >= screenH * 0.3f
        // Android 10 (brukerens stereo): nedtrekksmenyen er selve «StatusBar»-vinduet, som vokser fra en smal linje til hele
        // skjermen. Tittelen viser at det er SystemUI, også når pakkenavnet ikke kunne leses.
        if (squash(title) in statusBarTitles) return w.height >= screenH * 0.6f && w.width >= screenW * 0.5f
        // Uten kjent tittel: bare når vi vet at det er SystemUI, og vinduet dekker det meste av skjermen
        return w.pkg != null && w.height >= screenH * 0.6f && w.width >= screenW * 0.5f
    }

    /**
     * Er nedtrekksmenyen framme? Med vindusliste: bare lista avgjør (hendelsen alene kan være et innkommende varsel øverst).
     * Uten vindusliste (Android gir den ikke): hendelsen avgjør.
     */
    fun decide(eventMatch: Boolean, windows: List<WinSnap>?, screenW: Int, screenH: Int): Boolean =
        if (windows.isNullOrEmpty()) eventMatch else windows.any { isShadeWindow(it, screenW, screenH) }

    /** Et tillatelses- eller installasjonsvindu er åpent: da gjør T'OS ingenting med nedtrekksmenyen. */
    fun dialogOpen(windows: List<WinSnap>?): Boolean = windows.orEmpty().any { w ->
        val p = w.pkg ?: return@any false
        p.endsWith(".permissioncontroller") || p.endsWith(".packageinstaller")
    }

    /** Sveip ned fra øverste kant av T'OS: startet innen [edgePx] fra toppen og dratt minst [pullPx] mest nedover. */
    fun topEdgePull(startY: Float, dx: Float, dy: Float, edgePx: Float, pullPx: Float): Boolean =
        startY <= edgePx && dy >= pullPx && dy > kotlin.math.abs(dx) * 1.2f
}

/**
 * Reagerer bare når nedtrekksmenyen *kommer fram* (ikke for hver endring mens den er nede). Uten vindusliste vet vi ikke
 * når den lukkes – da regnes den som lukket etter [resetMs]. Ren logikk, testes i ShadeSurveyTest.
 */
internal class ShadeEdge(private val resetMs: Long = 2000) {
    private var open = false
    private var at = 0L

    fun fire(shade: Boolean, now: Long, windowsKnown: Boolean): Boolean {
        if (!shade) { if (windowsKnown) open = false; return false }
        val fresh = !open || (!windowsKnown && (now - at > resetMs || now < at))
        open = true; at = now
        return fresh
    }
}

/* ======================= Varsler i T'OS-panelet ======================= */

/** Det T'OS trenger å vite om et varsel for å velge og vise det. */
internal data class NoteMeta(
    val key: String, val pkg: String, val title: String?, val text: String?, val postedAt: Long,
    val ongoing: Boolean, val clearable: Boolean, val category: String?, val media: Boolean, val groupSummary: Boolean,
)

internal object NoteFilter {
    /**
     * Varslene som vises: ikke T'OS sine egne, ikke musikk/samtale/navigasjon (T'OS viser dem andre steder), ikke tomme,
     * og ikke en gruppeoversikt når varslene i gruppa vises. Nyeste først; pågående (kan ikke fjernes) til slutt.
     */
    fun shown(list: List<NoteMeta>, ownPkg: String, navApps: Set<String>): List<NoteMeta> = list.filter { n ->
        n.pkg != ownPkg && !n.media && n.category !in hiddenCategories && !(n.pkg in navApps && n.ongoing) &&
            !(n.title.isNullOrBlank() && n.text.isNullOrBlank()) &&
            !(n.groupSummary && list.any { o -> o !== n && o.pkg == n.pkg && !o.groupSummary })
    }.sortedWith(compareBy<NoteMeta> { it.ongoing }.thenByDescending { it.postedAt })

    private val hiddenCategories = setOf("transport", "call", "navigation")

    /** «Fjern alle»: bare varslene som vises og kan fjernes. */
    fun clearKeys(shown: List<NoteMeta>): List<String> = shown.filter { it.clearable }.map { it.key }

    /** «akkurat nå», «12 min siden», ellers klokkeslett («kl. 14:05») eller dato. */
    fun timeText(now: Long, at: Long): String {
        val d = now - at
        return when {
            at <= 0L -> ""
            d in 0 until 60_000 -> "akkurat nå"
            d in 0 until 3_600_000 -> "${d / 60_000} min siden"
            d in 0 until 86_400_000 -> "kl. " + SimpleDateFormat("HH:mm", Locale.US).format(Date(at))
            else -> SimpleDateFormat("d.M. HH:mm", Locale.US).format(Date(at))
        }
    }
}

/** Et varsel slik panelet viser det. */
internal data class NoteUi(val meta: NoteMeta, val label: String, val open: PendingIntent?, val autoCancel: Boolean)

/** Varslene på stereoen nå (fra varseltilgangen, [MediaListener]). Følges av panelet. */
object TosNotifications {
    internal var items by mutableStateOf<List<NoteUi>>(emptyList())
    /** Har T'OS varseltilgang (lytteren er koblet til)? */
    var connected by mutableStateOf(false)
        internal set

    private var listener: WeakReference<NotificationListenerService>? = null
    private val h = Handler(Looper.getMainLooper())
    private val labels = HashMap<String, String>()
    private val refreshNow = Runnable { refresh() }

    internal val service: NotificationListenerService? get() = listener?.get()

    internal fun attach(s: NotificationListenerService) {
        listener = WeakReference(s); h.post { connected = true }; changed()
    }

    internal fun detach() {
        listener = null; h.removeCallbacks(refreshNow); h.post { connected = false; items = emptyList() }
    }

    /** Et varsel kom eller forsvant: les lista på nytt (samlet, høyst ca. 4 ganger i sekundet). */
    internal fun changed() { h.removeCallbacks(refreshNow); h.postDelayed(refreshNow, 250) }

    private fun label(c: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    private fun refresh() {
        val s = service ?: return
        val all = runCatching { s.activeNotifications?.toList() }.getOrNull().orEmpty()
        val byKey = HashMap<String, NoteUi>()
        val metas = all.mapNotNull { sbn ->
            val n = sbn.notification ?: return@mapNotNull null
            val ex = n.extras
            val meta = NoteMeta(
                key = sbn.key, pkg = sbn.packageName,
                title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                text = (ex?.getCharSequence(Notification.EXTRA_TEXT) ?: ex?.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString(),
                postedAt = sbn.postTime, ongoing = sbn.isOngoing, clearable = sbn.isClearable, category = n.category,
                media = ex?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true || ex?.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true,
                groupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            )
            byKey[sbn.key] = NoteUi(meta, label(s, sbn.packageName), n.contentIntent, (n.flags and Notification.FLAG_AUTO_CANCEL) != 0)
            meta
        }
        items = NoteFilter.shown(metas, s.packageName, MediaListener.navApps).mapNotNull { byKey[it.key] }
    }

    /** Trykk på et varsel: åpne det appen ville åpnet (ellers selve appen). */
    internal fun open(c: Context, n: NoteUi) {
        AppRepository.markLaunched(n.meta.pkg)
        val pi = n.open
        val sent = pi != null && runCatching {
            // Android 14+: T'OS (som har en synlig tjeneste) må si at appen får lov å åpne seg
            val opts: Bundle? = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() else null
            pi.send(c, 0, null, null, null, null, opts)
        }.isSuccess
        if (!sent) AppRepository.launch(c, n.meta.pkg)
        if (n.autoCancel && n.meta.clearable) dismiss(n)
    }

    internal fun dismiss(n: NoteUi) {
        if (!n.meta.clearable) return
        runCatching { service?.cancelNotification(n.meta.key) }
        items = items.filter { it.meta.key != n.meta.key }
    }

    internal fun clearAll() {
        val keys = NoteFilter.clearKeys(items.map { it.meta })
        if (keys.isEmpty()) return
        runCatching { service?.cancelNotifications(keys.toTypedArray()) }
        items = items.filter { it.meta.key !in keys }
        Notify.show(if (keys.size == 1) "Fjernet 1 varsel" else "Fjernet ${keys.size} varsler")
    }
}

/** Varsel-delen i T'OS-panelet. Uten datakilder, så den kan tegnes i røyktestene. */
@Composable
internal fun NotificationsSection(
    items: List<NoteUi>, connected: Boolean, onOpen: (NoteUi) -> Unit, onDismiss: (NoteUi) -> Unit,
    onClearAll: () -> Unit, onAskAccess: () -> Unit, modifier: Modifier = Modifier,
) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(4000); armed = false } }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Varsler", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (items.any { it.meta.clearable }) {
                Pill(if (armed) "Trykk igjen for å fjerne alle" else "Fjern alle", Icons.Default.ClearAll, strong = armed) {
                    if (armed) { armed = false; onClearAll() } else armed = true
                }
            }
        }
        when {
            !connected -> Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(18.dp)).background(Card)
                    .clickable(onClickLabel = "Slå på varseltilgang") { onAskAccess() }.padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(Icons.Default.Info, null, tint = TextMuted, modifier = Modifier.size(22.dp))
                Text("Slå på varseltilgang for å se varslene her", color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ChevronRight, null, tint = TextMuted)
            }
            items.isEmpty() -> Text("Ingen varsler", color = TextMuted, fontSize = 15.sp, modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp))
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 270.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.meta.key }) { n -> NoteCard(n, now, { onOpen(n) }, { onDismiss(n) }) }
            }
        }
    }
}

@Composable
private fun NoteCard(n: NoteUi, now: Long, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val icon by produceState<Drawable?>(null, n.meta.pkg) {
        value = withContext(Dispatchers.IO) { runCatching { context.packageManager.getApplicationIcon(n.meta.pkg) }.getOrNull() }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp)).background(Card)
            .clickable(onClickLabel = "Åpne") { onOpen() }.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val ic = icon
        if (ic != null) AppIcon(ic, 40.dp, n.meta.pkg)
        else Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Line), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Notifications, null, tint = TextMuted, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            val time = NoteFilter.timeText(now, n.meta.postedAt)
            Text(if (time.isEmpty()) n.label else "${n.label} · $time", color = TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val title = n.meta.title?.takeIf { it.isNotBlank() } ?: n.meta.text.orEmpty()
            Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val text = n.meta.text?.takeIf { it.isNotBlank() && n.meta.title?.isNotBlank() == true }
            if (text != null) Text(text, color = TextMuted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (n.meta.clearable) RoundIcon(Icons.Default.Close, 44.dp, desc = "Fjern varselet") { onDismiss() }
    }
}

/* ======================= T'OS-panelet over andre apper ======================= */

/** Livssyklus for Compose i et vindu som ikke hører til en aktivitet (overlegget over andre apper). */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    fun start() {
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}

/**
 * T'OS sitt panel som et eget vindu over appen som er åpen (CarPlay, kart …), tegnet via knappestyringen.
 * Feiler det (eller krasjet det forrige gang), hentes T'OS fram med panelet åpent i stedet, og appen du var i
 * åpnes igjen når panelet lukkes.
 */
internal object ShadePanel {
    private val h = Handler(Looper.getMainLooper())
    private var view: WeakReference<View>? = null
    private var wm: WindowManager? = null
    private var owner: OverlayOwner? = null
    /** Appen som skal åpnes igjen når T'OS-panelet lukkes (bare når T'OS ble hentet fram for panelet). */
    @Volatile private var returnPkg: String? = null

    val shown get() = view?.get() != null

    private fun guard(c: Context) = c.getSharedPreferences("shade_guard", Context.MODE_PRIVATE)

    /** Vis panelet over appen. false = gikk ikke (bruk T'OS i stedet). */
    @SuppressLint("ApplySharedPref")   // commit: merket må være lagret før et eventuelt krasj
    fun show(svc: AccessibilityService): Boolean {
        if (shown) return true
        val g = guard(svc)
        // Krasjet T'OS mens panelet ble tegnet forrige gang (innen et døgn)? Da brukes reserven.
        val pendingAt = g.getLong("pending_at", 0L)
        if (pendingAt != 0L && System.currentTimeMillis() - pendingAt in 0 until 86_400_000L) return false
        g.edit().putLong("pending_at", System.currentTimeMillis()).commit()
        val ok = runCatching { attach(svc) }.isSuccess
        if (!ok) { removeNow(); g.edit().remove("pending_at").apply() }
        return ok
    }

    private fun attach(svc: AccessibilityService) {
        val w = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val o = OverlayOwner().apply { start() }
        val cv = ComposeView(svc).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            // Knappestyringen skal ikke tro at T'OS sin skjerm kom fram
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setContent { ShadeOverlayContent(onClose = { hide() }, onGo = { s -> hide(); if (s != null) Buttons.show(svc, s, instant = true) }) }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        w.addView(cv, lp)
        view = WeakReference(cv); wm = w; owner = o
        // Tegnet uten krasj: neste gang kan overlegget brukes igjen
        cv.postDelayed({ guard(svc).edit().remove("pending_at").apply() }, 1500)
    }

    private fun removeNow() {
        view?.get()?.let { v -> runCatching { wm?.removeView(v) } }
        owner?.destroy()
        view = null; wm = null; owner = null
    }

    fun hide() = h.post { removeNow() }

    /** Reserve: hent T'OS fram med panelet åpent, og husk appen du var i. */
    fun openInTos(c: Context, previous: String?) {
        returnPkg = previous?.takeIf { it != c.packageName }
        Buttons.show(c, Screen.DASH, instant = true, quick = true)
    }

    /** T'OS-panelet ble lukket: tilbake til appen T'OS ble hentet fram fra (hvis noen). */
    fun returnAfterClose(c: Context) {
        val p = returnPkg ?: return
        returnPkg = null
        AppRepository.launch(c, p)
    }

    fun forgetReturn() { returnPkg = null }
}

/** Panelet i overlegget: samme hurtigpanel som i T'OS, med samme tekststørrelse. */
@Composable
private fun ShadeOverlayContent(onClose: () -> Unit, onGo: (Screen?) -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * Theme.textScale)) {
        QuickPanel(onClose = onClose, onGo = onGo)
    }
}
