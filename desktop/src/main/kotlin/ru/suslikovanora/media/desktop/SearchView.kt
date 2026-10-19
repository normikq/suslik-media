package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*
import ru.suslikovanora.media.desktop.ui.*

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date

enum class Tab(val label: String) { Library(tr("search.biblioteka")), Search(tr("search.poisk_razdach")), Add(tr("search.magnet_torrent")) }

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
        val w = maxWidth / Tab.entries.size
        Box(Modifier.offset(x = w * lens).width(w).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(Color(0xFF282E3C)))
        Row(Modifier.fillMaxSize()) {
            for (t in Tab.entries) {
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

private data class Hit(
    val id: String, val title: String, val size: Long, val seeders: Int, val leechers: Int,
    val ru: Boolean, val kind: String, val source: String, val date: String
)

private data class Provider(val name: String, val ok: Boolean, val count: Int, val error: String)

private val dateFmt = SimpleDateFormat("dd.MM.yyyy")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchView(lib: Library, header: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(true) }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var providers by remember { mutableStateOf<List<Provider>>(emptyList()) }
    var kind by remember { mutableStateOf("video") }
    var onlySeeded by remember { mutableStateOf(false) }
    var refine by remember { mutableStateOf("") }
    val notes = remember { mutableStateOf(mapOf<String, Pair<String, Boolean>>()) }

    fun run() {
        val q = query.trim()
        if (q.length < 2 || busy) { status = tr("search.vvedite_minimum_simvola"); statusOk = false; return }
        busy = true; status = tr("search.oprashivaem_trekery"); statusOk = true
        scope.launch {
            try {
                val j = Api.get("/api/search?q=" + URLEncoder.encode(q, "UTF-8"))
                val arr = j.optJSONArray("results")
                hits = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                    val r = arr!!.optJSONObject(i) ?: return@mapNotNull null
                    val src = r.optJSONArray("sources")?.let { a -> (0 until a.length()).map { a.optString(it) } }?.takeIf { it.size > 1 }?.joinToString(" + ") ?: r.optString("provider")
                    val d = r.opt("date").let { v -> when (v) { is Number -> if (v.toLong() > 0) dateFmt.format(Date(v.toLong())) else ""; is String -> v.take(10); else -> "" } }
                    Hit(r.optString("id"), r.optString("title"), r.optLong("size"), r.optInt("seeders"), r.optInt("leechers"),
                        r.optBoolean("ru"), r.optString("kind"), src, d)
                }
                val pa = j.optJSONArray("providers")
                providers = (0 until (pa?.length() ?: 0)).mapNotNull { i -> pa!!.optJSONObject(i)?.let { Provider(it.optString("name"), it.optBoolean("ok"), it.optInt("count"), it.optString("error")) } }
                val failed = providers.count { !it.ok }
                status = if (hits.isEmpty()) tr("search.nichego_ne_naydeno") else tr("search.naydeno_p_za_p", "total" to j.optInt("total", hits.size), "sec" to "%.1f".format(j.optLong("tookMs") / 1000.0)) + if (failed > 0) tr("search.ne_otvetili_failed", "failed" to failed) else ""
                statusOk = hits.isNotEmpty()
            } catch (e: Exception) { status = Api.describe(e); statusOk = false } finally { busy = false }
        }
    }

    fun add(h: Hit) {
        notes.value = notes.value + (h.id to (tr("search.dobavlyayu") to true))
        scope.launch {
            try {
                val r = Api.post("/api/search/add", JSONObject().put("id", h.id))
                val id = r.optLong("id")
                if (r.optBoolean("ready")) { notes.value = notes.value + (h.id to (tr("add.gotovo_uzhe_v_biblioteke") to true)); lib.refresh(); return@launch }
                notes.value = notes.value + (h.id to (tr("add.dobavleno_alldebrid_kachaet") to true))
                val started = System.currentTimeMillis()
                repeat(60) {
                    delay(if (System.currentTimeMillis() - started < 60_000) 4000 else 15000)
                    val st = runCatching { Api.get("/api/magnets/$id") }.getOrNull() ?: return@repeat
                    val total = st.optLong("size"); val done = st.optLong("downloaded")
                    val code = st.optInt("statusCode")
                    if (code == 4 || st.optBoolean("ready")) { notes.value = notes.value + (h.id to (tr("add.gotovo_fayly_v_biblioteke") to true)); lib.refresh(); return@launch }
                    if (code >= 5) { notes.value = notes.value + (h.id to (tr("add.oshibka_p", "p" to st.optString("status")) to false)); return@launch }
                    notes.value = notes.value + (h.id to ("${st.optString("status").ifBlank { tr("add.obrabotka") }}${if (total > 0) " · ${done * 100 / total}%" else ""}" to true))
                }
            } catch (e: Exception) { notes.value = notes.value + (h.id to (Api.describe(e) to false)) }
        }
    }

    val shown = remember(hits, kind, onlySeeded, refine) {
        val words = refine.lowercase().split(' ').filter { it.isNotBlank() }
        hits.filter { h ->
            (kind == "all" || (kind == "video" && h.kind != "other") || h.kind == kind) &&
                (!onlySeeded || h.seeders > 0) && words.all { h.title.lowercase().contains(it) }
        }
    }

    LazyColumn(Modifier.fillMaxSize(),contentPadding = PaddingValues(28.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        // The header (logo, tabs, log out) is drawn by the main window above the pages, so it never moves.
        item {
            Panel {
                Text(tr("search.poisk_razdach"), fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 22.sp, color = Sm.text)
                Text(tr("search.odin_zapros_srazu_po"), color = Sm.muted,
                    fontFamily = Onest, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Field(query, { query = it }, tr("search.nazvanie_filma_ili_seriala"), onDone = { run() }) }
                    Spacer(Modifier.width(10.dp))
                    LampButton(if (busy) tr("search.ischem") else tr("search.nayti"), enabled = !busy) { run() }
                }
                if (providers.isNotEmpty()) FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    providers.forEach { p ->
                        Text((if (p.ok) "✓ ${p.name} · ${p.count}" else "✕ ${p.name}") + if (!p.ok && p.error.isNotBlank()) ": ${p.error.take(60)}" else "",
                            color = if (p.ok) Sm.ok else Sm.bad, fontFamily = Onest, fontSize = 12.sp,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (p.ok) Color(0xFF0F1F19) else Color(0xFF241211))
                                .border(1.dp, if (p.ok) Color(0xFF27533F) else Color(0xFF6A3330), RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
                status?.let { Text(it, color = if (statusOk) Sm.ok else Sm.bad, fontFamily = Onest, fontSize = 13.5.sp, modifier = Modifier.padding(top = 10.dp)) }
                if (hits.isNotEmpty()) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((k, label) in listOf("video" to tr("search.filmy_i_serialy"), "movie" to tr("search.tolko_filmy"), "series" to tr("search.tolko_serialy"), "all" to tr("search.vse"))) {
                        Chip(label, kind == k) { kind = k }
                    }
                    Chip(tr("search.tolko_s_sidami"), onlySeeded) { onlySeeded = !onlySeeded }
                    Box(Modifier.weight(1f)) { Field(refine, { refine = it }, tr("search.utochnit_p_p_rus")) }
                }
                if (hits.size != shown.size) Text(tr("search.skryto_filtrami_p", "p" to hits.size - shown.size), color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(10.dp))
        }
        items(shown, key = { it.id }) { h ->
            val note = notes.value[h.id]
            val rowSrc = remember { MutableInteractionSource() }
            val rowHover by rowSrc.collectIsHoveredAsState()
            val rowBg by animateColorAsState(if (rowHover) Color(0xFF171B25) else Sm.booth, tween(200))
            val rowEdge by animateColorAsState(if (rowHover) Color(0xFF3E475C) else Color.Transparent, tween(200))
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).hoverable(rowSrc).clip(RoundedCornerShape(12.dp)).background(rowBg)
                .border(1.dp, rowEdge, RoundedCornerShape(12.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (h.ru) Box(Modifier.width(3.dp).height(44.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF3E475C)))
                Column(Modifier.weight(1f).padding(start = if (h.ru) 12.dp else 0.dp)) {
                    Text(h.title, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (h.size > 0) Text(formatBytes(h.size), color = Sm.muted, fontFamily = Onest, fontSize = 13.sp)
                        Text("↑ ${h.seeders}", color = if (h.seeders > 0) Sm.ok else Sm.muted, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("↓ ${h.leechers}", color = Sm.muted, fontFamily = Onest, fontSize = 13.sp)
                        if (h.ru) Text("RU", color = Color.White, fontFamily = Onest, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF282E3C)).border(1.dp, Color(0xFF3A4356), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                        when (h.kind) { "series" -> tr("search.serial"); "movie" -> tr("search.film"); else -> null }?.let { Text(it, color = Sm.muted, fontFamily = Onest, fontSize = 13.sp) }
                        Text(h.source, color = Sm.muted, fontFamily = Onest, fontSize = 13.sp)
                        if (h.date.isNotBlank()) Text(h.date, color = Sm.muted, fontFamily = Onest, fontSize = 13.sp)
                    }
                    note?.let { (t, ok) -> Text(t, color = if (ok) Sm.ok else Sm.bad, fontFamily = Onest, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                }
                Spacer(Modifier.width(12.dp))
                LampButton(tr("search.v_biblioteku"), enabled = note == null || !note.second) { add(h) }
            }
        }
    }
}
