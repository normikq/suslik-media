package ru.suslikovanora.media.desktop.player

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
    fun savePos(t: Long, len: Long, i: Int = index) {
        val key = watchKey(i) ?: return
        if (t < 5_000L) return
        val done = len > 0 && t > len - 15_000L
        library.watch.put(key, "${if (done) len else t}|${len.coerceAtLeast(0)}|${System.currentTimeMillis()}")
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
    var enginePaused by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var retries by remember { mutableStateOf(0) }
    var lastGood by remember { mutableStateOf(initialPos) }
    var closing by remember { mutableStateOf(false) }
    var prefsApplied by remember { mutableStateOf(false) }
    var pausedAt by remember { mutableStateOf(0L) }
    var userMuted by remember { mutableStateOf(false) }
    var audioDelay by remember { mutableStateOf(0L) }
    var parked by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    var lastFrame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var pickerItems by remember { mutableStateOf(emptyList<Pair<Int, String>>()) }
    var pickerCurrent by remember { mutableStateOf(-2) }
    var pickerLoading by remember { mutableStateOf(false) }
    var pickerPendingCount by remember { mutableStateOf(0) }
    val trackMenuCache = remember { mutableMapOf<String, Pair<List<Pair<Int, String>>, Int>>() }
    var prefsUntil by remember { mutableStateOf(0L) }
    var rate by remember { mutableStateOf(ui.get("rate")?.toFloatOrNull()?.coerceIn(0.25f, 4f) ?: 1f) }
    var subDelay by remember { mutableStateOf(0L) }
    var chapters by remember { mutableStateOf(emptyList<Pair<Long, String>>()) }
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
            bufferedMs = if (length > 0L) (t + diskBufferMs).coerceIn(t, length) else 0L
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
                if (videoAspect != aspect) videoAspect = aspect
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
            engine.open(playUrl, startAt, nightMode, prefLanguage("audio"), prefLanguage("spu"),
                extras.first.map { BurstProxy.wrap(it) }, extras.second)
        }
    }

    fun reopen(at: Long) { val i = index; scope.launch { open(currentUrl, at, sidecars(i).await()) } }

    fun seekTo(t: Long) {
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

    fun applyPrefs() {
        val a = pref("audio"); val spu = pref("spu")
        if (a != null || spu != null) {
            if (a != null) eAudioTracks().firstOrNull { it.second == a }?.let { if (eAudioCur() != it.first) { eSetAudio(it.first); applyTrackNow() } }
            if (spu == "__off__") { if (eSubCur() != -1) eSetSub(-1) }
            else if (spu != null) eSubTracks().firstOrNull { it.second == spu }?.let { if (eSubCur() != it.first) eSetSub(it.first) }
        }
    }

    fun playEntry(i: Int) {
        if (i !in queue.indices) return
        savePos(time, length)
        index = i; nextIn = -1; currentUrl = queue[i].url; title = queue[i].title; retries = 0
        val url = currentUrl; val start = savedPos(i)
        playing = false; started = false; time = start; lastGood = start; length = 0L; status = tr("player.sleduyuschaya_seriya")
        scope.launch {
            val extras = withTimeoutOrNull(4000) { sidecars(i).await() } ?: (emptyList<String>() to emptyList())
            if (index == i && !closing) open(url, start, extras)
        }
    }

    fun refreshPicker(kind: String) {
        scope.launch {
            val audio = kind == "audio"
            val list = if (audio) eAudioTracks() else listOf(-1 to tr("player.vyklyuchit")) + eSubTracks()
            val cur = if (audio) eAudioCur() else eSubCur()
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

    fun togglePause() {
        val wasPaused = pausedAt > 0 || enginePaused
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

    var flashJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun flash(text: String) {
        status = text
        flashJob?.cancel()
        flashJob = scope.launch {
            delay(2000)
            if (status == text) status = null
        }
    }

    fun ensureAudio() {
        runOnPlayer {
            if (eAudioCur() != -1) return@runOnPlayer
            val first = eAudioTracks().firstOrNull() ?: return@runOnPlayer
            eSetAudio(first.first); applyTrackNow()
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
        val i = (chapterAt(time) + d).coerceIn(0, chapters.size - 1)
        val t = chapters[i].first; time = t; lastSeekAt = System.currentTimeMillis(); supRef.get()?.seeked()
        seekTo(t)
        flash(tr("player.glava_p_p", "n" to i + 1, "name" to chapters[i].second.ifBlank { tr("player.bez_nazvaniya") }))
    }

    val introRe = Regex("""(?i)(intro|opening|заставк|опенинг|вступлен|начальн|^op\b|\bop\s?\d*$)""")
    fun inIntro(): Boolean { val i = chapterAt(time); return i in 0 until chapters.size - 1 && introRe.containsMatchIn(chapters[i].second) }
    fun skipIntro() { if (inIntro()) jumpChapter(1) }

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

    fun setMini(on: Boolean) {
        if (on == mini || WindowMode.miniAnimating) return
        val target = owner ?: return
        if (on) {
            beforeMini[3] = onTop.value
            target.minimumSize = java.awt.Dimension(200, 150)
            val aspect = videoAspect.coerceIn(1.2, 2.4)
            val mw = Math.sqrt(590.0 * 330.0 * aspect).toInt().coerceIn(400, 620)
            val mh = (mw / aspect).toInt()
            WindowMode.setMiniMode(target, true, mw, mh) {
                mini = true
                onTop.value = true
            }
        } else {
            mini = false
            WindowMode.setMiniMode(target, false)
            onTop.value = beforeMini[3] as? Boolean ?: false
            target.minimumSize = java.awt.Dimension(MIN_W, MIN_H)
        }
    }

    fun shiftAudio(ms: Long) {
        audioDelay = (audioDelay + ms).coerceIn(-5_000L, 5_000L)
        val d = audioDelay
        ui.put("adelay:${seriesKey()}", if (d == 0L) null else d.toString())
        playerExecutor.execute { runCatching { eAudioDelay(d) } }
        flash(tr("player.zaderzhka_zvuka_p_d", "ms" to signed(d)))
    }

    fun seekBy(d: Long) {
        PlayerLog.write("seek ${if (d > 0) "+" else ""}${d / 1000}s")
        supRef.get()?.seeked()
        var t = (time + d).coerceAtLeast(0L)
        if (length > 0) t = t.coerceAtMost((length - 2_000L).coerceAtLeast(0L))
        time = t; lastGood = t; lastSeekAt = System.currentTimeMillis()
        seekTo(t)
    }

    fun toggleFullscreen() {
        if (mini) {
            setMini(false)
            return
        }
        WindowMode.toggleFullscreen(owner)
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

    fun close() {
        if (closing) return
        closing = true
        DisplayAwake.setAwake(false)
        savePos(time, length)
        library.bumpWatch()
        nextIn = -1
        supRef.get()?.closed()
        scope.launch {
            if (lastFrame == null) {
                playerAwait(180) { grabFrame() }?.let { lastFrame = it }
            }
            parked = true
            if (mini) setMini(false)
            MediaControls.stop()
            onClose()
            playerExecutor.execute { runCatching { eStop() } }
        }
    }

    // Keyboard shortcuts
    DisposableEffect(Unit) {
        keys.value = handler@{ e ->
            if (e.type != KeyEventType.KeyDown) return@handler false
            poke()
            when (e.key) {
                Key.Spacebar, Key.K -> togglePause()
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
                Key.F, Key.Enter, Key.F11 -> toggleFullscreen()
                Key.Escape -> if (WindowMode.isFullscreen) {
                    WindowMode.setFullscreen(owner, false)
                } else close()
                Key.Backspace -> close()
                Key.N -> playEntry(index + 1)
                Key.P -> playEntry(index - 1)
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
            onNext = { if (index + 1 < queue.size) playEntry(index + 1) },
            onPrevious = {
                PlayerLog.write("[smtc] previous (episode ${index + 1} of ${queue.size})")
                if (index > 0) playEntry(index - 1) else seekTo(0L)
            },
            onSeek = { targetMs -> seekTo(targetMs) }
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
    LaunchedEffect(Unit) {
        smtcPoster = runCatching { library.posterFileFor(req.posterQueries) }.getOrNull()
        posterLooked = true
        PlayerLog.write("[smtc] poster ${smtcPoster?.name ?: "none"}")
    }

    LaunchedEffect(title, index, queue.size, smtcPoster) {
        val (displayTitle, displaySubtitle) = smtcTitles()
        MediaControls.updateMetadata(displayTitle, displaySubtitle, smtcPoster)
        MediaControls.updateCapabilities(hasNext = index + 1 < queue.size, hasPrev = index > 0 || time > 5_000L)
    }

    val userPaused = pausedAt > 0L || enginePaused
    LaunchedEffect(userPaused) {
        MediaControls.updatePlayback(!userPaused)
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
        fun evPlaying() = ui2 {
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
                if (!started && currTime > 0) {
                    evPlaying()
                }
                if (seeking == null && System.currentTimeMillis() - lastSeekAt > 600 && (kotlin.math.abs(currTime - time) >= 250 || currTime < time)) time = currTime
                lastGood = currTime
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
                    if (index < queue.size - 1) nextIn = 10 else status = tr("player.konec")
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
            override fun opened() = evTracksChanged()
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

    LaunchedEffect(nextIn) {
        if (nextIn > 0) { delay(1000); nextIn-- } else if (nextIn == 0) playEntry(index + 1)
    }

    val fullscreen = WindowMode.isFullscreen

    LaunchedEffect(barVisible, lastMove, started, enginePaused, nextIn, picker, seeking, infoOpen) {
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
                bufferingShown = 0
                time = t
                lastGood = t
                lastSeekAt = System.currentTimeMillis()
                supRef.get()?.seeked()
                PlayerLog.write("seek to ${t / 1000}s")
                seekTo(t)
            },
            onPlayEntry = { playEntry(it) },
            onSkipIntro = { skipIntro() },
            onClosePicker = { closePicker() },
            onOpenPicker = { openPicker(it) },
            onSelectTrack = { kind, id, name ->
                lastSeekAt = System.currentTimeMillis()
                bufferingShown = 0
                trackSwitches++
                if (kind == "audio") {
                    runOnPlayer { eSetAudio(id) }
                    applyTrackNow()
                    ui.put("audio:${seriesKey()}", name)
                    ui.put("audio:*", name)
                } else {
                    runOnPlayer { eSetSub(id) }
                    val v = if (id == -1) "__off__" else name
                    ui.put("spu:${seriesKey()}", v)
                    ui.put("spu:*", v)
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
            onCancelNextIn = { nextIn = -1 },
            onMiniToggle = { setMini(false) },
            onClose = { close() },
            onWindowDragStart = onWindowDragStart,
            onWindowDragMove = onWindowDragMove,
            onWindowDragEnd = onWindowDragEnd,
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
                .pointerHoverIcon(if (mini || barVisible) androidx.compose.ui.input.pointer.PointerIcon.Default else hiddenPointer)
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
            if (parked || !hasFrame) lastFrame?.let { androidx.compose.foundation.Image(it, null, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                ?: Text(if (closing) "" else tr("player.otkryvaem"), color = Sm.muted, fontFamily = Onest, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center))
        }
        SceneBars(sceneMaxHeight)
    }
}
