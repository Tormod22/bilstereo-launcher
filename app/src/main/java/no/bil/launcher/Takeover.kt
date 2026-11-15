package no.bil.launcher

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * «Ta over stereoen» – uten PC.
 *
 * Er «USB-feilsøking» på og lytter stereoens ADB på nettverksport 5555 (127.0.0.1:5555), kobler T'OS seg til sin EGEN ADB
 * med biblioteket dadb (Apache-2.0, se docs/TREDJEPART.md) og kjører de samme kommandoene som PC-oppsettet
 * ([adbSetupCommands] – ÉN liste for begge veiene), skjuler FYT-linja, gjør T'OS til enhetseier («Full kontroll») og slår
 * feilsøkingen av igjen. Android viser ett vindu: «Tillat USB-feilsøking?» → «Tillat alltid» + OK.
 *
 * UVERIFISERT på stereoen: om FYT-firmwaren åpner port 5555 når USB-feilsøking er på (persist.sys.usb.config=none i
 * kartleggingen), om dialogen kommer for en tilkobling fra 127.0.0.1, og om Google-trikset (Play-tjenester av et øyeblikk)
 * lar dpm sette enhetseier med Google-konto på.
 */

/** Ren logikk (testes i TakeoverLogicTest). Ingen Android-kall. */
object TakeoverLogic {
    const val HOST = "127.0.0.1"
    const val PORT = 5555
    const val GMS = "com.google.android.gms"
    const val NAV_KEY = "show_navigationbar"

    /** Prefs: gammel verdi for FYT-linja (angre), Play-tjenester slått av (må på igjen), ferdig, slå av feilsøking etterpå. */
    const val NAV_PREV = "takeover_nav_prev"
    const val GMS_MARK = "takeover_gms_off"
    const val DONE = "takeover_done"
    const val DONE_AT = "takeover_done_at"
    const val ADB_OFF = "takeover_adb_off"
    const val LOG_FILE = "takeover-siste.txt"

    /**
     * Kommandoene T'OS kjører (uten «adb shell» foran). Samme liste som PC-oppsettet ([adbSetupCommands] i SettingsExtra.kt,
     * docs/ADB-OPPSETT.md, docs/tos-oppsett.bat). FYT-linja tas for seg selv (gammel verdi huskes for «Angre»).
     */
    internal fun commands(pkg: String, accessibility: String): List<AdbStep> =
        adbSetupCommands(pkg, accessibility)
            .filter { NAV_KEY !in it.cmd }
            .map { it.copy(cmd = it.cmd.removePrefix("adb shell ").trim()) }
            // Lista med tjenester kommer fra stereoen og kan inneholde tegn skallet tolker (f.eks. «$» i en indre klasse,
            // com.x/.A$B): uten anførselstegn ville stereoens egne tjenester blitt ødelagt. Samme som tos-oppsett.bat gjør.
            .map { if (it.cmd.startsWith(ACC_PUT)) it.copy(cmd = ACC_PUT + shQuote(accessibility)) else it }

    private const val ACC_PUT = "settings put secure enabled_accessibility_services "

    /** Én verdi trygt til stereoens skall (enkle anførselstegn; et «'» inni blir '\''). */
    fun shQuote(v: String): String = "'" + v.replace("'", "'\\''") + "'"

    fun ownerCommand(pkg: String) = "dpm set-device-owner $pkg/.TosAdmin"

    enum class Result(val sign: String) { OK("✓"), SKIP("–"), FAIL("✗") }

    /** Svar fra én kommando. «Finnes ikke på denne Android-versjonen» er ikke en feil (gjelder f.eks. Android 12+-tillatelser). */
    fun classify(exit: Int, out: String): Result {
        val o = out.lowercase(Locale.ROOT)
        val notHere = listOf(
            "unknown permission", "unknown operation", "not a changeable permission type", "unknown command",
            "has not requested permission", "unknown option", "can't find service", "no shell command implementation",
        )
        if (notHere.any { it in o }) return Result.SKIP
        if (exit != 0 || "exception" in o || "error" in o || "failure" in o || "not allowed" in o) return Result.FAIL
        return Result.OK
    }

    enum class Dpm { OK, ALREADY, ACCOUNTS, USERS, OTHER }

    /** Svaret fra «dpm set-device-owner». */
    fun dpmResult(out: String): Dpm {
        val o = out.lowercase(Locale.ROOT)
        return when {
            "success" in o -> Dpm.OK
            "already set" in o || "already has a device owner" in o || "device owner is already" in o -> Dpm.ALREADY
            "several users" in o || "users on the device" in o -> Dpm.USERS
            "account" in o -> Dpm.ACCOUNTS
            else -> Dpm.OTHER
        }
    }

