package ru.suslikovanora.media.player

import android.content.Context
import android.net.Uri
import android.view.View
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import ru.suslikovanora.media.Diag
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * [PlayerEngine] на libVLC 3.6. Один `LibVLC` живёт от первого [open] до [release]; каждое переподключение —
 * новый `MediaPlayer` на нём (старый на мёртвом сокете может висеть в `stop()`/`setMedia()`), а старые закрываются
 * в фоне на одном потоке: сначала плееры, `LibVLC` последним.
 */
class VlcEngine(private val context: Context) : PlayerEngine {
    private val layout = VLCVideoLayout(context)
    override val videoView: View get() = layout
    override var listener: EngineListener? = null
    override val kind = EngineKind.VLC

    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var media: Media? = null
    private var teardownExec: ExecutorService? = null // serial native teardown: players first, LibVLC last
    private var replaced: Pair<MediaPlayer, Media?>? = null

    override val hasPlayer: Boolean get() = player != null
    private var opened = 0
    override val generation: Int get() = opened

    private fun createLibVlc(): LibVLC {
        // HW decoding first, VLC falls back to software by itself. Caching is set once, on LibVLC.
        val vlc = LibVLC(
            context,
            arrayListOf(
                "--avcodec-hw=any",
                // 6 s of buffer rides out short mobile-network dips (4 s was sometimes too little for 4K).
                "--network-caching=6000",
                "--no-video-title-show",
                // stats stay on: the watchdog uses "displayed pictures" to notice a frozen picture.
                "--no-sub-autodetect-file" // do not probe the HTTP server for sidecar subtitle files
            ).apply { if (Diag.vlcVerbose(context)) add("-vv") } // debug build: VLC's own log → logcat.log
        )
        libVlc = vlc
        teardownExec = Executors.newSingleThreadExecutor { r -> Thread(r, "vlc-teardown").apply { isDaemon = true } }
        return vlc
    }

    private var subsWatched = false

