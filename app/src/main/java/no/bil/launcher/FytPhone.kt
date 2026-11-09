package no.bil.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.CallLog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ======================= BT-telefonen på FYT-stereoen (com.syu.ms, BT-modul 2) ======================= */

/*
 * iPhonen er koblet til FYT sin egen BT-brikke – Android (og dermed Telecom/TosInCallService) ser aldri samtalene.
 * Alt kommer fra BT-modulen i com.syu.ms (samme Binder som Fyt.kt). Her gjøres den om til T'OS sin vanlige
 * samtale ([PhoneCall] med id [FytPhone.CALL_ID]) – så CallScreen, samtalebanneret og Telefon-siden virker som før.
 *
 * Kodene (FinalBt – FYT sin egen konstantklasse com.syu.ipc.FinalBt, slik den ligger i
 * github.com/vasyl91/FYT-Launcher-Mod app/src/main/java/com/syu/ipc/FinalBt.java):
 *   U_ (tilbakemeldinger): 0 tittel, 1 artist, 2 sangens lengde (ms), 3 spor, 4 antall spor, 5 parede enheter, 6 telefonens
 *   MAC, 7 telefonens navn, 8 nummeret i samtalen, 9 telefontilstand, 10 «legg på og tøm nummer», 11 ringetid, 12 taletid,
 *   13 BT-musikk spiller, 14 egen MAC, 15 eget navn, 16 PIN, 17 BT-versjon, 18 HFP, 19 nullstilt, 20 svar automatisk,
 *   21 tid til automatisk svar, 22 telefonbok, 23 telefontype, 24 søkeliste, 25 paringsresultat, 26 album, 27 sjanger,
 *   28 posisjon i sangen (ms), 29 PBAP-tilstand (0 frakoblet, 1 tilkoblet, 2 laster), 30 AVRCP 1.4, 31 BT på, 32 mikrofonnivå,
 *   33 ringetone-prosent.
 *   C_ (kommandoer): 0 forrige, 1 neste, 2 spill/pause, 3 pause, 4 stopp, 5 hent parede, 6 tast (KEY_*), 7 ring (strs[0]),
 *   8 ring igjen, 9 svar, 10 legg på, 11 avvis, 12 nummer, 13 koble fra, 14 HFP, 15 tøm, 16 legg på og tøm, 17 nullstill,
 *   18 PIN, 19 eget navn, 20 synlig, 21 test, 22 mikrofonvolum, 23 mikrofonnivå, 24 koble til enhet, 25 koble til OBD,
 *   26 last ned telefonbok, 27 spill, 28 svar automatisk, 29 BT på, 30 ringetone-prosent.
 *   KEY_*: 0 ring, 1 legg på, 2–11 = sifrene 0–9, 12 «P», 13 «X», 14 «J».
 *   PHONE_STATE_*: 0 frakoblet, 1 kobler til, 2 tilkoblet, 3 ringer ut, 4 ringer inn, 5 samtale, 6 parer.
 * Bekreftet andre steder: github.com/Pacjonek/fyt-dialer (Apache-2.0; C_DIAL 7/C_PICKUP 9/C_HANG 10, U_PHONE_NAME 7,
 * U_PHONE_STATE 9, «bare når HFP er tilkoblet», testet på UIS7862) og FYT-Launcher-Mod com/fyt/car/StockBtSource.java +
 * com/android/launcher66/Launcher.java (BT-musikk: u=2 lengde i ms, u=28 posisjon i ms hvert sekund, u=26 album, u=13 = 1 spiller).
 * Kartleggingen 8. nov (denne stereoen): u=6 «F8D3F0D5EE6F», u=7 «iPhone», u=8 «» (ingen samtale), u=9 2, u=13 0, u=18 0,
 * u=29 1, u=31 1, u=11 [417, -1132380160] (ikke en ringetid – ukjent her, brukes ikke).
 *
 * UVERIFISERT (ingen åpen kilde viser innholdet): hvordan telefonboka (u=22) og parede enheter (u=5) ser ut – T'OS tolker dem
 * forsiktig (navn + nummer / navn + MAC i strs) og logger alt til kartleggingen; om C_KEY med sifrene gir tonevalg (DTMF) under
 * samtalen; om C_CONNECT_DEVICE tar MAC i strs[0]; om tegnene «X»/«J» er * og # (gjetning: kinesisk xing = stjerne, jing = #).
 * Stereoen sender ikke anropsliste: T'OS fører sin egen (fra tilstandene over). Ikke tatt med (ingen kilde): mikrofon av,
 * lyd over til telefonen, samtale på vent.
 */

