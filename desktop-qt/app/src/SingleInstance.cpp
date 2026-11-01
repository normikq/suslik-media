#include "SingleInstance.h"
#include "AppDir.h"

#include <QCryptographicHash>
#include <QLocalServer>
#include <QLocalSocket>
#include <QLockFile>
#include <QThread>

namespace suslik::app {

namespace {
/// Имя сокета: своё на пользователя и папку данных (отладочная сборка — своя копия).
QString socketName() {
    return QStringLiteral("suslik-media-qt-") + QString::fromLatin1(QCryptographicHash::hash(AppDir::dir().toUtf8(), QCryptographicHash::Sha1).toHex().left(12));
}
} // namespace

SingleInstance::SingleInstance(QObject *parent) : QObject(parent) {}

SingleInstance::~SingleInstance() {
    delete m_server;
    delete m_lock;
}

bool SingleInstance::acquire(const QString &link) {
    m_lock = new QLockFile(AppDir::file(QStringLiteral("instance.lock")));
    m_lock->setStaleLockTime(0); // замок мёртвой копии снимается (QLockFile проверяет, жив ли процесс)
    if (!m_lock->tryLock(100)) {
        if (m_lock->error() != QLockFile::LockFailedError) return true; // папка не пишется: запуск не запрещаем
        // Другая копия держит замок: разбудить её. Сервер может ещё подниматься — несколько попыток.
        for (int i = 0; i < 10; ++i) {
            QLocalSocket s;
            s.connectToServer(socketName());
            if (s.waitForConnected(500)) {
                // Ссылка — одной строкой: «open <ссылка>»; переводы строк из неё выкидываются
                QString l = link;
                l.remove(QLatin1Char('\n')).remove(QLatin1Char('\r'));
                s.write(l.isEmpty() ? QByteArray("show\n") : "open " + l.left(512).toUtf8() + "\n");
                s.waitForBytesWritten(1000);
                s.disconnectFromServer();
                return false;
            }
            QThread::msleep(200);
        }
        return false;
    }
    m_server = new QLocalServer;
    QLocalServer::removeServer(socketName());
    m_server->setSocketOptions(QLocalServer::UserAccessOption);
    if (m_server->listen(socketName())) {
        connect(m_server, &QLocalServer::newConnection, this, [this] {
            while (QLocalSocket *c = m_server->nextPendingConnection()) {
                auto checkRead = [this, c] {
                    while (c->canReadLine()) {
                        const QByteArray line = c->readLine(1024).trimmed();
                        if (line == "show") emit showRequested();
                        else if (line.startsWith("open ")) {
                            emit showRequested();
                            emit linkRequested(QString::fromUtf8(line.mid(5)));
                        }
                    }
                };
                connect(c, &QLocalSocket::readyRead, this, checkRead);
                connect(c, &QLocalSocket::disconnected, c, &QObject::deleteLater);
                if (c->canReadLine()) checkRead();
            }
        });
    }
    return true;
}

} // namespace suslik::app
