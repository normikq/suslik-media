package ru.suslikovanora.media.desktop.player

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import uk.co.caprica.vlcj.media.MediaSlavePriority
import uk.co.caprica.vlcj.media.MediaSlaveType
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import javax.swing.SwingUtilities

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

    var nightMode by remember { mutableStateOf(ui.get("night") == "1") }
    val subScale = remember { ui.get("subScale")?.toIntOrNull()?.coerceIn(50, 300) ?: 100 }
    val factory = remember { VlcFactory.get(subScale) }
    val canvas = remember { java.awt.Canvas().apply { background = java.awt.Color.BLACK; isFocusable = false } }
    val mp = remember {
        factory.mediaPlayers().newEmbeddedMediaPlayer().also {
            it.videoSurface().set(factory.videoSurfaces().newVideoSurface(canvas))
            it.input().enableMouseInputHandling(false)
            it.input().enableKeyInputHandling(false)
        }
    }

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
    var status by remember { mutableStateOf<String?>("Открываем…") }
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

    fun syncOverlayBounds() {
        if (parked || owner?.isShowing != true) return
        // Берем экранные координаты напрямую у canvas (если он показан), иначе у окна
        val target = if (canvas.isShowing && canvas.width > 0 && canvas.height > 0) {
            canvas
        } else {
            (owner as? javax.swing.RootPaneContainer)?.contentPane ?: owner ?: return
        }

        val loc = runCatching { target.locationOnScreen }.getOrNull() ?: return
        val w = target.width
        val h = target.height
        if (w <= 0 || h <= 0) return

        val topH = 75
        val bottomH = 460.coerceAtMost(h - topH)

        val topRect = java.awt.Rectangle(loc.x, loc.y, w, topH)
        val bottomRect = java.awt.Rectangle(loc.x, loc.y + h - bottomH, w, bottomH)
        topDialog?.takeIf { it.bounds != topRect }?.let { it.bounds = topRect; it.revalidate() }
        bottomDialog?.takeIf { it.bounds != bottomRect }?.let { it.bounds = bottomRect; it.revalidate() }
        // Окно всегда высотой под меню (без перестройки и подёргиваний), но клики ловит только его нужная часть
        bottomDialog?.let { d ->
            val visible = if (tallBottom) bottomH else 220.coerceAtMost(bottomH)
            val key = Triple(d.width, d.height, visible)
            if (d.isDisplayable && key != lastClip) { lastClip = key; WindowMode.clipToBottom(d, visible) }
        }
    }

    fun grabFrame(): androidx.compose.ui.graphics.ImageBitmap? {
        val img = runCatching {
            if (mp.status().isPlayable && mp.video().videoDimension() != null) {
                mp.snapshots().get(canvas.width.coerceIn(320, 1920), 0)
            } else null
        }.getOrNull() ?: return null
        val bmp = img.toComposeImageBitmap()
        SwingUtilities.invokeLater { lastFrame = bmp }
        return bmp
    }

    var openJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun open(url: String, startAt: Long, extras: Pair<List<String>, List<String>>) {
        status = "Открываем…"
        prefsApplied = false
        chapters = emptyList()
        lastGood = startAt
        time = startAt
        audioDelay = ui.get("adelay:${seriesKey()}")?.toLongOrNull() ?: 0L
        subDelay = ui.get("sdelay:${seriesKey()}")?.toLongOrNull() ?: 0L
        pausedAt = 0L

        val opts = ArrayList(listOf(":http-reconnect", ":network-caching=${PlaybackSettings.cacheMs()}"))
        if (nightMode) {
            opts += listOf(
                ":audio-filter=compressor",
                ":compressor-rms-peak=0.2",
                ":compressor-attack=25",
                ":compressor-release=100",
                ":compressor-threshold=-24",
                ":compressor-ratio=6",
                ":compressor-knee=4.5",
                ":compressor-makeup-gain=10"
            )
        }
        Reserve.init()
        BurstProxy.capBytes = PlaybackSettings.burstMb().toLong() shl 20
        BurstProxy.keepOnly(listOf(url) + extras.first)
        supRef.get()?.opening(url)
        PlayerLog.write("open ${url.substringBefore('?').substringAfterLast('/')} at ${startAt / 1000}s, voice-overs=${extras.first.size}, " +
            "buffer=${PlaybackSettings.cacheMs()}ms, reserve=${PlaybackSettings.burstMb()}MB")
        val playUrl = BurstProxy.wrap(url)
        val isTs = TsClock.isTs(url)
        if (startAt > 0 && !isTs) opts += ":start-time=${startAt / 1000.0}"
        tsStartAt = startAt; tsOpenDone = !isTs; if (!isTs) { ts = null; tsAbsolute = false }
        prefLanguage("audio")?.let { opts += ":audio-language=$it" }
        prefLanguage("spu")?.let { opts += ":sub-language=$it" }
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
            if (mp.status().isPlayable) {
                grabFrame()
                runCatching { mp.controls().stop() }
            }
            mp.media().prepare(playUrl, *opts.toTypedArray())
            extras.first.forEach { u -> runCatching { mp.media().slaves().add(MediaSlaveType.AUDIO, MediaSlavePriority.HIGHEST, BurstProxy.wrap(u)) } }
            extras.second.forEach { u -> runCatching { mp.media().slaves().add(MediaSlaveType.SUBTITLE, MediaSlavePriority.HIGHEST, u) } }
            mp.controls().play()
        }
    }

    fun reopen(at: Long) { val i = index; scope.launch { open(currentUrl, at, sidecars(i).await()) } }

    fun vlcSeekTo(t: Long) {
        lastGood = t
        val s = ts
        if (s == null) { vlc { mp.controls().setTime(t) }; return }
        if (s.offset > 0 && t < s.offset + 2_000L) {
            PlayerLog.write("[ts] seek to ${t / 1000}s is before VLC's clock (starts at ${s.offset / 1000}s): reopening with the real clock")
            tsAbsolute = true; reopen(t); return
        }
        vlc {
            val now = s.filmTime(mp.status().time()); val nowPos = mp.status().position()
            val pos = s.seek(t, now, nowPos)
            PlayerLog.write("[ts] seek %ds → position %.4f (now %ds at %.4f)".format(t / 1000, pos, now / 1000, nowPos))
            mp.controls().setPosition(pos)
        }
    }

    fun reseekHereNow() {
        if (ts != null) mp.controls().setPosition(mp.status().position())
        else { val t = mp.status().time(); if (t > 0) mp.controls().setTime(t) }
    }

    fun applyTrackNow() {
        supRef.get()?.seeked()
        scope.launch(vlcThread) {
            delay(120)
            runCatching { reseekHereNow() }
        }
    }

    fun applyPrefs() {
        val audio = ui.get("audio:${seriesKey()}") ?: ui.get("audio:*")
        val spu = ui.get("spu:${seriesKey()}") ?: ui.get("spu:*")
        vlc {
            if (audio != null) mp.audio().trackDescriptions().firstOrNull { it.description() == audio }?.let { if (mp.audio().track() != it.id()) { mp.audio().setTrack(it.id()); applyTrackNow() } }
            if (spu == "__off__") { if (mp.subpictures().track() != -1) mp.subpictures().setTrack(-1) }
            else if (spu != null) mp.subpictures().trackDescriptions().firstOrNull { it.description() == spu }?.let { if (mp.subpictures().track() != it.id()) mp.subpictures().setTrack(it.id()) }
        }
    }

    fun playEntry(i: Int) {
        if (i !in queue.indices) return
        savePos(time, length)
        index = i; nextIn = -1; currentUrl = queue[i].url; title = queue[i].title; retries = 0
        val url = currentUrl; val start = savedPos(i)
        playing = false; started = false; time = start; lastGood = start; length = 0L; status = "Следующая серия…"
        scope.launch {
            val extras = withTimeoutOrNull(4000) { sidecars(i).await() } ?: (emptyList<String>() to emptyList())
            if (index == i && !closing) open(url, start, extras)
        }
    }

    fun trackList(audio: Boolean): List<Pair<Int, String>> {
        val map = LinkedHashMap<Int, String>()
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
                    ).joinToString(" ").ifBlank { "Дорожка ${t.id()}" }
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
        scope.launch {
            val fresh = vlcAwait(700) {
                val audio = kind == "audio"
                val list = trackList(audio)
                (if (audio) list else listOf(-1 to "Выключить") + list) to
                    runCatching { if (audio) mp.audio().track() else mp.subpictures().track() }.getOrDefault(-2)
            }
            // VLC занят (сеть встала) — открываем меню с прошлым списком, а не оставляем кнопку мёртвой
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
            vlc { mp.controls().setPause(true) }
        } else {
            val paused = if (pausedAt > 0) System.currentTimeMillis() - pausedAt else 0L
            val longPause = paused > 8_000L && !BurstProxy.isActive(currentUrl)
            PlayerLog.write("resume after ${paused} ms${if (longPause) " (re-seek)" else ""}")
            supRef.get()?.resumed()
            playing = true; pausedAt = 0L; vlcPaused = false
            vlc {
                mp.controls().setPause(false)
                if (longPause) reseekHereNow()
            }
        }
    }

    fun flash(text: String, prefix: String) {
        status = text
        javax.swing.Timer(2000) { if (status?.startsWith(prefix) == true) status = null }.apply { isRepeats = false; start() }
    }

    fun ensureAudio() {
        vlc {
            if (mp.audio().track() != -1) return@vlc
            val first = mp.audio().trackDescriptions().firstOrNull { it.id() != -1 } ?: return@vlc
            mp.audio().setTrack(first.id()); applyTrackNow()
            SwingUtilities.invokeLater { flash("Звук: ${first.description()}", "Звук:") }
        }
    }

    fun shiftSubs(ms: Long) {
        subDelay = (subDelay + ms).coerceIn(-10_000L, 10_000L)
        val d = subDelay
        ui.put("sdelay:${seriesKey()}", if (d == 0L) null else d.toString())
        vlcExecutor.execute { runCatching { mp.subpictures().setDelay(d * 1000L) } }
        flash("Субтитры: ${if (d > 0) "+" else ""}$d мс", "Субтитры:")
    }

    val rates = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    fun setSpeed(r: Float) {
        rate = r; ui.put("rate", if (r == 1f) null else r.toString())
        vlcExecutor.execute { runCatching { mp.controls().setRate(r) } }
        flash("Скорость ${fmtRate(r)}", "Скорость")
    }
    fun stepSpeed(d: Int) { val i = rates.indexOfFirst { it >= rate - 0.01f }.coerceAtLeast(0); setSpeed(rates[(i + d).coerceIn(0, rates.size - 1)]) }

    fun chapterAt(t: Long): Int = chapters.indexOfLast { it.first <= t + 500 }
    fun jumpChapter(d: Int) {
        if (chapters.size < 2) return
        val i = (chapterAt(time) + d).coerceIn(0, chapters.size - 1)
        val t = chapters[i].first; time = t; lastSeekAt = System.currentTimeMillis(); supRef.get()?.seeked()
        vlcSeekTo(t)
        flash("Глава ${i + 1}: ${chapters[i].second.ifBlank { "без названия" }}", "Глава")
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
            vlcAwait(800) { grabFrame(); runCatching { mp.controls().stop() } }
            showCanvas = false
            onRestart(index)
        }
    }

    fun setMini(on: Boolean) {
        if (on == mini) return
        if (on) {
            if (WindowMode.isFullscreen) WindowMode.setFullscreen(owner, false)
            beforeMini[0] = win.size; beforeMini[1] = win.position; beforeMini[2] = win.placement; beforeMini[3] = onTop.value
            javax.swing.SwingUtilities.getWindowAncestor(canvas)?.minimumSize = java.awt.Dimension(400, 280)
            val b = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            win.placement = WindowPlacement.Floating
            win.size = androidx.compose.ui.unit.DpSize(560.dp, 390.dp)
            win.position = androidx.compose.ui.window.WindowPosition((b.x + b.width - 580).dp, (b.y + b.height - 410).dp)
            onTop.value = true
        } else {
            (beforeMini[2] as? WindowPlacement)?.let { win.placement = it }
            (beforeMini[0] as? androidx.compose.ui.unit.DpSize)?.let { win.size = it }
            (beforeMini[1] as? androidx.compose.ui.window.WindowPosition)?.let { win.position = it }
            onTop.value = beforeMini[3] as? Boolean ?: false
            javax.swing.SwingUtilities.getWindowAncestor(canvas)?.minimumSize = java.awt.Dimension(MIN_W, MIN_H)
        }
        mini = on
    }

    fun shiftAudio(ms: Long) {
        audioDelay = (audioDelay + ms).coerceIn(-5_000L, 5_000L)
        val d = audioDelay
        ui.put("adelay:${seriesKey()}", if (d == 0L) null else d.toString())
        vlcExecutor.execute { runCatching { mp.audio().setDelay(d * 1000L) } }
        status = "Задержка звука: ${if (d > 0) "+" else ""}$d мс"
        javax.swing.Timer(2000) { if (status?.startsWith("Задержка звука") == true) status = null }.apply { isRepeats = false; start() }
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
            vlcExecutor.execute { runCatching { mp.controls().stop() } }
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
                    vlc { mp.audio().setVolume(v) }
                    if (userMuted) {
                        userMuted = false
                        vlc { mp.audio().setMute(false) }
                    }
                    lastVolumeWheelAt = System.currentTimeMillis()
                    flash("Громкость: $v%", "Громкость:")
                }
                Key.DirectionDown -> {
                    volume = (volume - 5).coerceAtLeast(0)
                    val v = volume
                    vlc { mp.audio().setVolume(v) }
                    if (userMuted) {
                        userMuted = false
                        vlc { mp.audio().setMute(false) }
                    }
                    lastVolumeWheelAt = System.currentTimeMillis()
                    flash("Громкость: $v%", "Громкость:")
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
                Key.M -> { userMuted = !userMuted; val m = userMuted; vlc { mp.audio().setMute(m) } }
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
                Key.F8 -> if (Diag.on) flash("📍 Метка #${Diag.mark("player at ${time / 1000}s")}", "mark") else return@handler false
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
            while (true) {
                delay(5_000)
                if (closing) continue
                val line = withContext(vlcThread) { runCatching {
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

    val prefsTimer = remember { javax.swing.Timer(300) { applyPrefs(); ensureAudio() }.apply { isRepeats = false } }
    DisposableEffect(Unit) {
        val ui2 = { block: () -> Unit -> SwingUtilities.invokeLater(block) }
        val oneShots = mutableListOf<javax.swing.Timer>()
        fun oneShot(ms: Int, action: () -> Unit) {
            oneShots.removeAll { !it.isRunning }
            oneShots += javax.swing.Timer(ms) { action() }.apply { isRepeats = false; start() }
        }
        mp.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(p: MediaPlayer) = ui2 {
                WindowMode.disableNativeCanvasInput(canvas)
                WindowMode.restoreComposeFocus(owner)
                oneShot(250) {
                    WindowMode.disableNativeCanvasInput(canvas)
                    WindowMode.restoreComposeFocus(owner)
                }
                val wasStarted = started
                playing = true; status = null; retries = 0; started = true; vlcPaused = false
                ts?.let { s ->
                    BurstProxy.setDuration(currentUrl, s.lengthMs)
                    if (!tsOpenDone && !tsAbsolute) { tsOpenDone = true; s.openJump(tsStartAt)?.let { pos -> PlayerLog.write("[ts] continue at ${tsStartAt / 1000}s → position %.4f".format(pos)); vlc { mp.controls().setPosition(pos) } } }
                }
                if (pausedAt == 0L) supRef.get()?.playing()
                if (!prefsApplied) {
                    prefsApplied = true
                    prefsUntil = System.currentTimeMillis() + 8_000L
                    prefsTimer.initialDelay = 300; prefsTimer.restart()
                    oneShot(4500) { ensureAudio() }
                    if (audioDelay != 0L) { val d = audioDelay; vlcExecutor.execute { runCatching { mp.audio().setDelay(d * 1000L) } } }
                    if (subDelay != 0L) { val d = subDelay; vlcExecutor.execute { runCatching { mp.subpictures().setDelay(d * 1000L) } } }
                    if (rate != 1f) { val r = rate; vlcExecutor.execute { runCatching { mp.controls().setRate(r) } } }
                    scope.launch { delay(1200); chapters = withContext(vlcThread) { readChapters(mp) } }
                }
                val v = volume; val m = userMuted
                if (pausedAt > 0) { playing = false; vlc { mp.audio().setMute(true); mp.controls().setPause(true) } }
                else if (!wasStarted) vlc { mp.audio().setVolume(v); mp.audio().setMute(m) }
            }
            override fun elementaryStreamAdded(p: MediaPlayer, type: uk.co.caprica.vlcj.media.TrackType, id: Int) = ui2 {
                if (System.currentTimeMillis() < prefsUntil) { prefsTimer.initialDelay = 400; prefsTimer.restart() }
            }
            override fun paused(p: MediaPlayer) = ui2 { playing = false; vlcPaused = true; savePos(time, length) }
            override fun stopped(p: MediaPlayer) = ui2 { vlcPaused = false }
            override fun buffering(p: MediaPlayer, newCache: Float) = ui2 {
                status = if (newCache < 100f) "Буферизация ${newCache.toInt()}%" else null
                supRef.get()?.buffering(newCache)
                if (newCache < 100f) { if (bufferingSince == 0L) bufferingSince = System.currentTimeMillis() }
                else if (bufferingSince > 0) {
                    val ms = System.currentTimeMillis() - bufferingSince; bufferingSince = 0L
                    if (ms > 1500) PlayerLog.write("buffering took $ms ms at ${time / 1000}s")
                }
            }
            override fun lengthChanged(p: MediaPlayer, newLength: Long) = ui2 {
                if (newLength > 0 || ts == null) { length = newLength; BurstProxy.setDuration(currentUrl, newLength) }
            }
            override fun timeChanged(p: MediaPlayer, vlcTime: Long) {
                val pos = runCatching { p.status().position() }.getOrDefault(0f)
                ui2 tick@{
                    ts?.let { s0 -> if (tsAbsolute && !tsOpenDone && vlcTime > 0) { tsAbsolute = false; tsOpenDone = true; s0.openJump(tsStartAt)?.let { jumpPos -> vlc { mp.controls().setPosition(jumpPos) } } } }
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
            override fun finished(p: MediaPlayer) = ui2 {
                if (!closing) {
                    supRef.get()?.ended()
                    playing = false
                    val end = length > 0 && lastGood >= length - 5_000L
                    if (end) {
                        savePos(length, length)
                        if (index < queue.size - 1) nextIn = 10 else status = "Конец"
                    } else if (retries < 6) { retries++; status = "Переподключение…"; reopen(lastGood) }
                }
            }
            override fun error(p: MediaPlayer) = ui2 {
                if (!closing) {
                    if (retries < 6) { retries++; status = "Переподключение…"; reopen(lastGood) }
                    else status = "Не удалось воспроизвести поток"
                }
            }
        })
        onDispose {
            prefsTimer.stop()
            oneShots.forEach { it.stop() }
            Thread({ runCatching { BurstProxy.clear() } }, "reserve-clear").apply { isDaemon = true; start() }
            if (!closing) { savePos(time, length); library.bumpWatch() }
            if (mini) setMini(false)
            ui.put("volume", volume.toString())
            vlcExecutor.execute {
                runCatching { mp.controls().stop() }
                runCatching { mp.release() }
            }
            vlcExecutor.shutdown()
            Thread({ runCatching { vlcExecutor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS) }; AppExit.untrack(vlcExecutor) }, "vlc-untrack")
                .apply { isDaemon = true; start() }
        }
    }

    LaunchedEffect(Unit) {
        delay(440)
        showCanvas = true
        var waited = 0
        while ((!canvas.isDisplayable || canvas.width <= 0) && waited < 8000) { delay(50); waited += 50 }

        // Запрещаем нативному окну VLC красть фокус клавиатуры у главного окна!
        WindowMode.disableNativeCanvasInput(canvas)
        WindowMode.restoreComposeFocus(owner)
        if (startIndex > 0 && req.playlist != null) {
            val entries = Api.text(req.playlist)?.let { parseM3u(it) }.orEmpty()
            if (entries.size > startIndex) { queue = entries; playEntry(startIndex); return@LaunchedEffect }
        }
        open(req.url, if (req.fromStart) 0L else savedPos(), req.audioUrls to req.subUrls)
    }

    LaunchedEffect(index, queue.size) { if (index + 1 < queue.size) { delay(15_000); sidecars(index + 1) } }

    fun restartVideo() {
        PlayerLog.write("restart video output at ${time / 1000}s")
        vlc {
            val id = mp.video().track()
            if (id >= 0) { mp.video().setTrack(-1); mp.video().setTrack(id) }
            reseekHereNow()
        }
    }

    val restartOutput: () -> Unit = { restartVideo() }
    val reopenAt: (Long) -> Unit = { reopen(it) }
    val supervisor = remember {
        PlaybackSupervisor(object : PlaybackSupervisor.Actions {
            override fun restartVideo() = restartOutput()
            override fun fixSound() {
                val v = volume
                vlc { mp.audio().setMute(userMuted); mp.audio().setVolume(v) }
                ensureAudio()
            }
            override fun reopen(fresh: Boolean) {
                PlayerLog.write("supervisor: reopen at ${time / 1000}s${if (fresh) " with a fresh reserve" else ""}")
                CrashReports.recovery("reopen at ${time / 1000}s (fresh=$fresh)")
                if (fresh) BurstProxy.dropAll()
                reopenAt(time)
            }
            override fun notice(text: String) = flash(text, text.take(10))
            override fun log(message: String) = PlayerLog.write(message)
        }, stuckLimitMs = { if (BurstProxy.isActive(currentUrl)) 30_000L else 12_000L })
    }
    DisposableEffect(supervisor) { supRef.set(supervisor); onDispose { supRef.set(null) } }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            if (!started || closing) continue
            val st = withContext(vlcThread) {
                val stats = soundStats(mp)
                val fps = (call(mp.video(), "fps") as? Number)?.toDouble() ?: 0.0
                val audioTrack = runCatching { mp.audio().track() }.getOrDefault(-1)
                val audioTracks = runCatching { mp.audio().trackDescriptions().count { it.id() != -1 } }.getOrDefault(0)
                val videoTracks = runCatching { mp.video().trackCount() }.getOrDefault(0)
                val clock = runCatching { mp.status().time() }.getOrDefault(0L)
                stats?.let {
                    PlaybackSupervisor.Stats(clock, it.second, it.first, it.third, fps, audioTracks, audioTrack != -1, videoTracks, userMuted || volume == 0)
                }
            } ?: continue
            supervisor.tick(st)
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

    LaunchedEffect(req.playlist) {
        val text = req.playlist?.let { Api.text(it) } ?: return@LaunchedEffect
        val entries = parseM3u(text)
        if (entries.size > 1) {
            queue = entries
            if (startIndex in entries.indices && startIndex > 0 && title == req.title) {
                title = entries[startIndex].title
                currentUrl = entries[startIndex].url
            }
        }
    }

    LaunchedEffect(infoOpen) {
        while (infoOpen) {
            infoText = withContext(vlcThread) { describePlayback(mp) } +
                "  ·  скорость ${fmtRate(rate)}  ·  звук ${if (audioDelay > 0) "+" else ""}$audioDelay мс  ·  субтитры ${if (subDelay > 0) "+" else ""}$subDelay мс" +
                (if (nightMode) "  ·  ночной режим" else "") + "  ·  буфер ${PlaybackSettings.cacheMs() / 1000.0} с" +
                (BurstProxy.aheadMb(currentUrl)?.let { "  ·  запас на диске: %.0f МБ вперёд (из ${PlaybackSettings.burstMb()})".format(it) } ?: "  ·  без запаса (напрямую)") +
                "  ·  журнал: ${PlayerLog.file.absolutePath}"
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
                    val w = javax.swing.SwingUtilities.getWindowAncestor(canvas)
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
    LaunchedEffect(barVisible) {
        val targetCursor = if (barVisible) java.awt.Cursor.getDefaultCursor() else blankCursor
        canvas.cursor = targetCursor
        owner?.cursor = targetCursor
    }
    DisposableEffect(owner) {
        onDispose {
            owner?.cursor = java.awt.Cursor.getDefaultCursor()
        }
    }

    DisposableEffect(owner, canvas) {
        val root = (owner as? javax.swing.RootPaneContainer)?.contentPane
        val compListener = object : java.awt.event.ComponentAdapter() {
            override fun componentMoved(e: java.awt.event.ComponentEvent) { syncOverlayBounds() }
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
        owner?.addComponentListener(compListener)
        root?.addComponentListener(compListener)
        owner?.addWindowListener(winListener)
        canvas.addComponentListener(compListener)
        onDispose {
            owner?.removeComponentListener(compListener)
            root?.removeComponentListener(compListener)
            owner?.removeWindowListener(winListener)
            canvas.removeComponentListener(compListener)
            topDialog?.isVisible = false
            bottomDialog?.isVisible = false
            topDialog?.dispose()
            bottomDialog?.dispose()
        }
    }

    fun revealOverlays() {
        var changed = false
        if (topDialog?.isVisible == false) { topDialog.isVisible = true; changed = true }
        if (bottomDialog?.isVisible == false) { bottomDialog.isVisible = true; changed = true }
        if (changed) { lastClip = null; syncOverlayBounds(); WindowMode.restoreComposeFocus(owner) }
    }

    LaunchedEffect(barVisible, closing) {
        if (closing) {
            topDialog?.isVisible = false
            bottomDialog?.isVisible = false
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

    val singleClickTimer = remember { javax.swing.Timer(260) { togglePause() }.apply { isRepeats = false } }
    DisposableEffect(Unit) { onDispose { singleClickTimer.stop() } }

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
                                WindowMode.restoreComposeFocus(owner)
                                val now = System.currentTimeMillis()
                                if (now - lastClickTime < 300L) {
                                    singleClickTimer.stop()
                                    toggleFullscreen()
                                    lastClickTime = 0L
                                } else {
                                    singleClickTimer.restart()
                                    lastClickTime = now
                                }
                            }
                            PointerEventType.Scroll -> {
                                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                if (delta != 0f) {
                                    val step = if (delta < 0f) 5 else -5
                                    volume = (volume + step).coerceIn(0, 150)
                                    val v = volume
                                    vlc { mp.audio().setVolume(v) }
                                    if (userMuted) { userMuted = false; vlc { mp.audio().setMute(false) } }
                                    lastVolumeWheelAt = System.currentTimeMillis()
                                    poke()
                                    flash("Громкость: $v%", "Громкость:")
                                }
                            }
                        }
                    }
                }
            }
    ) {
        if (showCanvas) SwingPanel(factory = { canvas }, modifier = if (parked) Modifier.offset(x = (-8000).dp).size(320.dp, 180.dp) else Modifier.fillMaxSize(), background = Color.Black)
        if (!showCanvas || parked) lastFrame?.let { androidx.compose.foundation.Image(it, null, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            ?: Text(if (closing) "" else "Открываем…", color = Sm.muted, fontFamily = Onest, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center))
    }

    DisposableEffect(topDialog, bottomDialog) {
        topDialog?.setContent {
            SuslikTheme {
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
        }

        bottomDialog?.setContent {
            SuslikTheme {
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
                        if (kind == "audio") {
                            vlc { mp.audio().setTrack(id) }
                            applyTrackNow()
                            ui.put("audio:${seriesKey()}", name)
                            ui.put("audio:*", name)
                        } else {
                            vlc { mp.subpictures().setTrack(id) }
                            val v = if (id == -1) "__off__" else name
                            ui.put("spu:${seriesKey()}", v)
                            ui.put("spu:*", v)
                        }
                        closePicker()
                    },
                    onVolumeChange = {
                        volume = it
                        vlc { mp.audio().setVolume(it) }
                        poke()
                    },
                    onMuteToggle = {
                        userMuted = !userMuted
                        vlc { mp.audio().setMute(userMuted) }
                        poke()
                        flash(if (userMuted) "Без звука" else "Звук: $volume%", if (userMuted) "Без звука" else "Звук:")
                    },
                    onFullscreenToggle = { toggleFullscreen() },
                    onMoreRate = { setSpeed(it) },
                    onMoreSubScale = { v ->
                        ui.put("subScale", if (v == 100) null else v.toString())
                        restartPlayer()
                    },
                    onMoreSubDelay = { shiftSubs(it) },
                    onMoreNight = {
                        nightMode = !nightMode
                        ui.put("night", if (nightMode) "1" else null)
                        poke()
                        flash(if (nightMode) "Ночной звук включен" else "Ночной звук выключен", "Ночной звук")
                        reopen(time)
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
                    onVolumeWheel = { delta ->
                        val step = if (delta < 0) 5 else -5
                        volume = (volume + step).coerceIn(0, 150)
                        val v = volume
                        vlc { mp.audio().setVolume(v) }
                        if (userMuted) {
                            userMuted = false
                            vlc { mp.audio().setMute(false) }
                        }
                        lastVolumeWheelAt = System.currentTimeMillis()
                        poke()
                        flash("Громкость: $v%", "Громкость:")
                    }
                )
            }
        }
        onDispose { }
    }
}
