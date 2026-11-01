#include "PlayerLog.h"
#include "../AppDir.h"

#include <QDateTime>
#include <QFile>
#include <QMutex>

namespace suslik::app::PlayerLog {

namespace {
QMutex lock;
constexpr qint64 MAX_BYTES = 4 << 20;
} // namespace

QString file() { return AppDir::file(QStringLiteral("player.log")); }

void write(const QString &line) {
    QMutexLocker g(&lock);
    const QString path = file();
    QFile f(path);
    if (f.size() > MAX_BYTES) {
        const QString old = AppDir::file(QStringLiteral("player.old.log"));
        QFile::remove(old);
        QFile::rename(path, old);
    }
    if (!f.open(QIODevice::Append | QIODevice::Text)) return;
    f.write(QDateTime::currentDateTime().toString(QStringLiteral("dd.MM HH:mm:ss.zzz ")).toUtf8() + line.toUtf8() + '\n');
}

} // namespace suslik::app::PlayerLog
