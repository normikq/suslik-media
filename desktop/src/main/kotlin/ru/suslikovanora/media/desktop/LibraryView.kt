package ru.suslikovanora.media.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
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

data class PlayTarget(
    val magnetId: Long, val file: MFile, val label: String, val following: List<MFile>, val subs: List<MFile>, val audio: List<MFile> = emptyList(),
    /** External voice-overs and subtitles of any episode of the torrent (for the following episodes). */
    val sidecarsOf: (MFile) -> Pair<List<MFile>, List<MFile>> = { emptyList<MFile>() to emptyList() }
)

/** Signed mirror links for files next to a video (subtitles, external voice-overs). */
private suspend fun mirrorUrls(files: List<MFile>): List<String> = coroutineScope {
    val enc = { v: String -> URLEncoder.encode(v, "UTF-8") }
    files.filter { it.link != null }.map { f ->
        async { runCatching { Api.get("/api/file/mirror-url?link=${enc(f.link!!)}&name=${enc(f.name)}").optString("url").ifBlank { null } }.getOrNull() }
    }.mapNotNull { it.await() }
}

private fun openSite() = runCatching { Desktop.getDesktop().browse(URI(Api.SITE)) }
private fun copy(text: String) = Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
private fun clock(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0); val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
fun Brand(onLogout: (() -> Unit)?, tabs: (@Composable () -> Unit)? = null) {
    // Logo on the left, tabs in the middle of the window, "log out" on the right.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(Brush.radialGradient(listOf(Color(0xFFFFD27A), Sm.lamp, Color(0xFFC97800))), CircleShape),
                contentAlignment = Alignment.Center
            ) { Text("▶", color = Sm.lampInk, fontSize = 15.sp) }
            Column(Modifier.padding(start = 12.dp)) {
                Text("Приватная медиатека", color = Sm.muted, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Text("Suslik Media", color = Sm.text, fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
        tabs?.invoke()
        if (onLogout != null) Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            UpdateButton() // appears (breathing) only when a newer version is on the server
            if (Updater.available != null) Spacer(Modifier.width(10.dp))
            OutlineButton("Выйти", onClick = onLogout)
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
        // The library is loaded by the main window once "authenticated" flips (this screen disappears right away).
        scope.launch { val e = lib.login(pass); busy = false; if (e == null) pass = "" else error = e }
    }
    Column(Modifier.fillMaxSize().background(Sm.night).padding(32.dp)) {
        Brand(null)
        Column(Modifier.widthIn(max = 640.dp).padding(top = 64.dp)) {
            Text("Все ваши ссылки в одном месте", color = Sm.muted, fontFamily = Onest, fontSize = 14.sp)
            Text("Найдите. Разблокируйте.\nСмотрите как удобно.", color = Sm.text, fontFamily = Unbounded, fontWeight = FontWeight.Bold,
                fontSize = 40.sp, lineHeight = 46.sp, modifier = Modifier.padding(top = 6.dp, bottom = 28.dp))
            Panel {
                Text("Войти", fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 20.sp, color = Sm.text)
                Text("Введите общий пароль доступа.", color = Sm.muted, fontFamily = Onest, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                Field(pass, { pass = it }, "Пароль доступа", password = true, onDone = { submit() })
                Spacer(Modifier.height(12.dp))
                LampButton(if (busy) "Выполняется вход…" else "Войти", enabled = !busy && pass.isNotBlank()) { submit() }
                error?.let { Text(it, color = Sm.bad, fontFamily = Onest, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)) }
            }
        }
    }
}

@Composable
fun LibraryView(
    lib: Library, tabs: @Composable () -> Unit,
    grid: androidx.compose.foundation.lazy.grid.LazyGridState = rememberLazyGridState(),
    onPlay: (PlayRequest) -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<PlayTarget?>(null) }
    val all = lib.items
    val filtered = remember(all, query) {
        val q = query.trim().lowercase()
        // Favourites first; otherwise the server's order is kept (stable sort).
        (if (q.isEmpty()) all else all.filter { it.searchText.contains(q) }).sortedByDescending { it.m.favorite }
    }

    Box(Modifier.fillMaxSize().background(Sm.night)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(190.dp), state = grid,
            contentPadding = PaddingValues(28.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Моя библиотека AllDebrid", fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 22.sp, color = Sm.text)
                            Text("Торренты, папки и файлы из вашего аккаунта.", color = Sm.muted, fontFamily = Onest, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        LampButton(if (lib.loading) "Загрузка…" else "Обновить", enabled = !lib.loading) { scope.launch { lib.refresh() } }
                    }
                    Spacer(Modifier.height(14.dp))
                    Field(query, { query = it }, "Поиск по названию…")
                    Spacer(Modifier.height(10.dp))
                    val c by animateColorAsState(when (lib.statusKind) { StatusKind.Ok -> Sm.ok; StatusKind.Error -> Sm.bad; else -> Sm.muted })
                    Text(lib.status, color = c, fontFamily = Onest, fontSize = 13.5.sp)
                }
            }
            if (query.isBlank()) item(span = { GridItemSpan(maxLineSpan) }, key = "continue", contentType = "continue") {
                ContinueRow(all, lib) { t -> target = t }
            }
            items(filtered, key = { it.m.id }, contentType = { "tile" }) { item ->
                Tile(item, lib, onOpen = { target = it }, modifier = Modifier.animateItem(fadeInSpec = tween(300), fadeOutSpec = tween(200)))
            }
        }
        target?.let { t -> PlayDialog(t, onDismiss = { target = null }, onPlay = { target = null; onPlay(it) }) }
    }
}

