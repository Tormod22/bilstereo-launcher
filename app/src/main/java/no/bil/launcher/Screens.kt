package no.bil.launcher

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/* ---------- Felles topp: tittel + lukk ---------- */

@Composable
private fun PageHeader(title: String, onClose: () -> Unit, extra: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        extra()
        RoundIcon(Icons.Default.Close, 48.dp, desc = "Lukk") { onClose() }
    }
}

/* ======================= Bluetooth-side ======================= */

/** [via]: hvordan T'OS vet at enheten er tilkoblet (Android, en profil, stereoens BT-app …) – står i oppsettrapporten. */
data class BtDevice(val name: String, val connected: Boolean, val battery: Int = -1, val address: String = "", val via: String = "")

/**
 * [vendorPkg]: stereoens egen Bluetooth-app (finnes den, kan telefonen være tilkoblet uten at Android ser det).
 * [noPermission]: T'OS har ikke fått lov til Bluetooth.
 */
data class BtStatus(
    val available: Boolean, val on: Boolean, val devices: List<BtDevice>,
    val vendorPkg: String? = null, val noPermission: Boolean = false,
) {
    /** Telefonen som er tilkoblet (ikke OBD-brikka), eller null. */
    val phone: BtDevice? get() = devices.firstOrNull { it.connected && !looksLikeObdName(it.name) }
}

/**
 * Leser Bluetooth i flere lag (se [BtLink]). NB: på mange bilstereoer er telefon-Bluetooth en egen brikke som
 * stereoens egen BT-app styrer – da ser Android ingenting, og T'OS sier ikke «ikke tilkoblet».
 */
fun btStatus(context: Context): BtStatus = BtLink.status(context)

@Composable
fun BluetoothScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val status by produceState(initialValue = BtLink.current, BtLink.version) {
        // Bluetooth-oppslaget går via systemet (og prøver skjulte funksjoner) – ikke på hovedtråden hvert 3. sekund
        while (true) { value = withContext(Dispatchers.IO) { btStatus(context) }; delay(3000) }
    }

    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Panel).padding(24.dp)) {
        PageHeader("Bluetooth", onClose)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {

            // Venstre: status og enheter
            Column(
                Modifier.weight(0.45f).fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(Card).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val (head, sub) = btTexts(status)
                Text(head, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, color = TextMuted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // FYT-stereoen: telefonen er koblet til stereoens egen BT-brikke – koble fra/til her
                FytBtCard()
                if (status.devices.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Kjente enheter", color = TextMuted, fontSize = 13.sp)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(status.devices) { d ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(
                                    Modifier.size(10.dp).clip(CircleShape)
                                        .then(if (d.connected) Modifier.background(TextPrimary) else Modifier.border(1.5.dp, TextMuted, CircleShape))
                                )
                                Text(d.name, color = if (d.connected) TextPrimary else TextMuted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                } else Spacer(Modifier.weight(1f))

                // Musikk fra telefonen over Bluetooth
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Musikk", color = TextMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    RoundIcon(Icons.Default.SkipPrevious, 48.dp, desc = musicPrevLabel) { Media.previous(context) }
                    RoundIcon(musicPlayIcon, 56.dp, outlined = true,
                        desc = musicPlayLabel) { Media.playPause(context) }
                    RoundIcon(Icons.Default.SkipNext, 48.dp, desc = musicNextLabel) { Media.next(context) }
                }
            }

            // Høyre: fire store valg. Telefon og musikk er T'OS sine egne sider (ikke stereoens Bluetooth-app).
            Column(Modifier.weight(0.55f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    BigTile(Icons.Default.Phone, "Telefon", Modifier.weight(1f)) { Nav.screen = Screen.PHONE }
                    // Lyd fra telefonen: Bluetooth-kilden startes (stereoens BT-musikk om nødvendig), så musikksiden
                    BigTile(Icons.Default.Headphones, "Bluetooth-lyd fra telefonen", Modifier.weight(1f)) {
                        Nav.screen = Screen.MUSIC; Media.selectBluetooth(context)
                    }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    BigTile(Icons.Default.PhoneIphone, "CarPlay", Modifier.weight(1f)) { AppRepository.launchTile(context, Tiles.byPrefix("Car")) }
                    BigTile(Icons.Default.Settings, "Innstillinger", Modifier.weight(1f)) {
                        Prefs.set("settings_cat", "BT"); Nav.screen = Screen.SETTINGS   // T'OS sin egen Bluetooth-side
                    }
                }
            }
        }
    }
}

