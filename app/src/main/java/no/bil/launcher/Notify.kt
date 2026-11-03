package no.bil.launcher

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * T'OS sine egne korte meldinger (erstatter Androids grå «Toast»-bobler, som er en del av originalt UI).
 * Vises av Root i MainActivity når T'OS er synlig, ellers av KeyService som et lite overlegg.
 * Kan kalles fra hvilken som helst tråd.
 */
object Notify {
    data class Msg(val text: String, val long: Boolean, val id: Long)

    var current by mutableStateOf<Msg?>(null)
        private set

    /** Settes av MainActivity (onResume/onPause): er T'OS sin egen skjerm synlig nå? */
    @Volatile var appVisible = false

    private val main = Handler(Looper.getMainLooper())

    fun show(text: String, long: Boolean = false) {
        val m = Msg(text, long, SystemClock.elapsedRealtimeNanos())
        main.post {
            current = m
            main.postDelayed({ if (current?.id == m.id) current = null }, if (long) 5_000 else 2_800)
        }
    }

    fun dismiss() = main.post { current = null }
}
