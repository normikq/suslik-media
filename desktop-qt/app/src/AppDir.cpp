#include "AppDir.h"

#include <QDir>
#include <QFile>
#include <QStandardPaths>

namespace suslik::app::AppDir {

namespace {
QString base() {
    const QString appData = qEnvironmentVariable("APPDATA");
    return appData.isEmpty() ? QDir::homePath() : appData;
}
QString name() {
#ifdef SM_DIAG
    return QStringLiteral("SuslikMediaQtDebug");
#else
    return QStringLiteral("SuslikMediaQt");
#endif
}
} // namespace

QString dir() {
    static const QString d = [] {
        const QString override = qEnvironmentVariable("SUSLIK_APPDIR"); // тесты и отладка
        QString path = !override.isEmpty() ? override
                       : qEnvironmentVariableIsSet("APPDATA") ? QDir(base()).filePath(name())
                                                              : QDir(base()).filePath(QLatin1Char('.') + name().toLower());
        QDir().mkpath(path);
        return path;
    }();
    return d;
}

QString file(const QString &n) { return QDir(dir()).filePath(n); }

int migrateFromKotlin() {
    const QString old = QDir(base()).filePath(QStringLiteral("SuslikMedia"));
    // Трекеры (RuTracker с ПК, выбор источников) появились позже первого переноса: их — отдельно, пока своих нет
    {
        const QString f = QStringLiteral("local_trackers.properties");
        const QString from = QDir(old).filePath(f), to = file(f);
        if (QFile::exists(from) && !QFile::exists(to)) QFile::copy(from, to);
    }
    if (QFile::exists(file(QStringLiteral("migrated.txt")))) return 0;
    int n = 0;
    if (QDir(old).exists()) {
        // Вход, прогресс, выбор дорожек, избранное на этом устройстве, настройки. Кэши (постеры, медиатека) скачаются заново.
        static const char *names[] = {"cookies", "auth", "watch", "player_ui", "sync", "last_episode", "continue_hidden", "ui",
                                      "settings", "audio_offsets", "audio_pending", "markers", "whatsnew"};
        for (const char *nm : names) {
            const QString f = QString::fromLatin1(nm) + QStringLiteral(".properties");
            const QString from = QDir(old).filePath(f), to = file(f);
            if (QFile::exists(from) && !QFile::exists(to) && QFile::copy(from, to)) ++n;
        }
    }
    QFile marker(file(QStringLiteral("migrated.txt")));
    if (marker.open(QIODevice::WriteOnly)) marker.write(QByteArray::number(n));
    return n;
}

} // namespace suslik::app::AppDir
