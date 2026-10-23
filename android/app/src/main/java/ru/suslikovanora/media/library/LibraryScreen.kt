package ru.suslikovanora.media.library

import ru.suslikovanora.media.core.*
import ru.suslikovanora.media.ui.*

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.suslikovanora.media.net.Api
import ru.suslikovanora.media.Tv
import ru.suslikovanora.media.tr
import java.util.Locale

/** What the play sheet needs: one file, its display title and (for series) the rest of the season. */
/** [episodeSides]: subtitles and voice-overs of the following episodes, by each episode's link (signed when it starts). */
data class PlayTarget(
    val magnetId: Long, val file: MFile, val label: String, val following: List<MFile>,
    val subs: List<MFile> = emptyList(), val audio: List<MFile> = emptyList(),
    val episodeSides: Map<String, Pair<List<MFile>, List<MFile>>> = emptyMap()
)

object Posters {
    @Volatile private var loader: ImageLoader? = null
    /** Shares the API's HTTP client (cookie + keep-alive); Coil keeps decoded posters in memory and files on disk. */
    fun loader(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(context.applicationContext)
            .okHttpClient(Api.plain)
            .crossfade(350)
            .build().also { loader = it }
    }
}

/** The category the page shows; the side panel's «Коллекция» (TV) sets it. Each page («Библиотека», «Мои фильмы») remembers its own. */
object LibraryNav {
    var category by androidx.compose.runtime.mutableStateOf(Category.All)
    var mineCategory by androidx.compose.runtime.mutableStateOf(Category.All)
    fun of(mine: Boolean) = if (mine) mineCategory else category
    fun set(mine: Boolean, c: Category) { if (mine) mineCategory = c else category = c }
}

private val TAG_DV = Regex("""\b(dolby[ ._-]?vision|dovi|dv)\b""", RegexOption.IGNORE_CASE)
private val TAG_HDR = Regex("""\bhdr(10)?(\+|plus)?\b""", RegexOption.IGNORE_CASE)
private val TAG_DTS = Regex("""\bdts(?:[ ._:-]?(hd|x))?(?:[ ._-]?(ma))?\b""", RegexOption.IGNORE_CASE)

/** Marks of the release for the poster (a guess by the names; the server's answer, see LibraryStore.tagsOf, replaces it). */
fun itemTags(item: LibraryItem): List<String> {
    val text = item.m.name + "\n" + item.m.files.joinToString("\n") { it.path }
    val out = ArrayList<String>(3)
    if (TAG_DV.containsMatchIn(text)) out += "Dolby Vision"
    if (TAG_HDR.containsMatchIn(text)) out += "HDR"
    TAG_DTS.find(text)?.let { m ->
        val kind = m.groupValues[1].lowercase(); val ma = m.groupValues[2].isNotEmpty()
        out += when { kind == "hd" -> if (ma) "DTS-HD MA" else "DTS-HD"; kind == "x" -> "DTS:X"; else -> "DTS" }
    }
    return out
}

enum class Category(val label: String) {
    All(tr("library.vse")),
    Movies(tr("library.filmy")),
    Series(tr("library.serialy")),
    Anime(tr("library.anime")),
    Cartoons(tr("library.multfilmy")),
    Other(tr("library.drugoe"))
}

fun itemYear(item: LibraryItem): String {
    val match = Regex("""\b(19\d{2}|20\d{2})\b""").find(item.m.name)
    if (match != null) return match.value
    for (f in item.m.files) {
        val fMatch = Regex("""\b(19\d{2}|20\d{2})\b""").find(f.name)
        if (fMatch != null) return fMatch.value
    }
    return "2024"
}

fun isAnime(item: LibraryItem): Boolean {
    val text = (item.m.name + " " + item.baseTitle).lowercase()
    return text.contains("anime") || text.contains("аниме") ||
        text.contains("[horriblesubs]") || text.contains("[erai-raws]") ||
        text.contains("[subsplease]") || text.contains("[judas]") ||
        text.contains("[anilibria]") || text.contains("anilibria")
}

private val CARTOON = Regex("""мульт(?!сериал)|анимаци|\bcartoons?\b|\banimation\b|\bPixar\b|\bDreamWorks\b|\bDisney\b""", RegexOption.IGNORE_CASE)

fun isCartoon(item: LibraryItem): Boolean = CARTOON.containsMatchIn(item.m.name) || CARTOON.containsMatchIn(item.baseTitle)

fun itemCategory(item: LibraryItem): Category {
    if (isAnime(item)) return Category.Anime
    if (isCartoon(item)) return Category.Cartoons
    return when (item.playable) {
        is Playable.Series -> Category.Series
        is Playable.Movie -> Category.Movies
        else -> Category.Other
    }
}

