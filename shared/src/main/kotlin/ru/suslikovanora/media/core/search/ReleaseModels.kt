package ru.suslikovanora.media.core.search

import java.util.Locale

/**
 * Common resolution categories for torrent releases.
 */
enum class Resolution(val label: String, val order: Int) {
    UHD_4K("4K", 4),
    FHD_1080P("1080p", 3),
    HD_720P("720p", 2),
    SD("SD", 1),
    UNKNOWN("", 0);

    val isHdOrBetter: Boolean get() = this == UHD_4K || this == FHD_1080P || this == HD_720P
}

/**
 * Video codec used in the release.
 */
enum class VideoCodec(val label: String) {
    HEVC("HEVC"),
    AVC("AVC"),
    AV1("AV1"),
    OTHER("Other"),
    UNKNOWN("");

    /** HEVC/AV1 give ~2x compression efficiency over AVC at same visual quality, ideal for mobile data. */
    val isEfficient: Boolean get() = this == HEVC || this == AV1
}

/**
 * Source media / rip type.
 */
enum class RipType(val label: String) {
    REMUX("BDRemux"),
    BLURAY("Blu-ray"),
    BDRIP("BDRip"),
    WEBDL("WEB-DL"),
    WEBRIP("WEBRip"),
    HDTV("HDTV"),
    DVDRIP("DVDRip"),
    CAM("CAM/Экранка"),
    UNKNOWN("")
}

/**
 * High Dynamic Range / color metadata.
 */
enum class HdrType(val label: String) {
    DV("Dolby Vision"),
    HDR10_PLUS("HDR10+"),
    HDR10("HDR"),
    NONE("")
}

/**
 * Russian translation / voiceover classification.
 */
enum class VoiceType(val label: String) {
    DUB("Дубляж"),
    MVO("Многоголосый"),
    DVO("Двухголосый"),
    AVO("Авторский"),
    ORIGINAL("Оригинал/Sub"),
    UNKNOWN("")
}

/**
 * Network bitrate appetite for mobile and Wi-Fi streaming.
 */
enum class BitrateTier(val label: String, val hint: String) {
    LIGHT("Легкий", "Потянет моб. сеть"),
    MEDIUM("Оптимальный", "Хороший 4G / Wi-Fi"),
    HEAVY("Тяжёлый", "Высокий битрейт / Домашний Wi-Fi")
}

/**
 * Parsed release details extracted from tracker title and torrent size.
 */
data class ReleaseInfo(
    val cleanTitle: String,
    val year: Int?,
    val resolution: Resolution,
    val codec: VideoCodec,
    val ripType: RipType,
    val hdr: HdrType,
    val voice: VoiceType,
    val isCam: Boolean,
    val isCollection: Boolean = false,
    val collectionCount: Int? = null,
    val episodesCount: Int? = null,
    val estimatedBitrateMbps: Float,
    val bitrateTier: BitrateTier
) {
    /** Whether this release is suitable for mobile cellular streaming (doesn't burn data, low bitrate, no 4K/Remux). */
    val isMobileFriendly: Boolean
        get() = !isCam &&
                resolution != Resolution.UHD_4K &&
                ripType != RipType.REMUX &&
                ripType != RipType.BLURAY &&
                (estimatedBitrateMbps in 0.05f..4.5f || (estimatedBitrateMbps == 0f && resolution == Resolution.HD_720P))

    /** Quick visual badge tags to display in release cards (e.g. ["1080p", "HEVC", "WEB-DL", "Дубляж", "~2.8 Мбит/с"]). */
    val badges: List<String>
        get() = buildList {
            if (resolution != Resolution.UNKNOWN) add(resolution.label)
            if (codec != VideoCodec.UNKNOWN && codec != VideoCodec.OTHER) add(codec.label)
            if (hdr != HdrType.NONE) add(hdr.label)
            if (ripType != RipType.UNKNOWN) add(ripType.label)
            if (isCollection) {
                add(if (collectionCount != null) "Коллекция ($collectionCount)" else "Коллекция")
            }
            if (voice != VoiceType.UNKNOWN) add(voice.label)
            if (estimatedBitrateMbps > 0f) {
                val suffix = if (isCollection) " / фильм" else ""
                val rateStr = if (estimatedBitrateMbps < 1f) "< 1 Мбит/с" else String.format(Locale.US, "~%.1f Мбит/с", estimatedBitrateMbps)
                add(rateStr + suffix)
            }
        }
}
