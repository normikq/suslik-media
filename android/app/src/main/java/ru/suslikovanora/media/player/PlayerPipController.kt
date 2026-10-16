package ru.suslikovanora.media.player

import android.app.PictureInPictureParams
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import android.view.View
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi

/**
 * Manages Picture-in-Picture (PiP) mode, aspect ratio calculations,
 * and seamless background entry for Android 8.0+ / 12+.
 */
class PlayerPipController(
    private val activity: ComponentActivity,
    private val videoLayout: View,
    private val callback: Callback
) {
    interface Callback {
        fun getCurrentPlayer(): PlayerEngine?
        fun onPipModeChanged(inPip: Boolean)
    }

    var inPip: Boolean = false
        private set

    fun setup() {
        if (Build.VERSION.SDK_INT < 26) return
        activity.addOnPictureInPictureModeChangedListener { info ->
            inPip = info.isInPictureInPictureMode
            callback.onPipModeChanged(inPip)
        }
    }

    /**
     * On Android 8.0..11, user leaving home/overview triggers PiP manually.
     * On Android 12+, auto-enter handles this seamlessly without tearing down video surface.
     */
    fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT in 26..30 && callback.getCurrentPlayer()?.isPlaying == true) {
            try {
                activity.enterPictureInPictureMode(buildPipParams(playing = true))
            } catch (_: Exception) { }
        }
    }

    fun updatePipParams(playing: Boolean) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            activity.setPictureInPictureParams(buildPipParams(playing))
        } catch (_: Exception) { }
    }

    @RequiresApi(26)
    fun buildPipParams(playing: Boolean): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(pipAspect())
        val r = Rect()
        if (videoLayout.isAttachedToWindow && videoLayout.getGlobalVisibleRect(r) && !r.isEmpty) {
            b.setSourceRectHint(r)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(playing)
        }
        return b.build()
    }

    /** Video's own aspect ratio when known (PiP accepts 1:2.39 … 2.39:1), otherwise 16:9. */
    fun pipAspect(): Rational {
        val t = callback.getCurrentPlayer()?.videoTrack
        if (t != null && t.width > 0 && t.height > 0) {
            val sar = if (t.sarNum > 0 && t.sarDen > 0) t.sarNum.toDouble() / t.sarDen else 1.0
            val w = (t.width * sar).toInt()
            val ratio = w.toDouble() / t.height
            if (ratio in 0.42..2.38) return Rational(w, t.height)
        }
        return Rational(16, 9)
    }
}
