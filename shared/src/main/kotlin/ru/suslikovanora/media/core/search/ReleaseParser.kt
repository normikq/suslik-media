package ru.suslikovanora.media.core.search

import java.util.Locale

/**
 * Calibrated tracker release parser for Russian and international trackers (Rutor, Rutracker,
 * Kinozal, NNM-Club, TPB, YTS, Nyaa, Jackett, Prowlarr).
 *
 * Extracts clean titles, resolution, rip type, video codec, HDR, audio voiceovers,
 * collections/franchises, episode counts and estimates streaming bitrates for cellular / Wi-Fi networks.
 */
object ReleaseParser {

    private val IC = RegexOption.IGNORE_CASE

    // --- Resolution ---
    private val RES_4K = Regex("""(?:\b|_)(?:2160p|4k|uhd|3840x2160|4к)(?:\b|_)""", IC)
    private val RES_1080P = Regex("""(?:\b|_)(?:1080[pi]|fhd|full[- ]?hd|1920x1080)(?:\b|_)""", IC)
    private val RES_720P = Regex("""(?:\b|_)(?:720p|1280x720)(?:\b|_)""", IC)
    private val RES_SD = Regex("""(?:\b|_)(?:480[pi]|576[pi]|sd)(?:\b|_)""", IC)

    // --- Video Codec ---
    private val CODEC_HEVC = Regex("""(?:\b|_)(?:hevc|h\.?265|x265)(?:\b|_)""", IC)
    private val CODEC_AVC = Regex("""(?:\b|_)(?:avc|h\.?264|x264)(?:\b|_)""", IC)
    private val CODEC_AV1 = Regex("""(?:\b|_)(?:av1)(?:\b|_)""", IC)
    private val CODEC_OTHER = Regex("""(?:\b|_)(?:xvid|divx|mpeg-?2)(?:\b|_)""", IC)

    // --- Rip Types ---
    private val RIP_REMUX = Regex("""(?:\b|_)(?:bd[- ]?remux|remux)(?:\b|_)""", IC)
    private val RIP_CAM = Regex(
        """(?:\b|_)(?:cam[- ]?rip|cam|hdcam|telesync|hd-?ts|экранка)(?:\b|_)|\[ts\]|\|\s*ts\b|звук\s*с\s*ts|\b(?:dvdscr|scr)\b""",
        IC
    )
    private val RIP_BDRIP = Regex("""(?:\b|_)(?:bd[- ]?rip|brrip|bdrip-hevc)(?:\b|_)""", IC)
    private val RIP_BLURAY = Regex("""(?:\b|_)(?:bluray|blu[- ]?ray|bdmv)(?:\b|_)""", IC)
    private val RIP_WEBDL = Regex("""(?:\b|_)(?:web-?dl(?:rip)?|webdl(?:rip)?)(?:\b|_)""", IC)
    private val RIP_WEBRIP = Regex("""(?:\b|_)(?:web-?rip|webrip)(?:\b|_)""", IC)
    private val RIP_HDTV = Regex("""(?:\b|_)(?:hdtv(?:rip)?|tvrip|satrip)(?:\b|_)""", IC)
    private val RIP_DVDRIP = Regex("""(?:\b|_)(?:dvd[- ]?rip|dvd[59])(?:\b|_)""", IC)

    // --- Dynamic Range (HDR) ---
    private val HDR_DV = Regex("""(?:\b|_)(?:dolby\s*vision|dovi)(?:\b|_)|(?:\b|_|\|)(?:dv)(?:\b|_|\||\+)""", IC)
    private val HDR_10PLUS = Regex("""(?:\b|_)(?:hdr10\+|hdr10plus)(?:\b|_)""", IC)
    private val HDR_10 = Regex("""(?:\b|_)(?:hdr10?)(?:\b|_)""", IC)

