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
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.ui.draw.rotate
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

// The play dialog of a library item (source, start place, voice-overs and subtitles); moved out of LibraryView.kt.

private fun extractTrilogyFilmInfo(
    baseTitle: String,
    ep: Episode,
    index: Int,
    magnetId: Long,
    lib: Library
): Pair<String, String> {
    val raw = ep.file.name.ifBlank { ep.file.path.substringAfterLast('/') }
    val year = extractYear(raw)?.toString()
    val cleaned = cleanCollectionFilmTitle(raw)
    val partTitle = if (baseTitle.isNotBlank() && cleaned.startsWith(baseTitle, ignoreCase = true)) {
        cleaned.substring(baseTitle.length).trim(' ', '-', ':', '·', '.', '–')
    } else ""
    val title = when {
        partTitle.isNotBlank() -> partTitle
        !ep.title.isNullOrBlank() -> cleanCollectionFilmTitle(ep.title!!)
        else -> cleaned.ifBlank { tr("library.film_p", "p" to index + 1) }
    }

    val watch = lib.watchInfo(watchKey(magnetId, ep.file))
    val durMs = watch?.len?.takeIf { it > 0 }
    val durStr = if (durMs != null) clock(durMs) else null
    val details = listOfNotNull(year, durStr ?: formatBytes(ep.file.size)).joinToString(" • ")
    return title to details
}

