// RuTracker с ПК и выбор источников (desktop/.../trackers): разбор страниц и cookie, настройки.
#include "suslik/core/Store.h"
#include "suslik/core/Trackers.h"

#include <QJsonArray>
#include <QJsonObject>
#include <QTemporaryDir>
#include <QTest>

using namespace suslik::core;
#define S QStringLiteral

class TrackersTest : public QObject {
    Q_OBJECT
    QTemporaryDir dir;
private slots:
    void init() {
        Store::resetForTests();
        Store::setDirectory(dir.path());
    }

    void settingsDefaults() {
        QVERIFY(TrackerSettings::isEnabled(S("rutor")));        // по умолчанию включены
        QVERIFY(!TrackerSettings::isEnabled(S("rutracker")));   // RuTracker с ПК — нет
        TrackerSettings::setEnabled(S("tpb"), false);
        QVERIFY(!TrackerSettings::isProviderEnabled(S("The Pirate Bay")));
        QVERIFY(TrackerSettings::isProviderEnabled(S("Something new")));
        QVERIFY(TrackerSettings::isSourceEnabledForHit(S("Rutor + TPB")));   // хотя бы один включён
        QVERIFY(!TrackerSettings::isSourceEnabledForHit(S("TPB")));
        TrackerSettings::setOnlyRussian();
        QVERIFY(RuTracker::enabled());
        QVERIFY(!TrackerSettings::isEnabled(S("nyaa")));
        QCOMPARE(TrackerSettings::enabledCount(), 3);
        TrackerSettings::setAll(true);
        QCOMPARE(TrackerSettings::enabledCount(), 5);
        QCOMPARE(Store::of(S("local_trackers")).get(S("tracker.nyaa.enabled")).value_or(QString()), S("true"));
    }

    void urlAndCookie() {
        QCOMPARE(RuTracker::url(), S("https://rutracker.org"));
        RuTracker::setUrl(S(" https://rutracker.net/ "));
        QCOMPARE(RuTracker::url(), S("https://rutracker.net"));
        QString err;
        QCOMPARE(RuTracker::validateUrl(S("https://rutracker.org/"), &err), S("https://rutracker.org"));
        QVERIFY(RuTracker::validateUrl(S("http://rutracker.org"), &err).isEmpty());
        QVERIFY(!err.isEmpty());
        QVERIFY(RuTracker::validateUrl(S("https://evil.com"), &err).isEmpty());
        QCOMPARE(RuTracker::validateUrl(S("https://m.rutracker.nl")), S("https://m.rutracker.nl"));

        QCOMPARE(RuTracker::normalizeCookie(S("Cookie: bb_session=abc")), S("bb_session=abc; bb_ssl=1"));
        const auto map = RuTracker::parseCookieMap(S("bb_session=abc; empty=; bb_guid=g1; bb_session=xyz"));
        QCOMPARE(RuTracker::cookieHeader(map), S("bb_session=xyz; bb_guid=g1; bb_ssl=1"));
        QCOMPARE(RuTracker::cookieDomain(S("https://www.rutracker.org")), S("rutracker.org"));
        QCOMPARE(RuTracker::cookieDomain(S("https://m.rutracker.net")), S("rutracker.net"));
        QVERIFY(!RuTracker::isReady());
        RuTracker::setEnabled(true);
        RuTracker::setCookie(S(" bb_session=1 "));
        QVERIFY(RuTracker::isReady());
        QCOMPARE(RuTracker::cookie(), S("bb_session=1"));
    }

    void unescape() {
        QCOMPARE(RuTracker::unescapeHtml(S("Tom &amp; Jerry &quot;1&quot; &#1044;&#x44E;&nbsp;x")), S("Tom & Jerry \"1\" Дю x"));
    }

