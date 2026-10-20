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
import ru.suslikovanora.media.core.party.PartyEvent
import ru.suslikovanora.media.core.party.PartyFileRef
import ru.suslikovanora.media.core.party.PartyMember
import ru.suslikovanora.media.core.party.PartyProtocol
import ru.suslikovanora.media.core.party.PartyRoom
import ru.suslikovanora.media.core.party.RoomUpdate
import ru.suslikovanora.media.desktop.Api
import ru.suslikovanora.media.desktop.PlayerLog
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Совместный просмотр: одна комната на приложение. Сервер (`/api/rooms`) хранит, что и где играет:
 * `playing`, `pos` (с) в момент `anchor_server_ms` и скорость; изменения приходят потоком событий (SSE).
 * Фильм комнаты — ключ просмотра `magnetId|путь` (тот же, что у прогресса в медиатеке): у всех одна общая медиатека.
 * Данные комнаты, разбор ответов и потока, права — в общем модуле (`core/party/PartyProtocol.kt`); здесь — сеть и Compose.
 */
object Party {
    /** The room as the server last told it (seq rule, members' list: [PartyRoom.merge] / [PartyRoom.reduce]). */
    private var room by mutableStateOf(PartyRoom())

    var roomId by mutableStateOf<String?>(null)
        private set
    val hostId get() = room.hostId
    /** The creator's device (its key): with the shared password everyone has the id "shared", the device tells the host apart. */
    val hostKey get() = room.hostKey
    val hostName get() = room.hostName
    val hostOnly get() = room.hostOnly
    val file get() = room.file
    val state get() = room.state
    val members get() = room.members
    var connected by mutableStateOf(false)
        private set
    /** Why the app left the room by itself (shown on the room page until the next create / join). */
    var notice by mutableStateOf<String?>(null)
    /** My user id on the server (the shared password account is "shared"). */
    var myUserId by mutableStateOf<String?>(null)
        private set

    val inRoom get() = roomId != null
    /** Only the host picks the film (the server checks it too); pause and seek — everyone, unless [hostOnly]. */
    val isHost get() = room.isHost(myUserId, myKey)
    /** This member is the room's creator (with the shared password — the creating device only). */
    fun isCreator(m: PartyMember) = room.isCreator(m)
    /** Pause, play and seek of the whole room: the host only. A guest's pause stays on their own computer (PartySync). */
    val canControl get() = inRoom && isHost

    /** This computer in the room (the server keeps one entry per client id). */
    val clientId = PartyProtocol.newClientId("pc")
    /** How the server shows this computer to the others: sha-256 of [clientId], 16 hex (rooms.js clientKey). */
    val myKey: String = PartyProtocol.clientKey(clientId)
    val clock = PartyClock()

    /** I have just put this film into the room: my player starts the room from its own place (see PartySync). */
    @Volatile var startedByMe: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var stream: Job? = null
    @Volatile private var lines: java.util.stream.Stream<String>? = null

    /** Where the room is now, ms (estimated from the server's anchor with the calibrated clock). */
    fun targetMs(): Long = state.targetMs(clock.serverNow())

    suspend fun create(): String? = try {
        val res = timed { Api.post(PartyProtocol.ROOMS, PartyProtocol.createBody(clientId)) }
        myUserId = PartyProtocol.hostIdOf(res) // the creator is the host
        enter(roomOf(res))
        null
    } catch (e: Exception) { Api.describe(e) }

    suspend fun join(code: String): String? {
        val id = PartyProtocol.normalizeCode(code)
        if (id.isEmpty()) return "Введите код комнаты."
        return try {
            val res = timed { Api.post(PartyProtocol.joinPath(id), PartyProtocol.joinBody(clientId)) }
            PartyProtocol.memberIdOf(res)?.let { myUserId = it }
            enter(roomOf(res))
            null
        } catch (e: Exception) { Api.describe(e) }
    }

    fun leave() {
        val id = roomId ?: return
        stopStream()
        roomId = null; startedByMe = null
        synchronized(this) { room = PartyRoom() }
        reportedBuffering = false
        scope.launch { runCatching { Api.post(PartyProtocol.leavePath(id), PartyProtocol.leaveBody(clientId)) } }
    }

    /** Puts a film into the room (paused at the start, as the server does): the host only; my player will start it. */
    fun putFilm(f: PartyFileRef) {
        if (!inRoom || !isHost || file?.key == f.key) return
        synchronized(this) { room = room.copy(file = f) }
        startedByMe = f.key
        action(PartyProtocol.changeFile(f))
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

    fun play(posMs: Long) = action(PartyProtocol.play(posMs))
    fun pause(posMs: Long) = action(PartyProtocol.pause(posMs))
    fun seek(posMs: Long) { if (canSeek) action(PartyProtocol.seek(posMs)) }

    // One at a time, in order: «put the film» must reach the server before «play from here»
    private val outbox = Channel<Pair<String, JSONObject>>(Channel.UNLIMITED).also { ch ->
        scope.launch {
            for ((id, body) in ch) {
                try {
                    val res = timed { Api.post(PartyProtocol.actionPath(id), body) }
                    // My own change comes back at once (the stream may be a moment later)
                    if (roomId == id) PartyProtocol.roomOf(res)?.let { apply(it) }
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
        outbox.trySend(id to PartyProtocol.withClient(PartyProtocol.buffering(loading), clientId))
    }

    private fun action(body: JSONObject) {
        val id = roomId ?: return
        if (!canControl) return
        outbox.trySend(id to PartyProtocol.withClient(body, clientId))
    }

    /** A request whose answer carries `server_time`: one more sample for the clock. */
    private suspend fun timed(call: suspend () -> JSONObject): JSONObject {
        val t0 = System.currentTimeMillis()
        val res = call()
        val t1 = System.currentTimeMillis()
        val st = PartyProtocol.roomOf(res)?.serverTime ?: 0L
        if (st > 0L) clock.addSample(t0, t1, st)
        return res
    }

    private fun roomOf(res: JSONObject): RoomUpdate =
        PartyProtocol.roomOf(res)?.takeIf { it.id != null } ?: throw java.io.IOException("Сервер не прислал комнату.")

    private suspend fun enter(update: RoomUpdate) {
        stopStream()
        notice = null
        clock.reset()
        reportedBuffering = false
        // A fresh snapshot: another room's seq must not hide this one's state
        synchronized(this) { room = PartyRoom().merge(update) }
        val id = update.id!!
        roomId = id
        // A few more clock samples: the shortest round trip wins
        repeat(3) { runCatching { timed { Api.get(PartyProtocol.roomPath(id)) } } }
        startStream(id)
    }

    @Synchronized private fun apply(update: RoomUpdate) { room = room.merge(update) }

    private fun startStream(id: String) {
        stream = scope.launch {
            var failures = 0
            while (isActive && roomId == id) {
                try {
                    // No Accept-Encoding: a compressed answer would hold the events back until the buffer fills
                    val req = Api.request(PartyProtocol.eventsPath(id, clientId))
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
                    s.use { st -> st.forEach { line -> PartyProtocol.parseEventLine(line)?.let(::onEvent) } }
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

    // Under the same lock as apply(): «member joined/left» from the stream and the answer to my own action (outbox thread)
    // both rewrite [room]; without the lock one of the two changes could be lost
    @Synchronized private fun onEvent(e: PartyEvent) {
        if (e is PartyEvent.Init) e.myUserId?.let { myUserId = it }
        room = room.reduce(e)
    }
}
