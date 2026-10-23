package ru.suslikovanora.media.ui

import ru.suslikovanora.media.core.*

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
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
import ru.suslikovanora.media.library.Category
import ru.suslikovanora.media.library.LibraryNav
import ru.suslikovanora.media.library.LibraryStore
import ru.suslikovanora.media.library.itemCategory

private val GearIcon: @Composable (Color) -> Unit = { col ->
    Canvas(Modifier.size(16.dp)) {
        val c = center; val r = size.minDimension / 2
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            drawLine(col, Offset((c.x + kotlin.math.cos(a) * r * 0.62).toFloat(), (c.y + kotlin.math.sin(a) * r * 0.62).toFloat()),
                Offset((c.x + kotlin.math.cos(a) * r * 0.95).toFloat(), (c.y + kotlin.math.sin(a) * r * 0.95).toFloat()), strokeWidth = r * 0.28f, cap = StrokeCap.Round)
        }
        drawCircle(col, r * 0.62f, c, style = Stroke(width = r * 0.22f))
    }
}

private val HomeIcon: @Composable (Color) -> Unit = { col ->
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.5f, w * 0.06f); lineTo(w * 0.96f, w * 0.48f); lineTo(w * 0.84f, w * 0.48f); lineTo(w * 0.84f, w * 0.94f)
            lineTo(w * 0.62f, w * 0.94f); lineTo(w * 0.62f, w * 0.62f); lineTo(w * 0.38f, w * 0.62f); lineTo(w * 0.38f, w * 0.94f)
            lineTo(w * 0.16f, w * 0.94f); lineTo(w * 0.16f, w * 0.48f); lineTo(w * 0.04f, w * 0.48f); close()
        }
        drawPath(p, col)
    }
}

private val FilmIcon: @Composable (Color) -> Unit = { col ->
    Canvas(Modifier.size(16.dp)) {
        val w = size.width; val st = 1.5.dp.toPx()
        drawRoundRect(col, Offset(w * 0.1f, w * 0.06f), Size(w * 0.8f, w * 0.88f), CornerRadius(w * 0.14f), style = Stroke(st))
        drawLine(col, Offset(w * 0.32f, w * 0.06f), Offset(w * 0.32f, w * 0.94f), strokeWidth = st)
        drawLine(col, Offset(w * 0.68f, w * 0.06f), Offset(w * 0.68f, w * 0.94f), strokeWidth = st)
        for (y in listOf(0.26f, 0.5f, 0.74f)) { drawCircle(col, w * 0.045f, Offset(w * 0.21f, w * y)); drawCircle(col, w * 0.045f, Offset(w * 0.79f, w * y)) }
    }
}

private val ScreenIcon: @Composable (Color) -> Unit = { col ->
    Canvas(Modifier.size(16.dp)) {
        val w = size.width; val st = 1.6.dp.toPx()
        drawRoundRect(col, Offset(w * 0.06f, w * 0.12f), Size(w * 0.88f, w * 0.6f), CornerRadius(w * 0.1f), style = Stroke(st))
        drawLine(col, Offset(w * 0.5f, w * 0.72f), Offset(w * 0.5f, w * 0.88f), strokeWidth = st)
        drawLine(col, Offset(w * 0.3f, w * 0.9f), Offset(w * 0.7f, w * 0.9f), strokeWidth = st, cap = StrokeCap.Round)
    }
}

