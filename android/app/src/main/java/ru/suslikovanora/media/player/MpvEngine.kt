package ru.suslikovanora.media.player

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView
import dev.jdtech.mpv.MPVLib
import ru.suslikovanora.media.AppLog
import ru.suslikovanora.media.Diag
import ru.suslikovanora.media.R
import ru.suslikovanora.media.core.Chapter
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * [PlayerEngine] на libmpv (`dev.jdtech.mpv:libmpv`, mpv 0.40, FFmpeg с MediaCodec и dav1d), как `MpvEngine` на ПК.
 *
 * Обёртка `MPVLib` статическая: одно ядро mpv на процесс. Оно создаётся при первом [open] и живёт до конца процесса
 * (на холостом ходу без файла — только спящие потоки); [release] лишь останавливает файл. Владелец ядра — последний
 * открывший движок ([Core.owner]): экран плеера, пересозданный системой, забирает ядро у старого, и поздний
 * [release] старого его уже не трогает.
 *
 * Потоки: события mpv приходят в его собственном потоке; там обновляется кэш свойств ([Core]), а события плеера
 * уходят в главный поток. Чтение (время, дорожки) идёт из кэша и не ждёт ядро. Команды из главного потока быстрые
 * (mpv открывает файлы и озвучки в своих потоках); `audio-add` во время просмотра — в отдельном потоке.
 *
 * Видео: свой [SurfaceView]. Пока поверхности нет (фон, блокировка, PiP-переход), `vo=null`: mpv декодирует и
 * играет звук без вывода, а с новой поверхностью снова `vo=gpu` — переподключать видео плееру не нужно
 * ([handlesSurfaceItself]), как у mpv-android.
 */
class MpvEngine(private val context: Context) : PlayerEngine {
    private val surfaceView = SurfaceView(context)
    /** Субтитры в режиме «Напрямую» (Diag.mpvDirect): mpv не рисует их на поверхность, текст выводит этот слой. */
    private val subtitleView = SubtitleView(context)
    private val container = FrameLayout(context).apply {
        addView(surfaceView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(subtitleView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
    override val videoView: View get() = container

    /** Текст текущего субтитра из mpv (`sub-text`, без тегов ASS). Пустая строка — субтитра нет. */
    fun showSubtitle(text: String) {
        if (!Diag.mpvDirect(context)) { subtitleView.setCues(null); return }
        val clean = text.replace("\\N", "\n").replace(Regex("\\{[^}]*\\}"), "").trim()
        subtitleView.setCues(if (clean.isEmpty()) null else listOf(Cue.Builder().setText(clean).build()))
    }
    override var listener: EngineListener? = null
    override val kind = EngineKind.MPV

    private var opened = 0
    override val generation: Int get() = opened
    override val hasPlayer: Boolean get() = Core.owner === this

    private var fastSeek = false
    private var playerVolume = 100
    /** Подключённые файлы озвучки в порядке подключения (адреса как у mpv в `external-filename`). */
    private val attachedAudio = ArrayList<String>()

    // ---- поверхность --------------------------------------------------------------------------
    private var surfaceReady = false
    private var surfaceAttached = false
    private var surfaceW = 0
    private var surfaceH = 0

    init {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                AppLog.write("[mpv] surface created · live ${Core.owner === this@MpvEngine}")
                if (Core.owner === this@MpvEngine) attachSurface()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                surfaceW = width; surfaceH = height
                if (surfaceAttached) MPVLib.setPropertyString("android-surface-size", "${width}x$height")
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                AppLog.write("[mpv] surface destroyed · attached $surfaceAttached")
                detachSurface()
            }
        })
    }

    private fun attachSurface() {
        if (surfaceAttached) return
        if (!surfaceReady || !Core.created) {
            if (Core.owner === this) AppLog.write("[mpv] surface not ready for the film (ready $surfaceReady, core ${Core.created})")
            return
        }
        val s = surfaceView.holder.surface
        if (s == null || !s.isValid) {
            AppLog.write("[mpv] surface invalid (holder surface ${if (s == null) "null" else "not valid"})")
            return
        }
        Core.surfaceOwner?.takeIf { it !== this }?.detachSurface()
        runCatching {
            MPVLib.attachSurface(s)
            MPVLib.setOptionString("force-window", "yes")
            if (surfaceW > 0 && surfaceH > 0) MPVLib.setPropertyString("android-surface-size", "${surfaceW}x$surfaceH")
            val vo = if (Diag.mpvDirect(context)) "mediacodec_embed" else "gpu"
            MPVLib.setPropertyString("vo", vo)
            surfaceAttached = true
            Core.surfaceOwner = this
            AppLog.write("[mpv] surface attached · vo $vo")
        }.onFailure { AppLog.write("[mpv] attach surface failed: $it") }
    }

