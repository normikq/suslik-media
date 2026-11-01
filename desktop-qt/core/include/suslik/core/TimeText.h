#pragma once
#include <QString>

namespace suslik::core {
/// Время фильма, как его показывают плееры: «05:03», «1:05:03» (отрицательное — 0).
QString clockText(qint64 ms);
} // namespace suslik::core
