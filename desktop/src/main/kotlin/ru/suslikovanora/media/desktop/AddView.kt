package ru.suslikovanora.media.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File

/** One thing the user added in this session, with its live AllDebrid status. */
private class Added(val key: Long, val name: String) {
    var note by mutableStateOf("Отправляю…")
    var ok by mutableStateOf(true)
    var progress by mutableStateOf(-1f) // 0..1 while downloading, -1 unknown
    var done by mutableStateOf(false)
}

/** Polls AllDebrid's status of a magnet until it is ready (then refreshes the library) or fails. */
private suspend fun follow(a: Added, id: Long, readyNow: Boolean, lib: Library) {
    if (readyNow) { a.note = "Готово — уже в библиотеке"; a.done = true; lib.refresh(); return }
    a.note = "Добавлено, AllDebrid качает…"
    val started = System.currentTimeMillis()
    repeat(120) {
        delay(if (System.currentTimeMillis() - started < 60_000) 4000 else 15000)
        val st = runCatching { Api.get("/api/magnets/$id") }.getOrNull() ?: return@repeat
        val total = st.optLong("size"); val got = st.optLong("downloaded")
        val code = st.optInt("statusCode")
        if (code == 4 || st.optBoolean("ready")) { a.note = "Готово — файлы в библиотеке"; a.progress = 1f; a.done = true; lib.refresh(); return }
        if (code >= 5) { a.note = "Ошибка: ${st.optString("status")}"; a.ok = false; return }
        a.progress = if (total > 0) (got.toFloat() / total).coerceIn(0f, 1f) else -1f
        a.note = st.optString("status").ifBlank { "Обработка" } + if (total > 0) " · ${got * 100 / total}%" else ""
    }
}

