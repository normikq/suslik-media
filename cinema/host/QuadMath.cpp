#include "QuadMath.h"

#include <QPolygonF>
#include <QTransform>

namespace suslik::cinema {

bool isConvex(const Quad &q) {
    int sign = 0;
    for (int i = 0; i < 4; ++i) {
        const QPointF a = q[i], b = q[(i + 1) % 4], c = q[(i + 2) % 4];
        const double z = (b.x() - a.x()) * (c.y() - b.y()) - (b.y() - a.y()) * (c.x() - b.x());
        if (qFuzzyIsNull(z)) return false;
        const int s = z > 0 ? 1 : -1;
        if (sign == 0) sign = s;
        else if (s != sign) return false;
    }
    return true;
}

bool rectToQuad(double w, double h, const Quad &quad, QMatrix4x4 &out) {
    if (w <= 0 || h <= 0 || !isConvex(quad)) return false;
    QPolygonF dst;
    for (const QPointF &p : quad) dst << p;
    QTransform square;
    if (!QTransform::squareToQuad(dst, square)) return false;
    // QTransform: сначала левая, потом правая. Кадр -> единичный квадрат -> четырёхугольник.
    const QTransform t = QTransform::fromScale(1.0 / w, 1.0 / h) * square;
    out = QMatrix4x4(t);
    return true;
}

QPointF mapPoint(const QMatrix4x4 &m, const QPointF &p) {
    const QVector4D v = m.map(QVector4D(float(p.x()), float(p.y()), 0.f, 1.f));
    if (qFuzzyIsNull(v.w())) return {};
    return {v.x() / v.w(), v.y() / v.w()};
}

} // namespace suslik::cinema
