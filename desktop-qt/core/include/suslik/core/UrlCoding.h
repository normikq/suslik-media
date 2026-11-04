#pragma once
#include <QString>
#include <QStringList>
#include <optional>

namespace suslik::core {

/// Как java.net.URLEncoder (форма): пробел → «+», без кодирования только A–Z a–z 0–9 . - * _
QString formEncode(const QString &value);

/// Как java.net.URLDecoder: «+» → пробел, %XX → байты UTF-8. Битая последовательность (%zz, % в конце) — пусто.
std::optional<QString> formDecode(const QString &value);

/// Извлекает magnet-ссылки из пользовательского ввода:
/// - сохраняет пробелы в названии (&dn=...) и трекеры (&tr=...);
/// - поддерживает несколько ссылок (через перевод строки или пробел);
/// - нормализует схему magnet:? (регистронезависимо);
/// - распознаёт голый info_hash (40 hex или 32 base32);
/// - декодирует URL-encoded ссылки (magnet%3A%3F...).
QStringList parseMagnetLinks(const QString &text);

} // namespace suslik::core
