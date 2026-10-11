package ru.suslikovanora.media.desktop

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * «Экономная загрузка»: a tiny HTTP server on the phone itself (127.0.0.1) between VLC and the mirror.
 *
 * Desktop port of the Android version. VLC reads a stream as it plays, a little every moment; here the film is
 * fetched in bursts instead (a laptop's Wi-Fi rests between them):
 * as fast as the network allows up to ~1.5–2 minutes ahead (kept in a file in the app's cache), then the connection
 * is closed and the radio can idle until the reserve runs low. VLC reads from the local copy and notices nothing.
 *
 * More important on a PC: VLC only ever talks to localhost — a dropped or stalled mirror connection no longer leaves
 * it waiting on a dead socket (the downloader reconnects and continues), and an external voice-over and the video
 * are both read from disk at the same speed, so after a seek or pause they start together instead of drifting.
 *
 * Seeks are cheap: a request far from what is already downloaded opens a new "window" at that place (short probe
 * reads, e.g. MKV indexes at the end of a file, are served without bursting). Disk use is capped per window.
 */
object BurstProxy {
    private const val MAX_WINDOWS = 2              // per stream (the film + one far seek / index read)
    /** Reserve per window (ring file), from «Настройки → Экономная загрузка»; 0 = off (VLC reads directly). */
    @Volatile var capBytes = 96L shl 20
    private const val PROBE_AHEAD = 4L shl 20      // before a window proves to be real playback
    private const val PROBE_LIMIT = 6L shl 20      // read sequentially this far = playback → bursts
    private const val CHUNK = 256 * 1024
    private const val NEAR = 8L shl 20             // a request this close to a window's end joins it (the downloader gets there in a moment)

    private var dir: File? = null
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "burst-proxy").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private val sources = ConcurrentHashMap<String, Source>()   // token -> source
    private val byUrl = ConcurrentHashMap<String, String>()     // original url -> token
    private val ids = AtomicLong()

    /** Once per process. Leftovers of a previous run are removed. */
    @Synchronized fun init() {
        if (dir != null) return
        val d = File(System.getProperty("java.io.tmpdir"), "SuslikMedia-stream")
        runCatching { d.deleteRecursively(); d.mkdirs() }
        dir = d
    }

    @Synchronized private fun ensureServer(): ServerSocket? {
        server?.takeIf { !it.isClosed }?.let { return it }
        return runCatching {
            ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).also { s ->
                server = s
                pool.execute {
                    while (!s.isClosed) {
                        val c = runCatching { s.accept() }.getOrNull() ?: break
                        pool.execute { serve(c) }
                    }
                }
            }
        }.getOrNull()
    }

    /**
     * The address VLC should open for [url]: the local proxy, or [url] itself when the proxy cannot be used
     * (not an https stream, not enough free space, server could not start).
     */
    fun wrap(url: String): String {
        val d = dir ?: return url
        if (!url.startsWith("https://")) return url
        val cap = capBytes
        if (cap <= 0) return url
        if (runCatching { d.usableSpace }.getOrDefault(0L) < (cap * MAX_WINDOWS + (300L shl 20))) return url
        val s = ensureServer() ?: return url
        val token = byUrl.getOrPut(url) {
            val t = UUID.randomUUID().toString().replace("-", "")
            sources[t] = Source(t, url)
            t
        }
        if (sources[token]?.broken == true) return url // this stream did not work through the proxy: go direct
        val name = url.substringBefore('?').substringAfterLast('/').ifBlank { "stream" }
        return "http://127.0.0.1:${s.localPort}/s/$token/${URLEncoder.encode(java.net.URLDecoder.decode(name, "UTF-8"), "UTF-8").replace("+", "%20")}"
    }

    /** Streams that are no longer needed (another episode, player closed): stop downloading, delete their files. */
    fun keepOnly(urls: Collection<String>) {
        val keep = urls.toSet()
        for ((url, token) in byUrl.entries.toList()) if (url !in keep) {
            byUrl.remove(url); sources.remove(token)?.close()
        }
    }

    fun clear() = keepOnly(emptyList())

    /** The film is read through the local reserve right now (not directly from the mirror). */
    fun isActive(url: String): Boolean = capBytes > 0 && byUrl[url]?.let { sources[it]?.broken == false } == true

    /** For the info panel: MB downloaded ahead of the player in the window being read, or null when not in use. */
    fun aheadMb(url: String): Double? {
        val src = byUrl[url]?.let { sources[it] } ?: return null
        val w = synchronized(src) { src.windows.maxByOrNull { it.lastReadAt } } ?: return 0.0
        return (w.end() - w.readPos).coerceAtLeast(0L) / 1048576.0
    }

    // ---- one stream (one original URL) ----------------------------------------------------------
    private class Source(val token: String, val url: String) {
        @Volatile var total = -1L
        @Volatile var type = "application/octet-stream"
        @Volatile var broken = false
        // Copy-on-write: the downloaders read it (current()) without the source's lock — no lock-order deadlock.
        val windows = java.util.concurrent.CopyOnWriteArrayList<Window>()
        // consumption speed of the player (bytes/s), to size the bursts
        @Volatile var rate = 0.0

        @Synchronized fun windowFor(pos: Long): Window {
            windows.firstOrNull { pos >= it.validFrom() && pos <= it.end() + NEAR }?.let { return it }
            if (windows.size >= MAX_WINDOWS) windows.minByOrNull { it.lastReadAt }?.let { windows.remove(it); it.close() }
            PlayerLog.write("reserve: new window at ${pos / 1048576} MB of ${url.substringBefore('?').substringAfterLast('/')}")
            return Window(this, pos).also { windows += it; pool.execute { it.download() } }
        }

        @Synchronized fun close() { windows.forEach { it.close() }; windows.clear() }

        /** The window the player read last: only it may download. */
        fun current(): Window? = windows.maxByOrNull { it.lastReadAt }
    }

    // ---- a contiguous downloaded range, kept in a ring file ---------------------------------------
    private class Window(val src: Source, val start: Long) {
        private val CAP = capBytes.coerceAtLeast(16L shl 20) // fixed for this window's life
        private val file = File(dir, "${ids.incrementAndGet()}.bin")
        private val raf = RandomAccessFile(file, "rw")
        var written = 0L            // bytes from [start] available (contiguous)
        var readPos = start         // where the player reads now
        var sequential = 0L         // bytes read in order (decides probe → burst)
        var lastReadAt = System.currentTimeMillis()
        var eof = false
        var error: String? = null
        @Volatile var closed = false
        @Volatile var body: InputStream? = null
        val lock = java.lang.Object()

        fun end() = start + written
        /**
         * The player jumped elsewhere (another window is being read now): stop filling this one. Before, it went on
         * downloading its whole reserve at full speed and took half the bandwidth from the place the player jumped to —
         * the main reason a seek beyond the reserve took long.
         */
        fun abandoned(): Boolean {
            val quiet = System.currentTimeMillis() - lastReadAt
            // VLC closed its connection to this place (a short index read, or it jumped elsewhere): stop at once.
            return (readers == 0 && quiet > 400) || (quiet > 1500 && src.current() !== this)
        }
        @Volatile var readers = 0 // VLC connections reading this window right now
        fun validFrom() = start + maxOf(0L, written - CAP)
        private fun burst() = sequential >= PROBE_LIMIT

        /** How far ahead to fill, and when to start again (radio wakes up). Sized by the film's bitrate. */
        // Up to ~110 s ahead (or what the reserve holds), refill when ~25 s are left. A small reserve keeps a part of
        // itself for going back a little (seek back without a new download).
        private fun high(): Long = if (!burst()) PROBE_AHEAD else
            (src.rate * 110).toLong().coerceIn(minOf(24L shl 20, CAP * 3 / 4), CAP * 7 / 8)
        private fun low(): Long = if (!burst()) PROBE_AHEAD / 2 else
            (src.rate * 25).toLong().coerceIn(minOf(6L shl 20, high() / 3), high() / 2)

        fun close() {
            closed = true
            runCatching { body?.close() }
            synchronized(lock) { lock.notifyAll() }
            runCatching { raf.close() }; runCatching { file.delete() }
        }

        fun download() {
            var failures = 0
            while (!closed) {
                // Wait while there is enough ahead (or nobody has read for a minute: paused / abandoned window).
                synchronized(lock) {
                    while (!closed && !eof) {
                        val idle = System.currentTimeMillis() - lastReadAt > 60_000 || abandoned()
                        if (!idle && end() - readPos < low()) break
                        lock.wait(1000)
                    }
                }
                if (closed || eof) return
                try {
                    fetch()
                    failures = 0
                } catch (e: IOException) {
                    if (closed) return
                    PlayerLog.write("reserve: request failed (${e.message}), attempt ${failures + 1}")
                    if (e is FatalUpstream) { synchronized(lock) { error = e.message; lock.notifyAll() }; return }
                    failures++
                    if (failures > 8) { synchronized(lock) { error = e.message ?: "network"; lock.notifyAll() }; return }
                    Thread.sleep(minOf(8_000L, 500L shl minOf(failures, 4)))
                }
            }
        }

        /** One burst: from [end] until [high] bytes are ready ahead of the player (or the file ends). */
        private fun fetch() {
            val from = end()
            if (src.total in 0..from) { synchronized(lock) { eof = true; lock.notifyAll() }; return }
            val req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(src.url))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Range", "bytes=$from-").GET().build()
            val res = Api.client.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream())
            val code = res.statusCode()
            res.body().use { input0 ->
                body = input0
                when {
                    code == 416 -> { synchronized(lock) { eof = true; lock.notifyAll() }; return }
                    code == 200 && from > 0 -> { src.broken = true; throw FatalUpstream("no ranges") }
                    code in 400..499 -> throw FatalUpstream("HTTP $code")
                    code !in 200..299 -> throw IOException("HTTP $code")
                }
                res.headers().firstValue("content-type").ifPresent { src.type = it }
                if (src.total < 0) {
                    val cr = res.headers().firstValue("content-range").orElse(null)
                    src.total = cr?.substringAfterLast('/')?.toLongOrNull()
                        ?: res.headers().firstValueAsLong("content-length").let { if (it.isPresent) it.asLong + from else -1L }
                }
                synchronized(lock) { lock.notifyAll() }
                val input: InputStream = input0
                val buf = ByteArray(CHUNK)
                while (!closed) {
                    // Nobody reads here any more → close the request at once. Never keep an unread request open: all
                    // requests to the mirror share one HTTP/2 connection, and data piling up in an unread one used up the
                    // connection's flow-control window — the place the player had just jumped to then got nothing for
                    // seconds ("Буферизация 0 %" for long after a seek, then a sudden fast load).
                    if (abandoned()) return
                    val ahead = end() - readPos
                    // Real playback: a burst ends once enough is ahead (the radio rests until the reserve runs low).
                    if (burst() && ahead >= high()) return
                    // Not proven yet (start-up / just after a seek): keep the one open request streaming — reopening it
                    // costs a full round trip through the mirror each time; it stops when VLC leaves or the ring is full.
                    if (ahead >= CAP * 7 / 8) return
                    val n = input.read(buf)
                    if (n < 0) { synchronized(lock) { eof = true; lock.notifyAll() }; return }
                    synchronized(lock) {
                        if (closed) return
                        ringWrite(end(), buf, n)
                        written += n
                        lock.notifyAll()
                    }
                }
            }
        }

        private fun ringWrite(abs: Long, buf: ByteArray, n: Int) {
            var off = 0
            var pos = abs
            while (off < n) {
                val at = (pos - start) % CAP
                val len = minOf((n - off).toLong(), CAP - at).toInt()
                raf.seek(at); raf.write(buf, off, len)
                off += len; pos += len
            }
        }

        /** Copies up to [max] bytes at [abs] into [buf]; waits for the downloader when needed. -1 = nothing more. */
        fun read(abs: Long, buf: ByteArray, max: Int): Int {
            synchronized(lock) {
                val waitStart = System.currentTimeMillis()
                while (!closed && abs >= end() && !eof && error == null) {
                    readPos = abs; lastReadAt = System.currentTimeMillis(); lock.notifyAll()
                    if (System.currentTimeMillis() - waitStart > 45_000) return -1
                    lock.wait(500)
                }
                if (closed || abs >= end() || abs < validFrom()) return -1
                val n = minOf(max.toLong(), end() - abs).toInt()
                var off = 0; var pos = abs
                while (off < n) {
                    val at = (pos - start) % CAP
                    val len = minOf((n - off).toLong(), CAP - at).toInt()
                    raf.seek(at); raf.readFully(buf, off, len)
                    off += len; pos += len
                }
                return n
            }
        }

        fun consumed(abs: Long, n: Int) {
            synchronized(lock) {
                if (abs == readPos) sequential += n
                readPos = abs + n; lastReadAt = System.currentTimeMillis(); lock.notifyAll()
            }
        }
    }

    private class FatalUpstream(msg: String) : IOException(msg)

    // ---- the local HTTP side (what VLC talks to) --------------------------------------------------
    private fun serve(sock: Socket) {
        sock.use { s ->
            runCatching {
                s.soTimeout = 60_000
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                var range: String? = null
                while (true) {
                    val h = readLine(input) ?: return
                    if (h.isEmpty()) break
                    if (h.startsWith("range:", ignoreCase = true)) range = h.substringAfter(':').trim()
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                val token = parts.getOrNull(1)?.removePrefix("/s/")?.substringBefore('/').orEmpty()
                val src = sources[token]
                val out = s.getOutputStream()
                if (src == null || (method != "GET" && method != "HEAD")) { status(out, "404 Not Found"); return }
                var from = 0L; var to = -1L
                range?.removePrefix("bytes=")?.split('-')?.let { r ->
                    from = r.getOrNull(0)?.toLongOrNull() ?: 0L
                    to = r.getOrNull(1)?.toLongOrNull() ?: -1L
                }
                val w = src.windowFor(from)
                synchronized(w.lock) { w.readers++; w.lastReadAt = System.currentTimeMillis(); w.lock.notifyAll() }
                try { serveWindow(s, out, src, w, method, range, from, to) }
                finally { synchronized(w.lock) { w.readers--; w.lock.notifyAll() } }
            }
        }
    }

    private fun serveWindow(s: Socket, out: OutputStream, src: Source, w: Window, method: String, range: String?, from: Long, to: Long) {
            run {
                // The length is known after the first upstream answer.
                val waitStart = System.currentTimeMillis()
                while (src.total < 0 && w.error == null && !src.broken && System.currentTimeMillis() - waitStart < 30_000) Thread.sleep(50)
                if (src.total < 0) { status(out, "502 Bad Gateway"); return }
                if (from >= src.total) { out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${src.total}\r\nConnection: close\r\n\r\n".toByteArray()); return }
                val last = if (to in from until src.total) to else src.total - 1
                val head = StringBuilder()
                head.append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                head.append("Content-Type: ${src.type}\r\nAccept-Ranges: bytes\r\nContent-Length: ${last - from + 1}\r\n")
                if (range != null) head.append("Content-Range: bytes $from-$last/${src.total}\r\n")
                head.append("Connection: close\r\n\r\n")
                out.write(head.toString().toByteArray())
                if (method == "HEAD") return
                val buf = ByteArray(CHUNK)
                var pos = from
                val t0 = System.currentTimeMillis(); val p0 = from
                while (pos <= last) {
                    val n = w.read(pos, buf, minOf(CHUNK.toLong(), last - pos + 1).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    w.consumed(pos, n)
                    pos += n
                    // bitrate of the film ≈ how fast the player takes data once it is past its start-up buffer
                    val dt = System.currentTimeMillis() - t0
                    if (dt > 20_000) src.rate = maxOf(src.rate * 0.7 + ((pos - p0) * 1000.0 / dt) * 0.3, 0.0)
                }
                out.flush()
            }
    }

    private fun status(out: OutputStream, s: String) { out.write("HTTP/1.1 $s\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }
}
