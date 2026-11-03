package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date

/** Tester for GPS, fartsgrense, turmåler, EU-kontroll, kartnedlasting og equalizer (ren logikk, uten stereoen). */
class DataLogicTest {

    /* ---------- Fartsgrense: velg riktig vei ---------- */

    // Stavanger-ish. 0.0001° breddegrad ≈ 11 m.
    private val lat = 58.9700
    private val lon = 5.7300
    private fun ns(tags: Map<String, String>, dLon: Double) =   // vei nord–sør, dLon øst for bilen
        SpeedLimit.Way(tags, listOf((lat - 0.001) to (lon + dLon), (lat + 0.001) to (lon + dLon)))
    private fun ew(tags: Map<String, String>, dLat: Double) =   // vei øst–vest, dLat nord for bilen
        SpeedLimit.Way(tags, listOf((lat + dLat) to (lon - 0.002), (lat + dLat) to (lon + 0.002)))

    @Test fun naermeste_vei_velges_ikke_forste() {
        val far = ew(mapOf("highway" to "primary", "maxspeed" to "80"), 0.00012)       // ca. 13 m unna, kommer først
        val near = ns(mapOf("highway" to "residential", "maxspeed" to "30"), 0.00005)  // ca. 3 m unna
        assertEquals(SpeedLimit.Result.Found(30), SpeedLimit.pick(listOf(far, near), lat, lon, null))
    }

    @Test fun retning_foretrekkes_framfor_litt_naermere_sidevei() {
        val side = ew(mapOf("highway" to "residential", "maxspeed" to "30"), 0.00004)  // ca. 4 m, på tvers
        val main = ns(mapOf("highway" to "primary", "maxspeed" to "80"), 0.00012)      // ca. 7 m, samme retning
        assertEquals(SpeedLimit.Result.Found(80), SpeedLimit.pick(listOf(side, main), lat, lon, 0f))     // kjører nordover
        assertEquals(SpeedLimit.Result.Found(80), SpeedLimit.pick(listOf(side, main), lat, lon, 180f))   // sørover går også
        assertEquals(SpeedLimit.Result.Found(30), SpeedLimit.pick(listOf(side, main), lat, lon, 90f))    // østover: sideveien
    }

    @Test fun vei_uten_fartsgrense_gir_ingen() {
        val near = ns(mapOf("highway" to "residential"), 0.00003)
        val far = ew(mapOf("highway" to "primary", "maxspeed" to "80"), 0.00013)
        assertEquals(SpeedLimit.Result.NoneTagged, SpeedLimit.pick(listOf(near, far), lat, lon, null))
        assertEquals(SpeedLimit.Result.NoneTagged, SpeedLimit.pick(emptyList(), lat, lon, null))
    }

    @Test fun fartsgrense_per_kjoreretning() {
        val w = ns(mapOf("highway" to "primary", "maxspeed:forward" to "60", "maxspeed:backward" to "50"), 0.00002)
        assertEquals(SpeedLimit.Result.Found(60), SpeedLimit.pick(listOf(w), lat, lon, 5f))      // samme vei som tegnet
        assertEquals(SpeedLimit.Result.Found(50), SpeedLimit.pick(listOf(w), lat, lon, 185f))    // motsatt
    }

    @Test fun avstand_til_veibit() {
        // 0.0001° lengdegrad på 59° N ≈ 5,7 m
        assertEquals(5.7, SpeedLimit.segmentDistM(lat, lon, lat - 0.001, lon + 0.0001, lat + 0.001, lon + 0.0001), 0.3)
        // Utenfor enden av veibiten: avstand til endepunktet
        assertEquals(111.3, SpeedLimit.segmentDistM(lat, lon, lat + 0.001, lon, lat + 0.002, lon), 0.5)
        assertEquals(90.0, SpeedLimit.bearingDeg(lat, lon, lat, lon + 0.01), 0.5)
        assertEquals(0.0, SpeedLimit.bearingDeg(lat, lon, lat + 0.01, lon), 0.5)
    }

    /* ---------- Turmåler: tunnel uten GPS ---------- */

