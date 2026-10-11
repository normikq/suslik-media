package ru.suslikovanora.media.library

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.suslikovanora.media.net.Api
import ru.suslikovanora.media.ui.Onest
import ru.suslikovanora.media.ui.Sm
import ru.suslikovanora.media.ui.Unbounded

/** One thing added from this device: a magnet, a .torrent or a file-host link. Kept between app starts. */
class AddEntry(val key: String, title: String, val magnetId: Long, status: String, percent: Int, ready: Boolean, failed: Boolean, val at: Long) {
    var title by mutableStateOf(title)
    var status by mutableStateOf(status)
    var percent by mutableStateOf(percent)   // -1 unknown
    var ready by mutableStateOf(ready)
    var failed by mutableStateOf(failed)
    val active get() = magnetId > 0 && !ready && !failed
    fun toJson(): JSONObject = JSONObject().put("key", key).put("title", title).put("magnetId", magnetId).put("status", status)
        .put("percent", percent).put("ready", ready).put("failed", failed).put("at", at)
}

/** A file-host link unlocked through AllDebrid: ready to play. */
data class UnlockedLink(val name: String, val size: Long, val url: String)

/** A .torrent chosen on the device (read when uploading). */
data class PickedTorrent(val uri: Uri, val name: String, val size: Long)

/**
 * State of the "Ссылка" tab, owned by the activity: input, chosen files, history with live AllDebrid status.
 * History is saved on the device (like the site's «История поиска»); unfinished downloads resume polling on start.
 */
class AddModel(context: Context, private val scope: CoroutineScope, private val store: LibraryStore) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("add_history", Context.MODE_PRIVATE)
    var mode by mutableStateOf(0) // 0 = link / magnet, 1 = torrent file
    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
    var status by mutableStateOf<String?>(null)
    var statusOk by mutableStateOf(true)
    var unlocked by mutableStateOf<UnlockedLink?>(null)
    val chosen = mutableStateListOf<PickedTorrent>()
    val history = mutableStateListOf<AddEntry>()
    private val polling = HashMap<Long, Job>()

    init {
        runCatching {
            val arr = JSONArray(prefs.getString("items", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                history += AddEntry(o.optString("key"), o.optString("title"), o.optLong("magnetId"), o.optString("status"),
                    o.optInt("percent", -1), o.optBoolean("ready"), o.optBoolean("failed"), o.optLong("at"))
            }
        }
    }

    private fun save() {
        val arr = JSONArray(); history.take(40).forEach { arr.put(it.toJson()) }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    /** Continue following downloads that were still running when the app was closed. */
    fun resume() { history.filter { it.active }.forEach { follow(it) } }

    private fun say(text: String, ok: Boolean) { status = text; statusOk = ok }

    fun submitInput() {
        val v = input.trim()
        if (v.isEmpty()) { say("Вставьте magnet-ссылку или ссылку на файл (https://…)", false); return }
        unlocked = null
        when {
            v.startsWith("magnet:?", ignoreCase = true) -> {
                val links = v.split(Regex("\\s+")).filter { it.startsWith("magnet:?", true) }
                input = ""; links.forEach { addMagnet(it) }
            }
            v.startsWith("https://", ignoreCase = true) -> unlock(v)
            else -> say("Поддерживаются magnet:? и ссылки https://", false)
        }
    }

    private fun magnetName(link: String): String =
        Regex("[?&]dn=([^&]+)").find(link)?.groupValues?.get(1)?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?: "Загрузка по magnet-ссылке"

    private fun addMagnet(link: String) {
        val e = AddEntry("m:" + System.nanoTime(), magnetName(link), 0, "Отправляю…", -1, false, false, System.currentTimeMillis())
        history.add(0, e)
        scope.launch {
            try {
                val r = Api.post("/api/magnets", JSONObject().put("magnet", link))
                accepted(e, r)
            } catch (ex: Exception) { e.status = Api.describe(ex); e.failed = true; save() }
        }
    }

    fun uploadChosen(context: Context) {
        val list = chosen.toList(); chosen.clear()
        for (t in list) {
            val e = AddEntry("t:" + System.nanoTime(), t.name.removeSuffix(".torrent"), 0, "Отправляю…", -1, false, false, System.currentTimeMillis())
            history.add(0, e)
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(t.uri)?.use { it.readBytes() } }
                        ?: throw IllegalStateException("Не удалось прочитать файл")
                    if (bytes.size > 1024 * 1024) throw IllegalStateException("Файл больше 1 МБ")
                    accepted(e, Api.postTorrent(bytes, t.name))
                } catch (ex: Exception) { e.status = Api.describe(ex); e.failed = true; save() }
            }
        }
    }

    /** Server accepted it: replace the placeholder by a tracked entry and follow its status. */
    private fun accepted(placeholder: AddEntry, r: JSONObject) {
        val id = r.optLong("id")
        val ready = r.optBoolean("ready")
        val e = AddEntry(placeholder.key, r.optString("name").ifBlank { placeholder.title }, id,
            if (ready) "Готово" else "Добавлено, AllDebrid качает…", if (ready) 100 else -1, ready, false, placeholder.at)
        // the same torrent added again: keep one row
        history.removeAll { it !== placeholder && it.magnetId == id && id > 0 }
        val i = history.indexOf(placeholder)
        if (i >= 0) history[i] = e else history.add(0, e)
        save()
        if (ready) scope.launch { store.refresh() } else follow(e)
    }

    private fun follow(e: AddEntry) {
        if (polling[e.magnetId]?.isActive == true) return
        polling[e.magnetId] = scope.launch {
            val started = System.currentTimeMillis()
            while (e.active) {
                delay(if (System.currentTimeMillis() - started < 60_000) 4000 else 15000)
                val st = runCatching { Api.get("/api/magnets/${e.magnetId}") }.getOrNull() ?: continue
                val total = st.optLong("size"); val done = st.optLong("downloaded")
                val code = st.optInt("statusCode")
                st.optString("filename").takeIf { it.isNotBlank() }?.let { e.title = it }
                when {
                    code == 4 || st.optBoolean("ready") -> { e.ready = true; e.percent = 100; e.status = "Готово — в библиотеке"; save(); store.refresh() }
                    code >= 5 -> { e.failed = true; e.status = "Ошибка: ${st.optString("status")}"; save() }
                    else -> { e.status = st.optString("status").ifBlank { "Обработка" }; e.percent = if (total > 0) (done * 100 / total).toInt() else -1; save() }
                }
                if (System.currentTimeMillis() - started > 6 * 3_600_000L) break // not forever
            }
        }
    }

    fun cancel(e: AddEntry) {
        scope.launch {
            try {
                Api.post("/api/magnets/${e.magnetId}/cancel")
                polling.remove(e.magnetId)?.cancel()
                e.failed = true; e.status = "Отменено"; save(); store.refresh()
            } catch (ex: Exception) { say(Api.describe(ex), false) }
        }
    }

    fun forget(e: AddEntry) { history.remove(e); save() }
    fun clearFinished() { history.removeAll { !it.active }; save() }

    private fun unlock(url: String) {
        busy = true; say("Получаем ссылку через AllDebrid…", true)
        scope.launch {
            try {
                val r = Api.post("/api/unlock", JSONObject().put("url", url))
                unlocked = UnlockedLink(r.optString("filename").ifBlank { "Видео" }, r.optLong("filesize"), r.optString("url"))
                say("Ссылка готова", true); input = ""
            } catch (ex: Exception) { say(Api.describe(ex), false) } finally { busy = false }
        }
    }
}

