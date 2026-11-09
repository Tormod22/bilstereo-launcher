package no.bil.launcher

import android.content.Context
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import java.lang.ref.WeakReference
import java.util.Locale

/* ======================= Bil-data fra canbus-boksen (FYT canbus-modul 7) ======================= */

/**
 * FYT-stereoer kan ha en «canbus-boks» (dekoder i kabelsettet) som leser bilens data (dører, klima, radar …). com.syu.ms
 * tar imot dataene og sender dem videre på canbus-modulen (7); stereoens app com.syu.canbus («Vehicle settings») viser
 * dem som små vinduer over andre apper (dører: midt på skjermen, klima: stripe nederst).
 *
 * Kilder (com.syu.canbus dekompilert i github.com/vasyl91/FYT-Launcher-Mod, mappa decompiled_com.syu.canbus/…):
 *  - module/canbus/HandlerCanbus.getCallbackCanbusById: canbus-id (u=1000) = bil-nr × 65536 + protokoll-nr
 *    (protokoll = id & 0xFFFF velger tolkingen, «Callback_<protokoll>_…»; protokoll 0 = Callback_0000_null = ingen boks).
 *  - ui/door/DoorHelper + Callback_*: dørene er kode 0 panser, 1 venstre foran, 2 høyre foran, 3 venstre bak,
 *    4 høyre bak, 5 bakluke (511 av 513 protokoller bruker akkurat disse), verdi ≠ 0 = åpen. Vinduet midt på skjermen
 *    (gravity CENTER), borte 6 s etter siste melding.
 *  - module/canbus/FinalCanbus.U_AIR_* (10–96) + ui/air/AirHelper: klima (10 av/på, 11 A/C, 12 resirkulering, 13 auto,
 *    14 dual, 15 maks frontrute, 16 bakrute, 21 vifte, 27/28 temperatur venstre/høyre, 37 enhet). Vinduet nederst
 *    (gravity BOTTOM), borte 5 s etter siste melding. Temperaturen er kodet ulikt per protokoll: bare «WC2 Toyota All»
 *    (protokoll 294, Air_0294_WC2_Toyota_All: tideler °C, −2 LO, −3 HI) tolkes; ellers vises ikke gradene.
 *  - ipc/data/FinalCanbus + github.com/AxesOfEvil/FYTCanbusMonitor CanbusIdCodes.kt: kodene 1000–1036 (finnes dør/klima/
 *    utetemperatur, fart 1031, turtall 1032 …).
 * Utetemperatur, radar, fart, turtall, drivstoff og km-stand har ULIKE koder per protokoll – ingen av Toyota-protokollene i
 * com.syu.canbus har utetemperatur, og det finnes ingen Avensis-protokoll. Derfor tolkes de ikke; alt logges med navn i
 * kartleggingen («FYT-canbus»). Stereoens radarvindu dekkes ALDRI (sikkerhet) – T'OS dekker bare klima- og dørvinduet,
 * og bare rett etter at T'OS selv fikk en klima-/dørmelding.
 *
 * Denne stereoen (kartleggingen 8. nov): persist.syu.canbusidtype = 196608 = bil 3, protokoll 0 → trolig INGEN
 * canbus-boks valgt (rattknappene er også analoge, se Steer.kt). Da viser T'OS ingenting av dette – bare kartleggingen.
 */
internal object FytCanLogic {
    const val PKG = "com.syu.canbus"
    const val PROP = "persist.syu.canbusidtype"

    const val U_DOOR_ENGINE = 0
    const val U_DOOR_FL = 1
    const val U_DOOR_FR = 2
    const val U_DOOR_RL = 3
    const val U_DOOR_RR = 4
    const val U_DOOR_BACK = 5
    const val U_AIR_POWER = 10
    const val U_AIR_AC = 11
    const val U_AIR_CYCLE = 12
    const val U_AIR_AUTO = 13
    const val U_AIR_DUAL = 14
    const val U_AIR_MAX_FRONT = 15
    const val U_AIR_REAR_DEFROST = 16
    const val U_AIR_WIND_LEFT = 21
    const val U_AIR_TEMP_LEFT = 27
    const val U_AIR_TEMP_RIGHT = 28
    const val U_AIR_TEMP_UNIT = 37
    const val U_CANBUS_ID = 1000