/** Play dialog: one «Смотреть» through our server (the direct AllDebrid link stays on the server), styled for series and movie trilogies. */
@Composable
internal fun PlayDialog(t: PlayTarget, lib: Library, onDismiss: () -> Unit, onPlay: (PlayRequest) -> Unit) {
    val item = remember(t) { t.item ?: lib.items.find { it.m.id == t.magnetId } }
    val magnet = remember(t, item) {
        item?.m ?: Magnet(t.magnetId, t.label, "", 4, 0L, 0L, listOf(t.file) + t.following, false)
    }
    val playable = remember(t, item) {
        item?.playable ?: (if (t.following.isNotEmpty()) Playable.Series(listOf(Season(1, (listOf(t.file) + t.following).mapIndexed { i, f -> Episode(1, i + 1, f) }))) else Playable.Movie(t.file))
    }
    val series = playable as? Playable.Series
    // the same rule as the tile and the banner (shared isCollection), not a copy of its own
    val isCollection = remember(playable, magnet) { series != null && series.seasons.isNotEmpty() && ru.suslikovanora.media.core.isCollection(playable, magnet) }

    // State for Trilogy / Collection mode
    val films = remember(series) { series?.seasons?.flatMap { it.items } ?: emptyList() }
    val initFilmIdx = remember(t, films) {
        val idx = films.indexOfFirst { it.file.path == t.file.path }
        if (idx >= 0) idx else 0
    }
    var selectedFilmIdx by remember(t) { mutableStateOf(initFilmIdx) }

    // State for Series mode
    val seasons = remember(series) { series?.seasons ?: emptyList() }
    val (initSIdx, initEIdx) = remember(t, seasons) {
        var s = 0
        var e = 0
        seasons.forEachIndexed { sIdx, season ->
            val idx = season.items.indexOfFirst { it.file.path == t.file.path }
            if (idx >= 0) { s = sIdx; e = idx }
        }
        s to e
    }
    var selectedSeasonIdx by remember(t) { mutableStateOf(initSIdx) }
    var selectedEpisodeIdx by remember(t) { mutableStateOf(initEIdx) }

    // Active file, title and following playlist files
    val currentSeason = seasons.getOrNull(selectedSeasonIdx) ?: seasons.firstOrNull()
    val currentEpisode = currentSeason?.items?.getOrNull(selectedEpisodeIdx) ?: currentSeason?.items?.firstOrNull()
    val currentFilm = films.getOrNull(selectedFilmIdx) ?: films.firstOrNull()

    val currentFile: MFile = when {
        isCollection && currentFilm != null -> currentFilm.file
        series != null && currentEpisode != null -> currentEpisode.file
        else -> t.file
    }

    val currentTitle: String = when {
        isCollection && currentFilm != null -> {
            val (fTitle, _) = extractTrilogyFilmInfo(item?.baseTitle ?: "", currentFilm, selectedFilmIdx, t.magnetId, lib)
            (item?.baseTitle ?: t.label) + " • " + fTitle
        }
        series != null && currentSeason != null && currentEpisode != null -> {
            "${item?.baseTitle ?: t.label.substringBefore(" · ")} • S${currentSeason.season.toString().padStart(2, '0')}E${currentEpisode.episode.toString().padStart(2, '0')}"
        }
        else -> item?.baseTitle ?: t.label
    }

    // Discord: the title the library shows and the season / episode, not the file name of the torrent
    fun presenceOf(f: MFile): Presence {
        val base = item?.baseTitle ?: t.label.substringBefore(" · ")
        if (isCollection) {
            val i = films.indexOfFirst { it.file.path == f.path }
            val film = films.getOrNull(i) ?: return Presence(base)
            return Presence(extractTrilogyFilmInfo(item?.baseTitle ?: "", film, i, t.magnetId, lib).first.ifBlank { base })
        }
        val (s, e) = seasons.firstNotNullOfOrNull { s -> s.items.find { it.file.path == f.path }?.let { s to it } } ?: return Presence(base)
        return Presence(base, if (s.season == 0) tr("discord.specvypusk", "e" to e.episode) else tr("discord.sezon_seriya", "s" to s.season, "e" to e.episode))
    }

    // The player's queue: the whole season (or collection) with links, so «previous» works from the episode it started
    // with (it used to be this episode and the following ones only: back from the first one just went to 00:00).
    val followingFiles: List<MFile> = when {
        isCollection -> films.map { it.file }
        series != null && currentSeason != null -> currentSeason.items.map { it.file }
        else -> emptyList()
    }.filter { it.link != null }
    val queueStart = followingFiles.indexOfFirst { it.path == currentFile.path }.coerceAtLeast(0)

    val resumeAt = remember(t.magnetId, currentFile.path, lib.watchVersion) {
        lib.watchInfo(watchKey(t.magnetId, currentFile))?.takeIf { !it.watched && it.pos >= 5_000L }?.pos
    }
    var fromStart by remember(t.magnetId, currentFile.path) { mutableStateOf(false) }
    var sub by remember(t.magnetId, currentFile.path) { mutableStateOf(tr("library.gotovim")) }
    var loading by remember(t.magnetId, currentFile.path) { mutableStateOf(true) }
    var mirror by remember(t.magnetId, currentFile.path) { mutableStateOf<String?>(null) }
    var playlist by remember(t.magnetId, currentFile.path) { mutableStateOf<String?>(null) }
    var subs by remember(t.magnetId, currentFile.path) { mutableStateOf<List<String>>(emptyList()) }
    var audio by remember(t.magnetId, currentFile.path) { mutableStateOf<List<String>>(emptyList()) }

    var poster by remember(t.magnetId) { mutableStateOf(item?.let { lib.cachedPoster(it.posterQueries) }) }
    LaunchedEffect(t.magnetId) {
        if (poster == null && item != null) poster = lib.poster(item.posterQueries)
    }

    LaunchedEffect(t.magnetId, currentFile.path) {
        val link = currentFile.link ?: run {
            sub = tr("library.ssylka_na_fayl_esche"); loading = false
            return@LaunchedEffect
        }
        val enc = { v: String -> URLEncoder.encode(v, "UTF-8") }
        val name = currentFile.name.ifBlank { currentFile.path.substringAfterLast('/') }
        // Everything is fetched before the "Watch" buttons turn on: a click while side voice-overs, subtitles
        // or the playlist were still on their way opened the film without them.
        coroutineScope {
            val mr = async { runCatching { Api.get("/api/file/mirror-url?link=${enc(link)}&name=${enc(name)}").optString("url").ifBlank { null } } }
            val sb = async { mirrorUrls(subtitlesFor(magnet, currentFile)) }
            val au = async { mirrorUrls(externalAudioFor(magnet, currentFile)) }
            val m = mr.await().getOrNull()
            val validFollowing = followingFiles.filter { it.link != null }
            val pl = if (m != null && validFollowing.size > 1) runCatching {
                val items = JSONArray()
                validFollowing.forEach { items.put(JSONObject().put("index", it.index).put("link", it.link)) }
                Api.post("/api/playlist", JSONObject().put("magnetId", t.magnetId).put("title", currentTitle).put("items", items)).optString("url").ifBlank { null }
            }.getOrNull() else null
            subs = sb.await()
            audio = au.await()
            playlist = pl
            mirror = m
            loading = false
        }
        sub = if (mirror != null) tr("library.mozhno_smotret") else tr("library.ne_udalos_otkryt")

        lib.saveEpisode(t.magnetId, currentFile.path)
    }

    val scope = rememberCoroutineScope()
    val live = lib.items.find { it.m.id == t.magnetId } ?: item
    var hero by remember(t.magnetId) { mutableStateOf(item?.let { lib.cachedHero(it.posterQueries) }) }
    LaunchedEffect(t.magnetId) { if (hero == null && item != null) hero = lib.hero(item.posterQueries) }
    val info = remember(t.magnetId, currentFile.path, lib.watchVersion) { lib.watchInfo(watchKey(t.magnetId, currentFile)) }
    val extraDetails = (if (audio.isNotEmpty()) tr("library.ozvuchek_size", "size" to audio.size) else "") + (if (subs.isNotEmpty()) tr("library.subtitrov_size", "size" to subs.size) else "")
    val statusText = (if (mirror != null && !loading) tr("library.det_mozhno_smotret") else sub) + extraDetails

    var shown by remember(t) { mutableStateOf(false) }
    LaunchedEffect(t) { shown = true }
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(260, easing = Sm.ease))

    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = a }.background(Sm.scrim)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        val dialogWidth = if (isCollection) 660.dp else if (series != null && currentSeason != null && currentEpisode != null) 540.dp else 620.dp
        Column(
            Modifier.graphicsLayer { translationY = (1f - a) * 24f; scaleX = 0.97f + 0.03f * a; scaleY = 0.97f + 0.03f * a }
                .widthIn(max = dialogWidth).glassSheet(RoundedCornerShape(22.dp))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                .padding(22.dp)
        ) {
            if (isCollection) {
                // ---- SCREEN 2: MOVIE TRILOGY / COLLECTION DESIGN ----
                val filmCount = films.size
                val collectionTypeName = tr(collectionKind(filmCount, magnet.name).trKey)
                val years = films.mapNotNull { ep -> extractYear(ep.file.name.ifBlank { ep.file.path.substringAfterLast('/') }) }
                val yearsRange = if (years.isNotEmpty()) {
                    val min = years.minOrNull()!!
                    val max = years.maxOrNull()!!
                    if (min == max) " • $min" else " • $min–$max"
                } else ""
                val headerSubtitle = "$collectionTypeName • ${filmCountRu(filmCount)}$yearsRange"

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    // Left: Movie Poster
                    Box(
                        Modifier.width(155.dp).height(245.dp).clip(RoundedCornerShape(14.dp))
                            .background(Sm.glassFill).border(1.dp, Sm.glassEdge, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        val p = poster
                        if (p != null) {
                            Image(p, tr("library.poster"), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Sm.glassEdge, Color(0xFF10131B)))))
                        }
                    }

                    // Right: Title, Subtitle, Info Box, Movie Selection List
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(item?.let(lib::titleOf) ?: t.label, fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                                Text(headerSubtitle, color = Color(0xFF8E96A8), fontFamily = Onest, fontSize = 13.5.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                            CloseCircleButton(onClick = onDismiss)
                        }

                        // Info banner
                        Row(
                            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp).clip(RoundedCornerShape(10.dp))
                                .background(Sm.glassFill).border(1.dp, Sm.glassEdge, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            InfoCircleIcon(color = Color(0xFF60A5FA), size = 15.dp)
                            Text(tr("library.naydena_p_vyberite_film", "p" to collectionTypeName.lowercase(Locale.ROOT)), color = Color(0xFFC0C7D6), fontFamily = Onest, fontSize = 12.5.sp)
                        }

                        // Movies list (scrollable)
                        val filmScrollState = rememberScrollState()
                        LaunchedEffect(initFilmIdx) {
                            if (initFilmIdx > 2) {
                                filmScrollState.scrollTo(initFilmIdx * 60)
                            }
                        }
                        Box(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                            Column(
                                Modifier.fillMaxWidth()
                                    .verticalScroll(filmScrollState)
                                    .padding(end = if (films.size > 3) 10.dp else 0.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                films.forEachIndexed { i, ep ->
                                    val (fTitle, fDetails) = extractTrilogyFilmInfo(item?.baseTitle ?: "", ep, i, t.magnetId, lib)
                                    val isSelected = i == selectedFilmIdx
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                            .background(if (isSelected) Sm.accentSoft else Sm.glassFill)
                                            .border(1.dp, if (isSelected) Sm.accentEdge else Sm.glassEdge, RoundedCornerShape(12.dp))
                                            .hoverClick(remember { MutableInteractionSource() }) { selectedFilmIdx = i }
                                            .padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        // Mini poster / icon
                                        Box(
                                            Modifier.size(width = 34.dp, height = 44.dp).clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFF181D2A)).border(1.dp, Color(0xFF262E40), RoundedCornerShape(6.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            val p = poster
                                            if (p != null) {
                                                Image(p, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                            } else {
                                                PlayTriangle(color = Color(0xFF8E96A8), size = 12.dp)
                                            }
                                        }

                                        // Number badge
                                        Box(
                                            Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1D2638)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("${i + 1}", color = Color.White, fontFamily = Onest, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }

                                        // Title & details
                                        Column(Modifier.weight(1f)) {
                                            Text(fTitle, color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(fDetails, color = Color(0xFF7E879B), fontFamily = Onest, fontSize = 12.sp, maxLines = 1)
                                        }

                                        // Radio button indicator
                                        Box(
                                            Modifier.size(20.dp).clip(CircleShape).border(1.5.dp, if (isSelected) Color(0xFF4A80F5) else Color(0xFF384359), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isSelected) {
                                                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF4A80F5)))
                                            }
                                        }
                                    }
                                }
                            }

                            if (films.size > 3) {
                                VerticalScrollbar(
                                    adapter = rememberScrollbarAdapter(filmScrollState),
                                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 2.dp),
                                    style = defaultScrollbarStyle().copy(
                                        unhoverColor = Color(0x338E96A8),
                                        hoverColor = Color(0x888E96A8),
                                        shape = RoundedCornerShape(4.dp),
                                        thickness = 5.dp
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                resumeAt?.let { at ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StartOption(tr("library.s_p", "p" to clock(at)), tr("library.prodolzhit_s_proshlogo_mesta"), !fromStart, Modifier.weight(1f)) { fromStart = false }
                        StartOption(tr("library.s_nachala"), tr("library.smotret_zanovo"), fromStart, Modifier.weight(1f)) { fromStart = true }
                    }
                }

                WatchButton(
                    url = mirror,
                    loading = loading,
                    onPlay = { url ->
                        val list = followingFiles.filter { f -> f.link != null }
                        val keys = if (playlist != null) list.map { f -> watchKey(t.magnetId, f) } else listOf(watchKey(t.magnetId, currentFile))
                        val resolve: suspend (Int) -> Pair<List<String>, List<String>> = { i ->
                            list.getOrNull(i)?.let { f ->
                                val aud = externalAudioFor(magnet, f)
                                val sb = subtitlesFor(magnet, f)
                                mirrorUrls(aud) to mirrorUrls(sb)
                            } ?: (emptyList<String>() to emptyList())
                        }
                        onPlay(PlayRequest(url, currentTitle, playlist, subs, keys, audio, if (playlist != null) resolve else null, fromStart,
                            posterQueries = item?.posterQueries.orEmpty(), startIndex = if (playlist != null) queueStart else 0,
                            presence = if (playlist != null) list.map(::presenceOf) else listOf(presenceOf(currentFile))))
                    },
                )

                Spacer(Modifier.height(14.dp))
                CloseBottomButton(onClick = onDismiss)

            } else if (series != null && currentSeason != null && currentEpisode != null) {
                // ---- SERIES: the same card as a movie, plus the season / episode pickers ----
                DetailHeader(poster, item?.let(lib::titleOf) ?: t.label, item, hero, info?.len, onDismiss)
                DetailStatus(statusText)
                DetailStartRow(resumeAt, info, fromStart) { fromStart = it }

                WatchButton(
                    url = mirror,
                    loading = loading,
                    onPlay = { url ->
                        val list = followingFiles.filter { f -> f.link != null }
                        val keys = if (playlist != null) list.map { f -> watchKey(t.magnetId, f) } else listOf(watchKey(t.magnetId, currentFile))
                        val resolve: suspend (Int) -> Pair<List<String>, List<String>> = { i ->
                            list.getOrNull(i)?.let { f ->
                                val aud = externalAudioFor(magnet, f)
                                val sb = subtitlesFor(magnet, f)
                                mirrorUrls(aud) to mirrorUrls(sb)
                            } ?: (emptyList<String>() to emptyList())
                        }
                        onPlay(PlayRequest(url, currentTitle, playlist, subs, keys, audio, if (playlist != null) resolve else null, fromStart,
                            posterQueries = item?.posterQueries.orEmpty(), startIndex = if (playlist != null) queueStart else 0,
                            presence = if (playlist != null) list.map(::presenceOf) else listOf(presenceOf(currentFile))))
                    },
                )

                val seasonItems = seasons.mapIndexed { idx, s ->
                    val sLabel = if (s.season == 0) tr("library.spec_materialy") else tr("library.season_sezon", "season" to s.season)
                    sLabel to {
                        selectedSeasonIdx = idx
                        selectedEpisodeIdx = 0
                    }
                }
                val episodeItems = currentSeason.items.mapIndexed { idx, ep ->
                    val epLabel = if (ep.title != null) "${ep.episode}. ${ep.title}" else tr("library.episode_seriya", "episode" to ep.episode)
                    epLabel to {
                        selectedEpisodeIdx = idx
                    }
                }

                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val currentSLabel = if (currentSeason.season == 0) tr("library.spec") else tr("library.season_sezon", "season" to currentSeason.season)
                    val currentELabel = tr("library.episode_seriya", "episode" to currentEpisode.episode)
                    DropdownSelector(label = tr("library.sezon"), valueText = currentSLabel, modifier = Modifier.weight(1f), items = seasonItems)
                    DropdownSelector(label = tr("library.seriya"), valueText = currentELabel, modifier = Modifier.weight(1f), items = episodeItems)
                }

                ShelfRow(live, lib, scope)

            } else {
                // ---- SINGLE MOVIE DESIGN: poster, facts, resume / from start, one «Смотреть», two shelf buttons ----
                DetailHeader(poster, currentTitle, item, hero, info?.len, onDismiss)
                DetailStatus(statusText)
                DetailStartRow(resumeAt, info, fromStart) { fromStart = it }

                WatchButton(
                    url = mirror,
                    loading = loading,
                    onPlay = { url ->
                        onPlay(PlayRequest(url, currentTitle, null, subs, listOf(watchKey(t.magnetId, currentFile)), audio, fromStart = fromStart, posterQueries = item?.posterQueries.orEmpty(),
                            presence = listOf(presenceOf(currentFile))))
                    },
                )

                ShelfRow(live, lib, scope)
            }
        }
    }
}


