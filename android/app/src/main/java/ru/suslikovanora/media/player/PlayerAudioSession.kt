package ru.suslikovanora.media.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat

class PlayerAudioSession(
    private val context: Context,
    private val callback: Callback
) {
    interface Callback {
        fun onMediaPlay()
        fun onMediaPause()
        fun onSeekTo(pos: Long)
        fun onSkipToNext()
        fun onSkipToPrevious()
        fun onFastForward()
        fun onRewind()
        fun isPlaying(): Boolean
        fun onTransientPause()
        fun onFocusLossPause()
        fun onFocusGainResume()
        fun onBecomingNoisy()
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var session: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var noisyRegistered = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                callback.onFocusLossPause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (callback.isPlaying()) {
                    resumeOnFocusGain = true
                    callback.onTransientPause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    callback.onFocusGainResume()
                }
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                callback.onBecomingNoisy()
            }
        }
    }

    fun setup() {
        val s = runCatching { MediaSession(context, "SuslikPlayer") }.getOrNull() ?: return
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { callback.onMediaPlay() }
            override fun onPause() { callback.onMediaPause() }
            override fun onStop() { callback.onMediaPause() }
            override fun onSkipToNext() { callback.onSkipToNext() }
            override fun onSkipToPrevious() { callback.onSkipToPrevious() }
            override fun onFastForward() { callback.onFastForward() }
            override fun onRewind() { callback.onRewind() }
            override fun onSeekTo(pos: Long) { callback.onSeekTo(pos) }
        })
        @Suppress("DEPRECATION")
        s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        s.isActive = true
        session = s
    }

    fun update(
        title: String,
        duration: Long,
        position: Long,
        isPlaying: Boolean,
        isBuffering: Boolean,
        isEnded: Boolean,
        hasPlayer: Boolean,
        canSkipNext: Boolean,
        canSkipPrevious: Boolean
    ) {
        val s = session ?: return
        val state = when {
            !hasPlayer -> PlaybackState.STATE_NONE
            isBuffering -> PlaybackState.STATE_BUFFERING
            isPlaying -> PlaybackState.STATE_PLAYING
            isEnded -> PlaybackState.STATE_STOPPED
            else -> PlaybackState.STATE_PAUSED
        }
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_STOP
        if (canSkipNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (canSkipPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        runCatching {
            s.setPlaybackState(
                PlaybackState.Builder().setActions(actions)
                    .setState(state, position, if (isPlaying) 1f else 0f, SystemClock.elapsedRealtime()).build()
            )
            s.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                    .build()
            )
        }
    }

    fun requestAudioFocus() {
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

    fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }

    fun registerNoisyReceiver() {
        if (!noisyRegistered) {
            ContextCompat.registerReceiver(
                context, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyRegistered = true
        }
    }

    fun unregisterNoisyReceiver() {
        if (noisyRegistered) {
            runCatching { context.unregisterReceiver(noisyReceiver) }
            noisyRegistered = false
        }
    }

    val isPausedForFocus: Boolean get() = resumeOnFocusGain

    fun onUserPlay() {
        resumeOnFocusGain = false
    }

    fun setActive(active: Boolean) {
        session?.isActive = active
    }

    fun release() {
        unregisterNoisyReceiver()
        abandonAudioFocus()
        session?.release()
        session = null
    }

    fun getStreamVolume(): Int = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    fun getStreamMaxVolume(): Int = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    fun setStreamVolume(volume: Int) {
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0) }
    }
}