    /** Protokoller der temperaturen er tideler °C (−1 ingen, −2 LO, −3 HI). Kilde: Air_0294_WC2_Toyota_All. */
    val TENTHS_PROTOCOLS = setOf(294)

    data class CanId(val raw: Int) {
        val protocol get() = raw and 0xFFFF
        val car get() = (raw ushr 16) and 0xFFFF
        val none get() = protocol == 0
    }

    fun idText(raw: Int?): String {
        if (raw == null || raw < 0) return "ukjent"
        val id = CanId(raw)
        return "$raw = bil ${id.car} × 65536 + protokoll ${id.protocol}" +
            if (id.none) " → protokoll 0 = ingen canbus-boks valgt (Callback_0000_null)" else ""
    }

    /** Dørene: null = ikke meldt ennå. */
    data class Doors(
        val hood: Boolean? = null, val fl: Boolean? = null, val fr: Boolean? = null,
        val rl: Boolean? = null, val rr: Boolean? = null, val back: Boolean? = null,
    ) {
        val known get() = listOf(hood, fl, fr, rl, rr, back).any { it != null }
        val anyOpen get() = listOf(hood, fl, fr, rl, rr, back).any { it == true }
        fun openNames(): List<String> = buildList {
            if (fl == true) add("venstre fordør"); if (fr == true) add("høyre fordør")
            if (rl == true) add("venstre bakdør"); if (rr == true) add("høyre bakdør")
            if (back == true) add("bakluka"); if (hood == true) add("panseret")
        }
    }

    fun isDoorCode(u: Int) = u in U_DOOR_ENGINE..U_DOOR_BACK
    fun isAirCode(u: Int) = u in 10..96

    fun withDoor(d: Doors, u: Int, v: Int?): Doors {
        if (v == null) return d
        val open = v != 0
        return when (u) {
            U_DOOR_ENGINE -> d.copy(hood = open)
            U_DOOR_FL -> d.copy(fl = open)
            U_DOOR_FR -> d.copy(fr = open)
            U_DOOR_RL -> d.copy(rl = open)
            U_DOOR_RR -> d.copy(rr = open)
            U_DOOR_BACK -> d.copy(back = open)
            else -> d
        }
    }

    /** Klimaet: null = ikke meldt. Temperaturene er råverdier (se [tempText]). */
    data class Air(
        val power: Boolean? = null, val ac: Boolean? = null, val recirc: Boolean? = null, val auto: Boolean? = null,
        val dual: Boolean? = null, val maxFront: Boolean? = null, val rearDefrost: Boolean? = null,
        val fan: Int? = null, val tempL: Int? = null, val tempR: Int? = null, val fahrenheit: Boolean = false,
    ) {
        val known get() = listOf(power, ac, recirc, auto, fan, tempL, tempR).any { it != null }
    }

    fun withAir(a: Air, u: Int, v: Int?): Air {
        if (v == null) return a
        return when (u) {
            U_AIR_POWER -> a.copy(power = v != 0)
            U_AIR_AC -> a.copy(ac = v != 0)
            U_AIR_CYCLE -> a.copy(recirc = v != 0)
            U_AIR_AUTO -> a.copy(auto = v != 0)
            U_AIR_DUAL -> a.copy(dual = v != 0)
            U_AIR_MAX_FRONT -> a.copy(maxFront = v != 0)
            U_AIR_REAR_DEFROST -> a.copy(rearDefrost = v != 0)
            U_AIR_WIND_LEFT -> a.copy(fan = v.takeIf { it in 0..10 })
            U_AIR_TEMP_LEFT -> a.copy(tempL = v)
            U_AIR_TEMP_RIGHT -> a.copy(tempR = v)
            U_AIR_TEMP_UNIT -> a.copy(fahrenheit = v != 0)
            else -> a
        }
    }