fun itemTypeLabel(item: LibraryItem): String = when (itemCategory(item)) {
    Category.Anime -> tr("library.anime")
    Category.Cartoons -> tr("library.multfilm")
    Category.Series -> tr("library.serial")
    Category.Movies -> tr("library.film")
    Category.Other -> tr("library.drugoe")
    Category.All -> tr("library.video")
}

fun itemSpecs(item: LibraryItem, mediaStr: String?): Pair<String, String> {
    val name = item.m.name.lowercase()
    val str = mediaStr?.lowercase().orEmpty()

    val quality = when {
        str.contains("3840") || str.contains("2160") || name.contains("2160p") || name.contains("4k") -> "4K"
        str.contains("1920") || str.contains("1080") || name.contains("1080p") -> "1080p"
        str.contains("1280") || str.contains("720") || name.contains("720p") -> "720p"
        else -> "1080p"
    }

    val codec = when {
        str.contains("hevc") || str.contains("h.265") || str.contains("h265") || name.contains("x265") || name.contains("hevc") || name.contains("h265") || name.contains("h.265") -> "H265"
        str.contains("avc") || str.contains("h.264") || str.contains("h264") || name.contains("x264") || name.contains("h264") || name.contains("h.264") -> "H264"
        str.contains("av1") || name.contains("av1") -> "AV1"
        quality == "4K" -> "H265"
        else -> "H264"
    }

    val fpsMatch = Regex("""(\d+(\.\d+)?)\s*fps""").find(str) ?: Regex("""(\d+(\.\d+)?)\s*fps""").find(name)
    val fps = fpsMatch?.value ?: "23.98 fps"

    val brMatch = Regex("""(\d+(\.\d+)?)\s*мбит/с""").find(str)
    val bitrate = if (brMatch != null) {
        brMatch.value
    } else {
        val durH = 1.5
        val mbps = if (item.m.size > 0) (item.m.size * 8.0) / (durH * 3600.0 * 1_000_000.0) else 0.0
        if (mbps > 0) "%.1f Мбит/с".format(Locale.ROOT, mbps) else "12 Мбит/с"
    }

    val sz = formatBytes(item.m.size)
    return Pair("$quality • $codec • $fps", "$bitrate • $sz")
}

data class ContinueEntry(
    val item: LibraryItem,
    val target: PlayTarget,
    val sub: String,
    val progress: Float,
    val pos: Long,
    val len: Long,
    val at: Long
)

private fun nextEpisode(p: Playable.Series, file: MFile): MFile? {
    val all = p.seasons.flatMap { it.items }
    val i = all.indexOfFirst { it.file.path == file.path }
    return if (i in 0 until all.size - 1) all[i + 1].file else null
}

private fun targetFor(item: LibraryItem, file: MFile): PlayTarget? {
    val m = item.m
    val baseTitle = item.baseTitle
    return when (val p = item.playable) {
        is Playable.Movie -> if (p.file.path == file.path) PlayTarget(m.id, p.file, baseTitle, emptyList(), subtitlesFor(m, p.file), externalAudioFor(m, p.file)) else null
        is Playable.Series -> {
            for (s in p.seasons) {
                val i = s.items.indexOfFirst { it.file.path == file.path }
                if (i < 0) continue
                val e = s.items[i]
                val following = s.items.drop(i).map { it.file }
                val sides = following.drop(1).mapNotNull { f -> f.link?.let { it to (subtitlesFor(m, f) to externalAudioFor(m, f)) } }
                    .filter { (_, v) -> v.first.isNotEmpty() || v.second.isNotEmpty() }.toMap()
                return PlayTarget(m.id, e.file, episodeLabel(baseTitle, s, e), following, subtitlesFor(m, e.file), externalAudioFor(m, e.file), sides)
            }
            null
        }
        Playable.None -> null
    }
}

/** The room's film (`magnetId|path`) as a play target, if this library has it (the «Вместе» page). */
fun targetForKey(items: List<LibraryItem>, key: String): PlayTarget? {
    val id = key.substringBefore('|').toLongOrNull() ?: return null
    val path = key.substringAfter('|', "")
    val item = items.firstOrNull { it.m.id == id && it.m.ready } ?: return null
    val file = item.m.files.firstOrNull { it.path == path } ?: return null
    return targetFor(item, file)
}

