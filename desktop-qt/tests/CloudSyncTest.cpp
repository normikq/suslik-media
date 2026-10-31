// Прогресс и дорожки в хранилище ПК и круг синхронизации с сервером (desktop/.../CloudSync.kt).
#include "suslik/core/CloudSync.h"
#include "suslik/core/Store.h"

#include "FakeServer.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QSignalSpy>
#include <QTemporaryDir>

using namespace suslik::core;
using namespace testutil;
#define S QStringLiteral

class CloudSyncTest : public QObject {
    Q_OBJECT
    QTemporaryDir dir;
private slots:
    void init() {
        Store::resetForTests();
        Store::setDirectory(dir.path());
        for (const QString &f : QDir(dir.path()).entryList(QDir::Files)) QFile::remove(dir.filePath(f));
    }

    void oldTrackChoicesTakeTheLastWatchTime() {
        Store::of(S("watch")).put(S("42|a.mkv"), S("1000|9000|500"));
        Store::of(S("watch")).put(S("42|b.mkv"), S("1000|9000|800"));
        Store::of(S("watch")).put(S("7|c.mkv"), S("1000|9000|300"));
        Store::of(S("player_ui")).put(S("audio:m42"), S("Дубляж"));
        Store::of(S("player_ui")).put(S("spu:*"), S("__off__"));
        Store::of(S("player_ui")).put(S("audio:m99"), S("без просмотра — не уходит"));
        Store::of(S("player_ui")).put(S("audio:https://x"), S("не фильм"));
        DesktopSyncStore store;
        QHash<QString, TrackEntry> t;
        for (const auto &e : store.allTracks()) t.insert(e.key, e);
        QCOMPARE(t.size(), 2);
        QCOMPARE(t[S("m42")].at, 800);
        QCOMPARE(t[S("m42")].audio.value(), S("Дубляж"));
        QCOMPARE(t[S("*")].at, 800);
        QCOMPARE(t[S("*")].spu.value(), S("__off__"));
        QVERIFY(!store.watch(S("1|none")));
        QCOMPARE(store.watch(S("42|a.mkv"))->pos, 1000);
    }

    void roundTripStoresWhatCameFromOtherDevices() {
        FakeServer srv;
        srv.response = "HTTP/1.1 200 OK\r\n\r\n{\"now\":5000,\"watch\":[{\"key\":\"3|x.mkv\",\"pos\":42000,\"len\":90000,\"at\":4000}],"
                       "\"tracks\":[{\"key\":\"m3\",\"audio\":\"Оригинал\",\"spu\":null,\"sidecar\":null,\"at\":4000}]}";
        Store::of(S("watch")).put(S("1|a.mkv"), S("1000|9000|500"));
        ApiClient api(srv.base());
        CloudSync sync(api);
        QSignalSpy changed(&sync, &CloudSync::changed);
        sync.setUserId(S("u_123"));
        sync.start();
        QTRY_COMPARE_WITH_TIMEOUT(changed.size(), 1, 5000);
        QCOMPARE(changed.first().first().toInt(), 2);
        QCOMPARE(srv.requests.size(), 1);
        const QJsonObject body = QJsonDocument::fromJson(srv.requests.first().body).object();
        QCOMPARE(body.value(S("since")).toInteger(), 0);
        QCOMPARE(body.value(S("watch")).toArray().first().toObject().value(S("key")).toString(), S("1|a.mkv"));
        QCOMPARE(Store::of(S("watch")).get(S("3|x.mkv")).value(), S("42000|90000|4000"));
        QCOMPARE(Store::of(S("player_ui")).get(S("audio:m3")).value(), S("Оригинал"));
        QVERIFY(Store::of(S("sync")).get(S("cursor"))->startsWith(S("u_123|5000|")));
    }

    void sharedPasswordDoesNotSync() {
        FakeServer srv;
        ApiClient api(srv.base());
        CloudSync sync(api);
        sync.setUserId(S("shared"));
        sync.start();
        QTest::qWait(200);
        QCOMPARE(srv.requests.size(), 0);
    }
};

QTEST_GUILESS_MAIN(CloudSyncTest)
#include "CloudSyncTest.moc"