@Composable
private fun DetailHeader(
    poster: androidx.compose.ui.graphics.ImageBitmap?, title: String, item: LibraryItem?, hero: Library.HeroInfo?, lenMs: Long?, onDismiss: () -> Unit
) {
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(
                Modifier.width(150.dp).height(225.dp).clip(RoundedCornerShape(14.dp))
                    .background(Sm.glassFill).border(1.dp, Sm.glassEdge, RoundedCornerShape(14.dp))
            ) {
                if (poster != null) Image(poster, tr("library.poster"), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Sm.glassEdge, Color(0xFF10131B)))))
            }
            Column(Modifier.weight(1f).padding(end = 40.dp)) {
                Text(title, fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    @Composable fun chip(text: String) = Text(text, color = Color(0xFFD6DBE4), fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x1AFFFFFF)).border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
                    chip(hero?.year ?: item?.let { itemYear(it) } ?: "")
                    item?.let { chip(itemTypeLabel(it)) }
                    lenMs?.takeIf { it > 0 }?.let { chip(durationText(it)) }
                    hero?.rating?.let { r ->
                        Text("★ " + "%.1f".format(Locale.US, r), color = Color(0xFFFACC15), fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    }
                }
                hero?.overview?.let {
                    Text(it, color = Color(0xFFC3C9D6), fontFamily = Onest, fontSize = 14.sp, lineHeight = 21.sp, maxLines = 5, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 14.dp))
                }
            }
        }
        Box(Modifier.align(Alignment.TopEnd)) { CloseCircleButton(onClick = onDismiss) }
    }
}

