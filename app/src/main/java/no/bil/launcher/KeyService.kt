package no.bil.launcher

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bakgrunnstjeneste som fanger knappene på stereoen, også når T'OS ikke er på skjermen.
 * To måter, fordi stereoer gjør det ulikt:
 *  1. Knappen sender en tastekode → vi fanger den og gjør det brukeren har valgt (kort eller langt trykk).
 *  2. Knappen åpner stereoens egen app direkte → vi ser at appen kom fram og bytter til valgt handling.
 * I tillegg: husker siste app, starter CarPlay når iPhonen kobles til, og musikk når skjermen våkner.
 * Holder stereoens eget UI unna: volumknappene gir T'OS sin volumlinje (ikke Androids), T'OS sine meldinger vises
 * over andre apper, og stereoens originale startskjerm/app-liste/radio-app byttes ut med T'OS når de dukker opp.
 * Leser ikke innholdet på skjermen, bare hvilken app som er framme.
 */
class KeyService : AccessibilityService() {
    private var swallowUp = -1
    private var lastRedirect = 0L
    private val downAt = mutableMapOf<Int, Long>()
    private val longDone = mutableSetOf<Int>()
    private var screenOffAt = 0L
    private var registered = false
    /** Hvilken app som er framme, og om T'OS selv åpnet den (da sender vi deg ikke tilbake før du har gått ut av den). */
    private val front = FrontTracker()
    // Ikke ekte startskjermer (Android-innstillinger brukes kort ved oppstart, oppsettsveivisere)
    private val notHome = setOf("com.android.settings", "com.android.provision", "com.google.android.setupwizard", "com.android.car.settings")
    private val homeInfos by lazy {
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo }.filter { it.packageName !in notHome }
    }
    private val homePkgs by lazy { homeInfos.map { it.packageName }.toSet() }
    /** Selve startskjerm-skjermene (ikke radio/BT som noen stereoer har inne i samme app). */
    private val homeClasses by lazy { homeInfos.map { it.name }.toSet() }
    /** Systemapp eller ikke, per pakke (spørres ofte – huskes). */
    private val systemPkgs = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private fun isSystemPkg(pkg: String) = systemPkgs.getOrPut(pkg) {
        AppRepository.isSystemApp(runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull())
    }
    /** Hindrer at T'OS og stereoens startskjerm sender hverandre fram og tilbake i det uendelige. */
    private val redirects = RedirectLimiter()
    private val handler = Handler(Looper.getMainLooper())

    // Volumknappene: hvilke trykk T'OS tok (slippet svelges bare da), og når siste steg ble gjort ved holdt knapp
    private val volTaken = mutableSetOf<Int>()
    private var lastVolStep = 0L

    // Meldinger fra T'OS (Notify) vises som overlegg når T'OS ikke er framme
    private var lastNoticeId = -1L
    private val noticeCheck = AtomicBoolean(false)
    private var writeObserver: ObserverHandle? = null

    /** Volumet ble endret av noe annet enn volumknappene (f.eks. volumhjulet via stereoens MCU): vis T'OS sin linje. */
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!HwVolume.enabled || HwVolume.ownRecently()) return
            val active = HwVolume.activeStream(c)
            val stream = i.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
            if (stream != -1 && stream != active) return
            if (i.action == VOLUME_CHANGED_ACTION &&
                i.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1) == i.getIntExtra(EXTRA_PREV_VOLUME_STREAM_VALUE, -2)) return
            HwVolume.refresh(c, active, show = true)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> screenOffAt = SystemClock.elapsedRealtime()
                Intent.ACTION_SCREEN_ON ->
                    if (screenOffAt != 0L && SystemClock.elapsedRealtime() - screenOffAt > 120_000) Startup.run(c)
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val d: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (d?.vendorId == 0x05AC && Prefs.bool("carplay_auto")) AppRepository.launchTile(c, Tiles.byPrefix("Car"))
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val name = runCatching { d?.name }.getOrNull().orEmpty()
                    if (name.contains("iPhone", true) && Prefs.bool("carplay_auto")) AppRepository.launchTile(c, Tiles.byPrefix("Car"))
                }
            }
        }
    }

    /** En app ble installert, oppdatert eller fjernet: navnesøket (f.eks. «Car link 2.0») må leses på nytt. */
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = AppRepository.invalidate(c)
    }

    override fun onServiceConnected() {
        instance = this
        // Kan kalles igjen når Android kobler til tjenesten på nytt – da må mottakerne ikke registreres to ganger
        // (ellers ville f.eks. oppstartsmusikken startet to ganger)
        if (registered) return
        registered = true
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        runCatching { if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED) else registerReceiver(receiver, f) }
        val pf = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        runCatching { if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, pf, Context.RECEIVER_EXPORTED) else registerReceiver(pkgReceiver, pf) }
        val vf = IntentFilter().apply { addAction(VOLUME_CHANGED_ACTION); addAction(STREAM_MUTE_CHANGED_ACTION) }
        runCatching { if (Build.VERSION.SDK_INT >= 33) registerReceiver(volumeReceiver, vf, Context.RECEIVER_EXPORTED) else registerReceiver(volumeReceiver, vf) }
        runCatching { HwVolume.refresh(this, show = false) }
        // Følg med på T'OS sine meldinger: hver endring i Compose-tilstand gir en (samlet) sjekk på hovedtråden
        lastNoticeId = Notify.current?.id ?: -1L
        writeObserver = Snapshot.registerGlobalWriteObserver {
            if (noticeCheck.compareAndSet(false, true)) handler.post { noticeCheck.set(false); checkNotice() }
        }
        // Les app-navnene i bakgrunnen nå, så knappetrykk ikke må vente på det
        AppRepository.warm(this)
    }

    /** En ny melding fra T'OS mens T'OS ikke er framme: vis den som et lite T'OS-kort over appen. */
    private fun checkNotice() {
        val m = Notify.current
        if (m == null) {
            if (lastNoticeId != -1L) { lastNoticeId = -1L; TosOverlay.hideNoticeNow() }
            return
        }
        if (m.id == lastNoticeId) return
        lastNoticeId = m.id
        if (!TosOverlay.appShown) TosOverlay.notice(this, m.text, m.long)
    }

    override fun onDestroy() {
        NaviPopup.hide()
        TosOverlay.hideAll()
        if (instance === this) instance = null
        writeObserver?.dispose(); writeObserver = null
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(receiver) }
        runCatching { unregisterReceiver(pkgReceiver) }
        runCatching { unregisterReceiver(volumeReceiver) }
        registered = false
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP && code == swallowUp) { swallowUp = -1; return true }
        // Nytt trykk på samme knapp: slippet fra læringen kom aldri – ikke svelg slippet til dette trykket
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && code == swallowUp && Learn.target == null) swallowUp = -1

        // Volumknappene: T'OS endrer volumet selv (uten Androids volumpanel) og viser sin egen linje
        if (isVolume(code)) return handleVolume(event)

        if (Learn.target != null && event.action == KeyEvent.ACTION_DOWN && !isVolume(code) && Learn.offerKey(this, code)) {
            swallowUp = code
            return true
        }
        val key = Buttons.keyForCode(this, code) ?: return false
        // Under en samtale skal svar/legg på (og mediaknappene, som telefonen bruker til det) gå til telefonen
        if (passToCall(code) && Buttons.inCall(this)) return false
        if (!Buttons.shouldIntercept(this, key, code)) return false
        handlePress(this, key, event, downAt, longDone)
        return true
    }

    /**
     * Volum opp/ned/av med T'OS sin linje. Holdt knapp gir jevn endring (høyst ett steg per [VOL_REPEAT_MS]).
     * Slippet svelges bare når T'OS tok trykket – ellers får Android hele trykket som vanlig.
     */
    private fun handleVolume(e: KeyEvent): Boolean {
        val code = e.keyCode
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount == 0) volTaken.remove(code)
                if (!HwVolume.enabled || (e.repeatCount == 0 && !HwVolume.controllable(this))) return false
                if (e.repeatCount > 0 && code !in volTaken) return false
                if (e.repeatCount > 0 && (code == KeyEvent.KEYCODE_VOLUME_MUTE || !volumeRepeatDue(e.eventTime, lastVolStep))) return true
                lastVolStep = e.eventTime
                val dir = when (code) { KeyEvent.KEYCODE_VOLUME_UP -> 1; KeyEvent.KEYCODE_VOLUME_DOWN -> -1; else -> 0 }
                if (!HwVolume.step(this, dir)) return e.repeatCount > 0
                volTaken += code
                return true
            }
            KeyEvent.ACTION_UP -> return volTaken.remove(code)
            else -> return code in volTaken
        }
    }

    /** Hent T'OS fram (uten animasjon, bak et kort svart teppe) så stereoens skjermbilde ikke synes. */
    private fun bringBack(s: Screen) {
        lastRedirect = SystemClock.elapsedRealtime()
        TosOverlay.cover(this)
        Buttons.show(this, s, instant = true)
        TosOverlay.armUncover()
    }

    /** Stereoens «skall»: original startskjerm, app-liste/hovedmeny i startskjerm-appen, «car home»-apper. */
    private fun isShell(pkg: String, cls: String?): Boolean {
        // CarPlay-appen kan også være registrert som startskjerm – den skal aldri byttes ut
        if (isProjectionApp(pkg)) return false
        val home = pkg in homePkgs
        if (home && cls != null && cls in homeClasses) return true
        return isShellWindow(pkg, cls, home, isSystemPkg(pkg))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Systemvinduer og tastatur legger seg bare over appen som er framme – de bytter ikke app
        if (pkg in ignored || "inputmethod" in pkg || "keyboard" in pkg) return
        val cls = event.className?.toString()
        // Fanelista og tillatelsesvinduer er bare innom: appen under er fortsatt «framme» (og fortsatt T'OS sin)
        if (isPassingWindow(pkg, cls)) return
        if (pkg == packageName) {
            // Bare T'OS sine egne skjermer teller (ikke Waze-popupen o.l. som legges over andre apper)
            if (cls == null || cls.startsWith("$packageName.")) {
                front.update(pkg, launchedByUs = false)
                TosOverlay.tosFront = true
                // T'OS er framme: den viser volum og meldinger selv, og teppet kan tas bort
                TosOverlay.tosShown()
            }
            return
        }
        TosOverlay.tosFront = false
        val byUs = AppRepository.launchedByUs(pkg)
        val state = front.update(pkg, byUs)
        // Merket er brukt opp: neste gang appen åpnes (f.eks. med knappen på stereoen) er det ikke T'OS som åpnet den
        if (byUs && state.newlyFront) AppRepository.forgetLaunched(pkg)

        // En app T'OS selv nettopp åpnet er ikke det knappen åpnet
        if (Learn.target != null && !byUs && Learn.offerPackage(this, pkg)) return

        // Appen ble åpnet av T'OS selv (f.eks. «Start radio», «Telefon», stereoens egen startskjerm fra app-lista):
        // ikke send tilbake så lenge du er i den
        if (state.owned) { rememberLastApp(pkg); return }

        val now = SystemClock.elapsedRealtime()
        val key = Buttons.keyForPkg(this, pkg)

        // Hold T'OS som startskjerm: dukker stereoens originale startskjerm (eller app-lista/hovedmenyen dens) opp,
        // f.eks. etter «fjern alle faner», vises T'OS i stedet – raskt og bak et svart teppe, så den ikke blinker fram.
        // Trengs fordi stereoen ikke lar T'OS bli fast startskjerm.
        if (key == null && Prefs.bool("keep_home", true) && isShell(pkg, cls)) {
            if (!Buttons.inCall(this) && redirects.allow(now)) bringBack(Screen.DASH)
            return
        }

        // Radio-appen T'OS bruker som radiomotor kom fram igjen av seg selv: hent T'OS sin radio-side, la radioen spille
        if (HwRadio.isEngine(pkg) && (key == null || Buttons.action(this, key) != Action.NONE)) {
            if (state.newlyFront && !Buttons.inCall(this) && redirects.allow(now)) bringBack(Screen.RADIO)
            return
        }

        if (key == null) {
            // Stereoens FM-radio åpnet seg selv (f.eks. RADIO-knappen som ikke er lært): radioen spiller videre,
            // men i T'OS sin radio-side i stedet for radio-appen
            if (state.newlyFront && Prefs.bool("hide_stock_radio", true) && RadioGuard.looksLikeRadio(pkg) && isSystemPkg(pkg) &&
                !Buttons.inCall(this) && redirects.allow(now)) {
                lastRedirect = now
                HwRadio.adopt(this, pkg)
                return
            }
            rememberLastApp(pkg); return
        }
        // Bare når appen nettopp kom fram (ikke for hver ny skjerm inne i den)
        if (!state.newlyFront) return
        val action = Buttons.action(this, key)
        if (action == Action.NONE || action == Action.BACK) return
        if (Buttons.inCall(this)) return                          // ikke ta bort samtalebildet
        if (now - lastRedirect < 1500) return
        // FM-radio: stereoen har allerede startet radio-appen – behold radioen, vis T'OS sin radio-side
        if (action == Action.RADIO) { lastRedirect = now; HwRadio.adopt(this, pkg); return }
        val targets = Buttons.targets(this, action)
        if (pkg in targets) return                                // ville gått i ring
        lastRedirect = now
        // Det som spilte før knappen ble trykket (startes igjen hvis radioen tok over lyden)
        val resume = NowPlaying.recentMusic(exclude = pkg)
        // Til en T'OS-skjerm: svart teppe til T'OS er framme, så stereoens app ikke synes
        val toTos = packageName in targets
        if (toTos) TosOverlay.cover(this)
        Buttons.perform(this, action)
        if (toTos) TosOverlay.armUncover()
        // Stereoens radio-app rakk å starte radioen: skru den av igjen, så det ikke blir skurr – men ikke når
        // brukeren selv har slått på FM-radioen i T'OS. Bare en app som ser ut som radio (og ikke er stereoens
        // startskjerm) lukkes helt.
        if ((key == PanelKey.RADIO || RadioGuard.looksLikeRadio(pkg)) && !HwRadio.wanted)
            RadioGuard.silence(this, pkg, resume, mayClose = RadioGuard.looksLikeRadio(pkg) && pkg !in homePkgs)
    }

    /**
     * Husk siste vanlige app (for «Åpne siste app ved oppstart»). Ikke startskjermer, ikke apper en knapp er lært med
     * (da ville T'OS åpnet stereoens radio o.l. ved oppstart), ikke FM-radioen. Skrives bare når den er en annen.
     */
    private fun rememberLastApp(pkg: String) {
        if (pkg in homePkgs || pkg == Prefs.str("last_app")) return
        if (Buttons.keyForPkg(this, pkg) != null || RadioGuard.looksLikeRadio(pkg)) return
        if (packageManager.getLaunchIntentForPackage(pkg) != null) Prefs.setQuiet("last_app", pkg)
    }

    override fun onInterrupt() {}

    private fun isVolume(code: Int) =
        code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE

    private val ignored = setOf("android", "com.android.systemui")

    companion object {
        /** Tjenesten når den kjører – brukes til å vise popup over andre apper (uten egen tillatelse). */
        var instance: KeyService? = null
            private set

        /**
         * Kort trykk = handling ved slipp. Langt trykk (0,6 s) = egen handling, utføres mens knappen holdes.
         * Et avbrutt trykk gjør ingenting. Starttiden glemmes ved slipp, så et gammelt trykk aldri blir «langt».
         */
        fun handlePress(c: Context, key: PanelKey, e: KeyEvent, downAt: MutableMap<Int, Long>, longDone: MutableSet<Int>) {
            val code = e.keyCode
            when (e.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (e.repeatCount == 0 || code !in downAt) { downAt[code] = pressStart(e.downTime, e.eventTime); if (e.repeatCount == 0) longDone.remove(code) }
                    val long = Buttons.longAction(c, key)
                    if (long != Action.NONE && code !in longDone && e.eventTime - (downAt[code] ?: e.eventTime) >= LONG_MS) {
                        longDone += code; Buttons.perform(c, long)
                    }
                }
                KeyEvent.ACTION_UP -> {
                    val start = downAt.remove(code) ?: e.eventTime
                    if (longDone.remove(code)) return
                    if ((e.flags and KeyEvent.FLAG_CANCELED) != 0) return
                    val long = Buttons.longAction(c, key)
                    if (long != Action.NONE && e.eventTime - start >= LONG_MS) { Buttons.perform(c, long); return }
                    Buttons.perform(c, Buttons.action(c, key))
                }
                // Noen stereoer sender ett «flere trykk»-signal i stedet for trykk + slipp: regnes som et kort trykk
                @Suppress("DEPRECATION")
                KeyEvent.ACTION_MULTIPLE -> if (code != KeyEvent.KEYCODE_UNKNOWN) Buttons.perform(c, Buttons.action(c, key))
            }
        }

        private const val LONG_MS = 600L

        // Androids volum-meldinger (skjulte konstanter i AudioManager, men sendt til alle apper)
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val STREAM_MUTE_CHANGED_ACTION = "android.media.STREAM_MUTE_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
    }
}

