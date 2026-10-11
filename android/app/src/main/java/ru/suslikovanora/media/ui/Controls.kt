package ru.suslikovanora.media.ui

import ru.suslikovanora.media.core.*

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Small toggle chip (filters, sources) — amber when on, slight sink when pressed. */
@Composable
fun Chip(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, tween(160, easing = Sm.ease), label = "chip")
    val bg by animateColorAsState(if (on) Sm.lamp else Sm.film, tween(220), label = "chipbg")
    val fg by animateColorAsState(if (on) Sm.lampInk else Sm.text, tween(220), label = "chipfg")
    val edge by animateColorAsState(if (on) Sm.lamp else Sm.line2, tween(220), label = "chipedge")
    Text(
        label, color = fg, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1,
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(10.dp)).background(bg).border(1.dp, edge, RoundedCornerShape(10.dp))
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}
