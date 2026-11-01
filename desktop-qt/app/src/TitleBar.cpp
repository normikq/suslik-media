#include "TitleBar.h"

#include <QWindow>

#ifdef Q_OS_WIN
#include <windows.h>
#include <dwmapi.h>
#endif

namespace suslik::app::TitleBar {

void style(QWindow *w) {
#ifdef Q_OS_WIN
    if (!w) return;
    const HWND hwnd = reinterpret_cast<HWND>(w->winId());
    auto set = [hwnd](DWORD attr, DWORD v) { DwmSetWindowAttribute(hwnd, attr, &v, sizeof v); };
    auto ref = [](DWORD rgb) -> DWORD { return ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF); };
    set(20, 1);                // DWMWA_USE_IMMERSIVE_DARK_MODE
    set(35, ref(0x060911));    // DWMWA_CAPTION_COLOR = фон окна: полоса и окно — одно целое
    set(36, ref(0x8E96A8));    // DWMWA_TEXT_COLOR = приглушённый
    set(34, 0xFFFFFFFE);       // DWMWA_BORDER_COLOR = без линии между полосой и окном
    set(33, 0);                // DWMWA_WINDOW_CORNER_PREFERENCE = по умолчанию
#else
    (void)w;
#endif
}

} // namespace suslik::app::TitleBar
