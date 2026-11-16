package no.bil.launcher

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/*
 * Kart og fartsgrenser uten nett.
 *
 * I bilen kommer nettet fra iPhonen (delt nett), og det er sjelden klart når motoren starter. Før lå kartflisene bare i
 * minnet (60 stk.), så kartkortet var tomt ved hver kaldstart og der det ikke er dekning. Nå lagres hver flis som er
 * hentet på disk, og fartsgrensene for veier T'OS har sett før huskes.
 *
 * Plassering: filesDir, ikke cacheDir. Android – og stereoens egen «StorageClearManage»-app (com.unisoc.storageclearmanager)
 * – kan tømme cacheDir når som helst, og da er kartet borte akkurat når nettet mangler. T'OS holder selv størrelsen nede
 * (maks [TileCacheLogic.MAX_BYTES], de minst brukte flisene slettes først), og «Tøm kartbufferen» ligger i Innstillinger.
 */

/** Ren logikk for kartbufferen (testes i MapCacheTest). */
internal object TileCacheLogic {
    /** «820 kB» / «12,4 MB» (v5.17: «0 MB» for 196 fliser så ut som tom buffer). */
    fun sizeText(bytes: Long): String = if (bytes < 1_048_576) "${(bytes + 1023) / 1024} kB"
        else String.format(java.util.Locale.forLanguageTag("nb"), "%.1f MB", bytes / 1_048_576.0)

    /** Maks størrelse på disk. Over dette slettes de minst brukte flisene ned til [TARGET_BYTES]. */
    const val MAX_BYTES = 150L * 1024 * 1024
    const val TARGET_BYTES = 135L * 1024 * 1024
    /** En flis eldre enn dette hentes på nytt i bakgrunnen når det er nett (men vises fortsatt). */
    const val STALE_MS = 30L * 24 * 3600 * 1000

    fun key(z: Int, x: Int, y: Int) = "$z/$x/$y"

    /** Filnavnet under kartmappa: én mappe per zoom, så ingen mappe får titusener av filer. */
    fun relPath(z: Int, x: Int, y: Int) = "$z/${x}_$y.tile"

    /** Leser tilbake (z, x, y) fra en sti laget av [relPath]; null for alt annet (midlertidige filer o.l.). */
    fun parse(rel: String): Triple<Int, Int, Int>? {
        val parts = rel.split('/')
        if (parts.size != 2 || !parts[1].endsWith(".tile")) return null
        val z = parts[0].toIntOrNull() ?: return null
        val xy = parts[1].removeSuffix(".tile").split('_')
        if (xy.size != 2) return null
        val x = xy[0].toIntOrNull() ?: return null
        val y = xy[1].toIntOrNull() ?: return null
        return Triple(z, x, y)
    }

    fun stale(fetchedAt: Long, now: Long) = fetchedAt <= 0L || now - fetchedAt > STALE_MS

    data class Entry(val key: String, val bytes: Long, val lastUse: Long)

    /**
     * Hvilke fliser som skal slettes: ingenting så lenge alt er under [max]; ellers de minst brukte først, til det
     * som er igjen er under [target] (litt luft, så det ikke slettes én flis for hver nye).
     */
    fun evict(entries: Collection<Entry>, max: Long = MAX_BYTES, target: Long = TARGET_BYTES): List<String> {
        var total = entries.sumOf { it.bytes }
        if (total <= max) return emptyList()
        val out = ArrayList<String>()
        for (e in entries.sortedBy { it.lastUse }) {
            if (total <= target) break
            out += e.key; total -= e.bytes
        }
        return out
    }

    /** Flisene i en firkant med «radius» [r] rundt (x, y) på zoom [z], nærmeste først. */
    fun ring(z: Int, x: Int, y: Int, r: Int): List<Triple<Int, Int, Int>> {
        val n = 1 shl z
        return (-r..r).flatMap { dy -> (-r..r).map { dx -> dx to dy } }
            .sortedBy { (dx, dy) -> maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) }
            .map { (dx, dy) -> Triple(z, ((x + dx) % n + n) % n, (y + dy).coerceIn(0, n - 1)) }
            .distinct()
    }
}

