package ru.suslikovanora.media

import ru.suslikovanora.media.core.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.suslikovanora.media.player.PlayerAudioSession
import ru.suslikovanora.media.player.PlayerBluRayController
import ru.suslikovanora.media.player.PlayerControlsOverlay
import ru.suslikovanora.media.player.PlayerDiagnostics
import ru.suslikovanora.media.player.EngineListener
import ru.suslikovanora.media.player.EngineMedia
import ru.suslikovanora.media.player.PlayerEngine
import ru.suslikovanora.media.player.PlayerExtras
import ru.suslikovanora.media.player.PlayerGestures
import ru.suslikovanora.media.player.PlayerNetworkMonitor
import ru.suslikovanora.media.player.PlayerPipController
import ru.suslikovanora.media.player.PlayerOverlay
import ru.suslikovanora.media.player.PlayerPartySync
import ru.suslikovanora.media.party.Party
import ru.suslikovanora.media.player.PlayerPlaylistQueue
import ru.suslikovanora.media.player.PlayerTrackSelector
import ru.suslikovanora.media.player.PlayerUiState
import ru.suslikovanora.media.player.PlayerWatchProgress
import ru.suslikovanora.media.player.VideoScale
import ru.suslikovanora.media.player.EngineChoice
import ru.suslikovanora.media.player.EngineKind
import ru.suslikovanora.media.player.HybridEngine
import ru.suslikovanora.media.player.Media3Cache
import ru.suslikovanora.media.player.Media3Engine

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
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
import android.view.KeyEvent
import android.view.MotionEvent
import android.graphics.Rect
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class PlayerActivity : ComponentActivity() {
    /** The video engine (VLC or Media3, chosen per file — see [EngineChoice]), made in onCreate; [player] is it while a film is open. */
    private lateinit var engine: HybridEngine
    private val engineChoice by lazy { EngineChoice(this) }
    private var engineLoggedFor = "" // "<engine> <file>" last written to the journal (reconnects do not repeat it)
    private val player: PlayerEngine? get() = if (::engine.isInitialized) engine.takeIf { it.hasPlayer } else null
    private lateinit var root: FrameLayout
    private lateinit var stage: FrameLayout            // the film + everything over it
    private lateinit var infoPanel: android.view.View   // below the film while the phone is upright
    private var lastInsets = androidx.core.graphics.Insets.NONE
    private val panel = PlayerPanelState()
    private val videoLayout: View get() = engine.videoView
    private val ui = PlayerUiState()                    // the glass panel over the film (PlayerOverlay)
    private lateinit var overlay: androidx.compose.ui.platform.ComposeView
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
    private var buffering = false
    private var reconnectScheduled = false
    private var pendingSeekPos: Long? = null            // seek issued but not yet confirmed by TimeChanged
    private var tapSeekTarget: Long? = null             // accumulated double-tap target, applied after a short pause
    private var tapSeekStart = 0L
    private lateinit var seekLabel: TextView

    // 1.3: gestures, frame mode, media session, sidecar subtitles, remembered tracks, next-episode card, watch progress.
    private val uiPrefs by lazy { getSharedPreferences("player_ui", MODE_PRIVATE) }
    private var scaleIndex = 0
    private var currentUrl = ""
    private var currentTitle = tr("main.video")
    private var shownTitle = currentTitle // with «· 2/10» of a playlist

    // Network handling: reload the stream in place (same LibVLC/activity) when the network changes.
    private var needsReload = false
    private var userPaused = false // only set by the Play/Pause button; VLC's own Paused events must not count
    private var retryCount = 0
    private var lastTime = 0L
    private val networkLost: Boolean get() = networkMonitor.isNetworkLost

    private val networkMonitor by lazy {
        PlayerNetworkMonitor(this, object : PlayerNetworkMonitor.Callback {
            override fun hasPlayer(): Boolean = player != null
            // Media3 keeps 30–90 s loaded and retries its own requests on a new connection: like the reserve, a network
            // switch alone does not reopen the film (it would throw that buffer away).
            override fun isViaReserve(): Boolean = viaReserve() || player?.kind == EngineKind.MEDIA3
            // VLC that ran dry while offline stays stopped until reopened. Media3 only waits for data (its loader
            // retries, the reserve resumes by itself): reopening it re-reads the file's header from the start and
            // throws its buffer away (journal 18.10) — only after a real Media3 error (needsReload).
            override fun shouldReconnectViaReserve(wasLost: Boolean): Boolean =
                wasLost && (needsReload || (player?.kind != EngineKind.MEDIA3 && !ended && player?.isPlaying != true && !userPaused))
            override fun onScheduleReconnect(delayMs: Long) {
                retryCount = 0
                reconnectScheduled = true
                handler.removeCallbacks(reconnectRunnable)
                handler.postDelayed(reconnectRunnable, delayMs)
            }
            override fun onNetworkLost() {
                if (catchingUp) endCatchUp()
                handler.removeCallbacks(stallRunnable)
                if (!userPaused && !ended) showStatus(tr("player.net_seti_zhdem_podklyuchenie"))
            }
            override fun onNetworkRestoredWithoutReconnect() {
                if (!buffering) bufferingLabel.visibility = View.GONE
            }
        })
    }

    private val handler = Handler(Looper.getMainLooper())
    // Several quick reconnects in a row did not help: the VLC engine itself may be stuck on a dead connection, so
    // the next attempt starts it from scratch instead of building yet another player on it.
    private val reconnectRunnable = Runnable { if (retryCount >= 3 && player != null) restartClean(play = !userPaused, resetRetries = false) else reconnect() }
    // No buffering/open progress for a while: treat as a dead connection (goes through backoff).
    private val stallRunnable = Runnable { if (player != null && !userPaused && !ended) handleError() }
    private val applyTapSeekRunnable = Runnable {
        val t = tapSeekTarget ?: return@Runnable
        tapSeekTarget = null
        gestures.resetTapState()
        seekTo(t)
    }
    private var seekRedoneFor = -1L
    private var seekRedone = 0
    // A seek that VLC never confirms (dead socket after a Wi-Fi <-> mobile switch) is redone on a fresh connection.
    private val seekWatchdogRunnable: Runnable = Runnable {
        val target = pendingSeekPos ?: return@Runnable
        val p = player ?: return@Runnable
        if (bluRayController.onWatchdog(p, currentUrl, userPaused, target)) return@Runnable
        if (userPaused) {
            // User paused playback: do not wake up the device or re-seek in the background
            return@Runnable
        }
        val t = timeOf(p)
        if (p.isPlaying && !buffering && kotlin.math.abs(t - target) <= 25_000L) {
            // Stream is actively playing smoothly near the target (keyframe landed): confirm it now
            pendingSeekPos = null
            lastTime = t
            playbackPosition = t
            return@Runnable
        }
        if (kotlin.math.abs(t - target) > SEEK_CONFIRM_TOLERANCE_MS) {
            if (buffering || !p.isPlaying) {
                // The stream is still opening or buffering (e.g. slow network): give it another cycle
                handler.postDelayed(seekWatchdogRunnable, SEEK_CONFIRM_TIMEOUT_MS)
                return@Runnable
            }
            // One repeated seek per target, then a fresh connection: VLC playing on far from the target used to get the
            // same seek every 9 s for as long as it kept doing that.
            if (seekRedoneFor != target) { seekRedoneFor = target; seekRedone = 0 }
            if (p.isSeekable && retryCount == 0 && seekRedone++ == 0) {
                p.seekTo(target)
                handler.postDelayed(seekWatchdogRunnable, SEEK_CONFIRM_TIMEOUT_MS)
            } else {
                reconnect(forcePlay = !userPaused, startAt = target)
            }
        } else {
            pendingSeekPos = null
            lastTime = t
            playbackPosition = t
        }
    }
    // Playing but the clock does not move and no buffering is reported = frozen playback.
    // Runs only while VLC reports "playing" (restarted by the Playing event), so a paused player wakes nobody up.
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!p.isPlaying) return // paused: nobody wakes up (restarted by the Playing event)
            warnIfSoftwareDecoding(p) // once per file; the track may not be known yet at the first picture
            checkNoPicture(p)
            checkHeavy()
            // Network trouble is handled by the reconnect logic; the supervisor judges only real playback.
            if (!userPaused && !ended && !networkMonitor.isNetworkLost && !audioSession.isPausedForFocus) statsOf(p)?.let { supervisor.tick(it) }
            handler.postDelayed(this, WATCHDOG_TICK_MS)
        }
    }


    // ---- playback health: the supervisor shared with the PC app (core.PlaybackSupervisor) ---------------
    // One place decides when playback is broken and what to do — only while the film really plays, never right after
    // a seek / resume / reconnect, one gentle step at a time (video restart, sound fix) before any reopen.
    private val supervisor = PlaybackSupervisor(object : PlaybackSupervisor.Actions {
        override fun restartVideo() {
            val p = player ?: return
            runCatching { p.setVideoEnabled(false); p.setVideoEnabled(true) }
            val t = perceivedPosition()
            // new decoder from the keyframe right here: the picture comes back at once — unless that keyframe is no
            // longer kept on the phone (a heavy film after a long wait): then the seek would fetch it from the mirror
            // again, step by step; the new decoder picks up at the next keyframe instead
            val keyframeKept = !viaReserve() || BurstProxy.keptBehindSec(currentUrl) >= 8.0
            if (t > 0L && keyframeKept) seekTo(t)
        }
        override fun fixSound() {
            val p = player ?: return
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

    private fun statsOf(p: PlayerEngine): PlaybackSupervisor.Stats? {
        val st = p.stats() ?: return null
        val vt = p.videoTrack
        val fps = if (vt != null && vt.frameRateNum > 0 && vt.frameRateDen > 0) vt.frameRateNum.toDouble() / vt.frameRateDen else 0.0
        return PlaybackSupervisor.Stats(
            timeMs = p.timeMs, audioPlayed = st.playedAbuffers.toLong(), audioDecoded = st.decodedAudio.toLong(),
            pictures = st.displayedPictures.toLong(), expectedFps = fps,
            audioTracks = p.audioTracks().count { it.id != -1 }, audioSelected = p.audioTrack != -1,
            videoTracks = p.videoTrackCount, userMuted = p.volume == 0
        )
    }
    // Reset retry backoff only after the stream has played continuously, not on the first Playing event.
    private val stablePlaybackRunnable = Runnable { retryCount = 0 }

    // ---- engine events (main thread, only from the current player) ---------------------------
    private val engineListener = object : EngineListener {
        override fun playing() {
            val owner = player ?: return
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
        override fun paused() {
            playWhenReady = false
            root.keepScreenOn = false
            updatePipParams(playing = false)
            setPlayIcon(false)
            updateProgress(force = true)
            updateSession()
        }
        override fun stopped() { root.keepScreenOn = false }
        override fun tracksChanged() {
            trackSelector.onESAdded()
            // An open «Аудио» / «Субтитры» sheet shows the new list: a voice-over file picked as «＋ …» used to vanish
            // from it (the sheet was refreshed right after the tap, before Media3 had opened the file).
            handler.post { if (ui.picker == "audio" || ui.picker == "spu") refreshPicker() }
        }
        override fun endReached() {
            val owner = player ?: return
            // A dead network connection also shows up as EOF, so only a position near the known
            // duration is a real end; anything else is treated as a dropped stream and reloaded.
            val length = lengthOf(owner)
            val pos = maxOf(timeOf(owner), lastTime)
            if (length > 0L && pos >= length - 5_000L) {
                supervisor.ended()
                persistPosition(length, length) // watched to the end
                // A guest of the room does not go on by itself: the host's next episode comes through the room
                if (partyGuest() || !offerNextEpisode()) {
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
        override fun failed() = handleError()
        override fun videoOutput(count: Int) {
            val owner = player ?: return
            if (count > 0) { applyVideoFrameRate(owner); updatePipParams(playing = owner.isPlaying); warnIfSoftwareDecoding(owner) }
        }
        override fun buffering(percent: Float) {
            val owner = player ?: return
            supervisor.buffering(percent)
            if (catchingUp) {
                // the reserve holds the film back on purpose: its own label, no stall timer
                buffering = percent < 100f
            } else if (percent < 100f) {
                buffering = true
                // VLC refills its buffer from 0 after every jump (start at the saved place, seek, track switch,
                // an automatic reconnect): within one wait the number only goes up instead of falling back to 0.
                bufferingShown = maxOf(bufferingShown, percent.toInt())
                showStatus(
                    if (networkLost) tr("player.net_seti_zhdem_podklyuchenie")
                    else tr("player.buferizaciya_bufferingshown", "bufferingShown" to bufferingShown)
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
        override fun timeChanged() = timeOrLengthChanged(lengthChanged = false)
        override fun lengthChanged() = timeOrLengthChanged(lengthChanged = true)
        override fun unsupported(reason: String) = fallBackToVlc(reason)
    }

    /**
     * Media3 cannot play this file (codec, container, no index, decoder error): it goes to VLC + BurstProxy at the same
     * moment, and stays there next time (EngineChoice remembers). The reserve (BurstProxy) is the same for both engines:
     * what Media3 downloaded is kept.
     */
    private fun fallBackToVlc(reason: String) {
        if (engine.kind != EngineKind.MEDIA3 || player == null || currentUrl.isBlank()) return
        val at = perceivedPosition()
        AppLog.write("[engine] media3 → vlc at ${at / 1000}s: $reason")
        engineChoice.remember(currentUrl, EngineKind.VLC, reason)
        showStatus(tr("player.pereklyuchaem_na_vlc"))
        restartClean(play = !userPaused)
    }

    private fun timeOrLengthChanged(lengthChanged: Boolean) {
        val owner = player ?: return
        // The previous episode still plays while the next one's voice-overs are signed (onPlayEntry): its clock is not
        // the new episode's. 18.10 journal: «next» at 18 s of Ep.01 opened Ep.02 at 21 s and saved that as its place.
        if (sideJob?.isActive == true) return
        if (lengthChanged) { BurstProxy.setDuration(currentUrl, owner.lengthMs); checkHeavy() }
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
                    bluRayController.onSettled(currentUrl, t, owner.fraction)
                }
            } else if (kotlin.math.abs(t - pending) <= SEEK_CONFIRM_TOLERANCE_MS ||
                       (owner.isPlaying && !buffering && lastTime != t && kotlin.math.abs(t - pending) <= 25_000L)) {
                pendingSeekPos = null
                handler.removeCallbacks(seekWatchdogRunnable)
                lastTime = t
                playbackPosition = t
            }
        }
        updateProgress(force = false)
    }

    private val audioSession by lazy {
        PlayerAudioSession(this, object : PlayerAudioSession.Callback {
            override fun onMediaPlay() { userPlay() }
            override fun onMediaPause() { doPause() }
            override fun onSeekTo(pos: Long) { if (!guestBlocks("seek")) seekTo(pos) }
            override fun onSkipToNext() {
                if (playlistQueue.hasNext) userNext() else tapSeek(true)
            }
            override fun onSkipToPrevious() {
                if (playlistQueue.hasPrevious) userPrevious() else tapSeek(false)
            }
            override fun onFastForward() { tapSeek(true) }
            override fun onRewind() { tapSeek(false) }
            override fun isPlaying(): Boolean = player?.isPlaying == true
            override fun onTransientPause() { player?.pause() }
            override fun onFocusLossPause() { supervisor.paused(); player?.pause() }
            override fun onFocusGainResume() { supervisor.resumed(); player?.play() }
            override fun onBecomingNoisy() { player?.pause() }
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
                watchTrackSwitch("audio")
            }
            override fun onSubtitleTrackChanged() = watchTrackSwitch("subtitles")
            override fun isPlayerAlive(generation: Int): Boolean = player?.generation == generation
            override fun getCurrentTitle(): String = currentTitle
            override fun getCurrentPlayer(): PlayerEngine? = player
        })
    }

    private val reseekRunnable = Runnable { reseekHereNow() }

    // After a switch of the sound or subtitles the film could stay at «Буферизация 0%» until the viewer seeked
    // (16.10, report). A seek is what restarts the decoders, so if the wait does not move for a few seconds after a
    // switch, the player seeks itself — once per switch, to (almost) the same moment: ExoPlayer ignores a seek to
    // exactly its current millisecond.
    private var trackSwitchKind = ""
    private var trackSwitchAt = 0L
    private var trackSwitchShown = -1
    private fun watchTrackSwitch(kind: String) {
        trackSwitchKind = kind
        trackSwitchAt = SystemClock.uptimeMillis()
        trackSwitchShown = -1
        // Like a seek for the reserve: an active «копим запас» ends, and none starts while the switch refills the buffer.
        if (catchingUp) endCatchUp()
        catchUp.quiet(15_000L); catchUpFrom = perceivedPosition() + CATCH_UP_AFTER_PLAYED_MS
        AppLog.write("[track] $kind switched at ${perceivedPosition() / 1000}s" + (player?.diagLine()?.let { " · $it" } ?: ""))
        handler.removeCallbacks(trackSwitchRunnable)
        handler.postDelayed(trackSwitchRunnable, TRACK_SWITCH_CHECK_MS)
    }
    private val trackSwitchRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (userPaused || ended || networkLost || catchingUp || pendingSeekPos != null) return
            if (SystemClock.uptimeMillis() - trackSwitchAt > TRACK_SWITCH_WATCH_MS) return
            if (!buffering) { trackSwitchShown = -1; handler.postDelayed(this, TRACK_SWITCH_CHECK_MS); return }
            if (bufferingShown > trackSwitchShown) { trackSwitchShown = bufferingShown; handler.postDelayed(this, TRACK_SWITCH_CHECK_MS); return }
            val t = perceivedPosition()
            AppLog.write("[track] buffering stuck at $bufferingShown% after the $trackSwitchKind switch → seek to ${t / 1000}s" +
                (p.diagLine()?.let { " · $it" } ?: ""))
            trackSwitchAt = 0L
            if (t > 0L) seekTo(t - 1L)
        }
    }

    private val pipController: PlayerPipController by lazy {
        PlayerPipController(this, videoLayout, object : PlayerPipController.Callback {
            override fun getCurrentPlayer(): PlayerEngine? = player
            override fun onPipModeChanged(inPip: Boolean) {
                applyMode()
                supervisor.surfaceChanged()
                if (inPip) setControlsVisible(false) else showControlsTemporarily()
                // The window size changed: let VLC re-measure (and recover if the surface was recreated meanwhile).
                handler.post { player?.refreshVideoSize() }
            }
        })
    }

    private val controlsOverlay: PlayerControlsOverlay by lazy {
        PlayerControlsOverlay(this, handler, object : PlayerControlsOverlay.Callback {
            override fun isPip(): Boolean = inPip
            override fun onControlsShown() {
                updateProgress(force = true)
                ui.volume = audioSession.getStreamVolume()
                handler.removeCallbacks(progressTicker)
                handler.post(progressTicker)
            }
            override fun onControlsHidden() {
                handler.removeCallbacks(progressTicker)
            }
            override fun onPanelShown(shown: Boolean) {
                ui.shown = shown
                if (!shown) ui.picker = null
            }
            override fun canAutoHide(): Boolean = ui.picker == null
        })
    }

    private val diagnostics: PlayerDiagnostics by lazy {
        PlayerDiagnostics(this, handler, object : PlayerDiagnostics.Callback {
            override fun getCurrentUrl(): String = currentUrl
            override fun getCurrentPlayer(): PlayerEngine? = player
            override fun isUserPaused(): Boolean = userPaused
            override fun isBuffering(): Boolean = buffering
            override fun isNetworkLost(): Boolean = networkLost
            override fun isCatchingUp(): Boolean = catchingUp
            override fun isKeptOnStop(): Boolean = keptOnStop
            override fun getPerceivedPosition(): Long = perceivedPosition()
            override fun onReopenWithoutHardwareDecoder(startAt: Long) {
                // Media3 has no software video decoder: a picture the phone's decoder does not give is VLC's job.
                if (engine.kind == EngineKind.MEDIA3) fallBackToVlc("decoder gives no picture")
                else reconnect(forcePlay = !userPaused, startAt = startAt)
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

    private val scope = kotlinx.coroutines.MainScope()
    private var sideJob: kotlinx.coroutines.Job? = null

    private val playlistQueue: PlayerPlaylistQueue by lazy {
        PlayerPlaylistQueue(handler, object : PlayerPlaylistQueue.Callback {
            override fun isActivityAlive(): Boolean = alive && !isDestroyed && !isFinishing
            override fun getCurrentUrl(): String = currentUrl
            override fun onSaveCurrentPosition() {
                player?.let { persistPosition(timeOf(it).coerceAtLeast(0L), lengthOf(it)) }
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
                ui.time = 0L
                if (!trackSelector.needsSidecars(entry.url)) { reconnect(forcePlay = true, startAt = playbackPosition); return }
                // This episode's own voice-overs and subtitles are signed on the mirror first, then it opens with them.
                showStatus(tr("play.gotovim_dorozhki"))
                sideJob?.cancel()
                sideJob = scope.launch {
                    trackSelector.signSidecars(entry.url)
                    if (currentUrl == entry.url && alive) reconnect(forcePlay = true, startAt = playbackPosition)
                }
            }
            override fun onQueueUiUpdated(
                hasMultiple: Boolean,
                hasPrevious: Boolean,
                hasNext: Boolean,
                displayText: String?,
                episodes: List<String>,
                index: Int
            ) {
                ui.hasPrev = hasPrevious
                ui.hasNext = hasNext
                shownTitle = if (displayText != null) "$currentTitle  ·  $displayText" else currentTitle
                ui.title = shownTitle
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
            override fun onNextCountdown(title: String, seconds: Int) {
                ui.nextTitle = title
                ui.nextIn = seconds
            }
            override fun onNextCardDismissed() {
                ui.nextTitle = null
                if (ended) root.keepScreenOn = false
            }
            override fun showControlsTemporarily() {
                this@PlayerActivity.showControlsTemporarily()
            }
        })
    }

    // ---- shared viewing: the room's film plays here in step with the others (PlayerPartySync, core PartySyncEngine) ----
    private var seekStamp = 0L // every seek (the user's or the room's) changes it: the room tells its own from the user's

    private val syncPlayer = object : ru.suslikovanora.media.core.party.SyncPlayer {
        override val timeMs: Long get() = perceivedPosition()
        override val busy: Boolean get() = userSeeking || tapSeekTarget != null
        override val engineMs: Long get() = player?.let { timeOf(it) } ?: 0L
        override val seekStamp: Long get() = this@PlayerActivity.seekStamp
        override val paused: Boolean get() = userPaused || ended
        override val running: Boolean get() = player?.isPlaying == true && !buffering
        override val userRate: Float get() = 1f
        override fun applyPaused(paused: Boolean) { if (paused) doPause() else doPlay() }
        override fun seekTo(ms: Long) = this@PlayerActivity.seekTo(ms)
        override fun setRate(rate: Float) { runCatching { player?.setRate(rate) } }
    }

    private val partySync: PlayerPartySync by lazy {
        PlayerPartySync(handler, object : PlayerPartySync.Callback {
            override fun watchKey(): String? = watchProgress.currentWatchKey()
            override fun keys(): List<String> = watchProgress.keys
            override fun title(): String = currentTitle
            override fun ready(): Boolean = alive && !leaving && !keptOnStop && player != null && sideJob?.isActive != true
            override fun canPlayIndex(): Boolean = playlistQueue.size > 0
            override fun playIndex(i: Int) { if (i != playlistQueue.currentIndex) playlistQueue.playEntry(i) }
            override fun notice(text: String) = showSeekLabel(text)
            override fun onTick() { ui.live = partyGuest() }
        })
    }

    /** A guest of the room watching its film: pause / play, seeking and episodes are the host's. */
    private fun partyGuest(): Boolean = Party.isGuest && Party.file?.key == watchProgress.currentWatchKey()
    /** The host holds the pause (or the room waits for someone loading): a guest cannot lift it. */
    private fun partyPlayLocked(): Boolean = partyGuest() && Party.state.let { !it.playing || it.waiting }
    private fun guestBlocks(what: String): Boolean {
        if (!partyGuest()) return false
        showSeekLabel(when (what) {
            "seek" -> tr("party.peremativaet_sozdatel")
            "episode" -> tr("party.seriyu_vybiraet_sozdatel")
            else -> tr("party.pauzu_snimaet_sozdatel")
        })
        return true
    }
    private fun userPlay() { if (!(partyPlayLocked() && guestBlocks("play"))) doPlay() }
    private fun userNext() { if (!guestBlocks("episode")) playlistQueue.playNext() }
    private fun userPrevious() { if (!guestBlocks("episode")) playlistQueue.playPrevious() }

    private fun hideNextCard() = playlistQueue.hideNextCard()
    private fun offerNextEpisode(): Boolean = playlistQueue.offerNextEpisode()
    private fun fetchPlaylist(url: String) = playlistQueue.fetchPlaylist(url)

    private fun positionKey(): String? = watchProgress.positionKey()
    private fun savedPosition(): Long = watchProgress.savedPosition()
    private fun startPosition(i: Intent): Long = watchProgress.startPosition(i)
    private fun persistPosition(time: Long, length: Long) = watchProgress.persistPosition(time, length, ended)
    private fun maybePersist(time: Long) = watchProgress.maybePersist(time, lengthOf(player), ended)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ru.suslikovanora.media.net.Api.init(this)
        CrashReports.init(this)
        alive = true
        CrashReports.playerRunning(this, true) // still set at the next start = this screen died (native crash)
        BurstProxy.log = { AppLog.write(it) }
        diagnostics.onResume()
        TrafficMeter.init(this)
        sessionStartMb = BurstProxy.downloadedMb() + Media3Engine.downloaded.get() / 1048576.0
        BurstProxy.init(java.io.File(cacheDir, "stream")) { url, from, to, cancel ->
            // The shared reserve downloads through OkHttp here (same connection pool as the rest of the app).
            val call = ru.suslikovanora.media.net.Api.reserve.newCall(okhttp3.Request.Builder().url(url)
                .header("Range", "bytes=$from-" + if (to >= 0) "$to" else "").header("Accept-Encoding", "identity").build())
            cancel.attach { call.cancel() } // a request stuck in connect / TLS can be aborted from outside
            val res = call.execute()
            BurstProxy.UpstreamResponse(res.code, { n -> res.header(n) }, res.body?.byteStream())
        }
        // The mirror's signature lives 12 hours: a film paused overnight gets a new address instead of 401 forever.
        BurstProxy.refreshUrl = { old ->
            MirrorLink.renewPath(old)?.let { path ->
                kotlinx.coroutines.runBlocking { ru.suslikovanora.media.net.Api.get(path) }.optString("url").ifBlank { null }
            }
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
            ?: intent.getStringExtra(EXTRA_TITLE)?.ifBlank { tr("main.video") } ?: tr("main.video")
        // The library's watch keys first: the saved place is kept under them (read before, it was looked up by the
        // mirror address — nothing there — and every film started from 0).
        readSideExtras(intent)
        playbackPosition = savedInstanceState?.getLong(KEY_POSITION) ?: startPosition(intent)
        playWhenReady = savedInstanceState?.getBoolean(KEY_PLAYING) ?: true
        userPaused = !playWhenReady
        scaleIndex = uiPrefs.getInt("scale", 0).coerceIn(0, SCALES.size - 1)

        engine = HybridEngine(this).also { it.listener = engineListener }
        root = FrameLayout(this).apply { setBackgroundColor(NIGHT) }
        // The film and everything drawn over it; below it (phone held upright) the info panel.
        stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(stage, FrameLayout.LayoutParams(-1, -1))
        stage.addView(videoLayout, FrameLayout.LayoutParams(-1, -1))

        bufferingLabel = glassLabel(13f).apply { setPadding(dp(16), dp(8), dp(16), dp(8)) }
        stage.addView(bufferingLabel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        seekLabel = glassLabel(17f).apply { setPadding(dp(18), dp(10), dp(18), dp(10)) }
        stage.addView(seekLabel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(72) })

        // The buttons over the film: one Compose layer in the Windows player's look. Where it has no button the
        // touch is not taken and falls through to the stage (taps, double taps, swipes — PlayerGestures).
        ui.title = currentTitle
        ui.playing = playWhenReady
        ui.tv = tv
        ui.showLock = !tv // the lock is against touches: nothing to lock on a TV
        ui.showMark = Diag.on
        ui.scales = SCALES.map { it.second }
        ui.scaleIndex = scaleIndex
        ui.volumeMax = audioSession.getStreamMaxVolume()
        ui.volume = audioSession.getStreamVolume()
        ui.onBack = { leave() }
        ui.onPlayPause = { togglePlay() }
        ui.onSeekBy = { d -> tapSeek(d > 0) }
        ui.onScrub = { t -> userSeeking = t != null; showControlsTemporarily() }
        ui.onSeekTo = { t ->
            userSeeking = false
            gestures.resetTapState()
            if (!guestBlocks("seek")) { ui.time = t; seekTo(t) }
            showControlsTemporarily()
        }
        ui.onPrev = { userPrevious() }
        ui.onNext = { userNext() }
        ui.onFull = { toggleFullscreen() }
        ui.onLock = { ui.picker = null; lockScreen() }
        ui.onMark = {
            val n = Diag.mark("player at ${perceivedPosition() / 1000}s"); showStatus(tr("player.metka_n", "n" to n)); showControlsTemporarily()
            handler.postDelayed({ if (!buffering && !networkLost) bufferingLabel.visibility = View.GONE }, 1500L)
        }
        ui.onPicker = { kind -> openPicker(kind) }
        ui.onPick = { kind, i ->
            if (kind == "audio") trackSelector.pickAudio(player, i) else trackSelector.pickSubtitle(player, i)
            refreshPicker()
            showControlsTemporarily()
        }
        ui.onScale = { i -> setScale(i); showControlsTemporarily() }
        ui.onVolume = { v -> setSystemVolume(v); ui.volume = v; controlsOverlay.keepShown() }
        ui.onNextNow = { playlistQueue.hideNextCard(); userNext() }
        ui.onNextCancel = { playlistQueue.hideNextCard(); showControlsTemporarily() }
        ui.onPoke = { controlsOverlay.keepShown() }
        overlay = androidx.compose.ui.platform.ComposeView(this).apply {
            setContent { PlayerOverlay(ui) }
        }
        stage.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        // The panel under the film (phone upright) does the same as the buttons over it.
        panel.title = currentTitle
        panel.scale = SCALES[scaleIndex].second
        panel.ui = ui
        panel.onAudio = { openPicker(if (ui.picker == "audio") null else "audio") }
        panel.onSubs = { openPicker(if (ui.picker == "spu") null else "spu") }
        panel.onScale = { cycleScale() }
        panel.onFull = { toggleFullscreen() }
        panel.onLock = { lockScreen() }
        panel.onEpisode = { i -> if (i != playlistQueue.currentIndex && !guestBlocks("episode")) playlistQueue.playEntry(i) }
        root.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol) root.post { applyMode() } }
        // The film's area changed size (small player ⇄ full screen, rotation): VLC must re-measure its picture for it,
        // otherwise it keeps the old (bigger) size and the picture spills past the area and is cut off.
        stage.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) stage.post { runCatching { player?.refreshVideoSize() } }
        }
        infoPanel = androidx.compose.ui.platform.ComposeView(this).apply {
            setContent { PlayerInfoPanel(panel) }
        }
        root.addView(infoPanel, FrameLayout.LayoutParams(-1, -1))
        controlsOverlay.attachViews(root, seekLabel, ::pillButton)
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
            override fun handleOnBackPressed() {
                when {
                    locked -> showUnlockBadge()
                    ui.picker != null -> openPicker(null)
                    tv && controlsOverlay.isVisible -> setControlsVisible(false) // TV: Back first hides the panel
                    else -> leave()
                }
            }
        })
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_OPEN, R.anim.player_in, 0)
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, R.anim.player_out)
        }
        else { @Suppress("DEPRECATION") overridePendingTransition(R.anim.player_in, 0) }
        if (savedInstanceState == null) playEnterAnimation()

        gestures = PlayerGestures(this, object : PlayerGestures.Callback {
            override fun areControlsVisible(): Boolean = controlsOverlay.isVisible
            override fun getStageWidth(): Float = stage.width.toFloat()
            override fun getStageHeight(): Float = stage.height.toFloat()
            override fun isSeekable(): Boolean {
                val p = player ?: return false
                if (partyGuest()) return false // a guest's swipe does not seek: the host seeks for the room
                return lengthOf(p) > 0L || p.isSeekable
            }
            override fun getMediaLength(): Long = lengthOf(player)
            override fun getPerceivedPosition(): Long = perceivedPosition()
            override fun onSeekTo(position: Long) { seekTo(position) }
            override fun onTapSeek(forward: Boolean) { tapSeek(forward) }
            override fun onToggleControls() {
                gestures.resetTapState()
                controlsOverlay.toggle()
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
                ui.volume = current
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
        partySync.start(syncPlayer)
    }



    private fun initializePlayer() {
        if (player != null) return
        if (bluRayController.probeFirst(currentUrl)) return // Blu-ray: its length first (comes back here)
        val url = currentUrl.takeIf { it.isNotBlank() } ?: run { finish(); return }
        try {
            val position = pendingSeekPos ?: playbackPosition
            pendingSeekPos = null
            val p = createPlayer(url, position)
            audioSession.registerNoisyReceiver()
            networkMonitor.register()
            if (!userPaused && !ended) {
                audioSession.requestAudioFocus()
                p.play()
                // Opening itself can hang (bad network right at start): make sure something retries.
                handler.removeCallbacks(stallRunnable)
                handler.postDelayed(stallRunnable, openTimeout())
            }
            startWatchdog()
            showControlsTemporarily()
        } catch (e: Exception) {
            releasePlayer()
            Toast.makeText(this, tr("player.vlc_ne_smog_otkryt", "p" to (e.localizedMessage ?: tr("player.oshibka_istochnika"))), Toast.LENGTH_LONG).show()
        }
    }

    /** Opens [url] in a new player of the engine (the previous one, if any, is replaced and torn down by it). */
    private fun createPlayer(url: String, positionMs: Long): PlayerEngine {
        catchUp.quiet(15_000L); catchUpFrom = positionMs + CATCH_UP_AFTER_PLAYED_MS
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
        supervisor.opening(url)
        val pick = engineChoice.choose(url)
        val name = Uri.parse(url).lastPathSegment ?: "?"
        if (engineLoggedFor != "${pick.kind} $url") {
            engineLoggedFor = "${pick.kind} $url"
            AppLog.write("[engine] ${pick.kind.tag} for $name: ${pick.reason}")
        }
        engine.use(pick.kind)
        AppLog.write("open $name in ${pick.kind.tag} at ${positionMs / 1000}s, reserve=${PlaybackSettings.burstMb(this)}MB, buffer=${PlaybackSettings.cacheMs(this)}ms, " +
            "network=${if (TrafficMeter.isMetered(this)) "mobile" else "wifi"}")
        engine.open(buildMedia(url, positionMs), shownScale())
        hookSurfaces()
        return engine
    }

    private fun startWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, WATCHDOG_TICK_MS)
    }

    /** Detach on the UI thread, the engine does the (potentially blocking) native teardown off it, strictly in order. */
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
        root.keepScreenOn = false
        buffering = false
        if (::engine.isInitialized) engine.release()
    }

    /**
     * Every stream goes through BurstProxy (see there), for VLC and Media3 alike: downloaded in bursts so the radio
     * can sleep between them, the reserve sized by how much faster the network is than the film.
     */
    private fun buildMedia(url: String, positionMs: Long): EngineMedia {
        // «Настройки» on the main screen: reserve size (0 = off) and the player's own buffer.
        val reserveBytes = PlaybackSettings.burstMb(this).toLong() shl 20
        val episode = trackSelector.allEpisodeUrls(url)
        // Both engines read through the same reserve (BurstProxy): it downloads in bursts sized to the network and the
        // film. A file switched from Media3 to VLC keeps what was already downloaded.
        if (!media3Swept) { media3Swept = true; Thread { Media3Cache.clearAll(applicationContext) }.start() } // Media3's own disk cache of the first 2.2.1 builds
        BurstProxy.capBytes = reserveBytes
        val main = burst(url)
        // Only what this episode needs stays in the stream cache (the previous episode's files are dropped).
        BurstProxy.keepOnly(episode)
        val sidecars = trackSelector.sidecarsFor(url) { burst(it) }
        return EngineMedia(
            url = main,
            startMs = positionMs,
            hardwareDecoding = diagnostics.isHardwareDecoderAllowed(url),
            cacheMs = PlaybackSettings.cacheMs(this),
            // Not with an external voice-over: it is a separate input that lands on the exact moment, while the video
            // jumped to its keyframe — the sound drifted after every seek. Then seeks are exact for both.
            fastSeek = !trackSelector.hasExternalAudio(url),
            audioFiles = sidecars.audio,
            subtitleFiles = sidecars.subtitles
        )
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

    private fun checkNoPicture(p: PlayerEngine) = diagnostics.checkNoPicture(p)
    private fun warnIfSoftwareDecoding(p: PlayerEngine) = diagnostics.warnIfSoftwareDecoding(p)
    private fun applyVideoFrameRate(owner: PlayerEngine) = diagnostics.applyVideoFrameRate(owner, videoLayout)
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
        val old = player ?: return
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
        showStatus(tr("player.perepodklyuchenie"))
        try {
            // A dead socket makes stop()/setMedia() block, so the engine never reuses the old player: a fresh one
            // on the same views, the old one torn down in the background (serially) once the new one plays.
            val p = createPlayer(url, position)
            audioSession.requestAudioFocus()
            p.play()
                // The new connection may hang in "opening" without any event: arm the stall timer right away.
            handler.postDelayed(stallRunnable, openTimeout())
        } catch (e: Exception) {
            handleError()
        }
    }

    // ---- seeking ---------------------------------------------------------------------------
    /** Current position as the user perceives it (an unconfirmed seek counts as already done). */
    private fun perceivedPosition(): Long =
        tapSeekTarget ?: pendingSeekPos ?: (player?.let { timeOf(it) }?.takeIf { it > 0L } ?: lastTime).coerceAtLeast(0L)

    /** Double-tap / repeated tap: accumulates ±10 s steps, applies one real seek after a short pause. */
    private fun tapSeek(forward: Boolean) {
        val p = player ?: return
        if (guestBlocks("seek")) return
        if (!p.isSeekable && lengthOf(p) <= 0L && !hasSeekedOk) {
            showSeekLabel(tr("player.peremotka_nedostupna_dlya_etog"))
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
        showSeekLabel(tr("player.p_p_sek_p", "sign" to (if (diff >= 0) "+" else "−"), "sec" to kotlin.math.abs(diff), "time" to formatTime(target)))
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
        val p = player ?: return
        seekStamp++
        gestures.resetTapState()
        supervisor.seeked(); AppLog.write("seek to ${target / 1000}s")
        if (catchingUp) endCatchUp() // a jump: the gathered reserve belongs to the old place
        catchUp.quiet(15_000L); catchUpFrom = target.coerceAtLeast(0L) + CATCH_UP_AFTER_PLAYED_MS
        hideNextCard()
        val length = lengthOf(p)
        val t = target.coerceAtLeast(0L).let { if (length > 0L) it.coerceAtMost((length - 1_000L).coerceAtLeast(0L)) else it }
        playbackPosition = t
        lastTime = t
        handler.removeCallbacks(seekWatchdogRunnable)
        // A seek restarts the decoders itself: the wait after it is the new place loading, not a stuck track switch
        // (18.10 journal: a far seek 5 s after switching to DTS got a second seek of its own while the data came in).
        handler.removeCallbacks(trackSwitchRunnable); trackSwitchAt = 0L
        if (networkLost) { needsReload = true; pendingSeekPos = t; showStatus(tr("player.net_seti_zhdem_podklyuchenie")); return }
        if (needsReload || ended) {
            pendingSeekPos = null
            if (!userPaused || ended) reconnect(forcePlay = true, startAt = t) else { playbackPosition = t; lastTime = t }
            return
        }
        if (!p.isSeekable) {
            // Seekable flag is unreliable right after (re)opening or after a network switch: reload at the target.
            if (length > 0L || hasSeekedOk) { reconnect(forcePlay = !userPaused, startAt = t) }
            else showSeekLabel(tr("player.peremotka_nedostupna_dlya_etog"))
            return
        }
        pendingSeekPos = t
        when (bluRayController.seek(p, currentUrl, t, userPaused)) {
            PlayerBluRayController.SeekResult.REOPENING -> return
            PlayerBluRayController.SeekResult.NOT_TS -> p.seekTo(t)
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
        val p = player ?: return
        if (p.kind == EngineKind.MEDIA3) return // Media3 switches the sound itself, without VLC's read-ahead to flush
        if (userPaused || !p.isPlaying || buffering) return
        supervisor.seeked()
        if (bluRayController.isTsMode(p, currentUrl)) {
            val pos = p.fraction
            if (pos > 0.001f) p.seekToFraction(pos)
        } else {
            val t = p.timeMs
            if (t > 0L) p.seekTo(t)
        }
    }
    private fun handleError(fromEof: Boolean = false) {
        root.keepScreenOn = false
        handler.removeCallbacks(stallRunnable)
        if (networkLost) {
            needsReload = true
            showStatus(tr("player.net_seti_zhdem_podklyuchenie"))
        } else if (retryCount < MAX_RETRIES || !fromEof) {
            // Keep trying while the user remains on the player. A restored network may need time
            // before the origin/CDN starts serving the stream again; use capped exponential backoff.
            retryCount = (retryCount + 1).coerceAtMost(MAX_RETRIES)
            val delay = minOf(1000L * (1L shl (retryCount - 1).coerceAtLeast(0)), MAX_RETRY_DELAY_MS)
            showStatus(if (retryCount <= 1) tr("player.perepodklyuchenie") else tr("player.povtornoe_podklyuchenie"))
            if (reconnectScheduled) return
            reconnectScheduled = true
            handler.removeCallbacks(reconnectRunnable)
            handler.postDelayed(reconnectRunnable, delay)
        } else if (fromEof && lengthOf(player) <= 0L) {
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
            Toast.makeText(this, tr("player.oshibka_vosproizvedeniya_potok"), Toast.LENGTH_LONG).show()
        }
    }


    private val progressTicker = object : Runnable {
        override fun run() {
            if (!controlsVisible || !alive) return
            updateProgress(force = false)
            handler.postDelayed(this, 200L)
        }
    }

    /** UI is refreshed smoothly while controls are visible. */
    private fun updateProgress(force: Boolean) {
        val p = player ?: return
        if (userSeeking || inPip || !controlsVisible) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastUiUpdateAt < 200L) return
        lastUiUpdateAt = now
        val length = lengthOf(p)
        // While a stream is (re)opening VLC reports 0: show where the film will continue instead of 00:00.
        // A seek in flight shows where it goes (VLC still reports the old place, or 0 while a Blu-ray reloads).
        val time = pendingSeekPos ?: timeOf(p).takeIf { it > 0L } ?: playbackPosition.coerceAtLeast(0L)
        // M2TS often has no duration: show --:-- and disable the duration-based seek bar.
        ui.time = time
        if (length > 0L) {
            ui.length = length
            // Preloaded buffer: VLC network caching (time buffer ~6s) + BurstProxy disk buffer
            // Media3 knows what it has in memory itself; the reserve on disk comes after that.
            val diskBufferMs = BurstProxy.aheadMs(currentUrl, length)
            ui.buffered = p.bufferedPositionMs.takeIf { it >= 0L }?.let { (it + diskBufferMs).coerceIn(time, length) } ?: run {
                val vlcTimeBufferMs = if (p.isPlaying || buffering) 6_000L else 0L
                (time + vlcTimeBufferMs + diskBufferMs).coerceIn(time, length)
            }
        } else {
            ui.length = 0L
            ui.buffered = 0L
        }
    }

    private fun setControlsVisible(visible: Boolean) = controlsOverlay.setVisible(visible)
    private fun showControlsTemporarily() = controlsOverlay.showTemporarily()

    private fun setPlayIcon(playing: Boolean) { ui.playing = playing }

    private fun pillButton(label: String) = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE); setPadding(dp(14), 0, dp(14), 0)
        background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(22).toFloat(); setColor(0xB8222228.toInt()); setStroke(dp(1), 0x44FFFFFF) }
        isClickable = true; isFocusable = true
        // remote control: a white ring on the focused button
        foreground = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setStroke(dp(2), Color.WHITE) })
        }
    }

    /** Status / seek text over the film: the panel's glass capsule. */
    private fun glassLabel(size: Float) = TextView(this).apply {
        setTextColor(Color.WHITE); textSize = size; gravity = Gravity.CENTER
        typeface = runCatching { androidx.core.content.res.ResourcesCompat.getFont(this@PlayerActivity, R.font.onest_medium) }.getOrNull()
        background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0xD9161B26.toInt()); setStroke(dp(1), 0x33FFFFFF) }
        visibility = View.GONE
    }

    /** «Аудио», «Субтитры», «⚙» (or null: close). The sheet is over the bar sideways, at the top of the panel below upright. */
    private fun openPicker(kind: String?) {
        ui.picker = kind
        refreshPicker()
        if (kind != null) showControlsTemporarily() else controlsOverlay.keepShown()
    }

    private fun refreshPicker() {
        ui.pickerItems = when (ui.picker) {
            "audio" -> trackSelector.audioChoices(player)
            "spu" -> trackSelector.subtitleChoices(player)
            else -> emptyList()
        }
    }

    private fun togglePlay() {
        if (player?.isPlaying == true && !needsReload) doPause() else userPlay()
        showControlsTemporarily()
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
        val mbit = BurstProxy.bitrateMbit(currentUrl) ?: bluRayController.getBitrateMbit(currentUrl) ?: player?.bitrateMbit ?: return
        val name = Uri.parse(currentUrl).lastPathSegment ?: return
        if (mbit < limit || name in heavyOk || !TrafficMeter.isMetered(this)) return
        heavyOk += name
        val gbPerHour = mbit * 3600 / 8 / 1000
        AppLog.write("heavy file on mobile data: %.1f Mbit/s (limit %d), asking".format(mbit, limit))
        doPause()
        heavyDialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(tr("player.tyazhelyy_fayl_na_mobilnoy"))
            .setMessage(tr("player.etot_fayl_f_mbit").format(mbit, gbPerHour) +
                tr("player.luchshe_dozhdatsya_wi_fi"))
            .setPositiveButton(tr("player.vse_ravno_smotret")) { _, _ -> heavyDialog = null; doPlay() }
            .setNeutralButton(tr("player.ne_sprashivat")) { _, _ -> heavyDialog = null; PlaybackSettings.setHeavyMbit(this, 0); doPlay() }
            .setNegativeButton(tr("player.zakryt")) { _, _ -> heavyDialog = null; leave() }
            .setOnCancelListener { heavyDialog = null; leave() }
            .show()
    }

    // ---- Blu-ray (.m2ts): length and time delegated to PlayerBluRayController ------------------
    private var lastKnownLength = 0L
    private var lastKnownLengthUrl = ""

    /** The film's length: VLC's, or for a transport stream the one read from its clock (0 = unknown). */
    private fun lengthOf(p: PlayerEngine?): Long {
        val len = bluRayController.lengthOf(p, currentUrl)
        if (len > 0L) {
            lastKnownLength = len
            lastKnownLengthUrl = currentUrl
            return len
        }
        return if (lastKnownLengthUrl == currentUrl) lastKnownLength else 0L
    }

    /** The film's time: VLC's, plus the offset when VLC counts a continued Blu-ray from 0. */
    private fun timeOf(p: PlayerEngine?): Long = bluRayController.timeOf(p, currentUrl)

    // ---- a network slower than the film: one longer wait instead of a stutter every few seconds ------------------
    // 12.10 journal (LTE 12 Mbit/s, film 13.2): the reserve stayed at 0 MB, VLC stopped for 3–6 s every 15–30 s, and
    // after most of these short waits the hardware decoder showed a still picture with the sound going on. Now, when
    // the reserve has run dry on such a network, the reserve holds the film back until ~30 s of it are downloaded
    // (at most 45 s) and then it plays on for minutes. VLC is not paused for this (a pause in the middle of its
    // buffering raised its delay to ~8 s and froze the picture — 12.10 second test): for VLC it is just a longer
    // buffering, and the supervisor sees an ordinary end of buffering afterwards.
    // The rules are shared with the PC (CatchUp); not right after an open / seek / unlock: VLC fills its buffer then anyway.
    private val catchUp = CatchUp({ AppLog.write(it) }, { SystemClock.elapsedRealtime() })
    private val catchingUp: Boolean get() = catchUp.active
    // ...and not before the film has really played a little at the new place: 12.10 fifth test, after a seek on a
    // 2–5 Mbit/s network VLC was still loading the new place 15 s later — the wait for a reserve then held it 45 s more.
    private var catchUpFrom = 0L // film time (ms)

    private fun maybeCatchUp(p: PlayerEngine) {
        if (catchingUp || !viaReserve() || networkLost || userPaused || ended || heavyDialog != null) return
        if (!catchUp.maybeStart(currentUrl, canStart = pendingSeekPos == null && timeOf(p) >= catchUpFrom && p.timeMs > 0L)) return
        handler.removeCallbacks(stallRunnable)
        handler.removeCallbacks(catchUpRunnable)
        handler.post(catchUpRunnable)
    }

    private val catchUpRunnable = object : Runnable {
        override fun run() {
            if (!catchingUp) return
            if (player == null) { endCatchUp(); return }
            val gathered = catchUp.progress(currentUrl)
            if (gathered < 0) { endCatchUp(restart = true); return }
            // VLC may still play what it had in its own buffer: the message only while it really waits
            if (buffering) showStatus(tr("player.set_medlennee_filma_kopim", "p" to gathered))
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
        handler.removeCallbacks(catchUpRunnable)
        val t = perceivedPosition()
        // Media3's decoders need no restart; VLC's do (see CatchUp.end), from what is already on the phone.
        val restartHere = catchUp.end(currentUrl, restart && player != null && !userPaused && t > 0L && player?.kind == EngineKind.VLC)
        if (player == null || userPaused) return
        if (restartHere) { seekTo(t); return }
        // VLC is still buffering: its usual watch over a silent buffering starts again
        if (buffering) {
            handler.removeCallbacks(stallRunnable)
            handler.postDelayed(stallRunnable, stallTimeout())
        }
    }

    // ---- play / pause (button, headset, Bluetooth, PiP) ------------------------------------------
    private fun doPlay() {
        val p = player ?: return
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
        player?.pause()
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
            ui.full = true
            ui.insets = intArrayOf(i.left, i.top, i.right, i.bottom)
            controlsOverlay.updateUnlockBadgeLayout(full = true, topInset = 0, filmHeight = 0)
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
            ui.full = false // tracks, frame, lock: in the panel below
            ui.insets = intArrayOf(i.left, 0, i.right, 0)
            controlsOverlay.updateUnlockBadgeLayout(full = false, topInset = i.top, filmHeight = h)
            controlsOverlay.showSystemUi(window)
        }
        stage.layoutParams = sp; infoPanel.layoutParams = pp
        if (modeFull != full) {
            modeFull = full
            // The small player always shows the whole frame: «Заполнить» / «Растянуть» chosen for the full screen cropped
            // the picture past the small area's edges. Back on the full screen the chosen frame mode returns.
            runCatching { player?.setScale(shownScale()) }
        }
    }

    private var modeFull: Boolean? = null
    private fun shownScale() = if (isFull()) SCALES[scaleIndex].first else VideoScale.BEST_FIT

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
    private fun showUnlockBadge() = controlsOverlay.showUnlockBadge()

    // ---- frame mode ---------------------------------------------------------------------------
    private fun cycleScale() = setScale((scaleIndex + 1) % SCALES.size)

    private fun setScale(index: Int) {
        scaleIndex = index.coerceIn(0, SCALES.size - 1)
        uiPrefs.edit().putInt("scale", scaleIndex).apply()
        runCatching { player?.setScale(shownScale()) }
        ui.scaleIndex = scaleIndex
        panel.scale = SCALES[scaleIndex].second
        showSeekLabel(if (isFull()) tr("player.kadr_p", "p" to SCALES[scaleIndex].second) else tr("player.kadr_na_ves_ekran", "p" to SCALES[scaleIndex].second))
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
        val p = player
        audioSession.update(
            title = shownTitle,
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
        override fun surfaceDestroyed(holder: SurfaceHolder) { if (player != null && !videoParked) surfaceLost = true }
    }

    private fun hookSurfaces() {
        for (sv in surfaceViews(videoLayout)) if (hookedSurfaces.add(sv)) sv.holder.addCallback(surfaceCallback)
    }

    private fun reattachVideo() {
        val p = player ?: return
        if (p.handlesSurfaceItself) return // Media3 moves to the new surface by itself
        supervisor.surfaceChanged()
        p.detachVideo()
        p.attachVideo()
        hookSurfaces()
        // Restart the video output on the new surface; audio and position are not interrupted.
        runCatching { if (p.videoTrackCount > 0) { p.setVideoEnabled(false); p.setVideoEnabled(true) } }
        applyVideoFrameRate(p)
        if (!networkMonitor.isNetworkLost && !buffering) bufferingLabel.visibility = View.GONE
    }

    // ---- lifecycle -------------------------------------------------------------------------
    override fun onStart() {
        super.onStart()
        audioSession.setActive(true)
        BurstProxy.onScreenOn()
        handler.removeCallbacks(idleReleaseRunnable)
        val away = SystemClock.elapsedRealtime() - stoppedAt // elapsedRealtime: the phone sleeps while locked, uptime stops then
        when {
            keptOnStop && player != null && (player?.kind == EngineKind.MEDIA3 ||
                away < FAST_RESUME_MS && networkMonitor.activeNetwork() == networkAtStop) -> resumeAfterStop()
            keptOnStop && player != null -> {
                // Away longer: the server / the phone's network has most likely dropped the paused connection, and
                // VLC waits on such a dead socket forever (the endless "0 %" / "Переподключение" loop). Start clean
                // from the same moment instead. Media3 reconnects by itself: it always just continues (above).
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
        val p = player ?: return@Runnable
        if (!videoParked) return@Runnable
        videoParked = false
        if (p.videoTrackCount <= 0) return@Runnable
        runCatching { p.setVideoEnabled(true) }
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

    private fun keepOnStop(p: PlayerEngine) {
        partySync.hostAway(perceivedPosition())
        stoppedAt = SystemClock.elapsedRealtime()
        networkAtStop = networkMonitor.activeNetwork()
        endCatchUp()
        playOnStart = !userPaused && !ended && (p.isPlaying || playWhenReady || buffering)
        runCatching { p.pause() } // not doPause(): the user did not pause, the film continues on return
        supervisor.paused(); AppLog.write("screen off / app hidden: paused, keeping the player")
        BurstProxy.onScreenOff()
        keptOnStop = true
        // The screen's surface goes away: let go of it cleanly (video off, views detached) instead of letting the
        // video output die with it — that is what gave ~10 s of black / flicker after unlocking.
        videoParked = true
        runCatching { if (p.videoTrackCount > 0) p.setVideoEnabled(false) }
        p.detachVideo()
        handler.removeCallbacks(reattachRunnable); surfaceLost = false
        // Nothing may restart playback (or reconnect) behind the lock screen.
        trackSelector.stop()
        for (r in listOf(watchdogRunnable, stallRunnable, reconnectRunnable, seekWatchdogRunnable, reseekRunnable, trackSwitchRunnable))
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
        val p = player ?: return
        catchUp.quiet(15_000L); catchUpFrom = timeOf(p) + CATCH_UP_AFTER_PLAYED_MS
        keptOnStop = false
        p.attachVideo()
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

    // ---- remote control (Android TV) ---------------------------------------------------------------------------------
    private val tv by lazy { Tv.isTv(this) }
    /** A key whose press was taken here: its release is taken too (it must not click the button that got focus). */
    private var takenKey = KeyEvent.KEYCODE_UNKNOWN

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == takenKey) { takenKey = KeyEvent.KEYCODE_UNKNOWN; return true }
        if (event.action == KeyEvent.ACTION_DOWN && remoteKey(event)) { takenKey = event.keyCode; return true }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Panel hidden: OK — pause / play, ←/→ — 10 s back / forward (held — faster), ↑/↓ — the panel.
     * Panel shown: the arrows walk over its buttons, ←/→ on the timeline seek, every key keeps the panel up.
     * Media keys of the remote work either way.
     */
    private fun remoteKey(e: KeyEvent): Boolean {
        if (!::overlay.isInitialized || inPip) return false
        val code = e.keyCode
        val first = e.repeatCount == 0
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { if (first) togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (first) { userPlay(); showControlsTemporarily() }; return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (first) { doPause(); showControlsTemporarily() }; return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> { tapSeek(code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (first) userNext(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (first) userPrevious(); return true }
        }
        if (locked) return false
        val center = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
        val leftRight = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        val upDown = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
        if (!center && !leftRight && !upDown && code != KeyEvent.KEYCODE_MENU && code != KeyEvent.KEYCODE_INFO) return false
        if (!controlsOverlay.isVisible) {
            when {
                center -> { if (first) { togglePlay(); ui.focusWant = "play" } }
                leftRight -> { tapSeek(code == KeyEvent.KEYCODE_DPAD_RIGHT); ui.focusWant = "seek" }
                else -> { showControlsTemporarily(); ui.focusWant = "play" }
            }
            return true
        }
        controlsOverlay.keepShown()
        // The panel is up but nothing on it has focus yet (it was shown by a touch or on opening): start on play / pause.
        if (!ui.hasFocus) {
            ui.focusWant = "play"
            return true
        }
        if (ui.seekFocused && leftRight) { tapSeek(code == KeyEvent.KEYCODE_DPAD_RIGHT); return true }
        return false
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
        player?.let { persistPosition(perceivedPosition(), lengthOf(it)) }
        currentUrl = url
        lastKnownLength = 0L
        lastKnownLengthUrl = ""
        currentTitle = intent.getStringExtra(EXTRA_TITLE)?.ifBlank { tr("main.video") } ?: tr("main.video")
        playlistQueue.reset()
        readSideExtras(intent)
        shownTitle = currentTitle
        ui.title = currentTitle
        ui.picker = null
        trackSelector.resetSelectedTracks()
        playbackPosition = startPosition(intent); lastTime = playbackPosition
        pendingSeekPos = null; ended = false; userPaused = false; playWhenReady = true; retryCount = 0; needsReload = false
        if (player != null) reconnect(forcePlay = true, startAt = playbackPosition)
        intent.getStringExtra(EXTRA_PLAYLIST)?.takeIf { it.isNotBlank() }?.let { fetchPlaylist(it) }
    }

    override fun onDestroy() {
        alive = false
        scope.cancel()
        heavyDialog?.let { runCatching { it.dismiss() } }; heavyDialog = null
        AppLog.write("player closed: %.0f MB fetched in this session (%s)".format(
            BurstProxy.downloadedMb() + Media3Engine.downloaded.get() / 1048576.0 - sessionStartMb, if (TrafficMeter.isMetered(this)) "mobile" else "wifi"))
        TrafficMeter.flush(this)
        diagnostics.onDestroy()
        orientationWatch?.disable(); orientationWatch = null
        CrashReports.playerRunning(this, false)
        supervisor.closed()
        partySync.stop()
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
        player?.let { p ->
            playbackPosition = perceivedPosition()
            persistPosition(playbackPosition, lengthOf(p))
        }
        // playWhenReady is maintained by events, so a mid-buffering stop no longer resumes paused.
        controlsOverlay.onStop()
        playlistQueue.stop()
        val p = player
        if (p != null && !isFinishing && !isChangingConfigurations) keepOnStop(p) else releasePlayer()
        audioSession.setActive(false)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_URL, currentUrl)
        outState.putString(KEY_TITLE, currentTitle)
        outState.putLong(KEY_POSITION, if (player != null) perceivedPosition() else playbackPosition)
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
        const val EXTRA_URL = PlayerExtras.URL
        const val EXTRA_TITLE = PlayerExtras.TITLE
        const val EXTRA_PLAYLIST = PlayerExtras.PLAYLIST
        const val EXTRA_FROM_START = PlayerExtras.FROM_START
        private const val KEY_URL = "current_url"
        private val NIGHT = 0xFF090B10.toInt()
        private const val KEY_TITLE = "current_title"
        private const val KEY_POSITION = "playback_position"
        private const val KEY_PLAYING = "play_when_ready"
        private const val STALL_TIMEOUT_MS = 12_000L
        private const val OPEN_TIMEOUT_MS = 20_000L
        private const val WATCHDOG_TICK_MS = 2_000L
        private const val FAST_RESUME_MS = 20_000L // back within this: just continue the paused player
        private const val CATCH_UP_AFTER_PLAYED_MS = 5_000L // film played at a new place before a wait for a reserve
        private const val IDLE_RELEASE_MS = 10 * 60_000L // locked longer than this: free VLC and the connection
        const val EXTRA_SUB_URLS = PlayerExtras.SUB_URLS
        const val EXTRA_WATCH_KEYS = PlayerExtras.WATCH_KEYS
        const val EXTRA_AUDIO_URLS = PlayerExtras.AUDIO_URLS
        const val EXTRA_EPISODE_SIDES = PlayerExtras.EPISODE_SIDES
        @Volatile private var media3Swept = false // the old Media3 cache folder: removed once per process, off the main thread
        private val SCALES = listOf(
            VideoScale.BEST_FIT to tr("player.vpisat"),
            VideoScale.FIT_SCREEN to tr("player.zapolnit"),
            VideoScale.FILL to tr("player.rastyanut"),
            VideoScale.RATIO_16_9 to "16:9"
        )
        private const val SEEK_CONFIRM_TIMEOUT_MS = 9_000L
        private const val TRACK_SWITCH_CHECK_MS = 3_000L // the buffering % has not risen for this long: stuck
        private const val TRACK_SWITCH_WATCH_MS = 20_000L
        private const val SEEK_CONFIRM_TOLERANCE_MS = 12_000L
        private const val TAP_SEEK_APPLY_MS = 350L
        private const val STABLE_PLAYBACK_MS = 30_000L
        private const val MAX_RETRY_DELAY_MS = 15_000L
        private const val MAX_RETRIES = 5
    }
}
