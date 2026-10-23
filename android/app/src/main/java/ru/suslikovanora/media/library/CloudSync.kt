package ru.suslikovanora.media.library

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.suslikovanora.media.AppLog
import ru.suslikovanora.media.core.sync.SyncStore
import ru.suslikovanora.media.core.sync.TrackEntry
import ru.suslikovanora.media.core.sync.WatchEntry
import ru.suslikovanora.media.core.sync.WatchSync
import ru.suslikovanora.media.net.Api

/**
 * Прогресс и выбранные озвучка/субтитры — на аккаунт Discord (core/sync/WatchSync.kt, сервер `/api/sync`): продолжить на
 * ПК или ТВ с того же места и с той же озвучкой. При входе по общему паролю не работает.
 * В сеть — только при возврате на главный экран (закрыли плеер, открыли приложение) и, пока идёт фильм, раз в 2 минуты.
 */
object CloudSync {
    /** Пользователь сервера ("u_<id>" — вход через Discord); ставит [LibraryStore]. */
    @Volatile var userId: String? = null
        set(value) { field = value; kick() }

    /** Растёт, когда пришло новое с другого устройства: плитки перечитывают прогресс. */
    val changes = MutableStateFlow(0)

    private lateinit var watch: SharedPreferences     // "magnetId|path" -> "pos|len|at" (LibraryStore.watchInfo)
    private lateinit var positions: SharedPreferences // "w:magnetId|path" -> мс, с чего продолжит плеер
    private lateinit var ui: SharedPreferences        // "track:audio:m42", "track:spu:*"… (PlayerTrackSelector), "track:at:m42"
    private lateinit var state: SharedPreferences

    private fun parseWatch(key: String, v: Any?): WatchEntry? {
        val p = (v as? String)?.split('|') ?: return null
        if (p.size < 3) return null
        return WatchEntry(key, p[0].toLongOrNull() ?: return null, p[1].toLongOrNull() ?: 0L, p[2].toLongOrNull() ?: 0L)
    }

    private val filmKeyRe = Regex("""m\d+""")

    private val store = object : SyncStore {
        override fun watch(key: String) = parseWatch(key, watch.getString(key, null))
        override fun putWatch(e: WatchEntry) {
            watch.edit().putString(e.key, "${e.pos}|${e.len}|${e.at}").apply()
            // Плеер продолжает по "positions" (PlayerWatchProgress): досмотренное и первые секунды — с начала
            val done = e.len > 0 && e.pos > e.len - 15_000L
            positions.edit().apply { if (done || e.pos < 5_000L) remove("w:${e.key}") else putLong("w:${e.key}", e.pos) }.apply()
        }
        override fun trackAt(key: String) = ui.getLong("track:at:$key", 0L)
        override fun putTrack(e: TrackEntry) {
            ui.edit().apply {
                e.audio?.let { putString("track:audio:${e.key}", it) }
                e.spu?.let { putString("track:spu:${e.key}", it) }
                if (e.key != WatchSync.ANY) e.sidecar?.let { putString("track:sidecar:${e.key}", it) }
                putLong("track:at:${e.key}", e.at)
            }.apply()
        }
        override fun allWatch() = watch.all.mapNotNull { (k, v) -> parseWatch(k, v) }
        // Выбор, сделанный до синхронизации, без своего времени: считаем его временем последнего просмотра этого фильма
        override fun allTracks(): List<TrackEntry> {
            val seen = HashMap<String, Long>()
            for (w in allWatch()) WatchSync.filmKeyOf(w.key)?.let { k -> if (w.at > (seen[k] ?: 0L)) seen[k] = w.at }
            val keys = ui.all.keys.mapNotNull { k ->
                listOf("track:audio:", "track:spu:", "track:sidecar:").firstOrNull { k.startsWith(it) }?.let { k.removePrefix(it) }
                    ?.takeIf { it == WatchSync.ANY || it.matches(filmKeyRe) }
            }.toSet()
            return keys.mapNotNull { k ->
                val at = trackAt(k).takeIf { it > 0 } ?: if (k == WatchSync.ANY) seen.values.maxOrNull() else seen[k]
                if (at == null || at <= 0) null
                else TrackEntry(k, ui.getString("track:audio:$k", null), ui.getString("track:spu:$k", null),
                    ui.getString("track:sidecar:$k", null), at)
            }
        }
        override var cursor: String?
            get() = state.getString("cursor", null)
            set(value) { state.edit().putString("cursor", value).apply() }
    }
    private val sync = WatchSync(store)
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var started = false

    @Synchronized fun init(ctx: Context) {
        if (started) return
        val app = ctx.applicationContext
        watch = app.getSharedPreferences("watch", Context.MODE_PRIVATE)
        positions = app.getSharedPreferences("positions", Context.MODE_PRIVATE)
        ui = app.getSharedPreferences("player_ui", Context.MODE_PRIVATE)
        state = app.getSharedPreferences("sync", Context.MODE_PRIVATE)
        started = true
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { loop() }
    }

    /** Плеер сохранил прогресс (уже записан в "watch"). */
    fun noteWatch(key: String, pos: Long, len: Long, at: Long) {
        if (started && WatchSync.eligible(userId)) sync.noteWatch(WatchEntry(key, pos, len, at))
    }

    /**
     * Выбрали дорожку фильма [filmKey] ("m<id>"; null — фильм не из библиотеки, тогда только общий выбор "*").
     * Поле, которое не меняли, — null. Уже записано в "player_ui".
     */
    fun noteTrack(filmKey: String?, audio: String?, spu: String?, sidecar: String?, alsoAny: Boolean) {
        if (!started) return
        val now = System.currentTimeMillis()
        val keys = listOfNotNull(filmKey?.takeIf { it.matches(filmKeyRe) }, WatchSync.ANY.takeIf { alsoAny })
        ui.edit().apply { keys.forEach { putLong("track:at:$it", now) } }.apply()
        if (!WatchSync.eligible(userId)) return
        for (k in keys) sync.noteTrack(TrackEntry(k, audio, spu, if (k == WatchSync.ANY) null else sidecar, now))
    }

    /** Синхронизировать сейчас (главный экран снова виден, вошли). */
    fun kick() { kicks.trySend(Unit) }

    private suspend fun loop() {
        var lastPull = 0L
        var lastPush = 0L
        var kicked = true
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val user = userId
            val now = System.currentTimeMillis()
            // По [kick]: своё уходит сразу (закрыли плеер), пустой onResume (бывает часто) — не чаще раза в 30 с;
            // своё во время фильма — раз в 2 минуты
            val due = (kicked && (sync.hasPending || now - lastPull > 30_000L)) || (sync.hasPending && now - lastPush > 2 * 60_000L)
            if (due && user != null && WatchSync.eligible(user)) {
                try {
                    var changed = 0
                    var rounds = 0
                    do {
                        val sent = sync.request(user)
                        changed += sync.onResponse(sent, Api.post(WatchSync.PATH, sent.body))
                    } while (sent.more && ++rounds < 20 && userId == user)
                    lastPull = now
                    lastPush = now // только после успеха: при сбое сети очередь не ждёт лишних 2 минут
                    if (changed > 0) {
                        AppLog.write("[sync] $changed entries from other devices")
                        changes.value++
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                    AppLog.write("[sync] failed: ${e.message}")
                }
            }
            kicked = withTimeoutOrNull(60_000L) { kicks.receive() } != null
        }
    }
}
