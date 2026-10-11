package ru.suslikovanora.media.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Tab(val label: String, val webTab: String?) {
    Links("Ссылка", "links"),
    Library("Библиотека", null),
    Search("Поиск", "search")
}

/** The website's floating bottom bar with the amber "lens" that slides under the active tab. */
@Composable
fun NavBar(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            Modifier.widthIn(max = 340.dp).fillMaxWidth().padding(horizontal = 14.dp)
                .shadow(18.dp, RoundedCornerShape(26.dp))
                .clip(RoundedCornerShape(26.dp)).background(Color(0xFF0D1017))
                .border(1.dp, Sm.line2, RoundedCornerShape(26.dp))
                .padding(4.dp).height(48.dp)
        ) {
            val itemW = maxWidth / Tab.entries.size
            val x by animateDpAsState(itemW * current.ordinal, tween(420, easing = Sm.ease), label = "lens")
            Box(
                Modifier.offset(x = x).width(itemW).fillMaxHeight()
                    .shadow(10.dp, RoundedCornerShape(22.dp), ambientColor = Sm.lamp, spotColor = Sm.lamp)
                    .clip(RoundedCornerShape(22.dp)).background(Sm.lamp)
            )
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (t in Tab.entries) {
                    val color by animateColorAsState(if (t == current) Sm.lampInk else Sm.muted, tween(250), label = "tab")
                    Column(
                        Modifier.weight(1f).fillMaxHeight()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(t) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        TabIcon(t, color)
                        Text(t.label, color = color, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

/** Line icons drawn in code (no icon font needed): link arrow, library list, search loupe. */
@Composable
private fun TabIcon(tab: Tab, color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = size.width; val s = w / 18f
        val stroke = Stroke(width = 1.8f * s, cap = StrokeCap.Round)
        when (tab) {
            Tab.Links -> {
                drawLine(color, Offset(4f * s, 14f * s), Offset(14f * s, 4f * s), 1.8f * s, StrokeCap.Round)
                drawLine(color, Offset(7f * s, 4f * s), Offset(14f * s, 4f * s), 1.8f * s, StrokeCap.Round)
                drawLine(color, Offset(14f * s, 4f * s), Offset(14f * s, 11f * s), 1.8f * s, StrokeCap.Round)
            }
            Tab.Library -> {
                drawRoundRect(color, Offset(3f * s, 3f * s), Size(12f * s, 12f * s), CornerRadius(2f * s), style = stroke)
                for (y in listOf(7f, 10f, 13f)) drawLine(color, Offset(6f * s, (y - 0.5f) * s), Offset(12f * s, (y - 0.5f) * s), 1.4f * s, StrokeCap.Round)
            }
            Tab.Search -> {
                drawCircle(color, 5f * s, Offset(8f * s, 8f * s), style = stroke)
                drawLine(color, Offset(11.8f * s, 11.8f * s), Offset(15.5f * s, 15.5f * s), 2f * s, StrokeCap.Round)
            }
        }
    }
}
