package ru.suslikovanora.media.core.sync

import org.json.JSONArray
import org.json.JSONObject

/*
 * Синхронизация просмотра по аккаунту Discord: прогресс файлов и выбранные озвучка/субтитры
 * (сервер `website/src/services/sync.js`, `/api/sync`). Без сети: приложения шлют [WatchSync.request] и отдают ответ в
 * [WatchSync.onResponse]. Сервер забывает записи, которыми не пользовались 10 дней; у приложения своё остаётся.
 */

/** Прогресс файла: ключ "magnetId|path" ([ru.suslikovanora.media.core.watchKey]), как в "watch" приложений. */
data class WatchEntry(val key: String, val pos: Long, val len: Long, val at: Long)

/**
 * Выбор дорожек фильма или сериала: ключ [filmKey] ("m<magnetId>") или [ANY] (последний выбор вообще).
 * null — это поле не менялось (сервер оставит прежнее). [sidecar] — файл озвучки рядом с фильмом (только Android).
 */
data class TrackEntry(val key: String, val audio: String?, val spu: String?, val sidecar: String?, val at: Long)

/** Хранилище приложения: откуда брать своё и куда класть пришедшее с других устройств. Вызывается с потока синхронизации. */
interface SyncStore {
    fun watch(key: String): WatchEntry?
    fun putWatch(e: WatchEntry)
    /** Когда на этом устройстве выбрали дорожки [key] (0 — не выбирали или неизвестно). */
    fun trackAt(key: String): Long
    fun putTrack(e: TrackEntry)
    /** Всё своё — для первой выгрузки на аккаунт. */
    fun allWatch(): List<WatchEntry>
    fun allTracks(): List<TrackEntry>
    /**
     * Для кого и до какого места уже синхронизировано: "u_…|мс сервера|мс устройства". Третье поле — до какого времени своё
     * всё выгружено (очередь живёт в памяти, после перезапуска из хранилища берётся то, что новее); у старых курсоров его нет.
     */
    var cursor: String?
}

class WatchSync(private val store: SyncStore) {
    companion object {
        const val PATH = "/api/sync"
        const val ANY = "*"
        const val BATCH = 500 // столько записей одного вида сервер принимает за раз
        fun filmKey(magnetId: Long) = "m$magnetId"
        /** "m42" из "42|Season 1/E01.mkv"; null, если ключ не из библиотеки. */
        fun filmKeyOf(watchKey: String?): String? =
            watchKey?.substringBefore('|', "")?.toLongOrNull()?.let { filmKey(it) }
        /** Синхронизация только по Discord: при входе по общему паролю у всех один пользователь. */
        private val USER_RE = Regex("""u_\d{1,25}""")
        fun eligible(userId: String?) = userId != null && USER_RE.matches(userId)
    }

    private val lock = Any()
    private val pendingWatch = LinkedHashMap<String, WatchEntry>()
    private val pendingTracks = LinkedHashMap<String, TrackEntry>()
    /** Для кого очередь собрана в этом процессе; пока не совпало — [request] добирает её из хранилища. */
    private var loaded: String? = null

    /** Плеер сохранил прогресс. */
    fun noteWatch(e: WatchEntry) = synchronized(lock) { pendingWatch.remove(e.key); pendingWatch[e.key] = e }

    /** Выбрали дорожку: поле, которое не меняли, — null (второй выбор дополняет первый, а не стирает). */
    fun noteTrack(e: TrackEntry) = synchronized(lock) {
        val old = pendingTracks.remove(e.key)
        pendingTracks[e.key] = if (old == null) e
            else TrackEntry(e.key, e.audio ?: old.audio, e.spu ?: old.spu, e.sidecar ?: old.sidecar, maxOf(e.at, old.at))
    }

    val hasPending: Boolean get() = synchronized(lock) { pendingWatch.isNotEmpty() || pendingTracks.isNotEmpty() }

    /** Что ушло в запросе: после ответа убирается из очереди, если за это время не заменилось новым. */
    class Sent internal constructor(
        val user: String, val body: JSONObject, internal val watch: List<WatchEntry>, internal val tracks: List<TrackEntry>,
        internal val startedAt: Long,
    ) {
        /** Остались записи сверх [BATCH]: после ответа сразу следующий запрос. */
        var more = false
            internal set
    }

    /** Разобранный курсор для [user]: since — время сервера (-1 — не синхронизировались), mark — выгружено своё до этого времени. */
    private class Cursor(val since: Long, val mark: Long)

    private fun cursor(user: String): Cursor {
        val p = (store.cursor ?: return Cursor(-1, 0)).split('|')
        if (p[0] != user) return Cursor(-1, 0)
        val since = p.getOrNull(1)?.toLongOrNull() ?: return Cursor(-1, 0)
        return Cursor(since, p.getOrNull(2)?.toLongOrNull() ?: 0L)
    }

