// Перенос PartyTest.kt, PartyProtocolTest.kt и PartySyncEngineTest.kt (android/core/src/test).
#include "suslik/core/Party.h"

#include "TestUtil.h"

#include <QJsonArray>
#include <QTest>

using namespace suslik::core;

namespace {

const QByteArray ROOM =
    "{\"id\":\"ab12cd\",\"host_id\":\"u1\",\"host_key\":\"k1\",\"host_name\":\"Аня\",\"controls\":\"all\","
    "\"file\":{\"key\":\"42|Season 1/e01.mkv\",\"name\":\"Серия 1\",\"ad_id\":42},"
    "\"state\":{\"playing\":true,\"pos\":12.5,\"rate\":1,\"anchor_server_ms\":1000,\"seq\":7,\"waiting\":false,\"server_time\":1500},"
    "\"members\":[{\"key\":\"k1\",\"id\":\"u1\",\"name\":\"Аня\",\"buffering\":false,\"avatar\":\"https://cdn.discordapp.com/a.png\"},"
    "{\"key\":\"k2\",\"id\":\"u2\",\"name\":\"\",\"buffering\":true,\"avatar\":\"http://evil/a.png\"}]}";

QJsonObject room() { return testutil::json(ROOM); }
QString text(const QJsonObject &o) { return QString::fromUtf8(QJsonDocument(o).toJson(QJsonDocument::Compact)); }
QJsonObject withState(QJsonObject r, const QJsonObject &changes) {
    QJsonObject s = r.value(S("state")).toObject();
    for (auto it = changes.begin(); it != changes.end(); ++it) s.insert(it.key(), it.value());
    r.insert(S("state"), s);
    return r;
}

PartyState st(bool playing, double pos, double rate, qint64 anchor, qint64 seq, bool waiting = false) {
    return PartyState{playing, pos, rate, anchor, seq, waiting};
}

struct FakePlayer : SyncPlayer {
    qint64 t = 0, e = 0, stamp = 0;
    bool isBusy = false, isPaused = true, isRunning = true;
    float rate = 1.f;
    QList<qint64> seeks;
    QList<float> rates;
    qint64 timeMs() const override { return t; }
    bool busy() const override { return isBusy; }
    qint64 engineMs() const override { return e; }
    qint64 seekStamp() const override { return stamp; }
    bool paused() const override { return isPaused; }
    bool running() const override { return isRunning; }
    float userRate() const override { return rate; }
    void applyPaused(bool p) override { isPaused = p; }
    void seekTo(qint64 ms) override { seeks << ms; t = ms; e = ms; ++stamp; }
    void setRate(float r) override { rates << r; }
    void userSeek(qint64 ms) { t = ms; e = ms; ++stamp; }
    void play(qint64 ms) { if (!isPaused && isRunning) { t += ms; e += ms; } }
};

struct FakeLink : PartyLink {
    explicit FakeLink(bool h) : host(h) {}
    bool host;
    PartyState s;
    qint64 target = 0;
    bool others = false;
    std::optional<QString> startedKey;
    QStringList sent;
    QList<bool> buffering;
    PartyState state() const override { return s; }
    bool canControl() const override { return host; }
    bool canSeek() const override { return host; }
    qint64 targetMs() const override { return target; }
    bool othersBuffering() const override { return others; }
    bool takeStartedByMe(const QString &key) override {
        const bool r = startedKey && *startedKey == key;
        if (r) startedKey.reset();
        return r;
    }
    void play(qint64 p) override { sent << S("play %1").arg(p); }
    void pause(qint64 p) override { sent << S("pause %1").arg(p); }
    void seek(qint64 p) override { sent << S("seek %1").arg(p); }
    void reportBuffering(bool l) override { if (buffering.isEmpty() || buffering.last() != l) buffering << l; }
};

const QString KEY = S("42|a.mkv");

} // namespace

class PartyTest : public QObject {
    Q_OBJECT
private slots:
    // ---- PartyTest.kt ----
    void clockOffset() {
        PartyClock c;
        c.addSample(1000, 1040, 5000);
        QCOMPARE(c.offsetMs(), 3980);
        QCOMPARE(c.bestRttMs(), 40);
        QCOMPARE(c.serverNow(10000), 13980);
        c.addSample(2000, 2080, 6000);
        QCOMPARE(c.bestRttMs(), 40);
        QCOMPARE(c.offsetMs(), 3980);
        c.addSample(3000, 3020, 7000);
        QCOMPARE(c.bestRttMs(), 20);
        QCOMPARE(c.offsetMs(), 3990);
    }

