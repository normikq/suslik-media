package ru.suslikovanora.media.library

import ru.suslikovanora.media.core.*

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.shadow
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
import ru.suslikovanora.media.ui.Onest
import ru.suslikovanora.media.ui.Sm
import ru.suslikovanora.media.ui.Unbounded

/** What the play sheet needs: one file, its display title and (for series) the rest of the season. */
data class PlayTarget(val magnetId: Long, val file: MFile, val label: String, val following: List<MFile>, val subs: List<MFile> = emptyList(), val audio: List<MFile> = emptyList())

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

@Composable
fun LibraryScreen(
    store: LibraryStore,
    onPlay: (PlayTarget) -> Unit,
    onLogout: () -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    gridState: LazyGridState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val all = store.items
    val filtered = remember(all, query) {
        val q = query.trim().lowercase()
        // Favourites first; otherwise the server's order (stable sort).
        (if (q.isEmpty()) all else all.filter { it.searchText.contains(q) }).sortedByDescending { it.m.favorite }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 152.dp),
        state = gridState,
        modifier = modifier.fillMaxSize().background(Sm.night),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) { HeaderSlot() }
        item(key = "panel", span = { GridItemSpan(maxLineSpan) }) {
            LibraryPanel(
                query = query, onQuery = onQuery,
                loading = store.loading, status = store.status, kind = store.statusKind,
                onRefresh = { scope.launch { store.refresh() } }
            )
        }
        // Only once something is known: at start (before the saved copy is read) this would flash "nothing here".
        if (filtered.isEmpty() && !store.loading && (store.loadedOnce || store.magnets.isNotEmpty())) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Panel {
                    Text(
                        if (store.magnets.isEmpty()) "Сохранённой медиатеки пока нет. Нажмите «Обновить», чтобы загрузить данные из AllDebrid."
                        else "Совпадений не найдено.",
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

/**
 * Height of the brand header. The header itself is drawn once above the tabs (MainActivity) and follows the open
 * tab's scrolling, so it stays put while the tabs slide; each tab keeps this empty slot where it used to be.
 */
val LocalHeaderHeight = androidx.compose.runtime.compositionLocalOf { 46.dp }

@Composable
fun HeaderSlot() = Spacer(Modifier.fillMaxWidth().height(LocalHeaderHeight.current))

@Composable
fun BrandHeader(onLogout: (() -> Unit)?, onSettings: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).shadow(14.dp, CircleShape, ambientColor = Sm.lamp, spotColor = Sm.lamp)
                .background(Brush.radialGradient(listOf(Color(0xFFFFD27A), Sm.lamp, Color(0xFFC97800))), CircleShape),
            contentAlignment = Alignment.Center
        ) { Text("▶", color = Sm.lampInk, fontSize = 15.sp, modifier = Modifier.padding(start = 2.dp)) }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text("Приватная медиатека", color = Sm.muted, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Text("Suslik Media", color = Sm.text, fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        if (onSettings != null) { SettingsButton(onSettings); Spacer(Modifier.width(8.dp)) }
        if (onLogout != null) OutlineButton("Выйти", onClick = onLogout)
    }
}

/** Square outline button with a gear drawn in code (no icon font). */
@Composable
private fun SettingsButton(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val turn by animateFloatAsState(if (pressed) 30f else 0f, tween(260, easing = Sm.ease), label = "gear")
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Sm.line2, RoundedCornerShape(12.dp))
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
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Sm.booth)
            .border(1.dp, Sm.line, RoundedCornerShape(16.dp)).padding(16.dp)
    ) { content() }
}

@Composable
private fun LibraryPanel(
    query: String, onQuery: (String) -> Unit,
    loading: Boolean, status: String, kind: StatusKind,
    onRefresh: () -> Unit
) {
    Panel {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text("Моя библиотека AllDebrid", fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 19.sp, lineHeight = 24.sp, color = Sm.text)
                Text("Торренты, папки и файлы из вашего аккаунта.", color = Sm.muted, fontFamily = Onest, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            }
            LampButton(if (loading) "Загрузка…" else "Обновить", enabled = !loading, onClick = onRefresh)
        }
        Spacer(Modifier.height(14.dp))
        SearchField(value = query, onValue = onQuery, placeholder = "Поиск по названию…")
        Spacer(Modifier.height(10.dp))
        val color by animateColorAsState(
            when (kind) { StatusKind.Ok -> Sm.ok; StatusKind.Error -> Sm.bad; StatusKind.Info -> Sm.muted }, label = "status"
        )
        Text(status, color = color, fontFamily = Onest, fontSize = 13.5.sp, lineHeight = 19.sp)
    }
}

