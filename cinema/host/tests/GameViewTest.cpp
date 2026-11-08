#include "GameView.h"
#include "QuadMath.h"

#include <QtTest>
#include <QPointF>

#include <array>
#include <cmath>

using suslik::cinema::V3;
using suslik::cinema::CameraPose;
using suslik::cinema::projectFrac;
using suslik::cinema::advance;
using suslik::cinema::MotionPredictor;
using suslik::cinema::ClockMap;
using suslik::cinema::projectScreen;
using suslik::cinema::Quad;
using suslik::cinema::isConvex;
using suslik::cinema::dot;
using suslik::cinema::cross;
using suslik::cinema::normalized;

namespace {

constexpr double kPi = 3.14159265358979323846;

/// Вспомогательная функция расчёта базиса камеры: right = normalize(cross(f, up)), u = cross(r, f), Z мира вверх.
CameraPose makeCamera(V3 f, V3 pos = {0.0, 0.0, 0.0}, double t = 0.0)
{
    const V3 up{0.0, 0.0, 1.0};
    const V3 r = normalized(cross(f, up));
    const V3 u = cross(r, f);
    CameraPose cam;
    cam.t = t;
    cam.pos = pos;
    cam.f = f;
    cam.r = r;
    cam.u = u;
    cam.kx = 1.0;
    cam.ky = 1.0;
    cam.ox = 0.0;
    cam.oy = 0.0;
    return cam;
}

} // namespace

class GameViewTest : public QObject
{
    Q_OBJECT

private slots:
    /// Случай 1: проецирование точки мира в нормализованные доли окна (projectFrac).
    void testProjectFrac()
    {
        CameraPose cam;
        cam.pos = {0.0, 0.0, 0.0};
        cam.f = {1.0, 0.0, 0.0};
        cam.r = {0.0, -1.0, 0.0};
        cam.u = {0.0, 0.0, 1.0};
        cam.kx = 1.0;
        cam.ky = 1.0;
        cam.ox = 0.0;
        cam.oy = 0.0;

        QPointF pt;

        // Точка (2,0,0) -> (0.5, 0.5)
        QVERIFY(projectFrac(cam, {2.0, 0.0, 0.0}, pt));
        QVERIFY(std::abs(pt.x() - 0.5) < 1e-9);
        QVERIFY(std::abs(pt.y() - 0.5) < 1e-9);

        // Точка (2,-1,0) -> x = 0.75
        QVERIFY(projectFrac(cam, {2.0, -1.0, 0.0}, pt));
        QVERIFY(std::abs(pt.x() - 0.75) < 1e-9);

        // Точка (2,0,1) -> y = 0.25
        QVERIFY(projectFrac(cam, {2.0, 0.0, 1.0}, pt));
        QVERIFY(std::abs(pt.y() - 0.25) < 1e-9);

        // Точка (-1,0,0) -> позади камеры, возвращает false
        QVERIFY(!projectFrac(cam, {-1.0, 0.0, 0.0}, pt));

        // При ox = 0.1 координата x смещается на +0.05
        cam.ox = 0.1;
        QVERIFY(projectFrac(cam, {2.0, 0.0, 0.0}, pt));
        QVERIFY(std::abs(pt.x() - 0.55) < 1e-9);
    }

    /// Случай 2: поворот и перемещение камеры (advance), сохранение ортонормированности базиса.
    void testAdvance()
    {
        CameraPose cam;
        cam.pos = {0.0, 0.0, 0.0};
        cam.f = {1.0, 0.0, 0.0};
        cam.r = {0.0, -1.0, 0.0};
        cam.u = {0.0, 0.0, 1.0};

        // dyaw = pi/2 поворачивает f из +X в +Y
        const CameraPose cYaw = advance(cam, kPi / 2.0, 0.0, {0.0, 0.0, 0.0});
        QVERIFY(std::abs(cYaw.f.x - 0.0) < 1e-9);
        QVERIFY(std::abs(cYaw.f.y - 1.0) < 1e-9);
        QVERIFY(std::abs(cYaw.f.z - 0.0) < 1e-9);

        // dpitch = +0.3 делает f.z == sin(0.3)
        const CameraPose cPitch = advance(cam, 0.0, 0.3, {0.0, 0.0, 0.0});
        QVERIFY(std::abs(cPitch.f.z - std::sin(0.3)) < 1e-9);

        // Базис остаётся ортонормированным, dpos добавляется к pos
        const V3 dpos{2.0, -3.0, 1.5};
        const CameraPose cBoth = advance(cam, 0.5, -0.4, dpos);

        QVERIFY(std::abs(cBoth.pos.x - (cam.pos.x + dpos.x)) < 1e-9);
        QVERIFY(std::abs(cBoth.pos.y - (cam.pos.y + dpos.y)) < 1e-9);
        QVERIFY(std::abs(cBoth.pos.z - (cam.pos.z + dpos.z)) < 1e-9);

        // Проверка ортонормированности: скалярные произведения ~0, длины векторов ~1
        QVERIFY(std::abs(dot(cBoth.f, cBoth.r)) < 1e-9);
        QVERIFY(std::abs(dot(cBoth.f, cBoth.u)) < 1e-9);
        QVERIFY(std::abs(dot(cBoth.r, cBoth.u)) < 1e-9);
        QVERIFY(std::abs(dot(cBoth.f, cBoth.f) - 1.0) < 1e-9);
        QVERIFY(std::abs(dot(cBoth.r, cBoth.r) - 1.0) < 1e-9);
        QVERIFY(std::abs(dot(cBoth.u, cBoth.u) - 1.0) < 1e-9);
    }

