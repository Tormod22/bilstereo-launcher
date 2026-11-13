package no.bil.launcher

import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * «Bruksanvisning» (Screen.HELP; Innstillinger → Om stereoen og Sikkerhetskopi og hjelp) og den rolige oppstartsskjermen.
 *
 * Teksten er docs/BRUKSANVISNING.md, kopiert til assets/bruksanvisning.md (HelpDocTest passer på at de er like).
 * Vises med en enkel egen tolk: overskrifter, avsnitt, punkter, nummererte steg, **fet** og kodelinjer – ingen bibliotek.
 */

/** Én del av bruksanvisningen. */
sealed class HelpBlock {
    data class Title(val text: String) : HelpBlock()
    data class Section(val text: String) : HelpBlock()
    data class Sub(val text: String) : HelpBlock()
    data class Para(val text: String) : HelpBlock()
    data class Bullet(val text: String) : HelpBlock()
    data class Step(val n: Int, val text: String) : HelpBlock()
    data class Code(val text: String) : HelpBlock()
}

/** Ren logikk (testes): markdown → blokker, og **fet** / `kode` i en linje. */
object HelpDoc {
    const val ASSET = "bruksanvisning.md"
    private val stepRx = Regex("""^(\d+)[.)]\s+(.*)$""")

    fun parse(md: String): List<HelpBlock> {
        val out = mutableListOf<HelpBlock>()
        val para = StringBuilder()
        var code: StringBuilder? = null
        fun flush() { if (para.isNotBlank()) out += HelpBlock.Para(para.toString().trim()); para.clear() }
        fun extend(b: HelpBlock, t: String): HelpBlock? = when (b) {
            is HelpBlock.Bullet -> b.copy(text = b.text + " " + t)
            is HelpBlock.Step -> b.copy(text = b.text + " " + t)
            else -> null
        }
        for (raw in md.replace("\r", "").lines()) {
            val line = raw.trimEnd()
            if (code != null) {
                if (line.trim().startsWith("```")) { out += HelpBlock.Code(code.toString().trimEnd('\n')); code = null }
                else code.append(line.trim()).append('\n')
                continue
            }
            val t = line.trim()
            when {
                t.startsWith("```") -> { flush(); code = StringBuilder() }
                t.isEmpty() -> flush()
                t.startsWith("### ") -> { flush(); out += HelpBlock.Sub(t.removePrefix("### ").trim()) }
                t.startsWith("## ") -> { flush(); out += HelpBlock.Section(t.removePrefix("## ").trim()) }
                t.startsWith("# ") -> { flush(); out += HelpBlock.Title(t.removePrefix("# ").trim()) }
                t.startsWith("- ") || t.startsWith("* ") -> { flush(); out += HelpBlock.Bullet(t.substring(2).trim()) }
                stepRx.matches(t) -> { flush(); val m = stepRx.find(t)!!; out += HelpBlock.Step(m.groupValues[1].toInt(), m.groupValues[2].trim()) }
                // Innrykket fortsettelse av et punkt eller steg
                line.startsWith("  ") && para.isEmpty() && out.lastOrNull()?.let { extend(it, t) } != null ->
                    out[out.lastIndex] = extend(out.last(), t)!!
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
            }
        }
        code?.let { out += HelpBlock.Code(it.toString().trimEnd('\n')) }
        flush()
        return out
    }

    /** Teksten uten markdown-tegn: delene som skal være fete (indeksområder i [plain]). */
    data class Inline(val plain: String, val bold: List<IntRange>, val code: List<IntRange>)

    fun inline(s: String): Inline {
        val sb = StringBuilder(); val bold = mutableListOf<IntRange>(); val code = mutableListOf<IntRange>()
        var i = 0
        while (i < s.length) {
            if (s.startsWith("**", i)) {
                val end = s.indexOf("**", i + 2)
                if (end > i + 2) { val st = sb.length; sb.append(s, i + 2, end); bold += st until sb.length; i = end + 2; continue }
            }
            if (s[i] == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i + 1) { val st = sb.length; sb.append(s, i + 1, end); code += st until sb.length; i = end + 1; continue }
            }
            sb.append(s[i]); i++
        }
        return Inline(sb.toString(), bold, code)
    }

    /** Overskriftene til venstre: (blokkens plass i lista, tekst). */
    fun sections(blocks: List<HelpBlock>): List<Pair<Int, String>> =
        blocks.mapIndexedNotNull { i, b -> if (b is HelpBlock.Section) i to b.text else null }

    /** Overskriften som hører til det som vises øverst nå (indeks i [sections]), -1 før første. */
    fun currentSection(sections: List<Pair<Int, String>>, firstVisible: Int): Int =
        sections.indexOfLast { it.first <= firstVisible }

    private val cached = java.util.concurrent.ConcurrentHashMap<String, List<HelpBlock>>()

    /** Leser [asset] (bruksanvisningen, eller «Hva er nytt» = nytt.md) én gang. */
    fun load(c: Context, asset: String = ASSET, title: String = "Bruksanvisning"): List<HelpBlock> = cached[asset] ?: runCatching {
        parse(c.assets.open(asset).bufferedReader().use { it.readText() })
    }.getOrElse {
        listOf(HelpBlock.Title(title), HelpBlock.Para("Fant ikke teksten i denne versjonen av T'OS."))
    }.also { cached[asset] = it }
}

