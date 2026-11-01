#include "IconProvider.h"

#include <QPainter>
#include <QPainterPath>
#include <QTransform>
#include <cmath>
#include <initializer_list>

// Перенос рисования из Icon.qml / PlayerIcon.qml (Canvas 2D) на QPainter: те же координаты и толщины.
// Углы: в Canvas — радианы по часовой (ось y вниз), в QPainterPath::arcTo — градусы против часовой, отсюда минусы.

namespace suslik::app {

namespace {
constexpr double PI = 3.14159265358979323846;

class Pen2D {
public:
    Pen2D(QPainter &p, const QColor &c) : g(p), color(c) {}
    QPainter &g;
    QColor color;
    Qt::PenCapStyle cap = Qt::RoundCap;

    static QPainterPath path() { QPainterPath p; p.setFillRule(Qt::WindingFill); return p; }
    void stroke(const QPainterPath &p, double sw) {
        QPen pen(color, sw, Qt::SolidLine, cap, Qt::RoundJoin);
        g.strokePath(p, pen);
    }
    void fill(const QPainterPath &p) { g.fillPath(p, color); }

    // arc(x, y, r, a0, a1, ccw) как в Canvas: из текущей точки — линия к началу дуги
    static void arc(QPainterPath &p, double cx, double cy, double r, double a0, double a1, bool ccw = false) {
        double sweep = a1 - a0;
        if (!ccw) { if (sweep >= 2 * PI) sweep = 2 * PI; else { sweep = std::fmod(sweep, 2 * PI); if (sweep < 0) sweep += 2 * PI; } }
        else { if (-sweep >= 2 * PI) sweep = -2 * PI; else { sweep = std::fmod(sweep, 2 * PI); if (sweep > 0) sweep -= 2 * PI; } }
        const QPointF start(cx + r * std::cos(a0), cy + r * std::sin(a0));
        if (p.isEmpty()) p.moveTo(start); else p.lineTo(start); // isEmpty: setFillRule уже кладёт moveTo(0,0)
        p.arcTo(QRectF(cx - r, cy - r, 2 * r, 2 * r), -a0 * 180 / PI, -sweep * 180 / PI);
    }
    void circle(double x, double y, double r, bool fill_, double sw = 1) {
        QPainterPath p = path(); arc(p, x, y, r, 0, 2 * PI);
        if (fill_) fill(p); else stroke(p, sw);
    }
    void line(double x1, double y1, double x2, double y2, double sw) {
        QPainterPath p = path(); p.moveTo(x1, y1); p.lineTo(x2, y2); stroke(p, sw);
    }
};

void paintUi(Pen2D &d, const QString &name, double w, double h, bool filled) {
    auto path = Pen2D::path;
    const double k = w / 16; // толщины линий заданы для 16 px
    auto poly = [&](std::initializer_list<double> pts, bool close, bool fill, double sw = 1) {
        QPainterPath p = path();
        const double *a = pts.begin();
        p.moveTo(a[0] * w, a[1] * h);
        for (size_t i = 2; i + 1 < pts.size(); i += 2) p.lineTo(a[i] * w, a[i + 1] * h);
        if (close) p.closeSubpath();
        if (fill) d.fill(p); else d.stroke(p, sw);
    };
    auto rrect = [&](double x, double y, double rw, double rh, double r, double sw, const QTransform &t = {}) {
        QPainterPath p = path(); p.addRoundedRect(QRectF(x, y, rw, rh), r, r); d.stroke(t.map(p), sw);
    };
    // дуга как у Compose drawArc: градусы по часовой от «трёх часов», в прямоугольнике (x, y, rw, rh)
    auto arc = [&](double x, double y, double rw, double rh, double start, double sweep, double sw) {
        QPainterPath p = path();
        Pen2D::arc(p, 0, 0, rw / 2, start * PI / 180, (start + sweep) * PI / 180);
        QTransform t; t.translate(x + rw / 2, y + rh / 2); t.scale(1, rh / rw);
        d.stroke(t.map(p), sw);
    };

    if (name == QLatin1String("search")) {
        const double r = w * 0.32, cx = w * 0.42, cy = h * 0.42;
        d.circle(cx, cy, r, false, 1.8 * k);
        d.line(cx + r * 0.7071, cy + r * 0.7071, w * 0.88, h * 0.88, 1.8 * k);
    } else if (name == QLatin1String("refresh")) {
        const double r = w * 0.35, cx = w / 2, cy = h / 2;
        QPainterPath p = path(); Pen2D::arc(p, cx, cy, r, 20 * PI / 180, 270 * PI / 180); d.stroke(p, 1.7 * k);
        const double tx = cx + 2.5 * k, ty = cy - r, aw = 4 * k, ah = 3.2 * k;
        QPainterPath t = path(); t.moveTo(tx, ty); t.lineTo(tx - aw, ty - ah); t.lineTo(tx - aw * 0.7, ty); t.lineTo(tx - aw, ty + ah); t.closeSubpath(); d.fill(t);
    } else if (name == QLatin1String("funnel")) {
        poly({0.15, 0.22, 0.85, 0.22, 0.58, 0.56, 0.58, 0.82, 0.42, 0.74, 0.42, 0.56}, true, false, 1.6 * k);
    } else if (name == QLatin1String("dots")) {
        for (double y : {0.28, 0.5, 0.72}) d.circle(w / 2, h * y, 1.6 * k, true);
    } else if (name == QLatin1String("chevronLeft")) {
        poly({0.62, 0.22, 0.35, 0.5, 0.62, 0.78}, false, false, 1.8 * k);
    } else if (name == QLatin1String("chevronRight")) {
        poly({0.38, 0.22, 0.65, 0.5, 0.38, 0.78}, false, false, 1.8 * k);
    } else if (name == QLatin1String("chevronDown")) {
        poly({0.22, 0.38, 0.5, 0.65, 0.78, 0.38}, false, false, 1.8 * k);
    } else if (name == QLatin1String("play")) {
        poly({0.18, 0.08, 0.92, 0.5, 0.18, 0.92}, true, true);
    } else if (name == QLatin1String("lightning")) {
        poly({0.58, 0, 0.16, 0.54, 0.5, 0.54, 0.42, 1, 0.84, 0.44, 0.52, 0.44}, true, true);
    } else if (name == QLatin1String("link")) {
        QTransform t; t.translate(w / 2, h / 2); t.rotate(-45); t.translate(-w / 2, -h / 2);
        const double rw = w * 0.46, rh = h * 0.32;
        rrect(w * 0.10, (h - rh) / 2, rw, rh, rh / 2, 1.6 * k, t);
        rrect(w * 0.44, (h - rh) / 2, rw, rh, rh / 2, 1.6 * k, t);
    } else if (name == QLatin1String("close")) {
        const double p = w * 0.22;
        d.line(p, p, w - p, h - p, 1.8 * k); d.line(w - p, p, p, h - p, 1.8 * k);
    } else if (name == QLatin1String("info")) {
        d.circle(w / 2, h / 2, w * 0.42, false, 1.5 * k);
        d.circle(w / 2, h * 0.30, 1.1 * k, true);
        d.line(w / 2, h * 0.44, w / 2, h * 0.72, 1.5 * k);
    } else if (name == QLatin1String("heart")) {
        QPainterPath p = path(); p.moveTo(w * 0.5, h * 0.88);
        p.cubicTo(w * 0.08, h * 0.58, w * 0.06, h * 0.30, w * 0.26, h * 0.18);
        p.cubicTo(w * 0.40, h * 0.10, w * 0.50, h * 0.20, w * 0.5, h * 0.30);
        p.cubicTo(w * 0.50, h * 0.20, w * 0.60, h * 0.10, w * 0.74, h * 0.18);
        p.cubicTo(w * 0.94, h * 0.30, w * 0.92, h * 0.58, w * 0.5, h * 0.88);
        p.closeSubpath();
        if (filled) d.fill(p); else d.stroke(p, 1.6 * k);
    } else if (name == QLatin1String("bookmark")) {
        const double r = w * 0.10;
        QPainterPath p = path(); p.moveTo(w * 0.22, h * 0.88); p.lineTo(w * 0.22, h * 0.12 + r);
        p.quadTo(w * 0.22, h * 0.12, w * 0.22 + r, h * 0.12); p.lineTo(w * 0.78 - r, h * 0.12);
        p.quadTo(w * 0.78, h * 0.12, w * 0.78, h * 0.12 + r); p.lineTo(w * 0.78, h * 0.88); p.lineTo(w * 0.5, h * 0.68);
        p.closeSubpath();
        if (filled) d.fill(p); else d.stroke(p, 1.6 * k);
    } else if (name == QLatin1String("gear")) { // восемь зубцов и кольцо
        const double r = std::min(w, h) / 2, cx = w / 2, cy = h / 2;
        for (int i = 0; i < 8; ++i) {
            const double a = i * PI / 4;
            d.line(cx + std::cos(a) * r * 0.62, cy + std::sin(a) * r * 0.62, cx + std::cos(a) * r * 0.95, cy + std::sin(a) * r * 0.95, r * 0.28);
        }
        d.circle(cx, cy, r * 0.62, false, r * 0.22);
        if (filled) d.circle(cx, cy, r * 0.16, true);
    } else if (name == QLatin1String("people")) {
        d.circle(w * 0.36, w * 0.34, w * 0.17, false, 1.6 * k);
        arc(w * 0.06, w * 0.52, w * 0.60, w * 0.60, 200, 140, 1.6 * k);
        d.circle(w * 0.70, w * 0.30, w * 0.14, false, 1.6 * k);
        arc(w * 0.46, w * 0.48, w * 0.50, w * 0.50, 240, 100, 1.6 * k);
    } else if (name == QLatin1String("home")) {
        poly({0.5, 0.06, 0.96, 0.48, 0.84, 0.48, 0.84, 0.94, 0.62, 0.94, 0.62, 0.62, 0.38, 0.62, 0.38, 0.94, 0.16, 0.94, 0.16, 0.48, 0.04, 0.48}, true, true);
    } else if (name == QLatin1String("film")) {
        rrect(w * 0.1, w * 0.06, w * 0.8, w * 0.88, w * 0.14, 1.5 * k);
        d.cap = Qt::FlatCap;
        d.line(w * 0.32, w * 0.06, w * 0.32, w * 0.94, 1.5 * k);
        d.line(w * 0.68, w * 0.06, w * 0.68, w * 0.94, 1.5 * k);
        for (double y : {0.26, 0.5, 0.74}) { d.circle(w * 0.21, w * y, w * 0.045, true); d.circle(w * 0.79, w * y, w * 0.045, true); }
    } else if (name == QLatin1String("screen")) {
        rrect(w * 0.06, w * 0.12, w * 0.88, w * 0.6, w * 0.1, 1.6 * k);
        d.line(w * 0.5, w * 0.72, w * 0.5, w * 0.88, 1.6 * k);
        d.line(w * 0.3, w * 0.9, w * 0.7, w * 0.9, 1.6 * k);
    } else if (name == QLatin1String("anime") || name == QLatin1String("cartoon") || name == QLatin1String("other")) {
        const double st = 1.5 * k;
        if (name != QLatin1String("other")) { d.circle(w * 0.2, w * 0.2, w * 0.15, false, st); d.circle(w * 0.8, w * 0.2, w * 0.15, false, st); }
        d.circle(w * 0.5, w * 0.57, w * 0.38, false, st);
        d.circle(w * 0.37, w * 0.52, w * 0.045, true); d.circle(w * 0.63, w * 0.52, w * 0.045, true);
        if (name == QLatin1String("anime")) d.line(w * 0.4, w * 0.72, w * 0.6, w * 0.72, st);
        else arc(w * 0.34, w * 0.5, w * 0.32, w * 0.3, 20, 140, st);
    } else if (name == QLatin1String("grid")) {
        const double pad = w * 0.12, gap = w * 0.14, b = (w - pad * 2 - gap) / 2;
        for (double x : {pad, pad + b + gap})
            for (double y : {pad, pad + b + gap}) { QPainterPath p = path(); p.addRoundedRect(QRectF(x, y, b, b), 2 * k, 2 * k); d.fill(p); }
    } else if (name == QLatin1String("list")) {
        for (double y : {0.25, 0.5, 0.75}) d.line(w * 0.14, h * y, w * 0.86, h * y, 1.8 * k);
    } else if (name == QLatin1String("check")) {
        poly({0.2, 0.52, 0.42, 0.74, 0.82, 0.28}, false, false, 1.9 * k);
    }
}

void paintPlayer(Pen2D &d, const QString &name, double w, double h, int volume, bool muted) {
    auto path = Pen2D::path;
    // дуга как у Compose drawArc: градусы по часовой от «трёх часов»
    auto arc = [&](double cx, double cy, double r, double start, double sweep, double sw) {
        QPainterPath p = path();
        Pen2D::arc(p, cx, cy, r, start * PI / 180, (start + sweep) * PI / 180, sweep < 0);
        d.stroke(p, sw);
    };
    auto tri = [&](double x1, double y1, double x2, double y2, double x3, double y3, const QTransform &t = {}) {
        QPainterPath p = path(); p.moveTo(x1, y1); p.lineTo(x2, y2); p.lineTo(x3, y3); p.closeSubpath(); d.fill(t.map(p));
    };

    if (name == QLatin1String("audio")) { // нота
        const double hx = w * 0.38, hy = h * 0.70, rx = w * 0.22, ry = h * 0.16;
        QPainterPath head = path(); Pen2D::arc(head, 0, 0, rx, 0, 2 * PI);
        QTransform t; t.translate(hx, hy); t.rotate(-22); t.scale(1, ry / rx);
        d.fill(t.map(head));
        const double sx = hx + rx * 0.72, top = h * 0.18;
        d.line(sx, hy, sx, top, 1.8);
        QPainterPath f = path(); f.moveTo(sx, top);
        f.cubicTo(sx + w * 0.26, top + h * 0.08, sx + w * 0.32, top + h * 0.28, sx + w * 0.12, top + h * 0.44);
        f.cubicTo(sx + w * 0.24, top + h * 0.26, sx + w * 0.18, top + h * 0.14, sx, top + h * 0.10);
        f.closeSubpath(); d.fill(f);
    } else if (name == QLatin1String("subs")) { // рамка и «CC»
        const double px = w * 0.08, py = h * 0.18, rw = w - px * 2, rh = h - py * 2;
        QPainterPath p = path(); p.addRoundedRect(QRectF(px, py, rw, rh), 3, 3); d.stroke(p, 1.4);
        const double cr = rh * 0.21, cy = py + rh * 0.5, dd = 2 + 1.707 * cr, cx = px + rw * 0.5 + 0.3;
        arc(cx - dd / 2, cy, cr, 45, 270, 1.5);
        arc(cx + dd / 2, cy, cr, 45, 270, 1.5);
    } else if (name == QLatin1String("gear")) {
        const double cx = w / 2, cy = h / 2, ro = std::min(w, h) * 0.45, ri = std::min(w, h) * 0.31, rh = std::min(w, h) * 0.15;
        QPainterPath p = path();
        for (int i = 0; i < 8; i++) {
            const double b = i * 45;
            const double pts[4][2] = {{ro, b - 10}, {ro, b + 10}, {ri, b + 14.5}, {ri, b + 30.5}};
            for (int k = 0; k < 4; k++) {
                const double a = pts[k][1] * PI / 180, x = cx + pts[k][0] * std::cos(a), y = cy + pts[k][0] * std::sin(a);
                if (i == 0 && k == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
        }
        p.closeSubpath();
        p.moveTo(cx + rh, cy);
        p.arcTo(QRectF(cx - rh, cy - rh, 2 * rh, 2 * rh), 0, 360); // против часовой — дырка при правиле «ненулевой обмотки»
        d.fill(p);
    } else if (name == QLatin1String("volume")) {
        const double cy = h * 0.5;
        QPainterPath p = path(); p.moveTo(w * 0.08, cy - h * 0.18); p.lineTo(w * 0.30, cy - h * 0.18); p.lineTo(w * 0.48, cy - h * 0.38);
        p.lineTo(w * 0.48, cy + h * 0.38); p.lineTo(w * 0.30, cy + h * 0.18); p.lineTo(w * 0.08, cy + h * 0.18); p.closeSubpath(); d.fill(p);
        if (muted) d.line(w * 0.14, h * 0.16, w * 0.86, h * 0.84, 1.9);
        else if (volume > 0) {
            arc(w * 0.30, cy, w * 0.30, -42, 84, 1.8);
            if (volume > 40) arc(w * 0.30, cy, w * 0.48, -42, 84, 1.8);
        }
    } else if (name == QLatin1String("full") || name == QLatin1String("unfull")) {
        const double p = w * 0.18, x1 = p, x2 = w - p, y1 = p, y2 = h - p, a = w * 0.25, sw = 1.8;
        if (name == QLatin1String("full")) {
            d.line(x1, y1 + a, x1, y1, sw); d.line(x1, y1, x1 + a, y1, sw); d.line(x2 - a, y1, x2, y1, sw); d.line(x2, y1, x2, y1 + a, sw);
            d.line(x1, y2 - a, x1, y2, sw); d.line(x1, y2, x1 + a, y2, sw); d.line(x2 - a, y2, x2, y2, sw); d.line(x2, y2, x2, y2 - a, sw);
        } else {
            d.line(x1 + a, y1, x1 + a, y1 + a, sw); d.line(x1 + a, y1 + a, x1, y1 + a, sw); d.line(x2 - a, y1, x2 - a, y1 + a, sw); d.line(x2 - a, y1 + a, x2, y1 + a, sw);
            d.line(x1 + a, y2, x1 + a, y2 - a, sw); d.line(x1 + a, y2 - a, x1, y2 - a, sw); d.line(x2 - a, y2, x2 - a, y2 - a, sw); d.line(x2 - a, y2 - a, x2, y2 - a, sw);
        }
    } else if (name == QLatin1String("next") || name == QLatin1String("prev")) {
        if (name == QLatin1String("prev")) { d.g.translate(w, 0); d.g.scale(-1, 1); }
        const double cy = h * 0.5, hh = h * 0.28;
        tri(w * 0.16, cy - hh, w * 0.46, cy, w * 0.16, cy + hh);
        tri(w * 0.46, cy - hh, w * 0.76, cy, w * 0.46, cy + hh);
        d.line(w * 0.82, cy - hh, w * 0.82, cy + hh, 1.7);
    } else if (name == QLatin1String("seekBack") || name == QLatin1String("seekFwd")) { // круговая стрелка (цифры — текстом)
        const double cx = w / 2, cy = h / 2, r = w * 9.4 / 28, hl = w * 4.4 / 28, hh = w * 3.3 / 28;
        if (name == QLatin1String("seekBack")) {
            const double bx = cx + w * 0.8 / 28, tx = cx - hl, ty = cy - r;
            tri(tx, ty, bx, ty - hh, bx, ty + hh);
            arc(cx, cy, r, -86, 265, 1.9);
        } else {
            const double bx = cx - w * 0.8 / 28, tx = cx + hl, ty = cy - r;
            tri(tx, ty, bx, ty - hh, bx, ty + hh);
            arc(cx, cy, r, -94, -265, 1.9);
        }
    } else if (name == QLatin1String("play")) {
        tri(w * 0.18, h * 0.12, w * 0.88, h * 0.5, w * 0.18, h * 0.88);
    } else if (name == QLatin1String("cross")) {
        const double p = w * 0.14;
        d.line(p, p, w - p, h - p, 1.6); d.line(w - p, p, p, h - p, 1.6);
    } else if (name == QLatin1String("pin")) { // два окна: «поверх всех окон»
        QPainterPath a = path(); a.addRoundedRect(QRectF(w * 0.14, w * 0.30, w * 0.52, h * 0.52), 2, 2); d.stroke(a, 1.5);
        QPainterPath b = path(); b.addRoundedRect(QRectF(w * 0.36, w * 0.14, w * 0.50, h * 0.50), 2, 2); d.fill(b);
    } else if (name == QLatin1String("mini")) { // «⤢»: обычный режим из мини-плеера
        d.line(w * 0.2, h * 0.8, w * 0.8, h * 0.2, 1.7);
        d.line(w * 0.5, h * 0.2, w * 0.8, h * 0.2, 1.7); d.line(w * 0.8, h * 0.2, w * 0.8, h * 0.5, 1.7);
        d.line(w * 0.2, h * 0.5, w * 0.2, h * 0.8, 1.7); d.line(w * 0.2, h * 0.8, w * 0.5, h * 0.8, 1.7);
    } else if (name == QLatin1String("back")) { // «‹» в библиотеку
        QPainterPath p = path(); p.moveTo(w * 0.62, h * 0.2); p.lineTo(w * 0.36, h * 0.5); p.lineTo(w * 0.62, h * 0.8); d.stroke(p, 2.2);
    }
}
} // namespace

QImage IconProvider::requestImage(const QString &id, QSize *size, const QSize &requestedSize) {
    const QStringList a = id.split(QLatin1Char('/'));
    const QString set = a.value(0), name = a.value(1);
    const double logical = std::max(1.0, a.value(2).toDouble());
    const QColor color(a.value(3).isEmpty() ? QStringLiteral("white") : QLatin1Char('#') + a.value(3)); // AARRGGBB или RRGGBB
    const bool filled = a.value(4) == QLatin1String("1");
    const int volume = a.value(5).isEmpty() ? 100 : a.value(5).toInt();
    const bool muted = a.value(6) == QLatin1String("1");

    // картинка — под запрошенный размер в пикселях (с учётом масштаба экрана), рисунок — в логических единицах
    const int px = requestedSize.width() > 0 ? requestedSize.width() : int(std::ceil(logical));
    QImage img(px, px, QImage::Format_ARGB32_Premultiplied);
    img.fill(Qt::transparent);
    QPainter g(&img);
    g.setRenderHint(QPainter::Antialiasing);
    g.scale(px / logical, px / logical);
    Pen2D d(g, color);
    if (set == QLatin1String("player")) paintPlayer(d, name, logical, logical, volume, muted);
    else paintUi(d, name, logical, logical, filled);
    g.end();
    if (size) *size = img.size();
    return img;
}

} // namespace suslik::app
