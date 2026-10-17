package ru.suslikovanora.media.player

import ru.suslikovanora.media.core.MirrorLink
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import ru.suslikovanora.media.AppLog
import ru.suslikovanora.media.core.BurstProxy
import ru.suslikovanora.media.net.Api
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * [PlayerEngine] на Media3 (ExoPlayer). Читает через тот же запас, что и VLC (BurstProxy, 127.0.0.1): запас на диске
 * подстраивается под скорость сети и битрейт фильма, а свой буфер ExoPlayer держит небольшим и ровным ([ReserveLoadControl]).
 * Если запас выключен, читает зеркало напрямую (OkHttp) с обычным буфером. Внешняя озвучка (`.mka`) — [MergingMediaSource]: один источник
 * времени с видео, без дрейфа. Звук DTS и TrueHD — модуль FFmpeg (`EXTENSION_RENDERER_MODE_ON`: если телефон умеет
 * сам, как AC3/E-AC3 на Samsung, играет телефон).
 *
 * Один ExoPlayer на экран плеера; [open] (переподключение, другая серия) готовит его заново. Чего Media3 не умеет
 * (кодек, контейнер, файл без индекса, ошибка декодера), уходит в [EngineListener.unsupported]: плеер откроет файл
 * в VLC с того же места. Неподдерживаемый звук Media3 не считает ошибкой (видео идёт молча) — поэтому дорожки
 * проверяются в `onTracksChanged`.
 */
@OptIn(UnstableApi::class)
class Media3Engine(private val context: Context) : PlayerEngine {
    private val view = PlayerView(context).apply {
        useController = false
        setShutterBackgroundColor(Color.BLACK)
        setKeepContentOnPlayerReset(true) // a reconnect keeps the last picture instead of a black flash
        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER) // the player screen has its own label
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
    override val videoView: View get() = view

