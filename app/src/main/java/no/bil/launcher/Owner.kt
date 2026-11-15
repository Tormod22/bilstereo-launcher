package no.bil.launcher

import android.Manifest
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * «Full kontroll»: T'OS som Androids «enhetseier» (device owner). Settes én gang med PC:
 * `adb shell dpm set-device-owner no.bil.launcher/.TosAdmin` (Google-kontoen må fjernes først, se docs/ADB-OPPSETT.md).
 * Da kan T'OS selv: stenge Androids nedtrekksmeny, gi seg selv tillatelser, låse seg som startskjerm, skjule de andre
 * startskjermene og oppdatere seg uten spørsmål. Alt kan angres med «Slå av full kontroll» (ingen PC trengs).
 */
class TosAdmin : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) { runCatching { Owner.apply(context) } }
}

/** Ren logikk (testes i OwnerLogicTest): hva som skal skjules og gjøres. Ingen Android-kall. */
object OwnerLogic {
    const val SHADE_OFF = "owner_shade_off"
    const val HIDE_LAUNCHERS = "owner_hide_launchers"
    const val HIDE_RECENTS = "owner_hide_recents"
    const val HOME = "owner_home"
    const val GRANT = "owner_grant"
    const val NO_LOCK = "owner_no_lock"
    const val AUTO_TIME = "owner_auto_time"
    const val KEEP_SERVICES = "owner_keep_services"

    /** Standardverdier for bryterne. «Siste apper»-skjermen (Quickstep) skjules bare om man vil – stereoens knapp bruker den. */
    val DEFAULTS = mapOf(
        SHADE_OFF to true, HIDE_LAUNCHERS to true, HIDE_RECENTS to false, HOME to true,
        GRANT to true, NO_LOCK to true, AUTO_TIME to true, KEEP_SERVICES to true,
    )

    /** De andre startskjermene på stereoen (FYT/Unisoc). */
    val LAUNCHERS = listOf(
        "com.android.launcher4", "com.android.launcher9", "com.android.launcher24",
        "com.android.launcher35", "com.android.launcher36", "com.unisoc.launcher.customization",
    )
    /** Androids «siste apper» (Quickstep) på denne stereoen. */
    const val RECENTS = "com.android.launcher3"

    /** Skal ALDRI skjules: da kan stereoen slutte å virke (FYT-sentralen, radio, BT, systemet, Google). */
    private val CRITICAL_PREFIX = listOf("com.syu.", "com.fyt.", "com.txznet.", "com.android.providers.", "com.google.android.")
    private val CRITICAL = setOf(
        "android", "no.bil.launcher", "com.android.systemui", "com.android.settings", "com.android.phone",
        "com.android.server.telecom", "com.android.bluetooth", "com.android.permissioncontroller",
        "com.google.android.packageinstaller", "com.android.vending", "com.android.shell", "com.android.inputmethod.latin",
    )

    fun isCritical(pkg: String) = pkg in CRITICAL || CRITICAL_PREFIX.any { pkg.startsWith(it) }

    /** Bare startskjermene i [LAUNCHERS] og [RECENTS] kan skjules – og aldri noe kritisk. */
    fun mayHide(pkg: String) = (pkg in LAUNCHERS || pkg == RECENTS) && !isCritical(pkg)

    /** Hvilke pakker som skal være skjult nå (av de som finnes på stereoen). */
    fun toHide(hideLaunchers: Boolean, hideRecents: Boolean, installed: Collection<String>): List<String> =
        installed.filter { p -> mayHide(p) && ((hideLaunchers && p in LAUNCHERS) || (hideRecents && p == RECENTS)) }.sorted()

    /** Alle T'OS kan ha skjult – disse vises igjen når noe slås av, eller ved «Slå av full kontroll». */
    val ALL_HIDEABLE: List<String> get() = (LAUNCHERS + RECENTS).filter { mayHide(it) }

    /** Tillatelsene T'OS gir seg selv (de som Android ellers spør om i et eget vindu). */
    fun permissions(sdk: Int): List<String> = buildList {
        addAll(listOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE,
        ))
        if (sdk >= 31) { add("android.permission.BLUETOOTH_CONNECT"); add("android.permission.BLUETOOTH_SCAN") }
        if (sdk >= 33) add("android.permission.POST_NOTIFICATIONS")
    }
}

