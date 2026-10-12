package ru.suslikovanora.media.core

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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * «Экономная загрузка»: a tiny HTTP server on the phone itself (127.0.0.1) between VLC and the mirror.
 *
 * VLC reads a stream as it plays, a little every moment, so the radio (mobile data / Wi-Fi) never rests during a
 * film. Here the film is fetched in bursts instead:
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
    private const val KEEP_MS = 10 * 60_000L       // a closed film's downloaded part stays on disk this long (back in via «Продолжить»)
    private const val NEAR = 8L shl 20             // a request this close to a window's end joins it (the downloader gets there in a moment)

    private var dir: File? = null
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "burst-proxy").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private val sources = ConcurrentHashMap<String, Source>()   // token -> source
    private val byUrl = ConcurrentHashMap<String, String>()     // stable key of the url (see keyOf) -> token
    private val sweeper by lazy { java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "burst-sweep").apply { isDaemon = true } } }

    /** Bytes fetched from the mirror since the process started (all windows), and a hook for the apps' traffic meter. */
    val downloaded = AtomicLong()
    @Volatile var onBytes: (Long) -> Unit = {}

    /**
     * The same file has a different signed address (…?link=…&exp=…&sig=…) each time it is opened, so the address itself
     * cannot recognise it. The file's identity is the path plus the `link` parameter; other addresses are used as they are.
     */
    private fun keyOf(url: String): String {
        val q = url.substringAfter('?', "")
        val link = q.split('&').firstOrNull { it.startsWith("link=") } ?: return url
        return url.substringBefore('?') + "?" + link
    }
    private fun sourceOf(url: String): Source? = byUrl[keyOf(url)]?.let { sources[it] }
    private val ids = AtomicLong()

    /** How a range of a stream is fetched (OkHttp on Android, java.net.http on the PC). */
    fun interface Upstream { fun open(url: String, from: Long): UpstreamResponse }
    /** [header]: by name, case-insensitive. Closing [body] aborts the transfer. */
    class UpstreamResponse(val code: Int, val header: (String) -> String?, val body: java.io.InputStream?) : java.io.Closeable {
        override fun close() { runCatching { body?.close() } }
    }

    @Volatile private var upstream: Upstream? = null
    /** Journal hook (the app's player log). */
    @Volatile var log: (String) -> Unit = {}

    /** Once per process: where the reserve files go (leftovers of a previous run are removed) and how to download. */
    @Synchronized fun init(directory: File, up: Upstream) {
        upstream = up
        if (dir != null) return
        runCatching { directory.deleteRecursively(); directory.mkdirs() }
        dir = directory
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
        val key = keyOf(url)
        val token = byUrl.getOrPut(key) {
            val t = UUID.randomUUID().toString().replace("-", "")
            sources[t] = Source(t, url)
            t
        }
        sources[token]?.let { src ->
            src.url = url // the newest signature: the old one may have expired
            if (src.retiredAt != 0L) { src.retiredAt = 0L; log("reserve: back to a film kept on disk (${src.keptMb()} MB)") }
        }
        if (sources[token]?.broken == true) return url // this stream did not work through the proxy: go direct
        val name = url.substringBefore('?').substringAfterLast('/').ifBlank { "stream" }
        return "http://127.0.0.1:${s.localPort}/s/$token/${URLEncoder.encode(java.net.URLDecoder.decode(name, "UTF-8"), "UTF-8").replace("+", "%20")}"
    }

    /**
     * Streams that are no longer needed (another episode, player closed). Their downloaded part is not thrown away at
     * once: it stays on disk for [KEEP_MS] (nothing is downloaded meanwhile), so «Продолжить» right after leaving the player
     * reads it from disk instead of fetching the same place again. Only the last closed film is kept.
     */
    fun keepOnly(urls: Collection<String>) {
        val keep = urls.map { keyOf(it) }.toSet()
        val stamp = System.currentTimeMillis()
        var retired = false
        for ((key, token) in byUrl.entries.toList()) if (key !in keep) {
            val src = sources[token] ?: continue
            if (src.retiredAt == 0L) { src.retiredAt = stamp; retired = true }
        }
        // Older closed films go now: one is enough, the disk is the phone's.
        for ((key, token) in byUrl.entries.toList()) {
            val src = sources[token] ?: continue
            if (src.retiredAt != 0L && src.retiredAt != stamp) { byUrl.remove(key); sources.remove(token)?.close() }
        }
        if (retired) runCatching { sweeper.schedule({ sweep() }, KEEP_MS + 1000, TimeUnit.MILLISECONDS) }
    }

    private fun sweep() {
        val now = System.currentTimeMillis()
        for ((key, token) in byUrl.entries.toList()) {
            val src = sources[token] ?: continue
            if (src.retiredAt != 0L && now - src.retiredAt >= KEEP_MS) { byUrl.remove(key); sources.remove(token)?.close() }
        }
    }

    /** The player is closed: keep what was downloaded for a while (see [keepOnly]). */
    fun clear() = keepOnly(emptyList())

    /** Throw everything away now (a clean restart after a problem, the app exits). */
    fun dropAll() {
        for ((key, token) in byUrl.entries.toList()) { byUrl.remove(key); sources.remove(token)?.close() }
    }

    /**
     * The player knows the film's length: its average bitrate (file size ÷ duration) sizes the bursts from the first
     * second. Before, the bitrate was only measured after ~20 s of playback; until then a refill started with just
     * 6 MB (~4 s of a 13 Mbit/s film) left — on mobile internet, where a new request takes 1–3 s to get up to speed,
     * that meant stutters right after the start and after every seek. The measured value takes over later.
     */
    fun setDuration(url: String, ms: Long) {
        if (ms <= 0) return
        val src = sourceOf(url) ?: return
        val total = src.total
        if (total <= 0) return
        val avg = total * 1000.0 / ms
        val first = src.avgRate <= 0
        src.avgRate = avg
        if (src.rate <= 0) src.rate = avg
        if (first) log("reserve: bitrate from size/length ≈ %.1f Mbit/s".format(avg * 8 / 1_000_000))
    }

    /** The film is read through the local reserve right now (not directly from the mirror). */
    fun isActive(url: String): Boolean = capBytes > 0 && sourceOf(url)?.broken == false

    /** For the info panel: MB downloaded ahead of the player in the window being read, or null when not in use. */
    fun aheadMb(url: String): Double? {
        val src = sourceOf(url) ?: return null
        val w = synchronized(src) { src.windows.maxByOrNull { it.lastReadAt } } ?: return 0.0
        return (w.end() - w.readPos).coerceAtLeast(0L) / 1048576.0
    }

    /** The film's average bitrate in Mbit/s (file size ÷ length), once the player has told the length; null before. */
    fun bitrateMbit(url: String): Double? =
        sourceOf(url)?.let { if (it.avgRate > 0) it.avgRate else it.rate }?.takeIf { it > 0 }?.let { it * 8 / 1_000_000 }

    /** MB downloaded from the mirror since the process started. */
    fun downloadedMb(): Double = downloaded.get() / 1048576.0

    /**
     * Debug build: the reserve's state in one line — every stream (size, bitrates) and its windows (where, how much is
     * ahead of the player, phase, downloading or resting, how long nobody read). For tracing what the downloader does.
     */
    fun snapshot(): String {
        if (sources.isEmpty()) return "no streams"
        val now = System.currentTimeMillis()
        return sources.values.joinToString(" | ") { s ->
            val name = s.url.substringBefore('?').substringAfterLast('/').take(40)
            val retired = if (s.retiredAt > 0) " retired ${(now - s.retiredAt) / 1000}s ago" else ""
            val ws = s.windows.joinToString(", ") { w -> w.describe(now) }.ifEmpty { "no windows" }
            "%s %dMB · film %.1f avg %.1f net %.1f Mbit/s%s · %s".format(name, maxOf(s.total, 0L) shr 20,
                s.rate * 8 / 1e6, s.avgRate * 8 / 1e6, s.netRate * 8 / 1e6, retired, ws)
        }
    }

    // ---- one stream (one original URL) ----------------------------------------------------------
    private class Source(val token: String, @Volatile var url: String) {
        @Volatile var total = -1L
        @Volatile var type = "application/octet-stream"
        @Volatile var broken = false
        // Copy-on-write: the downloaders read it (current()) without the source's lock — no lock-order deadlock.
        val windows = java.util.concurrent.CopyOnWriteArrayList<Window>()
        // consumption speed of the player (bytes/s), to size the bursts
        @Volatile var rate = 0.0
        // the film's average bitrate (file size ÷ length): never measured, so a pause or a start-up read cannot distort it
        @Volatile var avgRate = 0.0
        /** The bitrate that sizes the reserve: the measured one, kept within 0.5–4× the file's average. */
        fun sizing(): Double = if (avgRate > 0) rate.coerceIn(avgRate * 0.5, avgRate * 4) else rate
        // how fast the network delivered the last bursts (bytes/s): a slow one never gets the short start-up reserve
        @Volatile var netRate = 0.0
        @Volatile var retiredAt = 0L // the player left; kept on disk until then + KEEP_MS (0 = in use)
        fun keptMb(): Long = windows.sumOf { it.written.coerceAtMost(it.cap()) } shr 20

        @Synchronized fun windowFor(pos: Long): Window {
            windows.firstOrNull { it.error == null && !it.closed && pos >= it.validFrom() && pos <= it.end() + NEAR }?.let { return it }
            if (windows.size >= MAX_WINDOWS) windows.minByOrNull { it.lastReadAt }?.let { windows.remove(it); it.close() }
            log("reserve: new window at ${pos / 1048576} MB of ${url.substringBefore('?').substringAfterLast('/')}")
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
        var consumedTotal = 0L      // bytes the player has taken from this window (decides when the short start-up reserve ends)
        var lastReadAt = System.currentTimeMillis()
        var eof = false
        var error: String? = null
        @Volatile var closed = false
        @Volatile var response: UpstreamResponse? = null
        val lock = java.lang.Object()

        fun end() = start + written
        fun cap() = CAP
        fun describe(now: Long): String {
            val state = when {
                closed -> "closed"; error != null -> "error($error)"; eof -> "eof"
                response != null -> "downloading"; else -> "resting"
            }
            val phase = when { !burst() -> "probe"; ramping() -> "ramp"; else -> "full" }
            return "@%dMB ahead %.1fMB (low %d high %d) kept %dMB %s %s readers %d quiet %ds".format(start shr 20,
                (end() - readPos).coerceAtLeast(0) / 1048576.0, low() shr 20, high() shr 20,
                written.coerceAtMost(CAP) shr 20, phase, state, readers, (now - lastReadAt) / 1000)
        }
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
        private fun high(): Long = if (!burst()) PROBE_AHEAD else {
            val full = (src.sizing() * 110).toLong().coerceIn(minOf(24L shl 20, CAP * 3 / 4), CAP * 7 / 8)
            if (ramping()) minOf(full, rampAhead()) else full
        }
        private fun low(): Long = if (!burst()) PROBE_AHEAD / 2 else
            (src.sizing() * 25).toLong().coerceIn(minOf(6L shl 20, high() / 3), high() / 2)

        // Start-up reserve: right after an open or a seek only ~24 MB (≥ 8 s of the film) is fetched; the full reserve is
        // filled once the player has stayed here ~10 s. Before, every open / seek / switch of an episode pulled a whole
        // reserve (80–160 MB at a fast network) that was thrown away when the viewer moved on within seconds.
        private fun rampAfter() = maxOf(16L shl 20, (src.sizing() * 10).toLong()).coerceAtMost(CAP / 2)
        private fun rampAhead() = maxOf(24L shl 20, (src.sizing() * 8).toLong())
        private fun ramping(): Boolean {
            if (consumedTotal >= rampAfter()) return false
            // A network slower than 3× the film's bitrate never gets the short reserve: the full one protects against stutter.
            if (src.netRate > 0 && src.sizing() > 0 && src.netRate < src.sizing() * 3) return false
            return true
        }

        fun close() {
            closed = true
            runCatching { response?.close() }
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
                    log("reserve: request failed (${e.message}), attempt ${failures + 1}")
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
            // Journal (option «Г»): how each burst went — answer time, time to the first data, speed, why it stopped.
            // On mobile internet this shows whether stutters come from slow starts of new requests or from the speed.
            val t0 = System.currentTimeMillis()
            var tFirst = 0L
            var got = 0L
            var why = "error"
            val res = try { (upstream ?: throw FatalUpstream("no upstream")).open(src.url, from) }
                      catch (e: IOException) { log("reserve: burst at ${from / 1048576} MB: no answer after ${System.currentTimeMillis() - t0} ms (${e.message})"); throw e }
            val tAnswer = System.currentTimeMillis() - t0
            response = res
            try { res.use { r ->
                val code = r.code
                when {
                    code == 416 -> { synchronized(lock) { eof = true; lock.notifyAll() }; return }
                    code == 200 && from > 0 -> { src.broken = true; throw FatalUpstream("no ranges") }
                    code in 400..499 -> throw FatalUpstream("HTTP $code")
                    code !in 200..299 -> throw IOException("HTTP $code")
                }
                r.header("content-type")?.let { src.type = it }
                if (src.total < 0) {
                    src.total = r.header("content-range")?.substringAfterLast('/')?.toLongOrNull()
                        ?: r.header("content-length")?.toLongOrNull()?.let { it + from } ?: -1L
                }
                synchronized(lock) { lock.notifyAll() }
                val input: InputStream = r.body ?: throw IOException("no body")
                val buf = ByteArray(CHUNK)
                while (!closed) {
                    // Nobody reads here any more → close the request at once. Never keep an unread request open: all
                    // requests to the mirror share one HTTP/2 connection, and data piling up in an unread one used up the
                    // connection's flow-control window — the place the player had just jumped to then got nothing for
                    // seconds ("Буферизация 0 %" for long after a seek, then a sudden fast load).
                    if (abandoned()) { why = "player left"; return }
                    val ahead = end() - readPos
                    // Real playback: a burst ends once enough is ahead (the radio rests until the reserve runs low).
                    if (burst() && ahead >= high()) { why = "reserve full"; return }
                    // Not proven yet (start-up / just after a seek): keep the one open request streaming — reopening it
                    // costs a full round trip through the mirror each time; it stops when VLC leaves or the ring is full.
                    if (ahead >= CAP * 7 / 8) { why = "ring full"; return }
                    val n = input.read(buf)
                    if (n < 0) { why = "end of file"; synchronized(lock) { eof = true; lock.notifyAll() }; return }
                    if (tFirst == 0L) tFirst = System.currentTimeMillis() - t0
                    got += n
                    downloaded.addAndGet(n.toLong()); onBytes(n.toLong())
                    synchronized(lock) {
                        if (closed) { why = "closed"; return }
                        ringWrite(end(), buf, n)
                        written += n
                        lock.notifyAll()
                    }
                }
                why = "closed"
            } } finally {
                response = null
                val ms = System.currentTimeMillis() - t0
                val dataMs = (ms - tFirst).coerceAtLeast(1)
                if (got >= (3L shl 20) && dataMs >= 200) {
                    val v = got * 1000.0 / dataMs
                    src.netRate = if (src.netRate <= 0) v else src.netRate * 0.5 + v * 0.5
                }
                val ahead = (end() - readPos).coerceAtLeast(0) / 1048576.0
                val rate = if (src.rate > 0) " · film %.1f (avg %.1f)".format(src.rate * 8 / 1_000_000, src.avgRate * 8 / 1_000_000) else ""
                log("reserve: burst at %d MB: %.1f MB in %.1f s, %.1f Mbit/s%s · answer %d ms, first data %d ms · ahead %.0f MB · %s".format(
                    from / 1048576, got / 1048576.0, ms / 1000.0, if (got > 0) got * 8.0 / 1000 / dataMs else 0.0, rate,
                    tAnswer, tFirst, ahead, why))
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
                consumedTotal += n
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
                var fromBrowser = false
                while (true) {
                    val h = readLine(input) ?: return
                    if (h.isEmpty()) break
                    if (h.startsWith("range:", ignoreCase = true)) range = h.substringAfter(':').trim()
                    // Only the player talks to this server. A web page in a browser on the same device sends Origin —
                    // refused outright (the address also carries a random 128-bit token that nobody else knows).
                    if (h.startsWith("origin:", ignoreCase = true)) fromBrowser = true
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                val token = parts.getOrNull(1)?.removePrefix("/s/")?.substringBefore('/').orEmpty()
                val src = sources[token]
                val out = s.getOutputStream()
                if (src == null || fromBrowser || (method != "GET" && method != "HEAD")) { status(out, "404 Not Found"); return }
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
                val t0 = System.currentTimeMillis()
                var tm = 0L; var pm = 0L // base of the bitrate measurement, set once the player's start-up read-ahead is over
                while (pos <= last) {
                    val n = w.read(pos, buf, minOf(CHUNK.toLong(), last - pos + 1).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    w.consumed(pos, n)
                    pos += n
                    // The film's local bitrate ≈ how fast the player takes data, measured over 20 s intervals after the first
                    // 15 s. (Counted from the very start it came out as 25 Mbit/s for a 9 Mbit/s film: VLC reads its
                    // start-up buffer at disk speed.) An interval with a pause or a stall in it (far below the file's
                    // average) is skipped, so a pause no longer drags the reserve down to a few MB.
                    val now = System.currentTimeMillis()
                    if (tm == 0L) { if (now - t0 > 15_000) { tm = now; pm = pos } }
                    else if (now - tm >= 20_000) {
                        val m = (pos - pm) * 1000.0 / (now - tm)
                        if (m >= src.avgRate * 0.4) src.rate = if (src.rate > 0) src.rate * 0.7 + m * 0.3 else m
                        tm = now; pm = pos
                    }
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