/** En paret enhet ifølge FYT (u=5). */
data class FytDevice(val name: String, val mac: String)

/** Ren logikk for BT-telefonen på FYT. Testes i FytPhoneTest. */
internal object FytPhoneLogic {
    const val U_BTAV_TOTAL = 2
    const val U_PAIR_LIST = 5
    const val U_PHONE_MAC = 6
    const val U_PHONE_NUMBER = 8
    const val U_PHONE_STATE = 9
    const val U_HFP = 18
    const val U_AUTOPICK = 20
    const val U_BOOK = 22
    const val U_BTAV_ALBUM = 26
    const val U_BTAV_POS = 28
    const val U_PBAP_STATE = 29

    const val C_QUERY_PAIR = 5
    const val C_KEY = 6
    const val C_DIAL = 7
    const val C_LINK_CUT = 13
    const val C_CONNECT_DEVICE = 24
    const val C_DOWNLOAD_BOOK = 26

    /** Alle U_-kodene i FinalBt (0–33): T'OS lytter på alle, så kartleggingen viser hva stereoen faktisk sender. */
    val SUBSCRIBE: List<Int> = (0..33).toList()

    private val names = mapOf(0 to "tittel", 1 to "artist", 2 to "sanglengde", 3 to "spor", 4 to "antall spor", 5 to "parede enheter",
        6 to "telefonens MAC", 7 to "telefonens navn", 8 to "nummer", 9 to "telefontilstand", 10 to "lagt på", 11 to "ringetid",
        12 to "taletid", 13 to "BT-musikk spiller", 14 to "egen MAC", 15 to "eget navn", 16 to "PIN", 17 to "BT-versjon", 18 to "HFP",
        19 to "nullstilt", 20 to "svar automatisk", 21 to "tid til autosvar", 22 to "telefonbok", 23 to "telefontype", 24 to "søkeliste",
        25 to "paringsresultat", 26 to "album", 27 to "sjanger", 28 to "posisjon", 29 to "telefonbok-tilkobling", 30 to "AVRCP 1.4",
        31 to "BT på", 32 to "mikrofonnivå", 33 to "ringetone %")

    /** Navnet på BT-koden (til kartleggingen), «» for ukjente. */
    fun codeName(u: Int): String = names[u].orEmpty()

    /** Koder som kommer hvert sekund (logges ikke hver gang). */
    fun isTicker(u: Int) = u == U_BTAV_POS || u == 11 || u == 12 || u == 21 || u == 32 || u == 33

    /** FYT-tilstand → T'OS sin samtaletilstand (null = ingen samtale). */
    fun callStateOf(fyt: Int): CallState? = when (fyt) {
        FytLogic.PHONE_DIAL -> CallState.DIALING
        FytLogic.PHONE_RING -> CallState.RINGING
        FytLogic.PHONE_TALK -> CallState.ACTIVE
        else -> null
    }

    /** Én FYT-samtale slik T'OS følger den. Tider i System.currentTimeMillis. */
    data class Track(val number: String, val incoming: Boolean, val startedAt: Long, val connectedAt: Long,
                     val state: CallState, val rejected: Boolean = false)

