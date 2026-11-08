#pragma once
#include "QuadMath.h"

#include <QPointF>
#include <QRectF>
#include <QTransform>

#include <deque>

/// Камера игры на стороне хоста: своя проекция с фокусом игры (kx, ky из мода) и упреждение поворота
/// до момента показа кадра оверлея (docs/CYBERPUNK.md, этап 2). Без окна и Windows — проверяется тестом.
namespace suslik::cinema {

struct V3 {
    double x = 0, y = 0, z = 0;
};
inline V3 operator+(V3 a, V3 b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
inline V3 operator-(V3 a, V3 b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
inline V3 operator*(V3 a, double k) { return {a.x * k, a.y * k, a.z * k}; }
inline double dot(V3 a, V3 b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
inline V3 cross(V3 a, V3 b) { return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x}; }
V3 normalized(V3 a);

/// Камера из state.json мода: позиция глаз, оси (вперёд, вправо, вверх; Z мира — вверх) и «линза» игры:
/// ndc = k·(x/z) + o по каждой оси (подогнано модом под CameraSystem:ProjectPoint).
struct CameraPose {
    double t = 0; ///< os.clock() игры, с
    V3 pos, f, r, u;
    double kx = 1, ky = 1, ox = 0, oy = 0;
};

/// Точка мира -> доли окна игры (0…1, Y вниз). false — точка ближе near к плоскости камеры или за ней.
bool projectFrac(const CameraPose &cam, V3 p, QPointF &out, double near = 0.05);

/// Камера, повёрнутая на dyaw вокруг вертикали мира и на dpitch вокруг своей оси «вправо» (крен сохраняется),
/// и сдвинутая на dpos.
CameraPose advance(const CameraPose &cam, double dyaw, double dpitch, V3 dpos);

/// Сглаженные угловая и линейная скорости камеры по кадрам игры и прогноз позы на lead секунд вперёд
/// (как predictPose в разведке): EMA 0,5, при остановке скорость быстро гаснет, прогноз ограничен.
class MotionPredictor {
public:
    void push(const CameraPose &s);
    /// Поза на lead с от последнего кадра игры. lead > 0 — прогноз по скорости; lead < 0 — интерполяция между
    /// кадрами игры из истории (как у генерации кадров DLSS: на мониторе картинка игры старше последней камеры).
    CameraPose predict(double lead) const;
    /// Сглаженная скорость поворота, °/с.
    double angularSpeedDeg() const;
    bool valid() const { return m_valid; }
    void reset() { *this = MotionPredictor(); }

    static constexpr double kMaxTurnRad = 0.14; ///< ~8° за прогноз
    static constexpr double kMaxMove = 0.25;    ///< м за прогноз

private:
    struct Sample {
        CameraPose pose;
        double yaw, pitch;
    };
    std::deque<Sample> m_hist; ///< последние кадры игры (до 0,25 с)
    CameraPose m_last;
    bool m_valid = false;
    double m_yaw = 0, m_pitch = 0;
    double m_wy = 0, m_wp = 0; ///< рад/с
    V3 m_vel;                  ///< м/с
    int m_still = 0;
};

/// Часы игры (os.clock) -> часы хоста (QPC, с). Смещение — минимум (время получения − t) за последние 2 с,
/// к которому текущее значение подтягивается не быстрее kSlew за кадр игры: скачок смещения сдвинул бы момент,
/// для которого считается камера, и на быстром повороте экран дёрнулся бы.
class ClockMap {
public:
    void sample(double hostSec, double gameT);
    double toHost(double gameT) const { return gameT + m_off; }
    bool valid() const { return m_valid; }

    static constexpr double kWindow = 2.0;
    static constexpr double kSlew = 0.0002;

private:
    struct Off {
        double host, off;
    };
    std::deque<Off> m_win;
    double m_off = 0;
    bool m_valid = false;
};

/// Четыре угла экрана (мир, ЛВ, ПВ, ПН, ЛН) -> доли окна. false — экран за камерой, повёрнут спиной
/// (normal — к зрителю) или целиком вне кадра.
bool projectScreen(const CameraPose &cam, const std::array<V3, 4> &corners, V3 normal, Quad &out);

/// Проекция экрана прямо из камеры: кадр (0,0)-(srcW,srcH) -> доли окна, однородная (w = глубина).
/// Работает, когда часть экрана за камерой (большой экран вблизи): отрезает видеокарта по w > 0.
/// bounds — рамка видимой части в долях окна (многоугольник, обрезанный плоскостью near).
/// false — экран повёрнут спиной, целиком за камерой или вне кадра.
bool screenTransform(const CameraPose &cam, const std::array<V3, 4> &corners, V3 normal, double srcW, double srcH,
                     QTransform &out, QRectF &bounds, double near = 0.05);

} // namespace suslik::cinema