@Composable
fun BigTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(Card).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = TextPrimary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text(label, color = TextPrimary, fontSize = 16.sp, textAlign = TextAlign.Center, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

/* ======================= Knapper-side ======================= */

@Composable
fun ActionPicker(current: Action, modifier: Modifier, prefix: String = "", onPick: (Action) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable { menu = true }.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(prefix + current.label, color = if (current == Action.NONE && prefix.isNotEmpty()) TextMuted else TextPrimary, fontSize = 15.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Default.ArrowDropDown, null, tint = TextMuted)
        }
        // Mørk meny i T'OS-farger (ellers blir den hvit som i Android)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Card,
            shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Line)) {
            Action.entries.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.label, fontSize = 16.sp, fontWeight = if (a == current) FontWeight.Medium else FontWeight.Normal) },
                    onClick = { onPick(a); menu = false },
                    leadingIcon = { if (a == current) Icon(Icons.Default.Check, null, tint = TextPrimary) else Spacer(Modifier.size(24.dp)) },
                    colors = MenuDefaults.itemColors(textColor = TextPrimary, leadingIconColor = TextPrimary),
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
fun KeysScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val serviceOn by produceState(initialValue = Buttons.serviceEnabled(context)) {
        while (true) { value = Buttons.serviceEnabled(context); delay(1500) }
    }
    // Forlates siden (Hjem, en annen side …), stopper læringen – ellers ville neste app som åpnes bli «lært»
    DisposableEffect(Unit) { onDispose { if (Learn.target != null) Learn.cancel() } }
    // Gi opp læring etter like lang tid som Learn selv (ellers sier siden «ingen signal» før tiden er ute)
    val target = Learn.target
    LaunchedEffect(target) {
        if (target != null) {
            delay(Learn.TIMEOUT_MS)
            if (Learn.target == target) Learn.cancel("Fikk ikke noe signal fra ${target.label}. Prøv igjen, og sjekk at knappestyring er på.")
        }
    }

    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Panel).padding(24.dp)) {
        PageHeader("Knapper", onClose) {
            Row(
                Modifier.height(48.dp).clip(RoundedCornerShape(24.dp))
                    .background(if (serviceOn) Card else TextPrimary)
                    .clickable {
                        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(if (serviceOn) Icons.Default.Check else Icons.Default.PowerSettingsNew, null,
                    tint = if (serviceOn) TextPrimary else Bg, modifier = Modifier.size(20.dp))
                Text(if (serviceOn) "Knappestyring er på" else "Slå på knappestyring",
                    color = if (serviceOn) TextPrimary else Bg, fontSize = 14.sp)
            }
            Spacer(Modifier.width(12.dp))
        }

        Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                Learn.target?.let { "Trykk ${it.label}-knappen på stereoen nå …" }
                    ?: Learn.message
                    ?: "Trykk en knapp på stereoen: riktig rad får en grønn prikk. «Lær» kobler en knapp til raden.",
                color = if (Learn.target != null) TextPrimary else TextMuted, fontSize = 15.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            // Siste signal: hvilken knapp T'OS kjente igjen, eller en ukjent kode
            val pinged = KeyPing.key
            if (KeyPing.lit && Learn.target == null) {
                Spacer(Modifier.width(12.dp))
                PingDot()
                Spacer(Modifier.width(8.dp))
                Text(
                    if (pinged != null) "${pinged.label} (${KeyPing.how})" else "Ukjent knapp (kode ${KeyPing.unknownCode})",
                    color = TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 320.dp),
                )
            }
        }

        // Lista leser Buttons.version selv, så den oppdateres uten å hoppe til toppen
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val keys = PanelKey.entries.toList()
            val firstRatt = keys.indexOf(PanelKey.RATT1)
            items(keys.take(firstRatt), key = { it.name }) { k -> KeyRow(k) { } }
            item(key = "rattkort") { SteerCard() }
            items(keys.drop(firstRatt), key = { it.name }) { k -> KeyRow(k) { } }
        }
    }
}

/**
 * «Rattknapper»: om stereoen har sendt noe fra rattknappene, siste rattknapp (så man ser om trykkene kommer fram),
 * og stereoens eget rattknapp-oppsett («Control»), der knappene må læres én gang. Se Steer.kt.
 */
