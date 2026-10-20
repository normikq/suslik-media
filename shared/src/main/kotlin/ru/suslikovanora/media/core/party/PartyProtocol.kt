package ru.suslikovanora.media.core.party

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/*
 * Совместный просмотр: данные комнаты и протокол сервера (`website/src/routes/rooms.js`, `services/rooms.js`).
 * Чистый Kotlin без сети и без Compose: приложения сами ходят в сеть (ПК — java.net.http, Android — OkHttp)
 * и держат состояние в своём UI, а сюда отдают ответы сервера и строки потока событий (SSE).
 */

/** Участник. [key] — отпечаток его client_id (сам client_id — пропуск устройства, другим не показывается). */
data class PartyMember(
    val key: String,
    val userId: String,
    val name: String,
    val buffering: Boolean = false,
    val avatar: String? = null
)

/** Фильм комнаты: ключ просмотра `magnetId|путь` (тот же, что у прогресса в медиатеке). */
data class PartyFileRef(val key: String, val title: String) {
    val adId: Long get() = key.substringBefore('|').toLongOrNull() ?: 0L
}

/**
 * Где комната: [pos] (с) в момент [anchorServerMs] по часам сервера, скорость [rate].
 * [waiting]: кто-то грузится — время комнаты стоит, пока все не готовы ([playing] остаётся намерением).
 */
data class PartyState(
    val playing: Boolean,
    val pos: Double,
    val rate: Double,
    val anchorServerMs: Long,
    val seq: Long,
    val waiting: Boolean = false
) {
    /** Где комната сейчас, мс, при часах сервера [serverNowMs] (см. [PartyClock.serverNow]). */
    fun targetMs(serverNowMs: Long): Long {
        val base = pos * 1000.0
        if (!playing || waiting) return base.toLong()
        val elapsed = (serverNowMs - anchorServerMs).coerceAtLeast(0L)
        return (base + elapsed * rate).toLong()
    }

    companion object {
        val Idle = PartyState(false, 0.0, 1.0, 0L, 0L)
    }
}

/**
 * Снимок комнаты, как его видит приложение. Меняется только через [merge] и [reduce]:
 * состояние плеера — только более новое по `seq`, участники — только если пришёл их список.
 */
data class PartyRoom(
    val id: String? = null,
    val hostId: String? = null,
    /** Устройство создателя: при общем пароле у всех id "shared", ведущего отличает устройство. */
    val hostKey: String? = null,
    val hostName: String = "",
    val hostOnly: Boolean = false,
    val file: PartyFileRef? = null,
    val state: PartyState = PartyState.Idle,
    val members: List<PartyMember> = emptyList(),
    /**
     * Ведущий поставил этот фильм, сервер ещё не подтвердил: ответы на прежние действия везут старый фильм и его
     * состояние — их фильм и состояние не берём, иначе ведущего вернуло бы на прошлую серию (журнал 20.10, 20:59:30).
     */
    val pendingFileKey: String? = null
) {
    /** Я ведущий: мой пользователь создал комнату (при общем пароле — именно это устройство). */
    fun isHost(myUserId: String?, myKey: String): Boolean =
        myUserId != null && myUserId == hostId && (hostId != SHARED_USER || hostKey == null || hostKey == myKey)

    /** Этот участник — создатель комнаты (при общем пароле — только создавшее устройство). */
    fun isCreator(m: PartyMember): Boolean =
        m.userId == hostId && (hostId != SHARED_USER || hostKey == null || m.key == hostKey)

    /** Грузится кто-то, кроме меня: комната ждёт его. */
    fun othersBuffering(myKey: String): Boolean = members.any { it.buffering && it.key != myKey }

    /** Ведущий ставит фильм [f] (до подтверждения сервера держим его, см. [pendingFileKey]). */
    fun putFile(f: PartyFileRef): PartyRoom = copy(file = f, pendingFileKey = f.key)

    /** Сервер не принял смену фильма: снова верим его ответам. */
    fun fileRejected(): PartyRoom = copy(pendingFileKey = null)

    /** Ответ сервера с комнатой (создание, вход, действие, `state_changed`). */
    fun merge(u: RoomUpdate): PartyRoom {
        if (pendingFileKey != null && u.file?.key != pendingFileKey) {
            // Старый ответ: участники и ведущий — свежие, фильм и время — ещё прежние
            return copy(id = u.id ?: id, hostId = u.hostId, hostKey = u.hostKey, hostName = u.hostName,
                hostOnly = u.hostOnly, members = u.members ?: members)
        }
        return mergeNow(u).copy(pendingFileKey = null)
    }

    private fun mergeNow(u: RoomUpdate): PartyRoom = copy(
        id = u.id ?: id,
        hostId = u.hostId,
        hostKey = u.hostKey,
        hostName = u.hostName,
        hostOnly = u.hostOnly,
        file = u.file,
        state = u.state?.takeIf { it.seq >= state.seq } ?: state,
        members = u.members ?: members
    )

    /** Событие потока. `Init` с участником ([PartyEvent.Init.myUserId]) приложение запоминает само. */
    fun reduce(e: PartyEvent): PartyRoom = when (e) {
        is PartyEvent.Init -> e.room?.let(::merge) ?: this
        is PartyEvent.StateChanged -> merge(e.room)
        is PartyEvent.MemberJoined -> copy(members = members.filter { it.key != e.member.key } + e.member)
        is PartyEvent.MemberLeft -> copy(members = members.filter { it.key != e.key })
    }

    companion object {
        const val SHARED_USER = "shared"
    }
}