@Composable
fun SearchField(value: String, onValue: (String) -> Unit, placeholder: String, password: Boolean = false, onDone: (() -> Unit)? = null) {
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(if (focused) Sm.lamp else Sm.line2, label = "border")
    BasicTextField(
        value = value, onValueChange = onValue, singleLine = true,
        textStyle = TextStyle(color = Sm.text, fontFamily = Onest, fontSize = 16.sp),
        cursorBrush = SolidColor(Sm.lamp),
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            imeAction = if (onDone != null) ImeAction.Go else ImeAction.Search,
            keyboardType = if (password) androidx.compose.ui.text.input.KeyboardType.Password else androidx.compose.ui.text.input.KeyboardType.Text
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onAny = { focus.clearFocus(); onDone?.invoke() }),
        modifier = Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(12.dp)).background(Sm.night)
            .border(1.dp, border, RoundedCornerShape(12.dp)),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
                if (value.isEmpty()) Text(placeholder, color = Sm.muted, fontFamily = Onest, fontSize = 16.sp)
                inner()
            }
        }
    )
}

@Composable
fun LampButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, tween(180, easing = Sm.ease), label = "press")
    Box(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.6f }
            .shadow(10.dp, RoundedCornerShape(12.dp), ambientColor = Sm.lamp, spotColor = Sm.lamp)
            .clip(RoundedCornerShape(12.dp)).background(Sm.lamp)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .heightIn(min = 44.dp).padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Sm.lampInk, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
}

@Composable
fun OutlineButton(text: String, modifier: Modifier = Modifier, color: Color = Sm.text, border: Color = Sm.line2, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, tween(180, easing = Sm.ease), label = "press")
    Box(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp)).border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = color, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
}

// ---- tile ------------------------------------------------------------------------------------

