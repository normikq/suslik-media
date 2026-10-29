package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*
import ru.suslikovanora.media.desktop.player.*
import ru.suslikovanora.media.desktop.ui.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.runtime.key
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isSecondaryPressed
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
    Cartoons(tr("library.multfilmy")),
    Other(tr("library.drugoe"))
}

/** The category the library page shows; the side panel's «Коллекция» sets it. */
object LibraryNav {
    var category by androidx.compose.runtime.mutableStateOf(Category.All)
    var mineCategory by androidx.compose.runtime.mutableStateOf(Category.All)
    fun of(mine: Boolean) = if (mine) mineCategory else category
    fun set(mine: Boolean, c: Category) { if (mine) mineCategory = c else category = c }
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

// The same rule the site uses to tell cartoons from films (website/classify.js).
private val CARTOON = Regex("""мульт(?!сериал)|анимаци|\bcartoons?\b|\banimation\b|\bPixar\b|\bDreamWorks\b|\bDisney\b""", RegexOption.IGNORE_CASE)

fun isCartoon(item: LibraryItem): Boolean = CARTOON.containsMatchIn(item.m.name) || CARTOON.containsMatchIn(item.baseTitle) || Animation.of(item)

fun itemCategory(item: LibraryItem): Category {
    if (isAnime(item)) return Category.Anime
    if (isCartoon(item)) return Category.Cartoons
    if (isCollection(item.playable, item.m)) return Category.Movies
    return when (item.playable) {
        is Playable.Series -> Category.Series
        is Playable.Movie -> Category.Movies
        else -> Category.Other
    }
}

fun itemTypeLabel(item: LibraryItem): String {
    val films = collectionFilms(item)
    if (films.isNotEmpty()) return tr(collectionKind(films.size, item.m.name).trKey)
    return when (itemCategory(item)) {
        Category.Anime -> tr("library.anime")
        Category.Cartoons -> tr("library.multfilm")
        Category.Series -> tr("library.serial")
        Category.Movies -> tr("library.film")
        Category.Other -> tr("library.drugoe")
        Category.All -> tr("library.video")
    }
}

private val TAG_DV = Regex("""\b(dolby[ ._-]?vision|dovi|dv)\b""", RegexOption.IGNORE_CASE)
private val TAG_HDR = Regex("""\bhdr(10)?(\+|plus)?\b""", RegexOption.IGNORE_CASE)
private val TAG_DTS = Regex("""\bdts(?:[ ._:-]?(hd|x))?(?:[ ._-]?(ma))?\b""", RegexOption.IGNORE_CASE)

/** Marks of the release for the poster: «Dolby Vision», «HDR», «DTS» (by the names of the torrent and its files). */
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

/** One mark for the picture, one for the sound: «Dolby Vision» (or «HDR» when there is none) and «DTS» — not every variant of every version. */
fun collapseTags(all: List<String>): List<String> = buildList {
    if (all.any { it.startsWith("Dolby") }) add("Dolby Vision") else if (all.any { it == "HDR" }) add("HDR")
    if (all.any { it.startsWith("DTS") }) add("DTS")
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

    LaunchedEffect(lib.pairing) {
        val p = lib.pairing
        if (p != null) {
            while (lib.pairing != null) {
                kotlinx.coroutines.delay(2000)
                val res = lib.pollDeviceAuth()
                if (res == true) break
                if (res == false) {
                    error = tr("login.vremya_ozhidaniya_isteklo")
                    break
                }
            }
        }
    }
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
                if (lib.pairing != null) {
                    val pairing = lib.pairing!!
                    Text(tr("login.vhod_cherez_discord"), fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 20.sp, color = Sm.text)
                    Text(tr("login.podtverdite_v_brauzere"), color = Sm.muted, fontFamily = Onest, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1E2330))
                            .border(1.dp, Sm.accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(pairing.userCode, color = Color.White, fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 32.sp, letterSpacing = 4.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LampButton(tr("login.otkryt_v_brauzere"), modifier = Modifier.weight(1f)) {
                            runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(pairing.url)) }
                        }
                        OutlineButton(tr("login.otmena")) { lib.cancelDeviceAuth() }
                    }
                } else {
                    Text(tr("library.voyti"), fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 20.sp, color = Sm.text)
                    Spacer(Modifier.height(12.dp))
                    LampButton(tr("login.voyti_cherez_discord"), enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        if (busy) return@LampButton
                        busy = true; error = null // прежняя ошибка (например, истёкший код) к новому коду не относится
                        scope.launch {
                            val err = lib.startDeviceAuth()
                            busy = false
                            if (err != null) error = err
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).height(1.dp).background(Color(0x1AFFFFFF)))
                        Text(tr("login.ili_po_parolyu"), color = Sm.muted, fontFamily = Onest, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp))
                        Box(Modifier.weight(1f).height(1.dp).background(Color(0x1AFFFFFF)))
                    }
                    Spacer(Modifier.height(14.dp))
                    Field(pass, { pass = it }, tr("library.parol_dostupa"), password = true, onDone = { submit() })
                    Spacer(Modifier.height(12.dp))
                    OutlineButton(if (busy) tr("library.vypolnyaetsya_vhod") else tr("library.voyti"), modifier = Modifier.fillMaxWidth()) { submit() }
                }
                error?.let { Text(it, color = Sm.bad, fontFamily = Onest, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)) }
            }
        }
    }
}

