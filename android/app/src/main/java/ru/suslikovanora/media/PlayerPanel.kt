package ru.suslikovanora.media

import ru.suslikovanora.media.ui.focusRing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.suslikovanora.media.player.PlayerSheet
import ru.suslikovanora.media.player.PlayerUiState
import ru.suslikovanora.media.ui.Onest
import ru.suslikovanora.media.ui.Sm
import ru.suslikovanora.media.ui.Unbounded

/** What the panel under the film shows; written by PlayerActivity, read by [PlayerInfoPanel]. */
class PlayerPanelState {
    var title by mutableStateOf("")
    var episodes by mutableStateOf<List<String>>(emptyList())
    var index by mutableIntStateOf(-1)
    var scale by mutableStateOf("")
    var bottomInsetPx by mutableIntStateOf(0)
    /** The panel over the film: its audio / subtitles sheet opens here while the phone is upright. */
    var ui: PlayerUiState? = null
    var onAudio: () -> Unit = {}
    var onSubs: () -> Unit = {}
    var onScale: () -> Unit = {}
    var onFull: () -> Unit = {}
    var onLock: () -> Unit = {}
    var onEpisode: (Int) -> Unit = {}
}

/** Phone held upright: under the film — its title, the player's buttons and the episodes of the playlist. */
@Composable
fun PlayerInfoPanel(s: PlayerPanelState) {
    val bottom = with(LocalDensity.current) { s.bottomInsetPx.toDp() }
    Column(Modifier.fillMaxSize().background(Sm.night).padding(bottom = bottom)) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp)) {
            Text(s.title, color = Sm.text, fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 17.sp,
                lineHeight = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (s.episodes.size > 1 && s.index >= 0)
                Text(tr("player.seriya_p_iz_size", "p" to s.index + 1, "size" to s.episodes.size), color = Sm.muted, fontFamily = Onest, fontWeight = FontWeight.Medium,
                    fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PanelChip(tr("player.na_ves_ekran_2"), accent = true, onClick = s.onFull)
            PanelChip(tr("player.audio"), onClick = s.onAudio)
            PanelChip(tr("player.cc_subtitry"), onClick = s.onSubs)
            PanelChip(tr("player.ves_ekran_scale", "scale" to s.scale), onClick = s.onScale)
            PanelChip(tr("player.blokirovka"), onClick = s.onLock)
        }
        s.ui?.let { ui ->
            AnimatedVisibility(
                visible = !ui.full && ui.picker != null,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
            ) { PlayerSheet(ui, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Sm.line))
        if (s.episodes.size > 1) {
            Text(tr("player.serii"), color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 4.dp))
            val list = rememberLazyListState()
            LaunchedEffect(s.index) { if (s.index > 0) list.animateScrollToItem((s.index - 1).coerceAtLeast(0)) }
            LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(s.episodes) { i, t -> EpisodeRow(i, t, i == s.index) { s.onEpisode(i) } }
            }
        } else {
            Spacer(Modifier.weight(1f))
            Text(tr("player.povernite_telefon_film_otkroet"), color = Sm.muted, fontFamily = Onest, fontSize = 13.sp,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp))
            Spacer(Modifier.weight(1.4f))
        }
    }
}

@Composable
private fun PanelChip(text: String, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp))
            .background(if (accent) Color(0xFF282E3C) else Color(0xFF141720))
            .border(1.dp, if (accent) Color(0xFF3E475C) else Color(0xFF242A38), RoundedCornerShape(20.dp))
            .focusRing(RoundedCornerShape(20.dp), color = Color.White).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (accent) Color.White else Color(0xFFD6DBE4), fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.5.sp, maxLines = 1)
    }
}

@Composable
private fun EpisodeRow(i: Int, title: String, current: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (current) Color(0xFF1C202B) else Sm.booth)
            .border(1.dp, if (current) Color(0xFF3E475C) else Sm.line, RoundedCornerShape(14.dp))
            .focusRing(RoundedCornerShape(14.dp), color = Color.White).clickable(enabled = !current, onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(if (current) Color.White else Sm.film), contentAlignment = Alignment.Center) {
            Text("${i + 1}", color = if (current) Color(0xFF111418) else Sm.muted, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        Text(title, color = if (current) Sm.text else Sm.text.copy(alpha = 0.85f), fontFamily = Onest,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, fontSize = 14.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 12.dp))
        if (current) Text(tr("player.seychas"), color = Color.White.copy(alpha = 0.75f), fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 12.sp,
            modifier = Modifier.padding(start = 8.dp))
    }
}
