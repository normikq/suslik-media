package ru.suslikovanora.media.desktop.player

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.suslikovanora.media.core.BurstProxy
import ru.suslikovanora.media.core.CatchUp
import ru.suslikovanora.media.core.Chapter
import ru.suslikovanora.media.core.MarkersDetector
import ru.suslikovanora.media.core.MediaMarkers
import ru.suslikovanora.media.core.TimeRange
import ru.suslikovanora.media.core.PlaybackSupervisor
import ru.suslikovanora.media.desktop.Store
import ru.suslikovanora.media.desktop.Api
import ru.suslikovanora.media.desktop.AppExit
import ru.suslikovanora.media.desktop.CrashReports
import ru.suslikovanora.media.desktop.Diag
import ru.suslikovanora.media.desktop.Library
import ru.suslikovanora.media.desktop.MIN_H
import ru.suslikovanora.media.desktop.MIN_W
import ru.suslikovanora.media.desktop.PlaybackSettings
import ru.suslikovanora.media.desktop.PlayerLog
import ru.suslikovanora.media.desktop.Reserve
import ru.suslikovanora.media.desktop.WindowMode
import ru.suslikovanora.media.desktop.ui.Onest
import ru.suslikovanora.media.desktop.ui.Sm
import javax.swing.SwingUtilities
import ru.suslikovanora.media.desktop.tr
import ru.suslikovanora.media.desktop.signed

// Панели плеера, dp: верхняя, нижняя (с местом под всплывающие меню) и её полоса с кнопками
private const val TOP_BAR_H = 75
private const val BOTTOM_BAR_H = 460
private const val BOTTOM_INPUT_H = 220
// Сохранённый выбор субтитров «как решат умные субтитры» (после смены озвучки)
private const val SMART_SUBS = "__smart__"

/**
 * Player inside the main window. mpv draws its frames into the window's own OpenGL scene ([MpvVideoSurface]), so the
 * picture and the Compose bars are layers of one surface.
 *
 * [keys] is the main window's key hook: the player installs its shortcuts there while it is on screen.
 */
