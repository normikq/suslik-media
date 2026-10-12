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
    private const val BRIDGE = 16L shl 20          // a request this close before a window's data only fetches the gap (see windowFor)
    private const val MAX_BRIDGES = 4              // such gap windows per stream (they are small: one keyframe search step each)

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
    fun interface Upstream {
        /** Bytes [from]..[to] (inclusive; [to] < 0 = to the end of the file). [cancel]: attach what aborts this request. */
        fun open(url: String, from: Long, to: Long, cancel: Cancel): UpstreamResponse
    }
    /** Lets the downloader abort a request that is still connecting / waiting for the answer (a hung TLS handshake). */
    class Cancel {
        @Volatile private var action: (() -> Unit)? = null
        @Volatile var cancelled = false; private set
        fun attach(a: () -> Unit) { action = a; if (cancelled) runCatching(a) }
        fun cancel() { cancelled = true; action?.let { runCatching(it) } }
    }
    /** [header]: by name, case-insensitive. Closing [body] aborts the transfer. */
    class UpstreamResponse(val code: Int, val header: (String) -> String?, val body: java.io.InputStream?) : java.io.Closeable {
        override fun close() { runCatching { body?.close() } }
    }

    @Volatile private var upstream: Upstream? = null
    /** Drops pooled connections (they belong to a network that is gone, or hang): set by the app. */
    @Volatile var resetConnections: () -> Unit = {}
    /** A request with no answer / no data for this long is cancelled and repeated on a fresh connection. */
    @Volatile var headerStallMs = 8_000L
    @Volatile var bodyStallMs = 6_000L
    private val watchdog by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "burst-watch").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
            .also { it.scheduleWithFixedDelay({ runCatching { val now = System.currentTimeMillis(); sources.values.forEach { s -> s.windows.forEach { w -> w.checkStall(now) } } } },
                1, 1, java.util.concurrent.TimeUnit.SECONDS) }
    }

    /**
     * The device switched networks (Wi-Fi ↔ mobile, VPN up / down): the requests in flight and the pooled connections
     * belong to the old one. Everything is dropped and repeated at once instead of waiting for a 30 s timeout.
     */
    fun networkChanged() {
        log("reserve: network changed → restarting the requests")
        runCatching { resetConnections() }
        sources.values.forEach { s -> s.windows.forEach { it.kick() } }
    }
    /** Journal hook (the app's player log). */
    @Volatile var log: (String) -> Unit = {}

    /** Once per process: where the reserve files go (leftovers of a previous run are removed) and how to download. */
    @Synchronized fun init(directory: File, up: Upstream) {
        upstream = up
        watchdog // starts the stall watcher (once)
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
     * A small piece of a stream straight from the mirror, not through the reserve (its windows stay as they are):
     * bytes [from] … [from] + [len] − 1 and the file's size. Blocking, gives up after 15 s. For TsClock.
     */
    fun readRange(url: String, from: Long, len: Int): Pair<ByteArray, Long>? = runCatching {
        val up = upstream ?: return null
        val c = Cancel()
        val timer = watchdog.schedule({ c.cancel() }, 15, TimeUnit.SECONDS)
        try {
            up.open(url, from, from + len - 1, c).use { r ->
                if (r.code !in 200..299) return null
                val total = r.header("content-range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: r.header("content-length")?.toLongOrNull()?.takeIf { r.code == 200 } ?: -1L
                val body = r.body ?: return null
                val out = java.io.ByteArrayOutputStream(len)
                val buf = ByteArray(64 * 1024)
                while (out.size() < len) { val n = body.read(buf, 0, minOf(buf.size, len - out.size())); if (n < 0) break; out.write(buf, 0, n) }
                downloaded.addAndGet(out.size().toLong()); onBytes(out.size().toLong())
                out.toByteArray() to total
            }
        } finally { timer.cancel(false) }
    }.getOrNull()

    /**
     * The network delivers less than the film needs (measured, with a 15 % margin): the reserve can never fill while
     * the film plays, VLC runs dry every few seconds. (12.10 journal: 12 Mbit/s LTE for a 13.2 Mbit/s film — the
     * reserve stayed at 0 MB for minutes and every short rebuffering left the hardware decoder showing a still picture.)
     */
    fun slowerThanFilm(url: String): Boolean {
        val src = sourceOf(url) ?: return false
        val film = if (src.avgRate > 0) src.avgRate else src.rate
        return src.netRate > 0 && film > 0 && src.netRate < film * 1.15
    }

    /**
     * How much reserve (MB) a pause to catch up should gather: ~30 s of the film, at most half the reserve — the other
     * half keeps what was just played, so a jump back to the last keyframe is served from the phone.
     */
    fun catchUpTargetMb(url: String): Double {
        val src = sourceOf(url) ?: return 0.0
        val film = if (src.avgRate > 0) src.avgRate else src.rate
        return minOf(film * 30, capBytes * 0.5) / 1048576.0
    }

    /** Seconds of the film still kept on the phone just behind the place the player reads (0 = unknown / nothing). */
    fun keptBehindSec(url: String): Double {
        val src = sourceOf(url) ?: return 0.0
        val w = src.current() ?: return 0.0
        val film = if (src.avgRate > 0) src.avgRate else src.rate
        if (film <= 0) return 0.0
        return synchronized(w.lock) { (w.readPos - w.validFrom()).coerceAtLeast(0L) / film }
    }

    /**
     * Gather a reserve before the player gets more ([ms] at most; 0 = let go now). The window being read keeps
     * downloading, but the player gets nothing beyond the place it reads now: for VLC it is just a longer buffering.
     * (Pausing VLC instead did not work — 12.10 test: paused, VLC kept pulling everything into its own buffer, so the
     * reserve never showed, and a pause in the middle of a buffering raised VLC's delay to ~8 s and froze the picture.)
     */
    fun hold(url: String, ms: Long) {
        val src = sourceOf(url) ?: return
        val w = src.current() ?: return
        synchronized(w.lock) {
            if (ms > 0) { if (!w.held()) w.gateAt = w.readPos; w.heldUntil = System.currentTimeMillis() + ms }
            else w.heldUntil = 0L
            w.lock.notifyAll()
        }
    }

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
        fun addNetSample(v: Double) { netRate = if (netRate <= 0) v else netRate * 0.5 + v * 0.5 }
        @Volatile var retiredAt = 0L // the player left; kept on disk until then + KEEP_MS (0 = in use)
        fun keptMb(): Long = windows.sumOf { it.written.coerceAtMost(it.cap()) } shr 20

        @Synchronized fun windowFor(pos: Long): Window {
            windowAt(pos)?.let { return it }
            // A little before data that is already here: VLC looking for a keyframe after a seek back steps ~1.5 MB
            // backwards again and again. Each step used to open a whole new window — a new request to the mirror that
            // pulled 6–11 MB forward (data already on the phone) before VLC stepped back again: 12.10, 31 requests and
            // 259 MB in 54 s. Now only the gap is fetched, and the player reads on into the data that is already here.
            windows.filter { it.usable() && pos < it.validFrom() && it.validFrom() - pos <= BRIDGE }
                .minByOrNull { it.validFrom() - pos }?.let { next ->
                    val bridges = windows.filter { it.limit >= 0 }
                    if (bridges.size >= MAX_BRIDGES) bridges.minByOrNull { it.lastReadAt }?.let { windows.remove(it); it.close() }
                    log("reserve: bridge at ${pos / 1048576} MB: only ${(next.validFrom() - pos) / 1024} KB up to what is already here")
                    return Window(this, pos, limit = next.validFrom()).also { windows += it; pool.execute { it.download() } }
                }
            val full = windows.filter { it.limit < 0 }
            if (full.size >= MAX_WINDOWS) full.minByOrNull { it.lastReadAt }?.let { windows.remove(it); it.close() }
            log("reserve: new window at ${pos / 1048576} MB of ${url.substringBefore('?').substringAfterLast('/')}")
            return Window(this, pos).also { windows += it; pool.execute { it.download() } }
        }

        /** The window that already holds [pos] (or gets there in a moment); a bridge only below its end. */
        fun windowAt(pos: Long): Window? =
            windows.firstOrNull { it.usable() && pos >= it.validFrom() && (if (it.limit >= 0) pos < it.limit else pos <= it.end() + NEAR) }

        @Synchronized fun close() { windows.forEach { it.close() }; windows.clear() }

        /** The window the player read last: only it may download. */
        fun current(): Window? = windows.maxByOrNull { it.lastReadAt }
    }

    // ---- a contiguous downloaded range, kept in a ring file ---------------------------------------
    /** [limit] ≥ 0: a bridge — fetches only [start] until [limit] (where another window's data begins), then stops. */
    private class Window(val src: Source, val start: Long, val limit: Long = -1L) {
        fun usable() = error == null && !closed
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
        // The request in flight (for the stall watcher and for network changes)
        @Volatile private var cancel: Cancel? = null
        @Volatile private var fetchAt = 0L
        @Volatile private var lastByteAt = 0L
        @Volatile private var gotHeaders = false
        @Volatile private var stalledWhy: String? = null
        @Volatile private var kicked = false
        private var failures = 0

        /** No answer / no data for too long: abort, the downloader repeats the burst on a new connection. */
        fun checkStall(now: Long) {
            val c = cancel ?: return
            if (closed) return
            val head = headerStallMs + 4_000L * minOf(failures, 2) // a repeatedly failing network gets more patience
            val why = when {
                !gotHeaders && now - fetchAt > head -> "no answer for %.0f s".format((now - fetchAt) / 1000.0)
                gotHeaders && now - lastByteAt > bodyStallMs -> "no data for %.0f s".format((now - lastByteAt) / 1000.0)
                else -> null
            } ?: return
            stalledWhy = why
            val r = response
            // Off the watcher thread: closing an answer can wait for the reader on some HTTP stacks.
            pool.execute { c.cancel(); runCatching { r?.close() } }
        }

        fun kick() {
            synchronized(lock) { kicked = true; lock.notifyAll() }
            cancel?.cancel(); runCatching { response?.close() }
        }

        fun end() = start + written
        fun cap() = CAP
        fun describe(now: Long): String {
            val state = when {
                closed -> "closed"; error != null -> "error($error)"; eof -> "eof"
                response != null -> "downloading"; else -> "resting"
            }
            val phase = when { limit >= 0 -> "bridge to ${limit shr 20}MB"; !burst() -> "probe"; ramping() -> "ramp"; else -> "full" }
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
            if (held()) return false
            val quiet = System.currentTimeMillis() - lastReadAt
            // VLC closed its connection to this place (a short index read, or it jumped elsewhere): stop at once.
            return (readers == 0 && quiet > 400) || (quiet > 1500 && src.current() !== this)
        }
        @Volatile var readers = 0 // VLC connections reading this window right now
        @Volatile var heldUntil = 0L // see BurstProxy.hold
        var gateAt = 0L              // while held, the player gets nothing from here on
        fun held() = System.currentTimeMillis() < heldUntil
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

        // Start-up reserve: right after an open or a seek only ~24 MB (≥ 10 s of the film) is fetched; the full reserve is
        // filled once the player has stayed here ~45 s of the film. Before, every open / seek / switch of an episode pulled
        // a whole reserve (80–160 MB at a fast network) that was thrown away when the viewer moved on within seconds.
        // (2.0.0.4 journal: the ramp ended after ~13 s, so a seek at 19 s still wasted ~94 MB — 3–4× what was watched.)
        private fun rampAfter() = maxOf(32L shl 20, (src.sizing() * 45).toLong()).coerceAtMost(CAP * 3 / 4)
        private fun rampAhead() = maxOf(24L shl 20, (src.sizing() * 10).toLong())
        private fun ramping(): Boolean {
            if (consumedTotal >= rampAfter()) return false
            // A network slower than 3× the film's bitrate never gets the short reserve: the full one protects against stutter.
            if (src.netRate > 0 && src.sizing() > 0 && src.netRate < src.sizing() * 3) return false
            return true
        }

        fun close() {
            closed = true
            cancel?.cancel()
            runCatching { response?.close() }
            synchronized(lock) { lock.notifyAll() }
            runCatching { raf.close() }; runCatching { file.delete() }
        }

        fun download() {
            while (!closed) {
                if (limit >= 0 && end() >= limit) return // a bridge is complete: the rest is in the next window
                // Wait while there is enough ahead (or nobody has read for a minute: paused / abandoned window).
                synchronized(lock) {
                    while (!closed && !eof) {
                        val idle = !held() && (System.currentTimeMillis() - lastReadAt > 60_000 || abandoned())
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
                    val stalled = stalledWhy; stalledWhy = null
                    val netChange = kicked; kicked = false
                    log("reserve: request failed (${stalled ?: e.message}), attempt ${failures + 1}" +
                        (if (stalled != null) " → new connection" else if (netChange) " (network changed) → new connection" else ""))
                    if (e is FatalUpstream) { synchronized(lock) { error = e.message; lock.notifyAll() }; return }
                    if (netChange) { failures = 0; runCatching { resetConnections() }; continue } // the new network: try again at once
                    if (isOffline(e)) {
                        // No network at all (airplane mode, a tunnel): this is not the server failing, so it does not use up
                        // the attempts. 2.0.2 journal: the windows gave up after 4.5 s offline and never came back.
                        // Wait quietly; a network change wakes it at once.
                        pause(3_000L)
                        if (kicked) { kicked = false; failures = 0 }
                        continue
                    }
                    failures++
                    if (failures > 8) { synchronized(lock) { error = e.message ?: "network"; lock.notifyAll() }; return }
                    if (stalled != null) { runCatching { resetConnections() }; Thread.sleep(200) }
                    else pause(minOf(8_000L, 500L shl minOf(failures, 4))) // a network change wakes it
                    if (kicked) { kicked = false; failures = 0 }
                }
            }
        }

        /**
         * Waits [ms] before the next attempt; only a network change ([kick]) or [close] ends it early. The lock is also
         * notified by every read of the player — VLC retries about twice a second while it has no data, and a plain
         * wait woke up on each: 12.10 journal, ~50 failed attempts in 25 s offline instead of one every 3 s.
         */
        private fun pause(ms: Long) {
            val until = System.currentTimeMillis() + ms
            synchronized(lock) {
                while (!kicked && !closed) {
                    val left = until - System.currentTimeMillis()
                    if (left <= 0) break
                    lock.wait(left)
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
            // The network's speed is measured every ~5 s while data comes, not only when a burst ends: on a network slower
            // than the film a burst never ends (12.10 journal: one request ran 9 minutes and the speed stayed unknown).
            var mAt = 0L; var mGot = 0L; var sampled = false
            var why = "error"
            // A proven (playing) stream asks for exactly what is missing: the answer then ends by itself and the connection
            // stays alive for the next burst (2.0.0.4 journal: every burst cut an open-ended answer off → a new TCP+TLS
            // connection each time, 0.5–0.9 s on mobile). Not proven yet / size unknown: the one open request.
            val to = when {
                limit >= 0 -> limit - 1 // a bridge: exactly the gap
                burst() && src.total > 0 -> minOf(src.total - 1, from + maxOf(high() - (end() - readPos).coerceAtLeast(0), 1L shl 20) - 1)
                else -> -1L
            }
            kicked = false; stalledWhy = null
            val c = Cancel()
            cancel = c; gotHeaders = false; fetchAt = t0; lastByteAt = t0
            val res = try { (upstream ?: throw FatalUpstream("no upstream")).open(src.url, from, to, c) }
                      catch (e: IOException) { cancel = null; log("reserve: burst at ${from / 1048576} MB: no answer after ${System.currentTimeMillis() - t0} ms (${e.message})"); throw e }
            val tAnswer = System.currentTimeMillis() - t0
            gotHeaders = true; lastByteAt = System.currentTimeMillis()
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
                    if (n < 0) {
                        // The answer ended. Real end of the file only when everything up to the size arrived; a bounded answer
                        // that is complete is just the end of this burst; anything shorter is a connection cut early.
                        val total = src.total
                        when {
                            total > 0 && end() < total && to >= 0 && end() > to -> { why = "burst done"; return }
                            total > 0 && end() < total -> throw IOException("answer ended at ${end() shr 20} of ${total shr 20} MB")
                            else -> { why = "end of file"; synchronized(lock) { eof = true; lock.notifyAll() }; return }
                        }
                    }
                    lastByteAt = System.currentTimeMillis()
                    if (tFirst == 0L) tFirst = System.currentTimeMillis() - t0
                    got += n
                    if (mAt == 0L) { mAt = lastByteAt; mGot = got }
                    else if (lastByteAt - mAt >= 5_000) {
                        if (got - mGot >= (1L shl 20)) src.addNetSample((got - mGot) * 1000.0 / (lastByteAt - mAt))
                        mAt = lastByteAt; mGot = got; sampled = true
                    }
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
                response = null; cancel = null
                val ms = System.currentTimeMillis() - t0
                val dataMs = (ms - tFirst).coerceAtLeast(1)
                if (!sampled) {
                    // a short burst: the whole of it is one sample
                    if (got >= (3L shl 20) && dataMs >= 200) src.addNetSample(got * 1000.0 / dataMs)
                } else {
                    // the tail since the last sample on the way
                    val tailMs = t0 + ms - mAt
                    if (got - mGot >= (3L shl 20) && tailMs >= 200) src.addNetSample((got - mGot) * 1000.0 / tailMs)
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
            if (limit in 0..abs) return -1 // past a bridge: the reader moves on to the next window
            synchronized(lock) {
                var waitStart = System.currentTimeMillis()
                while (!closed && error == null) {
                    if (held() && abs >= gateAt) {
                        // gathering a reserve: the player waits (this wait is not the 45 s "nothing comes" limit)
                        readPos = abs; lastReadAt = System.currentTimeMillis()
                        lock.wait(minOf(500L, (heldUntil - System.currentTimeMillis()).coerceAtLeast(1L)))
                        waitStart = System.currentTimeMillis()
                        continue
                    }
                    if (abs < end() || eof) break
                    readPos = abs; lastReadAt = System.currentTimeMillis(); lock.notifyAll()
                    if (System.currentTimeMillis() - waitStart > 45_000) return -1
                    lock.wait(500)
                }
                if (closed || abs >= end() || abs < validFrom()) return -1
                var n = minOf(max.toLong(), end() - abs).toInt()
                if (held() && gateAt > abs) n = minOf(n.toLong(), gateAt - abs).toInt()
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

    private fun isOffline(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth++ < 5) {
            if (t is java.net.UnknownHostException || t is java.net.NoRouteToHostException ||
                t is java.net.ConnectException || t is java.net.PortUnreachableException) return true
            t = t.cause
        }
        return false
    }

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
                val reading = arrayOf(src.windowFor(from)) // the window read now (a bridge hands over to the next one)
                reading[0].let { w -> synchronized(w.lock) { w.readers++; w.lastReadAt = System.currentTimeMillis(); w.lock.notifyAll() } }
                try { serveWindow(s, out, src, reading, method, range, from, to) }
                finally { reading[0].let { w -> synchronized(w.lock) { w.readers--; w.lock.notifyAll() } } }
            }
        }
    }

    private fun serveWindow(s: Socket, out: OutputStream, src: Source, reading: Array<Window>, method: String, range: String?, from: Long, to: Long) {
            var w = reading[0]
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
                    if (n <= 0) {
                        // the end of a bridge: on into the window whose data starts here (or a new one if it is gone)
                        if (w.limit < 0 || pos < w.limit) break
                        val next = src.windowFor(pos)
                        synchronized(w.lock) { w.readers--; w.lock.notifyAll() }
                        synchronized(next.lock) { next.readers++; next.lastReadAt = System.currentTimeMillis(); next.lock.notifyAll() }
                        w = next; reading[0] = next
                        continue
                    }
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
