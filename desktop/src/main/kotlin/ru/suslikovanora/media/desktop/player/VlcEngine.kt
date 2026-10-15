package ru.suslikovanora.media.desktop.player

import ru.suslikovanora.media.desktop.Store
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery

object Vlc {
    /** VLC 3.x (64-bit) must be installed; NativeDiscovery finds it in Program Files. */
    val available: Boolean by lazy { runCatching { NativeDiscovery().discover() }.getOrDefault(false) }
}

/**
 * One VLC engine for the whole app run. Creating it scans every VLC plugin (hundreds of ms, seconds on a cold start),
 * so it is built once at app start (warm-up) and reused by every player. Only a change of subtitle size
 * (option VLC reads at start) builds a new one; the old one stays alive, a player may still be releasing on it.
 */
object VlcFactory {
    private val built = HashMap<String, MediaPlayerFactory>()

    /** On exit: lets VLC stop its audio/video threads cleanly (an unreleased engine can hold the process). */
    @Synchronized fun releaseAll() { built.values.forEach { runCatching { it.release() } }; built.clear() }

    @Synchronized fun get(subScale: Int = 100): MediaPlayerFactory =
        built.getOrPut("$subScale") { build(subScale) }

    /** App start: build the engine with the current player settings in the background. */
    fun warm() {
        val ui = Store("player_ui")
        get(ui.get("subScale")?.toIntOrNull()?.coerceIn(50, 300) ?: 100)
    }

    private fun build(subScale: Int): MediaPlayerFactory {
        val base = listOf(
            "--avcodec-hw=any", "--no-video-title-show",
            // DirectSound: pauses and resumes instantly without WASAPI endpoint buffer draining or sleep latency.
            "--aout=directsound",
            // Disable scaletempo time-stretching filter to prevent audio buffer desync and 1s cutout on resume.
            "--no-audio-time-stretch",
            "--no-spdif"
        )
        val extra = ArrayList<String>()
        if (subScale != 100) extra += "--sub-text-scale=$subScale"
        // An option this VLC does not know would stop it from starting: then start without the extras.
        return runCatching { MediaPlayerFactory(*(base + extra).toTypedArray()) }.getOrElse { MediaPlayerFactory(*base.toTypedArray()) }
    }
}
