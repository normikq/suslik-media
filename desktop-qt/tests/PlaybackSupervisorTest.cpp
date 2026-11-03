// Перенос SupervisorTest и CatchUpTest (android/core/.../CoreTest.kt) и PlaybackSupervisorTest.kt.
#include "suslik/core/CatchUp.h"
#include "suslik/core/PlaybackSupervisor.h"

#include <QTest>

#define S QStringLiteral

using namespace suslik::core;
using State = PlaybackSupervisor::State;

namespace {

/// SupervisorTest: часы наши, отсчёт по 2 с, журнал действий строками.
struct Simple : PlaybackSupervisor::Actions {
    qint64 clock = 0;
    QStringList done;
    PlaybackSupervisor sup{*this, [this] { return clock; }};
    qint64 film = 0, audio = 0, pics = 0;

    void restartVideo() override { done << S("restartVideo"); }
    void fixSound() override { done << S("fixSound"); }
    void reopen(bool fresh) override { done << (fresh ? S("reopen(true)") : S("reopen(false)")); }
    void notice(const QString &) override {}
    void log(const QString &) override {}

    PlaybackSupervisor::Stats stats(double fps = 24.0) const {
        return {film, audio, audio, pics, fps, 1, true, 1, false};
    }
    void step(qint64 filmMs = 2000, bool sound = true, double picturesPerSec = 24.0) {
        clock += 2000; film += filmMs;
        if (sound) audio += 40;
        pics += static_cast<qint64>(picturesPerSec * filmMs / 1000);
        sup.tick(stats());
    }
    void steps(int n, qint64 filmMs = 2000, bool sound = true, double pps = 24.0) {
        for (int i = 0; i < n; ++i) step(filmMs, sound, pps);
    }
    void startPlaying() { sup.opening(S("ep1")); sup.playing(); steps(6); }
};

/// PlaybackSupervisorTest.kt: Sim.
struct Sim : PlaybackSupervisor::Actions {
    QList<bool> reopens;
    int videoRestarts = 0, soundFixes = 0;
    QStringList notices;
    qint64 t = 100000, time = 0, pictures = 100, played = 100;
    int audioTracks = 0;
    bool audioSelected = true;
    int videoTracks = 1;
    bool userMuted = false;
    double fps = 0;
    qint64 limit;
    PlaybackSupervisor sup;

    explicit Sim(qint64 stuckLimitMs = 12000)
        : limit(stuckLimitMs), sup(*this, [this] { return t; }, [this] { return limit; }) {}

    void restartVideo() override { ++videoRestarts; }
    void fixSound() override { ++soundFixes; }
    void reopen(bool fresh) override { reopens << fresh; }
    void notice(const QString &text) override { notices << text; }
    void log(const QString &) override {}

    void tick(qint64 clock = 2000, qint64 newPictures = 48, qint64 newAudio = 100) {
        t += 2000;
        time += clock; pictures += newPictures; played += newAudio;
        sup.tick({time, played, played, pictures, fps, audioTracks, audioSelected, videoTracks, userMuted});
    }
    void ticks(int n, qint64 clock = 2000, qint64 newPictures = 48, qint64 newAudio = 100) {
        for (int i = 0; i < n; ++i) tick(clock, newPictures, newAudio);
    }
    void warm(const QString &episode = S("a")) { sup.opening(episode); sup.buffering(100.f); ticks(5); }
};

} // namespace