@Composable
fun LibraryView(
    lib: Library,
    grid: androidx.compose.foundation.lazy.grid.LazyGridState = rememberLazyGridState(),
    mineOnly: Boolean = false,
    onPlay: (PlayRequest) -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<PlayTarget?>(null) }
    var pick by remember { mutableStateOf<List<LibraryItem>?>(null) } // versions of one film to choose from
    // The category is chosen in the side panel («Коллекция»); each page («Главная», «Моя медиатека») remembers its own.
    val category = LibraryNav.of(mineOnly)
    val setCategory: (Category) -> Unit = { LibraryNav.set(mineOnly, it) }
    var viewMode by remember { mutableStateOf(ViewMode.Grid) }
    var sortBy by remember { mutableStateOf(SortBy.Name) }
    var onlyReady by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }

    val all = lib.items
    val source = if (mineOnly) all.filter { it.m.mine } else all
    val filtered = remember(source, query, category, sortBy, onlyReady, Animation.version, lib.titleVersion) {
        val q = query.trim().lowercase()
        // the Russian title from TMDB is searched too: «дюна» finds «Dune.2021.2160p»
        var list = if (q.isEmpty()) source else source.filter { it.searchText.contains(q) || lib.titleOf(it).lowercase().contains(q) }

        if (onlyReady) {
            list = list.filter { it.m.ready }
        }

        if (category != Category.All) {
            list = list.filter { itemCategory(it) == category }
        }

        when (sortBy) {
            SortBy.Name -> list.sortedWith(compareByDescending<LibraryItem> { it.m.favorite }.thenBy { lib.titleOf(it).lowercase() })
            SortBy.Newest -> list.sortedWith(compareByDescending<LibraryItem> { it.m.favorite }.thenByDescending { it.m.id })
            SortBy.Size -> list.sortedWith(compareByDescending<LibraryItem> { it.m.favorite }.thenByDescending { it.m.size })
            SortBy.Favorite -> list.sortedByDescending { it.m.favorite }
        }
    }

    // One tile for the same film added several times; a tile with versions asks which one to play.
    val (shown, versionsOf) = remember(filtered, lib.posterVersion) { groupVersions(filtered) { lib.posterPathOf(it) } }
    // A page is 3 rows of posters: as many in a row as the window's width fits (the grid's own rule: 180 dp posters, 14 dp apart,
    // 36 dp margins). A new search, category, sorting or filter starts again from the first page.
    var areaWidthPx by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val columns = if (areaWidthPx <= 0) 6 else maxOf(1, ((areaWidthPx / density - 72f + 14f) / (180f + 14f)).toInt())
    val pageSize = if (viewMode == ViewMode.Grid) columns * PAGE_ROWS else PAGE_ROWS * 4
    val pageCount = ((shown.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    var page by remember(query, category, sortBy, onlyReady, mineOnly) { mutableStateOf(0) }
    val curPage = page.coerceIn(0, pageCount - 1)
    val pageItems = remember(shown, curPage, pageSize) { shown.drop(curPage * pageSize).take(pageSize) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val goPage: (Int) -> Unit = { page = it; scope.launch { grid.scrollToItem(0); listState.scrollToItem(0) } }
    val open: (PlayTarget) -> Unit = { t ->
        val vs = t.item?.let { versionsOf[it.m.id] }
        if (vs != null) pick = vs else target = t
    }

    Box(Modifier.fillMaxSize().onSizeChanged { areaWidthPx = it.width }) {
        if (viewMode == ViewMode.Grid) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(180.dp), state = grid,
                modifier = Modifier.onGloballyPositioned { TileBounds.viewport = it },
                contentPadding = PaddingValues(start = 36.dp, end = 36.dp, top = 28.dp, bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. Header (Title, Subtitle, Refresh button, Search bar, Count)
                item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                    LibraryHeader(
                        query = query,
                        onQueryChange = { query = it },
                        totalCount = shown.size,
                        title = if (mineOnly) tr("library.moi_filmy") else tr("library.moya_biblioteka_alldebrid"),
                        lib = lib,
                        onRefresh = { scope.launch { lib.refresh() } }
                    )
                }

                // 2. Continue watching section
                if (query.isBlank() && category == Category.All && !mineOnly) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "continue", contentType = "continue") {
                        ContinueRow(all, lib, atTop = { grid.firstVisibleItemIndex == 0 && grid.firstVisibleItemScrollOffset == 0 }) { t -> target = t }
                    }
                }

                // 3. Category filter chips and View/Sort Controls
                item(span = { GridItemSpan(maxLineSpan) }, key = "filters_bar") {
                    FilterControlsBar(
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

                if (mineOnly && filtered.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty") { EmptyMine() }
                }
                // 4. Catalog grid of poster cards
                items(pageItems, key = { it.m.id }, contentType = { "tile" }) { item ->
                    Tile(item, lib, versions = versionsOf[item.m.id].orEmpty(), inMine = mineOnly, onOpen = open, modifier = if (Motion.simple) Modifier else Modifier.animateItem(fadeInSpec = tween(300), fadeOutSpec = tween(320, easing = Sm.ease)))
                }
                if (pageCount > 1) item(span = { GridItemSpan(maxLineSpan) }, key = "pager", contentType = "pager") { PageBar(curPage, pageCount, goPage) }
            }
        } else {
            // List View Mode
            LazyColumn(
                state = listState,
                modifier = Modifier.onGloballyPositioned { TileBounds.viewport = it },
                contentPadding = PaddingValues(start = 36.dp, end = 36.dp, top = 28.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "header") {
                    LibraryHeader(
                        query = query,
                        onQueryChange = { query = it },
                        totalCount = shown.size,
                        title = if (mineOnly) tr("library.moi_filmy") else tr("library.moya_biblioteka_alldebrid"),
                        lib = lib,
                        onRefresh = { scope.launch { lib.refresh() } }
                    )
                }

                if (query.isBlank() && category == Category.All && !mineOnly) {
                    item(key = "continue", contentType = "continue") {
                        ContinueRow(all, lib, atTop = { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }) { t -> target = t }
                    }
                }

                item(key = "filters_bar") {
                    FilterControlsBar(
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

                if (mineOnly && filtered.isEmpty()) {
                    item(key = "empty") { EmptyMine() }
                }
                items(pageItems, key = { it.m.id }, contentType = { "list_row" }) { item ->
                    ListRow(item, lib, onOpen = open, modifier = if (Motion.simple) Modifier else Modifier.animateItem(fadeInSpec = tween(300), fadeOutSpec = tween(200)))
                }
                if (pageCount > 1) item(key = "pager", contentType = "pager") { PageBar(curPage, pageCount, goPage) }
            }
        }

        pick?.let { vs ->
            VersionPicker(vs.first().baseTitle, vs, lib, onDismiss = { pick = null }) { v -> pick = null; versionTarget(v, lib)?.let { target = it } }
        }
        target?.let { t -> PlayDialog(t, lib, onDismiss = { target = null }, onPlay = { target = null; onPlay(it) }) }
        lib.aiPanel?.let { p -> AiTitleDialog(p, lib, onDismiss = { lib.aiPanel = null }) }
    }
}

/** A page of the library is this many rows of posters (the rest are on the next pages). */
private const val PAGE_ROWS = 3

/** «‹ 1 2 3 … 9 ›» under the posters. */
@Composable
private fun PageBar(page: Int, count: Int, onPage: (Int) -> Unit) {
    // the numbers around the current page, the first and the last; «…» (-1) in the gaps
    val nums = (0 until count).filter { it == 0 || it == count - 1 || kotlin.math.abs(it - page) <= 2 }
        .let { list -> buildList { list.forEachIndexed { i, n -> if (i > 0 && n - list[i - 1] > 1) add(-1); add(n) } } }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        @Composable fun cell(label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
            val src = remember { MutableInteractionSource() }
            val hovered by src.collectIsHoveredAsState()
            val bg by animateColorAsState(when { active -> Sm.accent; hovered && enabled -> Color(0x26FFFFFF); else -> Color(0x12FFFFFF) }, tween(160))
            Box(
                Modifier.padding(horizontal = 3.dp).height(36.dp).widthIn(min = 36.dp).clip(RoundedCornerShape(10.dp)).background(bg)
                    .border(1.dp, if (active) Sm.accentEdge else Sm.glassEdge, RoundedCornerShape(10.dp))
                    .let { if (enabled && !active) it.hoverClick(src, onClick = onClick) else it.hoverable(src) }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (!enabled) Color(0xFF5B6272) else Color.White, fontFamily = Onest, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp)
            }
        }
        cell("‹", enabled = page > 0) { onPage(page - 1) }
        for (n in nums) if (n < 0) Text("…", color = Sm.muted, fontFamily = Onest, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp))
            else cell((n + 1).toString(), active = n == page) { onPage(n) }
        cell("›", enabled = page < count - 1) { onPage(page + 1) }
    }
}