    void clockForgetsOldSamples() {
        PartyClock c;
        c.addSample(1000, 1020, 5000);
        c.addSample(300000, 300100, 304550);
        QCOMPARE(c.offsetMs(), 3990);
        const qint64 late = 1020 + PartyClock::WINDOW_MS + 1;
        c.addSample(late - 60, late, late + 4470);
        QCOMPARE(c.bestRttMs(), 60);
        QCOMPARE(c.offsetMs(), 4500);
    }

    void driftDecisions() {
        QCOMPARE(DriftController::evaluate(10.1f, 10.0f).kind, DriftAction::Kind::None);
        QCOMPARE(DriftController::evaluate(9.8f, 10.0f).kind, DriftAction::Kind::None);
        const auto slow = DriftController::evaluate(10.5f, 10.0f);
        QCOMPARE(slow.kind, DriftAction::Kind::SlowDown);
        QCOMPARE(slow.rate, 0.95f);
        const auto fast = DriftController::evaluate(9.2f, 10.0f);
        QCOMPARE(fast.kind, DriftAction::Kind::SpeedUp);
        QCOMPARE(fast.rate, 1.05f);
        const auto ahead = DriftController::evaluate(25.0f, 10.0f);
        QCOMPARE(ahead.kind, DriftAction::Kind::HardSeek);
        QCOMPARE(ahead.targetSec, 10.0f);
        QCOMPARE(DriftController::evaluate(5.0f, 10.0f).kind, DriftAction::Kind::HardSeek);
    }

    // ---- PartyProtocolTest.kt ----
    void parsesRoom() {
        const auto r = PartyProtocol::parseRoom(room());
        QCOMPARE(r.id.value_or(QString()), S("ab12cd"));
        QCOMPARE(r.hostId.value_or(QString()), S("u1"));
        QCOMPARE(r.hostKey.value_or(QString()), S("k1"));
        QVERIFY(!r.hostOnly);
        QVERIFY(r.file);
        QCOMPARE(r.file->key, S("42|Season 1/e01.mkv"));
        QCOMPARE(r.file->title, S("Серия 1"));
        QCOMPARE(r.file->adId(), 42);
        QVERIFY(r.state);
        QCOMPARE(*r.state, st(true, 12.5, 1.0, 1000, 7, false));
        QCOMPARE(r.serverTime, 1500);
        QVERIFY(r.members);
        const auto &m = *r.members;
        QCOMPARE(m[0].avatar.value_or(QString()), S("https://cdn.discordapp.com/a.png"));
        QCOMPARE(m[1].name, S("Гость"));
        QVERIFY(!m[1].avatar);
        QVERIFY(m[1].buffering);
    }

    void mergeKeepsNewerStateAndMembers() {
        const auto first = PartyRoom().merge(PartyProtocol::parseRoom(room()));
        QJsonObject stale = withState(room(), {{S("seq"), 5}, {S("playing"), false}});
        stale.remove(S("members"));
        const auto r = first.merge(PartyProtocol::parseRoom(stale));
        QCOMPARE(r.state.seq, 7);
        QVERIFY(r.state.playing);
        QCOMPARE(r.members.size(), 2);
        QJsonObject noFile = room();
        noFile.remove(S("file"));
        QVERIFY(!first.merge(PartyProtocol::parseRoom(noFile)).file);
    }

    void targetMsFollowsAnchor() {
        const auto s = st(true, 10.0, 1.0, 5000, 1);
        QCOMPARE(s.targetMs(8000), 13000);
        QCOMPARE(s.targetMs(4000), 10000);
        auto w = s; w.waiting = true;
        QCOMPARE(w.targetMs(8000), 10000);
        auto p = s; p.playing = false;
        QCOMPARE(p.targetMs(8000), 10000);
        auto f = s; f.rate = 1.5;
        QCOMPARE(f.targetMs(8000), 14500);
    }

