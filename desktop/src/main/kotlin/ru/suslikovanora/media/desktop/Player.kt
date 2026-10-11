package ru.suslikovanora.media.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.offset
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.media.MediaSlavePriority
import uk.co.caprica.vlcj.media.MediaSlaveType
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import javax.swing.SwingUtilities

/** One stream to play plus everything the library knows about it. */
data class PlayRequest(
    val url: String,
    val title: String,
    val playlist: String?,        // signed .m3u of this episode and the following ones (mirror only)
    val subUrls: List<String>,    // subtitle files that lay next to the video in the torrent
    val watchKeys: List<String>,  // "magnetId|path" for this and the following episodes (progress in the library)
    val audioUrls: List<String> = emptyList(), // external voice-overs (.mka/.ac3…) next to the video
    /** Playlist episode index -> (voice-overs, subtitles) of that episode. */
    val sidecars: (suspend (Int) -> Pair<List<String>, List<String>>)? = null
)

object Vlc {
    /** VLC 3.x (64-bit) must be installed; NativeDiscovery finds it in Program Files. */
    val available: Boolean by lazy { runCatching { NativeDiscovery().discover() }.getOrDefault(false) }
}

private data class Entry(val title: String, val url: String)

private fun parseM3u(text: String): List<Entry> {
    val out = ArrayList<Entry>(); var title: String? = null
    for (raw in text.removePrefix("﻿").lineSequence()) {
        val line = raw.trim()
        when {
            line.startsWith("#EXTINF") -> title = line.substringAfter(',', "").trim()
            line.startsWith("http") -> { out += Entry(title?.ifBlank { null } ?: line.substringAfterLast('/').substringBefore('?'), line); title = null }
        }
    }
    return out
}

private fun fmt(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0); val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun fmtRate(r: Float): String = (if (r % 1f == 0f) r.toInt().toString() else r.toString().trimEnd('0')) + "×"

/** Reflection keeps this independent of the exact vlcj method signatures. */
private fun call(o: Any?, name: String): Any? =
    o?.let { runCatching { it.javaClass.getMethod(name).invoke(it) }.getOrNull() }

/** Chapters of the current title: (start ms, name). Empty if the file has none. */
private fun readChapters(mp: MediaPlayer): List<Pair<Long, String>> {
    val list = call(call(mp, "chapters"), "descriptions") as? List<*> ?: return emptyList()
    return list.mapNotNull { d ->
        val off = (call(d, "offset") as? Number)?.toLong() ?: return@mapNotNull null
        off to ((call(d, "name") as? String).orEmpty())
    }.sortedBy { it.first }
}

/** One line for the info panel: picture, codecs, bitrate, dropped frames. Runs on the player thread. */
private fun describePlayback(mp: MediaPlayer): String {
    val parts = ArrayList<String>()
    runCatching { mp.video().videoDimension()?.let { parts += "${it.width}×${it.height}" } }
    val info = runCatching { mp.media().info() }.getOrNull()
    (call(info, "videoTracks") as? List<*>)?.firstOrNull()?.let { t -> (call(t, "codecDescription") as? String)?.takeIf { it.isNotBlank() }?.let { parts += it } }
    val audioId = runCatching { mp.audio().track() }.getOrDefault(-1)
    runCatching { mp.audio().trackDescriptions().firstOrNull { it.id() == audioId }?.description() }.getOrNull()?.let { parts += "звук: $it" }
    runCatching {
        val cls = Class.forName("uk.co.caprica.vlcj.media.MediaStatistics")
        val st = cls.getDeclaredConstructor().newInstance()
        info!!.javaClass.getMethod("statistics", cls).invoke(info, st)
        fun n(vararg names: String): Number? = names.firstNotNullOfOrNull { call(st, it) as? Number }
        n("inputBitrate")?.toDouble()?.let { if (it > 0) parts += "поток %.1f Мбит/с".format(it * 8000 / 1000) }
        n("demuxBitrate")?.toDouble()?.let { if (it > 0) parts += "видео %.1f Мбит/с".format(it * 8000 / 1000) }
        val shown = n("picturesDisplayed", "displayedPictures")?.toLong() ?: 0L
        val lost = n("picturesLost", "lostPictures")?.toLong() ?: 0L
        if (shown + lost > 0) parts += "кадров показано $shown, потеряно $lost"
        val aLost = n("audioBuffersLost", "lostAudioBuffers")?.toLong() ?: 0L
        if (aLost > 0) parts += "потеряно звука: $aLost"
    }
    return parts.joinToString("  ·  ").ifBlank { "Нет данных" }
}

/**
 * Seek bar: amber progress, chapter boundaries as gaps, bigger on hover; click to jump, drag to scrub.
 * [onScrub] reports the scrub position while dragging (null when done), [onHover] the time under the cursor.
 */