    /** Resultatet av en ny tilstand: samtalen nå (null = ingen), og linja til anropslista når samtalen nettopp sluttet. */
    data class Step(val track: Track?, val finished: RecentCall?)

    fun step(t: Track?, fytState: Int, number: String?, now: Long): Step {
        val cs = callStateOf(fytState)
        val num = number?.trim()?.takeIf { it.isNotEmpty() }
        if (cs == null) {
            if (t == null) return Step(null, null)
            val type = when {
                t.connectedAt > 0 -> if (t.incoming) CallLog.Calls.INCOMING_TYPE else CallLog.Calls.OUTGOING_TYPE
                !t.incoming -> CallLog.Calls.OUTGOING_TYPE
                t.rejected -> CallLog.Calls.REJECTED_TYPE
                else -> CallLog.Calls.MISSED_TYPE
            }
            val dur = if (t.connectedAt > 0) ((now - t.connectedAt) / 1000).coerceAtLeast(0) else 0
            return Step(null, RecentCall(null, t.number, type, t.startedAt, dur))
        }
        if (t == null) return Step(Track(num.orEmpty(), incoming = cs == CallState.RINGING, startedAt = now,
            connectedAt = if (cs == CallState.ACTIVE) now else 0, state = cs), null)
        val conn = if (cs == CallState.ACTIVE && t.connectedAt == 0L) now else t.connectedAt
        return Step(t.copy(number = num ?: t.number, state = cs, connectedAt = conn), null)
    }

    /** Teksten når samtalen er over (samme ord som Telecom-samtalene). */
    fun endedText(type: Int): String = when (type) {
        CallLog.Calls.MISSED_TYPE -> "Tapt anrop"
        CallLog.Calls.REJECTED_TYPE -> "Avvist"
        else -> "Avsluttet"
    }

    /** Tast på tastaturet under samtalen → FYT KEY_* (null = finnes ikke). * og # er gjetning (se øverst). */
    fun dtmfKey(ch: Char): Int? = when (ch) {
        in '0'..'9' -> 2 + (ch - '0')
        '*' -> 13
        '#' -> 14
        else -> null
    }

    /** Ser ut som et telefonnummer: minst 3 sifre, og ellers bare + * # mellomrom, bindestrek, punktum og parenteser. */
    fun looksLikeNumber(s: String): Boolean {
        val t = s.trim()
        return t.count { it.isDigit() } >= 3 && t.all { it.isDigit() || it in "+*#-() ." }
    }

    /** MAC-adresse slik FYT sender den («F8D3F0D5EE6F») eller med kolon. */
    fun looksLikeMac(s: String): Boolean = Regex("^[0-9A-Fa-f]{12}$|^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$").matches(s.trim())

    /** «F8D3F0D5EE6F» → «F8:D3:F0:D5:EE:6F». */
    fun macText(s: String): String {
        val h = s.filter { it.isLetterOrDigit() }.uppercase()
        return if (h.length == 12) h.chunked(2).joinToString(":") else s.trim()
    }

