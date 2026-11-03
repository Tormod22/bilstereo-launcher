package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.TimeZone

/**
 * Tester utregningene i T'OS som ikke trenger stereoen. Kjøres på hver bygging på GitHub –
 * blir noe ødelagt, stopper byggingen før en feil versjon når bilen.
 */
class LogicTest {

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(y, mo - 1, d, h, mi)
    }

    /* ---------- Nattmodus (sol) ---------- */

    @Test fun oslo_november_soloppgang_og_nedgang() {
        val day = utc(2026, 11, 2, 12).get(Calendar.DAY_OF_YEAR)
        assertEquals(6.67, Sun.utcHour(59.91, 10.75, day, true), 0.15)    // 07:40 norsk tid
        assertEquals(15.33, Sun.utcHour(59.91, 10.75, day, false), 0.15)  // 16:20 norsk tid
    }

    @Test fun stavanger_dag_og_natt() {
        assertFalse(Sun.isNight(58.97, 5.73, utc(2026, 11, 3, 11)))   // midt på dagen
        assertTrue(Sun.isNight(58.97, 5.73, utc(2026, 11, 3, 20)))    // kveld
        assertTrue(Sun.isNight(58.97, 5.73, utc(2026, 11, 3, 3)))     // natt
    }

    @Test fun tromso_morketid_og_midnattssol() {
        assertTrue(Sun.isNight(69.65, 18.96, utc(2026, 12, 15, 11)))  // mørketid: natt hele dagen
        assertFalse(Sun.isNight(69.65, 18.96, utc(2026, 6, 21, 23)))  // midnattssol: dag hele døgnet
    }

    /* ---------- Fartsgrense fra OpenStreetMap ---------- */

    @Test fun fartsgrense_tolkes() {
        assertEquals(80, SpeedLimit.parse("80"))
        assertEquals(50, SpeedLimit.parse("50 km/h"))
        assertEquals(50, SpeedLimit.parse("NO:urban"))
        assertEquals(80, SpeedLimit.parse("NO:rural"))
        assertEquals(110, SpeedLimit.parse("NO:motorway"))
        assertNull(SpeedLimit.parse(""))
        assertNull(SpeedLimit.parse("signals"))
    }

    /* ---------- Radiofrekvens fra radio-appen ---------- */

    @Test fun radio_fm_med_baand() {
        val s = HwRadio.parseText("FM1 90.10")
        assertEquals("FM1", s.band); assertEquals("90.10", s.freq)
    }

    @Test fun radio_stasjon_og_mhz() {
        val s = HwRadio.parseText("NRK P1 101.7 MHz")
        assertEquals("FM", s.band); assertEquals("101.7", s.freq)
        assertTrue(s.station!!.contains("NRK"))
    }

    @Test fun radio_am_og_komma() {
        assertEquals("AM", HwRadio.parseText("1040 kHz").band)
        assertEquals("99.9", HwRadio.parseText("Radio Norge 99,9").freq)
    }

    /* ---------- Datoer for EU-kontroll og service ---------- */

    private fun ymd(d: java.util.Date?) = d?.let { SimpleDateFormat("yyyy-MM-dd").format(it) }

    @Test fun datoer_tolkes() {
        assertEquals("2027-03-15", ymd(Reminders.parse("15.3.2027")))
        assertEquals("2027-03-15", ymd(Reminders.parse("15.3.27")))      // to-sifret år = 2000-tallet
        assertEquals("2027-03-15", ymd(Reminders.parse("15/3-2027")))
        assertEquals("2027-03-15", ymd(Reminders.parse(" 15.03.2027 ")))
        assertNull(Reminders.parse("31.2.2027"))                         // finnes ikke
        assertNull(Reminders.parse("i morgen"))
        assertNull(Reminders.parse(""))
    }

    /* ---------- Kompass og batteri ---------- */

    @Test fun kompass() {
        assertEquals("N", compassName(0f)); assertEquals("N", compassName(350f)); assertEquals("N", compassName(-10f))
        assertEquals("NØ", compassName(45f)); assertEquals("Ø", compassName(90f))
        assertEquals("S", compassName(180f)); assertEquals("V", compassName(270f)); assertEquals("NV", compassName(315f))
    }

    @Test fun batterispenning_forklares() {
        assertEquals("Lader (motoren går)", CarBattery.meaning(14.1f))
        assertEquals("Godt ladet", CarBattery.meaning(12.6f))
        assertEquals("Litt lavt", CarBattery.meaning(12.1f))
        assertTrue(CarBattery.meaning(11.5f).startsWith("Lavt"))
        assertTrue(CarBattery.meaning(15.4f).startsWith("For høy"))
    }

    @Test fun signatur_sammenlignes() {
        assertTrue(Updater.sameSigner(setOf("a"), setOf("a")))
        assertFalse(Updater.sameSigner(setOf("a"), setOf("b")))
        assertTrue(Updater.sameSigner(emptySet(), setOf("b")))   // ukjent → la Android avgjøre
    }
}
