package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*

/** The disk reserve (shared BurstProxy) wired to this app: temp folder, java.net.http downloads, the player journal. */
object Reserve {
    @Volatile private var ready = false
    fun init() {
        if (ready) return
        ready = true
        BurstProxy.log = { PlayerLog.write(it) }
        // Own folder for the debug build: init() empties it, and the normal app may be playing from its own at the same time.
        BurstProxy.init(java.io.File(System.getProperty("java.io.tmpdir"), if (Diag.on) "SuslikDebug-stream" else "SuslikMedia-stream")) { url, from, to, cancel ->
            val req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(20)).header("Range", "bytes=$from-" + if (to >= 0) "$to" else "").GET().build()
            val future = Api.client.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream())
            cancel.attach { future.cancel(true) } // a request stuck connecting can be aborted from outside
            val res = try { future.get() }
                catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? java.io.IOException) ?: java.io.IOException(e.cause?.message ?: "request failed") }
                catch (e: java.util.concurrent.CancellationException) { throw java.io.IOException("cancelled") }
            BurstProxy.UpstreamResponse(res.statusCode(), { n -> res.headers().firstValue(n).orElse(null) }, res.body())
        }
    }
}
