#include "WindowMode.h"
#include "../TitleBar.h"

#include <QCoreApplication>
#include <QCursor>
#include <QQuickWindow>
#include <memory>
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

void WindowMode::setWindow(QWindow *w) {
    m_w = w;
    if (!w) return;
    TitleBar::style(w);
    TitleBar::hideCaption(w, true);
    // состояние и размер развёрнутого окна приходят следом за сменой состояния: пересчёт — один, в конце цикла событий
    connect(w, &QWindow::windowStateChanged, this, &WindowMode::scheduleState);
    connect(w, &QWindow::screenChanged, this, &WindowMode::scheduleState);
    updateState();
}

void WindowMode::scheduleState() {
    if (!m_stateTimer) {
        m_stateTimer = new QTimer(this);
        m_stateTimer->setSingleShot(true);
        m_stateTimer->setInterval(0);
        connect(m_stateTimer, &QTimer::timeout, this, &WindowMode::updateState);
    }
    m_stateTimer->start();
}

void WindowMode::setMaxButton(bool hover, bool press) {
    if (hover == m_maxHover && press == m_maxPress) return;
    m_maxHover = hover;
    m_maxPress = press;
    emit maxButtonChanged();
}

void WindowMode::updateState() {
    if (!m_w) return;
    const bool maxed = (m_w->windowStates() & Qt::WindowMaximized) && !m_full && !m_mini;
    const qreal inset = maxed ? TitleBar::maximizedInset(m_w) : 0;
    if (maxed == m_maximized && qFuzzyCompare(inset + 1, m_topInset + 1)) return;
    m_maximized = maxed;
    m_topInset = inset;
    emit changed();
}

bool WindowMode::edgeResize() const {
#ifdef Q_OS_WIN
    return false;
#else
    return !m_full && !m_mini && !m_maximized;
#endif
}

void WindowMode::toggleMaximized() {
    if (!m_w || m_full || m_mini) return;
    if (m_w->windowStates() & Qt::WindowMaximized) m_w->showNormal();
    else m_w->showMaximized();
}

void WindowMode::systemMenu(qreal x, qreal y) {
    if (!m_w || m_full || m_mini) return;
    TitleBar::systemMenu(m_w, QPointF(x, y));
}

bool WindowMode::nativeEventFilter(const QByteArray &type, void *message, qintptr *result) {
#ifdef Q_OS_WIN
    // handle() пуст, пока окно создаётся: winId() здесь создал бы его второй раз изнутри CreateWindowEx
    if (!m_w || !m_w->handle() || type != "windows_generic_MSG") return false;
    auto *msg = static_cast<MSG *>(message);
    if (msg->hwnd != reinterpret_cast<HWND>(m_w->winId())) return false;
    // Кнопка «Развернуть» своего заголовка отдаётся системе (HTMAXBUTTON): Windows 11 показывает над ней панель макетов
    // Snap Layouts. Её мышь тогда идёт «вне окна»: подсветку и щелчок ведём сами, системе их не отдаём (иначе она
    // нарисует поверх старую серую кнопку).
    const bool own = !m_mini && !m_full && !(m_w->windowStates() & Qt::WindowMinimized);
    auto onMax = [this, msg](LPARAM lp) {
        if (!m_captionButtons) return false;
        POINT p{int(short(LOWORD(lp))), int(short(HIWORD(lp)))};
        ScreenToClient(msg->hwnd, &p);
        RECT rc;
        GetClientRect(msg->hwnd, &rc);
        const qreal dpr = m_w->devicePixelRatio();
        const int top = qRound(m_topInset * dpr), h = qRound(32 * dpr), bw = qRound(46 * dpr);
        return p.y >= top && p.y < top + h && p.x >= rc.right - 2 * bw && p.x < rc.right - bw;
    };
    switch (msg->message) {
    case WM_NCHITTEST: {
        if (!own) return false;
        const LRESULT hit = DefWindowProcW(msg->hwnd, msg->message, msg->wParam, msg->lParam);
        if (hit != HTCLIENT) return false;
        if (onMax(msg->lParam)) { if (result) *result = HTMAXBUTTON; return true; }
        if (m_w->windowStates() & Qt::WindowMaximized) return false;
        // верхней системной рамки нет — верхний край тянет размер сам (бока и низ — системные)
        POINT p{int(short(LOWORD(msg->lParam))), int(short(HIWORD(msg->lParam)))};
        ScreenToClient(msg->hwnd, &p);
        RECT rc;
        GetClientRect(msg->hwnd, &rc);
        const int border = TitleBar::resizeBorder(m_w);
        if (p.y >= border) return false;
        if (result) *result = p.x < border * 2 ? HTTOPLEFT : p.x >= rc.right - border * 2 ? HTTOPRIGHT : HTTOP;
        return true;
    }
    case WM_NCMOUSEMOVE:
        if (msg->wParam != HTMAXBUTTON) { setMaxButton(false, false); return false; }
        if (!m_maxHover) {
            TRACKMOUSEEVENT t{sizeof t, TME_LEAVE | TME_NONCLIENT, msg->hwnd, 0};
            TrackMouseEvent(&t);
        }
        setMaxButton(true, m_maxPress);
        if (result) *result = 0;
        return true;
    case WM_NCMOUSELEAVE:
    case WM_MOUSEMOVE:
        setMaxButton(false, false);
        return false;
    case WM_NCLBUTTONDOWN:
    case WM_NCLBUTTONDBLCLK:
        if (msg->wParam != HTMAXBUTTON) return false;
        setMaxButton(true, true);
        if (result) *result = 0;
        return true;
    case WM_NCLBUTTONUP:
        if (msg->wParam != HTMAXBUTTON) return false;
        if (m_maxPress) QTimer::singleShot(0, this, &WindowMode::toggleMaximized);
        setMaxButton(true, false);
        if (result) *result = 0;
        return true;
    default:
        break;
    }
    // другой монитор (DPI): высота убранной полосы заголовка пересчитывается
    if (msg->message == WM_DPICHANGED && !m_mini && !m_full) {
        QTimer::singleShot(0, this, [this] {
            if (m_w && !m_mini && !m_full) TitleBar::hideCaption(m_w, true);
            scheduleState();
        });
        return false;
    }
    if (!m_mini || msg->message != WM_SIZING) return false;
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
#ifdef Q_OS_WIN
    // свои поля Qt применяет и к окну без рамки: снимаются до, ставятся после
    if (on) {
        TitleBar::hideCaption(m_w, false);
        m_w->setFlag(Qt::FramelessWindowHint, true);
    } else {
        m_w->setFlag(Qt::FramelessWindowHint, false);
        TitleBar::hideCaption(m_w, true);
    }
#else
    Q_UNUSED(on)
    m_w->setFlag(Qt::FramelessWindowHint, true); // вне Windows окно всегда без рамки, заголовок свой
#endif
}

