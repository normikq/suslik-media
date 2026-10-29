package ru.suslikovanora.media.library

import ru.suslikovanora.media.ui.focusRing
import ru.suslikovanora.media.core.*

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import ru.suslikovanora.media.ui.BookmarkIcon
import ru.suslikovanora.media.ui.ChevronDownIcon
import ru.suslikovanora.media.ui.CloseCrossIcon
import ru.suslikovanora.media.ui.HeartIcon
import ru.suslikovanora.media.ui.PlayTriangle
import ru.suslikovanora.media.ui.ReplayIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import ru.suslikovanora.media.core.audio.CustomAudio
import ru.suslikovanora.media.core.audio.CustomTrack
import ru.suslikovanora.media.net.Api
import ru.suslikovanora.media.ui.Onest
import ru.suslikovanora.media.ui.Sm
import ru.suslikovanora.media.ui.Unbounded
import java.net.URLEncoder
import ru.suslikovanora.media.tr

/** Everything the player needs: stream, title, optional season playlist, sidecar subtitles, progress keys. */
data class PlayRequest(
    val url: String,
    val title: String,
    val playlist: String?,
    val subUrls: List<String>,
    val watchKeys: List<String>,
    val audioUrls: List<String> = emptyList(),
    /** «С начала» chosen in the sheet: ignore the remembered place this time. */
    val fromStart: Boolean = false,
    /** The playlist's other episodes' subtitles and voice-overs (JSON, see [episodeSidesJson]); the player signs them. */
    val episodeSides: String? = null,
    /** Uploaded voice-overs: `{"<signed url>": {"title", "offsetMs"}}` (see player's CustomAudioInfo). */
    val customAudio: String? = null,
    /** Watch key of the chosen episode ([watchKeys] is the whole season); null — the first of [watchKeys]. */
    val watchKey: String? = null,
    /** The playlist is the films of a collection (shared isCollection): the player says «Фильмы», not «Серии». */
    val collection: Boolean = false,
    /** Intro and credits from the server: JSON array along [watchKeys] (player.PlayerExtras.MARKERS). */
    val markers: String? = null,
    /** Where to open (ms) instead of the remembered place (player.PlayerExtras.START_MS); null — as usual. */
    val startMs: Long? = null
)

/**
 * What the player asks the server for the intro and credits of each episode (core.MarkersDetector.serverPath): the
 * original title (the AI's once accepted, otherwise the release's), the library's title, season and episode; for a film
 * of a collection — its own name and year, without an episode.
 */
private fun markersJson(item: LibraryItem, series: Playable.Series?, files: List<MFile>, collection: Boolean): String {
    val original = item.m.aiTitle?.takeIf { it.accepted }?.original?.takeIf { it.isNotBlank() } ?: cleanTitle(item.m.name)
    val out = org.json.JSONArray()
    if (series == null) {
        val nameWithYear = MarkersDetector.filmTitle(files.firstOrNull()?.name?.ifBlank { item.m.name } ?: item.m.name, item.baseTitle.ifBlank { original })
        out.put(org.json.JSONObject().put("title", nameWithYear ?: original).put("alt", item.baseTitle))
        return out.toString()
    }
    for (f in files) {
        val hit = series.seasons.firstNotNullOfOrNull { s -> s.items.find { it.file.path == f.path }?.let { s to it } }
        if (collection) {
            val t = hit?.let { (_, e) -> MarkersDetector.filmTitle(f.name.ifBlank { f.path.substringAfterLast('/') }, e.title) }
            out.put(t?.let { org.json.JSONObject().put("title", it) } ?: org.json.JSONObject.NULL)
            continue
        }
        out.put(hit?.let { (s, e) -> org.json.JSONObject().put("title", original).put("alt", item.baseTitle).put("season", s.season).put("episode", e.episode) }
            ?: org.json.JSONObject.NULL)
    }
    return out.toString()
}

