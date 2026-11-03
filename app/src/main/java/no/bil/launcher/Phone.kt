package no.bil.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/*
 * T'OS som telefon-app.
 *
 * Når T'OS er valgt som «telefon-app» (standard telefonapp / ROLE_DIALER), kobler Android samtalene
 * (også Bluetooth-samtaler fra iPhonen, hvis stereoen bruker Androids vanlige Bluetooth-telefon, HFP)
 * til TosInCallService, og T'OS viser sin egen samtaleskjerm (CallActivity).
 * Ringing skjer med TelecomManager.placeCall, uten stereoens egen telefonapp.
 */

/** Hvor samtalen står. */
enum class CallState { RINGING, DIALING, ACTIVE, HOLDING, ENDED }

/** Øyeblikksbilde av én samtale (endres aldri – en ny kopi publiseres ved hver endring). */
data class PhoneCall(
    val id: Int,
    val number: String,
    /** Navn fra telefonboka eller fra telefonen (kan mangle). */
    val name: String?,
    val state: CallState,
    val incoming: Boolean,
    /** Når samtalen ble koblet til (System.currentTimeMillis), 0 = ikke ennå. */
    val connectedAt: Long,
    val canHold: Boolean,
    /** Kort forklaring når samtalen er over («Avvist», «Opptatt» …). */
    val ended: String? = null,
) {
    val title: String get() = name?.takeIf { it.isNotBlank() } ?: number.ifBlank { "Ukjent nummer" }
}

/** En linje i «Siste anrop». [type] er CallLog.Calls.*_TYPE. */
data class RecentCall(val name: String?, val number: String, val type: Int, val date: Long, val durationSec: Long) {
    val title: String get() = name?.takeIf { it.isNotBlank() } ?: number.ifBlank { "Ukjent nummer" }
}

/* ---------------- Ren logikk (testes i PhoneLogicTest) ---------------- */

/** 75 s → «01:15», 3 725 s → «1:02:05». */
internal fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%02d:%02d", m, sec)
}

/** Teksten under navnet på samtaleskjermen. */
internal fun callStatusText(state: CallState, connectedAt: Long, now: Long, ended: String? = null): String = when (state) {
    CallState.RINGING -> "Ringer …"
    CallState.DIALING -> "Ringer ut …"
    CallState.ACTIVE -> if (connectedAt > 0) "Samtale " + formatDuration(now - connectedAt) else "Samtale"
    CallState.HOLDING -> "Venter"
    CallState.ENDED -> ended ?: "Avsluttet"
}

/** android.telecom.Call.STATE_* → T'OS sin tilstand. (12 = AUDIO_PROCESSING, 13 = SIMULATED_RINGING, Android 10+) */
internal fun mapCallState(telecom: Int): CallState = when (telecom) {
    android.telecom.Call.STATE_RINGING, 13 -> CallState.RINGING
    android.telecom.Call.STATE_ACTIVE, 12 -> CallState.ACTIVE
    android.telecom.Call.STATE_HOLDING -> CallState.HOLDING
    android.telecom.Call.STATE_DISCONNECTED, android.telecom.Call.STATE_DISCONNECTING -> CallState.ENDED
    else -> CallState.DIALING   // NEW, CONNECTING, DIALING, SELECT_PHONE_ACCOUNT, PULLING_CALL
}

/** Hvorfor samtalen sluttet, på norsk. */
internal fun endedText(cause: Int): String = when (cause) {
    DisconnectCause.REJECTED -> "Avvist"
    DisconnectCause.MISSED -> "Tapt anrop"
    DisconnectCause.BUSY -> "Opptatt"
    DisconnectCause.ANSWERED_ELSEWHERE -> "Besvart på telefonen"
    DisconnectCause.ERROR -> "Samtalen ble brutt"
    else -> "Avsluttet"
}

/** Samtalen som skal vises stort: den som ringer inn først, så den som ringes ut, så pågående, så på vent. */
internal fun primaryCall(calls: List<PhoneCall>): PhoneCall? =
    calls.minByOrNull { listOf(CallState.RINGING, CallState.DIALING, CallState.ACTIVE, CallState.HOLDING, CallState.ENDED).indexOf(it.state) }

