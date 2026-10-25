package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.desktop.ui.*

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import java.io.File

/** Строка таблицы предпросмотра: пара из проверки и задержка, которую можно поправить. */
internal class AudioRow(val pair: ru.suslikovanora.media.core.audio.CustomPair) {
    /** Задержка, которой строка заполнилась (из имени файла, уже привязанной дорожки или с этого ПК). */
    var initial = pair.offsetMs ?: 0L
    var offset by mutableStateOf((pair.offsetMs ?: 0L).toString())
    val offsetMs: Long? get() = offset.trim().replace('−', '-').toLongOrNull()?.takeIf { it in -60_000L..60_000L }
}

/** Одна раздача с озвучкой: magnet или .torrent, её проверка и таблица. */
internal class Source(val key: Long, val magnet: String?, val torrent: File?) {
    val name: String = torrent?.name?.removeSuffix(".torrent")
        ?: Regex("[?&]dn=([^&]+)").find(magnet.orEmpty())?.groupValues?.get(1)?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        ?: tr("add.magnet_ssylka")
    var busy by mutableStateOf(false)
    var note by mutableStateOf<String?>(null)
    var noteOk by mutableStateOf(true)
    var valid by mutableStateOf<Boolean?>(null) // null — ещё не проверяли
    var audioId by mutableStateOf<Long?>(null)
    var movieId by mutableStateOf<Long?>(null)
    var candidates by mutableStateOf(emptyList<Pair<Long, String>>())
    var done by mutableStateOf(false)
    /** Раздача уже была в AllDebrid до этого экрана (в медиатеке, с озвучками другого фильма…): её не удаляем никогда. */
    var existed = false
    var allOffset by mutableStateOf("")
    val rows = mutableStateListOf<AudioRow>()
    fun say(text: String, ok: Boolean) { note = text; noteOk = ok }
    val ready: Boolean get() = !busy && !done && valid == true && audioId != null && rows.isNotEmpty() && rows.all { it.offsetMs != null }
}

/** Ждёт, пока AllDebrid докачает раздачу [id]; [note] — что показать. false — ошибка или не дождались. */
private suspend fun waitReady(id: Long, readyNow: Boolean, note: (String) -> Unit): Boolean {
    if (readyNow) return true
    repeat(120) {
        val st = runCatching { Api.get("/api/magnets/$id") }.getOrNull()
        if (st != null) {
            val code = st.optInt("statusCode")
            if (code == 4 || st.optBoolean("ready")) return true
            if (code >= 5) { note(tr("add.oshibka_p", "p" to st.optString("status"))); return false }
            val total = st.optLong("size"); val got = st.optLong("downloaded")
            note(tr("audio.kachaet", "p" to if (total > 0) "${got * 100 / total}%" else st.optString("status")))
        }
        delay(if (it < 15) 4000 else 15000)
    }
    note(tr("audio.ne_dozhdalis")); return false
}