    /// Случай 3: сглаживание и прогноз движения камеры (MotionPredictor), ограничение поворота и разрыв времени.
    void testMotionPredictor()
    {
        MotionPredictor predictor;

        // Отправляем 10 поз с шагом t = i * 0.016 при скорости рыскания 1 рад/с
        double lastYaw = 0.0;
        for (int i = 0; i < 10; ++i) {
            const double t = i * 0.016;
            const double yaw = i * 0.016;
            lastYaw = yaw;
            const V3 f{std::cos(yaw), std::sin(yaw), 0.0};
            predictor.push(makeCamera(f, {0.0, 0.0, 0.0}, t));
        }

        // Прогноз на 0.016 с: угол рыскания ≈ last yaw + 0.016 в пределах 0.002
        const CameraPose pred = predictor.predict(0.016);
        const double predYaw = std::atan2(pred.f.y, pred.f.x);
        QVERIFY(std::abs(predYaw - (lastYaw + 0.016)) <= 0.002);

        // Сглаженная угловая скорость angularSpeedDeg() ≈ 57.3°/с в пределах 3
        QVERIFY(std::abs(predictor.angularSpeedDeg() - 57.3) <= 3.0);

        // Отправляем ещё 3 кадра с неизменным углом (остановка)
        for (int i = 10; i < 13; ++i) {
            const double t = i * 0.016;
            const V3 f{std::cos(lastYaw), std::sin(lastYaw), 0.0};
            predictor.push(makeCamera(f, {0.0, 0.0, 0.0}, t));
        }

        // Прогноз на 0.016 с остаётся в пределах 0.002 от угла остановки
        const CameraPose predStopped = predictor.predict(0.016);
        const double stoppedYaw = std::atan2(predStopped.f.y, predStopped.f.x);
        QVERIFY(std::abs(stoppedYaw - lastYaw) <= 0.002);

        // Ограничение (clamp): новый предиктор со скоростью 10 рад/с
        MotionPredictor clampPredictor;
        clampPredictor.push(makeCamera({1.0, 0.0, 0.0}, {0.0, 0.0, 0.0}, 0.0));
        const double fastYaw = 10.0 * 0.016;
        const V3 fastF{std::cos(fastYaw), std::sin(fastYaw), 0.0};
        clampPredictor.push(makeCamera(fastF, {0.0, 0.0, 0.0}, 0.016));

        // predict(1.0): изменение угла рыскания <= kMaxTurnRad + 1e-9
        const CameraPose clampedPred = clampPredictor.predict(1.0);
        const double clampedYaw = std::atan2(clampedPred.f.y, clampedPred.f.x);
        const double yawChange = std::abs(clampedYaw - fastYaw);
        QVERIFY(yawChange <= MotionPredictor::kMaxTurnRad + 1e-9);

        // Разрыв времени (gap): после серии кадров с постоянной скоростью отправляем кадр с dt = 0.5 с
        MotionPredictor gapPredictor;
        for (int i = 0; i < 10; ++i) {
            const double t = i * 0.016;
            const double yaw = i * 0.016;
            const V3 f{std::cos(yaw), std::sin(yaw), 0.0};
            gapPredictor.push(makeCamera(f, {0.0, 0.0, 0.0}, t));
        }

        const double gapT = 9 * 0.016 + 0.5;
        const double gapYaw = 9 * 0.016 + 0.2;
        const V3 gapF{std::cos(gapYaw), std::sin(gapYaw), 0.0};
        gapPredictor.push(makeCamera(gapF, {0.0, 0.0, 0.0}, gapT));

        // Скорость сброшена, вектор f совпадает с последним отправленным с точностью до 1e-9
        const CameraPose gapPred = gapPredictor.predict(0.016);
        QVERIFY(std::abs(gapPred.f.x - gapF.x) <= 1e-9);
        QVERIFY(std::abs(gapPred.f.y - gapF.y) <= 1e-9);
        QVERIFY(std::abs(gapPred.f.z - gapF.z) <= 1e-9);
    }

