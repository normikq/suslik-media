package ru.suslikovanora.media.core

import org.json.JSONObject
import ru.suslikovanora.media.core.sync.SyncStore
import ru.suslikovanora.media.core.sync.TrackEntry
import ru.suslikovanora.media.core.sync.WatchEntry
import ru.suslikovanora.media.core.sync.WatchSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchSyncTest {
    private class MemStore : SyncStore {
        val w = LinkedHashMap<String, WatchEntry>()
        val t = LinkedHashMap<String, TrackEntry>()
        override fun watch(key: String) = w[key]
        override fun putWatch(e: WatchEntry) { w[e.key] = e }
        override fun trackAt(key: String) = t[key]?.at ?: 0L
        override fun putTrack(e: TrackEntry) { t[e.key] = e }
        override fun allWatch() = w.values.toList()
        override fun allTracks() = t.values.toList()
        override var cursor: String? = null
    }

    @Test
    fun firstSyncUploadsEverythingThenOnlyNew() {
        val store = MemStore()
        store.putWatch(WatchEntry("1|a.mkv", 10_000, 100_000, 50))
        store.putWatch(WatchEntry("2|b.mkv", 20_000, 100_000, 90))
        val sync = WatchSync(store)
        val first = sync.request("u_1")
        assertEquals(0, first.body.getLong("since"))
        val keys = first.body.getJSONArray("watch").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("key") } }
        assertEquals(listOf("2|b.mkv", "1|a.mkv"), keys) // свежее первым
        sync.onResponse(first, JSONObject("""{"now":1000,"watch":[],"tracks":[]}"""))
        assertFalse(sync.hasPending)
        assertEquals("u_1|1000", store.cursor)

        sync.noteWatch(WatchEntry("3|c.mkv", 5_000, 0, 1100))
        val next = sync.request("u_1")
        assertEquals(1000, next.body.getLong("since"))
        assertEquals(1, next.body.getJSONArray("watch").length())
        // Другой аккаунт на том же устройстве — снова всё
        assertEquals(0, sync.request("u_2").body.getLong("since"))
    }

    @Test
    fun newerRemoteWinsOlderIsIgnored() {
        val store = MemStore()
        store.cursor = "u_1|10"
        store.putWatch(WatchEntry("1|a.mkv", 10_000, 100_000, 500))
        val sync = WatchSync(store)
        val sent = sync.request("u_1")
        val n = sync.onResponse(sent, JSONObject("""
            {"now":20,"watch":[
              {"key":"1|a.mkv","pos":90000,"len":100000,"at":400},
              {"key":"2|b.mkv","pos":30000,"len":0,"at":700}],
             "tracks":[{"key":"m2","audio":"Дубляж [Russian]","spu":null,"sidecar":null,"at":700}]}
        """.trimIndent()))
        assertEquals(2, n)
        assertEquals(10_000, store.w["1|a.mkv"]!!.pos) // своё новее
        assertEquals(30_000, store.w["2|b.mkv"]!!.pos)
        assertEquals("Дубляж [Russian]", store.t["m2"]!!.audio)
        assertNull(store.t["m2"]!!.spu)
    }

    @Test
    fun replacedWhileSendingStaysQueued() {
        val store = MemStore()
        store.cursor = "u_1|10"
        val sync = WatchSync(store)
        sync.noteWatch(WatchEntry("1|a.mkv", 10_000, 0, 100))
        val sent = sync.request("u_1")
        sync.noteWatch(WatchEntry("1|a.mkv", 20_000, 0, 200))
        // Сервер вернул наше старое — новее в очереди, хранилище не трогаем
        sync.onResponse(sent, JSONObject("""{"now":30,"watch":[{"key":"1|a.mkv","pos":10000,"len":0,"at":100}]}"""))
        assertTrue(sync.hasPending)
        assertNull(store.w["1|a.mkv"])
    }

    @Test
    fun trackChoicesMergeAndKeys() {
        val store = MemStore()
        store.cursor = "u_1|10"
        val sync = WatchSync(store)
        sync.noteTrack(TrackEntry("m5", "A", null, null, 100))
        sync.noteTrack(TrackEntry("m5", null, "__off__", null, 120))
        val t = sync.request("u_1").body.getJSONArray("tracks").getJSONObject(0)
        assertEquals("A", t.getString("audio"))
        assertEquals("__off__", t.getString("spu"))
        assertEquals(120, t.getLong("at"))
        assertEquals("m42", WatchSync.filmKeyOf("42|Season 1/E01.mkv"))
        assertNull(WatchSync.filmKeyOf("https://x/y"))
        assertTrue(WatchSync.eligible("u_123"))
        assertFalse(WatchSync.eligible("shared"))
        assertFalse(WatchSync.eligible(null))
    }
}