    private val SPLIT = Regex("[\\n\\t|;\\u0001]+")
    private fun parts(strs: Array<String?>?): List<String> =
        strs.orEmpty().flatMap { it.orEmpty().split(SPLIT) }.map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Telefonbok-melding (u=22) → kontakter. Formatet er ukjent, så: hvert nummer hører til navnet før det
     * (eller etter, når nummeret kommer først). Tåler «navn, nummer», «nummer, navn», flere nummer per navn og
     * mange par i én melding. Nummer uten navn får nummeret som navn.
     */
    fun parseBook(strs: Array<String?>?): List<Contact> {
        val p = parts(strs)
        val out = ArrayList<Contact>()
        // Kommer nummeret først, gjelder det hele meldingen: «nummer, navn, nummer, navn …» (før fikk andre nummer
        // det første navnet)
        if (p.firstOrNull()?.let(::looksLikeNumber) == true) {
            var pending: String? = null
            for (s in p) {
                if (looksLikeNumber(s)) { pending?.let { out += Contact(favText(it), favText(it)) }; pending = s }
                else { pending?.let { out += Contact(favText(s), favText(it)) }; pending = null }
            }
            pending?.let { out += Contact(favText(it), favText(it)) }
            return out.filter { it.number.isNotBlank() }
        }
        var name: String? = null
        var orphan: String? = null       // nummer som kom før navnet
        for (s in p) {
            if (looksLikeNumber(s)) {
                if (name != null) out += Contact(favText(name), favText(s))
                else { orphan?.let { out += Contact(favText(it), favText(it)) }; orphan = s }
            } else {
                if (orphan != null && name == null) { out += Contact(favText(s), favText(orphan)); orphan = null; name = s; continue }
                name = s
            }
        }
        orphan?.let { out += Contact(favText(it), favText(it)) }
        return out.filter { it.number.isNotBlank() }
    }

    /** Lista med [add] lagt til (samme navn og nummer bare én gang), sortert på navn. Høyst [max]. */
    fun mergeBook(old: List<Contact>, add: List<Contact>, max: Int = 3000): List<Contact> {
        if (add.isEmpty()) return old
        val seen = HashSet<String>()
        return (old + add).filter { seen.add(it.name.lowercase() + "|" + normalizeNumber(it.number)) }
            .sortedBy { it.name.lowercase() }.take(max)
    }

    /** Parede enheter (u=5): navn og MAC i strs, i hvilken som helst rekkefølge. Bare de med MAC. */
    fun parseDevices(strs: Array<String?>?): List<FytDevice> {
        val p = parts(strs)
        val out = ArrayList<FytDevice>()
        var name: String? = null
        var mac: String? = null
        for (s in p) {
            if (looksLikeMac(s)) {
                when {
                    name != null -> { out += FytDevice(name, s); name = null }
                    else -> { mac?.let { out += FytDevice(macText(it), it) }; mac = s }
                }
            } else if (mac != null) { out += FytDevice(s, mac); mac = null } else name = s
        }
        if (mac != null) out += FytDevice(name ?: macText(mac), mac)
        return out
    }

    fun mergeDevices(old: List<FytDevice>, add: List<FytDevice>): List<FytDevice> =
        (old.filter { o -> add.none { macText(it.mac) == macText(o.mac) } } + add).take(10)

    /** Del av sangen som er spilt (0–1), 0 når lengden er ukjent. */
    fun progress(posMs: Long, totalMs: Long): Float = if (totalMs <= 0) 0f else (posMs.toFloat() / totalMs).coerceIn(0f, 1f)

    /* ----- Lagring (Prefs-tekst, én linje per oppføring) ----- */

    fun encodeBook(list: List<Contact>): String = list.joinToString("\n") { "${favText(it.name)}|${favText(it.number)}" }
    fun decodeBook(s: String): List<Contact> = s.split("\n").mapNotNull {
        val p = it.split("|"); if (p.size == 2 && p[1].isNotBlank()) Contact(p[0], p[1]) else null
    }

    fun encodeLog(list: List<RecentCall>): String =
        list.joinToString("\n") { "${it.type}|${it.date}|${it.durationSec}|${favText(it.number)}|${favText(it.name.orEmpty())}" }
    fun decodeLog(s: String): List<RecentCall> = s.split("\n").mapNotNull {
        val p = it.split("|")
        if (p.size < 4) return@mapNotNull null
        val type = p[0].toIntOrNull() ?: return@mapNotNull null
        val date = p[1].toLongOrNull() ?: return@mapNotNull null
        RecentCall(p.getOrNull(4)?.takeIf { n -> n.isNotBlank() }, p[3], type, date, p[2].toLongOrNull() ?: 0)
    }

