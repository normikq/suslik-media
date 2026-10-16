package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*

/** The disk reserve (shared BurstProxy) wired to this app: temp folder, java.net.http downloads, the player journal. */
object Reserve {
    /** The reserve's folder (also for «Очистить кэш» before any film was opened). */
    val dir = java.io.File(System.getProperty("java.io.tmpdir"), if (Diag.on) "SuslikDebug-stream" else "SuslikMedia-stream")
    @Volatile private var ready = false
    /**
     * Its own client, replaced by a fresh one when the network changes or a request hangs ([BurstProxy.resetConnections]):
     * java.net.http has no way to drop pooled connections, and the old ones belong to a network that is gone.
     */
    private fun newClient() = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(15)).followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build()
    @Volatile private var client = newClient()
    fun init() {
        if (ready) return
        ready = true
        BurstProxy.log = { PlayerLog.write(it) }
        // Own folder for the debug build: init() empties it, and the normal app may be playing from its own at the same time.
        BurstProxy.init(dir) { url, from, to, cancel ->
            val req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(20)).header("Range", "bytes=$from-" + if (to >= 0) "$to" else "").GET().build()
            val future = client.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream())
            cancel.attach { future.cancel(true) } // a request stuck connecting can be aborted from outside
            val res = try { future.get() }
                catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? java.io.IOException) ?: java.io.IOException(e.cause?.message ?: "request failed") }
                catch (e: java.util.concurrent.CancellationException) { throw java.io.IOException("cancelled") }
            BurstProxy.UpstreamResponse(res.statusCode(), { n -> res.headers().firstValue(n).orElse(null) }, res.body())
        }
        BurstProxy.resetConnections = { client = newClient() }
        // The mirror's signature lives 12 hours: a film paused overnight gets a new address instead of 401 forever.
        BurstProxy.refreshUrl = { old ->
            MirrorLink.renewPath(old)?.let { path -> kotlinx.coroutines.runBlocking { Api.get(path) }.optString("url").ifBlank { null } }
        }
    }
}
