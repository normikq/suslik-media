#include "suslik/core/Changelog.h"
#include "suslik/core/PlaybackPresets.h"
#include "suslik/core/TimeText.h"
#include "suslik/core/Versions.h"
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
};

QTEST_APPLESS_MAIN(CoreTest)
#include "CoreTest.moc"
