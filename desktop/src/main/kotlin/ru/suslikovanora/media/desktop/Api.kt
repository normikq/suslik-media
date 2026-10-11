package ru.suslikovanora.media.desktop

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

/** App data folder: %APPDATA%\SuslikMedia on Windows, ~/.suslikmedia elsewhere. */
object AppDir {
    val dir: File by lazy {
        val base = System.getenv("APPDATA")?.let { File(it, "SuslikMedia") } ?: File(System.getProperty("user.home"), ".suslikmedia")
        base.apply { mkdirs() }
    }
    fun file(name: String) = File(dir, name)
}

/** Tiny key-value store on disk (posters, watch progress, settings). Writes are debounced by the caller's rhythm. */
class Store(name: String) {
    private val f = AppDir.file("$name.properties")
    private val p = Properties().apply { runCatching { f.inputStream().use { load(it) } } }
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
    @Synchronized fun flush() {
        if (!pending) return
        pending = false
        runCatching {
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            tmp.outputStream().use { p.store(it, null) }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }
    init { all += this }
    companion object {
        private val all = java.util.concurrent.CopyOnWriteArrayList<Store>()
        private val writer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "store-writer").apply { isDaemon = true } }
        init { Runtime.getRuntime().addShutdownHook(Thread { all.forEach { it.flush() } }) } // nothing lost on exit
    }
    @Synchronized fun keys(): Set<String> = p.stringPropertyNames()
}

/** The site's JSON API with its own cookie jar (session + device cookie), kept between runs. */
object Api {
    const val HOST = "short.suslikovanora.ru"
    const val BASE = "https://$HOST"
    const val SITE = "$BASE/media/"

    class HttpError(val code: Int, message: String) : Exception(message)

    private val cookies = Store("cookies")
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

    suspend fun get(path: String): JSONObject = call(request(path).GET().build())

    suspend fun post(path: String, body: JSONObject? = null): JSONObject =
        call(request(path).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString((body ?: JSONObject()).toString())).build())

    /** Upload a .torrent file as the site does (raw body, name in a header). */
    suspend fun postTorrent(bytes: ByteArray, filename: String): JSONObject =
        call(request("/api/torrents").header("Content-Type", "application/x-bittorrent")
            .header("X-Torrent-Filename", java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20"))
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build())

    suspend fun bytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val res = client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
            storeCookies(res)
            if (res.statusCode() == 200) res.body() else null
        }.getOrNull()
    }

    suspend fun text(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val res = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            if (res.statusCode() == 200) res.body() else null
        }.getOrNull()
    }

    private suspend fun call(req: HttpRequest): JSONObject = withContext(Dispatchers.IO) {
        val res = client.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        storeCookies(res)
        val json = runCatching { JSONObject(res.body()) }.getOrElse { JSONObject() }
        if (res.statusCode() !in 200..299) throw HttpError(res.statusCode(), json.optString("error").ifBlank { "Ошибка сервера (${res.statusCode()})" })
        json
    }

    fun describe(e: Throwable): String = when (e) {
        is HttpError -> e.message ?: "Ошибка сервера"
        is java.net.ConnectException, is java.nio.channels.UnresolvedAddressException -> "Нет связи с сервером"
        is java.net.http.HttpTimeoutException -> "Сервер не ответил вовремя"
        is java.io.IOException -> "Нет связи с сервером"
        else -> e.message ?: "Неизвестная ошибка"
    }
}