@Composable
private fun SeekBar(position: Long, length: Long, marks: List<Long>, modifier: Modifier,
                    onScrub: (Long?) -> Unit, onSeek: (Long) -> Unit, onHover: (Long?) -> Unit) {
    var w by remember { mutableStateOf(1f) }
    var hover by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(false) }
    var scrubTo by remember { mutableStateOf(0L) }
    val grow by androidx.compose.animation.core.animateFloatAsState(if (hover || drag) 1f else 0f, tween(180))
    fun at(x: Float) = if (length <= 0) 0L else ((x / w).coerceIn(0f, 1f) * length).toLong()
    Box(
        modifier.height(26.dp)
            .onSizeChanged { w = it.width.toFloat().coerceAtLeast(1f) }
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(length) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        when (e.type) {
                            PointerEventType.Exit -> { hover = false; onHover(null) }
                            else -> { hover = true; e.changes.firstOrNull()?.let { onHover(at(it.position.x)) } }
                        }
                    }
                }
            }
            .pointerInput(length) { detectTapGestures { o -> if (length > 0) onSeek(at(o.x)) } }
            .pointerInput(length) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> if (length > 0) { drag = true; scrubTo = at(o.x); onScrub(scrubTo) } },
                    onDragEnd = { if (drag) { drag = false; onSeek(scrubTo) } },
                    onDragCancel = { drag = false; onScrub(null) }
                ) { change, _ -> if (length > 0) { change.consume(); scrubTo = at(change.position.x); onScrub(scrubTo) } }
            }
            .drawBehind {
                val h = (4f + 3f * grow).dp.toPx()
                val cy = size.height / 2
                val r = androidx.compose.ui.geometry.CornerRadius(h / 2)
                drawRoundRect(Sm.line2, topLeft = Offset(0f, cy - h / 2), size = Size(size.width, h), cornerRadius = r)
                val f = if (length > 0) (position.toFloat() / length).coerceIn(0f, 1f) else 0f
                if (f > 0f) drawRoundRect(Sm.lamp, topLeft = Offset(0f, cy - h / 2), size = Size(size.width * f, h), cornerRadius = r)
                // chapter boundaries: small dark gaps across the track
                if (length > 0) for (m in marks) {
                    if (m <= 0 || m >= length) continue
                    val x = size.width * (m.toFloat() / length)
                    drawRect(Sm.booth, topLeft = Offset(x - 1.5f, cy - h / 2 - 1), size = Size(3f, h + 2))
                }
                drawCircle(Sm.lamp, radius = (5f + 3f * grow).dp.toPx(), center = Offset(size.width * f, cy))
            }
    )
}

