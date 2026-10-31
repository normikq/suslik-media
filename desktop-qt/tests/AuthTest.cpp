// Перенос shared/src/test/.../AuthTest.kt.
#include "suslik/core/Auth.h"
#include "TestUtil.h"

using namespace suslik::core;
using namespace testutil;

class AuthTest : public QObject {
    Q_OBJECT
private slots:
    void parsesSession() {
        const auto s = SessionInfo::parse(json("{\"authenticated\":true,\"discord_client_id\":\"123\","
                                               "\"user\":{\"id\":\"9\",\"name\":null,\"username\":\"anya\",\"avatar\":\"https://cdn/a.png\",\"role\":\"admin\"}}"));
        QVERIFY(s.authenticated);
        QCOMPARE(s.discordClientId.value(), S("123"));
        QCOMPARE(s.user->name, S("anya")); // name: null → username
        QVERIFY(s.user->isAdmin());
        QVERIFY(!s.user->isShared());
        QCOMPARE(s.user->avatarUrl.value(), S("https://cdn/a.png"));

        const auto shared = SessionInfo::parse(json("{\"authenticated\":true,\"discord_client_id\":\"\",\"user\":{\"id\":\"shared\",\"role\":\"admin\"}}"));
        QVERIFY(shared.user->isShared());
        QVERIFY(!shared.discordClientId);

        QVERIFY(!SessionInfo::parse(json("{\"authenticated\":false,\"user\":{\"id\":\"9\"}}")).user);
        QVERIFY(!UserProfile::parse(nullptr));
    }

    void deviceFlow() {
        QCOMPARE(DeviceAuth::startBody(S("Suslik Media Windows"), S("https://site/media/")).value(S("site_url")).toString(), S("https://site/media"));

        const auto p = DeviceAuth::parsePairing(json("{\"code\":\"secret\",\"user_code\":\"ABC123\",\"verification_url\":\"https://s/link?code=ABC123\"}"), S("https://x/"));
        QCOMPARE(p->url, S("https://s/link?code=ABC123"));
        const auto old = DeviceAuth::parsePairing(json("{\"code\":\"secret\",\"user_code\":\"ABC123\"}"), S("https://x/media/"));
        QCOMPARE(old->url, S("https://x/media/link?code=ABC123"));
        QCOMPARE(DeviceAuth::pollBody(*old).value(S("code")).toString(), S("secret"));
        QVERIFY(!DeviceAuth::parsePairing(json("{\"user_code\":\"ABC123\"}"), S("https://x/")));

        const auto ok = DeviceAuth::parsePoll(json("{\"status\":\"authorized\",\"device_token\":\"t\",\"user\":{\"id\":\"9\",\"name\":\"A\",\"role\":\"user\"}}"));
        QVERIFY(ok.status == DevicePoll::Status::Authorized);
        QCOMPARE(ok.token.value(), S("t"));
        QCOMPARE(ok.user->name, S("A"));
        QVERIFY(DeviceAuth::parsePoll(json("{\"status\":\"pending\"}")).status == DevicePoll::Status::Pending);
        QVERIFY(DeviceAuth::parsePoll(json("{\"status\":\"expired\"}")).status == DevicePoll::Status::Expired);
        QVERIFY(DeviceAuth::pollFailed(410).status == DevicePoll::Status::Expired);
        QVERIFY(DeviceAuth::pollFailed(404).status == DevicePoll::Status::Expired);
        QVERIFY(DeviceAuth::pollFailed(502).status == DevicePoll::Status::Pending);
    }

    void credentialsOnlyToOwnServer() {
        const QString base = S("https://site.example");
        QVERIFY(DeviceAuth::isOwnServer(base, base));
        QVERIFY(DeviceAuth::isOwnServer(base + S("/api/session"), base));
        QVERIFY(!DeviceAuth::isOwnServer(S("https://site.example.evil.com/x"), base));
        QVERIFY(!DeviceAuth::isOwnServer(S("https://cdn.discordapp.com/avatars/1.png"), base));
    }

    void shelfBody() {
        const QJsonObject b = Shelf::body(42, true);
        QCOMPARE(b.value(S("id")).toInteger(), 42);
        QVERIFY(b.value(S("mine")).toBool());
    }
};

QTEST_APPLESS_MAIN(AuthTest)
#include "AuthTest.moc"
