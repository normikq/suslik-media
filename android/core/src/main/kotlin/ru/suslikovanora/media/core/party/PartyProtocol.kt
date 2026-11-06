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
    val avatar: String? = null,
    val variantKey: String? = null,
    /** Длина раздачи, которую участник смотрит (мс): по ней гости на других раздачах сдвигают время. */
    val durationMs: Long? = null
)

/** Раздачи одного фильма разной длины: лишнее обычно в начале (заставка релиз-группы), поэтому место на своей
 *  раздаче = место ведущего + сдвиг, сдвиг = своя длина − длина ведущего. Меньше 1,5 с — хвост кадров, больше 2 мин —
 *  другой монтаж: тогда 0. Те же числа у сервера (rooms.js) и ПК (core/Party.h). */
/** Подстройка, сохранённая гостем: место на [to] = место на [from] + [offsetMs]. */
data class VariantOffset(val from: String, val to: String, val offsetMs: Long)

/** Сдвиг своей раздачи [myKey] от раздачи ведущего [hostKey]: сохранённая подстройка пары, иначе разница длин. */
fun roomShiftMs(saved: List<VariantOffset>, hostKey: String?, hostDurationMs: Long, myKey: String?, myDurationMs: Long): Long {
    if (hostKey != null && hostKey == myKey) return 0
    for (o in saved) {
        if (o.from == hostKey && o.to == myKey) return o.offsetMs
        if (o.from == myKey && o.to == hostKey) return -o.offsetMs
    }
    return timelineShiftMs(myDurationMs, hostDurationMs)
}

const val TIMELINE_SHIFT_MIN_MS = 1500L
const val TIMELINE_SHIFT_MAX_MS = 120_000L
fun timelineShiftMs(myDurationMs: Long, hostDurationMs: Long): Long {
    if (myDurationMs <= 0 || hostDurationMs <= 0) return 0
    val d = myDurationMs - hostDurationMs
    return if (kotlin.math.abs(d) in TIMELINE_SHIFT_MIN_MS..TIMELINE_SHIFT_MAX_MS) d else 0
}

data class PartyVariant(
    val key: String,
    val label: String,
    val quality: String = "1080p",
    val audio: String = "Stereo",
    val bitrate: Double = 0.0,
    val offsetMs: Long = 0L,
    val score: Int = 0,
    val hardwareFlags: List<String> = emptyList()
)

