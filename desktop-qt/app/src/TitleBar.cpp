#include "TitleBar.h"

#include <QMargins>
#include <QPointF>
#include <QWindow>

#ifdef Q_OS_WIN
#include <QtGui/qpa/qplatformwindow_p.h>
#include <windows.h>
#include <dwmapi.h>
#endif

namespace suslik::app::TitleBar {

void style(QWindow *w) {
#ifdef Q_OS_WIN
    if (!w) return;
    const HWND hwnd = reinterpret_cast<HWND>(w->winId());
    auto set = [hwnd](DWORD attr, DWORD v) { DwmSetWindowAttribute(hwnd, attr, &v, sizeof v); };
    set(20, 1);                // DWMWA_USE_IMMERSIVE_DARK_MODE: тёмные системное меню и рамка
    set(34, 0xFFFFFFFE);       // DWMWA_BORDER_COLOR = без цветной линии по краю
    set(33, 0);                // DWMWA_WINDOW_CORNER_PREFERENCE = по умолчанию (Windows 11 скругляет)
#else
    (void)w;
#endif
}

#ifdef Q_OS_WIN
namespace {
UINT dpiOf(HWND hwnd) {
    const UINT d = GetDpiForWindow(hwnd);
    return d ? d : 96;
}
/// Рамка окна такого стиля при этом DPI (как её считает Qt — QWindowsGeometryHint::frame).
RECT frameOf(HWND hwnd) {
    RECT r{0, 0, 0, 0};
    const DWORD style = DWORD(GetWindowLongPtrW(hwnd, GWL_STYLE));
    const DWORD ex = DWORD(GetWindowLongPtrW(hwnd, GWL_EXSTYLE));
    AdjustWindowRectExForDpi(&r, style, FALSE, ex, dpiOf(hwnd));
    return r;
}
} // namespace
#endif

void hideCaption(QWindow *w, bool on) {
    if (!w) return;
#ifdef Q_OS_WIN
    const HWND hwnd = reinterpret_cast<HWND>(w->winId());
    auto *ni = w->nativeInterface<QNativeInterface::Private::QWindowsWindow>();
    if (!ni) return;
    if (!on) { ni->setCustomMargins(QMargins()); return; }
    if (w->flags() & Qt::FramelessWindowHint) return;
    // верх рамки (полоса изменения размера + заголовок) — внутрь окна; бока и низ остаются системными
    const RECT f = frameOf(hwnd);
    ni->setCustomMargins(QMargins(0, int(f.top), 0, 0)); // f.top < 0
#else
    w->setFlag(Qt::FramelessWindowHint, on);
#endif
}

int resizeBorder(QWindow *w) {
#ifdef Q_OS_WIN
    if (!w) return 0;
    const UINT dpi = dpiOf(reinterpret_cast<HWND>(w->winId()));
    return GetSystemMetricsForDpi(SM_CYSIZEFRAME, dpi) + GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi);
#else
    (void)w;
    return 0;
#endif
}

qreal maximizedInset(QWindow *w) {
#ifdef Q_OS_WIN
    if (!w || !(w->windowStates() & Qt::WindowMaximized) || (w->flags() & Qt::FramelessWindowHint)) return 0;
    const HWND hwnd = reinterpret_cast<HWND>(w->winId());
    RECT wr;
    if (!GetWindowRect(hwnd, &wr)) return 0;
    MONITORINFO mi{};
    mi.cbSize = sizeof mi;
    if (!GetMonitorInfoW(MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST), &mi)) return 0;
    const int px = int(mi.rcWork.top - wr.top);
    return px > 0 ? px / w->devicePixelRatio() : 0;
#else
    (void)w;
    return 0;
#endif
}

void systemMenu(QWindow *w, const QPointF &localPos) {
#ifdef Q_OS_WIN
    if (!w) return;
    const HWND hwnd = reinterpret_cast<HWND>(w->winId());
    HMENU menu = GetSystemMenu(hwnd, FALSE);
    if (!menu) return;
    const bool maxed = w->windowStates() & Qt::WindowMaximized;
    auto enable = [menu](UINT id, bool on) { EnableMenuItem(menu, id, MF_BYCOMMAND | (on ? MF_ENABLED : MF_GRAYED)); };
    enable(SC_RESTORE, maxed);
    enable(SC_MOVE, !maxed);
    enable(SC_SIZE, !maxed);
    enable(SC_MINIMIZE, true);
    enable(SC_MAXIMIZE, !maxed);
    enable(SC_CLOSE, true);
    SetMenuDefaultItem(menu, UINT(-1), FALSE);
    // точка окна → физические пиксели экрана самой системой: на мониторах с разным масштабом не съезжает
    const qreal dpr = w->devicePixelRatio();
    POINT p{qRound(localPos.x() * dpr), qRound(localPos.y() * dpr)};
    ClientToScreen(hwnd, &p);
    const UINT cmd = UINT(TrackPopupMenu(menu, TPM_RETURNCMD | TPM_RIGHTBUTTON | TPM_TOPALIGN | TPM_LEFTALIGN,
                                         p.x, p.y, 0, hwnd, nullptr));
    if (cmd) PostMessageW(hwnd, WM_SYSCOMMAND, cmd, 0);
#else
    (void)w;
    (void)localPos;
#endif
}

} // namespace suslik::app::TitleBar