    /** Ny linje først, høyst [max]. */
    fun addToLog(log: List<RecentCall>, r: RecentCall, max: Int = 60): List<RecentCall> = (listOf(r) + log).take(max)
}

/** BT-telefonen og BT-musikken på FYT-stereoen, som Compose-tilstand. Alt gjør ingenting uten FYT. */
object FytPhone {
    /** Id-en FYT-samtalen har i [Phone.calls] (Telecom bruker positive tall). */
    const val CALL_ID = -7002
    const val BT_PKG = "com.syu.bt"

    private val main = Handler(Looper.getMainLooper())

    /** Er T'OS sin telefon via FYT i bruk (FYT-tjenesten svarer og bryteren er på)? */
    val enabled: Boolean get() = Fyt.btReady && runCatching { Prefs.bool("fyt_phone", true) }.getOrDefault(true)
    /** Kan T'OS ringe via stereoen nå? */
    val canDial: Boolean get() = enabled && Fyt.phoneConnected == true

    /** Samtalen FYT vet om (null = ingen). Leses av [Phone.calls]. */
    var call by mutableStateOf<PhoneCall?>(null)
        internal set
    /** Telefonboka FYT har sendt (lagres i T'OS, så den er der etter omstart). */
    var contacts by mutableStateOf<List<Contact>>(emptyList())
        private set
    /** T'OS sin egen anropsliste for FYT-samtaler (stereoen sender ingen). */
    var callLog by mutableStateOf<List<RecentCall>>(emptyList())
        private set
    var devices by mutableStateOf<List<FytDevice>>(emptyList())
        private set
    var phoneMac by mutableStateOf<String?>(null)
        private set
    /** PBAP (telefonboka): 0 frakoblet, 1 tilkoblet, 2 laster, −1 ukjent. */
    var bookState by mutableIntStateOf(-1)
        private set
    var btavAlbum by mutableStateOf<String?>(null)
        private set
    var btavTotalMs by mutableLongStateOf(0L)
        private set
    var btavPosMs by mutableLongStateOf(0L)
        private set

    private var track: FytPhoneLogic.Track? = null
    private var loaded = false
    /** Brukeren trykket «Skjul» under denne samtalen: ikke hent samtaleskjermen fram igjen av seg selv. */
    @Volatile var userHid = false
        private set
    /**
     * Høyst hvert 1,5 s, og høyst 4 ganger på 30 s (så 60 s pause): kjemper stereoens BT-app og T'OS sin
     * samtaleskjerm om skjermen, skal det ikke bli en evig løkke – da får stereoens samtalebilde stå.
     */
    private val coverLimiter = RedirectLimiter(minGapMs = 1500, maxInWindow = 4, windowMs = 30_000, pauseMs = 60_000)
    private var pendingDial: String? = null
    private var pendingDialAt = 0L
    private val nameCache = HashMap<String, String?>()
    private var bookMessages = 0
    private val bookSamples = ArrayList<String>()

    /** Last lagret telefonbok/anropsliste (én gang). Tåler at Prefs ikke er klar (testene). */
    fun ensureLoaded() {
        if (loaded) return
        runCatching {
            contacts = FytPhoneLogic.decodeBook(Prefs.str("fyt_book"))
            callLog = FytPhoneLogic.decodeLog(Prefs.str("fyt_calls"))
            loaded = true
        }
    }