/** Фильм комнаты: ключ просмотра `magnetId|путь`, канонический ID и варианты качества. */
data class PartyFileRef(
    val key: String,
    val title: String,
    val canonicalId: String? = null,
    val edition: String? = null,
    val variants: List<PartyVariant> = emptyList()
) {
    val adId: Long get() = key.substringBefore('|').toLongOrNull() ?: 0L

    /** Признаёт фильм комнаты по прямому ключу, варианту или canonical_id */
    fun matches(fileKey: String?, otherCanonicalId: String? = null): Boolean {
        if (fileKey == null) return false
        if (fileKey == key) return true
        if (variants.any { it.key == fileKey }) return true
        if (canonicalId != null && otherCanonicalId != null && canonicalId == otherCanonicalId) {
            val myMag = key.substringBefore('|', "")
            val otherMag = fileKey.substringBefore('|', "")
            if (myMag.isNotEmpty() && myMag == otherMag) return false
            val myEp = if ('|' in key) ru.suslikovanora.media.core.episodePair(key.substringAfter('|')) else null
            val otherEp = if ('|' in fileKey) ru.suslikovanora.media.core.episodePair(fileKey.substringAfter('|')) else null
            if (myEp != otherEp) return false
            return true
        }
        return false
    }

    /**
     * Index of the room's film in a player's playlist ([keys] = watch keys), or -1. The same rules on every platform
     * (C++ `PartyFileRef::resolveIndex`, JS `fileRef().resolveIndex`): the film's own key, then any of its variants, then
     * the same episode (S/E) of the same work. Another show's S01E01 is never taken for the room's one.
     * [sameWorkHint]: the caller knows the work is the same (e.g. the library groups the two releases as versions).
     */
    fun resolveIndex(keys: List<String>, myCanonicalId: String?, sameWorkHint: Boolean = false): Int {
        keys.indexOf(key).let { if (it >= 0) return it }
        for (v in variants) keys.indexOf(v.key).let { if (it >= 0) return it }
        if ('|' !in key) return -1
        val sameWork = sameWorkHint ||
            (!canonicalId.isNullOrBlank() && canonicalId == myCanonicalId) ||
            (listOf(key) + variants.map { it.key }).any { rk ->
                val mag = rk.substringBefore('|')
                keys.any { it.substringBefore('|') == mag }
            }
        if (!sameWork) return -1
        val target = ru.suslikovanora.media.core.episodePair(key.substringAfter('|')) ?: return -1
        return keys.indexOfFirst { '|' in it && ru.suslikovanora.media.core.episodePair(it.substringAfter('|')) == target }
    }
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
    /** Подстройки времени между раздачей ведущего и раздачами участников (сервер: variant-offsets.js). */
    val variantOffsets: List<VariantOffset> = emptyList(),
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

    /** Ведущий закрыл фильм: у комнаты фильма нет (ждём подтверждения, как у [putFile]; пустой ключ — «без фильма»). */
    fun clearFile(): PartyRoom = copy(file = null, pendingFileKey = NO_FILE)

    /** Ответ сервера с комнатой (создание, вход, действие, `state_changed`). */
    fun merge(u: RoomUpdate): PartyRoom {
        if (pendingFileKey != null && (u.file?.key ?: NO_FILE) != pendingFileKey) {
            // Старый ответ: участники и ведущий — свежие, фильм и время — ещё прежние
            return copy(id = u.id ?: id, hostId = u.hostId, hostKey = u.hostKey, hostName = u.hostName,
                hostOnly = u.hostOnly, members = u.members ?: members, variantOffsets = u.variantOffsets ?: variantOffsets)
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
        members = u.members ?: members,
        variantOffsets = u.variantOffsets ?: variantOffsets
    )

    /** Событие потока. `Init` с участником ([PartyEvent.Init.myUserId]) приложение запоминает само. */
    fun reduce(e: PartyEvent): PartyRoom = when (e) {
        is PartyEvent.Init -> e.room?.let(::merge) ?: this
        is PartyEvent.StateChanged -> merge(e.room)
        is PartyEvent.MemberJoined -> copy(members = members.filter { it.key != e.member.key } + e.member)
        is PartyEvent.MemberLeft -> copy(members = members.filter { it.key != e.key })
        is PartyEvent.RefreshLibrary -> this
    }

    companion object {
        const val SHARED_USER = "shared"
        /** [pendingFileKey] закрытого фильма (у настоящего фильма ключ не пустой: см. [PartyProtocol.parseRoom]). */
        const val NO_FILE = ""
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
    val serverTime: Long,
    val variantOffsets: List<VariantOffset>? = null
)

/** Событие потока комнаты (`/api/rooms/<id>/events`, строки `data: {...}`). */
sealed class PartyEvent {
    /** Первое событие: я сам ([myUserId]) и вся комната. */
    data class Init(val myUserId: String?, val room: RoomUpdate?) : PartyEvent()
    data class StateChanged(val room: RoomUpdate) : PartyEvent()
    data class MemberJoined(val member: PartyMember) : PartyEvent()
    data class MemberLeft(val key: String) : PartyEvent()
    data class RefreshLibrary(val file: PartyFileRef?) : PartyEvent()
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

    /** Код комнаты из ссылки suslik://room/<код> (кнопка «Открыть в приложении» в Discord Activity); не та ссылка — null. */
    fun roomFromLink(link: String): String? {
        val t = link.trim()
        val prefix = "suslik://room/"
        if (!t.startsWith(prefix, ignoreCase = true)) return null
        val rest = t.substring(prefix.length).substringBefore('/').substringBefore('?').substringBefore('#')
        val code = normalizeCode(java.net.URLDecoder.decode(rest.replace("+", "%2B"), "UTF-8"))
        return code.takeIf { it.isNotEmpty() && it.length <= 24 }
    }

    // Тела запросов. client_id в действия добавляет отправитель (см. [withClient]).
    fun createBody(clientId: String): JSONObject = JSONObject().put("controls", "all").put("client_id", clientId)
    fun joinBody(clientId: String): JSONObject = JSONObject().put("client_id", clientId)
    fun leaveBody(clientId: String): JSONObject = JSONObject().put("client_id", clientId)

    fun play(posMs: Long): JSONObject = JSONObject().put("type", "play").put("pos", posMs / 1000.0)
    fun pause(posMs: Long): JSONObject = JSONObject().put("type", "pause").put("pos", posMs / 1000.0)
    fun seek(posMs: Long): JSONObject = JSONObject().put("type", "seek").put("pos", posMs / 1000.0)
    fun buffering(loading: Boolean): JSONObject = JSONObject().put("type", "buffering").put("buffering", loading)
    /** Свой вариант качества; [durationMs] > 0 — длина раздачи, [variant] — описание (своей раздачи нет у ведущего). */
    fun variantChange(variantKey: String, durationMs: Long = 0, variant: PartyVariant? = null): JSONObject {
        val obj = JSONObject().put("type", "variant_change").put("variant_key", variantKey)
        if (durationMs > 0) obj.put("duration_ms", durationMs)
        if (variant != null) obj.put("variant", variantJson(variant))
        return obj
    }
    /** Гость подстроил время: место на [variantKey] = место на [hostVariantKey] + [offsetMs] (запоминается для пары). */
    fun variantOffset(hostVariantKey: String, variantKey: String, offsetMs: Long): JSONObject = JSONObject()
        .put("type", "variant_offset").put("host_variant_key", hostVariantKey).put("variant_key", variantKey).put("offset_ms", offsetMs)
    fun variantJson(v: PartyVariant): JSONObject = JSONObject()
        .put("key", v.key)
        .put("label", v.label)
        .put("quality", v.quality)
        .put("audio", v.audio)
        .put("bitrate", v.bitrate)
        .put("offset_ms", v.offsetMs)
        .put("score", v.score)
        .put("hardware_flags", JSONArray(v.hardwareFlags))
    fun changeFile(f: PartyFileRef): JSONObject = JSONObject().put("type", "change_file").put("file", fileJson(f))
    /** Ведущий закрыл фильм: у гостей плеер тоже закрывается (сервер ставит `room.file = null`). */
    fun closeFile(): JSONObject = JSONObject().put("type", "change_file").put("file", JSONObject.NULL)
    fun withClient(body: JSONObject, clientId: String): JSONObject = body.put("client_id", clientId)

    fun fileJson(f: PartyFileRef): JSONObject {
        val obj = JSONObject().put("key", f.key).put("name", f.title).put("ad_id", f.adId)
        f.canonicalId?.let { obj.put("canonical_id", it) }
        f.edition?.let { obj.put("edition", it) }
        if (f.variants.isNotEmpty()) {
            val arr = JSONArray()
            for (v in f.variants) arr.put(variantJson(v))
            obj.put("variants", arr)
        }
        return obj
    }

    fun parseFileRef(f: JSONObject?): PartyFileRef? {
        if (f == null) return null
        val key = f.optString("key")
        if (key.isBlank()) return null
        val title = f.optString("name")
        val canonicalId = f.optString("canonical_id").takeIf { it.isNotBlank() }
        val edition = f.optString("edition").takeIf { it.isNotBlank() }
        val variantsArr = f.optJSONArray("variants")
        val variants = ArrayList<PartyVariant>()
        if (variantsArr != null) {
            for (i in 0 until variantsArr.length()) {
                val vObj = variantsArr.optJSONObject(i) ?: continue
                val vKey = vObj.optString("key")
                if (vKey.isBlank()) continue
                val flagsArr = vObj.optJSONArray("hardware_flags")
                val flags = ArrayList<String>()
                if (flagsArr != null) {
                    for (j in 0 until flagsArr.length()) flags += flagsArr.optString(j)
                }
                variants += PartyVariant(
                    key = vKey,
                    label = vObj.optString("label"),
                    quality = vObj.optString("quality").ifBlank { vObj.optString("resolution", "") },
                    audio = vObj.optString("audio").ifBlank { vObj.optString("audio_tech", "Stereo") },
                    bitrate = vObj.optDouble("bitrate", vObj.optDouble("bitrate_mbps", 0.0)),
                    offsetMs = vObj.optLong("offset_ms", 0L),
                    score = vObj.optInt("score", 0),
                    hardwareFlags = flags
                )
            }
        }
        return PartyFileRef(key, title, canonicalId, edition, variants)
    }

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
            file = room.optJSONObject("file")?.let(::parseFileRef),
            state = s?.let {
                PartyState(it.optBoolean("playing"), it.optDouble("pos", 0.0), it.optDouble("rate", 1.0),
                    it.optLong("anchor_server_ms"), it.optLong("seq"), it.optBoolean("waiting"))
            },
            members = room.optJSONArray("members")?.let(::parseMembers),
            serverTime = s?.optLong("server_time") ?: 0L,
            variantOffsets = room.optJSONArray("variant_offsets")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
                    val from = o.optString("from"); val to = o.optString("to")
                    if (from.isBlank() || to.isBlank()) null else VariantOffset(from, to, o.optLong("offset_ms", 0L))
                }
            }
        )
    }

    fun parseMember(m: JSONObject): PartyMember = PartyMember(
        m.optString("key"), m.optString("id"), m.optString("name").ifBlank { "Гость" },
        m.optBoolean("buffering"), m.optString("avatar").takeIf { it.startsWith("https://") },
        m.optString("variant_key").takeIf { it.isNotBlank() },
        m.optLong("duration_ms", 0L).takeIf { it > 0 }
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
            "refresh_library" -> {
                val f = msg.optJSONObject("data")?.optJSONObject("file")
                val ref = parseFileRef(f)
                PartyEvent.RefreshLibrary(ref)
            }
            else -> null
        }
    }

    /**
     * Строка потока с тем, что касается приглушения: voice_changed, settings_changed, init (room.voice и
     * my_voice_duck), state_changed (data.voice). Иначе — null. Тот же разбор, что PartyProtocol::voiceOf на ПК.
     */
    fun voiceOf(line: String): VoiceUpdate? {
        if (!line.startsWith("data:")) return null
        val msg = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: return null
        val u = when (msg.optString("event")) {
            "voice_changed" -> VoiceUpdate(speakers = speakersOf(msg.optJSONObject("data")) ?: return null)
            "settings_changed" -> {
                val d = msg.optJSONObject("data") ?: return null
                val who = d.optString("for_user").ifBlank { null } ?: return null
                val duck = d.optJSONObject("voice_duck") ?: return null
                VoiceUpdate(duck = parseVoiceDuck(duck), forUser = who)
            }
            "init" -> VoiceUpdate(
                speakers = speakersOf(msg.optJSONObject("room")?.optJSONObject("voice")),
                duck = msg.optJSONObject("my_voice_duck")?.let(::parseVoiceDuck)
            )
            "state_changed" -> VoiceUpdate(speakers = speakersOf(msg.optJSONObject("data")?.optJSONObject("voice")))
            else -> return null
        }
        return if (u.speakers == null && u.duck == null) null else u
    }

    fun parseVoiceDuck(o: JSONObject): VoiceDuck {
        val enabled = o.opt("enabled") as? Boolean ?: true
        val lv = o.opt("level") as? Number
        val level = if (lv == null) 10 else (Math.round(lv.toDouble() / 10.0).toInt() * 10).coerceIn(0, 100)
        return VoiceDuck(enabled, level)
    }

    private fun speakersOf(voice: JSONObject?): List<String>? {
        voice ?: return null
        val arr = voice.optJSONArray("speakers") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val id = arr.optString(i)
            if (id.isNotEmpty() && id !in out) out += id
        }
        return out
    }
}