@Composable
private fun DetailStatus(text: String) {
    Text(text, color = Color(0xFF8E96A8), fontFamily = Onest, fontSize = 13.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
}

@Composable
private fun DetailStartRow(resumeAt: Long?, info: WatchInfo?, fromStart: Boolean, setFromStart: (Boolean) -> Unit) {
    if (resumeAt == null) return
    // one height for both cards (the first one has the progress bar)
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DetailStartOption(
            tr("library.det_s_p", "p" to clock(resumeAt)), tr("library.prodolzhit_s_proshlogo_mesta"), !fromStart, Modifier.weight(1f).fillMaxHeight(),
            progress = info?.fraction, icon = { PlayTriangle(color = Color.White, size = 14.dp) }
        ) { setFromStart(false) }
        DetailStartOption(
            tr("library.det_s_nachala"), tr("library.det_smotret_zanovo"), fromStart, Modifier.weight(1f).fillMaxHeight(),
            progress = null, icon = { Text("↺", modifier = Modifier.rotate(-90f), color = Color.White, fontSize = 20.sp) }
        ) { setFromStart(true) }
    }
}

@Composable
private fun ShelfRow(live: LibraryItem?, lib: Library, scope: kotlinx.coroutines.CoroutineScope) {
    val isMine = live?.m?.mine == true
    val isFav = live?.m?.favorite == true
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ShelfButton(
            if (isMine) tr("library.det_iz_moey_mediateki") else tr("library.det_v_moyu_mediateku"), isMine, Modifier.weight(1f),
            icon = { BookmarkIcon(filled = isMine, color = if (isMine) Color(0xFF7FB2FF) else Color(0xFFC4CBD8), size = 16.dp) }
        ) { live?.let { scope.launch { lib.setMine(it.m, !isMine) } } }
        ShelfButton(
            if (isFav) tr("library.iz_izbrannogo") else tr("library.det_v_izbrannoe"), isFav, Modifier.weight(1f),
            icon = { HeartIcon(filled = isFav, color = if (isFav) Color(0xFFFF6B8A) else Color(0xFFC4CBD8), size = 16.dp) }
        ) { live?.let { scope.launch { lib.setFavorite(it.m, !isFav) } } }
    }
}

