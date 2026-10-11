package ru.suslikovanora.media.core

import org.json.JSONArray

/** «Что нового»: the changelog.json each app carries (newest first) — parsing and picking what to show. */
object Changelog {
    data class Release(val version: String, val title: String, val items: List<String>)

    fun parse(text: String): List<Release> = runCatching {
        val arr = JSONArray(text)
        (0 until arr.length()).mapNotNull { k ->
            val r = arr.optJSONObject(k) ?: return@mapNotNull null
            val items = r.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
            Release(r.optString("version"), r.optString("title"), items).takeIf { it.version.isNotBlank() && items.isNotEmpty() }
        }
    }.getOrDefault(emptyList())

    /** The last [count] releases up to the installed version [current], newest first. */
    fun upTo(all: List<Release>, current: String, count: Int = 3): List<Release> =
        all.filter { !Versions.isNewer(it.version, current) }
            .sortedWith { a, b -> Versions.compare(b.version, a.version) }
            .take(count)

    /**
     * Show the window after an update? Only when there are notes newer than the ones already seen ([lastSeen] = the
     * version the window was last shown for; null = never) — a small fix without its own notes shows nothing.
     */
    fun shouldShow(releases: List<Release>, lastSeen: String?): Boolean =
        releases.isNotEmpty() && (lastSeen == null || Versions.isNewer(releases.first().version, lastSeen))
}