/**
 * One way to watch: through our server (for series a playlist with the rest of the season). The direct AllDebrid
 * link stays on the server (security audit 25.10). The sheet looks like the PC's: poster and facts on top, where the
 * film was left, «Смотреть», for a series the season and episode pickers, and the two shelf buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaySheet(
    initial: PlayTarget,
    store: LibraryStore,
    onDismiss: () -> Unit,
    /** Set: no choice at all — a room's guest opens the host's file at the host's place as soon as the links are ready. */
    autoStartMs: (() -> Long)? = null,
    onPlay: (PlayRequest) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val item = store.items.firstOrNull { it.m.id == initial.magnetId }
    val series = item?.playable as? Playable.Series
    val isColl = item != null && isCollection(item.playable, item.m)
    val films = remember(series, isColl) {
        if (isColl && series != null) series.seasons.flatMap { it.items } else emptyList()
    }
    // The films' names for the picker: computed once per sheet, not on every recomposition
    val filmNames = remember(films) {
        films.mapIndexed { idx, ep ->
            val raw = ep.title?.takeIf { it.isNotBlank() } ?: collectionFileTitle(item!!.m, ep.file, item.baseTitle)
            val stripped = cleanCollectionFilmTitle(raw)
            when {
                stripped.isNotBlank() && stripped != "${idx + 1}" -> stripped
                raw.isNotBlank() && raw != "${idx + 1}" -> raw
                else -> tr("library.film_p", "p" to idx + 1)
            }
        }
    }
    val start = remember(initial, isColl, films) {
        var si = 0; var ei = 0
        if (isColl && films.isNotEmpty()) {
            val k = films.indexOfFirst { it.file.path == initial.file.path }
            if (k >= 0) ei = k
        } else {
            series?.seasons?.forEachIndexed { i, s ->
                val k = s.items.indexOfFirst { it.file.path == initial.file.path }
                if (k >= 0) { si = i; ei = k }
            }
        }
        si to ei
    }
    var seasonIdx by remember(initial) { mutableStateOf(start.first) }
    var episodeIdx by remember(initial) { mutableStateOf(start.second) }
    // The episode picked in the sheet is the one that plays (the same target the tile would build for it)
    val target = remember(initial, item?.m?.id, seasonIdx, episodeIdx, isColl) {
        val e = if (isColl && films.isNotEmpty()) films.getOrNull(episodeIdx)
                else series?.seasons?.getOrNull(seasonIdx)?.items?.getOrNull(episodeIdx)
        if (item != null && e != null) targetFor(item, e.file) ?: initial else initial
    }

    var sub by remember(target) { mutableStateOf(tr("play.gotovim")) }
    var mirror by remember(target) { mutableStateOf<String?>(null) }
    var playlist by remember(target) { mutableStateOf<String?>(null) }
    var preparingPlaylist by remember(target) { mutableStateOf(false) }
    var preparingExtras by remember(target) { mutableStateOf(false) }
    var subUrls by remember(target) { mutableStateOf<List<String>>(emptyList()) }
    var audioUrls by remember(target) { mutableStateOf<List<String>>(emptyList()) }
    var customByPath by remember(target) { mutableStateOf<Map<String, List<CustomTrack>>>(emptyMap()) }
    // Links, side files and the playlist are all fetched (or failed): what «Смотреть» waits for
    var prepared by remember(target) { mutableStateOf(false) }
    // Where the player stopped last time (the same place the player itself resumes from); null = nothing to resume.
    val resumeAt = remember(target) {
        context.getSharedPreferences("positions", Context.MODE_PRIVATE).getLong("w:" + watchKey(target.magnetId, target.file), 0L)
            .takeIf { it >= 5_000L }
    }
    val info = remember(target, store.watchVersion) { store.watchInfo(watchKey(target.magnetId, target.file)) }
    var fromStart by remember(target) { mutableStateOf(false) }

    val sheetQueries = remember(item, target.file.path, isColl) {
        if (item != null && isColl) continueItemPosterQueries(item, target.file) else item?.posterQueries.orEmpty()
    }
    var poster by remember(initial.magnetId, target.file.path) { mutableStateOf(item?.let { store.cachedPoster(sheetQueries) }) }
    var hero by remember(initial.magnetId, target.file.path) { mutableStateOf(item?.let { store.cachedHero(sheetQueries) }) }
    LaunchedEffect(initial.magnetId, target.file.path) {
        if (item == null) return@LaunchedEffect
        if (poster == null) poster = store.poster(sheetQueries)
        if (hero == null) hero = store.hero(sheetQueries)
    }

    LaunchedEffect(target) {
        val link = target.file.link
        if (link == null) { sub = tr("play.ssylka_na_fayl_esche"); prepared = true; return@LaunchedEffect }
        val name = target.file.name.ifBlank { target.file.path.substringAfterLast('/') }
        val enc = { v: String -> URLEncoder.encode(v, "UTF-8") }
        coroutineScope {
            val mr = runCatching { Api.get("/api/file/mirror-url?link=${enc(link)}&name=${enc(name)}").optString("url").ifBlank { null } }
            mirror = mr.getOrNull()
            if (mirror != null) {
                sub = tr("play.gotovo")
                // Subtitle files and voice-overs next to the video: the player adds them as extra tracks (signed mirror
                // links, no login needed). «Смотреть» waits for them so the film does not start without them.
                preparingExtras = true
                // Voice-overs uploaded by users from other torrents (/api/custom-audio): already signed mirror links.
                val custom = customAudio(target.magnetId)
                customByPath = custom
                if (target.subs.isNotEmpty()) {
                    subUrls = mirrorUrls(target.subs, enc)
                    if (subUrls.isNotEmpty()) sub = tr("play.gotovo_subtitrov", "size" to subUrls.size)
                }
                if (target.audio.isNotEmpty()) {
                    audioUrls = mirrorUrls(target.audio, enc)
                }
                audioUrls = audioUrls + custom[target.file.path].orEmpty().map { it.url }
                if (audioUrls.isNotEmpty()) sub = tr("play.sub_ozvuchek_iz_razdachi", "sub" to sub, "size" to audioUrls.size)
                preparingExtras = false
                // Series via the mirror: the whole season, the player starts at this episode.
                val season = target.season.filter { it.link != null }
                if (season.size > 1) {
                    preparingPlaylist = true
                    runCatching {
                        val items = JSONArray()
                        season.forEach { items.put(JSONObject().put("index", it.index).put("link", it.link)) }
                        Api.post("/api/playlist", JSONObject().put("magnetId", target.magnetId).put("title", target.label).put("items", items))
                            .optString("url").ifBlank { null }
                    }.onSuccess { playlist = it }
                    preparingPlaylist = false
                }
            }
            if (mirror == null) sub = mr.exceptionOrNull()?.let { Api.describe(it) } ?: tr("play.ne_udalos_otkryt")
        }
        prepared = true
    }

    fun requestFor(url: String): PlayRequest {
        store.saveEpisode(target.magnetId, target.file.path)
        val keys = if (playlist != null) target.season.filter { f -> f.link != null }.map { f -> watchKey(target.magnetId, f) }
        else listOf(watchKey(target.magnetId, target.file))
        val files = if (playlist != null) target.season.filter { f -> f.link != null } else listOf(target.file)
        return PlayRequest(url, target.label, playlist, subUrls, keys, audioUrls, fromStart, if (playlist != null) episodeSidesJson(target, customByPath) else null, customJson(customByPath), watchKey(target.magnetId, target.file), isColl,
            if (item == null) null else markersJson(item, series, files, isColl))
    }
    LaunchedEffect(prepared) {
        val at = autoStartMs ?: return@LaunchedEffect
        val url = mirror
        if (prepared && url != null) onPlay(requestFor(url).copy(startMs = at()))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Sm.sheet,
        contentColor = Sm.text,
        scrimColor = Sm.scrim,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp).padding(bottom = 18.dp).navigationBarsPadding()
        ) {
            if (autoStartMs != null) {
                // A room's guest: nothing to choose, the host's file opens by itself
                Text(tr("party.otkryvaem_film_vedushchego"), color = Sm.muted, fontFamily = Onest, fontSize = 13.sp)
                Text(item?.let(store::titleOf) ?: target.label, fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 23.sp,
                    color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Text(if (!prepared || mirror != null) tr("play.gotovim") else sub, fontFamily = Onest, fontSize = 13.sp, color = Sm.muted,
                    modifier = Modifier.padding(top = 12.dp, bottom = 12.dp))
                OutlineButton(tr("library.zakryt"), modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
                return@Column
            }
            // poster and facts
            Box(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier.width(104.dp).height(156.dp).clip(RoundedCornerShape(12.dp))
                            .background(Brush.verticalGradient(listOf(Sm.glassEdge, Color(0xFF10131B))))
                            .border(1.dp, Sm.glassEdge, RoundedCornerShape(12.dp))
                    ) {
                        poster?.let { url ->
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(url).memoryCacheKey(url).build(), imageLoader = Posters.loader(context),
                                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Column(Modifier.weight(1f).padding(end = 36.dp)) {
                        Text(item?.let(store::titleOf) ?: target.label, fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 23.sp, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            @Composable fun chip(t: String) = Text(t, color = Color(0xFFD6DBE4), fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, maxLines = 1, softWrap = false,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x1AFFFFFF)).border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                            (hero?.year ?: item?.let { itemYear(it) })?.let { chip(it) }
                            item?.let { chip(itemTypeLabel(it)) }
                            if (isColl && films.isNotEmpty()) {
                                chip(filmCountRu(films.size))
                            }
                            info?.len?.takeIf { it > 0 }?.let { chip(durationText(it)) }
                            hero?.rating?.let { r -> Text("★ " + "%.1f".format(java.util.Locale.US, r), color = Color(0xFFFACC15), fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp) }
                        }
                        hero?.overview?.let {
                            Text(it, color = Color(0xFFC3C9D6), fontFamily = Onest, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }
                Box(
                    Modifier.align(Alignment.TopEnd).size(32.dp).clip(CircleShape).background(Color(0xFF181E2B))
                        .border(1.dp, Color(0xFF262F40), CircleShape).focusRing(CircleShape, color = Color.White).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) { CloseCrossIcon(color = Color(0xFFB0B9C9), size = 11.dp) }
            }

            Text(sub, fontFamily = Onest, fontSize = 13.sp, color = Sm.muted, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            resumeAt?.let { at ->
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StartOption(
                        title = tr("play.det_s_p", "p" to clock(at)),
                        hint = tr("play.prodolzhit"),
                        on = !fromStart,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        progress = info?.fraction,
                        icon = { PlayTriangle(size = 14.dp) }
                    ) { fromStart = false }
                    StartOption(
                        title = tr("play.det_s_nachala"),
                        hint = tr("play.zanovo"),
                        on = fromStart,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        progress = null,
                        icon = { ReplayIcon(size = 16.dp) }
                    ) { fromStart = true }
                }
            }
            val busy = preparingPlaylist || preparingExtras
            LampButton(
                when { preparingPlaylist -> tr("play.gotovim_pleylist"); busy -> tr("play.gotovim_dorozhki"); else -> tr("add.smotret") },
                enabled = mirror != null && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)
            ) {
                mirror?.let { onPlay(requestFor(it)) }
            }

            if (isColl && films.isNotEmpty()) {
                val filmItems = filmNames.mapIndexed { idx, name -> "${idx + 1}. $name" to { seasonIdx = 0; episodeIdx = idx } }
                val currentName = filmNames.getOrNull(episodeIdx) ?: filmNames.first()
                Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    SheetSelector(tr("library.film"), "${episodeIdx + 1}. $currentName", Modifier.fillMaxWidth(), filmItems)
                }
            } else if (series != null) {
                val seasonItems = series.seasons.mapIndexed { idx, s ->
                    (if (s.season == 0) tr("library.spec_materialy") else tr("library.season_sezon", "season" to s.season)) to { seasonIdx = idx; episodeIdx = 0 }
                }
                val cs = series.seasons.getOrNull(seasonIdx) ?: series.seasons.first()
                val episodeItems = cs.items.mapIndexed { idx, ep ->
                    (if (ep.title != null) "${ep.episode}. ${ep.title}" else tr("library.episode_seriya", "episode" to ep.episode)) to { episodeIdx = idx }
                }
                val ce = cs.items.getOrNull(episodeIdx) ?: cs.items.first()
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SheetSelector(tr("library.sezon"), if (cs.season == 0) tr("library.spec") else tr("library.season_sezon", "season" to cs.season), Modifier.weight(1f), seasonItems)
                    SheetSelector(tr("library.seriya"), tr("library.episode_seriya", "episode" to ce.episode), Modifier.weight(1f), episodeItems)
                }
            }

            if (item != null) {
                val isMine = item.m.mine
                val isFav = item.m.favorite
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ShelfButton(
                        if (isMine) tr("play.det_iz_moey_mediateki") else tr("play.det_v_moyu_mediateku"), isMine, Modifier.weight(1f),
                        icon = { BookmarkIcon(filled = isMine, color = if (isMine) Color(0xFF7FB2FF) else Color(0xFFC4CBD8), size = 16.dp) }
                    ) { scope.launch { store.setMine(item.m, !isMine) } }
                    ShelfButton(
                        if (isFav) tr("play.det_iz_izbrannogo") else tr("play.det_v_izbrannoe"), isFav, Modifier.weight(1f),
                        icon = { HeartIcon(filled = isFav, color = if (isFav) Color(0xFFFF6B8A) else Color(0xFFC4CBD8), size = 16.dp) }
                    ) { scope.launch { store.setFavorite(item.m, !isFav) } }
                }
            }
        }
    }
}