private fun durationText(ms: Long): String {
    val min = (ms / 60_000L).toInt().coerceAtLeast(1)
    return if (min >= 60) tr("library.det_ch_min", "h" to min / 60, "m" to min % 60) else tr("library.det_min", "m" to min)
}

@Composable
private fun DetailStartOption(
    title: String, hint: String, on: Boolean, modifier: Modifier, progress: Float?, icon: @Composable () -> Unit, onClick: () -> Unit
) {
    val edge by animateColorAsState(if (on) Color(0xFF3B82F6) else Sm.glassEdge, tween(220))
    val bg by animateColorAsState(if (on) Color(0xFF12203F) else Sm.glassFill, tween(220))
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(bg).border(if (on) 1.5.dp else 1.dp, edge, RoundedCornerShape(16.dp))
            .hoverClick(remember { MutableInteractionSource() }, onClick = onClick).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(if (on) Sm.accent else Color(0xFF1D2638)), contentAlignment = Alignment.Center) { icon() }
            Column(Modifier.weight(1f)) {
                Text(title, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White, maxLines = 1)
                Text(hint, fontFamily = Onest, fontSize = 12.sp, color = Color(0xFF8E96A8), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (progress != null) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Sm.accent))
                }
                Text("${(progress * 100).toInt()}%", fontFamily = Onest, fontSize = 12.sp, color = Color(0xFFC3C9D6))
            }
        }
    }
}

