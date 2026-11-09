package no.bil.launcher

import android.os.Handler
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Ventehjelpen i TestWait.kt venter virkelig på det som gjorde røyktestene ustabile: IO-arbeid i ekte tid og Handler-forsinkelser. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class TestWaitTest {
    @get:Rule val rule = createComposeRule()

    @Test fun venterPaaArbeidPaaIoTraaden() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            // Som bruksanvisningen/applista: lastes på Dispatchers.IO og tar ekte tid (her med vilje 400 ms)
            val text by produceState<String?>(null) { value = withContext(Dispatchers.IO) { Thread.sleep(400); "Ferdig lastet" } }
            Text(text ?: "Laster …")
        }
        rule.settle()
        rule.awaitText("Ferdig lastet")
    }

    @Test fun venterPaaHandlerForsinkelse() {
        rule.mainClock.autoAdvance = false
        var done = false
        rule.setContent { Text("Hei") }
        // Som Hub.scope/Notify: forsinkelse på hovedtråden (Robolectric-klokka, ikke Compose-klokka)
        rule.runOnUiThread { Handler(Looper.getMainLooper()).postDelayed({ done = true }, 5_000) }
        rule.awaitUntil("Handler.postDelayed etter 5 s") { done }
    }
}