    private val NO = Locale.forLanguageTag("nb-NO")

    /** «21,5°», «LO», «HI» – eller null når kodingen er ukjent for protokollen (vises da ikke). */
    fun tempText(raw: Int?, protocol: Int, fahrenheit: Boolean): String? {
        if (raw == null || protocol !in TENTHS_PROTOCOLS) return null
        return when {
            raw == -2 -> "LO"
            raw == -3 -> "HI"
            fahrenheit && raw in 590..900 -> String.format(NO, "%.0f °F", raw / 10.0)
            !fahrenheit && raw in 100..350 -> String.format(NO, "%.1f°", raw / 10.0)
            else -> null
        }
    }

    fun fanText(fan: Int?): String? = when {
        fan == null -> null
        fan == 0 -> "Vifte av"
        else -> "Vifte $fan"
    }

    /** Kort oppsummering: «A/C · Auto · Resirkulering · Vifte 3» (bare det bilen har meldt). null = ingenting. */
    fun airSummary(a: Air): String? {
        if (a.power == false) return "Klima av"
        val parts = buildList {
            if (a.ac == true) add("A/C"); if (a.ac == false) add("A/C av")
            if (a.auto == true) add("Auto")
            if (a.recirc == true) add("Resirkulering")
            if (a.maxFront == true) add("Frontrute")
            if (a.rearDefrost == true) add("Bakrute")
            fanText(a.fan)?.let { add(it) }
        }
        return parts.joinToString(" · ").ifEmpty { null }
    }

    /** Navn på koden (til kartleggingen). Kodene 0–96 gjelder de fleste protokoller; resten er per protokoll. */
    fun label(u: Int): String = names[u] ?: when (u) {
        in 6..9 -> "ukjent (per protokoll)"
        in 10..96 -> "klima (per protokoll)"
        in 97..999 -> "bilinfo (per protokoll: f.eks. radar, fart, forbruk)"
        else -> "ukjent"
    }

    private val names = mapOf(
        0 to "dør: panser", 1 to "dør: venstre foran", 2 to "dør: høyre foran", 3 to "dør: venstre bak", 4 to "dør: høyre bak",
        5 to "dør: bakluke", 10 to "klima av/på", 11 to "A/C", 12 to "resirkulering", 13 to "auto", 14 to "dual", 15 to "maks frontrute",
        16 to "bakrutevarme", 17 to "frontvarme", 18 to "luft opp v.", 19 to "luft kropp v.", 20 to "luft føtter v.", 21 to "vifte v.",
        22 to "luft opp h.", 23 to "luft kropp h.", 24 to "luft føtter h.", 25 to "vifte h.", 26 to "auto h.", 27 to "temp v.",
        28 to "temp h.", 29 to "setevarme v.", 30 to "setevarme h.", 31 to "setevifte v.", 32 to "setevifte h.", 33 to "luftretning auto",
        35 to "sving", 36 to "resirk. auto", 37 to "temperaturenhet", 38 to "bak",
        1000 to "canbus-id", 1001 to "klimavindu på", 1002 to "dørvindu på", 1003 to "ratt til høyre", 1004 to "har dører",
        1005 to "canbus-versjon", 1006 to "melding-id", 1007 to "har klima", 1008 to "vis klimavindu", 1009 to "har 360-kamera",
        1010 to "MCU canbus-antall", 1011 to "vis dørvindu", 1012 to "har utetemperatur", 1013 to "kameramodus", 1014 to "klimaside",
        1015 to "bil-BT på", 1016 to "har bilradio", 1017 to "høyre kamera", 1018 to "har klimastyring", 1019 to "canbus-ramme til UI",
        1020 to "høyre kamera-tilstand", 1021 to "original ryggekamera", 1022 to "OnStar", 1023 to "klima-versjon", 1024 to "ramme til MCU",
        1025 to "slavebil berøring", 1026 to "slavebil lys", 1027 to "PM2.5 inne", 1028 to "PM2.5 ute", 1029 to "PM2.5 på",
        1030 to "vis ryggebilde", 1031 to "fart", 1032 to "turtall", 1033 to "GPS-vinkel", 1034 to "har bil-DVR",
        1035 to "har roterende skjerm", 1036 to "skjul radio",
    )

