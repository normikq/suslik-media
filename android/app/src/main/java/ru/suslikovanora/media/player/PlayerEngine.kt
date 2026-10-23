package ru.suslikovanora.media.player

import android.view.View

/** Дорожка (звук или субтитры). [id] — идентификатор движка, -1 = «выключено». */
data class EngineTrack(val id: Int, val name: String)

/** Текущая видеодорожка: кодек, размер, частота кадров и форма пикселя (0, если неизвестно). */
data class EngineVideoTrack(
    val codec: String?, val width: Int, val height: Int,
    val frameRateNum: Int, val frameRateDen: Int, val sarNum: Int, val sarDen: Int
)

/** Счётчики открытого файла с момента открытия (журнал Suslik Debug, сторож воспроизведения). */
data class EngineStats(
    val readBytes: Int, val decodedVideo: Int, val decodedAudio: Int,
    val displayedPictures: Int, val lostPictures: Int, val playedAbuffers: Int, val lostAbuffers: Int,
    val demuxCorrupted: Int, val demuxDiscontinuity: Int,
    /** Media3: pictures decoded but not shown on purpose (before the target of an exact seek, or too late to show). */
    val skippedPictures: Int = 0
)

/** Какой движок играет: libVLC (через запас BurstProxy) или Media3 (ExoPlayer, тоже через запас BurstProxy). */
enum class EngineKind(val tag: String) { VLC("vlc"), MEDIA3("media3") }

/** Режим кадра (кнопка «⤢» и щипок). */
enum class VideoScale { BEST_FIT, FIT_SCREEN, FILL, RATIO_16_9 }

/**
 * Что открыть. [url] и [audioFiles] уже идут через запас (`BurstProxy.wrap`) — у обоих движков (без запаса — адреса зеркала);
 * [subtitleFiles] — как есть.
 * [fastSeek]: перемотка к ближайшему ключевому кадру (без внешней озвучки).
 */
data class EngineMedia(
    val url: String,
    val startMs: Long,
    val hardwareDecoding: Boolean,
    val cacheMs: Int,
    val fastSeek: Boolean,
    val audioFiles: List<String>,
    val subtitleFiles: List<String>
)

/**
 * События движка. Приходят в главном потоке и только от текущего плеера: события плеера, который уже заменён
 * новым [PlayerEngine.open], отбрасываются.
 */
interface EngineListener {
    fun playing() {}
    fun paused() {}
    fun stopped() {}
    /** Появилась новая дорожка (внешняя озвучка приходит чуть позже). */
    fun tracksChanged() {}
    /** Сменилась текущая звуковая дорожка (VLC сам включает файл озвучки уже после [tracksChanged]). */
    fun audioSelected() {}
    /** Поток кончился. Обрыв сети тоже выглядит так: настоящий ли это конец, решает плеер. */
    fun endReached() {}
    fun failed() {}
    fun videoOutput(count: Int) {}
    fun buffering(percent: Float) {}
    fun timeChanged() {}
    fun lengthChanged() {}
    /**
     * Этот движок не может сыграть файл (кодек, контейнер, ошибка декодера; Media3 при неподдерживаемом звуке играет
     * молча, поэтому это видно только по дорожкам). Плеер переоткрывает файл в другом движке с того же места.
     */
    fun unsupported(reason: String) {}
}

/**
 * Всё, что [ru.suslikovanora.media.PlayerActivity] требует от видеодвижка (как `PlayerEngine` на ПК).
 * Реализации: [VlcEngine] (libVLC), [Media3Engine] (ExoPlayer); [HybridEngine] переключает их для каждого файла. Один объект на экран плеера; вызывается только из главного потока.
 * Пока плеера нет ([hasPlayer] = false), чтение возвращает нули, а команды ничего не делают.
 */
interface PlayerEngine {
    /** Где рисуется видео: кладётся в разметку один раз, движок сам подключает к нему каждый новый плеер. */
    val videoView: View
    var listener: EngineListener?
    val kind: EngineKind

