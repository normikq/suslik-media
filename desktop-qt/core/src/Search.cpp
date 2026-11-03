// Перенос android/core/.../core/search: ReleaseModels.kt, ReleaseParser.kt, SearchHit.kt, SearchFilter.kt.
#include "suslik/core/Search.h"

#include "JsonOpt.h"

#include <QDateTime>
#include <QRegularExpression>
#include <algorithm>
#include <cmath>
#include <limits>

namespace suslik::core {

namespace {

#define S QStringLiteral

/// Как uRegex в Kotlin: без учёта регистра и с Unicode-классами (\b и \w понимают кириллицу).
QRegularExpression uRx(const QString &p) {
    return QRegularExpression(p, QRegularExpression::CaseInsensitiveOption | QRegularExpression::UseUnicodePropertiesOption);
}

bool has(const QRegularExpression &rx, const QString &s) { return rx.match(s).hasMatch(); }

std::optional<int> intOf(const QString &s) {
    if (s.isEmpty()) return std::nullopt;
    bool ok = false;
    const int v = s.toInt(&ok);
    return ok ? std::optional<int>(v) : std::nullopt;
}

std::optional<int> group1(const QRegularExpression &rx, const QString &s) {
    const auto m = rx.match(s);
    return m.hasMatch() ? intOf(m.captured(1)) : std::nullopt;
}

constexpr qint64 GB = 1024LL * 1024 * 1024;

// --- Разрешение ---
const QRegularExpression &RES_4K() { static const auto r = uRx(S(R"((?:\b|_)(?:2160p|4k|uhd|3840x2160|4к)(?:\b|_))")); return r; }
const QRegularExpression &RES_1080P() { static const auto r = uRx(S(R"((?:\b|_)(?:1080[pi]|fhd|full[- ]?hd|1920x1080)(?:\b|_))")); return r; }
const QRegularExpression &RES_720P() { static const auto r = uRx(S(R"((?:\b|_)(?:720p|1280x720)(?:\b|_))")); return r; }
const QRegularExpression &RES_SD() { static const auto r = uRx(S(R"((?:\b|_)(?:480[pi]|576[pi]|sd)(?:\b|_))")); return r; }
// --- Кодек ---
const QRegularExpression &CODEC_HEVC() { static const auto r = uRx(S(R"((?:\b|_)(?:hevc|h\.?265|x265)(?:\b|_))")); return r; }
const QRegularExpression &CODEC_AVC() { static const auto r = uRx(S(R"((?:\b|_)(?:avc|h\.?264|x264)(?:\b|_))")); return r; }
const QRegularExpression &CODEC_AV1() { static const auto r = uRx(S(R"((?:\b|_)(?:av1)(?:\b|_))")); return r; }
const QRegularExpression &CODEC_OTHER() { static const auto r = uRx(S(R"((?:\b|_)(?:xvid|divx|mpeg-?2)(?:\b|_))")); return r; }
// --- Источник ---
const QRegularExpression &RIP_REMUX() { static const auto r = uRx(S(R"((?:\b|_)(?:bd[- ]?remux|remux)(?:\b|_))")); return r; }
const QRegularExpression &RIP_CAM() {
    static const auto r = uRx(S(R"rx((?:\b|_)(?:cam[- ]?rip|cam|hdcam|telesync|hd-?ts|экранка)(?:\b|_)|\[ts\]|\|\s*ts\b|звук\s*с\s*ts|\b(?:dvdscr|scr)\b)rx"));
    return r;
}
const QRegularExpression &RIP_BDRIP() { static const auto r = uRx(S(R"((?:\b|_)(?:bd[- ]?rip|brrip|bdrip-hevc)(?:\b|_))")); return r; }
const QRegularExpression &RIP_BLURAY() { static const auto r = uRx(S(R"((?:\b|_)(?:bluray|blu[- ]?ray|bdmv)(?:\b|_))")); return r; }
const QRegularExpression &RIP_WEBDL() { static const auto r = uRx(S(R"((?:\b|_)(?:web-?dl(?:rip)?|webdl(?:rip)?)(?:\b|_))")); return r; }
const QRegularExpression &RIP_WEBRIP() { static const auto r = uRx(S(R"((?:\b|_)(?:web-?rip|webrip)(?:\b|_))")); return r; }
const QRegularExpression &RIP_HDTV() { static const auto r = uRx(S(R"((?:\b|_)(?:hdtv(?:rip)?|tvrip|satrip)(?:\b|_))")); return r; }
const QRegularExpression &RIP_DVDRIP() { static const auto r = uRx(S(R"((?:\b|_)(?:dvd[- ]?rip|dvd[59])(?:\b|_))")); return r; }
// --- HDR ---
const QRegularExpression &HDR_DV() { static const auto r = uRx(S(R"((?:\b|_)(?:dolby\s*vision|dovi)(?:\b|_)|(?:\b|_|\|)(?:dv)(?:\b|_|\||\+))")); return r; }
const QRegularExpression &HDR_10PLUS() { static const auto r = uRx(S(R"((?:\b|_)(?:hdr10\+|hdr10plus)(?:\b|_))")); return r; }
const QRegularExpression &HDR_10() { static const auto r = uRx(S(R"((?:\b|_)(?:hdr10?)(?:\b|_))")); return r; }
// --- Озвучка ---
const QRegularExpression &VOICE_DUB() {
    static const auto r = uRx(S(R"rx((?:\b|_)(?:дублированный|дубляж|лицензия|dub)\b|(?<=[|\[(\s])(?:d|дб)\b|\[дубляж\])rx"));
    return r;
}
const QRegularExpression &VOICE_MVO() { static const auto r = uRx(S(R"rx((?:\b|_)(?:многоголосый|профессиональный\s*\(многоголосый\)|пм|mvo)\b)rx")); return r; }
const QRegularExpression &VOICE_DVO() { static const auto r = uRx(S(R"rx((?:\b|_)(?:двухголосый|профессиональный\s*\(двухголосый\)|пд|dvo)\b)rx")); return r; }
const QRegularExpression &VOICE_AVO() { static const auto r = uRx(S(R"((?:\b|_)(?:авторский|одноголосый|avo)\b)")); return r; }
const QRegularExpression &VOICE_ORIG() { static const auto r = uRx(S(R"((?:\b|_)(?:субтитры|оригинал|original)(?:\b|_)|(?:\b|_)(?:eng|jap)(?:\b|_))")); return r; }
// --- Серии ---
const QRegularExpression &EP_OF() { static const auto r = uRx(S(R"(\b\d{1,3}\s*(?:из|of)\s*(\d{1,3})\b)")); return r; }
const QRegularExpression &EP_RANGE() { static const auto r = uRx(S(R"(\[\d{1,2}(?:-\d{1,2})?x\d{1,2}-(\d{1,3})\])")); return r; }
const QRegularExpression &EP_WORD_RANGE() { static const auto r = uRx(S(R"((?:\b(?:сери[йи]|эпизод[оыв]?)\s*\d{1,3}-(\d{1,3})\b))")); return r; }
const QRegularExpression &EP_SX_EX() { static const auto r = uRx(S(R"(S\d{1,2}E\d{1,3}-E?(\d{1,3}))")); return r; }
const QRegularExpression &SERIES_HINT() { static const auto r = uRx(S(R"(\b(?:сезон|сериал|season|s\d{1,2}|сери[яи]|эпизод)\b)")); return r; }
// --- Сборники ---
const QRegularExpression &COLLECTION_WORDS() {
    static const auto r = uRx(S(R"rx(\b(коллекци[яи]|collection|boxset|box-set|антологи[яи]|anthology|все\s+части|все\s+фильмы|все\s+выпуски|полная\s+коллекция|полное\s+собрание|франшиза|киносага|сага|franchise)\b|\[коллекция\]|\(коллекция\)|\[антология\]|\(антология\))rx"));
    return r;
}
const QRegularExpression &COLLECTION_COUNT() {
    static const auto r = uRx(S(R"(\b(\d{1,2})\s*(?:фильм[а-ов]?|movies?|parts?|частей|части|фильма)\b|\b(?:части|фильмы|parts|movies)\s*1\s*[-–—]\s*(\d{1,2})\b)"));
    return r;
}
const QRegularExpression &TRILOGY() { static const auto r = uRx(S(R"(\b(трилоги[яи]|trilogy)\b)")); return r; }
const QRegularExpression &DUOLOGY() { static const auto r = uRx(S(R"(\b(дилоги[яи]|duology|dilogy)\b)")); return r; }
const QRegularExpression &QUADRILOGY() { static const auto r = uRx(S(R"(\b(квадрологи[яи]|тетралоги[яи]|quadrilogy|tetralogy)\b)")); return r; }
const QRegularExpression &PENTALOGY() { static const auto r = uRx(S(R"(\b(пенталоги[яи])\b)")); return r; }
const QRegularExpression &HEXALOGY() { static const auto r = uRx(S(R"(\b(гексалоги[яи])\b)")); return r; }

const QRegularExpression &YEAR_RANGE() { static const auto r = uRx(S(R"(\b(19\d{2}|20\d{2})\s*[-–—]\s*(19\d{2}|20\d{2})\b)")); return r; }
const QRegularExpression &YEAR() { static const auto r = uRx(S(R"(\b(19\d{2}|20\d{2})\b)")); return r; }

/// Где начинаются технические метки (там кончается название).
const QRegularExpression &TECH_SPLIT() {
    static const auto r = uRx(S(R"rx((?:\s*[|]\s*|\s+(?:2160p|1080[pi]|720p|480p|4k|bdremux|remux|bluray|blu-ray|bdrip|web-?dl(?:rip)?|web-?rip|hdtv(?:rip)?|dvdrip|camrip|telesync|x26[45]|hevc|avc|h\.?26[45]|aac|dts|ddp?5?\.?1)\b))rx"));
    return r;
}
const QRegularExpression &TRAILING_PUNCT() { static const QRegularExpression r(S(R"([\s|/\\-]+$)")); return r; }

// Метки, которых у фильма не бывает: раздача с любой из них — точно не кино.
const QRegularExpression &GAME_STRONG() {
    static const auto r = uRx(S(R"rx((?:(?:\b|_)(?:fpkg|xci|nsp|fitgirl|xatab|dodi|decepticon|steam-?rip|gog|cusa\d+|ppsa\d+)(?:\b|_)|)rx"
                                R"rx(\b(?:rus\s*text|eng\s*text|rus\s*sound|eng\s*sound)\b|)rx"
                                R"rx(\b(?:goty|game\s*of\s*the\s*year)\b|)rx"
                                R"rx((?:таблетка|кряк|crack)\s*[:(]?\s*(?:вшита|вшит|присутствует|не требуется)))rx"));
    return r;
}
// Метки, которые бывают и у фильмов («Dune.Part.Two.2024.REPACK.1080p»): не кино, только если их две и больше.
const QRegularExpression &GAME_WEAK() {
    static const auto r = uRx(S(R"rx((?:(?:\b|_)(?:repack|рипак|репак|pkg|pc)(?:\b|_)|)rx"
                                R"rx(\b(?:digital\s*deluxe|deluxe\s*edition|gold\s*edition|ultimate\s*edition)\b|)rx"
                                R"rx(\bv\d+\.\d+(?:\.\d+)?\b))rx"));
    return r;
}
// Окончания по одному: «трейлер[а-я]*» ловил «Парни из Трейлерного парка», «trailers?» — «Trailer Park Boys».
const QRegularExpression &TRAILER() {
    static const auto r = uRx(S(R"rx(\b(?:тизер(?:[- ]?трейлер)?(?:ы|а|ов|у|е|ом|ам|ами)?|трейлер(?:ы|а|ов|у|е|ом|ам|ами)?|teasers?|trailers?)\b(?!\s*(?:парк|park)))rx"));
    return r;
}

const QStringList VIDEO_FORUM_WHITELIST = {S("музыкальное видео"), S("видеоклип"), S("клип"), S("концерт"), S("экранизац"), S("по играм"), S("по книг")};
const QStringList NON_VIDEO_FORUM_KEYWORDS = {
    S("игр"), S("game"), S("ps3"), S("ps4"), S("ps5"), S("playstation"), S("xbox"), S("nintendo"), S("switch"),
    S("программ"), S("софт"), S("soft"), S("windows"), S("macos"), S("linux"), S("драйвер"),
    S("книги и журналы"), S("аудиокниг"), S("литератур"), S("электронные книги"), S("комикс"),
    S("музыка (lossless)"), S("дискографи"), S("lossless"), S("flac"), S("саундтрек"), S("музыка mp3"),
    S("обои"), S("картинк"), S("фотографии"), S("3d модел")};

bool anyIn(const QString &f, const QStringList &words) {
    return std::any_of(words.begin(), words.end(), [&](const QString &w) { return f.contains(w); });
}

/// Битрейт в Мбит/с: фильм ~105 мин, серия ~45 мин, сборник — на один фильм. Округление до 0,1 (как Math.round).
float bitrateMbps(qint64 sizeBytes, bool isSeries, std::optional<int> episodes, bool isCollection, std::optional<int> collectionCount) {
    if (sizeBytes <= 0) return 0.f;
    const double bits = double(sizeBytes) * 8.0;
    double seconds;
    if (isSeries) seconds = (episodes ? *episodes : (sizeBytes > 5 * GB ? 10 : 1)) * 2700.0;
    else if (isCollection) seconds = (collectionCount ? *collectionCount : 4) * 6300.0;
    else seconds = 6300.0;
    const float mbps = float(bits / (seconds * 1000000.0));
    return std::floor(mbps * 10.f + 0.5f) / 10.f;
}

} // namespace

QString label(Resolution r) {
    switch (r) {
    case Resolution::UHD_4K: return S("4K");
    case Resolution::FHD_1080P: return S("1080p");
    case Resolution::HD_720P: return S("720p");
    case Resolution::SD: return S("SD");
    default: return {};
    }
}
QString label(VideoCodec c) {
    switch (c) {
    case VideoCodec::HEVC: return S("HEVC");
    case VideoCodec::AVC: return S("AVC");
    case VideoCodec::AV1: return S("AV1");
    case VideoCodec::OTHER: return S("Other");
    default: return {};
    }
}
QString label(RipType r) {
    switch (r) {
    case RipType::REMUX: return S("BDRemux");
    case RipType::BLURAY: return S("Blu-ray");
    case RipType::BDRIP: return S("BDRip");
    case RipType::WEBDL: return S("WEB-DL");
    case RipType::WEBRIP: return S("WEBRip");
    case RipType::HDTV: return S("HDTV");
    case RipType::DVDRIP: return S("DVDRip");
    case RipType::CAM: return S("CAM/Экранка");
    default: return {};
    }
}
QString label(HdrType h) {
    switch (h) {
    case HdrType::DV: return S("Dolby Vision");
    case HdrType::HDR10_PLUS: return S("HDR10+");
    case HdrType::HDR10: return S("HDR");
    default: return {};
    }
}
QString label(VoiceType v) {
    switch (v) {
    case VoiceType::DUB: return S("Дубляж");
    case VoiceType::MVO: return S("Многоголосый");
    case VoiceType::DVO: return S("Двухголосый");
    case VoiceType::AVO: return S("Авторский");
    case VoiceType::ORIGINAL: return S("Оригинал/Sub");
    default: return {};
    }
}

bool ReleaseInfo::isMobileFriendly() const {
    return !isCam && resolution != Resolution::UHD_4K && ripType != RipType::REMUX && ripType != RipType::BLURAY &&
           ((estimatedBitrateMbps >= 0.05f && estimatedBitrateMbps <= 4.5f) ||
            (estimatedBitrateMbps == 0.f && resolution == Resolution::HD_720P));
}

QStringList ReleaseInfo::badges() const {
    QStringList out;
    if (resolution != Resolution::UNKNOWN) out << label(resolution);
    if (codec != VideoCodec::UNKNOWN && codec != VideoCodec::OTHER) out << label(codec);
    if (hdr != HdrType::NONE) out << label(hdr);
    if (ripType != RipType::UNKNOWN) out << label(ripType);
    if (isCollection) out << (collectionCount ? S("Коллекция (%1)").arg(*collectionCount) : S("Коллекция"));
    if (voice != VoiceType::UNKNOWN) out << label(voice);
    if (estimatedBitrateMbps > 0.f) {
        const QString suffix = isCollection ? S(" / фильм") : QString();
        const QString rate = estimatedBitrateMbps < 1.f ? S("< 1 Мбит/с") : QString::asprintf("~%.1f ", double(estimatedBitrateMbps)) + S("Мбит/с");
        out << rate + suffix;
    }
    return out;
}

bool ReleaseParser::isTrailerOrTeaser(const QString &rawTitle) { return has(TRAILER(), rawTitle); }

bool ReleaseParser::isNonVideo(const QString &rawTitle, const QString &forumCategory) {
    if (!forumCategory.trimmed().isEmpty()) {
        const QString f = forumCategory.toLower();
        if (!anyIn(f, VIDEO_FORUM_WHITELIST)) {
            if (anyIn(f, NON_VIDEO_FORUM_KEYWORDS)) return true;
            if ((f.contains(S("музык")) || f.contains(S("music"))) && !f.contains(S("видео")) && !f.contains(S("клип")) && !f.contains(S("концерт")))
                return true;
            if ((f.contains(S("книг")) || f.contains(S("book"))) && !f.contains(S("экранизац"))) return true;
        }
    }
    if (has(GAME_STRONG(), rawTitle)) return true;
    if (isTrailerOrTeaser(rawTitle)) return true;
    auto it = GAME_WEAK().globalMatch(rawTitle);
    int n = 0;
    while (it.hasNext() && n < 2) { it.next(); ++n; }
    return n >= 2;
}

ReleaseInfo ReleaseParser::parse(const QString &rawTitle, qint64 sizeBytes, const QString &kind) {
    const QString s = rawTitle.trimmed();
    const bool isSeries = kind == S("series") || has(SERIES_HINT(), s);
    ReleaseInfo r;

    if (has(RES_4K(), s)) r.resolution = Resolution::UHD_4K;
    else if (has(RES_1080P(), s)) r.resolution = Resolution::FHD_1080P;
    else if (has(RES_720P(), s)) r.resolution = Resolution::HD_720P;
    else if (has(RES_SD(), s)) r.resolution = Resolution::SD;

    if (has(CODEC_HEVC(), s)) r.codec = VideoCodec::HEVC;
    else if (has(CODEC_AV1(), s)) r.codec = VideoCodec::AV1;
    else if (has(CODEC_AVC(), s)) r.codec = VideoCodec::AVC;
    else if (has(CODEC_OTHER(), s)) r.codec = VideoCodec::OTHER;

    r.isCam = has(RIP_CAM(), s);
    if (r.isCam) r.ripType = RipType::CAM;
    else if (has(RIP_REMUX(), s)) r.ripType = RipType::REMUX;
    else if (has(RIP_BDRIP(), s)) r.ripType = RipType::BDRIP;
    else if (has(RIP_BLURAY(), s)) r.ripType = RipType::BLURAY;
    else if (has(RIP_WEBDL(), s)) r.ripType = RipType::WEBDL;
    else if (has(RIP_WEBRIP(), s)) r.ripType = RipType::WEBRIP;
    else if (has(RIP_HDTV(), s)) r.ripType = RipType::HDTV;
    else if (has(RIP_DVDRIP(), s)) r.ripType = RipType::DVDRIP;

    if (has(HDR_DV(), s)) r.hdr = HdrType::DV;
    else if (has(HDR_10PLUS(), s)) r.hdr = HdrType::HDR10_PLUS;
    else if (has(HDR_10(), s)) r.hdr = HdrType::HDR10;

    if (has(VOICE_DUB(), s)) r.voice = VoiceType::DUB;
    else if (has(VOICE_MVO(), s)) r.voice = VoiceType::MVO;
    else if (has(VOICE_DVO(), s)) r.voice = VoiceType::DVO;
    else if (has(VOICE_AVO(), s)) r.voice = VoiceType::AVO;
    else if (has(VOICE_ORIG(), s)) r.voice = VoiceType::ORIGINAL;

    // Год и годы («2002-2023»)
    const auto range = YEAR_RANGE().match(s);
    const auto yearStart = range.hasMatch() ? intOf(range.captured(1)) : std::nullopt;
    const auto yearEnd = range.hasMatch() ? intOf(range.captured(2)) : std::nullopt;
    const int yearSpan = yearStart && yearEnd ? *yearEnd - *yearStart : 0;
    r.year = yearStart ? yearStart : group1(YEAR(), s);

    // Сборник
    std::optional<int> specific;
    if (has(TRILOGY(), s)) specific = 3;
    else if (has(DUOLOGY(), s)) specific = 2;
    else if (has(QUADRILOGY(), s)) specific = 4;
    else if (has(PENTALOGY(), s)) specific = 5;
    else if (has(HEXALOGY(), s)) specific = 6;
    const auto countMatch = COLLECTION_COUNT().match(s);
    std::optional<int> explicitCount;
    if (countMatch.hasMatch()) {
        explicitCount = intOf(countMatch.captured(1));
        if (!explicitCount) explicitCount = intOf(countMatch.captured(2));
    }
    r.isCollection = !isSeries && (has(COLLECTION_WORDS(), s) || specific || (explicitCount && *explicitCount > 1) || yearSpan >= 3);
    if (r.isCollection) {
        if (specific) r.collectionCount = specific;
        else if (explicitCount) r.collectionCount = explicitCount;
        else if (yearSpan >= 3) r.collectionCount = std::clamp(yearSpan / 3, 2, 10);
        else if (sizeBytes > 15 * GB) r.collectionCount = std::clamp(int(sizeBytes / (12 * GB)), 2, 8);
    }

    // Серии
    if (isSeries) {
        r.episodesCount = group1(EP_OF(), s);
        if (!r.episodesCount) r.episodesCount = group1(EP_RANGE(), s);
        if (!r.episodesCount) r.episodesCount = group1(EP_WORD_RANGE(), s);
        if (!r.episodesCount) r.episodesCount = group1(EP_SX_EX(), s);
    }

    r.estimatedBitrateMbps = bitrateMbps(sizeBytes, isSeries, r.episodesCount, r.isCollection, r.collectionCount);
    r.bitrateTier = r.estimatedBitrateMbps <= 4.0f ? BitrateTier::LIGHT : r.estimatedBitrateMbps <= 12.0f ? BitrateTier::MEDIUM : BitrateTier::HEAVY;
    r.cleanTitle = cleanReleaseTitle(s);
    return r;
}

QString ReleaseParser::cleanReleaseTitle(const QString &raw) {
    QString s = raw.trimmed();
    if (s.isEmpty()) return {};
    // Точки вместо пробелов (англоязычные сцены): «Dune.Part.Two.2024.1080p» → «Dune Part Two 2024 1080p»
    const qsizetype spaces = s.count(QLatin1Char(' '));
    const qsizetype dots = s.count(QLatin1Char('.'));
    if (dots > 3 && dots > spaces * 2) s.replace(QLatin1Char('.'), QLatin1Char(' '));

    const auto m = TECH_SPLIT().match(s);
    const QString clean = m.hasMatch() && m.capturedStart() > 3 ? s.left(m.capturedStart()).trimmed() : s;
    QString result = QString(clean).replace(TRAILING_PUNCT(), QString()).trimmed();
    if (result.endsWith(QLatin1Char('(')) && !result.contains(QLatin1Char(')'))) result = result.chopped(1).trimmed();
    if (result.endsWith(QLatin1Char('[')) && !result.contains(QLatin1Char(']'))) result = result.chopped(1).trimmed();
    return result.trimmed().isEmpty() ? raw.trimmed() : result;
}

// ---- SearchHit ----

SearchHit SearchHit::make(const QString &id, const QString &title, qint64 size, int seeders, int leechers, bool ru,
                          const QString &kind, const QString &source, const QString &date) {
    SearchHit h;
    h.id = id; h.title = title; h.size = size; h.seeders = seeders; h.leechers = leechers; h.ru = ru;
    h.kind = kind; h.source = source; h.date = date;
    h.release = ReleaseParser::parse(title, size, kind);
    return h;
}

std::optional<SearchHit> SearchHit::fromJson(const QJsonObject &r) {
    const QString id = json::optString(r, S("id"));
    if (id.trimmed().isEmpty() || id == S("null")) return std::nullopt;

    QString src;
    const QJsonArray sources = r.value(S("sources")).toArray();
    if (sources.size() > 1) {
        QStringList names;
        for (const auto &v : sources) names << (v.isString() ? v.toString() : json::optString(QJsonObject{{S("v"), v}}, S("v")));
        src = names.join(S(" + "));
    } else {
        src = json::optString(r, S("provider"));
    }

    QString date;
    const QJsonValue dv = r.value(S("date"));
    if (dv.isDouble()) {
        const qint64 ms = qint64(dv.toDouble());
        if (ms > 0) date = QDateTime::fromMSecsSinceEpoch(ms).toString(S("dd.MM.yyyy"));
    } else if (dv.isString()) {
        date = dv.toString().left(10);
    }

    const QString kind = json::hasValue(r, S("kind")) ? json::optString(r, S("kind")) : S("movie");
    SearchHit h = make(id, json::optString(r, S("title")), json::optLong(r, S("size"), 0), int(json::optLong(r, S("seeders"), 0)),
                       int(json::optLong(r, S("leechers"), 0)), json::optBool(r, S("ru"), false), kind, src, date);
    const QString magnet = json::optString(r, S("magnet"));
    if (magnet.startsWith(S("magnet:?"))) h.magnet = magnet;
    return h;
}

QList<SearchHit> SearchHit::parseHits(const QJsonArray &arr) {
    QList<SearchHit> out;
    for (const auto &v : arr)
        if (v.isObject())
            if (auto h = fromJson(v.toObject())) out << *h;
    return out;
}

QList<SearchProviderInfo> SearchProviderInfo::parse(const QJsonArray &arr) {
    QList<SearchProviderInfo> out;
    for (const auto &v : arr) {
        if (!v.isObject()) continue;
        const QJsonObject o = v.toObject();
        SearchProviderInfo p;
        p.name = json::optString(o, S("name"));
        p.ok = json::optBool(o, S("ok"), false);
        p.count = int(json::optLong(o, S("count"), 0));
        p.error = json::hasValue(o, S("error")) ? json::optString(o, S("error")) : QString();
        out << p;
    }
    return out;
}

// ---- Фильтр ----

QString presetTitle(FilterPreset p) {
    switch (p) {
    case FilterPreset::MOBILE: return S("📱 Мобильная сеть");
    case FilterPreset::BALANCED: return S("Оптимальные (1080p)");
    case FilterPreset::MAX_QUALITY: return S("Макс. качество");
    default: return S("Все");
    }
}

QString SearchQueryCleaner::clean(const QString &raw) {
    static const auto stop = uRx(S(R"rx(\b(?:фильм[ыа-я]*|кино(?:фильм[ыа-я]*)?|сериал[ыа-я]*|мульт(?:фильм[ыа-я]*|ик[иа-я]*)?|аниме|смотреть|скачать|онлайн|в\s*хорошем\s*качестве)\b)rx"));
    static const QRegularExpression ws(S(R"(\s+)"));
    const QString s = raw.trimmed();
    const QString stripped = QString(s).replace(stop, S(" ")).replace(ws, S(" ")).trimmed();
    return stripped.length() >= 2 ? stripped : s;
}

bool SearchFilterCriteria::matches(const SearchHit &hit) const {
    // 0. Трейлеры и тизеры
    if (ReleaseParser::isTrailerOrTeaser(hit.title) || ReleaseParser::isTrailerOrTeaser(hit.release.cleanTitle)) return false;

    // 1. Тип
    if (kind == S("video")) { if (hit.kind == S("other")) return false; }
    else if (kind != S("all") && hit.kind != kind) return false;

    // 2. Сиды и русские трекеры
    if (onlySeeded && hit.seeders <= 0) return false;
    if (onlyRu && !hit.ru) return false;

    // 3. Экранки
    if ((hideCam || preset == FilterPreset::MOBILE || preset == FilterPreset::BALANCED) && hit.release.isCam) return false;

    // 4. Набор
    switch (preset) {
    case FilterPreset::MOBILE: {
        if (!hit.release.isMobileFriendly()) return false;
        const bool multi = hit.kind == S("series") || hit.release.episodesCount || hit.release.isCollection;
        // Один фильм больше 4 ГБ и битрейт выше 4,5 Мбит/с — слишком много трафика для мобильной сети
        if (!multi && hit.size > 4 * GB && hit.release.estimatedBitrateMbps > 4.5f) return false;
        break;
    }
    case FilterPreset::BALANCED:
        if (hit.release.resolution == Resolution::UHD_4K) return false;
        if (hit.size > 14 * GB) return false;
        break;
    case FilterPreset::MAX_QUALITY: {
        const bool high = hit.release.resolution == Resolution::UHD_4K || hit.release.ripType == RipType::REMUX ||
                          hit.release.ripType == RipType::BLURAY || hit.release.hdr != HdrType::NONE;
        if (!high) return false;
        break;
    }
    case FilterPreset::ALL: break;
    }

    // 5–7. Разрешение, кодек, размер
    if (resolution && *resolution != Resolution::UNKNOWN && hit.release.resolution != *resolution) return false;
    if (codec && *codec != VideoCodec::UNKNOWN && hit.release.codec != *codec) return false;
    if (maxSizeBytes && hit.size > *maxSizeBytes) return false;

    // 8. «Уточнить»: все слова — в названии или в чистом названии
    if (!refine.trimmed().isEmpty()) {
        const QString t1 = hit.title.toLower(), t2 = hit.release.cleanTitle.toLower();
        const auto words = refine.toLower().split(QLatin1Char(' '), Qt::SkipEmptyParts);
        for (const auto &w : words) {
            if (w.trimmed().isEmpty()) continue;
            if (!t1.contains(w) && !t2.contains(w)) return false;
        }
    }
    return true;
}

QList<SearchHit> SearchFilterCriteria::filter(const QList<SearchHit> &hits) const {
    QList<SearchHit> out;
    for (const auto &h : hits)
        if (matches(h)) out << h;

    auto bySeedsDesc = [](const SearchHit &a, const SearchHit &b) { return a.seeders > b.seeders; };
    switch (sort) {
    case SearchSort::DEFAULT:
        if (!query.trimmed().isEmpty()) {
            QList<std::pair<int, SearchHit>> scored;
            scored.reserve(out.size());
            for (const auto &h : out) scored.append({computeRelevanceScore(h, query), h});
            std::stable_sort(scored.begin(), scored.end(), [](const auto &a, const auto &b) {
                if (a.first != b.first) return a.first > b.first;
                return a.second.seeders > b.second.seeders;
            });
            out.clear();
            for (const auto &p : scored) out << p.second;
        } else {
            std::stable_sort(out.begin(), out.end(), bySeedsDesc);
        }
        break;
    case SearchSort::SIZE_ASC:
        std::stable_sort(out.begin(), out.end(), [](const SearchHit &a, const SearchHit &b) {
            const qint64 sa = a.size <= 0 ? std::numeric_limits<qint64>::max() : a.size;
            const qint64 sb = b.size <= 0 ? std::numeric_limits<qint64>::max() : b.size;
            if (sa != sb) return sa < sb;
            return a.seeders > b.seeders;
        });
        break;
    case SearchSort::SIZE_DESC:
        std::stable_sort(out.begin(), out.end(), [](const SearchHit &a, const SearchHit &b) { return a.size > b.size; });
        break;
    case SearchSort::SEEDS_DESC:
        std::stable_sort(out.begin(), out.end(), bySeedsDesc);
        break;
    case SearchSort::BITRATE_ASC:
        std::stable_sort(out.begin(), out.end(), [](const SearchHit &a, const SearchHit &b) {
            const float ra = a.release.estimatedBitrateMbps <= 0.f ? std::numeric_limits<float>::max() : a.release.estimatedBitrateMbps;
            const float rb = b.release.estimatedBitrateMbps <= 0.f ? std::numeric_limits<float>::max() : b.release.estimatedBitrateMbps;
            if (ra != rb) return ra < rb;
            return a.seeders > b.seeders;
        });
        break;
    }
    return out;
}

int SearchFilterCriteria::computeRelevanceScore(const SearchHit &hit, const QString &query) {
    if (query.trimmed().isEmpty()) return hit.seeders;
    static const QRegularExpression split(S("[^a-zA-Z0-9а-яА-ЯёЁ]+"));
    static const QRegularExpression year(S(R"(\b(19\d{2}|20\d{2})\b)"), QRegularExpression::UseUnicodePropertiesOption);

    const QString normQuery = query.trimmed().toLower().replace(QLatin1Char('-'), QLatin1Char(' '));
    QStringList qWords;
    for (const auto &w : normQuery.split(split))
        if (w.length() >= 2) qWords << w;
    if (qWords.isEmpty()) return hit.seeders;

    const QString normTitle = hit.title.toLower().replace(QLatin1Char('-'), QLatin1Char(' '));
    const QString normClean = hit.release.cleanTitle.toLower().replace(QLatin1Char('-'), QLatin1Char(' '));
    int score = 0;

    // 1. Точная фраза
    if (normClean.startsWith(normQuery)) score += 2000;
    else if (normTitle.startsWith(normQuery)) score += 1600;
    else if (normClean.contains(normQuery)) score += 1000;
    else if (normTitle.contains(normQuery)) score += 700;

    // 2. Сколько слов запроса нашлось
    int inClean = 0, inTitle = 0;
    for (const auto &w : qWords) { if (normClean.contains(w)) ++inClean; if (normTitle.contains(w)) ++inTitle; }
    const int maxMatched = std::max(inClean, inTitle);
    const bool all = maxMatched == qWords.size();
    score += all ? 500 : maxMatched * 80;

    // 3. Слова в том же порядке
    qsizetype last = -1;
    bool inOrder = true;
    for (const auto &w : qWords) {
        const qsizetype idx = normClean.indexOf(w);
        if (idx == -1 || idx < last) { inOrder = false; break; }
        last = idx;
    }
    if (inOrder && all) score += 350;

    // 4. Краткость
    if (!normClean.trimmed().isEmpty() && all) {
        const qsizetype diff = std::abs(normClean.length() - normQuery.length());
        if (diff < 15) score += 200;
        else if (diff < 30) score += 100;
    }

    // 5. Год из запроса
    const auto ym = year.match(normQuery);
    if (ym.hasMatch()) {
        const QString ys = ym.captured(1);
        const int target = ys.toInt();
        if ((hit.release.year && *hit.release.year == target) || normClean.contains(ys) || normTitle.contains(ys)) score += 1500;
        else if (hit.release.year && *hit.release.year != target) score -= 1500;
    }

    // 6. Сиды
    if (hit.seeders > 0) score += std::min(hit.seeders, 50) * 2;
    else score -= 250;

    // 7. Качество
    if (hit.release.resolution == Resolution::UHD_4K || hit.release.resolution == Resolution::FHD_1080P) score += 40;
    else if (hit.release.resolution == Resolution::HD_720P) score += 20;
    if (hit.release.isCam) score -= 300;
    return score;
}

} // namespace suslik::core