internal fun continueEntries(items: List<LibraryItem>, store: LibraryStore): List<ContinueEntry> = items.mapNotNull { item ->
    if (!item.m.ready) return@mapNotNull null
    val (file, w) = store.lastWatched(item.m) ?: return@mapNotNull null
    if (System.currentTimeMillis() - w.at > 60L * 24 * 3600 * 1000) return@mapNotNull null
    if (store.continueHiddenAt(item.m.id) >= w.at) return@mapNotNull null
    if (!w.watched) {
        val t = targetFor(item, file) ?: return@mapNotNull null
        val sub = t.label.removePrefix(item.baseTitle).removePrefix(" · ").ifBlank { tr("library.film") }
        ContinueEntry(item, t, sub, w.fraction, w.pos, w.len, w.at)
    } else {
        val series = item.playable as? Playable.Series ?: return@mapNotNull null
        val next = nextEpisode(series, file) ?: return@mapNotNull null
        if (store.watchInfo(watchKey(item.m.id, next))?.watched == true) return@mapNotNull null
        val t = targetFor(item, next) ?: return@mapNotNull null
        ContinueEntry(item, t, tr("library.dalshe") + t.label.removePrefix(item.baseTitle).removePrefix(" · "), 0f, 0L, 0L, w.at)
    }
}.sortedByDescending { it.at }.take(10)

@Composable
fun LibraryScreen(
    store: LibraryStore,
    onPlay: (PlayTarget) -> Unit,
    onLogout: () -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    /** «Мои фильмы»: only the films in this user's own list (the bookmark on a poster). */
    mineOnly: Boolean = false
) {
    val scope = rememberCoroutineScope()
    val category = LibraryNav.of(mineOnly)
    val tv = Tv.isTv(LocalContext.current)
    val all = store.items
    val source = if (mineOnly) all.filter { it.m.mine } else all
    val filtered = remember(source, query, category) {
        val q = query.trim().lowercase()
        var list = if (q.isEmpty()) source else source.filter { it.searchText.contains(q) }
        if (category != Category.All) {
            list = list.filter { itemCategory(it) == category }
        }
        list.sortedByDescending { it.m.favorite }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 152.dp),
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) { HeaderSlot() }
        item(key = "panel", span = { GridItemSpan(maxLineSpan) }) {
            LibraryPanel(
                title = if (mineOnly) tr("library.moi_filmy") else tr("library.moya_biblioteka_alldebrid"),
                subtitle = if (mineOnly) tr("library.moi_filmy_podskazka") else tr("library.torrenty_papki_i_fayly"),
                query = query, onQuery = onQuery,
                loading = store.loading, status = store.status, kind = store.statusKind,
                totalCount = filtered.size,
                selectedCategory = category,
                onCategorySelect = { LibraryNav.set(mineOnly, it) },
                showCategories = !tv,
                onRefresh = { scope.launch { store.refresh() } }
            )
        }

        // Continue watching section
        if (query.isBlank() && category == Category.All && !mineOnly) {
            item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                if (tv) TvContinueHero(all, store, onPlay) else ContinueRow(all, store, onPlay)
            }
        }

        // Empty state
        if (mineOnly && source.isEmpty() && !store.loading && (store.loadedOnce || store.magnets.isNotEmpty())) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) { EmptyMine() }
        } else if (filtered.isEmpty() && !store.loading && (store.loadedOnce || store.magnets.isNotEmpty())) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Panel {
                    Text(
                        if (store.magnets.isEmpty()) tr("library.sohranennoy_mediateki_poka_net")
                        else tr("library.sovpadeniy_ne_naydeno"),
                        color = Sm.muted, fontFamily = Onest, fontSize = 14.sp
                    )
                }
            }
        }
        itemsIndexed(filtered, key = { _, it -> it.m.id }, contentType = { _, _ -> "tile" }) { index, item ->
            PosterTile(
                item = item, store = store, onPlay = onPlay, index = index,
                modifier = Modifier.animateItem(fadeInSpec = tween(320), fadeOutSpec = tween(220))
            )
        }
    }
}

@Composable
private fun ContinueRow(
    items: List<LibraryItem>,
    store: LibraryStore,
    onOpen: (PlayTarget) -> Unit
) {
    val wv = store.watchVersion
    val entries = remember(items, wv) { continueEntries(items, store) }
    if (entries.isEmpty()) return

    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    tr("library.prodolzhit_prosmotr"),
                    fontFamily = Unbounded,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    entries.size.toString(),
                    color = Color.White,
                    fontFamily = Onest,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(10.dp))
                        .background(Sm.accent).padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x14FFFFFF))
                    .focusRing(RoundedCornerShape(8.dp))
                    .clickable { store.hideContinue(entries.map { it.item.m.id }) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CloseCrossIcon(color = Sm.muted, size = 9.dp)
                Text(
                    tr("library.ochistit"),
                    color = Sm.muted,
                    fontFamily = Onest,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(entries, key = { it.item.m.id }) { e ->
                ContinueCard(e, store, onRemove = { store.hideContinue(listOf(e.item.m.id)) }) { onOpen(e.target) }
            }
        }
    }
}

