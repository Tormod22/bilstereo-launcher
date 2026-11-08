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
 * over andre apper, og stereoens originale startskjerm/app-liste/radio-app/fanevisning/nedtrekksmeny byttes ut med
 * T'OS når de dukker opp. Ser hvilken app og hvilke vinduer som er framme (navn, plass på skjermen) – ikke det du skriver.
 * Under «Start opptak» (SystemSurvey.kt) holder tjenesten seg unna og tar bare opp hva stereoen selv gjør.
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

    // Stereoens nedtrekksmeny (varsler/hurtiginnstillinger): T'OS sitt panel i stedet
    private val shadeEdge = ShadeEdge()
    private val shadeLimiter = RedirectLimiter(minGapMs = 800, maxInWindow = 5, windowMs = 20_000, pauseMs = 30_000)
    private var shadeHint = false
    private var shadeHintInfo = ""
    /** Et tillatelses-/installasjonsvindu er framme (da rører T'OS ikke nedtrekksmenyen). */
    private var dialogFront = false
    private val shadeCheck = Runnable { checkShade() }
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
            if (SurveyRecorder.active) {
                SurveyRecorder.note("VOLUM  ${i.action?.substringAfterLast('.')}  strøm=${i.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)}  " +
                    "${i.getIntExtra(EXTRA_PREV_VOLUME_STREAM_VALUE, -1)} → ${i.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)}")
                return
            }
            if (!HwVolume.enabled || HwVolume.ownRecently()) return
            // FYT: Androids lydstrømmer er ikke det ekte volumet (volumhjulet endrer FYT sitt) – se FytVolume
            if (FytVolume.active) return
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
        override fun onReceive(c: Context, i: Intent) { AppRepository.invalidate(c); NaviKey.invalidate() }
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
        ShadePanel.hide()
        RecordGuide.hide()
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
        // Opptak (Kartlegg stereoen): bare skriv ned knappen, og la stereoen gjøre det den pleier
        if (SurveyRecorder.active) { SurveyRecorder.key(event); return false }
        val code = event.keyCode
        // «Siste rattknapp» på Knapper-siden: neste/forrige/svar … (volum er panelknappen her og vises ikke)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !isVolume(code)) Steer.noteKey(code)
        // T'OS-panelet over en annen app: BACK lukker det
        if (code == KeyEvent.KEYCODE_BACK && ShadePanel.shown) {
            if (event.action == KeyEvent.ACTION_UP) ShadePanel.hide()
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && code == swallowUp) { swallowUp = -1; return true }
        // Nytt trykk på samme knapp: slippet fra læringen kom aldri – ikke svelg slippet til dette trykket
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && code == swallowUp && Learn.target == null) swallowUp = -1

        // Volumknappene: T'OS endrer volumet selv (uten Androids volumpanel) og viser sin egen linje
        if (isVolume(code)) return handleVolume(event)

        if (Learn.target != null && event.action == KeyEvent.ACTION_DOWN && !isVolume(code) && Learn.offerKey(this, code)) {
            swallowUp = code
            return true
        }
        val key = Buttons.keyForCode(this, code)
        // Grønn prikk på Knapper-siden: viser hvilken knapp T'OS kjente igjen (også når T'OS lar stereoen ta den)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (key != null) KeyPing.hit(key, "kode $code") else if (KeyPing.reportUnknown(code)) KeyPing.unknown(code)
        }
        // Fanelista-knappen (nylige apper) sendt som tastekode: T'OS sin «Siste apper» i stedet for Androids
        if (key == null && code == KeyEvent.KEYCODE_APP_SWITCH && Prefs.bool("tos_recents", true) && !Buttons.inCall(this)) {
            if (event.action == KeyEvent.ACTION_UP && (event.flags and KeyEvent.FLAG_CANCELED) == 0) {
                if (redirects.allow(SystemClock.elapsedRealtime())) bringBack(Screen.RECENTS)
            }
            return true
        }
        if (key == null) return false
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
                // FYT styrer volumet selv: knappen går til stereoen som før, og T'OS viser sin linje over FYT sitt vindu
                if (e.repeatCount == 0 && FytVolume.active) return false
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

    /** Er hendelsen Androids fanevisning? Teksten (vindustittel) leses bare her, ikke innholdet på skjermen. */
    private fun isStockRecentsEvent(pkg: String, cls: String?, e: AccessibilityEvent): Boolean {
        val texts = runCatching { e.text.mapNotNull { it?.toString() } + listOfNotNull(e.contentDescription?.toString()) }.getOrDefault(emptyList())
        return RecentsLogic.isStockRecents(pkg, cls, texts, pkg in homePkgs, packageName)
    }

    /** Stereoens «skall»: original startskjerm, app-liste/hovedmeny i startskjerm-appen, «car home»-apper. */
    /** Kartappene T'OS kjenner: valgt kartapp, Google Maps, Waze og Kart-flisa sine kandidater. */
    private fun navApps(): Set<String> =
        (Buttons.targets(this, Action.NAVI) + listOf(NaviKey.MAPS, NaviKey.WAZE) + NaviKey.geoApps(this))
            .filter { it.isNotBlank() && it != packageName }.toSet()

    /** FYT sitt volumvindu: følg med på hvor det er og om det fortsatt finnes (bare mens T'OS-linja vises). */
    fun checkFytPopup() {
        if (!FytVolume.watching) return
        val dm = resources.displayMetrics
        val snaps = runCatching {
            windows.orEmpty().filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }.map { w ->
                val b = android.graphics.Rect(); w.getBoundsInScreen(b)
                val small = b.height() <= dm.heightPixels / 2
                // Pakkenavnet hentes bare for små vinduer (FYT-vinduet), ikke for appen som fyller skjermen
                val root = if (small) runCatching { w.root }.getOrNull() else null
                val pkg = root?.packageName?.toString()
                @Suppress("DEPRECATION") if (root != null && Build.VERSION.SDK_INT < 33) runCatching { root.recycle() }
                WinSnap(w.type, w.title?.toString(), pkg, b.left, b.top, b.right, b.bottom, w.layer, w.isActive, w.isFocused)
            }
        }.getOrNull()
        FytVolume.onWindows(snaps, dm.widthPixels, dm.heightPixels)
    }

    private val labels = HashMap<String, String>()
    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("")
    }

    private fun isShell(pkg: String, cls: String?): Boolean {
        // CarPlay-appen kan også være registrert som startskjerm – den skal aldri byttes ut
        if (isProjectionApp(pkg)) return false
        val home = pkg in homePkgs
        if (home && cls != null && cls in homeClasses) return true
        return isShellWindow(pkg, cls, home, isSystemPkg(pkg))
    }

    /**
     * Stereoens nedtrekksmeny kan ha kommet fram: sjekk litt etter (vinduene må rekke å legge seg). Hendelser fra
     * SystemUI med riktig tittel/klasse gir et hint; vinduslista avgjør (se [ShadeLogic]).
     */
    private fun noteShade(e: AccessibilityEvent) {
        if (ShadePanel.shown || !Prefs.bool("tos_shade", true)) return
        val pkg = e.packageName?.toString()
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!ShadeLogic.isSystemUi(pkg)) return
            val cls = e.className?.toString()
            val texts = runCatching { e.text.mapNotNull { it?.toString() } + listOfNotNull(e.contentDescription?.toString()) }.getOrDefault(emptyList())
            if (ShadeLogic.isShadeEvent(pkg, cls, texts)) { shadeHint = true; shadeHintInfo = "$pkg/${cls.orEmpty()} «${SurveyText.clip(texts.joinToString(" | "), 60)}»" }
        }
        handler.removeCallbacks(shadeCheck)
        handler.postDelayed(shadeCheck, 80)
    }

    /** Når et varsel sist kom (elapsedRealtime). */
    @Volatile private var lastNoteAt = 0L

    private fun checkShade() {
        val hint = shadeHint; val info = shadeHintInfo
        shadeHint = false
        if (!Prefs.bool("tos_shade", true) || SurveyRecorder.active || ShadePanel.shown) return
        // Pakkenavn hentes bare for systemvinduene (SystemUI svarer raskt; en travel app kunne ellers holdt T'OS igjen)
        val snaps = runCatching { windowSnaps(rootOf = { it == ShadeLogic.TYPE_SYSTEM }) }.getOrNull()
        val dm = resources.displayMetrics
        val now = SystemClock.elapsedRealtime()
        // Android 10: et innkommende varsel øverst (heads-up) gjør også statuslinja like stor som skjermen – ikke ta det for menyen
        val shade = ShadeLogic.decide(hint, snaps, dm.widthPixels, dm.heightPixels) && !ShadeLogic.maybeHeadsUp(hint, now - lastNoteAt)
        if (!shadeEdge.fire(shade, now, windowsKnown = !snaps.isNullOrEmpty())) return
        // Ikke under en samtale (svar-knappen kan ligge der), og aldri over tillatelses-/installasjonsvinduer
        if (Buttons.inCall(this) || dialogFront || ShadeLogic.dialogOpen(snaps) || !shadeLimiter.allow(now)) return
        // Til oppsettrapporten/kartleggingen: hvordan stereoens nedtrekksmeny ser ut
        val win = snaps?.firstOrNull { ShadeLogic.isShadeWindow(it, dm.widthPixels, dm.heightPixels) }
        Prefs.setQuiet("shade_seen", win?.let { "${it.pkg}/«${it.title.orEmpty()}» ${it.width}x${it.height}" } ?: info)
        // Lukk stereoens meny (Android 12+), og vis T'OS sitt panel
        when (ShadeLogic.closeWay(Build.VERSION.SDK_INT)) {
            ShadeLogic.CloseWay.DISMISS_SHADE -> if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            ShadeLogic.CloseWay.COLLAPSE_OR_BACK -> if (!collapseStatusBar()) performGlobalAction(GLOBAL_ACTION_BACK)
        }
        if (TosOverlay.appShown) { Nav.quick = true; return }
        if (!ShadePanel.show(this)) ShadePanel.openInTos(this, front.current?.takeIf { it !in homePkgs })
    }

    /** Android 10/11: lukk nedtrekksmenyen med StatusBarManager.collapsePanels (skjult funksjon). false = gikk ikke. */
    @SuppressLint("WrongConstant")
    private fun collapseStatusBar(): Boolean = runCatching {
        val sb = getSystemService("statusbar") ?: return false
        sb.javaClass.getMethod("collapsePanels").invoke(sb)
        true
    }.getOrDefault(false)

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Opptak (Kartlegg stereoen): skriv ned alt, og la stereoen være i fred
        if (SurveyRecorder.active) { SurveyRecorder.event(this, event); return }
        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) lastNoteAt = SystemClock.elapsedRealtime()
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED && FytVolume.watching) checkFytPopup()
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) noteShade(event)
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString()
        // FYT sitt volumvindu (com.syu.ms, «Popup Window» med tallet): T'OS-linja over det. Det er ikke en app som kom fram.
        if (FytVolumeLogic.isPopupEvent(pkg, cls)) {
            val texts = runCatching { event.text.mapNotNull { it?.toString() } }.getOrDefault(emptyList())
            FytVolume.onPopupEvent(this, texts)
            return
        }
        // Androids fanevisning (nylige apper, «No recent items») kom fram: vis T'OS sin «Siste apper» i stedet.
        // Sjekkes før systemvinduene under, fordi den på eldre Android ligger i SystemUI. Av: fanelista er bare innom.
        if (pkg != packageName && Prefs.bool("tos_recents", true) && isStockRecentsEvent(pkg, cls, event)) {
            // Til oppsettrapporten: hvordan stereoens fanevisning ser ut (pakke/klasse)
            Prefs.setQuiet("recents_seen", "$pkg/${cls.orEmpty()}")
            if (!Buttons.inCall(this) && redirects.allow(SystemClock.elapsedRealtime())) bringBack(Screen.RECENTS)
            return
        }
        // Systemvinduer og tastatur legger seg bare over appen som er framme – de bytter ikke app
        if (pkg in ignored || "inputmethod" in pkg || "keyboard" in pkg) return
        dialogFront = pkg.endsWith(".permissioncontroller") || pkg.endsWith(".packageinstaller")
        // Fanelista og tillatelsesvinduer er bare innom: appen under er fortsatt «framme» (og fortsatt T'OS sin)
        if (isPassingWindow(pkg, cls)) return
        if (pkg == packageName) {
            // Bare T'OS sine egne skjermer teller (ikke Waze-popupen o.l. som legges over andre apper)
            if (cls == null || cls.startsWith("$packageName.")) {
                // T'OS sin skjerm (en aktivitet) er framme: panelet over andre apper trengs ikke
                if (cls != null && ShadePanel.shown) ShadePanel.hide()
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
        // En annen app kom fram (f.eks. fra et varsel): T'OS-panelet over den forrige lukkes
        if (state.newlyFront && ShadePanel.shown) ShadePanel.hide()
        // T'OS sin egen liste til «Siste apper» (brukes når Android ikke deler appbruken). Ikke startskjermer.
        if (state.newlyFront && pkg !in homePkgs) RecentApps.record(this, pkg)
        // Merket er brukt opp: neste gang appen åpnes (f.eks. med knappen på stereoen) er det ikke T'OS som åpnet den
        if (byUs && state.newlyFront) AppRepository.forgetLaunched(pkg)
        // Stereoens eget rattknapp-oppsett («Control») og bilens oppsett (canbus): aldri bytt dem ut, og lær ikke
        // en knapp av dem (rattknappene læres der én gang)
        if (pkg in SteerLogic.SETUP_PKGS) return

        // En app T'OS selv nettopp åpnet er ikke det knappen åpnet
        if (Learn.target != null && !byUs && Learn.offerPackage(this, pkg)) return

        // Appen ble åpnet av T'OS selv (f.eks. «Start radio», «Telefon», stereoens egen startskjerm fra app-lista):
        // ikke send tilbake så lenge du er i den
        if (state.owned) { rememberLastApp(pkg); return }

        val now = SystemClock.elapsedRealtime()
        val key = Buttons.keyForPkg(this, pkg)
        if (key != null && state.newlyFront) KeyPing.hit(key, "åpnet ${label(pkg).ifBlank { pkg.substringAfterLast('.') }}")

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

        // NAVI-knappen: stereoen åpner kartappen selv (Google Maps/Waze) eller sin egen navigasjonsapp.
        // Uten dette ble ingenting gjort (kartappen er allerede «målet»), så Waze-popupen kom aldri.
        val navAction = key?.let { Buttons.action(this, it) }
        if (state.newlyFront && (key == null || navAction == Action.NAVI) && Prefs.bool("navi_detect", true) &&
            !Buttons.inCall(this) && now - lastRedirect >= 1500) {
            when (NaviKey.classify(pkg, navApps(), isSystemPkg(pkg), label(pkg), learned = key != null)) {
                NaviKey.Kind.REAL_NAV -> { lastRedirect = now; NaviKey.offerOther(this, pkg); return }
                NaviKey.Kind.STOCK_NAV -> {
                    // Stereoens egen navigasjonsapp: åpne den valgte kartappen i stedet (med popupen for den andre)
                    lastRedirect = now; Buttons.perform(this, Action.NAVI); return
                }
                NaviKey.Kind.OTHER -> {}
            }
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
        // FM-radio: stereoen har allerede startet radio-appen – behold radioen, vis T'OS sin radio-side.
        // FYT: RADIO-knappen har allerede gjort radioen til lydkilden – vis den i T'OS (via FYT-tjenesten), også for «Radio-side»
        if (action == Action.RADIO || (key == PanelKey.RADIO && action == Action.RADIO_PAGE && HwRadio.fyt)) {
            lastRedirect = now; HwRadio.adopt(this, pkg); return
        }
        // FYT sin BT-app viser også samtalebildet: vent litt, så FYT rekker å si fra om en samtale før T'OS bytter bort
        if (pkg == FytKeys.BT && Fyt.btReady) {
            lastRedirect = now
            handler.postDelayed({ if (front.current == pkg && !Buttons.inCall(this)) redirect(pkg, key, action) }, 400)
            return
        }
        lastRedirect = now
        redirect(pkg, key, action)
    }

    /** Knappen åpnet [pkg]: gjør handlingen brukeren har valgt i stedet. */
    private fun redirect(pkg: String, key: PanelKey, action: Action) {
        val targets = Buttons.targets(this, action)
        if (pkg in targets) return                                // ville gått i ring
        lastRedirect = SystemClock.elapsedRealtime()
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
        /** Appen som er framme nå (ifølge knappestyringen), null hvis tjenesten ikke kjører. */
        val frontPackage: String? get() = instance?.front?.current

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
 * De skal ikke regnes som at du har forlatt appen. (Med «Siste apper i T'OS-stil» på fanges fanelista før dette,
 * se [RecentsLogic.isStockRecents].) Testes i ServiceLogicTest2.
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
