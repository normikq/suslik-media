#include "ReleasePick.h"

#include "suslik/core/SiteAddress.h"

#include <QUrl>

namespace suslik::cinema {

using namespace suslik::core;

namespace {
QString S(const char *s) { return QString::fromUtf8(s); }
} // namespace

std::optional<int> pickVariant(const QList<Choice> &variants, qint64 release, const QString &quality,
                               const QStringList &savedQualities) {
    if (variants.isEmpty()) return std::nullopt;
    auto find = [&](auto pred) -> std::optional<int> {
        for (int i = 0; i < variants.size(); ++i)
            if (pred(variants[i])) return i;
        return std::nullopt;
    };
    if (release > 0) return find([&](const Choice &c) { return c.version.m.id == release; });
    if (!quality.trimmed().isEmpty()) {
        const QString q = quality.trimmed();
        if (auto i = find([&](const Choice &c) { return c.variant.quality.compare(q, Qt::CaseInsensitive) == 0; })) return i;
        return find([&](const Choice &c) { return c.variant.label.contains(q, Qt::CaseInsensitive); });
    }
    // Сохранённое качество — то же правило, что у плеера ПК-версии (PlayerModel::playEntry)
    for (const QString &q : savedQualities)
        if (auto i = find([&](const Choice &c) { return c.variant.quality == q || c.variant.label == q; })) return i;
    return 0;
}

bool isMirrorUrl(const QString &url, const QString &base) {
    if (base.isEmpty() || !SiteAddress::isOwn(url, base)) return false;
    const QUrl u(url);
    const bool local = u.host() == QLatin1String("localhost") || u.host() == QLatin1String("127.0.0.1");
    if (u.scheme() != QLatin1String("https") && !local) return false;
    return u.path().startsWith(SiteAddress::path(base) + S("/api/file/proxy/"));
}

} // namespace suslik::cinema
