#include "suslik/core/ApiClient.h"
#include "suslik/core/Store.h"
#include "suslik/core/Strings.h"

#include "FakeServer.h"

#include <QTemporaryDir>
#include <QTest>

using namespace suslik::core;
using namespace testutil;
#define S QStringLiteral


class ApiClientTest : public QObject {
    Q_OBJECT
    QTemporaryDir dir;
private slots:
    void init() {
        Store::resetForTests();
        Store::setDirectory(dir.path());
        for (const QString &f : QDir(dir.path()).entryList(QDir::Files)) QFile::remove(dir.filePath(f));
        Strings::load(R"j({"api.oshibka_servera_code":"Ошибка сервера ({code})","api.net_svyazi_s_serverom":"Нет связи с сервером"})j");
    }

    void cookiesAndTokenGoOnlyToOwnServer() {
        FakeServer srv;
        srv.response = "HTTP/1.1 200 OK\r\nSet-Cookie: sid=abc; Path=/; HttpOnly\r\nSet-Cookie: dev=42; Max-Age=31536000\r\nETag: \"v1\"\r\n\r\n{\"ok\":true}";
        ApiClient api(srv.base());
        api.setDeviceToken(S("tok"));
        api.setBetaCodeProvider([] { return std::optional<QString>(S("beta")); });
        const ApiResult r = wait([&](auto cb) { api.get(S("/api/session"), cb); });
        QVERIFY(r.ok());
        QVERIFY(r.json.value(S("ok")).toBool());
        QCOMPARE(r.etag.value(), S("\"v1\""));
        QCOMPARE(api.cookieHeader(), S("dev=42; sid=abc"));

        wait([&](auto cb) { api.post(S("/api/sync"), QJsonObject{{S("since"), 0}}, cb); });
        const auto &req = srv.requests.last();
        QCOMPARE(req.method, QByteArray("POST"));
        QCOMPARE(req.path, QByteArray("/api/sync"));
        QCOMPARE(req.headers.value("cookie"), QByteArray("dev=42; sid=abc"));
        QCOMPARE(req.headers.value("x-device-token"), QByteArray("tok"));
        QCOMPARE(req.headers.value("x-beta-code"), QByteArray("beta"));
        QCOMPARE(req.body, QByteArray("{\"since\":0}"));

        // Чужой адрес (аватар с CDN): без cookie и токена
        QVERIFY(api.request(S("https://cdn.discordapp.com/a.png")).rawHeader("Cookie").isEmpty());
        QVERIFY(api.request(S("https://cdn.discordapp.com/a.png")).rawHeader("X-Device-Token").isEmpty());
    }

    void expiredCookieIsRemoved() {
        ApiClient api(S("https://site.test"));
        api.storeCookies({"sid=abc; Path=/", "dev=1"});
        api.storeCookies({"sid=; Max-Age=0", "dev=2; Expires=Thu, 01 Jan 1970 00:00:00 GMT"});
        QCOMPARE(api.cookieHeader(), QString());
    }

    void serverErrorUsesItsMessage() {
        FakeServer srv;
        srv.response = "HTTP/1.1 403 Forbidden\r\n\r\n{\"error\":\"Нужен вход\"}";
        ApiClient api(srv.base());
        ApiResult r = wait([&](auto cb) { api.get(S("/api/library"), cb); });
        QVERIFY(r.error.kind == ApiError::Kind::Http);
        QCOMPARE(r.error.code, 403);
        QCOMPARE(ApiClient::describe(r.error), S("Нужен вход"));
        srv.response = "HTTP/1.1 502 Bad Gateway\r\n\r\n<html>";
        r = wait([&](auto cb) { api.get(S("/api/library"), cb); });
        QCOMPARE(r.error.message, S("Ошибка сервера (502)"));
    }

    void notModifiedHasNoBody() {
        FakeServer srv;
        srv.response = "HTTP/1.1 304 Not Modified\r\n\r\n";
        ApiClient api(srv.base());
        const ApiResult r = wait([&](auto cb) { api.getIfChanged(S("/api/library"), S("\"v1\""), cb); });
        QVERIFY(r.ok());
        QCOMPARE(r.code, 304);
        QCOMPARE(srv.requests.last().headers.value("if-none-match"), QByteArray("\"v1\""));
    }

    void bytesTreatsMissingFileAsEmpty() {
        FakeServer srv;
        srv.response = "HTTP/1.1 404 Not Found\r\n\r\n";
        ApiClient api(srv.base());
        const ApiResult r = wait([&](auto cb) { api.bytes(S("/api/markers?x=1"), cb); });
        QVERIFY(r.ok());
        QVERIFY(r.bytes.isEmpty());
    }

    void noServerIsANetworkError() {
        // сервер принимает соединение и сразу закрывает его, не ответив. Закрытый порт не годится:
        // на Windows отказ приходит через секунды, а после других тестов соединение может зависнуть
        QTcpServer mute;
        mute.listen(QHostAddress::LocalHost);
        connect(&mute, &QTcpServer::newConnection, &mute, [&mute] {
            while (QTcpSocket *s = mute.nextPendingConnection()) { s->abort(); s->deleteLater(); }
        });
        ApiClient api(S("http://127.0.0.1:%1").arg(mute.serverPort()));
        const ApiResult r = wait([&](auto cb) { api.get(S("/api/session"), cb); });
        QVERIFY(r.error.kind == ApiError::Kind::Network);
        QCOMPARE(r.error.message, S("Нет связи с сервером"));
    }
};

QTEST_GUILESS_MAIN(ApiClientTest)
#include "ApiClientTest.moc"