class PlaybackSupervisorTest : public QObject {
    Q_OBJECT
private slots:
    // ---- SupervisorTest ----
    void healthyPlaybackDoesNothing() {
        Simple s; s.startPlaying(); s.steps(30);
        QVERIFY(s.done.isEmpty());
    }
    void noFalseSilenceAfterSeek() {
        Simple s; s.startPlaying();
        s.sup.seeked();
        s.steps(2, 0, false, 0.0);
        s.steps(10);
        QVERIFY(s.done.isEmpty());
    }
    void silentFilmFixedGently() {
        Simple s; s.startPlaying();
        s.steps(4, 2000, false);
        QCOMPARE(s.done, QStringList{S("fixSound")});
        s.steps(8, 2000, false);
        QCOMPARE(s.done, (QStringList{S("fixSound"), S("reopen(false)")}));
    }
    void frozenPictureRestartsVideo() {
        Simple s; s.startPlaying();
        s.steps(3, 2000, true, 0.0);
        QCOMPARE(s.done, QStringList{S("restartVideo")});
    }
    void frozenAfterEveryRebufferingNeverReopens() {
        Simple s; s.startPlaying();
        for (int i = 0; i < 6; ++i) {
            s.sup.buffering(40.f); s.step(0, false, 0.0); s.sup.buffering(100.f);
            s.steps(3, 2000, true, 0.0);
            s.steps(5);
        }
        QStringList expected;
        for (int i = 0; i < 6; ++i) expected << S("restartVideo");
        QCOMPARE(s.done, expected);
    }
    void frozenAgainReopensKeepingTheReserveFirst() {
        Simple s; s.startPlaying();
        s.steps(60, 2000, true, 0.0);
        QCOMPARE(s.done.mid(0, 3), (QStringList{S("restartVideo"), S("restartVideo"), S("reopen(false)")}));
    }
    void choppyThenFreshReopen() {
        Simple s; s.startPlaying();
        s.steps(4, 2000, true, 5.0);
        QCOMPARE(s.done, QStringList{S("restartVideo")});
        s.steps(6, 2000, true, 5.0);
        QCOMPARE(s.done.value(1), S("restartVideo"));
        s.steps(6, 2000, true, 5.0);
        QCOMPARE(s.done.last(), S("reopen(true)"));
    }
    void pausedIsNeverJudged() {
        Simple s; s.startPlaying(); s.sup.paused();
        s.steps(20, 0, false, 0.0);
        QVERIFY(s.done.isEmpty());
    }
    void stuckClockReopens() {
        Simple s; s.startPlaying();
        s.steps(7, 0, false, 0.0);
        QCOMPARE(s.done, QStringList{S("reopen(false)")});
    }
    void stillCoverImageIsNotFrozen() {
        Simple s; s.sup.opening(S("ep1")); s.sup.playing();
        s.steps(20, 2000, true, 0.0);
        QVERIFY(s.done.isEmpty());
    }
    void stillCoverAfterAMovingEpisodeIsNotFrozen() {
        Simple s; s.startPlaying();
        s.sup.opening(S("ep2")); s.sup.playing();
        s.steps(20, 2000, true, 0.0);
        QVERIFY(s.done.isEmpty());
    }
    void budgetsStopLoops() {
        Simple s; s.startPlaying();
        s.steps(200, 2000, true, 0.0);
        QVERIFY2(s.done.size() <= 5, qPrintable(s.done.join(u',')));
    }

    // ---- PlaybackSupervisorTest.kt ----
    void stuckClockReopensAfterTheLimit() {
        Sim s; s.warm();
        s.ticks(5, 0);
        QVERIFY(s.reopens.isEmpty());
        s.ticks(1, 0);
        QCOMPARE(s.reopens, QList<bool>{false});
        QCOMPARE(s.sup.state(), State::Recovering);
    }
    void nothingIsJudgedDuringTheGracePeriodAfterASeek() {
        Sim s(2000); s.warm();
        s.sup.seeked();
        s.ticks(2, 0);
        QVERIFY(s.reopens.isEmpty());
        s.ticks(1, 0);
        QCOMPARE(s.reopens.size(), 1);
    }
    void surfaceChangeChecksThePictureQuickly() {
        Sim s; s.warm();
        s.sup.surfaceChanged();
        s.tick(2000, 0);
        QCOMPARE(s.videoRestarts, 1);
    }
    void silentFilmFixesSoundThenReopens() {
        Sim s; s.audioTracks = 1; s.videoTracks = 0;
        s.warm();
        s.ticks(2, 2000, 48, 0);
        QCOMPARE(s.soundFixes, 0);
        s.tick(2000, 48, 0);
        QCOMPARE(s.soundFixes, 1);
        QVERIFY(s.reopens.isEmpty());
        s.ticks(20, 2000, 48, 0);
        QVERIFY(!s.reopens.isEmpty());
        QCOMPARE(s.reopens.first(), false);
        QVERIFY(s.notices.contains(S("Восстанавливаем звук…")));
    }
    void silenceChosenByTheUserIsNotAProblem() {
        Sim s; s.audioTracks = 1; s.userMuted = true; s.videoTracks = 0;
        s.warm();
        s.ticks(30, 2000, 48, 0);
        QCOMPARE(s.soundFixes, 0);
        QVERIFY(s.reopens.isEmpty());
    }
    void missingAudioSelectionIsFixed() {
        Sim s; s.audioTracks = 1; s.audioSelected = false; s.videoTracks = 0;
        s.warm();
        s.ticks(2);
        QVERIFY(s.soundFixes >= 1);
    }
    void reopenBudgetIsThreePerEpisodeAndResetsOnANewOne() {
        Sim s; s.warm(S("a"));
        s.ticks(100, 0);
        QCOMPARE(s.reopens, (QList<bool>{false, true, true}));

        s.sup.opening(S("a")); s.sup.buffering(100.f);
        s.ticks(60, 0);
        QCOMPARE(s.reopens.size(), 3);

        s.sup.opening(S("b")); s.sup.buffering(100.f);
        s.ticks(60, 0);
        QVERIFY(s.reopens.size() > 3);
    }

    // ---- CatchUpTest ----
    void catchUpNothingWithoutAReserve() {
        BurstProxy proxy;
        qint64 clock = 1000000;
        CatchUp c(proxy, [](const QString &) {}, [&] { return clock; });
        QVERIFY(!c.maybeStart(S("https://x/not-in-the-reserve.mkv"), true));
        QVERIFY(!c.active());
        QVERIFY(!c.end(S("https://x/not-in-the-reserve.mkv"), true)); // не активен: нечего заканчивать
    }
};

QTEST_GUILESS_MAIN(PlaybackSupervisorTest)
#include "PlaybackSupervisorTest.moc"