    void hostRules() {
        const PartyMember a{S("ka"), S("shared"), S("A")};
        const PartyMember b{S("kb"), S("shared"), S("B")};
        PartyRoom shared; shared.hostId = S("shared"); shared.hostKey = S("ka");
        QVERIFY(shared.isHost(S("shared"), S("ka")));
        QVERIFY(!shared.isHost(S("shared"), S("kb")));
        QVERIFY(shared.isCreator(a));
        QVERIFY(!shared.isCreator(b));
        QVERIFY(shared.isHost(a));
        QVERIFY(!shared.isHost(b));
        PartyRoom discord; discord.hostId = S("u1"); discord.hostKey = S("ka");
        QVERIFY(discord.isHost(S("u1"), S("kb")));
        QVERIFY(!discord.isHost(S("u2"), S("ka")));
        QVERIFY(!discord.isHost(std::nullopt, S("ka")));
        PartyRoom r;
        auto ab = a; ab.buffering = true;
        r.members = {ab, b};
        QVERIFY(r.othersBuffering(S("kb")));
        QVERIFY(!r.othersBuffering(S("ka")));
    }

    void parsesEventsAndReduces() {
        const auto init = PartyProtocol::parseEventLine(S("data: {\"event\":\"init\",\"member\":{\"id\":\"u2\"},\"room\":%1}").arg(text(room())));
        QVERIFY(init);
        QCOMPARE(init->kind, PartyEvent::Kind::Init);
        QCOMPARE(init->myUserId.value_or(QString()), S("u2"));
        auto r = PartyRoom().reduce(*init);
        QCOMPARE(r.members.size(), 2);

        r = r.reduce(*PartyProtocol::parseEvent(S(R"({"event":"member_joined","data":{"member":{"key":"k2","id":"u2","name":"Боря"}}})")));
        QCOMPARE(r.members.size(), 2);
        QCOMPARE(r.members.last().name, S("Боря"));

        r = r.reduce(*PartyProtocol::parseEvent(S(R"({"event":"member_left","data":{"key":"k1"}})")));
        QCOMPARE(r.members.size(), 1);
        QCOMPARE(r.members[0].key, S("k2"));

        const auto changed = withState(room(), {{S("seq"), 8}, {S("playing"), false}});
        r = r.reduce(*PartyProtocol::parseEvent(S("{\"event\":\"state_changed\",\"data\":%1}").arg(text(changed))));
        QVERIFY(!r.state.playing);
        QCOMPARE(r.state.seq, 8);

        const auto ref = PartyProtocol::parseEventLine(S(R"(data: {"event":"refresh_library","data":{"file":{"key":"42|s1.mkv","name":"Test"}}})"));
        QVERIFY(ref);
        QCOMPARE(ref->kind, PartyEvent::Kind::RefreshLibrary);
        QVERIFY(ref->file);
        QCOMPARE(ref->file->key, S("42|s1.mkv"));
        QCOMPARE(ref->file->title, S("Test"));

        const auto refEmpty = PartyProtocol::parseEventLine(S(R"(data: {"event":"refresh_library"})"));
        QVERIFY(refEmpty);
        QCOMPARE(refEmpty->kind, PartyEvent::Kind::RefreshLibrary);
        QVERIFY(!refEmpty->file);

        QVERIFY(!PartyProtocol::parseEventLine(S(": ping")));
        QVERIFY(!PartyProtocol::parseEvent(S(R"({"event":"something_new"})")));
        QVERIFY(!PartyProtocol::parseEvent(S("not json")));
    }

