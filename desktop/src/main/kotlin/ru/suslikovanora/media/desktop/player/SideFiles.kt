package ru.suslikovanora.media.desktop.player

import ru.suslikovanora.media.desktop.Api
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// Files next to the video (subtitles, external voice-overs). 18.10 journal, Cyberpunk Ep.09 with 8 voice-overs and
// 8 subtitles: the video played after 1.1 s, but mpv adds side files one by one, each ~0.6 s (an own connection to the
// mirror, then the index at the end of the file), so the last subtitles came at 11 s.

/** A subtitle file for mpv: a local copy (or the address when it could not be fetched), its name in the menu, its language. */
class SideSub(val target: String, val title: String, val lang: String?)

/** The file name in a mirror or local reserve address (…/<name>?link=…). */
fun sideFileName(url: String): String =
    runCatching { URLDecoder.decode(url.substringBefore('?').substringAfterLast('/'), "UTF-8") }.getOrDefault("")

/**
 * Where each side file lies in the torrent, by its mirror address ([mirrorUrls] fills it): the language is often only in
 * the folder — Cyberpunk has 40 subtitle files all named like the video, in "Int Sub/Arabic/", "Int Sub/English/[Netflix]/"…
 */
object SidePaths {
    private val paths = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun remember(url: String, path: String) { paths[url] = path }
    fun of(url: String): String? = paths[url]
}

private val GENERIC_DIR = Regex("""(?i)^(int\.?\s*subs?|subs?|subtitles?|субтитры|сабы)$""")

/**
 * The name of a side file in the track menu: its folders inside the torrent ("Chinese Simplified", "English [Netflix]")
 * and what its name adds to the video's ("eng Forced"); the language code from that ("eng") goes apart.
 * (mpv took the name from the address: the menu showed a piece of the signature, "TzfQ4…&exp=…&sig=…".)
 */
fun sideName(sideUrl: String, videoUrl: String): Pair<String, String?> {
    val full = sideFileName(sideUrl)
    val file = full.substringBeforeLast('.')
    val video = sideFileName(videoUrl).substringBeforeLast('.')
    val suffix = if (video.isNotEmpty() && file.length > video.length && file.startsWith(video, ignoreCase = true))
        file.substring(video.length).replace(Regex("[._]+"), " ").trim() else ""
    val lang = suffix.substringBefore(' ').takeIf { it.length in 2..3 && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' } }?.lowercase()
    val rest = if (lang != null) suffix.substringAfter(' ', "") else suffix
    // the first folder is the torrent's own, the last part is the file
    val dirs = SidePaths.of(sideUrl)?.split('/')?.dropLast(1)?.drop(1)?.filterNot { GENERIC_DIR.matches(it.trim()) }.orEmpty()
    val title = (dirs + rest).filter { it.isNotBlank() }.joinToString(" ")
        .ifBlank { lang ?: if (suffix.isEmpty() && video.isNotEmpty() && file.equals(video, ignoreCase = true)) full.substringAfterLast('.', file) else file }
    return title to lang
}

/**
 * [urls] with the files that match the remembered choice first ([saved] as the player keeps it: "Flarrow Films [rus]",
 * "English [Netflix] [eng]"): the name in the file's folders or in its menu name counts most, then the language.
 * The internal name of a voice-over is only known once mpv has opened the file, but the torrents keep each one in a
 * folder named after it ("Int Audio/Russian/Flarrow Films/…").
 */
fun preferFirst(urls: List<String>, saved: String?, videoUrl: String): List<String> {
    if (saved.isNullOrBlank() || urls.size < 2) return urls
    val lang = Regex("""\[([^\]]+)]\s*$""").find(saved)?.groupValues?.get(1)?.lowercase()
    val name = saved.replace(Regex("""\s*\[[^\]]+]\s*$"""), "").trim().lowercase()
    fun score(u: String): Int {
        val (title, l) = sideName(u, videoUrl)
        val where = (SidePaths.of(u) ?: sideFileName(u)).lowercase()
        val byName = name.length >= 2 && (title.lowercase() == name || where.contains(name))
        val byLang = lang != null && l != null && (l == lang || l.take(2) == lang.take(2))
        return (if (byName) 2 else 0) + (if (byLang) 1 else 0)
    }
    return urls.sortedByDescending { score(it) } // stable: the rest keep their order
}

private val subDir by lazy { File(System.getProperty("java.io.tmpdir"), "suslik-subs").apply { mkdirs() } }

/** Fetches all [urls] at once into local files (they are small); a file that fails stays an address for mpv. */
fun fetchSubtitles(urls: List<String>, videoUrl: String): List<SideSub> {
    // copies of the previous film are not needed any more
    runCatching { subDir.listFiles()?.forEach { it.delete() } }
    val stamp = System.nanoTime()
    val jobs = urls.mapIndexed { i, u ->
        val req = HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(20)).GET().build()
        Api.client.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).thenApply { r ->
            if (r.statusCode() !in 200..299) return@thenApply null
            val ext = sideFileName(u).substringAfterLast('.', "srt").take(8)
            File(subDir, "$stamp-$i.$ext").apply { writeBytes(r.body()) }.absolutePath
        }.exceptionally { null }
    }
    return urls.mapIndexed { i, u ->
        val local = runCatching { jobs[i].get(25, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
        val (title, lang) = sideName(u, videoUrl)
        SideSub(local ?: u, title, lang)
    }
}

/**
 * An external voice-over through the local reserve ([localUrl] = BurstProxy.wrap): its start and its last 512 KB (the MKV
 * index; the reserve keeps the end of a file in memory) are fetched now, for all voice-overs at once. mpv then reads both
 * from the reserve instead of waiting for two answers of the mirror per file.
 */
fun warmSidecar(localUrl: String) {
    runCatching {
        val total = readSome(localUrl, "bytes=0-262143") ?: return
        if (total > (512L shl 10)) readSome(localUrl, "bytes=${total - (512L shl 10)}-${total - 1}")
    }
}

/** Reads the [range] of [url] to its end; the size of the whole file from Content-Range. */
private fun readSome(url: String, range: String): Long? {
    val req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Range", range).GET().build()
    val r = Api.client.send(req, HttpResponse.BodyHandlers.ofInputStream())
    r.body().use { input -> val buf = ByteArray(64 * 1024); while (input.read(buf) >= 0) Unit }
    return r.headers().firstValue("content-range").orElse(null)?.substringAfterLast('/')?.toLongOrNull()
}