    /** Должно закончиться до выхода из `surfaceDestroyed`: после него рисовать на поверхность уже нельзя. */
    private fun detachSurface() {
        if (!surfaceAttached) return
        surfaceAttached = false
        if (Core.surfaceOwner === this) Core.surfaceOwner = null
        runCatching {
            MPVLib.setPropertyString("vo", "null")
            MPVLib.setOptionString("force-window", "no")
            MPVLib.detachSurface()
        }
    }

    // ---- открыть / закрыть --------------------------------------------------------------------
    override fun open(media: EngineMedia, scale: VideoScale) {
        Core.ensure(context)
        Core.owner?.takeIf { it !== this }?.let { it.attachedAudio.clear() }
        Core.owner = this
        attachSurface()
        fastSeek = media.fastSeek
        attachedAudio.clear(); attachedAudio += media.audioFiles

        Core.verbose = Diag.mpvVerbose(context)
        MPVLib.setPropertyString("msg-level", if (Core.verbose) "all=v" else "all=warn")
        MPVLib.setPropertyBoolean("pause", true) // как новый плеер: играет после play()
        // Видео, «спрятанное» на паузе экрана (setVideoEnabled(false) → vid=no), остаётся выключенным у нового файла:
        // картинки нет, звук идёт. Новый файл — видео снова auto.
        parkedVid = -1
        MPVLib.setPropertyString("vid", "auto")
        // Отладка (Diag.mpvDirect): декодер пишет прямо в поверхность, как Media3, — без копии в память и GL-вывода.
        MPVLib.setPropertyString("hwdec", if (media.hardwareDecoding) (if (Diag.mpvDirect(context)) "mediacodec" else HWDEC) else "no")
        val secs = num(maxOf(1.0, media.cacheMs / 1000.0))
        MPVLib.setPropertyString("cache-secs", secs)
        MPVLib.setPropertyString("demuxer-readahead-secs", secs)
        MPVLib.setPropertyString("start", if (media.startMs > 0L) num(media.startMs / 1000.0) else "none")
        // External voice-overs / subtitles are part of the file from the start (opened with it, before the first frame),
        // as VLC's slaves were. `-append` adds one item without splitting it at ':' (the path-list separator).
        MPVLib.setPropertyString("audio-files", "")
        MPVLib.setPropertyString("sub-files", "")
        for (u in media.audioFiles) MPVLib.command(arrayOf("change-list", "audio-files", "append", u))
        for (u in media.subtitleFiles) MPVLib.command(arrayOf("change-list", "sub-files", "append", u))
        applyScale(scale)
        MPVLib.setPropertyDouble("volume", playerVolume.toDouble())
        applyAudioDelay(force = true)
        MPVLib.setPropertyDouble("sub-delay", subtitleDelayMs / 1000.0)

        Core.beginOpen()
        opened++
        MPVLib.command(arrayOf("loadfile", media.url, "replace"))
    }

    override fun release() {
        if (Core.owner !== this) { detachSurface(); return }
        Core.owner = null
        Core.silence() // события остановленного файла уже никому
        runCatching {
            MPVLib.command(arrayOf("stop"))
            MPVLib.setPropertyString("audio-files", "")
            MPVLib.setPropertyString("sub-files", "")
        }
        detachSurface()
        attachedAudio.clear()
    }

    // ---- воспроизведение ----------------------------------------------------------------------
    private fun live() = Core.owner === this

    override fun play() { if (live()) MPVLib.setPropertyBoolean("pause", false) }
    override fun pause() { if (live()) MPVLib.setPropertyBoolean("pause", true) }

    override val isPlaying: Boolean get() = live() && Core.loaded && !Core.pause && !Core.eof
    override val isSeekable: Boolean get() = live() && Core.seekable
    override val timeMs: Long get() = if (live()) (Core.timePos * 1000).toLong().coerceAtLeast(0L) else 0L
    override val lengthMs: Long get() = if (live()) (Core.duration * 1000).toLong().coerceAtLeast(0L) else 0L
    override val fraction: Float get() = if (live() && Core.percent >= 0) (Core.percent / 100.0).toFloat() else -1f
    override val rate: Float get() = if (live()) Core.speed.toFloat() else 1f
    override fun setRate(rate: Float) { if (live()) MPVLib.setPropertyDouble("speed", rate.toDouble()) }
    override val volume: Int get() = if (live()) playerVolume else 0
    override fun setPlayerVolume(percent: Int) {
        playerVolume = percent.coerceIn(0, 100)
        if (live()) MPVLib.setPropertyDouble("volume", playerVolume.toDouble())
    }

