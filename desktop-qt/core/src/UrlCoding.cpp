#include "suslik/core/UrlCoding.h"

#include <QRegularExpression>
#include <QUrl>

namespace suslik::core {

QString formEncode(const QString &value) {
    const QByteArray utf8 = value.toUtf8();
    QString out;
    out.reserve(utf8.size() * 3);
    static const char hex[] = "0123456789ABCDEF";
    for (const char ch : utf8) {
        const auto c = static_cast<unsigned char>(ch);
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '*' || c == '_') {
            out += QChar(c);
        } else if (c == ' ') {
            out += QLatin1Char('+');
        } else {
            out += QLatin1Char('%');
            out += QLatin1Char(hex[c >> 4]);
            out += QLatin1Char(hex[c & 0xF]);
        }
    }
    return out;
}

static int hexValue(QChar c) {
    const char16_t u = c.unicode();
    if (u >= '0' && u <= '9') return u - '0';
    if (u >= 'a' && u <= 'f') return u - 'a' + 10;
    if (u >= 'A' && u <= 'F') return u - 'A' + 10;
    return -1;
}

std::optional<QString> formDecode(const QString &value) {
    QString out;
    QByteArray pending; // подряд идущие %XX собираем в байты и декодируем как UTF-8 разом
    auto flush = [&] {
        if (!pending.isEmpty()) { out += QString::fromUtf8(pending); pending.clear(); }
    };
    for (qsizetype i = 0; i < value.size(); ++i) {
        const QChar c = value.at(i);
        if (c == QLatin1Char('%')) {
            if (i + 2 >= value.size()) return std::nullopt;
            const int hi = hexValue(value.at(i + 1)), lo = hexValue(value.at(i + 2));
            if (hi < 0 || lo < 0) return std::nullopt;
            pending.append(static_cast<char>(hi * 16 + lo));
            i += 2;
        } else {
            flush();
            out += (c == QLatin1Char('+')) ? QChar(' ') : c;
        }
    }
    flush();
    return out;
}

QStringList parseMagnetLinks(const QString &input) {
    QString text = input.trimmed();
    if (text.isEmpty()) return {};

    if (text.startsWith(QStringLiteral("magnet%3A"), Qt::CaseInsensitive)) {
        text = QUrl::fromPercentEncoding(text.toUtf8());
    }

    static const QRegularExpression hashRe(QStringLiteral("^[0-9a-fA-F]{40}$|^[2-7a-zA-Z]{32}$"));
    if (hashRe.match(text).hasMatch()) {
        return {QStringLiteral("magnet:?xt=urn:btih:") + text};
    }
    if (text.startsWith(QStringLiteral("urn:btih:"), Qt::CaseInsensitive)) {
        return {QStringLiteral("magnet:?xt=") + text};
    }
    if (text.startsWith(QStringLiteral("xt=urn:btih:"), Qt::CaseInsensitive)) {
        return {QStringLiteral("magnet:?") + text};
    }

    static const QRegularExpression splitRe(QStringLiteral("(?:\r\n|\r|\n)+|\\s*(?=[<\"']?[mM][aA][gG][nN][eE][tT]:\\?)"));
    const QStringList parts = text.split(splitRe, Qt::SkipEmptyParts);

    QStringList out;
    for (QString raw : parts) {
        raw = raw.trimmed();
        while (!raw.isEmpty() && (raw.startsWith(QLatin1Char('"')) || raw.startsWith(QLatin1Char('\'')) || raw.startsWith(QLatin1Char('<')))) {
            raw.remove(0, 1);
        }
        while (!raw.isEmpty() && (raw.endsWith(QLatin1Char('"')) || raw.endsWith(QLatin1Char('\'')) || raw.endsWith(QLatin1Char('>')))) {
            raw.chop(1);
        }
        raw = raw.trimmed();
        if (raw.startsWith(QStringLiteral("magnet:?"), Qt::CaseInsensitive)) {
            QString link = QStringLiteral("magnet:?") + raw.mid(8);
            out << link;
        }
    }
    return out;
}

} // namespace suslik::core
