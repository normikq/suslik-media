package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*
import ru.suslikovanora.media.desktop.player.*
import ru.suslikovanora.media.desktop.ui.*

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.defaultScrollbarStyle
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

data class PlayTarget(
    val magnetId: Long, val file: MFile, val label: String, val following: List<MFile>, val subs: List<MFile>, val audio: List<MFile> = emptyList(),
    val item: LibraryItem? = null,
    /** External voice-overs and subtitles of any episode of the torrent (for the following episodes). */
    val sidecarsOf: (MFile) -> Pair<List<MFile>, List<MFile>> = { emptyList<MFile>() to emptyList() }
)

enum class Category(val label: String) {
    All(tr("library.vse")),
    Movies(tr("library.filmy")),
    Series(tr("library.serialy")),
    Anime(tr("library.anime")),
    Other(tr("library.drugoe"))
}

enum class SortBy(val label: String) {
    Name(tr("library.nazvanie")),
    Newest(tr("library.snachala_novye")),
    Size(tr("library.razmer")),
    Favorite(tr("library.izbrannoe"))
}

enum class ViewMode { Grid, List }

/** Signed mirror links for files next to a video (subtitles, external voice-overs). */
internal suspend fun mirrorUrls(files: List<MFile>): List<String> = coroutineScope {
    val enc = { v: String -> URLEncoder.encode(v, "UTF-8") }
    files.filter { it.link != null }.map { f ->
        async {
            runCatching { Api.get("/api/file/mirror-url?link=${enc(f.link!!)}&name=${enc(f.name)}").optString("url").ifBlank { null } }.getOrNull()
                ?.also { ru.suslikovanora.media.desktop.player.SidePaths.remember(it, f.path) }
        }
    }.mapNotNull { it.await() }
}

private fun openSite() = runCatching { Desktop.getDesktop().browse(URI(Api.SITE)) }
internal fun copy(text: String) = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
internal fun clock(ms: Long): String = clockText(ms)

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

fun itemCategory(item: LibraryItem): Category {
    if (isAnime(item)) return Category.Anime
    return when (item.playable) {
        is Playable.Series -> Category.Series
        is Playable.Movie -> Category.Movies
        else -> Category.Other
    }
}

fun itemTypeLabel(item: LibraryItem): String {
    return when (itemCategory(item)) {
        Category.Anime -> tr("library.anime")
        Category.Series -> tr("library.serial")
        Category.Movies -> tr("library.film")
        Category.Other -> tr("library.drugoe")
        Category.All -> tr("library.video")
    }
}

/** Formats technical media line 1 (Quality • Codec • FPS) and line 2 (Bitrate • Size). */
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
    } else if (item.m.size > 0) {
        val approx = (item.m.size * 8.0) / (7200.0 * 1_000_000.0)
        String.format(Locale.US, tr("library.f_mbit_s"), approx.coerceIn(8.0, 75.0))
    } else {
        tr("library.mbit_s_2")
    }

    val sizeStr = formatBytes(item.m.size)
    return ("$quality • $codec • $fps") to ("$bitrate • $sizeStr")
}

/** Rounded square gear button. */
@Composable
private fun GearButton(onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val turn by animateFloatAsState(if (hovered) 45f else 0f, tween(380, easing = Sm.ease))
    val edge by animateColorAsState(if (hovered) Color(0xFF3E475C) else Color(0xFF242A38), tween(200))
    val bg by animateColorAsState(if (hovered) Color(0xFF1C202B) else Color(0xFF141720), tween(200))
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(bg)
            .border(1.dp, edge, RoundedCornerShape(12.dp))
            .hoverClick(src, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(19.dp).graphicsLayer { rotationZ = turn }) {
            val c = center; val r = size.minDimension / 2
            val col = if (hovered) Color.White else Color(0xFF8E96A8)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                drawLine(col, Offset((c.x + kotlin.math.cos(a) * r * 0.62).toFloat(), (c.y + kotlin.math.sin(a) * r * 0.62).toFloat()),
                    Offset((c.x + kotlin.math.cos(a) * r * 0.95).toFloat(), (c.y + kotlin.math.sin(a) * r * 0.95).toFloat()),
                    strokeWidth = r * 0.28f, cap = StrokeCap.Round)
            }
            drawCircle(col, r * 0.62f, c, style = Stroke(width = r * 0.22f))
            drawCircle(col, r * 0.16f, c)
        }
    }
}

@Composable
fun Brand(onLogout: (() -> Unit)?, tabs: (@Composable () -> Unit)? = null) {
    // Logo on the left, tabs in the middle of the window, settings/exit on the right.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            LogoPlayIcon(size = 36.dp)
            Text(
                "Suslik Media",
                color = Color.White,
                fontFamily = Onest,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
        tabs?.invoke()
        if (onLogout != null) Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            UpdateButton() // appears (breathing) only when a newer version is on the server
            if (Updater.available != null) Spacer(Modifier.width(10.dp))
            GearButton { PlaybackSettings.open = true }
            Spacer(Modifier.width(10.dp))
            PillButton(tr("library.vyyti"), onClick = onLogout)
        }
    }
}

