#pragma once
#include "suslik/core/Model.h"
#include "suslik/core/Party.h"

#include <QHash>
#include <QStringList>
#include <optional>

/// Что открыть в плеере: собирает окно «Смотреть» (PlayModel), принимает плеер (шаг 3). Перенос player/PlayRequest.kt.
namespace suslik::app {

/// Discord: название из медиатеки и серия.
struct Presence {
    QString title;
    std::optional<QString> episode;
};

/// Заставка и титры с сервера (/api/markers): по оригинальному названию, сезону и серии.
struct MarkerQuery {
    std::optional<QString> title, alt;
    std::optional<int> season, episode;
};

struct PlayRequest {
    QString url;                        ///< подписанная ссылка зеркала на файл
    QString title;
    std::optional<QString> playlist;    ///< .m3u всего сезона (сборника) со ссылками зеркала
    QStringList subUrls, audioUrls;     ///< субтитры и внешние озвучки рядом с видео
    QStringList watchKeys;              ///< "magnetId|path" каждого пункта очереди (прогресс в медиатеке)
    qint64 magnetId = 0;
    QList<core::MFile> queue;           ///< файлы очереди: их озвучки и субтитры плеер берёт сам
    bool fromStart = false;
    QStringList posterQueries;
    int startIndex = 0;
    QList<Presence> presence;
    bool collection = false;
    QList<MarkerQuery> markerQueries;
    std::optional<qint64> startMs;
    QHash<QString, QString> sidePaths;  ///< адрес зеркала озвучки или субтитров → путь в раздаче (язык часто в папке)
    QList<core::PartyVariant> variants;
    QString activeVariantKey;
    std::optional<QString> canonicalId;
    std::optional<QString> seriesTitle;
};

} // namespace suslik::app