    /// Отрицательное упреждение: поза между кадрами игры берётся интерполяцией из истории, а не по скорости.
    void testPredictorPast()
    {
        MotionPredictor predictor;
        double lastYaw = 0.0;
        // Неравномерный поворот: скорость растёт, прогноз по сглаженной скорости тут ошибся бы
        for (int i = 0; i < 10; ++i) {
            lastYaw = 0.01 * i * i;
            predictor.push(makeCamera({std::cos(lastYaw), std::sin(lastYaw), 0.0}, {0.1 * i, 0.0, 0.0}, i * 0.016));
        }
        // Середина между кадрами 8 и 9
        const CameraPose mid = predictor.predict(-0.008);
        const double expected = (0.01 * 64 + 0.01 * 81) / 2;
        QVERIFY(std::abs(std::atan2(mid.f.y, mid.f.x) - expected) < 1e-9);
        QVERIFY(std::abs(mid.pos.x - 0.85) < 1e-9);
        // Ровно кадр 7
        const CameraPose k7 = predictor.predict(-0.032);
        QVERIFY(std::abs(std::atan2(k7.f.y, k7.f.x) - 0.01 * 49) < 1e-9);
    }

    /// Проекция из камеры (screenTransform): совпадает с проекцией углов и работает, когда часть экрана за камерой.
    void testScreenTransform()
    {
        using suslik::cinema::screenTransform;
        const double W = 1920, H = 1080;
        // Экран 1.6x0.9 в плоскости x=3 лицом к камере, камера смотрит в +X с линзой игры
        CameraPose cam = makeCamera({1.0, 0.0, 0.0});
        cam.kx = 0.83;
        cam.ky = 1.47;
        cam.ox = 0.01;
        const std::array<V3, 4> corners = {V3{3, 0.8, 0.45}, V3{3, -0.8, 0.45}, V3{3, -0.8, -0.45}, V3{3, 0.8, -0.45}};
        QTransform t;
        QRectF b;
        QVERIFY(screenTransform(cam, corners, {-1, 0, 0}, W, H, t, b));
        const QPointF src[4] = {{0, 0}, {W, 0}, {W, H}, {0, H}};
        for (int i = 0; i < 4; ++i) {
            QPointF want;
            QVERIFY(projectFrac(cam, corners[i], want));
            const QPointF got = t.map(src[i]);
            QVERIFY(std::abs(got.x() - want.x()) < 1e-9 && std::abs(got.y() - want.y()) < 1e-9);
        }

        // Огромный экран 20 м, камера в 1 м от плоскости у левого края: левые углы за спиной, середина видна
        CameraPose near = makeCamera(normalized({1.0, -1.0, 0.0}), {2.0, 9.0, 0.0});
        near.kx = 0.83;
        near.ky = 1.47;
        const std::array<V3, 4> big = {V3{3, 10, 5.6}, V3{3, -10, 5.6}, V3{3, -10, -5.6}, V3{3, 10, -5.6}};
        QVERIFY(screenTransform(near, big, {-1, 0, 0}, W, H, t, b));
        // Точка экрана перед камерой (середина кадра по высоте, 60 % ширины) — та же, что у projectFrac
        const V3 p{3, 10 - 0.6 * 20, 0};
        QPointF want;
        QVERIFY(projectFrac(near, p, want));
        const QPointF got = t.map(QPointF(0.6 * W, 0.5 * H));
        QVERIFY(std::abs(got.x() - want.x()) < 1e-9 && std::abs(got.y() - want.y()) < 1e-9);
        // По углам старая проверка экран бы спрятала
        Quad q;
        QVERIFY(!projectScreen(near, big, {-1, 0, 0}, q));

        // Спиной к камере — не видно
        QVERIFY(!screenTransform(cam, corners, {1, 0, 0}, W, H, t, b));
    }