/** Kartfliser på disk (filesDir/kartfliser). Alt her skjer på bakgrunnstråder (MapTiles.around kjører på IO). */
object TileCache {
    private var dir: File? = null
    private class Meta(val bytes: Long, val fetchedAt: Long, @Volatile var lastUse: Long)
    private val index = ConcurrentHashMap<String, Meta>()
    private val total = AtomicLong(0)
    @Volatile private var loaded = false
    private val loadLock = Any()

    // Tall siden T'OS startet (til kartleggingen / «Send alt»)
    val memHits = AtomicInteger(0)
    val diskHits = AtomicInteger(0)
    val netFetches = AtomicInteger(0)
    val misses = AtomicInteger(0)

    /** Fliser som ligger på disk men er gamle: hentes på nytt av [MapPrefetch] når det er nett. */
    internal val refresh: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun init(c: Context) { if (dir == null) dir = File(c.filesDir, "kartfliser") }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            val d = dir ?: return
            runCatching {
                d.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(d).path.replace(File.separatorChar, '/')
                    val t = TileCacheLogic.parse(rel)
                    if (t == null) { if (f.name.endsWith(".tmp")) f.delete(); return@forEach }   // halvferdig skriving
                    val m = Meta(f.length(), f.lastModified(), f.lastModified())
                    index[TileCacheLogic.key(t.first, t.second, t.third)] = m
                    total.addAndGet(m.bytes)
                }
            }
            loaded = true
        }
    }

    private fun file(z: Int, x: Int, y: Int): File? = dir?.let { File(it, TileCacheLogic.relPath(z, x, y)) }

    /** Flisa fra disk (rå bytes, slik serveren sendte den), eller null. Gamle fliser legges i kø for oppfrisking. */
    fun get(z: Int, x: Int, y: Int): ByteArray? {
        ensureLoaded()
        val key = TileCacheLogic.key(z, x, y)
        val m = index[key] ?: return null
        val f = file(z, x, y) ?: return null
        val bytes = runCatching { f.readBytes() }.getOrNull()
        if (bytes == null || bytes.isEmpty()) { remove(key, f); return null }
        m.lastUse = System.currentTimeMillis()
        if (TileCacheLogic.stale(m.fetchedAt, System.currentTimeMillis())) refresh += key
        return bytes
    }

    fun has(z: Int, x: Int, y: Int): Boolean { ensureLoaded(); return index.containsKey(TileCacheLogic.key(z, x, y)) }

    fun isStale(z: Int, x: Int, y: Int): Boolean {
        ensureLoaded()
        val m = index[TileCacheLogic.key(z, x, y)] ?: return true
        return TileCacheLogic.stale(m.fetchedAt, System.currentTimeMillis())
    }

    /** Lagrer en flis (bare ekte kartbilder – kalles etter HTTP 200 og vellykket dekoding). Trygg skriving: tmp + rename. */
    fun put(z: Int, x: Int, y: Int, bytes: ByteArray) {
        val f = file(z, x, y) ?: return
        ensureLoaded()
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + "." + Thread.currentThread().id + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) { tmp.delete(); return } }
            val key = TileCacheLogic.key(z, x, y)
            val now = System.currentTimeMillis()
            index.put(key, Meta(bytes.size.toLong(), now, now))?.let { total.addAndGet(-it.bytes) }
            total.addAndGet(bytes.size.toLong())
            refresh -= key
        }
        trim()
    }

    private fun remove(key: String, f: File?) {
        index.remove(key)?.let { total.addAndGet(-it.bytes) }
        f?.delete()
    }

    private fun trim() {
        if (total.get() <= TileCacheLogic.MAX_BYTES) return
        val entries = index.map { (k, m) -> TileCacheLogic.Entry(k, m.bytes, m.lastUse) }
        for (k in TileCacheLogic.evict(entries)) {
            val p = k.split('/').map { it.toInt() }
            remove(k, file(p[0], p[1], p[2]))
        }
    }

    val bytes: Long get() { ensureLoaded(); return total.get() }
    val count: Int get() { ensureLoaded(); return index.size }

    /** «Tøm kartbufferen» i Innstillinger. */
    fun clear() {
        dir?.let { runCatching { it.deleteRecursively() } }
        index.clear(); total.set(0); refresh.clear()
    }

    fun report(): String {
        val asked = memHits.get() + diskHits.get() + netFetches.get() + misses.get()
        val fromCache = memHits.get() + diskHits.get()
        val pct = if (asked == 0) "-" else "${fromCache * 100 / asked} %"
        return "Kartbuffer: $count fliser, ${TileCacheLogic.sizeText(bytes)} (maks ${TileCacheLogic.MAX_BYTES / 1_048_576} MB)  " +
            "siden start: minne ${memHits.get()}, disk ${diskHits.get()}, nett ${netFetches.get()}, mangler ${misses.get()}  " +
            "uten nett-treff: $pct  venter på oppfrisking: ${refresh.size}  forhåndshentet: ${MapPrefetch.fetched.get()}" +
            (MapPrefetch.pauseText()?.let { "  ($it)" } ?: "")
    }

    @androidx.annotation.VisibleForTesting
    internal fun resetForTest(c: Context?) {
        index.clear(); total.set(0); refresh.clear(); loaded = false
        dir = c?.let { File(it.filesDir, "kartfliser") }
        memHits.set(0); diskHits.set(0); netFetches.set(0); misses.set(0)
    }
}