/** Комната из JSON сервера (`getRoomPublicData`). Поля, которых нет, — null: [PartyRoom.merge] их не трогает. */
data class RoomUpdate(
    val id: String?,
    val hostId: String?,
    val hostKey: String?,
    val hostName: String,
    val hostOnly: Boolean,
    val file: PartyFileRef?,
    val state: PartyState?,
    val members: List<PartyMember>?,
    /** `state.server_time`: ещё одна точка для [PartyClock]. 0 — нет. */
    val serverTime: Long
)

/** Событие потока комнаты (`/api/rooms/<id>/events`, строки `data: {...}`). */
sealed class PartyEvent {
    /** Первое событие: я сам ([myUserId]) и вся комната. */
    data class Init(val myUserId: String?, val room: RoomUpdate?) : PartyEvent()
    data class StateChanged(val room: RoomUpdate) : PartyEvent()
    data class MemberJoined(val member: PartyMember) : PartyEvent()
    data class MemberLeft(val key: String) : PartyEvent()
}

object PartyProtocol {
    const val ROOMS = "/api/rooms"
    fun roomPath(id: String) = "$ROOMS/$id"
    fun joinPath(id: String) = "$ROOMS/$id/join"
    fun leavePath(id: String) = "$ROOMS/$id/leave"
    fun actionPath(id: String) = "$ROOMS/$id/action"
    /** Поток событий. Без Accept-Encoding: сжатый ответ держит события, пока не заполнится буфер. */
    fun eventsPath(id: String, clientId: String) = "$ROOMS/$id/events?client_id=$clientId"

    /** Новый client_id устройства: [prefix] — платформа ("pc", "an", "tv"). Сервер обрезает до 64 знаков. */
    fun newClientId(prefix: String): String =
        prefix + "_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)