@Composable
fun LoginView(lib: Library) {
    val scope = rememberCoroutineScope()
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit() {
        if (busy || pass.isBlank()) return
        busy = true; error = null
        scope.launch { val e = lib.login(pass); busy = false; if (e == null) pass = "" else error = e }
    }
    Column(Modifier.fillMaxSize().background(Sm.night).padding(32.dp)) {
        Brand(null)
        Column(Modifier.widthIn(max = 640.dp).padding(top = 64.dp)) {
            Text(tr("library.vse_vashi_ssylki_v"), color = Sm.muted, fontFamily = Onest, fontSize = 14.sp)
            Text(tr("library.naydite_razblokiruyte_smotrite"), color = Sm.text, fontFamily = Unbounded, fontWeight = FontWeight.Bold,
                fontSize = 40.sp, lineHeight = 46.sp, modifier = Modifier.padding(top = 6.dp, bottom = 28.dp))
            Panel {
                Text(tr("library.voyti"), fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 20.sp, color = Sm.text)
                Text(tr("library.vvedite_obschiy_parol_dostupa"), color = Sm.muted, fontFamily = Onest, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                Field(pass, { pass = it }, tr("library.parol_dostupa"), password = true, onDone = { submit() })
                Spacer(Modifier.height(12.dp))
                LampButton(if (busy) tr("library.vypolnyaetsya_vhod") else tr("library.voyti"), enabled = !busy && pass.isNotBlank()) { submit() }
                error?.let { Text(it, color = Sm.bad, fontFamily = Onest, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)) }
            }
        }
    }
}

@Composable
fun LibraryView(
    lib: Library,
    grid: androidx.compose.foundation.lazy.grid.LazyGridState = rememberLazyGridState(),
    onPlay: (PlayRequest) -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<PlayTarget?>(null) }
    var category by remember { mutableStateOf(Category.All) }
    var viewMode by remember { mutableStateOf(ViewMode.Grid) }
    var sortBy by remember { mutableStateOf(SortBy.Name) }
    var onlyReady by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }

    val all = lib.items
    val filtered = remember(all, query, category, sortBy, onlyReady) {
        val q = query.trim().lowercase()
        var list = if (q.isEmpty()) all else all.filter { it.searchText.contains(q) }

        if (onlyReady) {
            list = list.filter { it.m.ready }
        }

        if (category != Category.All) {
            list = list.filter { itemCategory(it) == category }
        }

        when (sortBy) {
            SortBy.Name -> list.sortedWith(compareByDescending<LibraryItem> { it.m.favorite }.thenBy { it.baseTitle.lowercase() })
            SortBy.Newest -> list.sortedWith(compareByDescending<LibraryItem> { it.m.favorite }.thenByDescending { it.m.id })
            SortBy.Size -> list.sortedWith(compareByDescending<LibraryItem> { it.m.favorite }.thenByDescending { it.m.size })
            SortBy.Favorite -> list.sortedByDescending { it.m.favorite }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (viewMode == ViewMode.Grid) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(180.dp), state = grid,
                modifier = Modifier.onGloballyPositioned { TileBounds.viewport = it },
                contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 36.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. Header (Title, Subtitle, Refresh button, Search bar, Count)
                item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                    LibraryHeader(
                        query = query,
                        onQueryChange = { query = it },
                        totalCount = filtered.size,
                        lib = lib,
                        onRefresh = { scope.launch { lib.refresh() } }
                    )
                }

                // 2. Continue watching section
                if (query.isBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "continue", contentType = "continue") {
                        ContinueRow(all, lib) { t -> target = t }
                    }
                }

                // 3. Category filter chips and View/Sort Controls
                item(span = { GridItemSpan(maxLineSpan) }, key = "filters_bar") {
                    FilterControlsBar(
                        selectedCategory = category,
                        onCategorySelect = { category = it },
                        viewMode = viewMode,
                        onViewModeSelect = { viewMode = it },
                        sortBy = sortBy,
                        onSortSelect = { sortBy = it },
                        sortOpen = sortOpen,
                        onSortOpenChange = { sortOpen = it },
                        onlyReady = onlyReady,
                        onToggleOnlyReady = { onlyReady = !onlyReady }
                    )
                }

                // 4. Catalog grid of poster cards
                items(filtered, key = { it.m.id }, contentType = { "tile" }) { item ->
                    Tile(item, lib, onOpen = { target = it }, modifier = Modifier.animateItem(fadeInSpec = tween(300), fadeOutSpec = tween(200)))
                }
            }
        } else {
            // List View Mode
            LazyColumn(
                modifier = Modifier.onGloballyPositioned { TileBounds.viewport = it },
                contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "header") {
                    LibraryHeader(
                        query = query,
                        onQueryChange = { query = it },
                        totalCount = filtered.size,
                        lib = lib,
                        onRefresh = { scope.launch { lib.refresh() } }
                    )
                }

                if (query.isBlank()) {
                    item(key = "continue", contentType = "continue") {
                        ContinueRow(all, lib) { t -> target = t }
                    }
                }

                item(key = "filters_bar") {
                    FilterControlsBar(
                        selectedCategory = category,
                        onCategorySelect = { category = it },
                        viewMode = viewMode,
                        onViewModeSelect = { viewMode = it },
                        sortBy = sortBy,
                        onSortSelect = { sortBy = it },
                        sortOpen = sortOpen,
                        onSortOpenChange = { sortOpen = it },
                        onlyReady = onlyReady,
                        onToggleOnlyReady = { onlyReady = !onlyReady }
                    )
                }

                items(filtered, key = { it.m.id }, contentType = { "list_row" }) { item ->
                    ListRow(item, lib, onOpen = { target = it }, modifier = Modifier.animateItem(fadeInSpec = tween(300), fadeOutSpec = tween(200)))
                }
            }
        }

        target?.let { t -> PlayDialog(t, lib, onDismiss = { target = null }, onPlay = { target = null; onPlay(it) }) }
    }
}