@Composable
private fun ContinueCard(
    e: ContinueEntry,
    store: LibraryStore,
    onRemove: () -> Unit,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var poster by remember(e.item.m.id) { mutableStateOf(store.cachedPoster(e.item.posterQueries)) }
    LaunchedEffect(e.item.m.id) { if (poster == null) poster = store.poster(e.item.posterQueries) }

    Column(
        Modifier.width(170.dp).clip(RoundedCornerShape(14.dp))
            .background(Sm.glassFill).border(1.dp, Sm.glassEdge, RoundedCornerShape(14.dp))
            .focusRing(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(8.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF13161F))
        ) {
            val url = poster
            if (url != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(url).memoryCacheKey(url).crossfade(350).build(),
                    imageLoader = Posters.loader(context),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape)
                    .background(Color(0x99000000)).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) { CloseCrossIcon(color = Color.White, size = 9.dp) }

            Box(
                Modifier.align(Alignment.Center).size(36.dp).clip(CircleShape)
                    .background(Color(0xB3000000)).border(1.dp, Color(0x33FFFFFF), CircleShape),
                contentAlignment = Alignment.Center
            ) { Text("▶", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(start = 2.dp)) }

            if (e.progress > 0.01f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.5.dp).background(Color(0x66000000))) {
                    Box(Modifier.fillMaxWidth(e.progress).height(3.5.dp).background(Sm.accent))
                }
            }
        }
        Text(
            e.item.baseTitle,
            color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            e.sub,
            color = Color(0xFF60A5FA), fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 11.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/** «Dolby Vision» / «HDR» / «DTS»: a small framed mark on a poster. */
@Composable
fun TagChip(t: String) {
    val c = when { t.startsWith("Dolby") -> Color(0xFFB794F6); t == "HDR" -> Color(0xFFFFC857); else -> Color(0xFF7DD3FC) }
    Box(
        Modifier.height(18.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000))
            .border(1.dp, c.copy(alpha = 0.55f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            t, color = c, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 0.3.sp, maxLines = 1, softWrap = false,
            style = TextStyle(
                lineHeight = 10.sp,
                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                    androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.Both
                )
            )
        )
    }
}

val LocalHeaderHeight = androidx.compose.runtime.compositionLocalOf { 46.dp }

@Composable
fun HeaderSlot() = Spacer(Modifier.fillMaxWidth().height(LocalHeaderHeight.current))

@Composable
fun BrandHeader(onLogout: (() -> Unit)?, onSettings: (() -> Unit)? = null, profile: ru.suslikovanora.media.core.auth.UserProfile? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        val b by breathing(2600)
        Box(
            Modifier.size(42.dp).drawBehind {
                val r = size.maxDimension * (0.62f + 0.12f * b)
                drawCircle(Brush.radialGradient(listOf(Color(0xFF6D5BF0).copy(alpha = 0.25f + 0.22f * b), Color.Transparent)), r)
            },
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(ru.suslikovanora.media.R.drawable.brand_mark),
                null,
                Modifier.size(42.dp)
            )
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            // Signed in with Discord: whose library this is (the PC shows it in the sidebar's profile card)
            Text(profile?.name?.ifBlank { null } ?: tr("library.privatnaya_mediateka"), color = Sm.muted, fontFamily = Onest,
                fontWeight = FontWeight.Medium, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Suslik Media", color = Color.White, fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        if (profile != null) { Avatar(profile.avatarUrl, profile.name, 36.dp, 14.sp); Spacer(Modifier.width(8.dp)) }
        if (onSettings != null) { SettingsButton(onSettings); Spacer(Modifier.width(8.dp)) }
        if (onLogout != null) OutlineButton(tr("library.vyyti"), onClick = onLogout)
    }
}

@Composable
private fun SettingsButton(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val turn by animateFloatAsState(if (pressed) 30f else 0f, tween(260, easing = Sm.ease), label = "gear")
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Sm.glassFill).border(1.dp, Sm.glassEdge, RoundedCornerShape(12.dp))
            .focusRing(RoundedCornerShape(12.dp))
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(20.dp).graphicsLayer { rotationZ = turn }) {
            val c = center; val r = size.minDimension / 2
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val o = androidx.compose.ui.geometry.Offset((c.x + kotlin.math.cos(a) * r * 0.62).toFloat(), (c.y + kotlin.math.sin(a) * r * 0.62).toFloat())
                val e = androidx.compose.ui.geometry.Offset((c.x + kotlin.math.cos(a) * r * 0.95).toFloat(), (c.y + kotlin.math.sin(a) * r * 0.95).toFloat())
                drawLine(Sm.text, o, e, strokeWidth = r * 0.28f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            drawCircle(Sm.text, r * 0.62f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.22f))
            drawCircle(Sm.text, r * 0.16f, c)
        }
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Sm.glassFill)
            .border(1.dp, Sm.glassEdge, RoundedCornerShape(16.dp)).padding(16.dp)
    ) { content() }
}