@Composable
private fun ShelfButton(text: String, on: Boolean, modifier: Modifier, icon: @Composable () -> Unit, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(12.dp))
            .background(if (hovered) Color(0xFF1C2232) else if (on) Color(0xFF16203A) else Color(0xFF141824))
            .border(1.dp, if (hovered) Color(0xFF323B50) else if (on) Color(0x553B82F6) else Color(0xFF242A3A), RoundedCornerShape(12.dp))
            .hoverClick(src, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(8.dp))
        Text(text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White, maxLines = 1)
    }
}

/** The only way to watch: through our server. Off until the link, the tracks and the playlist are ready. */
@Composable
private fun WatchButton(url: String?, loading: Boolean, onPlay: (String) -> Unit) {
    PlayActionButton(text = if (loading) tr("library.gotovim") else tr("library.smotret"), enabled = url != null && !loading, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        url?.let(onPlay)
    }
}

@Composable
private fun PlayActionButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg = if (!enabled) Color(0xFF161C28) else if (hovered) Color(0xFF3B74F0) else Sm.accent
    val border = if (!enabled) Color(0xFF202736) else if (hovered) Color(0xFF4D86FF) else Sm.accentEdge
    val contentColor = if (!enabled) Color(0xFF5A6478) else Color.White

    Row(
        modifier = modifier.height(44.dp).clip(RoundedCornerShape(12.dp))
            .background(bg).border(1.dp, border, RoundedCornerShape(12.dp))
            .hoverClick(src, enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlayTriangle(color = contentColor, size = 12.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = contentColor)
    }
}