@Composable
private fun LibraryHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    totalCount: Int,
    lib: Library,
    onRefresh: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("library.moya_biblioteka_alldebrid"), fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = Color.White)
            Text(totalCount.toString(), color = Sm.muted, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, top = 6.dp))
            Spacer(Modifier.weight(1f))
            Field(
                value = query,
                onValue = onQueryChange,
                placeholder = tr("library.poisk_po_nazvaniyu"),
                leadingIcon = { SearchIcon(size = 16.dp, color = Color(0xFF8E96A8)) },
                modifier = Modifier.width(280.dp)
            )
            Spacer(Modifier.width(10.dp))
            val src = remember { MutableInteractionSource() }
            val hovered by src.collectIsHoveredAsState()
            val turn by animateFloatAsState(if (lib.loading) 360f else 0f, tween(600, easing = Sm.ease))
            Box(
                Modifier.size(42.dp).glass(RoundedCornerShape(12.dp), hovered).hoverClick(src, onClick = onRefresh),
                contentAlignment = Alignment.Center
            ) { RefreshIcon(Modifier.graphicsLayer { rotationZ = turn }, color = if (lib.loading) Sm.muted else Color.White, size = 16.dp) }
        }
        // A line of its own only when something is wrong.
        if (lib.statusKind == StatusKind.Error) {
            Text(lib.status, color = Sm.bad, fontFamily = Onest, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
        }
    }
}

@Composable
private fun FilterControlsBar(
    selectedCategory: Category,
    onCategorySelect: (Category) -> Unit,
    viewMode: ViewMode,
    onViewModeSelect: (ViewMode) -> Unit,
    sortBy: SortBy,
    onSortSelect: (SortBy) -> Unit,
    sortOpen: Boolean,
    onSortOpenChange: (Boolean) -> Unit,
    onlyReady: Boolean,
    onToggleOnlyReady: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: Filter Chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (cat in Category.entries) {
                val active = cat == selectedCategory
                val src = remember { MutableInteractionSource() }
                val hovered by src.collectIsHoveredAsState()
                val bg by animateColorAsState(when { active -> Sm.accent.copy(alpha = 0.85f); hovered -> Color(0x14FFFFFF); else -> Color.Transparent }, tween(200))
                val edge by animateColorAsState(when { active -> Sm.accentEdge; hovered -> Color(0x26FFFFFF); else -> Sm.glassEdge }, tween(200))
                val fg by animateColorAsState(when { active -> Color.White; hovered -> Color(0xFFD6DBE4); else -> Color(0xFF8E96A8) }, tween(200))

                Box(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(bg).border(1.dp, edge, RoundedCornerShape(16.dp))
                        .hoverClick(src) { onCategorySelect(cat) }
                        .height(32.dp).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(cat.label, color = fg, fontFamily = Onest, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, fontSize = 13.sp)
                }
            }
        }

        // Right: one menu for view mode, sorting and the "ready only" filter.
        Box {
            PillButton(
                text = tr("library.vid"),
                icon = { FunnelIcon(size = 14.dp, color = if (onlyReady) Color.White else Color(0xFF8E96A8)) },
                active = onlyReady || sortOpen,
                onClick = { onSortOpenChange(true) }
            )
            DropdownMenu(
                expanded = sortOpen,
                onDismissRequest = { onSortOpenChange(false) },
                modifier = Modifier.background(Color(0xFF181C26)).border(1.dp, Color(0xFF2A3040), RoundedCornerShape(8.dp))
            ) {
                @Composable fun item(label: String, on: Boolean, action: () -> Unit) = DropdownMenuItem(
                    text = { Text((if (on) "✓  " else "     ") + label, color = if (on) Color.White else Sm.text, fontFamily = Onest, fontSize = 13.5.sp) },
                    onClick = action
                )
                item(tr("library.setka"), viewMode == ViewMode.Grid) { onViewModeSelect(ViewMode.Grid); onSortOpenChange(false) }
                item(tr("library.spisok"), viewMode == ViewMode.List) { onViewModeSelect(ViewMode.List); onSortOpenChange(false) }
                Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(Color(0xFF2A3040)))
                for (s in SortBy.entries) item(s.label, s == sortBy) { onSortSelect(s); onSortOpenChange(false) }
                Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(Color(0xFF2A3040)))
                item(tr("library.tolko_gotovye"), onlyReady) { onToggleOnlyReady(); onSortOpenChange(false) }
            }
        }
    }
}