@Composable
private fun Tile(item: LibraryItem, lib: Library, onOpen: (PlayTarget) -> Unit, modifier: Modifier) {
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
        is Playable.Movie -> PlayTarget(m.id, playable.file, item.baseTitle, emptyList(), subtitlesFor(m, playable.file), externalAudioFor(m, playable.file))
        is Playable.Series -> {
            val s = playable.seasons.getOrNull(si) ?: playable.seasons.first()
            val e = s.items.getOrNull(ei) ?: s.items.first()
            lib.saveEpisode(m.id, e.file.path)
            PlayTarget(m.id, e.file, episodeLabel(item.baseTitle, s, e), s.items.drop(s.items.indexOf(e)).map { it.file }, subtitlesFor(m, e.file), externalAudioFor(m, e.file)) { f -> externalAudioFor(m, f) to subtitlesFor(m, f) }
        }
        Playable.None -> null
    }
    val canPlay = m.ready && playable !is Playable.None
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val lift by animateFloatAsState(if (hovered) -6f else 0f, tween(300, easing = Sm.ease))
    val zoom by animateFloatAsState(if (hovered) 1.05f else 1f, tween(500, easing = Sm.ease))
    val edge by animateColorAsState(if (hovered) Color(0x88FFB02E) else Sm.line)

    Column(modifier.graphicsLayer { translationY = lift }.hoverable(src)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF242A3A), Color(0xFF10131A))))
                .border(1.dp, edge, RoundedCornerShape(14.dp))
                .clickable(enabled = canPlay) { target()?.let(onOpen) }
                .then(if (canPlay) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
        ) {
            val p = poster
            if (p != null) Image(p, "Постер", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom })
            else Text(item.baseTitle, color = Color.White, fontFamily = Unbounded, fontSize = 15.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 52.dp))
            val w = remember(m.id, current?.path, wv) { current?.let { lib.watchInfo(watchKey(m.id, it)) } }
            if (m.ready && w != null && w.fraction > 0.01f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
                    Box(Modifier.fillMaxWidth(if (w.watched) 1f else w.fraction).height(4.dp).background(Sm.lamp))
                }
            }
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                if (m.downloading && m.size > 0) {
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(5.dp)).background(Color(0x50FFFFFF))) {
                        Box(Modifier.fillMaxWidth(m.progress).height(5.dp).background(Sm.lamp))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text(if (m.ready) "Готово · 100%" else if (m.downloading) "${(m.progress * 100).toInt()}% · ${statusRu(m)}" else statusRu(m),
                    color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Sm.scrim).padding(horizontal = 8.dp, vertical = 4.dp))
            }
            FavStar(m.favorite, tileHovered = hovered, Modifier.align(Alignment.TopEnd).padding(8.dp)) { on -> lib.setFavorite(m, on) }
            val playBg by animateColorAsState(if (hovered) Sm.lamp else Color(0xB3090B10), tween(240))
            val playScale by animateFloatAsState(if (hovered) 1.08f else 1f, tween(300, easing = Sm.ease))
            if (canPlay) Box(
                Modifier.align(Alignment.Center).padding(bottom = 24.dp).graphicsLayer { scaleX = playScale; scaleY = playScale }.size(58.dp).clip(CircleShape)
                    .background(playBg).border(1.dp, Color(0x44FFFFFF), CircleShape)
                    .clickable { target()?.let(onOpen) }.pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center
            ) { Text("▶", color = if (hovered) Sm.lampInk else Color.White, fontSize = 22.sp) }
        }
        Column(Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp)) {
            Text(m.name, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${statusRu(m)} · ${formatBytes(m.size)}", color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 3.dp))
            if (m.ready && last != null) {
                val (lf, lw) = last
                val ep = series?.seasons?.firstNotNullOfOrNull { s -> s.items.firstOrNull { it.file.path == lf.path }?.let { e -> episodeLabel("", s, e).removePrefix(" · ") } }
                Text(when {
                    lw.watched -> if (ep != null) "✓ Просмотрено до $ep" else "✓ Просмотрено"
                    ep != null -> "▶ Продолжить $ep · ${clock(lw.pos)}"
                    else -> "▶ Продолжить с ${clock(lw.pos)}"
                }, color = Sm.lamp, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (m.ready && current != null) Text("🎞 " + (media ?: "анализ видео…"), color = if (media == null) Sm.muted else Color(0xFFFFD791),
                fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
            if (series != null && m.ready) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (series.seasons.size > 1) Select(series.seasons.getOrNull(si)?.let { if (it.season == 0) "Спец." else "Сезон ${it.season}" } ?: "",
                    series.seasons.map { if (it.season == 0) "Спецвыпуски" else "Сезон ${it.season}" }, { si = it; ei = 0 }, Modifier.weight(1f))
                val season = series.seasons.getOrNull(si) ?: series.seasons.first()
                Select(season.items.getOrNull(ei)?.let { it.title ?: "Серия ${it.episode}" } ?: "", season.items.map { it.title ?: "Серия ${it.episode}" }, { ei = it }, Modifier.weight(1f))
            }
            if (m.canRemove) RemoveButton(m.downloading) { lib.remove(m) }
        }
    }
}

@Composable
private fun Select(label: String, options: List<String>, onPick: (Int) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val edge by animateColorAsState(if (hovered || open) Sm.lamp else Sm.line2, tween(200))
    val bg by animateColorAsState(if (hovered) Color(0xFF212636) else Sm.film, tween(200))
    val arrow by animateFloatAsState(if (open) 180f else 0f, tween(260, easing = Sm.ease))
    Box(modifier) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).border(1.dp, edge, RoundedCornerShape(10.dp))
            .hoverClick(src) { open = true }.heightIn(min = 34.dp).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Sm.text, fontFamily = Onest, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text("▾", color = if (hovered || open) Sm.lamp else Sm.muted, fontSize = 12.sp, modifier = Modifier.graphicsLayer { rotationZ = arrow })
        }
        DropdownMenu(open, { open = false }, Modifier.background(Sm.film).heightIn(max = 420.dp)) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o, color = Sm.text, fontFamily = Onest) }, onClick = { open = false; onPick(i) }) }
        }
    }
}