    /** Hendelse fra et lite FYT canbus-vindu (vanlig visningsklasse, ikke en egen skjerm i appen). */
    fun isPopupEvent(pkg: String?, cls: String?): Boolean = pkg == PKG && cls != null && cls.startsWith("android.") &&
        !cls.startsWith("android.app.")

    /** Et lite appvindu fra com.syu.canbus (ikke hele skjermen – da er det appen selv). */
    fun isCanPopup(w: WinSnap, screenW: Int, screenH: Int): Boolean {
        if (w.pkg != PKG || w.type != ShadeLogic.TYPE_APPLICATION || w.width <= 0 || w.height <= 0) return false
        if (screenW > 0 && screenH > 0 && w.width >= screenW && w.height >= screenH * 0.8f) return false
        return screenH <= 0 || w.height <= screenH * 0.75f
    }

    /** Klimastripa: der FYT-vinduet er, ellers nederst over hele bredden (AirHelper: gravity BOTTOM). */
    fun airBox(popup: FytVolumeLogic.Box?, screenW: Int, screenH: Int, stripH: Int): FytVolumeLogic.Box =
        popup?.let { FytVolumeLogic.overlayBox(it, screenW, screenH, margin = 4, minW = minOf(screenW, 600), minH = stripH) }
            ?: FytVolumeLogic.Box(0, maxOf(0, screenH - stripH), screenW, minOf(stripH, screenH))

    /** Dørkortet: over FYT-vinduet, ellers midt på skjermen (DoorHelper: gravity CENTER). */
    fun doorBox(popup: FytVolumeLogic.Box?, screenW: Int, screenH: Int, w: Int, h: Int): FytVolumeLogic.Box {
        val p = popup ?: FytVolumeLogic.Box(screenW / 2 - w / 2, screenH / 2 - h / 2, w, h)
        return FytVolumeLogic.overlayBox(p, screenW, screenH, margin = 6, minW = w, minH = h)
    }

    const val AIR_MIN_MS = 4000L
    const val DOOR_MIN_MS = 6000L
    const val MAX_MS = 12_000L
    /** Så lenge etter en melding regnes et nytt canbus-vindu som klima/dør (ellers kan det være radaren – røres ikke). */
    const val RECENT_MS = 2500L
    /** Meldinger de første sekundene etter tilkobling er nåverdier, ikke endringer. */
    const val SETTLE_MS = 3000L

    /** Skal kortet bort? Tidligst etter [minMs] (og bare når FYT-vinduet er borte), senest etter [MAX_MS]. */
    fun shouldHide(now: Long, lastChange: Long, popupPresent: Boolean, minMs: Long): Boolean {
        val since = now - lastChange
        if (since < 0) return false
        return since >= MAX_MS || (since >= minMs && !popupPresent)
    }
}

/** Tilstanden T'OS viser (fra Fyt.onUpdate, hovedtråden). */
object FytCan {
    var canId by mutableIntStateOf(-1); private set
    internal var doors by mutableStateOf(FytCanLogic.Doors()); private set
    internal var air by mutableStateOf(FytCanLogic.Air()); private set
    private var firstAt = 0L
    private val seen = java.util.TreeMap<Int, String>()
    private val counts = HashMap<Int, Int>()

    val protocol get() = if (canId >= 0) FytCanLogic.CanId(canId).protocol else 0
    /** En canbus-boks er valgt i stereoen (protokoll ≠ 0) og T'OS har fått id-en. */
    val active get() = canId >= 0 && protocol != 0
    private val enabled get() = Prefs.bool("tos_canbus", true)

