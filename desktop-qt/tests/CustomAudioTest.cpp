// Перенос android/core/src/test/.../CustomAudioTest.kt и проверка LibraryKeep.
#include "suslik/core/CustomAudio.h"
#include "suslik/core/LibraryKeep.h"
#include "TestUtil.h"

using namespace suslik::core;
using namespace testutil;

namespace {
const QString url = S("https://media.test/api/file/proxy/Film.rus.mka?link=https%3A%2F%2Falldebrid.com%2Ff%2Fa&exp=1000&sig=ff");
QJsonObject list() {
    return json(QByteArray("{\"tracks\": ["
                           "{\"id\": \"t1\", \"videoPath\": \"Film/Film.mkv\", \"audioPath\": \"Dub/HDRezka.mka\", \"title\": \"HDRezka [rus]\", \"lang\": \"rus\","
                           " \"offsetMs\": -5000, \"mine\": true, \"url\": \"") + url.toUtf8() + "\", \"audioMagnetId\": 7},"
                           "{\"id\": \"t2\", \"videoPath\": \"Film/Film.mkv\", \"audioPath\": \"Dub/Other.MKA\", \"title\": \"\", \"lang\": \"und\", \"url\": \"https://x/Other.MKA\"},"
                           "{\"id\": \"t4\", \"videoPath\": \"Film/Film.mkv\", \"audioPath\": \"Dub/Bare.aac\", \"url\": \"https://x/Bare.aac\"},"
                           "{\"id\": \"t3\", \"videoPath\": \"Film/Film.mkv\", \"audioPath\": \"Dub/Broken.mka\", \"url\": \"\"}]}");
}
QStringList ids(const QList<CustomTrack> &t) {
    QStringList out;
    for (const auto &x : t) out << x.id;
    return out;
}
} // namespace

class CustomAudioTest : public QObject {
    Q_OBJECT
private slots:
    void readsTracks() {
        const auto t = CustomAudio::parseTracks(list());
        QCOMPARE(ids(t), (QStringList{S("t1"), S("t2")}));
        QCOMPARE(t[0].menuName(), S("HDRezka [rus]"));
        QCOMPARE(t[0].audioMagnetId.value(), 7);
        QVERIFY(!t[1].lang);
        QCOMPARE(t[1].menuName(), S("Other.MKA"));
        CustomTrack upper = t[0];
        upper.title = S("HDRezka [RUS]");
        QCOMPARE(upper.menuName(), S("HDRezka [rus]"));
    }

    void findsByVideoAndMenuName() {
        const auto t = CustomAudio::parseTracks(list());
        QCOMPARE(CustomAudio::forVideo(t, S("12|Film/Film.mkv")).size(), 2);
        QCOMPARE(CustomAudio::forVideo(t, S("12|Film/Other.mkv")).size(), 0);
        QCOMPARE(CustomAudio::forVideo(t, std::nullopt).size(), 0);
        QCOMPARE(CustomAudio::byMenuName(t, S("HDRezka [rus]"))->id, S("t1"));
        QCOMPARE(CustomAudio::byMenuName(t, S("Other.MKA [und]"))->id, S("t2"));
        QVERIFY(!CustomAudio::byMenuName(t, S("HDRezka")));

        // Season/Episode matching test: Season 1 Ep 1 must NOT match Season 2 Ep 1
        CustomTrack s1e1, s2e1;
        s1e1.id = S("s1e1");
        s1e1.videoPath = S("Season 1/S01E01.mkv");
        s1e1.audioPath = S("Dub/s1e1.mka");
        s1e1.url = S("https://x/s1e1.mka");
        s2e1.id = S("s2e1");
        s2e1.videoPath = S("Season 2/S02E01.mkv");
        s2e1.audioPath = S("Dub/s2e1.mka");
        s2e1.url = S("https://x/s2e1.mka");
        const QList<CustomTrack> seasonTracks{s1e1, s2e1};

        const auto foundS1 = CustomAudio::forVideo(seasonTracks, S("10|Season 1/S01E01.mkv"));
        QCOMPARE(foundS1.size(), 1);
        QCOMPARE(foundS1[0].id, S("s1e1"));

        const auto foundS2 = CustomAudio::forVideo(seasonTracks, S("10|Season 2/S02E01.mkv"));
        QCOMPARE(foundS2.size(), 1);
        QCOMPARE(foundS2[0].id, S("s2e1"));
    }

    void readsCheckAndAttach() {
        const auto c = CustomAudio::parseCheck(json("{\"movieMagnetId\": 12, \"pairs\": ["
                                                    "{\"videoPath\": \"v.mkv\", \"audioPath\": \"a.mka\", \"voice\": \"LostFilm\", \"season\": 1, \"episode\": null, \"offsetMs\": null}],"
                                                    "\"targetMovies\": [{\"id\": 12, \"name\": \"Dune 1\"}, {\"id\": 13, \"name\": \"Dune 2\"}]}"));
        QCOMPARE(c.movieMagnetId.value(), 12);
        QVERIFY(c.attachable);
        QCOMPARE(c.pairs[0].season.value(), 1);
        QVERIFY(!c.pairs[0].episode);
        QVERIFY(!c.pairs[0].offsetMs);
        QCOMPARE(c.targetMagnetIds, (QList<qint64>{12, 13}));
        QCOMPARE(c.targetMovieNames, (QStringList{S("Dune 1"), S("Dune 2")}));
        const auto a = CustomAudio::parseAttached(json("{\"created\": [{\"id\": \"a\"}], \"existing\": [{\"id\": \"b\"}]}"));
        QCOMPARE(a.size(), 2);
        QCOMPARE(a[0].value(S("id")).toString(), S("a"));
        QVERIFY(!a[0].value(S("existing")).toBool());
        QVERIFY(a[1].value(S("existing")).toBool());
    }

    void indexFindsByFileAndWrappedAddress() {
        CustomAudioIndex idx;
        idx.read(CustomAudio::infoJson(CustomAudio::parseTracks(list())));
        const QString fresh = QString(url).replace(S("exp=1000&sig=ff"), S("exp=2000&sig=aa"));
        QCOMPARE(idx.of(fresh)->offsetMs, -5000);
        QCOMPARE(idx.of(fresh)->title, S("HDRezka [rus]"));
        idx.noteWrapped(url, S("http://127.0.0.1:5000/s/1"));
        QCOMPARE(idx.of(S("http://127.0.0.1:5000/s/1"))->offsetMs, -5000);
        QVERIFY(!CustomAudio::infoJson({}));
    }

    void libraryKeep() {
        Magnet m = magnet(5, S("Film"), S("Ready"), 4, 0, 0, {file(0, S("Film.mkv"))});
        const LibraryKeep keep(prepareItems({m}));
        QVERIFY(keep.usable());
        QVERIFY(keep.keepsKey(S("5|Film.mkv")));
        QVERIFY(keep.keepsKey(S("w:5|Film.mkv")));
        QVERIFY(keep.keepsKey(S("5")));
        QVERIFY(!keep.keepsKey(S("6|Gone.mkv")));
        QVERIFY(keep.keepsKey(S("https://plain/link.mkv")));
        QVERIFY(keep.keepsPoster(S("film")));
        QVERIFY(keep.links.contains(S("https://host/f/0")));
        QVERIFY(!LibraryKeep({}).usable());
    }
};

QTEST_APPLESS_MAIN(CustomAudioTest)
#include "CustomAudioTest.moc"