@Composable
private fun RemoveButton(downloading: Boolean, onConfirm: suspend () -> String?) {
    val scope = rememberCoroutineScope()
    var armed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(armed) { if (armed) { delay(4000); armed = false } }
    LaunchedEffect(error) { if (error != null) { delay(4000); error = null } }
    val label = when {
        error != null -> "Ошибка: ${error!!.take(50)}"
        busy -> if (downloading) "Отмена…" else "Удаление…"
        armed -> "Точно? Нажмите ещё раз"
        downloading -> "✕ Отменить"
        else -> "🗑 Удалить"
    }
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val scale = hoverScale(hovered, pressed)
    val bg by animateColorAsState(when { armed -> Color(0xFFFF4D4D); hovered -> Color(0x40FF6B6B); else -> Color(0x1AFF6B6B) }, tween(200))
    val edge by animateColorAsState(if (armed || hovered) Color(0xFFFF6B6B) else Color(0x66FF6B6B), tween(200))
    Box(Modifier.padding(top = 8.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(8.dp))
        .background(bg).border(1.dp, edge, RoundedCornerShape(8.dp))
        .hoverClick(src, enabled = !busy) {
            if (!armed) { armed = true; error = null } else { armed = false; busy = true; scope.launch { val e = onConfirm(); busy = false; error = e } }
        }.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(label, color = if (armed) Color.White else Color(0xFFFFB3B3), fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 12.sp)
    }
}