    fun airTemp(left: Boolean): String? = FytCanLogic.tempText(if (left) air.tempL else air.tempR, protocol, air.fahrenheit)

    /** Melding fra canbus-modulen (7). */
    fun onUpdate(u: Int, ints: IntArray?) {
        val now = SystemClock.elapsedRealtime()
        if (firstAt == 0L) firstAt = now
        synchronized(seen) {
            seen[u] = ints?.take(8)?.joinToString(",", "[", "]") ?: "(tomt)"
            counts[u] = (counts[u] ?: 0) + 1
        }
        val v = ints?.firstOrNull()
        if (u == FytCanLogic.U_CANBUS_ID) { if (v != null) canId = v; return }
        if (!active) return
        val settled = now - firstAt >= FytCanLogic.SETTLE_MS
        val c = Fyt.appContext
        if (FytCanLogic.isDoorCode(u)) {
            val d = FytCanLogic.withDoor(doors, u, v)
            val changed = d != doors
            doors = d
            if (changed && settled && enabled && c != null) FytCanOverlay.onDoors(c, d)
        } else if (FytCanLogic.isAirCode(u)) {
            val a = FytCanLogic.withAir(air, u, v)
            val changed = a != air
            air = a
            if (changed && settled && enabled && c != null) FytCanOverlay.onAir(c)
        }
    }

    @android.annotation.SuppressLint("PrivateApi")
    private fun prop(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String
    }.getOrNull().orEmpty()

    /** Kartleggingen: «FYT-canbus». */
    fun report(): String = buildString {
        val p = prop(FytCanLogic.PROP).trim()
        appendLine("${FytCanLogic.PROP} = «$p» → ${FytCanLogic.idText(p.toIntOrNull())}")
        appendLine("canbus-id fra stereoen (u=1000): ${FytCanLogic.idText(canId.takeIf { it >= 0 })}")
        appendLine("T'OS bruker canbus-dataene: ${if (active) "ja (protokoll $protocol)" else "nei (ingen protokoll valgt / ingen melding)"}" +
            "  bryter tos_canbus=${Prefs.bool("tos_canbus", true)}")
        appendLine("Dører: $doors")
        appendLine("Klima: $air  → «${FytCanLogic.airSummary(air).orEmpty()}»  temp v/h: ${airTemp(true) ?: "–"} / ${airTemp(false) ?: "–"}")
        val rows = synchronized(seen) { seen.entries.map { Triple(it.key, it.value, counts[it.key] ?: 0) } }
        appendLine("Koder sett på canbus-modulen (${rows.size}; kode, navn, siste verdi, antall):")
        if (rows.isEmpty()) appendLine("  ingen")
        rows.forEach { (u, v, n) -> appendLine("  u=$u (${FytCanLogic.label(u)}) = $v  ×$n") }
    }
}

/**
 * T'OS sine vinduer over FYT sine canbus-vinduer: klimastripe (nederst) og dørkort (midt på). Tilgjengelighetsvindu
 * (ligger over alle appvinduer), tett mørkt kort, ingen farger. Radarvinduet røres ikke.
 */
object FytCanOverlay {
    private val h = Handler(Looper.getMainLooper())
    private enum class Kind { AIR, DOOR }
    private var kind: Kind? = null
    private var slot: Pair<WindowManager, WeakReference<View>>? = null
    private var lastChange = 0L
    private var popup: FytVolumeLogic.Box? = null
    private var popupPresent = false

    /** Vises noe nå (KeyService følger da med på vinduslista)? */
    val watching get() = slot != null

    fun onAir(c: Context) {
        if (SurveyRecorder.active) return
        // Dørkortet viktigere enn klimaet: la det stå
        if (kind == Kind.DOOR && slot != null) return
        show(c, Kind.AIR)
    }

