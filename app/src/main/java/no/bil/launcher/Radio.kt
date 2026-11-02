package no.bil.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Nettradio i T'OS selv (trenger internett, f.eks. fra iPhonen). Kanaler sjekket 2. nov. 2026. */
object NetRadio {
    data class Station(val name: String, val url: String)

    val stations = listOf(
        Station("NRK P1", "https://lyd.nrk.no/nrk_radio_p1_ostlandssendingen_mp3_h"),
        Station("NRK P2", "https://lyd.nrk.no/nrk_radio_p2_mp3_h"),
        Station("NRK P3", "https://lyd.nrk.no/nrk_radio_p3_mp3_h"),
        Station("NRK mP3", "https://lyd.nrk.no/nrk_radio_mp3_mp3_h"),
        Station("Alltid Nyheter", "https://lyd.nrk.no/nrk_radio_alltid_nyheter_mp3_h"),
        Station("P4", "https://p4.p4groupaudio.com/P04_MM"),
        Station("Radio Norge", "https://live-bauerno.sharp-stream.com/radionorge_no_mp3"),
        Station("Radio Rock", "https://live-bauerno.sharp-stream.com/radiorock_no_mp3"),
    )

    var current by mutableStateOf<Station?>(null)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var player: MediaPlayer? = null

    /** Radioen er «valgt» (spiller eller pauset) – da styrer musikk-knappene radioen. */
    val active get() = current != null

    fun play(c: Context, s: Station) {
        stop()
        current = s; buffering = true; error = null
        Prefs.set("radio_last", s.name)
        player = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setOnPreparedListener { it.start(); buffering = false; playing = true }
            setOnErrorListener { _, _, _ -> error = "Fikk ikke spilt ${s.name}. Er det internett?"; buffering = false; playing = false; true }
            runCatching { setDataSource(c, android.net.Uri.parse(s.url)); prepareAsync() }
                .onFailure { error = "Fikk ikke spilt ${s.name}"; buffering = false }
        }
    }

    fun toggle(c: Context) {
        val p = player
        when {
            p == null -> current?.let { play(c, it) }
            playing -> { p.pause(); playing = false }
            else -> { p.start(); playing = true }
        }
    }

    fun step(c: Context, d: Int) {
        val i = stations.indexOf(current).coerceAtLeast(0)
        play(c, stations[(i + d + stations.size) % stations.size])
    }

    fun stop() {
        runCatching { player?.release() }
        player = null; playing = false; buffering = false; current = null
    }
}
