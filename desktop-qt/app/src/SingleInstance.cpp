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

bool SingleInstance::acquire() {
    m_lock = new QLockFile(AppDir::file(QStringLiteral("instance.lock")));
    m_lock->setStaleLockTime(0); // замок мёртвой копии снимается (QLockFile проверяет, жив ли процесс)
    if (!m_lock->tryLock(100)) {
        if (m_lock->error() != QLockFile::LockFailedError) return true; // папка не пишется: запуск не запрещаем
        // Другая копия держит замок: разбудить её. Сервер может ещё подниматься — несколько попыток.
        for (int i = 0; i < 10; ++i) {
            QLocalSocket s;
            s.connectToServer(socketName());
            if (s.waitForConnected(500)) {
                s.write("show\n");
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
                    if (c->canReadLine() && c->readLine().trimmed() == "show") emit showRequested();
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