    override fun seekTo(ms: Long) {
        if (!live()) return
        if (!Core.loaded) { Core.pendingSeekMs = ms; return } // before the file is open: right after it opens
        // ±10 s taps do not need frame accuracy: the keyframe, at once. With an external voice-over — exact (EngineMedia.fastSeek).
        MPVLib.command(arrayOf("seek", num(ms / 1000.0), if (fastSeek) "absolute+keyframes" else "absolute"))
    }
    override fun seekToFraction(fraction: Float) {
        if (live()) MPVLib.command(arrayOf("seek", num(fraction * 100.0), "absolute-percent"))
    }

    // ---- дорожки ------------------------------------------------------------------------------
    override fun audioTracks(): List<EngineTrack> = if (live()) Core.tracks.filter { it.type == "audio" }.map { it.engine() } else emptyList()
    override val audioTrack: Int get() = if (live()) Core.aid else -1
    override fun setAudioTrack(id: Int) {
        if (!live()) return
        Core.aid = id
        MPVLib.setPropertyString("aid", if (id < 0) "no" else id.toString())
    }
    override fun subtitleTracks(): List<EngineTrack> = if (live()) Core.tracks.filter { it.type == "sub" }.map { it.engine() } else emptyList()
    override val subtitleTrack: Int get() = if (live()) Core.sid else -1
    override fun setSubtitleTrack(id: Int) {
        if (!live()) return
        Core.sid = id
        MPVLib.setPropertyString("sid", if (id < 0) "no" else id.toString())
    }
    override fun chapters(): List<Chapter> = if (live()) Core.chapters else emptyList()

    override fun addAudioFile(url: String) {
        if (!live()) return
        attachedAudio += url
        val gen = Core.issued.get()
        // audio-add waits until the file is open (network): not on the main thread
        Core.side.execute { if (Core.issued.get() == gen) runCatching { MPVLib.command(arrayOf("audio-add", url, "select")) } }
    }

    override fun audioFileOf(id: Int, files: Int): Int? {
        val t = Core.tracks.firstOrNull { it.type == "audio" && it.id == id } ?: return null
        if (!t.external) return null
        val i = attachedAudio.indexOf(t.externalFile)
        return if (i in 0 until files) i else super.audioFileOf(id, files)
    }
    override fun audioFileTrack(index: Int, files: Int): Int? {
        val url = attachedAudio.getOrNull(index) ?: return null
        return Core.tracks.firstOrNull { it.type == "audio" && it.external && it.externalFile == url }?.id
    }

    // The sound's delay is the uploaded voice-over's own offset plus what the viewer set; the subtitles' is the viewer's.
    private var voiceDelayMs = 0L
    private var syncDelayMs = 0L
    private var appliedAudioDelayMs = Long.MIN_VALUE
    private var subtitleDelayMs = 0L

    override fun setAudioDelayMs(ms: Long) { voiceDelayMs = ms; applyAudioDelay() }
    override fun setAudioSyncMs(ms: Long) { syncDelayMs = ms; applyAudioDelay() }

    private fun applyAudioDelay(force: Boolean = false) {
        val ms = voiceDelayMs + syncDelayMs
        if (!force && ms == appliedAudioDelayMs) return
        if (!Core.created) return
        appliedAudioDelayMs = ms
        MPVLib.setPropertyDouble("audio-delay", ms / 1000.0) // mpv re-syncs the sound by itself, no seek needed
        if (!force) AppLog.write("[track] audio delay $ms ms (voice-over $voiceDelayMs, set by the viewer $syncDelayMs)")
    }

    override fun setSubtitleDelayMs(ms: Long) {
        if (ms == subtitleDelayMs && Core.created) return
        subtitleDelayMs = ms
        if (!live()) return
        MPVLib.setPropertyDouble("sub-delay", ms / 1000.0)
        AppLog.write("[track] subtitle delay $ms ms")
    }

