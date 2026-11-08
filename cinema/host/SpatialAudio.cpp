#include "SpatialAudio.h"

#include <algorithm>
#include <cmath>

namespace suslik::cinema {

namespace {
/// Частота между open и to по доле t (логарифмически): t = 0 — open, 1 — to.
double logLerp(double open, double to, double t) { return open * std::pow(to / open, std::clamp(t, 0.0, 1.0)); }

constexpr double kWallVolume = 0.40;  ///< за стеной — как WALL_VOLUME у TVKit
constexpr double kWallLowpass = 700;  ///< Гц — как «muffle» у TVKit
constexpr double kShadowLowpass = 4000; ///< дальнее ухо, экран сбоку
constexpr double kRearLowpass = 7000;   ///< экран за спиной
constexpr double kRearVolume = 0.80;
} // namespace

SpatialMix spatialMix(const CameraPose &cam, V3 center, double width, double wall, Audio3D mode, bool muffle) {
    SpatialMix m;
    if (mode == Audio3D::Off) return m;
    const V3 d = center - cam.pos;
    const double dist = std::sqrt(dot(d, d));
    width = std::max(width, 0.1);

    // расстояние: полная громкость вблизи, дальше — как у TVKit (1 − x)^1.5
    const double ref = std::max(2.0, width);
    const double hear = std::max(20.0, 6 * width);
    if (dist > ref) m.master = std::pow(std::clamp(1 - (dist - ref) / (hear - ref), 0.0, 1.0), 1.5);

    // направление в осях камеры: x — вправо, z — вперёд
    if (dist > 1e-3) {
        const V3 n = d * (1 / dist);
        const double x = dot(n, cam.r), z = dot(n, cam.f);
        const double flat = std::sqrt(x * x + z * z);
        double side = flat > 1e-6 ? x / flat : 0; // синус азимута: −1 слева … 1 справа
        // вблизи большого экрана он вокруг — направление размывается
        side *= std::clamp(dist / (0.5 * width + 0.5), 0.0, 1.0);
        const double a = std::abs(side);
        const bool surround = mode == Audio3D::Surround;
        const double far = 1 - (surround ? 0.6 : 0.8) * a; // дальнее ухо тише
        (side > 0 ? m.gainL : m.gainR) = far;
        if (surround) {
            (side > 0 ? m.lowpassL : m.lowpassR) = logLerp(SpatialMix::kOpen, kShadowLowpass, a);
            const double rear = std::clamp(-z, 0.0, 1.0) * std::clamp(dist / (0.5 * width + 0.5), 0.0, 1.0);
            const double rearLp = logLerp(SpatialMix::kOpen, kRearLowpass, rear);
            m.lowpassL = std::min(m.lowpassL, rearLp);
            m.lowpassR = std::min(m.lowpassR, rearLp);
            m.master *= 1 - (1 - kRearVolume) * rear;
        }
    }

    // за стеной
    if (muffle && wall > 0) {
        const double w = std::clamp(wall, 0.0, 1.0);
        m.master *= 1 - (1 - kWallVolume) * w;
        const double lp = logLerp(SpatialMix::kOpen, kWallLowpass, w);
        m.lowpassL = std::min(m.lowpassL, lp);
        m.lowpassR = std::min(m.lowpassR, lp);
    }
    return m;
}

SpatialMix approach(const SpatialMix &cur, const SpatialMix &t, double k) {
    k = std::clamp(k, 0.0, 1.0);
    auto lin = [k](double a, double b) { return a + (b - a) * k; };
    auto lg = [k](double a, double b) { return std::exp(std::log(a) + (std::log(b) - std::log(a)) * k); };
    SpatialMix r;
    r.gainL = lin(cur.gainL, t.gainL);
    r.gainR = lin(cur.gainR, t.gainR);
    r.master = lin(cur.master, t.master);
    r.lowpassL = lg(cur.lowpassL, t.lowpassL);
    r.lowpassR = lg(cur.lowpassR, t.lowpassR);
    return r;
}

} // namespace suslik::cinema
