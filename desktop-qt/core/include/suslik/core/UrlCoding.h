#pragma once
#include <QString>
#include <optional>

namespace suslik::core {

/// Как java.net.URLEncoder (форма): пробел → «+», без кодирования только A–Z a–z 0–9 . - * _
QString formEncode(const QString &value);

/// Как java.net.URLDecoder: «+» → пробел, %XX → байты UTF-8. Битая последовательность (%zz, % в конце) — пусто.
std::optional<QString> formDecode(const QString &value);

} // namespace suslik::core
