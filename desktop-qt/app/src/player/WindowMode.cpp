#include "WindowMode.h"

#include <QGuiApplication>
#include <QScreen>
#include <QVariantAnimation>
#include <QWindow>
#include <cmath>

namespace suslik::app {

void WindowMode::frameless(bool on) {
    m_w->setFlag(Qt::FramelessWindowHint, on);
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
        const double a = std::clamp(aspect, 1.2, 2.4);
        const int w = std::clamp(int(std::sqrt(590.0 * 330.0 * a)), 400, 620);
        const int h = int(std::round(w / a));
        QScreen *s = m_w->screen() ? m_w->screen() : QGuiApplication::primaryScreen();
        const QRect work = s->availableGeometry();
        m_w->setMinimumSize(QSize(160, 100));
        m_w->setWindowStates(Qt::WindowNoState);
        frameless(true);
        m_mini = true;
        setOnTop(true);
        m_w->show();
        fly(QRect(work.right() - w - 24, work.bottom() - h - 24, w, h));
    } else {
        if (m_anim) m_anim->stop();
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
    if (!m_w || !m_mini || (m_anim && m_anim->state() == QAbstractAnimation::Running)) return;
    const int h = int(std::round(m_w->width() / std::clamp(aspect, 1.2, 2.4)));
    if (std::abs(m_w->height() - h) > 2) m_w->resize(m_w->width(), h);
}

void WindowMode::startMove() { if (m_w) m_w->startSystemMove(); }

void WindowMode::startResize(int edges) { if (m_w) m_w->startSystemResize(Qt::Edges(edges)); }

void WindowMode::reset() {
    if (m_mini) setMini(false);
    if (m_full) setFullscreen(false);
}

} // namespace suslik::app
