package no.bil.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.KeyboardCapslock
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * T'OS sitt eget tastatur. Androids tastatur er en del av stereoens originale utseende,
 * så tekstfeltene i T'OS ([Field]) åpner dette i stedet. Logikken (oppsett og redigering)
 * er rene funksjoner som testes i UiLogicTest3.
 */

/* ======================= Logikk ======================= */

enum class KbKind { TEXT, NUMBER, PHONE }
/** [MORE]: flere tegn ($ € £ [ ] { } < > ~ | \\ …) – Wi-Fi-passord kan inneholde dem. */
enum class KbLayer { LETTERS, SYMBOLS, MORE }
enum class KbShift { OFF, ONCE, LOCK }

sealed interface KbKey {
    data class Ch(val c: String) : KbKey
    data object Shift : KbKey
    data object Back : KbKey
    data object Space : KbKey
    data class Layer(val to: KbLayer, val label: String) : KbKey
    data object Done : KbKey
}

/** Hvilket tastatur et felt skal ha. */
internal fun kbKind(type: KeyboardType): KbKind = when (type) {
    KeyboardType.Number, KeyboardType.Decimal, KeyboardType.NumberPassword -> KbKind.NUMBER
    KeyboardType.Phone -> KbKind.PHONE
    else -> KbKind.TEXT
}

internal fun kbIsPassword(type: KeyboardType) = type == KeyboardType.Password || type == KeyboardType.NumberPassword

/** Stor forbokstav av seg selv bare i vanlige tekstfelt (ikke passord, e-post eller nettadresser). */
internal fun kbAutoCap(type: KeyboardType) = type == KeyboardType.Text

private fun chars(s: String, upper: Boolean) = s.map { KbKey.Ch(if (upper) it.uppercaseChar().toString() else it.toString()) }

/** Radene på tastaturet. Norsk oppsett med Æ, Ø og Å. */
internal fun kbRows(kind: KbKind, layer: KbLayer, shift: KbShift): List<List<KbKey>> = when (kind) {
    KbKind.NUMBER -> listOf(
        chars("123", false) + KbKey.Back,
        chars("456-", false),
        chars("789,", false),
        chars(".0/", false) + KbKey.Done,
    )
    KbKind.PHONE -> listOf(
        chars("123", false) + KbKey.Back,
        chars("456+", false),
        chars("789", false) + KbKey.Space,
        chars("*0#", false) + KbKey.Done,
    )
    KbKind.TEXT -> when (layer) {
        KbLayer.LETTERS -> {
            val up = shift != KbShift.OFF
            listOf(
                chars("qwertyuiopå", up),
                chars("asdfghjkløæ", up),
                listOf(KbKey.Shift) + chars("zxcvbnm", up) + KbKey.Back,
                listOf(KbKey.Layer(KbLayer.SYMBOLS, "123"), KbKey.Ch(","), KbKey.Space, KbKey.Ch("."), KbKey.Done),
            )
        }
        KbLayer.SYMBOLS -> listOf(
            chars("1234567890", false),
            chars("@#&-_/:;()", false),
            listOf(KbKey.Layer(KbLayer.MORE, "#+=")) + chars("?!'\"+*", false) + KbKey.Back,
            listOf(KbKey.Layer(KbLayer.LETTERS, "ABC"), KbKey.Ch(","), KbKey.Space, KbKey.Ch("."), KbKey.Done),
        )
        // Før manglet $ € £ [ ] { } < > ^ ~ | \ ` helt – et Wi-Fi-passord med slike tegn kunne ikke skrives inn
        KbLayer.MORE -> listOf(
            chars("[]{}<>^~|\\", false),
            chars("\$€£=%`§°±·", false),
            listOf(KbKey.Layer(KbLayer.SYMBOLS, "123")) + chars("?!'\"+*", false) + KbKey.Back,
            listOf(KbKey.Layer(KbLayer.LETTERS, "ABC"), KbKey.Ch(","), KbKey.Space, KbKey.Ch("."), KbKey.Done),
        )
    }
}

/** Hvor bred en tast er (alle radene i et oppsett blir like brede, så tastene står rett under hverandre). */
internal fun kbWeight(k: KbKey, kind: KbKind, layer: KbLayer): Float = when {
    kind != KbKind.TEXT -> 1f
    k is KbKey.Ch -> 1f
    k == KbKey.Space -> if (layer == KbLayer.LETTERS) 5f else 4f
    else -> 2f
}

/** Setter inn [s] der markøren står. Gir ny tekst og ny markørplass. */
internal fun kbInsert(text: String, cursor: Int, s: String): Pair<String, Int> {
    val c = cursor.coerceIn(0, text.length)
    return text.substring(0, c) + s + text.substring(c) to c + s.length
}

