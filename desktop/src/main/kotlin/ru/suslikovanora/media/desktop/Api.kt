package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Properties

/** App data folder: %APPDATA%\SuslikMedia on Windows (the debug build: SuslikMediaDebug), ~/.suslikmedia elsewhere. */
object AppDir {
    val dir: File by lazy {
        // The debug build keeps its own folder: its own sign-in, settings, progress and journal, next to the normal app.
        val name = if (System.getProperty("suslik.diag") == "true") "SuslikMediaDebug" else "SuslikMedia"
        val base = System.getenv("APPDATA")?.let { File(it, name) } ?: File(System.getProperty("user.home"), "." + name.lowercase())
        base.apply { mkdirs() }
    }
    fun file(name: String) = File(dir, name)
}

/**
 * Tiny key-value store on disk (posters, watch progress, settings). Writes are debounced by the caller's rhythm.
 * One instance per file ([of]): two copies of "ui" each rewrote the whole file with only their own keys, and the
 * player made a new "player_ui" on every opening (each stayed in [all] for good).
 */
class Store private constructor(name: String) {
    private val f = AppDir.file("$name.properties")
    private val bak = AppDir.file("$name.properties.bak")
    private val p = load()

    /**
     * The file, or the copy before the last write when the file is damaged. 18.10: after a hard power-off cookies, watch
     * and player_ui were all zero bytes (the new name was on disk, its data not yet) — the zeros went into the Cookie
     * header and every request failed with «invalid header value». An entry with control characters is dropped.
     */
    private fun load(): Properties {
        for (src in listOf(f, bak)) {
            val bytes = runCatching { src.readBytes() }.getOrNull() ?: continue
            if (bytes.isEmpty() || bytes.any { it == 0.toByte() }) {
                runCatching { PlayerLog.write("[store] ${src.name} is damaged (${bytes.size} bytes), trying the copy") }
                continue
            }
            val props = Properties()
            if (runCatching { bytes.inputStream().use { props.load(it) } }.isFailure) continue
            props.stringPropertyNames().filter { k -> (k + props.getProperty(k)).any { it < ' ' } }.forEach { props.remove(it) }
            return props
        }
        return Properties()
    }
    @Synchronized fun get(k: String): String? = p.getProperty(k)
    @Synchronized fun put(k: String, v: String?) {
        if (v == null) p.remove(k) else p.setProperty(k, v)
        scheduleSave()
    }
    // Writing the file is done in the background and coalesced: the player saves its position every 10 s from the
    // window's thread, and rewriting the whole file there was a small stutter each time.
    private var pending = false
    private fun scheduleSave() {
        if (pending) return
        pending = true
        writer.schedule({ flush() }, 400, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    // Writing (with fsync) holds only [fileLock]: get/put from the window's thread no longer wait for the disk.
    // The copy is taken inside it, so two flushes (the writer and the exit hook) never put an older copy over a newer one.
    private val fileLock = Any()
    fun flush() { synchronized(fileLock) {
        val copy = synchronized(this) {
            if (!pending) return
            pending = false
            Properties().also { it.putAll(p) }
        }
        runCatching {
            // The data is on disk (sync) before the new file takes the name; the previous one stays as .bak.
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            java.io.FileOutputStream(tmp).use { out -> copy.store(out, null); out.fd.sync() }
            if (f.exists()) java.nio.file.Files.move(f.toPath(), bak.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
    } }
    companion object {
        private val all = java.util.concurrent.ConcurrentHashMap<String, Store>()
        fun of(name: String): Store = all.computeIfAbsent(name) { Store(it) }
        private val writer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "store-writer").apply { isDaemon = true } }
        init { Runtime.getRuntime().addShutdownHook(Thread { flushAll() }) } // nothing lost on exit
        fun flushAll() = all.values.forEach { runCatching { it.flush() } }
    }
    @Synchronized fun keys(): Set<String> = p.stringPropertyNames()
}

/** The site's JSON API with its own cookie jar (session + device cookie), kept between runs. */
object Api {
    /** Домен сайта приходит из сборки (-Dsuslik.host, секрет SITE_URL), в репозитории его нет. */
    val HOST: String = System.getProperty("suslik.host").orEmpty()
    val BASE = "https://$HOST"
    val SITE = "$BASE/media/"

    class HttpError(val code: Int, message: String) : Exception(message)

    private val cookies = Store.of("cookies")
    val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private fun cookieHeader(): String =
        cookies.keys().joinToString("; ") { "$it=${cookies.get(it)}" }

    private fun storeCookies(res: HttpResponse<*>) {
        for (c in res.headers().allValues("set-cookie")) {
            val pair = c.substringBefore(';')
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "").trim()
            if (name.isEmpty()) continue
            val expired = Regex("(?i)max-age=0|expires=Thu, 01 Jan 1970").containsMatchIn(c)
            cookies.put(name, if (expired || value.isEmpty()) null else value)
        }
    }

    fun request(path: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(if (path.startsWith("http")) path else BASE + path))
            .timeout(Duration.ofSeconds(30))
            .header("Cookie", cookieHeader())
            .apply { PlaybackSettings.sentBetaCode()?.let { header("X-Beta-Code", it) } } // tester: beta updates

    suspend fun get(path: String): JSONObject = call(request(path).GET().build())

    suspend fun post(path: String, body: JSONObject? = null): JSONObject =
        call(request(path).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString((body ?: JSONObject()).toString())).build())

