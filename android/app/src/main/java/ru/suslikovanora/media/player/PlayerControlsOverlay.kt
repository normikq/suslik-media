package ru.suslikovanora.media.player

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ru.suslikovanora.media.tr

/**
 * Manages playback controls overlay visibility, auto-hide timer,
 * screen lock shield with unlock button, transient seek label, and system UI bars.
 */
class PlayerControlsOverlay(
    private val context: Context,
    private val handler: Handler,
    private val callback: Callback
) {
    interface Callback {
        fun isPip(): Boolean
        fun onControlsShown()
        fun onControlsHidden()
        /** The Compose panel ([PlayerOverlay]) shows / hides itself with its own animation. */
        fun onPanelShown(shown: Boolean)
        /** False while a sheet (tracks, settings) is open: the panel stays until it is closed. */
        fun canAutoHide(): Boolean
    }

    var isVisible: Boolean = true
        private set

    var isLocked: Boolean = false
        private set

    private var seekLabel: TextView? = null
    private var lockShield: View? = null
    private var unlockBadge: TextView? = null

    private val hideControlsRunnable = object : Runnable {
        override fun run() {
            if (callback.canAutoHide()) setVisible(false) else handler.postDelayed(this, CONTROLS_TIMEOUT_MS)
        }
    }
    private val hideBadgeRunnable = Runnable {
        unlockBadge?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
            unlockBadge?.visibility = View.GONE
        }?.start()
    }
    private val seekLabelHideRunnable = Runnable {
        seekLabel?.visibility = View.GONE
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun attachViews(
        root: ViewGroup,
        seekLabel: TextView,
        pillButtonFactory: (String) -> TextView
    ) {
        this.seekLabel = seekLabel

        val shield = View(context).apply {
            visibility = View.GONE
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_UP) showUnlockBadge()
                true
            }
        }
        root.addView(shield, FrameLayout.LayoutParams(-1, -1))
        this.lockShield = shield

        val badge = pillButtonFactory(tr("playercontrols.razblokirovat")).apply {
            textSize = 15f
            setPadding(dp(24), 0, dp(24), 0)
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(25).toFloat()
                setColor(0xE6161B26.toInt())
                setStroke(dp(1), 0x4DFFFFFF)
            }
            contentDescription = tr("playercontrols.razblokirovat_ekran")
            setOnClickListener { unlock() }
        }
        root.addView(badge, FrameLayout.LayoutParams(-2, dp(50), Gravity.CENTER))
        this.unlockBadge = badge
    }

    fun setVisible(visible: Boolean) {
        if (visible && isLocked) return // locked: the panel comes back only after unlock
        isVisible = visible
        handler.removeCallbacks(hideControlsRunnable)
        callback.onPanelShown(visible)
        if (visible) {
            callback.onControlsShown()
            if (!callback.isPip()) handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS)
        } else {
            callback.onControlsHidden()
        }
    }

    fun showTemporarily() = setVisible(true)

    /** A remote key while the panel is up: keep it up (the hide timer starts again), without re-running the entrance. */
    fun keepShown() {
        if (!isVisible || callback.isPip()) return
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS)
    }

    fun toggle() {
        if (isVisible) setVisible(false) else showTemporarily()
    }

    fun lock() {
        if (isLocked) return
        setVisible(false)
        isLocked = true
        lockShield?.visibility = View.VISIBLE
        showSeekLabel(tr("playercontrols.ekran_zablokirovan"))
        handler.postDelayed({ if (isLocked) showUnlockBadge() }, LOCK_HINT_DELAY_MS)
    }

    fun unlock() {
        isLocked = false
        handler.removeCallbacks(hideBadgeRunnable)
        unlockBadge?.animate()?.cancel()
        unlockBadge?.visibility = View.GONE
        lockShield?.visibility = View.GONE
        showTemporarily()
    }

    fun showUnlockBadge() {
        if (!isLocked) return
        handler.removeCallbacks(hideBadgeRunnable)
        val badge = unlockBadge ?: return
        badge.animate().cancel()
        if (badge.visibility != View.VISIBLE) {
            badge.alpha = 0f
            badge.visibility = View.VISIBLE
        }
        badge.animate().alpha(1f).setDuration(180).start()
        badge.requestFocus() // remote control: OK unlocks (touch mode ignores this)
        handler.postDelayed(hideBadgeRunnable, UNLOCK_BADGE_TIMEOUT_MS)
    }

    fun updateUnlockBadgeLayout(full: Boolean, topInset: Int, filmHeight: Int) {
        val badge = unlockBadge?.layoutParams as? FrameLayout.LayoutParams ?: return
        if (full) {
            badge.gravity = Gravity.CENTER
            badge.topMargin = 0
        } else {
            badge.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            badge.topMargin = topInset + filmHeight / 2 - dp(25)
        }
        unlockBadge?.layoutParams = badge
    }

    fun showSeekLabel(text: String) {
        val label = seekLabel ?: return
        label.text = text
        label.visibility = View.VISIBLE
        handler.removeCallbacks(seekLabelHideRunnable)
        handler.postDelayed(seekLabelHideRunnable, SEEK_LABEL_TIMEOUT_MS)
    }

    fun hideSystemUi(window: Window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun showSystemUi(window: Window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    fun onStop() {
        handler.removeCallbacks(hideControlsRunnable)
        isVisible = false
        callback.onControlsHidden()
    }

    fun destroy() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.removeCallbacks(hideBadgeRunnable)
        handler.removeCallbacks(seekLabelHideRunnable)
        unlockBadge?.animate()?.cancel()
        callback.onControlsHidden()
    }

    companion object {
        const val CONTROLS_TIMEOUT_MS = 3800L
        const val UNLOCK_BADGE_TIMEOUT_MS = 3000L
        const val SEEK_LABEL_TIMEOUT_MS = 1200L
        const val LOCK_HINT_DELAY_MS = 700L
    }
}
