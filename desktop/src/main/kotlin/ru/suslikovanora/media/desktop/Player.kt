package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.hoverable
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.awt.ComposeDialog
import javax.swing.JDialog
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
    val sidecars: (suspend (Int) -> Pair<List<String>, List<String>>)? = null,
    /** «С начала» chosen in the play dialog: ignore the remembered place this time. */
    val fromStart: Boolean = false
)

object Vlc {
    /** VLC 3.x (64-bit) must be installed; NativeDiscovery finds it in Program Files. */
    val available: Boolean by lazy { runCatching { NativeDiscovery().discover() }.getOrDefault(false) }
}

/**
 * One VLC engine for the whole app run. Creating it scans every VLC plugin (hundreds of ms, seconds on a cold start),
 * so it is built once at app start (warm-up) and reused by every player. Only a change of night mode / subtitle size
 * (options VLC reads at start) builds a new one; the old one stays alive, a player may still be releasing on it.
 */
object VlcFactory {
    private val built = HashMap<String, MediaPlayerFactory>()

    /** On exit: lets VLC stop its audio/video threads cleanly (an unreleased engine can hold the process). */
    @Synchronized fun releaseAll() { built.values.forEach { runCatching { it.release() } }; built.clear() }

    @Synchronized fun get(night: Boolean, subScale: Int): MediaPlayerFactory =
        built.getOrPut("$night|$subScale") { build(night, subScale) }

    /** App start: build the engine with the current player settings in the background. */
    fun warm() {
        val ui = Store("player_ui")
        get(ui.get("night") == "1", ui.get("subScale")?.toIntOrNull()?.coerceIn(50, 300) ?: 100)
    }

    private fun build(night: Boolean, subScale: Int): MediaPlayerFactory {
        val base = listOf(
            "--avcodec-hw=any", "--no-video-title-show",
            // DirectSound: pauses and resumes instantly without WASAPI endpoint buffer draining or sleep latency.
            "--aout=directsound",
            // Disable scaletempo time-stretching filter to prevent audio buffer desync and 1s cutout on resume.
            "--no-audio-time-stretch",
            "--no-spdif"
            // (no "--no-avcodec-hurry-up" any more: without hurry-up a decoder that fell behind after a stall could not
            // catch up again and stayed at ~5 fps)
        )
        val extra = ArrayList<String>()
        // Night mode: a dynamic range compressor — quiet dialogue up, explosions down.
        if (night) extra += listOf(
            "--audio-filter=compressor", "--compressor-rms-peak=0.2", "--compressor-attack=25", "--compressor-release=100",
            "--compressor-threshold=-24", "--compressor-ratio=6", "--compressor-knee=4.5", "--compressor-makeup-gain=10"
        )
        if (subScale != 100) extra += "--sub-text-scale=$subScale"
        // An option this VLC does not know would stop it from starting: then start without the extras.
        return runCatching { MediaPlayerFactory(*(base + extra).toTypedArray()) }.getOrElse { MediaPlayerFactory(*base.toTypedArray()) }
    }
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

/** Reads (and throws away) the first [bytes] of [url]: the server and the file host get ready for the real request. */
private fun warmUp(url: String, bytes: Long) {
    runCatching {
        val req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(30))
            .header("Range", "bytes=0-${bytes - 1}").GET().build()
        Api.client.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream()).body().use { input ->
            val buf = ByteArray(64 * 1024); var left = bytes
            while (left > 0) { val n = input.read(buf); if (n < 0) break; left -= n }
        }
    }
}

private fun fmt(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0); val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun fmtRate(r: Float): String = (if (r % 1f == 0f) r.toInt().toString() else r.toString().trimEnd('0')) + "×"

/** VLC's name for a track without a name of its own (Blu-ray streams): «Track 1», «Дорожка 2». */
private val GENERIC_TRACK = Regex("""(?i)(track|дорожка)\s*\d+""")

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

/** (decoded audio blocks, audio buffers played, pictures shown) — null when this VLC gives no statistics. */
private fun soundStats(mp: MediaPlayer): Triple<Long, Long, Long>? = runCatching {
    val info = mp.media().info() ?: return null
    val cls = Class.forName("uk.co.caprica.vlcj.media.MediaStatistics")
    val st = cls.getDeclaredConstructor().newInstance()
    info.javaClass.getMethod("statistics", cls).invoke(info, st)
    fun n(vararg names: String): Long? = names.firstNotNullOfOrNull { (call(st, it) as? Number)?.toLong() }
    Triple(n("decodedAudio", "audioDecoded") ?: return null, n("audioBuffersPlayed", "playedAudioBuffers") ?: return null,
        n("picturesDisplayed", "displayedPictures") ?: 0L)
}.getOrNull()

