#include "WindowMode.h"

#include <QCoreApplication>
#include <QCursor>
#include <QGuiApplication>
#include <QScreen>
#include <QVariantAnimation>
#include <QWindow>
#include <algorithm>
#include <cmath>

#include <QTimer>

#ifdef Q_OS_WIN
#include <windows.h>
#include <dwmapi.h>
#endif

namespace suslik::app {

namespace {
/// Пропорции фильма для мини-плеера: от вертикальных до сверхшироких, без сужения — иначе чёрные полосы.
double clampAspect(double a) { return std::isfinite(a) && a > 0 ? std::clamp(a, 0.4, 4.0) : 16.0 / 9.0; }
} // namespace

WindowMode::WindowMode(QObject *parent) : QObject(parent) {
    if (QCoreApplication::instance()) QCoreApplication::instance()->installNativeEventFilter(this);
}

WindowMode::~WindowMode() {
    if (QCoreApplication::instance()) QCoreApplication::instance()->removeNativeEventFilter(this);
}

bool WindowMode::nativeEventFilter(const QByteArray &type, void *message, qintptr *result) {
#ifdef Q_OS_WIN
    if (!m_mini || !m_w || type != "windows_generic_MSG") return false;
    auto *msg = static_cast<MSG *>(message);
    if (msg->message != WM_SIZING || msg->hwnd != reinterpret_cast<HWND>(m_w->winId())) return false;
    auto *r = reinterpret_cast<RECT *>(msg->lParam);
    const int edge = int(msg->wParam);
    const double a = m_aspect;
    long w = r->right - r->left, h = r->bottom - r->top;
    if (edge == WMSZ_TOP || edge == WMSZ_BOTTOM) {
        w = std::lround(h * a); // тянут по высоте — ширина следом, вправо
        r->right = r->left + w;
    } else {
        h = std::lround(w / a); // по ширине или за угол — высота следом
        if (edge == WMSZ_TOPLEFT || edge == WMSZ_TOPRIGHT) r->top = r->bottom - h;
        else r->bottom = r->top + h;
    }
    if (result) *result = TRUE;
    return true;
#else
    Q_UNUSED(type)
    Q_UNUSED(message)
    Q_UNUSED(result)
    return false;
#endif
}

void WindowMode::frameless(bool on) {
    m_w->setFlag(Qt::FramelessWindowHint, on);
}

void WindowMode::roundCorners(bool on) {
#ifdef Q_OS_WIN
    // Windows 11: скругление рисует сама система (сглаженное, с тенью); в Windows 10 атрибута нет — углы прямые
    const DWORD pref = on ? 3 /*DWMWCP_ROUNDSMALL*/ : 0 /*DWMWCP_DEFAULT*/;
    DwmSetWindowAttribute(reinterpret_cast<HWND>(m_w->winId()), 33 /*DWMWA_WINDOW_CORNER_PREFERENCE*/, &pref, sizeof pref);
#else
    Q_UNUSED(on)
#endif
}

void WindowMode::setFullscreen(bool on) {
    if (!m_w || on == m_full) return;
    if (on) {
        if (m_mini) setMini(false);
        m_maximizedBefore = m_w->windowStates() & Qt::WindowMaximized;
        m_before = m_w->geometry();
        QScreen *s = m_w->screen() ? m_w->screen() : QGuiApplication::primaryScreen();
        frameless(true);
        m_w->setWindowStates(Qt::WindowNoState);
        const QRect g = s->geometry();
#ifdef Q_OS_WIN
        m_w->setGeometry(g.adjusted(0, 0, 0, 1)); // на пиксель выше экрана: не эксклюзивный режим
#else
        m_w->setGeometry(g);
#endif
        m_w->show();
    } else {
        frameless(false);
        m_w->setGeometry(m_before);
        if (m_maximizedBefore) m_w->setWindowStates(Qt::WindowMaximized);
        m_w->show();
    }
    m_full = on;
    emit changed();
}

void WindowMode::setOnTop(bool on) {
    if (!m_w || on == m_onTop) return;
    m_w->setFlag(Qt::WindowStaysOnTopHint, on);
    m_w->show();
    m_onTop = on;
    emit changed();
}

void WindowMode::fly(const QRect &to) {
    if (!m_anim) {
        m_anim = new QVariantAnimation(this);
        m_anim->setDuration(320);
        m_anim->setEasingCurve(QEasingCurve::OutCubic);
        connect(m_anim, &QVariantAnimation::valueChanged, this, [this](const QVariant &v) { if (m_w) m_w->setGeometry(v.toRect()); });
    }
    m_anim->stop();
    m_anim->setStartValue(m_w->geometry());
    m_anim->setEndValue(to);
    m_anim->start();
}

void WindowMode::setMini(bool on, double aspect) {
    if (!m_w || on == m_mini) return;
    if (on) {
        if (m_full) setFullscreen(false);
        m_maximizedBefore = m_w->windowStates() & Qt::WindowMaximized;
        m_before = m_w->geometry();
        m_minBefore = m_w->minimumSize();
        m_topBeforeMini = m_onTop;
        const double a = clampAspect(aspect);
        m_aspect = a;
        const int w = std::clamp(int(std::sqrt(590.0 * 330.0 * a)), 260, 620);
        const int h = int(std::round(w / a));
        QScreen *s = m_w->screen() ? m_w->screen() : QGuiApplication::primaryScreen();
        const QRect work = s->availableGeometry();
        m_w->setMinimumSize(QSize(120, 80));
        m_w->setWindowStates(Qt::WindowNoState);
        frameless(true);
        m_mini = true;
        setOnTop(true);
        m_w->show();
        roundCorners(true);
        fly(QRect(work.right() - w - 24, work.bottom() - h - 24, w, h));
    } else {
        if (m_anim) m_anim->stop();
        roundCorners(false);
        frameless(false);
        m_mini = false;
        setOnTop(m_topBeforeMini);
        m_w->setMinimumSize(m_minBefore);
        m_w->setGeometry(m_before);
        if (m_maximizedBefore) m_w->setWindowStates(Qt::WindowMaximized);
        m_w->show();
    }
    emit changed();
}

void WindowMode::fitMini(double aspect) {
    m_aspect = clampAspect(aspect);
    if (!m_w || !m_mini || (m_anim && m_anim->state() == QAbstractAnimation::Running)) return;
    const int h = int(std::round(m_w->width() / m_aspect));
    if (std::abs(m_w->height() - h) > 2) m_w->resize(m_w->width(), h);
}

void WindowMode::startMove() { if (m_w) m_w->startSystemMove(); }

void WindowMode::startResize(int edges) { if (m_w) m_w->startSystemResize(Qt::Edges(edges)); }

void WindowMode::reset() {
    if (m_mini) setMini(false);
    if (m_full) setFullscreen(false);
}

} // namespace suslik::app
