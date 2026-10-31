#include "suslik/core/SiteAddress.h"
#include <QTest>

using namespace suslik::core;
#define S QStringLiteral

class SiteAddressTest : public QObject {
    Q_OBJECT
private slots:
    void normalizesOneSetting() {
        QCOMPARE(SiteAddress::normalize(S("https://site.test")), S("https://site.test"));
        QCOMPARE(SiteAddress::normalize(S(" https://Site.Test/ ")), S("https://site.test"));
        QCOMPARE(SiteAddress::normalize(S("site.test")), S("https://site.test"));
        QCOMPARE(SiteAddress::normalize(S("https://site.test/suslik/")), S("https://site.test/suslik"));
        QCOMPARE(SiteAddress::normalize(S("https://site.test//a/b//?x=1#y")), S("https://site.test/a/b"));
        QCOMPARE(SiteAddress::normalize(S("http://site.test")), S("https://site.test"));
        QCOMPARE(SiteAddress::normalize(S("http://localhost:3000/")), S("http://localhost:3000"));
        QCOMPARE(SiteAddress::normalize(S("")), QString());
        QCOMPARE(SiteAddress::normalize(QString()), QString());
        QCOMPARE(SiteAddress::normalize(S("https://")), QString());
        QCOMPARE(SiteAddress::normalize(S("https://user@site.test")), QString());
    }

    void hostAndPath() {
        QCOMPARE(SiteAddress::host(S("https://site.test/suslik")), S("site.test"));
        QCOMPARE(SiteAddress::path(S("https://site.test/suslik")), S("/suslik"));
        QCOMPARE(SiteAddress::path(S("https://site.test")), QString());
        QCOMPARE(SiteAddress::host(S("http://localhost:3000")), S("localhost:3000"));
    }

    void ownServerIsInsideThePath() {
        const QString base = S("https://site.test/suslik");
        QVERIFY(SiteAddress::isOwn(base + S("/api/library"), base));
        QVERIFY(SiteAddress::isOwn(base, base));
        QVERIFY(!SiteAddress::isOwn(S("https://site.test/other/api"), base));
        QVERIFY(!SiteAddress::isOwn(S("https://site.test/suslik-evil/x"), base));
        QVERIFY(!SiteAddress::isOwn(S("https://site.test.evil.com/suslik/x"), base));
        QVERIFY(!SiteAddress::isOwn(S("https://site.test/x"), QString()));
        QVERIFY(SiteAddress::isOwn(S("https://site.test/api/x"), S("https://site.test")));
        QVERIFY(SiteAddress::isOwn(S("https://site.test"), S("https://site.test")));
        QVERIFY(SiteAddress::isOwn(base + S("?x=1"), base));
        QVERIFY(SiteAddress::isOwn(base + S("/api/file/proxy/Film.Name..2024.mkv?link=a"), base));
    }

    void pathCannotLeaveTheBase() {
        const QString base = S("https://site.test/suslik");
        QVERIFY(!SiteAddress::isOwn(base + S("/../other/api"), base));
        QVERIFY(!SiteAddress::isOwn(base + S("/api/.."), base));
        QVERIFY(!SiteAddress::isOwn(base + S("/./../other"), base));
        QVERIFY(!SiteAddress::isOwn(base + S("/%2e%2e/other"), base));
        QVERIFY(!SiteAddress::isOwn(base + S("/%2E./other"), base));
        QVERIFY(!SiteAddress::isOwn(base + S("/a%2f..%2fb"), base));
        QVERIFY(!SiteAddress::isOwn(base + S("/..\\other"), base));
        QVERIFY(!SiteAddress::isOwn(base + S("/%zz"), base));
    }

    void schemeAndHostIgnoreCase() {
        const QString base = S("https://site.test/suslik");
        QVERIFY(SiteAddress::isOwn(S("HTTPS://Site.Test/suslik/api"), base));
        QVERIFY(!SiteAddress::isOwn(S("https://site.test/Suslik/api"), base));
        QVERIFY(!SiteAddress::isOwn(S("http://site.test/suslik/api"), base));
        QVERIFY(!SiteAddress::isOwn(S("https://site.test@evil.test/suslik/api"), base));
        QVERIFY(!SiteAddress::isOwn(S("https://evil.test/x?https://site.test/suslik/"), base));
        QVERIFY(!SiteAddress::isOwn(S("https://site.test:8443/suslik/api"), base));
        QVERIFY(!SiteAddress::isOwn(S("site.test/suslik/api"), base));
    }
};

QTEST_APPLESS_MAIN(SiteAddressTest)
#include "SiteAddressTest.moc"
