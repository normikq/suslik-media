package ru.suslikovanora.media.desktop

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * Where each film sits in the library (poster tiles and «Продолжить просмотр» cards), so leaving the player can fly
 * the film's frame back into its own poster. Positions are read live on every frame of the exit: the library is
 * still scaling in and the «continue» row may reorder at that moment.
 */
object TileBounds {
    val poster = HashMap<Long, LayoutCoordinates>()
    val cont = HashMap<Long, LayoutCoordinates>()
    var viewport: LayoutCoordinates? = null
    /** The film was opened from the «continue» row (fly back there first). */
    var fromContinue = false

    private fun rectOf(c: LayoutCoordinates?): Rect? {
        if (c == null || !c.isAttached) return null
        val a = c.localToWindow(Offset.Zero)
        val b = c.localToWindow(Offset(c.size.width.toFloat(), c.size.height.toFloat()))
        return Rect(a, b).takeIf { it.width > 1f && it.height > 1f }
    }

    /** The poster tile of film [id] in the window (px), wherever it is (even partly off screen). */
    fun posterRect(id: Long): Rect? = rectOf(poster[id])

    /** The visible place of film [id] in the window (px), or null (another tab, scrolled away, not in the list). */
    fun target(id: Long?): Rect? {
        if (id == null) return null
        val vp = rectOf(viewport) ?: return null
        fun visible(c: LayoutCoordinates?) = rectOf(c)?.takeIf { r ->
            val i = r.intersect(vp)
            i.width >= r.width * 0.6f && i.height >= r.height * 0.6f
        }
        val a = visible(cont[id]); val b = visible(poster[id])
        return if (fromContinue) a ?: b else b ?: a
    }
}

/** A rounded window [x, y, w, h] into the layer (in the layer's own, unscaled coordinates). */
private class FrameWindow(val x: Float, val y: Float, val w: Float, val h: Float, val r: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rounded(RoundRect(x, y, x + w, y + h, CornerRadius(r)))
}

/**
 * The exit «flight»: the full-window frame shrinks into [target] (the poster's place in the window) as [e] goes 0 → 1.
 * The picture is never stretched: it is scaled uniformly (to cover the poster) and a rounded window over it
 * narrows from the whole screen to the poster's shape, gliding to the poster's position. Fades out at the very end,
 * dissolving into the poster underneath.
 */
fun GraphicsLayerScope.flyInto(target: Rect, e: Float) {
    val w = size.width; val h = size.height
    if (w <= 0f || h <= 0f) return
    fun lerp(a: Float, b: Float) = a + (b - a) * e
    val left = lerp(0f, target.left); val top = lerp(0f, target.top)
    val rw = lerp(w, target.width); val rh = lerp(h, target.height)
    val s = lerp(1f, maxOf(target.width / w, target.height / h))
    val lw = rw / s; val lh = rh / s               // the visible window in the frame's own coordinates…
    val lx = (w - lw) / 2f; val ly = (h - lh) / 2f // …always the middle of the picture
    transformOrigin = TransformOrigin(0f, 0f)
    scaleX = s; scaleY = s
    translationX = left - s * lx; translationY = top - s * ly
    shape = FrameWindow(lx, ly, lw, lh, lerp(0f, 14f * density) / s)
    clip = true
    alpha = ((1f - e) / 0.28f).coerceIn(0f, 1f)
}
