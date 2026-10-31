#pragma once
#include <QString>

/// Папка данных приложения: %APPDATA%\SuslikMediaQt (отладочная сборка — SuslikMediaQtDebug). Своя, не общая с
/// Kotlin-версией: обе могут работать одновременно. При первом запуске из папки старой версии переносятся вход,
/// прогресс и настройки (migrateFromKotlin), чтобы не входить заново.
namespace suslik::app::AppDir {

QString dir();
QString file(const QString &name);
/// Один раз: копирует файлы .properties старой ПК-версии, если своих ещё нет. Сколько скопировано.
int migrateFromKotlin();

} // namespace suslik::app::AppDir
