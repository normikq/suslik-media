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
    val demuxCorrupted: Int, val demuxDiscontinuity: Int
)

/** Режим кадра (кнопка «⤢» и щипок). */
enum class VideoScale { BEST_FIT, FIT_SCREEN, FILL, RATIO_16_9 }

/**
 * Что открыть. [url] уже идёт через запас (`BurstProxy.wrap`), как и [audioFiles]; [subtitleFiles] — как есть.
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
    /** Поток кончился. Обрыв сети тоже выглядит так: настоящий ли это конец, решает плеер. */
    fun endReached() {}
    fun failed() {}
    fun videoOutput(count: Int) {}
    fun buffering(percent: Float) {}
    fun timeChanged() {}
    fun lengthChanged() {}
}

/**
 * Всё, что [ru.suslikovanora.media.PlayerActivity] требует от видеодвижка (как `PlayerEngine` на ПК).
 * Реализация: [VlcEngine] (libVLC). Один объект на экран плеера; вызывается только из главного потока.
 * Пока плеера нет ([hasPlayer] = false), чтение возвращает нули, а команды ничего не делают.
 */
interface PlayerEngine {
    /** Где рисуется видео: кладётся в разметку один раз, движок сам подключает к нему каждый новый плеер. */
    val videoView: View
    var listener: EngineListener?

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

    val videoTrackCount: Int
    val videoTrack: EngineVideoTrack?
    /** Выключить/включить видео: перезапуск видеодекодера и вывода (картинка застыла, экран пропал и вернулся). */
    fun setVideoEnabled(enabled: Boolean)
    fun attachVideo()
    fun detachVideo()
    /** Область видео сменила размер: пересчитать картинку. */
    fun refreshVideoSize()
    fun setScale(scale: VideoScale)

    fun stats(): EngineStats?
}