    void parsesTrackerPage() {
        const QString html = S(
            "<table id=\"tor-tbl\"><tr class=\"hl-tr\"><th>head</th></tr>"
            "<tr id=\"trs-tr-123\" class=\"tCenter hl-tr\">"
            "<td><a class=\"gen f\" href=\"tracker.php?f=313\">Зарубежное кино (HD Video)</a></td>"
            "<td><a class=\"med tLink\" href=\"viewtopic.php?t=123\">Дюна / Dune (2021) BDRip <b>1080p</b> &amp; Дубляж</a></td>"
            "<td class=\"row4 small nowrap tor-size\" data-ts_text=\"8589934592\"><a href=\"dl.php?t=123\">8 GB</a></td>"
            "<td class=\"row4 nowrap\" data-ts_text=\"1700000000\"><b class=\"seedmed\">42</b></td>"
            "<td class=\"row4 leechmed\">7</td>"
            "<td data-topic_hash=\"0123456789abcdef0123456789abcdef01234567\" data-ts_text=\"1700000000\">x</td></tr>"
            "<tr id=\"trs-tr-124\" class=\"tCenter\"><td><a class=\"gen f\" href=\"tracker.php?f=5\">Игры для ПК</a></td>"
            "<td><a class=\"tLink\" href=\"viewtopic.php?t=124\">Dune: Spice Wars</a></td></tr>"
            "<tr id=\"trs-tr-125\" class=\"tCenter\"><td><a class=\"gen f\" href=\"tracker.php?f=9\">Зарубежные сериалы</a></td>"
            "<td><a class=\"tLink\" href=\"viewtopic.php?t=125\">Дюна: Пророчество (Сезон 1) WEB-DL 1080p</a></td>"
            "<td><a href=\"magnet:?xt=urn:btih:ABC\">m</a></td></tr></table>");
        const auto hits = RuTracker::parseHtml(html);
        QCOMPARE(hits.size(), 2); // игра отсеяна
        const auto &h = hits[0];
        QCOMPARE(h.id, S("rt_123"));
        QCOMPARE(h.title, S("Дюна / Dune (2021) BDRip 1080p & Дубляж"));
        QCOMPARE(h.size, 8589934592LL);
        QCOMPARE(h.leechers, 7);
        QCOMPARE(h.kind, S("movie"));
        QCOMPARE(h.source, S("RuTracker"));
        QVERIFY(h.ru);
        QCOMPARE(h.topicId.value_or(QString()), S("123"));
        QCOMPARE(h.localTrackerId.value_or(QString()), S("rutracker"));
        QVERIFY(h.magnet.value_or(QString()).startsWith(S("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=")));
        QVERIFY(!h.date.isEmpty());
        QCOMPARE(hits[1].kind, S("series"));
        QCOMPARE(hits[1].magnet.value_or(QString()), S("magnet:?xt=urn:btih:ABC"));
    }

    void parsesBrowserJson() {
        const QString j = S(R"([{"topicId":"55","title":"Король Лев (1994) BDRip 1080p","forum":"Мультфильмы","sizeBytes":4294967296,"seeds":9,"leeches":1,"magnet":""},{"topicId":"x","title":"bad"},{"topicId":"56","title":"FIFA 24","forum":"Игры для PS5"}])");
        const auto hits = RuTracker::parseJsonResults(j);
        QCOMPARE(hits.size(), 1);
        QCOMPARE(hits[0].kind, S("cartoon"));
        QCOMPARE(hits[0].seeders, 9);
        QVERIFY(!hits[0].magnet);
        QCOMPARE(hits[0].size, 4294967296LL);
    }

    void browserCookies() {
        const QJsonArray arr{
            QJsonObject{{S("domain"), S(".rutracker.org")}, {S("name"), S("bb_session")}, {S("value"), S("s1")}},
            QJsonObject{{S("domain"), S(".google.com")}, {S("name"), S("NID")}, {S("value"), S("n")}},
            QJsonObject{{S("domain"), S("rutracker.org")}, {S("name"), S("cf_clearance")}, {S("value"), S("cf")}}};
        QCOMPARE(RuTracker::formatTrackerCookies(arr, S("https://rutracker.org")).value_or(QString()), S("bb_session=s1; cf_clearance=cf; bb_ssl=1"));
        QVERIFY(!RuTracker::formatTrackerCookies(QJsonArray{arr[1]}, S("https://rutracker.org")));
    }

    void firefoxCookies() {
        QByteArray db("SQLite format 3\0\0junk", 22);
        db += QByteArray(".rutracker.org\x01\x02") + "bb_session\x03" + "0-12345678-AbCdEfGhIjKl" + QByteArray(1, '\0') + "bb_guid\x05" + "GUID123";
        QString err;
        QCOMPARE(RuTracker::extractFirefoxCookies(db, S("rutracker"), &err).value_or(QString()), S("bb_session=0-12345678-AbCdEfGhIjKl; bb_guid=GUID123; bb_ssl=1"));
        QVERIFY(!RuTracker::extractFirefoxCookies("nothing here", S("rutracker"), &err));
        QVERIFY(err.contains(S("rutracker")));
        QVERIFY(!RuTracker::extractFirefoxCookies("rutracker.org only", S("rutracker"), &err));
        QVERIFY(err.contains(S("bb_session")));
    }

    void cp1251() {
        const QString s = S("Дюна №1 ёЁ «x»");
        QCOMPARE(Cp1251::decode(Cp1251::encode(s)), s);
        QCOMPARE(Cp1251::urlEncode(S("Дюна 2")), QByteArray("%C4%FE%ED%E0+2"));
    }

    void years() {
        QCOMPARE(RuTracker::yearOf(S("Дюна 2021")).value_or(QString()), S("2021"));
        QCOMPARE(RuTracker::withoutYear(S("Дюна 2021 часть")), S("Дюна часть"));
        QVERIFY(!RuTracker::yearOf(S("Дюна")));
    }
};

QTEST_GUILESS_MAIN(TrackersTest)
#include "TrackersTest.moc"
