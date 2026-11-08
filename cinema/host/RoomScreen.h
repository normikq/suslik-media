#pragma once
#include <QByteArray>
#include <QJsonObject>

/// Экран в мире игры для комнаты «Вместе» (действие screen на сервере, website/src/services/rooms.js: cleanScreen).
/// Ведущий: screens.json мода -> экран для комнаты. Гость: экран из комнаты -> room-screen.json, который мод читает
/// и ставит экран (init.lua: applyRoomScreen). Только преобразования, без файлов и сети — тест RoomScreenTest.
namespace suslik::cinema {

/// Поля экрана из screens.json мода (saveCalib): c, n, w, h, sr, su, sn, ar, fit. Нет центра, нормали или размера —
/// пусто. Старая калибровка без ar: lock true — 16:9, иначе 0 (как parseCalib в моде).
QJsonObject screenFromCalib(const QByteArray &calibJson);

/// Файл room-screen.json для мода: id (новое значение — мод ставит экран снова) и поля экрана. Неверный экран
/// (нет c/n из трёх чисел, w/h не больше 0) — пусто.
QByteArray roomScreenFile(const QJsonObject &screen, qint64 id);

} // namespace suslik::cinema
