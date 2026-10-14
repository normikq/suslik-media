package ru.suslikovanora.media.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

class PlayerGestures(
    private val context: Context,
    private val callback: Callback
) {
    interface Callback {
        fun getStageWidth(): Float
        fun getStageHeight(): Float
        fun isSeekable(): Boolean
        fun getMediaLength(): Long
        fun getPerceivedPosition(): Long
        fun onSeekTo(position: Long)
        fun onTapSeek(forward: Boolean)
        fun onToggleControls()
        fun onShowControlsTemporarily()
        fun onScaleChanged(scaleIndex: Int)
        fun onShowSeekLabel(text: String)
        fun getScreenBrightness(): Float
        fun setScreenBrightness(brightness: Float)
        fun getStreamVolume(): Int
        fun getStreamMaxVolume(): Int
        fun setStreamVolume(volume: Int)
        fun onVolumeChanged(current: Int, max: Int)
        fun dp(value: Int): Int
    }

    private enum class Drag { None, Seek, Brightness, Volume }
    private var drag = Drag.None
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartValue = 0f
    private var dragSeekBase = 0L
    private var dragSeekTarget = 0L
    private var pinchFactor = 1f
    private var lastSeekTapAt = 0L

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop * 2f

    private val handler = Handler(Looper.getMainLooper())
    private var pendingTapRunnable: Runnable? = null
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var downTime = 0L

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { pinchFactor = 1f; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean { pinchFactor *= detector.scaleFactor; return true }
        override fun onScaleEnd(detector: ScaleGestureDetector) {
            // Pinch out = fill the screen (crop the black bars), pinch in = whole frame.
            if (pinchFactor > 1.08f) callback.onScaleChanged(1) else if (pinchFactor < 0.92f) callback.onScaleChanged(0)
        }
    })

    fun recordTapSeek() {
        lastSeekTapAt = SystemClock.uptimeMillis()
    }

    fun onTouch(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        if (ev.pointerCount > 1 || scaleDetector.isInProgress) {
            pendingTapRunnable?.let { handler.removeCallbacks(it); pendingTapRunnable = null }
            if (drag != Drag.None) cancelDrag()
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = ev.x
                dragStartY = ev.y
                drag = Drag.None
                downTime = SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag == Drag.None) startDragIfNeeded(ev) else updateDrag(ev)
                if (drag != Drag.None) {
                    pendingTapRunnable?.let { handler.removeCallbacks(it); pendingTapRunnable = null }
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (drag != Drag.None) {
                    finishDrag()
                    return true
                }
                val dx = ev.x - dragStartX
                val dy = ev.y - dragStartY
                val dist = hypot(dx, dy)
                val duration = SystemClock.uptimeMillis() - downTime
                if (dist <= touchSlop && duration <= 650L) {
                    val now = SystemClock.uptimeMillis()
                    val isRapidSeek = now - lastSeekTapAt < REPEAT_TAP_WINDOW_MS
                    val isDoubleTap = !isRapidSeek && (now - lastTapTime < 320L) &&
                        (hypot(ev.x - lastTapX, ev.y - lastTapY) < touchSlop * 1.5f)

                    if (isDoubleTap || isRapidSeek) {
                        pendingTapRunnable?.let { handler.removeCallbacks(it); pendingTapRunnable = null }
                        recordTapSeek()
                        callback.onTapSeek(ev.x >= callback.getStageWidth() / 2f)
                    } else {
                        lastTapTime = now
                        lastTapX = ev.x
                        lastTapY = ev.y
                        val tapX = ev.x
                        val runnable = Runnable {
                            pendingTapRunnable = null
                            if (SystemClock.uptimeMillis() - lastSeekTapAt < REPEAT_TAP_WINDOW_MS) {
                                recordTapSeek()
                                callback.onTapSeek(tapX >= callback.getStageWidth() / 2f)
                            } else {
                                callback.onToggleControls()
                            }
                        }
                        pendingTapRunnable = runnable
                        handler.postDelayed(runnable, 280L)
                    }
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pendingTapRunnable?.let { handler.removeCallbacks(it); pendingTapRunnable = null }
                if (drag != Drag.None) { cancelDrag(); return true }
            }
        }
        return true
    }

    private fun startDragIfNeeded(ev: MotionEvent) {
        val dx = ev.x - dragStartX
        val dy = ev.y - dragStartY
        val h = callback.getStageHeight().coerceAtLeast(1f)
        val edge = callback.dp(40).toFloat()
        if (dragStartY < edge || dragStartY > h - edge) return

        drag = when {
            abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f && callback.isSeekable() -> Drag.Seek
            abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.5f ->
                if (dragStartX < callback.getStageWidth() / 2f) Drag.Brightness else Drag.Volume
            else -> Drag.None
        }
        if (drag == Drag.None) return

        dragStartX = ev.x
        dragStartY = ev.y
        when (drag) {
            Drag.Seek -> {
                dragSeekBase = callback.getPerceivedPosition()
                dragSeekTarget = dragSeekBase
                callback.onShowControlsTemporarily()
            }
            Drag.Brightness -> dragStartValue = callback.getScreenBrightness()
            Drag.Volume -> dragStartValue = callback.getStreamVolume().toFloat()
            Drag.None -> {}
        }
    }

    private fun updateDrag(ev: MotionEvent) {
        val w = callback.getStageWidth().coerceAtLeast(1f)
        val h = callback.getStageHeight().coerceAtLeast(1f)
        when (drag) {
            Drag.Seek -> {
                val length = callback.getMediaLength()
                var t = dragSeekBase + ((ev.x - dragStartX) / w * SEEK_SWIPE_RANGE_MS).toLong()
                t = t.coerceAtLeast(0L)
                if (length > 0L) t = t.coerceAtMost((length - 1_000L).coerceAtLeast(0L))
                dragSeekTarget = t
                val diff = (t - dragSeekBase) / 1000
                val sign = if (diff >= 0) "+" else "−"
                callback.onShowSeekLabel(
                    "$sign${formatTime(abs(diff) * 1000)}  ·  ${formatTime(t)}" +
                        if (length > 0L) " / ${formatTime(length)}" else ""
                )
            }
            Drag.Brightness -> {
                val v = (dragStartValue - (ev.y - dragStartY) / (h * 0.8f)).coerceIn(0.01f, 1f)
                callback.setScreenBrightness(v)
                callback.onShowSeekLabel("☀  ${(v * 100).toInt()}%")
            }
            Drag.Volume -> {
                val max = callback.getStreamMaxVolume()
                val v = (dragStartValue - (ev.y - dragStartY) / (h * 0.8f) * max).toInt().coerceIn(0, max)
                callback.setStreamVolume(v)
                callback.onVolumeChanged(v, max)
                callback.onShowSeekLabel((if (v == 0) "🔇  " else "🔊  ") + "${v * 100 / max.coerceAtLeast(1)}%")
            }
            Drag.None -> {}
        }
    }

    private fun finishDrag() {
        if (drag == Drag.Seek && dragSeekTarget != dragSeekBase) callback.onSeekTo(dragSeekTarget)
        drag = Drag.None
    }

    private fun cancelDrag() {
        drag = Drag.None
    }

    companion object {
        private const val SEEK_SWIPE_RANGE_MS = 180_000L // full screen width = 3 min
        private const val REPEAT_TAP_WINDOW_MS = 900L

        fun formatTime(ms: Long): String {
            val totalSeconds = ms.coerceAtLeast(0L) / 1000L
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
            else String.format(Locale.getDefault(), "%02d:%02d", m, s)
        }
    }
}
