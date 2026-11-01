#include "suslik/core/MirrorLink.h"
#include "suslik/core/UrlCoding.h"

namespace suslik::core::MirrorLink {

static QString query(const QString &url) {
    const auto i = url.indexOf(QLatin1Char('?'));
    return i < 0 ? QString() : url.mid(i + 1);
}

static QString beforeQuery(const QString &url) {
    const auto i = url.indexOf(QLatin1Char('?'));
    return i < 0 ? url : url.left(i);
}

static std::optional<QString> rawParam(const QString &url, const QString &name) {
    const QString prefix = name + QLatin1Char('=');
    for (const QString &part : query(url).split(QLatin1Char('&')))
        if (part.startsWith(prefix)) return part;
    return std::nullopt;
}

QString fileKey(const QString &url) {
    const auto link = rawParam(url, QStringLiteral("link"));
    if (!link) return url;
    return beforeQuery(url) + QLatin1Char('?') + *link;
}

std::optional<QString> param(const QString &url, const QString &name) {
    const auto p = rawParam(url, name);
    if (!p) return std::nullopt;
    return formDecode(p->mid(p->indexOf(QLatin1Char('=')) + 1));
}

std::optional<qint64> expiresAt(const QString &url) {
    const auto v = param(url, QStringLiteral("exp"));
    if (!v) return std::nullopt;
    bool ok = false;
    const qint64 n = v->toLongLong(&ok);
    return ok ? std::optional<qint64>(n) : std::nullopt;
}

bool isExpired(const QString &url, qint64 nowSec, qint64 marginSec) {
    const auto exp = expiresAt(url);
    return exp && *exp <= nowSec + marginSec;
}

QString fileName(const QString &url) {
    const QString p = beforeQuery(url);
    const QString name = p.mid(p.lastIndexOf(QLatin1Char('/')) + 1);
    return formDecode(name).value_or(name);
}

std::optional<QString> renewPath(const QString &url) {
    const auto link = param(url, QStringLiteral("link"));
    if (!link || link->trimmed().isEmpty()) return std::nullopt;
    return QStringLiteral("/api/file/mirror-url?link=") + formEncode(*link) + QStringLiteral("&name=") + formEncode(fileName(url));
}

} // namespace suslik::core::MirrorLink
