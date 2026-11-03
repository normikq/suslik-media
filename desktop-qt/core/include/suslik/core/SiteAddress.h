#pragma once
#include <QString>

/// Адрес сервера из одной настройки сборки (секрет SITE_URL): домен и, если сервер не в корне, путь.
/// Перенос android/core/.../core/SiteAddress.kt — правила те же, тесты те же.
namespace suslik::core::SiteAddress {

/// `https://host[/path]` без `/` в конце; пусто, если адреса нет. Без схемы — https; запрос и `#` отбрасываются.
/// `http://` остаётся только для localhost и 127.0.0.1.
QString normalize(const QString &raw);

/// Домен (с портом) из normalize-адреса.
QString host(const QString &base);

/// Путь сервера без `/` в конце: "" в корне.
QString path(const QString &base);

/// Адрес на нашем сервере: тот же домен и внутри пути base. Только сюда идут cookie, токен устройства и бета-код.
bool isOwn(const QString &url, const QString &base);

} // namespace suslik::core::SiteAddress
