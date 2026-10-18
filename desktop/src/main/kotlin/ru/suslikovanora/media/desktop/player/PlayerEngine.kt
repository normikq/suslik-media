package ru.suslikovanora.media.desktop.player

import java.awt.image.BufferedImage

/** Дорожка (звук или субтитры). [id] — идентификатор движка, -1 = «выключено». */
data class EngineTrack(val id: Int, val description: String, val selected: Boolean)

data class EngineChapter(val timeMs: Long, val title: String)

/** События движка. Приходят из потока движка, не из EDT. Все методы по умолчанию пустые. */
interface EngineListener {
    /** Файл открыт, дорожки известны. */
    fun opened() {}
    /** Пошло воспроизведение (после открытия, паузы, буферизации, перемотки). */
    fun playing() {}
    fun paused() {}
    fun buffering(percent: Float) {}
    fun buffering(active: Boolean) { buffering(if (active) 0f else 100f) }
    fun timeChanged(ms: Long) {}
    /** VLC also reports its position (share of the file) with the time, read on its own thread. */
    fun timeChanged(ms: Long, position: Float) { timeChanged(ms) }
    fun stopped() {}
    fun lengthChanged(ms: Long) {}
    fun seekDone() {}
    fun tracksChanged() {}
    fun finished() {}
    fun failed(message: String) {}
}

/**
 * Всё, что [PlayerView] требует от видеодвижка. Реализации: [MpvEngine] (libmpv) и [VlcPlayer] (vlcj).
 * Все методы потокобезопасны и могут вызываться с любого потока.
 */
interface PlayerEngine {
    /**
     * Открыть [url] с позиции [startMs]. [opts] — опции файла (см. [MpvEngine]); [audioUrls]/[subUrls] — внешние
     * дорожки. Пауза после открытия выключена.
     */
    fun open(url: String, startMs: Long, nightMode: Boolean, audioLang: String?, subLang: String?,
             audioUrls: List<String>, subUrls: List<String>)

    fun setPaused(paused: Boolean)
    fun stop()
    fun seekTo(ms: Long)
    fun seekToFraction(pos: Float)
    fun setRate(rate: Float)

    /** 0..100 (и выше — усиление), как в плеере. */
    fun setVolume(percent: Int)
    fun setMute(mute: Boolean)
    fun setAudioDelayMs(ms: Long)
    fun setSubDelayMs(ms: Long)
    fun setSubScale(percent: Int) {}
    fun setNightMode(enabled: Boolean) {}
    /** Перезапуск видеодекодера и вывода при зависании картинки. */
    fun restartVideoOutput() {}

    val timeMs: Long
    val lengthMs: Long
    /** 0..1, -1 если длительность неизвестна. */
    val fraction: Float
    val isPlayable: Boolean
    val isPlaying: Boolean
    val videoSize: Pair<Int, Int>?
    val fps: Double

    fun audioTracks(): List<EngineTrack>
    fun subtitleTracks(): List<EngineTrack>
    fun setAudioTrack(id: Int)
    fun setSubtitleTrack(id: Int)
    fun chapters(): List<EngineChapter>

    /** Кадр для заставки при переоткрытии; null, если видео нет. */
    fun snapshot(maxWidth: Int): BufferedImage?

    /** Короткая сводка для панели F8 и журнала: кодеки, кадры, потери. */
    fun statsLine(): String

    /** Сводка для сторожа воспроизведения (PlaybackSupervisor). */
    fun supervisorStats(userMutedOrZero: Boolean): ru.suslikovanora.media.core.PlaybackSupervisor.Stats? = null

    fun release()
}

/**
 * Какой движок играет. По умолчанию VLC. mpv включается одним из способов (и только если найден libmpv-2.dll):
 * переменная среды `SUSLIK_ENGINE=mpv`, ключ JVM `-Dsuslik.engine=mpv` или `engine=mpv` в player_ui.properties.
 */
object EngineChoice {
    val useMpv: Boolean
        get() {
            val want = System.getenv("SUSLIK_ENGINE") ?: System.getProperty("suslik.engine")
                ?: ru.suslikovanora.media.desktop.PlaybackSettings.playerEngine()
            return want.equals("mpv", ignoreCase = true) && Mpv.available
        }
}

/** Перекладывает события движка в обработчики плеера, которые появляются позже самого движка. */
class EngineBridge : EngineListener {
    @Volatile var target: EngineListener? = null
    override fun opened() { target?.opened() }
    override fun playing() { target?.playing() }
    override fun paused() { target?.paused() }
    override fun buffering(percent: Float) { target?.buffering(percent) }
    override fun buffering(active: Boolean) { target?.buffering(active) }
    override fun timeChanged(ms: Long) { target?.timeChanged(ms) }
    override fun timeChanged(ms: Long, position: Float) { target?.timeChanged(ms, position) }
    override fun stopped() { target?.stopped() }
    override fun lengthChanged(ms: Long) { target?.lengthChanged(ms) }
    override fun seekDone() { target?.seekDone() }
    override fun tracksChanged() { target?.tracksChanged() }
    override fun finished() { target?.finished() }
    override fun failed(message: String) { target?.failed(message) }
}
