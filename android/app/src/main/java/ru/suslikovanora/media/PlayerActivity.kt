package ru.suslikovanora.media

import ru.suslikovanora.media.core.*
import ru.suslikovanora.media.player.PlayerAudioSession
import ru.suslikovanora.media.player.PlayerBluRayController
import ru.suslikovanora.media.player.PlayerControlsOverlay
import ru.suslikovanora.media.player.PlayerDiagnostics
import ru.suslikovanora.media.player.PlayerGestures
import ru.suslikovanora.media.player.PlayerNetworkMonitor
import ru.suslikovanora.media.player.PlayerPipController
import ru.suslikovanora.media.player.PlayerPlaylistQueue
import ru.suslikovanora.media.player.PlayerTrackSelector
import ru.suslikovanora.media.player.PlayerWatchProgress

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import android.view.Gravity
import android.view.MotionEvent
import android.graphics.Rect
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.Locale

class PlayerActivity : ComponentActivity() {
    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var media: Media? = null
    private lateinit var root: FrameLayout
    private lateinit var stage: FrameLayout            // the film + everything over it
    private lateinit var infoPanel: android.view.View   // below the film while the phone is upright
    private lateinit var fullButton: android.widget.ImageView
    private lateinit var optionsRow: LinearLayout
    private lateinit var centerRow: FrameLayout
    private var lastInsets = androidx.core.graphics.Insets.NONE
    private val panel = PlayerPanelState()
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var controls: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var playPauseButton: android.widget.ImageView
    private lateinit var seekBar: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var volumeBar: SeekBar
    private lateinit var volumeLabel: TextView
    private lateinit var bufferingLabel: TextView
    private lateinit var gestures: PlayerGestures

    private var userSeeking = false
    private var playbackPosition = 0L
    private var playWhenReady = true
    private var ended = false
    private val controlsVisible: Boolean get() = controlsOverlay.isVisible
    private val locked: Boolean get() = controlsOverlay.isLocked
    private val inPip: Boolean get() = pipController.inPip
    private var lastUiUpdateAt = 0L

    // Stability state.
    private var teardownExec: ExecutorService? = null   // serial native teardown: players first, LibVLC last
    private var buffering = false
    private var reconnectScheduled = false
    private var pendingSeekPos: Long? = null            // seek issued but not yet confirmed by TimeChanged
    private var tapSeekTarget: Long? = null             // accumulated double-tap target, applied after a short pause
    private var tapSeekStart = 0L
    private var lastWatchTime = -1L
    private var watchStuckTicks = 0
    private lateinit var seekLabel: TextView

    // 1.3: gestures, frame mode, media session, sidecar subtitles, remembered tracks, next-episode card, watch progress.
    private val uiPrefs by lazy { getSharedPreferences("player_ui", MODE_PRIVATE) }
    private var scaleIndex = 0
    private lateinit var scaleButton: TextView
    private var currentUrl = ""
    private var currentTitle = "Видео"
    private lateinit var titleView: TextView
    private lateinit var prevButton: android.widget.ImageView
    private lateinit var nextButton: android.widget.ImageView

    // Network handling: reload the stream in place (same LibVLC/activity) when the network changes.
    private var needsReload = false
    private var userPaused = false // only set by the Play/Pause button; VLC's own Paused events must not count
    private var retryCount = 0
    private var lastTime = 0L
    private val networkLost: Boolean get() = networkMonitor.isNetworkLost

    private val networkMonitor by lazy {
        PlayerNetworkMonitor(this, object : PlayerNetworkMonitor.Callback {
            override fun hasPlayer(): Boolean = mediaPlayer != null
            override fun isViaReserve(): Boolean = viaReserve()
            override fun shouldReconnectViaReserve(wasLost: Boolean): Boolean =
                wasLost && (needsReload || (!ended && mediaPlayer?.isPlaying != true && !userPaused))
            override fun onScheduleReconnect(delayMs: Long) {
                retryCount = 0
                reconnectScheduled = true
                handler.removeCallbacks(reconnectRunnable)
                handler.postDelayed(reconnectRunnable, delayMs)
            }
            override fun onNetworkLost() {
                if (catchingUp) endCatchUp()
                handler.removeCallbacks(stallRunnable)
                if (!userPaused && !ended) showStatus("Нет сети — ждём подключение…")
            }
            override fun onNetworkRestoredWithoutReconnect() {
                if (!buffering) bufferingLabel.visibility = View.GONE
            }
        })
    }

