package ru.suslikovanora.media

import ru.suslikovanora.media.core.*

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
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
    private lateinit var gestureDetector: GestureDetector

    private var userSeeking = false
    private var playbackPosition = 0L
    private var playWhenReady = true
    private var resumeOnFocusGain = false
    private var ended = false
    private var controlsVisible = true
    private var inPip = false
    private var lastUiUpdateAt = 0L
    private var noisyRegistered = false
    private var focusRequest: AudioFocusRequest? = null

    // Stability state.
    private var teardownExec: ExecutorService? = null   // serial native teardown: players first, LibVLC last
    private var buffering = false
    private var reconnectScheduled = false
    private var pendingSeekPos: Long? = null            // seek issued but not yet confirmed by TimeChanged
    private var tapSeekTarget: Long? = null             // accumulated double-tap target, applied after a short pause
    private var tapSeekStart = 0L
    private var lastSeekTapAt = 0L
    private var lastWatchTime = -1L
    private var watchStuckTicks = 0
    private var awaitingValidation = false
    private var selectedAudioId: Int? = null
    private var selectedSpuId: Int? = null
    private var restoreTracks = false
    private var lastPersistAt = 0L
    private lateinit var seekLabel: TextView

    // 1.3: gestures, frame mode, media session, sidecar subtitles, remembered tracks, next-episode card, watch progress.
    private val uiPrefs by lazy { getSharedPreferences("player_ui", MODE_PRIVATE) }
    private val watchPrefs by lazy { getSharedPreferences("watch", MODE_PRIVATE) } // read by the native library
    private lateinit var scaleDetector: ScaleGestureDetector
    private enum class Drag { None, Seek, Brightness, Volume }
    private var drag = Drag.None
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartValue = 0f
    private var dragSeekBase = 0L
    private var dragSeekTarget = 0L
    private var pinchFactor = 1f
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop * 2f }
    private var scaleIndex = 0
    private lateinit var scaleButton: TextView
    private var session: MediaSession? = null
    private var subUrls: List<String> = emptyList()
    private var audioUrls: List<String> = emptyList()
    private var subsForUrl = ""
    private var watchKeys: List<String> = emptyList()
    private var prefsAppliedForUrl = ""
    private var prefsUntil = 0L // remembered tracks may still be applied until then
    private val prefsRunnable = Runnable { mediaPlayer?.let { applyPreferredTracks(it) } }
    private lateinit var nextCard: LinearLayout
    private lateinit var nextCardTitle: TextView
    private lateinit var nextCardTimer: TextView
    private var countdown = 0
    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (countdown <= 0) { hideNextCard(); playQueueEntry(queueIndex + 1); return }
            nextCardTimer.text = "Через $countdown с"
            countdown--
            handler.postDelayed(this, 1000L)
        }
    }
    // Current episode + optional playlist (this episode and the ones after it, from the site).
    private data class Entry(val title: String, val url: String)
    private var currentUrl = ""
    private var currentTitle = "Видео"
    private var queue: List<Entry> = emptyList()
    private var queueIndex = -1
    private lateinit var titleView: TextView
    private lateinit var prevButton: android.widget.ImageView
    private lateinit var nextButton: android.widget.ImageView

    // Network handling: reload the stream in place (same LibVLC/activity) when the network changes.
    private var lastNetwork: Network? = null
    private var networkLost = false
    private var needsReload = false
    private var userPaused = false // only set by the Play/Pause button; VLC's own Paused events must not count
    private var retryCount = 0
    private var lastTime = 0L
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { setControlsVisible(false) }
    // Several quick reconnects in a row did not help: the VLC engine itself may be stuck on a dead connection, so
    // the next attempt starts it from scratch instead of building yet another player on it.
    private val reconnectRunnable = Runnable { if (retryCount >= 3 && mediaPlayer != null) restartClean(play = !userPaused, resetRetries = false) else reconnect() }
    // No buffering/open progress for a while: treat as a dead connection (goes through backoff).
    private val stallRunnable = Runnable { if (mediaPlayer != null && !userPaused && !ended) handleError() }
    private val seekLabelHideRunnable = Runnable { seekLabel.visibility = View.GONE }
    private val applyTapSeekRunnable = Runnable {
        val t = tapSeekTarget ?: return@Runnable
        tapSeekTarget = null
        seekTo(t)
    }
    // A seek that VLC never confirms (dead socket after a Wi-Fi <-> mobile switch) is redone on a fresh connection.
    private val seekWatchdogRunnable = Runnable {
        val target = pendingSeekPos ?: return@Runnable
        val p = mediaPlayer ?: return@Runnable
        if (kotlin.math.abs(p.time - target) > SEEK_CONFIRM_TOLERANCE_MS) {
            reconnect(forcePlay = !userPaused, startAt = target)
        } else pendingSeekPos = null
    }
    // Playing but the clock does not move and no buffering is reported = frozen playback.
    // Runs only while VLC reports "playing" (restarted by the Playing event), so a paused player wakes nobody up.
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val p = mediaPlayer ?: return
            if (!p.isPlaying) return // paused: nobody wakes up (restarted by the Playing event)
            // Network trouble is handled by the reconnect logic; the supervisor judges only real playback.
            if (!userPaused && !ended && !networkLost && !resumeOnFocusGain) statsOf(p)?.let { supervisor.tick(it) }
            handler.postDelayed(this, WATCHDOG_TICK_MS)
        }
    }

    // ---- playback health: the supervisor shared with the PC app (core.PlaybackSupervisor) ---------------
    // One place decides when playback is broken and what to do — only while the film really plays, never right after
    // a seek / resume / reconnect, one gentle step at a time (video restart, sound fix) before any reopen.
    private val supervisor = PlaybackSupervisor(object : PlaybackSupervisor.Actions {
        override fun restartVideo() {
            val p = mediaPlayer ?: return
            runCatching { p.setVideoTrackEnabled(false); p.setVideoTrackEnabled(true) }
            val t = perceivedPosition()
            if (t > 0L) seekTo(t) // new decoder from the keyframe right here: the picture comes back at once
        }
        override fun fixSound() {
            val p = mediaPlayer ?: return
            val id = p.audioTrack.takeIf { it != -1 } ?: p.audioTracks?.firstOrNull { it.id != -1 }?.id ?: return
            runCatching { p.setAudioTrack(-1); p.setAudioTrack(id) } // restarts the audio decoder and output
            selectedAudioId = id
        }
        override fun reopen(fresh: Boolean) {
            CrashReports.recovery("reopen at ${perceivedPosition() / 1000}s (fresh=$fresh)")
            if (fresh) { BurstProxy.keepOnly(emptyList()); restartClean(play = !userPaused) }
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
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val prefs by lazy { getSharedPreferences("positions", MODE_PRIVATE) }

    // ---- libVLC events (one listener per MediaPlayer) ----------------------------------------
    // Events of a player that was already replaced can still sit in the main looper queue; they are ignored.
    private fun listenerFor(owner: MediaPlayer) = MediaPlayer.EventListener { event ->
        if (owner !== mediaPlayer) return@EventListener
        when (event.type) {
            MediaPlayer.Event.Playing -> {
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
                if (prefsAppliedForUrl != currentUrl && !restoreTracks) {
                    prefsAppliedForUrl = currentUrl
                    // Remembered tracks: applied as soon as they are known, and again when VLC reports a new one
                    // (voice-over files arrive a little later) — instead of one fixed 1.5 s guess.
                    prefsUntil = SystemClock.uptimeMillis() + 8_000L
                    handler.removeCallbacks(prefsRunnable)
                    handler.postDelayed(prefsRunnable, 300L)
                }
                updateSession()
                if (restoreTracks) {
                    restoreTracks = false
                    handler.postDelayed({ if (owner === mediaPlayer) restoreSelectedTracks(owner) }, 700L)
                }
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
            MediaPlayer.Event.ESAdded -> if (SystemClock.uptimeMillis() < prefsUntil) {
                handler.removeCallbacks(prefsRunnable)
                handler.postDelayed(prefsRunnable, 400L)
            }
            MediaPlayer.Event.EndReached -> {
                // A dead network connection also shows up as EOF, so only a position near the known
                // duration is a real end; anything else is treated as a dropped stream and reloaded.
                val length = owner.length
                val pos = maxOf(owner.time, lastTime)
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
                if (event.buffering < 100f) {
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
                } else {
                    buffering = false
                    bufferingShown = 0
                    handler.removeCallbacks(stallRunnable)
                    if (!networkLost) bufferingLabel.visibility = View.GONE
                }
            }
            MediaPlayer.Event.TimeChanged, MediaPlayer.Event.LengthChanged -> {
                val t = owner.time
                if (t > 0L) {
                    val pending = pendingSeekPos
                    if (pending == null) {
                        lastTime = t
                        playbackPosition = t
                        maybePersist(t)
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

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocusGain = false; supervisor.paused(); mediaPlayer?.pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (mediaPlayer?.isPlaying == true) { resumeOnFocusGain = true; mediaPlayer?.pause() }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) { resumeOnFocusGain = false; supervisor.resumed(); mediaPlayer?.play() }
            }
        }
    }

    // Headphones unplugged / BT disconnected -> pause instead of blasting through the speaker.
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) mediaPlayer?.pause()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ru.suslikovanora.media.net.Api.init(this)
        CrashReports.init(this)
        alive = true
        CrashReports.playerRunning(this, true) // still set at the next start = this screen died (native crash)
        BurstProxy.log = { AppLog.write(it) }
        BurstProxy.init(java.io.File(cacheDir, "stream")) { url, from ->
            // The shared reserve downloads through OkHttp here (same connections as the rest of the app).
            val res = ru.suslikovanora.media.net.Api.plain.newCall(okhttp3.Request.Builder().url(url)
                .header("Range", "bytes=$from-").header("Accept-Encoding", "identity").build()).execute()
            BurstProxy.UpstreamResponse(res.code, { n -> res.header(n) }, res.body?.byteStream())
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
        playbackPosition = savedInstanceState?.getLong(KEY_POSITION) ?: savedPosition()
        playWhenReady = savedInstanceState?.getBoolean(KEY_PLAYING) ?: true
        userPaused = !playWhenReady
        readSideExtras(intent)
        playbackPosition = savedInstanceState?.getLong(KEY_POSITION) ?: savedPosition()
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

        nextCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xEB11141B.toInt()); setStroke(dp(1), 0x55FFB02E) }
            visibility = View.GONE
            isClickable = true
        }
        nextCard.addView(TextView(this).apply { text = "Следующая серия"; setTextColor(0xFF8E96A8.toInt()); textSize = 13f })
        nextCardTitle = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2; setPadding(0, dp(2), 0, dp(2))
        }
        nextCard.addView(nextCardTitle)
        nextCardTimer = TextView(this).apply { setTextColor(0xFFFFB02E.toInt()); textSize = 13f }
        nextCard.addView(nextCardTimer)
        val nextRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        nextRow.addView(pillButton("▶  Смотреть сейчас").apply {
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0xFFFFB02E.toInt()) }
            setTextColor(0xFF1C1200.toInt())
            setOnClickListener { hideNextCard(); playQueueEntry(queueIndex + 1) }
        }, LinearLayout.LayoutParams(-2, dp(42)).apply { marginEnd = dp(8) })
        nextRow.addView(pillButton("Отмена").apply { setOnClickListener { hideNextCard(); showControlsTemporarily() } }, LinearLayout.LayoutParams(-2, dp(42)))
        nextCard.addView(nextRow)
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
            setOnClickListener { playQueueEntry(queueIndex - 1) }
        }
        nextButton = iconButton(R.drawable.ic_pl_next, 46, 0x99151820.toInt(), 24).apply {
            visibility = View.GONE
            contentDescription = "Следующая серия"
            setOnClickListener { playQueueEntry(queueIndex + 1) }
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
                        val length = mediaPlayer?.length ?: -1L
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
        val audioButton = pillButton("♫  Аудио").apply { setOnClickListener { showAudioTracks(); showControlsTemporarily() } }
        val subsButton = pillButton("CC  Субтитры").apply { setOnClickListener { showSubtitleTracks(); showControlsTemporarily() } }
        scaleButton = pillButton("⤢  ${SCALES[scaleIndex].second}").apply { setOnClickListener { cycleScale(); showControlsTemporarily() } }
        options.addView(audioButton, LinearLayout.LayoutParams(-2, dp(42)).apply { marginEnd = dp(8) })
        options.addView(subsButton, LinearLayout.LayoutParams(-2, dp(42)).apply { marginEnd = dp(8) })
        options.addView(scaleButton, LinearLayout.LayoutParams(-2, dp(42)).apply { marginEnd = dp(8) })
        options.addView(pillButton("🔒").apply { contentDescription = "Блокировка экрана"; setOnClickListener { lockScreen() } },
            LinearLayout.LayoutParams(dp(48), dp(42)).apply { marginEnd = dp(14) })
        options.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        volumeLabel = TextView(this).apply { text = "🔊"; textSize = 16f; setTextColor(Color.WHITE) }
        options.addView(volumeLabel, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
        // System media volume (the same as the hardware buttons and the right-edge swipe).
        volumeBar = SeekBar(this).apply {
            max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
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
        panel.onAudio = { showAudioTracks() }
        panel.onSubs = { showSubtitleTracks() }
        panel.onScale = { cycleScale() }
        panel.onFull = { toggleFullscreen() }
        panel.onLock = { lockScreen() }
        panel.onEpisode = { i -> if (i != queueIndex) playQueueEntry(i) }
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
        buildLock()
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

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Rapid follow-up taps after a double-tap keep seeking (like video apps do).
                if (SystemClock.uptimeMillis() - lastSeekTapAt < REPEAT_TAP_WINDOW_MS) { tapSeek(e.x >= stage.width / 2f); return true }
                if (controlsVisible) setControlsVisible(false) else showControlsTemporarily()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                tapSeek(e.x >= stage.width / 2f)
                return true
            }
        })
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { pinchFactor = 1f; return true }
            override fun onScale(detector: ScaleGestureDetector): Boolean { pinchFactor *= detector.scaleFactor; return true }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                // Pinch out = fill the screen (crop the black bars), pinch in = whole frame.
                if (pinchFactor > 1.08f) setScale(1) else if (pinchFactor < 0.92f) setScale(0)
            }
        })
        val touchListener = View.OnTouchListener { _, event -> onPlayerTouch(event); true }
        stage.setOnTouchListener(touchListener)
        videoLayout.setOnTouchListener(touchListener)
        setupSession()

        addOnPictureInPictureModeChangedListener { info ->
            inPip = info.isInPictureInPictureMode
            applyMode()
            supervisor.surfaceChanged()
            if (inPip) setControlsVisible(false) else showControlsTemporarily()
            // The window size changed: let VLC re-measure (and recover if the surface was recreated meanwhile).
            handler.post { mediaPlayer?.updateVideoSurfaces() }
        }
        showControlsTemporarily()
        intent.getStringExtra(EXTRA_PLAYLIST)?.takeIf { it.isNotBlank() }?.let { fetchPlaylist(it) }
    }

    // ---- playlist: this episode plus every following one (built by the site/server as an .m3u) ----
    private fun fetchPlaylist(playlistUrl: String) {
        Thread {
            val entries: List<Entry> = try {
                val c = URL(playlistUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                try {
                    if (c.responseCode != 200) emptyList<Entry>() else parseM3u(c.inputStream.bufferedReader(Charsets.UTF_8).readText())
                } finally { c.disconnect() }
            } catch (_: Exception) { emptyList<Entry>() }
            runOnUiThread { if (!isDestroyed && !isFinishing) applyQueue(entries) }
        }.start()
    }

    private fun parseM3u(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        var title: String? = null
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXTINF") -> title = line.substringAfter(',', "").trim()
                line.startsWith("#") -> {}
                line.startsWith("https://") || line.startsWith("http://") -> {
                    out.add(Entry(title?.takeIf { it.isNotBlank() } ?: line.substringAfterLast('/').substringBefore('?'), line))
                    title = null
                }
            }
        }
        return out
    }

    private fun mediaKey(url: String): String { val u = Uri.parse(url); return u.host.orEmpty() + u.path.orEmpty() }

    private fun applyQueue(entries: List<Entry>) {
        val idx = entries.indexOfFirst { mediaKey(it.url) == mediaKey(currentUrl) }
        if (idx < 0) return // the playlist does not contain what is playing: ignore it
        queue = entries
        queueIndex = idx
        updateQueueUi()
    }

    private fun updateQueueUi() {
        val has = queue.size > 1 && queueIndex >= 0
        prevButton.visibility = if (has && queueIndex > 0) View.VISIBLE else View.GONE
        nextButton.visibility = if (has && queueIndex < queue.size - 1) View.VISIBLE else View.GONE
        titleView.text = if (has) "$currentTitle  ·  ${queueIndex + 1}/${queue.size}" else currentTitle
        panel.title = currentTitle
        panel.episodes = if (has) queue.map { it.title } else emptyList()
        panel.index = if (has) queueIndex else -1
    }

    /** Switch to another entry of the playlist on the existing player (no activity or LibVLC restart). */
    private fun playQueueEntry(index: Int) {
        hideNextCard()
        if (index < 0 || index >= queue.size || mediaPlayer == null) return
        // Remember where the episode we are leaving stopped (cleared automatically when it was watched to the end).
        mediaPlayer?.let { persistPosition(it.time.coerceAtLeast(0L), it.length) }
        val entry = queue[index]
        queueIndex = index
        currentUrl = entry.url
        currentTitle = entry.title
        playbackPosition = savedPosition()
        lastTime = playbackPosition
        ended = false
        userPaused = false
        playWhenReady = true
        retryCount = 0
        needsReload = false
        seekBar.progress = 0
        updateQueueUi()
        reconnect(forcePlay = true, startAt = playbackPosition)
    }

    /** Called when an episode really finished. True when playback moved on to the next one. */
    private fun advanceToNext(): Boolean {
        if (queueIndex < 0 || queueIndex >= queue.size - 1) return false
        positionKey()?.let { prefs.edit().remove(it).apply() }
        Toast.makeText(this, "Следующая серия", Toast.LENGTH_SHORT).show()
        playQueueEntry(queueIndex + 1)
        return true
    }

    private fun initializePlayer() {
        if (mediaPlayer != null) return
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
                )
            )
            libVlc = vlc
            teardownExec = Executors.newSingleThreadExecutor { r -> Thread(r, "vlc-teardown").apply { isDaemon = true } }
            val position = pendingSeekPos ?: playbackPosition
            pendingSeekPos = null
            val player = createPlayer(vlc, url, position)
            mediaPlayer = player
            ContextCompat.registerReceiver(
                this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyRegistered = true
            registerNetworkCallback()
            if (!userPaused && !ended) {
                requestAudioFocus()
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
        val player = MediaPlayer(vlc)
        player.setEventListener(listenerFor(player))
        // enableSubtitles = true so selected subtitle tracks are rendered; SurfaceView is cheaper than TextureView.
        player.attachViews(videoLayout, null, true, false)
        hookSurfaces()
        runCatching { player.setVideoScale(shownScale()) }
        supervisor.opening(url)
        AppLog.write("open ${Uri.parse(url).lastPathSegment ?: "?"} at ${positionMs / 1000}s, reserve=${PlaybackSettings.burstMb(this)}MB, buffer=${PlaybackSettings.cacheMs(this)}ms")
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
        if (noisyRegistered) { runCatching { unregisterReceiver(noisyReceiver) }; noisyRegistered = false }
        unregisterNetworkCallback()
        abandonAudioFocus()
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(seekWatchdogRunnable)
        handler.removeCallbacks(applyTapSeekRunnable)
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
        setHWDecoderEnabled(true, false)
        addOption(":http-reconnect")
        addOption(":network-caching=${PlaybackSettings.cacheMs(this@PlayerActivity)}")
        val sidecars = url == subsForUrl
        // Only what this episode needs stays in the stream cache (the previous episode's files are dropped).
        BurstProxy.keepOnly(listOf(url) + if (sidecars) audioUrls + subUrls else emptyList())
        // Seek to the nearest keyframe: the picture appears right away instead of waiting (or freezing) on
        // hardware decoders after a jump into the middle of a GOP. ±10 s taps do not need frame accuracy.
        // Not with an external voice-over: it is a separate input that lands on the exact moment, while the video
        // jumped to its keyframe — the sound drifted after every seek. Then seeks are exact for both.
        if (!sidecars || audioUrls.isEmpty()) addOption(":input-fast-seek")
        // External voice-overs / subtitles are part of the media from the start. Added to the running player (as
        // before, on every "Playing" — so again after each pause and reconnect) they reopened inputs mid-playback:
        // a hiccup and a drifting voice-over.
        if (sidecars) {
            for (u in audioUrls) runCatching { addSlave(org.videolan.libvlc.interfaces.IMedia.Slave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Audio, 4, burst(u))) }
            for (u in subUrls) runCatching { addSlave(org.videolan.libvlc.interfaces.IMedia.Slave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, 4, u)) }
        }
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

    /**
     * A film this phone has no hardware decoder for (often 4K / HEVC 10-bit / AV1 on older phones) is decoded by the
     * processor — many times the battery use, and the phone gets warm. VLC does that silently; say it once per file.
     */
    private var decodeCheckedUrl = ""
    private fun warnIfSoftwareDecoding(p: MediaPlayer) {
        if (decodeCheckedUrl == currentUrl) return
        val t = runCatching { p.currentVideoTrack }.getOrNull() ?: return
        decodeCheckedUrl = currentUrl
        val mime = when (t.codec?.lowercase(Locale.ROOT)?.trim()) {
            "h264", "avc1", "x264" -> "video/avc"
            "hevc", "h265", "hev1", "hvc1" -> "video/hevc"
            "av01", "av1" -> "video/av01"
            "vp90", "vp09", "vp9" -> "video/x-vnd.on2.vp9"
            "vp80", "vp8" -> "video/x-vnd.on2.vp8"
            "mpgv", "mp2v" -> "video/mpeg2"
            else -> return
        }
        val hw = runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                if (info.isEncoder || info.supportedTypes.none { it.equals(mime, true) }) return@any false
                val hardware = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
                    else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
                hardware && runCatching { info.getCapabilitiesForType(mime).videoCapabilities.isSizeSupported(t.width, t.height) }.getOrDefault(true)
            }
        }.getOrDefault(true)
        if (!hw) Toast.makeText(this, "Телефон не умеет аппаратно декодировать этот файл (${t.width}×${t.height}) — " +
            "он воспроизводится процессором и заметно сильнее расходует батарею.", Toast.LENGTH_LONG).show()
    }

    /**
     * Android 11+: tells the system the video's real frame rate. On 90/120 Hz screens the display can then run
     * at 24/30/60 Hz during playback (a large share of the screen's power), without any visual change: on
     * Android 12+ only seamless switches are allowed, so there is never a black flash or mode-change pause.
     */
    private fun applyVideoFrameRate(owner: MediaPlayer) {
        if (Build.VERSION.SDK_INT < 30) return
        val track = runCatching { owner.currentVideoTrack }.getOrNull() ?: return
        if (track.frameRateNum <= 0 || track.frameRateDen <= 0) return
        val fps = track.frameRateNum.toFloat() / track.frameRateDen
        if (fps < 10f || fps > 121f) return
        for (sv in surfaceViews(videoLayout)) {
            val surface = sv.holder?.surface?.takeIf { it.isValid } ?: continue
            try { setSurfaceFrameRate(surface, fps) } catch (_: Exception) { }
        }
    }

    @RequiresApi(30)
    private fun setSurfaceFrameRate(surface: Surface, fps: Float) {
        if (Build.VERSION.SDK_INT >= 31) {
            surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
        } else {
            surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        }
    }

    private fun surfaceViews(v: View, out: MutableList<SurfaceView> = ArrayList()): List<SurfaceView> {
        if (v is SurfaceView) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) surfaceViews(v.getChildAt(i), out)
        return out
    }

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
        val position = startAt ?: pendingSeekPos ?: maxOf(old.time.coerceAtLeast(0L), lastTime)
        playbackPosition = position
        lastTime = position
        pendingSeekPos = null
        needsReload = false
        ended = false
        buffering = false
        restoreTracks = selectedAudioId != null || selectedSpuId != null
        showStatus("Переподключение…")
        try {
            // A dead socket makes stop()/setMedia() block, so never reuse the old player: build a fresh
            // MediaPlayer on the same LibVLC/views and tear the old one down in the background (serially).
            val oldMedia = media
            old.setEventListener(null)
            runCatching { old.detachViews() }
            val player = createPlayer(vlc, url, position)
            mediaPlayer = player
            requestAudioFocus()
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
        tapSeekTarget ?: pendingSeekPos ?: (mediaPlayer?.time?.takeIf { it > 0L } ?: lastTime).coerceAtLeast(0L)

    /** Double-tap / repeated tap: accumulates ±10 s steps, applies one real seek after a short pause. */
    private fun tapSeek(forward: Boolean) {
        val p = mediaPlayer ?: return
        if (!p.isSeekable && p.length <= 0L && !hasSeekedOk) {
            showSeekLabel("Перемотка недоступна для этого потока")
            return
        }
        lastSeekTapAt = SystemClock.uptimeMillis()
        if (tapSeekTarget == null) tapSeekStart = perceivedPosition()
        val base = tapSeekTarget ?: tapSeekStart
        val length = p.length
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

    private fun showSeekLabel(text: String) {
        seekLabel.text = text
        seekLabel.visibility = View.VISIBLE
        handler.removeCallbacks(seekLabelHideRunnable)
        handler.postDelayed(seekLabelHideRunnable, 1200L)
    }

    private var hasSeekedOk = false

    /**
     * The one place that seeks. If the player is healthy it seeks in place and arms a watchdog; if the
     * stream is dead (no network, error state, not seekable after a connection change) or the seek is
     * never confirmed, the stream is reopened directly at the target position.
     */
    private fun seekTo(target: Long) {
        val p = mediaPlayer ?: return
        supervisor.seeked(); AppLog.write("seek to ${target / 1000}s")
        hideNextCard()
        val length = p.length
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
        p.setTime(t)
        hasSeekedOk = true
        ended = false
        handler.postDelayed(seekWatchdogRunnable, SEEK_CONFIRM_TIMEOUT_MS)
        updateProgress(force = true)
        updateSession()
    }

    private fun restoreSelectedTracks(p: MediaPlayer) {
        runCatching {
            selectedAudioId?.let { id -> if (p.audioTracks?.any { it.id == id } == true) p.setAudioTrack(id) }
            selectedSpuId?.let { id -> if (id == -1 || p.spuTracks?.any { it.id == id } == true) p.setSpuTrack(id) }
        }
    }

    private fun maybePersist(time: Long) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPersistAt < 10_000L) return
        lastPersistAt = now
        persistPosition(time, mediaPlayer?.length ?: -1L)
    }

    private fun handleError(fromEof: Boolean = false) {
        AppLog.write("stream problem${if (fromEof) " (unexpected end of stream)" else ""}, network lost=$networkLost")
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
        } else if (fromEof && (mediaPlayer?.length ?: -1L) <= 0L) {
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

    private fun registerNetworkCallback() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { runOnUiThread { onNetworkAvailable(network) } }
            override fun onLost(network: Network) { runOnUiThread { onNetworkLost(network) } }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // A freshly attached mobile/Wi-Fi network is often not validated yet: reconnect once it is.
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) runOnUiThread { onNetworkValidated(network) }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (_: Exception) { networkCallback = null }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb ->
            (getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager)?.let { runCatching { it.unregisterNetworkCallback(cb) } }
        }
        networkCallback = null
        lastNetwork = null; networkLost = false; needsReload = false; retryCount = 0
        awaitingValidation = false; reconnectScheduled = false
        handler.removeCallbacks(reconnectRunnable); handler.removeCallbacks(stallRunnable)
        handler.removeCallbacks(stablePlaybackRunnable)
    }

    private fun isValidated(network: Network): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return runCatching { cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
            .getOrNull() ?: true
    }

    private fun onNetworkAvailable(network: Network) {
        val previous = lastNetwork
        val wasLost = networkLost
        lastNetwork = network
        networkLost = false
        if (mediaPlayer == null) return
        if (previous == null && !wasLost) return // initial callback right after registration
        if (viaReserve()) {
            // VLC's connection is local and still alive; the reserve reconnects to the server by itself.
            if (wasLost && !buffering) bufferingLabel.visibility = View.GONE
            return
        }
        if (previous == network && !wasLost) return
        // Wi-Fi <-> mobile switch or connectivity is back: old sockets are dead.
        retryCount = 0
        reconnectScheduled = true
        handler.removeCallbacks(reconnectRunnable)
        if (isValidated(network)) {
            awaitingValidation = false
            handler.postDelayed(reconnectRunnable, 500L)
        } else {
            // Not validated yet: wait for onCapabilitiesChanged, but never longer than a few seconds.
            awaitingValidation = true
            handler.postDelayed(reconnectRunnable, 4_000L)
        }
    }

    private fun onNetworkValidated(network: Network) {
        if (!awaitingValidation || network != lastNetwork || mediaPlayer == null || viaReserve()) return
        awaitingValidation = false
        reconnectScheduled = true
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, 300L)
    }

    private fun onNetworkLost(network: Network) {
        if (network != lastNetwork) return // an old network went away after we already switched
        lastNetwork = null
        networkLost = true
        handler.removeCallbacks(stallRunnable)
        if (!userPaused && !ended) showStatus("Нет сети — ждём подключение…")
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build().also { focusRequest = it }
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    /** UI is refreshed only from player events, only while controls are visible, at most once per second. */
    private fun updateProgress(force: Boolean) {
        val p = mediaPlayer ?: return
        if (userSeeking || inPip || !controlsVisible) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastUiUpdateAt < 1000L) return
        lastUiUpdateAt = now
        val length = p.length.coerceAtLeast(0L)
        // While a stream is (re)opening VLC reports 0: show where the film will continue instead of 00:00.
        val time = p.time.takeIf { it > 0L } ?: pendingSeekPos ?: playbackPosition.coerceAtLeast(0L)
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

    private fun showAudioTracks() {
        val tracks = mediaPlayer?.audioTracks.orEmpty()
        if (tracks.isEmpty()) { Toast.makeText(this, "Аудиодорожки пока не определены", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("Аудиодорожка")
            .setItems(tracks.map { it.name }.toTypedArray()) { _, which ->
                selectedAudioId = tracks[which].id; mediaPlayer?.setAudioTrack(tracks[which].id); supervisor.seeked()
                saveTrackPref("audio", tracks[which].name); showControlsTemporarily()
            }.setNegativeButton("Закрыть", null).show()
    }

    private fun showSubtitleTracks() {
        val tracks = mediaPlayer?.spuTracks.orEmpty()
        val labels = listOf("Выключить субтитры") + tracks.map { it.name }
        AlertDialog.Builder(this).setTitle("Субтитры")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) { selectedSpuId = -1; mediaPlayer?.setSpuTrack(-1); saveTrackPref("spu", SPU_OFF) }
                else { selectedSpuId = tracks[which - 1].id; mediaPlayer?.setSpuTrack(tracks[which - 1].id); saveTrackPref("spu", tracks[which - 1].name) }
                showControlsTemporarily()
            }.setNegativeButton("Закрыть", null).show()
    }

    private fun setControlsVisible(visible: Boolean) {
        if (visible && locked) return // locked: the panel comes back only after «Разблокировать»
        controlsVisible = visible
        val target = if (visible) 1f else 0f
        topBar.animate().alpha(target).translationY(if (visible) 0f else -dp(14).toFloat()).setDuration(220).start()
        controls.animate().alpha(target).translationY(if (visible) 0f else dp(18).toFloat()).setDuration(220).withEndAction {
            if (!controlsVisible) { controls.visibility = View.INVISIBLE; topBar.visibility = View.INVISIBLE }
        }.start()
        handler.removeCallbacks(hideControlsRunnable)
        if (visible) {
            controls.visibility = View.VISIBLE; topBar.visibility = View.VISIBLE
            updateProgress(force = true) // catch up after being hidden
            volumeBar.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (!inPip) handler.postDelayed(hideControlsRunnable, 3800)
        }
    }

    private fun showControlsTemporarily() = setControlsVisible(true)

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

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---- position persistence (across app restarts, keyed by host+path, query/token ignored) --
    private fun positionKey(): String? {
        currentWatchKey()?.let { return "w:$it" }
        val u = Uri.parse(currentUrl.ifBlank { return null })
        val key = u.host.orEmpty() + u.path.orEmpty()
        return if (key.isBlank()) null else "pos:$key"
    }

    private fun savedPosition(): Long = positionKey()?.let { prefs.getLong(it, 0L) } ?: 0L

    private fun persistPosition(time: Long, length: Long) {
        val key = positionKey() ?: return
        val nearEnd = length > 0L && time > length - 15_000L
        if (ended || nearEnd || time < 5_000L) prefs.edit().remove(key).apply()
        else prefs.edit().putLong(key, time).apply()
        // Progress for the library tiles ("продолжить с …", watched bar). Watched to the end = pos == len.
        currentWatchKey()?.let { wk ->
            if (time < 5_000L && !nearEnd) return@let
            val pos = if (nearEnd || (ended && length > 0L)) length else time
            watchPrefs.edit().putString(wk, "$pos|${length.coerceAtLeast(0L)}|${System.currentTimeMillis()}").apply()
        }
    }

    // ---- play / pause (button, headset, Bluetooth, PiP) ------------------------------------------
    private fun doPlay() {
        val p = mediaPlayer ?: return
        userPaused = false
        supervisor.resumed(); AppLog.write("play")
        if (needsReload || ended) { reconnect(forcePlay = true); return }
        resumeOnFocusGain = false
        requestAudioFocus()
        p.play()
    }

    private fun doPause() {
        userPaused = true
        supervisor.paused(); AppLog.write("pause at ${perceivedPosition() / 1000}s")
        mediaPlayer?.pause()
    }

    // ---- extras from the library: sidecar subtitles and watch-progress keys --------------------
    private fun readSideExtras(i: Intent?) {
        subUrls = i?.getStringArrayListExtra(EXTRA_SUB_URLS)?.filter { it.startsWith("https://") }.orEmpty()
        audioUrls = i?.getStringArrayListExtra(EXTRA_AUDIO_URLS)?.filter { it.startsWith("https://") }.orEmpty()
        subsForUrl = i?.getStringExtra(EXTRA_URL).orEmpty()
        watchKeys = i?.getStringArrayListExtra(EXTRA_WATCH_KEYS).orEmpty()
    }

    /** "magnetId|path" of what is playing (the playlist starts with the current episode, like watchKeys). */
    private fun currentWatchKey(): String? =
        if (queue.isNotEmpty() && queueIndex >= 0) watchKeys.getOrNull(queueIndex) else watchKeys.firstOrNull()

    // ---- remembered audio / subtitle choice (per series, with a global fallback) ---------------
    private fun seriesKey() = currentTitle.substringBefore(" · ").trim().lowercase(Locale.ROOT)

    private fun saveTrackPref(kind: String, name: String) {
        uiPrefs.edit().putString("track:$kind:${seriesKey()}", name).putString("track:$kind:*", name).apply()
    }

    private fun applyPreferredTracks(p: MediaPlayer) {
        val key = seriesKey()
        val audio = uiPrefs.getString("track:audio:$key", null) ?: uiPrefs.getString("track:audio:*", null)
        val spu = uiPrefs.getString("track:spu:$key", null) ?: uiPrefs.getString("track:spu:*", null)
        runCatching {
            pickTrack(p.audioTracks, audio)?.let { id -> if (id != p.audioTrack) { p.setAudioTrack(id); selectedAudioId = id; supervisor.seeked() } }
            if (spu == SPU_OFF) { if (p.spuTrack != -1) { p.setSpuTrack(-1); selectedSpuId = -1 } }
            else pickTrack(p.spuTracks, spu)?.let { id -> if (id != p.spuTrack) p.setSpuTrack(id); selectedSpuId = id }
        }
    }

    /** Same name first, then the same language tag ("[Russian]"), then a name that contains the old one. */
    private fun pickTrack(tracks: Array<MediaPlayer.TrackDescription>?, wanted: String?): Int? {
        if (wanted.isNullOrBlank() || tracks == null) return null
        val list = tracks.filter { it.id != -1 }
        list.firstOrNull { it.name == wanted }?.let { return it.id }
        val lang = Regex("""\[([^\]]+)]""").find(wanted)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        if (lang != null) list.firstOrNull { it.name.lowercase(Locale.ROOT).contains("[$lang]") }?.let { return it.id }
        val core = wanted.lowercase(Locale.ROOT).replace(Regex("""^(track|дорожка)\s*\d+\s*-\s*"""), "").trim()
        if (core.length >= 3) list.firstOrNull { it.name.lowercase(Locale.ROOT).contains(core) }?.let { return it.id }
        return null
    }

    // ---- next episode card -------------------------------------------------------------------
    /** End of an episode with a following one: show "Следующая серия" with a 10 s countdown. */
    private fun offerNextEpisode(): Boolean {
        if (queueIndex < 0 || queueIndex >= queue.size - 1) return false
        ended = true
        playWhenReady = false
        root.keepScreenOn = true // the countdown should not let the screen sleep
        setPlayIcon(false)
        nextCardTitle.text = queue[queueIndex + 1].title
        countdown = NEXT_COUNTDOWN_S
        nextCard.alpha = 0f; nextCard.translationY = dp(16).toFloat(); nextCard.visibility = View.VISIBLE
        nextCard.animate().alpha(1f).translationY(0f).setDuration(260).start()
        handler.removeCallbacks(countdownRunnable)
        handler.post(countdownRunnable)
        showControlsTemporarily()
        return true
    }

    private fun hideNextCard() {
        handler.removeCallbacks(countdownRunnable)
        // "Отмена" leaves the player on a finished episode: let the screen sleep again.
        if (ended) root.keepScreenOn = false
        if (::nextCard.isInitialized && nextCard.visibility == View.VISIBLE) {
            nextCard.animate().alpha(0f).setDuration(180).withEndAction { nextCard.visibility = View.GONE }.start()
        }
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
        val badge = unlockBadge.layoutParams as FrameLayout.LayoutParams
        val card = nextCard.layoutParams as FrameLayout.LayoutParams
        if (full) {
            sp.height = -1; sp.topMargin = 0
            videoLayout.layoutParams = (videoLayout.layoutParams as FrameLayout.LayoutParams).apply { width = -1; height = -1; gravity = Gravity.CENTER }
            infoPanel.visibility = View.GONE
            topBar.setPadding(dp(18) + i.left, dp(12) + i.top, dp(18) + i.right, dp(12))
            controls.setPadding(dp(20) + i.left, dp(12), dp(20) + i.right, dp(18) + i.bottom)
            optionsRow.visibility = View.VISIBLE
            centerRow.layoutParams = centerRow.layoutParams.apply { height = dp(82) }
            playPauseButton.scaleX = 1f; playPauseButton.scaleY = 1f
            card.rightMargin = dp(24); card.bottomMargin = dp(110)
            badge.gravity = Gravity.CENTER; badge.topMargin = 0
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
            card.rightMargin = dp(10); card.bottomMargin = dp(10)
            badge.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; badge.topMargin = i.top + h / 2 - dp(25)
            fullButton.setImageResource(R.drawable.ic_pl_full); fullButton.contentDescription = "На весь экран"
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
        stage.layoutParams = sp; infoPanel.layoutParams = pp; unlockBadge.layoutParams = badge; nextCard.layoutParams = card
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
    // Locked: nothing on the screen reacts (no pause, seek, brightness, panel); a touch only shows «Разблокировать».
    private var locked = false
    private lateinit var lockShield: View
    private lateinit var unlockBadge: TextView
    private val hideBadgeRunnable = Runnable {
        unlockBadge.animate().alpha(0f).setDuration(200).withEndAction { unlockBadge.visibility = View.GONE }.start()
    }

    private fun buildLock() {
        lockShield = View(this).apply {
            visibility = View.GONE
            setOnTouchListener { _, ev -> if (ev.actionMasked == MotionEvent.ACTION_UP) showUnlockBadge(); true }
        }
        root.addView(lockShield, FrameLayout.LayoutParams(-1, -1))
        unlockBadge = pillButton("🔓  Разблокировать").apply {
            textSize = 15f; setPadding(dp(24), 0, dp(24), 0); visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = dp(25).toFloat(); setColor(0xF011141B.toInt()); setStroke(dp(1), 0x88FFB02E.toInt()) }
            contentDescription = "Разблокировать экран"
            setOnClickListener { unlockScreen() }
        }
        root.addView(unlockBadge, FrameLayout.LayoutParams(-2, dp(50), Gravity.CENTER))
    }

    private fun lockScreen() {
        if (locked) return
        setControlsVisible(false)
        locked = true
        lockShield.visibility = View.VISIBLE
        showSeekLabel("🔒  Экран заблокирован")
        handler.postDelayed({ if (locked) showUnlockBadge() }, 700L) // shows once where to unlock
    }

    private fun showUnlockBadge() {
        if (!locked) return
        handler.removeCallbacks(hideBadgeRunnable)
        unlockBadge.animate().cancel()
        if (unlockBadge.visibility != View.VISIBLE) { unlockBadge.alpha = 0f; unlockBadge.visibility = View.VISIBLE }
        unlockBadge.animate().alpha(1f).setDuration(180).start()
        handler.postDelayed(hideBadgeRunnable, 3000L)
    }

    private fun unlockScreen() {
        locked = false
        handler.removeCallbacks(hideBadgeRunnable)
        unlockBadge.animate().cancel()
        unlockBadge.visibility = View.GONE
        lockShield.visibility = View.GONE
        showControlsTemporarily()
    }

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

    // ---- gestures: horizontal = seek, left edge vertical = brightness, right = volume ----------
    private fun onPlayerTouch(ev: MotionEvent) {
        scaleDetector.onTouchEvent(ev)
        if (ev.pointerCount > 1 || scaleDetector.isInProgress) {
            if (drag != Drag.None) cancelDrag()
            return
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragStartX = ev.x; dragStartY = ev.y; drag = Drag.None }
            MotionEvent.ACTION_MOVE -> {
                if (drag == Drag.None) startDragIfNeeded(ev) else updateDrag(ev)
                if (drag != Drag.None) return
            }
            MotionEvent.ACTION_UP -> if (drag != Drag.None) { finishDrag(); return }
            MotionEvent.ACTION_CANCEL -> if (drag != Drag.None) { cancelDrag(); return }
        }
        gestureDetector.onTouchEvent(ev)
    }

    private fun startDragIfNeeded(ev: MotionEvent) {
        val dx = ev.x - dragStartX; val dy = ev.y - dragStartY
        val h = stage.height.toFloat().coerceAtLeast(1f)
        // Leave the screen edges to the system (notification shade, home/back gestures).
        val edge = dp(40).toFloat()
        if (dragStartY < edge || dragStartY > h - edge) return
        val p = mediaPlayer
        drag = when {
            kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f &&
                p != null && (p.length > 0L || p.isSeekable) -> Drag.Seek
            kotlin.math.abs(dy) > touchSlop && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.5f ->
                if (dragStartX < stage.width / 2f) Drag.Brightness else Drag.Volume
            else -> Drag.None
        }
        if (drag == Drag.None) return
        // Stop the tap detector from firing a single tap on release.
        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        gestureDetector.onTouchEvent(cancel); cancel.recycle()
        dragStartX = ev.x; dragStartY = ev.y
        when (drag) {
            Drag.Seek -> { dragSeekBase = perceivedPosition(); dragSeekTarget = dragSeekBase; showControlsTemporarily() }
            Drag.Brightness -> dragStartValue = currentBrightness()
            Drag.Volume -> dragStartValue = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
            Drag.None -> {}
        }
    }

    private fun updateDrag(ev: MotionEvent) {
        val w = stage.width.toFloat().coerceAtLeast(1f); val h = stage.height.toFloat().coerceAtLeast(1f)
        when (drag) {
            Drag.Seek -> {
                val length = mediaPlayer?.length ?: -1L
                var t = dragSeekBase + ((ev.x - dragStartX) / w * SEEK_SWIPE_RANGE_MS).toLong()
                t = t.coerceAtLeast(0L)
                if (length > 0L) t = t.coerceAtMost((length - 1_000L).coerceAtLeast(0L))
                dragSeekTarget = t
                val diff = (t - dragSeekBase) / 1000
                val sign = if (diff >= 0) "+" else "−"
                showSeekLabel("$sign${formatTime(kotlin.math.abs(diff) * 1000)}  ·  ${formatTime(t)}" + if (length > 0L) " / ${formatTime(length)}" else "")
            }
            Drag.Brightness -> {
                val v = (dragStartValue - (ev.y - dragStartY) / (h * 0.8f)).coerceIn(0.01f, 1f)
                window.attributes = window.attributes.also { it.screenBrightness = v }
                showSeekLabel("☀  ${(v * 100).toInt()}%")
            }
            Drag.Volume -> {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val v = (dragStartValue - (ev.y - dragStartY) / (h * 0.8f) * max).toInt().coerceIn(0, max)
                setSystemVolume(v)
                volumeBar.progress = v
                showSeekLabel((if (v == 0) "🔇  " else "🔊  ") + "${v * 100 / max.coerceAtLeast(1)}%")
            }
            Drag.None -> {}
        }
    }

    private fun finishDrag() {
        if (drag == Drag.Seek && dragSeekTarget != dragSeekBase) seekTo(dragSeekTarget)
        drag = Drag.None
    }

    private fun cancelDrag() { drag = Drag.None }

    private fun currentBrightness(): Float {
        val own = window.attributes.screenBrightness
        if (own >= 0f) return own
        return runCatching {
            android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
        }.getOrDefault(0.5f).coerceIn(0.01f, 1f)
    }

    private fun setSystemVolume(v: Int) {
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
    }

    // ---- MediaSession: headset / Bluetooth buttons, PiP controls ---------------------------------
    private fun setupSession() {
        val s = runCatching { MediaSession(this, "SuslikPlayer") }.getOrNull() ?: return
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { doPlay() }
            override fun onPause() { doPause() }
            override fun onStop() { doPause() }
            override fun onSkipToNext() { if (queueIndex in 0 until queue.size - 1) playQueueEntry(queueIndex + 1) else tapSeek(true) }
            override fun onSkipToPrevious() { if (queueIndex > 0) playQueueEntry(queueIndex - 1) else tapSeek(false) }
            override fun onFastForward() { tapSeek(true) }
            override fun onRewind() { tapSeek(false) }
            override fun onSeekTo(pos: Long) { seekTo(pos) }
        })
        @Suppress("DEPRECATION")
        s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        s.isActive = true
        session = s
        updateSession()
    }

    /** State + position with its timestamp: the system extrapolates the position itself (no periodic updates). */
    private fun updateSession() {
        val s = session ?: return
        val p = mediaPlayer
        val playing = p?.isPlaying == true
        val state = when {
            p == null -> PlaybackState.STATE_NONE
            buffering -> PlaybackState.STATE_BUFFERING
            playing -> PlaybackState.STATE_PLAYING
            ended -> PlaybackState.STATE_STOPPED
            else -> PlaybackState.STATE_PAUSED
        }
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_STOP
        if (queueIndex in 0 until queue.size - 1) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (queueIndex > 0) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        runCatching {
            s.setPlaybackState(
                PlaybackState.Builder().setActions(actions)
                    .setState(state, perceivedPosition(), if (playing) 1f else 0f, SystemClock.elapsedRealtime()).build()
            )
            s.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, titleView.text?.toString() ?: currentTitle)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, (p?.length ?: -1L).coerceAtLeast(0L))
                    .build()
            )
        }
    }

    // ---- Picture-in-Picture ----------------------------------------------------------------
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters PiP by itself (setAutoEnterEnabled): smoother, and the video surface is not torn down.
        if (Build.VERSION.SDK_INT >= 26 && Build.VERSION.SDK_INT < 31 && mediaPlayer?.isPlaying == true) {
            try { enterPictureInPictureMode(buildPipParams(playing = true)) } catch (_: Exception) { }
        }
    }

    private fun updatePipParams(playing: Boolean) {
        if (Build.VERSION.SDK_INT < 26) return
        try { setPictureInPictureParams(buildPipParams(playing)) } catch (_: Exception) { }
    }

    @RequiresApi(26)
    private fun buildPipParams(playing: Boolean): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(pipAspect())
        val r = Rect()
        if (videoLayout.isAttachedToWindow && videoLayout.getGlobalVisibleRect(r) && !r.isEmpty) b.setSourceRectHint(r)
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(playing)
        return b.build()
    }

    /** Video's own aspect ratio when known (PiP accepts 1:2.39 … 2.39:1), otherwise 16:9. */
    private fun pipAspect(): Rational {
        val t = runCatching { mediaPlayer?.currentVideoTrack }.getOrNull()
        if (t != null && t.width > 0 && t.height > 0) {
            val sar = if (t.sarNum > 0 && t.sarDen > 0) t.sarNum.toDouble() / t.sarDen else 1.0
            val w = (t.width * sar).toInt()
            val ratio = w.toDouble() / t.height
            if (ratio in 0.42..2.38) return Rational(w, t.height)
        }
        return Rational(16, 9)
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
        if (!networkLost && !buffering) bufferingLabel.visibility = View.GONE
    }

    // ---- lifecycle -------------------------------------------------------------------------
    override fun onStart() {
        super.onStart()
        session?.isActive = true
        handler.removeCallbacks(idleReleaseRunnable)
        val away = SystemClock.uptimeMillis() - stoppedAt
        when {
            keptOnStop && mediaPlayer != null && away < FAST_RESUME_MS && activeNetworkNow() == networkAtStop -> resumeAfterStop()
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
        val t = p.time
        if (t > 0L) seekTo(t)
    }

    private var stoppedAt = 0L
    private var networkAtStop: Network? = null // Wi-Fi <-> mobile while locked = the paused connection is dead
    private fun activeNetworkNow(): Network? =
        runCatching { (getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager)?.activeNetwork }.getOrNull()

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
        stoppedAt = SystemClock.uptimeMillis()
        networkAtStop = activeNetworkNow()
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
        for (r in listOf(watchdogRunnable, stallRunnable, reconnectRunnable, seekWatchdogRunnable, prefsRunnable))
            handler.removeCallbacks(r)
        unregisterNetworkCallback()
        if (noisyRegistered) { runCatching { unregisterReceiver(noisyReceiver) }; noisyRegistered = false }
        abandonAudioFocus()
        root.keepScreenOn = false
        handler.postDelayed(idleReleaseRunnable, IDLE_RELEASE_MS)
    }

    private fun resumeAfterStop() {
        val p = mediaPlayer ?: return
        keptOnStop = false
        runCatching { p.attachViews(videoLayout, null, true, false) }
        hookSurfaces()
        handler.removeCallbacks(videoBackRunnable)
        handler.postDelayed(videoBackRunnable, 150L) // once the new surface exists
        ContextCompat.registerReceiver(this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        noisyRegistered = true
        registerNetworkCallback()
        AppLog.write("back after ${(SystemClock.uptimeMillis() - stoppedAt) / 1000}s: continue")
        if (playOnStart && !userPaused) {
            supervisor.resumed()
            requestAudioFocus()
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
        mediaPlayer?.let { persistPosition(perceivedPosition(), it.length) }
        currentUrl = url
        currentTitle = intent.getStringExtra(EXTRA_TITLE)?.ifBlank { "Видео" } ?: "Видео"
        queue = emptyList(); queueIndex = -1
        readSideExtras(intent)
        hideNextCard()
        titleView.text = currentTitle
        updateQueueUi()
        selectedAudioId = null; selectedSpuId = null
        playbackPosition = savedPosition(); lastTime = playbackPosition
        pendingSeekPos = null; ended = false; userPaused = false; playWhenReady = true; retryCount = 0; needsReload = false
        if (mediaPlayer != null) reconnect(forcePlay = true, startAt = playbackPosition)
        intent.getStringExtra(EXTRA_PLAYLIST)?.takeIf { it.isNotBlank() }?.let { fetchPlaylist(it) }
    }

    override fun onDestroy() {
        alive = false
        orientationWatch?.disable(); orientationWatch = null
        CrashReports.playerRunning(this, false)
        supervisor.closed()
        handler.removeCallbacksAndMessages(null)
        if (keptOnStop) { keptOnStop = false; releasePlayer() }
        if (isFinishing) BurstProxy.clear() // the film's cached part is no longer needed
        session?.release(); session = null
        for (sv in hookedSurfaces) runCatching { sv.holder.removeCallback(surfaceCallback) }
        hookedSurfaces.clear()
        super.onDestroy()
    }

    override fun onStop() {
        mediaPlayer?.let { p ->
            playbackPosition = perceivedPosition()
            persistPosition(playbackPosition, p.length)
        }
        // playWhenReady is maintained by events, so a mid-buffering stop no longer resumes paused.
        handler.removeCallbacks(hideControlsRunnable)
        handler.removeCallbacks(countdownRunnable)
        val p = mediaPlayer
        if (p != null && !isFinishing && !isChangingConfigurations) keepOnStop(p) else releasePlayer()
        session?.isActive = false
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

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0L) / 1000L
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        else String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }

    companion object {
        /** This screen exists right now (CrashReports tells a crash from a normal close by it). */
        @Volatile var alive = false
        const val EXTRA_URL = "media_url"
        const val EXTRA_TITLE = "media_title"
        const val EXTRA_PLAYLIST = "media_playlist"
        private const val KEY_URL = "current_url"
        private val NIGHT = 0xFF090B10.toInt()
        private const val KEY_TITLE = "current_title"
        private const val KEY_POSITION = "playback_position"
        private const val KEY_PLAYING = "play_when_ready"
        private const val STALL_TIMEOUT_MS = 12_000L
        private const val OPEN_TIMEOUT_MS = 20_000L
        private const val WATCHDOG_TICK_MS = 2_000L
        private const val FAST_RESUME_MS = 20_000L // back within this: just continue the paused player
        private const val IDLE_RELEASE_MS = 10 * 60_000L // locked longer than this: free VLC and the connection
        const val EXTRA_SUB_URLS = "media_sub_urls"
        const val EXTRA_WATCH_KEYS = "media_watch_keys"
        const val EXTRA_AUDIO_URLS = "media_audio_urls"
        private const val SPU_OFF = "__off__"
        private const val SEEK_SWIPE_RANGE_MS = 180_000L // full screen width = 3 min
        private const val NEXT_COUNTDOWN_S = 10
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
        private const val REPEAT_TAP_WINDOW_MS = 900L
        private const val STABLE_PLAYBACK_MS = 30_000L
        private const val MAX_RETRY_DELAY_MS = 15_000L
        private const val MAX_RETRIES = 5
    }
}