void WindowMode::cloakWhile(const std::function<void()> &change, const std::function<void()> &after) {
#ifdef Q_OS_WIN
    const HWND before = reinterpret_cast<HWND>(m_w->winId());
    BOOL on = TRUE;
    const bool cloaked = SUCCEEDED(DwmSetWindowAttribute(before, 13 /*DWMWA_CLOAK*/, &on, sizeof on));
    change();
    if (!cloaked) { if (after) after(); return; }
    const int gen = ++m_cloakGen;
    auto uncloak = [this, before, gen, after] {
        if (gen != m_cloakGen) return;
        ++m_cloakGen;
        BOOL off = FALSE;
        DwmSetWindowAttribute(before, 13, &off, sizeof off);
        if (m_w) {
            const HWND now = reinterpret_cast<HWND>(m_w->winId());
            if (now != before) DwmSetWindowAttribute(now, 13, &off, sizeof off);
        }
        if (after) after();
    };
    // показать, когда Qt Quick дважды нарисовал окно уже в новой рамке; самое позднее — через 150 мс
    if (auto *qw = qobject_cast<QQuickWindow *>(m_w.data())) {
        auto conn = std::make_shared<QMetaObject::Connection>();
        auto frames = std::make_shared<int>(0);
        *conn = connect(qw, &QQuickWindow::frameSwapped, this, [conn, frames, uncloak] {
            if (++*frames < 2) return;
            QObject::disconnect(*conn);
            uncloak();
        }, Qt::QueuedConnection);
        qw->update();
    }
    QTimer::singleShot(150, this, uncloak);
#else
    change();
    if (after) after();
#endif
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
    scheduleState();
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
        connect(m_anim, &QVariantAnimation::finished, this, [this] { if (m_mini) fitMini(m_aspect); });
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
        const QRect to(work.right() - w - 24, work.bottom() - h - 24, w, h);
        m_mini = true;
        // рамка снимается, пока окно спрятано: без белой рамки на миг; в угол едет уже видимое окно
        cloakWhile([this] {
            m_w->setMinimumSize(QSize(120, 80));
            m_w->setWindowStates(Qt::WindowNoState);
            frameless(true);
            setOnTop(true);
            m_w->show();
            roundCorners(true);
        }, [this, to] { if (m_mini) fly(to); });
    } else {
        if (m_anim) m_anim->stop();
        if (m_smooth) m_smooth->stop();
        m_resizeEdges = 0;
        m_mini = false;
        cloakWhile([this] {
            roundCorners(false);
            frameless(false);
            setOnTop(m_topBeforeMini);
            m_w->setMinimumSize(m_minBefore);
            m_w->setGeometry(m_before);
            if (m_maximizedBefore) m_w->setWindowStates(Qt::WindowMaximized);
            m_w->show();
        });
    }
    emit changed();
    scheduleState();
}

void WindowMode::fitMini(double aspect) {
    m_aspect = clampAspect(aspect);
    if (!m_w || !m_mini) return;
    if (m_anim && m_anim->state() == QAbstractAnimation::Running) {
        const QRect cur = m_anim->endValue().toRect();
        const int h = int(std::round(cur.width() / m_aspect));
        const int y = cur.bottom() + 1 - h;
        m_anim->setEndValue(QRect(cur.x(), y, cur.width(), h));
        return;
    }
    const int h = int(std::round(m_w->width() / m_aspect));
    if (std::abs(m_w->height() - h) > 2) {
        const int y = m_w->y() + (m_w->height() - h);
        m_w->setGeometry(m_w->x(), y, m_w->width(), h);
    }
}