    // --- Audio / Voiceover ---
    private val VOICE_DUB = Regex(
        """(?:\b|_)(?:дублированный|дубляж|лицензия|dub)\b|(?<=[|\[(\s])(?:d|дб)\b|\[дубляж\]""",
        IC
    )
    private val VOICE_MVO = Regex(
        """(?:\b|_)(?:многоголосый|профессиональный\s*\(многоголосый\)|пм|mvo)\b""",
        IC
    )
    private val VOICE_DVO = Regex(
        """(?:\b|_)(?:двухголосый|профессиональный\s*\(двухголосый\)|пд|dvo)\b""",
        IC
    )
    private val VOICE_AVO = Regex(
        """(?:\b|_)(?:авторский|одноголосый|avo)\b""",
        IC
    )
    private val VOICE_ORIG = Regex(
        """(?:\b|_)(?:субтитры|оригинал|original)(?:\b|_)|(?:\b|_)(?:eng|jap)(?:\b|_)""",
        IC
    )

    // --- Series / Episodes count ---
    // e.g. "серии 1-9 из 9", "73 из 73", "1 of 10"
    private val EP_OF = Regex("""\b\d{1,3}\s*(?:из|of)\s*(\d{1,3})\b""", IC)
    // e.g. "[01-05x01-62]"
    private val EP_RANGE = Regex("""\[\d{1,2}(?:-\d{1,2})?x\d{1,2}-(\d{1,3})\]""", IC)
    // e.g. "серии 1-12", "эпизоды 1-8"
    private val EP_WORD_RANGE = Regex("""(?:\b(?:сери[йи]|эпизод[оыв]?)\s*\d{1,3}-(\d{1,3})\b)""", IC)
    // e.g. "S01E01-E10"
    private val EP_SX_EX = Regex("""S\d{1,2}E\d{1,3}-E?(\d{1,3})""", IC)
    private val SERIES_HINT = Regex("""\b(?:сезон|сериал|season|s\d{1,2}|сери[яи]|эпизод)\b""", IC)

    // --- Collections / Franchises / Multi-film sets ---
    private val COLLECTION_WORDS = Regex(
        """\b(коллекци[яи]|collection|boxset|box-set|антологи[яи]|anthology|все\s+части|все\s+фильмы|все\s+выпуски|полная\s+коллекция|полное\s+собрание|франшиза|киносага|сага|franchise)\b|\[коллекция\]|\(коллекция\)|\[антология\]|\(антология\)""",
        IC
    )
    private val COLLECTION_COUNT = Regex(
        """\b(\d{1,2})\s*(?:фильм[а-ов]?|movies?|parts?|частей|части|фильма)\b|\b(?:части|фильмы|parts|movies)\s*1\s*[-–—]\s*(\d{1,2})\b""",
        IC
    )
    private val YEAR_RANGE = Regex("""\b(19\d{2}|20\d{2})\s*[-–—]\s*(19\d{2}|20\d{2})\b""")
    private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")

    // Cut-off regex for cleanTitle: where technical release tags start
    private val TECH_SPLIT = Regex(
        """(?:\s*[|]\s*|\s+(?:2160p|1080[pi]|720p|480p|4k|bdremux|remux|bluray|blu-ray|bdrip|web-?dl(?:rip)?|web-?rip|hdtv(?:rip)?|dvdrip|camrip|telesync|x26[45]|hevc|avc|h\.?26[45]|aac|dts|ddp?5?\.?1)\b)""",
        IC
    )

    // --- Non-video signatures (Games, Software, Audiobooks, Music, Comics, Trailers, Teasers, etc.) ---
    // Метки, которые у фильма не встречаются: раздача с любой из них — точно не кино.
    private val GAME_STRONG_REGEX = Regex(
        """(?iU)(?:(?:\b|_)(?:fpkg|xci|nsp|fitgirl|xatab|dodi|decepticon|steam-?rip|gog|cusa\d+|ppsa\d+)(?:\b|_)|""" +
        """\b(?:rus\s*text|eng\s*text|rus\s*sound|eng\s*sound)\b|""" +
        """\b(?:goty|game\s*of\s*the\s*year)\b|""" +
        """(?:таблетка|кряк|crack)\s*[:(]?\s*(?:вшита|вшит|присутствует|не требуется))"""
    )

