#pragma once
#include <QJsonObject>
#include <QString>
#include <optional>

/// Вход (общий пароль, Discord, код устройства) и «Мои фильмы»: протокол сервера (website/src/routes/auth.js, /api/shelf).
/// Без сети: приложение шлёт запросы и отдаёт сюда ответы. Перенос shared/.../auth/Auth.kt.
namespace suslik::core {

/// Пользователь сервера. Вход по общему паролю — isShared (id "shared").
struct UserProfile {
    static constexpr const char *SHARED_ID = "shared";
    QString id;
    QString name;
    QString role;
    std::optional<QString> avatarUrl;

    bool isAdmin() const { return role == QLatin1String("admin"); }
    bool isShared() const { return id == QLatin1String(SHARED_ID); }
    /// `user` из /api/session или опроса кода устройства. Нет пользователя — пусто.
    static std::optional<UserProfile> parse(const QJsonObject *u);
    bool operator==(const UserProfile &) const = default;
};

/// Ответ /api/session. discordClientId — приложение Discord нашего сервера (активность «Смотрит»).
struct SessionInfo {
    static constexpr const char *PATH = "/api/session";
    bool authenticated = false;
    std::optional<UserProfile> user;
    std::optional<QString> discordClientId;
    static SessionInfo parse(const QJsonObject &res);
};

/// Код устройства: code — для опроса (секрет устройства), userCode — человеку на экран, url — где его подтвердить.
struct DevicePairing {
    QString code;
    QString userCode;
    QString url;
};

struct DevicePoll {
    enum class Status { Authorized, Expired, Pending };
    Status status = Status::Pending;
    std::optional<QString> token; ///< Authorized: шлём заголовком X-Device-Token с каждым запросом
    std::optional<UserProfile> user;
};

namespace DeviceAuth {
inline constexpr const char *START = "/api/auth/device";
inline constexpr const char *POLL = "/api/auth/device/poll";
inline constexpr const char *TOKEN_HEADER = "X-Device-Token";

/// name — как устройство видно на сайте («Suslik Media Windows»); siteUrl — адрес сайта.
QJsonObject startBody(const QString &name, const QString &siteUrl);
/// Ответ START. site — адрес сайта со `/` в конце: запасная ссылка для старого сервера. Нет code / user_code — пусто.
std::optional<DevicePairing> parsePairing(const QJsonObject &res, const QString &site);
QJsonObject pollBody(const DevicePairing &p);
/// Ответ POLL с кодом 2xx.
DevicePoll parsePoll(const QJsonObject &res);
/// Ошибка HTTP при опросе: 410 / 404 — код истёк (10 мин), иначе пробуем ещё.
DevicePoll pollFailed(int httpCode);
/// Сессию, токен устройства и бета-код шлём только своему серверу.
bool isOwnServer(const QString &url, const QString &base);
} // namespace DeviceAuth

/// «Мои фильмы» (services/shelf.js): флаг mine у раздачи.
namespace Shelf {
inline constexpr const char *PATH = "/api/shelf";
QJsonObject body(qint64 magnetId, bool mine);
} // namespace Shelf

} // namespace suslik::core
