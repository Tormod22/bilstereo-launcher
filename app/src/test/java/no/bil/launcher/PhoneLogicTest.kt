package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class PhoneLogicTest {
    @Test
    fun varighet_formateres() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("00:00", formatDuration(-5000))
        assertEquals("02:31", formatDuration(151_000))
        assertEquals("59:59", formatDuration(3_599_999))
        assertEquals("1:02:05", formatDuration(3_725_000))
    }

    @Test
    fun statustekst_paa_samtaleskjermen() {
        assertEquals("Ringer …", callStatusText(CallState.RINGING, 0, 0))
        assertEquals("Ringer ut …", callStatusText(CallState.DIALING, 0, 0))
        assertEquals("Samtale 02:31", callStatusText(CallState.ACTIVE, 1_000, 152_000))
        assertEquals("Samtale", callStatusText(CallState.ACTIVE, 0, 152_000))
        assertEquals("Venter", callStatusText(CallState.HOLDING, 0, 0))
        assertEquals("Avsluttet", callStatusText(CallState.ENDED, 0, 0))
        assertEquals("Opptatt", callStatusText(CallState.ENDED, 0, 0, "Opptatt"))
    }

    @Test
    fun telecom_tilstander_og_aarsaker() {
        assertEquals(CallState.RINGING, mapCallState(android.telecom.Call.STATE_RINGING))
        assertEquals(CallState.RINGING, mapCallState(13))   // SIMULATED_RINGING
        assertEquals(CallState.DIALING, mapCallState(android.telecom.Call.STATE_DIALING))
        assertEquals(CallState.DIALING, mapCallState(android.telecom.Call.STATE_CONNECTING))
        assertEquals(CallState.DIALING, mapCallState(android.telecom.Call.STATE_SELECT_PHONE_ACCOUNT))
        assertEquals(CallState.ACTIVE, mapCallState(android.telecom.Call.STATE_ACTIVE))
        assertEquals(CallState.HOLDING, mapCallState(android.telecom.Call.STATE_HOLDING))
        assertEquals(CallState.ENDED, mapCallState(android.telecom.Call.STATE_DISCONNECTED))
        assertEquals("Avvist", endedText(android.telecom.DisconnectCause.REJECTED))
        assertEquals("Opptatt", endedText(android.telecom.DisconnectCause.BUSY))
        assertEquals("Avsluttet", endedText(android.telecom.DisconnectCause.LOCAL))
        assertEquals("Avsluttet", endedText(android.telecom.DisconnectCause.REMOTE))
    }

    private fun call(id: Int, s: CallState) = PhoneCall(id, "1", null, s, false, 0, false)

    @Test
    fun innkommende_samtale_vises_foerst() {
        assertNull(primaryCall(emptyList()))
        assertEquals(2, primaryCall(listOf(call(1, CallState.ACTIVE), call(2, CallState.RINGING)))?.id)
        assertEquals(1, primaryCall(listOf(call(1, CallState.ACTIVE), call(2, CallState.HOLDING)))?.id)
        assertEquals(3, primaryCall(listOf(call(1, CallState.ENDED), call(3, CallState.DIALING)))?.id)
        // Uten navn vises nummeret, uten nummer «Ukjent nummer»
        assertEquals("1", call(1, CallState.ACTIVE).title)
        assertEquals("Ukjent nummer", PhoneCall(1, "", " ", CallState.ACTIVE, true, 0, false).title)
        assertEquals("Mor", PhoneCall(1, "900", "Mor", CallState.ACTIVE, true, 0, false).title)
    }

    @Test
    fun nummer_normaliseres() {
        assertEquals("+4790000000", normalizeNumber("+47 900 00 000"))
        assertEquals("+4790000000", normalizeNumber("0047 900-00-000"))
        assertEquals("+4790000000", normalizeNumber("90000000"))
        assertEquals("+4790000000", normalizeNumber("(900) 00 000"))
        assertEquals("113", normalizeNumber("113"))
        assertEquals("1881", normalizeNumber("1881"))
        assertEquals("", normalizeNumber(" - "))
        // Svensk nummer beholder landskoden
        assertEquals("+46701234567", normalizeNumber("0046 70 123 45 67"))
    }

    @Test
    fun samme_nummer_i_ulike_former() {
        assertTrue(numbersMatch("+47 900 00 000", "90000000"))
        assertTrue(numbersMatch("004790000000", "+4790000000"))
        assertTrue(numbersMatch("900 00 000", "900-00-000"))
        assertFalse(numbersMatch("+4690000000", "+4790000000"))
        assertFalse(numbersMatch("90000000", "90000001"))
        assertFalse(numbersMatch("113", "0113"))
        assertFalse(numbersMatch("", ""))
        assertTrue(sameNumber("22-22 22 22", "+47 22 22 22 22"))
    }

    @Test
    fun ringbart_nummer() {
        assertEquals("+4790000000", dialable(" +47 900 00 000 "))
        assertEquals("*21#", dialable("*21#"))
        assertEquals("90000000", dialable("tlf. 900 00 000"))
        assertEquals("", dialable("+"))
        assertEquals("", dialable("abc"))
    }

    @Test
    fun tastatur() {
        assertEquals("1", appendKey("", "1"))
        assertEquals("12", appendKey("1", "2"))
        assertEquals("1", appendKey("12", "⌫"))
        assertEquals("", appendKey("", "⌫"))
        assertEquals("+", appendKey("", "+"))
        assertEquals("4", appendKey("4", "+"))   // + bare først
        assertEquals(24, appendKey("1".repeat(24), "5").length)
    }

    private val book = listOf(
        Contact("Ola Nordmann", "+47 900 00 000"),
        Contact("Kari Nordmann", "22 22 22 22"),
        Contact("Øystein Ås", "41234567"),
        Contact("Tannlege Berg-Hansen", "51 00 00 00"),
    )

    @Test
    fun kontaktsoek_uten_t9() {
        assertEquals(book, filterContacts(book, "  "))
        assertEquals(listOf("Ola Nordmann", "Kari Nordmann"), filterContacts(book, "nord").map { it.name })
        assertEquals(listOf("Ola Nordmann"), filterContacts(book, "ola n").map { it.name })
        assertEquals(listOf("Øystein Ås"), filterContacts(book, "øy").map { it.name })
        assertEquals(listOf("Øystein Ås"), filterContacts(book, "ÅS").map { it.name })
        assertEquals(listOf("Tannlege Berg-Hansen"), filterContacts(book, "hansen").map { it.name })
        // Sifre søker i nummeret (uansett mellomrom og +47)
        assertEquals(listOf("Kari Nordmann"), filterContacts(book, "2222").map { it.name })
        assertEquals(listOf("Ola Nordmann"), filterContacts(book, "+4790000").map { it.name })
        assertTrue(filterContacts(book, "x").isEmpty())
        // Ett siffer er for lite til å søke i nummer
        assertTrue(filterContacts(book, "4").isEmpty())
    }

    @Test
    fun forbokstav() {
        assertEquals("O", letterOf("ola"))
        assertEquals("Ø", letterOf(" øystein"))
        assertEquals("#", letterOf("+47 900"))
        assertEquals("#", letterOf(""))
    }

    @Test
    fun tidspunkt_i_anropslisten() {
        val utc = TimeZone.getTimeZone("UTC")
        val now = 1_762_174_800_000L          // 3. nov 2025 13:00 UTC
        assertEquals("i dag 12:58", whenText(now - 120_000, now, utc))
        assertEquals("i går 13:00", whenText(now - 86_400_000, now, utc))
        assertEquals("1. nov", whenText(now - 2 * 86_400_000L, now, utc))
        assertEquals("3. nov 2024", whenText(now - 365 * 86_400_000L, now, utc))
    }

    @Test
    fun telefonkonto_og_lydvei() {
        assertEquals(-1, preferredAccount(emptyList()))
        assertEquals(0, preferredAccount(listOf("com.android.phone")))
        assertEquals(1, preferredAccount(listOf("com.android.phone", "com.android.bluetooth")))
        // Lydvalg: bare meningsfullt med mer enn én vei
        assertEquals(listOf(8), routesIn(8))
        assertEquals(listOf(8, 2), routesIn(2 or 8))
        assertEquals(2, nextRoute(8, 2 or 8))
        assertEquals(8, nextRoute(2, 2 or 8))
        assertEquals(8, nextRoute(0, 8))
        assertEquals("Høyttaler", routeLabel(8))
        assertEquals("Bluetooth", routeLabel(2))
    }

    @Test
    fun anropstype() {
        assertEquals("Tapt", recentLabel(android.provider.CallLog.Calls.MISSED_TYPE))
        assertEquals("Ut", recentLabel(android.provider.CallLog.Calls.OUTGOING_TYPE))
        assertEquals("", recentLabel(99))
    }
}