    /**
     * Тело POST [PATH] для [user]. Первый раз для этого пользователя на устройстве — всё своё (сервер отбросит
     * старше 10 дней) и since = 0, дальше — только новое с прошлого ответа. После перезапуска приложения очередь
     * добирается из хранилища: всё, что новее [Cursor.mark].
     */
    fun request(user: String): Sent = synchronized(lock) {
        val startedAt = System.currentTimeMillis()
        val c = cursor(user)
        val since = c.since
        if (loaded != user) {
            loaded = user
            if (since < 0) {
                // Очередь заводится заново: в хранилище уже всё, что в неё попадало (и другого пользователя тоже не уйдёт)
                pendingWatch.clear(); pendingTracks.clear()
                // Свежее — первым: больше [BATCH] уйдёт следующими запросами, а давнее сервер всё равно не примет
                for (e in store.allWatch().sortedByDescending { it.at }) pendingWatch[e.key] = e
                for (e in store.allTracks().sortedByDescending { it.at }) pendingTracks[e.key] = e
            } else {
                // Перезапуск: то, что не успело уйти, лежало только в памяти; уже заметное в этом процессе не трогаем
                for (e in store.allWatch().filter { it.at > c.mark }.sortedByDescending { it.at }) pendingWatch.putIfAbsent(e.key, e)
                for (e in store.allTracks().filter { it.at > c.mark }.sortedByDescending { it.at }) pendingTracks.putIfAbsent(e.key, e)
            }
        }
        val w = pendingWatch.values.take(BATCH)
        val t = pendingTracks.values.take(BATCH)
        val body = JSONObject()
            .put("since", since.coerceAtLeast(0))
            .put("watch", JSONArray().apply { w.forEach { put(JSONObject().put("key", it.key).put("pos", it.pos).put("len", it.len).put("at", it.at)) } })
            .put("tracks", JSONArray().apply {
                t.forEach { put(JSONObject().put("key", it.key).put("audio", it.audio ?: JSONObject.NULL).put("spu", it.spu ?: JSONObject.NULL)
                    .put("sidecar", it.sidecar ?: JSONObject.NULL).put("at", it.at)) }
            })
        Sent(user, body, w, t, startedAt).also { it.more = pendingWatch.size > w.size || pendingTracks.size > t.size }
    }

    /** Ответ сервера: пришедшее новее своего ложится в хранилище. Возвращает, сколько записей поменялось. */
    fun onResponse(sent: Sent, res: JSONObject): Int = synchronized(lock) {
        for (e in sent.watch) if (pendingWatch[e.key] == e) pendingWatch.remove(e.key)
        for (e in sent.tracks) if (pendingTracks[e.key] == e) pendingTracks.remove(e.key)
        var changed = 0
        res.optJSONArray("watch")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val e = WatchEntry(o.optString("key"), o.optLong("pos", -1), o.optLong("len", 0), o.optLong("at", 0))
                if ('|' !in e.key || e.pos < 0 || e.at <= 0) continue
                val mine = store.watch(e.key)
                if (mine != null && mine.at >= e.at) continue
                if (pendingWatch[e.key]?.let { it.at >= e.at } == true) continue
                pendingWatch.remove(e.key) // старее пришедшего: не должно уйти обратно
                store.putWatch(e); changed++
            }
        }
        res.optJSONArray("tracks")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                fun str(k: String) = if (o.isNull(k)) null else o.optString(k).ifEmpty { null }
                val e = TrackEntry(o.optString("key"), str("audio"), str("spu"), str("sidecar"), o.optLong("at", 0))
                if (e.key.isEmpty() || e.at <= 0) continue
                if (store.trackAt(e.key) >= e.at) continue
                if (pendingTracks[e.key]?.let { it.at >= e.at } == true) continue
                pendingTracks.remove(e.key) // иначе следующий выбор склеится со старым и затрёт пришедшее
                store.putTrack(e); changed++
            }
        }
        // Курсор — только когда ушло всё: оборвавшаяся выгрузка (больше [BATCH]) при следующем запуске начнётся заново
        val now = res.optLong("now", 0)
        if (now > 0 && !sent.more) {
            val old = cursor(sent.user)
            val drained = pendingWatch.isEmpty() && pendingTracks.isEmpty()
            // Что заметили, пока шёл запрос, осталось в очереди: границу не двигаем, после перезапуска оно подберётся
            val mark = if (drained) sent.startedAt else old.mark
            store.cursor = "${sent.user}|$now|$mark"
        }
        changed
    }
}