@Composable
private fun LibraryPanel(
    title: String, subtitle: String,
    query: String, onQuery: (String) -> Unit,
    loading: Boolean, status: String, kind: StatusKind,
    totalCount: Int,
    selectedCategory: Category,
    onCategorySelect: (Category) -> Unit,
    showCategories: Boolean = true,
    onRefresh: () -> Unit
) {
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = Color.White)
                    Text(totalCount.toString(), color = Sm.muted, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                }
                Text(subtitle, color = Sm.muted, fontFamily = Onest, fontSize = 13.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
            RefreshButton(loading = loading, onClick = onRefresh)
        }
        Spacer(Modifier.height(14.dp))
        SearchField(value = query, onValue = onQuery, placeholder = tr("library.poisk_po_nazvaniyu"))
        Spacer(Modifier.height(10.dp))
        if (showCategories) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (cat in Category.entries) {
                val active = cat == selectedCategory
                Box(
                    Modifier.clip(RoundedCornerShape(14.dp))
                        .background(if (active) Sm.accent.copy(alpha = 0.85f) else Color(0x14FFFFFF))
                        .border(1.dp, if (active) Sm.accentEdge else Sm.glassEdge, RoundedCornerShape(14.dp))
                        .focusRing(RoundedCornerShape(14.dp))
                        .clickable { onCategorySelect(cat) }
                        .padding(horizontal = 13.dp, vertical = 7.dp)
                ) {
                    Text(cat.label, color = if (active) Color.White else Color(0xFF8E96A8), fontFamily = Onest,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, fontSize = 12.5.sp)
                }
            }
        }
        if (status.isNotBlank()) {
            val color by animateColorAsState(
                when (kind) { StatusKind.Ok -> Sm.ok; StatusKind.Error -> Sm.bad; StatusKind.Info -> Sm.muted }, label = "status"
            )
            Text(status, color = color, fontFamily = Onest, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun RefreshButton(loading: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val rot = remember { Animatable(0f) }
    LaunchedEffect(loading) {
        if (loading) {
            while (true) rot.animateTo(rot.value + 360f, tween(900, easing = androidx.compose.animation.core.LinearEasing))
        } else if (rot.value != 0f) {
            val end = kotlin.math.ceil(rot.value / 360f) * 360f
            rot.animateTo(end, tween(520, easing = Sm.ease))
            rot.snapTo(0f)
        }
    }
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(180, easing = Sm.ease))
    Box(
        Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.size(42.dp)
            .glass(RoundedCornerShape(12.dp), loading)
            .focusRing(RoundedCornerShape(12.dp))
            .clickable(interactionSource = src, indication = null, enabled = !loading, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        RefreshIcon(Modifier.graphicsLayer { rotationZ = rot.value }, color = if (loading) Color(0xFF60A5FA) else Color.White, size = 16.dp)
    }
}

@Composable
fun SearchField(value: String, onValue: (String) -> Unit, placeholder: String, password: Boolean = false, onDone: (() -> Unit)? = null) {
    val focus = LocalFocusManager.current
    val tv = Tv.isTv(LocalContext.current)
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(if (focused) Sm.accentEdge else Sm.glassEdge, label = "border")
    BasicTextField(
        value = value, onValueChange = onValue, singleLine = true,
        textStyle = TextStyle(color = Sm.text, fontFamily = Onest, fontSize = 15.sp),
        cursorBrush = SolidColor(Color.White),
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            imeAction = if (onDone != null) ImeAction.Go else ImeAction.Search,
            keyboardType = if (password) androidx.compose.ui.text.input.KeyboardType.Password else androidx.compose.ui.text.input.KeyboardType.Text,
            showKeyboardOnFocus = if (tv) false else null
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onAny = { focus.clearFocus(); onDone?.invoke() }),
        modifier = Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp)).background(Color(0xFF12151D))
            .border(if (focused && tv) 2.5.dp else 1.dp, border, RoundedCornerShape(12.dp)),
        decorationBox = { inner ->
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchIcon(modifier = Modifier.padding(end = 10.dp), color = Color(0xFF8E96A8), size = 16.dp)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = Sm.muted, fontFamily = Onest, fontSize = 14.5.sp)
                    inner()
                }
            }
        }
    )
}

@Composable
fun LampButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.96f else 1f, tween(180, easing = Sm.ease), label = "press")
    val bg by animateColorAsState(if (enabled) Sm.accent else Sm.accent.copy(alpha = 0.5f), tween(200))
    val edge by animateColorAsState(if (enabled) Sm.accentEdge else Color.Transparent, tween(240))
    Box(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.55f }
            .shadow(10.dp, RoundedCornerShape(12.dp), ambientColor = Sm.accent, spotColor = Sm.accent)
            .clip(RoundedCornerShape(12.dp)).background(bg).border(1.dp, edge, RoundedCornerShape(12.dp))
            .focusRing(RoundedCornerShape(12.dp), color = Color.White)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp) }
}