@Composable
fun AddView(lib: Library, header: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var magnet by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(true) }
    val added = remember { mutableStateListOf<Added>() }
    var mode by remember { mutableStateOf(0) } // 0 = magnet, 1 = torrent file
    val chosen = remember { mutableStateListOf<File>() }

    fun submitMagnet() {
        val links = magnet.split(Regex("\\s+")).map { it.trim() }.filter { it.startsWith("magnet:?") }
        if (links.isEmpty()) { status = "Вставьте ссылку, которая начинается с magnet:?"; statusOk = false; return }
        magnet = ""; status = null
        for (link in links) {
            val dn = Regex("[?&]dn=([^&]+)").find(link)?.groupValues?.get(1)
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: "Magnet-ссылка"
            val a = Added(System.nanoTime(), dn).also { added.add(0, it) }
            scope.launch {
                try {
                    val r = Api.post("/api/magnets", JSONObject().put("magnet", link))
                    follow(a, r.optLong("id"), r.optBoolean("ready"), lib)
                } catch (e: Exception) { a.note = Api.describe(e); a.ok = false }
            }
        }
    }

    fun uploadFiles(files: List<File>) {
        val torrents = files.filter { it.isFile && it.name.endsWith(".torrent", ignoreCase = true) }
        if (torrents.isEmpty()) { status = "Выберите файл .torrent"; statusOk = false; return }
        status = null
        for (f in torrents) {
            val a = Added(System.nanoTime(), f.name.removeSuffix(".torrent")).also { added.add(0, it) }
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) { f.readBytes() }
                    if (bytes.size > 1024 * 1024) { a.note = "Файл больше 1 МБ"; a.ok = false; return@launch }
                    val r = Api.postTorrent(bytes, f.name)
                    follow(a, r.optLong("id"), r.optBoolean("ready"), lib)
                } catch (e: Exception) { a.note = Api.describe(e); a.ok = false }
            }
        }
    }

    fun pickTorrent() {
        val d = FileDialog(null as Frame?, "Выберите .torrent", FileDialog.LOAD).apply {
            isMultipleMode = true
            file = "*.torrent"
            setFilenameFilter { _, name -> name.endsWith(".torrent", ignoreCase = true) }
        }
        d.isVisible = true // native Windows dialog; returns when closed
        val picked = d.files.filter { it.name.endsWith(".torrent", ignoreCase = true) }
        val tooBig = picked.filter { it.length() > 1024 * 1024 }
        if (tooBig.isNotEmpty()) { status = "Больше 1 МБ: ${tooBig.joinToString { it.name }}"; statusOk = false } else status = null
        picked.filter { it.length() <= 1024 * 1024 && chosen.none { c -> c.absolutePath == it.absolutePath } }.forEach { chosen.add(it) }
    }

    fun pasteFromClipboard() {
        val text = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String }.getOrNull()
        if (text.isNullOrBlank()) { status = "В буфере обмена нет текста"; statusOk = false; return }
        magnet = text.trim(); status = null
    }

    LazyColumn(Modifier.fillMaxSize().background(Sm.night), contentPadding = PaddingValues(28.dp)) {
        // The header (logo, tabs, log out) is drawn by the main window above the pages, so it never moves.
        item {
            Panel {
                Text("Добавить раздачу", fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 22.sp, color = Sm.text)
                Text("Magnet-ссылка или файл .torrent — AllDebrid скачает, файлы появятся в библиотеке.", color = Sm.muted,
                    fontFamily = Onest, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                Text("Выберите тип ввода", color = Sm.muted, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModeCard("↗", "Magnet-ссылка", "Вставьте magnet-ссылку", mode == 0, Modifier.weight(1f)) { mode = 0; status = null }
                    ModeCard("⇧", "Torrent-файл", "Выберите файл .torrent", mode == 1, Modifier.weight(1f)) { mode = 1; status = null }
                }
                Spacer(Modifier.height(16.dp))
                // Same feel as the site: the input area glides in the direction of the chosen card, height follows smoothly.
                AnimatedContent(
                    targetState = mode,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (fadeIn(tween(280, delayMillis = 60, easing = Sm.ease)) + slideInHorizontally(tween(380, easing = Sm.ease)) { dir * it / 10 }) togetherWith
                            (fadeOut(tween(160)) + slideOutHorizontally(tween(380, easing = Sm.ease)) { -dir * it / 10 }) using
                            SizeTransform(clip = false) { _, _ -> tween(320, easing = Sm.ease) }
                    }
                ) { m ->
                    if (m == 0) Column {
                        Text("Magnet-ссылка", color = Sm.muted, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { Field(magnet, { magnet = it }, "magnet:?xt=urn:btih:…", onDone = { submitMagnet() }) }
                            Spacer(Modifier.width(10.dp))
                            LampButton("Добавить magnet", enabled = magnet.isNotBlank()) { submitMagnet() }
                        }
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlineButton("Вставить из буфера") { pasteFromClipboard() }
                            Text("Можно несколько ссылок через пробел.", color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(start = 12.dp))
                        }
                    } else Column {
                        Text("Torrent-файл  (до 1 МБ)", color = Sm.muted, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                        DropZone(hasFiles = chosen.isNotEmpty()) { pickTorrent() }
                        chosen.forEach { f ->
                            Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF11141D))
                                .border(1.dp, Sm.line2, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("TORRENT", color = Sm.lamp, fontFamily = Onest, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0x24FFB02E)).padding(horizontal = 8.dp, vertical = 6.dp))
                                Text(f.name, color = Sm.text, fontFamily = Onest, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                                Text("${(f.length() / 1024).coerceAtLeast(1)} KB", color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp)
                                Spacer(Modifier.width(8.dp))
                                OutlineButton("×") { chosen.remove(f) }
                            }
                        }
                        if (chosen.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            LampButton(if (chosen.size > 1) "Загрузить торренты (${chosen.size})" else "Загрузить торрент", modifier = Modifier.fillMaxWidth()) {
                                val list = chosen.toList(); chosen.clear(); uploadFiles(list)
                            }
                        }
                    }
                }
                status?.let { Text(it, color = if (statusOk) Sm.ok else Sm.bad, fontFamily = Onest, fontSize = 13.5.sp, modifier = Modifier.padding(top = 12.dp)) }
            }
            if (added.isNotEmpty()) Text("Добавлено сейчас", fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = Sm.text,
                modifier = Modifier.padding(top = 26.dp, bottom = 10.dp))
        }
        items(added, key = { it.key }) { a -> AddedRow(a, Modifier.animateItem(fadeInSpec = tween(300), fadeOutSpec = tween(200))) }
        if (added.isEmpty()) item {
            Text("Скачанное появится во вкладке «Библиотека». Лимит — две загрузки одновременно, как на сайте.",
                color = Sm.muted, fontFamily = Onest, fontSize = 13.5.sp, modifier = Modifier.padding(top = 22.dp).widthIn(max = 640.dp))
        }
    }
}