/** Debug build: VLC's cumulative counters for the [vlc] journal line (empty when this VLC gives none). Player thread. */
private fun vlcStats(mp: MediaPlayer): Map<String, Long> = runCatching {
    val info = mp.media().info() ?: return emptyMap()
    val cls = Class.forName("uk.co.caprica.vlcj.media.MediaStatistics")
    val st = cls.getDeclaredConstructor().newInstance()
    info.javaClass.getMethod("statistics", cls).invoke(info, st)
    fun n(vararg names: String): Long = names.firstNotNullOfOrNull { (call(st, it) as? Number)?.toLong() } ?: 0L
    mapOf(
        "read" to n("inputBytesRead", "readBytes"), "shown" to n("picturesDisplayed", "displayedPictures"),
        "lost" to n("picturesLost", "lostPictures"), "video" to n("decodedVideo", "videoDecoded"),
        "audio" to n("decodedAudio", "audioDecoded"), "played" to n("audioBuffersPlayed", "playedAudioBuffers"),
        "aLost" to n("audioBuffersLost", "lostAudioBuffers"), "corrupt" to n("demuxCorrupted"), "discont" to n("demuxDiscontinuity"),
    )
}.getOrDefault(emptyMap())

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
private fun SeekBar(position: Long, length: Long, buffered: Long = 0L, marks: List<Long>, modifier: Modifier,
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
                val bf = if (length > 0) (buffered.toFloat() / length).coerceIn(0f, 1f) else 0f
                if (bf > 0f) drawRoundRect(Color(0x80B4B8C4), topLeft = Offset(0f, cy - h / 2), size = Size(size.width * bf, h), cornerRadius = r)
                val f = if (length > 0) (position.toFloat() / length).coerceIn(0f, 1f) else 0f
                if (f > 0f) drawRoundRect(Color.White, topLeft = Offset(0f, cy - h / 2), size = Size(size.width * f, h), cornerRadius = r)
                // chapter boundaries: small dark gaps across the track
                if (length > 0) for (m in marks) {
                    if (m <= 0 || m >= length) continue
                    val x = size.width * (m.toFloat() / length)
                    drawRect(Sm.booth, topLeft = Offset(x - 1.5f, cy - h / 2 - 1), size = Size(3f, h + 2))
                }
                drawCircle(Color.White, radius = (5f + 3f * grow).dp.toPx(), center = Offset(size.width * f, cy))
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
    parentWindow: java.awt.Window? = null,  // main window for owning transparent overlay dialogs
    onRestart: (Int) -> Unit = {},          // re-create the player at this episode (night mode / subtitle size)
    onClose: () -> Unit
) {
    val ui = remember { Store("player_ui") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val vlcExecutor = remember { java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "vlc-control").apply { isDaemon = true } }.also { AppExit.track(it) } }
    val vlcThread = remember { vlcExecutor.asCoroutineDispatcher() }
    /**
     * Every call into VLC goes through here, onto the player's own thread. A libVLC call can block while VLC is busy
     * with the network (opening, seeking, a slow mirror): made from the window's thread, that froze the whole app.
     */
    fun vlc(block: () -> Unit) { runCatching { vlcExecutor.execute { runCatching(block) } } }
    // Settings VLC only reads when it starts (applied by re-creating the player, see restartPlayer()).
    val nightMode = remember { ui.get("night") == "1" }
    val subScale = remember { ui.get("subScale")?.toIntOrNull()?.coerceIn(50, 300) ?: 100 }
    val factory = remember { VlcFactory.get(nightMode, subScale) }
    val canvas = remember { java.awt.Canvas().apply { background = java.awt.Color.BLACK; isFocusable = false } }
    val mp = remember {
        factory.mediaPlayers().newEmbeddedMediaPlayer().also {
            it.videoSurface().set(factory.videoSurfaces().newVideoSurface(canvas))
            it.input().enableMouseInputHandling(false) // clicks go to our canvas, not VLC's window
            it.input().enableKeyInputHandling(false)
        }
    }

    var index by remember { mutableStateOf(0) }
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
    val initialPos = remember { if (req.fromStart) 0L else savedPos(0) }
    var time by remember { mutableStateOf(initialPos) }
    var length by remember { mutableStateOf(0L) }
    // Blu-ray (.m2ts): VLC knows no length over HTTP — read from the stream's clock (TsClock), seeks by position
    // (TsPlayback, shared with the Android app; see there).
    var ts by remember { mutableStateOf<TsPlayback?>(null) }
    var tsLen by remember { mutableStateOf(0L) }       // length read for tsLenUrl
    var tsLenUrl by remember { mutableStateOf("") }
    var tsStartAt by remember { mutableStateOf(0L) }  // where the stream was opened (:start-time is ignored there)
    var tsOpenDone by remember { mutableStateOf(true) }
    var tsAbsolute by remember { mutableStateOf(false) } // reopened for a seek before VLC's clock (see vlcSeekTo)
    var status by remember { mutableStateOf<String?>("Открываем…") }
    var volume by remember { mutableStateOf(ui.get("volume")?.toIntOrNull() ?: 100) }
    var lastVolumeWheelAt by remember { mutableStateOf(0L) }
    var queue by remember { mutableStateOf(listOf(Entry(req.title, req.url))) }
    var currentUrl by remember { mutableStateOf(req.url) }
    var nextIn by remember { mutableStateOf(-1) }       // countdown seconds, -1 = no card
    var barVisible by remember { mutableStateOf(true) }
    var lastMove by remember { mutableStateOf(System.currentTimeMillis()) } // last real mouse move (screen coords)
    var started by remember { mutableStateOf(false) }  // first picture is playing (bar stays up while opening)
    var vlcPaused by remember { mutableStateOf(false) } // paused, as reported by VLC itself
    var seeking by remember { mutableStateOf<Float?>(null) }
    var retries by remember { mutableStateOf(0) }
    var lastGood by remember { mutableStateOf(initialPos) }
    var showCanvas by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var prefsApplied by remember { mutableStateOf(false) } // remembered tracks: once per stream, not on every resume
    var pausedAt by remember { mutableStateOf(0L) }
    var userMuted by remember { mutableStateOf(false) }   // M key
    var audioDelay by remember { mutableStateOf(0L) }        // ms, [ / ] keys
    var parked by remember { mutableStateOf(false) } // video area moved off-screen (kept alive until VLC has stopped)
    var picker by remember { mutableStateOf<String?>(null) } // "audio" / "spu" while the track list is open
    var lastFrame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) } // still for the exit animation
    var pickerItems by remember { mutableStateOf(emptyList<Pair<Int, String>>()) }
    var pickerCurrent by remember { mutableStateOf(-2) } // selected track id in the open list
    var prefsUntil by remember { mutableStateOf(0L) }     // remembered tracks may still be applied until then (new tracks keep arriving)
    var rate by remember { mutableStateOf(ui.get("rate")?.toFloatOrNull()?.coerceIn(0.25f, 4f) ?: 1f) }
    var subDelay by remember { mutableStateOf(0L) }       // ms, Shift+[ / Shift+]
    var chapters by remember { mutableStateOf(emptyList<Pair<Long, String>>()) } // (start ms, name)
    var infoOpen by remember { mutableStateOf(false) }
    var infoText by remember { mutableStateOf("") }
    var mini by remember { mutableStateOf(false) }
    var lastSeekAt by remember { mutableStateOf(0L) } // a seek in flight: VLC's clock still reports the old place
    var bufferingSince by remember { mutableStateOf(0L) }
    val supRef = remember { java.util.concurrent.atomic.AtomicReference<PlaybackSupervisor?>(null) } // events → supervisor
    var hasVoiceOver by remember { mutableStateOf(false) } // the current episode plays an external audio file
    var hoverAt by remember { mutableStateOf<Long?>(null) } // time under the cursor on the seek bar
    var bufferedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(barVisible, currentUrl, length, time, playing, bufferingSince) {
        if (!barVisible) return@LaunchedEffect
        while (true) {
            val vlcTimeBufferMs = if (playing || bufferingSince > 0L) 6_000L else 0L
            val diskBufferMs = BurstProxy.aheadMs(currentUrl, length)
            bufferedMs = if (length > 0L) (time + vlcTimeBufferMs + diskBufferMs).coerceIn(time, length) else 0L
            delay(200L)
        }
    }
    val beforeMini = remember { arrayOfNulls<Any>(4) }    // size, position, placement, onTop before mini mode
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
    fun grabFrame(): androidx.compose.ui.graphics.ImageBitmap? {
        val img = runCatching { if (mp.video().videoDimension() != null) mp.snapshots().get(canvas.width.coerceIn(320, 1920), 0) else null }.getOrNull()
            ?: return null
        val bmp = img.toComposeImageBitmap()
        SwingUtilities.invokeLater { lastFrame = bmp }
        return bmp
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
        lastGood = startAt
        time = startAt
        // Remembered per series: sound / subtitle offsets.
        audioDelay = ui.get("adelay:${seriesKey()}")?.toLongOrNull() ?: 0L
        subDelay = ui.get("sdelay:${seriesKey()}")?.toLongOrNull() ?: 0L
        pausedAt = 0L
        // «Настройки»: the player's buffer and the reserve on disk (BurstProxy; 0 = off, VLC reads the mirror).
        val opts = ArrayList(listOf(":http-reconnect", ":network-caching=${PlaybackSettings.cacheMs()}"))
        Reserve.init()
        BurstProxy.capBytes = PlaybackSettings.burstMb().toLong() shl 20
        BurstProxy.keepOnly(listOf(url) + extras.first)
        hasVoiceOver = extras.first.isNotEmpty()
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
                // Blu-ray: its length before VLC starts (two small requests to both ends of the file, once per file)
                if (tsLenUrl != url) {
                    val r = withContext(Dispatchers.IO) { TsClock.probe(url) }
                    tsLen = r?.first ?: 0L; tsLenUrl = url
                    PlayerLog.write(if (r != null) "[ts] duration from the stream's clock: ${fmt(r.first)}" else "[ts] no duration in the stream's clock")
                }
                withContext(Dispatchers.Main) { ts = if (tsLen > 0) TsPlayback(tsLen, url) else null; if (tsLen > 0) length = tsLen }
                if (closing) return@launch
            }
            if (mp.status().isPlayable) {
                grabFrame() // fallback still for the exit animation if the user leaves while the next one is starting
                runCatching { mp.controls().stop() }
            }
            // Extra voice-overs / subtitles are attached BEFORE the media starts: adding them during playback
            // reopens inputs and makes the sound stutter and drift at the start.
            mp.media().prepare(playUrl, *opts.toTypedArray())
            extras.first.forEach { u -> runCatching { mp.media().slaves().add(MediaSlaveType.AUDIO, MediaSlavePriority.HIGHEST, BurstProxy.wrap(u)) } }
            extras.second.forEach { u -> runCatching { mp.media().slaves().add(MediaSlaveType.SUBTITLE, MediaSlavePriority.HIGHEST, u) } }
            mp.controls().play()
        }
    }
    /** Re-open the current episode (reconnect after a network error) with its own extra tracks. */
    fun reopen(at: Long) { val i = index; scope.launch { open(currentUrl, at, sidecars(i).await()) } }
    /** Seek to the film time [t]: by time, or for a Blu-ray by position measured from here (TsPlayback). */
    fun vlcSeekTo(t: Long) {
        lastGood = t
        val s = ts
        if (s == null) { vlc { mp.controls().setTime(t) }; return }
        // After a continued open VLC counts from 0 at that place (TsPlayback.offset): a seek back before it has no time
        // on VLC's clock — it showed 0:00 and stood (13.10). Reopen so that VLC reads the real clock at the file's start
        // first and jumps after that (one extra loading, only for this seek).
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
    /** A seek to the current moment (restarts the decoders / inputs together). Runs on the player thread. */
    fun reseekHereNow() {
        if (ts != null) mp.controls().setPosition(mp.status().position())
        else { val t = mp.status().time(); if (t > 0) mp.controls().setTime(t) }
    }
    /**
     * A new audio/subtitle track normally starts only when VLC's read-ahead (6 s of network cache) reaches it.
     * Re-seeking to the current moment flushes that read-ahead, so the chosen track is heard almost at once.
     */
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
        val url = currentUrl; val start = savedPos(i)
        playing = false; started = false; time = start; lastGood = start; length = 0L; status = "Следующая серия…"
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
        val extra = ArrayList<String>() // declared by the file but not (yet) in VLC's list — e.g. an external voice-over
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
            // Blu-ray (.m2ts): VLC lists its streams as «Track 1, Track 2…», and the same streams come again under other
            // ids with their names («Russian», «English») — every track twice. One entry each: VLC's id (that is the one
            // it switches to) with the real name, in the same order; extra names beyond VLC's list are dropped.
            val generic = map.entries.filter { GENERIC_TRACK.matches(it.value.trim()) }
            generic.forEachIndexed { i, e -> extra.getOrNull(i)?.let { e.setValue(it) } }
        }
        return map.toList()
    }
    /** The track list is read on the player's thread, then shown. */
    fun openPicker(kind: String) {
        scope.launch {
            val (items, cur) = withContext(vlcThread) {
                val audio = kind == "audio"
                val list = trackList(audio)
                (if (audio) list else listOf(-1 to "Выключить") + list) to
                    runCatching { if (audio) mp.audio().track() else mp.subpictures().track() }.getOrDefault(-2)
            }
            pickerItems = items; pickerCurrent = cur; picker = kind
        }
    }
    /**
     * Pause/resume from the real player state. Before, "is it playing?" was false while buffering, so pressing pause
     * during buffering sent PLAY instead — the picture/sound went on. VLC calls run on the player's thread.
     */
    fun togglePause() {
        val wasPaused = pausedAt > 0 || vlcPaused
        if (!wasPaused) {
            playing = false; pausedAt = System.currentTimeMillis()
            supRef.get()?.paused()
            // DirectSound stops video and sound immediately without needing artificial muting delays.
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
    /**
     * Blu-ray .m2ts: the audio stream (TrueHD / DTS-HD / BD-LPCM) often shows up a moment after the picture, and VLC may
     * then leave "no audio track" selected. If nothing is playing sound, take the first real audio track.
     */
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
    /** Night mode / subtitle size need a fresh VLC: stop cleanly, remember where we are, come back at the same spot. */
    fun restartPlayer() {
        if (closing) return
        closing = true
        savePos(time, length)
        library.bumpWatch()
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
    fun seekBy(d: Long) {
        PlayerLog.write("seek ${if (d > 0) "+" else ""}${d / 1000}s")
        supRef.get()?.seeked()
        var t = (time + d).coerceAtLeast(0L)
        if (length > 0) t = t.coerceAtMost((length - 2_000L).coerceAtLeast(0L))
        time = t; lastGood = t; lastSeekAt = System.currentTimeMillis()
        vlcSeekTo(t)
    }
    fun toggleFullscreen() {
        win.placement = if (win.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
    }
    fun poke() {
        lastMove = System.currentTimeMillis()
        if (!barVisible) barVisible = true
    }

    /**
     * Leaving: the native video cannot be animated, so it is swapped for a still of the current frame, which the main
     * window then flies back into the film's poster.
     *
     * It used to wait for VLC to STOP before the animation began (up to half a second with a frozen picture), and
     * the library recomputed everyone's progress only after the return — both were the "freeze" on the way back.
     * Now only the still is awaited (normally ~50 ms, at most 180 ms, while the film keeps playing), the native video
     * is moved off-screen at once (at a normal size: shrinking it to a few pixels makes Direct3D fail) and VLC stops in
     * the background; its window is removed only when the player goes away, after the animation, when VLC has
     * long let go of it (removing it while VLC is still opening a stream froze the app).
     */
    fun close() {
        if (closing) return
        closing = true
        savePos(time, length)
        library.bumpWatch() // the library updates progress now, behind the film, not when it is already on screen
        nextIn = -1
        supRef.get()?.closed()
        scope.launch {
            withTimeoutOrNull(180) { withContext(vlcThread) { grabFrame() } }?.let { lastFrame = it }
            parked = true
            if (mini) setMini(false)
            if (win.placement == WindowPlacement.Fullscreen) win.placement = WindowPlacement.Floating
            onClose() // the exit animation starts right away
            vlcExecutor.execute { runCatching { mp.controls().stop() } }
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
                Key.F, Key.Enter -> toggleFullscreen()
                Key.Escape -> if (win.placement == WindowPlacement.Fullscreen) win.placement = WindowPlacement.Floating else close()
                Key.Backspace -> close()
                Key.N -> playEntry(index + 1)
                Key.P -> playEntry(index - 1)
                Key.M -> { userMuted = !userMuted; val m = userMuted; vlc { mp.audio().setMute(m) } }
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
                Key.F8 -> if (Diag.on) flash("📍 Метка #${Diag.mark("player at ${time / 1000}s")}", "mark") else return@handler false
                else -> return@handler false
            }
            true
        }
        onDispose { keys.value = null }
    }

    // ---- debug build: [vlc] and [proxy] lines every 5 s (counters since the last line) ---------------------------
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
        val ui2 = { block: () -> Unit -> SwingUtilities.invokeLater(block) } // vlcj events come on a native thread
        mp.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(p: MediaPlayer) = ui2 {
                val wasStarted = started
                playing = true; status = null; retries = 0; started = true; vlcPaused = false
                // Blu-ray: the length to the reserve; opened at a saved place → jump there now (TsPlayback.openJump)
                ts?.let { s ->
                    BurstProxy.setDuration(currentUrl, s.lengthMs)
                    if (!tsOpenDone && !tsAbsolute) { tsOpenDone = true; s.openJump(tsStartAt)?.let { pos -> PlayerLog.write("[ts] continue at ${tsStartAt / 1000}s → position %.4f".format(pos)); vlc { mp.controls().setPosition(pos) } } }
                }
                if (pausedAt == 0L) supRef.get()?.playing()
                // Remembered voice-over / subtitles, once the extra tracks above have been probed (only on start,
                // not after every resume — a needless track switch is exactly the "sound gone after pause" glitch).
                if (!prefsApplied) {
                    prefsApplied = true
                    // Applied as soon as the tracks are known: now, and again whenever VLC reports a new one (an
                    // external voice-over or a Blu-ray audio stream shows up later) — no fixed guess-the-delay timer.
                    prefsUntil = System.currentTimeMillis() + 8_000L
                    prefsTimer.initialDelay = 300; prefsTimer.restart()
                    javax.swing.Timer(4500) { ensureAudio() }.apply { isRepeats = false; start() }
                    if (audioDelay != 0L) { val d = audioDelay; vlcExecutor.execute { runCatching { mp.audio().setDelay(d * 1000L) } } }
                    if (subDelay != 0L) { val d = subDelay; vlcExecutor.execute { runCatching { mp.subpictures().setDelay(d * 1000L) } } }
                    if (rate != 1f) { val r = rate; vlcExecutor.execute { runCatching { mp.controls().setRate(r) } } }
                    // Chapters (MKV): read once the container is parsed.
                    scope.launch { delay(1200); chapters = withContext(vlcThread) { readChapters(mp) } }
                }
                val v = volume; val m = userMuted
                // Pause was pressed while the film was still opening: VLC ignores a pause then and starts anyway —
                // the film ran on muted, with the "paused" state kept. Really pause it now.
                if (pausedAt > 0) { playing = false; vlc { mp.audio().setMute(true); mp.controls().setPause(true) } }
                else if (!wasStarted) vlc { mp.audio().setVolume(v); mp.audio().setMute(m) }
            }
            override fun elementaryStreamAdded(p: MediaPlayer, type: uk.co.caprica.vlcj.media.TrackType, id: Int) = ui2 {
                // A track appeared: re-check the remembered choice shortly after the burst of new tracks settles.
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
                if (newLength > 0 || ts == null) { length = newLength; BurstProxy.setDuration(currentUrl, newLength) } // Blu-ray: 0 — keep its own
            }
            override fun timeChanged(p: MediaPlayer, vlcTime: Long) = ui2 tick@{
                // Blu-ray: VLC's time → the film's (offset after a continued open; its old place right after a seek is ignored)
                // Reopened for a seek back (vlcSeekTo): VLC has read the real clock at the start now — go to the place.
                ts?.let { s0 -> if (tsAbsolute && !tsOpenDone && vlcTime > 0) { tsAbsolute = false; tsOpenDone = true; s0.openJump(tsStartAt)?.let { pos -> vlc { mp.controls().setPosition(pos) } } } }
                val s = ts
                val newTime = if (s == null) vlcTime else {
                    val pos = p.status().position()
                    val f = if (s.opening) s.openLanded(vlcTime, pos) else s.filmTime(vlcTime)
                    if (f == null || (s.seeking && !s.seekLanded(f, pos))) return@tick
                    s.tick(vlcTime, pos)
                    f
                }
                // The bar needs a few updates a second, not every VLC tick: fewer redraws of the whole bar. Right after a
                // seek VLC still reports the old place for a moment — keep the target instead of jumping back.
                if (seeking == null && System.currentTimeMillis() - lastSeekAt > 600 && (kotlin.math.abs(newTime - time) >= 250 || newTime < time)) time = newTime
                lastGood = newTime
                if (newTime / 10_000 != (newTime - 300) / 10_000) savePos(newTime, length)
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
            // The temporary reserve of the films watched here: closing its downloads and deleting the files can take
            // a moment — never on the UI thread (it was part of the hitch right as the library came back).
            Thread({ runCatching { BurstProxy.clear() } }, "reserve-clear").apply { isDaemon = true; start() }
            if (!closing) { savePos(time, length); library.bumpWatch() } // (close() / restartPlayer() already did)
            if (mini) setMini(false)
            ui.put("volume", volume.toString())
            // Released on the player's thread, after any switch still in progress (no freeze on the way out).
            vlcExecutor.execute {
                runCatching { mp.controls().stop() }
                runCatching { mp.release() } // the engine (factory) is shared and stays for the next player
            }
            vlcExecutor.shutdown()
            // Kept in AppExit's list until its release job ran: an exit right now waits for it (briefly).
            Thread({ runCatching { vlcExecutor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS) }; AppExit.untrack(vlcExecutor) }, "vlc-untrack")
                .apply { isDaemon = true; start() }
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
        open(req.url, if (req.fromStart) 0L else savedPos(), req.audioUrls to req.subUrls)
    }
    // Prefetch the next episode's extra tracks while this one plays, so the switch starts at once.
    LaunchedEffect(index, queue.size) { if (index + 1 < queue.size) { delay(15_000); sidecars(index + 1) } }
    // About a minute before the end the next episode is "warmed up": the server gets its AllDebrid link ready (that
    // lookup is most of the wait when a new file starts) and the first megabytes of the video and voice-over are
    // fetched once. When the episode ends — or "Следующая" is pressed — the next one starts almost at once.
    // Silent film: the picture moves but no sound comes out (the audio output failed to start — a headset was just
    // switched, the device was busy — or the voice-over stopped delivering). Changing tracks did not help, only
    // reopening did. Every 2 s while playing: no sound buffer played for ~6 s → first make sure nothing is muted and a
    // real track is selected, then reopen the film at the same moment (at most twice per episode).
    // Restarts the video output (new decoder + vout) at the current moment: the cure for a picture that froze while
    // the sound and the clock go on (seen after leaving fullscreen: the video surface was resized under VLC).
    fun restartVideo() {
        PlayerLog.write("restart video output at ${time / 1000}s")
        vlc {
            val id = mp.video().track()
            if (id >= 0) { mp.video().setTrack(-1); mp.video().setTrack(id) }
            reseekHereNow()
        }
    }
    // Playback health: one supervisor (shared with the Android app) decides when something is wrong and what to do —
    // only while the film really plays, never right after a seek / resume, one gentle step at a time (core module).
    // (plain lambdas: inside the object below, restartVideo / reopen would mean the object's own methods)
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
    // Entering / leaving fullscreen or mini mode resizes the video area under VLC: let the supervisor look right after.
    LaunchedEffect(win.placement, mini) { if (started) supervisor.surfaceChanged() }
    val warmed = remember { HashSet<Int>() }
    val nearEnd = length > 0 && length - time in 1..60_000L
    LaunchedEffect(index, nearEnd) {
        val next = index + 1
        if (!nearEnd || next !in queue.indices || !warmed.add(next)) return@LaunchedEffect
        val video = queue[next].url
        val extras = kotlinx.coroutines.withTimeoutOrNull(10_000) { sidecars(next).await() }
        withContext(Dispatchers.IO) {
            // Through the disk reserve when it is on: the next episode's start is then already on this computer.
            (listOf(video) + extras?.first.orEmpty()).forEach { u -> launch { warmUp(BurstProxy.wrap(u), if (u == video) 4L shl 20 else 1L shl 20) } }
        }
    }
    DisposableEffect(canvas) {
        // Single click = pause, double click = fullscreen (the single-click action waits to see if a second comes).
        val single = javax.swing.Timer(260) { togglePause() }.apply { isRepeats = false }
        val clicks = object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.button != java.awt.event.MouseEvent.BUTTON1) return
                if (e.clickCount >= 2) { single.stop(); toggleFullscreen() } else single.restart()
            }
        }
        val wheel = java.awt.event.MouseWheelListener { e ->
            val rot = e.wheelRotation
            if (rot != 0) {
                val step = if (rot < 0) 5 else -5
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
        }
        // No mouse-motion listener on purpose: Windows sends a WM_MOUSEMOVE to the window under a resting cursor whenever an
        // overlay dialog is shown or hidden, so hiding the bar itself "moved the mouse" and brought it straight back.
        // The polling loop below compares real screen coordinates instead.
        canvas.addMouseListener(clicks)
        canvas.addMouseWheelListener(wheel)
        onDispose {
            single.stop()
            canvas.removeMouseListener(clicks)
            canvas.removeMouseWheelListener(wheel)
        }
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
                (if (nightMode) "  ·  ночной режим" else "") + "  ·  буфер ${PlaybackSettings.cacheMs() / 1000.0} с" +
                (BurstProxy.aheadMb(currentUrl)?.let { "  ·  запас на диске: %.0f МБ вперёд (из ${PlaybackSettings.burstMb()})".format(it) } ?: "  ·  без запаса (напрямую)") +
                "  ·  журнал: ${PlayerLog.file.absolutePath}"
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

    // System-wide pointer tracking: detects mouse moves even over VLC native surface
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

    // Auto-hide timer: when bar is visible and playing, rolls down after 2.5s of inactivity
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
    // The cursor disappears together with the bar.
    val blankCursor = remember {
        java.awt.Toolkit.getDefaultToolkit().createCustomCursor(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB), java.awt.Point(0, 0), "blank")
    }
    LaunchedEffect(barVisible) {
        if (barVisible) {
            canvas.cursor = java.awt.Cursor.getDefaultCursor()
        } else {
            canvas.cursor = blankCursor
        }
    }

    val owner = parentWindow ?: runCatching { java.awt.Frame.getFrames().firstOrNull { it.isShowing } }.getOrNull()

    val topDialog = remember(owner) {
        if (owner == null) null else ComposeDialog(owner).apply {
            isUndecorated = true
            isTransparent = true
            isResizable = false
            focusableWindowState = false
        }
    }
    val bottomDialog = remember(owner) {
        if (owner == null) null else ComposeDialog(owner).apply {
            isUndecorated = true
            isTransparent = true
            isResizable = false
            focusableWindowState = false
        }
    }

    fun syncOverlayBounds() {
        val comp = if (canvas.isShowing) canvas else ((owner as? javax.swing.RootPaneContainer)?.contentPane ?: owner) ?: return
        val loc = runCatching { comp.locationOnScreen }.getOrNull() ?: return
        val w = comp.width
        val h = comp.height
        if (w <= 0 || h <= 0) return
        if (parked) return // the video area is off-screen then: keep the overlays where they were
        val topH = 75
        // Tall enough for the open menu AND the scrubber + buttons under it (Column gives the last children what is left).
        val bottomH = (when {
            picker == "more" -> 600 + (if (infoOpen) 60 else 0)
            picker != null -> 460 + (if (infoOpen) 60 else 0)
            infoOpen -> 240
            inIntro() || nextIn >= 0 -> 220
            else -> 210
        }).coerceAtMost(h - topH)

        topDialog?.setBounds(loc.x, loc.y, w, topH)
        bottomDialog?.setBounds(loc.x, loc.y + h - bottomH, w, bottomH)
        topDialog?.revalidate()
        bottomDialog?.revalidate()
    }

    DisposableEffect(owner, canvas) {
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
        owner?.addWindowListener(winListener)
        canvas.addComponentListener(compListener)
        onDispose {
            owner?.removeComponentListener(compListener)
            owner?.removeWindowListener(winListener)
            canvas.removeComponentListener(compListener)
            topDialog?.isVisible = false
            bottomDialog?.isVisible = false
            topDialog?.dispose()
            bottomDialog?.dispose()
        }
    }

    LaunchedEffect(barVisible, closing) {
        if (closing) {
            topDialog?.isVisible = false
            bottomDialog?.isVisible = false
            return@LaunchedEffect
        }
        if (barVisible) {
            syncOverlayBounds()
            topDialog?.isVisible = true
            bottomDialog?.isVisible = true
            barSlide.animateTo(0f, tween(280, easing = Sm.ease))
        } else {
            barSlide.animateTo(1f, tween(260, easing = Sm.ease))
            topDialog?.isVisible = false
            bottomDialog?.isVisible = false
        }
    }

    LaunchedEffect(picker, infoOpen, inIntro(), nextIn, win.placement) {
        if (barVisible && !closing) {
            syncOverlayBounds()
        }
    }

    // Video layer in main window: fills the window permanently without resizing or jumping
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press) {
                            poke()
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
            val controlsAlpha = if (closing) 0f else (1f - barSlide.value).coerceIn(0f, 1f)
            SuslikTheme {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xCC090B10),
                                    Color(0x66090B10),
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(horizontal = 24.dp, vertical = 14.dp)
                        .graphicsLayer {
                            translationY = -barSlide.value * 90.dp.toPx()
                            alpha = controlsAlpha
                        }
                        .pointerHoverIcon(PointerIcon.Default)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    if (e.type == PointerEventType.Press) { // not Move: Compose sends synthetic moves while the bar re-lays out, which would keep it up forever; real moves come from the polling loop
                                        poke()
                                    }
                                }
                            }
                        },
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassIconButton(onClick = { close() }, tooltip = "В библиотеку (Esc)") {
                            Text("‹", color = Sm.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                        GlassIconButton(
                            onClick = { onTop.value = !onTop.value },
                            active = onTop.value,
                            tooltip = if (onTop.value) "Открепить окно (T)" else "Поверх всех окон (T)"
                        ) {
                            Text("⧉", color = Sm.text, fontSize = 18.sp)
                        }
                    }
                    Text(
                        title + if (queue.size > 1) "  ·  ${index + 1}/${queue.size}" else "",
                        color = Sm.text.copy(alpha = 0.9f),
                        fontFamily = Onest,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 16.dp).weight(1f)
                    )
                }
            }
        }

        bottomDialog?.setContent {
            val controlsAlpha = if (closing) 0f else (1f - barSlide.value).coerceIn(0f, 1f)
            SuslikTheme {
                val animBuffered by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = bufferedMs.toFloat(),
                    animationSpec = tween(260)
                )
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val totalWidth = maxWidth
                    val isCompact = totalWidth < 880.dp
                    val isVeryNarrow = totalWidth < 680.dp
                    val buttonSpacing = when {
                        isVeryNarrow -> 4.dp
                        isCompact -> 6.dp
                        else -> 10.dp
                    }
                    val btnSize = when {
                        isVeryNarrow -> 34.dp
                        isCompact -> 38.dp
                        else -> 42.dp
                    }
                    val heroSize = when {
                        isVeryNarrow -> 40.dp
                        isCompact -> 44.dp
                        else -> 48.dp
                    }
                    Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color(0x33090B10),
                                    Color(0xB3090B10),
                                    Color(0xF0090B10)
                                )
                            )
                        )
                        .padding(start = if (isVeryNarrow) 12.dp else 24.dp, end = if (isVeryNarrow) 12.dp else 24.dp, bottom = 14.dp)
                        .graphicsLayer {
                            translationY = barSlide.value * 300.dp.toPx()
                            alpha = controlsAlpha
                        }
                        .pointerHoverIcon(PointerIcon.Default)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    if (e.type == PointerEventType.Press) { // not Move: Compose sends synthetic moves while the bar re-lays out, which would keep it up forever; real moves come from the polling loop
                                        poke()
                                    } else if (e.type == PointerEventType.Scroll) {
                                        val delta = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                        if (delta != 0f) {
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
                                    }
                                }
                            }
                        },
                    verticalArrangement = Arrangement.Bottom
                ) {
                    if (infoOpen) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xD90E1118))
                                .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(10.dp))
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Text(
                                infoText.ifBlank { "Собираем данные…" },
                                color = Color.White.copy(alpha = 0.85f),
                                fontFamily = Onest,
                                fontSize = 11.5.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }

                    if (inIntro()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0xCC161B26))
                                    .border(1.dp, Color(0x4DFFFFFF), RoundedCornerShape(50))
                                    .clickable { skipIntro() }
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Пропустить заставку (S)", color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("⏭", color = Color.White, fontSize = 14.sp)
                                }
                            }
                        }
                    }

                    if (picker == "more") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xF20E1118))
                                .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(14.dp))
                                .padding(16.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Настройки воспроизведения", color = Color.White, fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    GlassIconButton(onClick = { picker = null }, size = 26.dp) {
                                        Text("✕", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                                    }
                                }
                                MorePanel(
                                    rates = rates, rate = rate, onRate = { setSpeed(it) },
                                    subScale = subScale, onSubScale = { v -> ui.put("subScale", if (v == 100) null else v.toString()); restartPlayer() },
                                    subDelay = subDelay, onSubDelay = { d -> shiftSubs(d) },
                                    night = nightMode, onNight = { ui.put("night", if (nightMode) null else "1"); restartPlayer() },
                                    onTop = onTop.value, onOnTop = { onTop.value = !onTop.value }, mini = mini, onMini = { setMini(!mini); picker = null },
                                    info = infoOpen, onInfo = { infoOpen = !infoOpen },
                                    chapters = chapters, current = chapterAt(time), onChapter = { i -> jumpChapter(i - chapterAt(time)) }
                                )
                            }
                        }
                    }

                    if (picker == "audio" || picker == "spu") picker?.let { kind ->
                        val current = pickerCurrent
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xF20E1118))
                                .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(14.dp))
                                .padding(16.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        if (kind == "audio") "Аудиодорожки" else "Субтитры",
                                        color = Color.White,
                                        fontFamily = Onest,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )
                                    GlassIconButton(onClick = { picker = null }, size = 26.dp) {
                                        Text("✕", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                                    }
                                }
                                androidx.compose.foundation.layout.FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (pickerItems.none { it.first != -1 })
                                        Text("Дорожки ещё не определены", color = Color.White.copy(alpha = 0.5f), fontFamily = Onest, fontSize = 13.sp)
                                    pickerItems.forEach { (id, name) ->
                                        val on = id == current
                                        PlayerChip(name, on) {
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
                                            picker = null
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (nextIn >= 0 && index < queue.size - 1) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(bottom = 10.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xCC161B26))
                                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50))
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Следующая: ${queue[index + 1].title}", color = Sm.text, fontFamily = Onest, fontSize = 13.sp, maxLines = 1)
                                Text("через $nextIn с", color = Sm.lamp, fontFamily = Onest, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                GlassIconButton(onClick = { playEntry(index + 1) }, size = 30.dp, tooltip = "Включить сейчас") {
                                    Text("▶", color = Sm.text, fontSize = 11.sp)
                                }
                                GlassIconButton(onClick = { nextIn = -1 }, size = 30.dp, tooltip = "Отмена") {
                                    Text("✕", color = Sm.text, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    // Scrubber row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
                    ) {
                        Text(
                            fmt(hoverAt ?: seeking?.toLong() ?: time),
                            color = if (hoverAt != null) Sm.lamp else Sm.text,
                            fontFamily = Onest,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            modifier = Modifier.width(54.dp)
                        )
                        SeekBar(
                            position = seeking?.toLong() ?: time,
                            length = length,
                            buffered = animBuffered.toLong(),
                            marks = chapters.map { it.first },
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            onScrub = { t -> seeking = t?.toFloat(); poke() },
                            onSeek = { t ->
                                seeking = null
                                time = t
                                lastGood = t
                                lastSeekAt = System.currentTimeMillis()
                                supRef.get()?.seeked()
                                PlayerLog.write("seek to ${t / 1000}s")
                                vlcSeekTo(t)
                            },
                            onHover = { t -> hoverAt = t }
                        )
                        Text(
                            if (length > 0) fmt(length) else "--:--",
                            color = Sm.muted,
                            fontFamily = Onest,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            modifier = Modifier.width(54.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End
                        )
                    }

                    if (mini) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            HeroPlayButton(playing = playing, onClick = { togglePause() })
                            if (index < queue.size - 1) {
                                GlassIconButton(onClick = { playEntry(index + 1) }, tooltip = "Следующая") {
                                    Text("⏭", color = Sm.text, fontSize = 16.sp)
                                }
                            }
                            Text(
                                title,
                                color = Sm.text.copy(alpha = 0.9f),
                                fontFamily = Onest,
                                fontSize = 12.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                            )
                            GlassIconButton(onClick = { setMini(false) }, tooltip = "Обычный режим (O)") {
                                Text("⤢", color = Sm.text, fontSize = 16.sp)
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left group (Next episode, status)
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(buttonSpacing)
                                ) {
                                    if (index < queue.size - 1) {
                                        GlassIconButton(
                                            onClick = { playEntry(index + 1) },
                                            tooltip = "Следующая: ${queue[index + 1].title}",
                                            size = btnSize
                                        ) {
                                            Text("⏭", color = Sm.text, fontSize = if (isCompact) 14.sp else 16.sp)
                                        }
                                    }
                                    status?.let {
                                        Text(it, color = Color.White, fontFamily = Onest, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }

                            // Center Controls (Rewind 10, Hero Play/Pause, Forward 10)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 12.dp)
                            ) {
                                GlassIconButton(
                                    onClick = { seekBy(-10_000L) },
                                    size = if (isCompact) 36.dp else 40.dp
                                ) {
                                    Seek10Icon(forward = false, color = Sm.text)
                                }
                                HeroPlayButton(playing = playing, onClick = { togglePause() }, size = heroSize)
                                GlassIconButton(
                                    onClick = { seekBy(10_000L) },
                                    size = if (isCompact) 36.dp else 40.dp
                                ) {
                                    Seek10Icon(forward = true, color = Sm.text)
                                }
                            }

                            // Right group (Separator, Audio, Subtitles, Settings, Volume capsule, Fullscreen)
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(buttonSpacing, Alignment.End)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .padding(horizontal = 4.dp)
                                            .width(1.dp)
                                            .height(18.dp)
                                            .background(Color(0x33FFFFFF))
                                    )
                                    GlassIconButton(
                                        onClick = { if (picker == "audio") picker = null else openPicker("audio") },
                                        active = (picker == "audio"),
                                        size = btnSize
                                    ) {
                                        Text("♫", color = Color.White, fontSize = if (isCompact) 18.sp else 21.sp)
                                    }
                                    GlassIconButton(
                                        onClick = { if (picker == "spu") picker = null else openPicker("spu") },
                                        active = (picker == "spu"),
                                        size = btnSize
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .border(1.dp, Color.White, RoundedCornerShape(3.dp))
                                                .padding(horizontal = 3.5.dp, vertical = 0.5.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                "CC",
                                                color = Color.White,
                                                fontFamily = Onest,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = if (isCompact) 9.5.sp else 10.5.sp,
                                                letterSpacing = 0.5.sp
                                            )
                                        }
                                    }
                                    GlassIconButton(
                                        onClick = { picker = if (picker == "more") null else "more" },
                                        active = (picker == "more"),
                                        size = btnSize
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("⚙", color = Color.White, fontSize = if (isCompact) 15.sp else 17.sp)
                                            if (rate != 1f && !isVeryNarrow) {
                                                Text(" ${fmtRate(rate)}", color = Color.White, fontFamily = Onest, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                    VolumeGlassCapsule(
                                        volume = volume,
                                        muted = userMuted,
                                        onVolumeChange = {
                                            volume = it
                                            vlc { mp.audio().setVolume(it) }
                                            poke()
                                        },
                                        onMuteToggle = {
                                            userMuted = !userMuted
                                            vlc { mp.audio().setMute(userMuted) }
                                            poke()
                                            flash(if (userMuted) "Без звука" else "Звук: $volume%", "Звук:")
                                        },
                                        verticalOnly = isCompact,
                                        size = btnSize,
                                        wheelTrigger = lastVolumeWheelAt
                                    )
                                    GlassIconButton(
                                        onClick = { toggleFullscreen() },
                                        size = btnSize
                                    ) {
                                        Text(if (fullscreen) "⤡" else "⛶", color = Color.White, fontSize = if (isCompact) 15.sp else 17.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        }
        onDispose { }
    }
}

@Composable
private fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    tooltip: String? = null,
    size: androidx.compose.ui.unit.Dp = 42.dp,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
    content: @Composable () -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val scale = hoverScale(hovered && enabled, pressed && enabled, 1.05f)

    val bg = when {
        !enabled -> Color.Transparent
        active -> Color(0x40FFFFFF)
        hovered -> Color(0x26FFFFFF)
        else -> Color.Transparent
    }
    val border = when {
        active -> Color(0x66FFFFFF)
        else -> Color.Transparent
    }

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.4f }
            .size(size)
            .clip(shape)
            .background(bg)
            .then(if (active) Modifier.border(1.dp, border, shape) else Modifier)
            .hoverClick(src, enabled, onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun HeroPlayButton(
    playing: Boolean,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 48.dp
) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val scale = hoverScale(hovered, pressed, 1.06f)
    val bg = if (hovered) Color(0xFFF2F4F8) else Color.White

    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .hoverClick(src, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (playing) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(4.5.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Color(0xFF111418))
                )
                Box(
                    modifier = Modifier
                        .width(4.5.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(Color(0xFF111418))
                )
            }
        } else {
            Text(
                "▶",
                color = Color(0xFF111418),
                fontSize = (size.value * 0.42f).sp,
                modifier = Modifier.padding(start = 2.dp)
            )
        }
    }
}

@Composable
private fun Seek10Icon(
    forward: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Sm.text
) {
    Box(
        modifier = modifier.size(36.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(26.dp)
                .graphicsLayer {
                    if (forward) scaleX = -1f
                }
        ) {
            val strokeWidth = 1.6.dp.toPx()
            val stroke = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
            // Center of the circular track
            val cx = size.width / 2f
            val cy = size.height / 2f + 0.5.dp.toPx()
            val r = 8.5.dp.toPx()

            // Arc starting at top-right (-77 deg) and sweeping clockwise around the bottom to top-left (257 deg)
            drawArc(
                color = color,
                startAngle = -77f,
                sweepAngle = 334f,
                useCenter = false,
                topLeft = Offset(cx - r, cy - r),
                size = Size(r * 2f, r * 2f),
                style = stroke
            )

            // Crisp arrowhead at top-left pointing left
            val tipX = cx - 5.5.dp.toPx()
            val tipY = cy - r
            val wingW = 3.6.dp.toPx()
            val wingH = 3.2.dp.toPx()

            val arrowPath = Path().apply {
                moveTo(tipX + wingW, tipY - wingH)
                lineTo(tipX, tipY)
                lineTo(tipX + wingW, tipY + wingH)
            }
            drawPath(path = arrowPath, color = color, style = stroke)
        }
        Text(
            "10",
            color = color,
            fontFamily = Onest,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 1.dp)
        )
    }
}

