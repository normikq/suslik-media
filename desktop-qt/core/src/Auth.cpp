#include "suslik/core/Auth.h"
#include "suslik/core/SiteAddress.h"
#include "JsonOpt.h"

namespace suslik::core {

using namespace json;

std::optional<UserProfile> UserProfile::parse(const QJsonObject *u) {
    if (!u) return std::nullopt;
    UserProfile p;
    p.id = str(*u, QStringLiteral("id"));
    p.name = str(*u, QStringLiteral("name"));
    if (p.name.trimmed().isEmpty()) p.name = str(*u, QStringLiteral("username"));
    p.role = str(*u, QStringLiteral("role"));
    if (const QString a = str(*u, QStringLiteral("avatar")); !a.trimmed().isEmpty()) p.avatarUrl = a;
    return p;
}

static std::optional<UserProfile> userOf(const QJsonObject &res) {
    const QJsonValue v = res.value(QLatin1String("user"));
    if (!v.isObject()) return std::nullopt;
    const QJsonObject o = v.toObject();
    return UserProfile::parse(&o);
}

SessionInfo SessionInfo::parse(const QJsonObject &res) {
    SessionInfo s;
    s.authenticated = optBool(res, QStringLiteral("authenticated"));
    if (s.authenticated) s.user = userOf(res);
    if (const QString id = str(res, QStringLiteral("discord_client_id")); !id.trimmed().isEmpty()) s.discordClientId = id;
    return s;
}

QJsonObject DeviceAuth::startBody(const QString &name, const QString &siteUrl) {
    QString site = siteUrl;
    while (site.endsWith(QLatin1Char('/'))) site.chop(1);
    return QJsonObject{{QStringLiteral("name"), name}, {QStringLiteral("site_url"), site}};
}

std::optional<DevicePairing> DeviceAuth::parsePairing(const QJsonObject &res, const QString &site) {
    if (!res.value(QLatin1String("code")).isString() || !res.value(QLatin1String("user_code")).isString()) return std::nullopt;
    DevicePairing p;
    p.code = res.value(QLatin1String("code")).toString();
    p.userCode = res.value(QLatin1String("user_code")).toString();
    const QString url = optString(res, QStringLiteral("verification_url"));
    p.url = url.contains(QLatin1String("code=")) ? url : site + QStringLiteral("link?code=") + p.userCode;
    return p;
}

QJsonObject DeviceAuth::pollBody(const DevicePairing &p) { return QJsonObject{{QStringLiteral("code"), p.code}}; }

DevicePoll DeviceAuth::parsePoll(const QJsonObject &res) {
    const QString status = optString(res, QStringLiteral("status"));
    DevicePoll out;
    if (status == QLatin1String("authorized")) {
        out.status = DevicePoll::Status::Authorized;
        if (const QString t = str(res, QStringLiteral("device_token")); !t.trimmed().isEmpty()) out.token = t;
        out.user = userOf(res);
    } else if (status == QLatin1String("expired")) {
        out.status = DevicePoll::Status::Expired;
    }
    return out;
}

DevicePoll DeviceAuth::pollFailed(int httpCode) {
    DevicePoll out;
    if (httpCode == 410 || httpCode == 404) out.status = DevicePoll::Status::Expired;
    return out;
}

bool DeviceAuth::isOwnServer(const QString &url, const QString &base) { return SiteAddress::isOwn(url, base); }

QJsonObject Shelf::body(qint64 magnetId, bool mine) {
    return QJsonObject{{QStringLiteral("id"), magnetId}, {QStringLiteral("mine"), mine}};
}

} // namespace suslik::core