    internal fun onDoors(c: Context, d: FytCanLogic.Doors) {
        if (SurveyRecorder.active) return
        if (!d.anyOpen) { if (kind == Kind.DOOR) { lastChange = SystemClock.elapsedRealtime() - FytCanLogic.DOOR_MIN_MS + 1000; refresh() }; return }
        show(c, Kind.DOOR)
    }

    /** Et FYT canbus-vindu sendte en hendelse. Bare rett etter en T'OS-melding (ellers kan det være radaren). */
    fun onPopupEvent() {
        if (slot != null && SystemClock.elapsedRealtime() - lastChange < FytCanLogic.RECENT_MS) popupPresent = true
    }

    internal fun onWindows(snaps: List<WinSnap>?, screenW: Int, screenH: Int) {
        if (snaps == null || slot == null) return
        val p = snaps.firstOrNull { FytCanLogic.isCanPopup(it, screenW, screenH) }
        val recent = SystemClock.elapsedRealtime() - lastChange < FytCanLogic.RECENT_MS
        // Nytt vindu som dukker opp lenge etter vår melding: ikke vårt (kan være radaren) – følg det ikke
        if (p == null) { popupPresent = false; return }
        if (!popupPresent && !recent) return
        popupPresent = true
        val b = FytVolumeLogic.Box(p.left, p.top, p.width, p.height)
        if (b != popup) { popup = b; rebuild() }
    }

    private fun show(c: Context, k: Kind) {
        lastChange = SystemClock.elapsedRealtime()
        if (kind != k || slot == null) { hide(); kind = k; build(c) } else refresh()
        KeyService.instance?.checkCanPopup()
        h.removeCallbacks(tick); h.postDelayed(tick, 300)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (slot == null) return
            KeyService.instance?.checkCanPopup()
            val min = if (kind == Kind.DOOR) FytCanLogic.DOOR_MIN_MS else FytCanLogic.AIR_MIN_MS
            if (FytCanLogic.shouldHide(SystemClock.elapsedRealtime(), lastChange, popupPresent, min)) hide()
            else h.postDelayed(this, 300)
        }
    }

    fun hide() {
        h.removeCallbacks(tick)
        slot?.let { (wm, ref) -> ref.get()?.let { v -> runCatching { wm.removeView(v) } } }
        slot = null; kind = null; popup = null; popupPresent = false
    }

    private fun refresh() { val v = slot?.second?.get() ?: return; rebuild(v.context) }
    private fun rebuild(ctx: Context? = slot?.second?.get()?.context) {
        val k = kind ?: return; val c = ctx ?: return
        slot?.let { (wm, ref) -> ref.get()?.let { v -> runCatching { wm.removeView(v) } } }
        slot = null; kind = k
        build(c)
    }

    private fun lp(type: Int, b: FytVolumeLogic.Box) = WindowManager.LayoutParams(
        b.w, b.h, type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = b.x; y = b.y }

    private fun build(c: Context) {
        val k = kind ?: return
        val (ctx, type) = TosOverlay.host(c) ?: return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = ctx.resources.displayMetrics
        val dp = { v: Float -> TosOverlay.dp(ctx, v) }
        val fg = TextPrimary.toArgb(); val muted = TextMuted.toArgb()
        fun text(s: String, size: Float, color: Int) = TextView(ctx).apply { text = s; setTextColor(color); textSize = size; maxLines = 1 }
        val card = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            background = GradientDrawable().apply { setColor(Card.toArgb()); cornerRadius = dp(20f).toFloat(); setStroke(dp(1f), Line.toArgb()) }
        }
        val box: FytVolumeLogic.Box
        if (k == Kind.AIR) {
            val a = FytCan.air
            card.orientation = LinearLayout.HORIZONTAL
            card.setPadding(dp(28f), 0, dp(28f), 0)
            val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
            card.addView(text(FytCan.airTemp(true) ?: "", 30f, fg), LinearLayout.LayoutParams(wrap, wrap))
            val mid = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(text("Klima", 14f, muted))
                addView(text(FytCanLogic.airSummary(a) ?: "", 20f, fg))
            }
            card.addView(mid, LinearLayout.LayoutParams(0, wrap, 1f))
            card.addView(text(FytCan.airTemp(false) ?: "", 30f, fg), LinearLayout.LayoutParams(wrap, wrap))
            box = FytCanLogic.airBox(popup, dm.widthPixels, dm.heightPixels, dp(96f))
        } else {
            val d = FytCan.doors
            card.orientation = LinearLayout.HORIZONTAL
            card.setPadding(dp(24f), dp(16f), dp(28f), dp(16f))
            card.addView(DoorView(ctx).apply { this.d = d }, LinearLayout.LayoutParams(dp(72f), dp(120f)).apply { marginEnd = dp(22f) })
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val names = d.openNames()
                val door = listOf(d.fl, d.fr, d.rl, d.rr).any { it == true }
                addView(text(if (!d.anyOpen) "Dørene" else if (door) "Åpen dør" else "Åpen", 15f, muted))
                addView(text(names.joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { "Alt er lukket" }, 22f, fg).apply { maxLines = 2 })
            }
            card.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            box = FytCanLogic.doorBox(popup, dm.widthPixels, dm.heightPixels, dp(420f), dp(160f))
        }
        if (runCatching { wm.addView(card, lp(type, box)) }.isFailure) return
        slot = wm to WeakReference(card)
    }
}