/** Sletter tegnet foran markøren (et helt tegn, også emoji som består av to deler). */
internal fun kbBackspace(text: String, cursor: Int): Pair<String, Int> {
    val c = cursor.coerceIn(0, text.length)
    if (c == 0) return text to 0
    val n = if (c >= 2 && text[c - 1].isLowSurrogate() && text[c - 2].isHighSurrogate()) 2 else 1
    return text.substring(0, c - n) + text.substring(c) to c - n
}

/** Shift-knappen: av → én stor bokstav → caps lock → av. */
internal fun kbNextShift(s: KbShift) = when (s) { KbShift.OFF -> KbShift.ONCE; KbShift.ONCE -> KbShift.LOCK; KbShift.LOCK -> KbShift.OFF }

/** Shift etter at teksten er endret: «én stor» slippes etter én bokstav, og ny setning får stor forbokstav. */
internal fun kbShiftAfter(s: KbShift, newText: String, autoCap: Boolean): KbShift {
    val next = if (s == KbShift.ONCE) KbShift.OFF else s
    val sentenceStart = newText.isEmpty() || newText.endsWith(". ") || newText.endsWith("! ") || newText.endsWith("? ")
    return if (next == KbShift.OFF && autoCap && sentenceStart) KbShift.ONCE else next
}

/** Passord vises som prikker (ett per tegn) til brukeren trykker «vis». */
internal fun kbMask(text: String, reveal: Boolean): String = if (reveal) text else "•".repeat(text.codePointCount(0, text.length))

/* ======================= Tilstand ======================= */

/** Koblingen mellom tastaturet og feltet som har det. Lages av [Field] når det trykkes på. */
class KbBinding internal constructor(
    internal val owner: Any,
    val kind: KbKind,
    val autoCap: Boolean,
    val password: Boolean,
    val hint: String,
    val doneLabel: String,
    private val read: () -> String,
    private val write: (String) -> Unit,
    private val done: () -> Unit,
    private val revealed: () -> Boolean,
    private val toggle: () -> Unit,
) {
    val text: String get() = read()
    val reveal: Boolean get() = revealed()
    fun set(s: String) = write(s)
    fun done() = done.invoke()
    fun toggleReveal() = toggle()
}

object TosKeyboard {
    /** Feltet som skriver nå, eller null når tastaturet er skjult. */
    var binding by mutableStateOf<KbBinding?>(null)
        private set

    /** Hvor tastaturet ligger på skjermen (for «trykk utenfor lukker»). */
    internal var bounds: Rect? = null

    val visible: Boolean get() = binding != null

    fun open(b: KbBinding) { binding = b }
    fun close() { binding = null; bounds = null }
    internal fun closeIfOwner(owner: Any) { if (binding?.owner === owner) close() }
}

/* ======================= Visning ======================= */

/** Blinkende markør. */
@Composable
internal fun Caret(height: Dp) {
    val on by produceState(true) { while (true) { delay(530); value = !value } }
    Box(Modifier.padding(start = 1.dp).width(2.dp).height(height).background(if (on) TextPrimary else Color.Transparent))
}

/** Teksten i et felt: siste del vises alltid (markøren står bakerst), hint når det er tomt. */
@Composable
internal fun FieldText(text: String, hint: String, focused: Boolean, modifier: Modifier = Modifier, size: Int = 16) {
    val scroll = rememberScrollState()
    LaunchedEffect(scroll) { snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) } }
    Row(modifier.horizontalScroll(scroll, enabled = false), verticalAlignment = Alignment.CenterVertically) {
        if (text.isEmpty()) {
            if (focused) Caret((size + 6).dp)
            Text(hint, color = TextMuted, fontSize = size.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Text(text, color = TextPrimary, fontSize = size.sp, maxLines = 1, softWrap = false)
            if (focused) Caret((size + 6).dp)
        }
    }
}

/** Tastaturet, festet nederst på skjermen. Legges øverst i Root. */
@Composable
fun KeyboardHost(modifier: Modifier = Modifier) {
    val b = TosKeyboard.binding
    // Siste kobling huskes, så tastaturet kan gli ut av skjermen etter at det er lukket
    val last = remember { arrayOfNulls<KbBinding>(1) }
    if (b != null) last[0] = b
    // Registreres når tastaturet åpnes, så BACK lukker tastaturet før noe annet
    if (b != null) BackHandler { TosKeyboard.close() }
    AnimatedVisibility(b != null, modifier, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        last[0]?.let { KeyboardPanel(it) }
    }
}

