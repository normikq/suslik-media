package ru.suslikovanora.media.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.net.http.HttpRequest
import kotlinx.coroutines.launch
import ru.suslikovanora.media.core.audio.CustomCheck
import ru.suslikovanora.media.core.audio.CustomTrack
import ru.suslikovanora.media.core.audio.CustomAudio as Shared

/**
 * Внешние озвучки с сервера (`/api/custom-audio`, docs/CUSTOM-AUDIO-API.md): сеть, кэш и раздачи для проверки.
 * Разбор ответов и названия дорожек — в общем модуле ([Shared]).
 */
object CustomAudio {
    /** Ответ сервера с ошибкой проверки: [code] (`FORBIDDEN_FILES`, `AMBIGUOUS`, …), весь JSON — в [body]. */
    class Problem(val code: String, message: String, val body: JSONObject) : Exception(message)

    private val cache = java.util.concurrent.ConcurrentHashMap<Long, List<CustomTrack>>()

    /** Дорожки фильма (раздача [magnetId]). Нет сети или старый сервер — пусто: фильм играет и без них. */
    suspend fun tracks(magnetId: Long, fresh: Boolean = false): List<CustomTrack> {
        if (!fresh) cache[magnetId]?.let { return it }
        val list = runCatching { Shared.parseTracks(Api.get(Shared.tracksPath(magnetId))) }
            .getOrElse { PlayerLog.write("[custom-audio] list of $magnetId failed: ${it.message}"); return cache[magnetId].orEmpty() }
        list.forEach { t -> t.audioMagnetId?.let { attachedSources.add(it) } }
        cache[magnetId] = list
        return list
    }

    /** Дорожки для видеофайла по ключу прогресса «magnetId|path». */
    suspend fun forVideo(watchKey: String?): List<CustomTrack> {
        val id = watchKey?.substringBefore('|')?.toLongOrNull() ?: return emptyList()
        return Shared.forVideo(tracks(id), watchKey)
    }

    suspend fun saveOffset(trackId: String, offsetMs: Long) {
        Api.request(Shared.offsetPath(trackId)).header("Content-Type", "application/json")
            .method("PUT", HttpRequest.BodyPublishers.ofString(Shared.offsetBody(offsetMs).toString())).build()
            .let { Api.send(it) }
        cache.clear()
    }

    suspend fun deleteTrack(trackId: String): JSONObject {
        val res = Api.request(Shared.trackPath(trackId)).method("DELETE", HttpRequest.BodyPublishers.noBody()).build()
            .let { Api.send(it) }
        cache.clear()
        return res
    }

    // Задержка, подобранная для файла озвучки («раздача озвучки|путь»): та же озвучка, привязанная к другой раздаче фильма,
    // получает её в таблице сразу, а не задержку из имени файла (23.10: второй раз задержку приходилось подбирать заново).
    private val offsets = Store.of("audio_offsets")
    fun rememberOffset(audioMagnetId: Long, audioPath: String, ms: Long) = offsets.put("$audioMagnetId|$audioPath", ms.toString())
    fun knownOffset(audioMagnetId: Long, audioPath: String): Long? =
        offsets.get("$audioMagnetId|$audioPath")?.toLongOrNull()
            ?: cache.values.asSequence().flatten().firstOrNull { it.audioMagnetId == audioMagnetId && it.audioPath == audioPath }?.offsetMs

    // Раздачи с озвучкой, загруженные для проверки и предпросмотра, но ещё не привязанные: при уходе с экрана
    // (и при следующем запуске, если приложение закрыли раньше) они удаляются из AllDebrid.
    private val pending = Store.of("audio_pending")
    fun markPending(magnetId: Long) = pending.put(magnetId.toString(), System.currentTimeMillis().toString())
    fun donePending(magnetId: Long) = pending.put(magnetId.toString(), null)

    /** Раздачи, к которым привязаны озвучки (по спискам дорожек, что видел этот запуск): их удалять нельзя. */
    private val attachedSources = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** Удалить из AllDebrid раздачи, которые так и не привязали (в фоне; ошибка — останется в списке до следующего раза). */
    fun dropUnattached(ids: List<Long>) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            for (id in ids) {
                if (id in attachedSources) { PlayerLog.write("[custom-audio] voice-over $id is attached elsewhere, kept"); donePending(id); continue }
                val ok = runCatching { Api.post("/api/magnets/$id/delete") }
                    .recoverCatching { Api.post("/api/magnets/$id/cancel") }.isSuccess
                PlayerLog.write("[custom-audio] unattached voice-over $id ${if (ok) "deleted" else "not deleted"}")
                if (ok) donePending(id)
            }
        }
    }

    private var cleaned = false
    /** Один раз за запуск: то, что осталось с прошлого раза. */
    fun cleanupPending() {
        if (cleaned) return
        cleaned = true
        val ids = pending.keys().mapNotNull { it.toLongOrNull() }
        if (ids.isNotEmpty()) dropUnattached(ids)
    }

    private suspend fun <T> problems(block: suspend () -> T): T = try { block() } catch (e: Api.HttpError) {
        val body = e.body
        throw if (body != null && body.optString("code").isNotBlank()) Problem(body.optString("code"), e.message ?: "", body) else e
    }

    suspend fun check(audioMagnetId: Long, movieMagnetId: Long?): CustomCheck = problems {
        Shared.parseCheck(Api.post(Shared.CHECK_PATH, Shared.checkBody(audioMagnetId, movieMagnetId)))
    }

    /** Проверка по самому `.torrent` (сервер только разбирает его, в AllDebrid не загружает). */
    suspend fun checkTorrent(bytes: ByteArray, movieMagnetId: Long?): CustomCheck = problems {
        val q = movieMagnetId?.let { "?movieMagnetId=$it" } ?: ""
        Shared.parseCheck(Api.send(Api.request("${Shared.CHECK_PATH}$q").header("Content-Type", "application/x-bittorrent")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build()))
    }

    /** Привязать все пары; возвращает созданные и уже бывшие дорожки (id, audioPath, offsetMs). */
    suspend fun attach(audioMagnetId: Long, movieMagnetId: Long?): List<JSONObject> = problems {
        val r = Api.post(Shared.ATTACH_PATH, Shared.checkBody(audioMagnetId, movieMagnetId))
        cache.clear()
        attachedSources.add(audioMagnetId)
        Shared.parseAttached(r)
    }

    /** Понятная строка для ошибки проверки. */
    fun describe(e: Throwable): String {
        if (e !is Problem) return Api.describe(e)
        val b = e.body
        return when (e.code) {
            "FORBIDDEN_FILES" -> {
                val f = b.optJSONArray("forbidden") ?: JSONArray()
                val names = (0 until minOf(f.length(), 5)).joinToString(", ") { f.optJSONObject(it)?.optString("path")?.substringAfterLast('/').orEmpty() }
                tr("audio.zapreshchennye_fayly", "n" to f.length(), "files" to names) + if (b.optBoolean("removed")) " " + tr("audio.razdacha_udalena") else ""
            }
            "EPISODE_MISMATCH" -> tr("audio.net_seriy", "list" to (b.optJSONArray("episodes")?.join(", ")?.replace("\"", "") ?: ""))
            else -> {
                val all = b.optJSONArray("errors")
                if (all != null && all.length() > 1) (0 until all.length()).joinToString("\n") { all.opt(it).let { x -> (x as? JSONObject)?.let { o -> o.optString("message").ifBlank { o.optString("error") } }?.ifBlank { null } ?: x.toString() } }
                else e.message ?: e.code
            }
        }
    }
}
