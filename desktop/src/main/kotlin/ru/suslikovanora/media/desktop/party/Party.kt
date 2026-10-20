package ru.suslikovanora.media.desktop.party

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.suslikovanora.media.core.party.PartyClock
import ru.suslikovanora.media.desktop.Api
import ru.suslikovanora.media.desktop.PlayerLog
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Совместный просмотр: одна комната на приложение. Сервер (`/api/rooms`) хранит, что и где играет:
 * `playing`, `pos` (с) в момент `anchor_server_ms` и скорость; изменения приходят потоком событий (SSE).
 * Фильм комнаты — ключ просмотра `magnetId|путь` (тот же, что у прогресса в медиатеке): у всех одна общая медиатека.
 */
object Party {
    /** [key]: the server shows a device by a print of its client id (the id itself is its pass: it is not shown to others). */
    data class Member(val key: String, val userId: String, val name: String, val buffering: Boolean = false)
    data class FileRef(val key: String, val title: String)
    /** [waiting]: someone is loading — the room's time stands still until everyone is ready (playing stays the intent). */
    data class State(val playing: Boolean, val pos: Double, val rate: Double, val anchorServerMs: Long, val seq: Long, val waiting: Boolean = false)

    var roomId by mutableStateOf<String?>(null)
        private set
    var hostId by mutableStateOf<String?>(null)
        private set
    /** The creator's device (its key): with the shared password everyone has the id "shared", the device tells the host apart. */
    var hostKey by mutableStateOf<String?>(null)
        private set
    var hostName by mutableStateOf("")
        private set
    var hostOnly by mutableStateOf(false)
        private set
    var file by mutableStateOf<FileRef?>(null)
        private set
    var state by mutableStateOf(State(false, 0.0, 1.0, 0L, 0L))
        private set
    var members by mutableStateOf<List<Member>>(emptyList())
        private set
    var connected by mutableStateOf(false)
        private set
    /** Why the app left the room by itself (shown on the room page until the next create / join). */
    var notice by mutableStateOf<String?>(null)
    /** My user id on the server (the shared password account is "shared"). */
    var myUserId by mutableStateOf<String?>(null)
        private set

    val inRoom get() = roomId != null
    /** Only the host picks the film (the server checks it too); pause and seek — everyone, unless [hostOnly]. */
    val isHost get() = myUserId != null && myUserId == hostId && (hostId != "shared" || hostKey == null || hostKey == myKey)
    /** This member is the room's creator (with the shared password — the creating device only). */
    fun isCreator(m: Member) = m.userId == hostId && (hostId != "shared" || hostKey == null || m.key == hostKey)
    /** Pause, play and seek of the whole room: the host only. A guest's pause stays on their own computer (PartySync). */
    val canControl get() = inRoom && isHost

