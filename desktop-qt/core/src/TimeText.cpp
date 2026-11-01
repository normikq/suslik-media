#include "suslik/core/TimeText.h"

namespace suslik::core {
QString clockText(qint64 ms) {
    const qint64 t = (ms < 0 ? 0 : ms) / 1000;
    const qint64 h = t / 3600, m = (t % 3600) / 60, s = t % 60;
    const auto two = [](qint64 n) { return QString::number(n).rightJustified(2, QLatin1Char('0')); };
    return h > 0 ? QString::number(h) + QLatin1Char(':') + two(m) + QLatin1Char(':') + two(s) : two(m) + QLatin1Char(':') + two(s);
}
} // namespace suslik::core