/** A round face; [ears] — two round ears on top (anime / cartoon), [smile] — a smile instead of a dash. */
private fun faceIcon(ears: Boolean, smile: Boolean): @Composable (Color) -> Unit = { col ->
    Canvas(Modifier.size(16.dp)) {
        val w = size.width; val st = 1.5.dp.toPx()
        if (ears) {
            drawCircle(col, w * 0.15f, Offset(w * 0.2f, w * 0.2f), style = Stroke(st))
            drawCircle(col, w * 0.15f, Offset(w * 0.8f, w * 0.2f), style = Stroke(st))
        }
        drawCircle(col, w * 0.38f, Offset(w * 0.5f, w * 0.57f), style = Stroke(st))
        drawCircle(col, w * 0.045f, Offset(w * 0.37f, w * 0.52f)); drawCircle(col, w * 0.045f, Offset(w * 0.63f, w * 0.52f))
        if (smile) drawArc(col, 20f, 140f, false, Offset(w * 0.34f, w * 0.5f), Size(w * 0.32f, w * 0.3f), style = Stroke(st, cap = StrokeCap.Round))
        else drawLine(col, Offset(w * 0.4f, w * 0.72f), Offset(w * 0.6f, w * 0.72f), strokeWidth = st, cap = StrokeCap.Round)
    }
}

private val CollectionIcons: Map<Category, @Composable (Color) -> Unit> = mapOf(
    Category.Movies to FilmIcon, Category.Series to ScreenIcon,
    Category.Anime to faceIcon(ears = true, smile = false), Category.Cartoons to faceIcon(ears = true, smile = true),
    Category.Other to faceIcon(ears = false, smile = true)
)

@Composable
private fun SideItem(
    label: String, active: Boolean, icon: @Composable (Color) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, note: String? = null, count: Int? = null, onClick: () -> Unit
) {
    val bg by animateColorAsState(if (active) Sm.accent.copy(alpha = 0.78f) else Color.Transparent, label = "bg")
    val fg = when { !enabled -> Color(0xFF5B6272); active -> Color.White; else -> Color(0xFFCBD2E0) }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg)
            .border(1.dp, if (active) Sm.accentEdge else Color.Transparent, RoundedCornerShape(12.dp))
            .focusRing(RoundedCornerShape(12.dp), color = Color.White, width = 3.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)
            .height(40.dp).padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.requiredSize(18.dp), contentAlignment = Alignment.Center) { icon(fg) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, color = fg, fontFamily = Onest, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, color = Color(0xFF5B6272), fontFamily = Onest, fontSize = 10.5.sp, maxLines = 1, lineHeight = 11.sp)
        }
        if (count != null) Text(count.toString(), color = if (active) Color.White else Sm.muted, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(1.dp).background(Color(0x1AFFFFFF)))
    Text(text, color = Color(0xFF8E96A8), fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.4.sp,
        modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp))
}

/**
 * Android TV: the left panel instead of the bottom bar — pages, «Коллекция» with the number of films in each category, «Сервис».
 * The remote moves along it (up/down), OK picks, right goes into the page. [focus] lands on the active item, [onFocused] tells whether the panel has focus.
 */
