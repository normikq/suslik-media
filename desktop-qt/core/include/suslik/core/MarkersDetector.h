#pragma once
#include "suslik/core/Model.h"

#include <QJsonObject>
#include <QList>
#include <QString>
#include <optional>

/// Заставка и финальные титры видео и какие субтитры идут к озвучке. Сначала главы файла («Opening», «Заставка»,
/// «Credits», «Титры»…), потом то, что нашёл сервер (GET /api/markers). Перенос android/core/.../MarkersDetector.kt.
namespace suslik::core {

/// Глава открытого файла: где начинается и как называется.
struct Chapter {
    qint64 timeMs = 0;
    QString title;
};

namespace MarkersDetector {
/// Заставка без следующей главы (или слишком длинная: неверное имя) считается такой длины.
inline constexpr qint64 INTRO_MAX_MS = 120000;
/// Обратный отсчёт не предлагается для титров короче этого.
inline constexpr qint64 CREDITS_MIN_MS = 5000;
/// Автопропуск ждёт столько внутри заставки, чтобы прыжок в неё не дёргал картинку сразу.
inline constexpr qint64 AUTO_SKIP_DELAY_MS = 1500;
/// Выбор обратного отсчёта в настройках, секунды; 0 — сразу.
inline const QList<int> COUNTDOWN_CHOICES{5, 8, 12, 0};
inline constexpr int COUNTDOWN_DEFAULT = 8;

bool isIntroTitle(const QString &title);
bool isCreditsTitle(const QString &title);

MediaMarkers fromChapters(const QList<Chapter> &chapters, qint64 lengthMs);
/// Свои отметки файла первыми; чего в нём нет — от сервера, обрезанное по длине видео.
MediaMarkers merge(const MediaMarkers &file, const std::optional<MediaMarkers> &server, qint64 lengthMs);
/// Когда показать карточку «следующая серия / фильм»: начало титров, если они достаточно длинные.
std::optional<qint64> nextOfferAt(const MediaMarkers &m, qint64 lengthMs);
/// Заставка, которую ещё стоит пропустить в timeMs: внутри неё и осталось больше 2 с.
std::optional<TimeRange> introAt(const MediaMarkers &m, qint64 timeMs);
/// /api/markers?… для сервера; episode пусто — фильм (сборника). Пусто — не с чем спрашивать.
std::optional<QString> serverPath(const std::optional<QString> &title, const std::optional<QString> &alt, std::optional<int> season,
                                  std::optional<int> episode, qint64 durationMs);
/// Фильм сборника для сервера: «Harry Potter and the Chamber of Secrets (2002)» из имени файла.
std::optional<QString> filmTitle(const QString &fileName, const std::optional<QString> &parsed = std::nullopt);
/// Ответ сервера: { intro: {startMs, endMs} | null, credits: … }.
std::optional<MediaMarkers> parseServer(const QJsonObject *answer);

// ---- Умные субтитры ----

/// Дорожка звука или субтитров, как её видит плеер: lang как в файле («rus», «en»), forced — флаг файла.
struct Track {
    int id = 0;
    std::optional<QString> title;
    std::optional<QString> lang;
    bool forced = false;
};

struct SubChoice {
    enum class Kind { Keep, Off, Pick };
    Kind kind = Kind::Keep;
    int id = -1;
    static SubChoice keep() { return {}; }
    static SubChoice off() { return {Kind::Off, -1}; }
    static SubChoice pick(int id) { return {Kind::Pick, id}; }
    bool operator==(const SubChoice &) const = default;
};

/// true — русская речь, false — другой язык, пусто — не понять.
std::optional<bool> isRussianAudio(const Track &t);
bool isForced(const Track &t);
bool isRussianSub(const Track &t);
/// Русская речь — форсированные (надписи) русские или никаких; другой язык — полные русские («Полные» первыми).
SubChoice pickSubtitle(const std::optional<Track> &audio, const QList<Track> &subs);
} // namespace MarkersDetector

} // namespace suslik::core