    /** Ny melding fra BT-modulen (hovedtråden, fra Fyt.apply – etter at Fyt har lest sine egne felt). */
    internal fun onBt(u: Int, ints: IntArray?, strs: Array<String?>?) {
        ensureLoaded()
        val i0 = ints?.firstOrNull()
        when (u) {
            FytPhoneLogic.U_PHONE_MAC -> phoneMac = strs?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: phoneMac
            FytPhoneLogic.U_PHONE_NUMBER -> {
                val n = FytLogic.phoneName(strs)
                val t = track
                if (n != null && t != null && n != t.number) { track = t.copy(number = n); publish() }
            }
            FytPhoneLogic.U_PHONE_STATE -> FytLogic.phoneStateOf(ints)?.let { onState(it) }
            FytPhoneLogic.U_BTAV_TOTAL -> if (i0 != null) btavTotalMs = i0.toLong().coerceAtLeast(0)
            FytPhoneLogic.U_BTAV_POS -> if (i0 != null) btavPosMs = i0.toLong().coerceAtLeast(0)
            FytPhoneLogic.U_BTAV_ALBUM -> btavAlbum = strs?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            FytPhoneLogic.U_PBAP_STATE -> if (i0 != null) bookState = i0
            FytPhoneLogic.U_PAIR_LIST -> {
                val d = FytPhoneLogic.parseDevices(strs)
                if (d.isNotEmpty()) devices = FytPhoneLogic.mergeDevices(devices, d)
            }
            FytPhoneLogic.U_BOOK -> {
                bookMessages++
                if (bookSamples.size < 6) bookSamples += FytLogic.payload(ints, null, strs)
                val add = FytPhoneLogic.parseBook(strs)
                if (add.isNotEmpty()) { contacts = FytPhoneLogic.mergeBook(contacts, add); saveBookSoon() }
            }
        }
    }

    private fun onState(st: Int) {
        val now = System.currentTimeMillis()
        val before = track
        // Utgående samtale fra T'OS: FYT sender ofte nummeret senere (eller ikke) – bruk det T'OS ringte
        val dialled = pendingDial?.takeIf { st == FytLogic.PHONE_DIAL && SystemClock.elapsedRealtime() - pendingDialAt < 15_000 }
        val step = FytPhoneLogic.step(before, st, Fyt.phoneNumber ?: dialled ?: before?.number, now)
        track = step.track
        if (before == null && step.track != null) {
            userHid = false; pendingDial = null
            publish()
            if (enabled && runCatching { Prefs.bool("fyt_call_screen", true) }.getOrDefault(true))
                // Fra knappestyringen når den kjører (Android 10 lar da T'OS åpne skjermen fra bakgrunnen)
                (KeyService.instance ?: Fyt.appContext)?.let { c -> main.post { Phone.openCallScreen(c) } }
        } else if (step.track != null) publish()
        step.finished?.let { r ->
            val name = nameOf(r.number)
            callLog = FytPhoneLogic.addToLog(callLog, r.copy(name = name))
            runCatching { Prefs.setQuiet("fyt_calls", FytPhoneLogic.encodeLog(callLog)) }
            val shown = call
            call = shown?.copy(state = CallState.ENDED, ended = FytPhoneLogic.endedText(r.type))
            // Vis «Avsluttet» et øyeblikk, så forsvinner samtalen (samtaleskjermen lukker seg selv)
            main.postDelayed({ if (track == null) call = null }, 1500)
        }
    }

    /** Lag [PhoneCall] av samtalen T'OS følger. */
    private fun publish() {
        val t = track ?: return
        // Bryteren «Telefonen i T'OS (FYT)» er av: stereoen viser samtalen selv (T'OS bare med det lille banneret)
        if (!enabled) { call = null; return }
        call = PhoneCall(CALL_ID, t.number, nameOf(t.number), t.state, t.incoming, t.connectedAt, canHold = false)
    }

    /** Navnet til [number]: FYT-telefonboka, favorittene, ellers Androids telefonbok (slås opp i bakgrunnen). */
    private fun nameOf(number: String): String? {
        if (number.isBlank()) return null
        contacts.firstOrNull { numbersMatch(it.number, number) }?.let { return it.name }
        runCatching { Favorites.all().firstOrNull { numbersMatch(it.number, number) } }.getOrNull()?.let { return it.name }
        if (nameCache.containsKey(number)) return nameCache[number]
        nameCache[number] = null
        val c = Fyt.appContext ?: return null
        Hub.scope.launch {
            val n = withContext(Dispatchers.IO) { runCatching { Phone.nameFor(c, number) }.getOrNull() }
            if (n != null) { nameCache[number] = n; if (track?.number == number) publish() }
        }
        return null
    }

