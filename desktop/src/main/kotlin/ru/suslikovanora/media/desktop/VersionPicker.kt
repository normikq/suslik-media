package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*
import ru.suslikovanora.media.desktop.ui.*

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val YEAR = Regex("""\b(19\d{2}|20\d{2})\b""")

/**
 * Same film added more than once (different releases: 720p / 1080p / 4K, other voice-overs): one tile for all of them.
 * Only ready single films are merged — series and collections keep their own tiles. The first one in the list order
 * stays on screen; [Pair.second] maps its id to every version (itself included).
 */
fun groupVersions(list: List<LibraryItem>): Pair<List<LibraryItem>, Map<Long, List<LibraryItem>>> {
    val byKey = LinkedHashMap<String, MutableList<LibraryItem>>()
    val shown = ArrayList<LibraryItem>(list.size)
    for (it in list) {
        if (it.playable !is Playable.Movie || !it.m.ready || it.baseTitle.isBlank()) { shown += it; continue }
        val key = it.baseTitle.lowercase() + "|" + (YEAR.find(it.m.name)?.value ?: "")
        val group = byKey[key]
        if (group == null) { byKey[key] = mutableListOf(it); shown += it } else group += it
    }
    val versions = HashMap<Long, List<LibraryItem>>()
    for (g in byKey.values) if (g.size > 1) versions[g.first().m.id] = g
    return shown to versions
}

fun movieTarget(item: LibraryItem): PlayTarget? {
    val movie = item.playable as? Playable.Movie ?: return null
    return PlayTarget(item.m.id, movie.file, item.baseTitle, emptyList(), subtitlesFor(item.m, movie.file), externalAudioFor(item.m, movie.file), item)
}

fun versionsWord(n: Int) = tr(if (n % 10 in 2..4 && n % 100 !in 12..14) "library.versii_2" else "library.versiy_5", "n" to n)

/** «Какую версию включить?»: one row per release, with its quality, size and how far it was watched. */
@Composable
fun VersionPicker(title: String, versions: List<LibraryItem>, lib: Library, onDismiss: () -> Unit, onChoose: (LibraryItem) -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(260, easing = Sm.ease))
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = a }.background(Sm.scrim)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.graphicsLayer { translationY = (1f - a) * 24f; scaleX = 0.97f + 0.03f * a; scaleY = scaleX }
                .widthIn(max = 560.dp).padding(24.dp).glassSheet(RoundedCornerShape(22.dp))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                .padding(22.dp)
        ) {
            Text(tr("library.vyberite_versiyu"), color = Sm.text, fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 20.sp)
            Text(title + " · " + versionsWord(versions.size), color = Sm.muted, fontFamily = Onest, fontSize = 13.5.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                versions.forEach { v -> VersionRow(v, lib) { onChoose(v) } }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                OutlineButton(tr("library.zakryt")) { onDismiss() }
            }
        }
    }
}

@Composable
private fun VersionRow(v: LibraryItem, lib: Library, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered) Sm.glassFillHover else Sm.glassFill, tween(200))
    val edge by animateColorAsState(if (hovered) Sm.glassEdgeHover else Sm.glassEdge, tween(200))
    val file = (v.playable as? Playable.Movie)?.file
    val watched = file?.let { lib.watchInfo(watchKey(v.m.id, it)) }
    val specs = itemSpecs(v, null).first.split(" • ").take(2).joinToString(" • ") // quality • codec (the frame rate there is a guess)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).border(1.dp, edge, RoundedCornerShape(14.dp))
            .hoverClick(src, onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(specs, color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
            Text(formatBytes(v.m.size), color = Sm.muted, fontFamily = Onest, fontSize = 13.sp)
        }
        Text(v.m.name, color = Sm.muted, fontFamily = Onest, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        if (watched != null && watched.fraction > 0.01f) {
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF202636))) {
                Box(Modifier.fillMaxWidth(if (watched.watched) 1f else watched.fraction).height(3.dp).background(Sm.accent))
            }
        }
    }
}
