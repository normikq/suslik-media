package ru.suslikovanora.media.desktop.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/** «Проекторная» — the same tokens as the site and the Android app. */
object Sm {
    val night = Color(0xFF090B10)
    val booth = Color(0xFF11141B)
    val film = Color(0xFF181C26)
    val line = Color(0xFF252A37)
    val line2 = Color(0xFF333A4B)
    val text = Color(0xFFEEF0F5)
    val muted = Color(0xFF8E96A8)
    val lamp = Color(0xFFFFB02E)
    val lampInk = Color(0xFF1C1200)
    val ok = Color(0xFF7FE0B0)
    val bad = Color(0xFFFF9B8F)
    val scrim = Color(0xCC090B10)
    val ease = CubicBezierEasing(0.22f, 0.8f, 0.24f, 1f)
    /** Poster ↔ full-screen flight: soft start and soft landing (no jerk at either end). */
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

/** Glass surface: translucent fill, hairline light edge. Compose has no backdrop blur, so the glow behind does the work. */
fun Modifier.glass(shape: Shape, hovered: Boolean = false): Modifier =
    this.clip(shape).background(if (hovered) Sm.glassFillHover else Sm.glassFill).border(1.dp, if (hovered) Sm.glassEdgeHover else Sm.glassEdge, shape)

/** Dialog card: nearly opaque dark glass with a light hairline edge. */
fun Modifier.glassSheet(shape: Shape): Modifier = this.clip(shape).background(Sm.sheet).border(1.dp, Sm.sheetEdge, shape)

/** Right-hand hairline of a side panel. */
fun Modifier.drawEdge(): Modifier = this.drawBehind {
    drawLine(Color(0x14FFFFFF), Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1f)
}

/** Soft blue / violet / cyan glows behind everything, as in the concept. Static: no per-frame cost. */
fun Modifier.ambientBackdrop(): Modifier = this.background(Sm.backdrop).drawBehind {
    fun glow(c: Color, cx: Float, cy: Float, r: Float) =
        drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
    glow(Color(0x382563EB), size.width * 0.22f, 0f, size.minDimension * 0.85f)
    glow(Color(0x2E8B5CF6), size.width * 0.95f, size.height * 0.95f, size.minDimension * 0.75f)
    glow(Color(0x2406B6D4), size.width * 0.55f, size.height * 0.45f, size.minDimension * 0.6f)
    // fades into the title bar's colour at the very top, so the system bar and the window read as one
    drawRect(Brush.verticalGradient(listOf(Sm.backdrop, Color.Transparent), 0f, 72.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 72.dp.toPx()))
}

val Unbounded = FontFamily(
    Font("fonts/unbounded_medium.ttf", FontWeight.Medium),
    Font("fonts/unbounded_bold.ttf", FontWeight.Bold)
)

val Onest = FontFamily(
    Font("fonts/onest_regular.ttf", FontWeight.Normal),
    Font("fonts/onest_medium.ttf", FontWeight.Medium),
    Font("fonts/onest_semibold.ttf", FontWeight.SemiBold)
)

@Composable
fun SuslikTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Sm.lamp, onPrimary = Sm.lampInk, background = Sm.night, onBackground = Sm.text,
        surface = Sm.booth, onSurface = Sm.text, surfaceVariant = Sm.film, outline = Sm.line2
    ),
    content = content
)
