package ru.suslikovanora.media.desktop.player

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.SwingPanel
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
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
import ru.suslikovanora.media.core.TsClock
import ru.suslikovanora.media.core.TsPlayback
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
import ru.suslikovanora.media.desktop.TitleBar
import ru.suslikovanora.media.desktop.WindowMode
import ru.suslikovanora.media.desktop.ui.Onest
import ru.suslikovanora.media.desktop.ui.Sm
import ru.suslikovanora.media.desktop.ui.SuslikTheme
import javax.swing.SwingUtilities
import ru.suslikovanora.media.desktop.tr
import ru.suslikovanora.media.desktop.signed

/** Дольше всего прокси держит фильм, копя запас на сети медленнее фильма (как на Android). */

/**
 * Player inside the main window. VLC draws straight into a native canvas (original resolution, hardware decoding,
 * DirectX output, no frame copying); the controls are Compose bars layered over the picture.
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
    parentWindow: java.awt.Window? = null,  // main window for owning transparent overlay dialogs
    onRestart: (Int) -> Unit = {},          // re-create the player at this episode (subtitle size)
    onClose: () -> Unit
) {
    val ui = remember { Store("player_ui") }
    val scope = rememberCoroutineScope()
    val vlcExecutor = remember {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "vlc-control").apply { isDaemon = true }
        }.also { AppExit.track(it) }
    }
    val vlcThread = remember { vlcExecutor.asCoroutineDispatcher() }

    /** Every call into VLC goes through here, onto the player's own thread. */
    fun vlc(block: () -> Unit) { runCatching { vlcExecutor.execute { runCatching(block) } } }

    /**
     * Ответ от потока VLC с настоящим тайм-аутом. `withTimeoutOrNull { withContext(vlcThread) { … } }` не годится:
     * пока поток занят зависшим вызовом VLC (остановка или перемотка при провале сети), блок не стартует,
     * а withContext не завершается, и меню дорожек/выход из плеера ждали бы бесконечно. Здесь ждём `await`, его можно оборвать.
     */
    suspend fun <T> vlcAwait(ms: Long, block: () -> T): T? {
        val job = scope.async(vlcThread) { runCatching(block).getOrNull() }
        return withTimeoutOrNull(ms) { job.await() }
    }

    /**
     * [vlcAwait] for a loop: while the loop's previous call still waits for the VLC thread ([pending]), nothing new is
     * queued and the tick is skipped (null) — a hung VLC call must not stop the loop or pile up calls behind it.
     */
    suspend fun <T> vlcPoll(pending: java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Deferred<*>?>, ms: Long, block: () -> T): T? {
        if (pending.get()?.isActive == true) return null
        val job = scope.async(vlcThread) { runCatching(block).getOrNull() }
        pending.set(job)
        return withTimeoutOrNull(ms) { job.await() }
    }

    var nightMode by remember { mutableStateOf(ui.get("night") == "1") }
    var subScale by remember { mutableStateOf(ui.get("subScale")?.toIntOrNull()?.coerceIn(50, 300) ?: 100) }
    val canvas = remember { java.awt.Canvas().apply { background = java.awt.Color.BLACK; isFocusable = false } }
    // libmpv включается флагом (см. EngineChoice). VLC создаётся лениво: в режиме mpv к нему не прикасаются.
    val engBridge = remember { EngineBridge() }
    val eng: PlayerEngine? = remember { if (EngineChoice.useMpv) MpvEngine(engBridge, PlaybackSettings.cacheMs(), subScale) else null }
    val mp by remember {
        lazy {
            val factory = VlcFactory.get(subScale)
            factory.mediaPlayers().newEmbeddedMediaPlayer().also {
                it.videoSurface().set(factory.videoSurfaces().newVideoSurface(canvas))
                it.input().enableMouseInputHandling(false)
                it.input().enableKeyInputHandling(false)
            }
        }
    }
    // One engine for everything below: mpv or VLC (VlcPlayer, created with the first call — in mpv mode never).
    // [eng] stays the mpv engine (null with VLC) for what only one of them does. Any call may come from the control thread.
    val engine: PlayerEngine by remember { lazy { eng ?: VlcPlayer(mp, engBridge) } }
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
    var ts by remember { mutableStateOf<TsPlayback?>(null) }
    var tsLen by remember { mutableStateOf(0L) }
    var tsLenUrl by remember { mutableStateOf("") }
    var tsStartAt by remember { mutableStateOf(0L) }
    var tsOpenDone by remember { mutableStateOf(true) }
    var tsAbsolute by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(tr("player.otkryvaem")) }
    var volume by remember { mutableStateOf(ui.get("volume")?.toIntOrNull() ?: 100) }
    var lastVolumeWheelAt by remember { mutableStateOf(0L) }
    var queue by remember { mutableStateOf(listOf(Entry(req.title, req.url))) }
    var currentUrl by remember { mutableStateOf(req.url) }
    var nextIn by remember { mutableStateOf(-1) }
    var barVisible by remember { mutableStateOf(true) }
    var lastMove by remember { mutableStateOf(System.currentTimeMillis()) }
    var started by remember { mutableStateOf(false) }
    var vlcPaused by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var retries by remember { mutableStateOf(0) }
    var lastGood by remember { mutableStateOf(initialPos) }
    var showCanvas by remember { mutableStateOf(false) }
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
    var trackSwitches by remember { mutableStateOf(0) } // audio / subtitle switches from the menu (see the watch below)
    // Сеть медленнее фильма: прокси держит фильм, пока не накопится запас (как на Android, см. PlayerActivity.maybeCatchUp)
    val catchUp = remember { CatchUp({ PlayerLog.write(it) }) } // the rules are shared with Android
    var catchingUp by remember { mutableStateOf(false) }
    var catchUpStatus by remember { mutableStateOf<String?>(null) }
    var openedAt by remember { mutableStateOf(0L) }
    val supRef = remember { java.util.concurrent.atomic.AtomicReference<PlaybackSupervisor?>(null) }
    var bufferedMs by remember { mutableStateOf(0L) }
    // Высокое нижнее окно нужно только под всплывающие меню; иначе его прозрачная верхняя часть перехватывает клики по видео
    var tallBottom by remember { mutableStateOf(false) }
    var lastClip by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }

    // time is read inside the loop, so it must not be a key (it would restart the effect every tick)
    val timeNow by rememberUpdatedState(time)
    LaunchedEffect(barVisible, currentUrl, length, playing, bufferingSince) {
        if (!barVisible) return@LaunchedEffect
        while (true) {
            val vlcTimeBufferMs = if (playing || bufferingSince > 0L) 6_000L else 0L
            val diskBufferMs = BurstProxy.aheadMs(currentUrl, length)
            val t = timeNow
            bufferedMs = if (length > 0L) (t + vlcTimeBufferMs + diskBufferMs).coerceIn(t, length) else 0L
            delay(200L)
        }
    }

    val beforeMini = remember { arrayOfNulls<Any>(4) }
    var videoAspect by remember { mutableStateOf(16.0 / 9.0) }
    LaunchedEffect(Unit) {
        val pending = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Deferred<*>?>()
        while (true) {
            val d = vlcPoll(pending, 1000) { eDim() }
            if (d != null && d.first > 0 && d.second > 0) {
                val aspect = d.first.toDouble() / d.second
                if (videoAspect != aspect) videoAspect = aspect
            }
            delay(1500)
        }
    }
    fun seriesKey() = title.substringBefore(" · ").lowercase()
    fun prefLanguage(kind: String): String? =
        (ui.get("$kind:${seriesKey()}") ?: ui.get("$kind:*"))?.let { Regex("""\[([^\]]+)]""").find(it)?.groupValues?.get(1) }

    val sidecarCache = remember { java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.Deferred<Pair<List<String>, List<String>>>>() }
    fun sidecars(i: Int): kotlinx.coroutines.Deferred<Pair<List<String>, List<String>>> = sidecarCache.getOrPut(i) {
        if (i == 0 || req.sidecars == null) kotlinx.coroutines.CompletableDeferred(if (i == 0) req.audioUrls to req.subUrls else emptyList<String>() to emptyList())
        else scope.async(Dispatchers.IO) { runCatching { req.sidecars?.invoke(i) }.getOrNull() ?: (emptyList<String>() to emptyList()) }
    }

    val owner = parentWindow ?: runCatching { java.awt.Frame.getFrames().firstOrNull { it.isShowing } }.getOrNull()

    val topDialog = remember(owner) {
        if (owner == null) null else ComposeDialog(owner).apply {
            isUndecorated = true
            isTransparent = true
            isResizable = false
            focusableWindowState = false
            isAutoRequestFocus = false
        }
    }
    val bottomDialog = remember(owner) {
        if (owner == null) null else ComposeDialog(owner).apply {
            isUndecorated = true
            isTransparent = true
            isResizable = false
            focusableWindowState = false
            isAutoRequestFocus = false
        }
    }

    fun applyDialogBounds(d: ComposeDialog?, r: java.awt.Rectangle) {
        if (d == null || !d.isDisplayable) return
        WindowMode.makeNoActivate(d)
        if (d.bounds != r) {
            val resized = d.width != r.width || d.height != r.height
            // Сначала напрямую через Win32 (без отставания от главного окна); AWT подхватит новые границы сам
            if (!WindowMode.moveNative(d, r.x, r.y, r.width, r.height)) {
                if (resized) d.bounds = r else d.setLocation(r.x, r.y)
            }
            if (resized) d.revalidate()
        }
    }

    fun syncOverlayBounds() {
        if (parked || WindowMode.miniAnimating || owner?.isShowing != true) return
        val topVis = topDialog?.isVisible == true
        val botVis = bottomDialog?.isVisible == true
        if (!barVisible && !topVis && !botVis && !mini) return

        val root = (owner as? javax.swing.RootPaneContainer)?.contentPane
        val target = if (root != null && root.isShowing && root.width > 0 && root.height > 0) {
            root
        } else if (canvas.isShowing && canvas.width > 0 && canvas.height > 0) {
            canvas
        } else {
            owner ?: return
        }

        val loc = runCatching { target.locationOnScreen }.getOrNull() ?: return
        val w = target.width
        val h = target.height
        if (w <= 0 || h <= 0) return

        if (mini) {
            topDialog?.isVisible = false
            val fullRect = java.awt.Rectangle(loc.x, loc.y, w, h)
            applyDialogBounds(bottomDialog, fullRect)
            val d = bottomDialog ?: return
            val key = Triple(w, h, h)
            if (d.isDisplayable && key != lastClip) { lastClip = key; WindowMode.clipToBottom(d, h) }
            return
        }

        val topH = 75
        val bottomH = 460.coerceAtMost(h - topH)

        val topRect = java.awt.Rectangle(loc.x, loc.y, w, topH)
        val bottomRect = java.awt.Rectangle(loc.x, loc.y + h - bottomH, w, bottomH)
        applyDialogBounds(topDialog, topRect)
        applyDialogBounds(bottomDialog, bottomRect)

        // Окно всегда высотой под меню (без перестройки и подёргиваний), но клики ловит только его нужная часть
        bottomDialog?.let { d ->
            val visible = if (tallBottom) bottomH else 220.coerceAtMost(bottomH)
            val key = Triple(d.width, d.height, visible)
            if (d.isDisplayable && key != lastClip) { lastClip = key; WindowMode.clipToBottom(d, visible) }
        }
    }

    fun grabFrame(): androidx.compose.ui.graphics.ImageBitmap? {
        val img = runCatching { engine.snapshot(canvas.width.coerceIn(320, 1920)) }.getOrNull() ?: return null
        val bmp = img.toComposeImageBitmap()
        SwingUtilities.invokeLater { lastFrame = bmp }
        return bmp
    }

    var openJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var ensureAudioJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var chaptersJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var canvasFocusJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun open(url: String, startAt: Long, extras: Pair<List<String>, List<String>>) {
        ensureAudioJob?.cancel()
        chaptersJob?.cancel()
        canvasFocusJob?.cancel()
        status = tr("player.otkryvaem")
        prefsApplied = false
        chapters = emptyList()
        lastGood = startAt
        time = startAt
        audioDelay = ui.get("adelay:${seriesKey()}")?.toLongOrNull() ?: 0L
        subDelay = ui.get("sdelay:${seriesKey()}")?.toLongOrNull() ?: 0L
        pausedAt = 0L

        Reserve.init()
        BurstProxy.capBytes = PlaybackSettings.burstMb().toLong() shl 20
        BurstProxy.keepOnly(listOf(url) + extras.first)
        supRef.get()?.opening(url)
        openedAt = System.currentTimeMillis()
        PlayerLog.write("open ${url.substringBefore('?').substringAfterLast('/')} at ${startAt / 1000}s, voice-overs=${extras.first.size}, " +
            "buffer=${PlaybackSettings.cacheMs()}ms, reserve=${PlaybackSettings.burstMb()}MB")
        val playUrl = BurstProxy.wrap(url)
        val isTs = eng == null && TsClock.isTs(url)
        tsStartAt = startAt; tsOpenDone = !isTs; if (!isTs) { ts = null; tsAbsolute = false }
        val prev = openJob
        openJob = scope.launch(vlcThread) {
            prev?.join()
            if (closing) return@launch
            if (isTs) {
                if (tsLenUrl != url) {
                    val r = withContext(Dispatchers.IO) { TsClock.probe(url) }
                    tsLen = r?.first ?: 0L; tsLenUrl = url
                    PlayerLog.write(if (r != null) "[ts] duration from the stream's clock: ${fmt(r.first)}" else "[ts] no duration in the stream's clock")
                }
                withContext(Dispatchers.Main) { ts = if (tsLen > 0) TsPlayback(tsLen, url) else null; if (tsLen > 0) length = tsLen }
                if (closing) return@launch
            }
            if (ePlayable()) {
                grabFrame()
                runCatching { eStop() }
            }
            // A Blu-ray starts from the beginning: VLC ignores :start-time there, TsPlayback jumps by position instead
            engine.open(playUrl, if (isTs) 0L else startAt, nightMode, prefLanguage("audio"), prefLanguage("spu"),
                extras.first.map { BurstProxy.wrap(it) }, extras.second)
        }
    }

    fun reopen(at: Long) { val i = index; scope.launch { open(currentUrl, at, sidecars(i).await()) } }

    fun vlcSeekTo(t: Long) {
        lastGood = t
        val s = ts
        if (s == null) { vlc { eSetTime(t) }; return }
        if (s.offset > 0 && t < s.offset + 2_000L) {
            PlayerLog.write("[ts] seek to ${t / 1000}s is before VLC's clock (starts at ${s.offset / 1000}s): reopening with the real clock")
            tsAbsolute = true; reopen(t); return
        }
        vlc {
            val now = s.filmTime(engine.timeMs); val nowPos = engine.fraction
            val pos = s.seek(t, now, nowPos)
            PlayerLog.write("[ts] seek %ds → position %.4f (now %ds at %.4f)".format(t / 1000, pos, now / 1000, nowPos))
            engine.seekToFraction(pos)
        }
    }

    fun reseekHereNow() {
        if (ts != null) engine.seekToFraction(engine.fraction)
        else { val t = eTime(); if (t > 0) eSetTime(t) }
    }

    fun applyTrackNow() {
        supRef.get()?.seeked()
        // VLC only: a seek right here empties its 3–6 s network cache, so the new sound is heard at once. mpv switches the
        // track itself (its own refresh seek); a second seek right on top of that one is not needed.
        if (eng != null) return
        scope.launch(vlcThread) {
            delay(120)
            runCatching { reseekHereNow() }
        }
    }

    fun applyPrefs() {
        val audio = ui.get("audio:${seriesKey()}") ?: ui.get("audio:*")
        val spu = ui.get("spu:${seriesKey()}") ?: ui.get("spu:*")
        vlc {
            if (audio != null) eAudioTracks().firstOrNull { it.second == audio }?.let { if (eAudioCur() != it.first) { eSetAudio(it.first); applyTrackNow() } }
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

    fun trackList(audio: Boolean): List<Pair<Int, String>> {
        val map = LinkedHashMap<Int, String>()
        if (eng != null) return runCatching { if (audio) eAudioTracks() else eSubTracks() }.getOrDefault(emptyList())
        runCatching {
            val desc = if (audio) mp.audio().trackDescriptions() else mp.subpictures().trackDescriptions()
            desc.filter { it.id() != -1 }.forEach { map[it.id()] = it.description() }
        }
        val extra = ArrayList<String>()
        runCatching {
            val info = mp.media().info()
            val declared = if (audio) info?.audioTracks() else info?.textTracks()
            declared?.forEach { t ->
                if (t.id() !in map) {
                    val name = listOfNotNull(
                        t.description()?.takeIf { it.isNotBlank() },
                        t.language()?.takeIf { it.isNotBlank() }?.let { "[$it]" },
                        t.codecDescription()?.takeIf { it.isNotBlank() }
                    ).joinToString(" ").ifBlank { tr("player.dorozhka_p", "p" to t.id()) }
                    if (TsClock.isTs(currentUrl)) extra += name else map[t.id()] = name
                }
            }
        }
        if (extra.isNotEmpty()) {
            val generic = map.entries.filter { GENERIC_TRACK.matches(it.value.trim()) }
            generic.forEachIndexed { i, e -> extra.getOrNull(i)?.let { e.setValue(it) } }
        }
        return map.toList()
    }

    val trackMenuCache = remember { mutableMapOf<String, Pair<List<Pair<Int, String>>, Int>>() }

    fun openPicker(kind: String) {
        tallBottom = true
        syncOverlayBounds()
        if (kind == "more") {
            picker = "more"
            return
        }
        val cached = trackMenuCache[kind]
        if (cached != null) {
            pickerItems = cached.first
            pickerCurrent = cached.second
            picker = kind
        }
        scope.launch {
            val fresh = if (eng != null) {
                vlcAwait(700) {
                    val audio = kind == "audio"
                    val list = if (audio) eAudioTracks() else listOf(-1 to tr("player.vyklyuchit")) + eSubTracks()
                    val cur = if (audio) eAudioCur() else eSubCur()
                    list to cur
                }
            } else {
                vlcAwait(700) {
                    val audio = kind == "audio"
                    val list = trackList(audio)
                    (if (audio) list else listOf(-1 to tr("player.vyklyuchit")) + list) to
                        runCatching { if (audio) eAudioCur() else eSubCur() }.getOrDefault(-2)
                }
            }
            if (fresh != null) trackMenuCache[kind] = fresh
            val (items, cur) = fresh ?: trackMenuCache[kind] ?: (emptyList<Pair<Int, String>>() to -2)
            pickerItems = items; pickerCurrent = cur; picker = kind
        }
    }

    fun closePicker() {
        picker = null
    }

    fun toggleInfo(source: String = "key") {
        infoSource = source
        infoOpen = !infoOpen
    }

    fun togglePause() {
        val wasPaused = pausedAt > 0 || vlcPaused
        if (!wasPaused) {
            playing = false; pausedAt = System.currentTimeMillis()
            supRef.get()?.paused()
            vlc { ePause(true) }
        } else {
            val paused = if (pausedAt > 0) System.currentTimeMillis() - pausedAt else 0L
            val longPause = paused > 8_000L && !BurstProxy.isActive(currentUrl)
            PlayerLog.write("resume after ${paused} ms${if (longPause) " (re-seek)" else ""}")
            supRef.get()?.resumed()
            playing = true; pausedAt = 0L; vlcPaused = false
            val m = userMuted
            vlc {
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
        vlc {
            if (eAudioCur() != -1) return@vlc
            val first = eAudioTracks().firstOrNull() ?: return@vlc
            eSetAudio(first.first); applyTrackNow()
            SwingUtilities.invokeLater { flash(tr("player.zvuk_p", "p" to first.second)) }
        }
    }

    fun shiftSubs(ms: Long) {
        subDelay = (subDelay + ms).coerceIn(-10_000L, 10_000L)
        val d = subDelay
        ui.put("sdelay:${seriesKey()}", if (d == 0L) null else d.toString())
        vlcExecutor.execute { runCatching { eSubDelay(d) } }
        flash(tr("player.subtitry_p_d_ms", "ms" to signed(d)))
    }

    val rates = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    fun setSpeed(r: Float) {
        rate = r; ui.put("rate", if (r == 1f) null else r.toString())
        vlcExecutor.execute { runCatching { eRate(r) } }
        flash(tr("player.skorost_p", "p" to fmtRate(r)))
    }
    fun stepSpeed(d: Int) { val i = rates.indexOfFirst { it >= rate - 0.01f }.coerceAtLeast(0); setSpeed(rates[(i + d).coerceIn(0, rates.size - 1)]) }

    fun chapterAt(t: Long): Int = chapters.indexOfLast { it.first <= t + 500 }
    fun jumpChapter(d: Int) {
        if (chapters.size < 2) return
        val i = (chapterAt(time) + d).coerceIn(0, chapters.size - 1)
        val t = chapters[i].first; time = t; lastSeekAt = System.currentTimeMillis(); supRef.get()?.seeked()
        vlcSeekTo(t)
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
            vlcAwait(800) { grabFrame(); runCatching { eStop() } }
            showCanvas = false
            onRestart(index)
        }
    }

    fun setMini(on: Boolean) {
        if (on == mini || WindowMode.miniAnimating) return
        val target = owner ?: javax.swing.SwingUtilities.getWindowAncestor(canvas) ?: return
        if (on) {
            beforeMini[3] = onTop.value
            target.minimumSize = java.awt.Dimension(200, 150)
            // Размер как у картинки ~590×330 (16:9) по площади, но под формат фильма (ширина 400–620 dp);
            // формат берём из кэша — VLC нельзя дёргать из потока интерфейса
            val aspect = videoAspect.coerceIn(1.2, 2.4)
            val mw = Math.sqrt(590.0 * 330.0 * aspect).toInt().coerceIn(400, 620)
            val mh = (mw / aspect).toInt()
            // Оверлеи прячем на время «уезда»: иначе каждый кадр анимации пересчитывает их границы
            topDialog?.isVisible = false
            bottomDialog?.isVisible = false
            WindowMode.setMiniMode(target, true, mw, mh) {
                // Тяжёлая перестройка интерфейса под мини-режим — только после полёта окна
                mini = true
                // Compose-флаг «поверх всех» включаем только после полёта, чтобы он не сбил анимацию
                onTop.value = true
                lastClip = null
                syncOverlayBounds()
                bottomDialog?.isVisible = !closing
            }
        } else {
            mini = false
            WindowMode.setMiniMode(target, false)
            onTop.value = beforeMini[3] as? Boolean ?: false
            target.minimumSize = java.awt.Dimension(MIN_W, MIN_H)
            lastClip = null
            syncOverlayBounds()
        }
        javax.swing.SwingUtilities.invokeLater { syncOverlayBounds() }
    }

    fun shiftAudio(ms: Long) {
        audioDelay = (audioDelay + ms).coerceIn(-5_000L, 5_000L)
        val d = audioDelay
        ui.put("adelay:${seriesKey()}", if (d == 0L) null else d.toString())
        vlcExecutor.execute { runCatching { eAudioDelay(d) } }
        flash(tr("player.zaderzhka_zvuka_p_d", "ms" to signed(d)))
    }

    fun seekBy(d: Long) {
        PlayerLog.write("seek ${if (d > 0) "+" else ""}${d / 1000}s")
        supRef.get()?.seeked()
        var t = (time + d).coerceAtLeast(0L)
        if (length > 0) t = t.coerceAtMost((length - 2_000L).coerceAtLeast(0L))
        time = t; lastGood = t; lastSeekAt = System.currentTimeMillis()
        vlcSeekTo(t)
    }

    fun toggleFullscreen() {
        if (mini) {
            setMini(false)
            return
        }
        WindowMode.toggleFullscreen(owner)
        syncOverlayBounds()
        javax.swing.SwingUtilities.invokeLater { syncOverlayBounds() }
    }

    fun poke() {
        lastMove = System.currentTimeMillis()
        if (!barVisible) barVisible = true
    }

    fun close() {
        if (closing) return
        closing = true
        savePos(time, length)
        library.bumpWatch()
        nextIn = -1
        supRef.get()?.closed()
        scope.launch {
            if (lastFrame == null) {
                vlcAwait(180) { grabFrame() }?.let { lastFrame = it }
            }
            parked = true
            if (mini) setMini(false)
            onClose()
            vlcExecutor.execute { runCatching { eStop() } }
        }
    }

    LaunchedEffect(Unit) {
        WindowMode.restoreComposeFocus(owner)
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
                    vlc { eVolume(v) }
                    if (userMuted) {
                        userMuted = false
                        vlc { eMute(false) }
                    }
                    lastVolumeWheelAt = System.currentTimeMillis()
                    flash(tr("player.gromkost_v", "v" to v))
                }
                Key.DirectionDown -> {
                    volume = (volume - 5).coerceAtLeast(0)
                    val v = volume
                    vlc { eVolume(v) }
                    if (userMuted) {
                        userMuted = false
                        vlc { eMute(false) }
                    }
                    lastVolumeWheelAt = System.currentTimeMillis()
                    flash(tr("player.gromkost_v", "v" to v))
                }
                Key.F, Key.Enter, Key.F11 -> toggleFullscreen()
                Key.Escape -> if (WindowMode.isFullscreen) {
                    WindowMode.setFullscreen(owner, false)
                    syncOverlayBounds()
                    javax.swing.SwingUtilities.invokeLater { syncOverlayBounds() }
                } else close()
                Key.Backspace -> close()
                Key.N -> playEntry(index + 1)
                Key.P -> playEntry(index - 1)
                Key.M -> { userMuted = !userMuted; val m = userMuted; vlc { eMute(m) } }
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

    if (Diag.on) {
        DisposableEffect(Unit) { Diag.playerOpen = true; onDispose { Diag.playerOpen = false } }
        LaunchedEffect(Unit) {
            var prev: Map<String, Long>? = null
            var prevAt = 0L
            var described = false
            val pending = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Deferred<*>?>()
            while (true) {
                delay(5_000)
                if (closing) continue
                val line = vlcPoll(pending, 3000) { runCatching {
                    if (eng != null) return@runCatching eng.statsLine()
                    if (!described && mp.status().isPlaying) {
                        described = true
                        PlayerLog.write("[vlc] video: " + describePlayback(mp))
                    }
                    val now = System.currentTimeMillis()
                    val st = vlcStats(mp)
                    val p = prev
                    fun d(k: String) = (st[k] ?: 0L) - (if (p != null && (st["read"] ?: 0L) >= (p["read"] ?: 0L)) p[k] ?: 0L else 0L)
                    val span = if (prevAt == 0L) 0L else now - prevAt
                    val read = d("read")
                    val sb = StringBuilder("[vlc] %.1f/%ds ".format(mp.status().time() / 1000.0, mp.status().length() / 1000))
                    sb.append(when { mp.status().isPlaying -> "playing"; vlcPaused -> "paused"; else -> "not playing" })
                    if (bufferingSince > 0) sb.append(" BUFFERING")
                    if (rate != 1f) sb.append(" speed ${rate}x")
                    sb.append(" · read +%.2f MB%s".format(read / 1048576.0, if (span > 0) " (%.1f Mbit/s)".format(read * 8.0 / 1000 / span) else ""))
                    sb.append(" · pictures shown +${d("shown")} lost +${d("lost")} · decoded video +${d("video")} audio +${d("audio")}")
                    sb.append(" · audio played +${d("played")} lost +${d("aLost")}")
                    if ((st["corrupt"] ?: 0L) > 0 || (st["discont"] ?: 0L) > 0) sb.append(" · demux corrupted ${st["corrupt"]} discontinuities ${st["discont"]}")
                    BurstProxy.aheadMb(currentUrl)?.let { sb.append(" · reserve ahead %.1f MB".format(it)) }
                    prev = st; prevAt = now
                    sb.toString()
                }.getOrNull() }
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
        fun oneShot(ms: Long, action: () -> Unit) {
            scope.launch {
                delay(ms)
                action()
            }
        }
        fun evPlaying() = ui2 {
                WindowMode.disableNativeCanvasInput(canvas)
                WindowMode.restoreComposeFocus(owner)
                canvasFocusJob?.cancel()
                canvasFocusJob = scope.launch {
                    delay(250)
                    WindowMode.disableNativeCanvasInput(canvas)
                    WindowMode.restoreComposeFocus(owner)
                }
                val wasStarted = started
                playing = true; status = null; retries = 0; started = true; vlcPaused = false
                bufferingShown = 0
                bufferingPct = 100f
                if (bufferingSince > 0L) {
                    val ms = System.currentTimeMillis() - bufferingSince
                    bufferingSince = 0L
                    if (ms > 1500) PlayerLog.write("buffering took $ms ms at ${time / 1000}s")
                }
                ts?.let { s ->
                    BurstProxy.setDuration(currentUrl, s.lengthMs)
                    if (!tsOpenDone && !tsAbsolute) { tsOpenDone = true; s.openJump(tsStartAt)?.let { pos -> PlayerLog.write("[ts] continue at ${tsStartAt / 1000}s → position %.4f".format(pos)); vlc { engine.seekToFraction(pos) } } }
                }
                if (pausedAt == 0L) supRef.get()?.playing()
                if (!prefsApplied) {
                    prefsApplied = true
                    prefsUntil = System.currentTimeMillis() + 8_000L
                    schedulePrefs(300)
                    ensureAudioJob?.cancel()
                    ensureAudioJob = scope.launch {
                        delay(4500)
                        ensureAudio()
                    }
                    if (audioDelay != 0L) { val d = audioDelay; vlcExecutor.execute { runCatching { eAudioDelay(d) } } }
                    if (subDelay != 0L) { val d = subDelay; vlcExecutor.execute { runCatching { eSubDelay(d) } } }
                    if (rate != 1f) { val r = rate; vlcExecutor.execute { runCatching { eRate(r) } } }
                    chaptersJob?.cancel()
                    chaptersJob = scope.launch {
                        delay(1200)
                        vlcAwait(3000) { engine.chapters().map { it.timeMs to it.title } }?.let { chapters = it }
                    }
                }
                val v = volume; val m = userMuted
                if (pausedAt > 0) {
                    playing = false
                    vlc { eMute(true); ePause(true) }
                } else {
                    vlc {
                        eMute(m)
                        if (!wasStarted) eVolume(v)
                    }
                }
            }
        fun evStream() = ui2 {
                if (System.currentTimeMillis() < prefsUntil) { schedulePrefs(400) }
            }
        fun evPaused() = ui2 { playing = false; vlcPaused = true; savePos(time, length) }
        fun evStopped() = ui2 { vlcPaused = false }
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
                if (newLength > 0 || ts == null) { length = newLength; BurstProxy.setDuration(currentUrl, newLength) }
            }
        fun evTime(vlcTime: Long, pos: Float) {
                ui2 tick@{
                    if (!started && vlcTime > 0) {
                        evPlaying()
                    }
                    ts?.let { s0 -> if (tsAbsolute && !tsOpenDone && vlcTime > 0) { tsAbsolute = false; tsOpenDone = true; s0.openJump(tsStartAt)?.let { jumpPos -> vlc { engine.seekToFraction(jumpPos) } } } }
                    val s = ts
                    val newTime = if (s == null) vlcTime else {
                        val f = if (s.opening) s.openLanded(vlcTime, pos) else s.filmTime(vlcTime)
                        if (f == null || (s.seeking && !s.seekLanded(f, pos))) return@tick
                        s.tick(vlcTime, pos)
                        f
                    }
                    if (seeking == null && System.currentTimeMillis() - lastSeekAt > 600 && (kotlin.math.abs(newTime - time) >= 250 || newTime < time)) time = newTime
                    lastGood = newTime
                    if (newTime / 10_000 != (newTime - 300) / 10_000) savePos(newTime, length)
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
        engine // VLC's player is created here, on the window's thread, before anything plays (as before VlcPlayer)
        engBridge.target = object : EngineListener {
            override fun playing() = evPlaying()
            override fun paused() = evPaused()
            override fun stopped() = evStopped()
            override fun buffering(percent: Float) = evBuffering(percent)
            override fun buffering(active: Boolean) = evBuffering(if (active) 0f else 100f)
            override fun timeChanged(ms: Long) = evTime(ms, 0f)
            override fun timeChanged(ms: Long, position: Float) = evTime(ms, position)
            override fun lengthChanged(ms: Long) = evLength(ms)
            override fun tracksChanged() = evStream()
            override fun finished() = evFinished()
            override fun failed(message: String) { PlayerLog.write(if (eng != null) "[mpv] $message" else "[vlc] $message"); evError() }
        }
        onDispose {
            canvasFocusJob?.cancel()
            ensureAudioJob?.cancel()
            chaptersJob?.cancel()
            prefsJob?.cancel()
            Thread({ runCatching { BurstProxy.clear() } }, "reserve-clear").apply { isDaemon = true; start() }
            if (!closing) { savePos(time, length); library.bumpWatch() }
            if (mini) setMini(false)
            ui.put("volume", volume.toString())
            vlcExecutor.execute {
                runCatching { eStop() }
                runCatching { engine.release() }
            }
            vlcExecutor.shutdown()
            Thread({ runCatching { vlcExecutor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS) }; AppExit.untrack(vlcExecutor) }, "vlc-untrack")
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

    LaunchedEffect(Unit) {
        delay(440)
        showCanvas = true
        var waited = 0
        while ((!canvas.isDisplayable || canvas.width <= 0) && waited < 8000) { delay(50); waited += 50 }

        if (eng != null) {
            val hwnd = com.sun.jna.Native.getComponentID(canvas)
            eng.attach(hwnd)
        }

        // Запрещаем нативному окну красть фокус клавиатуры у главного окна!
        WindowMode.disableNativeCanvasInput(canvas)
        WindowMode.restoreComposeFocus(owner)

        val entries = playlistDeferred?.await().orEmpty()
        if (entries.size > 1) {
            queue = entries
        }
        if (startIndex > 0 && entries.size > startIndex) {
            playEntry(startIndex)
            return@LaunchedEffect
        }
        open(req.url, if (req.fromStart) 0L else savedPos(), req.audioUrls to req.subUrls)
    }

    LaunchedEffect(index, queue.size) { if (index + 1 < queue.size) { delay(15_000); sidecars(index + 1) } }

    fun restartVideo() {
        PlayerLog.write("restart video output at ${time / 1000}s")
        if (eng != null) eng.restartVideoOutput() // mpv seeks to the current place itself
        else vlc { engine.restartVideoOutput(); reseekHereNow() }
    }

    val restartOutput: () -> Unit = { restartVideo() }
    val reopenAt: (Long) -> Unit = { reopen(it) }
    val supervisor = remember {
        PlaybackSupervisor(object : PlaybackSupervisor.Actions {
            override fun restartVideo() = restartOutput()
            override fun fixSound() {
                val v = volume
                vlc { eMute(userMuted); eVolume(v) }
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

    // Сеть медленнее фильма: когда запас кончился, прокси не отдаёт VLC данные, пока не накопится ~30 с фильма (не дольше
    // CatchUp.MAX_MS). Для VLC это одна долгая буферизация вместо остановок каждые 15–30 с, после которых аппаратный
    // декодер показывает рассыпанную картинку (журнал 15.10: запас 0 МБ, сеть ≈ битрейт фильма). VLC на паузу не ставим.
    LaunchedEffect(Unit) {
        fun endCatchUp(restart: Boolean) {
            if (!catchingUp) return
            catchingUp = false
            // С запасом позади: прыжок попадает на ближайший ключевой кадр из того, что уже на диске. Так VLC запускает
            // декодеры заново, а не показывает картинку после долгого простоя.
            val restartHere = catchUp.end(currentUrl, restart && !closing && time > 0L)
            if (status == catchUpStatus) status = null
            if (restartHere) {
                lastSeekAt = System.currentTimeMillis()
                supRef.get()?.seeked()
                vlcSeekTo(time)
            }
        }
        while (true) {
            delay(1_000L)
            val now = System.currentTimeMillis()
            if (catchingUp) {
                val gathered = catchUp.progress(currentUrl)
                when {
                    closing || pausedAt > 0L || vlcPaused || seeking != null || lastSeekAt > catchUp.startedAt -> endCatchUp(false)
                    gathered < 0 -> endCatchUp(true)
                    bufferingSince > 0L -> { catchUpStatus = tr("player.set_medlennee_filma_kopim", "p" to gathered); status = catchUpStatus }
                }
                continue
            }
            if (closing || !started || pausedAt > 0L || vlcPaused || seeking != null || time <= 0L) continue
            // не сразу после открытия или перемотки: VLC тогда и так наполняет свой буфер
            if (now - openedAt < 20_000L || now - lastSeekAt < 20_000L) continue
            catchingUp = catchUp.maybeStart(currentUrl, canStart = true)
        }
    }

    LaunchedEffect(Unit) {
        val pending = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Deferred<*>?>()
        while (true) {
            delay(2000)
            if (!started || closing) continue
            val st = vlcPoll(pending, 1500) { engine.supervisorStats(userMuted || volume == 0) } ?: continue
            supervisor.tick(st)
        }
    }

    // After a switch of the sound or subtitles the film could stay at «Буферизация 0%» until the viewer seeked (16.10,
    // report, mpv). If the buffering does not rise for 3 s after a switch, the player makes that seek itself, once.
    LaunchedEffect(trackSwitches) {
        if (trackSwitches == 0) return@LaunchedEffect
        bufferingShown = 0
        PlayerLog.write("[track] switched at ${time / 1000}s" + (if (bufferingSince > 0L) ", buffering ${bufferingPct.toInt()}%" else ""))
        val until = System.currentTimeMillis() + 20_000L
        var shown = 0f
        while (System.currentTimeMillis() < until) {
            delay(3_000L)
            if (closing || pausedAt > 0L || vlcPaused || seeking != null || catchingUp) return@LaunchedEffect
            if (bufferingSince == 0L) { shown = 0f; continue }
            if (bufferingPct > shown) { shown = bufferingPct; continue }
            val t = time
            PlayerLog.write("[track] buffering stuck at ${bufferingPct.toInt()}% after the switch → seek to ${t / 1000}s")
            if (t <= 0L) return@LaunchedEffect
            lastSeekAt = System.currentTimeMillis()
            supRef.get()?.seeked()
            vlcSeekTo(t)
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
            val line = vlcPoll(pending, 800) { engine.statsLine() }
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

    LaunchedEffect(Unit) {
        var last: java.awt.Point? = null
        while (true) {
            val pos = runCatching { java.awt.MouseInfo.getPointerInfo()?.location }.getOrNull()
            if (pos != null && pos != last) {
                if (last != null) {
                    val w = owner ?: javax.swing.SwingUtilities.getWindowAncestor(canvas)
                    val b = w?.bounds
                    val inside = (w?.mousePosition != null) || (canvas.mousePosition != null) || (b != null && b.contains(pos))
                    if (inside) poke()
                }
                last = pos
            }
            delay(100)
        }
    }

    LaunchedEffect(barVisible, lastMove, started, vlcPaused, nextIn, picker, seeking, infoOpen) {
        val hold = !started || vlcPaused || nextIn >= 0 || picker != null || seeking != null || infoOpen || inIntro()
        if (hold) {
            barVisible = true
            return@LaunchedEffect
        }
        if (!barVisible) return@LaunchedEffect
        delay(2500)
        val stillHold = !started || vlcPaused || nextIn >= 0 || picker != null || seeking != null || infoOpen || inIntro()
        if (!stillHold) {
            barVisible = false
        }
    }

    val barSlide = remember { androidx.compose.animation.core.Animatable(0f) }
    val blankCursor = remember {
        java.awt.Toolkit.getDefaultToolkit().createCustomCursor(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB), java.awt.Point(0, 0), "blank")
    }
    LaunchedEffect(barVisible, mini) {
        val targetCursor = if (mini || barVisible) java.awt.Cursor.getDefaultCursor() else blankCursor
        canvas.cursor = targetCursor
        owner?.cursor = targetCursor
    }
    DisposableEffect(owner) {
        onDispose {
            owner?.cursor = java.awt.Cursor.getDefaultCursor()
        }
    }

    DisposableEffect(owner, canvas) {
        val ownerListener = object : java.awt.event.ComponentAdapter() {
            override fun componentMoved(e: java.awt.event.ComponentEvent) { syncOverlayBounds() }
            override fun componentResized(e: java.awt.event.ComponentEvent) { syncOverlayBounds() }
        }
        val canvasListener = object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) { syncOverlayBounds() }
        }
        val winListener = object : java.awt.event.WindowAdapter() {
            override fun windowIconified(e: java.awt.event.WindowEvent) {
                topDialog?.isVisible = false
                bottomDialog?.isVisible = false
            }
            override fun windowDeiconified(e: java.awt.event.WindowEvent) {
                if (barVisible) {
                    syncOverlayBounds()
                    topDialog?.isVisible = true
                    bottomDialog?.isVisible = true
                }
            }
        }
        owner?.addComponentListener(ownerListener)
        owner?.addWindowListener(winListener)
        canvas.addComponentListener(canvasListener)
        onDispose {
            owner?.removeComponentListener(ownerListener)
            owner?.removeWindowListener(winListener)
            canvas.removeComponentListener(canvasListener)
            topDialog?.isVisible = false
            bottomDialog?.isVisible = false
            topDialog?.dispose()
            bottomDialog?.dispose()
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
            bottomDialog?.setLocation(nx, ny)
        }
    }

    val onWindowDragEnd: () -> Unit = {
        syncOverlayBounds()
    }

    fun revealOverlays() {
        var changed = false
        if (topDialog?.isVisible == false && !mini) { topDialog.isVisible = true; changed = true }
        if (bottomDialog?.isVisible == false) { bottomDialog.isVisible = true; changed = true }
        if (changed) { lastClip = null; syncOverlayBounds(); WindowMode.restoreComposeFocus(owner) }
    }

    LaunchedEffect(barVisible, closing, mini) {
        if (closing) {
            topDialog?.isVisible = false
            bottomDialog?.isVisible = false
            return@LaunchedEffect
        }
        if (mini) {
            topDialog?.isVisible = false
            bottomDialog?.isVisible = !WindowMode.miniAnimating
            if (barVisible) {
                barSlide.animateTo(0f, tween(280, easing = Sm.ease))
            } else {
                barSlide.animateTo(1f, tween(260, easing = Sm.ease))
            }
            return@LaunchedEffect
        }
        if (barVisible) {
            syncOverlayBounds()
            revealOverlays()
            barSlide.animateTo(0f, tween(280, easing = Sm.ease))
        } else {
            barSlide.animateTo(1f, tween(260, easing = Sm.ease))
            topDialog?.isVisible = false
            bottomDialog?.isVisible = false
        }
    }

    // Debug build: every second for 20 s after F11 — dropped pictures, playback speed and UI-thread delay,
    // to tell a decoder that cannot keep up (AV1 on the CPU) from a window rebuild that stalls the picture.
    if (Diag.on) {
        val fsFirst = remember { booleanArrayOf(true) }
        LaunchedEffect(WindowMode.isFullscreen) {
            if (fsFirst[0]) { fsFirst[0] = false; return@LaunchedEffect }
            if (eng != null) return@LaunchedEffect
            val on = WindowMode.isFullscreen
            val pending = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Deferred<*>?>()
            var (prev, prevMedia) = vlcPoll(pending, 1000) { vlcStats(mp) to runCatching { mp.status().time() }.getOrDefault(0L) } ?: return@LaunchedEffect
            var prevAt = System.nanoTime()
            PlayerLog.write("[ui] fs ${if (on) "on" else "off"}: watching 20 s")
            for (sec in 1..20) {
                delay(1_000)
                if (closing) break
                val posted = System.nanoTime()
                var edtMs = -1L
                SwingUtilities.invokeLater { edtMs = (System.nanoTime() - posted) / 1_000_000 }
                val (st, media) = vlcPoll(pending, 1000) { vlcStats(mp) to runCatching { mp.status().time() }.getOrDefault(0L) } ?: continue
                val now = System.nanoTime()
                val wallMs = (now - prevAt) / 1_000_000
                fun d(k: String) = (st[k] ?: 0L) - (prev[k] ?: 0L)
                PlayerLog.write("[vlc] fs ${if (on) "on" else "off"} +${sec}s · pictures shown +${d("shown")} lost +${d("lost")} · decoded video +${d("video")}" +
                    " · film ${media - prevMedia} ms per ${wallMs} ms · ui thread delay ${edtMs} ms")
                prev = st; prevMedia = media; prevAt = now
            }
        }
    }

    LaunchedEffect(WindowMode.isFullscreen) {
        if (!closing) {
            // Прячем оверлеи на время перестройки нативного окна Windows
            topDialog?.isVisible = false
            bottomDialog?.isVisible = false

            delay(80) // Ждем, пока AWT и Win32 применят новые границы и Insets
            syncOverlayBounds()

            if (barVisible) {
                topDialog?.isVisible = true
                bottomDialog?.isVisible = true
            }
            // Возвращаем клавиатурный фокус слою Compose, чтобы работали горячие клавиши
            WindowMode.restoreComposeFocus(owner)

            delay(150)
            syncOverlayBounds()
        }
    }

    LaunchedEffect(picker, infoOpen) {
        if (picker != null || infoOpen) {
            tallBottom = true
        } else {
            delay(400) // пусть доиграет анимация закрытия меню
            tallBottom = false
        }
        syncOverlayBounds()
    }

    // Выход из полного экрана: Windows и Compose донастраивают размеры окна и холста с задержкой, и события
    // изменения размера могут прийти раньше, чем окно встанет на место, — нижняя панель оставалась за краем экрана.
    // Пока панели видны, раз в 300 мс сверяем их положение с холстом (setBounds вызывается только при расхождении).
    LaunchedEffect(barVisible, closing) {
        if (closing || !barVisible) return@LaunchedEffect
        while (true) {
            delay(300)
            syncOverlayBounds()
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
    fun RenderBottomBar() {
        PlayerBottomBar(
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
                vlcSeekTo(t)
            },
            onPlayEntry = { playEntry(it) },
            onSkipIntro = { skipIntro() },
            onClosePicker = { closePicker() },
            onOpenPicker = { openPicker(it) },
            onSelectTrack = { kind, id, name ->
                // Like a seek for the reserve: an active «копим запас» ends, and none starts while the switch refills the buffer.
                lastSeekAt = System.currentTimeMillis()
                bufferingShown = 0
                trackSwitches++
                if (kind == "audio") {
                    vlc { eSetAudio(id) }
                    applyTrackNow()
                    ui.put("audio:${seriesKey()}", name)
                    ui.put("audio:*", name)
                } else {
                    vlc { eSetSub(id) }
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
                vlc {
                    eVolume(it)
                    if (unmute) eMute(false)
                }
                poke()
            },
            onMuteToggle = {
                userMuted = !userMuted
                vlc { eMute(userMuted) }
                poke()
                flash(if (userMuted) tr("player.bez_zvuka") else tr("player.zvuk_volume", "volume" to volume))
            },
            onFullscreenToggle = { toggleFullscreen() },
            onMoreRate = { setSpeed(it) },
            onMoreSubScale = { v ->
                subScale = v
                ui.put("subScale", if (v == 100) null else v.toString())
                if (eng != null) {
                    eng.setSubScale(v)
                    poke()
                    flash("${tr("player.subtitry")}: $v%")
                } else {
                    restartPlayer()
                }
            },
            onMoreSubDelay = { shiftSubs(it) },
            onMoreNight = {
                nightMode = !nightMode
                ui.put("night", if (nightMode) "1" else null)
                poke()
                flash(if (nightMode) tr("player.nochnoy_zvuk_vklyuchen") else tr("player.nochnoy_zvuk_vyklyuchen"))
                if (eng != null) {
                    eng.setNightMode(nightMode)
                } else {
                    reopen(time)
                }
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
                vlc { eVolume(v) }
                if (userMuted) {
                    userMuted = false
                    vlc { eMute(false) }
                }
                lastVolumeWheelAt = System.currentTimeMillis()
                poke()
                flash(tr("player.gromkost_v", "v" to v))
            }
        )
    }

    // Video layer in main window
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                var lastClickTime = 0L
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Press -> {
                                poke()
                                if (picker != null) closePicker()
                                WindowMode.restoreComposeFocus(owner)
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
                            PointerEventType.Move -> {
                                poke()
                            }
                            PointerEventType.Scroll -> {
                                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                if (delta != 0f) {
                                    val step = if (delta < 0f) 5 else -5
                                    volume = (volume + step).coerceIn(0, 150)
                                    val v = volume
                                    vlc { eVolume(v) }
                                    if (userMuted) { userMuted = false; vlc { eMute(false) } }
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
        if (showCanvas) SwingPanel(factory = { canvas }, modifier = if (parked) Modifier.offset(x = (-8000).dp).size(320.dp, 180.dp) else Modifier.fillMaxSize(), background = Color.Black)
        if (!showCanvas || parked) lastFrame?.let { androidx.compose.foundation.Image(it, null, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            ?: Text(if (closing) "" else tr("player.otkryvaem"), color = Sm.muted, fontFamily = Onest, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center))
    }

    if (topDialog != null && bottomDialog != null) {
        DisposableEffect(topDialog, bottomDialog) {
            topDialog.setContent {
                SuslikTheme { RenderTopBar() }
            }
            bottomDialog.setContent {
                SuslikTheme { RenderBottomBar() }
            }
            // Любой клик/фокус на оверлее сразу возвращает фокус главному окну (клавиши и колесо работают как раньше)
            fun giveBack() {
                if (owner != null) javax.swing.SwingUtilities.invokeLater { WindowMode.restoreComposeFocus(owner) }
            }
            val focusBack = object : java.awt.event.WindowAdapter() {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent) = giveBack()
                override fun windowActivated(e: java.awt.event.WindowEvent) = giveBack()
            }
            topDialog.addWindowFocusListener(focusBack)
            bottomDialog.addWindowFocusListener(focusBack)
            topDialog.addWindowListener(focusBack)
            bottomDialog.addWindowListener(focusBack)
            val mouseBack = java.awt.event.AWTEventListener { ev ->
                if (ev is java.awt.event.MouseEvent && (ev.id == java.awt.event.MouseEvent.MOUSE_PRESSED || ev.id == java.awt.event.MouseEvent.MOUSE_RELEASED)) {
                    val w = javax.swing.SwingUtilities.getWindowAncestor(ev.component) ?: ev.component as? java.awt.Window
                    if (w === topDialog || w === bottomDialog) giveBack()
                }
            }
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(mouseBack, java.awt.AWTEvent.MOUSE_EVENT_MASK)
            onDispose {
                java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(mouseBack)
                topDialog.dispose()
                bottomDialog.dispose()
            }
        }
    }
}