    /** Kontotyper fra «dumpsys account» (navnet – e-postadressen – tas aldri med, bare typen, f.eks. com.google). */
    fun accountTypes(dump: String): List<String> =
        Regex("""Account \{name=[^,]*, type=([^}\s]+)\}""").findAll(dump).map { it.groupValues[1] }.distinct().toList()

    /** Antall brukere fra «pm list users». */
    fun userCount(list: String): Int = Regex("""UserInfo\{""").findAll(list).count()

    enum class OwnerPlan { DIRECT, GOOGLE_TRICK, MANUAL }

    /**
     * Får Play-tjenester slås av et øyeblikk? Bare når dpm FØRST har sagt nei på grunn av kontoer (prøves alltid rett på
     * først – da står Google aldri av uten grunn), og bare når alle kontoene er Google (eller typen ikke kunne leses).
     */
    fun googleTrickAllowed(types: List<String>): Boolean =
        types.isEmpty() || types.all { it == "com.google" || it.startsWith("com.google.") }

    /**
     * Ingen kontoer: rett på. Bare Google-kontoer: Play-tjenester av et øyeblikk (da ser ikke Android kontoen), dpm, Play-tjenester
     * på igjen. Andre kontoer: brukeren må fjerne dem selv (knapp til Android-siden).
     */
    fun ownerPlan(types: List<String>): OwnerPlan = when {
        types.isEmpty() -> OwnerPlan.DIRECT
        types.all { it == "com.google" || it.startsWith("com.google.") } -> OwnerPlan.GOOGLE_TRICK
        else -> OwnerPlan.MANUAL
    }

    /** «pm enable»/«pm disable-user» svarer «Package … new state: enabled/disabled-user». */
    fun gmsEnabled(out: String): Boolean = Regex("""new state:\s*enabled\b""").containsMatchIn(out)

    /** Portnummer fra service.adb.tcp.port / persist.adb.tcp.port (null = ikke satt). */
    fun tcpPort(vararg props: String?): Int? = props.mapNotNull { it?.trim()?.toIntOrNull() }.firstOrNull { it in 1..65535 }

    enum class Net { OPEN, ADB_OFF, NO_NETWORK_ADB }
    fun net(open: Boolean, adbOn: Boolean): Net = when { open -> Net.OPEN; !adbOn -> Net.ADB_OFF; else -> Net.NO_NETWORK_ADB }

    /** Verdien FYT-linja settes tilbake til ved «Angre» (stereoen hadde 1). */
    fun navUndo(prev: String?): String = prev?.trim()?.takeIf { Regex("""-?\d{1,4}""").matches(it) } ?: "1"

    /** Ble Play-tjenester stående av? enabledSetting fra PackageManager (2/3/4 = av). */
    fun gmsDisabled(enabledSetting: Int): Boolean = enabledSetting in 2..4

    /** Klar tekst når tilkoblingen feiler. */
    fun connectError(kind: String, msg: String?): String {
        val m = msg.orEmpty().lowercase(Locale.ROOT)
        return when {
            kind.contains("Timeout", ignoreCase = true) || "timed out" in m ->
                "Stereoen svarte ikke. Kom vinduet «Tillat USB-feilsøking?»? Trykk «Tillat alltid …» og «OK», og trykk «Koble til» igjen."
            kind.contains("Connect", ignoreCase = true) || "refused" in m ->
                "Fant ingen ADB på stereoen (port 5555). Se punkt 3."
            "auth" in m || "closed" in m || kind.contains("EOF", ignoreCase = true) ->
                "Stereoen sa nei. Trykk «Koble til» igjen, og trykk «Tillat» når stereoen spør."
            else -> "Fikk ikke koblet til ($kind). Prøv igjen."
        }
    }

    /** Hvor mange av de fem punktene som er ferdige. */
    fun progress(dev: Boolean, adb: Boolean, net: Boolean, connected: Boolean, done: Boolean): Int =
        if (done) 5 else listOf(dev, adb, net, connected).count { it }
}

/** Kjører «Ta over». All ADB-trafikk går på en egen bakgrunnstråd; ingenting her kan krasje T'OS. */
object Takeover {
    data class Line(val result: TakeoverLogic.Result, val text: String, val detail: String = "")

    val log = mutableStateListOf<Line>()
    var running by mutableStateOf(false); private set
    var connecting by mutableStateOf(false); private set
    var connected by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var phase by mutableStateOf<String?>(null); private set
    var accountsBlocked by mutableStateOf(false); private set
    /** Sist målt: port 5555 åpen (siden sjekker hvert sekund). */
    @Volatile var portOpenLast: Boolean? = null; private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var adb: Dadb? = null