@Composable
private fun DropdownSelector(
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
    items: List<Pair<String, () -> Unit>>
) {
    var expanded by remember { mutableStateOf(false) }
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()

    Column(modifier) {
        Text(label, color = Color(0xFF8E96A8), fontFamily = Onest, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
        Box {
            Row(
                Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (hovered) Color(0xFF161A26) else Sm.glassFill)
                    .border(1.dp, if (hovered) Color(0xFF323B50) else Sm.glassEdge, RoundedCornerShape(12.dp))
                    .hoverClick(src) { expanded = true }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(valueText, color = Color.White, fontFamily = Onest, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ChevronDownIcon(color = Color(0xFF8E96A8), size = 13.dp)
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Color(0xFF181D2A)).border(1.dp, Color(0xFF2A3345), RoundedCornerShape(10.dp))
            ) {
                items.forEach { (text, onSelect) ->
                    DropdownMenuItem(
                        text = { Text(text, color = Color.White, fontFamily = Onest, fontSize = 13.5.sp) },
                        onClick = {
                            expanded = false
                            onSelect()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CloseCircleButton(onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Box(
        Modifier.size(32.dp).clip(CircleShape)
            .background(if (hovered) Color(0xFF262F42) else Color(0xFF181E2B))
            .border(1.dp, if (hovered) Color(0xFF3B4863) else Color(0xFF262F40), CircleShape)
            .hoverClick(src, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        CloseCrossIcon(color = if (hovered) Color.White else Color(0xFFB0B9C9), size = 11.dp)
    }
}

@Composable
private fun CloseBottomButton(onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Box(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp))
            .background(if (hovered) Color(0xFF1C2232) else Color(0xFF141824))
            .border(1.dp, if (hovered) Color(0xFF323B50) else Color(0xFF242A3A), RoundedCornerShape(12.dp))
            .hoverClick(src, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(tr("library.zakryt"), fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color.White)
    }
}

@Composable
private fun StartOption(title: String, hint: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val edge by animateColorAsState(if (on) Color(0xFF3E475C) else Sm.glassEdge, tween(220))
    val bg by animateColorAsState(if (on) Color(0xFF282E3C) else Sm.glassFill, tween(220))
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(bg).border(1.dp, edge, RoundedCornerShape(14.dp))
            .hoverClick(remember { MutableInteractionSource() }, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(title, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = Color.White, maxLines = 1)
        Text(hint, fontFamily = Onest, fontSize = 12.5.sp, color = Color(0xFF8E96A8))
    }
}