    private val saveBook = Runnable { runCatching { Prefs.setQuiet("fyt_book", FytPhoneLogic.encodeBook(contacts)) } }
    private fun saveBookSoon() { main.removeCallbacks(saveBook); main.postDelayed(saveBook, 3000) }

    /* ---------- Kommandoer ---------- */

    /** Ring via stereoen. true = kommandoen kom fram (stereoen ringer bare når telefonen er tilkoblet). */
    fun dial(number: String): Boolean {
        val ok = Fyt.dial(number)
        if (ok) { pendingDial = number; pendingDialAt = SystemClock.elapsedRealtime() }
        return ok
    }

    fun answer(): Boolean = Fyt.answer()
    fun reject(): Boolean { track = track?.copy(rejected = true); return Fyt.reject() }
    fun hangUp(): Boolean = if (call?.state == CallState.RINGING) reject() else Fyt.hangUp()

    /** Tonevalg under samtalen (UVERIFISERT: C_KEY med sifferet). */
    fun dtmf(ch: Char): Boolean = FytPhoneLogic.dtmfKey(ch)?.let { Fyt.btCmd(FytPhoneLogic.C_KEY, intArrayOf(it)) } ?: false

    /** Be telefonen sende telefonboka til stereoen (C_DOWNLOAD_BOOK). */
    fun downloadBook(): Boolean = enabled && Fyt.btCmd(FytPhoneLogic.C_DOWNLOAD_BOOK)
    /** Be om lista over parede enheter (C_QUERY_PAIR). */
    fun queryDevices(): Boolean = enabled && Fyt.btCmd(FytPhoneLogic.C_QUERY_PAIR)
    /** Koble telefonen fra stereoen (C_LINK_CUT). */
    fun disconnect(): Boolean = enabled && Fyt.btCmd(FytPhoneLogic.C_LINK_CUT)
    /** Koble til [mac] (UVERIFISERT: C_CONNECT_DEVICE med MAC-en slik FYT selv sender den). */
    fun connect(mac: String): Boolean = enabled && Fyt.btCmd(FytPhoneLogic.C_CONNECT_DEVICE, strs = arrayOf(mac.filter { it.isLetterOrDigit() }.uppercase()))

    /**
     * Tilkoblingen til stereoen er borte (Fyt.dropLink): en FYT-samtale T'OS viste kan ikke lenger følges – ta den
     * bort, så samtaleskjermen/banneret ikke blir stående for alltid. (Kobler stereoen til igjen midt i en samtale,
     * melder den tilstanden på nytt, og samtalen vises igjen.)
     */
    internal fun onLinkLost() {
        track = null
        if (call != null) call = null
    }

    /** «Skjul» på samtaleskjermen. */
    fun onUserHide() { if (call != null) userHid = true }

    /**
     * Knappestyringen: stereoens BT-app (com.syu.bt) kom fram mens FYT har en samtale – vis T'OS sin samtaleskjerm
     * i stedet. Ikke når brukeren selv skjulte samtaleskjermen, og høyst hvert 1,5 s.
     */
    fun shouldCover(pkg: String): Boolean {
        if (pkg != BT_PKG || !enabled) return false
        val c = call ?: return false
        if (c.state == CallState.ENDED || userHid || Phone.callScreenVisible) return false
        if (!runCatching { Prefs.bool("fyt_call_screen", true) }.getOrDefault(true)) return false
        return coverLimiter.allow(SystemClock.elapsedRealtime())
    }