@Composable
private fun SteerCard() {
    val context = LocalContext.current
    val seen = Steer.everSeen
    val setup = remember { Steer.setupInstalled(context) }
    val last = Steer.last
    val clock = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Rattknapper", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text(
            if (!seen) "Stereoen har ikke lært rattknappene ennå (derfor gjør de ingenting). Trykk «Lær rattknappene» – " +
                "det tar et par minutter, og må bare gjøres én gang. Etterpå virker de også når T'OS ikke er framme."
            else "Neste, forrige og pause på rattet går til musikken som spiller (f.eks. Spotify). Radioen og Bluetooth-musikken " +
                "styrer stereoen selv. Skal en knapp gjøre noe annet: trykk «Lær» på en Ratt-rad og trykk knappen på rattet.",
            color = TextMuted, fontSize = 14.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) { if (Steer.lit) PingDot() }
            Text(
                if (last != null) "Siste rattknapp: $last (kl. ${clock.format(java.util.Date(Steer.lastAt))})"
                else "Siste rattknapp: ingen ennå",
                color = if (last != null) TextPrimary else TextMuted, fontSize = 15.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (Fyt.connected) PillAction("Lær rattknappene", primary = !seen) { Nav.screen = Screen.STEER }
            else if (setup) PillAction("Åpne stereoens rattknapp-oppsett", primary = !seen) { Steer.openSetup(context) }
        }
    }
}