    // Метки, которые бывают и у фильмов: «Dune.Part.Two.2024.REPACK.1080p», «Бэтмен против Супермена [Ultimate Edition]».
    // Такую раздачу отсеиваем, только если меток две и больше (напр. «... (2020) PC | RePack»).
    private val GAME_WEAK_REGEX = Regex(
        """(?iU)(?:(?:\b|_)(?:repack|рипак|репак|pkg|pc)(?:\b|_)|""" +
        """\b(?:digital\s*deluxe|deluxe\s*edition|gold\s*edition|ultimate\s*edition)\b|""" +
        """\bv\d+\.\d+(?:\.\d+)?\b)"""
    )

    // Окончания перечислены по одному: «трейлер[а-я]*» ловил «Парни из Трейлерного парка»,
    // а «trailers?» — «Trailer Park Boys» (его отводит проверка на парк).
    private val TRAILER_OR_TEASER_REGEX = Regex(
        """(?iU)\b(?:тизер(?:[- ]?трейлер)?(?:ы|а|ов|у|е|ом|ам|ами)?|трейлер(?:ы|а|ов|у|е|ом|ам|ами)?|teasers?|trailers?)\b(?!\s*(?:парк|park))"""
    )

    fun isTrailerOrTeaser(rawTitle: String): Boolean =
        TRAILER_OR_TEASER_REGEX.containsMatchIn(rawTitle)

    private val NON_VIDEO_FORUM_KEYWORDS = listOf(
        "игр", "game", "ps3", "ps4", "ps5", "playstation", "xbox", "nintendo", "switch",
        "программ", "софт", "soft", "windows", "macos", "linux", "драйвер",
        "книг", "журнал", "аудиокниг", "литератур", "book",
        "музык", "music", "дискографи", "lossless", "flac", "саундтрек",
        "обои", "картинк", "фото", "модел"
    )

    /**
     * Checks if a release title or its tracker category belongs to games, software, music, or books.
     */
    fun isNonVideo(rawTitle: String, forumCategory: String = ""): Boolean {
        if (forumCategory.isNotBlank()) {
            val f = forumCategory.lowercase()
            if (NON_VIDEO_FORUM_KEYWORDS.any { f.contains(it) }) {
                return true
            }
        }
        if (GAME_STRONG_REGEX.containsMatchIn(rawTitle)) return true
        if (isTrailerOrTeaser(rawTitle)) return true
        return GAME_WEAK_REGEX.findAll(rawTitle).take(2).count() >= 2
    }

