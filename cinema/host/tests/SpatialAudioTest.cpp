#include "SpatialAudio.h"

#include <QtTest>
#include <numbers>

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

    // ---------- колонки ----------

    void speakersWithoutCameraAreFlatDownmix() {
        const SpeakerMix m = speakerMix(nullptr, SpeakerSet{}, true);
        for (int ch = 0; ch < kSpeakers; ++ch) {
            QVERIFY(near(m.ch[ch].gainL, speakerLevel(ch)) && near(m.ch[ch].gainR, speakerLevel(ch)));
            QVERIFY(near(m.ch[ch].lowpassL, SpatialMix::kOpen));
        }
        QVERIFY(near(speakerLevel(FL), 1) && near(speakerLevel(FC), 0.7071) && near(speakerLevel(LFE), 0.5));
    }

    void eachChannelFromItsSpeaker() {
        SpeakerSet set;
        set.has[FL] = set.has[SR] = true;
        set.pos[FL] = {-1.2, 2, 0}; // впереди слева
        set.pos[SR] = {2, -0.7, 0}; // справа сзади
        const CameraPose cam = camera();
        const SpeakerMix m = speakerMix(&cam, set, true);
        QVERIFY(m.ch[FL].gainL > m.ch[FL].gainR);       // левый фронт громче в левом ухе
        QVERIFY(near(m.ch[FL].lowpassL, SpatialMix::kOpen) && m.ch[FL].lowpassR < SpatialMix::kOpen);
        QVERIFY(m.ch[SR].gainR > m.ch[SR].gainL);       // правый тыл — в правом
        QVERIFY(m.ch[SR].lowpassR < SpatialMix::kOpen); // и за спиной глуше
        // правый фронт не поставлен и экрана нет — без направления
        QVERIFY(near(m.ch[FR].gainL, 1) && near(m.ch[FR].gainR, 1));
    }

    void fallbacks() {
        SpeakerSet set;
        set.has[FL] = set.has[FR] = set.has[SL] = true;
        set.pos[FL] = {-1, 3, 0};
        set.pos[FR] = {1, 3, 0};
        set.pos[SL] = {-2, -1, 0};
        set.screen = true;
        set.center = {0, 5, 1};
        set.width = 3;
        V3 p;
        double wall = 0, size = 0;
        bool onScreen = false;
        QVERIFY(speakerPlace(set, FC, p, wall, size, onScreen) && near(p.x, 0) && near(p.y, 3) && !onScreen); // центр — между фронтами
        QVERIFY(speakerPlace(set, BL, p, wall, size, onScreen) && near(p.x, -2) && !onScreen);                // тыл 7.1 в раскладке 5.1
        QVERIFY(speakerPlace(set, SR, p, wall, size, onScreen) && near(p.y, 5) && near(size, 3) && onScreen); // нет колонки — экран
        set.screen = false;
        QVERIFY(!speakerPlace(set, SR, p, wall, size, onScreen));
    }

    void subwooferHasNoDirection() {
        SpeakerSet set;
        set.has[LFE] = true;
        set.pos[LFE] = {4, 0, 0}; // справа
        const CameraPose cam = camera();
        const SpeakerMix m = speakerMix(&cam, set, true);
        QVERIFY(near(m.ch[LFE].gainL, m.ch[LFE].gainR));
        QVERIFY(m.ch[LFE].gainL < 0.5); // но дальше 2 м тише
    }

    void speakerBehindWallIsMuffled() {
        SpeakerSet set;
        set.has[FL] = true;
        set.pos[FL] = {0, 2, 0};
        set.wall[FL] = 1;
        const CameraPose cam = camera();
        const SpeakerMix m = speakerMix(&cam, set, true);
        QVERIFY(near(m.ch[FL].gainL, 0.4) && near(m.ch[FL].lowpassL, 700, 0.5));
        QVERIFY(near(speakerMix(&cam, set, false).ch[FL].gainL, 1));
    }

    void bigHallSpeakersAreLoud() {
        // зал: колонки в 20 м вокруг — у середины громко, как у настоящей системы
        CameraPose cam = camera();
        SpeakerSet set;
        auto put = [&](int ch, double deg) {
            const double a = deg * std::numbers::pi / 180.0;
            set.has[ch] = true;
            set.pos[ch] = {20.0 * std::sin(a), 20.0 * std::cos(a), 0};
        };
        put(FL, -30);
        put(FR, 30);
        put(FC, 0);
        put(SL, -110);
        put(SR, 110);

        const SpeakerMix m = speakerMix(&cam, set, true);
        QVERIFY(m.ch[FC].gainL > 0.6 && m.ch[FC].gainR > 0.6);
        QVERIFY(m.ch[FL].gainL > 0.8);
        for (int ch : {FL, FR, FC, SL, SR}) {
            QVERIFY(std::max(m.ch[ch].gainL, m.ch[ch].gainR) > 0.5);
        }

        // далеко за пределами зала — тихо
        cam.pos = {0, -100, 0};
        const SpeakerMix mFar = speakerMix(&cam, set, true);
        for (int ch : {FL, FR, FC, SL, SR}) {
            QVERIFY(mFar.ch[ch].gainL < 0.01 && mFar.ch[ch].gainR < 0.01);
        }

        // у центральной колонки она громче
        cam.pos = {0, 17, 0};
        const SpeakerMix mNearFC = speakerMix(&cam, set, true);
        const double fcMaxOrigin = std::max(m.ch[FC].gainL, m.ch[FC].gainR);
        const double fcMaxNear = std::max(mNearFC.ch[FC].gainL, mNearFC.ch[FC].gainR);
        QVERIFY(fcMaxNear > fcMaxOrigin);
    }

    void speakerRoomRadius() {
        SpeakerSet set;
        set.has[FL] = set.has[FR] = true;
        set.pos[FL] = {-5, 0, 0};
        set.pos[FR] = {5, 0, 0};
        V3 c;
        double r = 0;
        QVERIFY(speakerRoom(set, c, r));
        QVERIFY(near(c.x, 0) && near(c.y, 0) && near(r, 5));

        SpeakerSet empty;
        QVERIFY(!speakerRoom(empty, c, r));

        SpeakerSet single;
        single.has[FL] = true;
        single.pos[FL] = {-5, 0, 0};
        QVERIFY(speakerRoom(single, c, r));
        QVERIFY(near(r, 2));
    }

    void speakerApproach() {
        SpeakerMix a, b;
        b.ch[SL].gainL = 0;
        b.ch[SL].lowpassR = 200;
        const SpeakerMix h = approach(a, b, 0.5);
        QVERIFY(near(h.ch[SL].gainL, 0.5) && near(h.ch[SL].lowpassR, 2000, 1));
        QVERIFY(near(h.ch[FL].gainL, 1));
    }
};

QTEST_GUILESS_MAIN(SpatialAudioTest)
#include "SpatialAudioTest.moc"