    void commandsAndKeys() {
        QCOMPARE(PartyProtocol::normalizeCode(S("  AB-12 cd ")), S("ab12cd"));
        // Ссылка из Discord Activity
        QCOMPARE(PartyProtocol::roomFromLink(S("suslik://room/842190")), S("842190"));
        QCOMPARE(PartyProtocol::roomFromLink(S(" SUSLIK://room/842-190/ ")), S("842190"));
        QCOMPARE(PartyProtocol::roomFromLink(S("suslik://room/a1b2c3d4e5f6?x=1")), S("a1b2c3d4e5f6"));
        QCOMPARE(PartyProtocol::roomFromLink(S("https://example.com/room/842190")), QString());
        QCOMPARE(PartyProtocol::roomFromLink(S("suslik://room/")), QString());
        QCOMPARE(PartyProtocol::clientKey(S("pc_test")), S("9703bd8de4eb8d02"));
        QVERIFY(PartyProtocol::newClientId(S("an")).startsWith(S("an_")));
        QCOMPARE(PartyProtocol::newClientId(S("pc")).length(), 15);
        const auto play = PartyProtocol::withClient(PartyProtocol::play(12340), S("pc_1"));
        QCOMPARE(play.value(S("type")).toString(), S("play"));
        QVERIFY(qAbs(play.value(S("pos")).toDouble() - 12.34) < 1e-9);
        QCOMPARE(play.value(S("client_id")).toString(), S("pc_1"));
        const auto vchange = PartyProtocol::variantChange(S("104921|Dune.1080p.mkv"));
        QCOMPARE(vchange.value(S("type")).toString(), S("variant_change"));
        QCOMPARE(vchange.value(S("variant_key")).toString(), S("104921|Dune.1080p.mkv"));
        const auto file = PartyProtocol::changeFile(PartyFileRef{S("42|a.mkv"), S("A")}).value(S("file")).toObject();
        QCOMPARE(qint64(file.value(S("ad_id")).toDouble()), 42);
        QCOMPARE(PartyProtocol::eventsPath(S("x"), S("pc_1")), S("/api/rooms/x/events?client_id=pc_1"));
        // Запрос Discord Activity к ПК: поиск на RuTracker и добавление найденного
        const auto rq = PartyProtocol::remoteRequestOf(S(R"(data: {"event":"rutracker_request","data":{"request_id":"rq_1","kind":"search","query":" Dune ","by":"Алиса"}})"));
        QVERIFY(rq);
        QCOMPARE(rq->query, S("Dune"));
        QCOMPARE(rq->by, S("Алиса"));
        const auto add = PartyProtocol::remoteRequestOf(S(R"(data: {"event":"rutracker_request","data":{"request_id":"rq_2","kind":"add","topic_id":"6123456","target":"9703bd8de4eb8d02"}})"));
        QVERIFY(add);
        QCOMPARE(add->topicId, S("6123456"));
        QVERIFY(!PartyProtocol::remoteRequestOf(S(R"(data: {"event":"rutracker_request","data":{"request_id":"rq_3","kind":"add","topic_id":"1"}})")));
        QVERIFY(!PartyProtocol::remoteRequestOf(S(R"(data: {"event":"rutracker_request","data":{"kind":"search","query":"x"}})")));
        QVERIFY(!PartyProtocol::remoteRequestOf(S(R"(data: {"event":"state_changed","data":{}})")));
        QVERIFY(!PartyProtocol::parseEventLine(S(R"(data: {"event":"rutracker_request","data":{"request_id":"rq_1","kind":"search","query":"x"}})")));
        QCOMPARE(PartyProtocol::remoteReplyPath(S("842190")), S("/api/rooms/842190/rutracker/reply"));
    }

    void pendingFileIsNotOverwrittenByStaleAnswers() {
        const auto r0 = PartyRoom().merge(PartyProtocol::parseRoom(room()));
        const PartyFileRef next{S("42|Season 1/e02.mkv"), S("Серия 2")};
        auto r = r0.putFile(next);
        QJsonObject stale = withState(room(), {{S("seq"), 9}, {S("pos"), 679.0}});
        QJsonArray members = stale.value(S("members")).toArray();
        members.append(testutil::json(R"({"key":"k3","id":"u3","name":"Вика"})"));
        stale.insert(S("members"), members);
        r = r.merge(PartyProtocol::parseRoom(stale));
        QCOMPARE(r.file.value_or(PartyFileRef{}), next);
        QCOMPARE(r.state.seq, 7);
        QCOMPARE(r.members.size(), 3);
        QJsonObject confirmed = withState(room(), {{S("seq"), 10}, {S("pos"), 0.0}, {S("playing"), false}});
        confirmed.insert(S("file"), QJsonObject{{S("key"), next.key}, {S("name"), next.title}});
        r = r.merge(PartyProtocol::parseRoom(confirmed));
        QVERIFY(!r.pendingFileKey);
        QCOMPARE(r.state.seq, 10);
        QCOMPARE(r.file.value_or(PartyFileRef{}), next);
        QVERIFY(!r0.putFile(next).fileRejected().pendingFileKey);
    }