/** The sleek poster tile matching the concept image. */
@Composable
private fun Tile(item: LibraryItem, lib: Library, onOpen: (PlayTarget) -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val m = item.m
    val playable = item.playable
    val series = playable as? Playable.Series
    var poster by remember(m.id) { mutableStateOf(lib.cachedPoster(item.posterQueries)) }
    LaunchedEffect(m.id) { if (poster == null) poster = lib.poster(item.posterQueries) }
    val wv = lib.watchVersion
    val last = remember(m.id, wv) { lib.lastWatched(m) }
    var si by remember(m.id) { mutableStateOf(0) }
    var ei by remember(m.id) { mutableStateOf(0) }
    LaunchedEffect(m.id, wv) {
        if (series != null) {
            val saved = last?.first?.path ?: lib.lastEpisode(m.id)
            series.seasons.forEachIndexed { s, season -> val e = season.items.indexOfFirst { it.file.path == saved }; if (e >= 0) { si = s; ei = e } }
        }
    }
    val current: MFile? = when (playable) {
        is Playable.Movie -> playable.file
        is Playable.Series -> (playable.seasons.getOrNull(si) ?: playable.seasons.first()).let { s -> (s.items.getOrNull(ei) ?: s.items.first()).file }
        Playable.None -> null
    }
    var media by remember(m.id, current?.path) { mutableStateOf(current?.let { lib.cachedMediaLine(m.id, it) }) }
    LaunchedEffect(m.id, current?.path) { if (media == null && current != null && m.ready) media = lib.mediaLine(m.id, current) }

    fun target(): PlayTarget? = when (playable) {
        is Playable.Movie -> PlayTarget(m.id, playable.file, item.baseTitle, emptyList(), subtitlesFor(m, playable.file), externalAudioFor(m, playable.file), item)
        is Playable.Series -> {
            val s = playable.seasons.getOrNull(si) ?: playable.seasons.first()
            val e = s.items.getOrNull(ei) ?: s.items.first()
            lib.saveEpisode(m.id, e.file.path)
            PlayTarget(m.id, e.file, episodeLabel(item.baseTitle, s, e), s.items.drop(s.items.indexOf(e)).map { it.file }, subtitlesFor(m, e.file), externalAudioFor(m, e.file), item) { f -> externalAudioFor(m, f) to subtitlesFor(m, f) }
        }
        Playable.None -> null
    }
    val canPlay = m.ready && playable !is Playable.None
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val lift by animateFloatAsState(if (hovered) -6f else 0f, tween(280, easing = Sm.ease))
    val cardScale by animateFloatAsState(if (hovered) 1.025f else 1f, tween(280, easing = Sm.ease))
    val zoom by animateFloatAsState(if (hovered) 1.06f else 1f, tween(360, easing = Sm.ease))
    val edge by animateColorAsState(if (hovered) Color(0xFF5A6680) else Color(0xFF202430), tween(240))

    var menuOpen by remember { mutableStateOf(false) }

    Box(
        modifier.graphicsLayer { translationY = lift; scaleX = cardScale; scaleY = cardScale }.hoverable(src)
            .fillMaxWidth().aspectRatio(2f / 3f)
            .onGloballyPositioned { TileBounds.poster[m.id] = it }
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF13161F))
            .border(1.dp, edge, RoundedCornerShape(14.dp))
            .clickable(enabled = canPlay) { TileBounds.fromContinue = false; target()?.let(onOpen) }
            .then(if (canPlay) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
    ) {
        // Poster Image
        val p = poster
        if (p != null) {
            Image(p, tr("library.poster"), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom })
        } else {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF222838), Color(0xFF10131B)))))
        }

        // Top Gradient & Three Dots Menu
        Box(Modifier.fillMaxWidth().height(60.dp).background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))))
        Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            val menuSrc = remember { MutableInteractionSource() }
            val menuHover by menuSrc.collectIsHoveredAsState()
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(if (menuHover) Color(0x99000000) else Color(0x55000000))
                    .hoverClick(menuSrc) { menuOpen = true },
                contentAlignment = Alignment.Center
            ) {
                ThreeDotsIcon(color = Color.White, size = 16.dp)
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.background(Color(0xFF181C26)).border(1.dp, Color(0xFF2A3040), RoundedCornerShape(8.dp))
            ) {
                DropdownMenuItem(
                    text = { Text(if (m.favorite) tr("library.iz_izbrannogo") else tr("library.v_izbrannoe"), color = Sm.text, fontFamily = Onest) },
                    onClick = { menuOpen = false; scope.launch { lib.setFavorite(m, !m.favorite) } }
                )
                if (series != null) {
                    series.seasons.forEachIndexed { sIdx, season ->
                        DropdownMenuItem(
                            text = { Text(tr("library.sezon_season_size_ser", "season" to season.season, "size" to season.items.size), color = Sm.text, fontFamily = Onest) },
                            onClick = { menuOpen = false; si = sIdx; ei = 0 }
                        )
                    }
                }
                if (m.canRemove) {
                    DropdownMenuItem(
                        text = { Text(if (m.downloading) tr("library.otmenit") else tr("library.udalit"), color = Sm.bad, fontFamily = Onest) },
                        onClick = { menuOpen = false; scope.launch { lib.remove(m) } }
                    )
                }
            }
        }

        // Bottom Rich Dark Gradient & Text Info (drawn below play button)
        Box(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC0B0E14), Color(0xFD0B0E14))))
                .padding(horizontal = 12.dp, vertical = 11.dp)
        ) {
            Column {
                Text(
                    item.baseTitle,
                    color = Color.White,
                    fontFamily = Onest,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp
                )
                Text(
                    "${itemYear(item)} • ${itemTypeLabel(item)}",
                    color = Color(0xFF9BA4B5),
                    fontFamily = Onest,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 5.dp)
                )
                // Status Badge
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF181B24))
                        .border(1.dp, Color(0xFF2A3040), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (m.ready) tr("library.gotovo") else if (m.downloading) "${(m.progress * 100).toInt()}% • ${statusRu(m)}" else statusRu(m),
                        color = Color.White,
                        fontFamily = Onest,
                        fontWeight = FontWeight.Medium,
                        fontSize = 10.5.sp
                    )
                }
                // Media Specs
                val specs = itemSpecs(item, media)
                Text(
                    specs.first,
                    color = Color(0xFFC0C7D5),
                    fontFamily = Onest,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 5.dp)
                )
                Text(
                    specs.second,
                    color = Color(0xFF7A8294),
                    fontFamily = Onest,
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }

        // Center Play Button (placed AFTER text overlay, bigger triangle, white glow on hover, no circle)
        if (canPlay) {
            val playAlpha by animateFloatAsState(if (hovered) 1f else 0f, tween(240, easing = Sm.ease))
            val playScale by animateFloatAsState(if (hovered) 1.05f else 0.72f, tween(260, easing = Sm.ease))
            if (playAlpha > 0.005f) {
                Box(
                    Modifier.align(Alignment.Center)
                        .graphicsLayer {
                            alpha = playAlpha
                            scaleX = playScale
                            scaleY = playScale
                        }
                        .drawBehind {
                            val r = 38.dp.toPx()
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colorStops = arrayOf(
                                        0.0f to Color.White.copy(alpha = playAlpha * 0.45f),
                                        0.45f to Color.White.copy(alpha = playAlpha * 0.22f),
                                        0.80f to Color.White.copy(alpha = playAlpha * 0.06f),
                                        1.0f to Color.Transparent
                                    ),
                                    center = center,
                                    radius = r
                                ),
                                radius = r,
                                center = center
                            )
                        }
                        .size(56.dp),
                    contentAlignment = Alignment.Center
                ) {
                    PlayTriangle(size = 32.dp, color = Color.White)
                }
            }
        }

        // Bottom Watch Progress Bar
        val w = remember(m.id, current?.path, wv) { current?.let { lib.watchInfo(watchKey(m.id, it)) } }
        val cyanGrad = Brush.horizontalGradient(listOf(Color(0xFF0284C7), Color(0xFF38BDF8), Color(0xFF7DD3FC)))
        if (m.ready && w != null && w.fraction > 0.01f) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x55000000))) {
                Box(Modifier.fillMaxWidth(if (w.watched) 1f else w.fraction).fillMaxHeight().background(cyanGrad))
            }
        } else if (m.downloading && m.size > 0) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x55000000))) {
                Box(Modifier.fillMaxWidth(m.progress).fillMaxHeight().background(cyanGrad))
            }
        }
    }
}

