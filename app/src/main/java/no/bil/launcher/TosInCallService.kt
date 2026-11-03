package no.bil.launcher

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import java.util.concurrent.Executors

/**
 * Android kobler seg hit når T'OS er telefon-app og en samtale starter (også Bluetooth-samtaler fra iPhonen,
 * når stereoen bruker Androids vanlige HFP-telefon). Tjenesten holder samtalene, publiserer øyeblikksbilder
 * i [Phone.calls] og åpner T'OS sin samtaleskjerm ([CallActivity]).
 */
class TosInCallService : InCallService() {
    private val live = LinkedHashMap<Int, Call>()
    private val ids = HashMap<Call, Int>()
    /** Avsluttede samtaler vises «Avsluttet» et lite øyeblikk før de forsvinner. */
    private val ended = LinkedHashMap<Int, PhoneCall>()
    private val names = HashMap<Int, String>()
    private val incoming = HashSet<Int>()
    private val activeSince = HashMap<Int, Long>()
    private var destroyed = false
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (state == Call.STATE_SELECT_PHONE_ACCOUNT) selectAccount(call)
            publish()
        }
        override fun onDetailsChanged(call: Call, details: Call.Details) = publish()
    }

    override fun onCreate() {
        super.onCreate()
        Phone.service = this
    }

    override fun onDestroy() {
        destroyed = true
        if (Phone.service === this) Phone.service = null
        // Avsluttede samtaler blir stående til tidsfristen (se onCallRemoved); levende samtaler finnes ikke lenger
        Phone.calls = ended.values.toList()
        main.postDelayed({ if (Phone.service == null) Phone.calls = emptyList() }, END_SHOW_MS + 500)
        io.shutdown()
        super.onDestroy()
    }

    fun callById(id: Int): Call? = live[id]

    override fun onCallAdded(call: Call) {
        Phone.service = this
        val id = nextId++
        live[id] = call; ids[call] = id
        if (stateOf(call) == Call.STATE_RINGING) incoming += id
        call.registerCallback(callback)
        if (stateOf(call) == Call.STATE_SELECT_PHONE_ACCOUNT) selectAccount(call)
        val number = numberOf(call)
        if (number.isNotEmpty()) io.execute {
            val n = runCatching { Phone.nameFor(applicationContext, number) }.getOrNull()
            if (n != null) main.post { names[id] = n; publish() }
        }
        publish()
        showCallScreen()
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        val id = ids.remove(call) ?: return
        snapshot(id, call).let { ended[id] = it.copy(state = CallState.ENDED, ended = it.ended ?: endedOf(call)) }
        live.remove(id); names.remove(id); incoming.remove(id); activeSince.remove(id)
        publish()
        main.postDelayed({
            ended.remove(id)
            if (destroyed) Phone.calls = Phone.calls.filterNot { it.id == id } else publish()
        }, END_SHOW_MS)
    }

    override fun onBringToForeground(showDialpad: Boolean) = showCallScreen()

    @Deprecated("Erstattet av onCallEndpointChanged i Android 14, men stereoene kjører eldre Android")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        audioState ?: return
        Phone.muted = audioState.isMuted
        Phone.route = audioState.route
        Phone.routes = audioState.supportedRouteMask
    }

    private fun showCallScreen() {
        runCatching {
            startActivity(Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    /** Flere telefonkontoer og ingen valgt: velg Bluetooth-telefonen selv i stedet for at Android spør. */
    @Suppress("DEPRECATION")
    private fun selectAccount(call: Call) {
        runCatching {
            val list = call.details.extras?.getParcelableArrayList<PhoneAccountHandle>(Call.AVAILABLE_PHONE_ACCOUNTS).orEmpty()
            val handles = list.ifEmpty { Phone.callAccounts(this) }
            val i = preferredAccount(handles.map { it.componentName.packageName })
            if (i >= 0) call.phoneAccountSelected(handles[i], false) else call.disconnect()
        }
    }

    private fun publish() {
        if (destroyed) return
        Phone.service = this
        Phone.calls = live.map { (id, c) -> snapshot(id, c) } + ended.values.filter { it.id !in live }
    }

    @Suppress("DEPRECATION")
    private fun stateOf(c: Call): Int = if (Build.VERSION.SDK_INT >= 31) c.details.state else c.state

    private fun numberOf(c: Call): String = runCatching {
        if (c.details.handlePresentation != TelecomManager.PRESENTATION_ALLOWED) "" else c.details.handle?.schemeSpecificPart.orEmpty()
    }.getOrDefault("")

    private fun endedOf(c: Call): String = endedText(runCatching { c.details.disconnectCause?.code }.getOrNull() ?: 0)

    private fun snapshot(id: Int, c: Call): PhoneCall {
        val d = c.details
        val state = mapCallState(stateOf(c))
        if (state == CallState.ACTIVE) activeSince.getOrPut(id) { System.currentTimeMillis() }
        val number = numberOf(c)
        val shown = runCatching {
            d.callerDisplayName?.takeIf { d.callerDisplayNamePresentation == TelecomManager.PRESENTATION_ALLOWED && it.isNotBlank() }
                ?: (if (Build.VERSION.SDK_INT >= 30) d.contactDisplayName?.takeIf { it.isNotBlank() } else null)
        }.getOrNull()
        val isIncoming = if (Build.VERSION.SDK_INT >= 29) d.callDirection == Call.Details.DIRECTION_INCOMING else id in incoming
        val connected = d.connectTimeMillis.takeIf { it > 0 } ?: activeSince[id] ?: 0L
        return PhoneCall(
            id = id,
            number = number.ifEmpty { if (d.handlePresentation == TelecomManager.PRESENTATION_ALLOWED) "" else "Skjult nummer" },
            name = names[id] ?: shown,
            state = state,
            incoming = isIncoming,
            connectedAt = connected,
            canHold = d.can(Call.Details.CAPABILITY_HOLD) || d.can(Call.Details.CAPABILITY_SUPPORT_HOLD),
            ended = if (state == CallState.ENDED) endedOf(c) else null,
        )
    }

    companion object {
        const val END_SHOW_MS = 1500L
        /** Felles teller, så en ny tjeneste aldri gjenbruker id-en til en samtale som fortsatt vises «Avsluttet». */
        private var nextId = 1
    }
}
