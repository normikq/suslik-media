#pragma once
#include <QHash>
#include <QJsonObject>
#include <QList>
#include <QString>
#include <QStringList>
#include <functional>
#include <optional>
#include <utility>

/// Медиатека: разбор /api/library, серии и сезоны, сборники, слияние версий, субтитры и озвучки к видео.
/// Перенос android/core/.../core/Model.kt — те же правила (они же на сайте и на ТВ), те же тесты.
namespace suslik::core {

/// Элемент дерева файлов раздачи, развёрнутого сервером (папки тоже, чтобы номера совпадали с сервером).
struct MFile {
    int index = 0;
    QString name;
    QString path;
    bool isFolder = false;
    qint64 size = 0;
    std::optional<QString> link;
    bool operator==(const MFile &) const = default;
};

/// Название и сезон, предложенные ИИ сервера (services/aititle.js). status: suggested / accepted / rejected / none.
struct AiTitle {
    QString base;
    std::optional<int> season;
    QString model;
    qint64 at = 0;
    QString status;
    std::optional<QString> kind; ///< movie | series | collection
    bool stale = false;
    std::optional<QString> original;
    std::optional<int> year;
    std::optional<QString> edition;
    std::optional<QString> canonicalId;

    bool accepted() const { return status == QLatin1String("accepted") && !base.trimmed().isEmpty(); }
    /// «Dune (2021)»: сервер берёт результат TMDB этого года. Пусто без оригинального названия.
    std::optional<QString> posterQuery() const;
    bool operator==(const AiTitle &) const = default;
};

struct Magnet {
    qint64 id = 0;
    QString name;
    QString status;
    int statusCode = 0;
    qint64 size = 0;
    qint64 downloaded = 0;
    QList<MFile> files;
    bool canRemove = true;
    bool favorite = false;   ///< на этом устройстве, первыми в медиатеке
    bool mine = false;       ///< в «Моих фильмах» владельца
    std::optional<AiTitle> aiTitle;
    bool canAiTitle = false; ///< этот пользователь добавил готовую раздачу (или админ)

    bool ready() const { return statusCode == 4; }
    bool downloading() const { return statusCode < 4; }
    bool failed() const { return statusCode >= 5; }
    float progress() const;
    const AiTitle *acceptedAi() const { return aiTitle && aiTitle->accepted() ? &*aiTitle : nullptr; }
    bool operator==(const Magnet &) const = default;
};

std::optional<AiTitle> parseAiTitle(const QJsonObject *a);
/// Когда пользователь снова может спросить ИИ (мс сервера, 0 — сейчас): `aiTitleReadyAt` из /api/library.
qint64 aiTitleReadyAt(const QJsonObject &json);
QList<Magnet> parseLibrary(const QJsonObject &json);

/// Отрезок видео [startMs, endMs).
struct TimeRange {
    qint64 startMs = 0;
    qint64 endMs = 0;
    bool contains(qint64 t) const { return t >= startMs && t < endMs; }
    qint64 lengthMs() const { return endMs - startMs; }
    bool operator==(const TimeRange &) const = default;
};

/// Где заставка и финальные титры (MarkersDetector: главы файла, потом сервер).
struct MediaMarkers {
    std::optional<TimeRange> intro;
    std::optional<TimeRange> credits;
    bool isEmpty() const { return !intro && !credits; }
    bool operator==(const MediaMarkers &) const = default;
};

struct Episode {
    int season = 0;
    int episode = 0;
    MFile file;
    std::optional<QString> title; ///< фильм сборника
    bool operator==(const Episode &) const = default;
};

struct Season {
    int season = 0;
    QList<Episode> items;
    bool operator==(const Season &) const = default;
};

/// Что играть: фильм (один файл), сериал (сезоны) или ничего.
struct Playable {
    enum class Kind { None, Movie, Series };
    Kind kind = Kind::None;
    MFile file;            ///< Movie
    QList<Season> seasons; ///< Series

