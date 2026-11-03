// Перенос TorrentInspectorTest.kt (shared) и BencodeTest.kt (desktop).
#include "suslik/core/TorrentInspector.h"

#include <QTest>

using namespace suslik::core;
#define S QStringLiteral

class TorrentInspectorTest : public QObject {
    Q_OBJECT
private slots:
    void videoInListOk() { QVERIFY(!TorrentInspector::rejectionForPaths({S("Film/readme.txt"), S("Film/movie.MKV")})); }

    void noVideoRejectedWithExtensions() {
        const auto r = TorrentInspector::rejectionForPaths({S("Game/setup.exe"), S("Game/data.bin"), S("Game/readme.txt")});
        QVERIFY(r);
        QCOMPARE(*r, S("Отклонено: в раздаче нет видеофайлов (найдены: .exe, .bin, .txt)"));
    }

    void emptyListNotJudged() { QVERIFY(!TorrentInspector::rejectionForPaths({})); }

    void torrentBytes() {
        QVERIFY(TorrentInspector::rejectionForTorrent("d4:infod4:name4:game5:filesld6:lengthi9e4:pathl9:setup.exeeeeee"));
        QVERIFY(!TorrentInspector::rejectionForTorrent("d4:infod6:lengthi1e4:name9:video.mkvee"));
        QVERIFY(!TorrentInspector::rejectionForTorrent(QString(S("мусор")).toUtf8()));
    }

    void magnetName() {
        QCOMPARE(TorrentInspector::rejectionForMagnetName(S("setup.exe")).value(), S("Отклонено: не является видеофайлом (.exe)"));
        QVERIFY(!TorrentInspector::rejectionForMagnetName(S("Some.Movie.2020.1080p")));
    }

    void singleFile() {
        const auto files = TorrentInspector::extractFiles("d4:infod6:lengthi123456e4:name9:video.mkvee");
        QCOMPARE(files.size(), 1);
        QCOMPARE(files[0].path, S("video.mkv"));
        QCOMPARE(files[0].size, 123456);
        QVERIFY(TorrentInspector::hasVideoFiles(files));
    }

    void multiFile() {
        const auto files = TorrentInspector::extractFiles("d4:infod4:name4:game5:filesld6:lengthi999e4:pathl9:setup.exeeed6:lengthi111e4:pathl7:doc.txteeeee");
        QCOMPARE(files.size(), 2);
        QCOMPARE(files[0].path, S("setup.exe"));
        QCOMPARE(files[1].path, S("doc.txt"));
        QVERIFY(!TorrentInspector::hasVideoFiles(files));
    }

    void pathUtf8() {
        const auto files = TorrentInspector::extractFiles(QString(S("d4:infod4:name4:test5:filesld6:lengthi500e10:path.utf-8l14:фильм.mkveeeee")).toUtf8());
        QCOMPARE(files.size(), 1);
        QCOMPARE(files[0].path, S("фильм.mkv"));
        QCOMPARE(files[0].size, 500);
    }

    void corruptedDoesNotThrow() {
        const auto i = TorrentInspector::inspect("d4:infod6:lengthi123e");
        QVERIFY(!i.isParsedSuccessfully);
        QVERIFY(!i.hasVideo);
        QCOMPARE(i.files.size(), 0);
    }

    void brokenInsideClosedDictIsRefused() {
        // строка длиннее остатка, длина не число, ключ без значения, число без «e» — ошибка, а не пустые узлы
        QVERIFY(TorrentInspector::inspect("d4:infod6:lengthi1e4:name9:video.mkvee").isParsedSuccessfully);
        QVERIFY(!TorrentInspector::inspect("d4:infod6:lengthi1e4:name99:video.mkvee").isParsedSuccessfully);
        QVERIFY(!TorrentInspector::inspect("d4:infod6:lengthi1e4:namex:video.mkvee").isParsedSuccessfully);
        QVERIFY(!TorrentInspector::inspect("d4:infod6:lengthi1e4:namee4:infoe").isParsedSuccessfully);
        QVERIFY(!TorrentInspector::inspect("d4:infod6:lengthi1x").isParsedSuccessfully);
        QVERIFY(!TorrentInspector::inspect("d4:infod6:lengthi1").isParsedSuccessfully);
    }

    void deepNestingIsRefused() {
        QVERIFY(!TorrentInspector::inspect(QByteArray(100, 'l') + QByteArray(100, 'e')).isParsedSuccessfully);
    }
};

QTEST_APPLESS_MAIN(TorrentInspectorTest)
#include "TorrentInspectorTest.moc"
