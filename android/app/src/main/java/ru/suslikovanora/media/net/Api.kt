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

    private object CookieBridge : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val ours = req.url.host == HOST
            val builder = req.newBuilder()
            if (ours) {
                CookieManager.getInstance().getCookie(BASE)?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
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
    fun init(ctx: android.content.Context) { if (cacheDir == null) cacheDir = java.io.File(ctx.applicationContext.cacheDir, "http") }

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
            .build()
    }

    /** Same connections, no HTTP cache: posters (Coil keeps its own image cache) and the update download. */
    val plain: OkHttpClient by lazy { client.newBuilder().cache(null).build() }

    private val jsonType = "application/json".toMediaType()

    suspend fun get(path: String): JSONObject = call(Request.Builder().url(BASE + path).get().build())

    /** Upload a .torrent file the way the site does (raw body, name in a header). */
    suspend fun postTorrent(bytes: ByteArray, filename: String): JSONObject =
        call(Request.Builder().url("$BASE/api/torrents")
            .header("X-Torrent-Filename", java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20"))
            .post(bytes.toRequestBody("application/x-bittorrent".toMediaType())).build())

    suspend fun post(path: String, body: JSONObject? = null): JSONObject =
        call(Request.Builder().url(BASE + path).post((body?.toString() ?: "{}").toRequestBody(jsonType)).build())

    private suspend fun call(request: Request): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { res ->
            val text = res.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!res.isSuccessful) {
                throw HttpError(res.code, json.optString("error").ifBlank { "Ошибка сервера (${res.code})" })
            }
            json
        }
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