    /** VLC sizes its subtitles surface like the picture — cut off with it in «Заполнить» / «16:9»: see [fitSubtitles]. */
    private fun watchSubtitles() {
        if (subsWatched) return
        val video = layout.findViewById<View>(org.videolan.R.id.surface_video) ?: return
        val subs = layout.findViewById<View>(org.videolan.R.id.surface_subtitles) ?: return
        subsWatched = true
        val fit = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            layout.post { if (video.width > 0 && video.height > 0) fitSubtitles(subs, video.width.toFloat() / video.height) }
        }
        video.addOnLayoutChangeListener(fit)
        subs.addOnLayoutChangeListener(fit)
    }

    override fun open(media: EngineMedia, scale: VideoScale) {
        val vlc = libVlc ?: createLibVlc()
        retireReplaced()
        val old = player
        if (old != null) {
            old.setEventListener(null)
            runCatching { old.detachViews() }
        }
        val p = MediaPlayer(vlc)
        p.setEventListener(listenerFor(p))
        // enableSubtitles = true so selected subtitle tracks are rendered; SurfaceView is cheaper than TextureView.
        p.attachViews(layout, null, true, false)
        watchSubtitles()
        runCatching { p.setVideoScale(scale.vlc) }
        val item = Media(vlc, Uri.parse(media.url)).apply { configure(media) }
        val oldMedia = this.media
        this.media = item
        p.media = item
        player = p
        opened++
        if (old != null) replaced = old to oldMedia
    }

    private fun Media.configure(m: EngineMedia) {
        setHWDecoderEnabled(m.hardwareDecoding, false)
        addOption(":http-reconnect")
        addOption(":network-caching=${m.cacheMs}")
        // Seek to the nearest keyframe: the picture appears right away instead of waiting (or freezing) on
        // hardware decoders after a jump into the middle of a GOP. ±10 s taps do not need frame accuracy.
        if (m.fastSeek) addOption(":input-fast-seek")
        // External voice-overs / subtitles are part of the media from the start. Added to the running player (as
        // before, on every "Playing" — so again after each pause and reconnect) they reopened inputs mid-playback:
        // a hiccup and a drifting voice-over.
        for (u in m.audioFiles) runCatching { addSlave(IMedia.Slave(IMedia.Slave.Type.Audio, 4, u)) }
        for (u in m.subtitleFiles) runCatching { addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle, 4, u)) }
        // Reliable resume over HTTP: seeking before play() is ignored by many streams.
        if (m.startMs > 0L) addOption(":start-time=${m.startMs / 1000.0}")
    }

    private fun runTeardown(block: () -> Unit) {
        val ex = teardownExec
        if (ex != null && !ex.isShutdown) ex.execute { runCatching(block) } else Thread { runCatching(block) }.start()
    }

    /** The player replaced by the last [open]: stopped in the background, after the new one was told to play. */
    private fun retireReplaced() {
        val (old, oldMedia) = replaced ?: return
        replaced = null
        runTeardown {
            runCatching { old.stop() }
            runCatching { old.release() }
            runCatching { oldMedia?.release() }
        }
    }

    override fun release() {
        retireReplaced()
        val p = player
        val item = media
        val vlc = libVlc
        val exec = teardownExec
        player = null; media = null; libVlc = null; teardownExec = null
        if (p == null && item == null && vlc == null) { exec?.shutdown(); return }
        p?.setEventListener(null)
        runCatching { p?.detachViews() }
        val job = Runnable {
            // Order matters: LibVLC must outlive every MediaPlayer/Media created on it, including the ones
            // replaced by earlier reconnects (those are queued on the same single-thread executor before this).
            runCatching { p?.stop() }
            runCatching { p?.release() }
            runCatching { item?.release() }
            runCatching { vlc?.release() }
        }
        if (exec != null && !exec.isShutdown) { exec.execute(job); exec.shutdown() } else Thread(job).start()
    }

    // ---- libVLC events (one listener per MediaPlayer) ----------------------------------------
    // Events of a player that was already replaced can still sit in the main looper queue; they are ignored.
    private fun listenerFor(owner: MediaPlayer) = MediaPlayer.EventListener { event ->
        if (owner !== player) return@EventListener
        val l = listener ?: return@EventListener
        when (event.type) {
            MediaPlayer.Event.Playing -> l.playing()
            MediaPlayer.Event.Paused -> l.paused()
            MediaPlayer.Event.Stopped -> l.stopped()
            MediaPlayer.Event.ESAdded -> l.tracksChanged()
            MediaPlayer.Event.EndReached -> l.endReached()
            MediaPlayer.Event.EncounteredError -> l.failed()
            MediaPlayer.Event.Vout -> l.videoOutput(event.voutCount)
            MediaPlayer.Event.Buffering -> l.buffering(event.buffering)
            MediaPlayer.Event.TimeChanged -> l.timeChanged()
            MediaPlayer.Event.LengthChanged -> l.lengthChanged()
        }
    }

    // ---- playback ----------------------------------------------------------------------------
    override fun play() {
        player?.play()
        retireReplaced()
    }

    override fun pause() { player?.pause() }

    override val isPlaying: Boolean get() = player?.isPlaying == true
    override val isSeekable: Boolean get() = player?.isSeekable == true
    override val timeMs: Long get() = player?.time ?: 0L
    override val lengthMs: Long get() = player?.length ?: 0L
    override val fraction: Float get() = player?.position ?: 0f
    override val rate: Float get() = player?.rate ?: 1f
    override val volume: Int get() = player?.volume ?: 0
    override fun seekTo(ms: Long) { player?.setTime(ms) }
    override fun seekToFraction(fraction: Float) { player?.position = fraction }

    // ---- tracks ------------------------------------------------------------------------------
    private fun Array<out MediaPlayer.TrackDescription>?.toTracks() = this?.map { EngineTrack(it.id, it.name.orEmpty()) }.orEmpty()

    override fun audioTracks(): List<EngineTrack> = player?.audioTracks.toTracks()
    override val audioTrack: Int get() = player?.audioTrack ?: -1
    override fun setAudioTrack(id: Int) { player?.setAudioTrack(id) }
    override fun subtitleTracks(): List<EngineTrack> = player?.spuTracks.toTracks()
    override val subtitleTrack: Int get() = player?.spuTrack ?: -1
    override fun setSubtitleTrack(id: Int) { player?.setSpuTrack(id) }
    override fun addAudioFile(url: String) {
        runCatching { player?.addSlave(IMedia.Slave.Type.Audio, Uri.parse(url), true) }
    }

    // ---- video -------------------------------------------------------------------------------
    override val videoTrackCount: Int get() = player?.videoTracksCount ?: 0
    override val videoTrack: EngineVideoTrack?
        get() = runCatching { player?.currentVideoTrack }.getOrNull()?.let {
            EngineVideoTrack(it.codec, it.width, it.height, it.frameRateNum, it.frameRateDen, it.sarNum, it.sarDen)
        }
    override fun setVideoEnabled(enabled: Boolean) { player?.setVideoTrackEnabled(enabled) }
    override fun attachVideo() { runCatching { player?.attachViews(layout, null, true, false) } }
    override fun detachVideo() { runCatching { player?.detachViews() } }
    override fun refreshVideoSize() { player?.updateVideoSurfaces() }
    override fun setScale(scale: VideoScale) { player?.setVideoScale(scale.vlc) }

    override fun stats(): EngineStats? {
        val st = runCatching { media?.stats }.getOrNull() ?: return null
        return EngineStats(
            readBytes = st.readBytes, decodedVideo = st.decodedVideo, decodedAudio = st.decodedAudio,
            displayedPictures = st.displayedPictures, lostPictures = st.lostPictures,
            playedAbuffers = st.playedAbuffers, lostAbuffers = st.lostAbuffers,
            demuxCorrupted = st.demuxCorrupted, demuxDiscontinuity = st.demuxDiscontinuity
        )
    }

    private val VideoScale.vlc: MediaPlayer.ScaleType
        get() = when (this) {
            VideoScale.BEST_FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            VideoScale.FIT_SCREEN -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            VideoScale.FILL -> MediaPlayer.ScaleType.SURFACE_FILL
            VideoScale.RATIO_16_9 -> MediaPlayer.ScaleType.SURFACE_16_9
        }
}
