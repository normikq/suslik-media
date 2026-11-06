package ru.suslikovanora.media.core

import org.json.JSONObject
import ru.suslikovanora.media.core.party.PartyEvent
import ru.suslikovanora.media.core.party.PartyFileRef
import ru.suslikovanora.media.core.party.PartyMember
import ru.suslikovanora.media.core.party.PartyProtocol
import ru.suslikovanora.media.core.party.PartyRoom
import ru.suslikovanora.media.core.party.PartyState
import ru.suslikovanora.media.core.party.PartyVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartyProtocolTest {
    private val roomJson = """
        {"id":"ab12cd","host_id":"u1","host_key":"k1","host_name":"Аня","controls":"all",
         "file":{"key":"42|Season 1/e01.mkv","name":"Серия 1","ad_id":42},
         "state":{"playing":true,"pos":12.5,"rate":1,"anchor_server_ms":1000,"seq":7,"waiting":false,"server_time":1500},
         "members":[{"key":"k1","id":"u1","name":"Аня","buffering":false,"avatar":"https://cdn.discordapp.com/a.png"},
                    {"key":"k2","id":"u2","name":"","buffering":true,"avatar":"http://evil/a.png"}]}
    """.trimIndent()

    @Test
    fun parsesRoom() {
        val r = PartyProtocol.parseRoom(JSONObject(roomJson))
        assertEquals("ab12cd", r.id)
        assertEquals("u1", r.hostId)
        assertEquals("k1", r.hostKey)
        assertFalse(r.hostOnly)
        assertEquals(PartyFileRef("42|Season 1/e01.mkv", "Серия 1"), r.file)
        assertEquals(42L, r.file!!.adId)
        assertEquals(PartyState(true, 12.5, 1.0, 1000L, 7L, false), r.state)
        assertEquals(1500L, r.serverTime)
        val m = r.members!!
        assertEquals("https://cdn.discordapp.com/a.png", m[0].avatar)
        assertEquals("Гость", m[1].name)      // пустое имя
        assertNull(m[1].avatar)               // не https — не грузим
        assertTrue(m[1].buffering)
    }

    @Test
    fun mergeKeepsNewerStateAndMembers() {
        val first = PartyRoom().merge(PartyProtocol.parseRoom(JSONObject(roomJson)))
        // Старое состояние (seq меньше) и без списка участников: состояние и участники не меняются
        val stale = JSONObject(roomJson).apply {
            getJSONObject("state").put("seq", 5).put("playing", false)
            remove("members")
        }
        val r = first.merge(PartyProtocol.parseRoom(stale))
        assertEquals(7L, r.state.seq)
        assertTrue(r.state.playing)
        assertEquals(2, r.members.size)
        // Файл убран — значит, его нет
        val noFile = JSONObject(roomJson).apply { remove("file") }
        assertNull(first.merge(PartyProtocol.parseRoom(noFile)).file)
    }

    @Test
    fun targetMsFollowsAnchor() {
        val s = PartyState(true, 10.0, 1.0, anchorServerMs = 5_000L, seq = 1)
        assertEquals(13_000L, s.targetMs(8_000L))
        assertEquals(10_000L, s.targetMs(4_000L))                 // часы раньше якоря: не назад
        assertEquals(10_000L, s.copy(waiting = true).targetMs(8_000L)) // ждём кого-то: время стоит
        assertEquals(10_000L, s.copy(playing = false).targetMs(8_000L))
        assertEquals(14_500L, s.copy(rate = 1.5).targetMs(8_000L))
    }

    @Test
    fun hostRules() {
        val a = PartyMember("ka", "shared", "A")
        val b = PartyMember("kb", "shared", "B")
        val shared = PartyRoom(hostId = "shared", hostKey = "ka")
        // Общий пароль: ведущий — создавшее устройство
        assertTrue(shared.isHost("shared", "ka"))
        assertFalse(shared.isHost("shared", "kb"))
        assertTrue(shared.isCreator(a))
        assertFalse(shared.isCreator(b))
        // Discord: ведущий — пользователь на любом своём устройстве
        val discord = PartyRoom(hostId = "u1", hostKey = "ka")
        assertTrue(discord.isHost("u1", "kb"))
        assertFalse(discord.isHost("u2", "ka"))
        assertFalse(discord.isHost(null, "ka"))
        // Кто-то, кроме меня, грузится
        val r = PartyRoom(members = listOf(a.copy(buffering = true), b))
        assertTrue(r.othersBuffering("kb"))
        assertFalse(r.othersBuffering("ka"))
    }

    @Test
    fun parsesEventsAndReduces() {
        val init = PartyProtocol.parseEventLine("""data: {"event":"init","member":{"id":"u2"},"room":$roomJson}""")
        assertIs<PartyEvent.Init>(init)
        assertEquals("u2", init.myUserId)
        var r = PartyRoom().reduce(init)
        assertEquals(2, r.members.size)

        val joined = PartyProtocol.parseEvent("""{"event":"member_joined","data":{"member":{"key":"k2","id":"u2","name":"Боря"}}}""")!!
        r = r.reduce(joined)
        assertEquals(2, r.members.size)                // тот же ключ — замена, не второй
        assertEquals("Боря", r.members.last().name)

        r = r.reduce(PartyProtocol.parseEvent("""{"event":"member_left","data":{"key":"k1"}}""")!!)
        assertEquals(listOf("k2"), r.members.map { it.key })

        val changed = JSONObject(roomJson).apply { getJSONObject("state").put("seq", 8).put("playing", false) }
        r = r.reduce(PartyProtocol.parseEvent("""{"event":"state_changed","data":$changed}""")!!)
        assertFalse(r.state.playing)
        assertEquals(8L, r.state.seq)

        val ref = PartyProtocol.parseEventLine("""data: {"event":"refresh_library","data":{"file":{"key":"42|s1.mkv","name":"Test"}}}""")
        assertIs<PartyEvent.RefreshLibrary>(ref)
        assertEquals("42|s1.mkv", ref.file?.key)
        assertEquals("Test", ref.file?.title)

        val refEmpty = PartyProtocol.parseEventLine("""data: {"event":"refresh_library"}""")
        assertIs<PartyEvent.RefreshLibrary>(refEmpty)
        assertNull(refEmpty.file)

        assertNull(PartyProtocol.parseEventLine(": ping"))
        assertNull(PartyProtocol.parseEvent("""{"event":"something_new"}"""))
        assertNull(PartyProtocol.parseEvent("not json"))
    }

    @Test
    fun commandsAndKeys() {
        assertEquals("ab12cd", PartyProtocol.normalizeCode("  AB-12 cd "))
        // Ссылка из Discord Activity
        assertEquals("842190", PartyProtocol.roomFromLink("suslik://room/842190"))
        assertEquals("842190", PartyProtocol.roomFromLink(" SUSLIK://room/842-190/ "))
        assertEquals("a1b2c3d4e5f6", PartyProtocol.roomFromLink("suslik://room/a1b2c3d4e5f6?x=1"))
        assertNull(PartyProtocol.roomFromLink("https://example.com/room/842190"))
        assertNull(PartyProtocol.roomFromLink("suslik://room/"))
        // Отпечаток как в rooms.js: sha256("pc_test").hex.slice(0, 16)
        assertEquals("9703bd8de4eb8d02", PartyProtocol.clientKey("pc_test"))
        assertTrue(PartyProtocol.newClientId("an").startsWith("an_"))

        val play = PartyProtocol.withClient(PartyProtocol.play(12_340L), "pc_1")
        assertEquals("play", play.getString("type"))
        assertEquals(12.34, play.getDouble("pos"), 1e-9)
        assertEquals("pc_1", play.getString("client_id"))
        val file = PartyProtocol.changeFile(PartyFileRef("42|a.mkv", "A")).getJSONObject("file")
        assertEquals(42L, file.getLong("ad_id"))
        assertEquals("/api/rooms/x/events?client_id=pc_1", PartyProtocol.eventsPath("x", "pc_1"))
    }

    /** Журнал 20.10, 20:59:30: ответ на прежнее действие со старой серией не возвращает ведущего назад. */
    @Test
    fun pendingFileIsNotOverwrittenByStaleAnswers() {
        val r0 = PartyRoom().merge(PartyProtocol.parseRoom(JSONObject(roomJson)))
        val next = PartyFileRef("42|Season 1/e02.mkv", "Серия 2")
        var r = r0.putFile(next)
        // Старый ответ: прежний фильм, состояние новее, новый участник
        val stale = JSONObject(roomJson).apply {
            getJSONObject("state").put("seq", 9).put("pos", 679.0)
            getJSONArray("members").put(JSONObject("""{"key":"k3","id":"u3","name":"Вика"}"""))
        }
        r = r.merge(PartyProtocol.parseRoom(stale))
        assertEquals(next, r.file)
        assertEquals(7L, r.state.seq)            // старое время не берём
        assertEquals(3, r.members.size)          // участников — берём
        // Сервер подтвердил: дальше всё как обычно
        val confirmed = JSONObject(roomJson).apply {
            put("file", JSONObject().put("key", next.key).put("name", next.title))
            getJSONObject("state").put("seq", 10).put("pos", 0.0).put("playing", false)
        }
        r = r.merge(PartyProtocol.parseRoom(confirmed))
        assertNull(r.pendingFileKey)
        assertEquals(10L, r.state.seq)
        assertEquals(next, r.file)
        // Отказ сервера снимает ожидание
        assertNull(r0.putFile(next).fileRejected().pendingFileKey)
    }

    /** Ведущий закрыл фильм: у комнаты его нет, старый ответ с фильмом его не возвращает; запрос — `file: null`. */
    @Test
    fun closedFileStaysClosed() {
        val r0 = PartyRoom().merge(PartyProtocol.parseRoom(JSONObject(roomJson)))
        var r = r0.clearFile()
        assertNull(r.file)
        r = r.merge(PartyProtocol.parseRoom(JSONObject(roomJson)))
        assertNull(r.file)                       // ответ на прежнее действие ещё с фильмом
        val confirmed = JSONObject(roomJson).apply {
            put("file", JSONObject.NULL)
            getJSONObject("state").put("seq", 10).put("playing", false)
        }
        r = r.merge(PartyProtocol.parseRoom(confirmed))
        assertNull(r.pendingFileKey)
        assertNull(r.file)
        assertEquals(10L, r.state.seq)
        val body = PartyProtocol.closeFile()
        assertEquals("change_file", body.getString("type"))
        assertTrue(body.isNull("file"))
    }

    @Test
    fun canonicalContextAndVariants() {
        val v1 = ru.suslikovanora.media.core.party.PartyVariant(
            key = "101|Dune.2021.2160p.Remux.mkv",
            label = "4K Remux",
            quality = "4K",
            audio = "TrueHD Atmos",
            bitrate = 48.2,
            offsetMs = 0L,
            score = 505,
            hardwareFlags = listOf("dv", "hdr")
        )
        val v2 = ru.suslikovanora.media.core.party.PartyVariant(
            key = "102|Dune.2021.1080p.BDRip.mkv",
            label = "1080p BDRip",
            quality = "1080p",
            audio = "AC3 5.1",
            bitrate = 8.1,
            offsetMs = 0L,
            score = 335
        )
        val ref = PartyFileRef(
            key = v1.key,
            title = "Дюна (2021)",
            canonicalId = "m:dune_2021:theatrical",
            edition = "theatrical",
            variants = listOf(v1, v2)
        )

        // matches()
        assertTrue(ref.matches(v1.key))
        assertTrue(ref.matches(v2.key))
        assertTrue(ref.matches("any.mkv", "m:dune_2021:theatrical"))
        assertFalse(ref.matches("other.mkv", "m:other:theatrical"))
        assertFalse(ref.matches(null))

        // JSON serialization and parsing
        val json = PartyProtocol.fileJson(ref)
        assertEquals("m:dune_2021:theatrical", json.optString("canonical_id"))
        assertEquals("theatrical", json.optString("edition"))
        assertEquals(2, json.optJSONArray("variants")?.length())

        val parsed = PartyProtocol.parseFileRef(json)
        assertEquals(ref.key, parsed?.key)
        assertEquals(ref.canonicalId, parsed?.canonicalId)
        assertEquals(ref.edition, parsed?.edition)
        assertEquals(2, parsed?.variants?.size)
        assertEquals(505, parsed?.variants?.get(0)?.score)
        assertEquals(8.1, parsed?.variants?.get(1)?.bitrate)

        val vChange = PartyProtocol.variantChange("104921|Dune.1080p.mkv")
        assertEquals("variant_change", vChange.optString("type"))
        assertEquals("104921|Dune.1080p.mkv", vChange.optString("variant_key"))
    }

    // Same fixtures as desktop-qt/tests/PartyTest.cpp (resolveIndexFixtures) and tizen/test/party.test.js
    @Test
    fun resolveIndexFixtures() {
        val keys = listOf("10|Show/S01E01.mkv", "10|Show/S01E02.mkv")
        val cid = "s:show_s1:default"
        fun v(k: String) = PartyVariant(key = k, label = k)
        assertEquals(1, PartyFileRef("10|Show/S01E02.mkv", "").resolveIndex(keys, null))
        assertEquals(1, PartyFileRef("20|Show.WEB/S01E02.mkv", "", canonicalId = cid).resolveIndex(keys, cid))
        assertEquals(-1, PartyFileRef("20|Show.WEB/S01E02.mkv", "", canonicalId = cid).resolveIndex(keys, "s:other"))
        assertEquals(-1, PartyFileRef("20|Other/S01E01.mkv", "", canonicalId = "s:other").resolveIndex(keys, cid))
        assertEquals(0, PartyFileRef("20|Show.WEB/S01E02.mkv", "", variants = listOf(v("10|Show/S01E01.mkv"))).resolveIndex(keys, null))
        assertEquals(1, PartyFileRef("20|Show.WEB/S01E02.mkv", "").resolveIndex(keys, null, sameWorkHint = true))
        assertEquals(-1, PartyFileRef("30|a.mkv", "").resolveIndex(keys, null))
    }
}