/** Row for List View Mode. */
@Composable
private fun ListRow(item: LibraryItem, lib: Library, onOpen: (PlayTarget) -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val m = item.m
    val playable = item.playable
    var poster by remember(m.id) { mutableStateOf(lib.cachedPoster(item.posterQueries)) }
    LaunchedEffect(m.id) { if (poster == null) poster = lib.poster(item.posterQueries) }
    val current: MFile? = when (playable) {
        is Playable.Movie -> playable.file
        is Playable.Series -> playable.seasons.firstOrNull()?.items?.firstOrNull()?.file
        Playable.None -> null
    }
    var media by remember(m.id, current?.path) { mutableStateOf(current?.let { lib.cachedMediaLine(m.id, it) }) }
    LaunchedEffect(m.id, current?.path) { if (media == null && current != null && m.ready) media = lib.mediaLine(m.id, current) }

    fun target(): PlayTarget? = when (playable) {
        is Playable.Movie -> PlayTarget(m.id, playable.file, item.baseTitle, emptyList(), subtitlesFor(m, playable.file), externalAudioFor(m, playable.file), item)
        is Playable.Series -> {
            val s = playable.seasons.first()
            val e = s.items.first()
            PlayTarget(m.id, e.file, episodeLabel(item.baseTitle, s, e), s.items.map { it.file }, subtitlesFor(m, e.file), externalAudioFor(m, e.file), item) { f -> externalAudioFor(m, f) to subtitlesFor(m, f) }
        }
        Playable.None -> null
    }

    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val edge by animateColorAsState(if (hovered) Color(0xFF40485C) else Color(0xFF242A38), tween(200))
    val bg by animateColorAsState(if (hovered) Color(0xFF181B26) else Color(0xFF141720), tween(200))

    Row(
        modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(12.dp)).background(bg).border(1.dp, edge, RoundedCornerShape(12.dp))
            .hoverClick(src) { target()?.let(onOpen) }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail
        Box(
            Modifier.width(52.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Color(0xFF222735)),
            contentAlignment = Alignment.Center
        ) {
            val p = poster
            if (p != null) Image(p, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text("▶", color = Color(0xFF8E96A8), fontSize = 16.sp)
        }

        Spacer(Modifier.width(16.dp))

        // Title and Info
        Column(Modifier.weight(1f)) {
            Text(item.baseTitle, color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 16.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${itemYear(item)} • ${itemTypeLabel(item)}", color = Color(0xFF8E96A8), fontFamily = Onest, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
            val specs = itemSpecs(item, media)
            Text(specs.first, color = Color(0xFFC0C7D5), fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 3.dp))
            Text(specs.second, color = Color(0xFF7A8294), fontFamily = Onest, fontSize = 11.5.sp, modifier = Modifier.padding(top = 1.dp))
        }

        Spacer(Modifier.width(16.dp))

        // Status Badge
        Box(
            Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF181B24)).border(1.dp, Color(0xFF2A3040), RoundedCornerShape(6.dp))
                .padding(horizontal = 9.dp, vertical = 4.dp)
        ) {
            Text(if (m.ready) tr("library.gotovo") else statusRu(m), color = Color.White, fontFamily = Onest, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.width(16.dp))

        // Favorite Button
        val favSrc = remember { MutableInteractionSource() }
        val favHovered by favSrc.collectIsHoveredAsState()
        val favGlowAlpha by animateFloatAsState(if (favHovered) 0.45f else 0f, tween(180))
        val favScale by animateFloatAsState(if (favHovered) 1.12f else 1f, tween(180, easing = Sm.ease))
        Box(
            Modifier.size(38.dp)
                .graphicsLayer { scaleX = favScale; scaleY = favScale }
                .drawBehind {
                    if (favGlowAlpha > 0.001f) {
                        val r = 17.dp.toPx()
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.White.copy(alpha = favGlowAlpha),
                                    0.45f to Color.White.copy(alpha = favGlowAlpha * 0.45f),
                                    0.80f to Color.White.copy(alpha = favGlowAlpha * 0.12f),
                                    1.0f to Color.Transparent
                                ),
                                center = center,
                                radius = r
                            ),
                            radius = r,
                            center = center
                        )
                    }
                }
                .hoverClick(favSrc) { scope.launch { lib.setFavorite(m, !m.favorite) } },
            contentAlignment = Alignment.Center
        ) {
            Text(if (m.favorite) "★" else "☆", color = if (m.favorite || favHovered) Color.White else Color(0xFF8E96A8), fontSize = 21.sp)
        }

        Spacer(Modifier.width(12.dp))

        // Play Button (no circle, larger triangle, white glow on hover, matching size)
        val playSrc = remember { MutableInteractionSource() }
        val playHovered by playSrc.collectIsHoveredAsState()
        val playGlowAlpha by animateFloatAsState(if (playHovered) 0.45f else 0f, tween(180))
        val playBtnScale by animateFloatAsState(if (playHovered) 1.12f else 1f, tween(180, easing = Sm.ease))
        Box(
            Modifier.size(38.dp)
                .graphicsLayer { scaleX = playBtnScale; scaleY = playBtnScale }
                .drawBehind {
                    if (playGlowAlpha > 0.001f) {
                        val r = 17.dp.toPx()
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.White.copy(alpha = playGlowAlpha),
                                    0.45f to Color.White.copy(alpha = playGlowAlpha * 0.45f),
                                    0.80f to Color.White.copy(alpha = playGlowAlpha * 0.12f),
                                    1.0f to Color.Transparent
                                ),
                                center = center,
                                radius = r
                            ),
                            radius = r,
                            center = center
                        )
                    }
                }
                .hoverClick(playSrc) { target()?.let(onOpen) },
            contentAlignment = Alignment.Center
        ) {
            PlayTriangle(size = 20.dp, color = if (playHovered) Color.White else Color(0xFFC0C7D5))
        }
    }
}

