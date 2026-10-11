package ru.suslikovanora.media.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Sm.booth)
            .border(1.dp, Sm.line, RoundedCornerShape(16.dp)).padding(18.dp)
    ) { content() }
}

/** Hover + press + hand cursor in one go, for anything clickable. */
fun Modifier.hoverClick(src: MutableInteractionSource, enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.hoverable(src, enabled).clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
        .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)

/** Scale for hover (slight lift) and press (slight sink). */
@Composable
fun hoverScale(hovered: Boolean, pressed: Boolean, up: Float = 1.03f): Float {
    val s by animateFloatAsState(if (pressed) 0.96f else if (hovered) up else 1f, tween(200, easing = Sm.ease))
    return s
}

@Composable
fun LampButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val hovered by src.collectIsHoveredAsState()
    val on = hovered && enabled
    val scale = hoverScale(on, pressed && enabled)
    val bg by animateColorAsState(if (on) Color(0xFFFFC965) else Sm.lamp, tween(200))
    val glow by animateColorAsState(if (on) Color(0x66FFB02E) else Color.Transparent, tween(240))
    Box(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.55f }
            .border(2.dp, glow, RoundedCornerShape(14.dp)).padding(2.dp)
            .clip(RoundedCornerShape(12.dp)).background(bg).hoverClick(src, enabled, onClick)
            .heightIn(min = 38.dp).padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Sm.lampInk, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
}

@Composable
fun OutlineButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val scale = hoverScale(hovered, pressed, 1.02f)
    val border by animateColorAsState(if (hovered) Sm.lamp else Sm.line2, tween(200))
    val fg by animateColorAsState(if (hovered) Sm.lamp else Sm.text, tween(200))
    val fill by animateColorAsState(if (hovered) Color(0x1FFFB02E) else Color.Transparent, tween(200))
    Box(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp)).background(fill).border(1.dp, border, RoundedCornerShape(12.dp))
            .hoverClick(src, onClick = onClick)
            .heightIn(min = 38.dp).padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = fg, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
}

/** Small toggle chip (filters, track lists). */
@Composable
fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val scale = hoverScale(hovered, pressed)
    val bg by animateColorAsState(when { on -> Sm.lamp; hovered -> Color(0xFF232838); else -> Sm.film }, tween(200))
    val edge by animateColorAsState(if (on || hovered) Sm.lamp else Sm.line2, tween(200))
    val fg by animateColorAsState(when { on -> Sm.lampInk; hovered -> Sm.lamp; else -> Sm.text }, tween(200))
    Text(label, color = fg, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.sp,
        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(10.dp)).background(bg)
            .border(1.dp, edge, RoundedCornerShape(10.dp)).hoverClick(src, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}

@Composable
fun Field(value: String, onValue: (String) -> Unit, placeholder: String, password: Boolean = false, onDone: (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(if (focused) Sm.lamp else Sm.line2)
    BasicTextField(
        value = value, onValueChange = onValue, singleLine = true,
        textStyle = TextStyle(color = Sm.text, fontFamily = Onest, fontSize = 15.sp),
        cursorBrush = SolidColor(Sm.lamp),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onAny = { onDone?.invoke() }),
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp)).background(Sm.night).border(1.dp, border, RoundedCornerShape(12.dp)),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (value.isEmpty()) Text(placeholder, color = Sm.muted, fontFamily = Onest, fontSize = 15.sp)
                inner()
            }
        }
    )
}