/** Source choice, like the site's play sheet: mirror (with the season playlist) or direct AllDebrid link. */
@Composable
private fun PlayDialog(t: PlayTarget, onDismiss: () -> Unit, onPlay: (PlayRequest) -> Unit) {
    var sub by remember(t) { mutableStateOf("Получение ссылок…") }
    var mirror by remember(t) { mutableStateOf<String?>(null) }
    var direct by remember(t) { mutableStateOf<String?>(null) }
    var playlist by remember(t) { mutableStateOf<String?>(null) }
    var subs by remember(t) { mutableStateOf<List<String>>(emptyList()) }
    var audio by remember(t) { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(t) {
        val link = t.file.link ?: run { sub = "Ссылка на файл ещё не готова — нажмите «Обновить»."; return@LaunchedEffect }
        val enc = { v: String -> URLEncoder.encode(v, "UTF-8") }
        val name = t.file.name.ifBlank { t.file.path.substringAfterLast('/') }
        coroutineScope {
            val d = async { runCatching { Api.post("/api/file/unlock", JSONObject().put("link", link)).optString("url").ifBlank { null } } }
            val mr = async { runCatching { Api.get("/api/file/mirror-url?link=${enc(link)}&name=${enc(name)}").optString("url").ifBlank { null } } }
            direct = d.await().getOrNull(); mirror = mr.await().getOrNull()
        }
        sub = if (mirror != null || direct != null) "Выберите источник" else "Не удалось получить ссылки"
        // Signed links for the files next to the video (subtitles and external voice-overs).
        if (t.subs.isNotEmpty()) subs = mirrorUrls(t.subs)
        if (t.audio.isNotEmpty()) audio = mirrorUrls(t.audio)
        val following = t.following.filter { it.link != null }
        if (mirror != null && following.size > 1) runCatching {
            val items = JSONArray(); following.forEach { items.put(JSONObject().put("index", it.index).put("link", it.link)) }
            Api.post("/api/playlist", JSONObject().put("magnetId", t.magnetId).put("title", t.label).put("items", items)).optString("url").ifBlank { null }
        }.onSuccess { playlist = it }
    }
    // Fades in with the card rising slightly (no hard pop).
    var shown by remember(t) { mutableStateOf(false) }
    LaunchedEffect(t) { shown = true }
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(260, easing = Sm.ease))
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = a }.background(Sm.scrim).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
        contentAlignment = Alignment.Center) {
        Column(Modifier.graphicsLayer { translationY = (1f - a) * 24f; scaleX = 0.97f + 0.03f * a; scaleY = 0.97f + 0.03f * a }.widthIn(max = 520.dp).clip(RoundedCornerShape(20.dp)).background(Sm.booth).border(1.dp, Sm.line2, RoundedCornerShape(20.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}.padding(22.dp)) {
            Text(t.label, fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 18.sp, color = Sm.text)
            Text(sub + (if (audio.isNotEmpty()) " · озвучек из раздачи: ${audio.size}" else "") + (if (subs.isNotEmpty()) " · субтитров: ${subs.size}" else ""), color = Sm.muted, fontFamily = Onest, fontSize = 13.5.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            Source("Через зеркало", if (playlist != null) "Через ваш сервер · дальше все серии сезона" else "Трафик идёт через ваш сервер", mirror) {
                val keys = if (playlist != null) t.following.filter { f -> f.link != null }.map { f -> watchKey(t.magnetId, f) } else listOf(watchKey(t.magnetId, t.file))
                val list = t.following.filter { f -> f.link != null }
                // Episode i of the playlist -> its own voice-overs / subtitles (fetched by the player just before it starts).
                val resolve: suspend (Int) -> Pair<List<String>, List<String>> = { i ->
                    list.getOrNull(i)?.let { f -> val (a, sb) = t.sidecarsOf(f); mirrorUrls(a) to mirrorUrls(sb) } ?: (emptyList<String>() to emptyList())
                }
                onPlay(PlayRequest(it, t.label, playlist, subs, keys, audio, if (playlist != null) resolve else null))
            }
            Spacer(Modifier.height(10.dp))
            Source("Напрямую", "Прямая ссылка AllDebrid", direct) { onPlay(PlayRequest(it, t.label, null, subs, listOf(watchKey(t.magnetId, t.file)), audio)) }
            Spacer(Modifier.height(14.dp))
            OutlineButton("Закрыть", Modifier.fillMaxWidth(), onDismiss)
        }
    }
}

@Composable
private fun Source(title: String, hint: String, url: String?, onPlay: (String) -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val edge by animateColorAsState(if (hovered && url != null) Color(0x88FFB02E) else Sm.line, tween(220))
    Column(Modifier.fillMaxWidth().hoverable(src).clip(RoundedCornerShape(14.dp)).background(Sm.film).border(1.dp, edge, RoundedCornerShape(14.dp)).padding(14.dp)) {
        Text(title, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Sm.text)
        Text(hint, fontFamily = Onest, fontSize = 13.sp, color = Sm.muted, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LampButton("▶ Смотреть", enabled = url != null, modifier = Modifier.weight(1f)) { url?.let(onPlay) }
            OutlineButton("Копировать ссылку") { url?.let(::copy) }
        }
    }
}

/** Star in the poster's corner: shows on hover (always when on); pops when toggled. */
@Composable
private fun FavStar(on: Boolean, tileHovered: Boolean, modifier: Modifier, onToggle: suspend (Boolean) -> String?) {
    val scope = rememberCoroutineScope()
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    var busy by remember { mutableStateOf(false) }
    val pop = remember { androidx.compose.animation.core.Animatable(1f) }
    val shown by animateFloatAsState(if (on || tileHovered || hovered) 1f else 0f, tween(220, easing = Sm.ease))
    val bg by animateColorAsState(if (on) Sm.lamp else Color(0xB3090B10), tween(220))
    val fg by animateColorAsState(when { on -> Sm.lampInk; hovered -> Sm.lamp; else -> Color.White }, tween(220))
    val edge by animateColorAsState(if (hovered && !on) Sm.lamp else if (on) Color.Transparent else Color(0x2EFFFFFF), tween(220))
    Box(
        modifier.graphicsLayer { alpha = shown; val s = (0.9f + 0.1f * shown) * pop.value * (if (hovered) 1.08f else 1f); scaleX = s; scaleY = s }
            .size(34.dp).clip(CircleShape).background(bg).border(1.dp, edge, CircleShape)
            .hoverClick(src, enabled = !busy && shown > 0.05f) {
                busy = true
                scope.launch {
                    launch { pop.animateTo(1.25f, tween(130)); pop.animateTo(1f, tween(200, easing = Sm.ease)) }
                    onToggle(!on); busy = false
                }
            },
        contentAlignment = Alignment.Center
    ) { Text(if (on) "★" else "☆", color = fg, fontSize = 17.sp) }
}

// ---- «Продолжить просмотр» -------------------------------------------------------------------

private class ContinueEntry(val item: LibraryItem, val target: PlayTarget, val label: String, val fraction: Float, val left: Long, val at: Long)

/** Play target for a given file of an item (the same choice the tile would make). */
private fun targetFor(item: LibraryItem, file: MFile): PlayTarget? {
    val m = item.m
    return when (val p = item.playable) {
        is Playable.Movie -> if (p.file.path == file.path) PlayTarget(m.id, p.file, item.baseTitle, emptyList(), subtitlesFor(m, p.file), externalAudioFor(m, p.file)) else null
        is Playable.Series -> {
            for (s in p.seasons) {
                val i = s.items.indexOfFirst { it.file.path == file.path }
                if (i < 0) continue
                val e = s.items[i]
                return PlayTarget(m.id, e.file, episodeLabel(item.baseTitle, s, e), s.items.drop(i).map { it.file },
                    subtitlesFor(m, e.file), externalAudioFor(m, e.file)) { f -> externalAudioFor(m, f) to subtitlesFor(m, f) }
            }
            null
        }
        Playable.None -> null
    }
}

/** Episode after [file] (next in the season, else first of the next season). */
private fun nextEpisode(p: Playable.Series, file: MFile): MFile? {
    val all = p.seasons.flatMap { it.items }
    val i = all.indexOfFirst { it.file.path == file.path }
    return if (i in 0 until all.size - 1) all[i + 1].file else null
}

private fun continueEntries(items: List<LibraryItem>, lib: Library): List<ContinueEntry> = items.mapNotNull { item ->
    if (!item.m.ready) return@mapNotNull null
    val (file, w) = lib.lastWatched(item.m) ?: return@mapNotNull null
    if (System.currentTimeMillis() - w.at > 60L * 24 * 3600 * 1000) return@mapNotNull null // older than 60 days
    if (lib.continueHiddenAt(item.m.id) >= w.at) return@mapNotNull null                  // removed from the row
    if (!w.watched) {
        val t = targetFor(item, file) ?: return@mapNotNull null
        ContinueEntry(item, t, t.label.removePrefix(item.baseTitle).removePrefix(" · ").ifBlank { "Фильм" }, w.fraction, (w.len - w.pos).coerceAtLeast(0), w.at)
    } else {
        // Finished an episode: offer the next one.
        val series = item.playable as? Playable.Series ?: return@mapNotNull null
        val next = nextEpisode(series, file) ?: return@mapNotNull null
        if (lib.watchInfo(watchKey(item.m.id, next))?.watched == true) return@mapNotNull null
        val t = targetFor(item, next) ?: return@mapNotNull null
        ContinueEntry(item, t, "Дальше: " + t.label.removePrefix(item.baseTitle).removePrefix(" · "), 0f, 0L, w.at)
    }
}.sortedByDescending { it.at }.take(12)

@Composable
private fun ContinueRow(items: List<LibraryItem>, lib: Library, onOpen: (PlayTarget) -> Unit) {
    val wv = lib.watchVersion
    val entries = remember(items, wv) { continueEntries(items, lib) }
    androidx.compose.animation.AnimatedVisibility(entries.isNotEmpty(), enter = fadeIn(tween(300)) + expandVertically(tween(320, easing = Sm.ease)),
        exit = fadeOut(tween(200)) + shrinkVertically(tween(260, easing = Sm.ease))) {
        Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Продолжить просмотр", fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = Sm.text,
                    modifier = Modifier.weight(1f))
                OutlineButton("Очистить") { lib.hideContinue(entries.map { it.item.m.id }) }
            }
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(entries.size, key = { entries[it].item.m.id }) { i ->
                    val e = entries[i]
                    ContinueCard(e, lib, Modifier.animateItem(fadeInSpec = tween(250), fadeOutSpec = tween(200)),
                        onRemove = { lib.hideContinue(listOf(e.item.m.id)) }) {
                        lib.saveEpisode(e.item.m.id, e.target.file.path); onOpen(e.target)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(e: ContinueEntry, lib: Library, modifier: Modifier, onRemove: () -> Unit, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val lift by animateFloatAsState(if (hovered) -4f else 0f, tween(260, easing = Sm.ease))
    val edge by animateColorAsState(if (hovered) Color(0x88FFB02E) else Sm.line, tween(220))
    var poster by remember(e.item.m.id) { mutableStateOf(lib.cachedPoster(e.item.posterQueries)) }
    LaunchedEffect(e.item.m.id) { if (poster == null) poster = lib.poster(e.item.posterQueries) }
    val xSrc = remember { MutableInteractionSource() }
    val xHover by xSrc.collectIsHoveredAsState()
    val xAlpha by animateFloatAsState(if (hovered || xHover) 1f else 0f, tween(200))
    Box(modifier.graphicsLayer { translationY = lift }.hoverable(src)) {
    Row(
        Modifier.width(320.dp).height(112.dp).clip(RoundedCornerShape(14.dp)).background(Sm.booth)
            .border(1.dp, edge, RoundedCornerShape(14.dp)).hoverClick(src, onClick = onClick)
    ) {
        Box(Modifier.width(75.dp).fillMaxHeight().background(Brush.linearGradient(listOf(Color(0xFF242A3A), Color(0xFF10131A))))) {
            poster?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Box(Modifier.align(Alignment.Center).size(34.dp).clip(CircleShape).background(if (hovered) Sm.lamp else Color(0xB3090B10)),
                contentAlignment = Alignment.Center) { Text("▶", color = if (hovered) Sm.lampInk else Color.White, fontSize = 13.sp) }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(e.item.baseTitle, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(e.label, color = Sm.lamp, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp))
            }
            Column {
                if (e.left > 0) Text("осталось ${clock(e.left)}", color = Sm.muted, fontFamily = Onest, fontSize = 12.sp, modifier = Modifier.padding(bottom = 5.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)).background(Sm.line)) {
                    Box(Modifier.fillMaxWidth(e.fraction.coerceIn(0f, 1f)).fillMaxHeight().background(Sm.lamp))
                }
            }
        }
    }
    // × in the corner (on hover): drop this one from the row.
    Box(Modifier.align(Alignment.TopEnd).padding(6.dp).graphicsLayer { alpha = xAlpha }.size(26.dp).clip(CircleShape)
        .background(if (xHover) Color(0xFFFF6B6B) else Color(0xCC090B10)).hoverClick(xSrc, enabled = xAlpha > 0.05f, onClick = onRemove),
        contentAlignment = Alignment.Center) { Text("×", color = Color.White, fontSize = 15.sp) }
    }
}
