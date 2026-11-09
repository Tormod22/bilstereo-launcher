package no.bil.launcher

import android.provider.CallLog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** BT-telefonen på FYT-stereoen (FytPhone.kt): samtaletilstander, anropsliste, telefonbok, parede enheter, tonevalg. */
class FytPhoneTest {
    private val L = FytPhoneLogic

    @Test fun tilstander_fra_finalbt() {
        assertEquals(CallState.DIALING, L.callStateOf(3))
        assertEquals(CallState.RINGING, L.callStateOf(4))
        assertEquals(CallState.ACTIVE, L.callStateOf(5))
        for (s in listOf(0, 1, 2, 6, -1, 99)) assertNull(L.callStateOf(s))
    }

    @Test fun innkommende_besvart_gir_innkommende_med_varighet() {
        var s = L.step(null, 4, "90000000", 1_000)
        assertEquals(CallState.RINGING, s.track!!.state); assertTrue(s.track!!.incoming); assertEquals(0L, s.track!!.connectedAt)
        s = L.step(s.track, 5, null, 5_000)
        assertEquals(CallState.ACTIVE, s.track!!.state); assertEquals(5_000L, s.track!!.connectedAt)
        assertEquals("nummeret beholdes når FYT ikke sender det igjen", "90000000", s.track!!.number)
        s = L.step(s.track, 5, null, 20_000)
        assertEquals("tidspunktet samtalen startet endres ikke", 5_000L, s.track!!.connectedAt)
        s = L.step(s.track, 2, null, 65_000)
        assertNull(s.track)
        val r = s.finished!!
        assertEquals(CallLog.Calls.INCOMING_TYPE, r.type); assertEquals(60L, r.durationSec); assertEquals(1_000L, r.date)
        assertEquals("Avsluttet", L.endedText(r.type))
    }

    @Test fun tapt_avvist_og_utgaende() {
        var s = L.step(null, 4, "113", 0)
        s = L.step(s.track, 2, null, 9_000)
        assertEquals(CallLog.Calls.MISSED_TYPE, s.finished!!.type); assertEquals("Tapt anrop", L.endedText(s.finished!!.type))
        s = L.step(null, 4, "113", 0)
        s = L.step(s.track!!.copy(rejected = true), 2, null, 1_000)
        assertEquals(CallLog.Calls.REJECTED_TYPE, s.finished!!.type); assertEquals("Avvist", L.endedText(s.finished!!.type))
        s = L.step(null, 3, "", 0)
        assertFalse(s.track!!.incoming); assertEquals("", s.track!!.number)
        s = L.step(s.track, 3, "+4790000000", 500)
        assertEquals("nummeret kommer etterpå", "+4790000000", s.track!!.number)
        s = L.step(s.track, 0, null, 2_000)
        assertEquals(CallLog.Calls.OUTGOING_TYPE, s.finished!!.type); assertEquals(0L, s.finished!!.durationSec)
        // Ingen samtale før og etter: ingenting skjer
        val none = L.step(null, 2, null, 0)
        assertNull(none.track); assertNull(none.finished)
        // T'OS startet midt i en samtale: regnes som pågående fra nå
        val mid = L.step(null, 5, "123", 7_000)
        assertEquals(7_000L, mid.track!!.connectedAt)
    }

    @Test fun tonevalg_taster() {
        assertEquals(2, L.dtmfKey('0')); assertEquals(11, L.dtmfKey('9')); assertEquals(6, L.dtmfKey('4'))
        assertEquals(13, L.dtmfKey('*')); assertEquals(14, L.dtmfKey('#'))
        assertNull(L.dtmfKey('a'))
    }

    @Test fun telefonbok_tolkes_forsiktig() {
        assertEquals(listOf(Contact("Ola Nordmann", "+47 900 00 000")), L.parseBook(arrayOf("Ola Nordmann", "+47 900 00 000")))
        assertEquals(listOf(Contact("Kari", "90000001")), L.parseBook(arrayOf("90000001", "Kari")))
        // Flere nummer per navn, og mange par i én melding
        assertEquals(listOf(Contact("Ola", "111"), Contact("Ola", "222"), Contact("Kari", "333")),
            L.parseBook(arrayOf("Ola", "111", "222", "Kari", "333")))
        // Nummer først i hele meldingen: hvert nummer får navnet etter seg (før fikk 222 navnet «Ola»)
        assertEquals(listOf(Contact("Ola", "111"), Contact("Kari", "222")), L.parseBook(arrayOf("111", "Ola", "222", "Kari")))
        assertEquals(listOf(Contact("111", "111"), Contact("Kari", "222")), L.parseBook(arrayOf("111", "222", "Kari")))
        // Skilletegn i én tekst
        assertEquals(listOf(Contact("Per", "444 55 666")), L.parseBook(arrayOf("Per\t444 55 666")))
        // Bare nummer: nummeret blir navnet; tomt og navn uten nummer gir ingenting
        assertEquals(listOf(Contact("1881", "1881")), L.parseBook(arrayOf("1881")))
        assertTrue(L.parseBook(arrayOf("Bare navn")).isEmpty())
        assertTrue(L.parseBook(null).isEmpty())
        assertTrue(L.parseBook(arrayOf(null, "")).isEmpty())
        // Ikke nummer: for få sifre / bokstaver
        assertFalse(L.looksLikeNumber("12")); assertFalse(L.looksLikeNumber("Ola 2")); assertTrue(L.looksLikeNumber("(+47) 900-00.000"))
    }

