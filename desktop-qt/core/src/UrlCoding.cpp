#include "suslik/core/UrlCoding.h"

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

} // namespace suslik::core
