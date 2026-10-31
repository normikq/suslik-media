#include "suslik/core/Changelog.h"
#include "suslik/core/Versions.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <algorithm>

namespace suslik::core::Changelog {

QList<Release> parse(const QByteArray &json) {
    QJsonParseError err{};
    const QJsonDocument doc = QJsonDocument::fromJson(json, &err);
    if (err.error != QJsonParseError::NoError || !doc.isArray()) return {};
    QList<Release> out;
    for (const QJsonValue &v : doc.array()) {
        if (!v.isObject()) continue;
        const QJsonObject r = v.toObject();
        Release rel{r.value(QLatin1String("version")).toString(), r.value(QLatin1String("title")).toString(), {}};
        for (const QJsonValue &item : r.value(QLatin1String("items")).toArray()) {
            const QString s = item.toString();
            if (!s.trimmed().isEmpty()) rel.items << s;
        }
        if (!rel.version.trimmed().isEmpty() && !rel.items.isEmpty()) out << rel;
    }
    return out;
}

QList<Release> upTo(const QList<Release> &all, const QString &current, int count) {
    QList<Release> out;
    for (const Release &r : all)
        if (!Versions::isNewer(r.version, current)) out << r;
    std::stable_sort(out.begin(), out.end(), [](const Release &a, const Release &b) { return Versions::isNewer(a.version, b.version); });
    if (out.size() > count) out.resize(count);
    return out;
}

bool shouldShow(const QList<Release> &releases, const std::optional<QString> &lastSeen) {
    return !releases.isEmpty() && (!lastSeen || Versions::isNewer(releases.first().version, *lastSeen));
}

} // namespace suslik::core::Changelog