@Composable
private fun KeyboardPanel(b: KbBinding) {
    var layer by remember(b.owner) { mutableStateOf(KbLayer.LETTERS) }
    var shift by remember(b.owner) { mutableStateOf(if (b.autoCap && b.text.isEmpty()) KbShift.ONCE else KbShift.OFF) }

    fun edit(new: String) {
        // Slett holdt inne i et tomt felt: ikke skriv den samme teksten om og om igjen (et innstillingsfelt lagret og tegnet
        // hele T'OS på nytt ca. 30 ganger i sekundet så lenge fingeren lå på tasten)
        if (new == b.text) return
        b.set(new); shift = kbShiftAfter(shift, new, b.autoCap)
    }
    fun press(k: KbKey) {
        val t = b.text
        when (k) {
            is KbKey.Ch -> edit(kbInsert(t, t.length, k.c).first)
            KbKey.Space -> edit(kbInsert(t, t.length, " ").first)
            KbKey.Back -> edit(kbBackspace(t, t.length).first)
            KbKey.Shift -> shift = kbNextShift(shift)
            is KbKey.Layer -> layer = k.to
            KbKey.Done -> { TosKeyboard.close(); b.done() }
        }
    }

    // Tastene tilpasses skjermhøyden: aldri under 48 dp, og tastaturet tar ikke mer enn drøyt halve skjermen
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    val keyH = ((screenH * 0.52f - 96.dp) / 4 - 8.dp).coerceIn(48.dp, 64.dp)
    Column(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { TosKeyboard.bounds = it.boundsInRoot() }
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(Panel)
            .border(1.dp, Line, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            // Trykk mellom tastene skal ikke gå gjennom til siden under
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Øverst: det som skrives (feltet kan ligge under tastaturet)
        Row(Modifier.fillMaxWidth().widthIn(max = 1100.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(b.hint, color = TextMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(Line).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart) {
                FieldText(if (b.password) kbMask(b.text, b.reveal) else b.text, "", focused = true, size = 18)
            }
            if (b.password) RoundIcon(if (b.reveal) Icons.Default.VisibilityOff else Icons.Default.Visibility, 48.dp,
                desc = if (b.reveal) "Skjul passordet" else "Vis passordet") { b.toggleReveal() }
            RoundIcon(Icons.Default.KeyboardHide, 48.dp, desc = "Skjul tastaturet") { TosKeyboard.close() }
        }
        Spacer(Modifier.height(10.dp))
        val maxW = if (b.kind == KbKind.TEXT) 1100.dp else 560.dp
        Column(Modifier.fillMaxWidth().widthIn(max = maxW), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            kbRows(b.kind, layer, shift).forEach { row ->
                Row(Modifier.fillMaxWidth().height(keyH), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { k -> KeyButton(k, b, shift, Modifier.weight(kbWeight(k, b.kind, layer)).fillMaxHeight()) { press(k) } }
                }
            }
        }
    }
}

@Composable
private fun KeyButton(k: KbKey, b: KbBinding, shift: KbShift, modifier: Modifier, onPress: () -> Unit) {
    val press by rememberUpdatedState(onPress)
    val scope = rememberCoroutineScope()
    val strong = k == KbKey.Done || (k == KbKey.Shift && shift == KbShift.LOCK)
    val label = when (k) {
        is KbKey.Ch -> k.c
        is KbKey.Layer -> k.label
        KbKey.Done -> b.doneLabel
        KbKey.Space -> "mellomrom"
        else -> ""
    }
    val desc = when (k) {
        KbKey.Back -> "Slett"
        KbKey.Shift -> when (shift) { KbShift.OFF -> "Stor bokstav"; KbShift.ONCE -> "Store bokstaver hele tiden"; KbShift.LOCK -> "Små bokstaver" }
        KbKey.Space -> "Mellomrom"
        else -> label
    }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    var held by remember { mutableStateOf(false) }
    val base = when {
        strong -> TextPrimary
        k is KbKey.Ch || k == KbKey.Space -> Card
        else -> Line
    }
    val bg = if (pressed || held) (if (strong) TextMuted else TextMuted.copy(alpha = 0.45f)) else base
    val fg = if (strong) Bg else TextPrimary
    val shape = RoundedCornerShape(12.dp)

    val input = if (k == KbKey.Back) {
        // Slett: virker med en gang når fingeren kommer ned, og gjentas mens den holdes
        Modifier.pointerInput(b.owner) {
            detectTapGestures(onPress = {
                held = true
                press()
                // Holdes den inne, slettes det fortløpende (raskere etter hvert)
                val job = scope.launch {
                    delay(450); var d = 90L
                    while (true) { press(); delay(d); d = (d - 6).coerceAtLeast(35) }
                }
                tryAwaitRelease()
                job.cancel(); held = false
            })
        }
    } else Modifier.clickable(interactionSource = source, indication = null) { press() }

    Box(
        modifier.clip(shape).background(bg).then(input).semantics { contentDescription = desc; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        when (k) {
            KbKey.Back -> Icon(Icons.AutoMirrored.Filled.Backspace, null, tint = fg, modifier = Modifier.size(26.dp))
            KbKey.Shift -> Icon(if (shift == KbShift.LOCK) Icons.Default.KeyboardCapslock else Icons.Default.ArrowUpward, null,
                tint = if (shift == KbShift.OFF) TextMuted else fg, modifier = Modifier.size(26.dp))
            KbKey.Space -> Text(label, color = TextMuted, fontSize = 14.sp, maxLines = 1)
            is KbKey.Ch -> Text(label, color = fg, fontSize = 22.sp, maxLines = 1)
            else -> Text(label, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