    /** This computer in the room (the server keeps one entry per client id). */
    val clientId = "pc_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
    /** How the server shows this computer to the others: sha-256 of [clientId], 16 hex (rooms.js clientKey). */
    val myKey: String = java.security.MessageDigest.getInstance("SHA-256").digest(clientId.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(16)
    val clock = PartyClock()

    /** I have just put this film into the room: my player starts the room from its own place (see PartySync). */
    @Volatile var startedByMe: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var stream: Job? = null
    @Volatile private var lines: java.util.stream.Stream<String>? = null

    /** Where the room is now, ms (estimated from the server's anchor with the calibrated clock). */
    fun targetMs(): Long {
        val s = state
        val base = s.pos * 1000.0
        if (!s.playing || s.waiting) return base.toLong()
        val elapsed = (clock.serverNow() - s.anchorServerMs).coerceAtLeast(0L)
        return (base + elapsed * s.rate).toLong()
    }

    suspend fun create(): String? = try {
        val body = JSONObject().put("controls", "all").put("client_id", clientId)
        val res = timed { Api.post("/api/rooms", body) }
        val room = res.getJSONObject("room")
        myUserId = room.optString("host_id").ifBlank { null } // the creator is the host
        enter(room)
        null
    } catch (e: Exception) { Api.describe(e) }

    suspend fun join(code: String): String? {
        val id = code.trim().lowercase().filter { it.isLetterOrDigit() }
        if (id.isEmpty()) return "Введите код комнаты."
        return try {
            val res = timed { Api.post("/api/rooms/$id/join", JSONObject().put("client_id", clientId)) }
            res.optJSONObject("member")?.let { myUserId = it.optString("id").ifBlank { null } }
            enter(res.getJSONObject("room"))
            null
        } catch (e: Exception) { Api.describe(e) }
    }

    fun leave() {
        val id = roomId ?: return
        stopStream()
        roomId = null; file = null; members = emptyList(); hostId = null; hostKey = null; startedByMe = null
        reportedBuffering = false
        state = State(false, 0.0, 1.0, 0L, 0L)
        scope.launch { runCatching { Api.post("/api/rooms/$id/leave", JSONObject().put("client_id", clientId)) } }
    }

    /** Puts a film into the room (paused at the start, as the server does): the host only; my player will start it. */
    fun putFilm(f: FileRef) {
        if (!inRoom || !isHost || file?.key == f.key) return
        file = f
        startedByMe = f.key
        action(JSONObject().put("type", "change_file").put("file", fileJson(f)))
    }

    /** Players showing the room's film here (PartySync). */
    @Volatile var watching = 0

    fun pauseIfNobodyWatches(posMs: Long) {
        scope.launch {
            delay(1500)
            if (watching == 0 && inRoom && state.playing) { PlayerLog.write("[party] host closed the film: pause"); pause(posMs) }
        }
    }

    /** Seeking moves everyone: the host only (a guest's timeline is a «live» line, see PlayerBottomBar). */
    val canSeek get() = inRoom && isHost

    fun play(posMs: Long) = action(JSONObject().put("type", "play").put("pos", posMs / 1000.0))
    fun pause(posMs: Long) = action(JSONObject().put("type", "pause").put("pos", posMs / 1000.0))
    fun seek(posMs: Long) { if (canSeek) action(JSONObject().put("type", "seek").put("pos", posMs / 1000.0)) }

    // One at a time, in order: «put the film» must reach the server before «play from here»
    private val outbox = Channel<Pair<String, JSONObject>>(Channel.UNLIMITED).also { ch ->
        scope.launch {
            for ((id, body) in ch) {
                try {
                    val res = timed { Api.post("/api/rooms/$id/action", body) }
                    // My own change comes back at once (the stream may be a moment later)
                    if (roomId == id) res.optJSONObject("room")?.let { apply(it) }
                } catch (e: Exception) { PlayerLog.write("[party] action ${body.optString("type")} failed: ${Api.describe(e)}") }
            }
        }
    }

    @Volatile private var reportedBuffering = false

    /** «My player is loading» / «ready»: while anyone is loading the room waits (any member may say it). */
    fun reportBuffering(loading: Boolean) {
        val id = roomId ?: return
        if (loading == reportedBuffering) return
        reportedBuffering = loading
        PlayerLog.write("[party] ${if (loading) "loading: the room waits" else "ready"}")
        outbox.trySend(id to JSONObject().put("type", "buffering").put("buffering", loading).put("client_id", clientId))
    }

    private fun action(body: JSONObject) {
        val id = roomId ?: return
        if (!canControl) return
        outbox.trySend(id to body.put("client_id", clientId))
    }

    private fun fileJson(f: FileRef) = JSONObject().put("key", f.key).put("name", f.title)
        .put("ad_id", f.key.substringBefore('|').toLongOrNull() ?: 0L)

    /** A request whose answer carries `server_time`: one more sample for the clock. */
    private suspend fun timed(call: suspend () -> JSONObject): JSONObject {
        val t0 = System.currentTimeMillis()
        val res = call()
        val t1 = System.currentTimeMillis()
        val st = res.optJSONObject("room")?.optJSONObject("state")?.optLong("server_time") ?: 0L
        if (st > 0L) clock.addSample(t0, t1, st)
        return res
    }

    private suspend fun enter(room: JSONObject) {
        stopStream()
        notice = null
        clock.reset()
        state = State(false, 0.0, 1.0, 0L, 0L) // another room's seq must not hide this one's state
        reportedBuffering = false
        apply(room)
        val id = room.getString("id")
        roomId = id
        // A few more clock samples: the shortest round trip wins
        repeat(3) { runCatching { timed { Api.get("/api/rooms/$id") } } }
        startStream(id)
    }

    @Synchronized private fun apply(room: JSONObject) {
        hostId = room.optString("host_id").ifBlank { null }
        hostName = room.optString("host_name")
        hostKey = room.optString("host_key").ifBlank { null }
        hostOnly = room.optString("controls") == "host"
        file = room.optJSONObject("file")?.let { f ->
            val key = f.optString("key")
            if (key.isBlank()) null else FileRef(key, f.optString("name"))
        }
        room.optJSONObject("state")?.let { s ->
            val seq = s.optLong("seq")
            if (seq >= state.seq) {
                state = State(s.optBoolean("playing"), s.optDouble("pos", 0.0), s.optDouble("rate", 1.0), s.optLong("anchor_server_ms"), seq,
                    s.optBoolean("waiting"))
            }
        }
        room.optJSONArray("members")?.let { arr ->
            members = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map(::member)
        }
    }

    private fun member(m: JSONObject) = Member(m.optString("key"), m.optString("id"), m.optString("name").ifBlank { "Гость" },
        m.optBoolean("buffering"))

    private fun startStream(id: String) {
        stream = scope.launch {
            var failures = 0
            while (isActive && roomId == id) {
                try {
                    // No Accept-Encoding: a compressed answer would hold the events back until the buffer fills
                    val req = Api.request("/api/rooms/$id/events?client_id=$clientId")
                        .timeout(Duration.ofHours(12)).header("Accept", "text/event-stream").GET().build()
                    val res = withContext(Dispatchers.IO) { Api.client.send(req, HttpResponse.BodyHandlers.ofLines()) }
                    if (!isActive || roomId != id) { runCatching { res.body().close() }; break }
                    if (res.statusCode() == 404) { PlayerLog.write("[party] room $id is gone"); leave(); notice = "Комната закрыта."; return@launch }
                    // Вход больше не действует (вышли, доступ отозван): повторять бессмысленно
                    if (res.statusCode() == 401 || res.statusCode() == 403) {
                        PlayerLog.write("[party] stream: HTTP ${res.statusCode()}, leaving the room")
                        leave(); notice = "Вы вышли из комнаты: вход больше не действует. Войдите снова."; return@launch
                    }
                    if (res.statusCode() !in 200..299) throw java.io.IOException("HTTP ${res.statusCode()}")
                    connected = true; failures = 0
                    val s = res.body(); lines = s
                    s.use { st -> st.forEach { line -> if (line.startsWith("data:")) onEvent(line.removePrefix("data:").trim()) } }
                } catch (e: Exception) {
                    if (!isActive || roomId != id) break
                    PlayerLog.write("[party] stream: ${e.message}")
                }
                connected = false
                delay((1000L shl failures.coerceAtMost(4)).coerceAtMost(15_000L)); failures++
            }
        }
    }

    private fun stopStream() {
        stream?.cancel(); stream = null
        runCatching { lines?.close() }; lines = null
        connected = false
    }

    private fun onEvent(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("event")) {
            "init" -> {
                msg.optJSONObject("member")?.let { myUserId = it.optString("id").ifBlank { myUserId } }
                msg.optJSONObject("room")?.let { apply(it) }
            }
            "state_changed" -> msg.optJSONObject("data")?.let { apply(it) }
            "member_joined" -> msg.optJSONObject("data")?.optJSONObject("member")?.let { m ->
                val mm = member(m); members = members.filter { it.key != mm.key } + mm
            }
            "member_left" -> msg.optJSONObject("data")?.optString("key")?.let { k -> members = members.filter { it.key != k } }
        }
    }
}
