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

SpatialMix spatialMix(const CameraPose &cam, V3 center, double width, double wall, Audio3D mode, bool muffle,
                      bool distance) {
    SpatialMix m;
    if (mode == Audio3D::Off) return m;
    const V3 d = center - cam.pos;
    const double dist = std::sqrt(dot(d, d));
    width = std::max(width, 0.1);

    // расстояние: полная громкость вблизи, дальше — как у TVKit (1 − x)^1.5
    const double ref = std::max(2.0, width);
    const double hear = std::max(20.0, 6 * width);
    if (distance && dist > ref) m.master = std::pow(std::clamp(1 - (dist - ref) / (hear - ref), 0.0, 1.0), 1.5);

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

namespace {
constexpr double kSpeakerSize = 0.3; ///< м: колонка — почти точка, направление размывается только вплотную
} // namespace

const char *speakerName(int ch) {
    static const char *const names[kSpeakers] = {"FL", "FR", "FC", "LFE", "BL", "BR", "SL", "SR"};
    return ch >= 0 && ch < kSpeakers ? names[ch] : "";
}

double speakerLevel(int ch) {
    switch (ch) {
    case FL: case FR: return 1;
    case LFE: return 0.5;
    default: return 0.70710678;
    }
}

bool speakerRoom(const SpeakerSet &set, V3 &centre, double &radius) {
    int n = 0;
    V3 sum;
    for (int ch = 0; ch < kSpeakers; ++ch)
        if (set.has[ch]) sum = sum + set.pos[ch], ++n;
    if (n == 0) return false;
    centre = sum * (1.0 / n);
    radius = 2;
    for (int ch = 0; ch < kSpeakers; ++ch)
        if (set.has[ch]) {
            const V3 d = set.pos[ch] - centre;
            radius = std::max(radius, std::sqrt(dot(d, d)));
        }
    return true;
}

bool speakerPlace(const SpeakerSet &set, int ch, V3 &pos, double &wall, double &size, bool &onScreen) {
    size = kSpeakerSize;
    onScreen = false;
    auto take = [&](int c) {
        if (!set.has[c]) return false;
        pos = set.pos[c];
        wall = set.wall[c];
        return true;
    };
    if (take(ch)) return true;
    switch (ch) {
    // раскладка 5.1: тылы фильма 7.1 (и 5.1 «back») — из боковых колонок, и наоборот
    case BL: if (take(SL)) return true; break;
    case BR: if (take(SR)) return true; break;
    case SL: if (take(BL)) return true; break;
    case SR: if (take(BR)) return true; break;
    case FC:
        if (set.has[FL] && set.has[FR]) {
            pos = (set.pos[FL] + set.pos[FR]) * 0.5;
            wall = std::min(set.wall[FL], set.wall[FR]);
            return true;
        }
        break;
    default: break;
    }
    if (!set.screen) return false;
    pos = set.center;
    wall = set.screenWall;
    size = set.width; // вблизи большого экрана звук вокруг
    onScreen = true;
    return true;
}

SpeakerMix speakerMix(const CameraPose *cam, const SpeakerSet &set, bool muffle) {
    SpeakerMix m;
    // Комната колонок: внутри неё (с запасом 25 %) — полная громкость, дальше спад до «слышно» (не меньше 20 м,
    // втрое больше радиуса). До 0.7.0 каждая колонка спадала сама по правилу телевизора (полная до 2 м, тишина к
    // 20 м) — в большом зале с колонками в 18–22 м звука почти не было.
    V3 centre;
    double radius = 2, room = 1;
    const bool hasRoom = cam && speakerRoom(set, centre, radius);
    if (hasRoom) {
        const V3 d = centre - cam->pos;
        const double dist = std::sqrt(dot(d, d));
        const double ref = radius * 1.25, hear = ref + std::max(20.0, 3 * radius);
        if (dist > ref) room = std::pow(std::clamp(1 - (dist - ref) / (hear - ref), 0.0, 1.0), 1.5);
    }
    for (int ch = 0; ch < kSpeakers; ++ch) {
        EarMix &e = m.ch[ch];
        const double level = speakerLevel(ch);
        e.gainL = e.gainR = level;
        V3 pos;
        double wall = 0, size = kSpeakerSize;
        bool onScreen = false;
        if (!cam || !speakerPlace(set, ch, pos, wall, size, onScreen)) continue; // без направления, как обычное сведение
        double gain = 1;
        if (!onScreen) {
            // ближняя колонка громче, дальняя тише — мягко (√), в пределах ×0,6…×1,4: у середины комнаты все ровно
            const V3 d = pos - cam->pos;
            gain = room * std::clamp(std::sqrt(radius / std::max(std::sqrt(dot(d, d)), 0.3)), 0.6, 1.4);
        }
        const SpatialMix s = spatialMix(*cam, pos, size, wall, Audio3D::Surround, muffle, onScreen);
        gain *= s.master;
        if (ch == LFE) { // низы не локализуются: только громкость и стена
            e.gainL = e.gainR = level * gain;
            continue;
        }
        e.gainL = level * gain * s.gainL;
        e.gainR = level * gain * s.gainR;
        e.lowpassL = s.lowpassL;
        e.lowpassR = s.lowpassR;
    }
    return m;
}

SpeakerMix approach(const SpeakerMix &cur, const SpeakerMix &t, double k) {
    SpeakerMix r;
    for (int ch = 0; ch < kSpeakers; ++ch) {
        SpatialMix a, b;
        a.gainL = cur.ch[ch].gainL, a.gainR = cur.ch[ch].gainR, a.lowpassL = cur.ch[ch].lowpassL, a.lowpassR = cur.ch[ch].lowpassR;
        b.gainL = t.ch[ch].gainL, b.gainR = t.ch[ch].gainR, b.lowpassL = t.ch[ch].lowpassL, b.lowpassR = t.ch[ch].lowpassR;
        const SpatialMix h = approach(a, b, k);
        r.ch[ch] = {h.gainL, h.gainR, h.lowpassL, h.lowpassR};
    }
    return r;
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
