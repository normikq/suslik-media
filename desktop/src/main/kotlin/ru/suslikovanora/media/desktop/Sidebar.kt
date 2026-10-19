package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.desktop.ui.*

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Who is signed in. Today it is the shared access password; with personal libraries (Discord sign-in) it fills in per user. */
data class Profile(val name: String, val note: String, val avatarUrl: String? = null) {
    companion object { val Shared = Profile(tr("sidebar.profil_imya"), tr("sidebar.profil_vhod_po_parolyu")) }
}

private val GearIcon: @Composable (Color) -> Unit = { col ->
    Canvas(Modifier.size(16.dp)) {
        val c = center; val r = size.minDimension / 2
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            drawLine(col, Offset((c.x + kotlin.math.cos(a) * r * 0.62).toFloat(), (c.y + kotlin.math.sin(a) * r * 0.62).toFloat()),
                Offset((c.x + kotlin.math.cos(a) * r * 0.95).toFloat(), (c.y + kotlin.math.sin(a) * r * 0.95).toFloat()),
                strokeWidth = r * 0.28f, cap = StrokeCap.Round)
        }
        drawCircle(col, r * 0.62f, c, style = Stroke(width = r * 0.22f))
    }
}

@Composable
private fun NavItem(label: String, active: Boolean, icon: @Composable (Color) -> Unit, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg by animateColorAsState(when { active -> Sm.accent.copy(alpha = 0.78f); hovered -> Color(0x0FFFFFFF); else -> Color.Transparent }, tween(200))
    val edge by animateColorAsState(if (active) Sm.accentEdge else Color.Transparent, tween(200))
    val fg by animateColorAsState(when { active -> Color.White; hovered -> Color.White; else -> Color(0xFFCBD2E0) }, tween(200))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).border(1.dp, edge, RoundedCornerShape(12.dp))
            .hoverClick(src, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        icon(fg)
        Text(label, color = fg, fontFamily = Onest, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, fontSize = 14.sp)
    }
}

@Composable
fun Sidebar(tab: Tab, profile: Profile, onSelect: (Tab) -> Unit, onSettings: () -> Unit, onLogout: () -> Unit) {
    Column(
        Modifier.width(236.dp).fillMaxHeight().background(Sm.glassSide).drawEdge()
            .padding(horizontal = 14.dp, vertical = 18.dp)
    ) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            LogoPlayIcon(size = 34.dp)
            Text("Suslik Media", color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(start = 11.dp))
        }
        Spacer(Modifier.height(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NavItem(Tab.Library.label, tab == Tab.Library, { GridIcon(color = it, size = 16.dp) }) { onSelect(Tab.Library) }
            NavItem(Tab.Search.label, tab == Tab.Search, { SearchIcon(color = it, size = 16.dp) }) { onSelect(Tab.Search) }
            NavItem(Tab.Add.label, tab == Tab.Add, { LinkChainIcon(color = it, size = 16.dp) }) { onSelect(Tab.Add) }
            Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(1.dp).background(Color(0x1AFFFFFF)))
            NavItem(tr("sidebar.nastroyki"), false, { GearIcon(it) }, onSettings)
        }
        Spacer(Modifier.weight(1f))
        UpdateButton() // appears only when a newer version is on the server
        if (Updater.available != null) Spacer(Modifier.height(10.dp))
        ProfileCard(profile, onLogout)
    }
}

/** Instead of the old storage gauge: whose library this is. Avatar slot is ready for the Discord picture. */
@Composable
private fun ProfileCard(profile: Profile, onLogout: () -> Unit) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(14.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Sm.accent, Color(0xFF6366F1))))
                    .border(1.dp, Color(0x33FFFFFF), CircleShape),
                contentAlignment = Alignment.Center
            ) { Text(profile.name.take(1).uppercase(), color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(profile.name, color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(profile.note, color = Sm.muted, fontFamily = Onest, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(10.dp))
        PillButton(tr("library.vyyti"), Modifier.fillMaxWidth(), onClick = onLogout)
    }
}