    void closedFileStaysClosed() {
        const auto r0 = PartyRoom().merge(PartyProtocol::parseRoom(room()));
        auto r = r0.clearFile();
        QVERIFY(!r.file);
        r = r.merge(PartyProtocol::parseRoom(room()));
        QVERIFY(!r.file);
        QJsonObject confirmed = withState(room(), {{S("seq"), 10}, {S("playing"), false}});
        confirmed.insert(S("file"), QJsonValue::Null);
        r = r.merge(PartyProtocol::parseRoom(confirmed));
        QVERIFY(!r.pendingFileKey);
        QVERIFY(!r.file);
        QCOMPARE(r.state.seq, 10);
        const auto body = PartyProtocol::closeFile();
        QCOMPARE(body.value(S("type")).toString(), S("change_file"));
        QVERIFY(body.value(S("file")).isNull());
    }

    // ---- PartySyncEngineTest.kt ----
    void guestJoinsPlayingRoom() {
        FakePlayer p; FakeLink l(false);
        l.s = st(true, 60.0, 1.0, 0, 3); l.target = 60000;
        PartySyncEngine e(l);
        e.tick(p, KEY, 1000);
        QVERIFY(!p.isPaused);
        QCOMPARE(p.seeks, QList<qint64>{60000});
        QVERIFY(l.sent.isEmpty());
        e.tick(p, KEY, 1400);
        QCOMPARE(l.buffering, (QList<bool>{false, true}));
    }

    void guestPauseStaysLocal() {
        FakePlayer p; FakeLink l(false);
        l.s = st(true, 10.0, 1.0, 0, 1); l.target = 10000;
        p.t = 10000; p.e = 10000;
        PartySyncEngine e(l);
        qint64 now = 1000;
        e.tick(p, KEY, now);
        QVERIFY(!p.isPaused);
        for (int i = 0; i < 3; ++i) { now += 400; p.play(400); l.target += 400; e.tick(p, KEY, now); }
        p.isPaused = true;
        for (int i = 0; i < 10; ++i) { now += 400; l.target += 400; e.tick(p, KEY, now); }
        QVERIFY(p.isPaused);
        QVERIFY(l.sent.isEmpty());
        p.isPaused = false;
        now += 400; l.target += 400; e.tick(p, KEY, now);
        QVERIFY(!p.isPaused);
        QCOMPARE(p.seeks.last(), l.target);
    }

    void hostPauseAndSeekGoToRoom() {
        FakePlayer p; FakeLink l(true);
        l.s = st(false, 5.0, 1.0, 0, 1); l.target = 5000;
        p.t = 5000; p.e = 5000;
        PartySyncEngine e(l);
        e.tick(p, KEY, 1000);
        QVERIFY(p.isPaused);
        e.tick(p, KEY, 3600);
        p.isPaused = false;
        e.tick(p, KEY, 4000);
        QCOMPARE(l.sent, QStringList{S("play 5000")});
        l.s = st(true, 5.0, 1.0, 0, 2);
        p.userSeek(90000);
        e.tick(p, KEY, 4400);
        QCOMPARE(l.sent.last(), S("play 90000"));
    }

    void startedByMeStartsRoomFromHere() {
        FakePlayer p; FakeLink l(true);
        p.isPaused = false; p.t = 1234;
        l.startedKey = KEY;
        PartySyncEngine(l).tick(p, KEY, 1000);
        QCOMPARE(l.sent, QStringList{S("play 1234")});
    }

    void smallDriftChangesRate() {
        FakePlayer p; FakeLink l(false);
        l.s = st(true, 10.0, 1.0, 0, 1); l.target = 10200;
        p.t = 10000; p.e = 10000;
        PartySyncEngine e(l);
        e.tick(p, KEY, 1000);
        QVERIFY(p.seeks.isEmpty());
        qint64 now = 1000;
        while (now < 3600) { now += 400; p.play(400); l.target += 400; e.tick(p, KEY, now); }
        QVERIFY(p.rates.isEmpty());
        l.target = p.t + 800;
        now += 400; p.play(400); l.target += 400; e.tick(p, KEY, now);
        QCOMPARE(p.rates, QList<float>{1.05f});
        l.target = p.t + 450;
        now += 400; p.play(400); e.tick(p, KEY, now);
        QCOMPARE(p.rates, (QList<float>{1.05f, 1.f}));
        QVERIFY(p.seeks.isEmpty());
    }