/**
 * Henter fliser i forkant, høflig: bare når T'OS vises, stereoen er våken, det er nett og bilen står stille (hjemme,
 * på parkeringen – det er der kaldstarten skjer). Mens du kjører lagres flisene kartet uansett henter (5 × 5 rundt
 * deg), så forhåndshentingen bruker ikke mobildata fra iPhonen hele turen. Ikke når det nedlastede kartet (PMTiles)
 * brukes – da vises ikke nettkartet i det hele tatt.
 */
object MapPrefetch {
    /** Zoomnivåene forsiden bruker (zoomFor: 13–16, flisene går til 16). */
    private val ZOOMS = 13..MAX_TILE_ZOOM
    private const val RADIUS = 3              // 7 × 7 fliser per zoom rundt posisjonen
    private const val MAX_PER_HOUR = 300      // ≈ 4–5 MB
    private const val GAP_MS = 400L           // én flis om gangen, aldri fortere enn dette

    val fetched = AtomicInteger(0)
    @Volatile private var pausedUntil = 0L
    private var hourStart = 0L
    private var hourCount = 0

    fun pauseText(): String? {
        val left = pausedUntil - SystemClock.elapsedRealtime()
        return if (left > 0) "forhåndshenting hviler ${left / 60_000 + 1} min" else null
    }

    /** Serveren sa nei (429/503 o.l.): la den være i fred en stund. */
    fun backoff(ms: Long = 10 * 60_000L) { pausedUntil = SystemClock.elapsedRealtime() + ms }

    fun start(c: Context) {
        Hub.scope.launch(Dispatchers.IO) {
            delay(20_000)                       // ikke mens T'OS starter
            while (true) {
                Power.awaitUi()                 // bare når T'OS vises og stereoen er våken
                runCatching { round(c) }
                delay(60_000)
            }
        }
    }

    private suspend fun round(c: Context) {
        if (!Hub.online || SafeMode.on || SystemClock.elapsedRealtime() < pausedUntil) return
        if (OfflineMaps.ready(c)) return
        // Gamle fliser som er vist: frisk opp først (de er allerede i bruk)
        for (key in TileCache.refresh.toList().take(40)) {
            if (!allowed()) return
            val p = key.split('/').mapNotNull { it.toIntOrNull() }
            if (p.size == 3) MapTiles.download(p[0], p[1], p[2], prefetch = true)
            TileCache.refresh -= key
            delay(GAP_MS)
        }
        val g = Hub.gps
        val (lat, lon) = when {
            g != null && !g.stale && g.speedKmh < 5 -> g.lat to g.lon
            g == null && Parking.known -> Parking.lat to Parking.lon
            else -> return                       // kjører: kartet lagrer det det henter selv
        }
        for (z in ZOOMS) {
            val (tx, ty) = tileOf(lat, lon, z)
            for ((zz, x, y) in TileCacheLogic.ring(z, tx, ty, RADIUS)) {
                if (TileCache.has(zz, x, y)) continue
                if (!allowed()) return
                MapTiles.download(zz, x, y, prefetch = true) ?: return   // feil/ingen svar: prøv neste runde
                fetched.incrementAndGet()
                delay(GAP_MS)
            }
        }
    }