private fun displayName(context: Context, uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file.torrent"; var size = -1L
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
    }
    return name to size
}

@Composable
fun AddScreen(m: AddModel, listState: LazyListState, onPlay: (String, String) -> Unit, onLogout: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        for (u in uris) {
            val (name, size) = displayName(context, u)
            if (!name.endsWith(".torrent", true)) { m.status = "Не .torrent: $name"; m.statusOk = false; continue }
            if (size > 1024 * 1024) { m.status = "Больше 1 МБ: $name"; m.statusOk = false; continue }
            if (m.chosen.none { it.uri == u }) m.chosen += PickedTorrent(u, name, size)
        }
    }
    LazyColumn(
        modifier.fillMaxSize().background(Sm.night), state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp)
    ) {
        item(key = "header") { BrandHeader(onLogout = onLogout); Spacer(Modifier.height(16.dp)) }
        item(key = "panel") {
            Panel(Modifier.animateContentSize(tween(280, easing = Sm.ease))) {
                Text("Добавить раздачу", fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 19.sp, color = Sm.text)
                Text("Magnet, ссылка на файл или .torrent — AllDebrid скачает, файлы появятся в библиотеке.",
                    color = Sm.muted, fontFamily = Onest, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeCard("↗", "Ссылка", "magnet или https", m.mode == 0, Modifier.weight(1f)) { m.mode = 0; m.status = null }
                    ModeCard("⇧", "Torrent-файл", "файл .torrent", m.mode == 1, Modifier.weight(1f)) { m.mode = 1; m.status = null }
                }
                Spacer(Modifier.height(14.dp))
                AnimatedContent(
                    targetState = m.mode,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (fadeIn(tween(260, delayMillis = 60)) + slideInHorizontally(tween(360, easing = Sm.ease)) { dir * it / 8 }) togetherWith
                            (fadeOut(tween(150)) + slideOutHorizontally(tween(360, easing = Sm.ease)) { -dir * it / 8 }) using
                            SizeTransform(clip = false) { _, _ -> tween(300, easing = Sm.ease) }
                    }, label = "mode"
                ) { mode ->
                    if (mode == 0) Column {
                        SearchField(m.input, { m.input = it }, "magnet:?xt=… или https://…", onDone = { m.submitInput() })
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LampButton(if (m.busy) "Выполняется…" else "Добавить", enabled = !m.busy && m.input.isNotBlank(), modifier = Modifier.weight(1f)) { m.submitInput() }
                            OutlineButton("Вставить") {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val t = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                                if (t.isNullOrBlank()) { m.status = "В буфере обмена нет текста"; m.statusOk = false } else m.input = t
                            }
                        }
                    } else Column {
                        DropZone(hasFiles = m.chosen.isNotEmpty()) { runCatching { picker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) } }
                        m.chosen.forEach { f ->
                            Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF11141D))
                                .border(1.dp, Sm.line2, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("TORRENT", color = Sm.lamp, fontFamily = Onest, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0x24FFB02E)).padding(horizontal = 7.dp, vertical = 5.dp))
                                Text(f.name, color = Sm.text, fontFamily = Onest, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                                if (f.size > 0) Text("${(f.size / 1024).coerceAtLeast(1)} KB", color = Sm.muted, fontFamily = Onest, fontSize = 12.sp)
                                Spacer(Modifier.width(6.dp))
                                OutlineButton("×") { m.chosen.remove(f) }
                            }
                        }
                        if (m.chosen.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            LampButton(if (m.chosen.size > 1) "Загрузить торренты (${m.chosen.size})" else "Загрузить торрент", modifier = Modifier.fillMaxWidth()) { m.uploadChosen(context) }
                        }
                    }
                }
                m.status?.let { Text(it, color = if (m.statusOk) Sm.ok else Sm.bad, fontFamily = Onest, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp)) }
                m.unlocked?.let { u ->
                    Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Sm.film)
                        .border(1.dp, Color(0x66FFB02E), RoundedCornerShape(14.dp)).padding(14.dp)) {
                        Text(u.name, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (u.size > 0) Text(formatBytes(u.size), color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LampButton("▶ Смотреть", modifier = Modifier.weight(1f)) { onPlay(u.url, u.name) }
                            OutlineButton("Копировать") {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", u.url))
                                Toast.makeText(context, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
        if (m.history.isNotEmpty()) item(key = "historyHead") {
            Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("История", fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = Sm.text, modifier = Modifier.weight(1f))
                if (m.history.any { !it.active }) OutlineButton("Очистить") { m.clearFinished() }
            }
        }
        items(m.history, key = { it.key }) { e -> HistoryRow(e, Modifier.animateItem(fadeInSpec = tween(260), fadeOutSpec = tween(180)), { m.cancel(e) }, { m.forget(e) }) }
        if (m.history.isEmpty()) item(key = "hint") {
            Text("Скачанное появится во вкладке «Библиотека». Magnet-ссылки из браузера и других приложений тоже открываются здесь.",
                color = Sm.muted, fontFamily = Onest, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 18.dp))
        }
    }
}

@Composable
private fun HistoryRow(e: AddEntry, modifier: Modifier, onCancel: () -> Unit, onForget: () -> Unit) {
    val bar by animateFloatAsState(e.percent.coerceAtLeast(0) / 100f, tween(600), label = "bar")
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Sm.booth)
        .border(1.dp, Sm.line, RoundedCornerShape(14.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.title, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(e.status + if (e.active && e.percent >= 0) " · ${e.percent}%" else "",
                    color = when { e.failed -> Sm.bad; e.ready -> Sm.ok; else -> Sm.lamp }, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Spacer(Modifier.width(8.dp))
            if (e.active) OutlineButton("Отменить", color = Color(0xFFFFB3B3), border = Color(0x66FF6B6B), onClick = onCancel)
            else OutlineButton("×", onClick = onForget)
        }
        if (e.active) Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(5.dp)).background(Sm.line)) {
            Box(Modifier.fillMaxWidth(bar).height(5.dp).background(Sm.lamp))
        }
    }
}