@Composable
fun TvSidebar(
    tab: Tab, store: LibraryStore, onSelect: (Tab) -> Unit, onSettings: () -> Unit, onLogout: () -> Unit,
    focus: FocusRequester, onFocused: (Boolean) -> Unit, locked: Set<Tab>, inRoom: Boolean, modifier: Modifier = Modifier
) {
    val inMine = tab == Tab.Mine
    val inLibrary = tab == Tab.Library
    val current = LibraryNav.of(inMine)
    // films per category, among the ones of the page that is open («Моя медиатека» — only the user's own)
    val counts = remember(store.items, inMine) { (if (inMine) store.items.filter { it.m.mine } else store.items).groupingBy { itemCategory(it) }.eachCount() }
    val lockNote = if (locked.isNotEmpty()) "вы в комнате" else null
    // the item the remote returns to: the open page's own entry
    val home = inLibrary && current == Category.All
    val mine = inMine && current == Category.All
    fun mod(on: Boolean) = if (on) Modifier.focusRequester(focus) else Modifier
    val focusOn = when {
        home || mine -> if (home) 0 else 1
        tab == Tab.Search -> 2
        tab == Tab.Links -> 3
        tab == Tab.Together -> 4
        else -> 10 + Category.entries.indexOf(current) // a collection of the open page
    }

    Column(
        modifier.width(236.dp).fillMaxHeight().background(Sm.glassSide).padding(horizontal = 12.dp, vertical = 16.dp)
            .onFocusChanged { onFocused(it.hasFocus) }
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            val b by breathing(2600)
            Box(
                Modifier.size(42.dp).drawBehind {
                    val r = size.maxDimension * (0.62f + 0.12f * b)
                    drawCircle(Brush.radialGradient(listOf(Color(0xFF6D5BF0).copy(alpha = 0.25f + 0.22f * b), Color.Transparent)), r)
                },
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(ru.suslikovanora.media.R.drawable.brand_mark), null, Modifier.size(42.dp))
            }
            Column(Modifier.padding(start = 10.dp)) {
                Text("Suslik", color = Color.White, fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                Text("MEDIA", fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 5.sp, maxLines = 1,
                    style = androidx.compose.ui.text.TextStyle(brush = Brush.horizontalGradient(listOf(Color(0xFF22D3EE), Color(0xFF6D8CF5), Color(0xFF9B6BFF)))))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SideItem("Главная", home, HomeIcon, mod(focusOn == 0), enabled = Tab.Library !in locked, note = lockNote) {
                if (inLibrary) LibraryNav.category = Category.All
                onSelect(Tab.Library)
            }
            SideItem("Моя медиатека", mine, { BookmarkIcon(filled = true, color = it, size = 16.dp) }, mod(focusOn == 1), enabled = Tab.Mine !in locked, note = lockNote) {
                if (inMine) LibraryNav.mineCategory = Category.All
                onSelect(Tab.Mine)
            }
            SideItem("Поиск раздач", tab == Tab.Search, { SearchIcon(color = it, size = 16.dp) }, mod(focusOn == 2)) { onSelect(Tab.Search) }
            SideItem("Magnet / торрент", tab == Tab.Links, { LinkChainIcon(color = it, size = 16.dp) }, mod(focusOn == 3)) { onSelect(Tab.Links) }
            SideItem("Совместный просмотр", tab == Tab.Together, { PeopleIcon(color = it, size = 16.dp) }, mod(focusOn == 4),
                note = if (inRoom) "в комнате" else null) { onSelect(Tab.Together) }
            SectionTitle("КОЛЛЕКЦИЯ")
            for (c in listOf(Category.Movies, Category.Series, Category.Anime, Category.Cartoons, Category.Other)) {
                val off = (inMine && Tab.Mine in locked) || (!inMine && Tab.Library in locked)
                SideItem(
                    c.label, (inLibrary || inMine) && current == c, CollectionIcons.getValue(c), mod(focusOn == 10 + Category.entries.indexOf(c)),
                    enabled = !off, count = counts[c] ?: 0
                ) {
                    // stays on the page it is on (the library or «Моя медиатека»)
                    LibraryNav.set(inMine, c); onSelect(if (inMine) Tab.Mine else Tab.Library)
                }
            }
            SectionTitle("СЕРВИС")
            SideItem("Настройки", false, GearIcon, onClick = onSettings)
        }
        Spacer(Modifier.height(8.dp))
        // whose library this is
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Sm.glassFill).border(1.dp, Sm.glassEdge, RoundedCornerShape(14.dp)).padding(10.dp)) {
            val p = store.profile
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (p != null) { Avatar(p.avatarUrl, p.name, 32.dp, 13.sp); Spacer(Modifier.width(10.dp)) }
                Text(p?.name?.ifBlank { null } ?: "Моя библиотека", color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x14FFFFFF)).border(1.dp, Sm.glassEdge, RoundedCornerShape(10.dp))
                    .focusRing(RoundedCornerShape(10.dp), color = Color.White, width = 3.dp)
                    .clickable(onClick = onLogout).padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) { Text("Выйти", color = Color.White, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.sp) }
        }
    }
}
