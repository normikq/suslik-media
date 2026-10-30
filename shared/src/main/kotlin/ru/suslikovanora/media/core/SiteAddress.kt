package ru.suslikovanora.media.core

/**
 * Адрес сервера из одной настройки сборки (секрет `SITE_URL`): домен и, если сервер не в корне, путь —
 * `https://example.org` или `https://example.org/suslik`. Всё остальное от него: API — `base + "/api/…"`,
 * страницы сайта — `base + "/media/"`. Домен в репозиторий не пишем, поэтому только разбор.
 */
object SiteAddress {
    /**
     * `https://host[/path]` без `/` в конце; пусто, если адреса нет. Без схемы — `https://`; запрос и `#` отбрасываются.
     * `http://` остаётся только для отладки на своём ПК (`localhost`, `127.0.0.1`).
     */
    fun normalize(raw: String?): String {
        var s = raw.orEmpty().trim().substringBefore('#').substringBefore('?')
        val scheme = when {
            s.startsWith("https://", ignoreCase = true) -> { s = s.substring(8); "https" }
            s.startsWith("http://", ignoreCase = true) -> { s = s.substring(7); "http" }
            else -> "https"
        }
        val host = s.substringBefore('/').lowercase()
        if (host.isEmpty() || host.any { it.isWhitespace() || it == '@' || it == '\\' }) return ""
        val path = s.substring(host.length).replace(Regex("/{2,}"), "/").trimEnd('/')
        val local = host.substringBefore(':').let { it == "localhost" || it == "127.0.0.1" }
        return "${if (scheme == "http" && local) "http" else "https"}://$host$path"
    }

    /** Домен (с портом, если он есть) из [normalize]-адреса: `example.org`. */
    fun host(base: String): String = base.substringAfter("://", "").substringBefore('/')

    /** Путь сервера без `/` в конце: `""` в корне, `/suslik` под путём. */
    fun path(base: String): String = base.substringAfter("://", "").let { r -> r.indexOf('/').let { if (it < 0) "" else r.substring(it) } }

    /**
     * Адрес на нашем сервере: тот же домен и внутри пути [base]. Сюда (и только сюда) идут cookie сеанса, токен
     * устройства и бета-код; аватары с CDN Discord и постеры чужих сайтов — нет. Схема и домен — без учёта регистра,
     * путь — с учётом; адрес с `.`/`..` в пути (и в виде `%2e`, `%2f`) — не наш: сервер свернул бы его за пределы пути.
     */
    fun isOwn(url: String, base: String): Boolean {
        if (base.isEmpty()) return false
        val bScheme = base.substringBefore("://", "")
        val uScheme = url.substringBefore("://", "")
        if (bScheme.isEmpty() || !uScheme.equals(bScheme, ignoreCase = true)) return false
        val rest = url.substring(uScheme.length + 3)
        val hostEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }.let { if (it < 0) rest.length else it }
        if (!rest.substring(0, hostEnd).equals(host(base), ignoreCase = true)) return false
        val rawPath = rest.substring(hostEnd).substringBefore('#').substringBefore('?')
        val decoded = runCatching { java.net.URLDecoder.decode(rawPath.replace("+", "%2B"), Charsets.UTF_8) }.getOrNull() ?: return false
        if (decoded.split('/', '\\').any { it == "." || it == ".." }) return false
        val own = path(base)
        return rawPath == own || rawPath.startsWith("$own/")
    }
}
