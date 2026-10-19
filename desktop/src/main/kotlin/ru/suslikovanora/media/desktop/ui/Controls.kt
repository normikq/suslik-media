package ru.suslikovanora.media.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val bg by animateColorAsState(if (on) Color(0xFF3B74F0) else Sm.accent, tween(200))
    val edge by animateColorAsState(if (on) Color(0xFF8DB6FF) else Sm.accentEdge, tween(240))
    Box(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.55f }
            .clip(RoundedCornerShape(12.dp)).background(bg).border(1.dp, edge, RoundedCornerShape(12.dp))
            .hoverClick(src, enabled, onClick)
            .heightIn(min = 38.dp).padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp) }
}

@Composable
fun OutlineButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val scale = hoverScale(hovered, pressed, 1.02f)
    val border by animateColorAsState(if (hovered) Color(0xFF3E475C) else Color(0xFF242A38), tween(200))
    val bg by animateColorAsState(if (hovered) Color(0xFF1C202B) else Color(0xFF141720), tween(200))
    Box(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp)).background(bg).border(1.dp, border, RoundedCornerShape(12.dp))
            .hoverClick(src, onClick = onClick)
            .heightIn(min = 38.dp).padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.5.sp) }
}

/** Small toggle chip (filters, track lists). */
@Composable
fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val scale = hoverScale(hovered, pressed)
    val bg by animateColorAsState(when { on -> Color(0xFF282E3C); hovered -> Color(0xFF1C202B); else -> Color(0xFF141720) }, tween(200))
    val edge by animateColorAsState(when { on -> Color(0xFF3A4356); hovered -> Color(0xFF2C3344); else -> Color(0xFF222735) }, tween(200))
    val fg by animateColorAsState(when { on -> Color.White; hovered -> Color(0xFFD6DBE4); else -> Color(0xFF8E96A8) }, tween(200))
    Text(label, color = fg, fontFamily = Onest, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, fontSize = 13.sp,
        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(10.dp)).background(bg)
            .border(1.dp, edge, RoundedCornerShape(10.dp)).hoverClick(src, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}


@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val scale = hoverScale(hovered, pressed, 1.02f)
    val edge by animateColorAsState(if (active || hovered) Color(0xFF3E475C) else Color(0xFF242A38), tween(200))
    val fg by animateColorAsState(if (active || hovered) Color.White else Color(0xFFD6DBE4), tween(200))
    val bg by animateColorAsState(when { active -> Color(0xFF282E3C); hovered -> Color(0xFF1C202B); else -> Color(0xFF141720) }, tween(200))
    Box(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(18.dp)).background(bg).border(1.dp, edge, RoundedCornerShape(18.dp))
            .hoverClick(src, onClick = onClick)
            .height(36.dp).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) icon()
            Text(text, color = fg, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        }
    }
}

@Composable
fun Field(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    password: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onDone: (() -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(if (focused) Color(0xFF3E475C) else Color(0xFF222735))
    BasicTextField(
        value = value, onValueChange = onValue, singleLine = true,
        textStyle = TextStyle(color = Sm.text, fontFamily = Onest, fontSize = 14.5.sp),
        cursorBrush = SolidColor(Color.White),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onAny = { onDone?.invoke() }),
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp)).background(Color(0xFF12151D)).border(1.dp, border, RoundedCornerShape(12.dp)),
        decorationBox = { inner ->
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (leadingIcon != null) {
                    leadingIcon()
                    Spacer(Modifier.width(10.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = Color(0xFF6B7280), fontFamily = Onest, fontSize = 14.5.sp)
                    inner()
                }
            }
        }
    )
}