/** Om en app nettopp kom fram, og om den er «T'OS sin» (åpnet av T'OS, og brukeren har ikke gått ut av den ennå). */
internal data class FrontState(val newlyFront: Boolean, val owned: Boolean)

/** Følger med på hvilken app som er framme. Ren logikk – testes i ServiceLogicTest. */
internal class FrontTracker {
    var current: String? = null
        private set
    private var owned: String? = null

    fun update(pkg: String, launchedByUs: Boolean): FrontState {
        val newly = pkg != current
        if (newly) { current = pkg; owned = if (launchedByUs) pkg else null }
        else if (launchedByUs) owned = pkg
        return FrontState(newly, owned == pkg)
    }
}

/** Når trykket startet: tiden knappen ble trykket ned, hvis stereoen oppgir den (noen sender 0). */
internal fun pressStart(downTime: Long, eventTime: Long): Long = if (downTime in 1..eventTime) downTime else eventTime

/** Taster telefonen bruker under en samtale (svar, legg på, og mediaknapper fra rattet). Testes i ServiceLogicTest2. */
internal fun passToCall(code: Int): Boolean = code in setOf(
    KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_ENDCALL,
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
    KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
)

/**
 * Vinduer som bare er innom over appen som er framme: fanelista (nylige apper) og tillatelses-/installasjonsvinduer.
 * De skal ikke regnes som at du har forlatt appen. Testes i ServiceLogicTest2.
 */