/** Bare det som kan ringes: sifre, * og #, og + først. */
internal fun dialable(s: String): String {
    val t = s.trim()
    val body = t.filter { it.isDigit() || it == '*' || it == '#' }
    return if (t.startsWith("+") && body.isNotEmpty()) "+$body" else body
}

/**
 * Samme form på alle numre, så «+47 900 00 000», «0047 90000000» og «90000000» blir like.
 * Norske nummer har 8 sifre uten retningsnummer; slike får +47 foran. Korte nummer (113, 1881) røres ikke.
 */
internal fun normalizeNumber(s: String, country: String = "47"): String {
    val t = s.trim()
    val digits = t.filter { it.isDigit() }
    return when {
        digits.isEmpty() -> ""
        t.startsWith("+") -> "+$digits"
        digits.startsWith("00") && digits.length > 4 -> "+" + digits.drop(2)
        digits.length == 8 && country == "47" -> "+47$digits"
        else -> digits
    }
}

/**
 * Er det samme nummer? Like etter [normalizeNumber], eller (når bare ett av dem har landskode)
 * de 8 siste sifrene er like – telefonen sender ofte nummeret i en annen form enn telefonboka har det.
 */
internal fun numbersMatch(a: String, b: String): Boolean {
    val x = normalizeNumber(a); val y = normalizeNumber(b)
    if (x.isEmpty() || y.isEmpty()) return false
    if (x == y) return true
    if (x.startsWith("+") && y.startsWith("+")) return false   // begge har landskode og er ulike
    val dx = x.filter { it.isDigit() }; val dy = y.filter { it.isDigit() }
    return dx.length >= 8 && dy.length >= 8 && dx.takeLast(8) == dy.takeLast(8)
}

/**
 * Søk i telefonboka: hvert ord må være starten på et ord i navnet («ola n» finner «Ola Nordmann»),
 * eller sifrene (minst to) må finnes i nummeret. Ingen T9 – vanlig tekst.
 */
internal fun filterContacts(all: List<Contact>, query: String): List<Contact> {
    val q = query.trim().lowercase(Locale.ROOT)
    if (q.isEmpty()) return all
    val words = q.split(Regex("\\s+"))
    val qDigits = q.filter { it.isDigit() }
    val numberQuery = qDigits.length >= 2 && q.all { it.isDigit() || it in " +-()" }
    return all.filter { c ->
        val nameWords = c.name.lowercase(Locale.ROOT).split(Regex("[\\s\\-.,()]+")).filter { it.isNotEmpty() }
        val byName = words.all { w -> nameWords.any { it.startsWith(w) } }
        val byNumber = numberQuery && run {
            val n = c.number.filter { it.isDigit() }
            n.contains(qDigits) || normalizeNumber(c.number).filter { it.isDigit() }.contains(normalizeNumber(q).filter { it.isDigit() })
        }
        byName || byNumber
    }
}

/** Forbokstaven til lista (# for alt som ikke begynner med en bokstav). */
internal fun letterOf(name: String): String {
    val ch = name.trim().firstOrNull() ?: return "#"
    return if (ch.isLetter()) ch.uppercaseChar().toString() else "#"
}

/** Tastetrykk på T'OS sitt eget tastatur: ⌫ sletter siste, ellers legges tegnet til (maks 24). */
internal fun appendKey(cur: String, key: String): String = when (key) {
    "⌫" -> cur.dropLast(1)
    "+" -> if (cur.isEmpty()) "+" else cur
    else -> if (cur.length >= 24) cur else cur + key
}

private val MONTHS = listOf("jan", "feb", "mar", "apr", "mai", "jun", "jul", "aug", "sep", "okt", "nov", "des")

