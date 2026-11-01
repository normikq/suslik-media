#pragma once
#include <QJsonArray>
#include <QJsonObject>
#include <QList>
#include <QString>
#include <QStringList>
#include <optional>

/// Поиск раздач: разбор названий с трекеров, выдача сервера и фильтры. Перенос shared/.../core/search
/// (ReleaseModels.kt, ReleaseParser.kt, SearchHit.kt, SearchFilter.kt) — те же правила и те же тесты.
namespace suslik::core {

enum class Resolution { UHD_4K, FHD_1080P, HD_720P, SD, UNKNOWN };
enum class VideoCodec { HEVC, AVC, AV1, OTHER, UNKNOWN };
enum class RipType { REMUX, BLURAY, BDRIP, WEBDL, WEBRIP, HDTV, DVDRIP, CAM, UNKNOWN };
enum class HdrType { DV, HDR10_PLUS, HDR10, NONE };
enum class VoiceType { DUB, MVO, DVO, AVO, ORIGINAL, UNKNOWN };
/// Аппетит к сети: лёгкий (потянет мобильная сеть), оптимальный, тяжёлый.
enum class BitrateTier { LIGHT, MEDIUM, HEAVY };

QString label(Resolution r);
QString label(VideoCodec c);
QString label(RipType r);
QString label(HdrType h);
QString label(VoiceType v);

/// Что видно по названию раздачи и её размеру.
struct ReleaseInfo {
    QString cleanTitle;
    std::optional<int> year;
    Resolution resolution = Resolution::UNKNOWN;
    VideoCodec codec = VideoCodec::UNKNOWN;
    RipType ripType = RipType::UNKNOWN;
    HdrType hdr = HdrType::NONE;
    VoiceType voice = VoiceType::UNKNOWN;
    bool isCam = false;
    bool isCollection = false;
    std::optional<int> collectionCount;
    std::optional<int> episodesCount;
    float estimatedBitrateMbps = 0;
    BitrateTier bitrateTier = BitrateTier::LIGHT;

    /// Годится для мобильной сети: не экранка, не 4K и не Remux/Blu-ray, битрейт до 4,5 Мбит/с.
    bool isMobileFriendly() const;
    /// Короткие отметки для карточки: «1080p», «HEVC», «WEB-DL», «Дубляж», «~2.8 Мбит/с».
    QStringList badges() const;
};

class ReleaseParser {
public:
    /// Трейлер или тизер (слово внутри названия вроде «Трейлерный парк» — нет).
    static bool isTrailerOrTeaser(const QString &rawTitle);
    /// Игра, программа, музыка, книга или трейлер — по названию и разделу трекера.
    static bool isNonVideo(const QString &rawTitle, const QString &forumCategory = QString());
    /// kind — категория трекера ("movie", "series", "cartoon", "other").
    static ReleaseInfo parse(const QString &rawTitle, qint64 sizeBytes = 0, const QString &kind = QStringLiteral("movie"));
    /// Название без технических меток: «Дюна / Dune (2021)».
    static QString cleanReleaseTitle(const QString &raw);
};

/// Одна раздача в выдаче (сервер или RuTracker с этого ПК).
struct SearchHit {
    QString id, title;
    qint64 size = 0;
    int seeders = 0, leechers = 0;
    bool ru = false;
    QString kind = QStringLiteral("movie");
    QString source;
    QString date;
    std::optional<QString> magnet;
    std::optional<QString> topicId;
    /// "rutracker": найдено на этом ПК (добавляется магнетом или .torrent, а не через сервер).
    std::optional<QString> localTrackerId;
    ReleaseInfo release;

    /// Разбирает название (release) по title, size и kind.
    static SearchHit make(const QString &id, const QString &title, qint64 size, int seeders, int leechers, bool ru,
                          const QString &kind, const QString &source, const QString &date = QString());
    static std::optional<SearchHit> fromJson(const QJsonObject &r);
    static QList<SearchHit> parseHits(const QJsonArray &arr);
};

struct SearchProviderInfo {
    QString name;
    bool ok = false;
    int count = 0;
    QString error;
    static QList<SearchProviderInfo> parse(const QJsonArray &arr);
};

enum class FilterPreset { ALL, MOBILE, BALANCED, MAX_QUALITY };
enum class SearchSort { DEFAULT, SIZE_ASC, SIZE_DESC, SEEDS_DESC, BITRATE_ASC };

QString presetTitle(FilterPreset p);

/// Убирает из запроса шум: «фильм», «сериал», «смотреть», «скачать»…
class SearchQueryCleaner {
public:
    static QString clean(const QString &raw);
};

/// Условия фильтра и сортировки выдачи.
struct SearchFilterCriteria {
    QString query;
    QString kind = QStringLiteral("video"); ///< "video", "movie", "series", "cartoon", "all"
    FilterPreset preset = FilterPreset::ALL;
    std::optional<Resolution> resolution;
    std::optional<VideoCodec> codec;
    std::optional<qint64> maxSizeBytes;
    bool onlySeeded = false;
    bool onlyRu = false;
    bool hideCam = false;
    QString refine;
    SearchSort sort = SearchSort::DEFAULT;

    bool matches(const SearchHit &hit) const;
    QList<SearchHit> filter(const QList<SearchHit> &hits) const;
    /// Насколько раздача подходит к запросу: точная фраза, все слова, порядок, год, сиды.
    static int computeRelevanceScore(const SearchHit &hit, const QString &query);
};

} // namespace suslik::core