/** Utfører «Full kontroll». Alt gjør ingenting når T'OS ikke er enhetseier. */
object Owner {
    private fun dpm(c: Context) = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    fun admin(c: Context) = ComponentName(c, TosAdmin::class.java)

    /** T'OS er enhetseier (satt med PC). */
    fun active(c: Context): Boolean = runCatching { dpm(c)?.isDeviceOwnerApp(c.packageName) == true }.getOrDefault(false)

    /** T'OS har fått WRITE_SECURE_SETTINGS med PC (`pm grant`) – da holder T'OS knappestyringen og varseltilgangen på selv. */
    fun canWriteSecure(c: Context) = c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun on(key: String) = Prefs.bool(key, OwnerLogic.DEFAULTS[key] ?: false)

    /** Hva som ikke virket sist (vises i oppsettrapporten). */
    @Volatile var problems: List<String> = emptyList(); private set

    /** Bruker bryterne. Kalles ved oppstart, når en «owner_»-bryter endres og når T'OS blir enhetseier. */
    fun apply(c: Context) {
        val ctx = c.applicationContext
        keepServices(ctx)
        if (!active(ctx)) return
        val dpm = dpm(ctx) ?: return
        val a = admin(ctx)
        val bad = mutableListOf<String>()
        fun step(name: String, block: () -> Unit) { runCatching(block).onFailure { bad += "$name: ${it.javaClass.simpleName} ${it.message.orEmpty()}".trim() } }

        // 1) Androids nedtrekksmeny (og varsler som spretter ned) kan ikke åpnes. T'OS sitt eget hurtigpanel virker som før.
        step("nedtrekksmeny") { if (!dpm.setStatusBarDisabled(a, on(OwnerLogic.SHADE_OFF))) bad += "nedtrekksmeny: Android sa nei" }
        // 2) Ingen tillatelsesvinduer: T'OS gir seg selv det den trenger
        if (on(OwnerLogic.GRANT)) for (p in OwnerLogic.permissions(Build.VERSION.SDK_INT)) step("tillatelse ${p.substringAfterLast('.')}") {
            dpm.setPermissionGrantState(a, ctx.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }
        // 3) T'OS er alltid startskjermen
        step("startskjerm") {
            dpm.clearPackagePersistentPreferredActivities(a, ctx.packageName)
            if (on(OwnerLogic.HOME)) dpm.addPersistentPreferredActivity(a,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) },
                ComponentName(ctx, MainActivity::class.java))
        }
        // 4) De andre startskjermene skjules (kan vises igjen når som helst)
        val installed = OwnerLogic.ALL_HIDEABLE.filter { exists(ctx, it) }
        val hide = OwnerLogic.toHide(on(OwnerLogic.HIDE_LAUNCHERS), on(OwnerLogic.HIDE_RECENTS), installed).toSet()
        for (p in installed) step("skjul $p") { dpm.setApplicationHidden(a, p, p in hide) }
        // 5) Ingen skjermlås
        step("skjermlås") { dpm.setKeyguardDisabled(a, on(OwnerLogic.NO_LOCK)) }
        // 6) Riktig klokke (Android 10 lar enhetseieren slå på automatisk tid)
        if (on(OwnerLogic.AUTO_TIME)) step("automatisk tid") {
            dpm.setGlobalSetting(a, Settings.Global.AUTO_TIME, "1")
            dpm.setGlobalSetting(a, Settings.Global.AUTO_TIME_ZONE, "1")
        }
        problems = bad
    }

    /**
     * Med `pm grant no.bil.launcher android.permission.WRITE_SECURE_SETTINGS` (PC, én gang): skrus knappestyringen eller
     * varseltilgangen av (f.eks. etter en oppdatering), slår T'OS dem på igjen selv. Stereoens egne tjenester beholdes.
     */
    fun keepServices(c: Context) {
        if (!canWriteSecure(c) || !on(OwnerLogic.KEEP_SERVICES)) return
        val cr = c.contentResolver
        runCatching {
            val ks = ComponentName(c, KeyService::class.java).flattenToString()
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val next = accessibilityValue(cur, ks)
            if (next != cur) Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
            if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
        runCatching {
            val ml = ComponentName(c, MediaListener::class.java).flattenToString()
            val key = "enabled_notification_listeners"
            val cur = Settings.Secure.getString(cr, key)
            val next = accessibilityValue(cur, ml)
            if (next != cur) Settings.Secure.putString(cr, key, next)
        }
    }

    /**
     * «Slå av full kontroll»: alt settes tilbake (startskjermene vises igjen, nedtrekksmenyen virker), og T'OS gir fra seg
     * rollen som enhetseier. Returnerer true når det gikk.
     */
    @Suppress("DEPRECATION")
    fun release(c: Context): Boolean {
        val ctx = c.applicationContext
        if (!active(ctx)) return true
        val dpm = dpm(ctx) ?: return false
        val a = admin(ctx)
        for (p in OwnerLogic.ALL_HIDEABLE) runCatching { if (exists(ctx, p)) dpm.setApplicationHidden(a, p, false) }
        runCatching { dpm.clearPackagePersistentPreferredActivities(a, ctx.packageName) }
        runCatching { dpm.setStatusBarDisabled(a, false) }
        runCatching { dpm.setKeyguardDisabled(a, false) }
        return runCatching { dpm.clearDeviceOwnerApp(ctx.packageName); true }.getOrDefault(false) && !active(ctx)
    }

    /** Finnes pakken (også når den er skjult)? */
    private fun exists(c: Context, pkg: String): Boolean = runCatching {
        c.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES); true
    }.getOrDefault(false)

    /** Skjulte startskjermer nå (for status og rapport). */
    fun hiddenNow(c: Context): List<String> {
        if (!active(c)) return emptyList()
        val dpm = dpm(c) ?: return emptyList()
        return OwnerLogic.ALL_HIDEABLE.filter { p -> exists(c, p) && runCatching { dpm.isApplicationHidden(admin(c), p) }.getOrDefault(false) }
    }

    /** Linjer til oppsettrapporten og kartleggingen. */
    fun report(c: Context): String = buildString {
        val act = active(c)
        appendLine("Enhetseier (full kontroll): ${if (act) "JA" else "nei"}")
        appendLine("WRITE_SECURE_SETTINGS (holder knappestyring/varseltilgang på): ${canWriteSecure(c)}")
        if (act) {
            OwnerLogic.DEFAULTS.keys.forEach { appendLine("$it: ${on(it)}") }
            appendLine("Skjulte apper: ${hiddenNow(c).joinToString().ifEmpty { "ingen" }}")
            appendLine("Nedtrekksmeny stengt: ${on(OwnerLogic.SHADE_OFF)}")
            appendLine("Problemer sist: ${problems.joinToString(" | ").ifEmpty { "ingen" }}")
        }
    }

    /* ---------- Oppstartsbilde (FYT «bil-logo») ---------- */

    const val BOOT_ASSET = "tos-oppstart.png"
    const val CARMARK = "com.syu.carmark"

    /** Lagrer T'OS-oppstartsbildet i Bilder/T'OS. Returnerer hvor det ligger, eller null. */
    fun saveBootLogo(c: Context): String? = runCatching {
        val bytes = c.assets.open(BOOT_ASSET).use { it.readBytes() }
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = c.contentResolver
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "tos-oppstart.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/T'OS")
            }
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: error("ingen plass")
            cr.openOutputStream(uri)?.use { it.write(bytes) } ?: error("kunne ikke skrive")
            "Bilder/T'OS/tos-oppstart.png"
        } else {
            val dir = c.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: error("ingen lagring")
            File(dir, "tos-oppstart.png").writeBytes(bytes)
            dir.absolutePath + "/tos-oppstart.png"
        }
    }.getOrNull()
}

