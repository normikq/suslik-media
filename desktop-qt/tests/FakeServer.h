#pragma once
// Мини-сервер HTTP/1.1 для тестов сетевой части и ожидание ответа ApiClient.
#include "suslik/core/ApiClient.h"

#include <QTcpServer>
#include <QTcpSocket>
#include <QTest>
#include <optional>

namespace testutil {
using namespace suslik::core;

/// Мини-сервер HTTP/1.1: на каждый запрос — заданный ответ, запросы запоминаются.
class FakeServer : public QObject {
public:
    struct Request { QByteArray method, path; QHash<QByteArray, QByteArray> headers; QByteArray body; };
    QList<Request> requests;
    QByteArray response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{}";
    QTcpServer server;

    FakeServer() {
        server.listen(QHostAddress::LocalHost);
        connect(&server, &QTcpServer::newConnection, this, [this] {
            while (QTcpSocket *s = server.nextPendingConnection()) {
                auto *buf = new QByteArray;
                connect(s, &QTcpSocket::readyRead, s, [this, s, buf] {
                    *buf += s->readAll();
                    const auto end = buf->indexOf("\r\n\r\n");
                    if (end < 0) return;
                    Request r;
                    const QList<QByteArray> lines = buf->left(end).split('\n');
                    const QList<QByteArray> first = lines[0].trimmed().split(' ');
                    r.method = first.value(0);
                    r.path = first.value(1);
                    for (qsizetype i = 1; i < lines.size(); ++i) {
                        const auto c = lines[i].indexOf(':');
                        if (c > 0) r.headers.insert(lines[i].left(c).trimmed().toLower(), lines[i].mid(c + 1).trimmed());
                    }
                    const int len = r.headers.value("content-length").toInt();
                    if (buf->size() < end + 4 + len) return;
                    r.body = buf->mid(end + 4, len);
                    requests << r;
                    QByteArray resp = response;
                    const auto he = resp.indexOf("\r\n\r\n");
                    const QByteArray body = resp.mid(he + 4);
                    resp = resp.left(he) + "\r\nContent-Length: " + QByteArray::number(body.size()) + "\r\nConnection: close\r\n\r\n" + body;
                    s->write(resp);
                    s->disconnectFromHost();
                    delete buf;
                });
                connect(s, &QTcpSocket::disconnected, s, &QObject::deleteLater);
            }
        });
    }
    QString base() const { return QStringLiteral("http://127.0.0.1:%1").arg(server.serverPort()); }
};

inline ApiResult wait(const std::function<void(ApiClient::Callback)> &call) {
    std::optional<ApiResult> out;
    call([&](const ApiResult &r) { out = r; });
    // до 20 с: на Windows отказ в соединении приходит только через ~2 с на попытку (повторы SYN), и Qt пробует ещё раз
    for (int i = 0; i < 2000 && !out; ++i) QTest::qWait(10);
    return out.value_or(ApiResult{});
}
} // namespace testutil
