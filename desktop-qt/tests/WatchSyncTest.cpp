// Перенос android/core/src/test/.../WatchSyncTest.kt.
#include "suslik/core/WatchSync.h"
#include "TestUtil.h"

#include <QJsonArray>
#include <map>

using namespace suslik::core;
using namespace testutil;

namespace {
class MemStore : public SyncStore {
public:
    std::map<QString, WatchEntry> w;
    std::map<QString, TrackEntry> t;
    QStringList wOrder, tOrder; // порядок вставки, как LinkedHashMap
    std::optional<QString> c;

    std::optional<WatchEntry> watch(const QString &key) override { auto it = w.find(key); return it == w.end() ? std::nullopt : std::optional(it->second); }
    void putWatch(const WatchEntry &e) override { if (!w.count(e.key)) wOrder << e.key; w[e.key] = e; }
    qint64 trackAt(const QString &key) override { auto it = t.find(key); return it == t.end() ? 0 : it->second.at; }
    void putTrack(const TrackEntry &e) override { if (!t.count(e.key)) tOrder << e.key; t[e.key] = e; }
    QList<WatchEntry> allWatch() override { QList<WatchEntry> out; for (const auto &k : wOrder) out << w[k]; return out; }
    QList<TrackEntry> allTracks() override { QList<TrackEntry> out; for (const auto &k : tOrder) out << t[k]; return out; }
    std::optional<QString> cursor() override { return c; }
    void setCursor(const std::optional<QString> &v) override { c = v; }
};

QStringList keys(const WatchSync::Sent &s) {
    QStringList out;
    for (const auto &v : s.body.value(S("watch")).toArray()) out << v.toObject().value(S("key")).toString();
    return out;
}

qint64 since(const WatchSync::Sent &s) { return s.body.value(S("since")).toInteger(); }
} // namespace

class WatchSyncTest : public QObject {
    Q_OBJECT
private slots:
    void firstSyncUploadsEverythingThenOnlyNew() {
        MemStore store;
        store.putWatch({S("1|a.mkv"), 10000, 100000, 50});
        store.putWatch({S("2|b.mkv"), 20000, 100000, 90});
        WatchSync sync(store);
        const auto first = sync.request(S("u_1"));
        QCOMPARE(since(first), 0);
        QCOMPARE(keys(first), (QStringList{S("2|b.mkv"), S("1|a.mkv")})); // свежее первым
        sync.onResponse(first, json("{\"now\":1000,\"watch\":[],\"tracks\":[]}"));
        QVERIFY(!sync.hasPending());
        QVERIFY(store.c->startsWith(S("u_1|1000|")));

        sync.noteWatch({S("3|c.mkv"), 5000, 0, 1100});
        const auto next = sync.request(S("u_1"));
        QCOMPARE(since(next), 1000);
        QCOMPARE(next.body.value(S("watch")).toArray().size(), 1);
        QCOMPARE(since(sync.request(S("u_2"))), 0); // другой аккаунт — снова всё
    }

    void newerRemoteWinsOlderIsIgnored() {
        MemStore store;
        store.c = S("u_1|10");
        store.putWatch({S("1|a.mkv"), 10000, 100000, 500});
        WatchSync sync(store);
        const auto sent = sync.request(S("u_1"));
        const int n = sync.onResponse(sent, json("{\"now\":20,\"watch\":["
                                                 "{\"key\":\"1|a.mkv\",\"pos\":90000,\"len\":100000,\"at\":400},"
                                                 "{\"key\":\"2|b.mkv\",\"pos\":30000,\"len\":0,\"at\":700}],"
                                                 "\"tracks\":[{\"key\":\"m2\",\"audio\":\"Дубляж [Russian]\",\"spu\":null,\"sidecar\":null,\"at\":700}]}"));
        QCOMPARE(n, 2);
        QCOMPARE(store.w[S("1|a.mkv")].pos, 10000); // своё новее
        QCOMPARE(store.w[S("2|b.mkv")].pos, 30000);
        QCOMPARE(store.t[S("m2")].audio.value(), S("Дубляж [Russian]"));
        QVERIFY(!store.t[S("m2")].spu);
    }

    void replacedWhileSendingStaysQueued() {
        MemStore store;
        store.c = S("u_1|10");
        WatchSync sync(store);
        sync.noteWatch({S("1|a.mkv"), 10000, 0, 100});
        const auto sent = sync.request(S("u_1"));
        sync.noteWatch({S("1|a.mkv"), 20000, 0, 200});
        sync.onResponse(sent, json("{\"now\":30,\"watch\":[{\"key\":\"1|a.mkv\",\"pos\":10000,\"len\":0,\"at\":100}]}"));
        QVERIFY(sync.hasPending());
        QVERIFY(!store.w.count(S("1|a.mkv")));
    }