private fun durationText(ms: Long): String {
    val min = (ms / 60_000L).toInt().coerceAtLeast(1)
    return if (min >= 60) tr("play.det_ch_min", "h" to min / 60, "m" to min % 60) else tr("play.det_min", "m" to min)
}

/** «С 04:14 · продолжить» / «С начала · заново»: two halves of one choice, the chosen one lit; the first shows how far the film went. */
@Composable
private fun StartOption(
    title: String,
    hint: String,
    on: Boolean,
    modifier: Modifier,
    progress: Float?,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    val edge by animateColorAsState(if (on) Color(0xFF3B82F6) else Sm.glassEdge, tween(220))
    val bg by animateColorAsState(if (on) Color(0xFF12203F) else Sm.glassFill, tween(220))
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(if (on) 1.5.dp else 1.dp, edge, RoundedCornerShape(16.dp))
            .focusRing(RoundedCornerShape(16.dp), color = Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (on) Sm.accent else Color(0xFF1D2638)),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontFamily = Onest,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.5.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    hint,
                    fontFamily = Onest,
                    fontSize = 12.sp,
                    color = if (on) Color(0xFF93C5FD) else Sm.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (progress != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x33FFFFFF))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(Sm.accent)
                    )
                }
                Text("${(progress * 100).toInt()}%", fontFamily = Onest, fontSize = 11.5.sp, color = Color(0xFFC3C9D6))
            }
        }
    }
}

