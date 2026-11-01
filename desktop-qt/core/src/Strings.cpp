#include "suslik/core/Strings.h"

#include <QJsonDocument>
#include <QJsonObject>

namespace suslik::core {

QHash<QString, QString> &Strings::base() { static QHash<QString, QString> h; return h; }
QHash<QString, QString> &Strings::current() { static QHash<QString, QString> h; return h; }

static QHash<QString, QString> parse(const QByteArray &json) {
    QHash<QString, QString> out;
    const QJsonObject o = QJsonDocument::fromJson(json).object();
    for (auto it = o.begin(); it != o.end(); ++it)
        if (it.value().isString()) out.insert(it.key(), it.value().toString());
    return out;
}

void Strings::load(const QByteArray &b, const QByteArray &c) {
    base() = parse(b);
    current() = parse(c);
}

QString Strings::get(const QString &key, std::initializer_list<std::pair<const char *, QString>> params) {
    QString s = current().value(key);
    if (s.isEmpty()) s = base().value(key, key);
    for (const auto &[name, value] : params) s.replace(QLatin1Char('{') + QLatin1String(name) + QLatin1Char('}'), value);
    return s;
}

} // namespace suslik::core
