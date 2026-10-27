package ru.suslikovanora.media.library

import ru.suslikovanora.media.core.*

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import ru.suslikovanora.media.tr
import ru.suslikovanora.media.ui.Sm
import ru.suslikovanora.media.ui.Onest
import ru.suslikovanora.media.ui.focusRing

private fun plural(n: Int, one: String, few: String, many: String): String {
    val t = n % 100; val u = n % 10
    return "$n " + if (t in 11..14) many else when (u) { 1 -> one; 2, 3, 4 -> few; else -> many }
}

/** «Сезон 1, серия 4» for an episode of a series; null for a film. */
private fun episodeSub(e: ContinueEntry): String? {
    val p = e.item.playable as? Playable.Series ?: return null
    for (s in p.seasons) {
        val ep = s.items.firstOrNull { it.file.path == e.target.file.path } ?: continue
        if (ep.title != null) return null
        val text = if (s.season == 0) tr("library.specvypusk_n", "episode" to ep.episode) else tr("library.sezon_seriya", "season" to s.season, "episode" to ep.episode)
        return if (e.sub.startsWith(tr("library.dalshe"))) tr("library.dalshe") + text else text
    }
    return null
}

private fun timeLeft(e: ContinueEntry): String? {
    if (e.len <= 0 || e.pos <= 0) return null
    val min = ((e.len - e.pos) / 60_000L).toInt().coerceAtLeast(1)
    return if (min >= 60) tr("library.ostalos_ch_min", "hours" to (min / 60), "minutes" to (min % 60)) else tr("library.ostalos_min", "minutes" to min)
}