@Composable
private fun ShelfButton(text: String, on: Boolean, modifier: Modifier, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(12.dp))
            .background(if (on) Color(0xFF16203A) else Color(0xFF141824))
            .border(1.dp, if (on) Color(0x553B82F6) else Color(0xFF242A3A), RoundedCornerShape(12.dp))
            .focusRing(RoundedCornerShape(12.dp), color = Color.White)
            .clickable(onClick = onClick).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(7.dp))
        Text(text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A field with a drop-down list (season / episode). */
@Composable
private fun SheetSelector(label: String, value: String, modifier: Modifier, items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 6.dp))
        Box {
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(Sm.glassFill)
                    .border(1.dp, Sm.glassEdge, RoundedCornerShape(12.dp)).focusRing(RoundedCornerShape(12.dp), color = Color.White)
                    .clickable { open = true }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(value, color = Color.White, fontFamily = Onest, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                ChevronDownIcon(color = Sm.muted, size = 13.dp)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Color(0xFF181D2A))) {
                items.forEach { (text, pick) ->
                    DropdownMenuItem(text = { Text(text, color = Color.White, fontFamily = Onest, fontSize = 13.5.sp) }, onClick = { open = false; pick() })
                }
            }
        }
    }
}

private fun clock(ms: Long): String = ru.suslikovanora.media.core.clockText(ms)

