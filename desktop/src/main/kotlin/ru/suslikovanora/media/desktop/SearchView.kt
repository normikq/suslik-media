package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*
import ru.suslikovanora.media.core.search.*
import ru.suslikovanora.media.desktop.trackers.LocalTrackersManager
import ru.suslikovanora.media.desktop.trackers.RuTrackerTracker
import ru.suslikovanora.media.desktop.trackers.TrackerSettings
import ru.suslikovanora.media.desktop.trackers.TrackerSettingsDialog
import ru.suslikovanora.media.desktop.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

enum class Tab(val label: String) { Library(tr("search.biblioteka")), Mine(tr("library.moi_filmy")), Search(tr("search.poisk_razdach")), Add(tr("search.magnet_torrent")), Together(tr("sidebar.sovmestnyy_prosmotr")) }

/** Pages of the top lens tabs (Together lives only in the side panel). */
val lensTabs = Tab.entries.filter { it != Tab.Together }

/**
 * The site's sliding amber "lens" tabs. [lens] is the animated tab position (0f..1f), kept by the main window so the
 * lens keeps gliding while the pages cross-fade (each page has its own copy of the header).
 */
@Composable
fun NavTabs(current: Tab, lens: Float, onSelect: (Tab) -> Unit) {
    BoxWithConstraints(
        Modifier.width(460.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF141720))
            .border(1.dp, Color(0xFF242A38), RoundedCornerShape(20.dp)).padding(3.dp).height(38.dp)
    ) {
        val w = maxWidth / lensTabs.size
        Box(Modifier.offset(x = w * lens).width(w).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Color(0xFF282E3C)))
        Row(Modifier.fillMaxSize()) {
            for (t in lensTabs) {
                val src = remember { MutableInteractionSource() }
                val hovered by src.collectIsHoveredAsState()
                val near = kotlin.math.abs(lens - t.ordinal) < 0.5f
                val fg by animateColorAsState(when { near -> Color.White; hovered -> Color(0xFFD6DBE4); else -> Color(0xFF8E96A8) }, tween(220))
                val hoverBg by animateColorAsState(if (hovered && !near) Color(0x0EFFFFFF) else Color.Transparent, tween(220))
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(hoverBg)
                        .hoverClick(src, enabled = t != current) { onSelect(t) },
                    contentAlignment = Alignment.Center
                ) { Text(t.label, color = fg, fontFamily = Onest, fontWeight = if (near) FontWeight.SemiBold else FontWeight.Medium, fontSize = 13.sp) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchView(lib: Library, header: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(true) }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var providers by remember { mutableStateOf<List<SearchProviderInfo>>(emptyList()) }
    var kind by remember { mutableStateOf("video") }
    var preset by remember { mutableStateOf(FilterPreset.ALL) }
    var resolution by remember { mutableStateOf<Resolution?>(null) }
    var sort by remember { mutableStateOf(SearchSort.DEFAULT) }
    var onlySeeded by remember { mutableStateOf(false) }
    var refine by remember { mutableStateOf("") }
    val notes = remember { mutableStateOf(mapOf<String, Pair<String, Boolean>>()) }
    var showTrackerSettings by remember { mutableStateOf(false) }
    var trackerUpdateTrigger by remember { mutableStateOf(0) }
    var activeQuery by remember { mutableStateOf("") }
    var currentPage by remember { mutableStateOf(1) }
    var pageSize by remember { mutableStateOf(25) }
    // RuTracker с ПК (фоновый Edge) бывает в разы дольше сервера: выдача сервера показывается сразу,
    // RuTracker дописывается, когда ответит. Новый поиск отменяет недописанный прошлый.
    var ruSearching by remember { mutableStateOf(false) }
    val searchJob = remember { arrayOfNulls<Job>(1) }
    val searchGen = remember { IntArray(1) }

    fun run() {
        val rawQ = query.trim()
        if (rawQ.length < 2 || busy) { status = tr("search.vvedite_minimum_simvola"); statusOk = false; return }
        val q = SearchQueryCleaner.clean(rawQ).ifBlank { rawQ }
        activeQuery = q
        currentPage = 1
        searchJob[0]?.cancel()
        val gen = ++searchGen[0]
        busy = true; ruSearching = false; status = tr("search.oprashivaem_trekery"); statusOk = true
        searchJob[0] = scope.launch {
            try {
                val t0 = System.currentTimeMillis()
                val ruDef = if (RuTrackerTracker.isReady && TrackerSettings.isEnabled("rutracker")) {
                    async { runCatching { RuTrackerTracker.search(q) } }
                } else null

                val serverRes = runCatching { Api.get("/api/search?q=" + URLEncoder.encode(q, "UTF-8")) }
                ensureActive()
                val serverHits = mutableListOf<SearchHit>()
                val serverProviders = mutableListOf<SearchProviderInfo>()
                serverRes.onSuccess { j ->
                    SearchHit.parseProviders(j.optJSONArray("providers")).filterTo(serverProviders) { TrackerSettings.isProviderEnabled(it.name) }
                    SearchHit.parseHits(j.optJSONArray("results")).filterTo(serverHits) {
                        !ReleaseParser.isNonVideo(it.title) && TrackerSettings.isSourceEnabledForHit(it.source)
                    }
                }

                /** [ruRes] == null, пока RuTracker ещё ищет (или он выключен). */
                fun publish(ruRes: Result<List<SearchHit>>?) {
                    val combinedHits = serverHits.toMutableList()
                    val combinedProviders = serverProviders.toMutableList()
                    ruRes?.onSuccess { localHits ->
                        val cleanLocalHits = localHits.filter { !ReleaseParser.isNonVideo(it.title) }
                        val existingTopics = combinedHits.mapNotNull { it.topicId }.toSet()
                        combinedHits.addAll(0, cleanLocalHits.filter { it.topicId == null || it.topicId !in existingTopics })
                        combinedProviders.add(SearchProviderInfo(name = "RuTracker (ПК)", ok = true, count = cleanLocalHits.size, error = ""))
                    }?.onFailure { err ->
                        combinedProviders.add(SearchProviderInfo(name = "RuTracker (ПК)", ok = false, count = 0, error = err.message ?: "Ошибка"))
                    }
                    val pending = ruDef != null && ruRes == null
                    hits = combinedHits
                    providers = combinedProviders
                    ruSearching = pending
                    val failed = combinedProviders.count { !it.ok }
                    val tookSec = "%.1f".format((System.currentTimeMillis() - t0) / 1000.0)
                    val wait = if (pending) " · RuTracker (ПК) ещё ищет…" else ""

                    if (serverRes.isFailure && ruRes?.isSuccess != true) {
                        status = Api.describe(serverRes.exceptionOrNull() ?: Exception("Ошибка поиска")) + wait
                        statusOk = false
                    } else {
                        status = (if (combinedHits.isEmpty()) tr("search.nichego_ne_naydeno")
                        else tr("search.naydeno_p_za_p", "total" to combinedHits.size, "sec" to tookSec) + if (failed > 0) tr("search.ne_otvetili_failed", "failed" to failed) else "") + wait
                        statusOk = combinedHits.isNotEmpty() || pending
                    }
                }

                publish(null)
                busy = false // можно искать заново, не дожидаясь RuTracker
                if (ruDef != null) {
                    val ruRes = ruDef.await()
                    ensureActive()
                    publish(ruRes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = Api.describe(e)
                statusOk = false
            } finally {
                if (searchGen[0] == gen) { busy = false; ruSearching = false }
            }
        }
    }

    fun add(h: SearchHit) {
        notes.value = notes.value + (h.id to (tr("search.dobavlyayu") to true))
        scope.launch {
            try {
                val r = if (h.localTrackerId != null) {
                    val tid = h.topicId
                    if (!h.magnet.isNullOrBlank()) {
                        Api.post("/api/magnets", JSONObject().put("magnet", h.magnet))
                    } else if (!tid.isNullOrBlank() && h.localTrackerId == "rutracker") {
                        notes.value = notes.value + (h.id to ("Скачиваю .torrent с RuTracker..." to true))
                        val torrentBytes = RuTrackerTracker.downloadTorrent(tid)
                            ?: throw IllegalStateException("Не удалось скачать .torrent с RuTracker")
                        val safeName = (h.release.cleanTitle.ifBlank { h.title })
                            .replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ ._-]"), "_").take(80) + ".torrent"
                        Api.postTorrent(torrentBytes, safeName)
                    } else {
                        throw IllegalStateException("Нет magnet-ссылки или .torrent")
                    }
                } else {
                    Api.post("/api/search/add", JSONObject().put("id", h.id))
                }
                val id = r.optLong("id")
                suspend fun ready(note: String) {
                    val rejected = rejectIfNoVideo(id)
                    notes.value = notes.value + (h.id to (if (rejected != null) rejected to false else note to true))
                    lib.refresh()
                }
                if (r.optBoolean("ready")) { ready(tr("add.gotovo_uzhe_v_biblioteke")); return@launch }
                notes.value = notes.value + (h.id to (tr("add.dobavleno_alldebrid_kachaet") to true))
                val started = System.currentTimeMillis()
                repeat(60) {
                    delay(if (System.currentTimeMillis() - started < 60_000) 4000 else 15000)
                    val st = runCatching { Api.get("/api/magnets/$id") }.getOrNull() ?: return@repeat
                    val total = st.optLong("size"); val done = st.optLong("downloaded")
                    val code = st.optInt("statusCode")
                    if (code == 4 || st.optBoolean("ready")) { ready(tr("add.gotovo_fayly_v_biblioteke")); return@launch }
                    if (code >= 5) { notes.value = notes.value + (h.id to (tr("add.oshibka_p", "p" to st.optString("status")) to false)); return@launch }
                    notes.value = notes.value + (h.id to ("${st.optString("status").ifBlank { tr("add.obrabotka") }}${if (total > 0) " · ${done * 100 / total}%" else ""}" to true))
                }
            } catch (e: Exception) { notes.value = notes.value + (h.id to (Api.describe(e) to false)) }
        }
    }

    val shown = remember(hits, kind, preset, resolution, onlySeeded, refine, sort, trackerUpdateTrigger, activeQuery) {
        SearchFilterCriteria(
            query = activeQuery,
            kind = kind,
            preset = preset,
            resolution = resolution,
            onlySeeded = onlySeeded,
            refine = refine,
            sort = sort
        ).filter(hits.filter { TrackerSettings.isSourceEnabledForHit(it.source) && !ReleaseParser.isNonVideo(it.title) })
    }

    val totalPages = maxOf(1, (shown.size + pageSize - 1) / pageSize)
    val safePage = currentPage.coerceIn(1, totalPages)
    if (safePage != currentPage) {
        currentPage = safePage
    }
    val fromIndex = if (shown.isEmpty()) 0 else (safePage - 1) * pageSize
    val toIndex = minOf(fromIndex + pageSize, shown.size)
    val pageItems = if (shown.isEmpty()) emptyList() else shown.subList(fromIndex, toIndex)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(28.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            item {
                Panel {
                    Text(tr("search.poisk_razdach"), fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 22.sp, color = Sm.text)
                    Text(tr("search.odin_zapros_srazu_po"), color = Sm.muted,
                        fontFamily = Onest, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Field(query, { query = it }, tr("search.nazvanie_filma_ili_seriala"), onDone = { run() }) }
                        Spacer(Modifier.width(10.dp))
                        LampButton(if (busy) tr("search.ischem") else tr("search.nayti"), enabled = !busy) { run() }
                        Spacer(Modifier.width(8.dp))
                        val settingsSrc = remember { MutableInteractionSource() }
                        val settingsHover by settingsSrc.collectIsHoveredAsState()
                        val isLocalActive = remember(trackerUpdateTrigger) { LocalTrackersManager.isAnyActive() }
                        Box(
                            Modifier
                                .height(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (settingsHover) Color(0xFF282E3C) else Color(0xFF1B1F2A))
                                .border(1.dp, if (isLocalActive) Sm.accent.copy(alpha = 0.7f) else if (settingsHover) Sm.line2 else Sm.line, RoundedCornerShape(12.dp))
                                .hoverClick(settingsSrc) { showTrackerSettings = true }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GearIcon(color = if (isLocalActive) Sm.accent else Sm.text, size = 15.dp)
                                Text(
                                    "Трекеры" + if (isLocalActive) " (ПК ✓)" else "",
                                    fontFamily = Onest,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp,
                                    color = if (isLocalActive) Sm.accent else Sm.text
                                )
                            }
                        }
                    }
                if (providers.isNotEmpty() || ruSearching) FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (ruSearching) Text("⋯ RuTracker (ПК) · ищем", color = Sm.muted, fontFamily = Onest, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xFF161A24))
                            .border(1.dp, Sm.line, RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
                    providers.forEach { p ->
                        Text((if (p.ok) "✓ ${p.name} · ${p.count}" else "✕ ${p.name}") + if (!p.ok && p.error.isNotBlank()) ": ${p.error.take(60)}" else "",
                            color = if (p.ok) Sm.ok else Sm.bad, fontFamily = Onest, fontSize = 12.sp,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (p.ok) Color(0xFF0F1F19) else Color(0xFF241211))
                                .border(1.dp, if (p.ok) Color(0xFF27533F) else Color(0xFF6A3330), RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
                status?.let { Text(it, color = if (statusOk) Sm.ok else Sm.bad, fontFamily = Onest, fontSize = 13.5.sp, modifier = Modifier.padding(top = 10.dp)) }

                if (hits.isNotEmpty()) {
                    // Row 1: Content categories + Only Seeded + Refine field
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((k, label) in listOf(
                            "video" to tr("search.filmy_i_serialy"),
                            "movie" to tr("search.tolko_filmy"),
                            "cartoon" to tr("search.tolko_multfilmy"),
                            "series" to tr("search.tolko_serialy"),
                            "all" to tr("search.vse")
                        )) {
                            Chip(label, kind == k) { kind = k; currentPage = 1 }
                        }
                        Chip(tr("search.tolko_s_sidami"), onlySeeded) { onlySeeded = !onlySeeded; currentPage = 1 }
                        Box(Modifier.weight(1f)) { Field(refine, { refine = it; currentPage = 1 }, tr("search.utochnit_p_p_rus")) }
                    }

                    // Row 2: Presets (including Mobile), Resolution & Sort
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Пресет:", color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp)
                        for (p in FilterPreset.entries) {
                            Chip(p.title, preset == p) { preset = p; currentPage = 1 }
                        }

                        Spacer(Modifier.width(8.dp))
                        Text("Качество:", color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp)
                        Chip("Любое", resolution == null) { resolution = null; currentPage = 1 }
                        for (res in listOf(Resolution.UHD_4K, Resolution.FHD_1080P, Resolution.HD_720P)) {
                            Chip(res.label, resolution == res) { resolution = if (resolution == res) null else res; currentPage = 1 }
                        }

                        Spacer(Modifier.weight(1f))
                        Text("Сортировка:", color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp)
                        for (s in listOf(SearchSort.DEFAULT, SearchSort.SIZE_ASC, SearchSort.SEEDS_DESC)) {
                            val label = when (s) {
                                SearchSort.DEFAULT -> "Обычная"
                                SearchSort.SIZE_ASC -> "Сначала лёгкие"
                                SearchSort.SEEDS_DESC -> "По сидам"
                                else -> s.title
                            }
                            Chip(label, sort == s) { sort = s; currentPage = 1 }
                        }
                    }
                }
                if (hits.size != shown.size) Text(tr("search.skryto_filtrami_p", "p" to hits.size - shown.size), color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(10.dp))
        }

        if (shown.isNotEmpty() && totalPages > 1) {
            item {
                PaginationBar(
                    currentPage = safePage,
                    totalPages = totalPages,
                    totalItems = shown.size,
                    fromIndex = fromIndex,
                    toIndex = toIndex,
                    pageSize = pageSize,
                    onPageSizeChange = { newSize ->
                        pageSize = newSize
                        currentPage = 1
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    onPageSelect = { newPage ->
                        currentPage = newPage
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }
        }

        items(pageItems, key = { it.id }) { h ->
            val note = notes.value[h.id]
            val rowSrc = remember { MutableInteractionSource() }
            val rowHover by rowSrc.collectIsHoveredAsState()
            val rowBg by animateColorAsState(if (rowHover) Color(0xFF171B25) else Sm.booth, tween(200))
            val rowEdge by animateColorAsState(if (rowHover) Color(0xFF3E475C) else Color.Transparent, tween(200))

            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).hoverable(rowSrc).clip(RoundedCornerShape(12.dp)).background(rowBg)
                    .border(1.dp, rowEdge, RoundedCornerShape(12.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (h.ru) Box(Modifier.width(3.dp).height(50.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF3E475C)))
                Column(Modifier.weight(1f).padding(start = if (h.ru) 12.dp else 0.dp)) {
                    // Clean Title
                    Text(
                        h.release.cleanTitle.ifBlank { h.title },
                        color = Sm.text,
                        fontFamily = Onest,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Secondary raw title if different
                    if (h.release.cleanTitle.isNotBlank() && h.release.cleanTitle != h.title) {
                        Text(
                            h.title,
                            color = Sm.muted,
                            fontFamily = Onest,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }

                    // Release badges (Resolution, Codec, Rip, Voice, Bitrate)
                    ReleaseBadges(h.release)

                    // Technical info line
                    Row(
                        Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (h.size > 0) Text(formatBytes(h.size), color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text("↑ ${h.seeders}", color = if (h.seeders > 0) Sm.ok else Sm.muted, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("↓ ${h.leechers}", color = Sm.muted, fontFamily = Onest, fontSize = 13.sp)
                        if (h.ru) Text(
                            "RU",
                            color = Color.White,
                            fontFamily = Onest,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF282E3C))
                                .border(1.dp, Color(0xFF3A4356), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                        when (h.kind) {
                            "series" -> tr("search.serial")
                            "movie" -> tr("search.film")
                            "cartoon" -> tr("search.multfilm")
                            else -> null
                        }?.let { Text(it, color = Sm.muted, fontFamily = Onest, fontSize = 13.sp) }
                        Text(h.source + if (h.localTrackerId != null && !h.source.contains("(ПК)")) " (ПК)" else "", color = Sm.muted, fontFamily = Onest, fontSize = 13.sp)
                        if (h.date.isNotBlank()) Text(h.date, color = Sm.muted, fontFamily = Onest, fontSize = 13.sp)
                    }
                    note?.let { (t, ok) -> Text(t, color = if (ok) Sm.ok else Sm.bad, fontFamily = Onest, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                }
                Spacer(Modifier.width(12.dp))
                LampButton(tr("search.v_biblioteku"), enabled = note == null || !note.second) { add(h) }
            }
        }

        if (shown.isNotEmpty() && totalPages > 1) {
            item {
                Spacer(Modifier.height(10.dp))
                PaginationBar(
                    currentPage = safePage,
                    totalPages = totalPages,
                    totalItems = shown.size,
                    fromIndex = fromIndex,
                    toIndex = toIndex,
                    pageSize = pageSize,
                    onPageSizeChange = { newSize ->
                        pageSize = newSize
                        currentPage = 1
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    onPageSelect = { newPage ->
                        currentPage = newPage
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
                )
            }
        }
    }

    if (showTrackerSettings) {
        TrackerSettingsDialog {
            showTrackerSettings = false
            trackerUpdateTrigger++
        }
    }
}
}

@Composable
private fun ReleaseBadge(
    text: String,
    bg: Color = Color(0xFF1C2230),
    edge: Color = Color(0xFF2E3850),
    fg: Color = Color(0xFFC7D0E0),
    dot: Color? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, edge, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.5.dp)
    ) {
        if (dot != null) {
            Box(Modifier.width(6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(dot))
        }
        Text(text, color = fg, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseBadges(release: ReleaseInfo) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    ) {
        // 1. Resolution badge
        when (release.resolution) {
            Resolution.UHD_4K -> ReleaseBadge(text = "4K", bg = Color(0xFF281E10), edge = Color(0xFF6E501C), fg = Color(0xFFFFB02E))
            Resolution.FHD_1080P -> ReleaseBadge(text = "1080p", bg = Color(0xFF101B2E), edge = Color(0xFF243A66), fg = Color(0xFF7FAFFF))
            Resolution.HD_720P -> ReleaseBadge(text = "720p", bg = Color(0xFF102022), edge = Color(0xFF1F4145), fg = Color(0xFF6ED9E6))
            Resolution.SD -> ReleaseBadge(text = "SD", bg = Color(0xFF181C25), edge = Color(0xFF283144), fg = Color(0xFFA0AABF))
            Resolution.UNKNOWN -> {}
        }

        // 2. Video Codec
        when (release.codec) {
            VideoCodec.HEVC -> ReleaseBadge(text = "HEVC", bg = Color(0xFF0F2218), edge = Color(0xFF204D36), fg = Color(0xFF7FE0B0))
            VideoCodec.AV1 -> ReleaseBadge(text = "AV1", bg = Color(0xFF0F2218), edge = Color(0xFF204D36), fg = Color(0xFF7FE0B0))
            VideoCodec.AVC -> ReleaseBadge(text = "AVC", bg = Color(0xFF161A24), edge = Color(0xFF272F42), fg = Color(0xFF94A0B8))
            else -> {}
        }

        // 3. Dynamic Range
        when (release.hdr) {
            HdrType.DV -> ReleaseBadge(text = "Dolby Vision", bg = Color(0xFF241618), edge = Color(0xFF5E2730), fg = Color(0xFFFF9EA8))
            HdrType.HDR10_PLUS -> ReleaseBadge(text = "HDR10+", bg = Color(0xFF241D14), edge = Color(0xFF5A4422), fg = Color(0xFFFFC069))
            HdrType.HDR10 -> ReleaseBadge(text = "HDR", bg = Color(0xFF241D14), edge = Color(0xFF5A4422), fg = Color(0xFFFFC069))
            HdrType.NONE -> {}
        }

        // 4. Rip Type
        when (release.ripType) {
            RipType.CAM -> ReleaseBadge(text = "CAM / Экранка", bg = Color(0xFF2A1010), edge = Color(0xFF662222), fg = Color(0xFFFF8B85))
            RipType.REMUX -> ReleaseBadge(text = "BDRemux", bg = Color(0xFF221430), edge = Color(0xFF4C276E), fg = Color(0xFFD49BFF))
            RipType.BLURAY -> ReleaseBadge(text = "Blu-ray", bg = Color(0xFF1A152E), edge = Color(0xFF382B63), fg = Color(0xFFBCA6F7))
            RipType.BDRIP -> ReleaseBadge(text = "BDRip")
            RipType.WEBDL -> ReleaseBadge(text = "WEB-DL")
            RipType.WEBRIP -> ReleaseBadge(text = "WEBRip")
            RipType.HDTV -> ReleaseBadge(text = "HDTV")
            RipType.DVDRIP -> ReleaseBadge(text = "DVDRip")
            RipType.UNKNOWN -> {}
        }

        // 5. Collection
        if (release.isCollection) {
            val text = if (release.collectionCount != null) "Коллекция (${release.collectionCount})" else "Коллекция"
            ReleaseBadge(
                text = text,
                bg = Color(0xFF261D3B),
                edge = Color(0xFF533B85),
                fg = Color(0xFFD6B4FC)
            )
        }

        // 6. Voice
        when (release.voice) {
            VoiceType.DUB -> ReleaseBadge(text = "Дубляж", bg = Color(0xFF14202B), edge = Color(0xFF233A50), fg = Color(0xFF86B8E6))
            VoiceType.MVO -> ReleaseBadge(text = "MVO")
            VoiceType.DVO -> ReleaseBadge(text = "DVO")
            VoiceType.AVO -> ReleaseBadge(text = "AVO")
            VoiceType.ORIGINAL -> ReleaseBadge(text = "Оригинал/Sub")
            VoiceType.UNKNOWN -> {}
        }

        // 7. Bitrate indicator (with color dot)
        if (release.estimatedBitrateMbps > 0f) {
            val (dotColor, textStr) = when (release.bitrateTier) {
                BitrateTier.LIGHT -> Sm.ok to (if (release.estimatedBitrateMbps < 1f) "< 1 Мбит/с" else "~%.1f Мбит/с".format(Locale.US, release.estimatedBitrateMbps))
                BitrateTier.MEDIUM -> Sm.lamp to "~%.1f Мбит/с".format(Locale.US, release.estimatedBitrateMbps)
                BitrateTier.HEAVY -> Sm.bad to "~%.1f Мбит/с".format(Locale.US, release.estimatedBitrateMbps)
            }
            val fullText = if (release.isCollection) "$textStr / фильм" else textStr
            ReleaseBadge(
                text = fullText,
                dot = dotColor,
                bg = Color(0xFF12151D),
                edge = Color(0xFF232A3B),
                fg = dotColor
            )
        }
    }
}