    static Playable none() { return {}; }
    static Playable movie(const MFile &f) { return {Kind::Movie, f, {}}; }
    static Playable series(QList<Season> s) { return {Kind::Series, {}, std::move(s)}; }
    bool isNone() const { return kind == Kind::None; }
    bool isMovie() const { return kind == Kind::Movie; }
    bool isSeries() const { return kind == Kind::Series; }
    std::optional<MFile> movieFile() const { return isMovie() && !file.path.isEmpty() ? std::optional<MFile>(file) : std::nullopt; }
    bool operator==(const Playable &) const = default;
};

/// Всё, что нужно плитке: считается один раз на загрузку медиатеки.
struct LibraryItem {
    Magnet m;
    Playable playable;
    QString baseTitle;
    QStringList posterQueries;
    QString searchText;
    /// То же с изменённым ответом ИИ: название и запросы постера следуют за ним.
    LibraryItem withAiTitle(const std::optional<AiTitle> &ai) const;
    bool operator==(const LibraryItem &) const = default;
};

/// Сезон, который называет раздача, если она называет ровно один.
std::optional<int> nameSeason(const QString &name);
std::optional<std::pair<int, int>> episodePair(const QString &path, int defaultSeason = 1);
QString parseEdition(const QString &name);
std::optional<MFile> mainVideo(const Magnet &m);
/// Что играть: по файлам, потом вид и сезон, принятые от ИИ.
Playable buildPlayable(const Magnet &m);
Playable applyAiTitle(const Playable &p, const Magnet &m);
/// Набор фильмов, играемый списком.
bool isCollection(const Playable &p, const Magnet *m = nullptr);

enum class CollectionKind { Trilogy, Dilogy, Tetralogy, Compilation, Anthology, Collection, Franchise };
QString collectionKindLabel(CollectionKind k);
/// Ключ строки в strings/ru.json приложений.
QString collectionKindTrKey(CollectionKind k);
CollectionKind collectionKind(int filmCount, const QString &name = {});

/// pluralRu(3, "фильм", "фильма", "фильмов") = "фильма".
QString pluralRu(int n, const QString &one, const QString &few, const QString &many);
QString filmCountRu(int count);
QList<Episode> collectionFilms(const LibraryItem &item);
QString cleanCollectionFilmTitle(const QString &raw);
QString collectionFileTitle(const Magnet &m, const MFile &file, const std::optional<QString> &baseTitle = std::nullopt);
QStringList collectionFilePosterQueries(const Magnet &m, const MFile &file, const std::optional<QString> &baseTitle = std::nullopt);
QStringList continueItemPosterQueries(const LibraryItem &item, const MFile &file);
QString continueItemTitle(const LibraryItem &item, const MFile &file);

/// Название раздачи → простое название для поиска постера (как escapeTitle на сайте).
QString cleanTitle(const QString &value);
/// Ключ «того же названия»: без отметок сезона и серии.
QString titleKey(const QString &baseTitle);
QString formatBytes(qint64 value);
QString statusRu(const Magnet &m);
QString tileTitle(const LibraryItem &item, const std::function<std::optional<QString>(const QString &)> &ruTitleOf);
QString episodeLabel(const QString &base, const Season &s, const Episode &e);
std::optional<Season> watchedSeason(const LibraryItem &item, const QString &path);
QList<LibraryItem> prepareItems(const QList<Magnet> &list);
/// «Сезоны 1–3 · 24 серии» (или «3 фильма» для сборника).
QString seriesLine(const Playable &series);

struct VersionSection {
    std::optional<QString> title;
    QList<LibraryItem> items;
};

struct MediaVariant {
    QString key;
    qint64 adId = 0;
    QString label;
    QString resolution;
    QString sourceType;
    QString videoTech;
    QString audioTech;
    double bitrateMbps = 0.0;
    qint64 sizeBytes = 0;
    qint64 durationSec = 0;
    qint64 offsetMs = 0;
    int score = 0;
    QStringList hardwareFlags;
    bool operator==(const MediaVariant &) const = default;
};

std::optional<int> extractYear(const QString &name);
std::optional<int> releaseYear(const LibraryItem &item);
std::optional<int> itemPart(const LibraryItem &item);
int qualityScore(const QString &name);
int smartQualityScore(const QString &name);
std::optional<std::pair<int, int>> itemSeasons(const LibraryItem &item);
QList<LibraryItem> sortVersions(const QList<LibraryItem> &versions);
QList<VersionSection> versionSections(const QList<LibraryItem> &versions);
QString versionLine(const LibraryItem &v, bool inSection);

using PosterOf = std::function<std::optional<QString>(const LibraryItem &)>;
/// Один фильм или сериал, добавленный несколько раз: одна плитка. second — id показанного → все версии по порядку.
std::pair<QList<LibraryItem>, QHash<qint64, QList<LibraryItem>>> groupVersions(const QList<LibraryItem> &list, const PosterOf &posterOf = {});

QList<MFile> subtitlesFor(const Magnet &m, const MFile &video);
QList<MFile> externalAudioFor(const Magnet &m, const MFile &video);

QString watchKey(qint64 magnetId, const MFile &file);

/// Сохраняет плеер: место, длина, когда.
struct WatchInfo {
    qint64 pos = 0;
    qint64 len = 0;
    qint64 at = 0;
    float fraction() const;
    bool watched() const { return len > 0 && pos >= len - 15000; }
    bool operator==(const WatchInfo &) const = default;
};

} // namespace suslik::core
