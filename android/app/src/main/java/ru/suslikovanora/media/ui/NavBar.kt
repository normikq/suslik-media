package ru.suslikovanora.media.ui

import ru.suslikovanora.media.core.*

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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.suslikovanora.media.tr

enum class Tab(val label: String) {
    Links(tr("add.ssylka")),
    Library(tr("navbar.biblioteka")),
    Search(tr("navbar.poisk"))
}

/**
 * Floating bottom bar styled with frosted glass and an electric blue sliding lens (ported from PC redesign).
 * Remote control: [focus] lands on the active tab, [onFocused] tells whether one of the tabs has focus.
 */
@Composable
fun NavBar(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier, focus: FocusRequester? = null, onFocused: (Boolean) -> Unit = {}) {
    Box(modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            Modifier.widthIn(max = 340.dp).fillMaxWidth().padding(horizontal = 14.dp)
                .shadow(20.dp, RoundedCornerShape(26.dp), ambientColor = Color.Black.copy(alpha = 0.6f))
                .clip(RoundedCornerShape(26.dp)).background(Sm.glassSide)
                .border(1.dp, Sm.glassEdge, RoundedCornerShape(26.dp))
                .padding(4.dp).height(50.dp)
        ) {
            val itemW = maxWidth / Tab.entries.size
            val x by animateDpAsState(itemW * current.ordinal, tween(420, easing = Sm.ease), label = "lens")
            Box(
                Modifier.offset(x = x).width(itemW).fillMaxHeight()
                    .shadow(12.dp, RoundedCornerShape(22.dp), ambientColor = Sm.accent, spotColor = Sm.accent)
                    .clip(RoundedCornerShape(22.dp)).background(Sm.accent)
                    .border(1.dp, Sm.accentEdge, RoundedCornerShape(22.dp))
            )
            Row(Modifier.fillMaxSize().onFocusChanged { onFocused(it.hasFocus) }, horizontalArrangement = Arrangement.SpaceEvenly) {
                for (t in Tab.entries) {
                    val active = t == current
                    val color by animateColorAsState(if (active) Color.White else Color(0xFF8E96A8), tween(200), label = "tab")
                    Column(
                        Modifier.weight(1f).fillMaxHeight()
                            .then(if (active && focus != null) Modifier.focusRequester(focus) else Modifier)
                            .focusRing(RoundedCornerShape(22.dp), color = Color.White)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(t) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        TabIcon(t, color)
                        Text(t.label, color = color, fontFamily = Onest, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

/** Crisp line icons drawn with Canvas, matching the desktop application. */
@Composable
private fun TabIcon(tab: Tab, color: Color) {
    when (tab) {
        Tab.Links -> LinkChainIcon(color = color, size = 18.dp)
        Tab.Library -> GridIcon(color = color, size = 17.dp)
        Tab.Search -> SearchIcon(color = color, size = 18.dp)
    }
}
