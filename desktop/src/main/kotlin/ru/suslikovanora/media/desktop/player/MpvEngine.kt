package ru.suslikovanora.media.desktop.player

import com.sun.jna.Pointer
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * [PlayerEngine] на libmpv. Кадры рисует сам mpv (vo=libmpv, render API) в OpenGL-контексте окна — см. [MpvVideoOut]:
 * видео и панели в одной сцене Compose. Аппаратное декодирование, субтитры ASS/SRT и их масштаб — средствами mpv.
 */
class MpvEngine(
    private val listener: EngineListener,
    private val cacheMs: Int = 3000,
    private var subScale: Int = 100
) : PlayerEngine {
    private val api = Mpv.api ?: error("libmpv-2.dll не найден")
    private var h: Pointer? = null
    @Volatile private var video: MpvVideoOut? = null
    @Volatile private var dead = false
    private var events: Thread? = null

    @Volatile private var loaded = false
    @Volatile private var pauseFlag = false
    @Volatile private var coreIdle = true
    @Volatile private var cacheWait = false
    @Volatile private var playingSent = false // `playing` was reported for the file being opened (see time-pos)
    @Volatile private var cacheBufferingPercent = 100f
    @Volatile private var lastTimeEvent = 0L
    @Volatile private var timeSec = 0.0
    @Volatile private var lengthSec = 0.0

    private val pendingProperties = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var nightMode = false

    /** Поток интерфейса, до первого [open]: создаёт ядро и подключает к нему вывод кадров в окно. */
    fun startVideo(out: MpvVideoOut): Boolean {
        val ok = out.start(api, handle())
        if (ok) video = out
        return ok
    }

    @Synchronized private fun handle(): Pointer {
        h?.let { return it }
        val p = api.mpv_create() ?: error("mpv_create")
        fun opt(n: String, v: String) { api.mpv_set_option_string(p, n, v) }

        opt("vo", "libmpv")
        // SUSLIK_MPV_HWDEC — только для проверки (например, no или nvdec-copy), по умолчанию auto-safe
        opt("hwdec", System.getenv("SUSLIK_MPV_HWDEC")?.takeIf { it.isNotBlank() } ?: "auto-safe")
        val mpvLog = ru.suslikovanora.media.desktop.AppDir.file("mpv.log")
        opt("log-file", mpvLog.absolutePath)
        // Every request with its signed address only in Suslik Debug; the normal app keeps warnings and errors.
        opt("msg-level", if (ru.suslikovanora.media.desktop.Diag.on) "all=v" else "all=warn")
        opt("keep-open", "yes")            // в конце файла остаёмся на последнем кадре, `finished` сообщаем сами
        opt("idle", "yes")
        opt("osc", "no"); opt("osd-level", "0"); opt("input-default-bindings", "no")
        opt("input-vo-keyboard", "no"); opt("input-cursor", "no"); opt("cursor-autohide", "no")
        opt("terminal", "no")
        opt("sub-auto", "no"); opt("audio-file-auto", "no")
        opt("cache", "yes")
        val secs = (cacheMs / 1000.0).coerceAtLeast(1.0).toString()
        opt("demuxer-readahead-secs", secs)
        opt("cache-secs", secs)
        opt("cache-pause", "yes")
        opt("cache-pause-initial", "yes")
        opt("cache-pause-wait", "1")
        // No preroll backward seeks on subtitle switch/seek: prevents huge backward range seeks over HTTP
        opt("demuxer-mkv-subtitle-preroll", "no")
        opt("demuxer-mkv-subtitle-preroll-secs", "1")
        // Keep previous keyframes and clusters in memory so track switching and short seeks do not hit network.
        // Native memory outside -Xmx; the long reserve already lives on disk (BurstProxy), so this is kept small.
        opt("demuxer-max-bytes", "96MiB"); opt("demuxer-max-back-bytes", "24MiB")
        opt("gapless-audio", "yes")
        opt("audio-stream-silence", "yes")
        opt("network-timeout", "30")
        opt("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5")
        opt("sub-scale", (subScale / 100.0).toString())
        opt("audio-client-name", "Suslik Media")
        opt("volume-max", "150")
        val rc = api.mpv_initialize(p)
        if (rc < 0) { api.mpv_terminate_destroy(p); error("mpv_initialize: ${api.mpv_error_string(rc)}") }

        api.mpv_observe_property(p, 1, "time-pos", Mpv.FORMAT_DOUBLE)
        api.mpv_observe_property(p, 2, "duration", Mpv.FORMAT_DOUBLE)
        api.mpv_observe_property(p, 3, "pause", Mpv.FORMAT_FLAG)
        api.mpv_observe_property(p, 4, "core-idle", Mpv.FORMAT_FLAG)
        api.mpv_observe_property(p, 5, "paused-for-cache", Mpv.FORMAT_FLAG)
        api.mpv_observe_property(p, 6, "eof-reached", Mpv.FORMAT_FLAG)
        api.mpv_observe_property(p, 7, "cache-buffering-state", Mpv.FORMAT_INT64)
        api.mpv_observe_property(p, 8, "track-list", 0)

        h = p
        events = Thread({ eventLoop(p) }, "mpv-events").apply { isDaemon = true; start() }

        pendingProperties.forEach { (k, v) -> api.mpv_set_property_string(p, k, v) }
        pendingProperties.clear()

        return p
    }

    private fun eventLoop(p: Pointer) {
        while (!dead) {
            val ev = api.mpv_wait_event(p, 0.5)
            if (dead) return
            val id = ev.getInt(0)
            if (id == 0) continue          // MPV_EVENT_NONE
            when (id) {
                Mpv.EV_FILE_LOADED -> {
                    loaded = true
                    val dur = str("duration")?.toDoubleOrNull()
                    if (dur != null && dur > 0.0) {
                        lengthSec = dur
                        listener.lengthChanged((dur * 1000).toLong())
                    }
                    addExternalTracks()
                    listener.opened()
                    maybePlaying()
                }
                Mpv.EV_PLAYBACK_RESTART -> {
                    listener.seekDone()
                    maybePlaying()
                }
                Mpv.EV_PROPERTY_CHANGE -> onProperty(ev)
                Mpv.EV_END_FILE -> {
                    val dataPtr = ev.getPointer(16) // offsetof(mpv_event, data) on 64-bit
                    val reason = dataPtr?.getInt(0) ?: Mpv.END_EOF // offsetof(mpv_event_end_file, reason) == 0
                    if (reason == Mpv.END_EOF) listener.finished()
                    else if (reason == Mpv.END_ERROR) {
                        val err = dataPtr?.getInt(4) ?: 0 // offsetof(mpv_event_end_file, error) == 4
                        listener.failed(api.mpv_error_string(err))
                    }
                }
                Mpv.EV_SHUTDOWN -> {
                    dead = true
                    return
                }
            }
        }
    }

    private fun onProperty(ev: Pointer) {
        val propPtr = ev.getPointer(16) ?: return // mpv_event_property*
        val namePtr = propPtr.getPointer(0) ?: return // const char *name (POINTER at offset 0!)
        val name = namePtr.getString(0, "UTF-8")
        val format = propPtr.getInt(8)
        val data = propPtr.getPointer(16)

        val dbl = if (format == Mpv.FORMAT_DOUBLE && data != null) data.getDouble(0) else null
        val flag = if (format == Mpv.FORMAT_FLAG && data != null) data.getInt(0) != 0 else null
        val int64 = if (format == Mpv.FORMAT_INT64 && data != null) data.getLong(0) else null

        when (name) {
            // Until the new file is loaded, time-pos is the previous file's (events queued before loadfile): taken as the new
            // episode's, it started the player at the old place — the room went to 11:24 of the next episode, and the
            // progress of the new episode got the old place (journal 20.10, 20:43:26)
            "time-pos" -> if (dbl != null && loaded) {
                timeSec = dbl
                // The picture is moving but `playing` never came (a stale event from the previous file during a quick
                // switch of episodes): the panel stayed at «Открываем…» with the play icon. Report it from the clock.
                if (!playingSent && dbl > 0.0) maybePlaying()
                val now = System.currentTimeMillis()
                if (now - lastTimeEvent >= 200L) {
                    lastTimeEvent = now
                    listener.timeChanged((dbl * 1000).toLong())
                }
            }
            "eof-reached" -> if (flag == true && loaded) listener.finished()
            "duration" -> if (dbl != null && dbl > 0.0) {
                lengthSec = dbl
                listener.lengthChanged((dbl * 1000).toLong())
            }
            "pause" -> if (flag != null) {
                pauseFlag = flag
                if (flag) listener.paused() else maybePlaying()
            }
            "core-idle" -> if (flag != null) {
                coreIdle = flag
                if (!flag) maybePlaying()
            }
            "paused-for-cache" -> if (flag != null) {
                cacheWait = flag
                if (flag) {
                    listener.buffering(cacheBufferingPercent.takeIf { it < 100f } ?: 0f)
                } else {
                    cacheBufferingPercent = 100f
                    listener.buffering(100f)
                    maybePlaying()
                }
            }
            "cache-buffering-state" -> if (int64 != null) {
                val pct = int64.toFloat().coerceIn(0f, 100f)
                cacheBufferingPercent = pct
                if (cacheWait) {
                    listener.buffering(pct)
                }
            }
            "track-list" -> listener.tracksChanged()
        }
    }

    /** `playing` — когда ядро действительно крутит кадры: не пауза, не ожидание кэша. */
    private fun maybePlaying() { if (loaded && !pauseFlag && !cacheWait) { playingSent = true; listener.playing() } }

    private fun str(name: String): String? {
        val p = h ?: return null
        val ptr = api.mpv_get_property_string(p, name) ?: return null
        return try { ptr.getString(0, "UTF-8") } finally { api.mpv_free(ptr) }
    }
    private fun set(name: String, value: String) {
        val p = h
        if (p != null) {
            api.mpv_set_property_string(p, name, value)
        } else {
            pendingProperties[name] = value
        }
    }
    private fun cmd(vararg a: String) {
        val p = h ?: return
        api.mpv_command(p, arrayOf<String?>(*a, null))
    }

    override fun setNightMode(enabled: Boolean) {
        nightMode = enabled
        val filter = if (enabled) "lavfi=[acompressor=threshold=0.063:ratio=6:attack=25:release=100:knee=2:makeup=3]" else ""
        set("af", filter)
    }

    override fun open(url: String, startMs: Long, nightMode: Boolean, audioLang: String?, subLang: String?,
                      audioUrls: List<String>, subUrls: List<String>, audioTitles: List<Pair<String, String?>?>) {
        this.nightMode = nightMode
        handle()
        loaded = false; playingSent = false; timeSec = 0.0; lengthSec = 0.0
        cacheWait = false; cacheBufferingPercent = 0f
        set("pause", "no")
        set("start", if (startMs > 0) "%.3f".format(java.util.Locale.ROOT, startMs / 1000.0) else "0")
        set("alang", audioLang ?: "")
        set("slang", subLang ?: "")
        set("af", if (nightMode) "lavfi=[acompressor=threshold=0.063:ratio=6:attack=25:release=100:knee=2:makeup=3]" else "")
        // Subtitle files are fetched now, all at once, while mpv opens the video (see SideFiles.kt).
        val subs = if (subUrls.isEmpty()) null else java.util.concurrent.CompletableFuture.supplyAsync { fetchSubtitles(subUrls, url) }
        synchronized(trackLock) {
            openGen++
            currentOpenUrl = url
            pendingAudio = audioUrls
            pendingAudioTitles = audioUrls.indices.associate { audioUrls[it] to audioTitles.getOrNull(it) }
            pendingSubs = subs
            pendingAudioCount = audioUrls.size
            pendingSubCount = subUrls.size
        }
        cmd("loadfile", url, "replace")
    }

    private val trackLock = Any()
    @Volatile private var openGen = 0
    @Volatile private var currentOpenUrl: String? = null
    @Volatile private var pendingAudio: List<String> = emptyList()
    @Volatile private var pendingAudioTitles: Map<String, Pair<String, String?>?> = emptyMap()
    @Volatile private var pendingSubs: java.util.concurrent.Future<List<SideSub>>? = null
    @Volatile private var pendingAudioCount = 0
    @Volatile private var pendingSubCount = 0
    @Volatile private var sideThread: Thread? = null

    /**
     * По событию file-loaded добавляет внешние дорожки: сначала субтитры (уже скачаны, мгновенно), потом озвучки.
     * Отдельным потоком: mpv открывает каждый файл, пока команда не вернётся, и поток событий раньше стоял всё это время.
     */
    private fun addExternalTracks() {
        val (gen, audio, subs) = synchronized(trackLock) {
            val cur = str("path")
            val target = currentOpenUrl
            val matches = cur == null || target == null || cur == target || cur.trimEnd('/') == target.trimEnd('/') ||
                cur.substringBefore('?') == target.substringBefore('?')
            if (matches) {
                val a = pendingAudio
                val s = pendingSubs
                pendingAudio = emptyList()
                pendingSubs = null
                Triple(openGen, a, s)
            } else {
                pendingAudioCount = 0
                pendingSubCount = 0
                Triple(openGen, emptyList<String>(), null)
            }
        }
        if (audio.isEmpty() && subs == null) {
            pendingAudioCount = 0
            pendingSubCount = 0
            return
        }
        sideThread = Thread({
            val current = { !dead && openGen == gen }
            try {
                val list = runCatching { subs?.get(20, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull().orEmpty()
                pendingSubCount = list.size
                for (s in list) {
                    if (!current()) return@Thread
                    if (s.lang != null) cmd("sub-add", s.target, "auto", s.title, s.lang) else cmd("sub-add", s.target, "auto", s.title)
                    pendingSubCount = (pendingSubCount - 1).coerceAtLeast(0)
                }
                pendingSubCount = 0
                pendingAudioCount = audio.size
                for (a in audio) {
                    if (!current()) return@Thread
                    val t = pendingAudioTitles[a]
                    when {
                        t == null -> cmd("audio-add", a, "auto")
                        t.second != null -> cmd("audio-add", a, "auto", t.first, t.second!!)
                        else -> cmd("audio-add", a, "auto", t.first)
                    }
                    pendingAudioCount = (pendingAudioCount - 1).coerceAtLeast(0)
                }
                pendingAudioCount = 0
            } finally {
                pendingSubCount = 0
                pendingAudioCount = 0
                if (!dead && openGen == gen) {
                    listener.tracksChanged()
                }
            }
        }, "mpv-sidecars").apply { isDaemon = true; start() }
    }

    override fun setPaused(paused: Boolean) {
        pauseFlag = paused
        set("pause", if (paused) "yes" else "no")
    }
    override fun stop() { if (h != null) cmd("stop") }
    override fun seekTo(ms: Long) {
        cacheBufferingPercent = 0f
        cmd("seek", "%.3f".format(java.util.Locale.ROOT, ms / 1000.0), "absolute")
    }
    override fun seekToFraction(pos: Float) {
        cacheBufferingPercent = 0f
        cmd("seek", "%.3f".format(java.util.Locale.ROOT, pos * 100.0), "absolute-percent")
    }
    override fun setRate(rate: Float) = set("speed", rate.toString())
    override fun setVolume(percent: Int) = set("volume", percent.coerceIn(0, 150).toString())
    override fun setMute(mute: Boolean) = set("mute", if (mute) "yes" else "no")
    override fun setAudioDelayMs(ms: Long) = set("audio-delay", (ms / 1000.0).toString())
    override fun setSubDelayMs(ms: Long) = set("sub-delay", (ms / 1000.0).toString())
    override fun setSubScale(percent: Int) {
        subScale = percent
        set("sub-scale", (percent / 100.0).toString())
    }

    override val timeMs: Long get() = (timeSec * 1000).toLong()
    override val lengthMs: Long get() = (lengthSec * 1000).toLong()
    override val fraction: Float get() = if (lengthSec > 0.0) (timeSec / lengthSec).toFloat().coerceIn(0f, 1f) else -1f
    override val isPlayable: Boolean get() = loaded
    override val isPlaying: Boolean get() = loaded && !pauseFlag && !cacheWait

    override val videoSize: Pair<Int, Int>? get() {
        val w = str("video-params/w")?.toIntOrNull() ?: return null
        val h = str("video-params/h")?.toIntOrNull() ?: return null
        return if (w > 0 && h > 0) w to h else null
    }

    override val fps: Double get() = str("container-fps")?.toDoubleOrNull() ?: 0.0

    override fun audioTracks(): List<EngineTrack> = tracks("audio")
    override fun subtitleTracks(): List<EngineTrack> = tracks("sub")

    override fun isTracksLoading(kind: String): Boolean = when (kind) {
        "audio" -> pendingAudioCount > 0 || (!loaded && (pendingAudio.isNotEmpty() || currentOpenUrl != null))
        "spu" -> pendingSubCount > 0 || (!loaded && (pendingSubs != null || currentOpenUrl != null))
        else -> false
    }

    override fun pendingTracksCount(kind: String): Int = when (kind) {
        "audio" -> {
            if (pendingAudioCount > 0) pendingAudioCount
            else if (!loaded) {
                if (pendingAudio.isNotEmpty()) pendingAudio.size + 1 else 6
            } else 0
        }
        "spu" -> {
            if (pendingSubCount > 0) pendingSubCount
            else if (!loaded) 5
            else 0
        }
        else -> 0
    }

    private fun tracks(type: String): List<EngineTrack> {
        val p = h ?: return emptyList()
        val count = str("track-list/count")?.toIntOrNull() ?: return emptyList()
        val list = ArrayList<EngineTrack>()
        for (i in 0 until count) {
            val tType = str("track-list/$i/type") ?: continue
            if (tType != type) continue
            val id = str("track-list/$i/id")?.toIntOrNull() ?: continue
            val title = str("track-list/$i/title")
            val lang = str("track-list/$i/lang")
            val selected = str("track-list/$i/selected") == "yes"
            val desc = buildString {
                if (!title.isNullOrBlank()) append(title)
                if (!lang.isNullOrBlank()) {
                    if (isNotEmpty()) append(" ")
                    append("[$lang]")
                }
                if (isEmpty()) append("$type #$id")
            }
            list += EngineTrack(id, desc, selected)
        }
        return list
    }

    override fun setAudioTrack(id: Int) {
        set("aid", if (id < 0) "no" else id.toString())
    }
    override fun removeAudioTrack(id: Int) { if (id >= 0) cmd("audio-remove", id.toString()) }
    override fun setSubtitleTrack(id: Int) {
        set("sid", if (id < 0) "no" else id.toString())
    }

    override fun chapters(): List<EngineChapter> {
        val p = h ?: return emptyList()
        val count = str("chapter-list/count")?.toIntOrNull() ?: return emptyList()
        val list = ArrayList<EngineChapter>()
        for (i in 0 until count) {
            val time = str("chapter-list/$i/time")?.toDoubleOrNull() ?: continue
            val title = str("chapter-list/$i/title") ?: "Глава ${i + 1}"
            list += EngineChapter((time * 1000).toLong(), title)
        }
        return list
    }

    override fun snapshot(maxWidth: Int): BufferedImage? {
        val p = h ?: return null
        val tmp = File.createTempFile("suslik-snap-", ".jpg").apply { deleteOnExit() }
        val ok = api.mpv_command(p, arrayOf("screenshot-to-file", tmp.absolutePath, "video", null))
        if (ok < 0 || !tmp.isFile || tmp.length() == 0L) { tmp.delete(); return null }
        val img = runCatching { ImageIO.read(tmp) }.getOrNull()
        tmp.delete()
        if (img == null) return null
        if (img.width <= maxWidth) return img
        val h = (img.height * (maxWidth.toDouble() / img.width)).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(maxWidth, h, BufferedImage.TYPE_INT_RGB)
        val g = scaled.createGraphics()
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(img, 0, 0, maxWidth, h, null)
        } finally {
            g.dispose()
        }
        return scaled
    }

    override fun statsLine(): String {
        fun g(n: String) = str(n) ?: "?"
        return "[mpv] ${"%.1f".format(java.util.Locale.ROOT, timeSec)}/${lengthSec.toInt()}s " +
            "video=${g("video-codec")} hw=${g("hwdec-current")} vo=${g("current-vo")} ${g("video-params/w")}x${g("video-params/h")} " +
            "fmt=${g("video-params/hw-pixelformat").takeIf { it != "?" } ?: g("video-params/pixelformat")} ${g("video-params/gamma")}/${g("video-params/primaries")} " +
            "audio=${g("audio-codec-name")} drop=${g("frame-drop-count")} vo-drop=${g("vo-delayed-frame-count")} " +
            "cache=${g("demuxer-cache-duration")}s"
    }

    override fun restartVideoOutput() {
        val vid = str("vid") ?: "auto"
        set("vid", "no")
        set("vid", vid)
        cmd("seek", "0", "relative", "exact")
    }

    override fun supervisorStats(userMutedOrZero: Boolean): ru.suslikovanora.media.core.PlaybackSupervisor.Stats? {
        if (!loaded) return null
        val aTracks = audioTracks()
        val curAudio = aTracks.firstOrNull { it.selected }
        val vCount = if (videoSize != null) 1 else 0

        // mpv has no counter of pictures really shown: `estimated-frame-number` is the clock times fps, so it grew
        // with a frozen picture too and the check could never fire. Pictures are reported as 0, which turns the
        // supervisor's frozen / choppy checks off for mpv (sound and a stuck clock are still watched).

        // Реальный прогресс аудио: audio-pts перестает расти при зависании аудиовывода
        val aPts = str("audio-pts")?.toDoubleOrNull()
        val aTime = if (aPts != null) (aPts * 1000).toLong() else timeMs
        val aPlayed = if (curAudio != null && curAudio.id != -1) aTime else 0L

        return ru.suslikovanora.media.core.PlaybackSupervisor.Stats(
            timeMs = timeMs,
            audioPlayed = aPlayed,
            audioDecoded = aPlayed,
            pictures = 0L,
            expectedFps = fps,
            audioTracks = aTracks.size,
            audioSelected = curAudio != null && curAudio.id != -1,
            videoTracks = vCount,
            userMuted = userMutedOrZero
        )
    }

    @Synchronized override fun release() {
        if (dead) return
        dead = true

        val p = h ?: return
        // Render context освобождается на потоке интерфейса (там его GL-контекст) и обязательно до уничтожения ядра
        // Живой render context при mpv_terminate_destroy — mpv роняет весь процесс (abort, 0xC0000409): тогда ядро
        // только останавливаем и оставляем как есть (при выходе процесс всё равно завершится)
        val canDestroy = video?.let { v ->
            val edt = javax.swing.SwingUtilities.isEventDispatchThread()
            if (edt) v.dispose()
            v.awaitFreed(if (edt) 0 else 3000).also {
                if (!it) ru.suslikovanora.media.desktop.PlayerLog.write("[video] render context not freed: mpv core is not destroyed")
            }
        } ?: true
        h = null
        runCatching { api.mpv_command(p, arrayOf("quit", "0", null)) }
        runCatching { api.mpv_wakeup(p) }
        val evThread = events
        events = null
        if (evThread != null && evThread != Thread.currentThread()) {
            runCatching { evThread.join(1500) }
            if (evThread.isAlive) {
                evThread.interrupt()
                runCatching { evThread.join(500) }
            }
        }
        // A track being added holds a call into the core: it must return before the core is destroyed.
        val side = sideThread
        if (side != null && side != Thread.currentThread()) runCatching { side.join(1500) }
        if (!canDestroy) return
        if (evThread?.isAlive == true || side?.isAlive == true) {
            Thread({
                runCatching { evThread?.join() }
                runCatching { side?.join() }
                runCatching { api.mpv_terminate_destroy(p) }
            }, "mpv-destroy-deferred").apply { isDaemon = true; start() }
        } else {
            runCatching { api.mpv_terminate_destroy(p) }
        }
    }
}
