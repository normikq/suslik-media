#include "suslik/core/SiteAddress.h"

#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>

int main(int argc, char *argv[]) {
    QGuiApplication app(argc, argv);
    QGuiApplication::setApplicationName(QStringLiteral("Suslik Media"));
    QGuiApplication::setApplicationVersion(QStringLiteral(SM_VERSION));

    // Адрес сервера: из сборки (SITE_URL), для отладки можно переопределить переменной окружения SUSLIK_SITE.
    const QString site = suslik::core::SiteAddress::normalize(
        qEnvironmentVariableIsSet("SUSLIK_SITE") ? qEnvironmentVariable("SUSLIK_SITE") : QStringLiteral(SM_SITE_URL));

    QQmlApplicationEngine engine;
    engine.rootContext()->setContextProperty(QStringLiteral("appVersion"), QStringLiteral(SM_VERSION));
    engine.rootContext()->setContextProperty(QStringLiteral("siteConfigured"), !site.isEmpty());
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed, &app, [] { QCoreApplication::exit(1); },
                     Qt::QueuedConnection);
#if QT_VERSION >= QT_VERSION_CHECK(6, 5, 0)
    engine.loadFromModule("Suslik", "Main");
#else
    engine.load(QUrl(QStringLiteral("qrc:/Suslik/qml/Main.qml"))); // Qt 6.4 (тесты на Linux)
#endif
    return app.exec();
}
