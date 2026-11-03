#pragma once
#include <QHash>
#include <QJsonObject>
#include <QList>
#include <QMutex>
#include <QString>
#include <optional>

/// Внешние озвучки с сервера (/api/custom-audio, docs/CUSTOM-AUDIO-API.md): аудио из другой раздачи, привязанное к
/// видеофайлу фильма, по подписанной ссылке зеркала. Перенос android/core/.../audio/CustomAudio.kt.
namespace suslik::core {

/// Дорожка фильма: url — свежая ссылка зеркала, offsetMs — задержка для всех, mine — её можно менять.
struct CustomTrack {
    QString id;
    QString videoPath;
    QString audioPath;
    QString title;
    std::optional<QString> lang;
    qint64 offsetMs = 0;
    bool mine = false;
    QString url;
    std::optional<qint64> audioMagnetId;
    /// Название в меню дорожек: «HDRezka [rus]» (и из «HDRezka [RUS]» — тег языка не повторяется).
    QString menuName() const;
};

/// Пара «файл озвучки → видеофайл фильма» из проверки.
struct CustomPair {
    QString videoPath;
    QString audioPath;
    QString voice;
    std::optional<int> season;
    std::optional<int> episode;
    std::optional<qint64> offsetMs;
};

struct CustomCheck {
    std::optional<qint64> movieMagnetId;
    bool attachable = true;
    QList<CustomPair> pairs;
};

namespace CustomAudio {
inline constexpr const char *CHECK_PATH = "/api/custom-audio/check";
inline constexpr const char *ATTACH_PATH = "/api/custom-audio/attach";
QString tracksPath(qint64 magnetId);
QString trackPath(const QString &trackId);
QString offsetPath(const QString &trackId);
/// Загруженная озвучка — только .mka (сервер другие не принимает).
bool isSupported(const QString &path);
/// Ответ tracksPath; дорожки без ссылки и не .mka пропускаются.
QList<CustomTrack> parseTracks(const QJsonObject &r);
/// Дорожки видеофайла по ключу прогресса «magnetId|path».
QList<CustomTrack> forVideo(const QList<CustomTrack> &tracks, const std::optional<QString> &watchKey);
/// Дорожка по названию в меню плеера (без языка плеер может показать название без « [xx]»).
std::optional<CustomTrack> byMenuName(const QList<CustomTrack> &tracks, const QString &name);
QJsonObject checkBody(qint64 audioMagnetId, std::optional<qint64> movieMagnetId);
QJsonObject offsetBody(qint64 offsetMs);
CustomCheck parseCheck(const QJsonObject &r);
/// Созданные и уже бывшие дорожки; у бывших — "existing": true.
QList<QJsonObject> parseAttached(const QJsonObject &r);
/// Название и задержка дорожек по ссылке, {"<url>": {"title", "offsetMs"}}; пусто, если дорожек нет.
std::optional<QString> infoJson(const QList<CustomTrack> &tracks);
} // namespace CustomAudio

/// Название и задержка загруженных озвучек по ссылке (infoJson): ссылка сравнивается по файлу (MirrorLink::fileKey),
/// а адрес через локальный запас запоминается отдельно (noteWrapped) — движок видит только его.
class CustomAudioIndex {
public:
    struct Info {
        QString title;
        qint64 offsetMs = 0;
    };
    void read(const std::optional<QString> &json);
    std::optional<Info> of(const QString &url) const;
    /// wrapped — это url через локальный запас.
    void noteWrapped(const QString &url, const QString &wrapped);

private:
    mutable QMutex m_lock;
    QHash<QString, Info> m_byKey;
    QHash<QString, Info> m_byWrapped;
};

} // namespace suslik::core