@Composable
private fun VerticalVolumeSlider(
    volume: Int,
    onVolumeChange: (Int) -> Unit,
    onDragStateChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .width(38.dp)
            .height(134.dp),
        shape = RoundedCornerShape(19.dp),
        color = Color(0xF2141824),
        border = BorderStroke(1.dp, Color(0x4DFFFFFF)),
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "$volume%",
                color = if (volume > 100) Sm.lamp else Sm.text,
                fontFamily = Onest,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
            Spacer(Modifier.height(6.dp))
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .width(28.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                onDragStateChange(true)
                                val frac = (1f - (offset.y / size.height)).coerceIn(0f, 1f)
                                onVolumeChange((frac * 150f).toInt())
                            },
                            onDragEnd = { onDragStateChange(false) },
                            onDragCancel = { onDragStateChange(false) },
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                val frac = (1f - (change.position.y / size.height)).coerceIn(0f, 1f)
                                onVolumeChange((frac * 150f).toInt())
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.type == PointerEventType.Press) {
                                    val y = ev.changes.firstOrNull()?.position?.y ?: continue
                                    val frac = (1f - (y / size.height)).coerceIn(0f, 1f)
                                    onVolumeChange((frac * 150f).toInt())
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.BottomCenter
            ) {
                val totalH = maxHeight
                val fillRatio = (volume / 150f).coerceIn(0f, 1f)

                // Background track
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x33FFFFFF))
                )
                // Active track
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(totalH * fillRatio)
                        .align(Alignment.BottomCenter)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White)
                )
                // Thumb circle
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = -(totalH * fillRatio - 5.dp).coerceAtLeast(0.dp))
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
    }
}

