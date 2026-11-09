package no.bil.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Førstegangsoppsettet: FYT-stereoen (ekte Android 10) får bare stegene som gjelder den. */
class SetupLogicTest {
    @Test fun fyt_allerede_startskjerm_og_telefon() {
        val s = setupSteps(fyt = true, sdk = 29, isHome = true, dialerAvailable = true, dialerDone = true, barKnown = true)
        assertEquals(listOf(SetupStep.KEYS, SetupStep.NOTES, SetupStep.USAGE, SetupStep.LOCATION, SetupStep.STEER, SetupStep.BAR,
            SetupStep.SELFTEST, SetupStep.UPDATES, SetupStep.RESTORE), s)
    }

    @Test fun fyt_mangler_startskjerm_kommer_forst() {
        val s = setupSteps(fyt = true, sdk = 29, isHome = false, dialerAvailable = true, dialerDone = false, barKnown = false)
        assertEquals(SetupStep.HOME, s[0]); assertEquals(SetupStep.DIALER, s[1])
        assertFalse(SetupStep.BAR in s)
        assertFalse(SetupStep.BT in s); assertFalse(SetupStep.OVERLAY in s); assertFalse(SetupStep.WRITE in s)
    }

    @Test fun vanlig_stereo_som_for() {
        val s = setupSteps(fyt = false, sdk = 31, isHome = true, dialerAvailable = false, dialerDone = false, barKnown = true)
        assertEquals(SetupStep.LOCATION, s[0])
        assertTrue(SetupStep.HOME in s && SetupStep.BT in s && SetupStep.OVERLAY in s)
        assertFalse(SetupStep.STEER in s || SetupStep.BAR in s || SetupStep.SELFTEST in s || SetupStep.DIALER in s)
    }
}