    fun devOn(c: Context) = runCatching { Settings.Global.getInt(c.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }.getOrDefault(false)
    fun adbOn(c: Context) = runCatching { Settings.Global.getInt(c.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)
    val done: Boolean get() = Prefs.bool(TakeoverLogic.DONE)
    val gmsOff: Boolean get() = Prefs.bool(TakeoverLogic.GMS_MARK)
    /** Kobler til eller kjører nå (knappestyringen rører da ikke SystemUI-vinduer – «Tillat USB-feilsøking?» kan ligge der). */
    val busy: Boolean get() = running || connecting || healing
    @Volatile private var healing = false

    /** Lytter stereoens ADB på 127.0.0.1:5555? Blokkerer maks 300 ms – kall fra en bakgrunnstråd. */
    fun portOpenBlocking(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(TakeoverLogic.HOST, TakeoverLogic.PORT), 300); true }
    }.getOrDefault(false).also { portOpenLast = it }

    suspend fun portOpen(): Boolean = withContext(Dispatchers.IO) { portOpenBlocking() }

    @android.annotation.SuppressLint("PrivateApi")   // samme som Fyt.kt: lese systemegenskaper (uendret siden Android 1)
    private fun prop(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String
    }.getOrNull().orEmpty()

    fun tcpProps(): String = "service.adb.tcp.port=${prop("service.adb.tcp.port").ifEmpty { "–" }}, " +
        "persist.adb.tcp.port=${prop("persist.adb.tcp.port").ifEmpty { "–" }}, sys.usb.config=${prop("sys.usb.config").ifEmpty { "–" }}"

