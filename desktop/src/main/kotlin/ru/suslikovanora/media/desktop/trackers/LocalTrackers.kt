package ru.suslikovanora.media.desktop.trackers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.suslikovanora.media.core.search.ReleaseParser
import ru.suslikovanora.media.core.search.SearchHit
import ru.suslikovanora.media.desktop.Store
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale

interface LocalTracker {
    val id: String
    val name: String
    val defaultUrl: String
    var url: String
    var enabled: Boolean
    var cookie: String
    val isReady: Boolean get() = enabled && cookie.isNotBlank()

    suspend fun testConnection(): Pair<Boolean, String>
    suspend fun search(query: String): List<SearchHit>
    suspend fun downloadTorrent(topicId: String): ByteArray?
}

object LocalTrackersManager {
    val store = Store.of("local_trackers")
    val trackers: List<LocalTracker> = listOf(RuTrackerTracker, NnmClubTracker, KinozalTracker)

    fun isAnyActive(): Boolean = trackers.any { it.isReady }
}

object RuTrackerTracker : LocalTracker {
    override val id = "rutracker"
    override val name = "RuTracker.org"
    override val defaultUrl = "https://rutracker.org"

    private val store = LocalTrackersManager.store

    override var url: String
        get() = store.get("rutracker.url")?.trim()?.removeSuffix("/")?.ifBlank { defaultUrl } ?: defaultUrl
        set(value) { store.put("rutracker.url", value.trim().removeSuffix("/")) }

    override var enabled: Boolean
        get() = store.get("rutracker.enabled") == "true"
        set(value) { store.put("rutracker.enabled", value.toString()) }

