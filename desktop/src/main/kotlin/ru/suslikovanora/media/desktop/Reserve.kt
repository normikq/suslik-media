package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*

/** The disk reserve (shared BurstProxy) wired to this app: temp folder, java.net.http downloads, the player journal. */
object Reserve {
    @Volatile private var ready = false
    fun init() {
        if (ready) return
        ready = true
        BurstProxy.log = { PlayerLog.write(it) }
        BurstProxy.init(java.io.File(System.getProperty("java.io.tmpdir"), "SuslikMedia-stream")) { url, from ->
            val req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(30)).header("Range", "bytes=$from-").GET().build()
            val res = Api.client.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream())
            BurstProxy.UpstreamResponse(res.statusCode(), { n -> res.headers().firstValue(n).orElse(null) }, res.body())
        }
    }
}
