package ru.suslikovanora.media.library

import ru.suslikovanora.media.ui.focusRing
import ru.suslikovanora.media.core.*
import ru.suslikovanora.media.core.torrent.TorrentInspector

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.suslikovanora.media.net.Api
import ru.suslikovanora.media.ui.Onest
import ru.suslikovanora.media.ui.Sm
import ru.suslikovanora.media.ui.Unbounded
import ru.suslikovanora.media.tr

/** One thing added from this device: a magnet or a .torrent. Kept between app starts. */
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

/** A .torrent chosen on the device (read when uploading). */
data class PickedTorrent(val uri: Uri, val name: String, val size: Long)

/**
 * State of the «Добавить» tab, owned by the activity: input, chosen files, history with live AllDebrid status.
 * History is saved on the device (like the site's «История поиска»); unfinished downloads resume polling on start.
 */
class AddModel(context: Context, private val scope: CoroutineScope, private val store: LibraryStore) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("add_history", Context.MODE_PRIVATE)
    var mode by mutableStateOf(0) // 0 = magnet, 1 = torrent file
    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
    var status by mutableStateOf<String?>(null)
    var statusOk by mutableStateOf(true)
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
        if (v.isEmpty()) { say(tr("add.vstavte_magnet_ssylku"), false); return }
        // Only magnets: links to file hosts are not opened through the shared AllDebrid account (security audit 25.10).
        if (!v.startsWith("magnet:?", ignoreCase = true)) { say(tr("add.vstavte_ssylku_kotoraya_nachin"), false); return }
        val links = v.split(Regex("\\s+")).filter { it.startsWith("magnet:?", true) }
        input = ""; links.forEach { addMagnet(it) }
    }

    private fun magnetName(link: String): String =
        Regex("[?&]dn=([^&]+)").find(link)?.groupValues?.get(1)?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?: tr("add.zagruzka_po_magnet_ssylke")

    private fun addMagnet(link: String) {
        val e = AddEntry("m:" + System.nanoTime(), magnetName(link), 0, tr("add.otpravlyayu"), -1, false, false, System.currentTimeMillis())
        history.add(0, e)
        // Имя dn уже говорит, что это не видео — на сервер не отправляем
        TorrentInspector.rejectionForMagnetName(e.title)?.let { e.status = it; e.failed = true; save(); return }
        scope.launch {
            try {
                val r = Api.post("/api/magnets", JSONObject().put("magnet", link), repeatable = false)
                accepted(e, r)
            } catch (ex: Exception) { e.status = Api.describe(ex); e.failed = true; save() }
        }
    }

    fun uploadChosen(context: Context) {
        val list = chosen.toList(); chosen.clear()
        for (t in list) {
            val e = AddEntry("t:" + System.nanoTime(), t.name.removeSuffix(".torrent"), 0, tr("add.otpravlyayu"), -1, false, false, System.currentTimeMillis())
            history.add(0, e)
            scope.launch {
                try {
                    // At most 1 MB + 1 byte: a film picked by mistake is not read into memory whole
                    val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(t.uri)?.use { readAtMost(it, 1024 * 1024 + 1) } }
                        ?: throw IllegalStateException(tr("add.ne_udalos_prochitat_fayl"))
                    if (bytes.size > 1024 * 1024) throw IllegalStateException(tr("add.fayl_bolshe_mb"))
                    // Список файлов виден в самом .torrent — раздачу без видео на сервер не отправляем
                    TorrentInspector.rejectionForTorrent(bytes)?.let { e.status = it; e.failed = true; save(); return@launch }
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
            if (ready) tr("settings.gotovo") else tr("add.dobavleno_alldebrid_kachaet"), if (ready) 100 else -1, ready, false, placeholder.at)
        // the same torrent added again: keep one row
        history.removeAll { it !== placeholder && it.magnetId == id && id > 0 }
        val i = history.indexOf(placeholder)
        if (i >= 0) history[i] = e else history.add(0, e)
        save()
        if (ready) scope.launch { noVideoCheck(e); store.refresh() } else follow(e)
    }

    /**
     * Раздача готова: список файлов AllDebrid отдаёт только теперь. Нет видео — просим удалить (готовую удаляет только админ).
     * Не получили список — не мешаем.
     */
    private suspend fun noVideoCheck(e: AddEntry) {
        val files = runCatching { Api.get("/api/magnets/${e.magnetId}/files").optJSONArray("files") }.getOrNull() ?: return
        val rejected = TorrentInspector.rejectionForPaths((0 until files.length()).map { files.optJSONObject(it)?.optString("name").orEmpty() }) ?: return
        val removed = runCatching { Api.post("/api/magnets/${e.magnetId}/cancel") }.isSuccess
        e.ready = false; e.failed = true
        e.status = rejected + if (removed) "" else " · её удалит админ или автоочистка"
        save()
    }

    private fun follow(e: AddEntry) {
        if (polling[e.magnetId]?.isActive == true) return
        polling[e.magnetId] = scope.launch {
            val started = System.currentTimeMillis()
            while (e.active) {
                ru.suslikovanora.media.Foreground.pause(if (System.currentTimeMillis() - started < 60_000) 4000 else 15000)
                val st = runCatching { Api.get("/api/magnets/${e.magnetId}") }.getOrNull() ?: continue
                val total = st.optLong("size"); val done = st.optLong("downloaded")
                val code = st.optInt("statusCode")
                val before = Triple(e.title, e.status, e.percent)
                st.optString("filename").takeIf { it.isNotBlank() }?.let { e.title = it }
                when {
                    code == 4 || st.optBoolean("ready") -> {
                        e.ready = true; e.percent = 100; e.status = tr("add.gotovo_v_biblioteke"); save()
                        noVideoCheck(e); store.refresh()
                    }
                    code >= 5 -> { e.failed = true; e.status = tr("add.oshibka_p", "p" to st.optString("status")); save() }
                    else -> {
                        e.status = st.optString("status").ifBlank { tr("add.obrabotka") }; e.percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (Triple(e.title, e.status, e.percent) != before) save() // the phone's storage only when something changed
                    }
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
                e.failed = true; e.status = tr("add.otmeneno"); save(); store.refresh()
            } catch (ex: Exception) { say(Api.describe(ex), false) }
        }
    }

    fun forget(e: AddEntry) { history.remove(e); save() }
    fun clearFinished() { history.removeAll { !it.active }; save() }
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
fun AddScreen(m: AddModel, listState: LazyListState, onLogout: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        for (u in uris) {
            val (name, size) = displayName(context, u)
            if (!name.endsWith(".torrent", true)) { m.status = tr("add.ne_torrent_name", "name" to name); m.statusOk = false; continue }
            if (size > 1024 * 1024) { m.status = tr("add.bolshe_mb_name", "name" to name); m.statusOk = false; continue }
            if (m.chosen.none { it.uri == u }) m.chosen += PickedTorrent(u, name, size)
        }
    }
    LazyColumn(
        modifier.fillMaxSize(), state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 80.dp)
    ) {
        item(key = "header") { HeaderSlot(); Spacer(Modifier.height(16.dp)) }
        item(key = "panel") {
            Panel(Modifier.animateContentSize(tween(280, easing = Sm.ease))) {
                Text(tr("add.dobavit_razdachu"), fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = Color.White)
                Text(tr("add.magnet_ssylka_ili_fayl"),
                    color = Sm.muted, fontFamily = Onest, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeCard("↗", tr("add.ssylka"), tr("add.magnet_ssylka"), m.mode == 0, Modifier.weight(1f)) { m.mode = 0; m.status = null }
                    ModeCard("⇧", tr("add.torrent_fayl"), tr("add.fayl_torrent"), m.mode == 1, Modifier.weight(1f)) { m.mode = 1; m.status = null }
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
                        SearchField(m.input, { m.input = it }, tr("add.magnet_xt"), onDone = { m.submitInput() })
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LampButton(if (m.busy) tr("add.vypolnyaetsya") else tr("add.dobavit"), enabled = !m.busy && m.input.isNotBlank(), modifier = Modifier.weight(1f)) { m.submitInput() }
                            OutlineButton(tr("add.vstavit")) {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val t = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                                if (t.isNullOrBlank()) { m.status = tr("add.v_bufere_obmena_net"); m.statusOk = false } else m.input = t
                            }
                        }
                    } else Column {
                        DropZone(hasFiles = m.chosen.isNotEmpty()) { runCatching { picker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) } }
                        m.chosen.forEach { f ->
                            Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Sm.glassFill)
                                .border(1.dp, Sm.glassEdge, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("TORRENT", color = Color.White, fontFamily = Onest, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Sm.accent).padding(horizontal = 7.dp, vertical = 5.dp))
                                Text(f.name, color = Sm.text, fontFamily = Onest, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                                if (f.size > 0) Text("${(f.size / 1024).coerceAtLeast(1)} KB", color = Sm.muted, fontFamily = Onest, fontSize = 12.sp)
                                Spacer(Modifier.width(6.dp))
                                OutlineButton("×") { m.chosen.remove(f) }
                            }
                        }
                        if (m.chosen.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            LampButton(if (m.chosen.size > 1) tr("add.zagruzit_torrenty_size", "size" to m.chosen.size) else tr("add.zagruzit_torrent"), modifier = Modifier.fillMaxWidth()) { m.uploadChosen(context) }
                        }
                    }
                }
                m.status?.let { Text(it, color = if (m.statusOk) Sm.ok else Sm.bad, fontFamily = Onest, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp)) }
            }
        }
        if (m.history.isNotEmpty()) item(key = "historyHead") {
            Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("add.istoriya"), fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color.White, modifier = Modifier.weight(1f))
                if (m.history.any { !it.active }) OutlineButton(tr("settings.ochistit")) { m.clearFinished() }
            }
        }
        items(m.history, key = { it.key }) { e -> HistoryRow(e, Modifier.animateItem(fadeInSpec = tween(260), fadeOutSpec = tween(180)), { m.cancel(e) }, { m.forget(e) }) }
        if (m.history.isEmpty()) item(key = "hint") {
            Text(tr("add.skachannoe_poyavitsya_vo_vklad"),
                color = Sm.muted, fontFamily = Onest, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 18.dp))
        }
    }
}

