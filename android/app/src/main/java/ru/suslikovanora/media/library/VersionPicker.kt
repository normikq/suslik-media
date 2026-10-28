package ru.suslikovanora.media.library

import ru.suslikovanora.media.core.*
import ru.suslikovanora.media.tr
import ru.suslikovanora.media.ui.Onest
import ru.suslikovanora.media.ui.Sm
import ru.suslikovanora.media.ui.Unbounded
import ru.suslikovanora.media.ui.focusRing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun versionsWord(n: Int) = tr(if (n % 10 in 2..4 && n % 100 !in 12..14) "library.versii_2" else "library.versiy_5", "n" to n)

/** What a chosen version opens: a film, or a series from where it was left (or its first episode); the play sheet follows. */
fun versionTarget(item: LibraryItem, store: LibraryStore): PlayTarget? {
    val saved = store.lastWatched(item.m)?.first?.path ?: store.lastEpisode(item.m.id)
    val file = when (val p = item.playable) {
        is Playable.Movie -> p.file
        is Playable.Series -> p.seasons.flatMap { it.items }.let { eps -> (eps.firstOrNull { it.file.path == saved } ?: eps.first()).file }
        Playable.None -> return null
    }
    return targetFor(item, file)
}

/** «Какую версию включить?»: one row per release (series: seasons and episodes in it), with quality, size and progress. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionSheet(title: String, versions: List<LibraryItem>, store: LibraryStore, onDismiss: () -> Unit, onChoose: (LibraryItem) -> Unit) {
    val sections = remember(versions) { versionSections(versions) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF10131B)
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 18.dp).padding(bottom = 16.dp)) {
            Text(tr("library.vyberite_versiyu"), color = Sm.text, fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 17.sp)
            Text(
                title + " · " + versionsWord(versions.size), color = Sm.muted, fontFamily = Onest, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sections.forEach { section ->
                    val secTitle = section.title
                    if (secTitle != null) {
                        SectionHeader(secTitle)
                    }
                    section.items.forEach { v -> VersionRow(v, store, inSection = secTitle != null) { onChoose(v) } }
                }
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
            fontSize = 11.sp,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Color(0x22FFFFFF)))
    }
}

@Composable
private fun VersionRow(v: LibraryItem, store: LibraryStore, inSection: Boolean, onClick: () -> Unit) {
    val series = v.playable as? Playable.Series
    val watched = if (series != null) store.lastWatched(v.m)?.second
        else (v.playable as? Playable.Movie)?.file?.let { store.watchInfo(watchKey(v.m.id, it)) }
    val baseSpecs = itemSpecs(v, null).first.split(" • ").take(2).joinToString(" • ")
    val tags = store.tagsOf(v).filter { it == "Dolby Vision" || it == "HDR" }
    val specs = if (tags.isNotEmpty()) baseSpecs + " • " + tags.joinToString(" • ") else baseSpecs

    // what the release holds (shared versionLine: seasons, films, year) • quality
    val heading = listOf(versionLine(v, inSection), specs).filter { it.isNotBlank() }.joinToString(" • ")

    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Color(0x14FFFFFF)).border(1.dp, Sm.glassEdge, shape)
            .focusRing(shape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                heading,
                color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f)
            )
            Text(formatBytes(v.m.size), color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp)
        }
        Text(v.m.name, color = Sm.muted, fontFamily = Onest, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        if (watched != null && watched.fraction > 0.01f) {
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF202636))) {
                Box(Modifier.fillMaxWidth(if (watched.watched) 1f else watched.fraction).height(3.dp).background(Sm.accent))
            }
        }
    }
}