/** The site's input-type card: the chosen one gets the amber edge, tinted background and a lit icon. */
@Composable
private fun ModeCard(icon: String, title: String, hint: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(160, easing = Sm.ease), label = "mc")
    val edge by animateColorAsState(if (selected) Sm.lamp else Sm.line, tween(240), label = "mce")
    val bg by animateColorAsState(if (selected) Color(0x1AFFB02E) else Sm.film, tween(240), label = "mcb")
    val disc by animateColorAsState(if (selected) Sm.lamp else Color(0xFF252A37), tween(240), label = "mcd")
    val ink by animateColorAsState(if (selected) Sm.lampInk else Sm.text, tween(240), label = "mci")
    Row(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(14.dp)).background(bg)
            .border(1.dp, edge, RoundedCornerShape(14.dp)).clickable(interactionSource = src, indication = null, onClick = onClick)
            .heightIn(min = 64.dp).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(disc), contentAlignment = Alignment.Center) {
            Text(icon, color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(title, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1)
            Text(hint, color = Sm.muted, fontFamily = Onest, fontSize = 11.5.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun DropZone(hasFiles: Boolean, onPick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val edge by animateColorAsState(if (pressed) Sm.lamp else Color(0xFF4A4E62), tween(160), label = "dz")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF10131B))
            .drawBehind {
                drawRoundRect(edge, cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            }
            .clickable(interactionSource = src, indication = null, onClick = onPick)
            .heightIn(min = if (hasFiles) 88.dp else 130.dp).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text("⇧", color = Sm.lamp, fontSize = 26.sp)
        Text(if (hasFiles) "Добавить ещё файл" else "Выбрать файл .torrent", color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        if (!hasFiles) Text("до 1 МБ, можно несколько", color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
    }
}
