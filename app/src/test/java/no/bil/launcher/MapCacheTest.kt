package no.bil.launcher

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** Kartbufferen og fartsgrense-bufferen: ren logikk (MapCache.kt). */
class MapCacheLogicTest {
    @Test fun flisNavn() {
        assertEquals("15/17000_9700", TileCacheLogic.relPath(15, 17000, 9700).removeSuffix(".tile"))
        assertEquals(Triple(15, 17000, 9700), TileCacheLogic.parse(TileCacheLogic.relPath(15, 17000, 9700)))
        assertNull(TileCacheLogic.parse("15/17000_9700.tile.123.tmp"))   // halvferdig skriving teller ikke
        assertNull(TileCacheLogic.parse("tull.txt"))
        assertNull(TileCacheLogic.parse("15/a_b.tile"))
    }

    @Test fun gammel() {
        val now = 100L * 24 * 3600 * 1000
        assertFalse(TileCacheLogic.stale(now - 29L * 24 * 3600 * 1000, now))
        assertTrue(TileCacheLogic.stale(now - 31L * 24 * 3600 * 1000, now))
        assertTrue(TileCacheLogic.stale(0L, now))
    }

    @Test fun minstBruktSlettesFørst() {
        val e = listOf(
            TileCacheLogic.Entry("a", 40, lastUse = 3),
            TileCacheLogic.Entry("b", 40, lastUse = 1),
            TileCacheLogic.Entry("c", 40, lastUse = 2),
        )
        // Under grensen: ingenting slettes
        assertEquals(emptyList<String>(), TileCacheLogic.evict(e, max = 120, target = 100))
        // Over: de minst brukte først, ned til målet (120 → 80 ≤ 90)
        assertEquals(listOf("b"), TileCacheLogic.evict(e, max = 100, target = 90))
        assertEquals(listOf("b", "c"), TileCacheLogic.evict(e, max = 100, target = 50))
    }

    @Test fun ringRundtPosisjonen() {
        val r = TileCacheLogic.ring(15, 100, 200, 1)
        assertEquals(9, r.size)
        assertEquals(Triple(15, 100, 200), r.first())           // midten først
        assertTrue(Triple(15, 99, 199) in r && Triple(15, 101, 201) in r)
        // Kanten av verden: x går rundt, y stopper
        val edge = TileCacheLogic.ring(2, 0, 0, 1)
        assertTrue(edge.all { it.third in 0..3 && it.second in 0..3 })
        assertTrue(Triple(2, 3, 0) in edge)
    }

    @Test fun rutenettForFartsgrenser() {
        val a = SpeedCacheLogic.cell(58.9700, 5.7300)
        assertEquals(a, SpeedCacheLogic.cell(58.9701, 5.7301))       // samme rute (~220 m)
        assertTrue(a != SpeedCacheLogic.cell(58.9800, 5.7300))
        // En lang veibit uten punkter imellom går likevel gjennom rutene mellom endene
        val cells = SpeedCacheLogic.cellsOf(listOf(58.960 to 5.73, 58.970 to 5.73))
        assertTrue(SpeedCacheLogic.cell(58.965, 5.73) in cells)
        assertEquals(9, SpeedCacheLogic.around(58.97, 5.73).size)
        val day = 24L * 3600 * 1000
        assertFalse(SpeedCacheLogic.expired(0, 89 * day))
        assertTrue(SpeedCacheLogic.expired(0, 91 * day))
    }
}

/** Uten nett: kartflis fra disken og fartsgrense fra en vei T'OS har sett før (Robolectric). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapCacheSmokeTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After fun tidy() {
        Hub.online = true
        TileCache.clear(); TileCache.resetForTest(null)
        SpeedCache.clear(); SpeedCache.resetForTest(null)
    }

    private fun tileBytes(): ByteArray {
        val b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test fun flisFraDiskenUtenNett() = runBlocking {
        TileCache.resetForTest(ctx)
        // Et sted ingen andre tester bruker (flisene i minnet deles mellom testene)
        val lat = 69.6489; val lon = 18.9551
        val (tx, ty) = tileOf(lat, lon, 14)
        TileCache.put(14, tx, ty, tileBytes())
        assertTrue(File(ctx.filesDir, "kartfliser/" + TileCacheLogic.relPath(14, tx, ty)).exists())

        // Som etter en omstart: indeksen leses fra disken på nytt
        TileCache.resetForTest(ctx)
        Hub.online = false
        val img = MapTiles.around(lat, lon, zoom = 14, cols = 1, rows = 1)
        assertNotNull("kartet skal vises fra disken uten nett", img)
        assertEquals(1, TileCache.diskHits.get())
        assertEquals(1, TileCache.count)
        assertTrue(TileCache.report().contains("disk 1"))

        // Tøm: borte fra disken
        TileCache.clear()
        assertEquals(0, TileCache.count)
        assertFalse(File(ctx.filesDir, "kartfliser").exists())
    }

    @Test fun fartsgrenseUtenNett() {
        SpeedCache.resetForTest(ctx)
        val road = SpeedLimit.Way(mapOf("highway" to "primary", "maxspeed" to "60", "name" to "Madlaveien"),
            listOf(58.9600 to 5.7000, 58.9600 to 5.7100))
        val side = SpeedLimit.Way(mapOf("highway" to "residential", "maxspeed" to "30"), listOf(58.9610 to 5.7050, 58.9620 to 5.7050))
        SpeedCache.remember(listOf(11L to road, 22L to side))
        SpeedCache.save()
        assertTrue(File(ctx.filesDir, "fartsgrenser.json").exists())

        // Etter omstart, uten nett: veien bilen er på (her, 5 m fra veien) gir 60
        SpeedCache.resetForTest(ctx)
        assertEquals(SpeedLimit.Result.Found(60), SpeedLimit.fromCache(58.96004, 5.7050, 90f))
        // Langt fra alle kjente veier: vet ikke (ikke «ingen grense»)
        assertEquals(SpeedLimit.Result.Failed, SpeedLimit.fromCache(58.9700, 5.7300, null))
        // Bare merkene som trengs, lagres
        assertFalse(File(ctx.filesDir, "fartsgrenser.json").readText().contains("Madlaveien"))
        assertEquals(2, SpeedCache.size)
    }
}