@Composable
fun OutlineButton(text: String, modifier: Modifier = Modifier, color: Color = Color.White, border: Color = Sm.glassEdge, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, tween(180, easing = Sm.ease), label = "press")
    Box(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp)).background(Sm.glassFill).border(1.dp, border, RoundedCornerShape(12.dp))
            .focusRing(RoundedCornerShape(12.dp))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = color, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.5.sp) }
}

// ---- tile ------------------------------------------------------------------------------------

@Composable
private fun PosterTile(item: LibraryItem, store: LibraryStore, onPlay: (PlayTarget) -> Unit, index: Int = 0, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val m = item.m
    val playable = item.playable
    val baseTitle = item.baseTitle
    val queries = item.posterQueries
    var poster by remember(m.id) { mutableStateOf(store.cachedPoster(queries)) }
    LaunchedEffect(m.id) { if (poster == null) poster = store.poster(queries) }

    val series = playable as? Playable.Series
    var seasonIdx by remember(m.id) { mutableStateOf(0) }
    var episodeIdx by remember(m.id) { mutableStateOf(0) }
    val watchVersion = store.watchVersion
    val last = remember(m.id, watchVersion) { store.lastWatched(m) }
    LaunchedEffect(m.id, watchVersion) {
        if (series != null) {
            val saved = last?.first?.path ?: store.lastEpisode(m.id)
            series.seasons.forEachIndexed { si, s ->
                val ei = s.items.indexOfFirst { it.file.path == saved }
                if (ei >= 0) { seasonIdx = si; episodeIdx = ei }
            }
        }
    }

    val currentFile: MFile? = when (playable) {
        is Playable.Movie -> playable.file
        is Playable.Series -> (playable.seasons.getOrNull(seasonIdx) ?: playable.seasons.first()).let { s -> (s.items.getOrNull(episodeIdx) ?: s.items.first()).file }
        Playable.None -> null
    }
    var media by remember(m.id, currentFile?.path) { mutableStateOf(currentFile?.let { store.cachedMediaLine(m.id, it) }) }
    LaunchedEffect(m.id, currentFile?.path) {
        if (media == null && currentFile != null && m.ready) media = store.mediaLine(m.id, currentFile)
    }

    fun target(): PlayTarget? = when (playable) {
        is Playable.Movie -> PlayTarget(m.id, playable.file, baseTitle, emptyList(), subtitlesFor(m, playable.file), externalAudioFor(m, playable.file))
        is Playable.Series -> {
            val s = playable.seasons.getOrNull(seasonIdx) ?: playable.seasons.first()
            val e = s.items.getOrNull(episodeIdx) ?: s.items.first()
            store.saveEpisode(m.id, e.file.path)
            val following = s.items.drop(s.items.indexOf(e)).map { it.file }
            val sides = following.drop(1).mapNotNull { f -> f.link?.let { it to (subtitlesFor(m, f) to externalAudioFor(m, f)) } }
                .filter { (_, v) -> v.first.isNotEmpty() || v.second.isNotEmpty() }.toMap()
            PlayTarget(m.id, e.file, episodeLabel(baseTitle, s, e), following, subtitlesFor(m, e.file), externalAudioFor(m, e.file), sides)
        }
        Playable.None -> null
    }

    val appear = remember(m.id) { Animatable(if (store.seen(m.id)) 1f else 0f) }
    LaunchedEffect(m.id) {
        if (appear.value < 1f) {
            delay(minOf(index, 12) * 40L)
            appear.animateTo(1f, tween(460, easing = Sm.ease))
        }
        store.markSeen(m.id)
    }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, tween(200, easing = Sm.ease), label = "tile")
    val canPlay = m.ready && playable !is Playable.None

    Column(modifier.graphicsLayer {
        val a = appear.value
        scaleX = scale * (0.97f + 0.03f * a); scaleY = scaleX
        alpha = a; translationY = (1f - a) * 22.dp.toPx()
    }) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF13161F))
                .border(1.dp, Sm.glassEdge, RoundedCornerShape(14.dp))
                .focusRing(RoundedCornerShape(14.dp), width = 3.dp)
                .clickable(interactionSource = source, indication = null, enabled = canPlay) { target()?.let(onPlay) }
        ) {
            val url = poster
            if (url != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(url).memoryCacheKey(url).placeholderMemoryCacheKey(url).crossfade(350).build(),
                    imageLoader = Posters.loader(context),
                    contentDescription = tr("library.poster_basetitle", "baseTitle" to baseTitle),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    baseTitle, color = Color.White, fontFamily = Unbounded, fontWeight = FontWeight.Medium,
                    fontSize = 14.sp, lineHeight = 18.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 52.dp)
                )
            }
            MineMark(m.mine, Modifier.align(Alignment.TopStart).padding(8.dp)) { on -> store.setMine(m, on) }
            FavStar(m.favorite, Modifier.align(Alignment.TopEnd).padding(8.dp)) { on -> store.setFavorite(m, on) }
            // Dolby Vision / HDR / DTS under the star
            val tags = store.tagsOf(item)
            if (tags.isNotEmpty()) {
                Column(Modifier.align(Alignment.TopEnd).padding(top = 46.dp, end = 8.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (t in tags) TagChip(t)
                }
            }

            // watched progress: electric blue line along the poster's bottom edge
            val watch = remember(m.id, currentFile?.path, watchVersion) { currentFile?.let { store.watchInfo(watchKey(m.id, it)) } }
            if (m.ready && watch != null && watch.fraction > 0.01f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
                    Box(Modifier.fillMaxWidth(if (watch.watched) 1f else watch.fraction).height(4.dp).background(Sm.accent))
                }
            }
            // bottom: progress + status badge
            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                if (m.downloading && m.size > 0) {
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(5.dp)).background(Color(0x50FFFFFF))) {
                        Box(Modifier.fillMaxWidth(m.progress).height(5.dp).clip(RoundedCornerShape(5.dp))
                            .background(Brush.horizontalGradient(listOf(Color(0xFF60A5FA), Sm.accent))))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                val label = when {
                    m.ready -> tr("library.gotovo")
                    m.downloading -> "${(m.progress * 100).toInt()}% · ${statusRu(m)}"
                    else -> statusRu(m)
                }
                Text(
                    label, color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp,
                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0xD910131B)).border(1.dp, Sm.glassEdge, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            if (canPlay) {
                Box(
                    Modifier.align(Alignment.Center).padding(bottom = 20.dp).size(50.dp)
                        .clip(CircleShape).background(Color(0xCC0B1020))
                        .border(1.dp, Color(0x40FFFFFF), CircleShape)
                        .focusProperties { canFocus = false }
                        .clickable { target()?.let(onPlay) },
                    contentAlignment = Alignment.Center
                ) { Text("▶", color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(start = 3.dp)) }
            }
        }
        Column(Modifier.padding(start = 2.dp, end = 2.dp, top = 8.dp)) {
            Text(m.name, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${itemYear(item)} • ${itemTypeLabel(item)} · ${formatBytes(m.size)}", color = Sm.muted, fontFamily = Onest, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            if (m.ready && media != null) {
                val specs = itemSpecs(item, media)
                Text(specs.first, color = Color(0xFFB0B7C6), fontFamily = Onest, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
            }
            if (m.ready && last != null) {
                val (lf, lw) = last
                val ep = (playable as? Playable.Series)?.seasons?.firstNotNullOfOrNull { s ->
                    s.items.firstOrNull { it.file.path == lf.path }?.let { e -> (if (s.season == 0) tr("library.spec") else "S" + s.season.toString().padStart(2, '0')) + "E" + e.episode.toString().padStart(2, '0') }
                }
                val text = when {
                    lw.watched && ep != null -> tr("library.prosmotreno_do_ep", "ep" to ep)
                    lw.watched -> tr("library.prosmotreno")
                    ep != null -> tr("library.prodolzhit_ep_p", "ep" to ep, "p" to formatClock(lw.pos))
                    else -> tr("library.prodolzhit_s_p", "p" to formatClock(lw.pos))
                }
                Text(text, color = Color(0xFF60A5FA), fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
            }
            if (series != null && m.ready) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (series.seasons.size > 1) {
                        SelectChip(
                            label = series.seasons.getOrNull(seasonIdx)?.let { if (it.season == 0) tr("library.spec") else tr("library.sezon_season", "season" to it.season) } ?: "",
                            options = series.seasons.map { if (it.season == 0) tr("library.specvypuski") else tr("library.sezon_season", "season" to it.season) },
                            onPick = { seasonIdx = it; episodeIdx = 0 },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    val season = series.seasons.getOrNull(seasonIdx) ?: series.seasons.first()
                    SelectChip(
                        label = season.items.getOrNull(episodeIdx)?.let { it.title ?: tr("library.seriya_episode", "episode" to it.episode) } ?: "",
                        options = season.items.map { it.title ?: tr("library.seriya_episode", "episode" to it.episode) },
                        onPick = { episodeIdx = it },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (m.canRemove) RemoveButton(downloading = m.downloading) { store.remove(m) }
        }
    }
}

@Composable
private fun SelectChip(label: String, options: List<String>, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Sm.glassFill)
                .border(1.dp, Sm.glassEdge, RoundedCornerShape(10.dp)).focusRing(RoundedCornerShape(10.dp)).clickable { open = true }
                .heightIn(min = 36.dp).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("▾", color = Sm.muted, fontSize = 12.sp)
        }
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.background(Sm.sheet).border(1.dp, Sm.sheetEdge, RoundedCornerShape(10.dp)).heightIn(max = 360.dp)
        ) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(
                    text = { Text(o, color = Sm.text, fontFamily = Onest, fontSize = 14.sp) },
                    onClick = { open = false; onPick(i) }
                )
            }
        }
    }
}

