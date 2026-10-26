package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*
import ru.suslikovanora.media.core.torrent.TorrentInspector
import ru.suslikovanora.media.desktop.ui.*

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
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.dragData

/** One thing the user added in this session, with its live AllDebrid status. */
private class Added(val key: Long, val name: String) {
    var note by mutableStateOf(tr("add.otpravlyayu"))
    var ok by mutableStateOf(true)
    var progress by mutableStateOf(-1f) // 0..1 while downloading, -1 unknown
    var done by mutableStateOf(false)
}

/**
 * Раздача без единого видеофайла (игра, музыка, софт): текст отказа, иначе null.
 * Список файлов AllDebrid отдаёт только готовой раздаче (`/api/magnets/:id/files`), поэтому проверка — в момент
 * готовности. Такую раздачу просим удалить; если сервер не даёт (готовую удаляет только админ) — так и пишем.
 * Не получили список — не мешаем (null).
 */
internal suspend fun rejectIfNoVideo(id: Long): String? {
    val files = runCatching { Api.get("/api/magnets/$id/files").optJSONArray("files") }.getOrNull() ?: return null
    val names = (0 until files.length()).map { files.optJSONObject(it)?.optString("name").orEmpty() }
    val rejected = TorrentInspector.rejectionForPaths(names) ?: return null
    val removed = runCatching { Api.post("/api/magnets/$id/cancel") }.isSuccess
    return rejected + if (removed) "" else " · её удалит админ или автоочистка"
}

/** Polls AllDebrid's status of a magnet until it is ready (then refreshes the library) or fails. */
private suspend fun follow(a: Added, id: Long, readyNow: Boolean, lib: Library) {
    suspend fun ready(note: String) {
        val rejected = rejectIfNoVideo(id)
        if (rejected != null) { a.note = rejected; a.ok = false } else { a.note = note; a.progress = 1f }
        a.done = true; lib.refresh()
    }
    if (readyNow) { ready(tr("add.gotovo_uzhe_v_biblioteke")); return }
    a.note = tr("add.dobavleno_alldebrid_kachaet")
    val started = System.currentTimeMillis()

    repeat(120) {
        delay(if (System.currentTimeMillis() - started < 60_000) 4000 else 15000)
        val st = runCatching { Api.get("/api/magnets/$id") }.getOrNull() ?: return@repeat
        val total = st.optLong("size"); val got = st.optLong("downloaded")
        val code = st.optInt("statusCode")
        if (code == 4 || st.optBoolean("ready")) { ready(tr("add.gotovo_fayly_v_biblioteke")); return }
        if (code >= 5) { a.note = tr("add.oshibka_p", "p" to st.optString("status")); a.ok = false; return }
        a.progress = if (total > 0) (got.toFloat() / total).coerceIn(0f, 1f) else -1f
        a.note = st.optString("status").ifBlank { tr("add.obrabotka") } + if (total > 0) " · ${got * 100 / total}%" else ""
    }
}