    // ---- видео --------------------------------------------------------------------------------
    override val videoTrackCount: Int get() = if (live()) Core.tracks.count { it.type == "video" && !it.albumArt } else 0
    override val videoTrack: EngineVideoTrack?
        get() {
            if (!live()) return null
            val t = Core.tracks.firstOrNull { it.type == "video" && it.id == Core.vid }
                ?: Core.tracks.firstOrNull { it.type == "video" && !it.albumArt } ?: return null
            val w = Core.videoW.takeIf { it > 0 } ?: t.width
            val h = Core.videoH.takeIf { it > 0 } ?: t.height
            val fps = Core.containerFps.takeIf { it > 0 } ?: t.fps
            val par = Core.videoPar
            return EngineVideoTrack(
                t.codec, w, h,
                if (fps > 0) (fps * 1000).roundToInt() else 0, if (fps > 0) 1000 else 0,
                if (par > 0) (par * 1000).roundToInt() else 0, if (par > 0) 1000 else 0
            )
        }
    override val hardwareDecoder: String? get() = if (live()) Core.hwdec else null

    private var parkedVid: Int = -1
    override fun setVideoEnabled(enabled: Boolean) {
        if (!live()) return
        if (!enabled) {
            if (Core.vid > 0) parkedVid = Core.vid
            MPVLib.setPropertyString("vid", "no")
        } else {
            MPVLib.setPropertyString("vid", if (parkedVid > 0) parkedVid.toString() else "auto")
        }
    }
    override fun attachVideo() { attachSurface() }
    override fun detachVideo() {}
    override fun refreshVideoSize() {}
    override fun setScale(scale: VideoScale) { if (Core.created) applyScale(scale) }

    private fun applyScale(scale: VideoScale) {
        // VLC's four: fit inside, fill the screen cropping (panscan), stretch, 16:9
        MPVLib.setPropertyString("keepaspect", if (scale == VideoScale.FILL) "no" else "yes")
        MPVLib.setPropertyDouble("panscan", if (scale == VideoScale.FIT_SCREEN) 1.0 else 0.0)
        MPVLib.setPropertyString("video-aspect-override", if (scale == VideoScale.RATIO_16_9) "16:9" else "-1")
    }

    override val handlesSurfaceItself: Boolean get() = true
    /** The end of mpv's demuxer cache (film time); the reserve on disk comes after it (PlayerActivity adds BurstProxy's). */
    override val bufferedPositionMs: Long
        get() = if (live() && Core.loaded && Core.cacheTime > 0) (Core.cacheTime * 1000).toLong() else -1L

    // ---- счётчики (сторож воспроизведения, журнал) ---------------------------------------------
    private var statsFile = -1
    private var lastFrame = -1
    private var lastDrops = 0
    private var shownPictures = 0
    private var lastAudioPts = -1.0
    private var audioPlayedMs = 0L

    /**
     * mpv has no "pictures displayed" counter: frames passed at the playback position (`estimated-frame-number`)
     * minus the dropped ones, summed over steady stretches (a seek jumps the frame number — not counted). Sound:
     * milliseconds of `audio-pts` gone by.
     */
    override fun stats(): EngineStats? {
        if (!live() || !Core.loaded) return null
        if (statsFile != Core.fileSerial) {
            statsFile = Core.fileSerial
            lastFrame = -1; lastDrops = 0; shownPictures = 0; lastAudioPts = -1.0; audioPlayedMs = 0L
        }
        val frame = MPVLib.getPropertyInt("estimated-frame-number") ?: -1
        val drops = (MPVLib.getPropertyInt("frame-drop-count") ?: 0) + (MPVLib.getPropertyInt("decoder-frame-drop-count") ?: 0)
        if (frame >= 0) {
            if (lastFrame >= 0 && frame > lastFrame && frame - lastFrame < 2_000) {
                shownPictures += (frame - lastFrame - (drops - lastDrops).coerceAtLeast(0)).coerceAtLeast(0)
            }
            lastFrame = frame
        }
        lastDrops = drops
        val apts = MPVLib.getPropertyDouble("audio-pts") ?: -1.0
        if (apts >= 0) {
            if (lastAudioPts >= 0 && apts > lastAudioPts && apts - lastAudioPts < 30.0) audioPlayedMs += ((apts - lastAudioPts) * 1000).toLong()
            lastAudioPts = apts
        }
        val audio = audioPlayedMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return EngineStats(
            readBytes = 0, decodedVideo = shownPictures + drops, decodedAudio = audio,
            displayedPictures = shownPictures, lostPictures = drops,
            playedAbuffers = audio, lostAbuffers = 0, demuxCorrupted = 0, demuxDiscontinuity = 0
        )
    }

    override fun diagLine(): String? {
        if (!live() || !Core.loaded) return null
        val cache = MPVLib.getPropertyDouble("demuxer-cache-duration")
        val vo = MPVLib.getPropertyInt("frame-drop-count") ?: 0
        val dec = MPVLib.getPropertyInt("decoder-frame-drop-count") ?: 0
        val av = MPVLib.getPropertyDouble("avsync")
        val sb = StringBuilder("[mpv] hwdec ").append(Core.hwdec ?: "?")
        if (cache != null) sb.append(" · cache %.1f s".format(Locale.US, cache))
        sb.append(" · dropped vo $vo dec $dec")
        if (av != null) sb.append(" · av %+.3f s".format(Locale.US, av))
        if (Core.pausedForCache) sb.append(" · waiting for data ${Core.cacheState}%")
        return sb.toString()
    }