    private fun allowed(): Boolean {
        if (!Hub.online || SystemClock.elapsedRealtime() < pausedUntil) return false
        if (!Power.state.value.plan.ui) return false
        val now = SystemClock.elapsedRealtime()
        if (hourStart == 0L || now - hourStart > 3_600_000) { hourStart = now; hourCount = 0 }
        if (hourCount >= MAX_PER_HOUR) return false
        hourCount++
        return true
    }
}

/* ======================= Fartsgrenser uten nett ======================= */

/** Ren logikk for fartsgrense-bufferen (testes i MapCacheTest). */
internal object SpeedCacheLogic {
    /** Rutenett på ca. 220 m (nord–sør); øst–vest blir rutene smalere mot nord, det gjør ingenting. */
    const val CELL_DEG = 0.002
    const val MAX_AGE_MS = 90L * 24 * 3600 * 1000
    const val MAX_WAYS = 4000
    /** Uten nett: veien må være så nær (meter) for å regnes som den bilen er på. */
    const val NEAR_M = 20.0

    fun cell(lat: Double, lon: Double): Long {
        val a = kotlin.math.floor(lat / CELL_DEG).toLong()
        val b = kotlin.math.floor(lon / CELL_DEG).toLong()
        return (a shl 32) xor (b and 0xffffffffL)
    }

    /** Rutene veien går gjennom (hvert punkt, og punkter imellom når to punkter ligger langt fra hverandre). */
    fun cellsOf(points: List<Pair<Double, Double>>): Set<Long> {
        val out = HashSet<Long>()
        for (i in points.indices) {
            val (la, lo) = points[i]
            out += cell(la, lo)
            if (i + 1 < points.size) {
                val (lb, lob) = points[i + 1]
                val steps = (maxOf(kotlin.math.abs(lb - la), kotlin.math.abs(lob - lo)) / (CELL_DEG / 2)).toInt()
                for (s in 1..steps) out += cell(la + (lb - la) * s / (steps + 1), lo + (lob - lo) * s / (steps + 1))
            }
        }
        return out
    }

    /** Rutene rundt en posisjon (3 × 3), så en vei like over grensen til naboruta også finnes. */
    fun around(lat: Double, lon: Double): List<Long> =
        (-1..1).flatMap { a -> (-1..1).map { b -> cell(lat + a * CELL_DEG, lon + b * CELL_DEG) } }.distinct()

    fun expired(savedAt: Long, now: Long) = now - savedAt > MAX_AGE_MS

    /** Bare merkene fartsgrensen trenger, så fila holdes liten. */
    val KEEP_TAGS = setOf("maxspeed", "maxspeed:forward", "maxspeed:backward", "highway")
}

/**
 * Veier (med fartsgrense-merkene og formen) som Overpass har svart med, lagret i filesDir/fartsgrenser.json.
 * Ett svar gir hele veien, så en vei du har kjørt én gang har grense også der det ikke er nett.
 */
internal object SpeedCache {
    private class CachedWay(val id: Long, val way: SpeedLimit.Way, val savedAt: Long, val cells: Set<Long>)
    private val ways = ConcurrentHashMap<Long, CachedWay>()
    private val byCell = ConcurrentHashMap<Long, MutableSet<Long>>()
    private var file: File? = null
    @Volatile private var loaded = false
    @Volatile private var dirty = false
    private val lock = Any()
    val hits = AtomicInteger(0)