/** Spins while the library loads (a full turn at a time, then settles with an ease), tilts and glows on hover, sinks on press. */
@Composable
private fun RefreshButton(loading: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val rot = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(loading) {
        if (loading) {
            while (true) rot.animateTo(rot.value + 360f, tween(900, easing = androidx.compose.animation.core.LinearEasing))
        } else if (rot.value != 0f) {
            val end = kotlin.math.ceil(rot.value / 360f) * 360f
            rot.animateTo(end, tween(520, easing = Sm.ease))
            rot.snapTo(0f)
        }
    }
    val tilt by animateFloatAsState(if (hovered && !loading) 90f else 0f, tween(380, easing = Sm.ease))
    val scale = hoverScale(hovered, pressed, 1.06f)
    val glow by animateFloatAsState(if (loading) 1f else if (hovered) 0.6f else 0.25f, tween(300))
    val bg by animateColorAsState(
        when {
            pressed -> Color(0x3860A5FA)
            hovered -> Color(0x2E60A5FA)
            loading -> Color(0x2460A5FA)
            else -> Color(0x1460A5FA)
        },
        tween(200)
    )
    val border by animateColorAsState(
        when {
            hovered || loading -> Color(0x8060A5FA)
            else -> Color(0x4060A5FA)
        },
        tween(200)
    )
    val iconColor by animateColorAsState(
        when {
            loading -> Color(0xFF60A5FA)
            hovered -> Color(0xFF93C5FD)
            else -> Color(0xFF60A5FA)
        },
        tween(200)
    )
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.size(42.dp)
            .drawBehind {
                if (glow > 0.01f) {
                    drawCircle(
                        Brush.radialGradient(listOf(Color(0xFF60A5FA).copy(alpha = 0.35f * glow), Color.Transparent)),
                        size.maxDimension * 0.85f
                    )
                }
            }
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .hoverClick(src, enabled = !loading, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        RefreshIcon(Modifier.graphicsLayer { rotationZ = rot.value + tilt }, color = iconColor, size = 16.dp)
    }
}

@Composable
private fun LibraryHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    totalCount: Int,
    title: String,
    lib: Library,
    onRefresh: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, color = Color.White)
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
            RefreshButton(loading = lib.loading, onClick = onRefresh)
        }
        // A line of its own only when something is wrong.
        if (lib.statusKind == StatusKind.Error) {
            Text(lib.status, color = Sm.bad, fontFamily = Onest, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
        }
    }
}