/**
 * «Добавить» → «Озвучка»: несколько раздач с озвучкой (magnet или .torrent), проверка форматов, таблица «файл → фильм/серия»
 * с задержкой, предпросмотр в плеере, «Привязать к библиотеке». Сервер: docs/CUSTOM-AUDIO-API.md.
 * Раздачи загружаются в AllDebrid сразу (для предпросмотра); не привязанные удаляются, когда экран закрыт
 * (и при следующем запуске, если приложение закрыли раньше — [CustomAudio.cleanupPending]).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun AudioAttachPane(lib: Library, onPlay: (ru.suslikovanora.media.desktop.player.PlayRequest) -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val sources = remember { AudioDraft.sources }

    fun failed(s: Source, e: Throwable) {
        val p = e as? CustomAudio.Problem
        if (p?.code == "FORBIDDEN_FILES") {
            s.valid = false
            // лишние файлы: эту раздачу не привяжут — сразу убрать
            s.audioId?.let { if (!s.existed) CustomAudio.dropUnattached(listOf(it)); s.audioId = null }
        }
        if (p?.code == "AMBIGUOUS") {
            val arr = p.body.optJSONArray("candidates")
            s.candidates = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                val c = arr!!.optJSONObject(i) ?: return@mapNotNull null
                val id = c.optLong("magnetId", c.optLong("id")).takeIf { it > 0 } ?: return@mapNotNull null
                id to c.optString("name", c.optString("title", "#$id"))
            }
        }
        s.say(CustomAudio.describe(e), false)
    }

    /** Загрузить раздачу в AllDebrid (если ещё нет), дождаться файлов и проверить на сервере. */
    fun check(s: Source) {
        if (s.busy) return
        s.busy = true; s.valid = null; s.rows.clear(); s.candidates = emptyList()
        scope.launch {
            try {
                if (s.audioId == null) {
                    s.say(tr("add.otpravlyayu"), true)
                    // Что уже есть в AllDebrid до загрузки (список свежий: раздачу могли добавить с другого устройства)
                    runCatching { lib.refresh() }
                    val known = (lib.items.map { it.m.id } + lib.voiceOvers.map { it.id }).toSet()
                    val r = if (s.torrent != null) {
                        val bytes = withContext(Dispatchers.IO) { s.torrent.readBytes() }
                        // Сначала только разбор: раздачу с лишними файлами не загружаем вовсе.
                        CustomAudio.checkTorrent(bytes, s.movieId)
                        Api.postTorrent(bytes, s.torrent.name)
                    } else Api.post("/api/magnets", JSONObject().put("magnet", s.magnet))
                    val id = r.optLong("id")
                    s.audioId = id
                    // AllDebrid отдаёт ту же раздачу, если она уже добавлена (тот же хеш): такую при отмене не трогаем.
                    s.existed = id in known || sources.any { it !== s && it.audioId == id }
                    if (s.existed) PlayerLog.write("[custom-audio] voice-over $id was already in AllDebrid, it will not be deleted")
                    else CustomAudio.markPending(id)
                    if (!waitReady(id, r.optBoolean("ready")) { s.say(it, true) }) { s.noteOk = false; return@launch }
                }
                val c = CustomAudio.check(s.audioId!!, s.movieId)
                s.valid = true
                s.movieId = c.movieMagnetId ?: s.movieId
                val aid = s.audioId!!
                // Уже привязанные к этому фильму дорожки: их задержка важнее всего (свежий список с сервера)
                val bound = s.movieId?.let { m -> runCatching { CustomAudio.tracks(m, fresh = true) }.getOrNull() }.orEmpty()
                s.rows.addAll(c.pairs.map { pair ->
                    AudioRow(pair).also { r ->
                        val known = bound.firstOrNull { it.audioMagnetId == aid && it.audioPath == pair.audioPath && it.videoPath == pair.videoPath }?.offsetMs
                            ?: CustomAudio.knownOffset(aid, pair.audioPath)
                        if (known != null) { r.offset = known.toString(); r.initial = known }
                    }
                })
                s.say(if (c.pairs.isEmpty()) tr("audio.net_par") else tr("audio.naydeno_par", "n" to c.pairs.size), c.pairs.isNotEmpty())
            } catch (e: Exception) { failed(s, e) } finally { s.busy = false }
        }
    }

    fun add(magnet: String?, torrent: File?) {
        if (torrent != null && torrent.length() > 1024 * 1024) { note = tr("add.fayl_bolshe_mb"); return }
        if (sources.any { !it.done && ((magnet != null && it.magnet == magnet) || (torrent != null && it.torrent?.absolutePath == torrent.absolutePath)) }) return
        val s = Source(System.nanoTime(), magnet, torrent)
        sources.add(s); note = null
        check(s)
    }

    fun addInput() {
        val links = input.split(Regex("\\s+")).map { it.trim() }.filter { it.startsWith("magnet:?") }
        if (links.isEmpty()) { note = tr("add.vstavte_ssylku_kotoraya_nachin"); return }
        input = ""
        links.forEach { add(it, null) }
    }

    fun attach(s: Source) {
        if (!s.ready) return
        val wanted = s.rows.associate { it.pair.audioPath to (it.offsetMs ?: 0L) }
        // Строки, где задержку поменяли руками (или «для всех серий»): только их можно записывать поверх уже привязанной дорожки
        val edited = s.rows.filter { it.offsetMs != it.initial }.map { it.pair.audioPath }.toSet()
        s.busy = true
        scope.launch {
            try {
                val tracks = CustomAudio.attach(s.audioId!!, s.movieId)
                var failedOffsets = 0
                // Сервер берёт задержку из имени файла; поправленную в таблице сохраняем каждой дорожке отдельно.
                for (t in tracks) {
                    val path = t.optString("audioPath")
                    val want = wanted[path] ?: continue
                    // 23.10: повторная привязка вернула уже бывшие дорожки, и ПК записал им задержку по умолчанию поверх подобранной
                    if (t.optBoolean("existing") && path !in edited) continue
                    if (want != t.optLong("offsetMs")) runCatching { CustomAudio.saveOffset(t.optString("id"), want) }
                        .onFailure { e -> failedOffsets++; PlayerLog.write("[custom-audio] offset of ${t.optString("id")} not saved: ${e.message}") }
                }
                wanted.forEach { (path, ms) -> CustomAudio.rememberOffset(s.audioId!!, path, ms) }
                CustomAudio.donePending(s.audioId!!)
                s.done = true
                if (failedOffsets > 0) s.say(tr("audio.zaderzhka_ne_sohranena", "n" to failedOffsets), false)
                else s.say(tr("audio.privyazano", "n" to tracks.size), true)
            } catch (e: Exception) { failed(s, e) } finally { s.busy = false }
        }
    }

    fun remove(s: Source) {
        sources.remove(s)
        if (!s.done && !s.existed) s.audioId?.let { CustomAudio.dropUnattached(listOf(it)) }
    }

    /** Предпросмотр: фильм (серия) с этой озвучкой в плеере; задержка из плеера возвращается в строку таблицы. */
    fun preview(s: Source, r: AudioRow) {
        val movie = lib.items.firstOrNull { it.m.id == s.movieId }?.m
        val video = movie?.files?.firstOrNull { it.path == r.pair.videoPath }
        scope.launch {
            try {
                if (movie == null || video?.link == null) { s.say(tr("audio.net_video"), false); return@launch }
                fun audioFile() = lib.voiceOvers.firstOrNull { it.id == s.audioId }?.files?.firstOrNull { it.path == r.pair.audioPath && it.link != null }
                val audio = audioFile() ?: run { lib.refresh(); audioFile() }
                    ?: run { s.say(tr("audio.net_fayla_ozvuchki"), false); return@launch }
                val urls = mirrorUrls(listOf(video, audio))
                if (urls.size < 2) { s.say(tr("api.net_svyazi_s_serverom"), false); return@launch }
                val item = lib.items.firstOrNull { it.m.id == movie.id }
                val track = ru.suslikovanora.media.core.audio.CustomTrack("preview", r.pair.videoPath, r.pair.audioPath, r.pair.voice.ifBlank { tr("audio.predprosmotr") },
                    null, r.offsetMs ?: 0L, false, urls[1])
                onPlay(ru.suslikovanora.media.desktop.player.PlayRequest(urls[0], tr("audio.predprosmotr_t", "t" to (item?.baseTitle ?: video.name)),
                    null, emptyList(), emptyList(), fromStart = true, posterQueries = item?.posterQueries.orEmpty(),
                    preview = track, onPreviewDelay = { d -> r.offset = d.toString() }))
            } catch (e: Exception) { s.say(Api.describe(e), false) }
        }
    }

    val drop = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = event.dragData() as? DragData.FilesList ?: return false
                val list = files.readFiles().map { File(java.net.URI(it)) }.filter { it.name.endsWith(".torrent", true) }
                list.forEach { add(null, it) }
                return list.isNotEmpty()
            }
        }
    }

    Column(Modifier.fillMaxWidth().dragAndDropTarget(shouldStartDragAndDrop = { it.dragData() is DragData.FilesList }, target = drop)) {
        Text(tr("audio.podskazka"), color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Field(input, { input = it }, tr("audio.magnet_ili_torrent"), onDone = { addInput() }) }
            Spacer(Modifier.width(10.dp))
            OutlineButton(tr("audio.torrent")) {
                val d = FileDialog(null as Frame?, tr("add.vyberite_torrent"), FileDialog.LOAD).apply {
                    isMultipleMode = true; file = "*.torrent"; setFilenameFilter { _, n -> n.endsWith(".torrent", true) }
                }
                d.isVisible = true
                d.files.filter { it.name.endsWith(".torrent", true) }.forEach { add(null, it) }
            }
            Spacer(Modifier.width(10.dp))
            LampButton(tr("audio.proverit"), enabled = input.isNotBlank()) { addInput() }
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlineButton(tr("add.vstavit_iz_bufera")) {
                val text = runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as String }.getOrNull()
                if (text.isNullOrBlank()) note = tr("add.v_bufere_obmena_net") else { input = text.trim(); note = null }
            }
            Text(tr("add.mozhno_neskolko_ssylok_cherez"), color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.padding(start = 12.dp))
        }
        note?.let { Text(it, color = Sm.bad, fontFamily = Onest, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }

        sources.forEach { s -> SourceCard(s, lib, onCheck = { check(s) }, onAttach = { attach(s) }, onRemove = { remove(s) },
            onMovie = { id -> s.movieId = id; check(s) }, onPreview = { r -> preview(s, r) }) }

        val readyAll = sources.filter { it.ready }
        if (readyAll.size > 1) LampButton(tr("audio.privyazat_vse", "n" to readyAll.size), modifier = Modifier.padding(top = 16.dp).fillMaxWidth()) {
            readyAll.forEach { attach(it) }
        }
    }
}