    fun init(c: Context) { if (file == null) file = File(c.filesDir, "fartsgrenser.json") }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val f = file
            if (f != null && f.exists()) runCatching {
                val arr = JSONArray(f.readText())
                val now = System.currentTimeMillis()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val saved = o.optLong("t")
                    if (SpeedCacheLogic.expired(saved, now)) continue
                    val tagsO = o.optJSONObject("g") ?: JSONObject()
                    val tags = tagsO.keys().asSequence().associateWith { tagsO.optString(it) }
                    val p = o.optJSONArray("p") ?: continue
                    val pts = (0 until p.length() / 2).map { p.getDouble(it * 2) to p.getDouble(it * 2 + 1) }
                    addLocked(o.optLong("id"), SpeedLimit.Way(tags, pts), saved)
                }
            }
            loaded = true
        }
    }

    private fun addLocked(id: Long, w: SpeedLimit.Way, savedAt: Long) {
        ways[id]?.let { old -> old.cells.forEach { byCell[it]?.remove(id) } }
        val cw = CachedWay(id, w, savedAt, SpeedCacheLogic.cellsOf(w.points))
        ways[id] = cw
        cw.cells.forEach { byCell.getOrPut(it) { ConcurrentHashMap.newKeySet() }.add(id) }
    }

    /** Husk veiene fra et Overpass-svar. Kalles på IO. */
    fun remember(found: List<Pair<Long, SpeedLimit.Way>>) {
        if (file == null || found.isEmpty()) return
        ensureLoaded()
        synchronized(lock) {
            val now = System.currentTimeMillis()
            for ((id, w) in found) {
                if (w.points.isEmpty()) continue
                val tags = w.tags.filterKeys { it in SpeedCacheLogic.KEEP_TAGS }
                // 5 desimaler ≈ 1 m: godt nok, og fila blir mye mindre
                val pts = w.points.map { (a, b) -> Math.round(a * 1e5) / 1e5 to Math.round(b * 1e5) / 1e5 }
                addLocked(id, SpeedLimit.Way(tags, pts), now)
            }
            if (ways.size > SpeedCacheLogic.MAX_WAYS) {
                ways.values.sortedBy { it.savedAt }.take(ways.size - SpeedCacheLogic.MAX_WAYS).forEach { old ->
                    ways.remove(old.id); old.cells.forEach { byCell[it]?.remove(old.id) }
                }
            }
            dirty = true
        }
        scheduleSave()
    }

    /** Veiene T'OS kjenner rundt posisjonen (kan være tom). */
    fun near(lat: Double, lon: Double): List<SpeedLimit.Way> {
        if (file == null) return emptyList()
        ensureLoaded()
        val now = System.currentTimeMillis()
        return SpeedCacheLogic.around(lat, lon).flatMap { byCell[it].orEmpty() }.distinct()
            .mapNotNull { ways[it] }.filter { !SpeedCacheLogic.expired(it.savedAt, now) }.map { it.way }
    }

    val size: Int get() { ensureLoaded(); return ways.size }

    @Volatile private var saving = false
    private fun scheduleSave() {
        if (saving) return
        saving = true
        Hub.scope.launch(Dispatchers.IO) {
            delay(30_000)                       // samle opp: ikke én skriving per spørring
            saving = false
            save()
        }
    }

    fun save() {
        val f = file ?: return
        if (!dirty) return
        val text = synchronized(lock) {
            dirty = false
            val arr = JSONArray()
            for (cw in ways.values) {
                val o = JSONObject().put("id", cw.id).put("t", cw.savedAt).put("g", JSONObject(cw.way.tags))
                val p = JSONArray(); cw.way.points.forEach { (a, b) -> p.put(a); p.put(b) }
                arr.put(o.put("p", p))
            }
            arr.toString()
        }
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    fun clear() {
        synchronized(lock) { ways.clear(); byCell.clear(); dirty = false }
        file?.delete()
    }

    fun report() = "Fartsgrenser husket: $size veier (maks ${SpeedCacheLogic.MAX_WAYS}, ${SpeedCacheLogic.MAX_AGE_MS / 86_400_000} dager), " +
        "brukt uten nett/fra buffer: ${hits.get()} ganger siden start"

    @androidx.annotation.VisibleForTesting
    internal fun resetForTest(c: Context?) {
        synchronized(lock) { ways.clear(); byCell.clear(); dirty = false; loaded = false }
        file = c?.let { File(it.filesDir, "fartsgrenser.json") }
        hits.set(0)
    }
}