    /**
     * Parses release title string and size into structured [ReleaseInfo].
     *
     * @param rawTitle The release name from the tracker.
     * @param sizeBytes Torrent size in bytes (from search API).
     * @param kind Category from tracker ("movie", "series", "cartoon", "other", "unknown").
     */
    fun parse(rawTitle: String, sizeBytes: Long = 0L, kind: String = "movie"): ReleaseInfo {
        val s = rawTitle.trim()
        val isSeries = kind == "series" || SERIES_HINT.containsMatchIn(s)

        // 1. Resolution
        val resolution = when {
            RES_4K.containsMatchIn(s) -> Resolution.UHD_4K
            RES_1080P.containsMatchIn(s) -> Resolution.FHD_1080P
            RES_720P.containsMatchIn(s) -> Resolution.HD_720P
            RES_SD.containsMatchIn(s) -> Resolution.SD
            else -> Resolution.UNKNOWN
        }

        // 2. Video Codec
        val codec = when {
            CODEC_HEVC.containsMatchIn(s) -> VideoCodec.HEVC
            CODEC_AV1.containsMatchIn(s) -> VideoCodec.AV1
            CODEC_AVC.containsMatchIn(s) -> VideoCodec.AVC
            CODEC_OTHER.containsMatchIn(s) -> VideoCodec.OTHER
            else -> VideoCodec.UNKNOWN
        }

        // 3. Rip Type & CAM check
        val isCam = RIP_CAM.containsMatchIn(s)
        val ripType = when {
            isCam -> RipType.CAM
            RIP_REMUX.containsMatchIn(s) -> RipType.REMUX
            RIP_BDRIP.containsMatchIn(s) -> RipType.BDRIP
            RIP_BLURAY.containsMatchIn(s) -> RipType.BLURAY
            RIP_WEBDL.containsMatchIn(s) -> RipType.WEBDL
            RIP_WEBRIP.containsMatchIn(s) -> RipType.WEBRIP
            RIP_HDTV.containsMatchIn(s) -> RipType.HDTV
            RIP_DVDRIP.containsMatchIn(s) -> RipType.DVDRIP
            else -> RipType.UNKNOWN
        }

        // 4. HDR
        val hdr = when {
            HDR_DV.containsMatchIn(s) -> HdrType.DV
            HDR_10PLUS.containsMatchIn(s) -> HdrType.HDR10_PLUS
            HDR_10.containsMatchIn(s) -> HdrType.HDR10
            else -> HdrType.NONE
        }

        // 5. Voice
        val voice = when {
            VOICE_DUB.containsMatchIn(s) -> VoiceType.DUB
            VOICE_MVO.containsMatchIn(s) -> VoiceType.MVO
            VOICE_DVO.containsMatchIn(s) -> VoiceType.DVO
            VOICE_AVO.containsMatchIn(s) -> VoiceType.AVO
            VOICE_ORIG.containsMatchIn(s) -> VoiceType.ORIGINAL
            else -> VoiceType.UNKNOWN
        }

        // 6. Year & Year Range (Span)
        val yearRangeMatch = YEAR_RANGE.find(s)
        val yearStart = yearRangeMatch?.groupValues?.get(1)?.toIntOrNull()
        val yearEnd = yearRangeMatch?.groupValues?.get(2)?.toIntOrNull()
        val yearSpan = if (yearStart != null && yearEnd != null) (yearEnd - yearStart) else 0
        val year = yearStart ?: YEAR_REGEX.find(s)?.groupValues?.get(1)?.toIntOrNull()

        // 7. Collection / Franchise detection
        val specificCount = when {
            Regex("""\b(трилоги[яи]|trilogy)\b""", IC).containsMatchIn(s) -> 3
            Regex("""\b(дилоги[яи]|duology|dilogy)\b""", IC).containsMatchIn(s) -> 2
            Regex("""\b(квадрологи[яи]|тетралоги[яи]|quadrilogy|tetralogy)\b""", IC).containsMatchIn(s) -> 4
            Regex("""\b(пенталоги[яи])\b""", IC).containsMatchIn(s) -> 5
            Regex("""\b(гексалоги[яи])\b""", IC).containsMatchIn(s) -> 6
            else -> null
        }
        val explicitCountMatch = COLLECTION_COUNT.find(s)
        val explicitCount = explicitCountMatch?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
            ?: explicitCountMatch?.groupValues?.getOrNull(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull()

        val isCollection = !isSeries && (
            COLLECTION_WORDS.containsMatchIn(s) ||
            specificCount != null ||
            (explicitCount != null && explicitCount > 1) ||
            yearSpan >= 3
        )

        val collectionCount = if (isCollection) {
            specificCount
                ?: explicitCount
                ?: if (yearSpan >= 3) (yearSpan / 3).coerceIn(2, 10)
                else if (sizeBytes > 15L * 1024 * 1024 * 1024) (sizeBytes / (12L * 1024 * 1024 * 1024)).toInt().coerceIn(2, 8)
                else null
        } else null

        // 8. Episode count for series
        val episodesCount = if (isSeries) {
            EP_OF.find(s)?.groupValues?.get(1)?.toIntOrNull()
                ?: EP_RANGE.find(s)?.groupValues?.get(1)?.toIntOrNull()
                ?: EP_WORD_RANGE.find(s)?.groupValues?.get(1)?.toIntOrNull()
                ?: EP_SX_EX.find(s)?.groupValues?.get(1)?.toIntOrNull()
        } else null

        // 9. Estimated Bitrate (in Mbps)
        val estimatedBitrate = calculateBitrateMbps(
            sizeBytes = sizeBytes,
            isSeries = isSeries,
            episodesCount = episodesCount,
            isCollection = isCollection,
            collectionCount = collectionCount
        )
        val bitrateTier = when {
            estimatedBitrate <= 4.0f -> BitrateTier.LIGHT
            estimatedBitrate <= 12.0f -> BitrateTier.MEDIUM
            else -> BitrateTier.HEAVY
        }

        // 10. Clean Title
        val cleanTitle = cleanReleaseTitle(s)

        return ReleaseInfo(
            cleanTitle = cleanTitle,
            year = year,
            resolution = resolution,
            codec = codec,
            ripType = ripType,
            hdr = hdr,
            voice = voice,
            isCam = isCam,
            isCollection = isCollection,
            collectionCount = collectionCount,
            episodesCount = episodesCount,
            estimatedBitrateMbps = estimatedBitrate,
            bitrateTier = bitrateTier
        )
    }

    /**
     * Estimates streaming bitrate in Mbps.
     *
     * Standard assumptions:
     * - Single movie: average ~105 min (6300 seconds).
     * - Collection: calculates bitrate per individual film in the set.
     * - Series episode: average ~45 min (2700 seconds).
     */
    private fun calculateBitrateMbps(
        sizeBytes: Long,
        isSeries: Boolean,
        episodesCount: Int?,
        isCollection: Boolean = false,
        collectionCount: Int? = null
    ): Float {
        if (sizeBytes <= 0) return 0f
        val bits = sizeBytes * 8.0

        val seconds = when {
            isSeries -> {
                val count = episodesCount ?: if (sizeBytes > 5L * 1024 * 1024 * 1024) 10 else 1
                count * 2700.0 // 45 min per episode
            }
            isCollection -> {
                val count = collectionCount ?: 4
                count * 6300.0 // 105 min per film in collection
            }
            else -> {
                6300.0 // 105 min for single movie
            }
        }

        val mbps = (bits / (seconds * 1_000_000.0)).toFloat()
        // Round to 1 decimal place
        return Math.round(mbps * 10f) / 10f
    }

    /**
     * Cleans up messy tracker title string into a human-readable title.
     * Retains movie/series name, alternate language title and year/season block,
     * while cutting off trailing codec, rip, audio tags and pipes.
     */
    fun cleanReleaseTitle(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""

        // If title uses dots instead of spaces (typical in scene/P2P English releases)
        // e.g. "Dune.Part.Two.2024.1080p.WEB-DL" -> "Dune Part Two (2024)"
        val spaceCount = s.count { it == ' ' }
        val dotCount = s.count { it == '.' }
        if (dotCount > 3 && dotCount > spaceCount * 2) {
            s = s.replace('.', ' ')
        }

        // Split at technical tags block or first pipe with technical specs
        val match = TECH_SPLIT.find(s)
        val clean = if (match != null && match.range.first > 3) {
            s.substring(0, match.range.first).trim()
        } else {
            s
        }

        // Clean up trailing punctuation, hyphens, pipes, or unclosed brackets
        var result = clean.replace(Regex("""[\s|/\\-]+$"""), "").trim()
        if (result.endsWith("(") && !result.contains(")")) result = result.dropLast(1).trim()
        if (result.endsWith("[") && !result.contains("]")) result = result.dropLast(1).trim()

        return result.ifBlank { raw.trim() }
    }
}
