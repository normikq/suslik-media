#include "UrlScheme.h"

#include <QCoreApplication>
#include <QDir>
#include <QSettings>

namespace suslik::app::UrlScheme {

void registerSelf() {
#if defined(Q_OS_WIN) && !defined(SM_DIAG)
    const QString exe = QDir::toNativeSeparators(QCoreApplication::applicationFilePath());
    const QString command = QStringLiteral("\"%1\" \"%2\"").arg(exe, QStringLiteral("%1"));
    QSettings reg(QStringLiteral("HKEY_CURRENT_USER\\Software\\Classes\\suslik"), QSettings::NativeFormat);
    if (reg.value(QStringLiteral("shell/open/command/Default")).toString() == command) return;
    reg.setValue(QStringLiteral("Default"), QStringLiteral("URL:Suslik Media"));
    reg.setValue(QStringLiteral("URL Protocol"), QString());
    reg.setValue(QStringLiteral("DefaultIcon/Default"), QStringLiteral("\"%1\",0").arg(exe));
    reg.setValue(QStringLiteral("shell/open/command/Default"), command);
    reg.sync();
#endif
}

} // namespace suslik::app::UrlScheme
