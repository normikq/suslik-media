package ru.suslikovanora.media.player

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Subtitles always on the screen. «Заполнить», «Растянуть», «16:9» make the picture bigger than the screen and cut its
 * edges; the subtitles' layer was as big as the picture, so the bottom line went under the screen's edge. Here the
 * layer gets the place of the whole frame fitted into the visible area (as with «Вписать»), centred: the picture
 * is still cropped, the subtitles are not.
 *
 * [aspect] — the frame's width / height (≤ 0 = not known yet: the layer fills [subs]' parent).
 */
internal fun fitSubtitles(subs: View, aspect: Float) {
    val parent = subs.parent as? View ?: return
    val cw = parent.width
    val ch = parent.height
    if (cw <= 0 || ch <= 0) return
    val (w, h) = if (aspect <= 0f) cw to ch else {
        val k = min(cw / aspect, ch.toFloat())
        (k * aspect).roundToInt().coerceAtMost(cw) to k.roundToInt().coerceAtMost(ch)
    }
    val lp = subs.layoutParams as? FrameLayout.LayoutParams
    if (lp != null && lp.width == w && lp.height == h && lp.gravity == Gravity.CENTER) return
    // A new object: VLC hands one LayoutParams to both its video and subtitles surfaces — changing it would move the video.
    subs.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
}
