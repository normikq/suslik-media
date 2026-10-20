package ru.suslikovanora.media.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.suslikovanora.media.core.sync.SyncStore
import ru.suslikovanora.media.core.sync.TrackEntry
import ru.suslikovanora.media.core.sync.WatchEntry
import ru.suslikovanora.media.core.sync.WatchSync

/**
 * Прогресс и выбранные озвучка/субтитры — на аккаунт Discord (core/sync/WatchSync.kt, сервер `/api/sync`): продолжить на
 * телефоне или ТВ с того же места и с той же озвучкой. При входе по общему паролю не работает.
 * Своё уходит при закрытии плеера (во время просмотра — раз в 2 минуты), чужое забирается раз в 5 минут.
 */
object CloudSync {
    /** Пользователь сервера ("u_<id>" — вход через Discord); ставит [Library]. */
    @Volatile var userId: String? = null
        set(value) { field = value; kick() }

    private val watch = Store.of("watch")       // "magnetId|path" -> "pos|len|at" (Library.watchInfo)
    private val ui = Store.of("player_ui")      // "audio:m42", "spu:m42", "audio:*"… (PlayerView), "trackAt:m42" — когда выбрали
    private val state = Store.of("sync")

    private fun parseWatch(key: String, v: String?): WatchEntry? {
        val p = v?.split('|') ?: return null
        if (p.size < 3) return null
        return WatchEntry(key, p[0].toLongOrNull() ?: return null, p[1].toLongOrNull() ?: 0L, p[2].toLongOrNull() ?: 0L)
    }

    private val store = object : SyncStore {
        override fun watch(key: String) = parseWatch(key, watch.get(key))
        override fun putWatch(e: WatchEntry) = watch.put(e.key, "${e.pos}|${e.len}|${e.at}")
        override fun trackAt(key: String) = ui.get("trackAt:$key")?.toLongOrNull() ?: 0L
        override fun putTrack(e: TrackEntry) {
            e.audio?.let { ui.put("audio:${e.key}", it) }
            e.spu?.let { ui.put("spu:${e.key}", it) }
            ui.put("trackAt:${e.key}", e.at.toString())
        }
        override fun allWatch() = watch.keys().mapNotNull { parseWatch(it, watch.get(it)) }
        // Выбор, сделанный до синхронизации, без своего времени: считаем его временем последнего просмотра этого фильма
        override fun allTracks(): List<TrackEntry> {
            val seen = HashMap<String, Long>()
            for (w in allWatch()) WatchSync.filmKeyOf(w.key)?.let { k -> if (w.at > (seen[k] ?: 0L)) seen[k] = w.at }
            val keys = ui.keys().mapNotNull { k ->
                k.removePrefix("audio:").removePrefix("spu:").takeIf { it != k && (it == WatchSync.ANY || it.matches(Regex("""m\d+"""))) }
            }.toSet()
            return keys.mapNotNull { k ->
                val at = trackAt(k).takeIf { it > 0 } ?: if (k == WatchSync.ANY) seen.values.maxOrNull() else seen[k]
                if (at == null || at <= 0) null else TrackEntry(k, ui.get("audio:$k"), ui.get("spu:$k"), null, at)
            }
        }
        override var cursor: String?
            get() = state.get("cursor")
            set(value) = state.put("cursor", value)
    }
    private val sync = WatchSync(store)
    private val kicks = Channel<Unit>(Channel.CONFLATED)

    /** Плеер сохранил прогресс (уже записан в "watch"). */
    fun noteWatch(key: String, pos: Long, len: Long, at: Long) {
        if (WatchSync.eligible(userId)) sync.noteWatch(WatchEntry(key, pos, len, at))
    }

    /** Выбрали дорожку ([filmKey] — "m<id>"; общий выбор "*" отмечается здесь же). Уже записано в "player_ui". */
    fun noteTrack(filmKey: String, audio: String?, spu: String?) {
        val now = System.currentTimeMillis()
        for (k in listOf(filmKey, WatchSync.ANY)) {
            if (k != WatchSync.ANY && !k.matches(Regex("""m\d+"""))) continue // фильм не из библиотеки
            ui.put("trackAt:$k", now.toString())
            if (WatchSync.eligible(userId)) sync.noteTrack(TrackEntry(k, audio, spu, null, now))
        }
    }

    /** Отправить сейчас (закрыли плеер, вошли). */
    fun kick() { kicks.trySend(Unit) }

    /** [onChanged] — пришло новое с другого устройства (вызывается не с потока окна). */
    fun start(scope: CoroutineScope, onChanged: () -> Unit) = scope.launch(Dispatchers.IO) {
        var lastPull = 0L
        var lastPush = 0L
        var kicked = true
        while (isActive) {
            val user = userId
            val now = System.currentTimeMillis()
            // Сразу — по [kick]; своё во время просмотра — раз в 2 минуты; чужое — раз в 5 минут
            val due = kicked || (sync.hasPending && now - lastPush > 2 * 60_000L) || now - lastPull > 5 * 60_000L
            if (due && user != null && WatchSync.eligible(user)) {
                lastPush = now
                try {
                    var changed = 0
                    var rounds = 0
                    do {
                        val sent = sync.request(user)
                        changed += sync.onResponse(sent, Api.post(WatchSync.PATH, sent.body))
                    } while (sent.more && ++rounds < 20 && userId == user)
                    lastPull = now
                    if (changed > 0) {
                        PlayerLog.write("[sync] $changed entries from other devices")
                        onChanged()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    PlayerLog.write("[sync] failed: ${e.message}")
                    lastPull = now - 4 * 60_000L // повтор через минуту, а не через пять
                }
            }
            kicked = withTimeoutOrNull(60_000L) { kicks.receive() } != null
        }
    }
}
