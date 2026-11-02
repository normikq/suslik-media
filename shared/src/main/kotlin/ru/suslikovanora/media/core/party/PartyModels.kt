package ru.suslikovanora.media.core.party

import kotlin.math.abs

/**
 * Синхронизация часов клиента с сервером комнаты совместного просмотра.
 * Смещение берётся по точке с самым коротким кругом (RTT) среди точек за последние [WINDOW_MS]:
 * offset = serverTime - (localTime0 + rtt / 2). Старые точки забываются, поэтому часы, уплывшие за долгий просмотр,
 * догоняются новыми точками (приложение берёт их раз в [RESAMPLE_MS]). Локальные часы лучше монотонные: перевод
 * системного времени (NTP) тогда не сдвигает место комнаты.
 */
class PartyClock {
    private class Sample(val at: Long, val rtt: Long, val offset: Long)
    private val samples = ArrayList<Sample>()

    @Volatile var offsetMs: Long = 0L
        private set

    @Volatile var bestRttMs: Long = Long.MAX_VALUE
        private set

    @Synchronized
    fun addSample(localSendTime: Long, localRecvTime: Long, serverTime: Long) {
        val rtt = (localRecvTime - localSendTime).coerceAtLeast(0L)
        samples += Sample(localRecvTime, rtt, serverTime - (localSendTime + rtt / 2L))
        samples.removeAll { localRecvTime - it.at > WINDOW_MS || it.at > localRecvTime }
        val best = samples.minByOrNull { it.rtt } ?: return
        bestRttMs = best.rtt
        offsetMs = best.offset
    }

    fun serverNow(localNow: Long = System.currentTimeMillis()): Long {
        return localNow + offsetMs
    }

    @Synchronized
    fun reset() {
        samples.clear()
        offsetMs = 0L
        bestRttMs = Long.MAX_VALUE
    }

    companion object {
        /** Точки старше этого не в счёт. */
        const val WINDOW_MS = 10 * 60 * 1000L
        /** Раз во столько приложение, пока в комнате, берёт ещё одну точку (GET комнаты). */
        const val RESAMPLE_MS = 60 * 1000L
    }
}

/** Действие по подстройке рассинхрона видеопотока. */
sealed class DriftAction {
    /** Рассинхрон в пределах нормы (< 300 мс), ничего делать не нужно. */
    data object None : DriftAction()
    /** Плавное замедление (0.95x), локальное видео слегка опережает комнату. */
    data class SlowDown(val rate: Float = 0.95f) : DriftAction()
    /** Плавное ускорение (1.05x), локальное видео слегка отстает от комнаты. */
    data class SpeedUp(val rate: Float = 1.05f) : DriftAction()
    /** Грубая перемотка (рассинхрон > 2 секунд). */
    data class HardSeek(val targetSec: Float) : DriftAction()
}

/**
 * Контроллер дрейфа таймлайна (согласно спецификации docs/DISCORD.md).
 * При |drift| < 0.3 с — не трогает плеер.
 * При 0.3..2.0 с — мягко подстраивает скорость воспроизведения.
 * При > 2.0 с — выполняет явный seek на серверную позицию.
 */
object DriftController {
    const val TOLERANCE_SEC = 0.3f
    const val HARD_SEEK_THRESHOLD_SEC = 2.0f

    fun evaluate(localPosSec: Float, targetPosSec: Float): DriftAction {
        val drift = localPosSec - targetPosSec
        return when {
            abs(drift) < TOLERANCE_SEC -> DriftAction.None
            drift > HARD_SEEK_THRESHOLD_SEC || drift < -HARD_SEEK_THRESHOLD_SEC -> DriftAction.HardSeek(targetPosSec)
            drift > 0f -> DriftAction.SlowDown(0.95f)
            else -> DriftAction.SpeedUp(1.05f)
        }
    }
}
