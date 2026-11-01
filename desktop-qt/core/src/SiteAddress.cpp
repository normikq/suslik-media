#include "suslik/core/SiteAddress.h"
#include "suslik/core/UrlCoding.h"

#include <QRegularExpression>

namespace suslik::core::SiteAddress {

static QString before(const QString &s, QChar c) {
    const auto i = s.indexOf(c);
    return i < 0 ? s : s.left(i);
}

QString normalize(const QString &raw) {
    QString s = before(before(raw.trimmed(), QLatin1Char('#')), QLatin1Char('?'));
    QString scheme = QStringLiteral("https");
    if (s.startsWith(QLatin1String("https://"), Qt::CaseInsensitive)) {
        s = s.mid(8);
    } else if (s.startsWith(QLatin1String("http://"), Qt::CaseInsensitive)) {
        s = s.mid(7);
        scheme = QStringLiteral("http");
    }
    const QString host = before(s, QLatin1Char('/')).toLower();
    if (host.isEmpty()) return {};
    for (const QChar c : host)
        if (c.isSpace() || c == QLatin1Char('@') || c == QLatin1Char('\\')) return {};
    static const QRegularExpression slashes(QStringLiteral("/{2,}"));
    QString p = s.mid(host.size()).replace(slashes, QStringLiteral("/"));
    while (p.endsWith(QLatin1Char('/'))) p.chop(1);
    const QString bare = before(host, QLatin1Char(':'));
    const bool local = bare == QLatin1String("localhost") || bare == QLatin1String("127.0.0.1");
    return (scheme == QLatin1String("http") && local ? QStringLiteral("http://") : QStringLiteral("https://")) + host + p;
}

static QString afterScheme(const QString &base) {
    const auto i = base.indexOf(QLatin1String("://"));
    return i < 0 ? QString() : base.mid(i + 3);
}

QString host(const QString &base) { return before(afterScheme(base), QLatin1Char('/')); }

QString path(const QString &base) {
    const QString r = afterScheme(base);
    const auto i = r.indexOf(QLatin1Char('/'));
    return i < 0 ? QString() : r.mid(i);
}

bool isOwn(const QString &url, const QString &base) {
    if (base.isEmpty()) return false;
    const auto bi = base.indexOf(QLatin1String("://"));
    const auto ui = url.indexOf(QLatin1String("://"));
    if (bi <= 0 || ui <= 0) return false;
    if (url.left(ui).compare(base.left(bi), Qt::CaseInsensitive) != 0) return false;
    const QString rest = url.mid(ui + 3);
    qsizetype hostEnd = rest.size();
    for (qsizetype k = 0; k < rest.size(); ++k) {
        const QChar c = rest.at(k);
        if (c == QLatin1Char('/') || c == QLatin1Char('?') || c == QLatin1Char('#') || c == QLatin1Char('\\')) { hostEnd = k; break; }
    }
    if (rest.left(hostEnd).compare(host(base), Qt::CaseInsensitive) != 0) return false;
    const QString rawPath = before(before(rest.mid(hostEnd), QLatin1Char('#')), QLatin1Char('?'));
    const auto decoded = formDecode(QString(rawPath).replace(QLatin1Char('+'), QLatin1String("%2B")));
    if (!decoded) return false;
    static const QRegularExpression seps(QStringLiteral("[/\\\\]"));
    for (const QString &part : decoded->split(seps))
        if (part == QLatin1String(".") || part == QLatin1String("..")) return false;
    const QString own = path(base);
    return rawPath == own || rawPath.startsWith(own + QLatin1Char('/'));
}

} // namespace suslik::core::SiteAddress