/** Two-tap confirm, like on the website: the first tap arms the button for 4 s. */
@Composable
private fun RemoveButton(downloading: Boolean, onConfirm: suspend () -> String?) {
    val scope = rememberCoroutineScope()
    var armed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(armed) { if (armed) { delay(4000); armed = false } }
    LaunchedEffect(error) { if (error != null) { delay(4000); error = null } }
    val label = when {
        error != null -> tr("add.oshibka_p", "p" to error!!.take(60))
        busy -> if (downloading) tr("library.otmena") else tr("library.udalenie")
        armed -> tr("library.tochno_nazhmite_esche_raz")
        downloading -> tr("library.otmenit")
        else -> tr("library.udalit")
    }
    val bg by animateColorAsState(if (armed) Color(0xFFFF4D4D) else if (downloading) Color(0x1AFFB86B) else Color(0x1AFF6B6B), label = "rmbg")
    val fg = if (armed) Color.White else if (downloading) Sm.warn else Color(0xFFFFB3B3)
    val br = if (armed) Color(0xFFFF4D4D) else if (downloading) Color(0x66FFB86B) else Color(0x66FF6B6B)
    Box(
        Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(bg).border(BorderStroke(1.dp, br), RoundedCornerShape(8.dp))
            .focusRing(RoundedCornerShape(8.dp))
            .clickable(enabled = !busy) {
                if (!armed) { armed = true; error = null }
                else {
                    armed = false; busy = true
                    scope.launch { val err = onConfirm(); busy = false; error = err }
                }
            }
            .heightIn(min = 30.dp).padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) { Text(label, color = fg, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 12.sp) }
}