@Composable
private fun HistoryRow(e: AddEntry, modifier: Modifier, onCancel: () -> Unit, onForget: () -> Unit) {
    val bar by animateFloatAsState(e.percent.coerceAtLeast(0) / 100f, tween(600), label = "bar")
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Sm.glassFill)
        .border(1.dp, Sm.glassEdge, RoundedCornerShape(14.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.title, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(e.status + if (e.active && e.percent >= 0) " · ${e.percent}%" else "",
                    color = when { e.failed -> Sm.bad; e.ready -> Sm.ok; else -> Color(0xFF60A5FA) }, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Spacer(Modifier.width(8.dp))
            if (e.active) OutlineButton(tr("add.otmenit"), color = Color(0xFFFFB3B3), border = Color(0x66FF6B6B), onClick = onCancel)
            else OutlineButton("×", onClick = onForget)
        }
        if (e.active) Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(5.dp)).background(Sm.line)) {
            Box(Modifier.fillMaxWidth(bar).height(5.dp).background(Sm.accent))
        }
    }
}

/** The site's input-type card: sleek frosted glass with electric blue highlight when selected. */
@Composable
private fun ModeCard(icon: String, title: String, hint: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(160, easing = Sm.ease), label = "mc")
    val edge by animateColorAsState(if (selected) Sm.accentEdge else Sm.glassEdge, tween(240), label = "mce")
    val bg by animateColorAsState(if (selected) Sm.accentSoft else Sm.glassFill, tween(240), label = "mcb")
    val disc by animateColorAsState(if (selected) Sm.accent else Color(0xFF1E222D), tween(240), label = "mcd")
    val ink by animateColorAsState(if (selected) Color.White else Color(0xFF8E96A8), tween(240), label = "mci")
    Row(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(14.dp)).background(bg)
            .border(1.dp, edge, RoundedCornerShape(14.dp)).focusRing(RoundedCornerShape(14.dp)).clickable(interactionSource = src, indication = null, onClick = onClick)
            .heightIn(min = 64.dp).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(disc), contentAlignment = Alignment.Center) {
            Text(icon, color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(title, color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1)
            Text(hint, color = Sm.muted, fontFamily = Onest, fontSize = 11.5.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun DropZone(hasFiles: Boolean, onPick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val edge by animateColorAsState(if (pressed) Sm.accent else Sm.glassEdgeHover, tween(160), label = "dz")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Sm.glassFill)
            .drawBehind {
                drawRoundRect(edge, cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            }
            .focusRing(RoundedCornerShape(16.dp))
            .clickable(interactionSource = src, indication = null, onClick = onPick)
            .heightIn(min = if (hasFiles) 88.dp else 130.dp).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text("⇧", color = Sm.accent, fontSize = 26.sp)
        Text(if (hasFiles) tr("add.dobavit_esche_fayl") else tr("add.vybrat_fayl_torrent"), color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        if (!hasFiles) Text(tr("add.do_mb_mozhno_neskolko"), color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Up to [limit] bytes of [input] (fewer if it ends sooner). */
private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