/** `{"<episode link>": {"subs": [{"link", "name"}], "audio": [...]}}` for the playlist's other episodes; null if none. */
private fun episodeSidesJson(target: PlayTarget, custom: Map<String, List<CustomTrack>>): String? {
    val customFor = target.season.filter { it.link != null && it.path != target.file.path && custom[it.path] != null }
    if (target.episodeSides.isEmpty() && customFor.isEmpty()) return null
    fun files(list: List<MFile>) = JSONArray().apply {
        list.forEach { f -> f.link?.let { put(JSONObject().put("link", it).put("name", f.name.ifBlank { f.path.substringAfterLast('/') })) } }
    }
    val out = JSONObject()
    target.episodeSides.forEach { (link, sides) -> out.put(link, JSONObject().put("subs", files(sides.first)).put("audio", files(sides.second))) }
    // Uploaded voice-overs come signed already: "signed" is used as is.
    customFor.forEach { f ->
        val o = out.optJSONObject(f.link!!) ?: JSONObject().also { out.put(f.link!!, it) }
        o.put("signed", JSONArray(custom[f.path]!!.map { it.url }))
    }
    return out.toString()
}

/** Signed mirror links for the files next to the video (subtitles, voice-overs); the ones that fail are left out. */
private suspend fun mirrorUrls(files: List<MFile>, enc: (String) -> String): List<String> = coroutineScope {
    files.map { f ->
        async {
            runCatching {
                Api.get("/api/file/mirror-url?link=${enc(f.link!!)}&name=${enc(f.name.ifBlank { f.path.substringAfterLast('/') })}")
                    .optString("url").ifBlank { null }
            }.getOrNull()
        }
    }.mapNotNull { it.await() }
}

/** Uploaded voice-overs of the film by its video path; empty on any error. */
private suspend fun customAudio(magnetId: Long): Map<String, List<CustomTrack>> = runCatching {
    CustomAudio.parseTracks(Api.get(CustomAudio.tracksPath(magnetId))).filter { it.url.startsWith("https://") }.groupBy { it.videoPath }
}.getOrDefault(emptyMap())

/** Name and delay of every uploaded voice-over by its signed link; null if there are none. */
private fun customJson(custom: Map<String, List<CustomTrack>>): String? = CustomAudio.infoJson(custom.values.flatten())
