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
 * so it is built once at app start (warm-up) and reused by every player. Only a change of night mode / subtitle size
 * (options VLC reads at start) builds a new one; the old one stays alive, a player may still be releasing on it.
 */
object VlcFactory {
    private val built = HashMap<String, MediaPlayerFactory>()

    /** On exit: lets VLC stop its audio/video threads cleanly (an unreleased engine can hold the process). */
    @Synchronized fun releaseAll() { built.values.forEach { runCatching { it.release() } }; built.clear() }

    @Synchronized fun get(night: Boolean, subScale: Int): MediaPlayerFactory =
        built.getOrPut("$night|$subScale") { build(night, subScale) }

    /** App start: build the engine with the current player settings in the background. */
    fun warm() {
        val ui = Store("player_ui")
        get(ui.get("night") == "1", ui.get("subScale")?.toIntOrNull()?.coerceIn(50, 300) ?: 100)
    }

    private fun build(night: Boolean, subScale: Int): MediaPlayerFactory {
        val base = listOf(
            "--avcodec-hw=any", "--no-video-title-show",
            // DirectSound: pauses and resumes instantly without WASAPI endpoint buffer draining or sleep latency.
            "--aout=directsound",
            // Disable scaletempo time-stretching filter to prevent audio buffer desync and 1s cutout on resume.
            "--no-audio-time-stretch",
            "--no-spdif"
        )
        val extra = ArrayList<String>()
        // Night mode: a dynamic range compressor — quiet dialogue up, explosions down.
        if (night) extra += listOf(
            "--audio-filter=compressor", "--compressor-rms-peak=0.2", "--compressor-attack=25", "--compressor-release=100",
            "--compressor-threshold=-24", "--compressor-ratio=6", "--compressor-knee=4.5", "--compressor-makeup-gain=10"
        )
        if (subScale != 100) extra += "--sub-text-scale=$subScale"
        // An option this VLC does not know would stop it from starting: then start without the extras.
        return runCatching { MediaPlayerFactory(*(base + extra).toTypedArray()) }.getOrElse { MediaPlayerFactory(*base.toTypedArray()) }
    }
}
