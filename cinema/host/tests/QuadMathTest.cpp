#include "QuadMath.h"

#include <QtTest>
#include <QMatrix4x4>
#include <QPointF>

#include <array>
#include <cmath>

using suslik::cinema::Quad;
using suslik::cinema::rectToQuad;
using suslik::cinema::mapPoint;
using suslik::cinema::isConvex;

namespace {

/// Проверка близости двух точек с заданной точностью eps.
bool near(const QPointF &p1, const QPointF &p2, double eps = 1e-3)
{
    return std::hypot(p1.x() - p2.x(), p1.y() - p2.y()) <= eps;
}

/// Нахождение точки пересечения двух отрезков/прямых (p1-p2) и (p3-p4).
QPointF lineIntersection(const QPointF &p1, const QPointF &p2, const QPointF &p3, const QPointF &p4)
{
    const double d = (p1.x() - p2.x()) * (p3.y() - p4.y()) - (p1.y() - p2.y()) * (p3.x() - p4.x());
    Q_ASSERT(std::abs(d) > 1e-9);

    const double t1 = p1.x() * p2.y() - p1.y() * p2.x();
    const double t2 = p3.x() * p4.y() - p3.y() * p4.x();

    const double x = (t1 * (p3.x() - p4.x()) - (p1.x() - p2.x()) * t2) / d;
    const double y = (t1 * (p3.y() - p4.y()) - (p1.y() - p2.y()) * t2) / d;

    return QPointF(x, y);
}

} // namespace

class QuadMathTest : public QObject
{
    Q_OBJECT

private slots:
    /// Случай 1: выровненный по осям прямоугольник.
    void testAxisAlignedRect()
    {
        const double w = 1920.0;
        const double h = 1080.0;
        const Quad quad = {
            QPointF(100.0, 50.0),
            QPointF(2020.0, 50.0),
            QPointF(2020.0, 1130.0),
            QPointF(100.0, 1130.0)
        };

        QMatrix4x4 m;
        const bool ok = rectToQuad(w, h, quad, m);
        QVERIFY(ok);

        const std::array<QPointF, 4> srcCorners = {
            QPointF(0.0, 0.0),
            QPointF(w, 0.0),
            QPointF(w, h),
            QPointF(0.0, h)
        };

        for (size_t i = 0; i < 4; ++i) {
            const QPointF mapped = mapPoint(m, srcCorners[i]);
            QVERIFY2(near(mapped, quad[i], 1e-3),
                     qPrintable(QString("Corner %1 mismatch: mapped (%2, %3) vs expected (%4, %5)")
                                    .arg(i)
                                    .arg(mapped.x())
                                    .arg(mapped.y())
                                    .arg(quad[i].x())
                                    .arg(quad[i].y())));
        }
    }

    /// Случай 2: перспективная трапеция.
    void testPerspectiveTrapezoid()
    {
        const double w = 1920.0;
        const double h = 1080.0;
        const Quad quad = {
            QPointF(400.0, 200.0),
            QPointF(1500.0, 300.0),
            QPointF(1450.0, 900.0),
            QPointF(450.0, 1000.0)
        };

        QMatrix4x4 m;
        const bool ok = rectToQuad(w, h, quad, m);
        QVERIFY(ok);

        // Проверяем отображение углов
        const std::array<QPointF, 4> srcCorners = {
            QPointF(0.0, 0.0),
            QPointF(w, 0.0),
            QPointF(w, h),
            QPointF(0.0, h)
        };

        for (size_t i = 0; i < 4; ++i) {
            const QPointF mapped = mapPoint(m, srcCorners[i]);
            QVERIFY2(near(mapped, quad[i], 1e-3),
                     qPrintable(QString("Trapezoid corner %1 mismatch").arg(i)));
        }

        // Центр исходного прямоугольника (960, 540)
        const QPointF srcCenter(w / 2.0, h / 2.0);
        const QPointF mappedCenter = mapPoint(m, srcCenter);

        // Точка пересечения диагоналей четырёхугольника: (quad[0]-quad[2]) и (quad[1]-quad[3])
        const QPointF diagonalIntersection = lineIntersection(quad[0], quad[2], quad[1], quad[3]);
        QVERIFY2(near(mappedCenter, diagonalIntersection, 1e-3),
                 "Source center must map to intersection of diagonals");

        // Среднее арифметическое вершин четырёхугольника
        const QPointF averageCenter = (quad[0] + quad[1] + quad[2] + quad[3]) / 4.0;
        const double distToAverage = std::hypot(mappedCenter.x() - averageCenter.x(),
                                                mappedCenter.y() - averageCenter.y());
        QVERIFY2(distToAverage > 1.0, "Center under perspective must not equal average of corners");
    }