@Composable
private fun PillAction(text: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(if (primary) TextPrimary else Line)
            .clickable(onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (primary) Bg else TextPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/* ======================= Rattknapper: læring i stereoen ======================= */

/**
 * T'OS sin egen rattknapp-læring (i stedet for stereoens «Control»): trykk en flis, hold inne knappen på rattet til
 * tallet vises, så lærer stereoen den (FytSteer). Lærte knapper gjør stereoen selv. Test: en lært knapp lyser når den
 * trykkes. Finnes ikke FYT-tjenesten, eller svarer den ikke, vises stereoens eget oppsett som reserve.
 */
@Composable
fun SteerScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        FytSteer.pageOpen = true
        onDispose { FytSteer.pageOpen = false; FytSteer.endSession() }
    }
    val setup = remember { Steer.setupInstalled(context) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(confirmClear) { if (confirmClear) { delay(4000); confirmClear = false } }

    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Panel).padding(24.dp)) {
        PageHeader("Rattknapper", onClose)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // Venstre: tallet fra rattet, hva du gjør, og knappene
            Column(
                Modifier.weight(0.36f).fillMaxHeight().clip(RoundedCornerShape(22.dp)).background(Card).padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!Fyt.connected) {
                    Text("Får ikke kontakt med stereoen", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text("Rattknappene læres i stereoen selv. Bruk stereoens eget oppsett: trykk en funksjon, hold inne " +
                        "rattknappen til tallet endrer seg, og trykk ✓.", color = TextMuted, fontSize = 14.sp)
                } else {
                    Text("Tall fra rattet", color = TextMuted, fontSize = 13.sp)
                    val adc = FytSteer.adc
                    Text(adc?.takeIf { it != SteerAdc.NO_KEY }?.toString() ?: "–", color = TextPrimary, fontSize = 44.sp, fontWeight = FontWeight.Medium)
                    val ch = FytSteer.channels
                    if (ch.any { it != null }) Text(ch.joinToString("   ") { it?.toString() ?: "–" }, color = TextMuted, fontSize = 14.sp, maxLines = 1)
                    Text(
                        "1. Trykk en flis til høyre, f.eks. «Volum +».\n2. Hold inne samme knapp på rattet til tallet vises.\n" +
                            "3. Når det står ✓, er den lært. Gjør det samme for de andre.\n4. Trykk «Lagre i stereoen».",
                        color = TextMuted, fontSize = 14.sp,
                    )
                }
                FytSteer.message?.let { Text(it, color = TextPrimary, fontSize = 15.sp) }
                if (FytSteer.sessionActive) PillAction("Lagre i stereoen", Modifier.fillMaxWidth(), primary = true) { FytSteer.save() }
                if (setup && (!Fyt.connected || FytSteer.noAnswer)) PillAction("Åpne stereoens eget oppsett", Modifier.fillMaxWidth(), primary = !Fyt.connected) { Steer.openSetup(context) }
                if (Fyt.connected && FytSteer.learned.values.any { SteerAdc.isLearnedValue(it) }) {
                    PillAction(if (confirmClear) "Trykk igjen for å slette alle" else "Slett alle lærte", Modifier.fillMaxWidth()) {
                        if (confirmClear) { confirmClear = false; FytSteer.clearAll() } else confirmClear = true
                    }
                }
            }
            // Høyre: flisene (det rattet har først, så valgfrie)
            Column(Modifier.weight(0.64f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                (SteerAdc.main + SteerAdc.extra).chunked(3).forEach { row ->
                    // Like høye fliser i raden, også når en tekst går over to linjer (stor tekst på stereoen)
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { f -> SteerTile(f, Modifier.weight(1f).fillMaxHeight()) }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Text("«Kart» (DISP) åpner det samme som NAVI-knappen. «Bytt kilde» (MODE) bytter mellom radio, musikk og " +
                    "Bluetooth – T'OS viser sin side for kilden. Neste/forrige går til musikken som spiller (f.eks. Spotify).",
                    color = TextMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SteerTile(f: SteerAdc.Fn, modifier: Modifier) {
    val value = FytSteer.learned[f.slot]?.takeIf { SteerAdc.isLearnedValue(it) }
    val waiting = FytSteer.waitingSlot == f.slot
    val lit = FytSteer.flashSlot == f.slot
    val sub = when {
        waiting && FytSteer.captured != null -> "Fikk tallet ${FytSteer.captured} …"
        waiting -> "Hold inne rattknappen nå …"
        value != null -> "✓ Lært (tall $value)"
        else -> "Ikke lært"
    }
    Column(
        modifier.heightIn(min = 104.dp).clip(RoundedCornerShape(20.dp))
            .background(if (waiting) TextPrimary else Card)
            .then(if (lit) Modifier.border(2.dp, PingGreen, RoundedCornerShape(20.dp)) else Modifier)
            .clickable { if (waiting) FytSteer.cancelWait() else FytSteer.learn(f.slot) }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(f.label, color = if (waiting) Bg else TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (lit) PingDot()
        }
        if (f.wheel.isNotEmpty()) Text("Rattet: ${f.wheel}", color = if (waiting) Bg else TextMuted, fontSize = 13.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        Text(sub, color = if (waiting) Bg else if (value != null) TextPrimary else TextMuted, fontSize = 13.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** Liten grønn «fikk signal»-prikk (brukerens ønske – eneste farge på siden). */
@Composable
private fun PingDot() {
    Box(Modifier.size(12.dp).clip(CircleShape).background(PingGreen).semantics { contentDescription = "Knappen er kjent igjen" })
}

private val PingGreen = Color(0xFF3DDC84)

@Composable
private fun KeyRow(k: PanelKey, onChanged: () -> Unit) {
    val context = LocalContext.current
    val action = Buttons.action(context, k)
    val code = Buttons.code(context, k)
    val fyt = Buttons.fytId(context, k)
    val pkg = Buttons.pkg(context, k)
    val fytDefault = pkg != null && Buttons.isFytDefault(context, k)
    // Appens navn («Radio», «Music Player», «Bluetooth»), ikke bare slutten av pakkenavnet
    val pkgLabel = remember(pkg) {
        pkg?.let { p -> runCatching { context.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } }.getOrNull()?.takeIf { it.isNotBlank() } ?: p.substringAfterLast('.') }
    }
    val learning = Learn.target == k

    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(18.dp)).background(Card).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Grønn prikk når T'OS akkurat kjente igjen denne knappen (ellers en tom plass, så teksten ikke hopper)
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            if (KeyPing.lit && KeyPing.key == k) PingDot()
        }
        Text(k.label, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(72.dp))

        // Hva knappen gjør: kort trykk og langt trykk (trykk for å endre)
        ActionPicker(action, Modifier.weight(1f)) { Buttons.setAction(context, k, it); onChanged() }
        ActionPicker(Buttons.longAction(context, k), Modifier.weight(0.8f), prefix = "Langt: ") { Buttons.setLongAction(context, k, it); onChanged() }

        Text(
            when {
                learning -> "Venter …"
                code > 0 -> "Kode $code"
                fyt != null -> SteerLogic.describeId(fyt)
                pkg != null -> "Åpner $pkgLabel" + if (fytDefault) " (FYT)" else ""
                else -> "Ikke lært"
            },
            color = TextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp),
        )

        Box(
            Modifier.height(48.dp).clip(RoundedCornerShape(24.dp))
                .background(if (learning) TextPrimary else Line)
                .clickable { if (learning) Learn.cancel() else Learn.start(k); onChanged() }
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (learning) "Avbryt" else if (code > 0 || fyt != null || (pkg != null && !fytDefault)) "Lær på nytt" else "Lær",
                color = if (learning) Bg else TextPrimary, fontSize = 14.sp)
        }
    }
}