    /** Til kartleggingen. */
    fun report(): String = buildString {
        appendLine("T'OS-telefon via FYT: ${if (enabled) "på" else "av"}  telefon tilkoblet=${Fyt.phoneConnected}  MAC=${phoneMac?.let { FytPhoneLogic.macText(it) } ?: "?"}")
        appendLine("Samtale nå: ${call?.let { "${it.state} «${it.number}» ${it.name.orEmpty()}" } ?: "ingen"}")
        appendLine("Telefonbok (u=22): $bookMessages meldinger, ${contacts.size} kontakter lagret i T'OS, PBAP-tilstand=$bookState")
        bookSamples.forEach { appendLine("  eksempel: $it") }
        appendLine("Parede enheter (u=5): " + (if (devices.isEmpty()) "ingen tolket" else devices.joinToString { "${it.name} ${FytPhoneLogic.macText(it.mac)}" }))
        appendLine("Anropsliste i T'OS: ${callLog.size} samtaler")
        appendLine("BT-musikk: album=«${btavAlbum.orEmpty()}» posisjon=${btavPosMs} ms av ${btavTotalMs} ms")
    }

    /** Bare til testene: sett samtalen direkte. */
    internal fun setCallForTest(c: PhoneCall?) { call = c; track = null; userHid = false }
}

/**
 * Musikken fra telefonen spiller via stereoens BT-brikke (lydkilde 3 = BT-musikk), og T'OS viser den. Musikkortet og
 * knappene (Media.playPause/next/previous) bruker begge denne, så kortet aldri viser BT-musikken mens knappene styrer en
 * (pauset) Spotify-økt. Spiller en Android-app nå (ikke FYT sin egen), er det den som vises og styres.
 */
internal val fytBtMusicShown: Boolean get() = Fyt.btReady && Fyt.appId == 3 && !NetRadio.active &&
    !(NowPlaying.playing && NowPlaying.pkg?.startsWith("com.syu.") != true)

/**
 * Stereoens Bluetooth på Bluetooth-siden: telefonen FYT er koblet til, koble fra/til, og parede enheter.
 * Viser ingenting uten FYT.
 */
@Composable
fun FytBtCard(modifier: Modifier = Modifier) {
    if (!FytPhone.enabled) return
    LaunchedEffect(Unit) { FytPhone.queryDevices() }
    val connected = Fyt.phoneConnected == true
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Line).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Stereoens Bluetooth", color = TextMuted, fontSize = 13.sp)
        Text(if (connected) "${Fyt.phoneName ?: "Telefonen"} er tilkoblet" else "Ingen telefon tilkoblet", color = TextPrimary, fontSize = 17.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        val others = FytPhone.devices.filter { d -> FytPhone.phoneMac?.let { FytPhoneLogic.macText(it) } != FytPhoneLogic.macText(d.mac) }
        others.take(3).forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.name, color = TextMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (!connected) Pill("Koble til", Icons.Default.Link) {
                    Notify.show(if (FytPhone.connect(d.mac)) "Kobler til ${d.name} …" else "Fikk ikke kontakt med stereoen")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (connected) Pill("Koble fra", Icons.Default.LinkOff) {
                Notify.show(if (FytPhone.disconnect()) "Kobler fra …" else "Fikk ikke kontakt med stereoen")
            } else FytPhone.phoneMac?.let { mac ->
                Pill("Koble til igjen", Icons.Default.Link, strong = true) {
                    Notify.show(if (FytPhone.connect(mac)) "Kobler til …" else "Fikk ikke kontakt med stereoen")
                }
            }
            if (connected) Pill("Hent kontakter", Icons.Default.Contacts) { fytDownloadBook() }
        }
    }
}

/** «Hent kontakter fra telefonen» (felles for Telefon-siden og Bluetooth-siden). */
internal fun fytDownloadBook() {
    Notify.show(
        if (FytPhone.downloadBook()) "Henter kontaktene fra telefonen … Godta på iPhonen hvis den spør." else "Fikk ikke kontakt med stereoen",
        long = true,
    )
}
