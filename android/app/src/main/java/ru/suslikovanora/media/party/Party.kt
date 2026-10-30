package ru.suslikovanora.media.party

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
import okhttp3.Call
import okhttp3.Request
import org.json.JSONObject
import ru.suslikovanora.media.AppLog
import ru.suslikovanora.media.core.party.PartyClock
import ru.suslikovanora.media.core.party.PartyEvent
import ru.suslikovanora.media.core.party.PartyFileRef
import ru.suslikovanora.media.core.party.PartyMember
import ru.suslikovanora.media.core.party.PartyProtocol
import ru.suslikovanora.media.core.party.PartyRoom
import ru.suslikovanora.media.core.party.RoomUpdate
import ru.suslikovanora.media.net.Api
import ru.suslikovanora.media.tr
import java.util.concurrent.TimeUnit

/**
 * Совместный просмотр: одна комната на приложение (как `desktop/party/Party.kt`). Сервер (`/api/rooms`) хранит, что и где
 * играет; изменения приходят потоком событий (SSE). Данные комнаты, разбор ответов и права — в общем модуле
 * (`core/party/PartyProtocol.kt`); здесь — сеть (OkHttp) и состояние для Compose. Живёт в процессе: медиатека и плеер
 * видят одну и ту же комнату.
 */
object Party {
    /** The room as the server last told it (seq rule, members' list: [PartyRoom.merge] / [PartyRoom.reduce]). */
    private var room by mutableStateOf(PartyRoom())

    var roomId by mutableStateOf<String?>(null)
        private set
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
    /** Only the host picks the film, pauses and seeks for everyone (the server checks it too). */
    val isHost get() = room.isHost(myUserId, myKey)
    /** In a room, not its host: the library is closed, the room's film plays (see MainActivity). */
    val isGuest get() = inRoom && !isHost
    fun isCreator(m: PartyMember) = room.isCreator(m)
    val canControl get() = inRoom && isHost
    val canSeek get() = inRoom && isHost

    /** This phone / TV in the room (the server keeps one entry per client id). */
    val clientId = PartyProtocol.newClientId(if (ru.suslikovanora.media.Tv.cached == true) "tv" else "an")
    /** How the server shows this device to the others: sha-256 of [clientId], 16 hex (rooms.js clientKey). */
    val myKey: String = PartyProtocol.clientKey(clientId)
    val clock = PartyClock()

    /** I have just put this film into the room: my player starts the room from its own place (PartySyncEngine). */
    @Volatile var startedByMe: String? = null

    /** The room's file a guest's app has already opened by itself (MainActivity): closed by the guest — not opened again. */
    var followed by mutableStateOf<String?>(null)
    /** Watch keys of the open player's playlist (PlayerPartySync; empty — no player): the host's next episode there needs no new player. */
    var playingKeys by mutableStateOf<List<String>>(emptyList())

    /** Players showing the room's film here (PlayerActivity): when the host closes the last one, the room pauses. */
    @Volatile var watching = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var stream: Job? = null
    @Volatile private var streamCall: Call? = null

    /** The stream never times out by itself (the server sends a ping); no HTTP cache for it. */
    private val streamClient by lazy { Api.plain.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build() }

    /** Where the room is now, ms (estimated from the server's anchor with the calibrated clock). */
    fun targetMs(): Long = state.targetMs(clock.serverNow())

    fun othersBuffering(): Boolean = room.othersBuffering(myKey)

    suspend fun create(): String? = try {
        val res = timed { Api.post(PartyProtocol.ROOMS, PartyProtocol.createBody(clientId), repeatable = false) }
        myUserId = PartyProtocol.hostIdOf(res) // the creator is the host
        enter(roomOf(res))
        null
    } catch (e: Exception) { Api.describe(e) }

    suspend fun join(code: String): String? {
        val id = PartyProtocol.normalizeCode(code)
        if (id.isEmpty()) return tr("party.vvedite_kod")
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
        roomId = null; startedByMe = null; followed = null
        synchronized(this) { room = PartyRoom() }
        reportedBuffering = false
        scope.launch { runCatching { Api.post(PartyProtocol.leavePath(id), PartyProtocol.leaveBody(clientId)) } }
    }

