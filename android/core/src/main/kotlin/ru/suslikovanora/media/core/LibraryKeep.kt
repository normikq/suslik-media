package ru.suslikovanora.media.core

/**
 * What still belongs to the library after a refresh. Everything the apps keep about films that are gone from it —
 * posters, the video info line, watch progress, the last episode, a reserve still on disk — can then be deleted.
 * Both apps store these under the same keys:
 *
 *  - "magnetId|path" (progress, video info), "w:magnetId|path" (the player's resume place), "magnetId" (last episode);
 *  - posters by the lower-case search query of a tile (LibraryItem.posterQueries);
 *  - the reserve by the file's AllDebrid link.
 *
 * A key that is not of these kinds (no torrent number in it — e.g. a stream opened by a plain link) is kept.
 */
class LibraryKeep(items: List<LibraryItem>) {
    val ids: Set<Long> = items.mapTo(HashSet()) { it.m.id }
    val posterKeys: Set<String> = items.flatMapTo(HashSet()) { it.posterQueries.map { q -> q.lowercase() } }
    val links: Set<String> = items.flatMapTo(HashSet()) { it.m.files.mapNotNull { f -> f.link } }

    /** An empty list is never trusted for cleaning up (a failed or half answer must not wipe everything). */
    val usable: Boolean get() = ids.isNotEmpty()

    /** A per-film key ("magnetId|path", "w:magnetId|path", "magnetId"): keep it? */
    fun keepsKey(key: String): Boolean {
        val id = key.removePrefix("w:").substringBefore('|').toLongOrNull() ?: return true
        return id in ids
    }

    /** A poster key (lower-case query): keep it? */
    fun keepsPoster(key: String): Boolean = key in posterKeys
}
