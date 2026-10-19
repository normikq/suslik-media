package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*
import ru.suslikovanora.media.desktop.ui.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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

private val PeopleIcon: @Composable (Color) -> Unit = { col ->
    Canvas(Modifier.size(16.dp)) {
        val w = size.width; val st = 1.6.dp.toPx()
        drawCircle(col, w * 0.17f, Offset(w * 0.36f, w * 0.34f), style = Stroke(st))
        drawArc(col, 200f, 140f, false, Offset(w * 0.06f, w * 0.52f), Size(w * 0.60f, w * 0.60f), style = Stroke(st, cap = StrokeCap.Round))
        drawCircle(col, w * 0.14f, Offset(w * 0.70f, w * 0.30f), style = Stroke(st))
        drawArc(col, 240f, 100f, false, Offset(w * 0.46f, w * 0.48f), Size(w * 0.50f, w * 0.50f), style = Stroke(st, cap = StrokeCap.Round))
    }
}

private val ui = Store.of("ui")

/** The logo (the groundhog in the play button, resources/icons/brand-mark.png) with a slowly breathing glow behind it. */
@Composable
private fun BrandMark() {
    val b by breathing(2600, active = !Motion.simple)
    Box(
        Modifier.size(46.dp).drawBehind {
            val r = size.maxDimension * (0.62f + 0.12f * b)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF6D5BF0).copy(alpha = 0.22f + 0.22f * b), Color.Transparent)), r)
        },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource("icons/brand-mark.png"), null, Modifier.size(46.dp))
    }
}

/** Text that slides in / out next to an icon when the panel is expanded / collapsed. */
@Composable
private fun Label(visible: Boolean, content: @Composable () -> Unit) = AnimatedVisibility(
    visible,
    enter = fadeIn(tween(200, 80)) + expandHorizontally(tween(260, easing = Sm.ease)),
    exit = fadeOut(tween(90)) + shrinkHorizontally(tween(220, easing = Sm.ease))
) { content() }

@Composable
private fun NavItem(
    label: String, active: Boolean, expanded: Boolean, icon: @Composable (Color) -> Unit,
    enabled: Boolean = true, soon: String? = null, tab: Tab? = null, onClick: () -> Unit = {}
) {
    val src = remember { MutableInteractionSource() }
    // A poster has just landed here: a short glow.
    LaunchedEffect(FlyHub.glowStamp) { if (tab != null && FlyHub.glowTab == tab) { kotlinx.coroutines.delay(650); if (FlyHub.glowTab == tab) FlyHub.glowTab = null } }
    val glow by androidx.compose.animation.core.animateFloatAsState(if (tab != null && FlyHub.glowTab == tab) 1f else 0f, tween(260))
    val hovered by src.collectIsHoveredAsState()
    val on = hovered && enabled
    val bg by animateColorAsState(when { active -> Sm.accent.copy(alpha = 0.78f); on -> Color(0x0FFFFFFF); else -> Color.Transparent }, tween(200))
    val edge by animateColorAsState(if (active) Sm.accentEdge else Color.Transparent, tween(200))
    val fg by animateColorAsState(when { !enabled -> Color(0xFF5B6272); active || on -> Color.White; else -> Color(0xFFCBD2E0) }, tween(200))
    Row(
        Modifier.fillMaxWidth().graphicsLayer { scaleX = 1f + 0.05f * glow; scaleY = scaleX }
            .onGloballyPositioned { if (tab != null) FlyHub.navBounds[tab] = it }
            .clip(RoundedCornerShape(12.dp)).background(bg).background(Sm.accent.copy(alpha = 0.55f * glow)).border(1.dp, edge, RoundedCornerShape(12.dp))
            .let { if (enabled) it.hoverClick(src, onClick = onClick) else it.hoverable(src) }
            // Fixed height, and the icon at the same x in both states (centred in the collapsed 48 dp): nothing moves or shrinks.
            .height(42.dp).padding(start = 15.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.requiredSize(18.dp), contentAlignment = Alignment.Center) { icon(fg) }
        Label(expanded) {
            Column(Modifier.padding(start = 12.dp)) {
                Text(label, color = fg, fontFamily = Onest, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, fontSize = 14.sp,
                    maxLines = 1, softWrap = false)
                if (soon != null) Text(soon, color = Color(0xFF5B6272), fontFamily = Onest, fontSize = 10.5.sp, maxLines = 1, softWrap = false, lineHeight = 11.sp)
            }
        }
    }
}