    /** Med WRITE_SECURE_SETTINGS (gitt ved en tidligere «Ta over») kan T'OS slå feilsøkingen på selv. */
    fun turnOnAdbSelf(c: Context): Boolean = Owner.canWriteSecure(c) && runCatching {
        Settings.Global.putInt(c.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
        Settings.Global.putInt(c.contentResolver, Settings.Global.ADB_ENABLED, 1)
    }.isSuccess

    /** T'OS sin egen ADB-nøkkel (lages første gang, ligger i appens private mappe). */
    private fun keyPair(c: Context): AdbKeyPair {
        val dir = File(c.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "adbkey"); val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    private fun closeQuiet() { runCatching { adb?.close() }; adb = null }

    private suspend fun ui(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    /** Har T'OS koblet til før (nøkkelen finnes, og stereoen har trolig sagt «Tillat alltid»)? */
    private fun hasKey(c: Context) = File(File(c.filesDir, "adb"), "adbkey").exists()

    /**
     * Kobler til uten å vise noe (brukes for å slå Play-tjenester på igjen): bare med en nøkkel stereoen har godtatt før, og bare
     * når porten er åpen – eller T'OS kan slå feilsøkingen på selv (WRITE_SECURE_SETTINGS). Kort ventetid, så Android ikke spør.
     */
    private suspend fun connectQuiet(ctx: Context): Boolean {
        if (adb != null) return true
        if (!hasKey(ctx)) return false
        if (!portOpenBlocking()) {
            if (!turnOnAdbSelf(ctx)) return false
            delay(2_500)
            if (!portOpenBlocking()) return false
        }
        val d = runCatching { Dadb.create(TakeoverLogic.HOST, TakeoverLogic.PORT, keyPair(ctx), 3_000, 15_000) }.getOrNull() ?: return false
        val ok = runCatching { d.shell("echo tos").allOutput.trim() == "tos" }.getOrDefault(false)
        if (!ok) { runCatching { d.close() }; return false }
        adb = d
        return true
    }

    /** Er Play-tjenester slått av nå? (Leses fra Android, uten ADB.) Finnes ikke Google på stereoen: nei. */
    fun gmsDisabledNow(c: Context): Boolean = runCatching {
        TakeoverLogic.gmsDisabled(c.packageManager.getApplicationEnabledSetting(TakeoverLogic.GMS))
    }.getOrDefault(false)

    /**
     * Slår Play-tjenester på igjen hvis «Ta over» ble avbrutt mens de var av (krasj, oppdatering, tenning av, forbindelsen
     * brøt). Uavhengig av siden: kobler til selv. true = de er på (merket fjernet).
     */
    private suspend fun healGmsNow(ctx: Context): Boolean {
        if (!gmsOff) return true
        if (!gmsDisabledNow(ctx)) { Prefs.set(TakeoverLogic.GMS_MARK, false); return true }
        val hadConnection = adb != null
        if (!connectQuiet(ctx)) return false
        val ok = enableGmsNow() || !gmsDisabledNow(ctx).also { if (!it) Prefs.set(TakeoverLogic.GMS_MARK, false) }
        // Koblet vi til bare for dette, og ingen bruker siden: lukk igjen
        if (!hadConnection && !running && !connected) closeQuiet()
        return ok
    }

    /**
     * Ved oppstart: står merket «Play-tjenester av», prøves det å slå dem på igjen – straks og så hvert minutt i en halvtime
     * (stereoen kan ha vært i dvale, eller feilsøkingen kan være av). Viser en melding om hva som skjer.
     */
    fun healOnStart(c: Context) {
        if (!gmsOff) return
        val ctx = c.applicationContext
        scope.launch {
            delay(5_000)
            var told = false
            repeat(30) {
                if (!gmsOff) return@launch
                if (!running && !connecting) {
                    healing = true
                    val ok = try { runCatching { healGmsNow(ctx) }.getOrDefault(false) } finally { healing = false }
                    if (ok) { ui { Notify.show("Google Play-tjenester er på igjen", true) }; return@launch }
                    if (!told) {
                        told = true
                        ui { Notify.show("Google Play-tjenester er av etter «Ta over». Åpne Ta over stereoen og trykk «Slå på Play-tjenester».", true) }
                    }
                }
                delay(60_000)
            }
        }
    }

    /** Siden «Ta over stereoen» lukkes: forbindelsen lukkes (ingen åpen ADB-forbindelse i bakgrunnen). */
    fun leave() {
        if (running || connecting || healing) return
        closeQuiet(); connected = false
    }

    /** Kobler til stereoens egen ADB. Første gang spør Android «Tillat USB-feilsøking?» – brukeren har 60 s. */
    fun connect(c: Context) {
        if (connecting || running) return
        val ctx = c.applicationContext
        connecting = true
        message = "Kobler til … Spør stereoen «Tillat USB-feilsøking?», trykk «Tillat alltid …» og «OK»."
        scope.launch {
            val r = runCatching {
                closeQuiet()
                val d = Dadb.create(TakeoverLogic.HOST, TakeoverLogic.PORT, keyPair(ctx), 3_000, 60_000)
                val out = d.shell("echo tos").allOutput.trim()
                if (out != "tos") { runCatching { d.close() }; error("uventet svar: ${out.take(60)}") }
                d
            }
            ui {
                connecting = false
                r.onSuccess { adb = it; connected = true; message = null }
                    .onFailure { connected = false; message = TakeoverLogic.connectError(it.javaClass.simpleName, it.message) }
            }
            // Sto Play-tjenester av fra en avbrutt kjøring? Slå dem på med en gang nå som vi er tilkoblet.
            if (r.isSuccess && gmsOff) {
                val ok = runCatching { enableGmsNow() }.getOrDefault(false)
                if (ok) ui { Notify.show("Google Play-tjenester er på igjen") }
            }
        }
    }

    /** Én kommando i stereoens skall. Mister vi forbindelsen, merkes det (brukeren kobler til igjen). */
    private suspend fun sh(cmd: String): Pair<Int, String> {
        val d = adb ?: return -1 to "ikke tilkoblet"
        return try {
            val r = d.shell(cmd)
            r.exitCode to r.allOutput.trim()
        } catch (e: java.io.IOException) {
            closeQuiet(); ui { connected = false }
            -1 to "forbindelsen brøt (${e.javaClass.simpleName})"
        }
    }

    /** «Ta over»: alle kommandoene, FYT-linja, enhetseier, feilsøking av. Stopper aldri hele kjøringen på én feil. */
    fun run(c: Context) {
        if (running || adb == null) return
        val ctx = c.applicationContext
        running = true; accountsBlocked = false; log.clear()
        scope.launch {
            val text = mutableListOf<String>()
            suspend fun add(r: TakeoverLogic.Result, what: String, detail: String = "") {
                text += "${r.sign} $what${if (detail.isNotEmpty()) " – $detail" else ""}"
                ui { log += Line(r, what, detail) }
            }
            suspend fun step(phaseText: String) = ui { phase = phaseText }
            try {
                val pkg = ctx.packageName
                step("Gir T'OS tillatelsene")
                val curAcc = sh("settings get secure enabled_accessibility_services").second
                val acc = accessibilityValue(curAcc.takeUnless { it.startsWith("ikke tilkoblet") || it.startsWith("forbindelsen") }, "$pkg/$pkg.KeyService")
                for (st in TakeoverLogic.commands(pkg, acc)) {
                    ensureActive()
                    val (code, out) = sh(st.cmd)
                    val r = TakeoverLogic.classify(code, out)
                    add(r, st.what, if (r == TakeoverLogic.Result.OK) "" else out.lineSequence().firstOrNull().orEmpty().take(140))
                }

                step("Skjuler stereoens statuslinje")
                val prev = sh("settings get system ${TakeoverLogic.NAV_KEY}").second
                if (prev.isEmpty() || prev == "null" || prev.startsWith("ikke") || prev.startsWith("forbindelsen")) {
                    add(TakeoverLogic.Result.SKIP, "Stereoens statuslinje", "finnes ikke på denne stereoen")
                } else {
                    if (Prefs.str(TakeoverLogic.NAV_PREV).isEmpty()) Prefs.set(TakeoverLogic.NAV_PREV, prev)
                    val (code, out) = sh("settings put system ${TakeoverLogic.NAV_KEY} 0")
                    val r = TakeoverLogic.classify(code, out)
                    add(r, "Stereoens statuslinje skjult", if (r == TakeoverLogic.Result.OK) "kan angres under «Angre»" else out.take(140))
                }

                step("Gjør T'OS til sjef (full kontroll)")
                deviceOwner(ctx, ::add)
                ui { runCatching { Owner.apply(ctx) } }

                // Brøt forbindelsen underveis, er ikke alt gjort: ikke «ferdig», og feilsøkingen får stå på så det kan kjøres igjen
                if (adb == null) {
                    add(TakeoverLogic.Result.FAIL, "Forbindelsen brøt", "trykk «Koble til» og «Ta over» igjen – det som er gjort, blir gjort på nytt uten skade")
                    return@launch
                }
                Prefs.set(TakeoverLogic.DONE, true)
                Prefs.set(TakeoverLogic.DONE_AT, System.currentTimeMillis())

                // Feilsøking av igjen (ingen åpen ADB-port på Wi-Fi-en). T'OS har nå WRITE_SECURE_SETTINGS og trenger den ikke.
                if (Prefs.bool(TakeoverLogic.ADB_OFF, true)) {
                    if (gmsOff) add(TakeoverLogic.Result.FAIL, "Feilsøking står på", "Google Play-tjenester må på igjen først – trykk «Slå på Play-tjenester»")
                    else {
                        step("Slår av feilsøking igjen")
                        val self = Owner.canWriteSecure(ctx) && runCatching {
                            Settings.Global.putInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0)
                        }.isSuccess
                        if (!self) runCatching { adb?.shell("settings put global adb_enabled 0") }
                        closeQuiet(); ui { connected = false }
                        add(TakeoverLogic.Result.OK, "Feilsøking slått av igjen", "tryggere – T'OS trenger den ikke lenger")
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                add(TakeoverLogic.Result.FAIL, "Stoppet", "${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}")
            } finally {
                withContext(NonCancellable) {
                    saveLog(ctx, text)
                    ui { running = false; phase = null }
                }
            }
        }
    }

    /**
     * Enhetseier («Full kontroll»). Prøves ALLTID rett på først. Sier Android nei på grunn av en Google-konto: Play-tjenester av
     * et øyeblikk (to forsøk), og ALLTID på igjen etterpå – også om forbindelsen brøt (da kobler T'OS til igjen selv, og
     * [healOnStart] prøver videre ved neste oppstart).
     */
    private suspend fun deviceOwner(ctx: Context, add: suspend (TakeoverLogic.Result, String, String) -> Unit) {
        val title = "Full kontroll (T'OS er sjef)"
        if (Owner.active(ctx)) { add(TakeoverLogic.Result.OK, title, "var allerede på"); return }
        if (TakeoverLogic.userCount(sh("pm list users").second) > 1) {
            add(TakeoverLogic.Result.SKIP, title, "stereoen har flere brukere – Android tillater det ikke uten nullstilling"); return
        }
        val types = TakeoverLogic.accountTypes(sh("dumpsys account").second)
        var out = sh(TakeoverLogic.ownerCommand(ctx.packageName)).second
        if (TakeoverLogic.dpmResult(out) == TakeoverLogic.Dpm.ACCOUNTS && TakeoverLogic.googleTrickAllowed(types) && adb != null) {
            Prefs.set(TakeoverLogic.GMS_MARK, true)
            try {
                sh("pm disable-user --user 0 ${TakeoverLogic.GMS}")
                for (wait in listOf(1_500L, 3_000L)) {
                    delay(wait)
                    out = sh(TakeoverLogic.ownerCommand(ctx.packageName)).second
                    if (TakeoverLogic.dpmResult(out) != TakeoverLogic.Dpm.ACCOUNTS || adb == null) break
                }
            } finally {
                withContext(NonCancellable) {
                    if (!runCatching { enableGmsNow() }.getOrDefault(false)) runCatching { healGmsNow(ctx) }
                }
            }
        }
        when (TakeoverLogic.dpmResult(out)) {
            TakeoverLogic.Dpm.OK -> add(TakeoverLogic.Result.OK, title, "T'OS styrer nå stereoen")
            TakeoverLogic.Dpm.ALREADY -> add(TakeoverLogic.Result.SKIP, title, "en annen app er allerede sjef: ${out.take(100)}")
            TakeoverLogic.Dpm.USERS -> add(TakeoverLogic.Result.SKIP, title, "stereoen har flere brukere")
            TakeoverLogic.Dpm.ACCOUNTS -> {
                ui { accountsBlocked = true }
                add(TakeoverLogic.Result.FAIL, title, "en konto er i veien (${types.joinToString().ifEmpty { "ukjent" }}). Følg stegene under og trykk «Ta over» igjen.")
            }
            TakeoverLogic.Dpm.OTHER -> add(TakeoverLogic.Result.FAIL, title, out.lineSequence().firstOrNull().orEmpty().take(160))
        }
        if (gmsOff) add(TakeoverLogic.Result.FAIL, "Google Play-tjenester", "kom ikke på igjen – trykk «Slå på Play-tjenester»")
    }

    /** Play-tjenester på igjen (krever tilkobling). Merket fjernes bare når Android bekrefter. */
    private suspend fun enableGmsNow(): Boolean {
        val (_, out) = sh("pm enable ${TakeoverLogic.GMS}")
        val ok = TakeoverLogic.gmsEnabled(out)
        if (ok) Prefs.set(TakeoverLogic.GMS_MARK, false)
        return ok
    }

    /** Knappen «Slå på Play-tjenester» (bare hvis noe gikk galt underveis). Kobler til selv om nødvendig. */
    fun enableGms(c: Context) {
        if (running) return
        val ctx = c.applicationContext
        running = true
        scope.launch {
            val ok = runCatching { if (adb != null) enableGmsNow() || healGmsNow(ctx) else healGmsNow(ctx) }.getOrDefault(false)
            ui {
                running = false; connected = adb != null
                Notify.show(if (ok) "Google Play-tjenester er på igjen" else "Fikk ikke slått på Play-tjenester – er feilsøking på? Prøv igjen.")
            }
        }
    }

    /** «Angre»: FYT-linja tilbake og batterisparing som før. Full kontroll angres med knappen i Om stereoen. */
    fun undo(c: Context) {
        if (running || adb == null) return
        val ctx = c.applicationContext
        running = true; log.clear()
        scope.launch {
            val text = mutableListOf<String>()
            suspend fun add(r: TakeoverLogic.Result, what: String, detail: String = "") {
                text += "${r.sign} $what${if (detail.isNotEmpty()) " – $detail" else ""}"
                ui { log += Line(r, what, detail) }
            }
            try {
                val v = TakeoverLogic.navUndo(Prefs.str(TakeoverLogic.NAV_PREV))
                val (c1, o1) = sh("settings put system ${TakeoverLogic.NAV_KEY} $v")
                add(TakeoverLogic.classify(c1, o1), "Stereoens statuslinje vises igjen")
                if (TakeoverLogic.classify(c1, o1) == TakeoverLogic.Result.OK) Prefs.set(TakeoverLogic.NAV_PREV, null)
                val (c2, o2) = sh("dumpsys deviceidle whitelist -${ctx.packageName}")
                add(TakeoverLogic.classify(c2, o2), "Batterisparing som før")
                Prefs.set(TakeoverLogic.DONE, false)
            } finally {
                withContext(NonCancellable) { saveLog(ctx, listOf("(Angre)") + text); ui { running = false } }
            }
        }
    }

    private fun saveLog(c: Context, lines: List<String>) = runCatching {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale("nb", "NO")).format(Date())
        File(c.filesDir, TakeoverLogic.LOG_FILE).writeText("Ta over stereoen – $stamp\n" + lines.joinToString("\n") + "\n")
    }

    fun lastLog(c: Context): String = runCatching { File(c.filesDir, TakeoverLogic.LOG_FILE).readText() }.getOrDefault("")

    /** Til «Send alt til utvikleren» og kartleggingen. */
    fun report(c: Context): String = buildString {
        appendLine("Utviklermodus: ${devOn(c)}")
        appendLine("Feilsøking (adb_enabled): ${adbOn(c)}")
        val open = if (Looper.myLooper() == Looper.getMainLooper()) portOpenLast else portOpenBlocking()
        appendLine("ADB på 127.0.0.1:5555: ${open ?: "ikke målt"}")
        appendLine("Systemegenskaper: ${tcpProps()}")
        appendLine("Tilkoblet nå: $connected")
        appendLine("Tatt over: $done${Prefs.long(TakeoverLogic.DONE_AT).takeIf { it > 0 }?.let { " (${Date(it)})" } ?: ""}")
        appendLine("Play-tjenester av (må på igjen): $gmsOff")
        appendLine("FYT-linja før: ${Prefs.str(TakeoverLogic.NAV_PREV).ifEmpty { "–" }}")
        appendLine("WRITE_SECURE_SETTINGS: ${Owner.canWriteSecure(c)}")
        val l = lastLog(c)
        if (l.isNotBlank()) { appendLine("Siste kjøring:"); append(l.lines().takeLast(80).joinToString("\n")) }
    }
}

/* ======================= Siden «Ta over stereoen» ======================= */

/** Åpner en Android-side. Finnes den ikke, prøves neste. */
private fun Context.openFirst(vararg intents: Intent): Boolean = intents.any { i ->
    runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
}

@Composable
fun TakeoverScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    var dev by remember { mutableStateOf(Takeover.devOn(context)) }
    var adbOn by remember { mutableStateOf(Takeover.adbOn(context)) }
    var open by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    // Sjekkes hvert sekund mens siden VISES (og straks brukeren kommer tilbake fra en Android-side). Er T'OS ikke framme
    // (brukeren er i Androids innstillinger), sjekkes det ikke – [tick] sjekker straks T'OS kommer tilbake.
    LaunchedEffect(tick) {
        while (true) {
            if (Notify.appVisible || !checked) {
                dev = Takeover.devOn(context); adbOn = Takeover.adbOn(context)
                open = Takeover.portOpen(); checked = true
            }
            delay(1000)
        }
    }
    // Siden lukkes: ingen ADB-forbindelse blir liggende åpen (med mindre «Ta over» kjører – den fullfører i bakgrunnen)
    DisposableEffect(Unit) { onDispose { Takeover.leave() } }
    val done = Takeover.done || Owner.active(context)
    val connected = Takeover.connected
    val net = TakeoverLogic.net(open, adbOn)
    val n = TakeoverLogic.progress(dev, adbOn, open, connected, done)
    val busy = Takeover.running || Takeover.connecting

    Page("Ta over stereoen", onClose) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // Venstre: status, den store knappen og angre
            Column(
                Modifier.weight(0.36f).fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(Card).dotGrid().padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(if (done && !busy) "FERDIG" else "$n AV 5", style = dotStyle(40.sp, weight = FontWeight.Bold), maxLines = 1)
                Text(caps(Takeover.phase ?: if (done) "T'OS har tatt over" else "Uten PC"), style = capsStyle(12.sp))
                Text(
                    if (done) "Vil du kjøre på nytt (f.eks. etter en nullstilling), gjør punktene til høyre igjen."
                    else "Gjør punktene til høyre ett for ett. Hakene kommer av seg selv. Til slutt trykker du «Ta over», og T'OS gjør resten.",
                    color = TextMuted, fontSize = 14.sp,
                )
                BigButton(
                    when { Takeover.running -> "Jobber …"; done -> "Ta over igjen"; else -> "Ta over" },
                    enabled = connected && !busy,
                ) { Takeover.run(context) }
                ToggleRow("Slå av feilsøking etterpå", TakeoverLogic.ADB_OFF, true, "Tryggere: ingen åpen ADB på Wi-Fi-en")
                if (Takeover.gmsOff) Pill("Slå på Play-tjenester", strong = true) { Takeover.enableGms(context) }
                Text(caps("Angre"), style = capsStyle(12.sp), modifier = Modifier.padding(top = 6.dp))
                Text("«Angre» viser stereoens statuslinje igjen (krever punkt 4). Full kontroll slås av under Innstillinger → Om stereoen → " +
                    "«Slå av full kontroll».", color = TextMuted, fontSize = 13.sp)
                Pill("Angre") { if (connected && !busy) Takeover.undo(context) else Notify.show("Koble til først (punkt 4)") }
            }
            // Høyre: de fem punktene, så loggen
            Column(Modifier.weight(0.64f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StepRow(1, "Utviklermodus", "Trykk 7 ganger på «Versjonsnummer» / «Build number», og trykk tilbake.", dev || done, "Åpne") {
                    context.openFirst(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), Intent(Settings.ACTION_SETTINGS))
                }
                StepRow(2, "USB-feilsøking", "Slå på «USB-feilsøking» (og «ADB over nettverk» hvis stereoen har det).", adbOn || done, "Åpne") {
                    if (!Takeover.turnOnAdbSelf(context))
                        context.openFirst(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Intent(Settings.ACTION_SETTINGS))
                }
                StepRow(3, "Stereoen åpner for T'OS",
                    when {
                        !checked -> "Sjekker …"
                        net == TakeoverLogic.Net.OPEN -> "Klar (port 5555)."
                        net == TakeoverLogic.Net.ADB_OFF -> "Kommer av seg selv når punkt 2 er gjort."
                        else -> "Feilsøking er på, men stereoen åpner ikke for T'OS. Se etter «ADB over nettverk» i Utvikleralternativer, " +
                            "eller i stereoens fabrikkinnstillinger (ikke prøvd). Ellers: bruk PC."
                    },
                    open || done, if (net == TakeoverLogic.Net.NO_NETWORK_ADB) "Bruk PC" else null,
                ) { HelpJump.target = "Med PC"; Nav.screen = Screen.HELP }
                StepRow(4, "Koble til",
                    Takeover.message ?: if (connected) "Tilkoblet." else "Stereoen spør «Tillat USB-feilsøking?». Trykk «Tillat alltid …» og «OK».",
                    connected || done, if (Takeover.connecting) "Vent …" else if (open && !connected) "Koble til" else null,
                ) { Takeover.connect(context) }
                StepRow(5, "Ta over", "Tillatelser, knappestyring, statuslinja og full kontroll – på én gang.", done, null) {}
                if (Takeover.accountsBlocked) {
                    StepRow(null, "Fjern Google-kontoen et øyeblikk",
                        "Android krever det for full kontroll. Fjern kontoen, kom tilbake og trykk «Ta over» igjen.", false, "Åpne") {
                        context.openFirst(Intent(Settings.ACTION_SYNC_SETTINGS), Intent(Settings.ACTION_SETTINGS))
                    }
                    StepRow(null, "Legg Google-kontoen inn igjen", "Etter at «Full kontroll» har fått hake.", Owner.active(context), "Åpne") {
                        context.openFirst(Intent(Settings.ACTION_ADD_ACCOUNT).putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google")),
                            Intent(Settings.ACTION_SYNC_SETTINGS))
                    }
                }
                Takeover.log.forEach { LogRow(it) }
            }
        }
    }
}

