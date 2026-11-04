#include "suslik/core/Changelog.h"
#include "suslik/core/PlaybackPresets.h"
#include "suslik/core/TimeText.h"
#include "suslik/core/Versions.h"
#include "suslik/core/UrlCoding.h"
#include <QTest>

using namespace suslik::core;
#define S QStringLiteral

class CoreTest : public QObject {
    Q_OBJECT
    const QByteArray json = R"([
      {"version":"1.9.0","title":"A","items":["a1"]},
      {"version":"2.0.0","title":"B","items":["b1","b2"]},
      {"version":"1.8.0","title":"C","items":["c1"]},
      {"version":"1.7.0","title":"D","items":[]}
    ])";

    static QStringList versions(const QList<Changelog::Release> &l) {
        QStringList out;
        for (const auto &r : l) out << r.version;
        return out;
    }
private slots:
    void compareVersions() {
        QVERIFY(Versions::isNewer(S("1.10.0"), S("1.9.5")));
        QVERIFY(!Versions::isNewer(S("1.9.5"), S("1.9.5")));
        QVERIFY(Versions::isNewer(S("2.0.0"), S("1.99.99")));
        QVERIFY(Versions::isNewer(S("2.0.0.1"), S("2.0.0")));
        QVERIFY(!Versions::isNewer(S("2.0.0.1"), S("2.0.1")));
        QVERIFY(Versions::isNewer(S("2.0.1"), S("2.0.0.1")));
        QCOMPARE(Versions::compare(S("1.2"), S("1.2.1")), -1);
    }

    void channelVerdict() {
        using V = UpdateChannel::Verdict;
        QCOMPARE(UpdateChannel::verdict(S("2.0.1"), S("2.0.0"), false), V::Offer);
        QCOMPARE(UpdateChannel::verdict(S("2.0.1"), S("2.0.1"), true), V::Installed);
        QCOMPARE(UpdateChannel::verdict(S("2.0.0"), S("2.0.1"), false), V::Older);
        QCOMPARE(UpdateChannel::verdict(S("1.9.5"), S("2.0.3"), true), V::Replace);
        QCOMPARE(UpdateChannel::verdict(QString(), S("2.0.1"), true), V::Nothing);
        QCOMPARE(UpdateChannel::verdict(S("2.0.0.1"), S("2.0.0"), false), V::Offer);
        QCOMPARE(UpdateChannel::verdict(S("2.0.0"), S("2.0.0.1"), true), V::Replace);
        QCOMPARE(UpdateChannel::message(V::Offer, S("2.0.1"), S("2.0.0"), true), S("Доступна версия 2.0.1 (бета)."));
    }

    void changelogPickAndOrder() {
        const auto all = Changelog::parse(json);
        QCOMPARE(all.size(), 3);
        QCOMPARE(versions(Changelog::upTo(all, S("1.9.5"))), (QStringList{S("1.9.0"), S("1.8.0")}));
        QCOMPARE(versions(Changelog::upTo(all, S("2.0.0"))), (QStringList{S("2.0.0"), S("1.9.0"), S("1.8.0")}));
    }

    void changelogShowOnlyWhenNewNotes() {
        const auto list = Changelog::upTo(Changelog::parse(json), S("1.9.5"));
        QVERIFY(Changelog::shouldShow(list, std::nullopt));
        QVERIFY(Changelog::shouldShow(list, S("1.8.0")));
        QVERIFY(!Changelog::shouldShow(list, S("1.9.0")));
        QVERIFY(!Changelog::shouldShow({}, std::nullopt));
    }

    void changelogBrokenJson() { QVERIFY(Changelog::parse("{oops").isEmpty()); }

    void clock() {
        QCOMPARE(clockText(303000), S("05:03"));
        QCOMPARE(clockText(3903000), S("1:05:03"));
        QCOMPARE(clockText(-5), S("00:00"));
        QCOMPARE(clockText(303999), S("05:03"));
    }

    void presetsClampAndValues() {
        using namespace PlaybackPresets;
        QCOMPARE(clampBurst(-5), 0);
        QCOMPARE(clampBurst(0), 0);
        QCOMPARE(clampBurst(96), 96);
        QCOMPARE(clampBurst(160), 160);
        QCOMPARE(clampBurst(256), 256);
        QCOMPARE(clampBurst(512), 256);
        QList<int> values;
        for (const auto &p : burst(S("на диске"))) values << p.value;
        QCOMPARE(values, (QList<int>{0, 32, 64, 96, 128, 160, 256}));
        QVERIFY(burst(S("на диске"))[0].hint.contains(S("без запаса на диске")));
    }

    void parseMagnetLinksTest() {
        // Обычная magnet-ссылка
        const QString single = S("magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709&dn=Movie+Title&tr=http://tracker.tld/announce");
        QCOMPARE(parseMagnetLinks(single), QStringList{single});

        // Ссылка с пробелами в названии и трекерами
        const QString withSpaces = S("magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709&dn=Movie Title 2024 1080p&tr=http://tracker.tld/announce");
        QCOMPARE(parseMagnetLinks(withSpaces), QStringList{withSpaces});

        // Ссылка в верхнем регистре MAGNET:?
        const QString upper = S("MAGNET:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709&dn=Test");
        QCOMPARE(parseMagnetLinks(upper), QStringList{S("magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709&dn=Test")});

        // Две ссылки через пробел
        const QString twoSpace = S("magnet:?xt=urn:btih:1111111111111111111111111111111111111111&dn=Film 1 1080p magnet:?xt=urn:btih:2222222222222222222222222222222222222222&dn=Film 2");
        const QStringList twoRes = parseMagnetLinks(twoSpace);
        QCOMPARE(twoRes.size(), 2);
        QCOMPARE(twoRes[0], S("magnet:?xt=urn:btih:1111111111111111111111111111111111111111&dn=Film 1 1080p"));
        QCOMPARE(twoRes[1], S("magnet:?xt=urn:btih:2222222222222222222222222222222222222222&dn=Film 2"));

        // Две ссылки через перевод строки
        const QString twoLines = S("magnet:?xt=urn:btih:1111111111111111111111111111111111111111\r\nmagnet:?xt=urn:btih:2222222222222222222222222222222222222222");
        QCOMPARE(parseMagnetLinks(twoLines).size(), 2);

        // Чистый 40-значный hex-хэш
        const QString rawHex = S("da39a3ee5e6b4b0d3255bfef95601890afd80709");
        QCOMPARE(parseMagnetLinks(rawHex), QStringList{S("magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709")});

        // Ссылка в кавычках и скобках
        QCOMPARE(parseMagnetLinks(S("<magnet:?xt=urn:btih:111>")), QStringList{S("magnet:?xt=urn:btih:111")});
        QCOMPARE(parseMagnetLinks(S("\"magnet:?xt=urn:btih:222\"")), QStringList{S("magnet:?xt=urn:btih:222")});

        // URL-encoded
        QCOMPARE(parseMagnetLinks(S("magnet%3A%3Fxt%3Durn%3Abtih%3A111")), QStringList{S("magnet:?xt=urn:btih:111")});

        // Пустой ввод
        QVERIFY(parseMagnetLinks(S("")).isEmpty());
        QVERIFY(parseMagnetLinks(S("   ")).isEmpty());
        QVERIFY(parseMagnetLinks(S("https://example.com/not-magnet")).isEmpty());
    }
};

QTEST_APPLESS_MAIN(CoreTest)
#include "CoreTest.moc"
