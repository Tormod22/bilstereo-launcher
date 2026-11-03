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
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Bakgrunnstjeneste som fanger knappene på stereoen, også når T'OS ikke er på skjermen.
 * To måter, fordi stereoer gjør det ulikt:
 *  1. Knappen sender en tastekode → vi fanger den og gjør det brukeren har valgt (kort eller langt trykk).
 *  2. Knappen åpner stereoens egen app direkte → vi ser at appen kom fram og bytter til valgt handling.
 * I tillegg: husker siste app, starter CarPlay når iPhonen kobles til, og musikk når skjermen våkner.
 * Leser ikke innholdet på skjermen, bare hvilken app som er framme.
 */
class KeyService : AccessibilityService() {
    private var swallowUp = -1
    private var lastRedirect = 0L
    private val downAt = mutableMapOf<Int, Long>()
    private val longDone = mutableSetOf<Int>()
    private var screenOffAt = 0L
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
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED) else registerReceiver(receiver, f)
        val pf = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        runCatching { if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, pf, Context.RECEIVER_EXPORTED) else registerReceiver(pkgReceiver, pf) }
        // Les app-navnene i bakgrunnen nå, så knappetrykk ikke må vente på det
        AppRepository.warm(this)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        NaviPopup.hide()
        runCatching { unregisterReceiver(receiver) }
        runCatching { unregisterReceiver(pkgReceiver) }
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP && code == swallowUp) { swallowUp = -1; return true }

        if (Learn.target != null && event.action == KeyEvent.ACTION_DOWN && !isVolume(code) && Learn.offerKey(this, code)) {
            swallowUp = code
            return true
        }
        val key = Buttons.keyForCode(this, code) ?: return false
        if (!Buttons.shouldIntercept(this, key, code)) return false
        handlePress(this, key, event, downAt, longDone)
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Systemvinduer og tastatur legger seg bare over appen som er framme – de bytter ikke app
        if (pkg in ignored || "inputmethod" in pkg || "keyboard" in pkg) return
        val cls = event.className?.toString()
        if (pkg == packageName) {
            // Bare T'OS sine egne skjermer teller (ikke Waze-popupen o.l. som legges over andre apper)
            if (cls == null || cls.startsWith("$packageName.")) front.update(pkg, launchedByUs = false)
            return
        }
        val state = front.update(pkg, AppRepository.launchedByUs(pkg))

        if (Learn.target != null && Learn.offerPackage(this, pkg)) return

        // Appen ble åpnet av T'OS selv (f.eks. «Start radio», «Telefon», stereoens egen startskjerm fra app-lista):
        // ikke send tilbake så lenge du er i den
        if (state.owned) { rememberLastApp(pkg); return }

        val now = SystemClock.elapsedRealtime()

        // Hold T'OS som startskjerm: dukker stereoens originale startskjerm opp (f.eks. etter «fjern alle faner»),
        // vises T'OS i stedet. Trengs fordi stereoen ikke lar T'OS bli fast startskjerm.
        if (pkg in homePkgs && cls != null && cls in homeClasses && Prefs.bool("keep_home", true) && Buttons.keyForPkg(this, pkg) == null) {
            if (now - lastRedirect > 1500 && !Buttons.inCall(this)) { lastRedirect = now; Buttons.show(this, Screen.DASH) }
            return
        }

        val key = Buttons.keyForPkg(this, pkg)
        if (key == null) { rememberLastApp(pkg); return }
        // Bare når appen nettopp kom fram (ikke for hver ny skjerm inne i den)
        if (!state.newlyFront) return
        val action = Buttons.action(this, key)
        if (action == Action.NONE || action == Action.BACK) return
        if (pkg in Buttons.targets(this, action)) return          // ville gått i ring
        if (Buttons.inCall(this)) return                          // ikke ta bort samtalebildet
        if (now - lastRedirect < 1500) return
        lastRedirect = now
        // Det som spilte før knappen ble trykket (startes igjen hvis radioen tok over lyden)
        val resume = NowPlaying.recentMusic(exclude = pkg)
        Buttons.perform(this, action)
        // Stereoens radio-app rakk å starte radioen: skru den av igjen, så det ikke blir skurr
        if ((key == PanelKey.RADIO || RadioGuard.looksLikeRadio(pkg)) && action != Action.RADIO) RadioGuard.silence(this, pkg, resume)
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

        /** Kort trykk = handling ved slipp. Langt trykk (0,6 s) = egen handling, utføres mens knappen holdes. */
        fun handlePress(c: Context, key: PanelKey, e: KeyEvent, downAt: MutableMap<Int, Long>, longDone: MutableSet<Int>) {
            val code = e.keyCode
            val long = Buttons.longAction(c, key)
            when (e.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (e.repeatCount == 0) { downAt[code] = e.eventTime; longDone.remove(code) }
                    if (long != Action.NONE && code !in longDone && e.eventTime - (downAt[code] ?: e.eventTime) >= 600) {
                        longDone += code; Buttons.perform(c, long)
                    }
                }
                KeyEvent.ACTION_UP -> {
                    val held = e.eventTime - (downAt[code] ?: e.eventTime)
                    if (code in longDone) { longDone.remove(code); return }
                    if (long != Action.NONE && held >= 600) { Buttons.perform(c, long); return }
                    Buttons.perform(c, Buttons.action(c, key))
                }
            }
        }
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