/** Приглушение фильма, когда говорят в Discord (website: services/voice-duck.js): своя настройка аккаунта.
 *  level — насколько тише, %: 10 — громкость × 0,9, 100 — тишина. */
data class VoiceDuck(val enabled: Boolean = true, val level: Int = 10)

/** Что о приглушении сказал поток комнаты. forUser — чья настройка (settings_changed); null — своя (init). */
data class VoiceUpdate(val speakers: List<String>? = null, val duck: VoiceDuck? = null, val forUser: String? = null)

/** Приглушение в плеере: настройка и кто говорит. Сам говорящий свой фильм не глушит. Как VoiceState на ПК. */
data class VoiceState(val duck: VoiceDuck = VoiceDuck(), val speakers: List<String> = emptyList()) {
    /** Новое состояние после события; чужая настройка пропускается. */
    fun apply(u: VoiceUpdate, myUserId: String?): VoiceState {
        val d = if (u.duck != null && (u.forUser == null || u.forUser == myUserId)) u.duck else duck
        return VoiceState(d, u.speakers ?: speakers)
    }

    /** Множитель громкости: 1 — как есть, 0,9 — на 10 % тише, 0 — тишина. */
    fun factor(myUserId: String?): Double {
        if (!duck.enabled || speakers.isEmpty()) return 1.0
        if (myUserId != null && myUserId in speakers) return 1.0
        return (1.0 - duck.level / 100.0).coerceIn(0.0, 1.0)
    }
}
