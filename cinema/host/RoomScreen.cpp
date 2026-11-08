#include "RoomScreen.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <cmath>

namespace suslik::cinema {

namespace {

QString S(const char *s) { return QString::fromLatin1(s); }

bool vec3(const QJsonValue &v) {
    const QJsonArray a = v.toArray();
    if (a.size() != 3) return false;
    for (const QJsonValue &x : a)
        if (!x.isDouble() || !std::isfinite(x.toDouble())) return false;
    return true;
}

bool positive(const QJsonValue &v) { return v.isDouble() && std::isfinite(v.toDouble()) && v.toDouble() > 0; }

QString fitOf(const QJsonValue &v) {
    const QString f = v.toString();
    return f == QLatin1String("fill") || f == QLatin1String("stretch") ? f : S("fit");
}

} // namespace

QJsonObject screenFromCalib(const QByteArray &calibJson) {
    const QJsonObject o = QJsonDocument::fromJson(calibJson).object();
    if (!vec3(o.value(S("c"))) || !vec3(o.value(S("n"))) || !positive(o.value(S("w"))) || !positive(o.value(S("h"))))
        return {};
    const auto num = [&](const char *k) { return o.value(S(k)).toDouble(0); };
    const double ar = o.contains(S("ar")) ? num("ar") : (o.value(S("lock")).toBool() ? 16.0 / 9.0 : 0.0);
    return QJsonObject{{S("c"), o.value(S("c"))}, {S("n"), o.value(S("n"))}, {S("w"), num("w")}, {S("h"), num("h")},
                       {S("sr"), num("sr")}, {S("su"), num("su")}, {S("sn"), num("sn")}, {S("ar"), ar},
                       {S("fit"), fitOf(o.value(S("fit")))}};
}

QByteArray roomScreenFile(const QJsonObject &screen, qint64 id) {
    if (!vec3(screen.value(S("c"))) || !vec3(screen.value(S("n"))) || !positive(screen.value(S("w")))
        || !positive(screen.value(S("h"))))
        return {};
    QJsonObject o{{S("id"), double(id)}};
    for (const char *k : {"c", "n", "w", "h"}) o.insert(S(k), screen.value(S(k)));
    for (const char *k : {"sr", "su", "sn", "ar"}) o.insert(S(k), screen.value(S(k)).toDouble(0));
    o.insert(S("fit"), fitOf(screen.value(S("fit"))));
    return QJsonDocument(o).toJson(QJsonDocument::Compact) + '\n';
}

} // namespace suslik::cinema
