#include "suslik/core/MirrorLink.h"
#include "suslik/core/UrlCoding.h"
#include <QTest>

using namespace suslik::core;
#define S QStringLiteral

class MirrorLinkTest : public QObject {
    Q_OBJECT
    const QString url = S("https://media.test/api/file/proxy/Show%20S01E02.mkv?link=https%3A%2F%2Falldebrid.com%2Ff%2Fabc&exp=1000&sig=ff");
private slots:
    void readsTheSignedAddress() {
        QCOMPARE(MirrorLink::param(url, S("link")).value(), S("https://alldebrid.com/f/abc"));
        QCOMPARE(MirrorLink::expiresAt(url).value(), qint64(1000));
        QCOMPARE(MirrorLink::fileName(url), S("Show S01E02.mkv"));
    }

    void expiresWithAMargin() {
        QVERIFY(MirrorLink::isExpired(url, 1000));
        QVERIFY(MirrorLink::isExpired(url, 950));
        QVERIFY(!MirrorLink::isExpired(url, 900));
        QVERIFY(!MirrorLink::isExpired(S("https://media.test/film.mkv"), 5000));
    }

    void renewPathAsksForTheSameFile() {
        QCOMPARE(MirrorLink::renewPath(url).value(), S("/api/file/mirror-url?link=https%3A%2F%2Falldebrid.com%2Ff%2Fabc&name=Show+S01E02.mkv"));
        QVERIFY(!MirrorLink::renewPath(S("https://media.test/film.mkv?exp=1")).has_value());
    }

    void fileKeyIgnoresTheSignature() {
        QCOMPARE(MirrorLink::fileKey(url), S("https://media.test/api/file/proxy/Show%20S01E02.mkv?link=https%3A%2F%2Falldebrid.com%2Ff%2Fabc"));
        QCOMPARE(MirrorLink::fileKey(S("https://x.test/a.mkv")), S("https://x.test/a.mkv"));
    }

    void formCodingMatchesJava() {
        QCOMPARE(formEncode(S("Фильм 1*~.mkv")), S("%D0%A4%D0%B8%D0%BB%D1%8C%D0%BC+1*%7E.mkv"));
        QCOMPARE(formDecode(S("%D0%A4+a")).value(), S("Ф a"));
        QVERIFY(!formDecode(S("%zz")).has_value());
        QVERIFY(!formDecode(S("abc%2")).has_value());
    }
};

QTEST_APPLESS_MAIN(MirrorLinkTest)
#include "MirrorLinkTest.moc"
