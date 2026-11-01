#pragma once
#include <QString>

/// Журнал плеера (PlayerLog.kt): %APPDATA%\SuslikMediaQt\player.log, строка с временем; больше 4 МБ — старый уходит
/// в player.old.log. Пишут любые потоки (запас BurstProxy, mpv, окно).
namespace suslik::app::PlayerLog {

void write(const QString &line);
QString file();

} // namespace suslik::app::PlayerLog