// ---- «Продолжить просмотр» -------------------------------------------------------------------

private class ContinueEntry(
    val item: LibraryItem,
    val target: PlayTarget,
    val label: String,
    val fraction: Float,
    val pos: Long,
    val len: Long,
    val at: Long
)

private fun targetFor(item: LibraryItem, file: MFile): PlayTarget? {
    val m = item.m
    return when (val p = item.playable) {
        is Playable.Movie -> if (p.file.path == file.path) PlayTarget(m.id, p.file, item.baseTitle, emptyList(), subtitlesFor(m, p.file), externalAudioFor(m, p.file), item) else null
        is Playable.Series -> {
            for (s in p.seasons) {
                val i = s.items.indexOfFirst { it.file.path == file.path }
                if (i < 0) continue
                val e = s.items[i]
                return PlayTarget(m.id, e.file, episodeLabel(item.baseTitle, s, e), s.items.drop(i).map { it.file },
                    subtitlesFor(m, e.file), externalAudioFor(m, e.file), item) { f -> externalAudioFor(m, f) to subtitlesFor(m, f) }
            }
            null
        }
        Playable.None -> null
    }
}

private fun nextEpisode(p: Playable.Series, file: MFile): MFile? {
    val all = p.seasons.flatMap { it.items }
    val i = all.indexOfFirst { it.file.path == file.path }
    return if (i in 0 until all.size - 1) all[i + 1].file else null
}