    override var cookie: String
        get() = store.get("rutracker.cookie") ?: ""
        set(value) { store.put("rutracker.cookie", value.trim()) }

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val cp1251: Charset = Charset.forName("windows-1251")
    private val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.ROOT)

    private fun normalizeCookie(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("Cookie:", ignoreCase = true)) s = s.substring(7).trim()
        if (!s.contains("bb_ssl=") && s.contains("bb_session=")) {
            s += "; bb_ssl=1"
        }
        return s
    }

    private fun parseCookieMap(raw: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (part in raw.split(';')) {
            val p = part.trim()
            val eq = p.indexOf('=')
            if (eq > 0) {
                val k = p.substring(0, eq).trim()
                val v = p.substring(eq + 1).trim()
                if (k.isNotBlank() && v.isNotBlank()) map[k] = v
            }
        }
        if (!map.containsKey("bb_ssl")) map["bb_ssl"] = "1"
        return map
    }

    override suspend fun testConnection(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val c = normalizeCookie(cookie)
        if (c.isBlank()) return@withContext false to "Куки не заданы"
        val base = url.ifBlank { defaultUrl }
        val cookieMap = parseCookieMap(c)

        // 1. Проверка через браузерный мост: обходит Cloudflare Turnstile на 100%
        if (EdgeBrowserBridge.isAnyAvailable) {
            val res = EdgeBrowserBridge.checkConnection(base, cookieMap)
            if (res.first) return@withContext res
            if (res.second.contains("Сессия не принята")) return@withContext res
            if (!res.second.contains("Cloudflare") && !res.second.contains("таймаут")) return@withContext res
        }

        // 2. Запасной прямой HTTP-запрос (если браузер не установлен или отключен)
        try {
            val req = HttpRequest.newBuilder(URI.create("$base/forum/index.php"))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0")
                .header("Cookie", c)
                .header("Accept-Language", "ru,en;q=0.9")
                .GET()
                .build()

            val res = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
            val html = String(res.body(), cp1251)

            if (html.contains("Just a moment") || html.contains("challenge-platform")) {
                val tip = if (EdgeBrowserBridge.isAnyAvailable) " (нажмите «🔑 Войти через ${EdgeBrowserBridge.activeBrowserName}»)" else ""
                return@withContext false to "Cloudflare-проверка: используйте вход через браузер или зеркало rutracker.net$tip"
            }

            val userMatch = Regex("""logged-in-username[^>]*>([^<]+)""").find(html)
                ?: Regex("""user-name[^>]*>([^<]+)""").find(html)
                ?: Regex("""href="profile\.php\?mode=viewprofile[^>]*><b>([^<]+)</b>""").find(html)

            if (userMatch != null) {
                val browserNote = if (EdgeBrowserBridge.isAnyAvailable) " · ${EdgeBrowserBridge.activeBrowserName}" else ""
                return@withContext true to "✓ Авторизован как ${userMatch.groupValues[1].trim()}$browserNote"
            }

            if (html.contains("name=\"login_username\"")) {
                return@withContext false to "Сессия не принята (обновите bb_session cookie)"
            }

            false to "Не удалось проверить ответ трекера"
        } catch (e: Exception) {
            false to (e.message ?: "Ошибка подключения")
        }
    }

    private suspend fun queryRuTracker(q: String, base: String): List<SearchHit> {
        // 1. Try background Edge browser first (bypasses Cloudflare Turnstile 100%)
        if (EdgeBrowserBridge.isAvailable()) {
            val cookiesMap = parseCookieMap(cookie)
            val html = EdgeBrowserBridge.searchHtml(base, q, cookiesMap)
            if (html != null) {
                if (html.isBlank()) return emptyList()
                return parseHtml(html, base)
            }
        }

        // 2. Fallback to direct HTTP request
        val c = normalizeCookie(cookie)
        val encodedQuery = URLEncoder.encode(q, "windows-1251")
        val req = HttpRequest.newBuilder(URI.create("$base/forum/tracker.php?nm=$encodedQuery"))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0")
            .header("Cookie", c)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ru,en;q=0.9")
            .header("Referer", "$base/forum/tracker.php")
            .GET()
            .build()

        val res = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
        if (res.statusCode() == 403) {
            throw IllegalStateException("Cloudflare 403 (защита от ботов)")
        }
        if (res.statusCode() != 200) {
            throw IllegalStateException("HTTP ${res.statusCode()}")
        }

        val html = String(res.body(), cp1251)
        if (html.contains("Just a moment", ignoreCase = true) || html.contains("challenge-platform", ignoreCase = true)) {
            throw IllegalStateException("Cloudflare Turnstile проверка")
        }
        // Без входа tracker.php уводит на форму входа: это не «ничего не найдено»
        if (html.contains("name=\"login_username\"") && !html.contains("id=\"tor-tbl\"")) throw TrackerAuthException()
        return parseHtml(html, base)
    }

    override suspend fun search(query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2 || !isReady) return@withContext emptyList()
        val base = url.ifBlank { defaultUrl }

        var hits = queryRuTracker(q, base)
        // If 0 results and query has a 4-digit year, retry without the year and filter by year
        val yearMatch = Regex("""\b(19\d{2}|20\d{2})\b""").find(q)
        if (hits.isEmpty() && yearMatch != null) {
            val cleanQ = q.replace(Regex("""\b(19\d{2}|20\d{2})\b"""), "").replace(Regex("""\s+"""), " ").trim()
            if (cleanQ.length >= 2) {
                val fallbackHits = queryRuTracker(cleanQ, base)
                val targetYear = yearMatch.groupValues[1]
                val matchingYear = fallbackHits.filter { it.title.contains(targetYear) }
                hits = if (matchingYear.isNotEmpty()) matchingYear else fallbackHits
            }
        }
        hits
    }

    private fun parseHtml(html: String, base: String): List<SearchHit> {
        val out = mutableListOf<SearchHit>()
        val rowRegex = Regex("""<tr\b([^>]*)>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)

        for (m in rowRegex.findAll(html)) {
            val attrs = m.groupValues[1]
            val row = m.groupValues[2]
            if (!attrs.contains("tCenter", ignoreCase = true) && !attrs.contains("trs-tr-", ignoreCase = true) && !attrs.contains("tor_", ignoreCase = true)) {
                continue
            }

            val linkMatch = Regex("""<a\b([^>]*\btLink\b[^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE).find(row)
                ?: Regex("""<a\b([^>]*href="[^"]*viewtopic\.php\?t=\d+[^"]*"[^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE).find(row)
                ?: continue

            val topicId = Regex("""viewtopic\.php\?t=(\d+)""").find(linkMatch.groupValues[1])?.groupValues?.get(1) ?: continue
            val rawTitle = linkMatch.groupValues[2].replace(Regex("""<[^>]+>"""), "").trim()
            if (rawTitle.isBlank()) continue

            // Extract forum category name
            val forumMatch = Regex("""<a\b[^>]*\bclass="gen f"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE).find(row)
                ?: Regex("""<a\b[^>]*href="[^"]*tracker\.php\?f=\d+[^"]*"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE).find(row)
            val forumName = forumMatch?.groupValues?.get(1)?.replace(Regex("""<[^>]+>"""), "")?.trim().orEmpty()

            // Filter out games, software, music, audiobooks, books
            if (ReleaseParser.isNonVideo(rawTitle, forumName)) {
                continue
            }

            // Detect video subcategory (series, cartoon, movie)
            val forumLow = forumName.lowercase()
            val titleLow = rawTitle.lowercase()
            val detectedKind = when {
                forumLow.contains("мульт") || forumLow.contains("аниме") || titleLow.contains("мультсериал") || titleLow.contains("мультфильм") || titleLow.contains("аниме") -> "cartoon"
                forumLow.contains("сериал") || titleLow.contains("сезон") || titleLow.contains("сериал") || Regex("""\bs\d{1,2}\b""").containsMatchIn(titleLow) -> "series"
                else -> "movie"
            }

            // Magnet link (often present in table for logged in users)
            val magnet = Regex("""href="(magnet:\?xt=urn:btih:[^"]+)"""", RegexOption.IGNORE_CASE).find(row)?.groupValues?.get(1)
                ?: buildMagnet(row, rawTitle)

            // Torrent size
            val sizeBytes = Regex("""data-ts_text="(\d+)"""").find(row)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

            // Seeds & Leeches
            val seeds = Regex("""class="[^"]*\bseedmed\b[^"]*"[^>]*>([\s\S]*?)</td>""", RegexOption.IGNORE_CASE)
                .find(row)?.groupValues?.get(1)?.replace(Regex("""\D"""), "")?.toIntOrNull() ?: 0
            val leeches = Regex("""class="[^"]*\bleechmed\b[^"]*"[^>]*>([\s\S]*?)</td>""", RegexOption.IGNORE_CASE)
                .find(row)?.groupValues?.get(1)?.replace(Regex("""\D"""), "")?.toIntOrNull() ?: 0

            // Date
            val stamp = Regex("""data-ts_text="(\d{9,11})"""").findAll(row).mapNotNull { it.groupValues[1].toLongOrNull() }.lastOrNull()
            val dateStr = if (stamp != null && stamp > 0) dateFmt.format(Date(stamp * 1000L)) else ""

            out.add(
                SearchHit(
                    id = "rt_$topicId",
                    title = rawTitle,
                    size = sizeBytes,
                    seeders = seeds,
                    leechers = leeches,
                    ru = true,
                    kind = detectedKind,
                    source = "RuTracker",
                    date = dateStr,
                    magnet = magnet,
                    topicId = topicId,
                    localTrackerId = id,
                    release = ReleaseParser.parse(rawTitle, sizeBytes, detectedKind)
                )
            )
        }
        return out
    }

    private fun buildMagnet(row: String, title: String): String? {
        val hashMatch = Regex("""data-topic_hash="([0-9a-fA-F]{40})"""").find(row)
            ?: Regex("""btih:([0-9a-fA-F]{40})""").find(row)
        val hash = hashMatch?.groupValues?.get(1) ?: return null
        return "magnet:?xt=urn:btih:$hash&dn=" + URLEncoder.encode(title.take(120), "UTF-8")
    }

    override suspend fun downloadTorrent(topicId: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!isReady) return@withContext null
        val base = url.ifBlank { defaultUrl }

        // 1. Try background Edge browser
        if (EdgeBrowserBridge.isAvailable()) {
            val bytes = EdgeBrowserBridge.downloadTorrentBytes(base, topicId, parseCookieMap(cookie))
            if (bytes != null && bytes.isNotEmpty() && bytes[0] == 0x64.toByte()) {
                return@withContext bytes
            }
        }

        // 2. Fallback to direct HTTP
        val c = normalizeCookie(cookie)
        if (c.isBlank()) return@withContext null
        try {
            val req = HttpRequest.newBuilder(URI.create("$base/forum/dl.php?t=$topicId"))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0")
                .header("Cookie", c)
                .header("Referer", "$base/forum/viewtopic.php?t=$topicId")
                .GET()
                .build()

            val res = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
            val bytes = res.body()
            if (res.statusCode() == 200 && bytes.isNotEmpty() && bytes[0] == 0x64.toByte()) { // bencode 'd'
                bytes
            } else null
        } catch (e: Exception) {
            null
        }
    }
}

/** Placeholder for NNM-Club direct local PC search */
object NnmClubTracker : LocalTracker {
    override val id = "nnmclub"
    override val name = "NNM-Club"
    override val defaultUrl = "https://nnmclub.to"

    override var url: String
        get() = LocalTrackersManager.store.get("nnmclub.url") ?: defaultUrl
        set(value) { LocalTrackersManager.store.put("nnmclub.url", value) }

    override var enabled: Boolean
        get() = LocalTrackersManager.store.get("nnmclub.enabled") == "true"
        set(value) { LocalTrackersManager.store.put("nnmclub.enabled", value.toString()) }

    override var cookie: String
        get() = LocalTrackersManager.store.get("nnmclub.cookie") ?: ""
        set(value) { LocalTrackersManager.store.put("nnmclub.cookie", value) }

    override suspend fun testConnection(): Pair<Boolean, String> = false to "В разработке"
    override suspend fun search(query: String): List<SearchHit> = emptyList()
    override suspend fun downloadTorrent(topicId: String): ByteArray? = null
}

/** Placeholder for Kinozal.tv direct local PC search */
object KinozalTracker : LocalTracker {
    override val id = "kinozal"
    override val name = "Kinozal.tv"
    override val defaultUrl = "https://kinozal.tv"

    override var url: String
        get() = LocalTrackersManager.store.get("kinozal.url") ?: defaultUrl
        set(value) { LocalTrackersManager.store.put("kinozal.url", value) }

    override var enabled: Boolean
        get() = LocalTrackersManager.store.get("kinozal.enabled") == "true"
        set(value) { LocalTrackersManager.store.put("kinozal.enabled", value.toString()) }

    override var cookie: String
        get() = LocalTrackersManager.store.get("kinozal.cookie") ?: ""
        set(value) { LocalTrackersManager.store.put("kinozal.cookie", value) }

    override suspend fun testConnection(): Pair<Boolean, String> = false to "В разработке"
    override suspend fun search(query: String): List<SearchHit> = emptyList()
    override suspend fun downloadTorrent(topicId: String): ByteArray? = null
}
