package ru.suslikovanora.media.core.search

import java.util.Locale

/**
 * Filter presets tailored for different devices and network situations.
 */
enum class FilterPreset(val title: String, val description: String) {
    ALL("Все", "Без ограничений по сети и качеству"),
    MOBILE("📱 Мобильная сеть", "До 4 ГБ, битрейт до 4.5 Мбит/с, без 4K/Remux/экранка"),
    BALANCED("Оптимальные (1080p)", "До 12 ГБ, 1080p/720p, проверенное качество"),
    MAX_QUALITY("Макс. качество", "4K, BDRemux, Dolby Vision / HDR")
}

/**
 * Sort options for search results.
 */
enum class SearchSort(val title: String) {
    DEFAULT("По умолчанию"),
    SIZE_ASC("Сначала лёгкие (экономия трафика)"),
    SIZE_DESC("Сначала тяжёлые"),
    SEEDS_DESC("По сидам (быстрая загрузка)"),
    BITRATE_ASC("По битрейту (сначала низкий)")
}

/**
 * Cleans user queries by stripping search noise/stop-words (e.g. "фильмы", "сериал", "скачать", "смотреть").
 */
object SearchQueryCleaner {
    private val MEDIA_STOP_WORDS_REGEX = ReleaseParser.uRegex(
        """\b(?:фильм[ыа-я]*|кино(?:фильм[ыа-я]*)?|сериал[ыа-я]*|мульт(?:фильм[ыа-я]*|ик[иа-я]*)?|аниме|смотреть|скачать|онлайн|в\s*хорошем\s*качестве)\b"""
    )
    private val WHITESPACE_REGEX = Regex("""\s+""")