    @Test fun telefonbok_flettes_uten_dobbelt() {
        val a = listOf(Contact("Ola", "90000000"))
        val m = L.mergeBook(a, listOf(Contact("ola", "+4790000000"), Contact("Anne", "111")))
        assertEquals(listOf("Anne", "Ola"), m.map { it.name })
        assertEquals(a, L.mergeBook(a, emptyList()))
        assertEquals(2, L.mergeBook(emptyList(), List(5) { Contact("N$it", "11$it") }, max = 2).size)
        // Lagring og lesing gir det samme tilbake (| og linjeskift i navn fjernes)
        val book = listOf(Contact("Ola | N", "900"), Contact("Kari", "+47 1"))
        assertEquals(listOf(Contact("Ola N", "900"), Contact("Kari", "+47 1")), L.decodeBook(L.encodeBook(book)))
    }

    @Test fun anropslista_lagres() {
        val r1 = RecentCall("Ola", "900", CallLog.Calls.MISSED_TYPE, 1_000, 0)
        val r2 = RecentCall(null, "113", CallLog.Calls.OUTGOING_TYPE, 2_000, 75)
        val log = L.addToLog(L.addToLog(emptyList(), r1), r2)
        assertEquals(listOf(r2, r1), log)
        assertEquals(log, L.decodeLog(L.encodeLog(log)))
        assertTrue(L.decodeLog("").isEmpty()); assertTrue(L.decodeLog("rot|x").isEmpty())
        assertEquals(60, (1..80).fold(emptyList<RecentCall>()) { acc, i -> L.addToLog(acc, r1.copy(date = i.toLong())) }.size)
    }

    @Test fun parede_enheter() {
        assertTrue(L.looksLikeMac("F8D3F0D5EE6F")); assertTrue(L.looksLikeMac("f8:d3:f0:d5:ee:6f")); assertFalse(L.looksLikeMac("iPhone"))
        assertEquals("F8:D3:F0:D5:EE:6F", L.macText("F8D3F0D5EE6F"))
        assertEquals(listOf(FytDevice("iPhone", "F8D3F0D5EE6F")), L.parseDevices(arrayOf("iPhone", "F8D3F0D5EE6F")))
        assertEquals(listOf(FytDevice("iPhone", "F8D3F0D5EE6F")), L.parseDevices(arrayOf("F8D3F0D5EE6F", "iPhone")))
        assertEquals(listOf(FytDevice("A", "112233445566"), FytDevice("B", "AABBCCDDEEFF")),
            L.parseDevices(arrayOf("A", "112233445566", "B", "AABBCCDDEEFF")))
        assertEquals(listOf(FytDevice("11:22:33:44:55:66", "112233445566")), L.parseDevices(arrayOf("112233445566")))
        assertTrue(L.parseDevices(arrayOf("bare tekst")).isEmpty())
        val m = L.mergeDevices(listOf(FytDevice("Gammel", "112233445566")), listOf(FytDevice("Ny", "11:22:33:44:55:66")))
        assertEquals(listOf(FytDevice("Ny", "11:22:33:44:55:66")), m)
    }

    @Test fun bt_musikk_og_koder() {
        assertEquals(0.5f, L.progress(30_000, 60_000), 1e-4f); assertEquals(0f, L.progress(5, 0), 1e-4f); assertEquals(1f, L.progress(90, 60), 1e-4f)
        assertEquals("telefontilstand", L.codeName(9)); assertEquals("telefonbok", L.codeName(22)); assertEquals("", L.codeName(99))
        assertTrue(L.isTicker(28)); assertFalse(L.isTicker(9))
        assertTrue(FytLogic.describe(2, 9, intArrayOf(2), null, null).endsWith("(telefontilstand)"))
        assertEquals((0..33).toList(), L.SUBSCRIBE)
    }
}

/** T'OS sin samtaleskjerm for en FYT-samtale (stereoens BT-brikke): alle tilstander, uten Android-samtale. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w1024dp-h600dp-land-mdpi")
class FytCallScreenSmokeTest {
    @get:Rule val rule = createComposeRule()

    @After fun reset() { rule.runOnUiThread { FytPhone.setCallForTest(null); Phone.calls = emptyList() } }

    @Test fun fytSamtaleIAlleTilstander() {
        rule.mainClock.autoAdvance = false
        var hidden = false
        rule.setContent { CallScreen(onHide = { hidden = true }) }
        for (st in CallState.entries) {
            rule.runOnUiThread {
                FytPhone.setCallForTest(PhoneCall(FytPhone.CALL_ID, "+4790000000", if (st == CallState.DIALING) null else "Ola", st,
                    incoming = st == CallState.RINGING, connectedAt = if (st == CallState.ACTIVE) System.currentTimeMillis() - 3_000 else 0,
                    canHold = false, ended = if (st == CallState.ENDED) "Tapt anrop" else null))
                check(Phone.calls.single().id == FytPhone.CALL_ID) { "FYT-samtalen skal vises i Phone.calls" }
            }
            rule.mainClock.advanceTimeBy(1_000); rule.waitForIdle()
        }
        // Telecom-samtale samtidig: den vises, ikke FYT-samtalen (unngår to like samtaler)
        rule.runOnUiThread {
            Phone.calls = listOf(PhoneCall(1, "+4790000000", "Ola", CallState.ACTIVE, false, System.currentTimeMillis(), true))
            check(Phone.calls.single().id == 1)
            Phone.calls = emptyList()
            FytPhone.setCallForTest(null)
        }
        rule.mainClock.advanceTimeBy(2_000); rule.waitForIdle()
        rule.awaitUntil("at samtaleskjermen lukker seg når FYT-samtalen er borte") { hidden }
    }
}