@Composable
private fun AddedRow(a: Added, modifier: Modifier) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val edge by animateColorAsState(if (hovered) Color(0x55FFB02E) else Sm.line, tween(200))
    val bar by androidx.compose.animation.core.animateFloatAsState(a.progress.coerceAtLeast(0f), tween(600))
    Column(modifier.fillMaxWidth().padding(vertical = 3.dp).hoverable(src).clip(RoundedCornerShape(12.dp)).background(Sm.booth)
        .border(1.dp, edge, RoundedCornerShape(12.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.name, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(a.note, color = if (!a.ok) Sm.bad else if (a.done) Sm.ok else Sm.lamp, fontFamily = Onest, fontSize = 13.sp)
        }
        if (a.progress >= 0f && !a.done) {
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(5.dp)).background(Sm.line)) {
                Box(Modifier.fillMaxWidth(bar).height(5.dp).background(Sm.lamp))
            }
        }
    }
}

/** The site's input-type card: icon disc, title + hint; the chosen one gets the amber edge and tint. */
@Composable
private fun ModeCard(icon: String, title: String, hint: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val lift by androidx.compose.animation.core.animateFloatAsState(if (hovered && !selected) -2f else 0f, tween(220, easing = Sm.ease))
    val scale = hoverScale(false, pressed)
    val edge by animateColorAsState(when { selected -> Sm.lamp; hovered -> Color(0xFF5A5F74); else -> Sm.line }, tween(240))
    val bg by animateColorAsState(when { selected -> Color(0x1AFFB02E); hovered -> Color(0xFF1A1D29); else -> Sm.film }, tween(240))
    val disc by animateColorAsState(if (selected) Sm.lamp else Color(0xFF252A37), tween(240))
    val discInk by animateColorAsState(if (selected) Sm.lampInk else Sm.text, tween(240))
    Row(
        modifier.graphicsLayer { translationY = lift; scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(14.dp)).background(bg).border(1.dp, edge, RoundedCornerShape(14.dp))
            .hoverClick(src, onClick = onClick).heightIn(min = 78.dp).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(disc), contentAlignment = Alignment.Center) {
            Text(icon, color = discInk, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
            Text(hint, color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** Dashed drop-zone look; click opens the system file dialog. */
@Composable
private fun DropZone(hasFiles: Boolean, onPick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val edge by animateColorAsState(if (hovered) Sm.lamp else Color(0xFF4A4E62), tween(200))
    val bg by animateColorAsState(if (hovered) Color(0xFF181A22) else Color(0xFF10131B), tween(200))
    val iconLift by androidx.compose.animation.core.animateFloatAsState(if (hovered) -4f else 0f, tween(300, easing = Sm.ease))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg)
            .drawBehind {
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                )
                drawRoundRect(edge, cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()), style = stroke)
            }
            .hoverClick(src, onClick = onPick).heightIn(min = if (hasFiles) 96.dp else 150.dp).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text("⇧", color = Sm.lamp, fontSize = 28.sp, modifier = Modifier.graphicsLayer { translationY = iconLift })
        Text(if (hasFiles) "Добавить ещё файл" else "Выберите файл .torrent", color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            modifier = Modifier.padding(top = 6.dp))
        if (!hasFiles) Text("Нажмите, чтобы открыть файл на компьютере — можно выбрать несколько", color = Sm.muted, fontFamily = Onest, fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp))
    }
}