@Composable
fun AddView(lib: Library, onPlay: (ru.suslikovanora.media.desktop.player.PlayRequest) -> Unit = {}, header: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var magnet by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(true) }
    val added = remember { mutableStateListOf<Added>() }
    var mode by remember { mutableStateOf(0) } // 0 = film (magnet or .torrent), 1 = voice-over for a film in the library
    // Ушли с вкладки «Добавить» — не привязанные озвучки удаляются из AllDebrid (предпросмотр вкладку не закрывает).
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { AudioDraft.leave() } }

    fun submitMagnet() {
        val links = magnet.split(Regex("\\s+")).map { it.trim() }.filter { it.startsWith("magnet:?") }
        if (links.isEmpty()) { status = tr("add.vstavte_ssylku_kotoraya_nachin"); statusOk = false; return }
        magnet = ""; status = null
        for (link in links) {
            val dn = Regex("[?&]dn=([^&]+)").find(link)?.groupValues?.get(1)
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: tr("add.magnet_ssylka")

            // Immediate pre-check if dn ends with known non-video format
            val a = Added(System.nanoTime(), dn).also { added.add(0, it) }
            val reason = TorrentInspector.rejectionForMagnetName(dn)
            if (reason != null) { a.note = reason; a.ok = false; continue }
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
        if (torrents.isEmpty()) { status = tr("add.vyberite_fayl_torrent"); statusOk = false; return }
        status = null
        for (f in torrents) {
            val a = Added(System.nanoTime(), f.name.removeSuffix(".torrent")).also { added.add(0, it) }
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) { f.readBytes() }
                    if (bytes.size > 1024 * 1024) { a.note = tr("add.fayl_bolshe_mb"); a.ok = false; return@launch }

                    // Pre-inspect .torrent files to ensure they contain video files
                    val rejected = TorrentInspector.rejectionForTorrent(bytes)
                    if (rejected != null) {
                        a.note = rejected
                        a.ok = false
                        return@launch
                    }

                    val r = Api.postTorrent(bytes, f.name)
                    follow(a, r.optLong("id"), r.optBoolean("ready"), lib)
                } catch (e: Exception) { a.note = Api.describe(e); a.ok = false }
            }
        }
    }

    /** .torrent из диалога или перетащенные: загружаются сразу (как на экране «Озвучка»). */
    fun takeTorrents(files: List<File>) {
        val picked = files.filter { it.name.endsWith(".torrent", ignoreCase = true) }
        if (picked.isEmpty()) return
        val tooBig = picked.filter { it.length() > 1024 * 1024 }
        if (tooBig.isNotEmpty()) { status = tr("add.bolshe_mb_p", "p" to tooBig.joinToString { it.name }); statusOk = false } else status = null
        picked.filter { it.length() <= 1024 * 1024 }.takeIf { it.isNotEmpty() }?.let { uploadFiles(it) }
    }

    fun pickTorrent() {
        val d = FileDialog(null as Frame?, tr("add.vyberite_torrent"), FileDialog.LOAD).apply {
            isMultipleMode = true
            file = "*.torrent"
            setFilenameFilter { _, name -> name.endsWith(".torrent", ignoreCase = true) }
        }
        d.isVisible = true // native Windows dialog; returns when closed
        takeTorrents(d.files.toList())
    }

    fun pasteFromClipboard() {
        val text = runCatching { Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String }.getOrNull()
        if (text.isNullOrBlank()) { status = tr("add.v_bufere_obmena_net"); statusOk = false; return }
        magnet = text.trim(); status = null
    }

    LazyColumn(Modifier.fillMaxSize(),contentPadding = PaddingValues(28.dp)) {
        // The header (logo, tabs, log out) is drawn by the main window above the pages, so it never moves.
        item {
            Panel {
                Text(tr("add.dobavit_razdachu"), fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 22.sp, color = Sm.text)
                Text(tr("add.magnet_ssylka_ili_fayl"), color = Sm.muted,
                    fontFamily = Onest, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                Text(tr("add.vyberite_tip_vvoda"), color = Sm.muted, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModeCard("▶", tr("add.film"), tr("add.film_podskazka"), mode == 0, Modifier.weight(1f)) { mode = 0; status = null }
                    ModeCard("♪", tr("audio.ozvuchka"), tr("audio.k_filmu_v_biblioteke"), mode == 1, Modifier.weight(1f)) { mode = 1; status = null }
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
                    if (m == 1) AudioAttachPane(lib, onPlay)
                    else FilmPane(magnet, { magnet = it }, ::submitMagnet, ::pickTorrent, ::pasteFromClipboard, ::takeTorrents)
                }
                status?.let { Text(it, color = if (statusOk) Sm.ok else Sm.bad, fontFamily = Onest, fontSize = 13.5.sp, modifier = Modifier.padding(top = 12.dp)) }
            }
            if (added.isNotEmpty()) Text(tr("add.dobavleno_seychas"), fontFamily = Unbounded, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = Sm.text,
                modifier = Modifier.padding(top = 26.dp, bottom = 10.dp))
        }
        items(added, key = { it.key }) { a -> AddedRow(a, Modifier.animateItem(fadeInSpec = tween(300), fadeOutSpec = tween(200))) }
        if (added.isEmpty()) item {
            Text(tr("add.skachannoe_poyavitsya_vo_vklad"),
                color = Sm.muted, fontFamily = Onest, fontSize = 13.5.sp, modifier = Modifier.padding(top = 22.dp).widthIn(max = 640.dp))
        }
    }
}

