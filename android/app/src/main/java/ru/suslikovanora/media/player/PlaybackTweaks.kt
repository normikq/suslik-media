package ru.suslikovanora.media.player

import android.content.Context
import android.net.Uri
import ru.suslikovanora.media.core.MirrorLink
import java.util.Locale

/**
 * Что зритель подкрутил в этом файле: сдвиг субтитров и сдвиг звука (мс, + — позже). Помнится на файл (ключ зеркала,
 * [MirrorLink.fileKey]) — как «Продолжить»: в следующий раз фильм открывается уже с настройкой, как в Kodi.
 */
class PlaybackTweaks(context: Context) {
    data class Delays(val subtitleMs: Long, val audioMs: Long) {
        val isZero get() = subtitleMs == 0L && audioMs == 0L
    }

    private val prefs = context.applicationContext.getSharedPreferences("player_tweaks", Context.MODE_PRIVATE)

    fun get(url: String): Delays {
        val v = prefs.getString(MirrorLink.fileKey(url), null)?.split('|') ?: return Delays(0L, 0L)
        return Delays(v.getOrNull(0)?.toLongOrNull() ?: 0L, v.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    fun set(url: String, d: Delays) {
        val key = MirrorLink.fileKey(url)
        val e = prefs.edit()
        if (d.isZero) e.remove(key)
        else {
            val all = prefs.all
            // a few hundred files at most: the oldest go
            if (all.size >= MAX_FILES && !all.containsKey(key)) all.entries
                .sortedBy { (it.value as? String)?.split('|')?.getOrNull(2)?.toLongOrNull() ?: 0L }
                .take(all.size - MAX_FILES + 1).forEach { e.remove(it.key) }
            e.putString(key, "${d.subtitleMs}|${d.audioMs}|${System.currentTimeMillis()}")
        }
        e.apply()
    }

    companion object {
        private const val MAX_FILES = 400
        const val MAX_DELAY_MS = 10_000L
        fun clamp(ms: Long) = ms.coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)

        /** «+0,3 с» / «0 с» / «−1,5 с» for the sheet. */
        fun label(ms: Long): String {
            if (ms == 0L) return "0"
            val s = String.format(Locale.US, "%.1f", kotlin.math.abs(ms) / 1000.0).replace('.', ',')
            return (if (ms > 0) "+" else "−") + s
        }
    }
}

/**
 * Что про картинку известно по имени файла (до открытия). Dolby Vision: libVLC 3.6 его не показывает (играет только
 * основной слой, на профиле 5 — зелёно-фиолетовую картинку), поэтому такие файлы идут в Media3 ([EngineChoice]).
 */
object VideoHints {
    private val DV = Regex("""(?<![a-z0-9])(dolby[ ._-]?vision|dovi|dv)(?![a-z0-9])""", RegexOption.IGNORE_CASE)
    private val HDR = Regex("""(?<![a-z0-9])(hdr(10)?(\+|plus)?|hlg)(?![a-z0-9])""", RegexOption.IGNORE_CASE)

    private fun name(url: String) = Uri.parse(url).lastPathSegment.orEmpty()
    fun isDolbyVision(url: String) = DV.containsMatchIn(name(url))
    fun isHdr(url: String) = isDolbyVision(url) || HDR.containsMatchIn(name(url))
}
