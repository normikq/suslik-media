#include "GameView.h"

#include <algorithm>
#include <cmath>
#include <vector>

namespace suslik::cinema {

namespace {

constexpr double kPi = 3.14159265358979323846;

double wrapPi(double a) {
    while (a > kPi) a -= 2 * kPi;
    while (a < -kPi) a += 2 * kPi;
    return a;
}

/// Поворот v вокруг единичной оси k на угол a (Родриг).
V3 rotate(V3 v, V3 k, double a) {
    const double c = std::cos(a), s = std::sin(a);
    return v * c + cross(k, v) * s + k * (dot(k, v) * (1 - c));
}

} // namespace

V3 normalized(V3 a) {
    const double l = std::sqrt(dot(a, a));
    return l < 1e-12 ? a : a * (1 / l);
}

bool projectFrac(const CameraPose &cam, V3 p, QPointF &out, double near) {
    const V3 d = p - cam.pos;
    const double z = dot(d, cam.f);
    if (z < near) return false;
    const double nx = cam.kx * dot(d, cam.r) / z + cam.ox;
    const double ny = cam.ky * dot(d, cam.u) / z + cam.oy;
    out = {(nx + 1) / 2, (1 - ny) / 2};
    return true;
}

CameraPose advance(const CameraPose &cam, double dyaw, double dpitch, V3 dpos) {
    CameraPose c = cam;
    c.pos = cam.pos + dpos;
    const V3 up{0, 0, 1};
    if (dyaw != 0) {
        c.f = rotate(c.f, up, dyaw);
        c.r = rotate(c.r, up, dyaw);
        c.u = rotate(c.u, up, dyaw);
    }
    if (dpitch != 0) {
        // Ось «вправо» без крена: при наклоне головы поворот по тангажу всё равно идёт вокруг горизонтали
        V3 axis = normalized(cross(c.f, up));
        if (dot(axis, axis) < 0.5) axis = c.r;
        // Положительный тангаж — взгляд вверх: поворот вокруг «вправо» (f × up) на +dpitch ведёт f к up
        c.f = rotate(c.f, axis, dpitch);
        c.r = rotate(c.r, axis, dpitch);
        c.u = rotate(c.u, axis, dpitch);
    }
    return c;
}

void MotionPredictor::push(const CameraPose &s) {
    const double yaw = std::atan2(s.f.y, s.f.x);
    const double pitch = std::asin(std::clamp(s.f.z, -1.0, 1.0));
    if (m_valid) {
        const double dt = s.t - m_last.t;
        if (dt > 0.001 && dt < 0.1) {
            const double ry = wrapPi(yaw - m_yaw) / dt, rp = (pitch - m_pitch) / dt;
            const V3 v = (s.pos - m_last.pos) * (1 / dt);
            constexpr double a = 0.5;
            m_wy = a * m_wy + (1 - a) * ry;
            m_wp = a * m_wp + (1 - a) * rp;
            m_vel = m_vel * a + v * (1 - a);
            // Остановка: быстро гасим скорость, чтобы экран не проскакивал и не «возвращался»
            m_still = (std::abs(ry) < 0.01 && std::abs(rp) < 0.01) ? m_still + 1 : 0;
            if (m_still >= 1) {
                m_wy *= 0.4;
                m_wp *= 0.4;
            }
            if (dot(v, v) < 0.01 * 0.01) m_vel = m_vel * 0.4;
        } else if (dt >= 0.1 || dt < 0) {
            // Пропуск кадров (загрузка, пауза) или перезапуск мода: скорость неизвестна
            m_wy = m_wp = 0;
            m_vel = {};
            m_still = 0;
            m_hist.clear();
        }
        // dt ≈ 0 — тот же кадр игры: скорость не меняем
    }
    m_last = s;
    m_yaw = yaw;
    m_pitch = pitch;
    m_valid = true;
    if (m_hist.empty() || s.t > m_hist.back().pose.t) m_hist.push_back({s, yaw, pitch});
    while (m_hist.size() > 2 && m_hist.front().pose.t < s.t - 0.25) m_hist.pop_front();
}

CameraPose MotionPredictor::predict(double lead) const {
    if (!m_valid) return {};
    if (lead < 0 && m_hist.size() >= 2) {
        // В прошлое — между двумя кадрами игры: поворот и сдвиг от раннего кадра долей пути до позднего
        const double t = m_last.t + lead;
        if (t <= m_hist.front().pose.t) return m_hist.front().pose;
        for (size_t i = m_hist.size() - 1; i > 0; --i) {
            const Sample &a = m_hist[i - 1], &b = m_hist[i];
            if (t < a.pose.t) continue;
            const double k = (t - a.pose.t) / (b.pose.t - a.pose.t);
            CameraPose c = advance(a.pose, wrapPi(b.yaw - a.yaw) * k, (b.pitch - a.pitch) * k, (b.pose.pos - a.pose.pos) * k);
            c.t = t;
            return c;
        }
    }
    const double dy = std::clamp(m_wy * lead, -kMaxTurnRad, kMaxTurnRad);
    const double dp = std::clamp(m_wp * lead, -kMaxTurnRad, kMaxTurnRad);
    V3 dpos = m_vel * lead;
    const double l = std::sqrt(dot(dpos, dpos));
    if (l > kMaxMove) dpos = dpos * (kMaxMove / l);
    CameraPose c = advance(m_last, dy, dp, dpos);
    c.t = m_last.t + lead;
    return c;
}

double MotionPredictor::angularSpeedDeg() const {
    return std::sqrt(m_wy * m_wy + m_wp * m_wp) * 180 / kPi;
}

void ClockMap::sample(double hostSec, double gameT) {
    const double off = hostSec - gameT;
    // Первый кадр или перезапуск игры/мода (os.clock начался заново): смещение берём сразу
    if (!m_valid || std::abs(off - m_off) > 1.0) {
        m_off = off;
        m_win.clear();
        m_valid = true;
    }
    m_win.push_back({hostSec, off});
    while (m_win.size() > 1 && m_win.front().host < hostSec - kWindow) m_win.pop_front();
    double target = off;
    for (const Off &o : m_win) target = std::min(target, o.off);
    m_off += std::clamp(target - m_off, -kSlew, kSlew);
}

bool projectScreen(const CameraPose &cam, const std::array<V3, 4> &corners, V3 normal, Quad &out) {
    const V3 ctr = (corners[0] + corners[1] + corners[2] + corners[3]) * 0.25;
    if (dot(normal, cam.pos - ctr) <= 0) return false; // смотрим на заднюю сторону
    for (int i = 0; i < 4; ++i)
        if (!projectFrac(cam, corners[i], out[i])) return false;
    double x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
    for (const QPointF &p : out) {
        x0 = std::min(x0, p.x());
        x1 = std::max(x1, p.x());
        y0 = std::min(y0, p.y());
        y1 = std::max(y1, p.y());
    }
    return x1 > 0 && x0 < 1 && y1 > 0 && y0 < 1;
}

bool screenTransform(const CameraPose &cam, const std::array<V3, 4> &corners, V3 normal, double srcW, double srcH,
                     QTransform &out, QRectF &bounds, double near) {
    const V3 ctr = (corners[0] + corners[1] + corners[2] + corners[3]) * 0.25;
    if (dot(normal, cam.pos - ctr) <= 0 || srcW <= 0 || srcH <= 0) return false;

    // Видимая часть: многоугольник углов, обрезанный плоскостью z = near (Сазерленд — Ходжмен)
    std::vector<V3> poly; // в координатах камеры (x вправо, y вверх, z вперёд)
    for (const V3 &p : corners) {
        const V3 d = p - cam.pos;
        poly.push_back({dot(d, cam.r), dot(d, cam.u), dot(d, cam.f)});
    }
    std::vector<V3> clipped;
    for (size_t i = 0; i < poly.size(); ++i) {
        const V3 a = poly[i], b = poly[(i + 1) % poly.size()];
        const bool ina = a.z >= near, inb = b.z >= near;
        if (ina) clipped.push_back(a);
        if (ina != inb) {
            const double k = (near - a.z) / (b.z - a.z);
            clipped.push_back(a + (b - a) * k);
        }
    }
    if (clipped.size() < 3) return false;
    double x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
    for (const V3 &c : clipped) {
        const double fx = (cam.kx * c.x / c.z + cam.ox + 1) / 2, fy = (1 - cam.ky * c.y / c.z - cam.oy) / 2;
        x0 = std::min(x0, fx);
        x1 = std::max(x1, fx);
        y0 = std::min(y0, fy);
        y1 = std::max(y1, fy);
    }
    if (!(x1 > 0 && x0 < 1 && y1 > 0 && y0 < 1)) return false;
    bounds = QRectF(QPointF(x0, y0), QPointF(x1, y1));

    // Точка кадра (s, t) -> мир: ЛВ + s·(ПВ − ЛВ)/srcW + t·(ЛН − ЛВ)/srcH -> камера (линейно) -> доли окна
    const V3 ex = (corners[1] - corners[0]) * (1 / srcW), ey = (corners[3] - corners[0]) * (1 / srcH);
    const V3 d0 = corners[0] - cam.pos;
    struct Lin {
        double s, t, c;
    };
    const auto lin = [&](V3 axis) { return Lin{dot(ex, axis), dot(ey, axis), dot(d0, axis)}; };
    const Lin X = lin(cam.r), Y = lin(cam.u), Z = lin(cam.f);
    // Числители долей окна: fx·z = (kx·x + (ox + 1)·z) / 2, fy·z = ((1 − oy)·z − ky·y) / 2; знаменатель — z
    const auto nx = [&](double x, double z) { return (cam.kx * x + (cam.ox + 1) * z) / 2; };
    const auto ny = [&](double y, double z) { return ((1 - cam.oy) * z - cam.ky * y) / 2; };
    out = QTransform(nx(X.s, Z.s), ny(Y.s, Z.s), Z.s,
                     nx(X.t, Z.t), ny(Y.t, Z.t), Z.t,
                     nx(X.c, Z.c), ny(Y.c, Z.c), Z.c);
    return true;
}

} // namespace suslik::cinema