private fun continueEntries(items: List<LibraryItem>, lib: Library): List<ContinueEntry> = items.mapNotNull { item ->
    if (!item.m.ready) return@mapNotNull null
    val (file, w) = lib.lastWatched(item.m) ?: return@mapNotNull null
    if (System.currentTimeMillis() - w.at > 60L * 24 * 3600 * 1000) return@mapNotNull null
    if (lib.continueHiddenAt(item.m.id) >= w.at) return@mapNotNull null
    if (!w.watched) {
        val t = targetFor(item, file) ?: return@mapNotNull null
        val sub = t.label.removePrefix(item.baseTitle).removePrefix(" · ").ifBlank { tr("library.film") }
        ContinueEntry(item, t, sub, w.fraction, w.pos, w.len, w.at)
    } else {
        val series = item.playable as? Playable.Series ?: return@mapNotNull null
        val next = nextEpisode(series, file) ?: return@mapNotNull null
        if (lib.watchInfo(watchKey(item.m.id, next))?.watched == true) return@mapNotNull null
        val t = targetFor(item, next) ?: return@mapNotNull null
        ContinueEntry(item, t, tr("library.dalshe") + t.label.removePrefix(item.baseTitle).removePrefix(" · "), 0f, 0L, 0L, w.at)
    }
}.sortedByDescending { it.at }.take(14)

@Composable
private fun ContinueRow(items: List<LibraryItem>, lib: Library, onOpen: (PlayTarget) -> Unit) {
    val scope = rememberCoroutineScope()
    val wv = lib.watchVersion
    val entries = remember(items, wv) { continueEntries(items, lib) }
    val rowState = rememberLazyListState()

    androidx.compose.animation.AnimatedVisibility(
        entries.isNotEmpty(),
        enter = fadeIn(tween(300)) + expandVertically(tween(320, easing = Sm.ease)),
        exit = fadeOut(tween(200)) + shrinkVertically(tween(260, easing = Sm.ease))
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("library.prodolzhit_prosmotr"),
                    fontFamily = Unbounded,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.5.sp,
                    color = Color.White,
                )
                Text(
                    entries.size.toString(), color = Color.White, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(10.dp)).background(Sm.accent).padding(horizontal = 8.dp, vertical = 2.dp)
                )
                Spacer(Modifier.weight(1f))

                // Clear the whole row (each card also has its own ×). Brings back anything watched again later.
                ClearAllButton { lib.hideContinue(entries.map { it.item.m.id }) }
                Spacer(Modifier.width(14.dp))

                // Carousel Chevrons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val prevSrc = remember { MutableInteractionSource() }
                    val prevHover by prevSrc.collectIsHoveredAsState()
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(if (prevHover) Color(0xFF282E3C) else Color(0xFF141720))
                            .border(1.dp, Color(0xFF242A38), CircleShape)
                            .hoverClick(prevSrc) { scope.launch { rowState.animateScrollBy(-560f) } },
                        contentAlignment = Alignment.Center
                    ) {
                        ChevronLeft(color = if (prevHover) Color.White else Color(0xFF8E96A8), size = 12.dp)
                    }

                    val nextSrc = remember { MutableInteractionSource() }
                    val nextHover by nextSrc.collectIsHoveredAsState()
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(if (nextHover) Color(0xFF282E3C) else Color(0xFF141720))
                            .border(1.dp, Color(0xFF242A38), CircleShape)
                            .hoverClick(nextSrc) { scope.launch { rowState.animateScrollBy(560f) } },
                        contentAlignment = Alignment.Center
                    ) {
                        ChevronRight(color = if (nextHover) Color.White else Color(0xFF8E96A8), size = 12.dp)
                    }
                }
            }

            LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(entries.size, key = { entries[it].item.m.id }) { i ->
                    val e = entries[i]
                    ContinueCard(
                        e, lib, Modifier.animateItem(fadeInSpec = tween(250), fadeOutSpec = tween(200)),
                        onRemove = { lib.hideContinue(listOf(e.item.m.id)) }
                    ) {
                        TileBounds.fromContinue = true
                        lib.saveEpisode(e.item.m.id, e.target.file.path)
                        onOpen(e.target)
                    }
                }
            }
        }
    }
}