    // своя скорость плеера (1,25×) в фильме комнаты не действует, дрейф выравнивается; вне комнаты — снова своя
    void roomRateOverridesUserRate() {
        FakePlayer p; FakeLink l(true);
        p.rate = 1.25f;
        l.s = st(true, 10.0, 1.0, 0, 1); l.target = 10000;
        p.t = 10000; p.e = 10000; p.isPaused = false;
        PartySyncEngine e(l);
        qint64 now = 1000;
        e.tick(p, KEY, now);
        QCOMPARE(p.rates, QList<float>{1.f});
        while (now < 4000) { now += 400; p.play(400); l.target += 400; e.tick(p, KEY, now); }
        l.target = p.t + 800;
        now += 400; p.play(400); l.target += 400; e.tick(p, KEY, now);
        QCOMPARE(p.rates, (QList<float>{1.f, 1.05f}));
        e.idle(p);
        QCOMPARE(p.rates, (QList<float>{1.f, 1.05f, 1.25f}));
        QVERIFY(l.sent.isEmpty());
    }

    void stallReportsBuffering() {
        FakePlayer p; FakeLink l(false);
        l.s = st(true, 10.0, 1.0, 0, 1); l.target = 10000;
        p.t = 10000; p.e = 10000;
        PartySyncEngine e(l);
        e.tick(p, KEY, 1000);
        p.isRunning = false;
        e.tick(p, KEY, 1400);
        QCOMPARE(l.buffering, QList<bool>{false});
        e.tick(p, KEY, 2600);
        QCOMPARE(l.buffering, (QList<bool>{false, true}));
        p.isRunning = true; p.play(400);
        e.tick(p, KEY, 3000);
        QCOMPARE(l.buffering, (QList<bool>{false, true, false}));
    }

    void waitsForOthersAndIdles() {
        FakePlayer p; FakeLink l(false);
        l.s = st(true, 10.0, 1.0, 0, 1); l.target = 10000;
        p.t = 10000; p.e = 10000;
        PartySyncEngine e(l);
        e.tick(p, KEY, 1000);
        QVERIFY(!p.isPaused);
        l.s.waiting = true; l.others = true;
        e.tick(p, KEY, 1400);
        QVERIFY(p.isPaused);
        e.idle(p);
        QCOMPARE(l.buffering.last(), false);
    }

    void seekDoesNotPauseWhenNoOthersBuffering() {
        FakePlayer p; FakeLink l(true);
        l.s = st(true, 10.0, 1.0, 0, 1); l.target = 10000;
        p.t = 10000; p.e = 10000; p.isPaused = false;
        PartySyncEngine e(l);
        e.tick(p, KEY, 1000);
        QVERIFY(!p.isPaused);
        p.userSeek(50000);
        e.tick(p, KEY, 4000);
        l.s = st(true, 50.0, 1.0, 0, 3, true);
        l.others = false;
        l.target = 50000;
        p.e = 50200;
        p.isRunning = true;
        e.tick(p, KEY, 4800);
        QVERIFY(!p.isPaused);
    }

    void hostPauseWorksImmediately() {
        FakePlayer p; FakeLink l(true);
        l.s = st(true, 10.0, 1.0, 0, 1); l.target = 10000;
        p.t = 10000; p.e = 10000; p.isPaused = false;
        PartySyncEngine e(l);
        e.tick(p, KEY, 1000);
        QVERIFY(!p.isPaused);
        p.isPaused = true;
        e.tick(p, KEY, 1400);
        QCOMPARE(l.sent, QStringList{S("pause 10000")});
    }

    void hostSeekWhilePausedSendsPause() {
        FakePlayer p; FakeLink l(true);
        l.s = st(false, 10.0, 1.0, 0, 1); l.target = 10000;
        p.t = 10000; p.e = 10000; p.isPaused = true;
        PartySyncEngine e(l);
        e.tick(p, KEY, 1000);
        QVERIFY(p.isPaused);
        p.userSeek(60000);
        e.tick(p, KEY, 1400);
        QCOMPARE(l.sent.last(), S("pause 60000"));
        l.s = st(false, 60.0, 1.0, 0, 2);
        l.target = 60000;
        for (int step = 0; step < 10; ++step) {
            e.tick(p, KEY, 2000 + step * 400);
            QVERIFY(p.isPaused);
        }
    }

