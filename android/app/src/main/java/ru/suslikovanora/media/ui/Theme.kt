package ru.suslikovanora.media.ui

import ru.suslikovanora.media.core.*

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.suslikovanora.media.R

/** «Проекторная» — design tokens and glass style ported from desktop. */
object Sm {
    val night = Color(0xFF090B10)
    val booth = Color(0xFF11141B)
    val film = Color(0xFF181C26)
    val line = Color(0xFF252A37)
    val line2 = Color(0xFF333A4B)
    val text = Color(0xFFEEF0F5)
    val muted = Color(0xFF8E96A8)
    val lamp = Color(0xFFFFB02E)
    val lampSoft = Color(0x24FFB02E)
    val lampInk = Color(0xFF1C1200)
    val ok = Color(0xFF7FE0B0)
    val bad = Color(0xFFFF9B8F)
    val warn = Color(0xFFFFD9A8)
    val scrim = Color(0xCC090B10)

    /** cubic-bezier(.22,.8,.24,1) — the site's --ease. */
    val ease = CubicBezierEasing(0.22f, 0.8f, 0.24f, 1f)
    val fly = CubicBezierEasing(0.45f, 0.05f, 0.2f, 1f)

    // «Матовое стекло»: полупрозрачные слои поверх мягкого свечения фона (redesign).
    val backdrop = Color(0xFF060911)
    val accent = Color(0xFF2563EB)
    val accentEdge = Color(0x4D60A5FA)
    val glassSide = Color(0x94080C16)
    val glassFill = Color(0x4D2A3A5C)
    val glassFillHover = Color(0xAD1C2844)
    val glassEdge = Color(0x17FFFFFF)
    val glassEdgeHover = Color(0x6660A5FA)
    val sheet = Color(0xF00B1020)
    val sheetEdge = Color(0x2EFFFFFF)
    val accentSoft = Color(0x402563EB)
}

/** Glass surface: translucent fill, hairline light edge. */
fun Modifier.glass(shape: Shape, hovered: Boolean = false): Modifier =
    clip(shape)
        .background(if (hovered) Sm.glassFillHover else Sm.glassFill)
        .border(1.dp, if (hovered) Sm.glassEdgeHover else Sm.glassEdge, shape)

/** Dialog card: nearly opaque dark glass with a light hairline edge. */
fun Modifier.glassSheet(shape: Shape): Modifier =
    clip(shape)
        .background(Sm.sheet)
        .border(1.dp, Sm.sheetEdge, shape)

/**
 * Soft blue / violet / cyan glows behind everything, ported from desktop. Drawn once per window size into a picture:
 * three full-screen gradients computed again at every frame of a scroll cost the render thread a frame now and then.
 */
fun Modifier.ambientBackdrop(): Modifier =
    background(Sm.backdrop).drawWithCache {
        val w = size.width.toInt().coerceAtLeast(1)
        val h = size.height.toInt().coerceAtLeast(1)
        val picture = androidx.compose.ui.graphics.ImageBitmap(w, h)
        androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(this, layoutDirection, androidx.compose.ui.graphics.Canvas(picture), size) {
            fun glow(c: Color, cx: Float, cy: Float, r: Float) {
                val at = Offset(cx, cy)
                drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), at, r), r, at)
            }
            glow(Color(0x382563EB), size.width * 0.22f, 0f, size.minDimension * 0.85f)
            glow(Color(0x2E8B5CF6), size.width * 0.95f, size.height * 0.95f, size.minDimension * 0.75f)
            glow(Color(0x2406B6D4), size.width * 0.55f, size.height * 0.45f, size.minDimension * 0.6f)
        }
        onDrawBehind { drawImage(picture) }
    }

/**
 * A slow 0 → 1 → 0 pulse for decorations. Pauses when window is not focused. [cycles] pulses, then it rests at 0:
 * a pulse that never stops redraws the whole window 120 times a second (24.10 journal: ~600 frames per 5 s with the
 * library standing still, a third of a core on the render thread, and the scroll stuttering on top of it).
 */
@Composable
fun breathing(periodMs: Int, active: Boolean = true, cycles: Int = Int.MAX_VALUE): androidx.compose.runtime.State<Float> {
    val a = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val done = androidx.compose.runtime.remember { intArrayOf(0) }
    val focused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    androidx.compose.runtime.LaunchedEffect(active, focused) {
        if (active && focused) while (done[0] < cycles) {
            a.animateTo(1f, androidx.compose.animation.core.tween(periodMs, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            a.animateTo(0f, androidx.compose.animation.core.tween(periodMs, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            done[0]++
        }
    }
    return a.asState()
}

/** Pulses of the logo's glow after the screen opens: then it stays still (see [breathing]). */
const val LOGO_PULSES = 3

val Unbounded = FontFamily(
    Font(R.font.unbounded_medium, FontWeight.Medium),
    Font(R.font.unbounded_bold, FontWeight.Bold)
)

val Onest = FontFamily(
    Font(R.font.onest_regular, FontWeight.Normal),
    Font(R.font.onest_medium, FontWeight.Medium),
    Font(R.font.onest_semibold, FontWeight.SemiBold)
)

private val base = TextStyle(fontFamily = Onest, color = Sm.text)

private val typography = Typography(
    bodyLarge = base.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = base.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = base.copy(fontSize = 12.5.sp, lineHeight = 17.sp, color = Sm.muted),
    labelLarge = base.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 21.sp, lineHeight = 26.sp, color = Sm.text, letterSpacing = (-0.3).sp),
    titleMedium = base.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
)

@Composable
fun SuslikTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Sm.accent, onPrimary = Color.White,
            background = Sm.backdrop, onBackground = Sm.text,
            surface = Sm.booth, onSurface = Sm.text,
            surfaceVariant = Sm.glassFill, onSurfaceVariant = Sm.muted,
            surfaceContainer = Sm.sheet, surfaceContainerHigh = Sm.glassFill, surfaceContainerLow = Sm.backdrop,
            outline = Sm.glassEdge, outlineVariant = Sm.line,
            error = Sm.bad
        ),
        typography = typography,
        content = content
    )
}