    @Test fun tur_hull_telles_ved_troverdig_fart() {
        assertTrue(tripCounts(25f, 1000))                // vanlig måling
        assertFalse(tripCounts(300f, 1000))              // hopp
        assertTrue(tripCounts(2000f, 90_000))            // 2 km på 90 s = 80 km/t gjennom tunnel
        assertFalse(tripCounts(20_000f, 120_000))        // 600 km/t: feil posisjon
        assertFalse(tripCounts(5f, 600_000))             // sto stille mens GPS var borte
        assertFalse(tripCounts(50_000f, 3_600_000))      // over 30 min: ny tur
    }

    @Test fun gps_alder() {
        assertEquals(2_000_000_000L, gpsAgeNanos(1_000_000_000L, 3_000_000_000L))
        assertEquals(0L, gpsAgeNanos(0L, 3_000_000_000L))              // mangler tidspunkt
        assertEquals(0L, gpsAgeNanos(5_000_000_000L, 3_000_000_000L))  // i framtiden
        assertNull(Gps(50, lat, lon, 0f, stale = true).speed)
        assertEquals(50, Gps(50, lat, lon, 0f).speed)
    }

    /* ---------- EU-kontroll ---------- */

    private val f = SimpleDateFormat("yyyy-MM-dd")
    private fun d(s: String): Date = f.parse(s)!!
    private fun eu(old: String?, today: String) = f.format(nextEu(old?.let { d(it) }, d(today)))

    @Test fun eu_kontroll_neste_frist() {
        assertEquals("2028-06-15", eu("2026-06-15", "2026-05-01"))   // 1,5 mnd før fristen: beholder rytmen
        assertEquals("2028-06-15", eu("2026-06-15", "2026-06-15"))   // på dagen
        assertEquals("2028-06-15", eu("2026-06-15", "2026-08-20"))   // på overtid: fortsatt gammel frist + 2 år
        assertEquals("2028-02-01", eu("2026-06-15", "2026-02-01"))   // over 4 mnd før: fra i dag
        assertEquals("2028-03-10", eu(null, "2026-03-10"))           // ingen frist lagt inn
        assertEquals("2028-11-03", eu("2023-01-10", "2026-11-03"))   // flere år på overtid: fra i dag
    }

    /* ---------- Kartnedlasting ---------- */

    @Test fun innloggingsside_paa_wifi_oppdages() {
        assertTrue(OfflineMaps.fromGitHub("objects.githubusercontent.com", "application/octet-stream"))
        assertTrue(OfflineMaps.fromGitHub("release-assets.githubusercontent.com", null))
        assertTrue(OfflineMaps.fromGitHub("github.com", "application/octet-stream"))
        assertFalse(OfflineMaps.fromGitHub("login.hotspot.no", "application/octet-stream"))
        assertFalse(OfflineMaps.fromGitHub("objects.githubusercontent.com", "text/html; charset=utf-8"))
        assertFalse(OfflineMaps.fromGitHub("evilgithub.com", "application/octet-stream"))
        assertFalse(OfflineMaps.fromGitHub(null, null))
    }

    @Test fun content_range_tolkes() {
        assertEquals(OfflineMaps.ContentRange(100, 199, 1000), OfflineMaps.parseContentRange("bytes 100-199/1000"))
        assertEquals(OfflineMaps.ContentRange(100, 199, null), OfflineMaps.parseContentRange("bytes 100-199/*"))
        assertEquals(OfflineMaps.ContentRange(null, null, 187_400_000), OfflineMaps.parseContentRange("bytes */187400000"))
        assertNull(OfflineMaps.parseContentRange(null))
        assertNull(OfflineMaps.parseContentRange("tull"))
    }

    /* ---------- Equalizer ---------- */

    @Test fun equalizer_frekvens() {
        assertEquals("60 Hz", eqLabel(60_000))
        assertEquals("910 Hz", eqLabel(910_000))
        assertEquals("3,6 kHz", eqLabel(3_600_000))
        assertEquals("14 kHz", eqLabel(14_000_000))
        assertEquals("1 kHz", eqLabel(1_000_000))
    }
}
