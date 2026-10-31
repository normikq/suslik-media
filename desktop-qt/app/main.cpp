#include "src/AppDir.h"
#include "src/ImageProvider.h"
#include "src/Library.h"
#include "src/LibraryPage.h"
#include "src/SingleInstance.h"
#include "src/Ui.h"

#include "suslik/core/ApiClient.h"
#include "suslik/core/CloudSync.h"
#include "suslik/core/SiteAddress.h"
#include "suslik/core/Store.h"
#include "suslik/core/Strings.h"

#include <QFile>
#include <QFontDatabase>
#include <QGuiApplication>
#include <QIcon>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickWindow>
#include <QTimer>

using namespace suslik;

namespace {
QByteArray resource(const QString &path) {
    QFile f(path);
    return f.open(QIODevice::ReadOnly) ? f.readAll() : QByteArray();
}
} // namespace

int main(int argc, char *argv[]) {
    QGuiApplication app(argc, argv);
    QGuiApplication::setApplicationName(QStringLiteral("Suslik Media"));
    QGuiApplication::setApplicationVersion(QStringLiteral(SM_VERSION));
    QGuiApplication::setOrganizationName(QStringLiteral("Suslik"));

    // Уже открыто: то окно выходит вперёд, этот запуск на этом кончается.
    app::SingleInstance single;
    if (!single.acquire()) return 0;

    core::Store::setDirectory(app::AppDir::dir());
    app::AppDir::migrateFromKotlin();
    core::Strings::load(resource(QStringLiteral(":/res/strings/ru.json")));

    for (const char *f : {"onest_regular", "onest_medium", "onest_semibold", "unbounded_medium", "unbounded_bold"})
        QFontDatabase::addApplicationFont(QStringLiteral(":/res/fonts/%1.ttf").arg(QString::fromLatin1(f)));
    QFont base(QStringLiteral("Onest"));
    base.setPixelSize(14);
    QGuiApplication::setFont(base);

    QIcon icon;
    for (int sz : {16, 24, 32, 48, 64, 128, 256}) icon.addFile(QStringLiteral(":/res/icons/icon-%1.png").arg(sz), QSize(sz, sz));
    QGuiApplication::setWindowIcon(icon);

    // Адрес сервера: из сборки (SITE_URL), для отладки можно переопределить переменной окружения SUSLIK_SITE.
    const QString site = core::SiteAddress::normalize(
        qEnvironmentVariableIsSet("SUSLIK_SITE") ? qEnvironmentVariable("SUSLIK_SITE") : QStringLiteral(SM_SITE_URL));

    core::ApiClient api(site);
    core::CloudSync sync(api);
    app::Library library(api, sync);
    app::Ui ui(QStringLiteral(SM_VERSION), !site.isEmpty());
    // Прогресс с телефона или ТВ — сразу в плитки «Продолжить».
    QObject::connect(&sync, &core::CloudSync::changed, &library, [&library](int) { library.bumpWatch(); });

    qmlRegisterType<app::LibraryPage>("Suslik.Native", 1, 0, "LibraryPage");
    qmlRegisterUncreatableType<app::Library>("Suslik.Native", 1, 0, "Library", QStringLiteral("one per app"));

    QQmlApplicationEngine engine;
    engine.addImageProvider(QStringLiteral("suslik"), new app::ImageProvider(library));
    engine.rootContext()->setContextProperty(QStringLiteral("Ui"), &ui);
    engine.rootContext()->setContextProperty(QStringLiteral("lib"), &library);
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed, &app, [] { QCoreApplication::exit(1); },
                     Qt::QueuedConnection);
#if QT_VERSION >= QT_VERSION_CHECK(6, 5, 0)
    engine.loadFromModule("Suslik", "Main");
#else
    engine.load(QUrl(QStringLiteral("qrc:/Suslik/qml/Main.qml"))); // Qt 6.4 (тесты на Linux)
#endif
    if (engine.rootObjects().isEmpty()) return 1;
    auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first());
    if (window) {
        window->setColor(QColor(0x09, 0x0B, 0x10));
        ui.styleTitleBar(window);
        QObject::connect(&single, &app::SingleInstance::showRequested, window, [&ui, window] { ui.raise(window); });
    }

    sync.start();
    library.start();
    QTimer::singleShot(4000, &library, [&library] { library.prunePosterFiles(); });
    const int rc = app.exec();
    core::Store::flushAll();
    return rc;
}
