package ru.suslikovanora.media.desktop.player

import ru.suslikovanora.media.desktop.Api

data class Entry(val title: String, val url: String)

/** VLC's name for a track without a name of its own (Blu-ray streams): «Track 1», «Дорожка 2». */
val GENERIC_TRACK = Regex("""(?i)(track|дорожка)\s*\d+""")

fun parseM3u(text: String): List<Entry> {
    val out = ArrayList<Entry>(); var title: String? = null
    for (raw in text.removePrefix("\uFEFF").lineSequence()) {
        val line = raw.trim()
        when {
            line.startsWith("#EXTINF") -> title = line.substringAfter(',', "").trim()
            line.startsWith("http") -> { out += Entry(title?.ifBlank { null } ?: line.substringAfterLast('/').substringBefore('?'), line); title = null }
        }
    }
    return out
}

/** Reads (and throws away) the first [bytes] of [url]: the server and the file host get ready for the real request. */
fun warmUp(url: String, bytes: Long) {
    runCatching {
        val req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(30))
            .header("Range", "bytes=0-${bytes - 1}").GET().build()
        Api.client.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream()).body().use { input ->
            val buf = ByteArray(64 * 1024); var left = bytes
            while (left > 0) { val n = input.read(buf); if (n < 0) break; left -= n }
        }
    }
}

fun fmt(ms: Long): String = ru.suslikovanora.media.core.clockText(ms)

fun fmtRate(r: Float): String = (if (r % 1f == 0f) r.toInt().toString() else r.toString().trimEnd('0')) + "×"
