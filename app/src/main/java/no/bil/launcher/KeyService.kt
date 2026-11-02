package no.bil.launcher

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Bakgrunnstjeneste som fanger knappene på stereoen, også når T'OS ikke er på skjermen.
 * To måter, fordi stereoer gjør det ulikt:
 *  1. Knappen sender en tastekode → vi fanger den og gjør det brukeren har valgt.
 *  2. Knappen åpner stereoens egen app direkte → vi ser at appen kom fram og bytter til valgt handling.
 * Leser ikke innholdet på skjermen, bare hvilken app som er framme.
 */
class KeyService : AccessibilityService() {
    private var swallowUp = -1
    private var lastRedirect = 0L

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP && code == swallowUp) { swallowUp = -1; return true }

        if (Learn.target != null && event.action == KeyEvent.ACTION_DOWN && !isVolume(code)) {
            Learn.offerKey(this, code)
            swallowUp = code
            return true
        }
        val key = Buttons.keyForCode(this, code) ?: return false
        if (!Buttons.shouldIntercept(this, key, code)) return false
        if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
            Buttons.perform(this, Buttons.action(this, key))
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in ignored || "inputmethod" in pkg || "keyboard" in pkg) return

        if (Learn.target != null) { Learn.offerPackage(this, pkg); return }

        val key = Buttons.keyForPkg(this, pkg) ?: return
        val action = Buttons.action(this, key)
        val now = SystemClock.elapsedRealtime()
        if (action == Action.NONE || action == Action.BACK) return
        if (pkg in Buttons.targets(this, action)) return          // ville gått i ring
        if (Buttons.inCall(this)) return                          // ikke ta bort samtalebildet
        if (now - lastRedirect < 1500) return
        lastRedirect = now
        Buttons.perform(this, action)
    }

    override fun onInterrupt() {}

    private fun isVolume(code: Int) =
        code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE

    private val ignored = setOf("android", "com.android.systemui")
}
