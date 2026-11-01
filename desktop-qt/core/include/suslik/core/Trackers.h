#pragma once
#include "Search.h"

#include <QByteArray>
#include <QJsonArray>
#include <QList>
#include <QString>
#include <optional>
#include <utility>

/// Трекеры на ПК: какие источники выдачи включены и RuTracker прямо с этого компьютера (разбор его страниц, cookie).
/// Перенос desktop/.../trackers (TrackerSettings.kt, LocalTrackers.kt, часть BrowserDetector.kt и EdgeBrowserBridge.kt) —
/// без сети: запросы делает приложение (фоновый браузер или прямой HTTP). Настройки — хранилище "local_trackers",
/// те же ключи, что у Kotlin-версии.
namespace suslik::core {

struct TrackerSourceInfo {
    QString id, name, descKey, tagKey;
    bool isLocal = false;
};

class TrackerSettings {
public:
    static const QList<TrackerSourceInfo> &sources();
    static bool isEnabled(const QString &id);
    static void setEnabled(const QString &id, bool enabled);
    static void setAll(bool enabled);
    /// Только русские: Rutor, NNM-Club, RuTracker.
    static void setOnlyRussian();
    /// Строка «Источники» сервера: показывать ли этот трекер.
    static bool isProviderEnabled(const QString &providerName);
    /// Раздача из выдачи (source — «Rutor + NNM-Club»): хотя бы один её трекер включён.
    static bool isSourceEnabledForHit(const QString &source);
    static int enabledCount();
};

/// cookie как пары «имя — значение» в порядке появления.
using CookieList = QList<std::pair<QString, QString>>;

class RuTracker {
public:
    static QString defaultUrl() { return QStringLiteral("https://rutracker.org"); }

    // ---- настройки ----
    static QString url();
    static void setUrl(const QString &u);
    static bool enabled();
    static void setEnabled(bool on);
    static QString cookie();
    static void setCookie(const QString &c);
    /// Браузер, из которого взяты cookie (имя BrowserType из Kotlin: "EDGE", "FIREFOX"…); пусто — вставлены вручную.
    static QString cookieSource();
    static void setCookieSource(const QString &type);
    /// Включён и есть cookie.
    static bool isReady() { return enabled() && !cookie().trimmed().isEmpty(); }

    // ---- разбор ----
    /// Только https и зеркала rutracker.org / .net / .nl; иначе пусто и текст ошибки в error.
    static QString validateUrl(const QString &url, QString *error = nullptr);
    static QString unescapeHtml(const QString &s);
    /// «Cookie: …» без приставки и с bb_ssl=1, если есть bb_session.
    static QString normalizeCookie(const QString &raw);
    static CookieList parseCookieMap(const QString &raw);
    static QString cookieHeader(const CookieList &c);
    /// Корень домена для cookie: «rutracker.org» из «https://www.rutracker.org».
    static QString cookieDomain(const QString &baseUrl);
    /// Вид по разделу и названию: "cartoon", "series" или "movie".
    static QString kindOf(const QString &forum, const QString &title);
    /// Страница tracker.php (прямой HTTP).
    static QList<SearchHit> parseHtml(const QString &html);
    /// Таблица, которую собрал скрипт в фоновом браузере: [{topicId, title, forum, sizeBytes, seeds, leeches, magnet}].
    static QList<SearchHit> parseJsonResults(const QString &json);
    /// Storage.getCookies фонового браузера → заголовок Cookie для трекера (нет bb_session — пусто).
    static std::optional<QString> formatTrackerCookies(const QJsonArray &cookies, const QString &baseUrl);
    /// cookies.sqlite Firefox как байты (копия: Firefox держит файл): bb_session, bb_guid, cf_clearance.
    static std::optional<QString> extractFirefoxCookies(const QByteArray &bytes, const QString &domainKeyword, QString *error);
    /// Поиск без найденного года: «Дюна 2021» → «Дюна».
    static QString withoutYear(const QString &q);
    static std::optional<QString> yearOf(const QString &q);
};

/// windows-1251 (страницы RuTracker и его поиск без браузера).
struct Cp1251 {
    static QString decode(const QByteArray &bytes);
    static QByteArray encode(const QString &s);
    /// Как URLEncoder.encode(q, "windows-1251"): пробел — «+».
    static QByteArray urlEncode(const QString &s);
};

} // namespace suslik::core