/**
 * Player inside the main window. VLC draws straight into a native canvas (original resolution, hardware decoding,
 * DirectX output, no frame copying); the controls are a Compose bar under the picture.
 *
 * [keys] is the main window's key hook: the player installs its shortcuts there while it is on screen.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PlayerView(
    req: PlayRequest, library: Library, win: WindowState, keys: MutableState<((KeyEvent) -> Boolean)?>,
    onTop: MutableState<Boolean>,           // main window "always on top" (mini-player / pin)
    startIndex: Int = 0,                    // playlist episode to start with (after a settings restart)
    onRestart: (Int) -> Unit = {},          // re-create the player at this episode (night mode / subtitle size)
    onClose: () -> Unit
) {
    val ui = remember { Store("player_ui") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val vlcExecutor = remember { java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "vlc-control").apply { isDaemon = true } } }
    val vlcThread = remember { vlcExecutor.asCoroutineDispatcher() }
    // Settings VLC only reads when it starts (applied by re-creating the player, see restartPlayer()).
    val nightMode = remember { ui.get("night") == "1" }
    val subScale = remember { ui.get("subScale")?.toIntOrNull()?.coerceIn(50, 300) ?: 100 }
    val factory = remember {
        val base = listOf(
            "--avcodec-hw=any", "--no-video-title-show",
            // WASAPI first: it pauses and resumes instantly. When it failed, VLC silently fell back to an output with a
            // long buffer — sound kept going after pause and came back late (or not at all) after resume.
            "--aout=mmdevice,directsound",
            // Decode AC3/DTS to PCM instead of trying passthrough (the failed passthrough attempt is what broke WASAPI).
            "--mmdevice-passthrough=0", "--no-spdif"
        )
        val extra = ArrayList<String>()
        // Night mode: a dynamic range compressor — quiet dialogue up, explosions down.
        if (nightMode) extra += listOf(
            "--audio-filter=compressor", "--compressor-rms-peak=0.2", "--compressor-attack=25", "--compressor-release=100",
            "--compressor-threshold=-24", "--compressor-ratio=6", "--compressor-knee=4.5", "--compressor-makeup-gain=10"
        )
        if (subScale != 100) extra += "--sub-text-scale=$subScale"
        // An option this VLC does not know would stop it from starting: then start without the extras.
        runCatching { MediaPlayerFactory(*(base + extra).toTypedArray()) }.getOrElse { MediaPlayerFactory(*base.toTypedArray()) }
    }
    val canvas = remember { java.awt.Canvas().apply { background = java.awt.Color.BLACK; isFocusable = false } }
    val mp = remember {
        factory.mediaPlayers().newEmbeddedMediaPlayer().also {
            it.videoSurface().set(factory.videoSurfaces().newVideoSurface(canvas))
            it.input().enableMouseInputHandling(false) // clicks go to our canvas, not VLC's window
            it.input().enableKeyInputHandling(false)
        }
    }

    var title by remember { mutableStateOf(req.title) }
    var playing by remember { mutableStateOf(false) }
    var time by remember { mutableStateOf(0L) }
    var length by remember { mutableStateOf(0L) }
    var status by remember { mutableStateOf<String?>("Открываем…") }
    var volume by remember { mutableStateOf(ui.get("volume")?.toIntOrNull() ?: 100) }
    var queue by remember { mutableStateOf(listOf(Entry(req.title, req.url))) }
    var index by remember { mutableStateOf(0) }
    var currentUrl by remember { mutableStateOf(req.url) }
    var nextIn by remember { mutableStateOf(-1) }       // countdown seconds, -1 = no card
    var barVisible by remember { mutableStateOf(true) }
    var lastMove by remember { mutableStateOf(System.currentTimeMillis()) } // last real mouse move (screen coords)
    var started by remember { mutableStateOf(false) }  // first picture is playing (bar stays up while opening)
    var vlcPaused by remember { mutableStateOf(false) } // paused, as reported by VLC itself
    var seeking by remember { mutableStateOf<Float?>(null) }
    var retries by remember { mutableStateOf(0) }
    var lastGood by remember { mutableStateOf(0L) }
    var showCanvas by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var prefsApplied by remember { mutableStateOf(false) } // remembered tracks: once per stream, not on every resume
    var pausedAt by remember { mutableStateOf(0L) }
    var pauseMuted by remember { mutableStateOf(false) } // output muted by pause (not by the user)
    var userMuted by remember { mutableStateOf(false) }   // M key
    var resumeFrom by remember { mutableStateOf(-1L) }    // time when resume was pressed
    var audioDelay by remember { mutableStateOf(0L) }        // ms, [ / ] keys
    var parked by remember { mutableStateOf(false) } // video area moved off-screen (kept alive until VLC has stopped)
    var picker by remember { mutableStateOf<String?>(null) } // "audio" / "spu" while the track list is open
    var lastFrame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) } // still for the exit animation
    var pickerItems by remember { mutableStateOf(emptyList<Pair<Int, String>>()) }
    var rate by remember { mutableStateOf(ui.get("rate")?.toFloatOrNull()?.coerceIn(0.25f, 4f) ?: 1f) }
    var subDelay by remember { mutableStateOf(0L) }       // ms, Shift+[ / Shift+]
    var chapters by remember { mutableStateOf(emptyList<Pair<Long, String>>()) } // (start ms, name)
    var infoOpen by remember { mutableStateOf(false) }
    var infoText by remember { mutableStateOf("") }
    var mini by remember { mutableStateOf(false) }
    var hoverAt by remember { mutableStateOf<Long?>(null) } // time under the cursor on the seek bar
    val beforeMini = remember { arrayOfNulls<Any>(4) }    // size, position, placement, onTop before mini mode

    fun watchKey() = req.watchKeys.getOrNull(index)
    fun savePos(t: Long, len: Long) {
        val key = watchKey() ?: return
        if (t < 5_000L) return
        val done = len > 0 && t > len - 15_000L
        library.watch.put(key, "${if (done) len else t}|${len.coerceAtLeast(0)}|${System.currentTimeMillis()}")
    }
    fun savedPos(): Long {
        val w = watchKey()?.let { library.watchInfo(it) } ?: return 0L
        return if (w.watched) 0L else w.pos
    }
    fun seriesKey() = title.substringBefore(" · ").lowercase()
    /** "Track 3 - [Russian]" -> "Russian": lets VLC pick the remembered language itself, without a switch later. */
    fun prefLanguage(kind: String): String? =
        (ui.get("$kind:${seriesKey()}") ?: ui.get("$kind:*"))?.let { Regex("""\[([^\]]+)]""").find(it)?.groupValues?.get(1) }
    /** Voice-overs / subtitles per playlist index (index 0 = the files found by the play dialog). */
    val sidecarCache = remember { java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.Deferred<Pair<List<String>, List<String>>>>() }
    fun sidecars(i: Int): kotlinx.coroutines.Deferred<Pair<List<String>, List<String>>> = sidecarCache.getOrPut(i) {
        if (i == 0 || req.sidecars == null) kotlinx.coroutines.CompletableDeferred(if (i == 0) req.audioUrls to req.subUrls else emptyList<String>() to emptyList())
        else scope.async(Dispatchers.IO) { runCatching { req.sidecars?.invoke(i) }.getOrNull() ?: (emptyList<String>() to emptyList()) }
    }
    /** Still of the current frame (must run on the player thread). */
    fun grabFrame() {
        val img = runCatching { if (mp.video().videoDimension() != null) mp.snapshots().get(canvas.width.coerceIn(320, 1920), 0) else null }.getOrNull()
            ?: return
        val bmp = img.toComposeImageBitmap()
        SwingUtilities.invokeLater { lastFrame = bmp }
    }
    var openJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    /**
     * Starting a stream stops the previous one, which blocks for a moment (network input + video output teardown):
     * all of it runs on the player's own thread, so the window never freezes while switching episodes.
     */
    fun open(url: String, startAt: Long, extras: Pair<List<String>, List<String>>) {
        status = "Открываем…"
        prefsApplied = false
        chapters = emptyList()
        // Remembered per series: sound / subtitle offsets.
        audioDelay = ui.get("adelay:${seriesKey()}")?.toLongOrNull() ?: 0L
        subDelay = ui.get("sdelay:${seriesKey()}")?.toLongOrNull() ?: 0L
        pauseMuted = false; pausedAt = 0L; resumeFrom = -1L // a new stream never inherits the pause mute
        val opts = ArrayList(listOf(":http-reconnect", ":input-fast-seek", ":network-caching=6000"))
        if (startAt > 0) opts += ":start-time=${startAt / 1000.0}"
        prefLanguage("audio")?.let { opts += ":audio-language=$it" }
        prefLanguage("spu")?.let { opts += ":sub-language=$it" }
        val prev = openJob
        openJob = scope.launch(vlcThread) {
            prev?.join()
            if (closing) return@launch
            if (mp.status().isPlayable) {
                grabFrame() // fallback still for the exit animation if the user leaves while the next one is starting
                runCatching { mp.controls().stop() }
            }
            // Extra voice-overs / subtitles are attached BEFORE the media starts: adding them during playback
            // reopens inputs and makes the sound stutter and drift at the start.
            mp.media().prepare(url, *opts.toTypedArray())
            extras.first.forEach { u -> runCatching { mp.media().slaves().add(MediaSlaveType.AUDIO, MediaSlavePriority.HIGHEST, u) } }
            extras.second.forEach { u -> runCatching { mp.media().slaves().add(MediaSlaveType.SUBTITLE, MediaSlavePriority.HIGHEST, u) } }
            mp.controls().play()
        }
    }
    /** Re-open the current episode (reconnect after a network error) with its own extra tracks. */
    fun reopen(at: Long) { val i = index; scope.launch { open(currentUrl, at, sidecars(i).await()) } }
    /**
     * A new audio/subtitle track normally starts only when VLC's read-ahead (6 s of network cache) reaches it.
     * Re-seeking to the current moment flushes that read-ahead, so the chosen track is heard almost at once.
     */
    fun applyTrackNow() {
        val t = mp.status().time()
        if (t > 0) javax.swing.Timer(120) { runCatching { mp.controls().setTime(mp.status().time().coerceAtLeast(0L)) } }.apply { isRepeats = false; start() }
    }
    fun applyPrefs() {
        val audio = ui.get("audio:${seriesKey()}") ?: ui.get("audio:*")
        val spu = ui.get("spu:${seriesKey()}") ?: ui.get("spu:*")
        runCatching {
            // Switch only if VLC did not already pick it (every switch restarts the audio decoder = a hiccup).
            if (audio != null) mp.audio().trackDescriptions().firstOrNull { it.description() == audio }?.let { if (mp.audio().track() != it.id()) { mp.audio().setTrack(it.id()); applyTrackNow() } }
            if (spu == "__off__") { if (mp.subpictures().track() != -1) mp.subpictures().setTrack(-1) }
            else if (spu != null) mp.subpictures().trackDescriptions().firstOrNull { it.description() == spu }?.let { if (mp.subpictures().track() != it.id()) mp.subpictures().setTrack(it.id()) }
        }
    }
    fun playEntry(i: Int) {
        if (i !in queue.indices) return
        savePos(time, length)
        index = i; nextIn = -1; currentUrl = queue[i].url; title = queue[i].title; retries = 0
        playing = false; started = false; time = 0L; length = 0L; status = "Следующая серия…"
        val url = currentUrl; val start = savedPos()
        scope.launch {
            // Usually already fetched in the background; never wait more than a few seconds for it.
            val extras = kotlinx.coroutines.withTimeoutOrNull(4000) { sidecars(i).await() } ?: (emptyList<String>() to emptyList())
            if (index == i && !closing) open(url, start, extras)
        }
    }
    /**
     * Track list = what the player reports plus every track the container declares (some never show up in the
     * player's list until they are first decoded). Ids are the same ES ids, so any of them can be selected.
     */
    fun trackList(audio: Boolean): List<Pair<Int, String>> {
        val map = LinkedHashMap<Int, String>()
        runCatching {
            val desc = if (audio) mp.audio().trackDescriptions() else mp.subpictures().trackDescriptions()
            desc.filter { it.id() != -1 }.forEach { map[it.id()] = it.description() }
        }
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
                    map[t.id()] = name
                }
            }
        }
        return map.toList()
    }
    /**
     * Pause/resume from the real player state. Before, "is it playing?" was false while buffering, so pressing pause
     * during buffering sent PLAY instead — the picture/sound went on. VLC calls run on the player's thread.
     */
    fun unmuteAfterPause() {
        if (!pauseMuted || pausedAt > 0) return
        pauseMuted = false; resumeFrom = -1L
        runCatching { mp.audio().setMute(userMuted) }
    }
    fun togglePause() {
        val wasPaused = runCatching { mp.status().state() == uk.co.caprica.vlcj.player.base.State.PAUSED }.getOrDefault(!playing)
        if (!wasPaused) {
            playing = false; pausedAt = System.currentTimeMillis()
            // VLC's pause is a request to its stream thread, which may be busy reading the network for a moment: the
            // sound would play on until it gets there. Muting the output takes effect at once.
            pauseMuted = true
            runCatching { mp.audio().setMute(true) }
            vlcExecutor.execute { runCatching { mp.controls().setPause(true) } }
        } else {
            val longPause = pausedAt > 0 && System.currentTimeMillis() - pausedAt > 8_000L
            playing = true; pausedAt = 0L; resumeFrom = time
            vlcExecutor.execute {
                runCatching {
                    mp.controls().setPause(false)
                    // After a long pause the network inputs (video + external voice-over) drift apart or the server
                    // has closed the connection: a seek to the same moment restarts them together, in sync.
                    if (longPause) mp.controls().setTime(mp.status().time().coerceAtLeast(0L))
                }
            }
            // Sound comes back once the picture really moves again (see timeChanged); this is only a safety net.
            javax.swing.Timer(2500) { unmuteAfterPause() }.apply { isRepeats = false; start() }
        }
    }
    fun flash(text: String, prefix: String) {
        status = text
        javax.swing.Timer(2000) { if (status?.startsWith(prefix) == true) status = null }.apply { isRepeats = false; start() }
    }
    /**
     * Blu-ray .m2ts: the audio stream (TrueHD / DTS-HD / BD-LPCM) often shows up a moment after the picture, and VLC may
     * then leave "no audio track" selected. If nothing is playing sound, take the first real audio track.
     */
    fun ensureAudio() {
        runCatching {
            if (mp.audio().track() != -1) return
            val first = mp.audio().trackDescriptions().firstOrNull { it.id() != -1 } ?: return
            mp.audio().setTrack(first.id()); applyTrackNow()
            flash("Звук: ${first.description()}", "Звук:")
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
        val t = chapters[i].first; time = t
        vlcExecutor.execute { runCatching { mp.controls().setTime(t) } }
        flash("Глава ${i + 1}: ${chapters[i].second.ifBlank { "без названия" }}", "Глава")
    }
    val introRe = Regex("""(?i)(intro|opening|заставк|опенинг|вступлен|начальн|^op\b|\bop\s?\d*$)""")
    fun inIntro(): Boolean { val i = chapterAt(time); return i in 0 until chapters.size - 1 && introRe.containsMatchIn(chapters[i].second) }
    fun skipIntro() { if (inIntro()) jumpChapter(1) }
    /** Night mode / subtitle size need a fresh VLC: stop cleanly, remember where we are, come back at the same spot. */
    fun restartPlayer() {
        if (closing) return
        closing = true
        savePos(time, length)
        scope.launch {
            withTimeoutOrNull(800) { withContext(vlcThread) { grabFrame(); runCatching { mp.controls().stop() } } }
            showCanvas = false
            onRestart(index)
        }
    }
    fun setMini(on: Boolean) {
        if (on == mini) return
        if (on) {
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
        ui.put("adelay:${seriesKey()}", if (d == 0L) null else d.toString()) // remembered for the whole series
        vlcExecutor.execute { runCatching { mp.audio().setDelay(d * 1000L) } } // microseconds
        status = "Задержка звука: ${if (d > 0) "+" else ""}$d мс"
        javax.swing.Timer(2000) { if (status?.startsWith("Задержка звука") == true) status = null }.apply { isRepeats = false; start() }
    }
    fun seekBy(d: Long) { val t = (mp.status().time() + d).coerceAtLeast(0L); mp.controls().setTime(t); time = t }
    fun toggleFullscreen() {
        win.placement = if (win.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
    }
    fun poke() { lastMove = System.currentTimeMillis() }

    /**
     * Leaving: the native video cannot be animated, so it is swapped for a still of the current frame, which the main
     * window then shrinks away. VLC is stopped BEFORE its window goes away — removing the video area while VLC is still
     * opening a stream (e.g. right after "next") made it retry every video output and froze the app.
     * Nothing here blocks the UI: all VLC calls run on the player's own thread.
     */
    fun close() {
        if (closing) return
        closing = true
        savePos(time, length)
        nextIn = -1
        if (mini) setMini(false)
        if (win.placement == WindowPlacement.Fullscreen) win.placement = WindowPlacement.Floating
        scope.launch {
            // Normally a still + stop take well under 0.2 s. Then the video area is removed cleanly (VLC already let go of it).
            val stopped = withTimeoutOrNull(450) {
                withContext(vlcThread) { grabFrame(); runCatching { mp.controls().stop() }; true }
            } == true
            delay(16) // let the still land
            if (stopped) showCanvas = false
            else {
                // VLC is stuck opening a stream: move its window off-screen at a normal size (shrinking it to a few
                // pixels makes Direct3D fail) and stop it in the background.
                parked = true
                vlcExecutor.execute { runCatching { mp.controls().stop() } }
            }
            onClose()
        }
    }

    // Keyboard shortcuts (installed into the main window while the player is shown).
    DisposableEffect(Unit) {
        keys.value = handler@{ e ->
            if (e.type != KeyEventType.KeyDown) return@handler false
            poke()
            when (e.key) {
                Key.Spacebar, Key.K -> togglePause()
                Key.DirectionRight -> seekBy(if (e.isShiftPressed) 60_000L else 10_000L)
                Key.DirectionLeft -> seekBy(if (e.isShiftPressed) -60_000L else -10_000L)
                Key.DirectionUp -> { volume = (volume + 5).coerceAtMost(150); mp.audio().setVolume(volume) }
                Key.DirectionDown -> { volume = (volume - 5).coerceAtLeast(0); mp.audio().setVolume(volume) }
                Key.F, Key.Enter -> toggleFullscreen()
                Key.Escape -> if (win.placement == WindowPlacement.Fullscreen) win.placement = WindowPlacement.Floating else close()
                Key.Backspace -> close()
                Key.N -> playEntry(index + 1)
                Key.P -> playEntry(index - 1)
                Key.M -> { userMuted = !userMuted; if (!pauseMuted) mp.audio().setMute(userMuted) }
                Key.LeftBracket -> if (e.isShiftPressed) shiftSubs(-100L) else shiftAudio(-100L)   // [ : earlier
                Key.RightBracket -> if (e.isShiftPressed) shiftSubs(100L) else shiftAudio(100L)    // ] : later
                Key.Backslash -> if (e.isShiftPressed) shiftSubs(-subDelay) else shiftAudio(-audioDelay) // \ : reset
                Key.Period -> stepSpeed(1)            // > faster
                Key.Comma -> stepSpeed(-1)            // < slower
                Key.PageDown -> jumpChapter(1)
                Key.PageUp -> jumpChapter(-1)
                Key.S -> skipIntro()
                Key.I -> infoOpen = !infoOpen
                Key.O -> setMini(!mini)
                Key.T -> if (!mini) onTop.value = !onTop.value
                else -> return@handler false
            }
            true
        }
        onDispose { keys.value = null }
    }

    DisposableEffect(Unit) {
        val ui2 = { block: () -> Unit -> SwingUtilities.invokeLater(block) } // vlcj events come on a native thread
        mp.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(p: MediaPlayer) = ui2 {
                playing = true; status = null; retries = 0; started = true; vlcPaused = false
                // Remembered voice-over / subtitles, once the extra tracks above have been probed (only on start,
                // not after every resume — a needless track switch is exactly the "sound gone after pause" glitch).
                if (!prefsApplied) {
                    prefsApplied = true
                    javax.swing.Timer(1500) { applyPrefs(); ensureAudio() }.apply { isRepeats = false; start() }
                    javax.swing.Timer(4500) { ensureAudio() }.apply { isRepeats = false; start() }
                    if (audioDelay != 0L) { val d = audioDelay; vlcExecutor.execute { runCatching { mp.audio().setDelay(d * 1000L) } } }
                    if (subDelay != 0L) { val d = subDelay; vlcExecutor.execute { runCatching { mp.subpictures().setDelay(d * 1000L) } } }
                    if (rate != 1f) { val r = rate; vlcExecutor.execute { runCatching { mp.controls().setRate(r) } } }
                    // Chapters (MKV): read once the container is parsed.
                    scope.launch { delay(1200); chapters = withContext(vlcThread) { readChapters(mp) } }
                }
                mp.audio().setVolume(volume)
                mp.audio().setMute(userMuted || pauseMuted)
            }
            override fun paused(p: MediaPlayer) = ui2 { playing = false; vlcPaused = true; savePos(time, length) }
            override fun stopped(p: MediaPlayer) = ui2 { vlcPaused = false }
            override fun buffering(p: MediaPlayer, newCache: Float) = ui2 { status = if (newCache < 100f) "Буферизация ${newCache.toInt()}%" else null }
            override fun lengthChanged(p: MediaPlayer, newLength: Long) = ui2 { length = newLength }
            override fun timeChanged(p: MediaPlayer, newTime: Long) = ui2 {
                // Resumed and the clock really moves again (the old position + a bit): give the sound back.
                if (pauseMuted && pausedAt == 0L && resumeFrom >= 0 && kotlin.math.abs(newTime - resumeFrom) > 120) unmuteAfterPause()
                if (seeking == null) time = newTime
                lastGood = newTime
                if (newTime / 10_000 != (newTime - 300) / 10_000) savePos(newTime, length)
            }
            override fun finished(p: MediaPlayer) = ui2 {
                if (!closing) {
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
            if (!closing) savePos(time, length)
            if (mini) setMini(false)
            ui.put("volume", volume.toString())
            library.bumpWatch()
            // Released on the player's thread, after any switch still in progress (no freeze on the way out).
            vlcExecutor.execute {
                runCatching { mp.controls().stop() }
                runCatching { mp.release() }
                runCatching { factory.release() }
            }
            vlcExecutor.shutdown()
        }
    }

    // The screen slides in first; the native canvas (which cannot animate) appears after that, then VLC starts.
    LaunchedEffect(Unit) {
        delay(440) // = the main window's entrance animation
        showCanvas = true
        var waited = 0
        while ((!canvas.isDisplayable || canvas.width <= 0) && waited < 8000) { delay(50); waited += 50 }
        // Coming back after a settings restart on a later episode: load the playlist first and start there.
        if (startIndex > 0 && req.playlist != null) {
            val entries = Api.text(req.playlist)?.let { parseM3u(it) }.orEmpty()
            if (entries.size > startIndex) { queue = entries; playEntry(startIndex); return@LaunchedEffect }
        }
        open(req.url, savedPos(), req.audioUrls to req.subUrls)
    }
    // Prefetch the next episode's extra tracks while this one plays, so the switch starts at once.
    LaunchedEffect(index, queue.size) { if (index + 1 < queue.size) { delay(15_000); sidecars(index + 1) } }
    DisposableEffect(canvas) {
        // Single click = pause, double click = fullscreen (the single-click action waits to see if a second comes).
        val single = javax.swing.Timer(260) { togglePause() }.apply { isRepeats = false }
        val clicks = object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount >= 2) { single.stop(); toggleFullscreen() } else single.restart()
            }
        }
        canvas.addMouseListener(clicks)
        onDispose { single.stop(); canvas.removeMouseListener(clicks) }
    }
    LaunchedEffect(req.playlist) {
        val text = req.playlist?.let { Api.text(it) } ?: return@LaunchedEffect
        val entries = parseM3u(text)
        if (entries.size > 1) queue = entries
    }
    LaunchedEffect(infoOpen) {
        while (infoOpen) {
            infoText = withContext(vlcThread) { describePlayback(mp) } +
                "  ·  скорость ${fmtRate(rate)}  ·  звук ${if (audioDelay > 0) "+" else ""}$audioDelay мс  ·  субтитры ${if (subDelay > 0) "+" else ""}$subDelay мс" +
                (if (nightMode) "  ·  ночной режим" else "") + "  ·  буфер сети 6 с"
            delay(1000)
        }
    }
    LaunchedEffect(nextIn) {
        if (nextIn > 0) { delay(1000); nextIn-- } else if (nextIn == 0) playEntry(index + 1)
    }
    // Bar (window and fullscreen alike). Every ~150 ms: where is the system cursor? Moved -> show; nothing for 2.5 s ->
    // roll down. It stays while the cursor rests over the bottom of the window, while paused / opening, while choosing a
    // track, dragging the slider or on the next-episode card. No event plumbing that a resizing video could disturb.
    val fullscreen = win.placement == WindowPlacement.Fullscreen
    LaunchedEffect(Unit) {
        var last: java.awt.Point? = null
        var movedAt = System.currentTimeMillis()
        var keysAt = lastMove
        while (true) {
            val now = System.currentTimeMillis()
            val pos = runCatching { java.awt.MouseInfo.getPointerInfo()?.location }.getOrNull()
            if (pos != null && pos != last) { if (last != null) movedAt = now; last = pos }
            if (lastMove != keysAt) { keysAt = lastMove; movedAt = now } // key presses count as activity
            val w = javax.swing.SwingUtilities.getWindowAncestor(canvas)
            val b = w?.bounds
            val inside = b != null && pos != null && b.contains(pos) && w?.isActive == true
            val zone = b?.let { (it.height * 0.16).toInt().coerceIn(130, 220) } ?: 0
            val nearBottom = inside && pos!!.y >= b!!.y + b.height - zone
            val hold = b == null || !started || vlcPaused || nextIn >= 0 || picker != null || seeking != null || closing || infoOpen || inIntro()
            val show = hold || nearBottom || (inside && now - movedAt < 2500L)
            if (show != barVisible) barVisible = show
            delay(150)
        }
    }
    val barSlide = remember { androidx.compose.animation.core.Animatable(0f) } // 0 = shown, 1 = rolled down
    var barInLayout by remember { mutableStateOf(true) }
    LaunchedEffect(barVisible) {
        if (barVisible) { barInLayout = true; barSlide.animateTo(0f, tween(280, easing = Sm.ease)) }
        else { barSlide.animateTo(1f, tween(260, easing = Sm.ease)); barInLayout = false }
    }
    // The cursor disappears together with the bar.
    val blankCursor = remember {
        java.awt.Toolkit.getDefaultToolkit().createCustomCursor(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB), java.awt.Point(0, 0), "blank")
    }
    LaunchedEffect(lastMove, barVisible) {
        canvas.cursor = java.awt.Cursor.getDefaultCursor()
        if (!barVisible) canvas.cursor = blankCursor // over the bare picture the cursor goes away with the bar
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showCanvas) SwingPanel(factory = { canvas }, modifier = if (parked) Modifier.offset(x = (-8000).dp).size(320.dp, 180.dp) else Modifier.fillMaxSize(), background = Color.Black)
            if (!showCanvas || parked) lastFrame?.let { androidx.compose.foundation.Image(it, null, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                ?: Text(if (closing) "" else "Открываем…", color = Sm.muted, fontFamily = Onest, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center))
        }
        // Slides down / up inside its own slot. The video is resized only ONCE per show/hide (slot added at the start of
        // showing, removed at the end of hiding) — resizing it every frame was what made the bar stutter.
        if (barInLayout) Box(Modifier.fillMaxWidth().clipToBounds()) {
            Column(Modifier.fillMaxWidth().graphicsLayer { translationY = barSlide.value * size.height; alpha = 1f - 0.5f * barSlide.value }
                .background(Sm.booth).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp)) {
                if (infoOpen) Text(infoText.ifBlank { "Собираем данные…" }, color = Color(0xFFFFD791), fontFamily = Onest, fontSize = 12.5.sp,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                if (inIntro()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Text("Идёт заставка", color = Sm.muted, fontFamily = Onest, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    LampButton("Пропустить заставку (S) ⏭") { skipIntro() }
                }
                if (picker == "more") MorePanel(
                    rates = rates, rate = rate, onRate = { setSpeed(it) },
                    subScale = subScale, onSubScale = { v -> ui.put("subScale", if (v == 100) null else v.toString()); restartPlayer() },
                    subDelay = subDelay, onSubDelay = { d -> shiftSubs(d) },
                    night = nightMode, onNight = { ui.put("night", if (nightMode) null else "1"); restartPlayer() },
                    onTop = onTop.value, onOnTop = { onTop.value = !onTop.value }, mini = mini, onMini = { setMini(!mini); picker = null },
                    info = infoOpen, onInfo = { infoOpen = !infoOpen },
                    chapters = chapters, current = chapterAt(time), onChapter = { i -> jumpChapter(i - chapterAt(time)) }
                )
                // Track list opens inside the bar: a popup would be hidden behind the native video surface.
                if (picker == "audio" || picker == "spu") picker?.let { kind ->
                    val current = runCatching { if (kind == "audio") mp.audio().track() else mp.subpictures().track() }.getOrDefault(-2)
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (pickerItems.none { it.first != -1 })
                            Text("Дорожки ещё не определены", color = Sm.muted, fontFamily = Onest, fontSize = 13.sp)
                        pickerItems.forEach { (id, name) ->
                            val on = id == current
                            Chip(name, on) {
                                if (kind == "audio") { mp.audio().setTrack(id); applyTrackNow(); ui.put("audio:${seriesKey()}", name); ui.put("audio:*", name) }
                                else {
                                    mp.subpictures().setTrack(id); if (id != -1) applyTrackNow()
                                    val v = if (id == -1) "__off__" else name
                                    ui.put("spu:${seriesKey()}", v); ui.put("spu:*", v)
                                }
                                picker = null
                            }
                        }
                    }
                }
                if (nextIn >= 0 && index < queue.size - 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                        Text("Следующая серия: ${queue[index + 1].title}", color = Sm.text, fontFamily = Onest, fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("через $nextIn с", color = Sm.lamp, fontFamily = Onest, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp))
                        LampButton("▶ Сейчас") { playEntry(index + 1) }
                        Spacer(Modifier.width(8.dp))
                        OutlineButton("Отмена") { nextIn = -1 }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmt(hoverAt ?: seeking?.toLong() ?: time), color = if (hoverAt != null) Sm.lamp else Sm.text, fontFamily = Onest, fontSize = 13.sp,
                        modifier = Modifier.width(64.dp))
                    SeekBar(
                        position = seeking?.toLong() ?: time, length = length, marks = chapters.map { it.first },
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        onScrub = { t -> seeking = t?.toFloat(); poke() },
                        onSeek = { t -> seeking = null; time = t; vlcExecutor.execute { runCatching { mp.controls().setTime(t) } } },
                        onHover = { t -> hoverAt = t }
                    )
                    Text(if (length > 0) fmt(length) else "--:--", color = Sm.muted, fontFamily = Onest, fontSize = 13.sp)
                }
                if (mini) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Mini-player: just the essentials.
                    LampButton(if (playing) "Ⅱ" else "▶") { togglePause() }
                    if (index < queue.size - 1) OutlineButton("⏭") { playEntry(index + 1) }
                    Text(title, color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                    OutlineButton("⤢ Обычный (O)") { setMini(false) }
                } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlineButton("‹ Библиотека") { close() }
                    OutlineButton("−10") { seekBy(-10_000L) }
                    LampButton(if (playing) "Ⅱ  Пауза" else "▶  Играть") { togglePause() }
                    OutlineButton("+10") { seekBy(10_000L) }
                    if (index < queue.size - 1) OutlineButton("⏭ Следующая") { playEntry(index + 1) }
                    OutlineButton(if (picker == "audio") "♫ Аудио ▴" else "♫ Аудио") {
                        if (picker == "audio") picker = null else { pickerItems = trackList(audio = true); picker = "audio" }
                    }
                    OutlineButton(if (picker == "spu") "CC Субтитры ▴" else "CC Субтитры") {
                        if (picker == "spu") picker = null else { pickerItems = listOf(-1 to "Выключить") + trackList(audio = false); picker = "spu" }
                    }
                    OutlineButton((if (picker == "more") "⚙ Ещё ▴" else "⚙ Ещё") + if (rate != 1f) "  ${fmtRate(rate)}" else "") {
                        picker = if (picker == "more") null else "more"
                    }
                    Text(title + if (queue.size > 1) "  ·  ${index + 1}/${queue.size}" else "", color = Sm.muted, fontFamily = Onest,
                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    status?.let { Text(it, color = Sm.lamp, fontFamily = Onest, fontSize = 13.sp, maxLines = 1) }
                    Text("🔊", fontSize = 14.sp, color = Sm.text)
                    Slider(
                        value = volume.toFloat(), valueRange = 0f..150f,
                        onValueChange = { volume = it.toInt(); mp.audio().setVolume(volume); poke() },
                        colors = SliderDefaults.colors(thumbColor = Sm.text, activeTrackColor = Sm.lamp, inactiveTrackColor = Sm.line2),
                        modifier = Modifier.width(110.dp)
                    )
                    OutlineButton(if (fullscreen) "⤡ (F)" else "⛶ (F)") { toggleFullscreen() }
                }
            }
        }
    }
}

