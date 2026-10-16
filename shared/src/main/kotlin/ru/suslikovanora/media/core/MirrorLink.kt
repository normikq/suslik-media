package ru.suslikovanora.media.core

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A signed mirror address: `https://<site>/api/file/proxy/<name>?link=…&exp=…&sig=…`. The signature lives 12 hours
 * (`MIRROR_TTL_HOURS` on the server); after that the mirror answers 401 and the app has to ask for a new address.
 */
object MirrorLink {
    /** The parameter [name] of [url], decoded; null when there is none. */
    fun param(url: String, name: String): String? =
        url.substringAfter('?', "").split('&').firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }

    /** When the signature runs out (unix seconds); null for an address without one. */
    fun expiresAt(url: String): Long? = param(url, "exp")?.toLongOrNull()

    /** The signature has run out, or will within [marginSec]. An address without one never expires. */
    fun isExpired(url: String, nowSec: Long = System.currentTimeMillis() / 1000, marginSec: Long = 60): Boolean =
        expiresAt(url)?.let { it <= nowSec + marginSec } ?: false

    /** The file name in the path (what the server puts in Content-Disposition). */
    fun fileName(url: String): String =
        url.substringBefore('?').substringAfterLast('/').let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

    /** The site's API path that signs [url]'s file again (`/api/file/mirror-url`, needs the login); null without `link`. */
    fun renewPath(url: String): String? {
        val link = param(url, "link")?.takeIf { it.isNotBlank() } ?: return null
        val enc = { v: String -> URLEncoder.encode(v, "UTF-8") }
        return "/api/file/mirror-url?link=${enc(link)}&name=${enc(fileName(url))}"
    }
}