    // Subtitles out of the content frame (it is bigger than the screen in «Заполнить»): over the whole view, in the
    // place of the fitted frame ([fitSubtitles]) — never cut off with the cropped picture.
    private val subs = view.subtitleView?.also { s ->
        (s.parent as? android.view.ViewGroup)?.removeView(s)
        view.addView(s, 1, android.widget.FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER))
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> view.post { fitSubs() } }
    }

    private fun fitSubs() {
        val s = subs ?: return
        val v = exo?.videoSize
        val aspect = when {
            forced169 -> 16f / 9f
            v != null && v.width > 0 && v.height > 0 -> v.width * v.pixelWidthHeightRatio / v.height
            else -> 0f
        }
        fitSubtitles(s, aspect)
    }
    override var listener: EngineListener? = null
    override val kind = EngineKind.MEDIA3

    private val handler = Handler(Looper.getMainLooper())
    private var exo: ExoPlayer? = null
    private var opened = 0
    override val hasPlayer: Boolean get() = exo != null
    override val generation: Int get() = opened

    private var media: EngineMedia? = null
    private val extraAudio = ArrayList<String>()   // voice-overs picked in the menu during playback
    private var selectLastAudio = false
    private var reported = false                   // unsupported() already sent for this open
    private var tracksLogged = ""
    private var lengthSent = 0L
    private var bufferedPct = -1
    private var forced169 = false
    private var videoDecoder = ""
    private var audioDecoder = ""
    @Volatile private var bandwidth = 0L
    private val bytesRead = AtomicLong()           // from the network since this open
    @Volatile private var mainKey = ""
    @Volatile private var fileSize = -1L
    @Volatile private var viaReserve = false     // the film is read from BurstProxy on this phone, not from the mirror

    private val transfer = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            if (!isNetwork || fileSize > 0 || MirrorLink.fileKey(dataSpec.uri.toString()) != mainKey) return
            // "Content-Range: bytes a-b/total" → the file's size → its average bitrate (the heavy-file question)
            val range = runCatching { source.responseHeaders.entries.firstOrNull { it.key.equals("content-range", true) }?.value?.firstOrNull() }.getOrNull()
            range?.substringAfterLast('/')?.toLongOrNull()?.let { fileSize = it }
        }
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (!isNetwork) return
            bytesRead.addAndGet(bytesTransferred.toLong())
            if (dataSpec.uri.host == LOCAL_HOST) return // from the reserve: BurstProxy counts what it downloads itself
            downloaded.addAndGet(bytesTransferred.toLong())
            BurstProxy.onBytes(bytesTransferred.toLong()) // «Трафик фильмов» counts Media3's data too (only the hook is called)
        }
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    }

    private fun build(): ExoPlayer {
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON) // FFmpeg only where the phone can't
            .setEnableDecoderFallback(true)
        // Straight from the mirror: loads in bursts, up to MAX_BUFFER_MS ahead, then the radio rests until only
        // MIN_BUFFER_MS are left. Through the reserve: see ReserveLoadControl.
        // After a stall it waits for AFTER_REBUFFER_MS of film (one longer wait instead of a stutter every few seconds).
        val direct = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, AFTER_REBUFFER_MS)
            .setBackBuffer(BACK_BUFFER_MS, true)
            // ExoPlayer keeps its buffer on the Java heap: 4K pushed the app to ~207 of 256 MB (16.10 journal). Bytes cap
            // it before the seconds do on heavy files.
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val p = ExoPlayer.Builder(context, renderers)
            .setLoadControl(ReserveLoadControl(direct))
            // Audio focus, «becoming noisy» and the media buttons stay with PlayerAudioSession (the same for both engines).
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
            .setHandleAudioBecomingNoisy(false)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
            .setSeekParameters(SeekParameters.EXACT)
            .build()
        p.addListener(events)
        p.addAnalyticsListener(analytics)
        logFfmpegOnce()
        return p
    }

    private fun dataSourceFactory(): DataSource.Factory =
        OkHttpDataSource.Factory(if (viaReserve) localClient else client).setTransferListener(transfer)

    /**
     * Through the reserve the downloading is BurstProxy's: it fetches in bursts sized to how much faster the network is
     * than the film, and keeps up to its limit on disk. ExoPlayer then only keeps [RESERVE_AHEAD_US] in memory and takes
     * the next piece as soon as it plays one — steady reads, as VLC does: BurstProxy measures the film's bitrate by them,
     * and stops downloading for a reader that has not read for a minute (the direct burst-and-rest would look like that).
     * Less on the Java heap, too (4K pushed it to ~207 of 256 MB, 16.10 journal).
     */
    private inner class ReserveLoadControl(private val d: DefaultLoadControl) : LoadControl {
        override fun onPrepared(playerId: PlayerId) = d.onPrepared(playerId)
        override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<ExoTrackSelection?>) =
            d.onTracksSelected(parameters, trackGroups, trackSelections)
        override fun onStopped(playerId: PlayerId) = d.onStopped(playerId)
        override fun onReleased(playerId: PlayerId) = d.onReleased(playerId)
        override fun getAllocator() = d.allocator
        override fun getBackBufferDurationUs(playerId: PlayerId) = d.getBackBufferDurationUs(playerId)
        override fun retainBackBufferFromKeyframe(playerId: PlayerId) = d.retainBackBufferFromKeyframe(playerId)
        override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
            val load = d.shouldContinueLoading(parameters) // always asked: it keeps its own state
            // Nothing loaded ahead of one of the sources (a voice-over file or subtitles switched back on start again
            // from the current moment) while the byte cap is reached by the film's own buffer: without this ExoPlayer
            // waited at «Буферизация 0%» until a seek emptied the buffer.
            if (!load && parameters.bufferedDurationUs < STARVING_US) return true
            return load && (!viaReserve || parameters.bufferedDurationUs < RESERVE_AHEAD_US)
        }
        override fun shouldStartPlayback(parameters: LoadControl.Parameters) = d.shouldStartPlayback(parameters)
    }

    private fun buildSource(m: EngineMedia): MediaSource {
        val factory = DefaultMediaSourceFactory(dataSourceFactory())
        val item = MediaItem.Builder().setUri(m.url)
            .setSubtitleConfigurations(m.subtitleFiles.map { subtitle(it) })
            .build()
        val main = factory.createMediaSource(item)
        val audio = (m.audioFiles + extraAudio).map { factory.createMediaSource(MediaItem.fromUri(it)) }
        return if (audio.isEmpty()) main else MergingMediaSource(main, *audio.toTypedArray())
    }

    private fun subtitle(url: String): MediaItem.SubtitleConfiguration {
        val name = Uri.parse(url).lastPathSegment.orEmpty()
        val mime = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt" -> MimeTypes.TEXT_VTT
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        return MediaItem.SubtitleConfiguration.Builder(Uri.parse(url)).setMimeType(mime).setLabel(name.substringBeforeLast('.')).build()
    }

    override fun open(media: EngineMedia, scale: VideoScale) {
        val p = exo ?: build().also { exo = it; view.player = it }
        this.media = media
        extraAudio.clear()
        opened++
        reported = false; tracksLogged = ""; selectLastAudio = false; lengthSent = 0L; bufferedPct = -1
        // A reconnect to the same file reuses the decoders (no new "initialized" event): their names stay.
        if (MirrorLink.fileKey(media.url) != mainKey) { videoDecoder = ""; audioDecoder = "" }
        bytesRead.set(0L)
        if (MirrorLink.fileKey(media.url) != mainKey) fileSize = -1L
        mainKey = MirrorLink.fileKey(media.url)
        viaReserve = Uri.parse(media.url).host == LOCAL_HOST
        setScale(scale)
        // Remembered tracks are applied by PlayerTrackSelector once the tracks are known.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .build()
        p.playWhenReady = false // like a new VLC player: plays on play()
        p.setMediaSource(buildSource(media), media.startMs.coerceAtLeast(0L))
        p.prepare()
    }

    override fun release() {
        handler.removeCallbacksAndMessages(null)
        val p = exo ?: return
        exo = null
        view.player = null
        media = null
        runCatching { p.removeListener(events); p.removeAnalyticsListener(analytics); p.release() }
    }

    // ---- events ------------------------------------------------------------------------------
    private val events = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val l = listener ?: return
            when (state) {
                Player.STATE_BUFFERING -> { bufferedPct = -1; tick() }
                Player.STATE_READY -> { bufferedPct = -1; l.buffering(100f); checkSeekable() }
                Player.STATE_ENDED -> l.endReached()
                else -> Unit
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) { listener?.playing(); tick() }
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) listener?.paused()
        }
        override fun onTracksChanged(tracks: Tracks) {
            judge(tracks)
            if (selectLastAudio) pickLastAudio(tracks)
            listener?.tracksChanged()
        }
        override fun onRenderedFirstFrame() { listener?.videoOutput(1) }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val d = lengthMs
            if (d > 0L && d != lengthSent) { lengthSent = d; listener?.lengthChanged() }
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            listener?.timeChanged() // a seek is confirmed at once, also while paused
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (forced169) view.post { applyForcedAspect() }
            view.post { fitSubs() }
        }
        override fun onPlayerError(error: PlaybackException) {
            val cause = error.cause?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: error.message
            AppLog.write("[media3] error ${error.errorCodeName}: $cause")
            // Network / HTTP trouble: the usual reconnect (again in Media3). Anything else — the file is VLC's.
            if (error.errorCode in 2000..2999) listener?.failed() else report("error ${error.errorCodeName}")
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            videoDecoder = decoderName
            AppLog.write("[media3] video decoder $decoderName (${exo?.videoFormat?.let { "${it.sampleMimeType} ${it.width}x${it.height}" } ?: "?"})")
        }
        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            audioDecoder = decoderName
            AppLog.write("[media3] audio decoder $decoderName (${exo?.audioFormat?.let { "${it.sampleMimeType} ${it.channelCount}ch" } ?: "?"})")
        }
        override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
            bandwidth = bitrateEstimate
        }
    }

    /** Clock and buffering progress for the player screen (ExoPlayer has no such events): only while there is something to say. */
    private val ticker = object : Runnable {
        override fun run() {
            val p = exo ?: return
            val l = listener
            if (p.playbackState == Player.STATE_BUFFERING) {
                // Only a rising number is reported: a stream that stopped delivering goes silent, and the player's
                // stall timer reconnects it (the same as with VLC).
                val ahead = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L)
                val pct = (ahead * 100 / AFTER_REBUFFER_MS).toInt().coerceIn(0, 99)
                if (pct > bufferedPct) { bufferedPct = pct; l?.buffering(pct.toFloat()) }
            } else if (p.isPlaying) l?.timeChanged()
            if (p.playbackState == Player.STATE_BUFFERING || p.isPlaying) handler.postDelayed(this, TICK_MS)
        }
    }

    private fun tick() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    /** Sends [reason] once per open, after the current callback (the listener may close this player). */
    private fun report(reason: String) {
        if (reported) return
        reported = true
        val gen = opened
        handler.post { if (gen == opened && exo != null) listener?.unsupported(reason) }
    }

    /** Video or sound that no decoder (the phone's or FFmpeg) takes: Media3 would play a black picture or silence. */
    private fun judge(tracks: Tracks) {
        if (reported || tracks.groups.isEmpty()) return
        val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val sig = tracks.groups.joinToString { "${it.type}:${it.length}" }
        if (sig != tracksLogged) { tracksLogged = sig; AppLog.write("[media3] tracks: video ${mimes(video)}, audio ${mimes(audio)}, " +
            "subtitles ${mimes(tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT })}") }
        when {
            video.isNotEmpty() && video.none { it.isSupported(true) } -> report("video ${mimes(video)}: no decoder")
            audio.isNotEmpty() && audio.none { it.isSupported(true) } -> report("audio ${mimes(audio)}: no decoder (FFmpeg ${ffmpegState()})")
        }
    }

    private fun mimes(groups: List<Tracks.Group>): String =
        if (groups.isEmpty()) "none" else groups.joinToString(", ") { g ->
            (0 until g.length).joinToString("/") { i ->
                g.getTrackFormat(i).sampleMimeType.orEmpty().substringAfter('/') + if (g.isTrackSupported(i, true)) "" else "(no)"
            }
        }

    /** A film without an index (MKV without Cues) cannot seek in Media3 — «Продолжить» and the seek bar need it; VLC can. */
    private fun checkSeekable() {
        val p = exo ?: return
        if (!reported && !p.isCurrentMediaItemSeekable && lengthMs > 0L) report("no index: Media3 cannot seek in this file")
    }

    // ---- playback ----------------------------------------------------------------------------
    override fun play() { exo?.playWhenReady = true }
    override fun pause() { exo?.playWhenReady = false }

    // Like VLC: "playing" also while it waits for data (the play/pause button then pauses, not starts again).
    override val isPlaying: Boolean
        get() = exo?.let { it.playWhenReady && (it.playbackState == Player.STATE_READY || it.playbackState == Player.STATE_BUFFERING) } == true
    override val isSeekable: Boolean get() = exo?.isCurrentMediaItemSeekable == true
    override val timeMs: Long get() = exo?.currentPosition ?: 0L
    override val lengthMs: Long get() = exo?.duration?.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
    override val fraction: Float get() = lengthMs.let { d -> if (d > 0L) timeMs.toFloat() / d else -1f }
    override val rate: Float get() = exo?.playbackParameters?.speed ?: 1f
    override val volume: Int get() = ((exo?.volume ?: 0f) * 100).toInt()
    override fun seekTo(ms: Long) { exo?.seekTo(ms.coerceAtLeast(0L)) }
    override fun seekToFraction(fraction: Float) { lengthMs.takeIf { it > 0L }?.let { seekTo((it * fraction).toLong()) } }
    override val bufferedPositionMs: Long get() = exo?.bufferedPosition ?: -1L
    override val bitrateMbit: Double?
        get() = fileSize.takeIf { it > 0L }?.let { size -> lengthMs.takeIf { it > 0L }?.let { size * 8.0 / it / 1000.0 } }
    override val handlesSurfaceItself: Boolean get() = true

    // ---- tracks ------------------------------------------------------------------------------
    // Track id = index of its group in currentTracks × 100 + index in the group (stable for one file across reopens).
    private fun tracksOf(type: Int): List<EngineTrack> {
        val p = exo ?: return emptyList()
        val out = ArrayList<EngineTrack>()
        var n = 0
        p.currentTracks.groups.forEachIndexed { gi, g ->
            if (g.type != type) return@forEachIndexed
            for (ti in 0 until g.length) {
                n++
                if (g.isTrackSupported(ti, true)) out += EngineTrack(gi * 100 + ti, trackName(g.getTrackFormat(ti), n))
            }
        }
        return out
    }

    private fun selectedOf(type: Int): Int {
        val p = exo ?: return -1
        p.currentTracks.groups.forEachIndexed { gi, g ->
            if (g.type == type) for (ti in 0 until g.length) if (g.isTrackSelected(ti)) return gi * 100 + ti
        }
        return -1
    }

    private fun select(type: Int, id: Int) {
        val p = exo ?: return
        val b = p.trackSelectionParameters.buildUpon()
        if (id < 0) b.setTrackTypeDisabled(type, true)
        else {
            val g = p.currentTracks.groups.getOrNull(id / 100)?.takeIf { it.type == type } ?: return
            b.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, id % 100))
        }
        p.trackSelectionParameters = b.build()
    }

    /** The voice-over file added from the menu is the last audio group (MergingMediaSource keeps the sources' order). */
    private fun pickLastAudio(tracks: Tracks) {
        val gi = tracks.groups.indexOfLast { it.type == C.TRACK_TYPE_AUDIO && it.isSupported(true) }
        if (gi < 0) return
        selectLastAudio = false
        select(C.TRACK_TYPE_AUDIO, gi * 100)
    }

    override fun audioTracks(): List<EngineTrack> = tracksOf(C.TRACK_TYPE_AUDIO)
    override val audioTrack: Int get() = selectedOf(C.TRACK_TYPE_AUDIO)
    override fun setAudioTrack(id: Int) = select(C.TRACK_TYPE_AUDIO, id)
    override fun subtitleTracks(): List<EngineTrack> = tracksOf(C.TRACK_TYPE_TEXT)
    override val subtitleTrack: Int get() = selectedOf(C.TRACK_TYPE_TEXT)
    override fun setSubtitleTrack(id: Int) = select(C.TRACK_TYPE_TEXT, id)

    override fun addAudioFile(url: String) {
        val p = exo ?: return
        val m = media ?: return
        if (url in extraAudio || url in m.audioFiles) return
        extraAudio += url
        selectLastAudio = true
        // Media3 cannot add a source to a prepared one: the same file again with the new voice-over, at the same moment.
        p.setMediaSource(buildSource(m), p.currentPosition)
        p.prepare()
    }

    // ---- video -------------------------------------------------------------------------------
    override val videoTrackCount: Int get() = exo?.currentTracks?.groups?.count { it.type == C.TRACK_TYPE_VIDEO } ?: 0
    override val videoTrack: EngineVideoTrack?
        get() = exo?.videoFormat?.let { f ->
            val fps = if (f.frameRate > 0f) (f.frameRate * 1000).toInt() else 0
            val sar = if (f.pixelWidthHeightRatio > 0f) (f.pixelWidthHeightRatio * 1000).toInt() else 0
            EngineVideoTrack(f.sampleMimeType, f.width, f.height, fps, if (fps > 0) 1000 else 0, sar, if (sar > 0) 1000 else 0)
        }
    override fun setVideoEnabled(enabled: Boolean) {
        val p = exo ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled).build()
    }
    override fun attachVideo() { exo?.let { if (view.player !== it) view.player = it } }
    override fun detachVideo() { view.player = null }
    override fun refreshVideoSize() { view.requestLayout() }
    override fun setScale(scale: VideoScale) {
        forced169 = scale == VideoScale.RATIO_16_9
        view.resizeMode = when (scale) {
            VideoScale.BEST_FIT, VideoScale.RATIO_16_9 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            VideoScale.FIT_SCREEN -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            VideoScale.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        if (forced169) applyForcedAspect()
        else exo?.videoSize?.let { s -> if (s.width > 0 && s.height > 0) contentFrame()?.setAspectRatio(s.width * s.pixelWidthHeightRatio / s.height) }
        fitSubs()
    }

    private fun contentFrame(): AspectRatioFrameLayout? = view.findViewById(androidx.media3.ui.R.id.exo_content_frame)
    private fun applyForcedAspect() { if (forced169) contentFrame()?.setAspectRatio(16f / 9f) }

    override fun stats(): EngineStats? {
        val p = exo ?: return null
        val v = p.videoDecoderCounters?.also { it.ensureUpdated() }
        val a = p.audioDecoderCounters?.also { it.ensureUpdated() }
        return EngineStats(
            readBytes = bytesRead.get().coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            decodedVideo = v?.queuedInputBufferCount ?: 0, decodedAudio = a?.queuedInputBufferCount ?: 0,
            displayedPictures = v?.renderedOutputBufferCount ?: 0, lostPictures = v?.droppedBufferCount ?: 0,
            playedAbuffers = a?.renderedOutputBufferCount ?: 0, lostAbuffers = a?.droppedBufferCount ?: 0,
            demuxCorrupted = 0, demuxDiscontinuity = 0
        )
    }

    override fun diagLine(): String? {
        val p = exo ?: return null
        val ahead = (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L) / 1000.0
        // through the reserve ExoPlayer's estimate is the phone's own speed: the network is in the [proxy] line
        val source = if (viaReserve) "through the reserve" else "straight from the mirror, network estimate %.1f Mbit/s".format(bandwidth / 1e6)
        return "[media3] buffered ahead %.1f s · %s · decoders video=%s audio=%s".format(
            ahead, source, videoDecoder.ifEmpty { "-" }, audioDecoder.ifEmpty { "-" })
    }

    private fun trackName(f: Format, n: Int): String {
        val parts = ArrayList<String>()
        f.label?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
        codecName(f.sampleMimeType)?.let { c -> if (parts.none { it.contains(c, true) }) parts += c }
        if (f.channelCount > 0) parts += if (f.channelCount > 2) "${f.channelCount - 1}.1" else "${f.channelCount}.0"
        val base = if (parts.isEmpty()) "Track $n" else parts.joinToString(" · ")
        // "[Russian]" as in VLC's names: a track remembered in one engine is found in the other (PlayerTrackSelector.pickTrack)
        val lang = f.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
            ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH) }?.takeIf { it.isNotBlank() }
        return if (lang != null) "$base - [$lang]" else base
    }

    private fun codecName(mime: String?): String? = when (mime) {
        null -> null
        MimeTypes.AUDIO_AC3 -> "AC3"
        MimeTypes.AUDIO_E_AC3 -> "E-AC3"
        MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3 Atmos"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.APPLICATION_SUBRIP -> "SRT"
        MimeTypes.TEXT_SSA -> "ASS"
        MimeTypes.TEXT_VTT -> "VTT"
        MimeTypes.APPLICATION_PGS -> "PGS"
        MimeTypes.APPLICATION_VOBSUB -> "VobSub"
        else -> null
    }

    companion object {
        private const val MIN_BUFFER_MS = 30_000
        private const val MAX_BUFFER_MS = 90_000
        private const val START_BUFFER_MS = 2_500
        private const val AFTER_REBUFFER_MS = 6_000
        private const val BACK_BUFFER_MS = 15_000
        private const val TICK_MS = 250L
        private const val TARGET_BUFFER_BYTES = 80 shl 20
        private const val RESERVE_AHEAD_US = 15_000_000L
        private const val STARVING_US = 2_000_000L
        private const val LOCAL_HOST = "127.0.0.1"

        /**
         * The reserve's client with a shorter read timeout: 16.10, after a seek the mirror sent the headers only after
         * 10.9 s. Six seconds without data and ExoPlayer asks again for the same range on a new connection.
         */
        private val client by lazy { Api.reserve.newBuilder().readTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build() }

        /**
         * To the reserve on this phone: it answers at once or waits for the network itself (its own stall watcher and
         * reconnects), and «копим запас» holds the data back up to ~50 s on purpose — no timeout of ours before that.
         */
        private val localClient by lazy {
            okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
                .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()
        }

        /** Bytes Media3 fetched from the mirror since the process started (the journal's «MB fetched»). */
        val downloaded = AtomicLong()

        private var ffmpegLogged = false
        fun ffmpegState(): String = runCatching {
            if (!FfmpegLibrary.isAvailable()) "not loaded"
            else "${FfmpegLibrary.getVersion()}: dts=${FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_DTS)} truehd=${FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_TRUEHD)}"
        }.getOrElse { "missing (${it.javaClass.simpleName})" }

        private fun logFfmpegOnce() {
            if (ffmpegLogged) return
            ffmpegLogged = true
            AppLog.write("[media3] FFmpeg ${ffmpegState()}")
        }
    }
}