private fun styled(s: String): AnnotatedString {
    val inl = HelpDoc.inline(s)
    return buildAnnotatedString {
        append(inl.plain)
        inl.bold.forEach { addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = TextPrimary), it.first, it.last + 1) }
        inl.code.forEach { addStyle(SpanStyle(fontFamily = FontFamily.Monospace), it.first, it.last + 1) }
    }
}

/** Bruksanvisningen – og «Hva er nytt» (samme visning, annen tekst: [asset], [title], knapper i [extra]). */
@Composable
fun HelpScreen(
    onClose: () -> Unit, title: String = "Bruksanvisning", asset: String = HelpDoc.ASSET,
    extra: @Composable RowScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val blocks by produceState<List<HelpBlock>?>(null, asset) {
        value = withContext(Dispatchers.IO) { HelpDoc.load(context.applicationContext, asset, title) }
    }
    Page(title, onClose, extra) {
        val list = blocks
        if (list == null) Text("Henter …", color = TextMuted, fontSize = 18.sp)
        else {
            val sections = remember(list) { HelpDoc.sections(list) }
            val state = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val current by remember(sections) { derivedStateOf { HelpDoc.currentSection(sections, state.firstVisibleItemIndex) } }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                // Venstre: innholdet (trykk = hopp dit)
                LazyColumn(Modifier.width(250.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(sections) { si, (index, title) ->
                        val on = si == current.coerceAtLeast(0)
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(14.dp))
                                .background(if (on) Line else Panel)
                                .clickable { scope.launch { state.animateScrollToItem(index) } }.padding(horizontal = 14.dp, vertical = 8.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) { Text(title, color = if (on) TextPrimary else TextMuted, fontSize = 16.sp, maxLines = 2) }
                    }
                }
                // Høyre: teksten
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(), state = state,
                    contentPadding = PaddingValues(bottom = 48.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(list) { HelpItem(it) }
                }
            }
        }
    }
}

@Composable
private fun HelpItem(b: HelpBlock) {
    when (b) {
        is HelpBlock.Title -> Text(styled(b.text), color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Medium)
        is HelpBlock.Section -> Text(styled(b.text), color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 18.dp))
        is HelpBlock.Sub -> Text(styled(b.text), color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 6.dp))
        // «Slik gjør du det:» – en liten mellomtittel
        is HelpBlock.Para -> if (b.text.endsWith(":") && b.text.length < 60)
            Text(styled(b.text), color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
        else Text(styled(b.text), color = TextMuted, fontSize = 18.sp, lineHeight = 26.sp)
        is HelpBlock.Bullet -> Row(Modifier.padding(start = 6.dp)) {
            Text("•", color = TextMuted, fontSize = 18.sp, modifier = Modifier.width(22.dp))
            Text(styled(b.text), color = TextMuted, fontSize = 18.sp, lineHeight = 26.sp)
        }
        is HelpBlock.Step -> Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(15.dp)).background(Line), contentAlignment = Alignment.Center) {
                Text("${b.n}", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.width(12.dp))
            Text(styled(b.text), color = TextMuted, fontSize = 18.sp, lineHeight = 26.sp, modifier = Modifier.padding(top = 1.dp))
        }
        is HelpBlock.Code -> Text(b.text, color = TextPrimary, fontSize = 15.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

/* ======================= Oppstart: T'OS-merket på svart ======================= */

/** Når den rolige oppstartsskjermen skal vises. Ren logikk (testes). */
object SplashLogic {
    /** Skjermen må ha vært av så lenge før «våkne» regnes som en ny start (som Startup sine 2 min). */
    const val WAKE_MS = 120_000L
    const val HOLD_MS = 200L
    const val FADE_MS = 600L

    /** T'OS kom fram igjen etter at skjermen var av (tenning av/på), ikke etter at du bare brukte en annen app. */
    fun afterWake(stoppedWithScreenOff: Boolean, stoppedAt: Long, now: Long): Boolean =
        stoppedWithScreenOff && stoppedAt > 0L && now - stoppedAt >= WAKE_MS
}

/**
 * Den rolige oppstartsskjermen: tegnes oppå første bilde (ingen egen aktivitet, ingen forsinkelse), bare når T'OS
 * starter kaldt eller skjermen har vært av en stund – aldri når du går hjem fra en app. Ikke i sikker modus.
 */
object Splash {
    var shownAt by mutableLongStateOf(0L); private set
    private var cold = true
    private var stoppedAt = 0L
    private var stoppedDark = false

    fun onCreate() { if (cold) { cold = false; request() } }
    fun onStop(screenOn: Boolean) { stoppedAt = SystemClock.elapsedRealtime(); stoppedDark = !screenOn }
    fun onStart() {
        if (SplashLogic.afterWake(stoppedDark, stoppedAt, SystemClock.elapsedRealtime())) request()
        stoppedDark = false
    }
    private fun request() { if (!SafeMode.on) shownAt = SystemClock.elapsedRealtime() }
}

@Composable
fun SplashHost() {
    val at = Splash.shownAt
    if (at == 0L) return
    var done by remember(at) { mutableStateOf(false) }
    if (done) return
    val a = remember(at) { Animatable(1f) }
    LaunchedEffect(at) {
        delay(SplashLogic.HOLD_MS)
        a.animateTo(0f, tween(SplashLogic.FADE_MS.toInt(), easing = LinearOutSlowInEasing))
        done = true
    }
    Box(Modifier.fillMaxSize().alpha(a.value).background(Bg), contentAlignment = Alignment.Center) {
        Text("T'OS", color = TextPrimary, fontSize = 64.sp, fontWeight = FontWeight.Light, letterSpacing = 8.sp)
    }
}
