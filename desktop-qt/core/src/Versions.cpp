#include "suslik/core/Versions.h"

#include <QRegularExpression>
#include <array>

namespace suslik::core {

namespace {
std::array<qint64, 4> parts(const QString &v) {
    std::array<qint64, 4> out{0, 0, 0, 0};
    static const QRegularExpression digits(QStringLiteral("\\d+"));
    auto it = digits.globalMatch(v);
    for (int i = 0; i < 4 && it.hasNext(); ++i) out[i] = it.next().captured(0).toLongLong();
    return out;
}
} // namespace

bool Versions::isNewer(const QString &remote, const QString &local) {
    const auto a = parts(remote), b = parts(local);
    for (int i = 0; i < 4; ++i)
        if (a[i] != b[i]) return a[i] > b[i];
    return false;
}

int Versions::compare(const QString &a, const QString &b) {
    return isNewer(a, b) ? 1 : isNewer(b, a) ? -1 : 0;
}

UpdateChannel::Verdict UpdateChannel::verdict(const QString &remote, const QString &installed, bool release) {
    if (remote.trimmed().isEmpty()) return Verdict::Nothing;
    if (Versions::isNewer(remote, installed)) return Verdict::Offer;
    if (Versions::compare(remote, installed) == 0) return Verdict::Installed;
    return release ? Verdict::Replace : Verdict::Older;
}

bool UpdateChannel::offers(Verdict v) { return v == Verdict::Offer || v == Verdict::Replace; }

QString UpdateChannel::message(Verdict v, const QString &remote, const QString &installed, bool beta) {
    switch (v) {
    case Verdict::Offer:
        return QStringLiteral("Доступна версия %1%2.").arg(remote, beta ? QStringLiteral(" (бета)") : QString());
    case Verdict::Replace:
        return QStringLiteral("Релиз %1 заменит установленную бету %2.").arg(remote, installed);
    case Verdict::Installed:
        return QStringLiteral("Установлена актуальная %1 %2.").arg(beta ? QStringLiteral("бета-версия") : QStringLiteral("версия"), installed);
    case Verdict::Older:
        return QStringLiteral("Установлена %1 — она новее последней беты (%2).").arg(installed, remote);
    case Verdict::Nothing:
        break;
    }
    return QStringLiteral("Не удалось проверить обновления — попробуйте позже.");
}

} // namespace suslik::core
