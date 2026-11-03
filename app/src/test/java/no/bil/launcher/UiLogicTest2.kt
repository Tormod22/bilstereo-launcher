package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLogicTest2 {
    @Test
    fun kartflis_regnes_som_i_MapTiles() {
        // Zoom 0: hele verden er én flis
        assertEquals(0 to 0, tileOf(59.9, 10.7, 0))
        // Oslo på zoom 16 (kjent flis fra OSM-regning)
        val (x, y) = tileOf(59.9139, 10.7522, 16)
        assertEquals(34725, x)
        assertEquals(19062, y)
        // Litt lenger nord samme sted: ny flis i y-retning, samme x
        val (x2, y2) = tileOf(59.9139 + 0.003, 10.7522, 16)
        assertEquals(x, x2)
        assertTrue(y2 < y)
    }

    @Test
    fun kartet_zoomer_ikke_når_posisjonen_flytter_seg_i_midtflisa() {
        val size = 1280f; val half = 512f   // 5 fliser à 256 px, to hele fliser på hver side
        assertEquals(half, mapReach(640f, size, half), 0f)
        assertEquals(half, mapReach(530f, size, half), 0f)
        assertEquals(half, mapReach(760f, size, half), 0f)
        // Utenfor midtflisa (nytt bilde er på vei): kortet skal fortsatt fylles
        assertEquals(300f, mapReach(300f, size, half), 0f)
        assertEquals(1f, mapReach(0f, size, half), 0f)
    }

    @Test
    fun favoritt_med_samme_nummer_legges_ikke_inn_to_ganger() {
        val list = listOf(Contact("Mor", "+47 900 00 000"), Contact("Jobb", "22 22 22 22"))
        val out = withFavorite(list, Contact("Mamma", "+4790000000"))
        assertEquals(2, out.size)
        assertEquals(Contact("Mamma", "+4790000000"), out.last())
        assertTrue(sameNumber("22-22 22 22", "22222222"))
        assertFalse(sameNumber("", ""))
        // Tomt nummer endrer ingenting; tomt navn blir nummeret
        assertEquals(list, withFavorite(list, Contact("X", "  ")))
        assertEquals("123", withFavorite(emptyList(), Contact("", "123")).single().name)
    }
}
