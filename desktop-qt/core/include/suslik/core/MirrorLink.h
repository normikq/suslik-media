#pragma once
#include <QDateTime>
#include <QString>
#include <optional>

/// Подписанный адрес зеркала `https://<site>/api/file/proxy/<name>?link=…&exp=…&sig=…` (подпись живёт 12 ч).
/// Перенос android/core/.../core/MirrorLink.kt.
namespace suslik::core::MirrorLink {

/// Ключ файла: путь и параметр link (у одного фильма каждый раз новая подпись). Без link — сам адрес.
QString fileKey(const QString &url);

/// Параметр name, раскодированный; пусто, если его нет или он битый.
std::optional<QString> param(const QString &url, const QString &name);

/// Когда кончается подпись (unix-секунды); пусто для адреса без неё.
std::optional<qint64> expiresAt(const QString &url);

/// Подпись кончилась или кончится в пределах marginSec. Адрес без подписи не истекает.
bool isExpired(const QString &url, qint64 nowSec = QDateTime::currentSecsSinceEpoch(), qint64 marginSec = 60);

/// Имя файла в пути (то, что сервер ставит в Content-Disposition).
QString fileName(const QString &url);

/// Путь API, который заново подписывает файл (`/api/file/mirror-url`); пусто без link.
std::optional<QString> renewPath(const QString &url);

} // namespace suslik::core::MirrorLink