/** "⚙ Ещё": speed, subtitles, night mode, window modes, info and chapters — inline in the bar (popups would hide behind the video). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MorePanel(
    rates: List<Float>, rate: Float, onRate: (Float) -> Unit,
    subScale: Int, onSubScale: (Int) -> Unit, subDelay: Long, onSubDelay: (Long) -> Unit,
    night: Boolean, onNight: () -> Unit, onTop: Boolean, onOnTop: () -> Unit, mini: Boolean, onMini: () -> Unit,
    info: Boolean, onInfo: () -> Unit,
    chapters: List<Pair<Long, String>>, current: Int, onChapter: (Int) -> Unit
) {
    @Composable fun Label(t: String) = Text(t, color = Sm.muted, fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.width(92.dp))
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Скорость")
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rates.forEach { r -> Chip(fmtRate(r), kotlin.math.abs(r - rate) < 0.01f) { onRate(r) } }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Субтитры")
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(75, 100, 125, 150, 200).forEach { v -> Chip("$v%", v == subScale) { if (v != subScale) onSubScale(v) } }
                Spacer(Modifier.width(10.dp))
                Chip("−0.1 с", false) { onSubDelay(-100L) }
                Text("${if (subDelay > 0) "+" else ""}${subDelay} мс", color = Sm.text, fontFamily = Onest, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 7.dp))
                Chip("+0.1 с", false) { onSubDelay(100L) }
                if (subDelay != 0L) Chip("Сброс", false) { onSubDelay(-subDelay) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Режимы")
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("🌙 Ночной звук", night) { onNight() }
                Chip("📌 Поверх окон (T)", onTop) { onOnTop() }
                Chip("▣ Мини-плеер (O)", mini) { onMini() }
                Chip("ⓘ Информация (I)", info) { onInfo() }
            }
        }
        if (chapters.size > 1) Row(verticalAlignment = Alignment.Top) {
            Label("Главы")
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                chapters.take(40).forEachIndexed { i, (_, name) -> Chip("${i + 1}. ${name.ifBlank { "Глава" }.take(28)}", i == current) { onChapter(i) } }
            }
        }
        Text("Размер субтитров и ночной звук применяются перезапуском потока с того же места.  Клавиши: < > скорость · Shift+[ ] субтитры · PgUp/PgDn главы",
            color = Sm.muted, fontFamily = Onest, fontSize = 11.5.sp)
    }
}