    // ---- ядро mpv на процесс: кэш свойств и события --------------------------------------------
    internal class Track(
        val type: String, val id: Int, val title: String?, val lang: String?, val codec: String?, val channels: Int,
        val forced: Boolean, val external: Boolean, val externalFile: String?, val albumArt: Boolean,
        val width: Int, val height: Int, val fps: Double, val index: Int
    ) {
        /** «Дубляж · AC3 · 5.1 - [Russian]» — the format of VLC's and Media3's names (PlayerTrackSelector.pickTrack). */
        fun engine(): EngineTrack {
            val parts = ArrayList<String>()
            title?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
            codecName(codec)?.let { c -> if (parts.none { it.contains(c, true) }) parts += c }
            if (type == "audio" && channels > 0) parts += if (channels > 2) "${channels - 1}.1" else "$channels.0"
            val base = if (parts.isEmpty()) "Track $index" else parts.joinToString(" · ")
            val langName = lang?.takeIf { it.isNotBlank() && it != "und" }
                ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH) }?.takeIf { it.isNotBlank() }
            return EngineTrack(id, if (langName != null) "$base - [$langName]" else base,
                title?.trim()?.ifEmpty { null }, lang?.takeIf { it.isNotBlank() && it != "und" }, forced)
        }
    }

    internal object Core : MPVLib.EventObserver, MPVLib.LogObserver {
        @Volatile var created = false
        /** Suslik Debug's «Журнал mpv: подробный»: mpv's own messages go to logcat (logcat.log of the journal). */
        @Volatile var verbose = false
        /** Main thread: the engine whose file plays (null — none). */
        @Volatile var owner: MpvEngine? = null
        var surfaceOwner: MpvEngine? = null
        val side = Executors.newSingleThreadExecutor { r -> Thread(r, "mpv-side").apply { isDaemon = true } }
        private val main = Handler(Looper.getMainLooper())

        /** `loadfile`s issued (main thread). The file is current when the mpv thread has seen as many START_FILEs. */
        val issued = AtomicInteger(0)
        private var seen = 0 // mpv thread
        private var silenced = -1

        // cache, written by the mpv thread
        @Volatile var loaded = false
        @Volatile var fileSerial = 0
        @Volatile var timePos = 0.0
        @Volatile var duration = 0.0
        @Volatile var pause = true
        @Volatile var pausedForCache = false
        @Volatile var cacheState = 0
        @Volatile var eof = false
        @Volatile var seekable = false
        @Volatile var speed = 1.0
        @Volatile var percent = -1.0
        @Volatile var aid = -1
        @Volatile var sid = -1
        @Volatile var vid = -1
        @Volatile var hwdec: String? = null
        @Volatile var videoW = 0
        @Volatile var videoH = 0
        @Volatile var videoPar = 0.0
        @Volatile var containerFps = 0.0
        @Volatile var cacheTime = 0.0
        @Volatile var tracks: List<Track> = emptyList()
        @Volatile var chapters: List<Chapter> = emptyList()
        @Volatile var pendingSeekMs: Long? = null
        // mpv thread
        private var playingSent = false
        private var endSent = false
        @Volatile private var bufferingSent = false
        @Volatile private var seeking = false
        private var lastTimePost = 0L
        private var logLines = 0
        private var logVerboseLines = 0

        fun ensure(context: Context) {
            if (created) return
            val app = context.applicationContext
            MPVLib.create(app)
            val dir = File(app.filesDir, "mpv").apply { mkdirs() }
            subtitleFont(app, dir)
            verbose = Diag.mpvVerbose(app)
            val opts = listOf(
                "config" to "yes", "config-dir" to dir.path, // only ~~/subfont.ttf is there (no mpv.conf)
                "gpu-shader-cache-dir" to File(app.cacheDir, "mpv-shaders").apply { mkdirs() }.path,
                "vo" to "null", "force-window" to "no", // until the surface is there (attachSurface)
                "gpu-context" to "android", "opengl-es" to "yes",
                "hwdec" to HWDEC, "hwdec-codecs" to HWDEC_CODECS,
                "ao" to "audiotrack,opensles",
                "idle" to "yes", "keep-open" to "yes",
                "input-default-bindings" to "no", "input-vo-keyboard" to "no", "osc" to "no", "load-scripts" to "no",
                "ytdl" to "no", "terminal" to "no", "osd-level" to "0", "sub-auto" to "no", "audio-file-auto" to "no",
                "save-position-on-quit" to "no", "resume-playback" to "no",
                "tls-verify" to "no",
                "cache" to "yes", "cache-pause" to "yes", "cache-pause-initial" to "yes", "cache-pause-wait" to "1",
                // what the film reads ahead lives in BurstProxy (on disk); mpv's own buffer stays small in memory
                "demuxer-max-bytes" to "48MiB", "demuxer-max-back-bytes" to "16MiB",
                "network-timeout" to "30",
                "stream-lavf-o" to "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5",
                "volume-max" to "100",
                "msg-level" to if (verbose) "all=v" else "all=warn"
            )
            for ((k, v) in opts) {
                val rc = MPVLib.setOptionString(k, v)
                if (rc < 0) AppLog.write("[mpv] option $k=$v rejected ($rc)")
            }
            MPVLib.init()
            MPVLib.addObserver(this)
            MPVLib.addLogObserver(this)
            for ((name, format) in OBSERVED) MPVLib.observeProperty(name, format)
            created = true
            AppLog.write("[mpv] core ready (${Build.SUPPORTED_ABIS.firstOrNull()}, verbose=$verbose)")
        }

        /** libass on Android has no system fonts: Onest (Cyrillic) as mpv's fallback `~~/subfont.ttf`. */
        private fun subtitleFont(app: Context, dir: File) {
            val f = File(dir, "subfont.ttf")
            if (f.length() > 0) return
            runCatching { app.resources.openRawResource(R.font.onest_medium).use { i -> f.outputStream().use { i.copyTo(it) } } }
                .onFailure { AppLog.write("[mpv] subtitle font: $it") }
        }

        /** Main thread, right before `loadfile`. */
        fun beginOpen() {
            pendingSeekMs = null
            pause = true // MpvEngine.open has just set it
            issued.incrementAndGet()
            loaded = false; eof = false; timePos = 0.0; duration = 0.0; percent = -1.0; seekable = false
            tracks = emptyList(); chapters = emptyList(); aid = -1; sid = -1; vid = -1; hwdec = null
            videoW = 0; videoH = 0; videoPar = 0.0; containerFps = 0.0; cacheTime = 0.0
            pausedForCache = false; cacheState = 0
        }

        /** Main thread: nobody listens to the current file any more (it is being stopped). */
        fun silence() { silenced = issued.get() }

        private fun current() = seen == issued.get()

        private fun post(block: EngineListener.() -> Unit) {
            val gen = seen
            main.post {
                if (issued.get() != gen || silenced == gen) return@post
                owner?.listener?.block()
            }
        }

        // ---- mpv thread ----
        override fun event(id: Int) {
            when (id) {
                MPVLib.MPV_EVENT_START_FILE -> {
                    seen++
                    if (!current()) return
                    fileSerial++
                    loaded = false; eof = false; playingSent = false; endSent = false; seeking = false
                    logLines = 0; logVerboseLines = 0
                    bufferingSent = true
                    post { buffering(0f) }
                }
                MPVLib.MPV_EVENT_FILE_LOADED -> {
                    if (!current()) return
                    loaded = true
                    readTracks(); readChapters()
                    pendingSeekMs?.let { ms ->
                        pendingSeekMs = null
                        runCatching { MPVLib.command(arrayOf("seek", num(ms / 1000.0), "absolute")) }
                    }
                    post { tracksChanged(); lengthChanged() }
                }
                MPVLib.MPV_EVENT_SEEK -> {
                    if (!current()) return
                    seeking = true
                    val gen = seen
                    // a seek that waits for the network shows up as buffering (a quick one does not flash the label)
                    main.postDelayed({
                        if (seeking && issued.get() == gen && silenced != gen && !bufferingSent) {
                            bufferingSent = true
                            owner?.listener?.buffering(0f)
                        }
                    }, SEEK_BUFFERING_MS)
                }
                MPVLib.MPV_EVENT_PLAYBACK_RESTART -> {
                    if (!current()) return
                    seeking = false
                    if (bufferingSent && !pausedForCache) { bufferingSent = false; post { buffering(100f) } }
                    if (loaded && !pause && !playingSent) { playingSent = true; post { playing() } }
                }
                MPVLib.MPV_EVENT_VIDEO_RECONFIG -> if (current()) readVideoParams()
                MPVLib.MPV_EVENT_END_FILE -> {
                    if (!current()) return
                    val wasLoaded = loaded
                    loaded = false
                    when {
                        !wasLoaded -> post { failed(); stopped() }
                        !endSent -> { endSent = true; post { endReached(); stopped() } }
                        else -> post { stopped() }
                    }
                }
            }
        }

        override fun eventProperty(property: String) {
            // the property is unavailable now (no file, no track)
            when (property) {
                "current-tracks/audio/id" -> if (current()) { aid = -1; post { audioSelected() } }
                "current-tracks/sub/id" -> if (current()) sid = -1
                "current-tracks/video/id" -> if (current()) vid = -1
                "track-list" -> if (current() && loaded) { readTracks(); post { tracksChanged() } }
                "chapter-list" -> if (current() && loaded) readChapters()
                "hwdec-current" -> if (current()) hwdec = null
                "sub-text" -> main.post { owner?.showSubtitle("") }
            }
        }

        override fun eventProperty(property: String, value: Long) {
            if (!current()) return
            when (property) {
                "cache-buffering-state" -> {
                    cacheState = value.toInt()
                    if (pausedForCache && loaded) { bufferingSent = true; post { buffering(value.toFloat().coerceAtMost(99f)) } }
                }
                "current-tracks/audio/id" -> { aid = value.toInt(); post { audioSelected() } }
                "current-tracks/sub/id" -> sid = value.toInt()
                "current-tracks/video/id" -> vid = value.toInt()
            }
        }

        override fun eventProperty(property: String, value: Double) {
            if (property == "speed") { speed = value; return } // a global setting: whatever file it came with
            if (!current()) return
            when (property) {
                "time-pos" -> {
                    timePos = value
                    if (!loaded) return
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTimePost >= TIME_EVENT_MS) { lastTimePost = now; post { timeChanged() } }
                }
                "duration" -> { if (abs(value - duration) > 0.001) { duration = value; if (loaded) post { lengthChanged() } } }
                "percent-pos" -> percent = value
                "container-fps" -> containerFps = value
                "demuxer-cache-time" -> cacheTime = value
            }
        }

        override fun eventProperty(property: String, value: Boolean) {
            // `pause` is global and set right before `loadfile` (MpvEngine.open): its change can come before the new
            // file's START_FILE — the value is kept anyway, only the event belongs to the current file
            if (property == "pause") pause = value
            if (!current()) return
            when (property) {
                "pause" -> {
                    if (!loaded) return
                    if (value) post { paused() } else { playingSent = true; post { playing() } }
                }
                "paused-for-cache" -> {
                    pausedForCache = value
                    if (!loaded) return
                    if (value) { bufferingSent = true; val s = cacheState.toFloat().coerceAtMost(99f); post { buffering(s) } }
                    else if (bufferingSent && !seeking) { bufferingSent = false; post { buffering(100f) } }
                }
                "eof-reached" -> {
                    eof = value
                    if (value && loaded && !endSent) { endSent = true; post { endReached() } }
                }
                "seekable" -> seekable = value
                "vo-configured" -> post { videoOutput(if (value) 1 else 0) }
            }
        }

        override fun eventProperty(property: String, value: String) {
            if (!current()) return
            when (property) {
                "hwdec-current" -> hwdec = value
                "sub-text" -> {
                    val o = owner
                    if (o != null) main.post { if (owner === o) o.showSubtitle(value) }
                }
            }
        }

        override fun logMessage(prefix: String, level: Int, text: String) {
            val line = text.trimEnd()
            if (line.isEmpty()) return
            if (level <= MPVLib.MPV_LOG_LEVEL_WARN) {
                // the same warning can come with every frame: a few hundred lines per file at most
                if (logLines++ < 300) AppLog.write("[mpv] $prefix: $line")
            } else if (verbose) {
                Log.d("mpv", "[$prefix] $line")
                // Подробный режим: строки уходят и в журнал плеера (иначе видны только в logcat). Не больше 1500 на файл.
                if (logVerboseLines++ < 1500) AppLog.write("[mpv:v] $prefix: $line")
            }
        }

        private fun str(p: String) = runCatching { MPVLib.getPropertyString(p) }.getOrNull()
        private fun int(p: String) = runCatching { MPVLib.getPropertyInt(p) }.getOrNull()
        private fun dbl(p: String) = runCatching { MPVLib.getPropertyDouble(p) }.getOrNull()
        private fun flag(p: String) = runCatching { MPVLib.getPropertyBoolean(p) }.getOrNull() == true

        private fun readTracks() {
            val n = int("track-list/count") ?: 0
            val out = ArrayList<Track>(n)
            val perType = HashMap<String, Int>()
            for (i in 0 until n) {
                val p = "track-list/$i/"
                val type = str(p + "type") ?: continue
                val id = int(p + "id") ?: continue
                val k = (perType[type] ?: 0) + 1
                perType[type] = k
                out += Track(
                    type, id, str(p + "title"), str(p + "lang"), str(p + "codec"), int(p + "demux-channel-count") ?: 0,
                    flag(p + "forced"), flag(p + "external"), str(p + "external-filename"), flag(p + "albumart"),
                    int(p + "demux-w") ?: 0, int(p + "demux-h") ?: 0, dbl(p + "demux-fps") ?: 0.0, k
                )
            }
            tracks = out
        }

        private fun readChapters() {
            val n = int("chapter-list/count") ?: 0
            chapters = (0 until n).mapNotNull { i ->
                val t = dbl("chapter-list/$i/time") ?: return@mapNotNull null
                Chapter((t * 1000).toLong(), str("chapter-list/$i/title")?.takeIf { it.isNotBlank() } ?: "${i + 1}")
            }
        }

        private fun readVideoParams() {
            videoW = int("video-params/w") ?: 0
            videoH = int("video-params/h") ?: 0
            videoPar = dbl("video-params/par") ?: 0.0
        }
    }

    companion object {
        /** libmpv's native libraries are built for Android 8+. */
        val supported: Boolean get() = Build.VERSION.SDK_INT >= 26

        /** Hardware decoding copied back to memory: the picture goes through mpv's renderer (subtitles drawn on it). */
        private const val HWDEC = "mediacodec-copy"
        // No VC-1: Samsung's decoder takes it and shows nothing / flashes black (12.10, 23.10 journals) — FFmpeg's own instead.
        private const val HWDEC_CODECS = "h264,hevc,mpeg2video,mpeg4,vp8,vp9,av1"
        private const val TIME_EVENT_MS = 250L
        private const val SEEK_BUFFERING_MS = 500L

        private val OBSERVED = listOf(
            "time-pos" to MPVLib.MPV_FORMAT_DOUBLE,
            "duration" to MPVLib.MPV_FORMAT_DOUBLE,
            "percent-pos" to MPVLib.MPV_FORMAT_DOUBLE,
            "speed" to MPVLib.MPV_FORMAT_DOUBLE,
            "container-fps" to MPVLib.MPV_FORMAT_DOUBLE,
            "demuxer-cache-time" to MPVLib.MPV_FORMAT_DOUBLE,
            "pause" to MPVLib.MPV_FORMAT_FLAG,
            "paused-for-cache" to MPVLib.MPV_FORMAT_FLAG,
            "eof-reached" to MPVLib.MPV_FORMAT_FLAG,
            "seekable" to MPVLib.MPV_FORMAT_FLAG,
            "vo-configured" to MPVLib.MPV_FORMAT_FLAG,
            "cache-buffering-state" to MPVLib.MPV_FORMAT_INT64,
            "current-tracks/audio/id" to MPVLib.MPV_FORMAT_INT64,
            "current-tracks/sub/id" to MPVLib.MPV_FORMAT_INT64,
            "current-tracks/video/id" to MPVLib.MPV_FORMAT_INT64,
            "hwdec-current" to MPVLib.MPV_FORMAT_STRING,
            "sub-text" to MPVLib.MPV_FORMAT_STRING,
            "track-list" to MPVLib.MPV_FORMAT_NONE,
            "chapter-list" to MPVLib.MPV_FORMAT_NONE
        )

        private fun num(v: Double) = String.format(Locale.US, "%.3f", v)

        private fun codecName(codec: String?): String? = when (codec?.lowercase(Locale.ROOT)) {
            null, "" -> null
            "ac3" -> "AC3"; "eac3" -> "E-AC3"; "dts" -> "DTS"; "truehd" -> "TrueHD"; "mlp" -> "MLP"
            "aac" -> "AAC"; "mp3" -> "MP3"; "mp2" -> "MP2"; "opus" -> "Opus"; "vorbis" -> "Vorbis"; "flac" -> "FLAC"
            "pcm_s16le", "pcm_s24le", "pcm_bluray", "pcm_dvd" -> "PCM"
            "subrip" -> "SRT"; "ass", "ssa" -> "ASS"; "webvtt" -> "WebVTT"; "mov_text" -> "TX3G"
            "hdmv_pgs_subtitle" -> "PGS"; "dvd_subtitle" -> "VobSub"; "dvb_subtitle" -> "DVB"
            else -> codec.uppercase(Locale.ROOT)
        }
    }
}
