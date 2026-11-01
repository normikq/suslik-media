#pragma once

/// Ссылки suslik://room/<код> (кнопка «Открыть в приложении» в Discord Activity) открывают эту программу.
/// Windows: HKCU\Software\Classes\suslik → «SuslikMediaQt.exe "%1"». Пишется при запуске, если записано другое
/// (переустановка в другую папку, самообновление); прав администратора не нужно. Удаляет запись SuslikUninstall.exe.
/// Отладочная сборка (SM_DIAG) ссылку себе не забирает.
namespace suslik::app::UrlScheme {
void registerSelf();
}
