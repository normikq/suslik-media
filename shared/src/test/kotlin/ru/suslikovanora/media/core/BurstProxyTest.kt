package ru.suslikovanora.media.core

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The local reserve against a real HTTP origin with Range support (what the mirror is). */
class BurstProxyTest {
    private val data = Random(7).nextBytes(40 shl 20)
    private lateinit var origin: HttpServer
    private val requests = AtomicInteger()
    private lateinit var url: String

    @BeforeTest fun start() {
        origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/film.mkv") { ex ->
                requests.incrementAndGet()
                val r = ex.requestHeaders.getFirst("Range")
                var a = 0; var z = data.size - 1
                if (r != null) { val m = Regex("""bytes=(\d*)-(\d*)""").find(r)!!; a = m.groupValues[1].toInt(); if (m.groupValues[2].isNotEmpty()) z = minOf(m.groupValues[2].toInt(), data.size - 1) }
                if (a >= data.size) { ex.responseHeaders.add("Content-Range", "bytes */${data.size}"); ex.sendResponseHeaders(416, -1); ex.close(); return@createContext }
                ex.responseHeaders.add("Content-Type", "video/x-matroska")
                if (r != null) ex.responseHeaders.add("Content-Range", "bytes $a-$z/${data.size}")
                ex.sendResponseHeaders(if (r != null) 206 else 200, (z - a + 1).toLong())
                runCatching { ex.responseBody.use { it.write(data, a, z - a + 1) } }
                ex.close()
            }
            executor = java.util.concurrent.Executors.newCachedThreadPool()
            start()
        }
        url = "https://origin.test/film.mkv?sig=1"
        val dir = File(System.getProperty("java.io.tmpdir"), "bp-test-${System.nanoTime()}")
        BurstProxy.init(dir) { u, from ->
            val c = URL("http://127.0.0.1:${origin.address.port}/" + u.substringAfter("origin.test/")).openConnection() as HttpURLConnection
            c.setRequestProperty("Range", "bytes=$from-")
            BurstProxy.UpstreamResponse(c.responseCode, { c.getHeaderField(it) }, runCatching { c.inputStream }.getOrNull())
        }
        BurstProxy.capBytes = 16L shl 20 // smaller than the file: the ring wraps around
    }
    @AfterTest fun stop() { BurstProxy.dropAll(); origin.stop(0) }

    private fun get(local: String, range: String?): Pair<Int, ByteArray> {
        val c = URL(local).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        range?.let { c.setRequestProperty("Range", it) }
        val code = c.responseCode
        return code to (if (code < 400) c.inputStream.readBytes() else ByteArray(0))
    }

    @Test fun servesExactBytes() {
        val local = BurstProxy.wrap(url)
        assertTrue(local.startsWith("http://127.0.0.1:"), local)
        val (code, all) = get(local, null)
        assertEquals(200, code); assertContentEquals(data, all)
        for ((a, z) in listOf(0 to 99, 1000 to 1999, data.size - 100 to data.size - 1, 5_000_000 to 5_100_000)) {
            val (c, b) = get(local, "bytes=$a-$z")
            assertEquals(206, c); assertContentEquals(data.copyOfRange(a, z + 1), b, "range $a-$z")
        }
        assertEquals(416, get(local, "bytes=${data.size + 10}-").first)
    }

    @Test fun throttledPlaybackComesInBursts() {
        val local = BurstProxy.wrap(url)
        val before = requests.get()
        val c = URL(local).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
        c.setRequestProperty("Range", "bytes=0-")
        val out = java.io.ByteArrayOutputStream()
        c.inputStream.use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); Thread.sleep(4) } // ~16 MB/s
        }
        assertContentEquals(data, out.toByteArray())
        val used = requests.get() - before
        assertTrue(used in 1..12, "upstream requests: $used") // bursts, not one per chunk
    }

    @Test fun sameFileKeepsItsReserveAcrossSignedAddressesAndShortAbsence() {
        fun addr(link: String, n: Int) = "https://origin.test/film.mkv?link=$link&exp=$n&sig=s$n"
        val a = BurstProxy.wrap(addr("L1", 1))
        assertEquals(a, BurstProxy.wrap(addr("L1", 2)))              // the same file, a new signature: one reserve
        assertTrue(a != BurstProxy.wrap(addr("L2", 2)))              // another file: its own
        val (code, head) = get(a, "bytes=0-999999")
        assertEquals(206, code); assertContentEquals(data.copyOfRange(0, 1_000_000), head)
        BurstProxy.clear()                                           // the player is closed: kept on disk for a while
        val again = BurstProxy.wrap(addr("L1", 3))
        assertEquals(a, again)
        val (c2, mid) = get(again, "bytes=100000-299999")            // read after coming back: same bytes
        assertEquals(206, c2); assertContentEquals(data.copyOfRange(100_000, 300_000), mid)
        BurstProxy.dropAll()                                         // a clean restart: everything is forgotten
        assertTrue(a != BurstProxy.wrap(addr("L1", 4)))
    }

    @Test fun offWhenReserveIsZero() {
        BurstProxy.capBytes = 0
        assertEquals("https://origin.test/other.mkv", BurstProxy.wrap("https://origin.test/other.mkv"))
    }
}