/** TV: «Продолжить просмотр» as a banner with the film watched last; the cards on the right choose another (the remote just moves over them). */
@Composable
fun TvContinueHero(all: List<ContinueEntry>, store: LibraryStore, onOpen: (PlayTarget) -> Unit) {
    val entries = remember(all) { all.take(CONTINUE_SHOWN) }
    if (entries.isEmpty()) return
    val context = LocalContext.current
    var chosen by remember { mutableStateOf<Long?>(null) }
    var focused by remember { mutableStateOf(false) }
    val e = entries.firstOrNull { it.item.m.id == chosen } ?: entries.first()
    val queries = e.item.posterQueries
    var hero by remember(e.item.m.id) { mutableStateOf(store.cachedHero(queries)) }
    LaunchedEffect(e.item.m.id) { if (hero == null) hero = store.hero(queries) }

    // The banners change by themselves, one after another (not while the remote is on the banner)
    LaunchedEffect(entries.map { it.item.m.id }, chosen, focused) {
        if (entries.size < 2 || focused) return@LaunchedEffect
        delay(9000)
        val cur = entries.indexOfFirst { it.item.m.id == chosen }.coerceAtLeast(0)
        chosen = entries[(cur + 1) % entries.size].item.m.id
    }

    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.padding(top = 2.dp, bottom = 6.dp).fillMaxWidth().height(if (LocalConfiguration.current.screenWidthDp < 560) 350.dp else 340.dp).clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF0B1226)).border(1.dp, Sm.glassEdge, RoundedCornerShape(20.dp))
            .onFocusChanged { focused = it.hasFocus }
    ) {
        // a phone: no room for the cards on the right, the text takes the width and the dots below switch the film
        val narrow = maxWidth < 560.dp
        // the wide picture on the right; without it the banner stays plain (a stretched poster would be blurry)
        Crossfade(hero?.backdrop, animationSpec = tween(400), modifier = Modifier.fillMaxSize(), label = "backdrop") { url ->
            if (url != null) AsyncImage(
                model = ImageRequest.Builder(context).data(url).memoryCacheKey(url).build(), imageLoader = Posters.loader(context),
                contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd, modifier = Modifier.fillMaxSize()
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xFF0B1226), 0.45f to Color(0xF20B1226), 0.8f to Color(0x330B1226), 1f to Color(0x000B1226))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xAA0B1226))))
        if (narrow) Box(Modifier.fillMaxSize().background(Color(0x990B1226)))

        // the text stops where the cards begin (4 cards: 78 dp each, 10 dp apart, 16 dp from the edge, a gap of 14 dp)
        val cardsWidth = if (entries.size > 1 && !narrow) (78 * entries.size + 10 * (entries.size - 1) + 16 + 14).dp else 0.dp
        Column(
            (if (narrow) Modifier.fillMaxWidth().padding(end = 18.dp) else if (entries.size > 1) Modifier.fillMaxWidth().padding(end = cardsWidth) else Modifier.fillMaxWidth(0.58f))
                .fillMaxHeight().padding(start = 26.dp, top = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(tr("library.prodolzhit_prosmotr").uppercase(), color = Color(0xFF9AA3B8), fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.6.sp)
                val logo = hero?.logo
                if (logo != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(logo).memoryCacheKey(logo).build(), imageLoader = Posters.loader(context),
                        contentDescription = e.item.baseTitle, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                        modifier = Modifier.padding(top = 8.dp).height(56.dp).widthIn(max = 300.dp)
                    )
                } else {
                    Text(e.item.baseTitle, color = Color.White, fontFamily = Onest, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    @Composable fun chip(t: String) = Text(t, color = Color(0xFFD6DBE4), fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x1AFFFFFF)).border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 3.dp))
                    hero?.year?.let { chip(it) }
                    chip(itemTypeLabel(e.item))
                    (e.item.playable as? Playable.Series)?.let { p ->
                        chip(plural(p.seasons.size, tr("common.plural_sezon_1"), tr("common.plural_sezon_2"), tr("common.plural_sezon_5")) + " • " + plural(p.seasons.sumOf { it.items.size }, tr("common.plural_seriya_1"), tr("common.plural_seriya_2"), tr("common.plural_seriya_5")))
                    }
                    hero?.rating?.let { r -> Text("★ " + "%.1f".format(java.util.Locale.US, r), color = Color(0xFFFACC15), fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp) }
                }
                hero?.overview?.let {
                    Text(it, color = Color(0xFFC3C9D6), fontFamily = Onest, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
            Column {
                Row(Modifier.fillMaxWidth().padding(bottom = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(episodeSub(e) ?: e.sub, color = Color(0xFFC3C9D6), fontFamily = Onest, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    timeLeft(e)?.let { Text(it, color = Color(0xFF8E96A8), fontFamily = Onest, fontSize = 11.5.sp, maxLines = 1, softWrap = false) }
                }
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
                    Box(Modifier.fillMaxWidth(e.progress.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(2.dp))
                        .background(Brush.horizontalGradient(listOf(Sm.accent, Color(0xFF38BDF8)))))
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Sm.accent).border(1.dp, Sm.accentEdge, RoundedCornerShape(12.dp))
                        .focusRing(RoundedCornerShape(12.dp), color = Color.White)
                        .clickable { store.saveEpisode(e.item.m.id, e.target.file.path); onOpen(e.target) }
                        .heightIn(min = 42.dp).padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("▶", color = Color.White, fontSize = 13.sp)
                    Text(tr("library.prodolzhit"), color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
        }

        // the other films: the remote moves over the cards (the banner follows), OK opens the one it stands on
        if (entries.size > 1 && narrow) {
            Row(Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (x in entries) Box(
                    Modifier.size(width = if (x.item.m.id == e.item.m.id) 18.dp else 7.dp, height = 7.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (x.item.m.id == e.item.m.id) Color.White else Color(0x66FFFFFF))
                        .clickable { chosen = x.item.m.id }
                )
            }
        }
        if (entries.size > 1 && !narrow) {
            Row(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (x in entries) HeroThumb(x, store, selected = x.item.m.id == e.item.m.id,
                    onFocus = { chosen = x.item.m.id }, onClick = { store.saveEpisode(x.item.m.id, x.target.file.path); onOpen(x.target) })
            }
        }
    }
}

@Composable
private fun HeroThumb(e: ContinueEntry, store: LibraryStore, selected: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    val context = LocalContext.current
    var poster by remember(e.item.m.id) { mutableStateOf(store.cachedPoster(e.item.posterQueries)) }
    LaunchedEffect(e.item.m.id) { if (poster == null) poster = store.poster(e.item.posterQueries) }
    Box(
        Modifier.width(78.dp).height(117.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF222736))
            .border(if (selected) 2.dp else 1.dp, if (selected) Color(0xFF7DD3FC) else Color(0x26FFFFFF), RoundedCornerShape(10.dp))
            .onFocusChanged { if (it.isFocused) onFocus() }
            .focusRing(RoundedCornerShape(10.dp), color = Color.White, width = 3.dp)
            .clickable(onClick = onClick)
    ) {
        poster?.let {
            AsyncImage(
                model = ImageRequest.Builder(context).data(it).memoryCacheKey(it).build(), imageLoader = Posters.loader(context),
                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(e.progress.coerceIn(0f, 1f)).height(3.dp).background(Color(0xFF38BDF8)))
    }
}

/** At the start of the app: the banners' texts and pictures (wide picture, logo, posters) are loaded before the page opens. */
suspend fun warmHero(store: LibraryStore, context: android.content.Context) {
    val loader = Posters.loader(context)
    for (e in continueEntries(store.items, store).take(CONTINUE_SHOWN)) {
        val q = e.item.posterQueries
        runCatching {
            store.poster(q)?.let { loader.enqueue(ImageRequest.Builder(context).data(it).memoryCacheKey(it).build()) }
            store.hero(q)?.let { h ->
                for (u in listOfNotNull(h.backdrop, h.logo)) loader.enqueue(ImageRequest.Builder(context).data(u).memoryCacheKey(u).build())
            }
        }
    }
}