@Composable
private fun AddedRow(a: Added, modifier: Modifier) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val edge by animateColorAsState(if (hovered) Color(0xFF3E475C) else Sm.line, tween(200))
    val bar by androidx.compose.animation.core.animateFloatAsState(a.progress.coerceAtLeast(0f), tween(600))
    Column(modifier.fillMaxWidth().padding(vertical = 3.dp).hoverable(src).clip(RoundedCornerShape(12.dp)).background(Sm.booth)
        .border(1.dp, edge, RoundedCornerShape(12.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.name, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(a.note, color = if (!a.ok) Sm.bad else if (a.done) Sm.ok else Color.White, fontFamily = Onest, fontSize = 13.sp)
        }
        if (a.progress >= 0f && !a.done) {
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(5.dp)).background(Sm.line)) {
                Box(Modifier.fillMaxWidth(bar).height(5.dp).background(Color.White))
            }
        }
    }
}

/**
 * «Фильм»: magnet-ссылки и .torrent в одном поле, как на экране «Озвучка» — ссылки вставляют (можно несколько),
 * .torrent выбирают кнопкой или перетаскивают на панель; торренты загружаются сразу.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun FilmPane(magnet: String, onMagnet: (String) -> Unit, onSubmit: () -> Unit, onPick: () -> Unit, onPaste: () -> Unit,
                     onDrop: (List<File>) -> Unit) {
    val drop = remember(onDrop) {
        object : androidx.compose.ui.draganddrop.DragAndDropTarget {
            override fun onDrop(event: androidx.compose.ui.draganddrop.DragAndDropEvent): Boolean {
                val files = event.dragData() as? androidx.compose.ui.draganddrop.DragData.FilesList ?: return false
                val list = files.readFiles().map { File(java.net.URI(it)) }.filter { it.name.endsWith(".torrent", true) }
                onDrop(list)
                return list.isNotEmpty()
            }
        }
    }
    Column(Modifier.fillMaxWidth().dragAndDropTarget(
        shouldStartDragAndDrop = { it.dragData() is androidx.compose.ui.draganddrop.DragData.FilesList }, target = drop)) {
        Text(tr("add.film_tekst"), color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Field(magnet, onMagnet, tr("audio.magnet_ili_torrent"), onDone = onSubmit) }
            Spacer(Modifier.width(10.dp))
            OutlineButton(tr("audio.torrent")) { onPick() }
            Spacer(Modifier.width(10.dp))
            LampButton(tr("add.dobavit_magnet"), enabled = magnet.isNotBlank()) { onSubmit() }
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlineButton(tr("add.vstavit_iz_bufera")) { onPaste() }
            Text(tr("add.mozhno_neskolko_ssylok_cherez"), color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

/** The site's input-type card: icon disc, title + hint; slate dark theme when selected. */
@Composable
private fun ModeCard(icon: String, title: String, hint: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val lift by androidx.compose.animation.core.animateFloatAsState(if (hovered && !selected) -2f else 0f, tween(220, easing = Sm.ease))
    val scale = hoverScale(false, pressed)
    val edge by animateColorAsState(when { selected -> Color(0xFF3E475C); hovered -> Color(0xFF2C3344); else -> Color(0xFF222735) }, tween(240))
    val bg by animateColorAsState(when { selected -> Color(0xFF1C202B); hovered -> Color(0xFF181B24); else -> Color(0xFF141720) }, tween(240))
    val disc by animateColorAsState(if (selected) Color(0xFF282E3C) else Color(0xFF1E222D), tween(240))
    val discInk by animateColorAsState(if (selected) Color.White else Color(0xFF8E96A8), tween(240))
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
            Text(title, color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
            Text(hint, color = Color(0xFF8E96A8), fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** Dashed drop-zone look; click opens the system file dialog. */
@Composable
private fun DropZone(hasFiles: Boolean, onPick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val edge by animateColorAsState(if (hovered) Color.White else Color(0xFF4A4E62), tween(200))
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
        Text("⇧", color = if (hovered) Color.White else Color(0xFF8E96A8), fontSize = 28.sp, modifier = Modifier.graphicsLayer { translationY = iconLift })
        Text(if (hasFiles) tr("add.dobavit_esche_fayl") else tr("add.vyberite_fayl_torrent"), color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            modifier = Modifier.padding(top = 6.dp))
        if (!hasFiles) Text(tr("add.nazhmite_chtoby_otkryt_fayl"), color = Sm.muted, fontFamily = Onest, fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp))
    }
}
