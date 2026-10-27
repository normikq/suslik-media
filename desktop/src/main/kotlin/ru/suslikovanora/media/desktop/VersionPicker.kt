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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

fun movieTarget(item: LibraryItem): PlayTarget? {
    val movie = item.playable as? Playable.Movie ?: return null
    return PlayTarget(item.m.id, movie.file, item.baseTitle, emptyList(), subtitlesFor(item.m, movie.file), externalAudioFor(item.m, movie.file), item)
}

/** What a chosen version opens: a film plays, a series goes on from where it was left (or from its first episode). */
fun versionTarget(item: LibraryItem, lib: Library): PlayTarget? {
    val series = item.playable as? Playable.Series ?: return movieTarget(item)
    val m = item.m
    val saved = lib.lastWatched(m)?.first?.path ?: lib.lastEpisode(m.id)
    val seasons = series.seasons
    val s = seasons.firstOrNull { s -> s.items.any { it.file.path == saved } } ?: seasons.first()
    val e = s.items.firstOrNull { it.file.path == saved } ?: s.items.first()
    return PlayTarget(m.id, e.file, episodeLabel(item.baseTitle, s, e), s.items.drop(s.items.indexOf(e)).map { it.file }, subtitlesFor(m, e.file), externalAudioFor(m, e.file), item) { f -> externalAudioFor(m, f) to subtitlesFor(m, f) }
}

fun versionsWord(n: Int) = tr(if (n % 10 in 2..4 && n % 100 !in 12..14) "library.versii_2" else "library.versiy_5", "n" to n)

/** «Какую версию включить?»: one row per release, with its quality, size and how far it was watched. */
@Composable
fun VersionPicker(title: String, versions: List<LibraryItem>, lib: Library, onDismiss: () -> Unit, onChoose: (LibraryItem) -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(260, easing = Sm.ease))
    val sections = remember(versions) { versionSections(versions) }

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
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sections.forEach { section ->
                    val secTitle = section.title
                    if (secTitle != null) {
                        SectionHeader(secTitle)
                    }
                    section.items.forEach { v -> VersionRow(v, lib, inSection = secTitle != null) { onChoose(v) } }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                OutlineButton(tr("library.zakryt")) { onDismiss() }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title.uppercase(),
            color = Sm.muted,
            fontFamily = Onest,
            fontWeight = FontWeight.Bold,
            fontSize = 11.5.sp,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Sm.line))
    }
}

@Composable
private fun VersionRow(v: LibraryItem, lib: Library, inSection: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered) Sm.glassFillHover else Sm.glassFill, tween(200))
    val edge by animateColorAsState(if (hovered) Sm.glassEdgeHover else Sm.glassEdge, tween(200))
    val series = v.playable as? Playable.Series
    val watched = if (series != null) lib.lastWatched(v.m)?.second
        else (v.playable as? Playable.Movie)?.file?.let { lib.watchInfo(watchKey(v.m.id, it)) }
    val baseSpecs = itemSpecs(v, null).first.split(" • ").take(2).joinToString(" • ")
    val tags = lib.tagsOf(v).filter { it == "Dolby Vision" || it == "HDR" }
    val specs = if (tags.isNotEmpty()) baseSpecs + " • " + tags.joinToString(" • ") else baseSpecs

    val heading = when {
        series != null -> {
            val sRange = itemSeasons(v)
            val eps = series.seasons.sumOf { it.items.size }
            val first = series.seasons.firstOrNull()?.items?.firstOrNull()
            val isFilms = first?.title != null
            val epWord = if (isFilms) "фильм." else if (eps % 10 in 2..4 && eps % 100 !in 12..14) "серии" else if (eps % 10 == 1 && eps % 100 != 11) "серия" else "серий"
            if (inSection) {
                if (sRange != null && sRange.first != sRange.second) {
                    "Сезоны ${sRange.first}–${sRange.second} · $eps $epWord • $specs"
                } else {
                    "$eps $epWord • $specs"
                }
            } else {
                seriesLine(series) + " • " + specs
            }
        }
        else -> {
            val y = releaseYear(v)
            if (y != null && !inSection) "$y год • $specs" else specs
        }
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).border(1.dp, edge, RoundedCornerShape(14.dp))
            .hoverClick(src, onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(heading, color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
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
