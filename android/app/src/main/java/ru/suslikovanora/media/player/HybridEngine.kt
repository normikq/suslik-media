package ru.suslikovanora.media.player

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/**
 * Два движка за одним [PlayerEngine]: перед каждым открытием плеер выбирает [use] (см. [EngineChoice]), а всё
 * остальное идёт в выбранный движок. Видео обоих лежит в одном контейнере [videoView] (его и знают разметка, PiP,
 * жесты); видно только видео выбранного. Движок, который не нужен, закрывается при переключении.
 */
@OptIn(UnstableApi::class)
class HybridEngine(private val context: Context) : PlayerEngine {
    private val container = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
    override val videoView: View get() = container
    override var listener: EngineListener? = null

    private var vlc: VlcEngine? = null
    private var media3: Media3Engine? = null
    private var active: PlayerEngine = engine(EngineKind.VLC)

    override val kind: EngineKind get() = active.kind
    private var opened = 0
    override val generation: Int get() = opened
    override val hasPlayer: Boolean get() = active.hasPlayer

    /** Events only from the engine in use (a closed one can still have a few queued). */
    private fun forwardFrom(engine: PlayerEngine) = object : EngineListener {
        private fun l() = if (engine === active) listener else null
        override fun playing() { l()?.playing() }
        override fun paused() { l()?.paused() }
        override fun stopped() { l()?.stopped() }
        override fun tracksChanged() { l()?.tracksChanged() }
        override fun endReached() { l()?.endReached() }
        override fun failed() { l()?.failed() }
        override fun videoOutput(count: Int) { l()?.videoOutput(count) }
        override fun buffering(percent: Float) { l()?.buffering(percent) }
        override fun timeChanged() { l()?.timeChanged() }
        override fun lengthChanged() { l()?.lengthChanged() }
        override fun unsupported(reason: String) { l()?.unsupported(reason) }
    }

    private fun engine(kind: EngineKind): PlayerEngine = when (kind) {
        EngineKind.VLC -> vlc ?: VlcEngine(context).also { vlc = it; add(it) }
        EngineKind.MEDIA3 -> media3 ?: Media3Engine(context).also { media3 = it; add(it) }
    }

    private fun add(e: PlayerEngine) {
        e.listener = forwardFrom(e)
        e.videoView.visibility = View.GONE
        container.addView(e.videoView, FrameLayout.LayoutParams(-1, -1))
    }

    /** The engine for the next [open]. Switching closes the other one (in the background, as its [release] does). */
    fun use(kind: EngineKind) {
        val next = engine(kind)
        if (next !== active && active.hasPlayer) active.release()
        active = next
        for (e in listOfNotNull(vlc, media3)) e.videoView.visibility = if (e === active) View.VISIBLE else View.GONE
    }

    override fun open(media: EngineMedia, scale: VideoScale) {
        active.videoView.visibility = View.VISIBLE
        active.open(media, scale)
        opened++
    }

    override fun release() {
        vlc?.release()
        media3?.release()
    }

    override fun play() = active.play()
    override fun pause() = active.pause()
    override val isPlaying: Boolean get() = active.isPlaying
    override val isSeekable: Boolean get() = active.isSeekable
    override val timeMs: Long get() = active.timeMs
    override val lengthMs: Long get() = active.lengthMs
    override val fraction: Float get() = active.fraction
    override val rate: Float get() = active.rate
    override fun setRate(rate: Float) = active.setRate(rate)
    override val volume: Int get() = active.volume
    override fun seekTo(ms: Long) = active.seekTo(ms)
    override fun seekToFraction(fraction: Float) = active.seekToFraction(fraction)

    override fun audioTracks(): List<EngineTrack> = active.audioTracks()
    override val audioTrack: Int get() = active.audioTrack
    override fun setAudioTrack(id: Int) = active.setAudioTrack(id)
    override fun subtitleTracks(): List<EngineTrack> = active.subtitleTracks()
    override val subtitleTrack: Int get() = active.subtitleTrack
    override fun setSubtitleTrack(id: Int) = active.setSubtitleTrack(id)
    override fun addAudioFile(url: String) = active.addAudioFile(url)
    override fun audioFileOf(id: Int, files: Int): Int? = active.audioFileOf(id, files)
    override fun audioFileTrack(index: Int, files: Int): Int? = active.audioFileTrack(index, files)
    override fun setAudioDelayMs(ms: Long) = active.setAudioDelayMs(ms)

    override val videoTrackCount: Int get() = active.videoTrackCount
    override val videoTrack: EngineVideoTrack? get() = active.videoTrack
    override fun setVideoEnabled(enabled: Boolean) = active.setVideoEnabled(enabled)
    override fun restartVideo(seekMs: Long?) = active.restartVideo(seekMs)
    override fun attachVideo() = active.attachVideo()
    override fun detachVideo() = active.detachVideo()
    override fun refreshVideoSize() = active.refreshVideoSize()
    override fun setScale(scale: VideoScale) = active.setScale(scale)
    override fun stats(): EngineStats? = active.stats()

    override val bufferedPositionMs: Long get() = active.bufferedPositionMs
    override val bitrateMbit: Double? get() = active.bitrateMbit
    override val handlesSurfaceItself: Boolean get() = active.handlesSurfaceItself
    override fun diagLine(): String? = active.diagLine()
}
