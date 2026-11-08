#include "SceneProbe.h"
#include "ScreenQuad.h"

#include "player/PlayerLog.h"

#include <QCoreApplication>
#include <QPointer>
#include <QThreadPool>
#include <QWindow>
#include <algorithm>
#include <cmath>

#ifdef Q_OS_WIN
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#endif

namespace suslik::cinema {

namespace {

constexpr int kPeriodMs = 1000;  // раз в секунду: снимок рабочего стола через GDI не бесплатен для видеокарты
constexpr double kTau = 1.5;      // с: свет меняется плавно, но вход в зал заметен за пару секунд
constexpr double kRing = 0.7;     // область замера — экран, раздутый на 70 % размера в каждую сторону
constexpr double kSkip = 0.32;    // не считается — экран, раздутый на 32 % (полотно и почти весь отсвет, spill в Main.qml)
constexpr int kCells = 64;        // снимок по длинной стороне

#ifdef Q_OS_WIN
/// Уменьшенный снимок области экрана (физические пиксели) в RGB32 размером out.
QImage grab(const QRect &src, const QSize &out) {
    HDC screen = GetDC(nullptr);
    if (!screen) return {};
    HDC mem = CreateCompatibleDC(screen);
    BITMAPINFO bi{};
    bi.bmiHeader.biSize = sizeof(bi.bmiHeader);
    bi.bmiHeader.biWidth = out.width();
    bi.bmiHeader.biHeight = -out.height(); // сверху вниз
    bi.bmiHeader.biPlanes = 1;
    bi.bmiHeader.biBitCount = 32;
    bi.bmiHeader.biCompression = BI_RGB;
    void *bits = nullptr;
    HBITMAP bmp = CreateDIBSection(mem, &bi, DIB_RGB_COLORS, &bits, nullptr, 0);
    QImage img;
    if (bmp && bits) {
        HGDIOBJ old = SelectObject(mem, bmp);
        SetStretchBltMode(mem, HALFTONE); // среднее по области, а не каждая N-я точка
        SetBrushOrgEx(mem, 0, 0, nullptr);
        if (StretchBlt(mem, 0, 0, out.width(), out.height(), screen, src.x(), src.y(), src.width(), src.height(), SRCCOPY)) {
            GdiFlush();
            img = QImage(static_cast<const uchar *>(bits), out.width(), out.height(), out.width() * 4,
                         QImage::Format_RGB32).copy();
        }
        SelectObject(mem, old);
        DeleteObject(bmp);
    }
    DeleteDC(mem);
    ReleaseDC(nullptr, screen);
    return img;
}
#endif

QRectF inflate(const QRectF &r, double k) {
    return r.adjusted(-r.width() * k, -r.height() * k, r.width() * k, r.height() * k);
}

} // namespace

SceneProbe::SceneProbe(ScreenQuad *quad, QObject *parent) : QObject(parent), m_quad(quad) {
    m_timer.setInterval(kPeriodMs);
    connect(&m_timer, &QTimer::timeout, this, &SceneProbe::tick);
}

SceneProbe::~SceneProbe() {
    m_timer.stop();
}

void SceneProbe::setEnabled(bool on) {
    if (on == m_enabled) return;
    m_enabled = on;
    app::PlayerLog::write(QStringLiteral("[scene] вписать в сцену: %1").arg(on ? QStringLiteral("да") : QStringLiteral("нет")));
    setActive(m_active);
    emit changed();
}

void SceneProbe::setTuning(const SceneTuning &t) {
    if (t == m_tuning) return;
    m_tuning = t;
    emit changed();
}

void SceneProbe::setActive(bool on) {
    m_active = on;
    const bool run = m_enabled && m_active;
    if (run && !m_timer.isActive()) {
        m_since.start();
        m_timer.start();
        tick();
    } else if (!run) {
        m_timer.stop();
    }
}

void SceneProbe::tick() {
#ifdef Q_OS_WIN
    if (!m_window || !m_quad || !m_quad->valid() || m_busy->load()) return;
    const HWND hwnd = reinterpret_cast<HWND>(m_window->winId());
    RECT rc{};
    POINT origin{0, 0};
    if (!GetClientRect(hwnd, &rc) || !ClientToScreen(hwnd, &origin)) return;
    const QRectF client(origin.x, origin.y, rc.right - rc.left, rc.bottom - rc.top);
    if (client.isEmpty()) return;

    // углы экрана (доли окна) -> рамка в физических пикселях экрана
    QRectF box;
    for (const QVariant &v : m_quad->corners()) {
        const QPointF f = v.toPointF();
        const QPointF p(client.x() + f.x() * client.width(), client.y() + f.y() * client.height());
        box = box.isNull() ? QRectF(p, QSizeF(1, 1)) : box.united(QRectF(p, QSizeF(1, 1)));
    }
    const QRect area = inflate(box, kRing).intersected(client).toAlignedRect();
    if (area.width() < 8 || area.height() < 8) return;
    const double s = double(kCells) / std::max(area.width(), area.height());
    const QSize out(std::max(4, int(std::lround(area.width() * s))), std::max(4, int(std::lround(area.height() * s))));
    const QRectF skipF = inflate(box, kSkip).translated(-area.topLeft());
    const QRect skip(int(std::floor(skipF.left() * s)), int(std::floor(skipF.top() * s)),
                     int(std::ceil(skipF.width() * s)) + 1, int(std::ceil(skipF.height() * s)) + 1);

    m_busy->store(true);
    const auto busy = m_busy;
    QPointer<SceneProbe> self(this);
    QThreadPool::globalInstance()->start([self, busy, area, out, skip] {
        QElapsedTimer t;
        t.start();
        const QImage img = grab(area, out);
        const double grabMs = t.nsecsElapsed() / 1e6;
        const SceneSample sample = measureScene(img, skip);
        busy->store(false);
        QMetaObject::invokeMethod(qApp, [self, sample, grabMs] { if (self) self->apply(sample, grabMs); }, Qt::QueuedConnection);
    });
#endif
}

void SceneProbe::apply(const SceneSample &s, double grabMs) {
    // цена снимка — в журнал раз в 30 замеров: если экран «плавает», видно, не снимок ли тормозит
    m_grabSum += grabMs;
    m_grabMax = std::max(m_grabMax, grabMs);
    if (++m_grabs >= 30) {
        app::PlayerLog::write(QStringLiteral("[scene] снимок: в среднем %1 мс, до %2 мс; свет зала %3 %")
                                  .arg(m_grabSum / m_grabs, 0, 'f', 1).arg(m_grabMax, 0, 'f', 1)
                                  .arg(m_scene.luma * 100, 0, 'f', 2));
        m_grabs = 0;
        m_grabSum = m_grabMax = 0;
    }
    if (!m_enabled) return;
    const double dt = m_since.isValid() ? m_since.restart() / 1000.0 : kPeriodMs / 1000.0;
    if (!s.valid()) {
        if (m_logged++ < 3) app::PlayerLog::write(QStringLiteral("[scene] замер не вышел"));
        return;
    }
    m_scene = smoothScene(m_scene, s, std::min(dt, 2.0), kTau);
    m_look = sceneLookFor(m_scene);
    emit changed();
}

} // namespace suslik::cinema
