package no.bil.launcher

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPingTest {
    @Test fun ukjente_koder_vises_men_ikke_systemknapper() {
        assertTrue(KeyPing.reportUnknown(KeyEvent.KEYCODE_F1))
        assertTrue(KeyPing.reportUnknown(1234))
        assertFalse(KeyPing.reportUnknown(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(KeyPing.reportUnknown(KeyEvent.KEYCODE_BACK))
        assertFalse(KeyPing.reportUnknown(0))
        assertFalse(KeyPing.reportUnknown(-1))
    }
}