internal fun isPassingWindow(pkg: String, cls: String?): Boolean {
    if (cls != null && cls.contains("recents", ignoreCase = true)) return true
    return pkg.endsWith(".permissioncontroller") || pkg.endsWith(".packageinstaller")
}

/** Holdt volumknapp: et nytt steg først når det er gått minst [VOL_REPEAT_MS] siden forrige. Testes i ServiceLogicTest3. */
internal fun volumeRepeatDue(eventTime: Long, lastStep: Long): Boolean = eventTime - lastStep >= VOL_REPEAT_MS || eventTime < lastStep
internal const val VOL_REPEAT_MS = 90L

/**
 * Begrenser hvor ofte T'OS hentes fram når stereoens skjermbilder dukker opp: ikke tettere enn [minGapMs], og
 * kommer det mer enn [maxInWindow] på [windowMs] (stereoen og T'OS sender hverandre i ring), tas en pause på
 * [pauseMs]. Ren logikk – testes i ServiceLogicTest3.
 */
internal class RedirectLimiter(
    private val minGapMs: Long = 300, private val maxInWindow: Int = 6,
    private val windowMs: Long = 10_000, private val pauseMs: Long = 30_000,
) {
    private val times = ArrayDeque<Long>()
    private var pausedUntil = 0L

    fun allow(now: Long): Boolean {
        if (now < pausedUntil) return false
        times.lastOrNull()?.let { if (now - it in 0 until minGapMs) return false }
        while (times.isNotEmpty() && (now - times.first() > windowMs || now < times.first())) times.removeFirst()
        if (times.size >= maxInWindow) { pausedUntil = now + pauseMs; times.clear(); return false }
        times.addLast(now)
        return true
    }
}