    /** Upload a .torrent file as the site does (raw body, name in a header). */
    suspend fun postTorrent(bytes: ByteArray, filename: String): JSONObject =
        call(request("/api/torrents").header("Content-Type", "application/x-bittorrent")
            .header("X-Torrent-Filename", java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20"))
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build())

    /** The file, or null when the server has none (404). No network or a server error throws: worth asking again later. */
    suspend fun bytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val res = client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        storeCookies(res)
        when (res.statusCode()) {
            200 -> res.body()
            404, 410 -> null
            else -> throw HttpError(res.statusCode(), tr("api.oshibka_servera_code", "code" to res.statusCode()))
        }
    }

    suspend fun text(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val res = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            if (res.statusCode() == 200) res.body() else null
        }.getOrNull()
    }

    private suspend fun call(req: HttpRequest): JSONObject = fetch(req).json

    class Fetched(val code: Int, val json: JSONObject, val etag: String?)

    /**
     * JSON answers come gzip-compressed (Java's client does not ask for it by itself: the library list used to travel
     * uncompressed, several times bigger). 304 = unchanged since [etag] — then there is no body at all.
     */
    private suspend fun fetch(req0: HttpRequest, etag: String? = null): Fetched = withContext(Dispatchers.IO) {
        val req = HttpRequest.newBuilder(req0, { _, _ -> true }).header("Accept-Encoding", "gzip")
            .apply { if (etag != null) header("If-None-Match", etag) }.build()
        val t0 = System.currentTimeMillis()
        val res = try { client.send(req, HttpResponse.BodyHandlers.ofInputStream()) }
            catch (e: Exception) { Diag.http(req.method(), req.uri().path, null, System.currentTimeMillis() - t0, 0, e); throw e }
        storeCookies(res)
        val gz = res.headers().firstValue("content-encoding").orElse("").equals("gzip", true)
        val text = res.body().use { input -> (if (gz) java.util.zip.GZIPInputStream(input) else input).readBytes().toString(Charsets.UTF_8) }
        val code = res.statusCode()
        Diag.http(req.method(), req.uri().path, code, System.currentTimeMillis() - t0, text.length)
        if (code == 304) return@withContext Fetched(304, JSONObject(), etag)
        val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        if (code !in 200..299) throw HttpError(code, json.optString("error").ifBlank { tr("api.oshibka_servera_code", "code" to code) })
        Fetched(code, json, res.headers().firstValue("etag").orElse(null))
    }

    /** GET that may answer "not modified" (304) when the server's copy still has the same [etag]. */
    suspend fun getIfChanged(path: String, etag: String?): Fetched = fetch(request(path).GET().build(), etag)

    fun describe(e: Throwable): String = when (e) {
        is HttpError -> e.message ?: tr("api.oshibka_servera")
        is java.net.ConnectException, is java.nio.channels.UnresolvedAddressException -> tr("api.net_svyazi_s_serverom")
        is java.net.http.HttpTimeoutException -> tr("api.server_ne_otvetil_vovremya")
        is java.io.IOException -> tr("api.net_svyazi_s_serverom")
        else -> e.message ?: tr("api.neizvestnaya_oshibka")
    }
}
