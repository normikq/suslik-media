package ru.suslikovanora.media.net

import ru.suslikovanora.media.core.*

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The site's JSON API. Login state is the WebView's own session cookie (CookieManager), so the native
 * screens and the website tabs are always signed in (and out) together, and the server sees the same
 * device cookie ("sdev") for ownership checks.
 */
object Api {
    const val HOST = "short.suslikovanora.ru"
    const val BASE = "https://$HOST"

    class HttpError(val code: Int, message: String) : Exception(message)

    @Volatile private var lastFlush = 0L
    @Volatile private var appCtx: android.content.Context? = null
    /** Tester code («Настройки → Бета-версии»): sent with every request to our server, unlocks beta updates. */
    private fun betaCode(): String? = appCtx?.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        ?.takeIf { !it.getBoolean("beta_off", false) } // «Канал обновлений: Релиз»
        ?.getString("beta_code", null)?.takeIf { it.isNotBlank() }

    private object CookieBridge : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val ours = req.url.host == HOST
            val builder = req.newBuilder()
            if (ours) {
                CookieManager.getInstance().getCookie(BASE)?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
                betaCode()?.let { builder.header("X-Beta-Code", it) }
            }
            val res = chain.proceed(builder.build())
            if (ours) {
                val set = res.headers("Set-Cookie")
                if (set.isNotEmpty()) {
                    val cm = CookieManager.getInstance()
                    for (c in set) cm.setCookie(BASE, c)
                    // The server renews the login cookie on every answer: writing the cookie file to disk each time
                    // (dozens of times while posters load) is wasted work. Once a minute is enough; CookieManager
                    // also saves by itself, and the activity flushes when it goes to the background.
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastFlush > 60_000L) { lastFlush = now; cm.flush() }
                }
            }
            return res
        }
    }

    @Volatile private var cacheDir: java.io.File? = null
    /** Called once by the activities: enables the small HTTP cache (needs a Context for its folder). */
    fun init(ctx: android.content.Context) {
        appCtx = ctx.applicationContext
        if (cacheDir == null) cacheDir = java.io.File(ctx.applicationContext.cacheDir, "http")
    }

    /**
     * API calls (one connection pool, keep-alive, HTTP/2). With the 10 MB cache the client sends the ETag back, so an
     * unchanged library is answered "304 Not Modified" (a few hundred bytes) instead of the whole list again: less
     * data, and the radio goes back to sleep sooner.
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply { cacheDir?.let { cache(okhttp3.Cache(it, 10L * 1024 * 1024)) } }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(CookieBridge)
            .apply { if (ru.suslikovanora.media.Diag.on) eventListenerFactory(ru.suslikovanora.media.Diag.http) } // debug build: [http] timings
            .build()
    }

    /** Same connections, no HTTP cache: posters (Coil keeps its own image cache) and the update download. */
    val plain: OkHttpClient by lazy { client.newBuilder().cache(null).build() }

    /**
     * The film reserve's downloads: the same pool and connections, but short timeouts. A dead connection is noticed in
     * seconds (the reserve also has its own stall watcher) instead of the 30 s of the API client.
     */
    val reserve: OkHttpClient by lazy {
        plain.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }

    private val jsonType = "application/json".toMediaType()

    suspend fun get(path: String): JSONObject = call(Request.Builder().url(BASE + path).get().build())

    /** Upload a .torrent file the way the site does (raw body, name in a header). */
    suspend fun postTorrent(bytes: ByteArray, filename: String): JSONObject =
        call(Request.Builder().url("$BASE/api/torrents")
            .header("X-Torrent-Filename", java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20"))
            .post(bytes.toRequestBody("application/x-bittorrent".toMediaType())).build(), hedged = false)

    suspend fun post(path: String, body: JSONObject? = null): JSONObject =
        call(Request.Builder().url(BASE + path).post((body?.toString() ?: "{}").toRequestBody(jsonType)).build())

    private val watchdog by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "api-watchdog").apply { isDaemon = true } }
    }

    /**
     * One attempt. If the server's answer (headers) does not arrive within [limitMs], the call is cancelled: on a mobile
     * network a new connection sometimes hangs in the TLS handshake for the whole 30 s (2.0.2 journal: library 20 s,
     * unlock and mirror-url 30 s), while a fresh one connects in a second.
     */
    private fun attempt(request: Request, limitMs: Long?): JSONObject {
        val call = client.newCall(request)
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val task = limitMs?.let { watchdog.schedule({ timedOut.set(true); call.cancel() }, it, TimeUnit.MILLISECONDS) }
        try {
            call.execute().use { res ->
                task?.cancel(false)
                if (timedOut.get()) throw java.net.SocketTimeoutException("no answer in ${limitMs} ms")
                val text = res.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
                if (!res.isSuccessful) {
                    throw HttpError(res.code, json.optString("error").ifBlank { "Ошибка сервера (${res.code})" })
                }
                return json
            }
        } catch (e: java.io.IOException) {
            if (timedOut.get() && e !is java.net.SocketTimeoutException) throw java.net.SocketTimeoutException("no answer in ${limitMs} ms")
            throw e
        } finally {
            task?.cancel(false)
        }
    }

    private suspend fun call(request: Request, hedged: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        if (!hedged) return@withContext attempt(request, null)
        val limits = longArrayOf(6_000L, 8_000L, 10_000L)
        var n = 0
        while (true) {
            try {
                return@withContext attempt(request, limits[n])
            } catch (e: java.net.SocketTimeoutException) {
                n++
                if (n >= limits.size || kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]?.isActive == false) throw e
                ru.suslikovanora.media.AppLog.write("api: no answer for ${request.url.encodedPath}, retrying on a new connection ($n)")
                runCatching { client.connectionPool.evictAll() }
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    /** Human-readable message for any failure (no network, timeout, server error). */
    fun describe(e: Throwable): String = when (e) {
        is HttpError -> e.message ?: "Ошибка сервера"
        is java.net.UnknownHostException -> "Нет подключения к интернету"
        is java.net.SocketTimeoutException -> "Сервер не ответил вовремя"
        is java.io.IOException -> "Нет связи с сервером"
        else -> e.message ?: "Неизвестная ошибка"
    }
}