    void unsentQueueSurvivesRestart() {
        MemStore store;
        WatchSync sync(store);
        sync.onResponse(sync.request(S("u_1")), json("{\"now\":1000,\"watch\":[],\"tracks\":[]}"));
        const qint64 mark = store.c->split(QLatin1Char('|'))[2].toLongLong();
        store.putWatch({S("1|a.mkv"), 10000, 100000, mark + 5});
        store.putWatch({S("2|old.mkv"), 10000, 100000, mark - 5}); // старее границы: уже ушло раньше
        store.putTrack({S("m1"), S("A"), std::nullopt, std::nullopt, std::nullopt, std::nullopt, mark + 6});
        WatchSync after(store);
        const auto sent = after.request(S("u_1"));
        QCOMPARE(since(sent), 1000);
        QCOMPARE(keys(sent), (QStringList{S("1|a.mkv")}));
        QCOMPARE(sent.body.value(S("tracks")).toArray()[0].toObject().value(S("key")).toString(), S("m1"));
    }

    void oldCursorWithoutMarkReloadsStore() {
        MemStore store;
        store.c = S("u_1|10");
        store.putWatch({S("1|a.mkv"), 10000, 0, 50});
        WatchSync sync(store);
        QCOMPARE(keys(sync.request(S("u_1"))), (QStringList{S("1|a.mkv")}));
    }

    void cursorWaitsForLastBatch() {
        MemStore store;
        for (int i = 0; i < WatchSync::BATCH + 3; ++i) store.putWatch({S("%1|a.mkv").arg(i), 10000, 0, 100 + i});
        WatchSync sync(store);
        const auto first = sync.request(S("u_1"));
        QVERIFY(first.more);
        sync.onResponse(first, json("{\"now\":1000}"));
        QVERIFY(!store.c);
        const auto second = sync.request(S("u_1"));
        QCOMPARE(since(second), 0);
        QCOMPARE(second.body.value(S("watch")).toArray().size(), 3);
        QVERIFY(!second.more);
        sync.onResponse(second, json("{\"now\":1100}"));
        QVERIFY(store.c->startsWith(S("u_1|1100|")));
        QVERIFY(!sync.hasPending());
    }

    void cursorMarkStaysWhileQueueNotDrained() {
        MemStore store;
        store.c = S("u_1|10|77");
        WatchSync sync(store);
        const auto sent = sync.request(S("u_1"));
        sync.noteWatch({S("1|a.mkv"), 10000, 0, 5}); // заметили, пока шёл запрос
        sync.onResponse(sent, json("{\"now\":20}"));
        QCOMPARE(store.c.value(), S("u_1|20|77"));
    }

    void remoteWinDropsStalePending() {
        MemStore store;
        store.c = S("u_1|10|0");
        WatchSync sync(store);
        sync.noteTrack({S("m5"), S("ru"), std::nullopt, std::nullopt, std::nullopt, std::nullopt, 100});
        sync.noteWatch({S("1|a.mkv"), 1000, 0, 100});
        WatchSync other(store);
        const auto sent = other.request(S("u_1")); // чужой запрос, чтобы в ответе пришло новее нашего
        sync.onResponse(sent, json("{\"now\":30,\"watch\":[{\"key\":\"1|a.mkv\",\"pos\":9000,\"len\":0,\"at\":200}],"
                                   "\"tracks\":[{\"key\":\"m5\",\"audio\":\"orig\",\"spu\":null,\"sidecar\":null,\"at\":200}]}"));
        QCOMPARE(store.w[S("1|a.mkv")].pos, 9000);
        QVERIFY(!sync.hasPending());
        sync.noteTrack({S("m5"), std::nullopt, S("sub"), std::nullopt, std::nullopt, std::nullopt, 300});
        const QJsonObject t = sync.request(S("u_1")).body.value(S("tracks")).toArray()[0].toObject();
        QVERIFY(t.value(S("audio")).isNull());
        QCOMPARE(t.value(S("spu")).toString(), S("sub"));
    }

    void trackChoicesMergeAndKeys() {
        MemStore store;
        store.c = S("u_1|10");
        WatchSync sync(store);
        sync.noteTrack({S("m5"), S("A"), std::nullopt, std::nullopt, std::nullopt, std::nullopt, 100});
        sync.noteTrack({S("m5"), std::nullopt, S("__off__"), std::nullopt, std::nullopt, std::nullopt, 120});
        const QJsonObject t = sync.request(S("u_1")).body.value(S("tracks")).toArray()[0].toObject();
        QCOMPARE(t.value(S("audio")).toString(), S("A"));
        QCOMPARE(t.value(S("spu")).toString(), S("__off__"));
        QCOMPARE(t.value(S("at")).toInteger(), 120);
        QCOMPARE(WatchSync::filmKeyOf(S("42|Season 1/E01.mkv")).value(), S("m42"));
        QVERIFY(!WatchSync::filmKeyOf(S("https://x/y")));
        QVERIFY(WatchSync::eligible(S("u_123")));
        QVERIFY(!WatchSync::eligible(S("shared")));
        QVERIFY(!WatchSync::eligible(std::nullopt));
    }
};

QTEST_APPLESS_MAIN(WatchSyncTest)
#include "WatchSyncTest.moc"