@Composable
fun Sidebar(tab: Tab, profile: Profile, onSelect: (Tab) -> Unit, onSettings: () -> Unit, onLogout: () -> Unit) {
    var collapsed by remember { mutableStateOf(ui.get("sidebar_collapsed") == "1") }
    val width by animateDpAsState(if (collapsed) 76.dp else 244.dp, tween(300, easing = Sm.ease))
    val expanded = !collapsed
    Column(
        Modifier.width(width).fillMaxHeight().background(Sm.glassSide).drawEdge()
            .padding(horizontal = 14.dp, vertical = 18.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandMark()
            Label(expanded) {
                Column(Modifier.padding(start = 10.dp)) {
                    Text("Suslik", color = Color.White, fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, softWrap = false)
                    Text("MEDIA", fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, letterSpacing = 5.sp, maxLines = 1, softWrap = false,
                        style = androidx.compose.ui.text.TextStyle(brush = Brush.horizontalGradient(listOf(Color(0xFF22D3EE), Color(0xFF6D8CF5), Color(0xFF9B6BFF)))),
                        modifier = Modifier.padding(top = 1.dp))
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NavItem(Tab.Library.label, tab == Tab.Library, expanded, { GridIcon(color = it, size = 16.dp) }, tab = Tab.Library) { onSelect(Tab.Library) }
            NavItem(Tab.Favorites.label, tab == Tab.Favorites, expanded, { HeartIcon(filled = false, color = it, size = 16.dp) }, tab = Tab.Favorites) { onSelect(Tab.Favorites) }
            NavItem(Tab.Search.label, tab == Tab.Search, expanded, { SearchIcon(color = it, size = 16.dp) }) { onSelect(Tab.Search) }
            NavItem(Tab.Add.label, tab == Tab.Add, expanded, { LinkChainIcon(color = it, size = 16.dp) }) { onSelect(Tab.Add) }
            // Not available yet: greyed out, no click.
            NavItem(tr("sidebar.sovmestnyy_prosmotr"), false, expanded, PeopleIcon, enabled = false, soon = tr("sidebar.skoro"))
            Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(1.dp).background(Color(0x1AFFFFFF)))
            NavItem(tr("sidebar.nastroyki"), false, expanded, { GearIcon(it) }, onClick = onSettings)
        }
        Spacer(Modifier.weight(1f))
        UpdateButton() // appears only when a newer version is on the server
        if (Updater.available != null) Spacer(Modifier.height(10.dp))
        ProfileCard(profile, expanded, onLogout)
        Spacer(Modifier.height(10.dp))
        // Collapse / expand: the chevron points where the panel goes.
        val src = remember { MutableInteractionSource() }
        val hovered by src.collectIsHoveredAsState()
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (hovered) Color(0x0FFFFFFF) else Color.Transparent)
                .hoverClick(src) { collapsed = !collapsed; ui.put("sidebar_collapsed", if (collapsed) "1" else null) }.padding(vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            val c = if (hovered) Color.White else Sm.muted
            if (collapsed) ChevronRight(color = c, size = 14.dp) else ChevronLeft(color = c, size = 14.dp)
        }
    }
}

/** Instead of the old storage gauge: whose library this is. Avatar slot is ready for the Discord picture. */
@Composable
private fun ProfileCard(profile: Profile, expanded: Boolean, onLogout: () -> Unit) {
    val avatar: @Composable () -> Unit = {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Sm.accent, Color(0xFF6366F1))))
                .border(1.dp, Color(0x33FFFFFF), CircleShape),
            contentAlignment = Alignment.Center
        ) { Text(profile.name.take(1).uppercase(), color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
    }
    if (!expanded) {
        // Icons only: the avatar and a small exit button.
        val src = remember { MutableInteractionSource() }
        val hovered by src.collectIsHoveredAsState()
        Column(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(14.dp)).padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            avatar()
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(if (hovered) Color(0x26FFFFFF) else Color(0x0DFFFFFF)).hoverClick(src, onClick = onLogout),
                contentAlignment = Alignment.Center
            ) { CloseCrossIcon(color = if (hovered) Color.White else Sm.muted, size = 11.dp) }
        }
        return
    }
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(14.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            avatar()
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(profile.name, color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(profile.note, color = Sm.muted, fontFamily = Onest, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(10.dp))
        PillButton(tr("library.vyyti"), Modifier.fillMaxWidth(), onClick = onLogout)
    }
}