/** «i dag 14:02», «i går 09:15», «3. nov» eller «3. nov 2025». */
internal fun whenText(date: Long, now: Long, tz: TimeZone = TimeZone.getDefault()): String {
    val d = Calendar.getInstance(tz).apply { timeInMillis = date }
    val n = Calendar.getInstance(tz).apply { timeInMillis = now }
    val time = String.format(Locale.ROOT, "%02d:%02d", d.get(Calendar.HOUR_OF_DAY), d.get(Calendar.MINUTE))
    val y = (n.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when {
        same(d, n) -> "i dag $time"
        same(d, y) -> "i går $time"
        d.get(Calendar.YEAR) == n.get(Calendar.YEAR) -> "${d.get(Calendar.DAY_OF_MONTH)}. ${MONTHS[d.get(Calendar.MONTH)]}"
        else -> "${d.get(Calendar.DAY_OF_MONTH)}. ${MONTHS[d.get(Calendar.MONTH)]} ${d.get(Calendar.YEAR)}"
    }
}

/** Hvilken telefonkonto (av flere) det skal ringes med: Bluetooth-telefonen først, ellers den første. */
internal fun preferredAccount(packages: List<String>): Int {
    if (packages.isEmpty()) return -1
    val bt = packages.indexOfFirst { it.contains("bluetooth", true) || it.contains("hfp", true) }
    return if (bt >= 0) bt else 0
}

/** CallAudioState.ROUTE_* → kort navn på knappen. */
internal fun routeLabel(route: Int): String = when (route) {
    1 -> "Røret"            // ROUTE_EARPIECE
    2 -> "Bluetooth"        // ROUTE_BLUETOOTH
    4 -> "Hodetelefoner"    // ROUTE_WIRED_HEADSET
    8 -> "Høyttaler"        // ROUTE_SPEAKER
    16 -> "Strømming"       // ROUTE_STREAMING (Android 14)
    else -> "Lyd"
}

/** Lydveiene i [mask] i fast rekkefølge (kun når det finnes mer enn én er knappen meningsfull). */
internal fun routesIn(mask: Int): List<Int> = listOf(8, 2, 1, 4).filter { mask and it != 0 }

/** Neste lydvei når man trykker på knappen. */
internal fun nextRoute(current: Int, mask: Int): Int {
    val all = routesIn(mask)
    if (all.isEmpty()) return current
    val i = all.indexOf(current)
    return all[(i + 1) % all.size]
}

/* ---------------- Telefonen ---------------- */

object Phone {
    /** Samtalene akkurat nå (tom = ingen). Compose-tilstand: Root kan vise et banner når denne ikke er tom. */
    var calls by mutableStateOf<List<PhoneCall>>(emptyList())
        internal set

    /** Samtalen som vises stort (innkommende først). */
    val current: PhoneCall? get() = primaryCall(calls)

    /** Er det en samtale som ikke er avsluttet? */
    val inCall: Boolean get() = calls.any { it.state != CallState.ENDED }

    /** Mikrofonen er slått av. */
    var muted by mutableStateOf(false)
        internal set
    /** Lydvei nå og alle mulige (CallAudioState.ROUTE_*, bitmaske). */
    var route by mutableIntStateOf(0)
        internal set
    var routes by mutableIntStateOf(0)
        internal set

    /** Samtaleskjermen er framme (da trengs ikke banneret). */
    var callScreenVisible by mutableStateOf(false)
        internal set

    /** Nummer som skal stå klart på tastaturet når Telefon-siden åpnes («» = åpne tastaturet tomt). */
    var pendingDial by mutableStateOf<String?>(null)

    /** Tjenesten som eier samtalene (satt mens Android har koblet seg til T'OS). Svak referanse: ingen minnelekkasje. */
    @Volatile private var serviceRef: WeakReference<TosInCallService>? = null
    internal var service: TosInCallService?
        get() = serviceRef?.get()
        set(v) { serviceRef = v?.let { WeakReference(it) } }

    val PERMISSIONS = arrayOf(
        Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG,
        Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE,
    )

    fun missingPermissions(c: Context): List<String> =
        PERMISSIONS.filter { ContextCompat.checkSelfPermission(c, it) != PackageManager.PERMISSION_GRANTED }

    private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    /* ----- Telefon-app-rollen ----- */

    /** Er T'OS valgt som telefon-app? */
    fun isDialer(c: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 29) c.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        else c.getSystemService(TelecomManager::class.java)?.defaultDialerPackage == c.packageName
    }.getOrDefault(false)

    /** Lar stereoen i det hele tatt andre apper være telefon-app? (Android 10+ sier nei på enheter uten «taleevne».) */
    fun dialerAvailable(c: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 29) c.getSystemService(RoleManager::class.java)?.isRoleAvailable(RoleManager.ROLE_DIALER) == true
        else true
    }.getOrDefault(false)

    /** Androids eget spørsmål «Bruke T'OS som telefonapp?» (null = ikke mulig her). Må startes med startActivityForResult. */
    fun dialerRoleIntent(c: Context): Intent? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = c.getSystemService(RoleManager::class.java) ?: return null
            if (!rm.isRoleAvailable(RoleManager.ROLE_DIALER)) null else rm.createRequestRoleIntent(RoleManager.ROLE_DIALER)
        } else Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
            .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, c.packageName)
    }.getOrNull()

    /**
     * Ber om å bli telefon-app. Returnerer false hvis stereoen ikke tillater det (eller T'OS allerede er det).
     * Fra Compose: bruk heller [dialerRoleIntent] med rememberLauncherForActivityResult.
     */
    fun requestDialerRole(activity: Activity): Boolean {
        if (isDialer(activity)) return false
        val i = dialerRoleIntent(activity) ?: return false
        return runCatching { activity.startActivityForResult(i, REQUEST_DIALER) }.isSuccess
    }
    const val REQUEST_DIALER = 4711

    /* ----- Ringe ----- */

    /** Telefonkontoene det kan ringes med (Bluetooth-telefonen når iPhonen er koblet til). Krever READ_PHONE_STATE. */
    @SuppressLint("MissingPermission")   // sjekkes rett over
    fun callAccounts(c: Context): List<PhoneAccountHandle> {
        if (!granted(c, Manifest.permission.READ_PHONE_STATE)) return emptyList()
        return runCatching { c.getSystemService(TelecomManager::class.java)?.callCapablePhoneAccounts.orEmpty() }.getOrDefault(emptyList())
    }

    /** Kan T'OS ringe selv akkurat nå? */
    fun canPlaceCalls(c: Context): Boolean = granted(c, Manifest.permission.CALL_PHONE) && callAccounts(c).isNotEmpty()

    /**
     * Ring [number]. Helst selv (TelecomManager.placeCall via Bluetooth-telefonen) – da viser T'OS samtalen
     * hvis T'OS er telefon-app. Mangler tillatelse: Telefon-siden åpnes med nummeret klart.
     * Finnes ingen telefonkonto (stereoens Bluetooth går utenom Android): stereoens telefon-app som før.
     */
    @SuppressLint("MissingPermission")   // CALL_PHONE sjekkes først
    fun call(c: Context, number: String) {
        val num = dialable(number)
        if (num.isEmpty() || num == "+") { Notify.show("Skriv inn et nummer først"); return }
        if (!granted(c, Manifest.permission.CALL_PHONE) || !granted(c, Manifest.permission.READ_PHONE_STATE)) {
            pendingDial = num; Nav.screen = Screen.PHONE
            Notify.show("T'OS trenger lov til å ringe. Trykk «Gi tilgang».", long = true)
            return
        }
        val accounts = callAccounts(c)
        if (accounts.isEmpty()) { callWithStereoApp(c, num); return }
        val extras = Bundle()
        if (accounts.size > 1) {
            val i = preferredAccount(accounts.map { it.componentName.packageName })
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accounts[i])
        }
        val ok = runCatching {
            c.getSystemService(TelecomManager::class.java)!!.placeCall(Uri.fromParts("tel", num, null), extras)
        }.isSuccess
        if (!ok) callWithStereoApp(c, num)
    }

    /** Reserve: stereoens egen Bluetooth-telefonapp med nummeret (det gamle oppsettet). */
    private fun callWithStereoApp(c: Context, num: String) {
        val tile = Tiles.byPrefix("Tel")
        val pkg = AppRepository.tilePackage(c, tile)
        Notify.show("Fant ingen telefon T'OS kan ringe med. Åpner stereoens telefon-app.", long = true)
        if (pkg != null && pkg != c.packageName) {
            AppRepository.markLaunched(pkg)
            val dial = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", num, null)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { c.startActivity(dial) }.isSuccess) return
        }
        AppRepository.launchTile(c, tile)
    }

    /** Åpner stereoens egen telefonapp (bare som reserve når T'OS ikke får tak i samtalene). */
    fun openStereoApp(c: Context) = AppRepository.launchTile(c, Tiles.byPrefix("Tel"))

    /* ----- Samtalen (styres via tjenesten) ----- */

    private fun live(id: Int) = service?.callById(id)

    fun answer(id: Int) { runCatching { live(id)?.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY) } }
    fun reject(id: Int) { runCatching { live(id)?.reject(false, null) } }
    fun hangUp(id: Int) { runCatching { live(id)?.disconnect() } }
    fun hold(id: Int) { runCatching { live(id)?.hold() } }
    fun unhold(id: Int) { runCatching { live(id)?.unhold() } }

    /** To samtaler: den som venter blir aktiv, den aktive settes på vent. */
    fun swap() {
        val held = calls.firstOrNull { it.state == CallState.HOLDING } ?: return
        val active = calls.firstOrNull { it.state == CallState.ACTIVE }
        if (active != null) hold(active.id)
        unhold(held.id)
    }

    fun setMuted(on: Boolean) { runCatching { service?.setMuted(on) } }
    @Suppress("DEPRECATION")   // erstatning (requestCallEndpointChange) finnes først i Android 14
    fun setRoute(r: Int) { runCatching { service?.setAudioRoute(r) } }

    fun dtmfDown(id: Int, ch: Char) { runCatching { live(id)?.playDtmfTone(ch) } }
    fun dtmfUp(id: Int) { runCatching { live(id)?.stopDtmfTone() } }

    /** Viser T'OS sin samtaleskjerm (fra banneret). */
    fun openCallScreen(c: Context) {
        if (calls.isEmpty()) return
        runCatching {
            c.startActivity(Intent(c, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    /* ----- Telefonbok og anropsliste ----- */

    private val nameCache = ConcurrentHashMap<String, String>()

    /** Navn i telefonboka for [number] (null = ukjent eller ingen tillatelse). Kjøres utenfor hovedtråden. */
    fun nameFor(c: Context, number: String): String? {
        if (number.isBlank() || !granted(c, Manifest.permission.READ_CONTACTS)) return null
        nameCache[number]?.let { return it }
        val name = runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            c.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (name != null) nameCache[number] = name
        return name
    }

    /** Hele telefonboka (navn + nummer), sortert på navn. Tom hvis stereoen ikke har kontaktene. Kjøres på IO. */
    fun contacts(c: Context): List<Contact> {
        if (!granted(c, Manifest.permission.READ_CONTACTS)) return emptyList()
        return runCatching {
            c.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC",
            )?.use { cur ->
                val out = ArrayList<Contact>(cur.count.coerceAtMost(5000))
                val seen = HashSet<String>()
                while (cur.moveToNext() && out.size < 5000) {
                    val num = cur.getString(1)?.trim().orEmpty()
                    if (num.isEmpty()) continue
                    val name = cur.getString(0)?.trim().orEmpty().ifBlank { num }
                    if (seen.add(name + "|" + normalizeNumber(num))) out += Contact(favText(name), favText(num))
                }
                out
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /** Siste anrop, nyeste først. Kjøres på IO. */
    fun recents(c: Context, limit: Int = 60): List<RecentCall> {
        if (!granted(c, Manifest.permission.READ_CALL_LOG)) return emptyList()
        return runCatching {
            c.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                null, null, CallLog.Calls.DATE + " DESC",
            )?.use { cur ->
                val out = mutableListOf<RecentCall>()
                while (cur.moveToNext() && out.size < limit) {
                    val num = cur.getString(1).orEmpty()
                    out += RecentCall(cur.getString(0), num, cur.getInt(2), cur.getLong(3), cur.getLong(4))
                }
                out
            }.orEmpty()
        }.getOrDefault(emptyList())
    }
}