/* ---------- Kortet under Innstillinger → Om stereoen ---------- */

@Composable
fun FullControlSettings() {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    val active = remember(tick, Prefs.version) { Owner.active(context) }
    val howTo = { HelpJump.target = "Full kontroll"; Nav.screen = Screen.HELP }
    Section("Full kontroll")
    if (!active) {
        SettingRow("Full kontroll: Av",
            "Med PC én gang kan T'OS ta over helt: Androids nedtrekksmeny stenges, ingen tillatelsesvinduer, de andre " +
                "startskjermene skjules og oppdateringer går av seg selv. Kan slås av igjen her.",
            onClick = howTo) { Pill("Slik slår du det på", onClick = howTo) }
        if (Owner.canWriteSecure(context)) ToggleRow("Hold knappestyringen på", OwnerLogic.KEEP_SERVICES, true,
            "Slår du av «T'OS knapper» eller varseltilgangen ved et uhell, slår T'OS dem på igjen (gitt med PC)")
    } else {
        SettingRow("Full kontroll: På", "T'OS styrer stereoen. Alt under kan slås av hver for seg.")
        ToggleRow("Steng Androids nedtrekksmeny", OwnerLogic.SHADE_OFF, true,
            "Dra ned fra toppen viser bare T'OS sitt hurtigpanel. Androids varsler spretter heller ikke ned.")
        ToggleRow("Skjul stereoens andre startskjermer", OwnerLogic.HIDE_LAUNCHERS, true,
            "De originale startskjermene kan ikke dukke opp. Vises igjen hvis du slår dette av.")
        ToggleRow("Skjul Androids «siste apper»", OwnerLogic.HIDE_RECENTS, false,
            "Stereoens «siste apper»-knapp åpner da ikke Androids liste. Prøv – slå av hvis knappen slutter å virke.")
        ToggleRow("T'OS er alltid startskjermen", OwnerLogic.HOME, true)
        ToggleRow("Gi T'OS tillatelser selv", OwnerLogic.GRANT, true, "Posisjon, telefon og kontakter – uten Android-vinduer")
        ToggleRow("Ingen skjermlås", OwnerLogic.NO_LOCK, true)
        ToggleRow("Riktig klokke automatisk", OwnerLogic.AUTO_TIME, true, "Tid og tidssone fra nett/GPS")
        if (Owner.canWriteSecure(context)) ToggleRow("Hold knappestyringen på", OwnerLogic.KEEP_SERVICES, true,
            "Slår T'OS knapper og varseltilgangen på igjen hvis de blir slått av")
        Owner.problems.takeIf { it.isNotEmpty() }?.let { SettingRow("Dette virket ikke", it.joinToString("\n")) }
        var ask by remember { mutableStateOf(false) }
        LaunchedEffect(ask) { if (ask) { delay(5000); ask = false } }
        val release = {
            if (!ask) ask = true
            else {
                ask = false
                Notify.show(if (Owner.release(context)) "Full kontroll er slått av" else "Fikk ikke slått av full kontroll")
                Prefs.set("owner_released_at", System.currentTimeMillis())
            }
        }
        SettingRow("Slå av full kontroll",
            if (ask) "Trykk igjen: alt settes tilbake, og T'OS gir fra seg kontrollen. Skal den på igjen, trengs PC."
            else "Setter alt tilbake slik det var. Trengs PC for å slå på igjen.",
            onClick = release) { if (ask) Pill("Slå av", strong = true, onClick = release) }
    }
    BootLogoRow()
}

@Composable
private fun BootLogoRow() {
    val context = LocalContext.current
    val hasCarmark = remember { runCatching { context.packageManager.getLaunchIntentForPackage(Owner.CARMARK) != null }.getOrDefault(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val save = {
        if (!busy) {
            busy = true
            scope.launch {
                val where = withContext(Dispatchers.IO) { Owner.saveBootLogo(context) }
                busy = false
                if (where == null) Notify.show("Fikk ikke lagret bildet")
                else {
                    Notify.show("Lagret i $where")
                    if (hasCarmark) context.packageManager.getLaunchIntentForPackage(Owner.CARMARK)?.let { i ->
                        runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
            }
        }
    }
    SettingRow("Lagre T'OS-oppstartsbilde",
        "Lagrer et svart T'OS-bilde i Bilder/T'OS. " +
            (if (hasCarmark) "Deretter åpnes stereoens logo-valg: velg «egendefinert» og bildet tos-oppstart. "
            else "Velg det som oppstartsbilde i stereoens logo-valg. ") +
            "(Ikke prøvd på denne stereoen ennå.)",
        onClick = { save() }) { Spacer(Modifier.width(4.dp)); Pill(if (busy) "Lagrer …" else "Lagre") { save() } }
}