@Composable
private fun FilterControlsBar(
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
        // (Categories are chosen in the side panel.)
        Spacer(Modifier.width(1.dp))

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

@Composable
private fun EmptyMine() {
    Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BookmarkIcon(filled = false, color = Sm.muted, size = 40.dp)
        Text(tr("library.izbrannoe_pusto"), color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 14.dp))
        Text(tr("library.izbrannoe_pusto_podskazka"), color = Sm.muted, fontFamily = Onest, fontSize = 13.5.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** A round button in the poster's corner: always shown when [on], otherwise only while the tile is hovered. */
@Composable
private fun CornerButton(on: Boolean, tileHovered: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hover by src.collectIsHoveredAsState()
    val alpha by animateFloatAsState(if (on || tileHovered) 1f else 0f, tween(200))
    val scale by animateFloatAsState(if (hover) 1.12f else 1f, tween(180, easing = Sm.ease))
    Box(
        Modifier.graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale }
            .size(30.dp).clip(CircleShape).background(if (hover) Color(0x99000000) else Color(0x55000000))
            .hoverClick(src, enabled = alpha > 0.05f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { icon() }
}

/** The sleek poster tile matching the concept image. */
@Composable
private fun Tile(item: LibraryItem, lib: Library, versions: List<LibraryItem>, inMine: Boolean, onOpen: (PlayTarget) -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val m = item.m
    val playable = item.playable
    val series = playable as? Playable.Series
    // по названию: после «Принять» название от ИИ ищет свой постер
    var poster by remember(m.id, item.posterQueries) { mutableStateOf(lib.cachedPoster(item.posterQueries)) }
    LaunchedEffect(m.id, item.posterQueries) { if (poster == null) poster = lib.poster(item.posterQueries) }
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
            .trackBounds(TileBounds.poster, m.id)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF13161F))
            .border(1.dp, edge, RoundedCornerShape(14.dp))
            .clickable(enabled = canPlay) { TileBounds.fromContinue = false; target()?.let(onOpen) }
            .then(if (canPlay) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .pointerInput(m.id) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Press && e.buttons.isSecondaryPressed) menuOpen = true
                    }
                }
            }
    ) {
        // Poster Image
        val p = poster
        if (p != null) {
            Image(p, tr("library.poster"), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom })
        } else {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF222838), Color(0xFF10131B)))))
        }

        // Top gradient under the corner buttons
        Box(Modifier.fillMaxWidth().height(60.dp).background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))))
        // Corner buttons: «Мои фильмы» (bookmark) and favourite (heart, shown first in the lists). Always visible when set,
        // appear on hover otherwise.
        val isMine = m.mine || versions.any { it.m.mine }
        val isFav = m.favorite || versions.any { it.m.favorite }
        val group = versions.ifEmpty { listOf(item) }
        Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CornerButton(isMine, hovered, onClick = {
                // Added: the poster flies into «Мои фильмы»; taken out there: back into «Библиотека».
                val to = if (!isMine) Tab.Mine else if (inMine) Tab.Library else null
                if (to != null) TileBounds.posterRect(m.id)?.let { FlyHub.launch(poster, it, to) }
                scope.launch { group.forEach { lib.setMine(it.m, !isMine) } }
            }) { BookmarkIcon(filled = isMine, color = if (isMine) Color(0xFF7FB2FF) else Color.White, size = 15.dp) }
            CornerButton(isFav, hovered, onClick = { scope.launch { group.forEach { lib.setFavorite(it.m, !isFav) } } }) {
                HeartIcon(filled = isFav, color = if (isFav) Color(0xFFFF6B8A) else Color.White, size = 16.dp)
            }
        }
        val tags = collapseTags(versions.ifEmpty { listOf(item) }.flatMap { lib.tagsOf(it) })
        if (versions.size > 1 || tags.isNotEmpty()) {
            Column(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (versions.size > 1) Text(
                    versionsWord(versions.size), color = Color.White, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x99000000))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 4.dp)
                )
                for (t in tags) {
                    val c = when { t.startsWith("Dolby") -> Color(0xFFB794F6); t == "HDR" -> Color(0xFFFFC857); else -> Color(0xFF7DD3FC) }
                    // a fixed-height box with the text centred in it (the font's own line padding made the text sit high)
                    Box(
                        Modifier.height(22.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xCC0E0B1A))
                            .border(1.dp, c.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).padding(horizontal = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            t, color = c, fontFamily = Onest, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, softWrap = false,
                            style = androidx.compose.ui.text.TextStyle(
                                lineHeight = 12.sp,
                                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                                    androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.Both
                                )
                            )
                        )
                    }
                }
            }
        }
        // Right click: seasons of a series and delete / cancel (the corner button with dots is gone).
        Box(Modifier.align(Alignment.Center)) {
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.background(Color(0xFF181C26)).border(1.dp, Color(0xFF2A3040), RoundedCornerShape(8.dp))
            ) {
                if (series != null) {
                    series.seasons.forEachIndexed { sIdx, season ->
                        DropdownMenuItem(
                            text = { Text(tr("library.sezon_season_size_ser", "season" to season.season, "size" to season.items.size), color = Sm.text, fontFamily = Onest) },
                            onClick = { menuOpen = false; si = sIdx; ei = 0 }
                        )
                    }
                }
                DropdownMenuItem(
                    text = { Text(if (isMine) tr("library.iz_moih_filmov") else tr("library.v_moi_filmy"), color = Sm.text, fontFamily = Onest) },
                    onClick = { menuOpen = false; scope.launch { group.forEach { lib.setMine(it.m, !isMine) } } }
                )
                DropdownMenuItem(
                    text = { Text(if (isFav) tr("library.iz_izbrannogo") else tr("library.v_izbrannoe"), color = Sm.text, fontFamily = Onest) },
                    onClick = { menuOpen = false; scope.launch { group.forEach { lib.setFavorite(it.m, !isFav) } } }
                )
                // «Определить через ИИ» (server services/aititle.js): все свои раздачи плитки (версии, сезоны) одним запросом;
                // ответы — в окне, там же принять или отклонить
                // stale — ответ до появления типа раздачи: можно спросить ещё раз
                val aiAsk = group.map { it.m }.filter { it.canAiTitle && (it.aiTitle == null || it.aiTitle?.stale == true) }
                val aiKnown = group.map { it.m }.filter { it.aiTitle != null && (it.canAiTitle || it.aiTitle?.status == "suggested" || it.aiTitle?.accepted == true) }
                if (aiAsk.isNotEmpty()) {
                    val left = lib.aiReadyAt - System.currentTimeMillis()
                    val label = when {
                        left > 0 -> tr("library.ai_cherez", "min" to (left + 59_999) / 60_000)
                        aiAsk.size > 1 -> tr("library.ai_opredelit_n", "n" to aiAsk.size)
                        else -> tr("library.ai_opredelit")
                    }
                    DropdownMenuItem(
                        text = { Text(label, color = if (left > 0 || lib.aiBusy) Sm.muted else Sm.text, fontFamily = Onest) },
                        enabled = left <= 0 && !lib.aiBusy,
                        onClick = { menuOpen = false; scope.launch { lib.askAi(aiAsk) } }
                    )
                }
                if (aiKnown.isNotEmpty()) {
                    val waiting = aiKnown.count { it.canAiTitle && it.aiTitle?.status == "suggested" }
                    DropdownMenuItem(
                        text = { Text(if (waiting > 0) tr("library.ai_predlozheniya_n", "n" to waiting) else tr("library.ai_nazvanie"), color = Sm.text, fontFamily = Onest) },
                        onClick = { menuOpen = false; lib.aiPanel = Library.AiPanel(aiKnown.map { it.id }) }
                    )
                }
                // «Удалить» стирает раздачу из AllDebrid у всех: в «Моих фильмах» его нет, там только «Убрать из моих фильмов»
                // (отменить свою незаконченную загрузку можно и там)
                if (m.canRemove && (!inMine || m.downloading)) {
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
                    lib.titleOf(item),
                    color = Color.White,
                    fontFamily = Onest,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp
                )
                // Details only on hover (and always for what is not ready yet: its progress matters).
                androidx.compose.animation.AnimatedVisibility(
                    visible = hovered || !m.ready,
                    enter = fadeIn(tween(200)) + expandVertically(tween(240, easing = Sm.ease)),
                    exit = fadeOut(tween(140)) + shrinkVertically(tween(200, easing = Sm.ease))
                ) {
                    Column {
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
    // по названию: после «Принять» название от ИИ ищет свой постер
    var poster by remember(m.id, item.posterQueries) { mutableStateOf(lib.cachedPoster(item.posterQueries)) }
    LaunchedEffect(m.id, item.posterQueries) { if (poster == null) poster = lib.poster(item.posterQueries) }
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
            Text(lib.titleOf(item), color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 16.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

        // «Мои фильмы»
        val mineSrc = remember { MutableInteractionSource() }
        val mineHovered by mineSrc.collectIsHoveredAsState()
        val mineScale by animateFloatAsState(if (mineHovered) 1.12f else 1f, tween(180, easing = Sm.ease))
        Box(
            Modifier.size(38.dp).graphicsLayer { scaleX = mineScale; scaleY = mineScale }
                .hoverClick(mineSrc) { scope.launch { lib.setMine(m, !m.mine) } },
            contentAlignment = Alignment.Center
        ) {
            BookmarkIcon(filled = m.mine, color = if (m.mine) Color(0xFF7FB2FF) else if (mineHovered) Color.White else Color(0xFF8E96A8), size = 18.dp)
        }

        Spacer(Modifier.width(4.dp))

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
    val at: Long,
    val posterQueries: List<String>
)

private fun targetFor(item: LibraryItem, file: MFile): PlayTarget? = targetFor(item, file.path)

/** What to open for this file of the item (a movie, or an episode with the rest of its season). */
internal fun targetFor(item: LibraryItem, path: String): PlayTarget? {
    val m = item.m
    return when (val p = item.playable) {
        is Playable.Movie -> if (p.file.path == path) PlayTarget(m.id, p.file, item.baseTitle, emptyList(), subtitlesFor(m, p.file), externalAudioFor(m, p.file), item) else null
        is Playable.Series -> {
            for (s in p.seasons) {
                val i = s.items.indexOfFirst { it.file.path == path }
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

/** «Фильм 2 · Властелин колец: Две крепости» — which film of a collection the banner continues. */
private fun filmSub(item: LibraryItem, file: MFile): String {
    val films = collectionFilms(item)
    val idx = films.indexOfFirst { it.file.path == file.path }
    val title = collectionFileTitle(item.m, file, item.baseTitle)
    return if (films.size > 1 && idx >= 0) tr("library.film_p", "p" to (idx + 1)) + " · " + title else title
}

private fun continueEntries(items: List<LibraryItem>, lib: Library): List<ContinueEntry> = items.mapNotNull { item ->
    if (!item.m.ready) return@mapNotNull null
    val (file, w) = lib.lastWatched(item.m) ?: return@mapNotNull null
    if (System.currentTimeMillis() - w.at > 60L * 24 * 3600 * 1000) return@mapNotNull null
    if (lib.continueHiddenAt(item.m.id) >= w.at) return@mapNotNull null
    val isColl = isCollection(item.playable, item.m)
    if (!w.watched) {
        val t = targetFor(item, file) ?: return@mapNotNull null
        val queries = continueItemPosterQueries(item, file)
        val sub = if (isColl) filmSub(item, file) else {
            t.label.removePrefix(item.baseTitle).removePrefix(" · ").ifBlank { tr("library.film") }
        }
        ContinueEntry(item, t, sub, w.fraction, w.pos, w.len, w.at, queries)
    } else {
        val series = item.playable as? Playable.Series ?: return@mapNotNull null
        val next = nextEpisode(series, file) ?: return@mapNotNull null
        if (lib.watchInfo(watchKey(item.m.id, next))?.watched == true) return@mapNotNull null
        val t = targetFor(item, next) ?: return@mapNotNull null
        val queries = continueItemPosterQueries(item, next)
        val sub = if (isColl) tr("library.dalshe") + filmSub(item, next) else {
            tr("library.dalshe") + t.label.removePrefix(item.baseTitle).removePrefix(" · ")
        }
        ContinueEntry(item, t, sub, 0f, 0L, 0L, w.at, queries)
    }
}.sortedByDescending { it.at }.take(4)

/** At the start of the app: the banners' pictures and texts are loaded before the library page is opened. */
suspend fun warmContinue(items: List<LibraryItem>, lib: Library) {
    for (e in continueEntries(items, lib)) {
        val q = e.posterQueries
        runCatching { lib.hero(q, watchedSeason(e.item, e.target.file.path)?.season ?: 0); lib.poster(q) }
    }
}

private fun plural(n: Int, one: String, few: String, many: String): String {
    val t = n % 100; val u = n % 10
    return "$n " + if (t in 11..14) many else when (u) { 1 -> one; 2, 3, 4 -> few; else -> many }
}

/** «Сезон 2 • 10 серий»: the season the banner continues (in a merged show it is not always the first). */
private fun seasonChip(s: Season): String =
    (if (s.season == 0) tr("library.specvypuski") else tr("library.sezon_season", "season" to s.season)) + " • " +
        plural(s.items.size, tr("common.plural_seriya_1"), tr("common.plural_seriya_2"), tr("common.plural_seriya_5"))

/** «Сезон 1, серия 4» for an episode of a series; null for a film. */
private fun episodeSub(e: ContinueEntry): String? {
    val p = e.item.playable as? Playable.Series ?: return null
    for (s in p.seasons) {
        val ep = s.items.firstOrNull { it.file.path == e.target.file.path } ?: continue
        if (ep.title != null) return null
        val text = if (s.season == 0) tr("library.specvypusk_n", "episode" to ep.episode) else tr("library.sezon_seriya", "season" to s.season, "episode" to ep.episode)
        return if (e.label.startsWith(tr("library.dalshe"))) tr("library.dalshe") + text else text
    }
    return null
}

private fun timeLeft(e: ContinueEntry): String? {
    if (e.len <= 0 || e.pos <= 0) return null
    val min = ((e.len - e.pos) / 60_000L).toInt().coerceAtLeast(1)
    return if (min >= 60) tr("library.ostalos_ch_min", "hours" to (min / 60), "minutes" to (min % 60)) else tr("library.ostalos_min", "minutes" to min)
}

/** «Продолжить просмотр»: a banner with the film watched last (wide picture, description, progress); the others are the cards on the right. */
@Composable
private fun ContinueRow(items: List<LibraryItem>, lib: Library, atTop: () -> Boolean, onOpen: (PlayTarget) -> Unit) {
    val scope = rememberCoroutineScope()
    val wv = lib.watchVersion
    val entries = remember(items, wv) { continueEntries(items, lib) }
    var chosen by remember { mutableStateOf<Long?>(null) }
    var removed by remember { mutableStateOf(setOf<Long>()) } // posters on their way out (the exit animation runs before the film leaves the list)
    var armTimerKey by remember { mutableStateOf(0) }          // a click restarts the wait before the banner changes by itself
    val bannerSrc = remember { MutableInteractionSource() }
    val bannerHover by bannerSrc.collectIsHoveredAsState()
    // The banners change by themselves, one after another (not while the pointer is over the banner); a click on a card restarts the wait
    LaunchedEffect(entries, chosen, bannerHover, armTimerKey) {
        if (entries.size < 2 || bannerHover) return@LaunchedEffect
        kotlinx.coroutines.delay(9000)
        val cur = entries.indexOfFirst { it.item.m.id == chosen }.coerceAtLeast(0)
        chosen = entries[(cur + 1) % entries.size].item.m.id
    }

    // While the banner fades out (signed out, the list emptied) it keeps showing the film it had: the list has none
    var lastShown by remember { mutableStateOf(entries.firstOrNull()) }

    androidx.compose.animation.AnimatedVisibility(
        entries.isNotEmpty(),
        enter = fadeIn(tween(300)) + expandVertically(tween(320, easing = Sm.ease), clip = false),
        exit = fadeOut(tween(200)) + shrinkVertically(tween(260, easing = Sm.ease), clip = false)
    ) {
        val e = entries.firstOrNull { it.item.m.id == chosen } ?: entries.firstOrNull() ?: lastShown ?: return@AnimatedVisibility
        androidx.compose.runtime.SideEffect { lastShown = e }
        val queries = e.posterQueries
        val heroSeason = watchedSeason(e.item, e.target.file.path)?.season ?: 0
        var hero by remember(e.item.m.id, e.target.file.path) { mutableStateOf(lib.cachedHero(queries, heroSeason)) }
        LaunchedEffect(e.item.m.id, e.target.file.path) { if (hero == null) hero = lib.hero(queries, heroSeason) }

        // While the window is being dragged the heavy blurred glow is off (it would be recomputed on every step); it fades back 200 ms after the last step
        var resizing by remember { mutableStateOf(false) }
        var lastWidth by remember { mutableStateOf(-1) }
        var resizeStamp by remember { mutableStateOf(0) }
        LaunchedEffect(resizeStamp) { if (resizeStamp > 0) { kotlinx.coroutines.delay(200); resizing = false } }
        val glow by animateFloatAsState(if (resizing) 0f else 0.55f, tween(if (resizing) 0 else 300))

        // The glow is as tall as the picture at the banner's width: in a wide window it sticks out far above the banner
        // and the top edge of the page cuts it with a straight line. Then the glow (not the banner) goes down just enough to fit.
        // The room above is measured only on the unscrolled page (scrolling must not move the glow).
        var roomAbovePx by remember { mutableStateOf(-1f) }
        val density = androidx.compose.ui.platform.LocalDensity.current
        val backdrop = hero?.backdrop
        val needTop = if (backdrop == null || roomAbovePx < 0f || lastWidth <= 0 || backdrop.width <= 0) 0.dp else with(density) {
            val glowH = lastWidth * backdrop.height / backdrop.width.toFloat()
            // the glow fades in over its top 30 % (clearly seen from about 15 %), and the blur adds a little more
            val above = (glowH - 360.dp.toPx()) / 2f - glowH * 0.15f + 22.dp.toPx()
            (above - roomAbovePx - 6.dp.toPx()).coerceAtLeast(0f).toDp()
        }
        val glowShift by androidx.compose.animation.core.animateDpAsState(needTop, tween(300, easing = Sm.ease))
        Box(Modifier
            .onGloballyPositioned { c ->
                val vp = TileBounds.viewport
                if (atTop() && vp != null && vp.isAttached) roomAbovePx = c.localToWindow(Offset.Zero).y - vp.localToWindow(Offset.Zero).y
            }
            .padding(top = 6.dp, bottom = 22.dp).fillMaxWidth().height(360.dp).hoverable(bannerSrc)
            .onSizeChanged {
                val diff = if (lastWidth >= 0) kotlin.math.abs(it.width - lastWidth) else 0
                if (lastWidth >= 0 && diff > 8) { resizing = true; resizeStamp++ }
                lastWidth = it.width
            }) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Color(0xFF0B1226)))
        // One crossfade for both pictures, so the blurred part and the sharp one change together.
        // The part of the picture that does not fit the banner is not cut off: it goes on past the banner edges, blurred, onto the page background


        androidx.compose.animation.Crossfade(hero?.backdrop, animationSpec = tween(350), modifier = Modifier.fillMaxSize()) { img ->
            if (img != null) Box(Modifier.fillMaxSize()) {

                // the glow is drawn everywhere except the banner itself
                Box(Modifier.fillMaxSize().drawWithContent {
                    val hole = androidx.compose.ui.graphics.Path().apply {
                        addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, androidx.compose.ui.geometry.CornerRadius(20.dp.toPx())))
                    }
                    clipPath(hole, androidx.compose.ui.graphics.ClipOp.Difference) { this@drawWithContent.drawContent() }
                }) {
                    if (glow > 0f) Image(img, null, contentScale = ContentScale.FillWidth, alignment = Alignment.CenterEnd,
                        modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.CenterVertically, unbounded = true)
                            .graphicsLayer { alpha = glow; translationY = glowShift.toPx(); compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                            .drawWithContent { // soft edges: the picture melts into the page instead of ending in a straight line
                                drawContent()
                                val clear = Color.Transparent
                                drawRect(Brush.verticalGradient(0f to clear, 0.3f to Color.Black, 0.7f to Color.Black, 1f to clear), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                                drawRect(Brush.horizontalGradient(0f to clear, 0.12f to Color.Black, 0.88f to Color.Black, 1f to clear), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                            }
                            .blur(22.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded))
                }
                // The wide picture on the right (a poster stretched to this width would be blurry: without a wide picture the banner stays plain).
                // When the glow goes down, the frame of the sharp picture goes down with it, so the two still line up.
                val overflowPx = if (lastWidth > 0 && img.width > 0) lastWidth * img.height / img.width.toFloat() - with(density) { 360.dp.toPx() } else 0f
                val sharpAlign = if (overflowPx > 1f) {
                    val bias = (-2f * with(density) { glowShift.toPx() } / overflowPx).coerceIn(-1f, 1f)
                    androidx.compose.ui.BiasAlignment(1f, bias)
                } else Alignment.CenterEnd
                Image(img, null, contentScale = ContentScale.Crop, alignment = sharpAlign, filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)))
            }
        }
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).border(1.dp, Sm.glassEdge, RoundedCornerShape(20.dp))
        ) {
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xFF0B1226), 0.42f to Color(0xF20B1226), 0.78f to Color(0x330B1226), 1f to Color(0x000B1226))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color(0xAA0B1226))))

            // The cards on the right take at most 420 dp (and under half of a narrow window); the text keeps the rest and never runs under them
            val thumbsW = if (entries.size > 1) minOf(420.dp, maxWidth * 0.45f) else 0.dp
            val textW = (maxWidth - thumbsW - if (entries.size > 1) 60.dp else 0.dp).coerceIn(260.dp, 560.dp)
            Column(Modifier.fillMaxHeight().width(textW).padding(start = 28.dp, top = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(tr("library.prodolzhit_prosmotr").uppercase(), color = Color(0xFF9AA3B8), fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 1.6.sp)
                    val bannerTitle = if (isCollection(e.item.playable, e.item.m)) {
                        hero?.title ?: continueItemTitle(e.item, e.target.file)
                    } else {
                        lib.titleOf(e.item)
                    }
                    val logo = hero?.logo
                    if (logo != null) {
                        // the film's own title picture (transparent), like on streaming sites
                        Image(logo, bannerTitle, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                            filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                            modifier = Modifier.padding(top = 8.dp).height(68.dp).widthIn(max = 360.dp))
                    } else {
                        Text(bannerTitle, color = Color.White, fontFamily = Onest, fontWeight = FontWeight.Bold, fontSize = 32.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            lineHeight = 36.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        @Composable fun chip(t: String) = Text(t, color = Color(0xFFD6DBE4), fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 12.5.sp,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x1AFFFFFF)).border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
                        hero?.year?.let { chip(it) }
                        chip(itemTypeLabel(e.item))
                        val bannerFilms = collectionFilms(e.item)
                        if (bannerFilms.isNotEmpty()) chip(filmCountRu(bannerFilms.size))
                        else {
                            val s = watchedSeason(e.item, e.target.file.path)
                            if (s != null) chip(seasonChip(s))
                            else (e.item.playable as? Playable.Series)?.let { p ->
                                val eps = p.seasons.sumOf { it.items.size }
                                chip(plural(p.seasons.size, tr("common.plural_sezon_1"), tr("common.plural_sezon_2"), tr("common.plural_sezon_5")) + " • " + plural(eps, tr("common.plural_seriya_1"), tr("common.plural_seriya_2"), tr("common.plural_seriya_5")))
                            }
                        }
                        hero?.rating?.let { r ->
                            Text("★ " + "%.1f".format(java.util.Locale.US, r), color = Color(0xFFFACC15), fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                    hero?.overview?.let {
                        Text(it, color = Color(0xFFC3C9D6), fontFamily = Onest, fontSize = 14.5.sp, lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 12.dp))
                    }
                }
                Column {
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(episodeSub(e) ?: e.label, color = Color(0xFFC3C9D6), fontFamily = Onest, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        timeLeft(e)?.let { Text(it, color = Color(0xFF8E96A8), fontFamily = Onest, fontSize = 12.5.sp) }
                    }
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
                        Box(Modifier.fillMaxWidth(e.fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(2.dp))
                            .background(Brush.horizontalGradient(listOf(Color(0xFF2563EB), Color(0xFF38BDF8)))))
                    }
                    Spacer(Modifier.height(16.dp))
                    HeroPlayButton {
                        TileBounds.fromContinue = true
                        lib.saveEpisode(e.item.m.id, e.target.file.path)
                        onOpen(e.target)
                    }
                }
            }

            // The other films being watched: the chosen one is outlined, a click chooses; the arrow scrolls
            if (entries.size > 1) {
                Row(Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Only the OTHER films: the one shown in the banner has no poster here (it comes back when another one is chosen).
                    // Posters come and go smoothly; a removed one shrinks away first, then leaves the list.
                    Row(Modifier.widthIn(max = thumbsW)) {
                        for (x in entries) key(x.item.m.id) {
                            val id = x.item.m.id
                            AnimatedVisibility(
                                visible = id != e.item.m.id && id !in removed,
                                enter = fadeIn(tween(260)) + expandHorizontally(tween(320, easing = Sm.ease), expandFrom = Alignment.Start),
                                exit = fadeOut(tween(200)) + shrinkHorizontally(tween(300, easing = Sm.ease), shrinkTowards = Alignment.Start)
                            ) {
                                Box(Modifier.padding(start = 10.dp)) {
                                    HeroThumb(x, lib, onRemove = {
                                        removed = removed + id
                                        scope.launch { delay(340); lib.hideContinue(listOf(id)); removed = removed - id }
                                    }) { chosen = id; armTimerKey++ }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun HeroPlayButton(onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered) Color(0xFF3B74F0) else Sm.accent, tween(180))
    Row(
        Modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(bg).border(1.dp, Sm.accentEdge, RoundedCornerShape(12.dp))
            .hoverClick(src, onClick = onClick).padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("▶", color = Color.White, fontSize = 13.sp)
        Text(tr("library.prodolzhit"), color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable
private fun HeroThumb(e: ContinueEntry, lib: Library, onRemove: () -> Unit, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    var poster by remember(e.item.m.id, e.target.file.path) { mutableStateOf(lib.cachedPoster(e.posterQueries)) }
    LaunchedEffect(e.item.m.id, e.target.file.path) { if (poster == null) poster = lib.poster(e.posterQueries) }
    val edge by animateColorAsState(if (hovered) Color(0x66FFFFFF) else Color(0x26FFFFFF), tween(180))
    val lift by animateFloatAsState(if (hovered) -3f else 0f, tween(220, easing = Sm.ease))
    Box(Modifier.graphicsLayer { translationY = lift }.hoverable(src)) {
        Box(
            Modifier.width(96.dp).height(144.dp).trackBounds(TileBounds.cont, e.item.m.id).clip(RoundedCornerShape(10.dp)).background(Color(0xFF222736))
                .border(1.dp, edge, RoundedCornerShape(10.dp)).hoverClick(src, onClick = onClick)
        ) {
            poster?.let { Image(it, null, contentScale = ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.High, modifier = Modifier.fillMaxSize()) }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(e.fraction.coerceIn(0f, 1f)).height(3.dp).background(Color(0xFF38BDF8)))
        }
        if (hovered) {
            val xSrc = remember { MutableInteractionSource() }
            Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(CircleShape).background(Color(0xB3000000)).hoverClick(xSrc, onClick = onRemove), contentAlignment = Alignment.Center) {
                CloseCrossIcon(color = Color.White, size = 8.dp)
            }
        }
    }
}