/**
 * Раздачи на экране «Озвучка» живут дольше самого экрана: предпросмотр открывает плеер поверх, и экран уходит из композиции.
 * Не привязанные удаляются из AllDebrid, когда пользователь ушёл с вкладки «Добавить» ([leave]) или при выходе
 * (следующий запуск — [CustomAudio.cleanupPending]).
 */
internal object AudioDraft {
    val sources = mutableStateListOf<Source>()
    fun leave() {
        val left = sources.filter { !it.done && !it.existed }.mapNotNull { it.audioId }
        sources.clear()
        if (left.isNotEmpty()) CustomAudio.dropUnattached(left)
    }
}

@Composable
private fun SourceCard(s: Source, lib: Library, onCheck: () -> Unit, onAttach: () -> Unit, onRemove: () -> Unit,
                       onMovie: (Long) -> Unit, onPreview: (AudioRow) -> Unit) {
    Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Sm.booth)
        .border(1.dp, Sm.line, RoundedCornerShape(14.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (s.torrent != null) "📄 " else "🧲 ") + s.name, color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (!s.busy && !s.done && s.valid != true) { OutlineButton(tr("audio.proverit")) { onCheck() }; Spacer(Modifier.width(8.dp)) }
            OutlineButton("×") { onRemove() }
        }
        s.valid?.let { ok ->
            Text(if (ok) "[✓] " + tr("audio.fayly_validny") else "[✗] " + tr("audio.est_lishnie_fayly"),
                color = if (ok) Sm.ok else Sm.bad, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp,
                modifier = Modifier.padding(top = 10.dp))
        }
        s.note?.let { Text(it, color = if (s.noteOk) Sm.muted else Sm.bad, fontFamily = Onest, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
        if (s.candidates.isNotEmpty()) {
            Text(tr("audio.vyberite_film"), color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
            // много вариантов в узком окне переносятся на следующую строку, а не обрезаются
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                s.candidates.take(6).forEach { (id, name) -> OutlineButton(name) { onMovie(id) } }
            }
        }
        if (s.rows.isNotEmpty() && !s.done) {
            val movie = lib.items.firstOrNull { it.m.id == s.movieId }
            Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, Sm.line2, RoundedCornerShape(12.dp))) {
                Row(Modifier.fillMaxWidth().background(Color(0xFF11141D)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Head(tr("audio.kol_fayl"), 1.3f); Head(tr("audio.kol_film"), 1.6f); Head(tr("audio.kol_dorozhka"), 1f); Head(tr("audio.kol_zaderzhka"), 0.9f)
                }
                s.rows.forEach { r -> PairRow(r, movie, lib) { onPreview(r) } }
            }
            if (s.rows.size > 1) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("audio.zaderzhka_dlya_vseh"), color = Sm.muted, fontFamily = Onest, fontSize = 13.sp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.width(110.dp)) { Field(s.allOffset, { v -> s.allOffset = v.filter { it.isDigit() || it == '-' || it == '+' || it == '−' }.take(7) }, "0") }
                Text(tr("common.ms"), color = Sm.muted, fontFamily = Onest, fontSize = 12.sp)
                Spacer(Modifier.width(10.dp))
                val v = s.allOffset.trim().replace('−', '-').toLongOrNull()?.takeIf { it in -60_000L..60_000L }
                OutlineButton(tr("audio.primenit_ko_vsem", "n" to s.rows.size)) { if (v != null) s.rows.forEach { it.offset = v.toString() } }
            }
            LampButton(tr("audio.privyazat"), enabled = s.ready, modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) { onAttach() }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Head(text: String, w: Float) =
    Text(text, color = Sm.muted, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 12.sp, modifier = Modifier.weight(w))