/** Pakker som aldri byttes ut med T'OS (innstillinger, Bluetooth-paring, tillatelser, installasjon, systemvinduer). */
internal val protectedShellPkgs = setOf("android", "com.android.settings", "com.android.bluetooth", "com.android.systemui",
    "com.android.provision", "com.google.android.setupwizard", "com.android.car.settings", "com.android.permissioncontroller",
    "com.google.android.permissioncontroller", "com.android.packageinstaller", "com.google.android.packageinstaller")

private val shellPkgWords = listOf("launcher", "carhome", "car.home", "mainui", "mainmenu", "desktop", ".home")
private val shellClassWords = listOf("allapp", "applist", "appdrawer", "drawer", "apps", "launcher", "home", "main", "menu",
    "workspace", "desktop")
/** Skjermer i en startskjerm-app som er en egen funksjon (radio, BT, samtale …) – de byttes ikke ut. */
private val functionClassWords = listOf("radio", "bt", "bluetooth", "phone", "call", "dial", "music", "video", "media", "camera",
    "setting", "carplay", "link", "navi", "dialog", "pair", "permission", "install", "recent", "tuning", "eq", "avm", "dvr")

/**
 * Er vinduet stereoens «skall» (app-liste, hovedmeny, «car home») som T'OS skal erstatte? Bare systemapper med
 * startskjerm-preg (startskjerm-appen selv, eller navn som «launcher»/«carhome»), og bare skjermer som heter noe
 * med «apps/menu/main/home …» og ikke er en egen funksjon (radio, BT, innstillinger …). Selve startskjermen sjekkes
 * i KeyService (homeClasses). Testes i ServiceLogicTest3.
 */
internal fun isShellWindow(pkg: String, cls: String?, isHomePkg: Boolean, isSystem: Boolean): Boolean {
    if (cls == null || !isSystem || pkg in protectedShellPkgs || isProjectionApp(pkg)) return false
    if (pkg.endsWith(".packageinstaller") || pkg.endsWith(".permissioncontroller")) return false
    val p = pkg.lowercase()
    if (!isHomePkg && shellPkgWords.none { it in p }) return false
    // Vindusklassen kan være en annen pakke (f.eks. android.app.Dialog) – da er det ikke en skjerm i appen
    val c = cls.substringAfterLast('.').lowercase()
    if (functionClassWords.any { it in c }) return false
    return shellClassWords.any { it in c }
}