    /** Puts a film into the room (paused at the start, as the server does): the host only; my player will start it. */
    fun putFilm(f: PartyFileRef) {
        if (!inRoom || !isHost || file?.key == f.key) return
        synchronized(this) { room = room.putFile(f) }
        startedByMe = f.key
        action(PartyProtocol.changeFile(f))
    }

    /**
     * The host closed the film: the room is left without it, and the guests' players close too. A moment later and only
     * if no player shows it by then: re-creating the player (rotation) or opening the next film is not closing.
     */
    fun closeIfNobodyWatches() {
        scope.launch {
            delay(1500)
            if (watching != 0 || !inRoom || !isHost || file == null) return@launch
            AppLog.write("[party] host closed the film: the room has none now")
            synchronized(this@Party) { room = room.clearFile() }
            action(PartyProtocol.closeFile())
        }
    }

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
                } catch (e: Exception) {
                    AppLog.write("[party] action ${body.optString("type")} failed: ${Api.describe(e)}")
                    if (body.optString("type") == "change_file") synchronized(this@Party) { room = room.fileRejected() }
                }
            }
        }
    }

    @Volatile private var reportedBuffering = false

    /** «My player is loading» / «ready»: while anyone is loading the room waits (any member may say it). */
    fun reportBuffering(loading: Boolean) {
        val id = roomId ?: return
        if (loading == reportedBuffering) return
        reportedBuffering = loading
        AppLog.write("[party] ${if (loading) "loading: the room waits" else "ready"}")
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
        PartyProtocol.roomOf(res)?.takeIf { it.id != null } ?: throw java.io.IOException(tr("party.server_ne_prislal_komnatu"))

    private suspend fun enter(update: RoomUpdate) {
        stopStream()
        notice = null; followed = null
        clock.reset()
        reportedBuffering = false
        // A fresh snapshot: another room's seq must not hide this one's state
        synchronized(this) { room = PartyRoom().merge(update) }
        // Joining by code does not start the film already in the room: «Смотреть вместе» opens it; the host's next one opens by itself
        followed = room.file?.key
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
                    // identity: a compressed answer would hold the events back until the buffer fills
                    val req = Request.Builder().url(Api.BASE + PartyProtocol.eventsPath(id, clientId))
                        .header("Accept", "text/event-stream").header("Accept-Encoding", "identity").get().build()
                    val call = streamClient.newCall(req)
                    streamCall = call
                    call.execute().use { res ->
                        if (!isActive || roomId != id) return@use
                        when (res.code) {
                            404 -> { AppLog.write("[party] room $id is gone"); leave(); notice = tr("party.komnata_zakryta"); return@launch }
                            // The sign-in no longer works (signed out, access revoked): repeating makes no sense
                            401, 403 -> {
                                AppLog.write("[party] stream: HTTP ${res.code}, leaving the room")
                                leave(); notice = tr("party.vhod_bolshe_ne_deystvuet"); return@launch
                            }
                        }
                        if (!res.isSuccessful) throw java.io.IOException("HTTP ${res.code}")
                        connected = true; failures = 0
                        val src = res.body?.source() ?: throw java.io.IOException("no body")
                        while (isActive && roomId == id) {
                            val line = src.readUtf8Line() ?: break
                            PartyProtocol.parseEventLine(line)?.let(::onEvent)
                        }
                    }
                } catch (e: Exception) {
                    if (!isActive || roomId != id) break
                    AppLog.write("[party] stream: ${e.message}")
                }
                connected = false
                delay((1000L shl failures.coerceAtMost(4)).coerceAtMost(15_000L)); failures++
            }
        }
    }

    private fun stopStream() {
        stream?.cancel(); stream = null
        runCatching { streamCall?.cancel() }; streamCall = null
        connected = false
    }

    // Under the same lock as apply(): «member joined/left» from the stream and the answer to my own action (outbox thread)
    // both rewrite [room]; without the lock one of the two changes could be lost
    @Synchronized private fun onEvent(e: PartyEvent) {
        if (e is PartyEvent.Init) e.myUserId?.let { myUserId = it }
        room = room.reduce(e)
    }
}
