package ru.suslikovanora.media.library

import ru.suslikovanora.media.core.*

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

data class Hit(
    val id: String, val title: String, val size: Long, val seeders: Int, val leechers: Int,
    val ru: Boolean, val kind: String, val source: String, val date: String
)

data class SearchProvider(val name: String, val ok: Boolean, val count: Int, val error: String)

/**
 * Search state lives outside the screen (owned by the activity), so results, filters and the "adding…" notes
 * survive switching tabs, and polling for a download continues while another tab is open.
 */
class SearchModel(private val scope: CoroutineScope, private val store: LibraryStore) {
    var query by mutableStateOf("")
    var busy by mutableStateOf(false)
    var status by mutableStateOf<String?>(null)
    var statusOk by mutableStateOf(true)
    var hits by mutableStateOf<List<Hit>>(emptyList())
    var providers by mutableStateOf<List<SearchProvider>>(emptyList())
    var kind by mutableStateOf("video")
    var onlySeeded by mutableStateOf(false)
    var refine by mutableStateOf("")
    var notes by mutableStateOf(mapOf<String, Pair<String, Boolean>>()) // id -> (text, ok)

    private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.ROOT)

    fun run() {
        val q = query.trim()
        if (q.length < 2 || busy) { status = "Введите минимум 2 символа."; statusOk = false; return }
        busy = true; status = "Опрашиваем трекеры…"; statusOk = true
        scope.launch {
            try {
                val j = Api.get("/api/search?q=" + URLEncoder.encode(q, "UTF-8"))
                val arr = j.optJSONArray("results")
                hits = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                    val r = arr!!.optJSONObject(i) ?: return@mapNotNull null
                    val src = r.optJSONArray("sources")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                        ?.takeIf { it.size > 1 }?.joinToString(" + ") ?: r.optString("provider")
                    val d = r.opt("date").let { v ->
                        when (v) { is Number -> if (v.toLong() > 0) dateFmt.format(Date(v.toLong())) else ""; is String -> v.take(10); else -> "" }
                    }
                    Hit(r.optString("id"), r.optString("title"), r.optLong("size"), r.optInt("seeders"), r.optInt("leechers"),
                        r.optBoolean("ru"), r.optString("kind"), src, d)
                }
                val pa = j.optJSONArray("providers")
                providers = (0 until (pa?.length() ?: 0)).mapNotNull { i ->
                    pa!!.optJSONObject(i)?.let { SearchProvider(it.optString("name"), it.optBoolean("ok"), it.optInt("count"), it.optString("error")) }
                }
                val failed = providers.count { !it.ok }
                status = if (hits.isEmpty()) "Ничего не найдено."
                else "Найдено ${j.optInt("total", hits.size)} за ${"%.1f".format(j.optLong("tookMs") / 1000.0)} с" + if (failed > 0) " · не ответили: $failed" else ""
                statusOk = hits.isNotEmpty()
            } catch (e: Exception) { status = Api.describe(e); statusOk = false } finally { busy = false }
        }
    }

    private fun note(id: String, text: String, ok: Boolean) { notes = notes + (id to (text to ok)) }

    fun add(h: Hit) {
        note(h.id, "Добавляю…", true)
        scope.launch {
            try {
                val r = Api.post("/api/search/add", JSONObject().put("id", h.id))
                val id = r.optLong("id")
                if (r.optBoolean("ready")) { note(h.id, "Готово — уже в библиотеке", true); store.refresh(); return@launch }
                note(h.id, "Добавлено, AllDebrid качает…", true)
                val started = System.currentTimeMillis()
                repeat(60) {
                    ru.suslikovanora.media.Foreground.pause(if (System.currentTimeMillis() - started < 60_000) 4000 else 15000)
                    val st = runCatching { Api.get("/api/magnets/$id") }.getOrNull() ?: return@repeat
                    val total = st.optLong("size"); val done = st.optLong("downloaded")
                    val code = st.optInt("statusCode")
                    if (code == 4 || st.optBoolean("ready")) { note(h.id, "Готово — файлы в библиотеке", true); store.refresh(); return@launch }
                    if (code >= 5) { note(h.id, "Ошибка: ${st.optString("status")}", false); return@launch }
                    note(h.id, st.optString("status").ifBlank { "Обработка" } + if (total > 0) " · ${done * 100 / total}%" else "", true)
                }
            } catch (e: Exception) { note(h.id, Api.describe(e), false) }
        }
    }

    fun shown(): List<Hit> {
        val words = refine.lowercase().split(' ').filter { it.isNotBlank() }
        return hits.filter { h ->
            (kind == "all" || (kind == "video" && h.kind != "other") || h.kind == kind) &&
                (!onlySeeded || h.seeders > 0) && words.all { h.title.lowercase().contains(it) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(m: SearchModel, listState: LazyListState, onLogout: () -> Unit, modifier: Modifier = Modifier) {
    val shown = m.shown()
    LazyColumn(
        modifier.fillMaxSize().background(Sm.night), state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp)
    ) {
        item(key = "header") { HeaderSlot(); Spacer(Modifier.height(16.dp)) }
        item(key = "panel") {
            Panel(Modifier.animateContentSize(tween(260))) {
                Text("Поиск раздач", fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 19.sp, color = Sm.text)
                Text("Один запрос — сразу по нескольким трекерам. Результат добавляется в вашу библиотеку AllDebrid.",
                    color = Sm.muted, fontFamily = Onest, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                SearchField(m.query, { m.query = it }, "Название фильма или сериала…", onDone = { m.run() })
                Spacer(Modifier.height(10.dp))
                LampButton(if (m.busy) "Ищем…" else "Найти", enabled = !m.busy, modifier = Modifier.fillMaxWidth()) { m.run() }
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
                        for ((k, label) in listOf("video" to "Фильмы и сериалы", "movie" to "Фильмы", "series" to "Сериалы", "all" to "Всё")) {
                            Chip(label, m.kind == k) { m.kind = k }
                        }
                        Chip("Только с сидами", m.onlySeeded) { m.onlySeeded = !m.onlySeeded }
                    }
                    Spacer(Modifier.height(10.dp))
                    SearchField(m.refine, { m.refine = it }, "Уточнить: 1080p, 2160p, rus…")
                    if (m.hits.size != shown.size) Text("Скрыто фильтрами: ${m.hits.size - shown.size}", color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        items(shown, key = { it.id }) { h -> HitRow(h, m.notes[h.id], Modifier.animateItem(fadeInSpec = tween(260), fadeOutSpec = tween(180))) { m.add(h) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HitRow(h: Hit, note: Pair<String, Boolean>?, modifier: Modifier, onAdd: () -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Sm.booth)
            .border(1.dp, Sm.line, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (h.ru) Box(Modifier.width(3.dp).height(48.dp).clip(RoundedCornerShape(3.dp)).background(Sm.lamp))
        Column(Modifier.weight(1f).padding(start = if (h.ru) 12.dp else 0.dp)) {
            Text(h.title, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 19.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            FlowRow(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (h.size > 0) Text(formatBytes(h.size), color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp)
                Text("↑ ${h.seeders}", color = if (h.seeders > 0) Sm.ok else Sm.muted, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                Text("↓ ${h.leechers}", color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp)
                if (h.ru) Text("RU", color = Sm.lamp, fontFamily = Onest, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x24FFB02E)).padding(horizontal = 6.dp, vertical = 1.dp))
                when (h.kind) { "series" -> "сериал"; "movie" -> "фильм"; else -> null }?.let { Text(it, color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp) }
                Text(h.source, color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp)
                if (h.date.isNotBlank()) Text(h.date, color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp)
            }
            note?.let { (t, ok) -> Text(t, color = if (ok) Sm.ok else Sm.bad, fontFamily = Onest, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp)) }
        }
        Spacer(Modifier.width(10.dp))
        val canAdd = note == null || !note.second || note.first.startsWith("Ошибка")
        LampButton("＋", enabled = canAdd, onClick = onAdd)
    }
}
