package ru.suslikovanora.media.core.search

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Unified model for a search hit across all platforms (Android, Desktop).
 */
data class SearchHit(
    val id: String,
    val title: String,
    val size: Long,
    val seeders: Int,
    val leechers: Int,
    val ru: Boolean,
    val kind: String,
    val source: String,
    val date: String = "",
    val magnet: String? = null,
    val topicId: String? = null,
    val localTrackerId: String? = null,
    val release: ReleaseInfo = ReleaseParser.parse(title, size, kind)
) {
    companion object {
        private val defaultDateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.ROOT)

        fun fromJson(r: JSONObject, formatDate: ((Long) -> String)? = null): SearchHit? {
            val id = r.optString("id")
            if (id.isNullOrBlank()) return null

            val src = r.optJSONArray("sources")?.let { a ->
                (0 until a.length()).map { a.optString(it) }
            }?.takeIf { it.size > 1 }?.joinToString(" + ") ?: r.optString("provider")

            val d = r.opt("date").let { v ->
                when (v) {
                    is Number -> if (v.toLong() > 0) {
                        formatDate?.invoke(v.toLong()) ?: defaultDateFmt.format(Date(v.toLong()))
                    } else ""
                    is String -> v.take(10)
                    else -> ""
                }
            }

            val title = r.optString("title")
            val size = r.optLong("size", 0L)
            val kind = r.optString("kind", "movie")
            val magnet = r.optString("magnet").takeIf { it.startsWith("magnet:?") }

            return SearchHit(
                id = id,
                title = title,
                size = size,
                seeders = r.optInt("seeders", 0),
                leechers = r.optInt("leechers", 0),
                ru = r.optBoolean("ru", false),
                kind = kind,
                source = src,
                date = d,
                magnet = magnet,
                topicId = null,
                localTrackerId = null,
                release = ReleaseParser.parse(title, size, kind)
            )
        }

        fun parseHits(arr: JSONArray?): List<SearchHit> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { fromJson(it) }
            }
        }

        fun parseProviders(arr: JSONArray?): List<SearchProviderInfo> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    SearchProviderInfo(
                        name = it.optString("name"),
                        ok = it.optBoolean("ok", false),
                        count = it.optInt("count", 0),
                        error = it.optString("error", "")
                    )
                }
            }
        }
    }
}

data class SearchProviderInfo(
    val name: String,
    val ok: Boolean,
    val count: Int,
    val error: String
)
