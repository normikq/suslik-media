package ru.suslikovanora.media.ui

import ru.suslikovanora.media.core.*

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ru.suslikovanora.media.R

/** «Проекторная» — the same tokens as the website's redesign.css. */
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
}

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
            primary = Sm.lamp, onPrimary = Sm.lampInk,
            background = Sm.night, onBackground = Sm.text,
            surface = Sm.booth, onSurface = Sm.text,
            surfaceVariant = Sm.film, onSurfaceVariant = Sm.muted,
            surfaceContainer = Sm.booth, surfaceContainerHigh = Sm.film, surfaceContainerLow = Sm.booth,
            outline = Sm.line2, outlineVariant = Sm.line,
            error = Sm.bad
        ),
        typography = typography,
        content = content
    )
}
