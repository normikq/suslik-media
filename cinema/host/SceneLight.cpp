#include "SceneLight.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

namespace suslik::cinema {

namespace {

double toLin(double g) { return std::pow(std::clamp(g, 0.0, 1.0), 2.2); }
double toGam(double l) { return std::pow(std::clamp(l, 0.0, 1.0), 1.0 / 2.2); }

const std::array<float, 256> &linTable() {
    static const std::array<float, 256> t = [] {
        std::array<float, 256> a{};
        for (int i = 0; i < 256; ++i) a[i] = float(toLin(i / 255.0));
        return a;
    }();
    return t;
}

double luma(const QVector3D &c) { return 0.2126 * c.x() + 0.7152 * c.y() + 0.0722 * c.z(); }

double smoothstep(double a, double b, double x) {
    const double t = std::clamp((x - a) / (b - a), 0.0, 1.0);
    return t * t * (3 - 2 * t);
}

} // namespace

SceneSample measureScene(const QImage &img, const QRect &exclude) {
    if (img.isNull()) return {};
    const QImage rgb = img.format() == QImage::Format_RGB32 || img.format() == QImage::Format_ARGB32
        ? img : img.convertToFormat(QImage::Format_RGB32);
    const auto &lut = linTable();
    struct Px { float r, g, b, y; };
    std::vector<Px> px;
    px.reserve(size_t(rgb.width()) * rgb.height());
    for (int y = 0; y < rgb.height(); ++y) {
        const auto *line = reinterpret_cast<const QRgb *>(rgb.constScanLine(y));
        for (int x = 0; x < rgb.width(); ++x) {
            if (exclude.contains(x, y)) continue;
            const QRgb c = line[x];
            const float r = lut[qRed(c)], g = lut[qGreen(c)], b = lut[qBlue(c)];
            px.push_back({r, g, b, 0.2126f * r + 0.7152f * g + 0.0722f * b});
        }
    }
    if (px.size() < 16) return {};
    // самые яркие 10 % (HUD, лампы, неон в кадре) — не свет зала
    const size_t keep = std::max<size_t>(1, px.size() * 9 / 10);
    std::nth_element(px.begin(), px.begin() + (keep - 1), px.end(), [](const Px &a, const Px &b) { return a.y < b.y; });
    double r = 0, g = 0, b = 0;
    for (size_t i = 0; i < keep; ++i) { r += px[i].r; g += px[i].g; b += px[i].b; }
    SceneSample s;
    s.lin = QVector3D(float(r / keep), float(g / keep), float(b / keep));
    s.luma = luma(s.lin);
    s.pixels = int(keep);
    return s;
}

SceneSample smoothScene(const SceneSample &prev, const SceneSample &next, double dt, double tau) {
    if (!next.valid()) return prev;
    if (!prev.valid() || tau <= 0) return next;
    const float k = float(1.0 - std::exp(-std::max(dt, 0.0) / tau));
    SceneSample s;
    s.lin = prev.lin + (next.lin - prev.lin) * k;
    s.luma = luma(s.lin);
    s.pixels = next.pixels;
    return s;
}

SceneLook sceneLookFor(const SceneSample &in) {
    const SceneSample s = in.valid() ? in : defaultScene();
    // 0 — тёмный зал (средний свет около 0,4 % белого), 1 — светлая сцена (18 %, «средне-серый» день)
    const double t = smoothstep(0.004, 0.18, s.luma);
    SceneLook look;
    look.dim = 0.84 + 0.16 * t;
    // свет зала подмешивается к кадру и разбавляет его цвета: немного (08.11 — «цвета глушит»)
    look.liftAmount = 0.05 + 0.07 * t;
    look.glow = 0.26 - 0.18 * t;
    // полотно белое и отражает больше, чем стены вокруг: свет зала на нём втрое ярче среднего
    look.lift = QColor::fromRgbF(float(toGam(s.lin.x() * 3)), float(toGam(s.lin.y() * 3)), float(toGam(s.lin.z() * 3)));
    return look;
}

const QList<SceneKnob> &sceneKnobs() {
    static const QList<SceneKnob> list = {
        {"exposure", "Яркость", 0.6, 1.4, 1.0, "USER_EXPOSURE"},
        {"satur", "Насыщенность", 0.6, 1.5, 1.0, "USER_SATUR"},
        {"highlights", "Светлые места: мягче / ярче", -0.3, 0.3, 0.0, "USER_SHOULDER"},
        {"bloom", "Свечение ярких мест", 0.0, 3.0, 1.0, "USER_BLOOM"},
        {"soften", "Мягкость", 0.0, 3.0, 1.0, "USER_SOFTEN"},
        {"fabric", "Фактура полотна", 0.0, 3.0, 1.0, "USER_FABRIC"},
        {"adapt", "Подстройка под свет зала", 0.0, 2.0, 1.0, ""},
        {"lift", "Свет зала на полотне", 0.0, 2.0, 1.0, ""},
        {"spill", "Отсвет вокруг экрана", 0.0, 3.0, 1.0, ""},
        {"spillSize", "Размер отсвета", 0.5, 2.0, 1.0, ""},
    };
    return list;
}

const SceneKnob *sceneKnob(const QString &id) {
    for (const SceneKnob &k : sceneKnobs())
        if (id == QLatin1String(k.id)) return &k;
    return nullptr;
}

double sceneKnobClamp(const QString &id, double v) {
    const SceneKnob *k = sceneKnob(id);
    if (!k) return 0;
    if (!std::isfinite(v)) return k->def;
    return std::clamp(v, k->min, k->max);
}

double tuningValue(const SceneTuning &t, const char *id) {
    const QString key = QString::fromLatin1(id);
    const auto it = t.constFind(key);
    return it == t.constEnd() ? sceneKnobClamp(key, std::nan("")) : sceneKnobClamp(key, *it);
}

QList<QPair<QByteArray, QByteArray>> sceneUserDefines(const SceneTuning &t) {
    QList<QPair<QByteArray, QByteArray>> out;
    for (const SceneKnob &k : sceneKnobs()) {
        if (!*k.define) continue;
        out.append({QByteArray(k.define), QByteArray::number(tuningValue(t, k.id), 'f', 3)});
    }
    return out;
}

SceneLook tunedLook(const SceneLook &look, const SceneTuning &t) {
    SceneLook r = look;
    r.dim = std::clamp(1.0 - (1.0 - look.dim) * tuningValue(t, "adapt"), 0.3, 1.0);
    r.liftAmount = std::clamp(look.liftAmount * tuningValue(t, "lift"), 0.0, 0.5);
    r.glow = std::max(0.0, look.glow * tuningValue(t, "spill"));
    return r;
}

SceneSample defaultScene() {
    SceneSample s;
    s.lin = QVector3D(0.016f, 0.021f, 0.020f); // тёмный зал с бирюзовым светом, как кинотеатр в игре
    s.luma = luma(s.lin);
    s.pixels = 1;
    return s;
}

} // namespace suslik::cinema