    /// Случай 3: самопересекающийся четырёхугольник (галстук-бабочка).
    void testBowTieQuad()
    {
        const double w = 1920.0;
        const double h = 1080.0;
        // Переставляем углы 2 и 3 местами, чтобы получить самопересечение
        const Quad bowTie = {
            QPointF(400.0, 200.0),
            QPointF(1500.0, 300.0),
            QPointF(450.0, 1000.0),
            QPointF(1450.0, 900.0)
        };

        QMatrix4x4 m;
        m.translate(5.0f, 7.0f, 0.0f);
        const QMatrix4x4 initialMatrix = m;

        const bool ok = rectToQuad(w, h, bowTie, m);
        QVERIFY(!ok);
        QCOMPARE(m, initialMatrix);
    }

    /// Случай 4: некорректные размеры (w<=0 или h<=0) и три коллинеарные точки.
    void testInvalidInputs()
    {
        const Quad validQuad = {
            QPointF(0.0, 0.0),
            QPointF(100.0, 0.0),
            QPointF(100.0, 100.0),
            QPointF(0.0, 100.0)
        };

        QMatrix4x4 m;
        m.translate(1.0f, 2.0f, 3.0f);
        const QMatrix4x4 backup = m;

        // Некорректная ширина
        QVERIFY(!rectToQuad(0.0, 100.0, validQuad, m));
        QCOMPARE(m, backup);

        QVERIFY(!rectToQuad(-10.0, 100.0, validQuad, m));
        QCOMPARE(m, backup);

        // Некорректная высота
        QVERIFY(!rectToQuad(100.0, 0.0, validQuad, m));
        QCOMPARE(m, backup);

        QVERIFY(!rectToQuad(100.0, -5.0, validQuad, m));
        QCOMPARE(m, backup);

        // Три коллинеарные вершины
        const Quad collinearQuad = {
            QPointF(0.0, 0.0),
            QPointF(50.0, 0.0),
            QPointF(100.0, 0.0),
            QPointF(0.0, 100.0)
        };
        QVERIFY(!rectToQuad(100.0, 100.0, collinearQuad, m));
        QCOMPARE(m, backup);
    }

    /// Случай 5: проверка функции isConvex для по и против часовой стрелки, а также для галстука-бабочки.
    void testIsConvex()
    {
        // Выпуклый по часовой стрелке
        const Quad cwQuad = {
            QPointF(0.0, 0.0),
            QPointF(100.0, 0.0),
            QPointF(100.0, 100.0),
            QPointF(0.0, 100.0)
        };
        QVERIFY(isConvex(cwQuad));

        // Выпуклый против часовой стрелки
        const Quad ccwQuad = {
            QPointF(0.0, 0.0),
            QPointF(0.0, 100.0),
            QPointF(100.0, 100.0),
            QPointF(100.0, 0.0)
        };
        QVERIFY(isConvex(ccwQuad));

        // Невыпуклый / самопересекающийся (галстук-бабочка)
        const Quad bowTieQuad = {
            QPointF(0.0, 0.0),
            QPointF(100.0, 0.0),
            QPointF(0.0, 100.0),
            QPointF(100.0, 100.0)
        };
        QVERIFY(!isConvex(bowTieQuad));
    }
};

QTEST_APPLESS_MAIN(QuadMathTest)

#include "QuadMathTest.moc"
