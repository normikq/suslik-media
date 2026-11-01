#include "DiscordRpc.h"
#include "PlayerLog.h"
#include "../Settings.h"

#include "suslik/core/Store.h"
#include "suslik/core/Strings.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QJsonDocument>
#include <QLocalSocket>
#include <QRegularExpression>
#include <QUuid>
#include <QtEndian>

namespace suslik::app {

namespace {
QString S(const char *s) { return QString::fromUtf8(s); }

QString clientId() {
    static const QRegularExpression digits(S("^\\d{15,25}$"));
    const QString id = core::Store::of(S("settings")).get(S("discord_client_id")).value_or(QString()).trimmed();
    return digits.match(id).hasMatch() ? id : QString();
}

/// Имя канала: на Windows — \\.\pipe\discord-ipc-N, в Linux — сокет в XDG_RUNTIME_DIR.
QString pipeName(int n) {
#ifdef Q_OS_WIN
    return S("discord-ipc-%1").arg(n);
#else
    QString dir = qEnvironmentVariable("XDG_RUNTIME_DIR");
    if (dir.isEmpty()) dir = QDir::tempPath();
    return QDir(dir).filePath(S("discord-ipc-%1").arg(n));
#endif
}

QString padded(const QString &s) {
    QString t = s.left(128);
    while (t.size() < 2) t += QLatin1Char(' ');
    return t;
}
} // namespace

DiscordRpc::~DiscordRpc() { closePipe(); }

void DiscordRpc::problem(const QString &text) {
    if (text == m_lastProblem) return;
    m_lastProblem = text;
    PlayerLog::write(S("[discord] ") + text);
}

void DiscordRpc::update(const Activity &a) {
    if (!PlaybackSettings::discordOn()) { stop(); return; }
    const QString id = clientId();
    if (id.isEmpty()) { problem(S("no Discord application id yet (the server sends it after sign-in)")); return; }
    m_want = a;
    if (m_sock && m_connectedAs == id) {
        if (m_ready) send(build(a));
        return;
    }
    closePipe();
    m_id = id;
    connectPipe(0);
}

void DiscordRpc::stop() {
    m_want.reset();
    if (m_sock && m_ready) {
        send(QJsonValue::Null);
        m_sock->flush();
    }
    closePipe();
}

void DiscordRpc::connectPipe(int n) {
    if (n > 9) { problem(S("Discord is not running (no discord-ipc pipe answered)")); closePipe(); return; }
    if (m_sock) { m_sock->disconnect(this); m_sock->abort(); m_sock->deleteLater(); }
    m_sock = new QLocalSocket(this);
    m_ready = false;
    m_buf.clear();
    connect(m_sock, &QLocalSocket::connected, this, [this, n] {
        m_connectedAs = m_id;
        writeFrame(0, QJsonObject{{S("v"), 1}, {S("client_id"), m_id}});
        PlayerLog::write(S("[discord] connected to %1").arg(pipeName(n)));
    });
    connect(m_sock, &QLocalSocket::errorOccurred, this, [this, n](QLocalSocket::LocalSocketError) {
        if (!m_connectedAs.isEmpty()) { problem(S("pipe closed: %1").arg(m_sock->errorString())); closePipe(); return; }
        QMetaObject::invokeMethod(this, [this, n] { connectPipe(n + 1); }, Qt::QueuedConnection);
    });
    connect(m_sock, &QLocalSocket::readyRead, this, &DiscordRpc::readFrames);
    m_sock->connectToServer(pipeName(n));
}

void DiscordRpc::writeFrame(int op, const QJsonObject &json) {
    if (!m_sock) return;
    const QByteArray payload = QJsonDocument(json).toJson(QJsonDocument::Compact);
    QByteArray header(8, 0);
    qToLittleEndian<qint32>(op, header.data());
    qToLittleEndian<qint32>(qint32(payload.size()), header.data() + 4);
    m_sock->write(header + payload);
}

void DiscordRpc::readFrames() {
    if (!m_sock) return;
    m_buf += m_sock->readAll();
    while (m_buf.size() >= 8) {
        const int op = qFromLittleEndian<qint32>(m_buf.constData());
        const int len = qFromLittleEndian<qint32>(m_buf.constData() + 4);
        if (len < 0 || len > 1000000) { problem(S("bad frame length %1: reconnecting").arg(len)); closePipe(); return; }
        if (m_buf.size() < 8 + len) return;
        const QJsonObject res = QJsonDocument::fromJson(m_buf.mid(8, len)).object();
        m_buf.remove(0, 8 + len);
        if (op == 2) { problem(S("closed by Discord: %1").arg(res.value(S("message")).toString())); closePipe(); return; }
        const QString evt = res.value(S("evt")).toString();
        if (!m_ready && evt == S("READY")) { onReady(); continue; }
        if (evt == S("ERROR")) {
            problem(S("SET_ACTIVITY error: %1").arg(res.value(S("data")).toObject().value(S("message")).toString()));
            // старый Discord принимает только «Играет»: тогда без типа «Смотрит»
            if (!m_triedPlain && m_want) {
                m_triedPlain = true;
                QJsonObject a = build(*m_want);
                a.remove(S("type"));
                send(a);
            }
        } else if (op == 1) m_lastProblem.clear();
    }
}

void DiscordRpc::onReady() {
    m_ready = true;
    m_lastProblem.clear();
    if (m_want) send(build(*m_want));
}

void DiscordRpc::send(const QJsonValue &activity) {
    writeFrame(1, QJsonObject{{S("cmd"), S("SET_ACTIVITY")},
                              {S("args"), QJsonObject{{S("pid"), qint64(QCoreApplication::applicationPid())}, {S("activity"), activity}}},
                              {S("nonce"), QUuid::createUuid().toString(QUuid::WithoutBraces)}});
}

void DiscordRpc::closePipe() {
    m_connectedAs.clear();
    m_ready = false;
    m_buf.clear();
    if (m_sock) {
        m_sock->disconnect(this);
        m_sock->abort();
        m_sock->deleteLater();
        m_sock = nullptr;
    }
}

QJsonObject DiscordRpc::build(const Activity &a) const {
    using core::tx;
    QJsonObject act{{S("type"), 3}, {S("name"), S("Suslik Media")}, {S("details"), padded(a.title)}};
    if (!a.subtitle.trimmed().isEmpty()) act.insert(S("state"), padded(a.subtitle));
    else if (!a.playing) act.insert(S("state"), tx(S("discord.pauza")));
    if (a.playing && a.lengthMs > 0) {
        const qint64 now = QDateTime::currentMSecsSinceEpoch();
        act.insert(S("timestamps"), QJsonObject{{S("start"), (now - a.timeMs) / 1000}, {S("end"), (now + std::max<qint64>(0, a.lengthMs - a.timeMs)) / 1000}});
    }
    QJsonObject assets;
    QString hover = a.largeText.trimmed();
    if (hover.isEmpty()) hover = a.title;
    if (!a.posterUrl.isEmpty()) {
        assets.insert(S("large_image"), a.posterUrl);
        assets.insert(S("large_text"), hover.left(128));
    } else {
        assets.insert(S("large_image"), S("brand-mark"));
        assets.insert(S("large_text"), S("Suslik Media"));
    }
    assets.insert(S("small_image"), a.playing ? S("play") : S("pause"));
    assets.insert(S("small_text"), a.playing ? tx(S("discord.vosproizvedenie")) : tx(S("discord.pauza")));
    act.insert(S("assets"), assets);
    return act;
}

} // namespace suslik::app
