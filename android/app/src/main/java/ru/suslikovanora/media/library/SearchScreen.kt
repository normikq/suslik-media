package ru.suslikovanora.media.library

import ru.suslikovanora.media.core.*
import ru.suslikovanora.media.core.search.*

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.suslikovanora.media.net.Api
import ru.suslikovanora.media.ui.Chip
import ru.suslikovanora.media.ui.Onest
import ru.suslikovanora.media.ui.Sm
import ru.suslikovanora.media.ui.Unbounded
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ru.suslikovanora.media.tr

/**
 * Search state lives outside the screen (owned by the activity), so results, filters and the "adding…" notes
 * survive switching tabs, and polling for a download continues while another tab is open.
 * The same search as on the PC (parsed releases, presets, quality, sorting), except the PC-only direct RuTracker search.
 */
class SearchModel(private val scope: CoroutineScope, private val store: LibraryStore) {
    var query by mutableStateOf("")
    var busy by mutableStateOf(false)
    var status by mutableStateOf<String?>(null)
    var statusOk by mutableStateOf(true)
    var hits by mutableStateOf<List<SearchHit>>(emptyList())
    var providers by mutableStateOf<List<SearchProviderInfo>>(emptyList())
    var kind by mutableStateOf("video")
    var preset by mutableStateOf(FilterPreset.ALL)
    var resolution by mutableStateOf<Resolution?>(null)
    var sort by mutableStateOf(SearchSort.DEFAULT)
    var onlySeeded by mutableStateOf(false)
    var onlyRu by mutableStateOf(false)
    var refine by mutableStateOf("")
    var shownCount by mutableStateOf(PAGE)
    var notes by mutableStateOf(mapOf<String, Pair<String, Boolean>>()) // id -> (text, ok)
    var activeQuery by mutableStateOf("")
        private set

    fun run() {
        val rawQ = query.trim()
        if (rawQ.length < 2 || busy) { status = tr("search.vvedite_minimum_simvola"); statusOk = false; return }
        val q = SearchQueryCleaner.clean(rawQ).ifBlank { rawQ }
        activeQuery = q; shownCount = PAGE
        busy = true; status = tr("search.oprashivaem_trekery"); statusOk = true
        scope.launch {
            try {
                val j = Api.get("/api/search?q=" + URLEncoder.encode(q, "UTF-8"))
                hits = SearchHit.parseHits(j.optJSONArray("results")).filter { !ReleaseParser.isNonVideo(it.title) }
                providers = SearchHit.parseProviders(j.optJSONArray("providers"))
                val failed = providers.count { !it.ok }
                status = if (hits.isEmpty()) tr("search.nichego_ne_naydeno")
                else tr("search.naydeno_p_za_p", "total" to hits.size, "sec" to "%.1f".format(j.optLong("tookMs") / 1000.0)) + if (failed > 0) tr("search.ne_otvetili_failed", "failed" to failed) else ""
                statusOk = hits.isNotEmpty()
            } catch (e: Exception) { status = Api.describe(e); statusOk = false } finally { busy = false }
        }
    }

    private fun note(id: String, text: String, ok: Boolean) { notes = notes + (id to (text to ok)) }

    fun add(h: SearchHit) {
        note(h.id, tr("search.dobavlyayu"), true)
        scope.launch {
            try {
                val r = Api.post("/api/search/add", JSONObject().put("id", h.id), repeatable = false)
                val id = r.optLong("id")
                if (r.optBoolean("ready")) { note(h.id, tr("search.gotovo_uzhe_v_biblioteke"), true); store.refresh(); return@launch }
                note(h.id, tr("add.dobavleno_alldebrid_kachaet"), true)
                val started = System.currentTimeMillis()
                repeat(60) {
                    ru.suslikovanora.media.Foreground.pause(if (System.currentTimeMillis() - started < 60_000) 4000 else 15000)
                    val st = runCatching { Api.get("/api/magnets/$id") }.getOrNull() ?: return@repeat
                    val total = st.optLong("size"); val done = st.optLong("downloaded")
                    val code = st.optInt("statusCode")
                    if (code == 4 || st.optBoolean("ready")) { note(h.id, tr("search.gotovo_fayly_v_biblioteke"), true); store.refresh(); return@launch }
                    if (code >= 5) { note(h.id, tr("add.oshibka_p", "p" to st.optString("status")), false); return@launch }
                    note(h.id, st.optString("status").ifBlank { tr("add.obrabotka") } + if (total > 0) " · ${done * 100 / total}%" else "", true)
                }
            } catch (e: Exception) { note(h.id, Api.describe(e), false) }
        }
    }

    fun shown(): List<SearchHit> = SearchFilterCriteria(
        query = activeQuery, kind = kind, preset = preset, resolution = resolution,
        onlySeeded = onlySeeded, onlyRu = onlyRu, refine = refine, sort = sort
    ).filter(hits)