    /// Случай 4: синхронизация часов игры и хоста (ClockMap).
    void testClockMap()
    {
        ClockMap clockMap;

        // sample(10.0, 1.0) -> toHost(1.0) == 10.0 (проверка abs < 1e-9)
        clockMap.sample(10.0, 1.0);
        QVERIFY(std::abs(clockMap.toHost(1.0) - 10.0) < 1e-9);

        // Задержка больше: минимум за окно прежний, смещение не меняется
        clockMap.sample(10.020, 1.016);
        QVERIFY(std::abs(clockMap.toHost(1.016) - 10.016) < 1e-9);

        // Задержка меньше на 0,5 мс: смещение подтягивается не больше чем на kSlew за кадр
        clockMap.sample(10.030, 1.0305);
        QVERIFY(std::abs(clockMap.toHost(0.0) - (9.0 - ClockMap::kSlew)) < 1e-9);
        clockMap.sample(10.046, 1.0465);
        clockMap.sample(10.062, 1.0625);
        QVERIFY(std::abs(clockMap.toHost(0.0) - 8.9995) < 1e-9);

        // Старый минимум уходит из окна (2 с): смещение плавно растёт к новому минимуму
        for (int i = 1; i <= 200; ++i) clockMap.sample(10.062 + 0.016 * i, 1.0625 + 0.016 * i - 0.001);
        QVERIFY(std::abs(clockMap.toHost(0.0) - 9.0005) < 1e-9);

        // sample(20.0, 0.5) -> скачок смещения больше 1 с приводит к сбросу, toHost(0.5) ≈ 20.0
        clockMap.sample(20.0, 0.5);
        QVERIFY(std::abs(clockMap.toHost(0.5) - 20.0) < 1e-9);
    }

    /// Случай 5: проецирование четырёхугольника экрана в окно (projectScreen).
    void testProjectScreen()
    {
        CameraPose cam;
        cam.pos = {0.0, 0.0, 0.0};
        cam.f = {1.0, 0.0, 0.0};
        cam.r = {0.0, -1.0, 0.0};
        cam.u = {0.0, 0.0, 1.0};
        cam.kx = 1.0;
        cam.ky = 1.0;
        cam.ox = 0.0;
        cam.oy = 0.0;

        // Экран 1.6x0.9 м с центром в (3,0,0) в плоскости x=3, обращён к камере (нормаль (-1,0,0))
        // Для камеры, смотрящей в +X, вектор вправо равен -Y:
        // TL=(3, 0.8, 0.45), TR=(3, -0.8, 0.45), BR=(3, -0.8, -0.45), BL=(3, 0.8, -0.45)
        const std::array<V3, 4> corners = {
            V3{3.0, 0.8, 0.45},   // TL
            V3{3.0, -0.8, 0.45},  // TR
            V3{3.0, -0.8, -0.45}, // BR
            V3{3.0, 0.8, -0.45}   // BL
        };
        const V3 normal{-1.0, 0.0, 0.0};

        Quad q;

        // Экран виден в камере: возвращает true
        QVERIFY(projectScreen(cam, corners, normal, q));

        // Четырёхугольник симметричен относительно центра (0.5, 0.5)
        QVERIFY(std::abs((q[0].x() + q[1].x()) - 1.0) < 1e-6);
        QVERIFY(std::abs((q[0].y() + q[3].y()) - 1.0) < 1e-6);

        // Четырёхугольник выпуклый (isConvex из QuadMath.h)
        QVERIFY(isConvex(q));

        // Экран повёрнут спиной: normal (+1,0,0) -> возвращает false
        QVERIFY(!projectScreen(cam, corners, {1.0, 0.0, 0.0}, q));

        // Камера смотрит в направлении -X (f=(-1,0,0), r=(0,1,0), u=(0,0,1)) -> возвращает false
        CameraPose camBack = cam;
        camBack.f = {-1.0, 0.0, 0.0};
        camBack.r = {0.0, 1.0, 0.0};
        camBack.u = {0.0, 0.0, 1.0};
        QVERIFY(!projectScreen(camBack, corners, normal, q));

        // Экран смещён далеко в сторону (центр (3,30,0)) -> возвращает false
        const std::array<V3, 4> farCorners = {
            V3{3.0, 30.8, 0.45},
            V3{3.0, 29.2, 0.45},
            V3{3.0, 29.2, -0.45},
            V3{3.0, 30.8, -0.45}
        };
        QVERIFY(!projectScreen(cam, farCorners, normal, q));
    }
};

QTEST_APPLESS_MAIN(GameViewTest)

#include "GameViewTest.moc"
