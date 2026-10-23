package ru.suslikovanora.media.core.audio

import org.json.JSONArray
import org.json.JSONObject
import ru.suslikovanora.media.core.MirrorLink
import java.util.concurrent.ConcurrentHashMap

/*
 * Внешние озвучки с сервера (`/api/custom-audio`, docs/CUSTOM-AUDIO-API.md): аудио из другой раздачи, привязанное
 * к видеофайлу фильма, по подписанной ссылке зеркала. Без сети: приложения запрашивают адреса [CustomAudio] и отдают
 * ответы сюда.
 */

/** Дорожка фильма: [url] — свежая ссылка зеркала, [offsetMs] — задержка для всех, [mine] — её можно менять. */
data class CustomTrack(
    val id: String, val videoPath: String, val audioPath: String, val title: String, val lang: String?,
    val offsetMs: Long, val mine: Boolean, val url: String, val audioMagnetId: Long? = null
) {
    /** Название в меню дорожек: «HDRezka [rus]» (и из «HDRezka [RUS]» — тег языка не повторяется). */
    val menuName: String get() = (lang?.let { l ->
        val tag = " [$l]"
        if (title.endsWith(tag, ignoreCase = true)) title.dropLast(tag.length).trim() else title
    } ?: title).ifBlank { audioPath.substringAfterLast('/') } +
        (lang?.let { " [$it]" } ?: "")
}

/** Пара «файл озвучки → видеофайл фильма» из проверки. */
data class CustomPair(val videoPath: String, val audioPath: String, val voice: String, val season: Int?, val episode: Int?, val offsetMs: Long?)

class CustomCheck(val movieMagnetId: Long?, val attachable: Boolean, val pairs: List<CustomPair>)

object CustomAudio {
    fun tracksPath(magnetId: Long) = "/api/custom-audio/$magnetId"
    fun trackPath(trackId: String) = "/api/custom-audio/$trackId"
    fun offsetPath(trackId: String) = "/api/custom-audio/$trackId/offset"
    const val CHECK_PATH = "/api/custom-audio/check"
    const val ATTACH_PATH = "/api/custom-audio/attach"

    private fun JSONObject.longOrNull(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null
    private fun JSONObject.intOrNull(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
    private fun JSONArray?.objects(): List<JSONObject> = this?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

    /** Загруженная озвучка — только `.mka` (сервер другие не принимает). */
    fun isSupported(path: String): Boolean = path.endsWith(".mka", ignoreCase = true)

    // Описание, обложка, контрольные суммы рядом с озвучкой (как IGNORED_EXT на сервере)
    private val SIDE_FILE = Regex("""\.(txt|nfo|md|jpe?g|png|webp|gif|bmp|sfv|md5|sha1|sha256|url|log|cue)$""", RegexOption.IGNORE_CASE)

    /** Раздача — загруженная озвучка: есть `.mka`, а кроме них только описание и обложки. В медиатеке такую не показывают. */
    fun isVoiceOverOnly(paths: List<String>): Boolean =
        paths.any { isSupported(it) } && paths.all { isSupported(it) || SIDE_FILE.containsMatchIn(it) }

    /** Ответ [tracksPath]; дорожки без ссылки и не `.mka` пропускаются. */
    fun parseTracks(r: JSONObject): List<CustomTrack> = r.optJSONArray("tracks").objects().mapNotNull { t ->
        val url = t.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (!isSupported(t.optString("audioPath"))) return@mapNotNull null
        CustomTrack(t.optString("id"), t.optString("videoPath"), t.optString("audioPath"), t.optString("title"),
            t.optString("lang").takeIf { it.isNotBlank() && it != "und" }, t.optLong("offsetMs"), t.optBoolean("mine"), url,
            t.optLong("audioMagnetId").takeIf { it > 0 })
    }

    /** Дорожки видеофайла по ключу прогресса «magnetId|path». */
    fun forVideo(tracks: List<CustomTrack>, watchKey: String?): List<CustomTrack> {
        if (watchKey?.substringBefore('|')?.toLongOrNull() == null) return emptyList()
        val path = watchKey.substringAfter('|')
        return tracks.filter { it.videoPath == path }
    }

    /** Дорожка по названию в меню плеера (без языка плеер может показать название без « [xx]»). */
    fun byMenuName(tracks: List<CustomTrack>, name: String): CustomTrack? =
        tracks.firstOrNull { it.menuName == name || (it.lang == null && name.substringBeforeLast(" [") == it.menuName) }

    fun checkBody(audioMagnetId: Long, movieMagnetId: Long?): JSONObject =
        JSONObject().put("audioMagnetId", audioMagnetId).apply { movieMagnetId?.let { put("movieMagnetId", it) } }

    fun offsetBody(offsetMs: Long): JSONObject = JSONObject().put("offsetMs", offsetMs)

    fun parseCheck(r: JSONObject): CustomCheck {
        val pairs = r.optJSONArray("pairs").objects().map { p ->
            CustomPair(p.optString("videoPath"), p.optString("audioPath"), p.optString("voice"), p.intOrNull("season"), p.intOrNull("episode"), p.longOrNull("offsetMs"))
        }
        return CustomCheck(r.longOrNull("movieMagnetId"), r.optBoolean("attachable", true), pairs)
    }

    /** Ответ [ATTACH_PATH]: созданные и уже бывшие дорожки (id, audioPath, offsetMs). */
    /** Созданные и уже бывшие дорожки; у бывших — `"existing": true` (их задержку повторная привязка не трогает). */
    fun parseAttached(r: JSONObject): List<JSONObject> =
        r.optJSONArray("created").objects() + r.optJSONArray("existing").objects().map { it.put("existing", true) }

    /** Название и задержка дорожек по подписанной ссылке, `{"<url>": {"title", "offsetMs"}}` (для [CustomAudioIndex]); null, если их нет. */
    fun infoJson(tracks: List<CustomTrack>): String? {
        if (tracks.isEmpty()) return null
        val out = JSONObject()
        tracks.forEach { out.put(it.url, JSONObject().put("title", it.menuName).put("offsetMs", it.offsetMs)) }
        return out.toString()
    }
}

/**
 * Название и задержка загруженных озвучек по ссылке ([CustomAudio.infoJson]): ссылка сравнивается по файлу
 * ([MirrorLink.fileKey]), а адрес через локальный запас запоминается отдельно ([noteWrapped]) — движок видит только его.
 */
class CustomAudioIndex {
    class Info(val title: String, val offsetMs: Long)

    private val byKey = ConcurrentHashMap<String, Info>()
    private val byWrapped = ConcurrentHashMap<String, Info>()

    fun read(json: String?) {
        byKey.clear(); byWrapped.clear()
        val o = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        for (url in o.keys()) {
            val t = o.optJSONObject(url) ?: continue
            byKey[MirrorLink.fileKey(url)] = Info(t.optString("title"), t.optLong("offsetMs"))
        }
    }

    fun of(url: String): Info? = byWrapped[url] ?: byKey[MirrorLink.fileKey(url)]

    /** [wrapped] — это [url] через локальный запас. */
    fun noteWrapped(url: String, wrapped: String) { of(url)?.let { byWrapped[wrapped] = it } }
}