    private val handler = Handler(Looper.getMainLooper())
    // Several quick reconnects in a row did not help: the VLC engine itself may be stuck on a dead connection, so
    // the next attempt starts it from scratch instead of building yet another player on it.
    private val reconnectRunnable = Runnable { if (retryCount >= 3 && mediaPlayer != null) restartClean(play = !userPaused, resetRetries = false) else reconnect() }
    // No buffering/open progress for a while: treat as a dead connection (goes through backoff).
    private val stallRunnable = Runnable { if (mediaPlayer != null && !userPaused && !ended) handleError() }
    private val applyTapSeekRunnable = Runnable {
        val t = tapSeekTarget ?: return@Runnable
        tapSeekTarget = null
        seekTo(t)
    }
    // A seek that VLC never confirms (dead socket after a Wi-Fi <-> mobile switch) is redone on a fresh connection.
    private val seekWatchdogRunnable: Runnable = Runnable {
        val target = pendingSeekPos ?: return@Runnable
        val p = mediaPlayer ?: return@Runnable
        if (bluRayController.onWatchdog(p, currentUrl, userPaused, target)) return@Runnable
        val t = timeOf(p)
        if (kotlin.math.abs(t - target) > SEEK_CONFIRM_TOLERANCE_MS) {
            if (buffering || !p.isPlaying) {
                // The stream is still opening or buffering (e.g. slow network): give it another cycle
                handler.postDelayed(seekWatchdogRunnable, SEEK_CONFIRM_TIMEOUT_MS)
                return@Runnable
            }
            if (p.isSeekable && retryCount == 0) {
                p.setTime(target)
                handler.postDelayed(seekWatchdogRunnable, SEEK_CONFIRM_TIMEOUT_MS)
            } else {
                reconnect(forcePlay = !userPaused, startAt = target)
            }
        } else pendingSeekPos = null
    }
    // Playing but the clock does not move and no buffering is reported = frozen playback.
    // Runs only while VLC reports "playing" (restarted by the Playing event), so a paused player wakes nobody up.
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val p = mediaPlayer ?: return
            if (!p.isPlaying) return // paused: nobody wakes up (restarted by the Playing event)
            warnIfSoftwareDecoding(p) // once per file; the track may not be known yet at the first picture
            checkNoPicture(p)
            checkHeavy()
            // Network trouble is handled by the reconnect logic; the supervisor judges only real playback.
            if (!userPaused && !ended && !networkMonitor.isNetworkLost && !audioSession.isPausedForFocus) statsOf(p)?.let { supervisor.tick(it) }
            handler.postDelayed(this, WATCHDOG_TICK_MS)
        }
    }

    private fun diagSample() = diagnostics.sample()

    // ---- playback health: the supervisor shared with the PC app (core.PlaybackSupervisor) ---------------
    // One place decides when playback is broken and what to do — only while the film really plays, never right after
    // a seek / resume / reconnect, one gentle step at a time (video restart, sound fix) before any reopen.
    private val supervisor = PlaybackSupervisor(object : PlaybackSupervisor.Actions {
        override fun restartVideo() {
            val p = mediaPlayer ?: return
            runCatching { p.setVideoTrackEnabled(false); p.setVideoTrackEnabled(true) }
            val t = perceivedPosition()
            // new decoder from the keyframe right here: the picture comes back at once — unless that keyframe is no
            // longer kept on the phone (a heavy film after a long wait): then the seek would fetch it from the mirror
            // again, step by step; the new decoder picks up at the next keyframe instead
            val keyframeKept = !viaReserve() || BurstProxy.keptBehindSec(currentUrl) >= 8.0
            if (t > 0L && keyframeKept) seekTo(t)
        }
        override fun fixSound() {
            val p = mediaPlayer ?: return
            trackSelector.fixSound(p)
        }
        override fun reopen(fresh: Boolean) {
            CrashReports.recovery("reopen at ${perceivedPosition() / 1000}s (fresh=$fresh)")
            if (fresh) { BurstProxy.dropAll(); restartClean(play = !userPaused) }
            else reconnect(forcePlay = true, startAt = perceivedPosition())
        }
        override fun notice(text: String) {
            showStatus(text)
            handler.postDelayed({ if (!buffering && !networkLost) bufferingLabel.visibility = View.GONE }, 2500L)
        }
        override fun log(message: String) = AppLog.write(message)
    }, stuckLimitMs = { if (viaReserve()) 30_000L else 8_000L })

    private fun statsOf(p: MediaPlayer): PlaybackSupervisor.Stats? {
        val st = runCatching { media?.stats }.getOrNull() ?: return null
        val vt = runCatching { p.currentVideoTrack }.getOrNull()
        val fps = if (vt != null && vt.frameRateNum > 0 && vt.frameRateDen > 0) vt.frameRateNum.toDouble() / vt.frameRateDen else 0.0
        return PlaybackSupervisor.Stats(
            timeMs = p.time, audioPlayed = st.playedAbuffers.toLong(), audioDecoded = st.decodedAudio.toLong(),
            pictures = st.displayedPictures.toLong(), expectedFps = fps,
            audioTracks = p.audioTracks?.count { it.id != -1 } ?: 0, audioSelected = p.audioTrack != -1,
            videoTracks = p.videoTracksCount, userMuted = p.volume == 0
        )
    }
    // Reset retry backoff only after the stream has played continuously, not on the first Playing event.
    private val stablePlaybackRunnable = Runnable { retryCount = 0 }

    // ---- libVLC events (one listener per MediaPlayer) ----------------------------------------
    // Events of a player that was already replaced can still sit in the main looper queue; they are ignored.
    private fun listenerFor(owner: MediaPlayer) = MediaPlayer.EventListener { event ->
        if (owner !== mediaPlayer) return@EventListener
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                bluRayController.onPlaying(owner, currentUrl) // Blu-ray: the length to the reserve, continue at the saved place
                supervisor.playing()
                bufferingShown = 0
                playWhenReady = true
                ended = false
                buffering = false
                handler.removeCallbacks(stallRunnable)
                handler.removeCallbacks(stablePlaybackRunnable)
                handler.postDelayed(stablePlaybackRunnable, STABLE_PLAYBACK_MS)
                root.keepScreenOn = true
                bufferingLabel.visibility = View.GONE
                setPlayIcon(true)
                startWatchdog()
                applyVideoFrameRate(owner)
                updatePipParams(playing = true)
                trackSelector.onPlaying(owner, currentUrl, currentTitle)
                updateSession()
                updateProgress(force = true)
            }
            MediaPlayer.Event.Paused -> {
                playWhenReady = false
                root.keepScreenOn = false
                updatePipParams(playing = false)
                setPlayIcon(false)
                updateProgress(force = true)
                updateSession()
            }
            MediaPlayer.Event.Stopped -> root.keepScreenOn = false
            MediaPlayer.Event.ESAdded -> trackSelector.onESAdded()
            MediaPlayer.Event.EndReached -> {
                // A dead network connection also shows up as EOF, so only a position near the known
                // duration is a real end; anything else is treated as a dropped stream and reloaded.
                val length = lengthOf(owner)
                val pos = maxOf(timeOf(owner), lastTime)
                if (length > 0L && pos >= length - 5_000L) {
                    supervisor.ended()
                    persistPosition(length, length) // watched to the end
                    if (!offerNextEpisode()) {
                        ended = true
                        playWhenReady = false
                        playbackPosition = 0L
                        root.keepScreenOn = false
                        setPlayIcon(false)
                        showControlsTemporarily()
                    }
                } else {
                    handleError(fromEof = true)
                }
            }
            MediaPlayer.Event.EncounteredError -> handleError()
            MediaPlayer.Event.Vout -> if (event.voutCount > 0) { applyVideoFrameRate(owner); updatePipParams(playing = owner.isPlaying); warnIfSoftwareDecoding(owner) }
            MediaPlayer.Event.Buffering -> {
                supervisor.buffering(event.buffering)
                if (catchingUp) {
                    // the reserve holds the film back on purpose: its own label, no stall timer
                    buffering = event.buffering < 100f
                } else if (event.buffering < 100f) {
                    buffering = true
                    // VLC refills its buffer from 0 after every jump (start at the saved place, seek, track switch,
                    // an automatic reconnect): within one wait the number only goes up instead of falling back to 0.
                    bufferingShown = maxOf(bufferingShown, event.buffering.toInt())
                    showStatus(
                        if (networkLost) "Нет сети — ждём подключение…"
                        else "Буферизация $bufferingShown%"
                    )
                    // Dead socket after a network switch = no more events: recover if buffering goes silent.
                    handler.removeCallbacks(stallRunnable)
                    handler.postDelayed(stallRunnable, stallTimeout())
                    maybeCatchUp(owner)
                } else {
                    buffering = false
                    bufferingShown = 0
                    handler.removeCallbacks(stallRunnable)
                    if (!networkLost) bufferingLabel.visibility = View.GONE
                }
            }
            MediaPlayer.Event.TimeChanged, MediaPlayer.Event.LengthChanged -> {
                if (event.type == MediaPlayer.Event.LengthChanged) { BurstProxy.setDuration(currentUrl, owner.length); checkHeavy() }
                // Blu-ray continued at a saved place: VLC may count from 0 there — timeOf() adds the known offset.
                val t = bluRayController.handleTimeUpdate(owner, currentUrl)
                if (t > 0L) {
                    val pending = pendingSeekPos
                    if (pending == null) {
                        lastTime = t
                        playbackPosition = t
                        maybePersist(t)
                    } else if (bluRayController.isSeeking) {
                        // a position seek (Blu-ray): accepted as soon as VLC reports the new place
                        if (!bluRayController.isSettling(t)) {
                            bluRayController.clearSeekTarget()
                            pendingSeekPos = null
                            handler.removeCallbacks(seekWatchdogRunnable)
                            lastTime = t
                            playbackPosition = t
                            bluRayController.onSettled(currentUrl, t, mediaPlayer?.position ?: 0f)
                        }
                    } else if (kotlin.math.abs(t - pending) <= SEEK_CONFIRM_TOLERANCE_MS) {
                        pendingSeekPos = null
                        handler.removeCallbacks(seekWatchdogRunnable)
                        lastTime = t
                        playbackPosition = t
                    }
                }
                updateProgress(force = false)
            }
        }
    }

    private val audioSession by lazy {
        PlayerAudioSession(this, object : PlayerAudioSession.Callback {
            override fun onMediaPlay() { doPlay() }
            override fun onMediaPause() { doPause() }
            override fun onSeekTo(pos: Long) { seekTo(pos) }
            override fun onSkipToNext() {
                if (playlistQueue.hasNext) playlistQueue.playNext() else tapSeek(true)
            }
            override fun onSkipToPrevious() {
                if (playlistQueue.hasPrevious) playlistQueue.playPrevious() else tapSeek(false)
            }
            override fun onFastForward() { tapSeek(true) }
            override fun onRewind() { tapSeek(false) }
            override fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true
            override fun onTransientPause() { mediaPlayer?.pause() }
            override fun onFocusLossPause() { supervisor.paused(); mediaPlayer?.pause() }
            override fun onFocusGainResume() { supervisor.resumed(); mediaPlayer?.play() }
            override fun onBecomingNoisy() { mediaPlayer?.pause() }
        })
    }

    private val bluRayController: PlayerBluRayController by lazy {
        PlayerBluRayController(handler, object : PlayerBluRayController.Callback {
            override fun isActivityAlive(): Boolean = alive && !isFinishing && !isDestroyed
            override fun showStatus(text: String) = this@PlayerActivity.showStatus(text)
            override fun onDurationProbed(url: String, durationMs: Long, mbit: Double) {
                checkHeavy()
            }
            override fun onProbeCompleted() {
                initializePlayer()
            }
            override fun reconnect(forcePlay: Boolean, startAt: Long) {
                this@PlayerActivity.reconnect(forcePlay = forcePlay, startAt = startAt)
            }
            override fun scheduleSeekWatchdog() {
                handler.postDelayed(seekWatchdogRunnable, SEEK_CONFIRM_TIMEOUT_MS)
            }
            override fun onSeekSettled(target: Long) {
                pendingSeekPos = null
                lastTime = target
                playbackPosition = target
            }
            override fun clearPendingSeekIfMatches(positionMs: Long) {
                if (pendingSeekPos == positionMs) pendingSeekPos = null
            }
        })
    }

    private val trackSelector: PlayerTrackSelector by lazy {
        PlayerTrackSelector(this, handler, object : PlayerTrackSelector.Callback {
            override fun onAudioTrackChanged() {
                supervisor.seeked()
                handler.removeCallbacks(reseekRunnable)
                handler.postDelayed(reseekRunnable, 120L)
            }
            override fun isPlayerAlive(p: MediaPlayer): Boolean = p === mediaPlayer
            override fun getCurrentTitle(): String = currentTitle
            override fun getCurrentPlayer(): MediaPlayer? = mediaPlayer
        })
    }

    private val reseekRunnable = Runnable { reseekHereNow() }

    private val pipController: PlayerPipController by lazy {
        PlayerPipController(this, videoLayout, object : PlayerPipController.Callback {
            override fun getCurrentPlayer(): MediaPlayer? = mediaPlayer
            override fun onPipModeChanged(inPip: Boolean) {
                applyMode()
                supervisor.surfaceChanged()
                if (inPip) setControlsVisible(false) else showControlsTemporarily()
                // The window size changed: let VLC re-measure (and recover if the surface was recreated meanwhile).
                handler.post { mediaPlayer?.updateVideoSurfaces() }
            }
        })
    }

    private val controlsOverlay: PlayerControlsOverlay by lazy {
        PlayerControlsOverlay(this, handler, object : PlayerControlsOverlay.Callback {
            override fun isPip(): Boolean = inPip
            override fun onControlsShown() {
                updateProgress(force = true)
                volumeBar.progress = audioSession.getStreamVolume()
            }
        })
    }

    private val diagnostics: PlayerDiagnostics by lazy {
        PlayerDiagnostics(this, handler, object : PlayerDiagnostics.Callback {
            override fun getCurrentUrl(): String = currentUrl
            override fun getCurrentPlayer(): MediaPlayer? = mediaPlayer
            override fun getCurrentMedia(): org.videolan.libvlc.interfaces.IMedia? = media
            override fun isUserPaused(): Boolean = userPaused
            override fun isBuffering(): Boolean = buffering
            override fun isNetworkLost(): Boolean = networkLost
            override fun isCatchingUp(): Boolean = catchingUp
            override fun isKeptOnStop(): Boolean = keptOnStop
            override fun getPerceivedPosition(): Long = perceivedPosition()
            override fun onReopenWithoutHardwareDecoder(startAt: Long) {
                reconnect(forcePlay = !userPaused, startAt = startAt)
            }
        })
    }

    private val watchProgress: PlayerWatchProgress by lazy {
        PlayerWatchProgress(this, object : PlayerWatchProgress.Callback {
            override fun getCurrentUrl(): String = currentUrl
            override fun getCurrentQueueIndex(): Int = playlistQueue.currentIndex
            override fun getQueueSize(): Int = playlistQueue.size
        })
    }

    private val playlistQueue: PlayerPlaylistQueue by lazy {
        PlayerPlaylistQueue(handler, object : PlayerPlaylistQueue.Callback {
            override fun isActivityAlive(): Boolean = alive && !isDestroyed && !isFinishing
            override fun getCurrentUrl(): String = currentUrl
            override fun onSaveCurrentPosition() {
                mediaPlayer?.let { persistPosition(timeOf(it).coerceAtLeast(0L), lengthOf(it)) }
            }
            override fun onPlayEntry(entry: PlayerPlaylistQueue.Entry, index: Int) {
                currentUrl = entry.url
                lastKnownLength = 0L
                lastKnownLengthUrl = ""
                currentTitle = entry.title
                playbackPosition = savedPosition()
                lastTime = playbackPosition
                ended = false
                userPaused = false
                playWhenReady = true
                retryCount = 0
                needsReload = false
                seekBar.progress = 0
                reconnect(forcePlay = true, startAt = playbackPosition)
            }
            override fun onQueueUiUpdated(
                hasMultiple: Boolean,
                hasPrevious: Boolean,
                hasNext: Boolean,
                displayText: String?,
                episodes: List<String>,
                index: Int
            ) {
                prevButton.visibility = if (hasPrevious) View.VISIBLE else View.GONE
                nextButton.visibility = if (hasNext) View.VISIBLE else View.GONE
                titleView.text = if (displayText != null) "$currentTitle  ·  $displayText" else currentTitle
                panel.title = currentTitle
                panel.episodes = episodes
                panel.index = index
            }
            override fun onNextEpisodeOffered() {
                ended = true
                playWhenReady = false
                root.keepScreenOn = true
                setPlayIcon(false)
                showControlsTemporarily()
            }
            override fun onNextCardDismissed() {
                if (ended) root.keepScreenOn = false
            }
            override fun showControlsTemporarily() {
                this@PlayerActivity.showControlsTemporarily()
            }
        })
    }

    private fun hideNextCard() = playlistQueue.hideNextCard()
    private fun offerNextEpisode(): Boolean = playlistQueue.offerNextEpisode()
    private fun playQueueEntry(index: Int) = playlistQueue.playEntry(index)
    private fun advanceToNext(): Boolean {
        if (!playlistQueue.hasNext) return false
        watchProgress.clearPosition()
        Toast.makeText(this, "Следующая серия", Toast.LENGTH_SHORT).show()
        return playlistQueue.advanceToNext()
    }
    private fun updateQueueUi() = playlistQueue.notifyUiUpdated()
    private fun fetchPlaylist(url: String) = playlistQueue.fetchPlaylist(url)

    private fun positionKey(): String? = watchProgress.positionKey()
    private fun savedPosition(): Long = watchProgress.savedPosition()
    private fun startPosition(i: Intent): Long = watchProgress.startPosition(i)
    private fun persistPosition(time: Long, length: Long) = watchProgress.persistPosition(time, length, ended)
    private fun maybePersist(time: Long) = watchProgress.maybePersist(time, lengthOf(mediaPlayer), ended)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ru.suslikovanora.media.net.Api.init(this)
        CrashReports.init(this)
        alive = true
        CrashReports.playerRunning(this, true) // still set at the next start = this screen died (native crash)
        BurstProxy.log = { AppLog.write(it) }
        diagnostics.onResume()
        TrafficMeter.init(this)
        sessionStartMb = BurstProxy.downloadedMb()
        BurstProxy.init(java.io.File(cacheDir, "stream")) { url, from, to, cancel ->
            // The shared reserve downloads through OkHttp here (same connection pool as the rest of the app).
            val call = ru.suslikovanora.media.net.Api.reserve.newCall(okhttp3.Request.Builder().url(url)
                .header("Range", "bytes=$from-" + if (to >= 0) "$to" else "").header("Accept-Encoding", "identity").build())
            cancel.attach { call.cancel() } // a request stuck in connect / TLS can be aborted from outside
            val res = call.execute()
            BurstProxy.UpstreamResponse(res.code, { n -> res.header(n) }, res.body?.byteStream())
        }
        // Dead sockets of a network that is gone (or a hung one) must not be reused by the repeated request.
        BurstProxy.resetConnections = {
            ru.suslikovanora.media.net.Api.plain.connectionPool.evictAll()
            ru.suslikovanora.media.net.Api.reserve.connectionPool.evictAll()
        }
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.also {
                it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        currentUrl = savedInstanceState?.getString(KEY_URL)?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra(EXTRA_URL).orEmpty()
        currentTitle = savedInstanceState?.getString(KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra(EXTRA_TITLE)?.ifBlank { "Видео" } ?: "Видео"
        playbackPosition = savedInstanceState?.getLong(KEY_POSITION) ?: startPosition(intent)
        playWhenReady = savedInstanceState?.getBoolean(KEY_PLAYING) ?: true
        userPaused = !playWhenReady
        readSideExtras(intent)
        playbackPosition = savedInstanceState?.getLong(KEY_POSITION) ?: startPosition(intent)
        scaleIndex = uiPrefs.getInt("scale", 0).coerceIn(0, SCALES.size - 1)

        videoLayout = VLCVideoLayout(this)
        root = FrameLayout(this).apply { setBackgroundColor(NIGHT) }
        // The film and everything drawn over it; below it (phone held upright) the info panel.
        stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(stage, FrameLayout.LayoutParams(-1, -1))
        stage.addView(videoLayout, FrameLayout.LayoutParams(-1, -1))

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = gradient(intArrayOf(0xB8000000.toInt(), 0x00000000), 0)
        }
        val back = iconButton(R.drawable.ic_pl_back, 44, 0x99151820.toInt(), 26).apply {
            setOnClickListener { leave() }
            contentDescription = "Назад"
        }
        topBar.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
        val title = TextView(this).apply {
            text = currentTitle
            setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1; setPadding(dp(14), 0, 0, 0)
        }
        titleView = title
        topBar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        fullButton = iconButton(R.drawable.ic_pl_full, 44, 0x99151820.toInt(), 24).apply {
            contentDescription = "На весь экран"
            setOnClickListener { toggleFullscreen() }
        }
        topBar.addView(fullButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(10) })
        stage.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        bufferingLabel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xB8222228.toInt()) }
            visibility = View.GONE
        }
        stage.addView(bufferingLabel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        seekLabel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xB8222228.toInt()) }
            visibility = View.GONE
        }
        stage.addView(seekLabel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(72) })

        val nextCard = playlistQueue.buildNextCard(this, ::dp, ::pillButton)
        stage.addView(nextCard, FrameLayout.LayoutParams(dp(320), -2, Gravity.END or Gravity.BOTTOM).apply { rightMargin = dp(24); bottomMargin = dp(110) })

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(18))
            background = gradient(intArrayOf(0x00000000, 0xD9000000.toInt()), 0)
        }

        val center = FrameLayout(this)
        playPauseButton = iconButton(if (playWhenReady) R.drawable.ic_pl_pause else R.drawable.ic_pl_play, 64, 0xF2FFFFFF.toInt(), 32, Color.BLACK).apply {
            contentDescription = "Пауза / воспроизведение"
            elevation = dp(3).toFloat()
            setOnClickListener {
                if (mediaPlayer?.isPlaying == true && !needsReload) doPause() else doPlay()
                showControlsTemporarily()
            }
        }
        center.addView(playPauseButton, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
        prevButton = iconButton(R.drawable.ic_pl_prev, 46, 0x99151820.toInt(), 24).apply {
            visibility = View.GONE
            contentDescription = "Предыдущая серия"
            setOnClickListener { playlistQueue.playPrevious() }
        }
        nextButton = iconButton(R.drawable.ic_pl_next, 46, 0x99151820.toInt(), 24).apply {
            visibility = View.GONE
            contentDescription = "Следующая серия"
            setOnClickListener { playlistQueue.playNext() }
        }
        center.addView(prevButton, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER).apply { rightMargin = dp(140) })
        center.addView(nextButton, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER).apply { leftMargin = dp(140) })
        controls.addView(center, LinearLayout.LayoutParams(-1, dp(82)))
        centerRow = center

        val seekRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        timeLabel = TextView(this).apply {
            text = "00:00  /  --:--"; setTextColor(0xFFECECF0.toInt()); textSize = 12f
            setPadding(dp(2), 0, 0, dp(2))
        }
        seekRow.addView(timeLabel)
        seekBar = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(0xFFE6B94A.toInt())
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0x66FFFFFF)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) { userSeeking = true; showControlsTemporarily() }
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val length = lengthOf(mediaPlayer)
                        val durationText = if (length > 0L) formatTime(length) else "--:--"
                        timeLabel.text = "${formatTime(progress.toLong())}  /  $durationText"
                    }
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    userSeeking = false
                    seekTo(seekBar.progress.toLong())
                    showControlsTemporarily()
                }
            })
        }
        seekRow.addView(seekBar)
        controls.addView(seekRow)

        val options = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val audioButton = pillButton("♫  Аудио").apply { setOnClickListener { trackSelector.showAudioTracks(this@PlayerActivity, mediaPlayer) { showControlsTemporarily() } } }
        val subsButton = pillButton("CC  Субтитры").apply { setOnClickListener { trackSelector.showSubtitleTracks(this@PlayerActivity, mediaPlayer) { showControlsTemporarily() } } }
        scaleButton = pillButton("⤢  ${SCALES[scaleIndex].second}").apply { setOnClickListener { cycleScale(); showControlsTemporarily() } }
        options.addView(audioButton, LinearLayout.LayoutParams(-2, dp(42)).apply { marginEnd = dp(8) })
        options.addView(subsButton, LinearLayout.LayoutParams(-2, dp(42)).apply { marginEnd = dp(8) })
        options.addView(scaleButton, LinearLayout.LayoutParams(-2, dp(42)).apply { marginEnd = dp(8) })
        options.addView(pillButton("🔒").apply { contentDescription = "Блокировка экрана"; setOnClickListener { lockScreen() } },
            LinearLayout.LayoutParams(dp(48), dp(42)).apply { marginEnd = dp(14) })
        if (Diag.on) options.addView(pillButton("📍").apply {
            contentDescription = "Метка в журнале"
            setOnClickListener { val n = Diag.mark("player at ${perceivedPosition() / 1000}s"); showStatus("Метка #$n"); showControlsTemporarily()
                handler.postDelayed({ if (!buffering && !networkLost) bufferingLabel.visibility = View.GONE }, 1500L) }
        }, LinearLayout.LayoutParams(dp(48), dp(42)).apply { marginEnd = dp(14) })
        options.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        volumeLabel = TextView(this).apply { text = "🔊"; textSize = 16f; setTextColor(Color.WHITE) }
        options.addView(volumeLabel, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
        // System media volume (the same as the hardware buttons and the right-edge swipe).
        volumeBar = SeekBar(this).apply {
            max = audioSession.getStreamMaxVolume()
            progress = audioSession.getStreamVolume()
            progressTintList = ColorStateList.valueOf(0xFFE6B94A.toInt())
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) { showControlsTemporarily() }
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) { setSystemVolume(progress); volumeLabel.text = if (progress == 0) "🔇" else "🔊" }
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) { showControlsTemporarily() }
            })
        }
        options.addView(volumeBar, LinearLayout.LayoutParams(dp(92), dp(36)))
        controls.addView(options)
        optionsRow = options
        stage.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        // The panel under the film (phone upright) does the same as the buttons over it.
        panel.title = currentTitle
        panel.scale = SCALES[scaleIndex].second
        panel.onAudio = { trackSelector.showAudioTracks(this, mediaPlayer) { showControlsTemporarily() } }
        panel.onSubs = { trackSelector.showSubtitleTracks(this, mediaPlayer) { showControlsTemporarily() } }
        panel.onScale = { cycleScale() }
        panel.onFull = { toggleFullscreen() }
        panel.onLock = { lockScreen() }
        panel.onEpisode = { i -> if (i != playlistQueue.currentIndex) playlistQueue.playEntry(i) }
        root.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol) root.post { applyMode() } }
        // The film's area changed size (small player ⇄ full screen, rotation): VLC must re-measure its picture for it,
        // otherwise it keeps the old (bigger) size and the picture spills past the area and is cut off.
        stage.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) stage.post { runCatching { mediaPlayer?.updateVideoSurfaces() } }
        }
        infoPanel = androidx.compose.ui.platform.ComposeView(this).apply {
            setContent { PlayerInfoPanel(panel) }
        }
        root.addView(infoPanel, FrameLayout.LayoutParams(-1, -1))
        controlsOverlay.attachViews(root, topBar, controls, seekLabel, ::pillButton)
        // Keep controls out of the camera cutout / system bars while the video stays full-bleed.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars())
            lastInsets = i
            applyMode()
            insets
        }
        setContentView(root)
        applyMode()
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (locked) showUnlockBadge() else leave() }
        })
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_OPEN, R.anim.player_in, 0)
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, R.anim.player_out)
        }
        else { @Suppress("DEPRECATION") overridePendingTransition(R.anim.player_in, 0) }
        if (savedInstanceState == null) playEnterAnimation()

        gestures = PlayerGestures(this, object : PlayerGestures.Callback {
            override fun getStageWidth(): Float = stage.width.toFloat()
            override fun getStageHeight(): Float = stage.height.toFloat()
            override fun isSeekable(): Boolean {
                val p = mediaPlayer ?: return false
                return lengthOf(p) > 0L || p.isSeekable
            }
            override fun getMediaLength(): Long = lengthOf(mediaPlayer)
            override fun getPerceivedPosition(): Long = perceivedPosition()
            override fun onSeekTo(position: Long) { seekTo(position) }
            override fun onTapSeek(forward: Boolean) { tapSeek(forward) }
            override fun onToggleControls() {
                if (controlsVisible) setControlsVisible(false) else showControlsTemporarily()
            }
            override fun onShowControlsTemporarily() { showControlsTemporarily() }
            override fun onScaleChanged(scaleIndex: Int) { setScale(scaleIndex) }
            override fun onShowSeekLabel(text: String) { showSeekLabel(text) }
            override fun getScreenBrightness(): Float = currentBrightness()
            override fun setScreenBrightness(brightness: Float) {
                window.attributes = window.attributes.also { it.screenBrightness = brightness }
            }
            override fun getStreamVolume(): Int = audioSession.getStreamVolume()
            override fun getStreamMaxVolume(): Int = audioSession.getStreamMaxVolume()
            override fun setStreamVolume(volume: Int) { audioSession.setStreamVolume(volume) }
            override fun onVolumeChanged(current: Int, max: Int) {
                volumeBar.progress = current
            }
            override fun dp(value: Int): Int = this@PlayerActivity.dp(value)
        })
        val touchListener = View.OnTouchListener { _, event -> gestures.onTouch(event) }
        stage.setOnTouchListener(touchListener)
        videoLayout.setOnTouchListener(touchListener)
        audioSession.setup()

        pipController.setup()
        showControlsTemporarily()
        intent.getStringExtra(EXTRA_PLAYLIST)?.takeIf { it.isNotBlank() }?.let { fetchPlaylist(it) }
    }



    private fun initializePlayer() {
        if (mediaPlayer != null) return
        if (bluRayController.probeFirst(currentUrl)) return // Blu-ray: its length first (comes back here)
        val url = currentUrl.takeIf { it.isNotBlank() } ?: run { finish(); return }
        try {
            // HW decoding first, VLC falls back to software by itself. Caching is set once, on LibVLC.
            val vlc = LibVLC(
                this,
                arrayListOf(
                    "--avcodec-hw=any",
                    // 6 s of buffer rides out short mobile-network dips (4 s was sometimes too little for 4K).
                    "--network-caching=6000",
                    "--no-video-title-show",
                    // stats stay on: the watchdog uses "displayed pictures" to notice a frozen picture.
                    "--no-sub-autodetect-file" // do not probe the HTTP server for sidecar subtitle files
                ).apply { if (Diag.vlcVerbose(this@PlayerActivity)) add("-vv") } // debug build: VLC's own log → logcat.log
            )
            libVlc = vlc
            teardownExec = Executors.newSingleThreadExecutor { r -> Thread(r, "vlc-teardown").apply { isDaemon = true } }
            val position = pendingSeekPos ?: playbackPosition
            pendingSeekPos = null
            val player = createPlayer(vlc, url, position)
            mediaPlayer = player
            audioSession.registerNoisyReceiver()
            networkMonitor.register()
            if (!userPaused && !ended) {
                audioSession.requestAudioFocus()
                player.play()
                // Opening itself can hang (bad network right at start): make sure something retries.
                handler.removeCallbacks(stallRunnable)
                handler.postDelayed(stallRunnable, openTimeout())
            }
            startWatchdog()
            showControlsTemporarily()
        } catch (e: Exception) {
            releasePlayer()
            Toast.makeText(this, "VLC не смог открыть поток: ${e.localizedMessage ?: "ошибка источника"}", Toast.LENGTH_LONG).show()
        }
    }

    /** New MediaPlayer on the given LibVLC, attached to the shared video layout, with its own event listener. */
    private fun createPlayer(vlc: LibVLC, url: String, positionMs: Long): MediaPlayer {
        catchUpNotBefore = SystemClock.elapsedRealtime() + 15_000L; catchUpFrom = positionMs + CATCH_UP_AFTER_PLAYED_MS
        diagnostics.onMediaOpened(positionMs)
        // A Blu-ray or regular stream reopened at a saved place: until the jump there the bar shows that place, not
        // VLC's 0:00, and the position is not saved over it (see tsPlaying / tsOpenLanded).
        if (bluRayController.onPlayerCreated(url, positionMs)) {
            pendingSeekPos = positionMs
        } else if (positionMs > 0L) {
            pendingSeekPos = positionMs
            handler.removeCallbacks(seekWatchdogRunnable)
            handler.postDelayed(seekWatchdogRunnable, SEEK_CONFIRM_TIMEOUT_MS)
        }
        val player = MediaPlayer(vlc)
        player.setEventListener(listenerFor(player))
        // enableSubtitles = true so selected subtitle tracks are rendered; SurfaceView is cheaper than TextureView.
        player.attachViews(videoLayout, null, true, false)
        hookSurfaces()
        runCatching { player.setVideoScale(shownScale()) }
        supervisor.opening(url)
        AppLog.write("open ${Uri.parse(url).lastPathSegment ?: "?"} at ${positionMs / 1000}s, reserve=${PlaybackSettings.burstMb(this)}MB, buffer=${PlaybackSettings.cacheMs(this)}ms, " +
            "network=${if (TrafficMeter.isMetered(this)) "mobile" else "wifi"}")
        val item = buildMedia(vlc, url, positionMs)
        media = item
        player.media = item
        return player
    }

    private fun runTeardown(block: () -> Unit) {
        val ex = teardownExec
        if (ex != null && !ex.isShutdown) ex.execute { runCatching(block) } else Thread { runCatching(block) }.start()
    }

    private fun startWatchdog() {
        lastWatchTime = -1L; watchStuckTicks = 0
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, WATCHDOG_TICK_MS)
    }

    /** Detach on the UI thread, do the (potentially blocking) native teardown off it, strictly in order. */
    private fun releasePlayer() {
        endCatchUp()
        audioSession.unregisterNoisyReceiver()
        networkMonitor.unregister()
        needsReload = false; retryCount = 0; reconnectScheduled = false
        handler.removeCallbacks(reconnectRunnable); handler.removeCallbacks(stallRunnable)
        handler.removeCallbacks(stablePlaybackRunnable)
        audioSession.abandonAudioFocus()
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(seekWatchdogRunnable)
        handler.removeCallbacks(applyTapSeekRunnable)
        lastKnownLength = 0L
        lastKnownLengthUrl = ""
        // A seek still in flight must survive the release (resume from where the user wanted to be).
        (tapSeekTarget ?: pendingSeekPos)?.let { playbackPosition = it; lastTime = it }
        tapSeekTarget = null; pendingSeekPos = null
        val player = mediaPlayer
        val item = media
        val vlc = libVlc
        val exec = teardownExec
        mediaPlayer = null; media = null; libVlc = null; teardownExec = null
        root.keepScreenOn = false
        buffering = false
        if (player == null && item == null && vlc == null) { exec?.shutdown(); return }
        player?.setEventListener(null)
        runCatching { player?.detachViews() }
        val job = Runnable {
            // Order matters: LibVLC must outlive every MediaPlayer/Media created on it, including the ones
            // replaced by earlier reconnects (those are queued on the same single-thread executor before this).
            runCatching { player?.stop() }
            runCatching { player?.release() }
            runCatching { item?.release() }
            runCatching { vlc?.release() }
        }
        if (exec != null && !exec.isShutdown) { exec.execute(job); exec.shutdown() } else Thread(job).start()
    }

    // Every stream goes through BurstProxy (see there): downloaded in bursts so the radio can sleep between them.
    private fun buildMedia(vlc: LibVLC, url: String, positionMs: Long): Media {
        // «Настройки» on the main screen: reserve size (0 = off) and the player's own buffer.
        BurstProxy.capBytes = PlaybackSettings.burstMb(this).toLong() shl 20
        return Media(vlc, Uri.parse(burst(url))).apply { configureMedia(url, positionMs) }
    }

    private fun Media.configureMedia(url: String, positionMs: Long) {
        setHWDecoderEnabled(diagnostics.isHardwareDecoderAllowed(url), false)
        addOption(":http-reconnect")
        addOption(":network-caching=${PlaybackSettings.cacheMs(this@PlayerActivity)}")
        // Only what this episode needs stays in the stream cache (the previous episode's files are dropped).
        BurstProxy.keepOnly(trackSelector.allEpisodeUrls(url))
        // Seek to the nearest keyframe: the picture appears right away instead of waiting (or freezing) on
        // hardware decoders after a jump into the middle of a GOP. ±10 s taps do not need frame accuracy.
        // Not with an external voice-over: it is a separate input that lands on the exact moment, while the video
        // jumped to its keyframe — the sound drifted after every seek. Then seeks are exact for both.
        if (!trackSelector.hasExternalAudio(url)) addOption(":input-fast-seek")
        // External voice-overs / subtitles are part of the media from the start. Added to the running player (as
        // before, on every "Playing" — so again after each pause and reconnect) they reopened inputs mid-playback:
        // a hiccup and a drifting voice-over.
        trackSelector.attachSidecars(this, url) { burst(it) }
        // Reliable resume over HTTP: seeking before play() is ignored by many streams.
        if (positionMs > 0L) addOption(":start-time=${positionMs / 1000.0}")
    }

    private fun burst(u: String) = BurstProxy.wrap(u)

    /**
     * Through the local reserve VLC only talks to the phone itself: when data stops coming, it is the reserve that
     * waits for the network and reconnects by itself. Tearing VLC down then (black screen, timer at 00:00, a new
     * start from the saved place) only made the wait longer — so with the reserve the player waits much longer
     * before it rebuilds itself, and does not rebuild at all on a Wi-Fi ↔ mobile switch.
     */
    private fun viaReserve() = BurstProxy.isActive(currentUrl)
    private fun stallTimeout() = if (viaReserve()) 35_000L else STALL_TIMEOUT_MS
    private fun openTimeout() = if (viaReserve()) 45_000L else OPEN_TIMEOUT_MS
    private fun stuckTicks() = if (viaReserve()) 15 else WATCHDOG_STUCK_TICKS

    private fun checkNoPicture(p: MediaPlayer) = diagnostics.checkNoPicture(p)
    private fun warnIfSoftwareDecoding(p: MediaPlayer) = diagnostics.warnIfSoftwareDecoding(p)
    private fun applyVideoFrameRate(owner: MediaPlayer) = diagnostics.applyVideoFrameRate(owner, videoLayout)
    private fun surfaceViews(v: View, out: MutableList<SurfaceView> = ArrayList()) = PlayerDiagnostics.surfaceViews(v, out)

    private fun showStatus(text: String) {
        bufferingLabel.text = text
        bufferingLabel.visibility = View.VISIBLE
    }

    /**
     * Re-opens the same URL at the last position on the existing LibVLC/views (no activity restart).
     * Used after Wi-Fi <-> mobile switches, network return, stalls, failed seeks and transient errors.
     */
    private fun reconnect(forcePlay: Boolean = false, startAt: Long? = null) {
        AppLog.write("reconnect at ${(startAt ?: perceivedPosition()) / 1000}s (retry $retryCount)")
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(stallRunnable)
        handler.removeCallbacks(seekWatchdogRunnable)
        reconnectScheduled = false
        val old = mediaPlayer ?: return
        val vlc = libVlc ?: return
        val url = currentUrl.takeIf { it.isNotBlank() } ?: return
        if (ended && !forcePlay) return
        // An explicit target (seek) must survive even when we cannot reload right now.
        if (startAt != null) { playbackPosition = startAt; lastTime = startAt; pendingSeekPos = null }
        if (networkLost && !forcePlay) { needsReload = true; return }
        // Paused by the user: do not start playback behind their back, reload on the next Play press.
        if (userPaused && !forcePlay) { needsReload = true; return }
        // Position to resume from: explicit target > unconfirmed seek > furthest confirmed time. lastTime follows
        // TimeChanged and is reset by seeks, so a backward seek is not undone by a stale larger value.
        val position = startAt ?: pendingSeekPos ?: maxOf(timeOf(old).coerceAtLeast(0L), lastTime)
        playbackPosition = position
        lastTime = position
        pendingSeekPos = null
        needsReload = false
        ended = false
        buffering = false
        trackSelector.prepareForReconnect()
        showStatus("Переподключение…")
        try {
            // A dead socket makes stop()/setMedia() block, so never reuse the old player: build a fresh
            // MediaPlayer on the same LibVLC/views and tear the old one down in the background (serially).
            val oldMedia = media
            old.setEventListener(null)
            runCatching { old.detachViews() }
            val player = createPlayer(vlc, url, position)
            mediaPlayer = player
            audioSession.requestAudioFocus()
            player.play()
            runTeardown {
                runCatching { old.stop() }
                runCatching { old.release() }
                runCatching { oldMedia?.release() }
            }
            lastWatchTime = -1L; watchStuckTicks = 0
            // The new connection may hang in "opening" without any event: arm the stall timer right away.
            handler.postDelayed(stallRunnable, openTimeout())
        } catch (e: Exception) {
            handleError()
        }
    }

    // ---- seeking ---------------------------------------------------------------------------
    /** Current position as the user perceives it (an unconfirmed seek counts as already done). */
    private fun perceivedPosition(): Long =
        tapSeekTarget ?: pendingSeekPos ?: (mediaPlayer?.let { timeOf(it) }?.takeIf { it > 0L } ?: lastTime).coerceAtLeast(0L)

    /** Double-tap / repeated tap: accumulates ±10 s steps, applies one real seek after a short pause. */
    private fun tapSeek(forward: Boolean) {
        val p = mediaPlayer ?: return
        if (!p.isSeekable && lengthOf(p) <= 0L && !hasSeekedOk) {
            showSeekLabel("Перемотка недоступна для этого потока")
            return
        }
        gestures.recordTapSeek()
        if (tapSeekTarget == null) tapSeekStart = perceivedPosition()
        val base = tapSeekTarget ?: tapSeekStart
        val length = lengthOf(p)
        var target = base + if (forward) 10_000L else -10_000L
        target = target.coerceAtLeast(0L)
        if (length > 0L) target = target.coerceAtMost((length - 1_000L).coerceAtLeast(0L))
        tapSeekTarget = target
        val diff = (target - tapSeekStart) / 1000
        showSeekLabel("${if (diff >= 0) "+" else "−"}${kotlin.math.abs(diff)} сек  ·  ${formatTime(target)}")
        handler.removeCallbacks(applyTapSeekRunnable)
        handler.postDelayed(applyTapSeekRunnable, TAP_SEEK_APPLY_MS)
        showControlsTemporarily()
    }

    private fun showSeekLabel(text: String) = controlsOverlay.showSeekLabel(text)

    private var hasSeekedOk = false

    /**
     * The one place that seeks. If the player is healthy it seeks in place and arms a watchdog; if the
     * stream is dead (no network, error state, not seekable after a connection change) or the seek is
     * never confirmed, the stream is reopened directly at the target position.
     */
    private fun seekTo(target: Long) {
        val p = mediaPlayer ?: return
        supervisor.seeked(); AppLog.write("seek to ${target / 1000}s")
        if (catchingUp) endCatchUp() // a jump: the gathered reserve belongs to the old place
        catchUpNotBefore = SystemClock.elapsedRealtime() + 15_000L; catchUpFrom = target.coerceAtLeast(0L) + CATCH_UP_AFTER_PLAYED_MS
        hideNextCard()
        val length = lengthOf(p)
        val t = target.coerceAtLeast(0L).let { if (length > 0L) it.coerceAtMost((length - 1_000L).coerceAtLeast(0L)) else it }
        playbackPosition = t
        lastTime = t
        handler.removeCallbacks(seekWatchdogRunnable)
        if (networkLost) { needsReload = true; pendingSeekPos = t; showStatus("Нет сети — ждём подключение…"); return }
        if (needsReload || ended) {
            pendingSeekPos = null
            if (!userPaused || ended) reconnect(forcePlay = true, startAt = t) else { playbackPosition = t; lastTime = t }
            return
        }
        if (!p.isSeekable) {
            // Seekable flag is unreliable right after (re)opening or after a network switch: reload at the target.
            if (length > 0L || hasSeekedOk) { reconnect(forcePlay = !userPaused, startAt = t) }
            else showSeekLabel("Перемотка недоступна для этого потока")
            return
        }
        pendingSeekPos = t
        when (bluRayController.seek(p, currentUrl, t, userPaused)) {
            PlayerBluRayController.SeekResult.REOPENING -> return
            PlayerBluRayController.SeekResult.NOT_TS -> p.setTime(t)
            PlayerBluRayController.SeekResult.HANDLED -> Unit
        }
        hasSeekedOk = true
        ended = false
        handler.postDelayed(seekWatchdogRunnable, SEEK_CONFIRM_TIMEOUT_MS)
        updateProgress(force = true)
        updateSession()
    }




    /**
     * A seek to the current moment (flushes LibVLC's 3-6 s network read-ahead buffer so a newly chosen
     * audio track is heard almost immediately).
     */
    private fun reseekHereNow() {
        val p = mediaPlayer ?: return
        if (userPaused || !p.isPlaying || buffering) return
        supervisor.seeked()
        if (bluRayController.isTsMode(p, currentUrl)) {
            val pos = p.position
            if (pos > 0.001f) p.position = pos
        } else {
            val t = p.time
            if (t > 0L) p.time = t
        }
    }
    private fun handleError(fromEof: Boolean = false) {
        root.keepScreenOn = false
        handler.removeCallbacks(stallRunnable)
        if (networkLost) {
            needsReload = true
            showStatus("Нет сети — ждём подключение…")
        } else if (retryCount < MAX_RETRIES || !fromEof) {
            // Keep trying while the user remains on the player. A restored network may need time
            // before the origin/CDN starts serving the stream again; use capped exponential backoff.
            retryCount = (retryCount + 1).coerceAtMost(MAX_RETRIES)
            val delay = minOf(1000L * (1L shl (retryCount - 1).coerceAtLeast(0)), MAX_RETRY_DELAY_MS)
            showStatus(if (retryCount <= 1) "Переподключение…" else "Повторное подключение…")
            if (reconnectScheduled) return
            reconnectScheduled = true
            handler.removeCallbacks(reconnectRunnable)
            handler.postDelayed(reconnectRunnable, delay)
        } else if (fromEof && lengthOf(mediaPlayer) <= 0L) {
            // Unknown duration (e.g. M2TS) and repeated EOF: this really is the end of the file.
            if (offerNextEpisode()) return
            ended = true
            playWhenReady = false
            bufferingLabel.visibility = View.GONE
            setPlayIcon(false)
            showControlsTemporarily()
        } else {
            bufferingLabel.visibility = View.GONE
            needsReload = true
            Toast.makeText(this, "Ошибка воспроизведения потока. Нажмите ▶ для повтора", Toast.LENGTH_LONG).show()
        }
    }

    /** UI is refreshed only from player events, only while controls are visible, at most once per second. */
    private fun updateProgress(force: Boolean) {
        val p = mediaPlayer ?: return
        if (userSeeking || inPip || !controlsVisible) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastUiUpdateAt < 1000L) return
        lastUiUpdateAt = now
        val length = lengthOf(p)
        // While a stream is (re)opening VLC reports 0: show where the film will continue instead of 00:00.
        // A seek in flight shows where it goes (VLC still reports the old place, or 0 while a Blu-ray reloads).
        val time = pendingSeekPos ?: timeOf(p).takeIf { it > 0L } ?: playbackPosition.coerceAtLeast(0L)
        // M2TS often has no duration: show --:-- and disable the duration-based seek bar.
        if (length > 0L) {
            val max = length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (seekBar.max != max) seekBar.max = max
            seekBar.progress = time.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            timeLabel.text = "${formatTime(time)}  /  ${formatTime(length)}"
            if (!seekBar.isEnabled) seekBar.isEnabled = true
        } else {
            if (seekBar.max != 1000) seekBar.max = 1000
            seekBar.progress = 0
            timeLabel.text = "${formatTime(time)}  /  --:--"
            if (seekBar.isEnabled) seekBar.isEnabled = false
        }
    }



    private fun setControlsVisible(visible: Boolean) = controlsOverlay.setVisible(visible)
    private fun showControlsTemporarily() = controlsOverlay.showTemporarily()

    /** Round button with a vector icon, centred exactly (text glyphs like ‹ Ⅱ ▶ sat off-centre and differed per phone font). */
    private fun iconButton(icon: Int, size: Int, bg: Int, iconSize: Int, tint: Int = Color.WHITE) = android.widget.ImageView(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(tint)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val pad = dp((size - iconSize) / 2)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg); if (bg ushr 24 < 0xF0) setStroke(dp(1), 0x26FFFFFF) }
        isClickable = true; isFocusable = true
        foreground = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null,
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
    }

    private fun setPlayIcon(playing: Boolean) {
        if (::playPauseButton.isInitialized) playPauseButton.setImageResource(if (playing) R.drawable.ic_pl_pause else R.drawable.ic_pl_play)
    }

    private fun pillButton(label: String) = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE); setPadding(dp(14), 0, dp(14), 0)
        background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(22).toFloat(); setColor(0xB8222228.toInt()); setStroke(dp(1), 0x44FFFFFF) }
        isClickable = true; isFocusable = true
    }

    private fun gradient(colors: IntArray, radius: Int) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun hideSystemUi() = controlsOverlay.hideSystemUi(window)



    // ---- a heavy film on mobile data: ask once ------------------------------------------------------------------
    private var sessionStartMb = 0.0
    private val heavyOk = HashSet<String>()
    private var heavyDialog: AlertDialog? = null

    /** Called when the film's bitrate is known (and again by the watchdog tick until it is): once per file. */
    private fun checkHeavy() {
        val limit = PlaybackSettings.heavyMbit(this)
        if (limit <= 0 || heavyDialog != null || isFinishing) return
        val mbit = BurstProxy.bitrateMbit(currentUrl) ?: bluRayController.getBitrateMbit(currentUrl) ?: return
        val name = Uri.parse(currentUrl).lastPathSegment ?: return
        if (mbit < limit || name in heavyOk || !TrafficMeter.isMetered(this)) return
        heavyOk += name
        val gbPerHour = mbit * 3600 / 8 / 1000
        AppLog.write("heavy file on mobile data: %.1f Mbit/s (limit %d), asking".format(mbit, limit))
        doPause()
        heavyDialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Тяжёлый файл на мобильной сети")
            .setMessage("Этот файл ≈ %.0f Мбит/с — около %.1f ГБ в час. Мобильный интернет и заряд уйдут быстро. ".format(mbit, gbPerHour) +
                "Лучше дождаться Wi-Fi или выбрать файл полегче.")
            .setPositiveButton("Всё равно смотреть") { _, _ -> heavyDialog = null; doPlay() }
            .setNeutralButton("Не спрашивать") { _, _ -> heavyDialog = null; PlaybackSettings.setHeavyMbit(this, 0); doPlay() }
            .setNegativeButton("Закрыть") { _, _ -> heavyDialog = null; leave() }
            .setOnCancelListener { heavyDialog = null; leave() }
            .show()
    }

    // ---- Blu-ray (.m2ts): length and time delegated to PlayerBluRayController ------------------
    private var lastKnownLength = 0L
    private var lastKnownLengthUrl = ""

    /** The film's length: VLC's, or for a transport stream the one read from its clock (0 = unknown). */
    private fun lengthOf(p: MediaPlayer?): Long {
        val len = bluRayController.lengthOf(p, currentUrl)
        if (len > 0L) {
            lastKnownLength = len
            lastKnownLengthUrl = currentUrl
            return len
        }
        return if (lastKnownLengthUrl == currentUrl) lastKnownLength else 0L
    }

    /** The film's time: VLC's, plus the offset when VLC counts a continued Blu-ray from 0. */
    private fun timeOf(p: MediaPlayer?): Long = bluRayController.timeOf(p, currentUrl)

    // ---- a network slower than the film: one longer wait instead of a stutter every few seconds ------------------
    // 12.10 journal (LTE 12 Mbit/s, film 13.2): the reserve stayed at 0 MB, VLC stopped for 3–6 s every 15–30 s, and
    // after most of these short waits the hardware decoder showed a still picture with the sound going on. Now, when
    // the reserve has run dry on such a network, the reserve holds the film back until ~30 s of it are downloaded
    // (at most 45 s) and then it plays on for minutes. VLC is not paused for this (a pause in the middle of its
    // buffering raised its delay to ~8 s and froze the picture — 12.10 second test): for VLC it is just a longer
    // buffering, and the supervisor sees an ordinary end of buffering afterwards.
    private var catchingUp = false
    private var catchUpStartedAt = 0L
    private var catchUpNotBefore = 0L // not right after an open / seek / unlock: VLC fills its buffer then anyway
    // ...and not before the film has really played a little at the new place: 12.10 fifth test, after a seek on a
    // 2–5 Mbit/s network VLC was still loading the new place 15 s later — the wait for a reserve then held it 45 s more.
    private var catchUpFrom = 0L // film time (ms)

    private fun maybeCatchUp(p: MediaPlayer) {
        if (catchingUp || !viaReserve() || networkLost || userPaused || ended || heavyDialog != null) return
        if (pendingSeekPos != null || timeOf(p) < catchUpFrom || p.time <= 0L || SystemClock.elapsedRealtime() < catchUpNotBefore) return
        if (!BurstProxy.slowerThanFilm(currentUrl) || (BurstProxy.aheadMb(currentUrl) ?: 99.0) >= 2.0) return
        catchingUp = true
        catchUpStartedAt = SystemClock.elapsedRealtime()
        AppLog.write("network slower than the film, reserve empty: holding the film to gather %.0f MB".format(BurstProxy.catchUpTargetMb(currentUrl)))
        handler.removeCallbacks(stallRunnable)
        BurstProxy.hold(currentUrl, CATCH_UP_MAX_MS + 5_000L)
        handler.removeCallbacks(catchUpRunnable)
        handler.post(catchUpRunnable)
    }

    private val catchUpRunnable = object : Runnable {
        override fun run() {
            if (!catchingUp) return
            if (mediaPlayer == null) { endCatchUp(); return }
            val target = BurstProxy.catchUpTargetMb(currentUrl)
            val ahead = BurstProxy.aheadMb(currentUrl) ?: 0.0
            if (ahead >= target || SystemClock.elapsedRealtime() - catchUpStartedAt >= CATCH_UP_MAX_MS) { endCatchUp(restart = true); return }
            // VLC may still play what it had in its own buffer: the message only while it really waits
            if (buffering) showStatus("Сеть медленнее фильма — копим запас ${(ahead * 100 / target.coerceAtLeast(1.0)).toInt().coerceIn(0, 99)}%")
            handler.postDelayed(this, 1_000L)
        }
    }

    /**
     * The reserve lets the film go on (gathered, waited long enough, or the viewer / the network decided otherwise).
     * [restart]: the reserve is there — start VLC's decoders anew at this moment. Let go as it was, VLC got the data
     * but showed no picture for ~5 s with the sound playing (12.10 third test, 4K film): after a long buffering the
     * hardware decoder lags behind the clock and VLC drops its pictures as late. A seek right here — from data that is
     * already on the phone — starts it clean; it is what the supervisor's fix does, only without the frozen seconds.
     */
    private fun endCatchUp(restart: Boolean = false) {
        if (!catchingUp) return
        catchingUp = false
        handler.removeCallbacks(catchUpRunnable)
        BurstProxy.hold(currentUrl, 0L)
        catchUpNotBefore = SystemClock.elapsedRealtime() + 20_000L
        AppLog.write("reserve gathered: %.0f MB in %d s".format(BurstProxy.aheadMb(currentUrl) ?: 0.0,
            (SystemClock.elapsedRealtime() - catchUpStartedAt) / 1000))
        if (mediaPlayer == null || userPaused) return
        val t = perceivedPosition()
        // Only with enough kept just behind: the jump lands on the last keyframe, a few seconds back. 12.10 fourth test,
        // 4K 57 Mbit/s: 34 MB behind was under 5 s, the keyframe was not on the phone any more — a new window, the
        // gathered 62 MB thrown away, VLC stepping back through the mirror.
        val behind = BurstProxy.keptBehindSec(currentUrl)
        if (restart && t > 0L && behind >= 8.0) { seekTo(t); return }
        if (restart) AppLog.write("restart skipped: only %.1f s kept behind".format(behind))
        // VLC is still buffering: its usual watch over a silent buffering starts again
        if (buffering) {
            handler.removeCallbacks(stallRunnable)
            handler.postDelayed(stallRunnable, stallTimeout())
        }
    }

    // ---- play / pause (button, headset, Bluetooth, PiP) ------------------------------------------
    private fun doPlay() {
        val p = mediaPlayer ?: return
        endCatchUp()
        userPaused = false
        supervisor.resumed(); AppLog.write("play")
        if (needsReload || ended) { reconnect(forcePlay = true); return }
        audioSession.onUserPlay()
        audioSession.requestAudioFocus()
        p.play()
    }

    private fun doPause() {
        endCatchUp()
        userPaused = true
        supervisor.paused(); AppLog.write("pause at ${perceivedPosition() / 1000}s")
        mediaPlayer?.pause()
    }

    // ---- extras from the library: sidecar subtitles and watch-progress keys --------------------
    private fun readSideExtras(i: Intent?) {
        trackSelector.readSideExtras(i)
        watchProgress.readIntent(i)
    }

    // ---- layout: upright phone = film on top + info below; sideways (or PiP) = the whole screen ----------------
    private fun isFull() = inPip || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyMode()
        supervisor.surfaceChanged()
    }

    /** Places the film and the panel for the current orientation (no activity restart: VLC keeps playing). */
    private fun applyMode() {
        if (!::infoPanel.isInitialized) return
        val full = isFull()
        val i = lastInsets
        val sp = stage.layoutParams as FrameLayout.LayoutParams
        val pp = infoPanel.layoutParams as FrameLayout.LayoutParams
        if (full) {
            sp.height = -1; sp.topMargin = 0
            videoLayout.layoutParams = (videoLayout.layoutParams as FrameLayout.LayoutParams).apply { width = -1; height = -1; gravity = Gravity.CENTER }
            infoPanel.visibility = View.GONE
            topBar.setPadding(dp(18) + i.left, dp(12) + i.top, dp(18) + i.right, dp(12))
            controls.setPadding(dp(20) + i.left, dp(12), dp(20) + i.right, dp(18) + i.bottom)
            optionsRow.visibility = View.VISIBLE
            centerRow.layoutParams = centerRow.layoutParams.apply { height = dp(82) }
            playPauseButton.scaleX = 1f; playPauseButton.scaleY = 1f
            playlistQueue.updateCardMargins(full = true)
            controlsOverlay.updateUnlockBadgeLayout(full = true, topInset = 0, filmHeight = 0)
            fullButton.setImageResource(R.drawable.ic_pl_full_exit); fullButton.contentDescription = "Выйти из полноэкранного режима"
            hideSystemUi()
        } else {
            val w = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val h = w * 9 / 16
            sp.height = h; sp.topMargin = i.top
            // VLC's own sizing (libVLC VideoHelper) swaps width and height when a wide area sits on a screen held
            // upright — it took the 16:9 strip for a tall 9:16 one: the picture came out small and cut, «Заполнить»
            // showed only its middle. A slightly-taller-than-wide video area avoids that swap; centred on the strip,
            // the fitted picture lands exactly in it (the extra above/below is empty and clipped).
            videoLayout.layoutParams = (videoLayout.layoutParams as FrameLayout.LayoutParams).apply { width = -1; height = w + dp(2); gravity = Gravity.CENTER }
            pp.topMargin = i.top + h
            panel.bottomInsetPx = i.bottom
            infoPanel.visibility = View.VISIBLE
            topBar.setPadding(dp(10) + i.left, dp(6), dp(10) + i.right, dp(6))
            controls.setPadding(dp(14) + i.left, dp(2), dp(14) + i.right, dp(6))
            optionsRow.visibility = View.GONE // the same buttons are in the panel below
            centerRow.layoutParams = centerRow.layoutParams.apply { height = dp(60) }
            playPauseButton.scaleX = 0.82f; playPauseButton.scaleY = 0.82f
            playlistQueue.updateCardMargins(full = false)
            controlsOverlay.updateUnlockBadgeLayout(full = false, topInset = i.top, filmHeight = h)
            fullButton.setImageResource(R.drawable.ic_pl_full); fullButton.contentDescription = "На весь экран"
            controlsOverlay.showSystemUi(window)
        }
        stage.layoutParams = sp; infoPanel.layoutParams = pp
        if (modeFull != full) {
            modeFull = full
            // The small player always shows the whole frame: «Заполнить» / «Растянуть» chosen for the full screen cropped
            // the picture past the small area's edges. Back on the full screen the chosen frame mode returns.
            runCatching { mediaPlayer?.setVideoScale(shownScale()) }
        }
    }

    private var modeFull: Boolean? = null
    private fun shownScale() = if (isFull()) SCALES[scaleIndex].first else MediaPlayer.ScaleType.SURFACE_BEST_FIT

    /** The button: upright → sideways full screen, and back. Turning the phone does the same by itself. */
    private fun toggleFullscreen() {
        if (inPip) return
        val goFull = !isFull()
        requestedOrientation = if (goFull) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        releaseOrientationWhenHeld(goFull)
        showControlsTemporarily()
    }

    /**
     * After the button forced an orientation, the phone's sensor takes over again once the phone is really held that
     * way — so turning it back later works as usual. With auto-rotate off in the system, the button's choice stays.
     * The sensor listens only during that short wait.
     */
    private var orientationWatch: android.view.OrientationEventListener? = null
    private fun releaseOrientationWhenHeld(landscape: Boolean) {
        orientationWatch?.disable(); orientationWatch = null
        val autoRotate = runCatching { android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 1 }.getOrDefault(false)
        if (!autoRotate) return
        val watch = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return
                val held = if (landscape) deg in 60..120 || deg in 240..300 else deg <= 25 || deg >= 335
                if (!held) return
                disable(); if (orientationWatch === this) orientationWatch = null
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
            }
        }
        if (watch.canDetectOrientation()) { orientationWatch = watch; watch.enable() }
    }

    /** Opening upright: the film slides down into place, the panel rises under it. */
    private fun playEnterAnimation() {
        if (isFull()) return
        val ease = android.view.animation.DecelerateInterpolator(2.2f)
        // Set before the first frame is drawn (no flash of the final layout); the animators start on the next frame.
        stage.translationY = -(resources.displayMetrics.widthPixels * 9f / 16f) * 0.35f
        stage.animate().translationY(0f).setDuration(380).setInterpolator(ease).start()
        infoPanel.alpha = 0f; infoPanel.translationY = dp(36).toFloat()
        infoPanel.animate().alpha(1f).translationY(0f).setStartDelay(90).setDuration(380).setInterpolator(ease).start()
    }

    /** Back / «‹»: the same in reverse, then the library fades back in. */
    private var leaving = false
    private fun leave() {
        if (leaving) return
        leaving = true
        if (isFull()) { finishQuietly(); return }
        val ease = android.view.animation.AccelerateInterpolator(1.6f)
        stage.animate().translationY(-stage.height * 0.35f).setDuration(240).setInterpolator(ease).start()
        infoPanel.animate().alpha(0f).translationY(dp(36).toFloat()).setDuration(240).setInterpolator(ease)
            .withEndAction { finishQuietly() }.start()
    }

    private fun finishQuietly() {
        finish()
        if (Build.VERSION.SDK_INT < 34) { @Suppress("DEPRECATION") overridePendingTransition(0, R.anim.player_out) }
    }

    // ---- screen lock against accidental touches ---------------------------------------------------------------
    private fun lockScreen() = controlsOverlay.lock()
    private fun unlockScreen() = controlsOverlay.unlock()
    private fun showUnlockBadge() = controlsOverlay.showUnlockBadge()

    // ---- frame mode ---------------------------------------------------------------------------
    private fun cycleScale() = setScale((scaleIndex + 1) % SCALES.size)

    private fun setScale(index: Int) {
        scaleIndex = index.coerceIn(0, SCALES.size - 1)
        uiPrefs.edit().putInt("scale", scaleIndex).apply()
        runCatching { mediaPlayer?.setVideoScale(shownScale()) }
        scaleButton.text = "⤢  ${SCALES[scaleIndex].second}"
        panel.scale = SCALES[scaleIndex].second
        showSeekLabel(if (isFull()) "Кадр: ${SCALES[scaleIndex].second}" else "Кадр на весь экран: ${SCALES[scaleIndex].second}")
    }

    private fun currentBrightness(): Float {
        val own = window.attributes.screenBrightness
        if (own >= 0f) return own
        return runCatching {
            android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f).coerceIn(0.01f, 1f)
    }

    private fun setSystemVolume(v: Int) {
        audioSession.setStreamVolume(v)
    }

    // ---- MediaSession: headset / Bluetooth buttons, PiP controls ---------------------------------
    /** State + position with its timestamp: the system extrapolates the position itself (no periodic updates). */
    private fun updateSession() {
        val p = mediaPlayer
        audioSession.update(
            title = titleView.text?.toString() ?: currentTitle,
            duration = lengthOf(p),
            position = perceivedPosition(),
            isPlaying = p?.isPlaying == true,
            isBuffering = buffering,
            isEnded = ended,
            hasPlayer = p != null,
            canSkipNext = playlistQueue.hasNext,
            canSkipPrevious = playlistQueue.hasPrevious
        )
    }

    // ---- Picture-in-Picture ----------------------------------------------------------------
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        pipController.onUserLeaveHint()
    }

    private fun updatePipParams(playing: Boolean) {
        pipController.updatePipParams(playing)
    }

    // ---- video surface recovery --------------------------------------------------------------
    // Entering/leaving PiP (and some launchers on Home) can destroy and recreate the video Surface while audio keeps
    // playing. libVLC does not restart its video output on the new surface by itself, which left a black picture.
    private val hookedSurfaces = HashSet<SurfaceView>()
    private var surfaceLost = false
    private val reattachRunnable = Runnable { reattachVideo() }
    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (!surfaceLost || videoParked) return
            surfaceLost = false
            handler.removeCallbacks(reattachRunnable)
            handler.postDelayed(reattachRunnable, 150L)
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
        override fun surfaceDestroyed(holder: SurfaceHolder) { if (mediaPlayer != null && !videoParked) surfaceLost = true }
    }

    private fun hookSurfaces() {
        for (sv in surfaceViews(videoLayout)) if (hookedSurfaces.add(sv)) sv.holder.addCallback(surfaceCallback)
    }

    private fun reattachVideo() {
        val p = mediaPlayer ?: return
        supervisor.surfaceChanged()
        runCatching { p.detachViews() }
        runCatching { p.attachViews(videoLayout, null, true, false) }
        hookSurfaces()
        // Restart the video output on the new surface; audio and position are not interrupted.
        runCatching { if (p.videoTracksCount > 0) { p.setVideoTrackEnabled(false); p.setVideoTrackEnabled(true) } }
        applyVideoFrameRate(p)
        if (!networkMonitor.isNetworkLost && !buffering) bufferingLabel.visibility = View.GONE
    }

    // ---- lifecycle -------------------------------------------------------------------------
    override fun onStart() {
        super.onStart()
        audioSession.setActive(true)
        handler.removeCallbacks(idleReleaseRunnable)
        val away = SystemClock.elapsedRealtime() - stoppedAt // elapsedRealtime: the phone sleeps while locked, uptime stops then
        when {
            keptOnStop && mediaPlayer != null && away < FAST_RESUME_MS && networkMonitor.activeNetwork() == networkAtStop -> resumeAfterStop()
            keptOnStop && mediaPlayer != null -> {
                // Away longer: the server / the phone's network has most likely dropped the paused connection, and
                // VLC waits on such a dead socket forever (the endless "0 %" / "Переподключение" loop). Start clean
                // from the same moment instead.
                keptOnStop = false; videoParked = false
                restartClean(play = playOnStart)
            }
            else -> { keptOnStop = false; initializePlayer() }
        }
    }

    // ---- screen locked / app switched: keep the player, just paused ------------------------------
    // Before, stopping the activity released VLC entirely; unlocking then had to start VLC again, reconnect through
    // the mirror and refill the 6 s buffer from the saved position — a long wait. Now the player only pauses: the
    // connection and what is already buffered stay, so the film continues at once. After a long absence
    // (IDLE_RELEASE_MS) everything is released after all — no resources held for a phone left in a drawer.
    private var keptOnStop = false
    private var playOnStart = false
    private val idleReleaseRunnable = Runnable {
        if (keptOnStop) { keptOnStop = false; videoParked = false; releasePlayer(); BurstProxy.clear() }
    }
    private var videoParked = false
    private var bufferingShown = 0 // highest buffering % of the current wait
    /**
     * Video back on the new surface. A hardware decoder can only show a picture from the next keyframe (often 5–10 s
     * away), so we jump to the current moment: the picture is decoded from the keyframe right there and appears at once.
     */
    private val videoBackRunnable = Runnable {
        val p = mediaPlayer ?: return@Runnable
        if (!videoParked) return@Runnable
        videoParked = false
        if (p.videoTracksCount <= 0) return@Runnable
        runCatching { p.setVideoTrackEnabled(true) }
        applyVideoFrameRate(p)
        val t = timeOf(p)
        if (t > 0L) seekTo(t)
    }

    private var stoppedAt = 0L
    private var networkAtStop: Network? = null // Wi-Fi <-> mobile while locked = the paused connection is dead

    /** Everything torn down (VLC too, in the background) and opened again at the same moment. */
    private fun restartClean(play: Boolean, resetRetries: Boolean = true) {
        val pos = perceivedPosition()
        releasePlayer()
        playbackPosition = pos; lastTime = pos; pendingSeekPos = null
        if (resetRetries) retryCount = 0
        reconnectScheduled = false; needsReload = false
        if (!play) userPaused = true
        initializePlayer()
    }

    private fun keepOnStop(p: MediaPlayer) {
        stoppedAt = SystemClock.elapsedRealtime()
        networkAtStop = networkMonitor.activeNetwork()
        endCatchUp()
        playOnStart = !userPaused && !ended && (p.isPlaying || playWhenReady || buffering)
        runCatching { p.pause() } // not doPause(): the user did not pause, the film continues on return
        supervisor.paused(); AppLog.write("screen off / app hidden: paused, keeping the player")
        keptOnStop = true
        // The screen's surface goes away: let go of it cleanly (video off, views detached) instead of letting the
        // video output die with it — that is what gave ~10 s of black / flicker after unlocking.
        videoParked = true
        runCatching { if (p.videoTracksCount > 0) p.setVideoTrackEnabled(false) }
        runCatching { p.detachViews() }
        handler.removeCallbacks(reattachRunnable); surfaceLost = false
        // Nothing may restart playback (or reconnect) behind the lock screen.
        trackSelector.stop()
        for (r in listOf(watchdogRunnable, stallRunnable, reconnectRunnable, seekWatchdogRunnable, reseekRunnable))
            handler.removeCallbacks(r)
        networkMonitor.unregister()
        needsReload = false; retryCount = 0; reconnectScheduled = false
        handler.removeCallbacks(reconnectRunnable); handler.removeCallbacks(stallRunnable)
        handler.removeCallbacks(stablePlaybackRunnable)
        audioSession.unregisterNoisyReceiver()
        audioSession.abandonAudioFocus()
        root.keepScreenOn = false
        handler.postDelayed(idleReleaseRunnable, IDLE_RELEASE_MS)
    }

    private fun resumeAfterStop() {
        val p = mediaPlayer ?: return
        catchUpNotBefore = SystemClock.elapsedRealtime() + 15_000L; catchUpFrom = timeOf(p) + CATCH_UP_AFTER_PLAYED_MS
        keptOnStop = false
        runCatching { p.attachViews(videoLayout, null, true, false) }
        hookSurfaces()
        handler.removeCallbacks(videoBackRunnable)
        handler.postDelayed(videoBackRunnable, 150L) // once the new surface exists
        audioSession.registerNoisyReceiver()
        networkMonitor.register()
        AppLog.write("back after ${(SystemClock.elapsedRealtime() - stoppedAt) / 1000}s: continue")
        if (playOnStart && !userPaused) {
            supervisor.resumed()
            audioSession.requestAudioFocus()
            runCatching { p.play() }
            // The connection may have been closed while paused: VLC reconnects by itself; if it hangs, the usual
            // stall recovery takes over.
            handler.removeCallbacks(stallRunnable)
            handler.postDelayed(stallRunnable, stallTimeout())
        }
        startWatchdog()
        showControlsTemporarily()
    }

    // Tapping "play" twice must not stack two players: the running one switches to the new stream.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() } ?: return
        if (url == currentUrl) return
        // Another film chosen while this one was parked behind the lock screen / in the background: wake the player
        // up first (receivers, video surface) without resuming the old film — the new one replaces it right below.
        if (keptOnStop) { playOnStart = false; handler.removeCallbacks(idleReleaseRunnable); resumeAfterStop() }
        mediaPlayer?.let { persistPosition(perceivedPosition(), lengthOf(it)) }
        currentUrl = url
        lastKnownLength = 0L
        lastKnownLengthUrl = ""
        currentTitle = intent.getStringExtra(EXTRA_TITLE)?.ifBlank { "Видео" } ?: "Видео"
        playlistQueue.reset()
        readSideExtras(intent)
        titleView.text = currentTitle
        trackSelector.resetSelectedTracks()
        playbackPosition = startPosition(intent); lastTime = playbackPosition
        pendingSeekPos = null; ended = false; userPaused = false; playWhenReady = true; retryCount = 0; needsReload = false
        if (mediaPlayer != null) reconnect(forcePlay = true, startAt = playbackPosition)
        intent.getStringExtra(EXTRA_PLAYLIST)?.takeIf { it.isNotBlank() }?.let { fetchPlaylist(it) }
    }

    override fun onDestroy() {
        alive = false
        heavyDialog?.let { runCatching { it.dismiss() } }; heavyDialog = null
        AppLog.write("player closed: %.0f MB fetched in this session (%s)".format(
            BurstProxy.downloadedMb() - sessionStartMb, if (TrafficMeter.isMetered(this)) "mobile" else "wifi"))
        TrafficMeter.flush(this)
        diagnostics.onDestroy()
        orientationWatch?.disable(); orientationWatch = null
        CrashReports.playerRunning(this, false)
        supervisor.closed()
        handler.removeCallbacksAndMessages(null)
        if (keptOnStop) { keptOnStop = false; releasePlayer() }
        if (isFinishing) BurstProxy.clear() // the film's cached part is no longer needed
        audioSession.release()
        controlsOverlay.destroy()
        for (sv in hookedSurfaces) runCatching { sv.holder.removeCallback(surfaceCallback) }
        hookedSurfaces.clear()
        super.onDestroy()
    }

    override fun onStop() {
        mediaPlayer?.let { p ->
            playbackPosition = perceivedPosition()
            persistPosition(playbackPosition, lengthOf(p))
        }
        // playWhenReady is maintained by events, so a mid-buffering stop no longer resumes paused.
        controlsOverlay.onStop()
        playlistQueue.stop()
        val p = mediaPlayer
        if (p != null && !isFinishing && !isChangingConfigurations) keepOnStop(p) else releasePlayer()
        audioSession.setActive(false)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_URL, currentUrl)
        outState.putString(KEY_TITLE, currentTitle)
        outState.putLong(KEY_POSITION, if (mediaPlayer != null) perceivedPosition() else playbackPosition)
        outState.putBoolean(KEY_PLAYING, !userPaused)
        super.onSaveInstanceState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isFull()) hideSystemUi()
    }

    private fun formatTime(ms: Long): String = PlayerGestures.formatTime(ms)

    companion object {
        /** This screen exists right now (CrashReports tells a crash from a normal close by it). */
        @Volatile var alive = false
        const val EXTRA_URL = "media_url"
        const val EXTRA_TITLE = "media_title"
        const val EXTRA_PLAYLIST = "media_playlist"
        const val EXTRA_FROM_START = "media_from_start"
        private const val KEY_URL = "current_url"
        private val NIGHT = 0xFF090B10.toInt()
        private const val KEY_TITLE = "current_title"
        private const val KEY_POSITION = "playback_position"
        private const val KEY_PLAYING = "play_when_ready"
        private const val STALL_TIMEOUT_MS = 12_000L
        private const val OPEN_TIMEOUT_MS = 20_000L
        private const val WATCHDOG_TICK_MS = 2_000L
        private const val FAST_RESUME_MS = 20_000L // back within this: just continue the paused player
        private const val CATCH_UP_MAX_MS = 45_000L // the longest pause to gather a reserve on a slow network
        private const val CATCH_UP_AFTER_PLAYED_MS = 5_000L // film played at a new place before a wait for a reserve
        private const val IDLE_RELEASE_MS = 10 * 60_000L // locked longer than this: free VLC and the connection
        const val EXTRA_SUB_URLS = "media_sub_urls"
        const val EXTRA_WATCH_KEYS = "media_watch_keys"
        const val EXTRA_AUDIO_URLS = "media_audio_urls"
        private val SCALES = listOf(
            MediaPlayer.ScaleType.SURFACE_BEST_FIT to "Вписать",
            MediaPlayer.ScaleType.SURFACE_FIT_SCREEN to "Заполнить",
            MediaPlayer.ScaleType.SURFACE_FILL to "Растянуть",
            MediaPlayer.ScaleType.SURFACE_16_9 to "16:9"
        )
        private const val WATCHDOG_STUCK_TICKS = 4 // 4 x 2 s = the same 8 s as before
        private const val SEEK_CONFIRM_TIMEOUT_MS = 9_000L
        private const val SEEK_CONFIRM_TOLERANCE_MS = 4_000L
        private const val TAP_SEEK_APPLY_MS = 350L
        private const val STABLE_PLAYBACK_MS = 30_000L
        private const val MAX_RETRY_DELAY_MS = 15_000L
        private const val MAX_RETRIES = 5
    }
}
