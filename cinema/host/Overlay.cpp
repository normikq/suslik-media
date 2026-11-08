#include "Overlay.h"
#include "PlayerLog.h"

#include <QGuiApplication>
#include <QScreen>
#include <QTimer>
#include <QWindow>

#ifdef Q_OS_WIN
#include <windows.h>
#endif

namespace suslik::cinema {

namespace {

#ifdef Q_OS_WIN
enum HotkeyId { HkCalibrate = 0x5C01, HkPause, HkHide, HkQuit, HkLeadUp, HkLeadDown, HkRecord };

QString exePathOf(DWORD pid) {
    HANDLE p = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (!p) return {};
    wchar_t buf[MAX_PATH];
    DWORD n = MAX_PATH;
    QString path;
    if (QueryFullProcessImageNameW(p, 0, buf, &n)) path = QString::fromWCharArray(buf, int(n));
    CloseHandle(p);
    return path;
}

QString exeOf(DWORD pid) { return exePathOf(pid).section(QLatin1Char('\\'), -1); }

struct Find {
    QString exe;
    HWND found = nullptr;
};

BOOL CALLBACK findTop(HWND h, LPARAM lp) {
    auto *f = reinterpret_cast<Find *>(lp);
    if (!IsWindowVisible(h) || GetWindow(h, GW_OWNER)) return TRUE;
    DWORD pid = 0;
    GetWindowThreadProcessId(h, &pid);
    if (exeOf(pid).compare(f->exe, Qt::CaseInsensitive) != 0) return TRUE;
    RECT r;
    if (!GetClientRect(h, &r) || r.right - r.left < 320) return TRUE; // не заставка и не служебное окно
    f->found = h;
    return FALSE;
}

/// Клиентская область окна игры в физических пикселях экрана.
QRect gameClientRect(const QString &exe, HWND *hwnd) {
    Find f{exe};
    EnumWindows(findTop, reinterpret_cast<LPARAM>(&f));
    *hwnd = f.found;
    if (!f.found || IsIconic(f.found)) return {};
    RECT r;
    GetClientRect(f.found, &r);
    POINT tl{r.left, r.top};
    ClientToScreen(f.found, &tl);
    return QRect(tl.x, tl.y, r.right - r.left, r.bottom - r.top);
}
Overlay *g_overlay = nullptr;
#endif

} // namespace

#ifdef Q_OS_WIN
/// Смена активного окна (SetWinEventHook): экран прячется сразу, как только игра ушла на задний план.
struct ForegroundHook {
    static void CALLBACK proc(HWINEVENTHOOK, DWORD, HWND, LONG, LONG, DWORD, DWORD) {
        if (g_overlay) g_overlay->checkForeground();
    }
};
#endif

Overlay::Overlay(QString gameExe, QObject *parent) : QObject(parent), m_gameExe(std::move(gameExe)) {}

void Overlay::attach(QWindow *window) {
    m_window = window;
    applyStyles();
    auto *t = new QTimer(this);
    t->setInterval(500);
    connect(t, &QTimer::timeout, this, &Overlay::followGame);
    t->start();
    followGame();
#ifdef Q_OS_WIN
    g_overlay = this;
    m_winEventHook = SetWinEventHook(EVENT_SYSTEM_FOREGROUND, EVENT_SYSTEM_FOREGROUND, nullptr, &ForegroundHook::proc,
                                     0, 0, WINEVENT_OUTOFCONTEXT);
#endif
}

void Overlay::checkForeground() {
    bool active = true;
#ifdef Q_OS_WIN
    if (m_gameFound && m_window) {
        const HWND fg = GetForegroundWindow();
        active = fg == reinterpret_cast<HWND>(m_gameHwnd) || fg == reinterpret_cast<HWND>(m_window->winId());
        // Окно игры в Borderless тоже «всегда сверху»: активируясь, оно встаёт над оверлеем. Поднимаем оверлей
        // при каждой смене активного окна и раз в 0,5 с (followGame), не забирая фокус.
        if (active)
            SetWindowPos(reinterpret_cast<HWND>(m_window->winId()), HWND_TOPMOST, 0, 0, 0, 0,
                         SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_NOOWNERZORDER);
    }
#endif
    if (active == m_gameActive) return;
    m_gameActive = active;
    app::PlayerLog::write(QStringLiteral("[cinema] игра %1").arg(active ? QStringLiteral("на переднем плане") : QStringLiteral("в фоне")));
    emit gameActiveChanged();
}

Overlay::~Overlay() {
#ifdef Q_OS_WIN
    if (m_winEventHook) UnhookWinEvent(static_cast<HWINEVENTHOOK>(m_winEventHook));
    if (g_overlay == this) g_overlay = nullptr;
    if (m_hotkeys)
        for (int id = HkCalibrate; id <= HkRecord; ++id) UnregisterHotKey(nullptr, id);
#endif
    if (m_hotkeys) QGuiApplication::instance()->removeNativeEventFilter(this);
}

void Overlay::setCalibrating(bool on) {
    if (on == m_calibrating) return;
    m_calibrating = on;
    applyStyles();
    app::PlayerLog::write(QStringLiteral("[cinema] калибровка %1").arg(on ? QStringLiteral("вкл") : QStringLiteral("выкл")));
    emit calibratingChanged();
}

void Overlay::applyStyles() {
    if (!m_window) return;
#ifdef Q_OS_WIN
    auto h = reinterpret_cast<HWND>(m_window->winId());
    LONG_PTR ex = GetWindowLongPtrW(h, GWL_EXSTYLE);
    ex |= WS_EX_LAYERED | WS_EX_TOOLWINDOW | WS_EX_TOPMOST;
    ex &= ~LONG_PTR(WS_EX_APPWINDOW);
    if (m_calibrating) ex &= ~LONG_PTR(WS_EX_TRANSPARENT | WS_EX_NOACTIVATE);
    else ex |= WS_EX_TRANSPARENT | WS_EX_NOACTIVATE;
    SetWindowLongPtrW(h, GWL_EXSTYLE, ex);
    // Слоистое окно без атрибутов не показывается; альфа 255 — прозрачность берётся из пикселей сцены
    SetLayeredWindowAttributes(h, 0, 255, LWA_ALPHA);
    SetWindowPos(h, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_FRAMECHANGED);
    if (m_calibrating) SetForegroundWindow(h);
#endif
}

void Overlay::followGame() {
    if (!m_window) return;
    QRect r;
#ifdef Q_OS_WIN
    HWND hwnd = nullptr;
    if (!m_gameExe.isEmpty()) r = gameClientRect(m_gameExe, &hwnd);
    if (hwnd && quintptr(hwnd) != m_gameHwnd) {
        DWORD pid = 0;
        GetWindowThreadProcessId(hwnd, &pid);
        m_gameExePath = exePathOf(pid);
    }
    m_gameHwnd = quintptr(hwnd);
#endif
    const bool found = !r.isEmpty();
    if (found != m_gameFound) {
        m_gameFound = found;
        app::PlayerLog::write(QStringLiteral("[cinema] окно %1 %2").arg(m_gameExe, found ? QStringLiteral("найдено") : QStringLiteral("не найдено")));
        emit gameChanged();
    }
    checkForeground();
    if (!found) {
        // Без игры — весь основной экран (физические пиксели)
        QScreen *s = QGuiApplication::primaryScreen();
        const QRect g = s->geometry();
        const qreal dpr = s->devicePixelRatio();
        r = QRect(qRound(g.x() * dpr), qRound(g.y() * dpr), qRound(g.width() * dpr), qRound(g.height() * dpr));
    }
    if (r == m_lastRect) return;
    m_lastRect = r;
    app::PlayerLog::write(QStringLiteral("[cinema] область %1,%2 %3x%4").arg(r.x()).arg(r.y()).arg(r.width()).arg(r.height()));
#ifdef Q_OS_WIN
    // В физических пикселях: Qt сам пересчитает в логические по WM_SIZE / WM_DPICHANGED
    SetWindowPos(reinterpret_cast<HWND>(m_window->winId()), HWND_TOPMOST, r.x(), r.y(), r.width(), r.height(),
                 SWP_NOACTIVATE);
#else
    m_window->setGeometry(r);
#endif
}

void Overlay::registerHotkeys() {
#ifdef Q_OS_WIN
    if (m_hotkeys) return;
    m_hotkeys = true;
    const UINT mod = MOD_CONTROL | MOD_ALT | MOD_NOREPEAT;
    RegisterHotKey(nullptr, HkCalibrate, mod, 'C');
    RegisterHotKey(nullptr, HkPause, mod, 'P');
    RegisterHotKey(nullptr, HkHide, mod, 'H');
    RegisterHotKey(nullptr, HkQuit, mod, 'Q');
    RegisterHotKey(nullptr, HkLeadUp, MOD_CONTROL | MOD_ALT, VK_PRIOR);
    RegisterHotKey(nullptr, HkLeadDown, MOD_CONTROL | MOD_ALT, VK_NEXT);
    RegisterHotKey(nullptr, HkRecord, mod, 'R');
    QGuiApplication::instance()->installNativeEventFilter(this);
#endif
}

bool Overlay::nativeEventFilter(const QByteArray &type, void *message, qintptr *) {
#ifdef Q_OS_WIN
    if (type != "windows_generic_MSG") return false;
    const MSG *m = static_cast<const MSG *>(message);
    if (m->message != WM_HOTKEY) return false;
    switch (int(m->wParam)) {
    case HkCalibrate: setCalibrating(!m_calibrating); break;
    case HkPause: emit hotkey(QStringLiteral("pause")); break;
    case HkHide: emit hotkey(QStringLiteral("hide")); break;
    case HkQuit: emit hotkey(QStringLiteral("quit")); break;
    case HkLeadUp: emit hotkey(QStringLiteral("lead+")); break;
    case HkLeadDown: emit hotkey(QStringLiteral("lead-")); break;
    case HkRecord: emit hotkey(QStringLiteral("rec")); break;
    default: return false;
    }
    return true;
#else
    Q_UNUSED(type);
    Q_UNUSED(message);
    return false;
#endif
}

} // namespace suslik::cinema
