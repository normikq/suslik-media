package ru.suslikovanora.media.desktop.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