@Composable
private fun VolumeGlassCapsule(
    volume: Int,
    muted: Boolean,
    onVolumeChange: (Int) -> Unit,
    onMuteToggle: () -> Unit,
    verticalOnly: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 42.dp,
    wheelTrigger: Long = 0L
) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    var wheelActive by remember { mutableStateOf(false) }

    LaunchedEffect(wheelTrigger) {
        if (wheelTrigger > 0L) {
            wheelActive = true
            delay(1500)
            wheelActive = false
        }
    }

    val icon = when {
        muted || volume == 0 -> "🔇"
        volume < 40 -> "🔈"
        volume < 100 -> "🔉"
        else -> "🔊"
    }

    if (verticalOnly) {
        val showPopup = hovered || dragging || wheelActive
        Box(
            modifier = Modifier.hoverable(src),
            contentAlignment = Alignment.BottomCenter
        ) {
            AnimatedVisibility(
                visible = showPopup,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(180)),
                modifier = Modifier.padding(bottom = (size + 6.dp))
            ) {
                VerticalVolumeSlider(
                    volume = volume,
                    onVolumeChange = onVolumeChange,
                    onDragStateChange = { dragging = it }
                )
            }

            GlassIconButton(
                onClick = onMuteToggle,
                active = muted,
                size = size
            ) {
                Text(icon, fontSize = if (size < 40.dp) 13.sp else 15.sp, color = if (muted) Sm.lamp else Sm.text)
            }
        }
    } else {
        val isExpanded = hovered || dragging || wheelActive
        val width by androidx.compose.animation.core.animateDpAsState(
            targetValue = if (isExpanded) 160.dp else size,
            animationSpec = tween(220, easing = Sm.ease)
        )

        val bg = if (isExpanded) Color(0x99161B26) else if (hovered) Color(0x26FFFFFF) else Color.Transparent
        val border = if (isExpanded) Color(0x33FFFFFF) else Color.Transparent

        val animVol by androidx.compose.animation.core.animateFloatAsState(
            targetValue = volume.toFloat(),
            animationSpec = tween(120)
        )

        Box(
            modifier = Modifier
                .height(size)
                .width(width)
                .clip(RoundedCornerShape(50))
                .background(bg)
                .then(if (isExpanded) Modifier.border(1.dp, border, RoundedCornerShape(50)) else Modifier)
                .hoverable(src)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .size(size - 6.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onMuteToggle)
                        .pointerHoverIcon(PointerIcon.Hand),
                    contentAlignment = Alignment.Center
                ) {
                    Text(icon, fontSize = 15.sp, color = if (muted) Sm.lamp else Sm.text)
                }
                if (width > 60.dp) {
                    Spacer(Modifier.width(2.dp))
                    Slider(
                        value = if (dragging) volume.toFloat() else animVol,
                        valueRange = 0f..150f,
                        onValueChange = {
                            dragging = true
                            onVolumeChange(it.toInt())
                        },
                        onValueChangeFinished = {
                            dragging = false
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color(0x4DFFFFFF)
                        ),
                        modifier = Modifier.weight(1f).height(24.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "$volume%",
                        color = Sm.text.copy(alpha = 0.85f),
                        fontFamily = Onest,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerChip(label: String, on: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val scale = hoverScale(hovered, pressed, 1.04f)
    val bg = when {
        on -> Color.White
        hovered -> Color(0x38FFFFFF)
        else -> Color(0x1F161B26)
    }
    val border = when {
        on -> Color.White
        hovered -> Color(0x66FFFFFF)
        else -> Color(0x33FFFFFF)
    }
    val fg = when {
        on -> Color(0xFF111418)
        else -> Color.White.copy(alpha = 0.9f)
    }
    val weight = if (on) FontWeight.Bold else FontWeight.Medium
    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .hoverClick(src, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, color = fg, fontFamily = Onest, fontWeight = weight, fontSize = 12.5.sp, maxLines = 1)
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
    @Composable fun Label(t: String) = Text(t, color = Color.White.copy(alpha = 0.6f), fontFamily = Onest, fontSize = 12.5.sp, modifier = Modifier.width(92.dp))
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Скорость")
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rates.forEach { r -> PlayerChip(fmtRate(r), kotlin.math.abs(r - rate) < 0.01f) { onRate(r) } }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Субтитры")
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(75, 100, 125, 150, 200).forEach { v -> PlayerChip("$v%", v == subScale) { if (v != subScale) onSubScale(v) } }
                Spacer(Modifier.width(10.dp))
                PlayerChip("−0.1 с", false) { onSubDelay(-100L) }
                Text("${if (subDelay > 0) "+" else ""}${subDelay} мс", color = Color.White, fontFamily = Onest, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 7.dp))
                PlayerChip("+0.1 с", false) { onSubDelay(100L) }
                if (subDelay != 0L) PlayerChip("Сброс", false) { onSubDelay(-subDelay) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Режимы")
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PlayerChip("🌙 Ночной звук", night) { onNight() }
                PlayerChip("📌 Поверх окон (T)", onTop) { onOnTop() }
                PlayerChip("▣ Мини-плеер (O)", mini) { onMini() }
                PlayerChip("ⓘ Информация (I)", info) { onInfo() }
            }
        }
        if (chapters.size > 1) Row(verticalAlignment = Alignment.Top) {
            Label("Главы")
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                chapters.take(40).forEachIndexed { i, (_, name) -> PlayerChip("${i + 1}. ${name.ifBlank { "Глава" }.take(28)}", i == current) { onChapter(i) } }
            }
        }
        Text("Размер субтитров и ночной звук применяются перезапуском потока с того же места.  Клавиши: < > скорость · Shift+[ ] субтитры · PgUp/PgDn главы",
            color = Color.White.copy(alpha = 0.45f), fontFamily = Onest, fontSize = 11.5.sp)
    }
}