@Composable
private fun PosterTile(item: LibraryItem, store: LibraryStore, onPlay: (PlayTarget) -> Unit, index: Int = 0, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Heavy work (playlist, titles) was done once in the store; scrolling only reads it.
    val m = item.m
    val playable = item.playable
    val baseTitle = item.baseTitle
    val queries = item.posterQueries
    var poster by remember(m.id) { mutableStateOf(store.cachedPoster(queries)) }
    // Only tiles that are actually composed (on or near the screen) ask for a poster.
    LaunchedEffect(m.id) { if (poster == null) poster = store.poster(queries) }

    // Series: chosen season/episode (remembered per torrent, like on the website).
    val series = playable as? Playable.Series
    var seasonIdx by remember(m.id) { mutableStateOf(0) }
    var episodeIdx by remember(m.id) { mutableStateOf(0) }
    val watchVersion = store.watchVersion
    val last = remember(m.id, watchVersion) { store.lastWatched(m) }
    LaunchedEffect(m.id, watchVersion) {
        if (series != null) {
            // The episode watched last (also when the player moved on by itself), otherwise the one picked by hand.
            val saved = last?.first?.path ?: store.lastEpisode(m.id)
            series.seasons.forEachIndexed { si, s ->
                val ei = s.items.indexOfFirst { it.file.path == saved }
                if (ei >= 0) { seasonIdx = si; episodeIdx = ei }
            }
        }
    }

    // The file the ▶ button would play right now (follows the chosen episode).
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
            PlayTarget(m.id, e.file, episodeLabel(baseTitle, s, e), s.items.drop(s.items.indexOf(e)).map { it.file }, subtitlesFor(m, e.file), externalAudioFor(m, e.file))
        }
        Playable.None -> null
    }

    // Entrance: first time a tile is shown it rises in (staggered), never again when coming back to the tab.
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
                .background(Brush.linearGradient(listOf(Color(0xFF242A3A), Color(0xFF10131A))))
                .border(1.dp, Sm.line, RoundedCornerShape(14.dp))
                .clickable(interactionSource = source, indication = null, enabled = canPlay) { target()?.let(onPlay) }
        ) {
            val url = poster
            if (url != null) {
                AsyncImage(
                    // Same key for the placeholder: an already decoded poster shows on the very first frame
                    // (coming back to the tab no longer flashes an empty tile before the image).
                    model = ImageRequest.Builder(context).data(url).memoryCacheKey(url).placeholderMemoryCacheKey(url).crossfade(350).build(),
                    imageLoader = Posters.loader(context),
                    contentDescription = "Постер: $baseTitle",
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
            FavStar(m.favorite, Modifier.align(Alignment.TopEnd).padding(8.dp)) { on -> store.setFavorite(m, on) }
            // watched part of the current file: thin amber line along the poster's bottom edge
            val watch = remember(m.id, currentFile?.path, watchVersion) { currentFile?.let { store.watchInfo(watchKey(m.id, it)) } }
            if (m.ready && watch != null && watch.fraction > 0.01f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
                    Box(Modifier.fillMaxWidth(if (watch.watched) 1f else watch.fraction).height(4.dp).background(Sm.lamp))
                }
            }
            // bottom: progress + label
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                if (m.downloading && m.size > 0) {
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(5.dp)).background(Color(0x50FFFFFF))) {
                        Box(Modifier.fillMaxWidth(m.progress).height(5.dp).clip(RoundedCornerShape(5.dp))
                            .background(Brush.horizontalGradient(listOf(Color(0xFFFFCF70), Sm.lamp))))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                val label = when {
                    m.ready -> "Готово · 100%"
                    m.downloading -> "${(m.progress * 100).toInt()}% · ${statusRu(m)}"
                    else -> statusRu(m)
                }
                Text(
                    label, color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Sm.scrim).padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            if (canPlay) {
                Box(
                    Modifier.align(Alignment.Center).padding(bottom = 24.dp).size(56.dp)
                        .clip(CircleShape).background(Color(0xB3090B10))
                        .border(1.dp, Color(0x44FFFFFF), CircleShape)
                        .clickable { target()?.let(onPlay) },
                    contentAlignment = Alignment.Center
                ) { Text("▶", color = Color.White, fontSize = 22.sp, modifier = Modifier.padding(start = 3.dp)) }
            }
        }
        Column(Modifier.padding(start = 2.dp, end = 2.dp, top = 10.dp)) {
            Text(m.name, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${statusRu(m)} · ${formatBytes(m.size)}", color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 3.dp))
            if (m.ready && last != null) {
                val (lf, lw) = last
                val ep = (playable as? Playable.Series)?.seasons?.firstNotNullOfOrNull { s ->
                    s.items.firstOrNull { it.file.path == lf.path }?.let { e -> (if (s.season == 0) "Спец." else "S" + s.season.toString().padStart(2, '0')) + "E" + e.episode.toString().padStart(2, '0') }
                }
                val text = when {
                    lw.watched && ep != null -> "✓ Просмотрено до $ep"
                    lw.watched -> "✓ Просмотрено"
                    ep != null -> "▶ Продолжить $ep · ${formatClock(lw.pos)}"
                    else -> "▶ Продолжить с ${formatClock(lw.pos)}"
                }
                Text(text, color = Sm.lamp, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (m.ready && currentFile != null) {
                Text(
                    "🎞 " + (media ?: "анализ видео…"),
                    color = if (media == null) Sm.muted else Color(0xFFFFD791),
                    fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (series != null && m.ready) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (series.seasons.size > 1) {
                        SelectChip(
                            label = series.seasons.getOrNull(seasonIdx)?.let { if (it.season == 0) "Спец." else "Сезон ${it.season}" } ?: "",
                            options = series.seasons.map { if (it.season == 0) "Спецвыпуски" else "Сезон ${it.season}" },
                            onPick = { seasonIdx = it; episodeIdx = 0 },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    val season = series.seasons.getOrNull(seasonIdx) ?: series.seasons.first()
                    SelectChip(
                        label = season.items.getOrNull(episodeIdx)?.let { it.title ?: "Серия ${it.episode}" } ?: "",
                        options = season.items.map { it.title ?: "Серия ${it.episode}" },
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
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Sm.film)
                .border(1.dp, Sm.line2, RoundedCornerShape(10.dp)).clickable { open = true }
                .heightIn(min = 36.dp).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("▾", color = Sm.muted, fontSize = 12.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Sm.film).heightIn(max = 360.dp)) {
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
        error != null -> "Ошибка: ${error!!.take(60)}"
        busy -> if (downloading) "Отмена…" else "Удаление…"
        armed -> "Точно? Нажмите ещё раз"
        downloading -> "✕ Отменить"
        else -> "🗑 Удалить"
    }
    val bg by animateColorAsState(if (armed) Color(0xFFFF4D4D) else if (downloading) Color(0x1AFFB86B) else Color(0x1AFF6B6B), label = "rmbg")
    val fg = if (armed) Color.White else if (downloading) Sm.warn else Color(0xFFFFB3B3)
    val br = if (armed) Color(0xFFFF4D4D) else if (downloading) Color(0x66FFB86B) else Color(0x66FF6B6B)
    Box(
        Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).background(bg).border(BorderStroke(1.dp, br), RoundedCornerShape(8.dp))
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

/** Favourite star in the poster's corner (touch: always visible; amber when on, pops when toggled). */
@Composable
private fun FavStar(on: Boolean, modifier: Modifier, onToggle: suspend (Boolean) -> String?) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val pop = remember { Animatable(1f) }
    val bg by animateColorAsState(if (on) Sm.lamp else Color(0x99090B10), tween(220), label = "favbg")
    val fg by animateColorAsState(if (on) Sm.lampInk else Color.White, tween(220), label = "favfg")
    Box(
        modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }.size(34.dp).clip(CircleShape).background(bg)
            .border(1.dp, if (on) Color.Transparent else Color(0x33FFFFFF), CircleShape)
            .clickable(enabled = !busy, interactionSource = remember { MutableInteractionSource() }, indication = null) {
                busy = true
                scope.launch {
                    launch { pop.animateTo(1.25f, tween(130)); pop.animateTo(1f, tween(220, easing = Sm.ease)) }
                    onToggle(!on); busy = false
                }
            },
        contentAlignment = Alignment.Center
    ) { Text(if (on) "★" else "☆", color = fg, fontSize = 17.sp) }
}