    /** Плеер открыт (между первым [open] и [release]). */
    val hasPlayer: Boolean
    /** Растёт с каждым [open]: отличает плеер от того, что его заменил. */
    val generation: Int

    /**
     * Открывает [media] в новом плеере. Прежний плеер (переподключение) сразу отключается от событий и экрана,
     * а закрывается в фоне при следующем [play] или [release]: на мёртвом сокете его остановка может висеть.
     * Бросает исключение, если открыть не удалось.
     */
    fun open(media: EngineMedia, scale: VideoScale)
    /** Закрывает всё в фоне, строго по порядку. */
    fun release()

    fun play()
    fun pause()

    val isPlaying: Boolean
    val isSeekable: Boolean
    val timeMs: Long
    val lengthMs: Long
    /** Доля файла 0..1 (у движка — -1, если неизвестна). */
    val fraction: Float
    val rate: Float
    /** Playback speed (the shared room evens out a small drift with 0.95–1.05×). */
    fun setRate(rate: Float)
    val volume: Int
    fun seekTo(ms: Long)
    fun seekToFraction(fraction: Float)

    fun audioTracks(): List<EngineTrack>
    val audioTrack: Int
    fun setAudioTrack(id: Int)
    fun subtitleTracks(): List<EngineTrack>
    val subtitleTrack: Int
    fun setSubtitleTrack(id: Int)
    /** Внешняя озвучка, выбранная в меню во время просмотра; сразу становится текущей дорожкой. */
    fun addAudioFile(url: String)
    /**
     * Из какого подключённого файла озвучки дорожка [id]: номер файла в порядке подключения (их [files]) или null — дорожка фильма.
     * VLC: дорожки файлов идут после дорожек фильма в порядке подключения.
     */
    fun audioFileOf(id: Int, files: Int): Int? {
        if (files <= 0) return null
        val tracks = audioTracks().filter { it.id != -1 }
        val base = tracks.size - files
        val idx = tracks.indexOfFirst { it.id == id }
        return if (base >= 0 && idx >= base) idx - base else null
    }
    /** Дорожка [index]-го подключённого файла озвучки (см. [audioFileOf]) или null, если её ещё нет. */
    fun audioFileTrack(index: Int, files: Int): Int? {
        val tracks = audioTracks().filter { it.id != -1 }
        val base = tracks.size - files
        return if (base >= 1 && index in 0 until files) tracks[base + index].id else null
    }
    /** Задержка звука (загруженная озвучка со сдвигом). Media3 сдвигает сам файл озвучки ([CustomAudioInfo]), здесь — только VLC. */
    fun setAudioDelayMs(ms: Long) {}

    val videoTrackCount: Int
    val videoTrack: EngineVideoTrack?
    /** Выключить/включить видео: перезапуск видеодекодера и вывода (картинка застыла, экран пропал и вернулся). */
    fun setVideoEnabled(enabled: Boolean)
    /**
     * Картинка застыла: новый видеодекодер. [seekMs] — с опорного кадра в этом месте (его перемотку делает экран
     * плеера сразу после этого вызова), null — без перемотки, со следующего опорного кадра.
     */
    fun restartVideo(seekMs: Long?) { setVideoEnabled(false); setVideoEnabled(true) }
    fun attachVideo()
    fun detachVideo()
    /** Область видео сменила размер: пересчитать картинку. */
    fun refreshVideoSize()
    fun setScale(scale: VideoScale)

    fun stats(): EngineStats?

    /** Сколько файла уже загружено вперёд (мс позиции), если движок знает это сам; -1 — нет (VLC: см. BurstProxy). */
    val bufferedPositionMs: Long get() = -1L
    /** Средний битрейт файла (размер ÷ длительность), если движок знает размер; null — нет (VLC: BurstProxy). */
    val bitrateMbit: Double? get() = null
    /** Движок сам переживает смену поверхности (PiP, блокировка): плееру не нужно переподключать видео. */
    val handlesSurfaceItself: Boolean get() = false
    /** Строка для журнала Suslik Debug (запас, оценка сети, декодеры). */
    fun diagLine(): String? = null
}
