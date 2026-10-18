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
    private val cancelledRequests = AtomicInteger()
    private val stallNext = AtomicInteger()                       // the next N answers send their headers and then nothing
    @Volatile private var slowOrigin = false                      // answers trickle at ~2 MB/s (a mobile network slower than the film)
    private val ranges = java.util.concurrent.CopyOnWriteArrayList<String>()
    private lateinit var url: String

    @BeforeTest fun start() {
        cancelledRequests.set(0)
        origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/film.mkv") { ex ->
                requests.incrementAndGet()
                val r = ex.requestHeaders.getFirst("Range")
                ranges.add(r ?: "")
                var a = 0; var z = data.size - 1
                if (r != null) { val m = Regex("""bytes=(\d*)-(\d*)""").find(r)!!; a = m.groupValues[1].toInt(); if (m.groupValues[2].isNotEmpty()) z = minOf(m.groupValues[2].toInt(), data.size - 1) }
                if (a >= data.size) { ex.responseHeaders.add("Content-Range", "bytes */${data.size}"); ex.sendResponseHeaders(416, -1); ex.close(); return@createContext }
                ex.responseHeaders.add("Content-Type", "video/x-matroska")
                if (r != null) ex.responseHeaders.add("Content-Range", "bytes $a-$z/${data.size}")
                ex.sendResponseHeaders(if (r != null) 206 else 200, (z - a + 1).toLong())
                if (stallNext.getAndDecrement() > 0) { runCatching { Thread.sleep(6_000) }; ex.close(); return@createContext } // a hung connection
                if (slowOrigin) runCatching { ex.responseBody.use { o -> var p = a; while (p <= z) { val n = minOf(65_536, z - p + 1); o.write(data, p, n); o.flush(); p += n; Thread.sleep(32) } } }
                else runCatching { ex.responseBody.use { it.write(data, a, z - a + 1) } }
                ex.close()
            }
            // the mirror after 12 hours: an old signature gets 401, a renewed one the bytes
            createContext("/signed.mkv") { ex ->
                requests.incrementAndGet()
                if ("sig=fresh" !in (ex.requestURI.rawQuery ?: "")) { ex.sendResponseHeaders(401, -1); ex.close(); return@createContext }
                val m = Regex("""bytes=(\d*)-(\d*)""").find(ex.requestHeaders.getFirst("Range") ?: "bytes=0-")!!
                val a = m.groupValues[1].toInt(); val z = if (m.groupValues[2].isNotEmpty()) minOf(m.groupValues[2].toInt(), data.size - 1) else data.size - 1
                ex.responseHeaders.add("Content-Range", "bytes $a-$z/${data.size}")
                ex.sendResponseHeaders(206, (z - a + 1).toLong())
                runCatching { ex.responseBody.use { it.write(data, a, z - a + 1) } }
                ex.close()
            }
            executor = java.util.concurrent.Executors.newCachedThreadPool()
            start()
        }
        url = "https://origin.test/film.mkv?sig=1"
        val dir = File(System.getProperty("java.io.tmpdir"), "bp-test-${System.nanoTime()}")
        // java.net.http like on the PC: an answer in flight can be cut from another thread (HttpURLConnection.disconnect() waits for the reader)
        val client = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build()
        BurstProxy.init(dir) { u, from, to, cancel ->
            val req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:${origin.address.port}/" + u.substringAfter("origin.test/")))
                .header("Range", "bytes=$from-" + if (to >= 0) "$to" else "").GET().build()
            val future = client.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream())
            cancel.attach { cancelledRequests.incrementAndGet(); future.cancel(true) }
            val res = try { future.get() }
                catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? java.io.IOException) ?: java.io.IOException(e.message) }
                catch (e: java.util.concurrent.CancellationException) { throw java.io.IOException("cancelled") }
            BurstProxy.UpstreamResponse(res.statusCode(), { res.headers().firstValue(it).orElse(null) }, res.body())
        }
        BurstProxy.capBytes = 16L shl 20 // smaller than the file: the ring wraps around
    }
    // a failed offline test must not leave the next ones without a network (they would download nothing)
    @AfterTest fun stop() { BurstProxy.dropAll(); if (!BurstProxy.isNetworkOnline) BurstProxy.networkChanged(); origin.stop(0) }

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
        // A playing stream asks for exactly what is missing (bytes=a-b), so the answer ends by itself and the connection is reused.
        assertTrue(ranges.any { Regex("""bytes=\d+-\d+""").matches(it) }, "ranges: $ranges")
    }

    @Test fun anOpenButUnreadStreamStopsAtTheProbeReserve() {
        // An external voice-over that is not playing: mpv reads its header and keeps the connection open, reading nothing.
        slowOrigin = true // ~2 MB/s: the old code fills the ring (14 MB ahead) in these 7 s
        val local = URL(BurstProxy.wrap(url))
        val before = BurstProxy.downloaded.get()
        java.net.Socket().use { s ->
            s.receiveBufferSize = 64 * 1024
            s.connect(InetSocketAddress(local.host, local.port))
            s.getOutputStream().write("GET ${local.path} HTTP/1.1\r\nHost: 127.0.0.1\r\nRange: bytes=0-\r\n\r\n".toByteArray())
            val inp = s.getInputStream()
            val buf = ByteArray(64 * 1024)
            var got = 0
            while (got < 256 * 1024) { val n = inp.read(buf); if (n < 0) break; got += n }
            Thread.sleep(7_000) // the connection stays open, nobody reads
            val fetched = BurstProxy.downloaded.get() - before
            // the probe reserve is 4 MB past what the socket took
            assertTrue(fetched < (9L shl 20), "fetched ${fetched shr 20} MB for an unread stream")
        }
        slowOrigin = false
    }

    @Test fun stalledAnswerIsRepeatedOnANewConnection() {
        BurstProxy.headerStallMs = 700; BurstProxy.bodyStallMs = 700
        try {
            stallNext.set(1) // the first answer comes with headers and then hangs
            val local = BurstProxy.wrap(url)
            val t0 = System.currentTimeMillis()
            val (code, b) = get(local, "bytes=0-2999999")
            assertEquals(206, code); assertContentEquals(data.copyOfRange(0, 3_000_000), b)
            val took = System.currentTimeMillis() - t0
            assertTrue(took < 5_500, "took $took ms: the hung answer should be given up after ~1-2 s, not after its 6 s")
            assertTrue(requests.get() >= 2, "requests: ${requests.get()}")
        } finally { BurstProxy.headerStallMs = 8_000; BurstProxy.bodyStallMs = 6_000 }
    }

    @Test fun networkChangeRestartsRequestsInFlight() {
        stallNext.set(1)
        val local = BurstProxy.wrap(url)
        var result: Pair<Int, ByteArray>? = null
        val th = Thread { result = get(local, "bytes=0-1999999") }.apply { start() }
        Thread.sleep(600)           // the first answer is hanging now
        BurstProxy.networkChanged() // Wi-Fi → mobile: no waiting for a timeout
        th.join(4_000)
        assertEquals(206, result?.first)
        assertContentEquals(data.copyOfRange(0, 2_000_000), result?.second)
    }

    @Test fun sameFileKeepsItsReserveAcrossSignedAddressesAndShortAbsence() {
        fun addr(link: String, n: Int) = "https://origin.test/film.mkv?link=$link&exp=$n&sig=s$n"
        val a = BurstProxy.wrap(addr("L1", 1))
        assertEquals(a, BurstProxy.wrap(addr("L1", 2)))              // the same file, a new signature: one reserve
        assertTrue(a != BurstProxy.wrap(addr("L2", 2)))              // another file: its own
        val (code, head) = get(a, "bytes=0-999999")
        assertEquals(206, code); assertContentEquals(data.copyOfRange(0, 1_000_000), head)
        BurstProxy.snapshot().let { assertTrue("film.mkv" in it && "ahead" in it && "sig=" !in it, it) } // debug line, no signatures
        BurstProxy.clear()                                           // the player is closed: kept on disk for a while
        val again = BurstProxy.wrap(addr("L1", 3))
        assertEquals(a, again)
        val (c2, mid) = get(again, "bytes=100000-299999")            // read after coming back: same bytes
        assertEquals(206, c2); assertContentEquals(data.copyOfRange(100_000, 300_000), mid)
        BurstProxy.dropAll()                                         // a clean restart: everything is forgotten
        assertTrue(a != BurstProxy.wrap(addr("L1", 4)))
    }

    @Test fun bitrateComesFromSizeAndLength() {
        val local = BurstProxy.wrap(url)
        get(local, "bytes=0-99")                       // the first answer tells the file's size (40 MiB)
        BurstProxy.setDuration(url, 40_000)            // 40 MiB in 40 s
        assertEquals(8.39, BurstProxy.bitrateMbit(url)!!, 0.05)
        BurstProxy.setDuration(url, 20_000)            // the player corrected the length: the average follows
        assertEquals(16.78, BurstProxy.bitrateMbit(url)!!, 0.05)
    }

    @Test fun slowNetworkIsKnownWhileTheFirstBurstStillRuns() {
        slowOrigin = true
        val local = BurstProxy.wrap(url)
        get(local, "bytes=0-99")
        BurstProxy.setDuration(url, 10_000) // 40 MiB in 10 s = 4 MiB/s film, the network gives ~2
        // the player reads the stream as it comes (one long request: the reserve never fills)
        val reader = Thread { runCatching { get(local, "bytes=0-") } }.apply { isDaemon = true; start() }
        Thread.sleep(7_500)
        assertTrue(reader.isAlive, "the first burst should still be running")
        assertTrue(BurstProxy.slowerThanFilm(url), BurstProxy.snapshot())
        assertTrue(BurstProxy.catchUpTargetMb(url) in 1.0..(16.0 * 0.6 + 0.01), "target ${BurstProxy.catchUpTargetMb(url)}")
        slowOrigin = false
    }

    @Test fun holdGathersAReserveThePlayerGetsAfterwards() {
        val local = BurstProxy.wrap(url)
        get(local, "bytes=0-99")
        BurstProxy.hold(url, 10_000)
        var result: Pair<Int, ByteArray>? = null
        val reader = Thread { result = get(local, "bytes=0-5999999") }.apply { isDaemon = true; start() }
        Thread.sleep(1_500)
        assertTrue(reader.isAlive, "the player must wait while the reserve is gathered")
        val ahead = BurstProxy.aheadMb(url)!!
        assertTrue(ahead >= 2.0, "ahead $ahead MB: the reserve should grow meanwhile")
        BurstProxy.hold(url, 0)  // let go
        reader.join(5_000)
        assertEquals(206, result?.first)
        assertContentEquals(data.copyOfRange(0, 6_000_000), result?.second)
    }

    @Test fun keyframeSearchBackwardsFetchesOnlyTheGaps() {
        val local = BurstProxy.wrap(url)
        val p = 20_000_000
        // a seek: the player lands at p and reads a little
        assertContentEquals(data.copyOfRange(p, p + 3_000_000), get(local, "bytes=$p-${p + 2_999_999}").second)
        Thread.sleep(300)
        // VLC looks for a keyframe: 1.5 MB back each time, reading on across what is already on the phone
        var start = p
        repeat(4) {
            start -= 1_500_000
            val (c, b) = get(local, "bytes=$start-${start + 2_999_999}")
            assertEquals(206, c); assertContentEquals(data.copyOfRange(start, start + 3_000_000), b, "step at $start")
        }
        // each step asked the mirror for its gap only (a bounded range), not for megabytes the phone already had;
        // the first one up to where the seek's window still holds data (its small test ring has moved on a little)
        assertTrue(ranges.any { it.startsWith("bytes=${p - 1_500_000}-") && !it.endsWith("-") }, "step 1: ranges $ranges")
        for (k in 2..4) {
            val a = p - k * 1_500_000
            assertTrue("bytes=$a-${a + 1_499_999}" in ranges, "step $k: ranges $ranges")
        }
        assertEquals(5, requests.get(), "one request per step: $ranges")
    }

    @Test fun readRangeGivesAPieceAndTheSize() {
        val (bytes, total) = BurstProxy.readRange(url, data.size - 5000L, 5000)!!
        assertEquals(data.size.toLong(), total)
        assertContentEquals(data.copyOfRange(data.size - 5000, data.size), bytes)
    }

    @Test fun aClosedFilmThatLeftTheLibraryIsForgotten() {
        fun addr(link: String) = "https://origin.test/film.mkv?link=" + java.net.URLEncoder.encode(link, "UTF-8") + "&exp=1&sig=s"
        val gone = "https://alld.test/f/gone"; val kept = "https://alld.test/f/kept"
        val a = BurstProxy.wrap(addr(gone)); val b = BurstProxy.wrap(addr(kept))
        get(a, "bytes=0-99999"); get(b, "bytes=0-99999")
        BurstProxy.clear()                                   // both closed: kept on disk for a while
        assertTrue(BurstProxy.diskBytes() > 0)
        assertEquals(1, BurstProxy.forgetRetired { it == kept })
        assertEquals(b, BurstProxy.wrap(addr(kept)))         // the one still in the library is still here
        assertTrue(a != BurstProxy.wrap(addr(gone)))         // the gone one starts from nothing
    }

    @Test fun theLastClosedFilmSurvivesASecondClear() {
        fun addr(n: Int) = "https://origin.test/film.mkv?link=L1&exp=$n&sig=s$n"
        val a = BurstProxy.wrap(addr(1))
        get(a, "bytes=0-99999")
        BurstProxy.keepOnly(emptyList())                     // the player is closed
        Thread.sleep(5)
        BurstProxy.clear()                                   // and closed once more (another screen, onDestroy)
        assertEquals(a, BurstProxy.wrap(addr(2)))            // still kept on disk
    }

    @Test fun anExpiredSignatureIsRenewedInsteadOfFailing() {
        val now = System.currentTimeMillis() / 1000
        val renewed = AtomicInteger()
        BurstProxy.refreshUrl = { old ->
            renewed.incrementAndGet()
            if ("link=L9" in old) "https://origin.test/signed.mkv?link=L9&exp=${now + 3600}&sig=fresh" else null
        }
        try {
            val local = BurstProxy.wrap("https://origin.test/signed.mkv?link=L9&exp=${now - 10}&sig=old")
            val (code, b) = get(local, "bytes=0-999999")
            assertEquals(206, code); assertContentEquals(data.copyOfRange(0, 1_000_000), b)
            assertEquals(1, renewed.get())
        } finally { BurstProxy.refreshUrl = { null } }
    }

    @Test fun offWhenReserveIsZero() {
        BurstProxy.capBytes = 0
        assertEquals("https://origin.test/other.mkv", BurstProxy.wrap("https://origin.test/other.mkv"))
    }

    @Test fun offlineServesBufferedRangeWithoutNetworkAndResumesOnNetworkChanged() {
        val local = BurstProxy.wrap(url)
        // 1. Pre-buffer first 4 MB
        val (c1, b1) = get(local, "bytes=0-3999999")
        assertEquals(206, c1)
        assertContentEquals(data.copyOfRange(0, 4_000_000), b1)

        val reqsBeforeOffline = requests.get()

        // 2. Network goes completely offline
        BurstProxy.networkLost()
        assertTrue(!BurstProxy.isNetworkOnline, "isNetworkOnline must be false after networkLost")

        // 3. Read sub-range within the already buffered data. Not from the very start: the burst keeps going after the
        // first read (up to 7/8 of the 16 MB ring ahead of 4 MB), so on a fast machine the ring may already have moved
        // past the first ~1.9 MB (CI 16.10: an empty answer for 1–3 MB).
        val (c2, b2) = get(local, "bytes=2500000-3999999")
        assertEquals(206, c2)
        assertContentEquals(data.copyOfRange(2_500_000, 4_000_000), b2)
        assertEquals(reqsBeforeOffline, requests.get(), "no upstream requests should be made when reading buffered data offline")

        // 4. Network returns
        BurstProxy.networkChanged()
        assertTrue(BurstProxy.isNetworkOnline, "isNetworkOnline must be true after networkChanged")

        // 5. Read a chunk beyond anything the burst could have fetched before going offline (it stops below ~19 MB)
        val (c3, b3) = get(local, "bytes=30000000-30999999")
        assertEquals(206, c3)
        assertContentEquals(data.copyOfRange(30_000_000, 31_000_000), b3)
        assertTrue(requests.get() > reqsBeforeOffline, "upstream requests should resume after networkChanged")
    }

    @Test fun farSeekCancelsInFlightUpstreamAndStartsNewWindow() {
        val local = BurstProxy.wrap(url)
        slowOrigin = true
        try {
            val reader = Thread { runCatching { get(local, "bytes=0-9999999") } }.apply { isDaemon = true; start() }
            Thread.sleep(400) // let the initial trickle request begin

            val seekPos = 25_000_000
            val (code, chunk) = get(local, "bytes=$seekPos-${seekPos + 999_999}")
            assertEquals(206, code)
            assertContentEquals(data.copyOfRange(seekPos, seekPos + 1_000_000), chunk)

            Thread.sleep(600) // allow abandoned window to notify/cancel
            assertTrue(cancelledRequests.get() > 0 || requests.get() >= 2,
                "far seek should start a new window and cancel or abandon the old request")
        } finally {
            slowOrigin = false
        }
    }

    @Test fun aPlayerThatLeftWhileWaitingNoLongerCountsAsReading() {
        val local = URL(BurstProxy.wrap(url))
        get(local.toString(), "bytes=0-99")
        BurstProxy.hold(url, 15_000) // the player gets nothing: its connection waits inside the proxy
        try {
            java.net.Socket().use { s ->
                s.connect(InetSocketAddress(local.host, local.port))
                s.getOutputStream().write("GET ${local.path} HTTP/1.1\r\nHost: 127.0.0.1\r\nRange: bytes=0-\r\n\r\n".toByteArray())
                Thread.sleep(800)
                assertTrue("readers 1" in BurstProxy.snapshot(), BurstProxy.snapshot())
            } // the player closes its connection while waiting
            Thread.sleep(1_200)
            assertTrue("readers 0" in BurstProxy.snapshot(), "the gone player still counts: ${BurstProxy.snapshot()}")
        } finally { BurstProxy.hold(url, 0) }
    }

    @Test fun aJumpJustPastTheReserveOnASlowNetworkOpensItsOwnRequest() {
        slowOrigin = true // ~2 MB/s: a jump of 1.5 s of that network or more is quicker with a new request
        try {
            val local = BurstProxy.wrap(url)
            val before = BurstProxy.downloaded.get()
            val reader = Thread {
                runCatching {
                    val c = URL(local).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
                    c.setRequestProperty("Range", "bytes=0-")
                    c.inputStream.use { inp -> val buf = ByteArray(64 * 1024); val t0 = System.currentTimeMillis(); while (System.currentTimeMillis() - t0 < 6_500 && inp.read(buf) >= 0) {} }
                    c.disconnect()
                }
            }.apply { isDaemon = true; start() }
            reader.join(10_000)
            Thread.sleep(800) // nobody reads: the window stops downloading
            val end = BurstProxy.downloaded.get() - before // one window from 0: where its data ends
            val pos = end + 5_500_000 // within the old 8 MB «near», but well past 1.5 s of this network
            val (code, chunk) = get(local, "bytes=$pos-${pos + 99_999}")
            assertEquals(206, code)
            assertContentEquals(data.copyOfRange(pos.toInt(), pos.toInt() + 100_000), chunk)
            assertTrue(ranges.any { it.startsWith("bytes=$pos-") }, "a new request at the jump: ranges $ranges, ${BurstProxy.snapshot()}")
        } finally { slowOrigin = false }
    }

    @Test fun fastScrubbingDoesNotExceedMaxWindows() {
        val local = BurstProxy.wrap(url)
        val positions = listOf(1_000_000, 10_000_000, 20_000_000, 28_000_000, 35_000_000)
        for (pos in positions) {
            val (code, chunk) = get(local, "bytes=$pos-${pos + 99_999}")
            assertEquals(206, code)
            assertContentEquals(data.copyOfRange(pos, pos + 100_000), chunk, "failed at pos $pos")
        }
        val snap = BurstProxy.snapshot()
        val windowCount = Regex("""@\d+MB""").findAll(snap).count()
        assertTrue(windowCount <= 3, "active full windows must not exceed MAX_WINDOWS (found $windowCount in: $snap)")
    }

    @Test fun ringBufferWraparoundAllowsReFetchOfOverwrittenData() {
        val local = BurstProxy.wrap(url)
        // capBytes is 16 MB. Read 20 MB sequentially so validFrom() moves forward past byte 0
        val (c1, b1) = get(local, "bytes=0-19999999")
        assertEquals(206, c1)
        assertContentEquals(data.copyOfRange(0, 20_000_000), b1)

        // Read the overwritten start of the stream again
        val (c2, b2) = get(local, "bytes=0-99999")
        assertEquals(206, c2)
        assertContentEquals(data.copyOfRange(0, 100_000), b2)
    }
}
