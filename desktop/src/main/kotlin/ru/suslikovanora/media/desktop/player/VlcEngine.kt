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

/**
 * VLC (vlcj) behind [PlayerEngine], like [MpvEngine]: [PlayerView] drives both the same way. What only VLC has (its
 * detailed counters for the journal, track names from the media info, Blu-ray seeks by position) stays reachable
 * through [mp]. Events go to [listener] from VLC's own threads, as with mpv.
 */
class VlcPlayer(val mp: uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer, private val listener: EngineListener) : PlayerEngine {
    init {
        mp.events().addMediaPlayerEventListener(object : uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter() {
            override fun playing(p: uk.co.caprica.vlcj.player.base.MediaPlayer) = listener.playing()
            override fun elementaryStreamAdded(p: uk.co.caprica.vlcj.player.base.MediaPlayer, type: uk.co.caprica.vlcj.media.TrackType, id: Int) = listener.tracksChanged()
            override fun paused(p: uk.co.caprica.vlcj.player.base.MediaPlayer) = listener.paused()
            override fun stopped(p: uk.co.caprica.vlcj.player.base.MediaPlayer) = listener.stopped()
            override fun buffering(p: uk.co.caprica.vlcj.player.base.MediaPlayer, newCache: Float) = listener.buffering(newCache)
            override fun lengthChanged(p: uk.co.caprica.vlcj.player.base.MediaPlayer, newLength: Long) = listener.lengthChanged(newLength)
            // The position is read here, on VLC's callback thread: asking for it later from the window's thread could
            // deadlock with VLC (2.1.3).
            override fun timeChanged(p: uk.co.caprica.vlcj.player.base.MediaPlayer, newTime: Long) =
                listener.timeChanged(newTime, runCatching { p.status().position() }.getOrDefault(0f))
            override fun finished(p: uk.co.caprica.vlcj.player.base.MediaPlayer) = listener.finished()
            override fun error(p: uk.co.caprica.vlcj.player.base.MediaPlayer) = listener.failed("VLC error")
        })
    }

    /**
     * [startMs] = 0 for a Blu-ray / transport stream: VLC ignores :start-time there, the player jumps by position
     * once it plays (TsPlayback).
     */
    override fun open(url: String, startMs: Long, nightMode: Boolean, audioLang: String?, subLang: String?,
                      audioUrls: List<String>, subUrls: List<String>) {
        val opts = ArrayList(listOf(":http-reconnect", ":network-caching=${ru.suslikovanora.media.desktop.PlaybackSettings.cacheMs()}"))
        if (nightMode) opts += listOf(
            ":audio-filter=compressor",
            ":compressor-rms-peak=0.2",
            ":compressor-attack=25",
            ":compressor-release=100",
            ":compressor-threshold=-24",
            ":compressor-ratio=6",
            ":compressor-knee=4.5",
            ":compressor-makeup-gain=10"
        )
        if (startMs > 0) opts += ":start-time=${startMs / 1000.0}"
        audioLang?.let { opts += ":audio-language=$it" }
        subLang?.let { opts += ":sub-language=$it" }
        mp.media().prepare(url, *opts.toTypedArray())
        audioUrls.forEach { u -> runCatching { mp.media().slaves().add(uk.co.caprica.vlcj.media.MediaSlaveType.AUDIO, uk.co.caprica.vlcj.media.MediaSlavePriority.HIGHEST, u) } }
        subUrls.forEach { u -> runCatching { mp.media().slaves().add(uk.co.caprica.vlcj.media.MediaSlaveType.SUBTITLE, uk.co.caprica.vlcj.media.MediaSlavePriority.HIGHEST, u) } }
        mp.controls().play()
    }

    override fun setPaused(paused: Boolean) { mp.controls().setPause(paused) }
    override fun stop() { mp.controls().stop() }
    override fun seekTo(ms: Long) { mp.controls().setTime(ms) }
    override fun seekToFraction(pos: Float) { mp.controls().setPosition(pos) }
    override fun setRate(rate: Float) { mp.controls().setRate(rate) }
    override fun setVolume(percent: Int) { mp.audio().setVolume(percent) }
    override fun setMute(mute: Boolean) { mp.audio().setMute(mute) }
    override fun setAudioDelayMs(ms: Long) { mp.audio().setDelay(ms * 1000L) }
    override fun setSubDelayMs(ms: Long) { mp.subpictures().setDelay(ms * 1000L) }
    /** A new video decoder and output (the picture froze); the player then seeks to the current place itself. */
    override fun restartVideoOutput() {
        val id = mp.video().track()
        if (id >= 0) { mp.video().setTrack(-1); mp.video().setTrack(id) }
    }

    override val timeMs: Long get() = mp.status().time()
    override val lengthMs: Long get() = mp.status().length()
    override val fraction: Float get() = mp.status().position()
    override val isPlayable: Boolean get() = mp.status().isPlayable
    override val isPlaying: Boolean get() = mp.status().isPlaying
    override val videoSize: Pair<Int, Int>? get() = mp.video().videoDimension()?.let { it.width to it.height }
    override val fps: Double get() = (call(mp.video(), "fps") as? Number)?.toDouble() ?: 0.0

    override fun audioTracks(): List<EngineTrack> {
        val cur = mp.audio().track()
        return mp.audio().trackDescriptions().filter { it.id() != -1 }.map { EngineTrack(it.id(), it.description(), it.id() == cur) }
    }
    override fun subtitleTracks(): List<EngineTrack> {
        val cur = mp.subpictures().track()
        return mp.subpictures().trackDescriptions().filter { it.id() != -1 }.map { EngineTrack(it.id(), it.description(), it.id() == cur) }
    }
    override fun setAudioTrack(id: Int) { mp.audio().setTrack(id) }
    override fun setSubtitleTrack(id: Int) { mp.subpictures().setTrack(id) }
    override fun chapters(): List<EngineChapter> = readChapters(mp).map { EngineChapter(it.first, it.second) }

    override fun snapshot(maxWidth: Int): java.awt.image.BufferedImage? =
        if (mp.status().isPlayable && mp.video().videoDimension() != null) mp.snapshots().get(maxWidth, 0) else null

    override fun statsLine(): String = describePlayback(mp)

    override fun supervisorStats(userMutedOrZero: Boolean): ru.suslikovanora.media.core.PlaybackSupervisor.Stats? {
        val stats = soundStats(mp) ?: return null
        val audioTrack = runCatching { mp.audio().track() }.getOrDefault(-1)
        val audioTracks = runCatching { mp.audio().trackDescriptions().count { it.id() != -1 } }.getOrDefault(0)
        val videoTracks = runCatching { mp.video().trackCount() }.getOrDefault(0)
        val clock = runCatching { mp.status().time() }.getOrDefault(0L)
        return ru.suslikovanora.media.core.PlaybackSupervisor.Stats(clock, stats.second, stats.first, stats.third, fps, audioTracks,
            audioTrack != -1, videoTracks, userMutedOrZero)
    }

    override fun release() { mp.release() }
}