@Composable
private fun PairRow(r: AudioRow, movie: ru.suslikovanora.media.core.LibraryItem?, lib: Library, onPreview: () -> Unit) {
    val p = r.pair
    var poster by remember(movie) { mutableStateOf<ImageBitmap?>(movie?.let { lib.cachedPoster(it.posterQueries) }) }
    LaunchedEffect(movie) { if (poster == null && movie != null) poster = lib.poster(movie.posterQueries) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.audioPath.substringAfterLast('/'), color = Sm.text, fontFamily = Onest, fontSize = 12.5.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1.3f).padding(end = 8.dp))
        Row(Modifier.weight(1.6f).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp, 40.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF1E222D))) {
                poster?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.size(28.dp, 40.dp)) }
            }
            Column(Modifier.padding(start = 8.dp)) {
                Text(movie?.baseTitle ?: "—", color = Sm.text, fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val ep = if (p.season != null && p.episode != null) "S%02dE%02d · ".format(p.season, p.episode) else ""
                Text(ep + p.videoPath.substringAfterLast('/'), color = Sm.muted, fontFamily = Onest, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(p.voice.ifBlank { "—" }, color = Sm.text, fontFamily = Onest, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp))
        Row(Modifier.weight(0.9f), verticalAlignment = Alignment.CenterVertically) {
            OutlineButton("▶") { onPreview() }
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f)) { Field(r.offset, { v -> r.offset = v.filter { it.isDigit() || it == '-' || it == '+' || it == '−' }.take(7) }, "0") }
            Text(tr("common.ms"), color = if (r.offsetMs == null) Sm.bad else Sm.muted, fontFamily = Onest, fontSize = 12.sp)
        }
    }
    Spacer(Modifier.height(1.dp).fillMaxWidth().background(Sm.line))
}