private fun formatClock(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    val h = t / 3600; val mm = (t % 3600) / 60; val ss = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, mm, ss) else "%02d:%02d".format(mm, ss)
}

/** «Мои фильмы» bookmark in the poster's corner (blue when the film is in the list, pops when toggled). */
@Composable
private fun MineMark(on: Boolean, modifier: Modifier, onToggle: suspend (Boolean) -> String?) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val pop = remember { Animatable(1f) }
    val bg by animateColorAsState(if (on) Color(0x99000000) else Color(0x55000000), tween(220), label = "minebg")
    Box(
        modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }.size(34.dp).clip(CircleShape).background(bg)
            .border(1.dp, if (on) Color(0x33FFFFFF) else Color(0x22FFFFFF), CircleShape)
            .focusRing(CircleShape, color = Color.White)
            .clickable(enabled = !busy, interactionSource = remember { MutableInteractionSource() }, indication = null) {
                busy = true
                scope.launch {
                    launch { pop.animateTo(1.25f, tween(130)); pop.animateTo(1f, tween(220, easing = Sm.ease)) }
                    onToggle(!on); busy = false
                }
            },
        contentAlignment = Alignment.Center
    ) { BookmarkIcon(filled = on, color = if (on) Color(0xFF7FB2FF) else Color.White, size = 16.dp) }
}

@Composable
private fun EmptyMine() {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BookmarkIcon(filled = false, color = Sm.muted, size = 40.dp)
        Text(tr("library.izbrannoe_pusto"), color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
            modifier = Modifier.padding(top = 14.dp))
        Text(tr("library.izbrannoe_pusto_podskazka"), color = Sm.muted, fontFamily = Onest, fontSize = 13.5.sp, lineHeight = 19.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp, start = 12.dp, end = 12.dp))
    }
}

/** Favourite heart in the poster's corner (touch: always visible; pink when on, pops when toggled). */
@Composable
private fun FavStar(on: Boolean, modifier: Modifier, onToggle: suspend (Boolean) -> String?) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val pop = remember { Animatable(1f) }
    val bg by animateColorAsState(if (on) Color(0x99000000) else Color(0x55000000), tween(220), label = "favbg")
    Box(
        modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }.size(34.dp).clip(CircleShape).background(bg)
            .border(1.dp, if (on) Color(0x33FFFFFF) else Color(0x22FFFFFF), CircleShape)
            .focusRing(CircleShape, color = Color.White)
            .clickable(enabled = !busy, interactionSource = remember { MutableInteractionSource() }, indication = null) {
                busy = true
                scope.launch {
                    launch { pop.animateTo(1.25f, tween(130)); pop.animateTo(1f, tween(220, easing = Sm.ease)) }
                    onToggle(!on); busy = false
                }
            },
        contentAlignment = Alignment.Center
    ) { HeartIcon(filled = on, color = if (on) Color(0xFFFF6B8A) else Color.White, size = 16.dp) }
}