/** Bilen sett ovenfra: rolig omriss, åpne dører/luker tegnet i hvitt (Android-visning, til vinduet over andre apper). */
private class DoorView(c: Context) : View(c) {
    var d = FytCanLogic.Doors()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        DoorDraw.draw(canvas, width.toFloat(), height.toFloat(), d, Line.toArgb(), TextPrimary.toArgb(), p, resources.displayMetrics.density)
    }
}

/** Felles tegning (også brukt på «Bil og tur»-siden). */
internal object DoorDraw {
    fun draw(cv: android.graphics.Canvas, w: Float, h: Float, d: FytCanLogic.Doors, line: Int, open: Int, p: Paint, density: Float) {
        val sw = 2.5f * density
        val bw = w * 0.62f; val bh = h * 0.92f
        val l = (w - bw) / 2; val t = (h - bh) / 2; val r = l + bw; val b = t + bh
        p.strokeWidth = sw; p.color = line
        cv.drawRoundRect(RectF(l, t, r, b), bw * 0.32f, bw * 0.32f, p)
        // Frontrute og bakrute
        cv.drawLine(l + bw * 0.18f, t + bh * 0.28f, r - bw * 0.18f, t + bh * 0.28f, p)
        cv.drawLine(l + bw * 0.2f, t + bh * 0.8f, r - bw * 0.2f, t + bh * 0.8f, p)
        p.color = open; p.strokeWidth = sw * 1.4f
        val sx = w * 0.16f       // så langt ut en åpen dør står
        fun side(left: Boolean, y0: Float, y1: Float) {
            val x = if (left) l else r
            cv.drawLine(x, y0, x + if (left) -sx else sx, y0 + (y1 - y0) * 0.75f, p)
        }
        if (d.fl == true) side(true, t + bh * 0.34f, t + bh * 0.55f)
        if (d.rl == true) side(true, t + bh * 0.57f, t + bh * 0.78f)
        if (d.fr == true) side(false, t + bh * 0.34f, t + bh * 0.55f)
        if (d.rr == true) side(false, t + bh * 0.57f, t + bh * 0.78f)
        if (d.hood == true) cv.drawLine(l + bw * 0.2f, t + bh * 0.06f, r - bw * 0.2f, t + bh * 0.06f, p)
        if (d.back == true) cv.drawLine(l + bw * 0.2f, b - bh * 0.06f, r - bw * 0.2f, b - bh * 0.06f, p)
    }
}