    void guestSeekWhileRoomWaitsIsNotDoneUntilLoaded() {
        FakePlayer p; FakeLink l(false);
        l.s = st(true, 1104.0, 1.0, 0, 3, true); l.target = 1104000; l.others = true;
        p.t = 774000; p.e = 774000;
        PartySyncEngine e(l);
        e.tick(p, KEY, 1000);
        QCOMPARE(p.seeks, QList<qint64>{1104000});
        QVERIFY(!p.isPaused);
        p.isRunning = false;
        qint64 now = 1000;
        for (int i = 0; i < 5; ++i) { now += 400; e.tick(p, KEY, now); }
        QCOMPARE(l.buffering, (QList<bool>{false, true}));
        p.isRunning = true;
        for (int i = 0; i < 2; ++i) { now += 400; p.play(400); e.tick(p, KEY, now); }
        QCOMPARE(l.buffering, (QList<bool>{false, true, false}));
        now += 400; p.play(400); e.tick(p, KEY, now);
        QVERIFY(p.isPaused);
    }

    void frozenTimeCountsAsLoading() {
        FakePlayer p; FakeLink l(true);
        p.isPaused = false; p.t = 5208700; p.e = 5208700;
        l.startedKey = KEY;
        PartySyncEngine e(l);
        e.tick(p, KEY, 1000);
        l.s = st(true, 5208.7, 1.0, 0, 2); l.target = 5208700;
        qint64 now = 1000;
        for (int i = 0; i < 4; ++i) { now += 400; l.target += 400; e.tick(p, KEY, now); }
        QCOMPARE(l.buffering, (QList<bool>{false, true}));
        for (int i = 0; i < 6; ++i) { now += 400; e.tick(p, KEY, now); }
        QVERIFY(p.seeks.isEmpty());
        p.play(400); now += 400; e.tick(p, KEY, now);
        QCOMPARE(l.buffering, (QList<bool>{false, true, false}));
    }

    void guestSeesHostActions() {
        FakePlayer p; FakeLink l(false);
        QList<PartyNotice> notes;
        l.s = st(true, 100.0, 1.0, 0, 1); l.target = 100000;
        p.t = 100000; p.e = 100000;
        PartySyncEngine e(l, {}, [&](const PartyNotice &n) { notes << n; });
        qint64 now = 1000;
        e.tick(p, KEY, now);
        auto step = [&](const PartyState &s) { l.s = s; l.target = qint64(s.pos * 1000); now += 400; p.play(400); e.tick(p, KEY, now); };
        step(st(false, 110.0, 1.0, 10000, 2));
        step(st(true, 110.0, 1.0, 20000, 3));
        step(st(true, 600.0, 1.0, 25000, 4));
        step(st(true, 600.0, 1.0, 25500, 5, true));
        QCOMPARE(notes, (QList<PartyNotice>{{PartyNotice::Kind::Paused, 0}, {PartyNotice::Kind::Resumed, 0}, {PartyNotice::Kind::Seeked, 600000}}));
    }

    void waitingNoticeOnceAfterTwoSeconds() {
        FakePlayer p; FakeLink l(true);
        QList<PartyNotice> notes;
        l.s = st(true, 10.0, 1.0, 0, 1); l.target = 10000;
        p.t = 10000; p.e = 10000;
        PartySyncEngine e(l, {}, [&](const PartyNotice &n) { notes << n; });
        qint64 now = 1000;
        e.tick(p, KEY, now);
        l.s.waiting = true; l.s.seq = 2; l.others = true;
        for (int i = 0; i < 4; ++i) { now += 400; e.tick(p, KEY, now); }
        QVERIFY(notes.isEmpty());
        for (int i = 0; i < 10; ++i) { now += 400; e.tick(p, KEY, now); }
        QCOMPARE(notes, (QList<PartyNotice>{{PartyNotice::Kind::Waiting, 0}}));
    }

