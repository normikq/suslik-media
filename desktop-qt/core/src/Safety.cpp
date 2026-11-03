#include "suslik/core/Safety.h"

#include <QRegularExpression>

namespace suslik::core::Safety {

bool sha256Matches(const QString &expected, const QString &actualHex) {
    static const QRegularExpression hex64(QStringLiteral("^[0-9a-f]{64}$"));
    const QString e = expected.trimmed().toLower();
    return hex64.match(e).hasMatch() && e == actualHex.trimmed().toLower();
}

QString quoteWindowsArg(const QString &arg, bool always) {
    static const QRegularExpression special(QStringLiteral("[ \\t\\n\\v\"]"));
    if (!always && !arg.isEmpty() && !arg.contains(special)) return arg;
    QString out(QLatin1Char('"'));
    qsizetype slashes = 0;
    for (const QChar c : arg) {
        if (c == QLatin1Char('\\')) { ++slashes; continue; }
        out += QString(c == QLatin1Char('"') ? slashes * 2 + 1 : slashes, QLatin1Char('\\'));
        out += c;
        slashes = 0;
    }
    out += QString(slashes * 2, QLatin1Char('\\'));
    out += QLatin1Char('"');
    return out;
}

bool RateLimit::allow(qint64 now) {
    while (!m_stamps.isEmpty() && now - m_stamps.first() >= m_window) m_stamps.removeFirst();
    if (m_stamps.size() >= m_max) return false;
    m_stamps << now;
    return true;
}

} // namespace suslik::core::Safety