@Composable
private fun BigButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(32.dp))
            .background(if (enabled) TextPrimary else Line)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Bg else TextMuted, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StepRow(nr: Int?, title: String, detail: String, ok: Boolean, action: String?, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp)).background(Card)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val box = Modifier.size(38.dp).clip(CircleShape)
        Box(if (ok) box.background(TextPrimary) else box.border(2.dp, Line, CircleShape), contentAlignment = Alignment.Center) {
            if (ok) Text("✓", color = Bg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            else if (nr != null) Text("$nr", style = dotStyle(18.sp, TextMuted, FontWeight.Bold))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 16.sp)
            Text(detail, color = TextMuted, fontSize = 13.sp)
        }
        if (action != null) { Spacer(Modifier.width(10.dp)); Pill(action, onClick = onAction) }
    }
}

@Composable
private fun LogRow(l: Takeover.Line) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.Top) {
        Text(l.result.sign, color = if (l.result == TakeoverLogic.Result.FAIL) TextPrimary else TextMuted,
            fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            Text(l.text, color = TextPrimary, fontSize = 14.sp)
            if (l.detail.isNotEmpty()) Text(l.detail, color = TextMuted, fontSize = 12.sp)
        }
    }
}

/** Raden øverst i «Full kontroll» (Om stereoen) og i førstegangsoppsettet. */
@Composable
fun TakeoverRow() {
    val done = Takeover.done
    SettingRow("Ta over stereoen – uten PC",
        if (done) "Gjort. Åpne for å se hva som ble gjort, eller kjøre på nytt."
        else "T'OS gjør alt selv: tillatelser, knappestyring, statuslinja og full kontroll. Du slår på to ting i Android og trykker «Tillat».",
        onClick = { Nav.screen = Screen.TAKEOVER }) { Pill(if (done) "Åpne" else "Start", strong = !done) { Nav.screen = Screen.TAKEOVER } }
}