    void canonicalContextAndVariants() {
        PartyVariant v1{S("101|Dune.2021.2160p.Remux.mkv"), S("4K Remux"), S("4K"), S("TrueHD Atmos"), 48.2, 0, 505, {S("dv"), S("hdr")}};
        PartyVariant v2{S("102|Dune.2021.1080p.BDRip.mkv"), S("1080p BDRip"), S("1080p"), S("AC3 5.1"), 8.1, 0, 335, {}};
        PartyFileRef ref{v1.key, S("Дюна (2021)"), S("m:dune_2021:theatrical"), S("theatrical"), {v1, v2}};

        // matches() checks
        QVERIFY(ref.matches(v1.key));
        QVERIFY(ref.matches(v2.key)); // variant matches
        QVERIFY(ref.matches(S("any_file.mkv"), S("m:dune_2021:theatrical"))); // canonical matches
        QVERIFY(!ref.matches(S("other.mkv"), S("m:other_film:theatrical")));
        QVERIFY(!ref.matches(QString()));

        // JSON serialization and parsing
        const QJsonObject json = PartyProtocol::fileJson(ref);
        QCOMPARE(json.value(S("canonical_id")).toString(), S("m:dune_2021:theatrical"));
        QCOMPARE(json.value(S("edition")).toString(), S("theatrical"));
        QCOMPARE(json.value(S("variants")).toArray().size(), 2);

        const auto parsed = PartyProtocol::parseFileRef(json);
        QVERIFY(parsed.has_value());
        QCOMPARE(parsed->canonicalId.value_or(QString()), S("m:dune_2021:theatrical"));
        QCOMPARE(parsed->edition.value_or(QString()), S("theatrical"));
        QCOMPARE(parsed->variants.size(), 2);
        QCOMPARE(parsed->variants[0].score, 505);
        QCOMPARE(parsed->variants[1].bitrate, 8.1);
    }

    void parsePartyVariantTest() {
        const QJsonObject o{
            {S("key"), S("42|video.mkv")},
            {S("label"), S("4K HDR")},
            {S("resolution"), S("4K")},
            {S("audio_tech"), S("TrueHD Atmos")},
            {S("bitrate_mbps"), 45.5},
            {S("offset_ms"), 1500},
            {S("score"), 500},
            {S("hardware_flags"), QJsonArray{S("dv"), S("hdr")}}
        };
        const PartyVariant v = parsePartyVariant(o);
        QCOMPARE(v.key, S("42|video.mkv"));
        QCOMPARE(v.label, S("4K HDR"));
        QCOMPARE(v.quality, S("4K"));
        QCOMPARE(v.audio, S("TrueHD Atmos"));
        QCOMPARE(v.bitrate, 45.5);
        QCOMPARE(v.offsetMs, 1500);
        QCOMPARE(v.score, 500);
        QCOMPARE(v.hardwareFlags, QStringList({S("dv"), S("hdr")}));
    }

    void buildPartyVariantTest() {
        const MFile f = testutil::file(0, S("Dune.2021.mkv"), 45000);
        const Magnet m = testutil::magnet(42, S("Dune.2021.2160p.UHD.Remux.DV.TrueHD.Atmos.mkv"), S("Ready"), 4, 45000000000LL, 45000000000LL, {f});
        const LibraryItem item = prepareItems({m}).first();
        const PartyVariant v = buildPartyVariant(item, f);

        QCOMPARE(v.key, S("42|Dune.2021.mkv"));
        QCOMPARE(v.quality, S("4K"));
        QCOMPARE(v.audio, S("TrueHD Atmos"));
        QCOMPARE(v.score, 505);
        QCOMPARE(v.hardwareFlags, QStringList{S("dv")});
        QVERIFY(v.bitrate > 0.0);
        QVERIFY(v.label.contains(S("4K")));
        QVERIFY(!v.label.contains(S("Мбит/с"))); // Без профиля сервера битрейт не показывается как факт в подписи

        PartyVariant prof;
        prof.key = S("42|Dune.2021.mkv");
        prof.bitrate = 54.5;
        const PartyVariant vProf = buildPartyVariant(item, f, prof);
        QCOMPARE(vProf.bitrate, 54.5);

        // Неизвестное разрешение не подменяется на 1080p
        const MFile fUnknown = testutil::file(0, S("video.mkv"), 1000);
        const Magnet mUnknown = testutil::magnet(43, S("SomeVideo"), S("Ready"), 4, 1000LL, 1000LL, {fUnknown});
        const LibraryItem itemUnknown = prepareItems({mUnknown}).first();
        const PartyVariant vUnknown = buildPartyVariant(itemUnknown, fUnknown);
        QVERIFY(vUnknown.quality.isEmpty());
        QVERIFY(!vUnknown.label.contains(S("1080p")));
    }
};

QTEST_GUILESS_MAIN(PartyTest)
#include "PartyTest.moc"