    /** Как сервер показывает устройство другим: sha-256 от client_id, 16 hex (`rooms.js clientKey`). */
    fun clientKey(clientId: String): String =
        MessageDigest.getInstance("SHA-256").digest(clientId.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    /** Код комнаты, как его ввёл человек: только буквы и цифры, в нижнем регистре. Пусто — кода нет. */
    fun normalizeCode(code: String): String = code.trim().lowercase().filter { it.isLetterOrDigit() }

    // Тела запросов. client_id в действия добавляет отправитель (см. [withClient]).
    fun createBody(clientId: String): JSONObject = JSONObject().put("controls", "all").put("client_id", clientId)
    fun joinBody(clientId: String): JSONObject = JSONObject().put("client_id", clientId)
    fun leaveBody(clientId: String): JSONObject = JSONObject().put("client_id", clientId)

    fun play(posMs: Long): JSONObject = JSONObject().put("type", "play").put("pos", posMs / 1000.0)
    fun pause(posMs: Long): JSONObject = JSONObject().put("type", "pause").put("pos", posMs / 1000.0)
    fun seek(posMs: Long): JSONObject = JSONObject().put("type", "seek").put("pos", posMs / 1000.0)
    fun buffering(loading: Boolean): JSONObject = JSONObject().put("type", "buffering").put("buffering", loading)
    fun changeFile(f: PartyFileRef): JSONObject = JSONObject().put("type", "change_file").put("file", fileJson(f))
    fun withClient(body: JSONObject, clientId: String): JSONObject = body.put("client_id", clientId)

    fun fileJson(f: PartyFileRef): JSONObject = JSONObject().put("key", f.key).put("name", f.title).put("ad_id", f.adId)

    /** Комната из ответа создания / входа / действия (`{room: {...}}`), если она там есть. */
    fun roomOf(res: JSONObject): RoomUpdate? = res.optJSONObject("room")?.let(::parseRoom)

    /** Мой пользователь из ответа: создание — `room.host_id`, вход — `member.id`. */
    fun hostIdOf(res: JSONObject): String? = res.optJSONObject("room")?.optString("host_id")?.ifBlank { null }
    fun memberIdOf(res: JSONObject): String? = res.optJSONObject("member")?.optString("id")?.ifBlank { null }

    fun parseRoom(room: JSONObject): RoomUpdate {
        val s = room.optJSONObject("state")
        return RoomUpdate(
            id = room.optString("id").ifBlank { null },
            hostId = room.optString("host_id").ifBlank { null },
            hostKey = room.optString("host_key").ifBlank { null },
            hostName = room.optString("host_name"),
            hostOnly = room.optString("controls") == "host",
            file = room.optJSONObject("file")?.let { f ->
                val key = f.optString("key")
                if (key.isBlank()) null else PartyFileRef(key, f.optString("name"))
            },
            state = s?.let {
                PartyState(it.optBoolean("playing"), it.optDouble("pos", 0.0), it.optDouble("rate", 1.0),
                    it.optLong("anchor_server_ms"), it.optLong("seq"), it.optBoolean("waiting"))
            },
            members = room.optJSONArray("members")?.let(::parseMembers),
            serverTime = s?.optLong("server_time") ?: 0L
        )
    }

    fun parseMember(m: JSONObject): PartyMember = PartyMember(
        m.optString("key"), m.optString("id"), m.optString("name").ifBlank { "Гость" },
        m.optBoolean("buffering"), m.optString("avatar").takeIf { it.startsWith("https://") }
    )

    private fun parseMembers(arr: JSONArray): List<PartyMember> =
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map(::parseMember)

    /** Строка потока SSE. Не `data:` или незнакомое событие — null. */
    fun parseEventLine(line: String): PartyEvent? =
        if (line.startsWith("data:")) parseEvent(line.removePrefix("data:").trim()) else null

    fun parseEvent(text: String): PartyEvent? {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return when (msg.optString("event")) {
            "init" -> PartyEvent.Init(
                msg.optJSONObject("member")?.optString("id")?.ifBlank { null },
                msg.optJSONObject("room")?.let(::parseRoom)
            )
            "state_changed" -> msg.optJSONObject("data")?.let { PartyEvent.StateChanged(parseRoom(it)) }
            "member_joined" -> msg.optJSONObject("data")?.optJSONObject("member")?.let { PartyEvent.MemberJoined(parseMember(it)) }
            "member_left" -> msg.optJSONObject("data")?.optString("key")?.ifBlank { null }?.let { PartyEvent.MemberLeft(it) }
            else -> null
        }
    }
}