    companion object { const val PAGE = 25 }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(m: SearchModel, listState: LazyListState, onLogout: () -> Unit, modifier: Modifier = Modifier) {
    // Filtering and sorting parse every hit: once per change of the results or filters, not at every recomposition (the keyboard's, scrolling)
    val shown = remember(m.hits, m.kind, m.preset, m.resolution, m.sort, m.onlySeeded, m.onlyRu, m.refine, m.activeQuery) { m.shown() }
    LazyColumn(
        modifier.fillMaxSize(), state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 80.dp)
    ) {
        item(key = "header") { HeaderSlot(); Spacer(Modifier.height(16.dp)) }
        item(key = "panel") {
            Panel(Modifier.animateContentSize(tween(260))) {
                Text(tr("search.poisk_razdach"), fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = Color.White)
                Text(tr("search.odin_zapros_srazu_po"),
                    color = Sm.muted, fontFamily = Onest, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                SearchField(m.query, { m.query = it }, tr("search.nazvanie_filma_ili_seriala"), onDone = { m.run() })
                Spacer(Modifier.height(10.dp))
                LampButton(if (m.busy) tr("search.ischem") else tr("search.nayti"), enabled = !m.busy, modifier = Modifier.fillMaxWidth()) { m.run() }
                if (m.providers.isNotEmpty()) FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    m.providers.forEach { p ->
                        Text((if (p.ok) "✓ ${p.name} · ${p.count}" else "✕ ${p.name}") + if (!p.ok && p.error.isNotBlank()) ": ${p.error.take(70)}" else "",
                            color = if (p.ok) Sm.ok else Sm.bad, fontFamily = Onest, fontSize = 12.sp, lineHeight = 16.sp,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (p.ok) Color(0xFF0F1F19) else Color(0xFF241211))
                                .border(1.dp, if (p.ok) Color(0xFF27533F) else Color(0xFF6A3330), RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
                m.status?.let { Text(it, color = if (m.statusOk) Sm.ok else Sm.bad, fontFamily = Onest, fontSize = 13.5.sp, modifier = Modifier.padding(top = 10.dp)) }
                if (m.hits.isNotEmpty()) {
                    Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((k, label) in listOf("video" to tr("search.filmy_i_serialy"), "movie" to tr("search.filmy"), "cartoon" to tr("search.multfilmy"), "series" to tr("search.serialy"), "all" to tr("search.vse"))) {
                            Chip(label, m.kind == k) { m.kind = k; m.shownCount = SearchModel.PAGE }
                        }
                        Chip(tr("search.tolko_s_sidami"), m.onlySeeded) { m.onlySeeded = !m.onlySeeded; m.shownCount = SearchModel.PAGE }
                        Chip("Только RU-трекеры", m.onlyRu) { m.onlyRu = !m.onlyRu; m.shownCount = SearchModel.PAGE }
                    }
                    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Пресет:", color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp)
                        for (p in FilterPreset.entries) Chip(p.title, m.preset == p) { m.preset = p; m.shownCount = SearchModel.PAGE }
                    }
                    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Качество:", color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp)
                        Chip("Любое", m.resolution == null) { m.resolution = null; m.shownCount = SearchModel.PAGE }
                        for (res in listOf(Resolution.UHD_4K, Resolution.FHD_1080P, Resolution.HD_720P)) {
                            Chip(res.label, m.resolution == res) { m.resolution = if (m.resolution == res) null else res; m.shownCount = SearchModel.PAGE }
                        }
                    }
                    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Сортировка:", color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp)
                        for ((so, label) in listOf(SearchSort.DEFAULT to "Обычная", SearchSort.SIZE_ASC to "Сначала лёгкие", SearchSort.SEEDS_DESC to "По сидам")) {
                            Chip(label, m.sort == so) { m.sort = so; m.shownCount = SearchModel.PAGE }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    SearchField(m.refine, { m.refine = it; m.shownCount = SearchModel.PAGE }, tr("search.utochnit_p_p_rus"))
                    if (m.hits.size != shown.size) Text(tr("search.skryto_filtrami_p", "p" to m.hits.size - shown.size), color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        items(shown.take(m.shownCount), key = { it.id }) { h -> HitRow(h, m.notes[h.id], Modifier.animateItem(fadeInSpec = tween(260), fadeOutSpec = tween(180))) { m.add(h) } }
        if (shown.size > m.shownCount) item(key = "more") {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                OutlineButton("Показать ещё (${shown.size - m.shownCount})") { m.shownCount += SearchModel.PAGE }
            }
        }
    }
}

@Composable
private fun ReleaseBadge(text: String, fg: Color = Color(0xFFC7D0E0), bg: Color = Color(0xFF1C2230), edge: Color = Color(0xFF2E3850)) {
    Text(text, color = fg, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).border(1.dp, edge, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp))
}

/** Resolution, codec, HDR, rip, collection, voice and the bitrate estimate of the release (as on the PC). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseBadges(r: ReleaseInfo) {
    FlowRow(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (r.resolution) {
            Resolution.UHD_4K -> ReleaseBadge("4K", Color(0xFFFFB02E), Color(0xFF281E10), Color(0xFF6E501C))
            Resolution.FHD_1080P -> ReleaseBadge("1080p", Color(0xFF7FAFFF), Color(0xFF101B2E), Color(0xFF243A66))
            Resolution.HD_720P -> ReleaseBadge("720p", Color(0xFF6ED9E6), Color(0xFF102022), Color(0xFF1F4145))
            Resolution.SD -> ReleaseBadge("SD", Color(0xFFA0AABF), Color(0xFF181C25), Color(0xFF283144))
            Resolution.UNKNOWN -> {}
        }
        when (r.codec) {
            VideoCodec.HEVC, VideoCodec.AV1 -> ReleaseBadge(r.codec.label, Color(0xFF7FE0B0), Color(0xFF0F2218), Color(0xFF204D36))
            VideoCodec.AVC -> ReleaseBadge("AVC", Color(0xFF94A0B8), Color(0xFF161A24), Color(0xFF272F42))
            else -> {}
        }
        if (r.hdr != HdrType.NONE) ReleaseBadge(r.hdr.label, Color(0xFFFFC069), Color(0xFF241D14), Color(0xFF5A4422))
        when (r.ripType) {
            RipType.CAM -> ReleaseBadge("CAM / Экранка", Color(0xFFFF8B85), Color(0xFF2A1010), Color(0xFF662222))
            RipType.REMUX -> ReleaseBadge("BDRemux", Color(0xFFD49BFF), Color(0xFF221430), Color(0xFF4C276E))
            RipType.BLURAY -> ReleaseBadge("Blu-ray", Color(0xFFBCA6F7), Color(0xFF1A152E), Color(0xFF382B63))
            RipType.UNKNOWN -> {}
            else -> ReleaseBadge(r.ripType.label)
        }
        if (r.isCollection) ReleaseBadge(if (r.collectionCount != null) "Коллекция (${r.collectionCount})" else "Коллекция", Color(0xFFD6B4FC), Color(0xFF261D3B), Color(0xFF533B85))
        when (r.voice) {
            VoiceType.DUB -> ReleaseBadge("Дубляж", Color(0xFF86B8E6), Color(0xFF14202B), Color(0xFF233A50))
            VoiceType.MVO -> ReleaseBadge("MVO")
            VoiceType.DVO -> ReleaseBadge("DVO")
            VoiceType.AVO -> ReleaseBadge("AVO")
            VoiceType.ORIGINAL -> ReleaseBadge("Оригинал/Sub")
            VoiceType.UNKNOWN -> {}
        }
        if (r.estimatedBitrateMbps > 0f) {
            val c = when (r.bitrateTier) { BitrateTier.LIGHT -> Sm.ok; BitrateTier.MEDIUM -> Sm.lamp; BitrateTier.HEAVY -> Sm.bad }
            val t = if (r.estimatedBitrateMbps < 1f) "< 1 Мбит/с" else "~%.1f Мбит/с".format(Locale.US, r.estimatedBitrateMbps)
            ReleaseBadge(if (r.isCollection) "$t / фильм" else t, c, Color(0xFF12151D), Color(0xFF232A3B))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HitRow(h: SearchHit, note: Pair<String, Boolean>?, modifier: Modifier, onAdd: () -> Unit) {
    val clean = h.release.cleanTitle.ifBlank { h.title }
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Sm.glassFill)
            .border(1.dp, Sm.glassEdge, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (h.ru) Box(Modifier.width(3.dp).height(48.dp).clip(RoundedCornerShape(3.dp)).background(Sm.accent))
        Column(Modifier.weight(1f).padding(start = if (h.ru) 12.dp else 0.dp)) {
            Text(clean, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, lineHeight = 19.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (clean != h.title) Text(h.title, color = Sm.muted, fontFamily = Onest, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
            ReleaseBadges(h.release)
            FlowRow(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (h.size > 0) Text(formatBytes(h.size), color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 12.5.sp)
                Text("↑ ${h.seeders}", color = if (h.seeders > 0) Sm.ok else Sm.muted, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                Text("↓ ${h.leechers}", color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp)
                if (h.ru) Text("RU", color = Color.White, fontFamily = Onest, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Sm.accent).padding(horizontal = 6.dp, vertical = 1.dp))
                when (h.kind) { "series" -> tr("search.serial"); "movie" -> tr("search.film"); "cartoon" -> tr("search.multfilm"); else -> null }?.let { Text(it, color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp) }
                Text(h.source, color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp)
                if (h.date.isNotBlank()) Text(h.date, color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp)
            }
            note?.let { (t, ok) -> Text(t, color = if (ok) Sm.ok else Sm.bad, fontFamily = Onest, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp)) }
        }
        Spacer(Modifier.width(10.dp))
        val canAdd = note == null || !note.second
        LampButton("＋", enabled = canAdd, onClick = onAdd)
    }
}