    fun clean(raw: String): String {
        val s = raw.trim()
        val stripped = s.replace(MEDIA_STOP_WORDS_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()

        return if (stripped.length >= 2) stripped else s
    }
}

/**
 * Complete search filter criteria used across Android and Desktop.
 */
data class SearchFilterCriteria(
    val query: String = "",
    val kind: String = "video",
    val preset: FilterPreset = FilterPreset.ALL,
    val resolution: Resolution? = null,
    val codec: VideoCodec? = null,
    val maxSizeBytes: Long? = null,
    val onlySeeded: Boolean = false,
    val hideCam: Boolean = false,
    val refine: String = "",
    val sort: SearchSort = SearchSort.DEFAULT
) {
    /**
     * Checks if a single [hit] satisfies all conditions of this filter criteria.
     */
    fun matches(hit: SearchHit): Boolean {
        // 0. Exclude trailers and teasers
        if (ReleaseParser.isTrailerOrTeaser(hit.title) || ReleaseParser.isTrailerOrTeaser(hit.release.cleanTitle)) {
            return false
        }

        // 1. Kind filter ("video", "movie", "series", "cartoon", "all")
        val matchesKind = when (kind) {
            "all" -> true
            "video" -> hit.kind != "other"
            else -> hit.kind == kind
        }
        if (!matchesKind) return false

        // 2. Only seeded check
        if (onlySeeded && hit.seeders <= 0) return false

        // 3. CAM / Telesync exclusion
        if ((hideCam || preset == FilterPreset.MOBILE || preset == FilterPreset.BALANCED) && hit.release.isCam) {
            return false
        }

        // 4. Preset constraints
        when (preset) {
            FilterPreset.MOBILE -> {
                if (!hit.release.isMobileFriendly) return false
                val isMultiItem = hit.kind == "series" || hit.release.episodesCount != null || hit.release.isCollection
                // Single movie over 4GB with bitrate > 4.5 Mbps burns excessive cellular traffic
                if (!isMultiItem && hit.size > 4L * 1024 * 1024 * 1024 && hit.release.estimatedBitrateMbps > 4.5f) {
                    return false
                }
            }
            FilterPreset.BALANCED -> {
                if (hit.release.resolution == Resolution.UHD_4K) return false
                if (hit.size > 14L * 1024 * 1024 * 1024) return false
            }
            FilterPreset.MAX_QUALITY -> {
                val isHighQ = hit.release.resolution == Resolution.UHD_4K ||
                        hit.release.ripType == RipType.REMUX ||
                        hit.release.ripType == RipType.BLURAY ||
                        hit.release.hdr != HdrType.NONE
                if (!isHighQ) return false
            }
            FilterPreset.ALL -> {}
        }

        // 5. Specific resolution
        if (resolution != null && resolution != Resolution.UNKNOWN && hit.release.resolution != resolution) {
            return false
        }

        // 6. Specific codec
        if (codec != null && codec != VideoCodec.UNKNOWN && hit.release.codec != codec) {
            return false
        }

        // 7. Max size
        if (maxSizeBytes != null && hit.size > maxSizeBytes) {
            return false
        }

        // 8. Refine substring search (matches against raw title and parsed clean title)
        if (refine.isNotBlank()) {
            val words = refine.lowercase(Locale.ROOT).split(' ').filter { it.isNotBlank() }
            val t1 = hit.title.lowercase(Locale.ROOT)
            val t2 = hit.release.cleanTitle.lowercase(Locale.ROOT)
            if (!words.all { t1.contains(it) || t2.contains(it) }) return false
        }

        return true
    }

    /**
     * Applies filter and sort criteria to a list of [SearchHit]s.
     */
    fun filter(hits: List<SearchHit>): List<SearchHit> {
        val filtered = hits.filter { matches(it) }

        return when (sort) {
            SearchSort.DEFAULT -> {
                if (query.isNotBlank()) {
                    filtered.sortedWith(
                        compareByDescending<SearchHit> { computeRelevanceScore(it, query) }
                            .thenByDescending { it.seeders }
                    )
                } else {
                    filtered.sortedByDescending { it.seeders }
                }
            }
            SearchSort.SIZE_ASC -> filtered.sortedWith(compareBy({ if (it.size <= 0) Long.MAX_VALUE else it.size }, { -it.seeders }))
            SearchSort.SIZE_DESC -> filtered.sortedByDescending { it.size }
            SearchSort.SEEDS_DESC -> filtered.sortedByDescending { it.seeders }
            SearchSort.BITRATE_ASC -> filtered.sortedWith(
                compareBy(
                    { if (it.release.estimatedBitrateMbps <= 0f) Float.MAX_VALUE else it.release.estimatedBitrateMbps },
                    { -it.seeders }
                )
            )
        }
    }

    companion object {
        private val WORD_SPLIT_REGEX = Regex("[^a-zA-Z0-9а-яА-ЯёЁ]+")
        private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")

        /**
         * Computes search relevance score for [hit] against [query].
         * Prioritizes exact phrase matches, complete query words coverage, word order, and seeders.
         */
        fun computeRelevanceScore(hit: SearchHit, query: String): Int {
            if (query.isBlank()) return hit.seeders

            val normQuery = query.trim().lowercase(Locale.ROOT).replace('-', ' ')
            val qWords = normQuery.split(WORD_SPLIT_REGEX).filter { it.length >= 2 }
            if (qWords.isEmpty()) return hit.seeders

            val normTitle = hit.title.lowercase(Locale.ROOT).replace('-', ' ')
            val normClean = hit.release.cleanTitle.lowercase(Locale.ROOT).replace('-', ' ')

            var score = 0

            // 1. Exact query phrase matching
            if (normClean.startsWith(normQuery)) {
                score += 2000 // Clean title starts with exact query phrase (e.g. "Новый Человек-паук")
            } else if (normTitle.startsWith(normQuery)) {
                score += 1600
            } else if (normClean.contains(normQuery)) {
                score += 1000 // Clean title contains exact query phrase
            } else if (normTitle.contains(normQuery)) {
                score += 700
            }

            // 2. Query words coverage
            val matchedInClean = qWords.count { normClean.contains(it) }
            val matchedInTitle = qWords.count { normTitle.contains(it) }
            val maxMatched = maxOf(matchedInClean, matchedInTitle)

            if (maxMatched == qWords.size) {
                score += 500 // All query words are present!
            } else {
                score += maxMatched * 80 // Partial word match
            }

            // 3. Word order bonus: do words appear in the same order as in query?
            var lastIdx = -1
            var inOrder = true
            for (w in qWords) {
                val idx = normClean.indexOf(w)
                if (idx == -1 || idx < lastIdx) {
                    inOrder = false
                    break
                }
                lastIdx = idx
            }
            if (inOrder && maxMatched == qWords.size) {
                score += 350
            }

            // 4. Closeness of clean title length to query length (conciseness)
            if (normClean.isNotBlank() && maxMatched == qWords.size) {
                val lenDiff = Math.abs(normClean.length - normQuery.length)
                if (lenDiff < 15) {
                    score += 200
                } else if (lenDiff < 30) {
                    score += 100
                }
            }

            // 5. Target release year bonus / penalty
            val yearMatch = YEAR_REGEX.find(normQuery)
            if (yearMatch != null) {
                val targetYearStr = yearMatch.groupValues[1]
                val targetYear = targetYearStr.toIntOrNull()
                if (targetYear != null) {
                    if (hit.release.year == targetYear || normClean.contains(targetYearStr) || normTitle.contains(targetYearStr)) {
                        score += 1500 // Huge bonus for matching requested year
                    } else if (hit.release.year != null && hit.release.year != targetYear) {
                        score -= 1500 // Penalty for a different release year
                    }
                }
            }

            // 6. Seeders score
            if (hit.seeders > 0) {
                score += minOf(hit.seeders, 50) * 2 // up to +100
            } else {
                score -= 250 // dead torrent penalty
            }

            // 7. Quality tier bonus
            when (hit.release.resolution) {
                Resolution.UHD_4K, Resolution.FHD_1080P -> score += 40
                Resolution.HD_720P -> score += 20
                else -> {}
            }

            if (hit.release.isCam) {
                score -= 300 // CAM / Экранка penalty
            }

            return score
        }
    }
}