void WindowMode::startMove(qreal grabY) {
    if (!m_w) return;
    // Развёрнутое окно системное перемещение не двигает: сначала обычный размер, окно встаёт под курсор
    // на ту же долю ширины, за которую его взяли, и уже тогда едет (как у системного заголовка)
    if (grabY >= 0 && !m_mini && !m_full && (m_w->windowStates() & Qt::WindowMaximized)) {
        const QPoint c = QCursor::pos();
        const QRect was = m_w->geometry();
        const double fx = was.width() > 0 ? double(c.x() - was.x()) / was.width() : 0.5;
        m_w->showNormal();
        const QRect now = m_w->geometry();
        m_w->setPosition(c.x() - int(fx * now.width()), c.y() - int(grabY));
    }
    m_w->startSystemMove();
}

void WindowMode::startResize(int edges) { if (m_w) m_w->startSystemResize(Qt::Edges(edges)); }

void WindowMode::beginResize(int edges) {
    if (!m_w || !m_mini) return;
    if (m_anim) m_anim->stop();
    if (m_smooth) m_smooth->stop();
    m_resizeEdges = edges;
    m_resizeFrom = m_w->geometry();
    m_resizeCursor = QCursor::pos();
    m_curW = m_targetW = m_resizeFrom.width();
    m_resizeTimer.start();
    m_lastResizeMs = -1000;
}

void WindowMode::resizeStep() {
    if (!m_w || !m_mini || !m_resizeEdges) return;
    const QPoint d = QCursor::pos() - m_resizeCursor;
    const bool left = m_resizeEdges & Qt::LeftEdge, top = m_resizeEdges & Qt::TopEdge;
    const bool hasH = (m_resizeEdges & (Qt::LeftEdge | Qt::RightEdge)) != 0;
    const bool hasV = (m_resizeEdges & (Qt::TopEdge | Qt::BottomEdge)) != 0;
    const double a = m_aspect;
    const double ux = left ? -d.x() : d.x();
    const double uy = top ? -d.y() : d.y();

    double deltaW = 0.0;
    if (hasH && hasV) {
        // Угол: проекция смещения курсора на диагональ пропорций фильма (16:9 / 21:9)
        deltaW = (a * (a * ux + uy)) / (a * a + 1.0);
    } else if (hasH) {
        // Боковая сторона: тянут по горизонтали — высота следует пропорциям
        deltaW = ux;
    } else if (hasV) {
        // Верх/низ: тянут по вертикали — ширина следует пропорциям
        deltaW = uy * a;
    }

    QScreen *s = m_w->screen() ? m_w->screen() : QGuiApplication::primaryScreen();
    const QRect work = s->availableGeometry();
    const double maxW = (std::min<double>)(work.width(), work.height() * a);
    const double minW = (std::max)(200.0, 120.0 * a);
    m_targetW = std::clamp(m_resizeFrom.width() + deltaW, minW, (std::max)(minW, maxW));

    const qint64 now = m_resizeTimer.elapsed();
    if (now - m_lastResizeMs >= 16) {
        m_lastResizeMs = now;
        applyResize();
    } else {
        if (!m_smooth) {
            m_smooth = new QTimer(this);
            m_smooth->setSingleShot(true);
            m_smooth->setTimerType(Qt::PreciseTimer);
            connect(m_smooth, &QTimer::timeout, this, [this] {
                if (m_resizeEdges && m_curW != m_targetW) {
                    m_lastResizeMs = m_resizeTimer.elapsed();
                    applyResize();
                }
            });
        }
        if (!m_smooth->isActive()) {
            m_smooth->start(16);
        }
    }
}

void WindowMode::applyResize() {
    if (!m_w || !m_mini || !m_resizeEdges) return;
    m_curW = m_targetW;
    const bool left = m_resizeEdges & Qt::LeftEdge, top = m_resizeEdges & Qt::TopEdge;
    const int w = int(std::lround(m_curW));
    const int h = int(std::lround(w / m_aspect));
    const int x = left ? m_resizeFrom.right() + 1 - w : m_resizeFrom.left();
    const int y = top ? m_resizeFrom.bottom() + 1 - h : m_resizeFrom.top();
    const QRect next(x, y, w, h);
    if (m_w->geometry() != next) {
        m_w->setGeometry(next);
    }
}

void WindowMode::endResize() {
    if (m_resizeEdges) {
        if (m_smooth) m_smooth->stop();
        if (m_curW != m_targetW) {
            applyResize();
        }
        m_resizeEdges = 0;
    }
}

void WindowMode::reset() {
    if (m_mini) setMini(false);
    if (m_full) setFullscreen(false);
}

void WindowMode::minimize() {
    if (m_w) m_w->setWindowStates(m_w->windowStates() | Qt::WindowMinimized);
}

} // namespace suslik::app