@Composable
private fun ClearAllButton(onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val fg by animateColorAsState(if (hovered) Color.White else Sm.muted, tween(200))
    val bg by animateColorAsState(if (hovered) Color(0x1AFFFFFF) else Color.Transparent, tween(200))
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(bg).hoverClick(src, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        CloseCrossIcon(color = fg, size = 10.dp)
        Text(tr("library.ochistit"), color = fg, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 12.5.sp)
    }
}

@Composable
private fun ContinueCard(e: ContinueEntry, lib: Library, modifier: Modifier, onRemove: () -> Unit, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val lift by animateFloatAsState(if (hovered) -3f else 0f, tween(260, easing = Sm.ease))
    val edge by animateColorAsState(if (hovered) Sm.glassEdgeHover else Sm.glassEdge, tween(220))
    val bg by animateColorAsState(if (hovered) Sm.glassFillHover else Sm.glassFill, tween(220))
    var poster by remember(e.item.m.id) { mutableStateOf(lib.cachedPoster(e.item.posterQueries)) }
    LaunchedEffect(e.item.m.id) { if (poster == null) poster = lib.poster(e.item.posterQueries) }
    val xSrc = remember { MutableInteractionSource() }
    val xHover by xSrc.collectIsHoveredAsState()
    val xAlpha by animateFloatAsState(if (hovered || xHover) 1f else 0f, tween(200))

    Box(modifier.graphicsLayer { translationY = lift }.hoverable(src)) {
        Row(
            Modifier.width(304.dp).height(108.dp).clip(RoundedCornerShape(12.dp)).background(bg)
                .border(1.dp, edge, RoundedCornerShape(12.dp)).hoverClick(src, onClick = onClick)
                .padding(11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left Poster Thumbnail
            Box(
                Modifier.width(62.dp).fillMaxHeight()
                    .onGloballyPositioned { TileBounds.cont[e.item.m.id] = it }
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF222736))
            ) {
                poster?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }

            Spacer(Modifier.width(13.dp))

            // Right Text and Progress
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        e.item.baseTitle,
                        color = Color.White,
                        fontFamily = Onest,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        e.label,
                        color = Color(0xFF8E96A8),
                        fontFamily = Onest,
                        fontSize = 13.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Column {
                    val timeText = if (e.pos > 0 && e.len > 0) "${clock(e.pos)} / ${clock(e.len)}" else ""
                    if (timeText.isNotEmpty()) {
                        Text(timeText, color = Color(0xFF8E96A8), fontFamily = Onest, fontSize = 13.5.sp, modifier = Modifier.padding(bottom = 5.dp))
                    }
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF202636))) {
                        Box(
                            Modifier.fillMaxWidth(e.fraction.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(Brush.horizontalGradient(listOf(Color(0xFF0284C7), Color(0xFF38BDF8), Color(0xFF7DD3FC))))
                        )
                    }
                }
            }
        }

        // × close button in corner on hover (bigger cross, aligned in corner, white glow on hover, no circle)
        val xGlowAlpha by animateFloatAsState(if (xHover) 0.50f else 0f, tween(180))
        val xCrossScale by animateFloatAsState(if (xHover) 1.15f else 1f, tween(180, easing = Sm.ease))
        Box(
            Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 8.dp)
                .graphicsLayer { alpha = xAlpha; scaleX = xCrossScale; scaleY = xCrossScale }
                .size(26.dp)
                .drawBehind {
                    if (xGlowAlpha > 0.001f) {
                        val r = 14.dp.toPx()
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.White.copy(alpha = xGlowAlpha),
                                    0.45f to Color.White.copy(alpha = xGlowAlpha * 0.45f),
                                    0.80f to Color.White.copy(alpha = xGlowAlpha * 0.12f),
                                    1.0f to Color.Transparent
                                ),
                                center = center,
                                radius = r
                            ),
                            radius = r,
                            center = center
                        )
                    }
                }
                .hoverClick(xSrc, enabled = xAlpha > 0.05f, onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Text("×", color = Color.White, fontFamily = Onest, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
    }
}
