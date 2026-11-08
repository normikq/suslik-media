#include "SpatialAudio.h"

#include <QtTest>

using namespace suslik::cinema;

namespace {
/// Камера в начале координат, смотрит по +Y мира (Z — вверх), вправо — +X.
CameraPose camera() {
    CameraPose c;
    c.pos = {0, 0, 0};
    c.f = {0, 1, 0};
    c.r = {1, 0, 0};
    c.u = {0, 0, 1};
    return c;
}
bool near(double a, double b, double eps = 1e-3) { return std::abs(a - b) <= eps; }
} // namespace

class SpatialAudioTest : public QObject {
    Q_OBJECT
private slots:
    void offIsNeutral() {
        QCOMPARE(spatialMix(camera(), {5, 5, 0}, 1.2, 1, Audio3D::Off, true), SpatialMix{});
    }

    void frontCloseIsFullAndCentred() {
        const SpatialMix m = spatialMix(camera(), {0, 2, 0}, 1.2, 0, Audio3D::Surround, true);
        QVERIFY(near(m.master, 1));
        QVERIFY(near(m.gainL, 1) && near(m.gainR, 1));
        QVERIFY(near(m.lowpassL, SpatialMix::kOpen) && near(m.lowpassR, SpatialMix::kOpen));
    }

    void distanceFadesLikeTvkit() {
        // экран 1,2 м: ref 2 м, hear 20 м; на полпути (11 м) — 0,5^1.5
        const SpatialMix mid = spatialMix(camera(), {0, 11, 0}, 1.2, 0, Audio3D::Stereo, true);
        QVERIFY(near(mid.master, std::pow(0.5, 1.5)));
        QVERIFY(near(spatialMix(camera(), {0, 25, 0}, 1.2, 0, Audio3D::Stereo, true).master, 0));
        // большой экран слышен дальше: 24 м шириной — 144 м
        QVERIFY(spatialMix(camera(), {0, 60, 0}, 24, 0, Audio3D::Stereo, true).master > 0.5);
    }

    void rightSideQuietsLeftEar() {
        const SpatialMix s = spatialMix(camera(), {3, 0, 0}, 1.2, 0, Audio3D::Stereo, true);
        QVERIFY(near(s.gainR, 1));
        QVERIFY(near(s.gainL, 0.2));
        QVERIFY(near(s.lowpassL, SpatialMix::kOpen)); // стерео — без тени головы
        const SpatialMix h = spatialMix(camera(), {3, 0, 0}, 1.2, 0, Audio3D::Surround, true);
        QVERIFY(near(h.gainL, 0.4));
        QVERIFY(near(h.lowpassL, 4000, 1)); // тень головы
        QVERIFY(near(h.lowpassR, SpatialMix::kOpen));
    }

    void behindIsDarker() {
        const SpatialMix b = spatialMix(camera(), {0, -3, 0}, 1.2, 0, Audio3D::Surround, true);
        QVERIFY(near(b.lowpassL, 7000, 1) && near(b.lowpassR, 7000, 1));
        QVERIFY(near(b.master, 0.8 * std::pow(1 - 1.0 / 18, 1.5)));
        QVERIFY(near(b.gainL, b.gainR));
    }

    void insideBigScreenHasNoDirection() {
        // перед серединой экрана 24 м, в метре от него сбоку — звук вокруг, не справа
        const SpatialMix m = spatialMix(camera(), {0.9, 0.3, 0}, 24, 0, Audio3D::Surround, true);
        QVERIFY(m.gainL > 0.95 && m.gainR > 0.95);
    }

    void wallMufflesLikeTvkit() {
        const SpatialMix w = spatialMix(camera(), {0, 2, 0}, 1.2, 1, Audio3D::Surround, true);
        QVERIFY(near(w.master, 0.4));
        QVERIFY(near(w.lowpassL, 700, 0.5) && near(w.lowpassR, 700, 0.5));
        // флажок «за стеной глуше» снят
        QVERIFY(near(spatialMix(camera(), {0, 2, 0}, 1.2, 1, Audio3D::Surround, false).master, 1));
    }

    void approachIsSmooth() {
        SpatialMix a, b;
        b.gainL = 0;
        b.lowpassL = 200;
        const SpatialMix h = approach(a, b, 0.5);
        QVERIFY(near(h.gainL, 0.5));
        QVERIFY(near(h.lowpassL, 2000, 1)); // середина по логарифму 20 000 и 200
        const SpatialMix full = approach(a, b, 1);
        QVERIFY(near(full.gainL, b.gainL) && near(full.lowpassL, b.lowpassL) && near(full.master, b.master));
    }
};

QTEST_GUILESS_MAIN(SpatialAudioTest)
#include "SpatialAudioTest.moc"