@Composable
fun PlayerView(
    req: PlayRequest,
    library: Library,
    win: WindowState,
    keys: MutableState<((KeyEvent) -> Boolean)?>,
    onTop: MutableState<Boolean>,           // main window "always on top" (mini-player / pin)
    startIndex: Int = 0,                    // playlist episode to start with (after a settings restart)
    parentWindow: java.awt.Window? = null,  // main window
    onRestart: (Int) -> Unit = {},          // re-create the player at this episode (subtitle size)
    onClose: () -> Unit
) {
    val ui = Store.of("player_ui")
    val scope = rememberCoroutineScope()
    val playerExecutor = remember {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "player-control").apply { isDaemon = true }
        }.also { AppExit.track(it) }
    }
    val playerThread = remember { playerExecutor.asCoroutineDispatcher() }

    fun runOnPlayer(block: () -> Unit) { runCatching { playerExecutor.execute { runCatching(block) } } }

    suspend fun <T> playerAwait(ms: Long, block: () -> T): T? {
        val job = scope.async(playerThread) { runCatching(block).getOrNull() }
        return withTimeoutOrNull(ms) { job.await() }
    }

    suspend fun <T> playerPoll(pending: java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Deferred<*>?>, ms: Long, block: () -> T): T? {
        if (pending.get()?.isActive == true) return null
        val job = scope.async(playerThread) { runCatching(block).getOrNull() }
        pending.set(job)
        return withTimeoutOrNull(ms) { job.await() }
    }

    var nightMode by remember { mutableStateOf(ui.get("night") == "1") }
    var subScale by remember { mutableStateOf(ui.get("subScale")?.toIntOrNull()?.coerceIn(50, 300) ?: 100) }
    val owner = parentWindow ?: runCatching { java.awt.Frame.getFrames().firstOrNull { it.isShowing } }.getOrNull()
    val skikoGl = remember { SkikoGl.find(owner) }
    val engBridge = remember { EngineBridge() }
    val engine: MpvEngine = remember { MpvEngine(engBridge, PlaybackSettings.cacheMs(), subScale) }
    val videoOut = remember { skikoGl?.let { MpvVideoOut(it) } }
    var sceneWidthPx by remember { mutableStateOf(0) }

    fun eTime(): Long = engine.timeMs
    fun eSetTime(t: Long) = engine.seekTo(t)
    fun ePause(b: Boolean) = engine.setPaused(b)
    fun eMute(b: Boolean) = engine.setMute(b)
    fun eVolume(v: Int) = engine.setVolume(v)
    fun eAudioDelay(ms: Long) = engine.setAudioDelayMs(ms)
    fun eSubDelay(ms: Long) = engine.setSubDelayMs(ms)
    fun eRate(r: Float) = engine.setRate(r)
    fun eStop() = engine.stop()
    fun ePlayable(): Boolean = engine.isPlayable
    fun eAudioTracks(): List<Pair<Int, String>> = engine.audioTracks().map { it.id to it.description }
    fun eSubTracks(): List<Pair<Int, String>> = engine.subtitleTracks().map { it.id to it.description }
    fun eAudioCur(): Int = engine.audioTracks().firstOrNull { it.selected }?.id ?: -1
    fun eSubCur(): Int = engine.subtitleTracks().firstOrNull { it.selected }?.id ?: -1
    fun eSetAudio(id: Int) = engine.setAudioTrack(id)
    fun eSetSub(id: Int) = engine.setSubtitleTrack(id)
    fun eDim(): Pair<Int, Int>? = engine.videoSize

    var index by remember { mutableStateOf(startIndex) }
    fun watchKey(i: Int = index) = req.watchKeys.getOrNull(i)
    // Гость комнаты совместного просмотра: ведёт комната — своей перемотки и смены серии нет
    fun partyGuest(): Boolean = ru.suslikovanora.media.desktop.party.Party.let { it.inRoom && !it.isHost && it.file?.key == watchKey() }
    fun savePos(t: Long, len: Long, i: Int = index) {
        val key = watchKey(i) ?: return
        if (t < 5_000L) return
        val done = len > 0 && t > len - 15_000L
        val pos = if (done) len else t
        val at = System.currentTimeMillis()
        library.watch.put(key, "$pos|${len.coerceAtLeast(0)}|$at")
        ru.suslikovanora.media.desktop.CloudSync.noteWatch(key, pos, len.coerceAtLeast(0), at)
    }
    fun savedPos(i: Int = index): Long {
        val w = watchKey(i)?.let { library.watchInfo(it) } ?: return 0L
        return if (w.watched) 0L else w.pos
    }

    var title by remember { mutableStateOf(req.title) }
    var playing by remember { mutableStateOf(false) }
    val initialPos = remember { if (req.fromStart) 0L else savedPos(startIndex) }
    var time by remember { mutableStateOf(initialPos) }
    var length by remember { mutableStateOf(0L) }
    var status by remember { mutableStateOf<String?>(tr("player.otkryvaem")) }
    var volume by remember { mutableStateOf(ui.get("volume")?.toIntOrNull() ?: 100) }
    var lastVolumeWheelAt by remember { mutableStateOf(0L) }
    var queue by remember { mutableStateOf(listOf(Entry(req.title, req.url))) }
    var currentUrl by remember { mutableStateOf(req.url) }
    var nextIn by remember { mutableStateOf(-1) }
    var barVisible by remember { mutableStateOf(true) }
    var lastMove by remember { mutableStateOf(System.currentTimeMillis()) }
    var started by remember { mutableStateOf(false) }
    // Another episode is chosen, the new file is not open yet: up to 4 s go on waiting for its voice-overs, and all that
    // time mpv still plays the old one. Its time and «playing» are not the new episode's (they started the player at the
    // old place, and the room went to 11:24 of the next episode — journal 20.10, 20:43:26 and 20:59:07).
    var switching by remember { mutableStateOf(false) }
    var enginePaused by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var seekByJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var pendingSeekByTarget by remember { mutableStateOf<Long?>(null) }
    var retries by remember { mutableStateOf(0) }
    var lastGood by remember { mutableStateOf(initialPos) }
    var closing by remember { mutableStateOf(false) }
    var prefsApplied by remember { mutableStateOf(false) }
    var pausedAt by remember { mutableStateOf(0L) }
    var userMuted by remember { mutableStateOf(false) }
    var audioDelay by remember { mutableStateOf(0L) }
    // Внешние озвучки с сервера для открытого файла (docs/CUSTOM-AUDIO-API.md) и выбранная из них.
    var customTracks by remember { mutableStateOf(emptyList<ru.suslikovanora.media.core.audio.CustomTrack>()) }
    var activeCustom by remember { mutableStateOf<ru.suslikovanora.media.core.audio.CustomTrack?>(null) }
    var endCut by remember { mutableStateOf(false) }
    var deleteArmed by remember { mutableStateOf<String?>(null) } // id дорожки после первого нажатия «Удалить»
    var parked by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    var lastFrame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    // The film's poster while the player opens: the tile's copy at once (in memory), the sharper one from disk a moment later.
    val openPoster = remember { library.cachedPoster(req.posterQueries) }
    var bigPoster by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(Unit) { bigPoster = library.posterLarge(req.posterQueries) }
    var pickerItems by remember { mutableStateOf(emptyList<Pair<Int, String>>()) }
    var pickerCurrent by remember { mutableStateOf(-2) }
    var pickerLoading by remember { mutableStateOf(false) }
    var pickerPendingCount by remember { mutableStateOf(0) }
    val trackMenuCache = remember { mutableMapOf<String, Pair<List<Pair<Int, String>>, Int>>() }
    var prefsUntil by remember { mutableStateOf(0L) }
    var rate by remember { mutableStateOf(ui.get("rate")?.toFloatOrNull()?.coerceIn(0.25f, 4f) ?: 1f) }
    var subDelay by remember { mutableStateOf(0L) }
    var chapters by remember { mutableStateOf(emptyList<Pair<Long, String>>()) }
    // Заставка и титры этого файла: из глав ([chapters]) и с сервера (core.MarkersDetector, /api/markers)
    var serverMarkers by remember { mutableStateOf<MediaMarkers?>(null) }
    var nextDismissed by remember { mutableStateOf(false) } // «Отмена» на карточке следующей серии: досмотреть титры
    var introAutoDone by remember { mutableStateOf<TimeRange?>(null) } // заставка, которую уже пропустили сами
    var infoOpen by remember { mutableStateOf(false) }
    var infoSource by remember { mutableStateOf("key") }
    var infoText by remember { mutableStateOf("") }
    var mini by remember { mutableStateOf(false) }
    var lastSeekAt by remember { mutableStateOf(0L) }
    var bufferingSince by remember { mutableStateOf(0L) }
    var bufferingPct by remember { mutableStateOf(100f) }
    var bufferingShown by remember { mutableStateOf(0) } // highest buffering % during the current wait (monotonic)
    var trackSwitches by remember { mutableStateOf(0) } // audio / subtitle switches from the menu
    val catchUp = remember { CatchUp({ PlayerLog.write(it) }) }
    var catchingUp by remember { mutableStateOf(false) }
    var catchUpStatus by remember { mutableStateOf<String?>(null) }
    var openedAt by remember { mutableStateOf(0L) }
    val supRef = remember { java.util.concurrent.atomic.AtomicReference<PlaybackSupervisor?>(null) }
    var bufferedMs by remember { mutableStateOf(0L) }
    var tallBottom by remember { mutableStateOf(false) }

    val timeNow by rememberUpdatedState(time)
    LaunchedEffect(barVisible, currentUrl, length, playing, bufferingSince) {
        if (!barVisible) return@LaunchedEffect
        while (true) {
            val diskBufferMs = BurstProxy.aheadMs(currentUrl, length)
            val t = timeNow
            bufferedMs = if (length > 0L) (t + diskBufferMs).coerceIn(minOf(t, length), length) else 0L
            delay(200L)
        }
    }

    val beforeMini = remember { arrayOfNulls<Any>(4) }
    var videoAspect by remember { mutableStateOf(16.0 / 9.0) }
    LaunchedEffect(Unit) {
        val pending = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Deferred<*>?>()
        while (true) {
            val d = playerPoll(pending, 1000) { eDim() }
            if (d != null && d.first > 0 && d.second > 0) {
                val aspect = d.first.toDouble() / d.second
                if (videoAspect != aspect) {
                    videoAspect = aspect
                    if (mini && !WindowMode.miniAnimating && owner != null) {
                        val a = aspect.coerceIn(1.2, 2.4)
                        val curW = owner.width
                        val newH = kotlin.math.round(curW / a).toInt()
                        if (newH > 0 && kotlin.math.abs(owner.height - newH) > 2) {
                            owner.setSize(curW, newH)
                        }
                    }
                }
            }
            delay(1500)
        }
    }

    fun titleKey() = title.substringBefore(" · ").lowercase()
    fun seriesKey() = req.watchKeys.firstOrNull()?.substringBefore('|')?.takeIf { it.isNotBlank() }?.let { "m$it" } ?: titleKey()
    fun pref(kind: String): String? = ui.get("$kind:${seriesKey()}") ?: ui.get("$kind:${titleKey()}")
    fun prefLanguage(kind: String): String? =
        (pref(kind) ?: ui.get("$kind:*"))?.let { Regex("""\[([^\]]+)]""").find(it)?.groupValues?.get(1) }

    val sidecarCache = remember { java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.Deferred<Pair<List<String>, List<String>>>>() }
    fun sidecars(i: Int): kotlinx.coroutines.Deferred<Pair<List<String>, List<String>>> = sidecarCache.getOrPut(i) {
        if (i == req.startIndex || req.sidecars == null) kotlinx.coroutines.CompletableDeferred(if (i == req.startIndex) req.audioUrls to req.subUrls else emptyList<String>() to emptyList())
        else scope.async(Dispatchers.IO) { runCatching { req.sidecars?.invoke(i) }.getOrNull() ?: (emptyList<String>() to emptyList()) }
    }

    fun grabFrame(): androidx.compose.ui.graphics.ImageBitmap? {
        val img = runCatching { engine.snapshot(sceneWidthPx.coerceIn(320, 1920)) }.getOrNull() ?: return null
        val bmp = img.toComposeImageBitmap()
        SwingUtilities.invokeLater { lastFrame = bmp }
        return bmp
    }

    var openJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var ensureAudioJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var chaptersJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun open(url: String, startAt: Long, sides: Pair<List<String>, List<String>>) {
        val extras = preferFirst(sides.first, pref("audio") ?: ui.get("audio:*"), url) to
            preferFirst(sides.second, pref("spu") ?: ui.get("spu:*"), url)
        fun firstOf(l: List<String>) = l.firstOrNull()?.let { SidePaths.of(it) ?: sideFileName(it) }
        if (extras != sides) PlayerLog.write("[track] chosen before goes first: voice-over ${firstOf(extras.first)}, subtitles ${firstOf(extras.second)}")
        ensureAudioJob?.cancel()
        chaptersJob?.cancel()
        status = tr("player.otkryvaem")
        prefsApplied = false
        chapters = emptyList()
        lastGood = startAt
        time = startAt
        audioDelay = pref("adelay")?.toLongOrNull() ?: 0L
        subDelay = pref("sdelay")?.toLongOrNull() ?: 0L
        customTracks = emptyList(); activeCustom = null; endCut = false
        val customKey = watchKey()
        pausedAt = 0L
        trackMenuCache.clear()
        if (picker == "audio" || picker == "spu") {
            pickerItems = emptyList()
            pickerPendingCount = if (picker == "audio") extras.first.size + 1 else extras.second.size
            pickerLoading = true
        }

        Reserve.init()
        BurstProxy.capBytes = PlaybackSettings.burstMb().toLong() shl 20
        BurstProxy.keepOnly(listOf(url) + extras.first)
        supRef.get()?.opening(url)
        openedAt = System.currentTimeMillis()
        PlayerLog.write("open ${url.substringBefore('?').substringAfterLast('/')} at ${startAt / 1000}s, voice-overs=${extras.first.size}, " +
            "buffer=${PlaybackSettings.cacheMs()}ms, reserve=${PlaybackSettings.burstMb()}MB")
        val playUrl = BurstProxy.wrap(url)
        extras.first.map { BurstProxy.wrap(it) }.filter { it.startsWith("http://127.0.0.1") }
            .forEach { u -> scope.launch(Dispatchers.IO) { warmSidecar(u) } }

        val prev = openJob
        openJob = scope.launch(playerThread) {
            prev?.join()
            if (closing) return@launch
            if (ePlayable()) {
                grabFrame()
                runCatching { eStop() }
            }
            // Серверные озвучки — после озвучек раздачи; список кешируется, второе открытие не ждёт сеть.
            val custom = req.preview?.let { listOf(it) }
                ?: if (!ru.suslikovanora.media.desktop.PlaybackSettings.customAudioOn()) emptyList()
                else withTimeoutOrNull(3000) { withContext(Dispatchers.IO) { ru.suslikovanora.media.desktop.CustomAudio.forVideo(customKey) } }.orEmpty()
            if (custom.isNotEmpty()) {
                BurstProxy.keepOnly(listOf(url) + extras.first + custom.map { it.url })
                PlayerLog.write("[custom-audio] ${custom.size} voice-over(s) from the server")
            }
            SwingUtilities.invokeLater { customTracks = custom }
            val audio = extras.first + custom.map { it.url }
            val titles = extras.first.map { null } + custom.map { it.title.removeSuffix(" [${it.lang}]").trim().ifBlank { it.audioPath.substringAfterLast('/') } to it.lang }
            engine.open(playUrl, startAt, nightMode, prefLanguage("audio"), prefLanguage("spu"),
                audio.map { BurstProxy.wrap(it) }, extras.second, titles)
        }
    }

    fun reopen(at: Long) { val i = index; scope.launch { open(currentUrl, at, sidecars(i).await()) } }

    fun seekTo(t: Long) {
        if (t < length) endCut = false
        lastGood = t
        MediaControls.updateTimeline(t, length, force = true)
        runOnPlayer { eSetTime(t) }
    }

    fun reseekHereNow() {
        val t = eTime(); if (t > 0) eSetTime(t)
    }

    fun applyTrackNow() {
        supRef.get()?.seeked()
    }

    /**
     * Выбрана дорожка [name]: у серверной озвучки — её задержка (своя поправка с этого ПК важнее), у остальных — задержка фильма.
     * Задержка всегда абсолютная — от 00:00 видео, а не прибавка к задержке фильма или прошлой дорожки:
     * −5000 = дорожка с 00:05 звучит на 00:00 видео (интро студии пропущено), +2000 = звук начинается на 00:02.
     */
    fun applyCustomDelay(name: String) {
        val ct = ru.suslikovanora.media.core.audio.CustomAudio.byMenuName(customTracks, name)
        if (ct == null && activeCustom == null) return
        activeCustom = ct
        val before = audioDelay
        audioDelay = if (ct != null) (if (ct.id == "preview") null else ui.get("adelay:${ct.id}")?.toLongOrNull()) ?: ct.offsetMs else pref("adelay")?.toLongOrNull() ?: 0L
        val d = audioDelay
        // mpv не перечитывает дорожку при новой задержке: звука нет, пока видео не догонит сдвиг (+10 с = 10 с тишины).
        // Перемотка на то же место сразу берёт звук со смещением.
        val jump = kotlin.math.abs(d - before) >= 1000L
        runOnPlayer { eAudioDelay(d); if (jump) reseekHereNow() }
        if (ct != null && d != 0L) PlayerLog.write("[custom-audio] ${ct.id} delay $d ms")
    }

    fun EngineTrack.forSubs() = MarkersDetector.Track(id, title, lang, forced)

    /**
     * Умные субтитры для звука [audioId] (-1 — выбранный): при русской речи — форсированные (надписи) или никаких,
     * при иностранной — полные русские (core.MarkersDetector.pickSubtitle). Поток плеера.
     */
    fun applySmartSubs(audioId: Int = -1) {
        val audio = engine.audioTracks().let { l -> l.firstOrNull { if (audioId >= 0) it.id == audioId else it.selected } } ?: return
        when (val c = MarkersDetector.pickSubtitle(audio.forSubs(), engine.subtitleTracks().map { it.forSubs() })) {
            is MarkersDetector.SubChoice.Pick -> if (eSubCur() != c.id) { eSetSub(c.id); PlayerLog.write("[subs] smart: track ${c.id} for «${audio.description}»") }
            MarkersDetector.SubChoice.Off -> if (eSubCur() != -1) { eSetSub(-1); PlayerLog.write("[subs] smart: off for «${audio.description}»") }
            MarkersDetector.SubChoice.Keep -> {}
        }
    }

    fun applyPrefs() {
        // Предпросмотр озвучки: слушаем именно её
        val a = req.preview?.menuName ?: pref("audio"); val spu = pref("spu")
        if (a != null || spu != null) {
            if (a != null) eAudioTracks().firstOrNull { it.second == a }?.let { if (eAudioCur() != it.first) { eSetAudio(it.first); applyTrackNow() } }
            val cur = eAudioCur()
            eAudioTracks().firstOrNull { it.first == cur }?.second?.let { n -> SwingUtilities.invokeLater { applyCustomDelay(n) } }
            if (spu == "__off__") { if (eSubCur() != -1) eSetSub(-1) }
            else if (spu != null && spu != SMART_SUBS) eSubTracks().firstOrNull { it.second == spu }?.let { if (eSubCur() != it.first) eSetSub(it.first) }
        }
        // Субтитры для этого сериала не выбирали сами (или после смены озвучки их выбрали умные): по звуку
        if ((spu == null || spu == SMART_SUBS) && PlaybackSettings.smartSubs()) applySmartSubs()
    }

    /** [finished]: the current file goes to the library as watched to the end (left on its end credits). */
    fun playEntry(i: Int, finished: Boolean = false) {
        if (i !in queue.indices) return
        seekByJob?.cancel(); seekByJob = null; pendingSeekByTarget = null
        if (finished && length > 0) savePos(length, length) else savePos(time, length)
        index = i; nextIn = -1; currentUrl = queue[i].url; title = queue[i].title; retries = 0
        serverMarkers = null; nextDismissed = false; introAutoDone = null
        val url = currentUrl; val start = savedPos(i)
        playing = false; started = false; switching = true; time = start; lastGood = start; length = 0L; status = tr("player.sleduyuschaya_seriya")
        scope.launch {
            val extras = withTimeoutOrNull(4000) { sidecars(i).await() } ?: (emptyList<String>() to emptyList())
            if (index == i && !closing) open(url, start, extras)
        }
    }

    fun refreshPicker(kind: String) {
        scope.launch {
            val audio = kind == "audio"
            // Off the window's thread: a property waits for mpv's core, busy right after opening or a seek
            // (journal 19.10: the window stood 224 ms in mpv_get_property_string).
            val (list, cur) = withContext(Dispatchers.IO) {
                (if (audio) eAudioTracks() else listOf(-1 to tr("player.vyklyuchit")) + eSubTracks()) to (if (audio) eAudioCur() else eSubCur())
            }
            val pending = engine.pendingTracksCount(kind)
            val loading = engine.isTracksLoading(kind)
            val fresh = list to cur
            trackMenuCache[kind] = fresh
            if (picker == kind) {
                pickerItems = list
                pickerCurrent = cur
                pickerPendingCount = pending
                pickerLoading = loading
            }
        }
    }

    fun openPicker(kind: String) {
        tallBottom = true
        if (kind == "more") {
            picker = "more"
            return
        }
        picker = kind
        val cached = trackMenuCache[kind]
        if (cached != null) {
            pickerItems = cached.first
            pickerCurrent = cached.second
        } else {
            pickerItems = emptyList()
        }
        pickerPendingCount = engine.pendingTracksCount(kind)
        pickerLoading = engine.isTracksLoading(kind)
        refreshPicker(kind)
    }

    fun closePicker() {
        picker = null
    }

    fun toggleInfo(source: String = "key") {
        infoSource = source
        infoOpen = !infoOpen
    }

    var flashJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun flash(text: String) {
        status = text
        flashJob?.cancel()
        flashJob = scope.launch {
            delay(2000)
            if (status == text) status = null
        }
    }

    // Гость комнаты: пока создатель держит паузу (или комната ждёт загрузку), снять её гость не может
    fun partyPlayLocked(): Boolean = partyGuest() && ru.suslikovanora.media.desktop.party.Party.state.let { !it.playing || it.waiting }

    fun togglePause(byRoom: Boolean = false) {
        val wasPaused = pausedAt > 0 || enginePaused
        if (wasPaused && !byRoom && partyPlayLocked()) { flash(tr("party.pauzu_snimaet_sozdatel")); return }
        if (!wasPaused) {
            playing = false; pausedAt = System.currentTimeMillis()
            supRef.get()?.paused()
            runOnPlayer { ePause(true) }
        } else {
            val paused = if (pausedAt > 0) System.currentTimeMillis() - pausedAt else 0L
            val longPause = paused > 8_000L && !BurstProxy.isActive(currentUrl)
            PlayerLog.write("resume after ${paused} ms${if (longPause) " (re-seek)" else ""}")
            supRef.get()?.resumed()
            playing = true; pausedAt = 0L; enginePaused = false
            val m = userMuted
            runOnPlayer {
                eMute(m)
                ePause(false)
                if (longPause) reseekHereNow()
            }
        }
    }

    fun ensureAudio() {
        runOnPlayer {
            if (eAudioCur() != -1) return@runOnPlayer
            val first = eAudioTracks().firstOrNull() ?: return@runOnPlayer
            eSetAudio(first.first); applyTrackNow()
            SwingUtilities.invokeLater { applyCustomDelay(first.second) }
            SwingUtilities.invokeLater { flash(tr("player.zvuk_p", "p" to first.second)) }
        }
    }

    fun shiftSubs(ms: Long) {
        subDelay = (subDelay + ms).coerceIn(-10_000L, 10_000L)
        val d = subDelay
        ui.put("sdelay:${seriesKey()}", if (d == 0L) null else d.toString())
        playerExecutor.execute { runCatching { eSubDelay(d) } }
        flash(tr("player.subtitry_p_d_ms", "ms" to signed(d)))
    }

    val rates = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    fun setSpeed(r: Float) {
        rate = r; ui.put("rate", if (r == 1f) null else r.toString())
        playerExecutor.execute { runCatching { eRate(r) } }
        flash(tr("player.skorost_p", "p" to fmtRate(r)))
    }
    fun stepSpeed(d: Int) { val i = rates.indexOfFirst { it >= rate - 0.01f }.coerceAtLeast(0); setSpeed(rates[(i + d).coerceIn(0, rates.size - 1)]) }

    fun chapterAt(t: Long): Int = chapters.indexOfLast { it.first <= t + 500 }
    fun jumpChapter(d: Int) {
        if (chapters.size < 2) return
        if (partyGuest()) { flash(tr("party.peremativaet_sozdatel")); return }
        val i = (chapterAt(time) + d).coerceIn(0, chapters.size - 1)
        val t = chapters[i].first; time = t; lastSeekAt = System.currentTimeMillis(); supRef.get()?.seeked()
        seekTo(t)
        flash(tr("player.glava_p_p", "n" to i + 1, "name" to chapters[i].second.ifBlank { tr("player.bez_nazvaniya") }))
    }

    // A state, not a plain value: the key handler (installed once) must see this file's marks
    val markers by remember { androidx.compose.runtime.derivedStateOf {
        MarkersDetector.merge(MarkersDetector.fromChapters(chapters.map { Chapter(it.first, it.second) }, length), serverMarkers, length)
    } }
    fun introNow(): TimeRange? = MarkersDetector.introAt(markers, time)
    fun inIntro(): Boolean = introNow() != null
    fun skipIntro(auto: Boolean = false) {
        val r = introNow() ?: return
        if (partyGuest()) { flash(tr("party.peremativaet_sozdatel")); return }
        val t = r.endMs; time = t; lastGood = t; lastSeekAt = System.currentTimeMillis(); supRef.get()?.seeked()
        PlayerLog.write("skip intro ${r.startMs / 1000}-${r.endMs / 1000}s${if (auto) " (auto)" else ""}")
        seekTo(t)
        if (auto) flash(tr("player.zastavka_propuschena"))
    }
    /** The next entry of the queue, from its end credits: this one counts as watched. */
    fun playNext() { if (index + 1 < queue.size) playEntry(index + 1, finished = true) }
    fun cancelNext() { nextIn = -1; nextDismissed = true }
    /** «Следующая серия: S01E02» / «Следующий фильм: Две крепости» for the card on the credits and at the end. */
    fun nextTitle(): String {
        val i = index + 1
        val fallback = queue.getOrNull(i)?.title.orEmpty()
        if (req.collection) return tr("player.sleduyuschiy_film_p", "p" to (req.presence.getOrNull(i)?.title ?: fallback))
        val q = req.markerQueries.getOrNull(i)
        val se = q?.let { "S%02dE%02d".format(it.season ?: 1, it.episode) }
        return if (se != null || req.presence.size > 1) tr("player.sleduyuschaya_seriya_p", "p" to (se ?: req.presence.getOrNull(i)?.episode ?: fallback))
        else tr("player.sleduyuschaya_p", "p" to fallback)
    }

    fun restartPlayer() {
        if (closing) return
        closing = true
        savePos(time, length)
        library.bumpWatch()
        scope.launch {
            playerAwait(800) { grabFrame(); runCatching { eStop() } }
            onRestart(index)
        }
    }

    var userMiniWidth by remember { mutableStateOf<Int?>(null) }

    fun setMini(on: Boolean) {
        if (on == mini || WindowMode.miniAnimating) return
        val target = owner ?: return
        if (on) {
            beforeMini[3] = onTop.value
            target.minimumSize = java.awt.Dimension(160, 100)
            val aspect = videoAspect.coerceIn(1.2, 2.4)
            val defaultMw = Math.sqrt(590.0 * 330.0 * aspect).toInt().coerceIn(400, 620)
            val mw = (userMiniWidth ?: defaultMw).coerceIn(280, 960)
            val mh = kotlin.math.round(mw / aspect).toInt()
            WindowMode.setMiniMode(target, true, mw, mh) {
                mini = true
                onTop.value = true
            }
        } else {
            mini = false
            WindowMode.setMiniMode(target, false)
            onTop.value = beforeMini[3] as? Boolean ?: false
            target.minimumSize = java.awt.Dimension(MIN_W, MIN_H)
            target.cursor = java.awt.Cursor.getDefaultCursor()
        }
    }

    fun shiftAudio(ms: Long) {
        audioDelay = (audioDelay + ms).coerceIn(-60_000L, 60_000L)
        val d = audioDelay
        val ct = activeCustom
        if (ct?.id == "preview") req.onPreviewDelay?.invoke(d)
        else if (ct != null) ui.put("adelay:${ct.id}", if (d == ct.offsetMs) null else d.toString())
        else ui.put("adelay:${seriesKey()}", if (d == 0L) null else d.toString())
        playerExecutor.execute { runCatching { eAudioDelay(d); if (kotlin.math.abs(ms) >= 1000L) reseekHereNow() } }
        flash(tr("player.zaderzhka_zvuka_p_d", "ms" to signed(d)))
    }

    var leaveJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun poke() {
        leaveJob?.cancel(); leaveJob = null
        lastMove = System.currentTimeMillis()
        if (!barVisible) barVisible = true
    }

    fun hideBarSoon() {
        leaveJob?.cancel()
        leaveJob = scope.launch {
            delay(500)
            val hold = !started || enginePaused || nextIn >= 0 || picker != null || seeking != null || infoOpen || inIntro()
            if (!hold) barVisible = false
        }
    }

    fun seekBy(d: Long) {
        if (partyGuest()) { flash(tr("party.peremativaet_sozdatel")); return }
        val base = pendingSeekByTarget ?: time
        var t = (base + d).coerceAtLeast(0L)
        if (length > 0) t = t.coerceAtMost((length - 2_000L).coerceAtLeast(0L))
        time = t
        lastGood = t
        pendingSeekByTarget = t
        poke()
        seekByJob?.cancel()
        seekByJob = scope.launch {
            delay(250)
            val target = pendingSeekByTarget ?: t
            pendingSeekByTarget = null
            PlayerLog.write("seek to ${target / 1000}s")
            bufferingShown = 0
            // The aim again: in the 250 ms the clock of mpv wrote the old place into [time], and the room saw a seek
            // mark without a jump — the seek was not sent, the room pulled the host back (journal 20.10, 21:17:04)
            time = target; lastGood = target
            lastSeekAt = System.currentTimeMillis()
            supRef.get()?.seeked()
            seekTo(target)
        }
    }

    fun toggleFullscreen() {
        if (mini) {
            setMini(false)
            return
        }
        WindowMode.toggleFullscreen(owner)
    }

    fun close() {
        if (closing) return
        closing = true
        seekByJob?.cancel(); seekByJob = null; pendingSeekByTarget = null
        DisplayAwake.setAwake(false)
        savePos(time, length)
        library.bumpWatch()
        nextIn = -1
        supRef.get()?.closed()
        scope.launch {
            // The flight back starts at once: the picture stops (pause, the sound too) and the live frame flies; the still
            // copy takes its place as soon as it is ready (before, the flight waited up to 180 ms for it). The video layer
            // still goes well before the player is removed (the flight is 600 ms).
            runOnPlayer { ePause(true) }
            if (mini) setMini(false)
            MediaControls.stop()
            onClose()
            // Always a fresh frame: one kept from the previous episode would fly back instead of this one.
            playerAwait(250) { grabFrame() }?.let { lastFrame = it }
            parked = true
            playerExecutor.execute { runCatching { eStop() } }
        }
    }

    // Keyboard shortcuts
    DisposableEffect(Unit) {
        keys.value = handler@{ e ->
            if (e.type != KeyEventType.KeyDown) return@handler false
            poke()
            when (e.key) {
                Key.Spacebar, Key.Enter -> if (nextIn >= 0 && index + 1 < queue.size) playNext() else if (e.key == Key.Spacebar) togglePause() else toggleFullscreen()
                Key.K -> togglePause()
                Key.DirectionRight -> seekBy(if (e.isShiftPressed) 60_000L else 10_000L)
                Key.DirectionLeft -> seekBy(if (e.isShiftPressed) -60_000L else -10_000L)
                Key.DirectionUp -> {
                    volume = (volume + 5).coerceAtMost(150)
                    val v = volume
                    runOnPlayer { eVolume(v) }
                    if (userMuted) {
                        userMuted = false
                        runOnPlayer { eMute(false) }
                    }
                    lastVolumeWheelAt = System.currentTimeMillis()
                    flash(tr("player.gromkost_v", "v" to v))
                }
                Key.DirectionDown -> {
                    volume = (volume - 5).coerceAtLeast(0)
                    val v = volume
                    runOnPlayer { eVolume(v) }
                    if (userMuted) {
                        userMuted = false
                        runOnPlayer { eMute(false) }
                    }
                    lastVolumeWheelAt = System.currentTimeMillis()
                    flash(tr("player.gromkost_v", "v" to v))
                }
                Key.F, Key.F11 -> toggleFullscreen()
                Key.Escape -> if (nextIn >= 0) cancelNext() else if (WindowMode.isFullscreen) {
                    WindowMode.setFullscreen(owner, false)
                } else close()
                Key.Backspace -> if (nextIn >= 0) cancelNext() else close()
                Key.N -> if (!partyGuest()) playEntry(index + 1) else flash(tr("party.seriyu_vybiraet_sozdatel"))
                Key.P -> if (!partyGuest()) playEntry(index - 1) else flash(tr("party.seriyu_vybiraet_sozdatel"))
                Key.M -> { userMuted = !userMuted; val m = userMuted; runOnPlayer { eMute(m) } }
                Key.LeftBracket -> if (e.isShiftPressed) shiftSubs(-100L) else shiftAudio(-100L)
                Key.RightBracket -> if (e.isShiftPressed) shiftSubs(100L) else shiftAudio(100L)
                Key.Backslash -> if (e.isShiftPressed) shiftSubs(-subDelay) else shiftAudio(-audioDelay)
                Key.Period -> stepSpeed(1)
                Key.Comma -> stepSpeed(-1)
                Key.PageDown -> jumpChapter(1)
                Key.PageUp -> jumpChapter(-1)
                Key.S -> skipIntro()
                Key.I -> toggleInfo("key")
                Key.O -> setMini(!mini)
                Key.T -> if (!mini) onTop.value = !onTop.value
                Key.F8 -> if (Diag.on) flash(tr("player.metka_p", "p" to Diag.mark("player at ${time / 1000}s"))) else return@handler false
                else -> return@handler false
            }
            true
        }
        onDispose { keys.value = null }
    }

    // Windows System Media Transport Controls (SMTC) integration (Media Flyout, ModernFlyouts, Windows volume popup)
    DisposableEffect(owner) {
        val callbacks = MediaControls.Callbacks(
            onPlay = {
                PlayerLog.write("[smtc] play (playing=$playing pausedAt=$pausedAt enginePaused=$enginePaused)")
                togglePause()
            },
            onPause = {
                PlayerLog.write("[smtc] pause (playing=$playing pausedAt=$pausedAt enginePaused=$enginePaused)")
                togglePause()
            },
            onNext = { if (index + 1 < queue.size && !partyGuest()) playEntry(index + 1) },
            onPrevious = {
                PlayerLog.write("[smtc] previous (episode ${index + 1} of ${queue.size})")
                if (partyGuest()) Unit else if (index > 0) playEntry(index - 1) else seekTo(0L)
            },
            onSeek = { targetMs -> if (!partyGuest()) seekTo(targetMs) }
        )
        if (owner != null) MediaControls.attach(owner, callbacks)
        onDispose {
            MediaControls.detach(callbacks)
        }
    }

    fun smtcTitles(): Pair<String, String> {
        val episodeTitle = queue.getOrNull(index)?.title ?: title
        return if (queue.size > 1) episodeTitle to req.title else req.title to "Suslik Media"
    }

    var smtcPoster by remember { mutableStateOf<java.io.File?>(null) }
    var posterLooked by remember { mutableStateOf(false) }
    var discordPosterUrl by remember { mutableStateOf(library.posterUrl(req.posterQueries)) }
    LaunchedEffect(Unit) {
        smtcPoster = runCatching { library.posterFileFor(req.posterQueries) }.getOrNull()
        posterLooked = true
        PlayerLog.write("[smtc] poster ${smtcPoster?.name ?: "none"}")
    }
    LaunchedEffect(posterLooked) {
        if (posterLooked && discordPosterUrl == null) {
            discordPosterUrl = library.posterUrl(req.posterQueries)
        }
    }

    val userPaused = pausedAt > 0L || enginePaused

    // Совместный просмотр: плеер идёт за комнатой, если в ней этот фильм (иначе ничего не делает)
    ru.suslikovanora.media.desktop.party.PartySync(watchKey(), title, req.watchKeys, started, closing,
        ru.suslikovanora.media.desktop.party.SyncHooks(
            // Right after a seek mpv still reports the old place for a moment: then the bar's target counts
            timeMs = { if (System.currentTimeMillis() - lastSeekAt < 2500L) time else engine.timeMs.takeIf { it > 0L } ?: time },
            busy = { seeking != null || pendingSeekByTarget != null },
            engineMs = { engine.timeMs },
            seekStamp = { lastSeekAt },
            paused = { pausedAt > 0L || enginePaused },
            running = { playing && engine.isPlaying },
            userRate = { rate },
            setPaused = { p -> if ((pausedAt > 0L || enginePaused) != p) togglePause(byRoom = true) },
            seekTo = { t -> time = t; lastGood = t; lastSeekAt = System.currentTimeMillis(); seekTo(t) },
            setRate = { r -> playerExecutor.execute { runCatching { eRate(r) } } },
            playIndex = { i -> playEntry(i) },
            notice = { text -> flash(text) }
        ))

    LaunchedEffect(title, index, queue.size, smtcPoster) {
        val (displayTitle, displaySubtitle) = smtcTitles()
        MediaControls.updateMetadata(displayTitle, displaySubtitle, smtcPoster)
        MediaControls.updateCapabilities(hasNext = index + 1 < queue.size, hasPrev = index > 0 || time > 5_000L)
    }

    LaunchedEffect(userPaused) {
        // (closing pauses the film for the flight back: the media controls are already stopped by then)
        if (!closing) MediaControls.updatePlayback(!userPaused)
    }

    val inParty = ru.suslikovanora.media.desktop.party.Party.let {
        it.inRoom && it.file?.key == watchKey()
    }
    val partyMembersCount = if (inParty) maxOf(1, ru.suslikovanora.media.desktop.party.Party.members.size) else 0

    // Активность в Discord: фильм, пауза и полоса времени. Обновляется, когда фильм пошёл (длина известна),
    // на паузе, перемотке, смене серии или изменении состава комнаты совместного просмотра;
    // короткая задержка — одна отправка на серию быстрых нажатий.
    LaunchedEffect(title, index, userPaused, started, length > 0L, lastSeekAt, discordPosterUrl, inParty, partyMembersCount) {
        if (closing || !started) return@LaunchedEffect
        delay(700)
        // The library's title (line 1) and the season / episode (line 2); without them (a voice-over preview) the player's titles
        val (displayTitle, displaySubtitle) = smtcTitles()
        val presence = req.presence.getOrNull(if (queue.size > 1) index else req.startIndex)
            ?: if (queue.size > 1) Presence(displaySubtitle, displayTitle) else Presence(displayTitle)
        val state = when {
            inParty -> tr("discord.smotrit_vmeste", "count" to partyMembersCount) + (if (userPaused) " · " + tr("discord.pauza") else "")
            userPaused -> tr("discord.pauza")
            else -> null
        }
        val discordTitle = presence.title
        val discordSubtitle = listOfNotNull(presence.episode, state).joinToString(" · ").ifEmpty { tr("discord.film") }
        ru.suslikovanora.media.desktop.discord.DiscordRpc.update(
            title = discordTitle,
            subtitle = discordSubtitle,
            playing = !userPaused,
            timeMs = engine.timeMs.takeIf { it > 0L } ?: time,
            lengthMs = length,
            posterUrl = discordPosterUrl,
            activityName = "Suslik Media",
            largeText = presence.episode?.let { "${presence.title} · $it" } ?: presence.title
        )
    }

    val isPlaybackActive = playing && !userPaused && !closing
    LaunchedEffect(isPlaybackActive) {
        DisplayAwake.setAwake(isPlaybackActive)
    }

    // Once per whole second of the film: the media controls skip anything more often anyway (seeks go at once, with force).
    LaunchedEffect(length) {
        if (length > 0L) snapshotFlow { time / 1000L }.collect { MediaControls.updateTimeline(time, length) }
    }

    LaunchedEffect(index, posterLooked, smtcPoster) {
        if (!posterLooked || smtcPoster != null) return@LaunchedEffect
        snapshotFlow { playing }.first { it }
        delay(2500L)
        if (!playing) return@LaunchedEffect
        val bmp = playerAwait(3000) { engine.snapshot(320) } ?: return@LaunchedEffect
        val thumbFile = withContext(Dispatchers.IO) {
            MediaControls.thumbFile("frame-${System.currentTimeMillis()}.jpg").takeIf { f ->
                runCatching { javax.imageio.ImageIO.write(bmp, "jpg", f) }.getOrDefault(false) && f.length() > 0L
            }
        } ?: return@LaunchedEffect
        val (displayTitle, displaySubtitle) = smtcTitles()
        MediaControls.updateMetadata(displayTitle, displaySubtitle, thumbFile)
    }

    if (Diag.on) {
        DisposableEffect(Unit) { Diag.playerOpen = true; onDispose { Diag.playerOpen = false } }
        LaunchedEffect(Unit) {
            val pending = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Deferred<*>?>()
            while (true) {
                delay(5_000)
                if (closing) continue
                val line = playerPoll(pending, 3000) { runCatching { engine.statsLine() }.getOrNull() }
                line?.let { PlayerLog.write(it) }
                PlayerLog.write("[proxy] " + BurstProxy.snapshot())
            }
        }
    }

    var prefsJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun schedulePrefs(delayMs: Long) {
        prefsJob?.cancel()
        prefsJob = scope.launch {
            delay(delayMs)
            applyPrefs()
            ensureAudio()
        }
    }
    DisposableEffect(Unit) {
        val ui2 = { block: () -> Unit -> SwingUtilities.invokeLater(block) }
        fun evPlayingNow() {
            val wasStarted = started
            playing = true; status = null; retries = 0; started = true; enginePaused = false
            bufferingShown = 0
            bufferingPct = 100f
            if (bufferingSince > 0L) {
                val ms = System.currentTimeMillis() - bufferingSince
                bufferingSince = 0L
                if (ms > 1500) PlayerLog.write("buffering took $ms ms at ${time / 1000}s")
            }
            if (pausedAt == 0L) supRef.get()?.playing()
            if (!prefsApplied) {
                prefsApplied = true
                scope.launch { delay(2000); playerAwait(1500) { engine.statsLine() }?.let { PlayerLog.write(it) } }
                prefsUntil = System.currentTimeMillis() + 8_000L
                schedulePrefs(300)
                ensureAudioJob?.cancel()
                ensureAudioJob = scope.launch {
                    delay(4500)
                    ensureAudio()
                }
                if (audioDelay != 0L) { val d = audioDelay; playerExecutor.execute { runCatching { eAudioDelay(d) } } }
                if (subDelay != 0L) { val d = subDelay; playerExecutor.execute { runCatching { eSubDelay(d) } } }
                if (rate != 1f) { val r = rate; playerExecutor.execute { runCatching { eRate(r) } } }
                chaptersJob?.cancel()
                chaptersJob = scope.launch {
                    delay(1200)
                    playerAwait(3000) { engine.chapters().map { it.timeMs to it.title } }?.let { chapters = it }
                }
            }
            val v = volume; val m = userMuted
            if (pausedAt > 0) {
                playing = false
                runOnPlayer { eMute(true); ePause(true) }
            } else {
                runOnPlayer {
                    eMute(m)
                    if (!wasStarted) eVolume(v)
                }
            }
        }
        fun evPlaying() = ui2 { if (!switching) evPlayingNow() }
        fun evStream() = ui2 {
            if (System.currentTimeMillis() < prefsUntil) { schedulePrefs(400) }
        }
        fun evPaused() = ui2 { playing = false; enginePaused = true; savePos(time, length) }
        fun evStopped() = ui2 { enginePaused = false }
        fun evBuffering(newCache: Float) = ui2 {
            if (newCache < 100f) {
                bufferingShown = maxOf(bufferingShown, newCache.toInt())
                status = tr("player.buferizaciya_p", "p" to bufferingShown)
                bufferingPct = bufferingShown.toFloat()
                supRef.get()?.buffering(bufferingPct)
                if (bufferingSince == 0L) bufferingSince = System.currentTimeMillis()
            } else {
                bufferingShown = 0
                bufferingPct = 100f
                status = null
                supRef.get()?.buffering(100f)
                if (bufferingSince > 0L) {
                    val ms = System.currentTimeMillis() - bufferingSince
                    bufferingSince = 0L
                    if (ms > 1500) PlayerLog.write("buffering took $ms ms at ${time / 1000}s")
                }
            }
        }
        fun evLength(newLength: Long) = ui2 {
            if (newLength > 0) { length = newLength; BurstProxy.setDuration(currentUrl, newLength) }
        }
        fun evTime(currTime: Long) {
            ui2 {
                if (switching) return@ui2
                if (!started && currTime > 0) {
                    evPlayingNow()
                }
                if (seeking == null && pendingSeekByTarget == null && System.currentTimeMillis() - lastSeekAt > 600 && (kotlin.math.abs(currTime - time) >= 250 || currTime < time)) time = currTime
                lastGood = currTime
                // Серверная озвучка длиннее видео: когда видео кончилось, остаток дорожки не играем.
                if (activeCustom != null && !endCut && length > 0 && currTime >= length + 300) {
                    endCut = true
                    PlayerLog.write("[custom-audio] video ended at ${length / 1000}s, the rest of the voice-over is cut")
                    runOnPlayer { ePause(true) }
                    engBridge.target?.finished()
                }
                if (currTime / 10_000 != (currTime - 300) / 10_000) savePos(currTime, length)
            }
        }
        fun evFinished() = ui2 {
            if (!closing) {
                supRef.get()?.ended()
                playing = false
                val end = length > 0 && lastGood >= length - 5_000L
                if (end) {
                    savePos(length, length)
                    if (index < queue.size - 1) { if (nextIn < 0) nextIn = PlaybackSettings.nextCountdown() } else status = tr("player.konec")
                } else if (retries < 6) { retries++; status = tr("player.perepodklyuchenie"); reopen(lastGood) }
            }
        }
        fun evError() = ui2 {
            if (!closing) {
                if (retries < 6) { retries++; status = tr("player.perepodklyuchenie"); reopen(lastGood) }
                else status = tr("player.ne_udalos_vosproizvesti_potok")
            }
        }

        fun evTracksChanged() = ui2 {
            val p = picker
            if (p == "audio" || p == "spu") {
                refreshPicker(p)
            }
        }

        engBridge.target = object : EngineListener {
            override fun opened() { ui2 { switching = false }; evTracksChanged() }
            override fun playing() = evPlaying()
            override fun paused() = evPaused()
            override fun stopped() = evStopped()
            override fun buffering(percent: Float) = evBuffering(percent)
            override fun buffering(active: Boolean) = evBuffering(if (active) 0f else 100f)
            override fun timeChanged(ms: Long) = evTime(ms)
            override fun timeChanged(ms: Long, position: Float) = evTime(ms)
            override fun lengthChanged(ms: Long) = evLength(ms)
            override fun tracksChanged() {
                evStream()
                evTracksChanged()
            }
            override fun finished() = evFinished()
            override fun failed(message: String) { PlayerLog.write("[mpv] $message"); evError() }
        }
        onDispose {
            DisplayAwake.setAwake(false)
            MediaControls.stop()
            ru.suslikovanora.media.desktop.discord.DiscordRpc.stop()
            ensureAudioJob?.cancel()
            chaptersJob?.cancel()
            prefsJob?.cancel()
            Thread({ runCatching { BurstProxy.clear() } }, "reserve-clear").apply { isDaemon = true; start() }
            if (!closing) { savePos(time, length); library.bumpWatch() }
            if (mini) setMini(false)
            ui.put("volume", volume.toString())
            playerExecutor.execute {
                runCatching { eStop() }
                runCatching { engine.release() }
                System.gc() // SerialGC: shrinks the heap back after the player's spike
            }
            playerExecutor.shutdown()
            Thread({ runCatching { playerExecutor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS) }; AppExit.untrack(playerExecutor) }, "player-untrack")
                .apply { isDaemon = true; start() }
        }
    }

    val playlistDeferred = remember(req.playlist) {
        req.playlist?.let { url ->
            scope.async(Dispatchers.IO) {
                runCatching { Api.text(url)?.let { parseM3u(it) } }.getOrNull().orEmpty()
            }
        }
    }

    DisposableEffect(videoOut) { onDispose { videoOut?.dispose() } }

    DisposableEffect(Unit) {
        val tk = java.awt.Toolkit.getDefaultToolkit()
        val was = runCatching { tk.isDynamicLayoutActive }.getOrDefault(true)
        runCatching { tk.setDynamicLayout(false) }
        onDispose { runCatching { tk.setDynamicLayout(was) } }
    }

    LaunchedEffect(Unit) {
        if (videoOut != null) {
            if (!engine.startVideo(videoOut)) PlayerLog.write("[video] mpv could not attach to the window's OpenGL: sound only")
        }

        val entries = playlistDeferred?.await().orEmpty()
        if (entries.size > 1) {
            queue = entries
        }
        if (startIndex != req.startIndex && entries.size > startIndex) {
            playEntry(startIndex)
            return@LaunchedEffect
        }
        open(req.url, if (req.fromStart) 0L else savedPos(), req.audioUrls to req.subUrls)
    }

    LaunchedEffect(index, queue.size) { if (index + 1 < queue.size) { delay(15_000); sidecars(index + 1) } }

    fun restartVideo() {
        PlayerLog.write("restart video output at ${time / 1000}s")
        engine.restartVideoOutput()
    }

    val restartOutput: () -> Unit = { restartVideo() }
    val reopenAt: (Long) -> Unit = { reopen(it) }
    val supervisor = remember {
        PlaybackSupervisor(object : PlaybackSupervisor.Actions {
            override fun restartVideo() = restartOutput()
            override fun fixSound() {
                val v = volume
                runOnPlayer { eMute(userMuted); eVolume(v) }
                ensureAudio()
            }
            override fun reopen(fresh: Boolean) {
                PlayerLog.write("supervisor: reopen at ${time / 1000}s${if (fresh) " with a fresh reserve" else ""}")
                CrashReports.recovery("reopen at ${time / 1000}s (fresh=$fresh)")
                if (fresh) BurstProxy.dropAll()
                reopenAt(time)
            }
            override fun notice(text: String) = flash(text)
            override fun log(message: String) = PlayerLog.write(message)
        }, stuckLimitMs = { if (catchingUp) CatchUp.MAX_MS + 15_000L else if (BurstProxy.isActive(currentUrl)) 30_000L else 12_000L })
    }
    DisposableEffect(supervisor) { supRef.set(supervisor); onDispose { supRef.set(null) } }

    LaunchedEffect(Unit) {
        fun endCatchUp(restart: Boolean) {
            if (!catchingUp) return
            catchingUp = false
            val restartHere = catchUp.end(currentUrl, restart && !closing && time > 0L)
            if (status == catchUpStatus) status = null
            if (restartHere) {
                lastSeekAt = System.currentTimeMillis()
                supRef.get()?.seeked()
                seekTo(time)
            }
        }
        while (true) {
            delay(1_000L)
            val now = System.currentTimeMillis()
            if (catchingUp) {
                val gathered = catchUp.progress(currentUrl)
                when {
                    closing || pausedAt > 0L || enginePaused || seeking != null || lastSeekAt > catchUp.startedAt -> endCatchUp(false)
                    gathered < 0 -> endCatchUp(true)
                    bufferingSince > 0L -> { catchUpStatus = tr("player.set_medlennee_filma_kopim", "p" to gathered); status = catchUpStatus }
                }
                continue
            }
            if (closing || !started || pausedAt > 0L || enginePaused || seeking != null || time <= 0L) continue
            if (now - openedAt < 20_000L || now - lastSeekAt < 20_000L) continue
            catchingUp = catchUp.maybeStart(currentUrl, canStart = true)
        }
    }

    LaunchedEffect(Unit) {
        val pending = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Deferred<*>?>()
        while (true) {
            delay(2000)
            if (!started || closing) continue
            val st = playerPoll(pending, 1500) { engine.supervisorStats(userMuted || volume == 0) } ?: continue
            supervisor.tick(st)
        }
    }

    LaunchedEffect(trackSwitches) {
        if (trackSwitches == 0) return@LaunchedEffect
        bufferingShown = 0
        PlayerLog.write("[track] switched at ${time / 1000}s" + (if (bufferingSince > 0L) ", buffering ${bufferingPct.toInt()}%" else ""))
        val until = System.currentTimeMillis() + 20_000L
        var shown = 0f
        while (System.currentTimeMillis() < until) {
            delay(3_000L)
            if (closing || pausedAt > 0L || enginePaused || seeking != null || catchingUp) return@LaunchedEffect
            if (bufferingSince == 0L) { shown = 0f; continue }
            if (bufferingPct > shown) { shown = bufferingPct; continue }
            val t = time
            PlayerLog.write("[track] buffering stuck at ${bufferingPct.toInt()}% after the switch → seek to ${t / 1000}s")
            if (t <= 0L) return@LaunchedEffect
            lastSeekAt = System.currentTimeMillis()
            supRef.get()?.seeked()
            seekTo(t)
            return@LaunchedEffect
        }
    }

    LaunchedEffect(win.placement, mini) { if (started) supervisor.surfaceChanged() }

    val warmed = remember { HashSet<Int>() }
    val nearEnd = length > 0 && length - time in 1..60_000L
    LaunchedEffect(index, nearEnd) {
        val next = index + 1
        if (!nearEnd || next !in queue.indices || !warmed.add(next)) return@LaunchedEffect
        val video = queue[next].url
        val extras = withTimeoutOrNull(10_000) { sidecars(next).await() }
        withContext(Dispatchers.IO) {
            (listOf(video) + extras?.first.orEmpty()).forEach { u -> launch { warmUp(BurstProxy.wrap(u), if (u == video) 4L shl 20 else 1L shl 20) } }
        }
    }

    LaunchedEffect(infoOpen) {
        val pending = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Deferred<*>?>()
        while (infoOpen) {
            val line = playerPoll(pending, 800) { engine.statsLine() }
            if (line == null) { delay(1000); continue }
            infoText = line +
                tr("player.skorost_p_zvuk_p", "rate" to fmtRate(rate), "audio" to signed(audioDelay), "subs" to signed(subDelay)) +
                (if (nightMode) tr("player.nochnoy_rezhim") else "") + tr("player.bufer_p_s", "p" to PlaybackSettings.cacheMs() / 1000.0) +
                (BurstProxy.aheadMb(currentUrl)?.let { tr("player.zapas_na_diske_f", "p" to PlaybackSettings.burstMb()).format(it) } ?: tr("player.bez_zapasa_napryamuyu")) +
                tr("player.zhurnal_absolutepath", "absolutePath" to PlayerLog.file.absolutePath)
            delay(1000)
        }
    }

    // On a pause the countdown waits (the end of the file is not a pause: it goes on)
    LaunchedEffect(nextIn, userPaused) {
        if (nextIn > 0) { if (userPaused) return@LaunchedEffect; delay(1000); nextIn-- } else if (nextIn == 0) playNext()
    }

    // Intro and credits from the open databases when the file's chapters do not have them (series episodes only)
    LaunchedEffect(index, started, length > 0L) {
        if (!started || length <= 0L || serverMarkers != null) return@LaunchedEffect
        val q = req.markerQueries.getOrNull(if (queue.size > 1) index else req.startIndex) ?: return@LaunchedEffect
        delay(2500) // the chapters come first
        val file = MarkersDetector.fromChapters(chapters.map { Chapter(it.first, it.second) }, length)
        if (file.intro != null && file.credits != null) return@LaunchedEffect
        val path = MarkersDetector.serverPath(q.title, q.alt, q.season, q.episode, length) ?: return@LaunchedEffect
        val found = withTimeoutOrNull(10_000) { runCatching { MarkersDetector.parseServer(Api.get(path)) }.getOrNull() }
        if (found != null && !found.isEmpty) {
            PlayerLog.write("[markers] server: intro ${found.intro?.let { "${it.startMs / 1000}-${it.endMs / 1000}s" } ?: "-"}, credits ${found.credits?.let { "${it.startMs / 1000}s" } ?: "-"}")
            serverMarkers = found
        }
    }

    // Auto-skip: 1.5 s into the intro, once per file (back into it by hand — the button stays)
    val introRange = introNow()
    LaunchedEffect(introRange, started) {
        val r = introRange ?: return@LaunchedEffect
        if (!started || introAutoDone == r || !PlaybackSettings.autoSkipIntro() || partyGuest()) return@LaunchedEffect
        delay(MarkersDetector.AUTO_SKIP_DELAY_MS)
        if (closing || pausedAt > 0L || enginePaused || introNow() != r) return@LaunchedEffect
        introAutoDone = r
        skipIntro(auto = true)
    }

    // End credits of an episode / a film of a collection: the card of the next one with its countdown, not waiting for the end
    val creditsAt = MarkersDetector.nextOfferAt(markers, length)
    val inCredits = creditsAt != null && started && time >= creditsAt && time < length
    LaunchedEffect(inCredits, index) {
        if (!inCredits || nextIn >= 0 || nextDismissed || closing || index + 1 >= queue.size) return@LaunchedEffect
        if (!PlaybackSettings.offerNextOnCredits() || partyGuest()) return@LaunchedEffect
        PlayerLog.write("[markers] credits at ${time / 1000}s: next in ${PlaybackSettings.nextCountdown()}s")
        nextIn = PlaybackSettings.nextCountdown()
    }

    val fullscreen = WindowMode.isFullscreen

    LaunchedEffect(barVisible, lastMove, started, enginePaused, nextIn, picker, seeking, infoOpen, closing) {
        if (closing) return@LaunchedEffect // the pause for the flight back must not bring the panels up
        val hold = !started || enginePaused || nextIn >= 0 || picker != null || seeking != null || infoOpen || inIntro()
        if (hold) {
            barVisible = true
            return@LaunchedEffect
        }
        if (!barVisible) return@LaunchedEffect
        delay(2500)
        val stillHold = !started || enginePaused || nextIn >= 0 || picker != null || seeking != null || infoOpen || inIntro()
        if (!stillHold) {
            barVisible = false
        }
    }

    val barSlide = remember { androidx.compose.animation.core.Animatable(0f) }
    val blankCursor = remember {
        java.awt.Toolkit.getDefaultToolkit().createCustomCursor(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB), java.awt.Point(0, 0), "blank")
    }
    val hiddenPointer = remember { androidx.compose.ui.input.pointer.PointerIcon(blankCursor) }

    DisposableEffect(owner) {
        onDispose {
            owner?.cursor = java.awt.Cursor.getDefaultCursor()
        }
    }

    var dragStartMouseX = 0
    var dragStartMouseY = 0
    var dragStartWinX = 0
    var dragStartWinY = 0

    val onWindowDragStart: () -> Unit = {
        val p = java.awt.MouseInfo.getPointerInfo()?.location
        if (p != null && owner != null) {
            dragStartMouseX = p.x
            dragStartMouseY = p.y
            dragStartWinX = owner.x
            dragStartWinY = owner.y
        }
    }

    val onWindowDragMove: () -> Unit = {
        val p = java.awt.MouseInfo.getPointerInfo()?.location
        if (p != null && owner != null) {
            val dx = p.x - dragStartMouseX
            val dy = p.y - dragStartMouseY
            val nx = dragStartWinX + dx
            val ny = dragStartWinY + dy
            owner.setLocation(nx, ny)
        }
    }

    val onWindowDragEnd: () -> Unit = {}

    var resizeStartMouseX = 0
    var resizeStartMouseY = 0
    var resizeStartWinX = 0
    var resizeStartWinY = 0
    var resizeStartWinW = 0
    var resizeStartWinH = 0
    var resizeAspect = 16.0 / 9.0

    val onSetCursor: (java.awt.Cursor) -> Unit = { c ->
        if (owner?.cursor != c) owner?.cursor = c
    }

    val onWindowResizeStart: (ResizeDirection) -> Unit = { _ ->
        val p = java.awt.MouseInfo.getPointerInfo()?.location
        val w = owner
        if (p != null && w != null) {
            resizeStartMouseX = p.x
            resizeStartMouseY = p.y
            resizeStartWinX = w.x
            resizeStartWinY = w.y
            resizeStartWinW = w.width
            resizeStartWinH = w.height
            resizeAspect = videoAspect.coerceIn(1.2, 2.4)
        }
    }

    val onWindowResizeMove: (ResizeDirection) -> Unit = { dir ->
        val p = java.awt.MouseInfo.getPointerInfo()?.location
        val w = owner
        if (p != null && w != null && resizeStartWinW > 0 && resizeStartWinH > 0) {
            val dx = p.x - resizeStartMouseX
            val dy = p.y - resizeStartMouseY
            val a = resizeAspect
            val w0 = resizeStartWinW
            val h0 = resizeStartWinH
            val x0 = resizeStartWinX
            val y0 = resizeStartWinY

            val gc = w.graphicsConfiguration
            val scrBounds = gc?.bounds ?: java.awt.Rectangle(0, 0, 1920, 1080)
            val insets = runCatching { java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc) }.getOrNull()
                ?: java.awt.Insets(0, 0, 0, 0)
            val workLeft = scrBounds.x + insets.left
            val workTop = scrBounds.y + insets.top
            val workRight = scrBounds.x + scrBounds.width - insets.right
            val workBottom = scrBounds.y + scrBounds.height - insets.bottom
            val maxW = minOf(1200, ((workRight - workLeft) * 0.85).toInt()).coerceAtLeast(400)
            val minW = 280.coerceAtMost(maxW)

            var newW = w0
            var newH = h0
            var newX = x0
            var newY = y0

            when (dir) {
                ResizeDirection.BOTTOM_RIGHT -> {
                    val proj = (dx * a + dy) / (a * a + 1.0)
                    newW = (w0 + proj * a).toInt().coerceIn(minW, maxW)
                    newH = kotlin.math.round(newW / a).toInt()
                    newX = x0
                    newY = y0
                    if (newX + newW > workRight) {
                        newW = (workRight - newX).coerceAtLeast(minW)
                        newH = kotlin.math.round(newW / a).toInt()
                    }
                    if (newY + newH > workBottom) {
                        newH = (workBottom - newY).coerceAtLeast(kotlin.math.round(minW / a).toInt())
                        newW = kotlin.math.round(newH * a).toInt().coerceIn(minW, maxW)
                        newH = kotlin.math.round(newW / a).toInt()
                    }
                }
                ResizeDirection.RIGHT -> {
                    newW = (w0 + dx).coerceIn(minW, maxW)
                    newH = kotlin.math.round(newW / a).toInt()
                    newX = x0
                    newY = y0
                    if (newX + newW > workRight) {
                        newW = (workRight - newX).coerceAtLeast(minW)
                        newH = kotlin.math.round(newW / a).toInt()
                    }
                    if (newY + newH > workBottom) {
                        newY = (workBottom - newH).coerceAtLeast(workTop)
                    }
                }
                ResizeDirection.BOTTOM -> {
                    newH = (h0 + dy).coerceIn(kotlin.math.round(minW / a).toInt(), kotlin.math.round(maxW / a).toInt())
                    newW = kotlin.math.round(newH * a).toInt().coerceIn(minW, maxW)
                    newH = kotlin.math.round(newW / a).toInt()
                    newX = x0
                    newY = y0
                    if (newY + newH > workBottom) {
                        newH = (workBottom - newY).coerceAtLeast(kotlin.math.round(minW / a).toInt())
                        newW = kotlin.math.round(newH * a).toInt().coerceIn(minW, maxW)
                        newH = kotlin.math.round(newW / a).toInt()
                    }
                    if (newX + newW > workRight) {
                        newX = (workRight - newW).coerceAtLeast(workLeft)
                    }
                }
                ResizeDirection.TOP_LEFT -> {
                    val proj = (-dx * a - dy) / (a * a + 1.0)
                    newW = (w0 + proj * a).toInt().coerceIn(minW, maxW)
                    newH = kotlin.math.round(newW / a).toInt()
                    newX = (x0 + w0) - newW
                    newY = (y0 + h0) - newH
                    if (newX < workLeft) {
                        newX = workLeft
                        newW = (x0 + w0 - newX).coerceAtMost(maxW)
                        newH = kotlin.math.round(newW / a).toInt()
                        newY = (y0 + h0) - newH
                    }
                    if (newY < workTop) {
                        newY = workTop
                        newH = (y0 + h0 - newY).coerceAtMost(kotlin.math.round(maxW / a).toInt())
                        newW = kotlin.math.round(newH * a).toInt().coerceIn(minW, maxW)
                        newH = kotlin.math.round(newW / a).toInt()
                        newX = (x0 + w0) - newW
                    }
                }
                ResizeDirection.TOP -> {
                    newH = (h0 - dy).coerceIn(kotlin.math.round(minW / a).toInt(), kotlin.math.round(maxW / a).toInt())
                    newW = kotlin.math.round(newH * a).toInt().coerceIn(minW, maxW)
                    newH = kotlin.math.round(newW / a).toInt()
                    newY = (y0 + h0) - newH
                    newX = (x0 + w0) - newW
                    if (newY < workTop) {
                        newY = workTop
                        newH = (y0 + h0 - newY).coerceAtMost(kotlin.math.round(maxW / a).toInt())
                        newW = kotlin.math.round(newH * a).toInt().coerceIn(minW, maxW)
                        newH = kotlin.math.round(newW / a).toInt()
                        newX = (x0 + w0) - newW
                    }
                    if (newX < workLeft) {
                        newX = workLeft
                    }
                }
                ResizeDirection.LEFT -> {
                    newW = (w0 - dx).coerceIn(minW, maxW)
                    newH = kotlin.math.round(newW / a).toInt()
                    newX = (x0 + w0) - newW
                    newY = (y0 + h0) - newH
                    if (newX < workLeft) {
                        newX = workLeft
                        newW = (x0 + w0 - newX).coerceAtMost(maxW)
                        newH = kotlin.math.round(newW / a).toInt()
                        newY = (y0 + h0) - newH
                    }
                    if (newY < workTop) {
                        newY = workTop
                    }
                }
                ResizeDirection.TOP_RIGHT -> {
                    val proj = (dx * a - dy) / (a * a + 1.0)
                    newW = (w0 + proj * a).toInt().coerceIn(minW, maxW)
                    newH = kotlin.math.round(newW / a).toInt()
                    newX = x0
                    newY = (y0 + h0) - newH
                    if (newX + newW > workRight) {
                        newW = (workRight - newX).coerceAtLeast(minW)
                        newH = kotlin.math.round(newW / a).toInt()
                        newY = (y0 + h0) - newH
                    }
                    if (newY < workTop) {
                        newY = workTop
                        newH = (y0 + h0 - newY).coerceAtMost(kotlin.math.round(maxW / a).toInt())
                        newW = kotlin.math.round(newH * a).toInt().coerceIn(minW, maxW)
                        newH = kotlin.math.round(newW / a).toInt()
                    }
                }
                ResizeDirection.BOTTOM_LEFT -> {
                    val proj = (-dx * a + dy) / (a * a + 1.0)
                    newW = (w0 + proj * a).toInt().coerceIn(minW, maxW)
                    newH = kotlin.math.round(newW / a).toInt()
                    newX = (x0 + w0) - newW
                    newY = y0
                    if (newX < workLeft) {
                        newX = workLeft
                        newW = (x0 + w0 - newX).coerceAtMost(maxW)
                        newH = kotlin.math.round(newW / a).toInt()
                    }
                    if (newY + newH > workBottom) {
                        newH = (workBottom - newY).coerceAtLeast(kotlin.math.round(minW / a).toInt())
                        newW = kotlin.math.round(newH * a).toInt().coerceIn(minW, maxW)
                        newH = kotlin.math.round(newW / a).toInt()
                        newX = (x0 + w0) - newW
                    }
                }
                ResizeDirection.NONE -> {}
            }

            if (newW > 0 && newH > 0) {
                userMiniWidth = newW
                w.setBounds(newX, newY, newW, newH)
            }
        }
    }

    val onWindowResizeEnd: () -> Unit = {
        owner?.let { w ->
            userMiniWidth = w.width
        }
    }

    LaunchedEffect(barVisible, closing, mini) {
        if (closing) return@LaunchedEffect
        if (mini) {
            if (barVisible) {
                barSlide.animateTo(0f, tween(280, easing = Sm.ease))
            } else {
                barSlide.animateTo(1f, tween(260, easing = Sm.ease))
            }
            return@LaunchedEffect
        }
        if (barVisible) {
            barSlide.animateTo(0f, tween(280, easing = Sm.ease))
        } else {
            barSlide.animateTo(1f, tween(260, easing = Sm.ease))
        }
    }

    LaunchedEffect(picker, infoOpen) {
        if (picker != null || infoOpen) {
            tallBottom = true
        } else {
            delay(400) // пусть доиграет анимация закрытия меню
            tallBottom = false
        }
    }

    var singleClickJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    @Composable
    fun RenderTopBar() {
        PlayerTopBar(
            title = title,
            queueSize = queue.size,
            index = index,
            onTop = onTop.value,
            onToggleOnTop = { onTop.value = !onTop.value },
            closing = closing,
            barSlide = barSlide.value,
            onClose = { close() },
            onPoke = { poke() }
        )
    }

    @Composable
    fun RenderBottomBar(inputHeight: androidx.compose.ui.unit.Dp? = null) {
        PlayerBottomBar(
            inputHeight = inputHeight,
            title = title,
            queue = queue,
            index = index,
            playing = playing,
            time = time,
            length = length,
            bufferedMs = bufferedMs,
            status = status,
            closing = closing,
            barSlide = barSlide.value,
            rate = rate,
            rates = rates,
            subScale = subScale,
            subDelay = subDelay,
            nightMode = nightMode,
            onTop = onTop.value,
            mini = mini,
            infoOpen = infoOpen,
            infoSource = infoSource,
            infoText = infoText,
            chapters = chapters,
            chapterAtTime = chapterAt(time),
            picker = picker,
            pickerItems = pickerItems,
            pickerCurrent = pickerCurrent,
            pickerLoading = pickerLoading,
            pickerPendingCount = pickerPendingCount,
            saveDelayText = activeCustom?.takeIf { it.mine }?.let { tr("player.zapomnit_zaderzhku", "ms" to signed(audioDelay)) },
            deleteTrackText = activeCustom?.takeIf { it.mine }?.let { tr(if (deleteArmed == it.id) "player.udalit_ozvuchku_tochno" else "player.udalit_ozvuchku") },
            onDeleteTrack = {
                val ct = activeCustom
                if (ct != null && deleteArmed != ct.id) deleteArmed = ct.id
                else if (ct != null) scope.launch {
                    deleteArmed = null
                    try {
                        ru.suslikovanora.media.desktop.CustomAudio.deleteTrack(ct.id)
                        PlayerLog.write("[custom-audio] ${ct.id} deleted")
                        ui.put("adelay:${ct.id}", null)
                        customTracks = customTracks.filter { it.id != ct.id }
                        applyCustomDelay("") // задержка фильма вместо задержки озвучки
                        val (list, cur) = withContext(Dispatchers.IO) { eAudioTracks() to eAudioCur() }
                        val next = list.firstOrNull { it.first != cur }
                        runOnPlayer {
                            if (next != null) eSetAudio(next.first)
                            engine.removeAudioTrack(cur)
                        }
                        next?.let { ui.put("audio:${seriesKey()}", it.second) }
                        trackMenuCache.clear()
                        delay(300); refreshPicker("audio")
                        flash(tr("player.ozvuchka_udalena"))
                    } catch (e: Exception) { flash(Api.describe(e)) }
                }
            },
            onSaveDelay = {
                val ct = activeCustom
                val d = audioDelay
                if (ct != null) scope.launch {
                    try {
                        ru.suslikovanora.media.desktop.CustomAudio.saveOffset(ct.id, d)
                        ct.audioMagnetId?.let { ru.suslikovanora.media.desktop.CustomAudio.rememberOffset(it, ct.audioPath, d) }
                        val saved = ct.copy(offsetMs = d)
                        customTracks = customTracks.map { if (it.id == ct.id) saved else it }
                        activeCustom = saved
                        ui.put("adelay:${ct.id}", null)
                        flash(tr("player.zaderzhka_sohranena", "ms" to signed(d)))
                    } catch (e: Exception) { flash(Api.describe(e)) }
                }
            },
            volume = volume,
            userMuted = userMuted,
            fullscreen = fullscreen,
            lastVolumeWheelAt = lastVolumeWheelAt,
            nextIn = nextIn,
            inIntro = inIntro(),
            seeking = seeking,
            onScrubChange = { seeking = it },
            onPoke = { poke() },
            onTogglePause = { togglePause() },
            onSeekBy = { seekBy(it) },
            onSeekTo = { t ->
                seeking = null
                seekByJob?.cancel(); seekByJob = null; pendingSeekByTarget = null
                if (partyGuest()) return@PlayerBottomBar
                bufferingShown = 0
                time = t
                lastGood = t
                lastSeekAt = System.currentTimeMillis()
                supRef.get()?.seeked()
                PlayerLog.write("seek to ${t / 1000}s")
                seekTo(t)
            },
            onPlayEntry = { if (it == index + 1 && nextIn >= 0) playNext() else playEntry(it) },
            onSkipIntro = { skipIntro() },
            nextTitle = if (index + 1 < queue.size) nextTitle() else "",
            onClosePicker = { closePicker() },
            onOpenPicker = { openPicker(it) },
            onSelectTrack = { kind, id, name ->
                lastSeekAt = System.currentTimeMillis()
                bufferingShown = 0
                trackSwitches++
                if (kind == "audio") {
                    val smart = PlaybackSettings.smartSubs()
                    runOnPlayer { eSetAudio(id); if (smart) applySmartSubs(id) }
                    // Субтитры теперь выбираются по звуку и при следующем открытии — пока не выберут сами
                    if (smart) ui.put("spu:${seriesKey()}", SMART_SUBS)
                    applyTrackNow()
                    applyCustomDelay(name)
                    ui.put("audio:${seriesKey()}", name)
                    ui.put("audio:*", name)
                    ru.suslikovanora.media.desktop.CloudSync.noteTrack(seriesKey(), name, null)
                } else {
                    runOnPlayer { eSetSub(id) }
                    val v = if (id == -1) "__off__" else name
                    ui.put("spu:${seriesKey()}", v)
                    ui.put("spu:*", v)
                    ru.suslikovanora.media.desktop.CloudSync.noteTrack(seriesKey(), null, v)
                }
                closePicker()
            },
            onVolumeChange = {
                volume = it
                val unmute = userMuted && it > 0
                if (unmute) userMuted = false
                runOnPlayer {
                    eVolume(it)
                    if (unmute) eMute(false)
                }
                poke()
            },
            onMuteToggle = {
                userMuted = !userMuted
                runOnPlayer { eMute(userMuted) }
                poke()
                flash(if (userMuted) tr("player.bez_zvuka") else tr("player.zvuk_volume", "volume" to volume))
            },
            onFullscreenToggle = { toggleFullscreen() },
            onMoreRate = { setSpeed(it) },
            onMoreSubScale = { v ->
                subScale = v
                ui.put("subScale", if (v == 100) null else v.toString())
                engine.setSubScale(v)
                poke()
                flash("${tr("player.subtitry")}: $v%")
            },
            onMoreSubDelay = { shiftSubs(it) },
            onMoreNight = {
                nightMode = !nightMode
                ui.put("night", if (nightMode) "1" else null)
                poke()
                flash(if (nightMode) tr("player.nochnoy_zvuk_vklyuchen") else tr("player.nochnoy_zvuk_vyklyuchen"))
                engine.setNightMode(nightMode)
            },
            onMoreOnTop = { onTop.value = !onTop.value },
            onMoreMini = {
                setMini(!mini)
                closePicker()
            },
            onMoreInfo = { toggleInfo("button") },
            onMoreChapter = { i -> jumpChapter(i - chapterAt(time)) },
            onCancelNextIn = { cancelNext() },
            onMiniToggle = { setMini(false) },
            onClose = { close() },
            live = partyGuest(),
            playLocked = partyPlayLocked(),
            onWindowDragStart = onWindowDragStart,
            onWindowDragMove = onWindowDragMove,
            onWindowDragEnd = onWindowDragEnd,
            onWindowResizeStart = onWindowResizeStart,
            onWindowResizeMove = onWindowResizeMove,
            onWindowResizeEnd = onWindowResizeEnd,
            onSetCursor = onSetCursor,
            onVolumeWheel = { delta ->
                val step = if (delta < 0) 5 else -5
                volume = (volume + step).coerceIn(0, 150)
                val v = volume
                runOnPlayer { eVolume(v) }
                if (userMuted) {
                    userMuted = false
                    runOnPlayer { eMute(false) }
                }
                lastVolumeWheelAt = System.currentTimeMillis()
                poke()
                flash(tr("player.gromkost_v", "v" to v))
            }
        )
    }

    @Composable
    fun SceneBars(maxHeight: androidx.compose.ui.unit.Dp) {
        if (closing || !(mini || barVisible || barSlide.value < 1f)) return
        Box(Modifier.fillMaxSize()) {
            if (!mini) Box(Modifier.fillMaxWidth().height(TOP_BAR_H.dp).align(Alignment.TopCenter)) { RenderTopBar() }
            val bottomH = if (mini) maxHeight else minOf(BOTTOM_BAR_H.dp, maxHeight - TOP_BAR_H.dp)
            val input = if (mini) null else if (tallBottom) bottomH else minOf(BOTTOM_INPUT_H.dp, bottomH)
            Box(Modifier.fillMaxWidth().height(bottomH).align(Alignment.BottomCenter)) { RenderBottomBar(input) }
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var lastPos: androidx.compose.ui.geometry.Offset? = null
                    while (true) {
                        val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        when (e.type) {
                            PointerEventType.Move, PointerEventType.Enter -> {
                                val p = e.changes.firstOrNull()?.position
                                if (p != lastPos) { lastPos = p; poke() }
                            }
                            PointerEventType.Exit -> { lastPos = null; hideBarSoon() }
                        }
                    }
                }
            }
    ) {
        val sceneMaxHeight = maxHeight
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .then(if (mini) Modifier else Modifier.pointerHoverIcon(if (barVisible) androidx.compose.ui.input.pointer.PointerIcon.Default else hiddenPointer))
                .pointerInput(Unit) {
                    var lastClickTime = 0L
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Press -> {
                                    poke()
                                    if (picker != null) closePicker()
                                    val now = System.currentTimeMillis()
                                    if (now - lastClickTime < 300L) {
                                        singleClickJob?.cancel()
                                        if (mini) {
                                            setMini(false)
                                        } else {
                                            toggleFullscreen()
                                        }
                                        lastClickTime = 0L
                                    } else {
                                        singleClickJob?.cancel()
                                        singleClickJob = scope.launch {
                                            delay(260)
                                            togglePause()
                                        }
                                        lastClickTime = now
                                    }
                                }
                                PointerEventType.Move -> {}
                                PointerEventType.Scroll -> {
                                    val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                    if (delta != 0f) {
                                        val step = if (delta < 0f) 5 else -5
                                        volume = (volume + step).coerceIn(0, 150)
                                        val v = volume
                                        runOnPlayer { eVolume(v) }
                                        if (userMuted) { userMuted = false; runOnPlayer { eMute(false) } }
                                        lastVolumeWheelAt = System.currentTimeMillis()
                                        poke()
                                        flash(tr("player.gromkost_v", "v" to v))
                                    }
                                }
                            }
                        }
                    }
                }
        ) {
            val hasFrame by remember { androidx.compose.runtime.derivedStateOf { (videoOut?.frame?.value ?: 0L) > 0L } }
            if (!parked && videoOut != null) MpvVideoSurface(videoOut, Modifier.fillMaxSize().onSizeChanged { sceneWidthPx = it.width })
            // Until the first frame of the film: its poster, as large as the window's height. The flight out of the tile
            // shows just the middle of the frame in the tile's shape, so it starts as exactly the poster that was clicked;
            // once the video is there the poster fades out over it. Closed before the first frame: the poster flies back.
            val showPoster = !hasFrame && lastFrame == null
            val posterAlpha by androidx.compose.animation.core.animateFloatAsState(if (showPoster) 1f else 0f, tween(if (showPoster) 0 else 380, easing = Sm.ease))
            val poster = bigPoster ?: openPoster
            if (parked || !hasFrame) lastFrame?.let { androidx.compose.foundation.Image(it, null, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            if (poster != null && posterAlpha > 0f) {
                androidx.compose.foundation.Image(poster, null, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = posterAlpha })
            }
            if ((parked || !hasFrame) && lastFrame == null && !closing) {
                Text(tr("player.otkryvaem"), color = if (poster != null) Color.White.copy(alpha = 0.85f) else Sm.muted, fontFamily = Onest, fontSize = 15.sp,
                    style = if (poster != null) androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 12f)) else androidx.compose.ui.text.TextStyle.Default,
                    modifier = Modifier.align(if (poster != null) Alignment.BottomCenter else Alignment.Center).padding(bottom = if (poster != null) 36.dp else 0.dp))
            }
        }
        SceneBars(sceneMaxHeight)
    }
}
