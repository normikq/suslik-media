#include "src/AppDir.h"
#include "src/ImageProvider.h"
#include "src/Library.h"
#include "src/LibraryPage.h"
#include "src/PlayModel.h"
#include "src/AddModel.h"
#include "src/AudioAttach.h"
#include "src/SearchModel.h"
#include "src/trackers/BrowserBridge.h"
#include "src/trackers/TrackerSettingsModel.h"
#include "src/party/Party.h"
#include "src/party/PartySync.h"
#include "src/Settings.h"
#include "src/SingleInstance.h"
#include "src/UrlScheme.h"
#include "src/Updater.h"
#include "src/player/Reserve.h"
#include "src/IconProvider.h"

#include <chrono>
#include <cstdlib>
#include <thread>
#include "src/Ui.h"
#include "src/player/MpvEngine.h"
#include "src/player/MpvVideo.h"
#include "src/player/MediaSession.h"
#include "src/player/MpvInstall.h"
#include "src/player/PlayerLog.h"
#include "src/player/PlayerModel.h"
#include "src/player/WindowMode.h"

#include "suslik/core/ApiClient.h"
#include "suslik/core/CloudSync.h"
#include "suslik/core/SiteAddress.h"
#include "suslik/core/Store.h"
#include "suslik/core/Strings.h"

#include <QFile>
#include <QFontDatabase>
#include <QPixmapCache>
#include <QGuiApplication>
#include <QIcon>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickWindow>
#include <QSGRendererInterface>
#include <QTimer>

using namespace suslik;

namespace {
QByteArray resource(const QString &path) {
    QFile f(path);
    return f.open(QIODevice::ReadOnly) ? f.readAll() : QByteArray();
}
} // namespace

int main(int argc, char *argv[]) {
    // Единый нативный стек OpenGL для Qt Quick и libmpv: прямой рендеринг видео в FBO сцены,
    // минимальное потребление ОЗУ (без дублирования DirectX/OpenGL драйверов) и абсолютная стабильность.
    QQuickWindow::setGraphicsApi(QSGRendererInterface::OpenGL);
    QGuiApplication app(argc, argv);
    // QPixmapCache (картинки виджетов и значки) — 4 МБ достаточно (постеры живут в кэше текстур QML).
    QPixmapCache::setCacheLimit(4 * 1024);
    QGuiApplication::setApplicationName(QStringLiteral("Suslik Media"));
    QGuiApplication::setApplicationVersion(QStringLiteral(SM_VERSION));
    QGuiApplication::setOrganizationName(QStringLiteral("Suslik"));

    // Запуск по ссылке suslik://room/<код> (Discord Activity): войти в эту комнату.
    QString pendingLink;
    for (const QString &a : app.arguments().mid(1))
        if (a.startsWith(QStringLiteral("suslik:"), Qt::CaseInsensitive)) { pendingLink = a; break; }

    // Уже открыто: то окно выходит вперёд (и получает ссылку), этот запуск на этом кончается.
    app::SingleInstance single;
    if (!single.acquire(pendingLink)) return 0;
    app::UrlScheme::registerSelf();

    core::Store::setDirectory(app::AppDir::dir());
    app::AppDir::migrateFromKotlin();
    core::Strings::load(resource(QStringLiteral(":/res/strings/ru.json")));

    for (const char *f : {"onest_regular", "onest_medium", "onest_semibold", "unbounded_medium", "unbounded_bold"})
        QFontDatabase::addApplicationFont(QStringLiteral(":/res/fonts/%1.ttf").arg(QString::fromLatin1(f)));
    QFont base(QStringLiteral("Onest"));
    base.setPixelSize(14);
    // Сглаженные буквы, как в Kotlin-версии (Skia): без подгонки к пиксельной сетке, дробные позиции.
    // С подгонкой (по умолчанию в Windows) буквы тонкие и угловатые.
    base.setHintingPreference(QFont::PreferNoHinting);
    QGuiApplication::setFont(base);

    QIcon icon;
    for (int sz : {16, 24, 32, 48, 64, 128, 256}) icon.addFile(QStringLiteral(":/res/icons/icon-%1.png").arg(sz), QSize(sz, sz));
    QGuiApplication::setWindowIcon(icon);

    // Адрес сервера: из сборки (SITE_URL), для отладки можно переопределить переменной окружения SUSLIK_SITE.
    const QString site = core::SiteAddress::normalize(
        qEnvironmentVariableIsSet("SUSLIK_SITE") ? qEnvironmentVariable("SUSLIK_SITE") : QStringLiteral(SM_SITE_URL));

    // после обновления — кэши прошлой версии долой (до того, как хранилища откроются)
    app::Updater::clearCachesOnUpdate(QStringLiteral(SM_VERSION));

    core::ApiClient api(site);
    core::CloudSync sync(api);
    app::Library library(api, sync);
    app::Ui ui(QStringLiteral(SM_VERSION), !site.isEmpty());
    app::Updater updater(api, QStringLiteral(SM_VERSION));
    app::Prefs prefs(api, updater);
    // Прогресс с телефона или ТВ — сразу в плитки «Продолжить».
    // Плеер один на программу: экран плеера поверх медиатеки, mpv создаётся при первом фильме.
    app::PlayerModel player;
    player.setLibrary(&library);
    app::WindowMode windowMode;
    app::MpvInstall mpvInstall(site);
    // «Вместе»: комната и плеер в ней (до MediaSession — у Discord «Смотрит вместе»)
    app::Party party(api);
    app::PartySync partySync(player, party);
    QObject::connect(&library, &app::Library::authenticatedChanged, &party, [&library, &party] {
        if (library.authenticated() == 0) party.leave(); // вышли из аккаунта — и из комнаты
        party.setHelper(library.authenticated() == 1);   // Discord Activity может попросить поискать на RuTracker
    });
    party.setHelper(library.authenticated() == 1);
    // Ссылка из Discord: в комнату — когда вход в аккаунт уже есть (при запуске он проверяется не сразу)
    auto runLink = [&library, &party, &pendingLink] {
        if (pendingLink.isEmpty() || library.authenticated() != 1) return;
        party.openLink(pendingLink);
        pendingLink.clear();
    };
    QObject::connect(&library, &app::Library::authenticatedChanged, &party, runLink);
    QObject::connect(&single, &app::SingleInstance::linkRequested, &party, [&pendingLink, runLink](const QString &link) {
        pendingLink = link;
        runLink();
    });
    app::MediaSession media(player, library);
    QObject::connect(&sync, &core::CloudSync::changed, &library, [&library](int) { library.bumpWatch(); });

    qmlRegisterType<app::LibraryPage>("Suslik.Native", 1, 0, "LibraryPage");
    qmlRegisterType<app::PlayModel>("Suslik.Native", 1, 0, "PlayModel");
    qmlRegisterType<app::AddModel>("Suslik.Native", 1, 0, "AddModel");
    qmlRegisterType<app::AudioAttachModel>("Suslik.Native", 1, 0, "AudioAttachModel");
    qmlRegisterType<app::SearchModel>("Suslik.Native", 1, 0, "SearchModel");
    qmlRegisterType<app::TrackerSettingsModel>("Suslik.Native", 1, 0, "TrackerSettingsModel");
    qmlRegisterUncreatableType<app::PlayerModel>("Suslik.Native", 1, 0, "PlayerModel", QStringLiteral("one per app"));
    qmlRegisterType<app::MpvEngine>("Suslik.Native", 1, 0, "MpvEngine");
    qmlRegisterType<app::MpvVideo>("Suslik.Native", 1, 0, "MpvVideo");
    qmlRegisterUncreatableType<app::Library>("Suslik.Native", 1, 0, "Library", QStringLiteral("one per app"));

    QQmlApplicationEngine engine;
    ui.setEngine(&engine);
    // Модуль Suslik вшит в программу; копия рядом (qml\Suslik) — запасной путь для упакованной сборки.
    engine.addImportPath(QCoreApplication::applicationDirPath() + QStringLiteral("/qml"));
    engine.addImageProvider(QStringLiteral("suslik"), new app::ImageProvider(library));
    engine.addImageProvider(QStringLiteral("icon"), new app::IconProvider);
    engine.rootContext()->setContextProperty(QStringLiteral("Ui"), &ui);
    engine.rootContext()->setContextProperty(QStringLiteral("lib"), &library);
    engine.rootContext()->setContextProperty(QStringLiteral("prefs"), &prefs);
    engine.rootContext()->setContextProperty(QStringLiteral("updater"), &updater);
    engine.rootContext()->setContextProperty(QStringLiteral("player"), &player);
    engine.rootContext()->setContextProperty(QStringLiteral("windowMode"), &windowMode);
    engine.rootContext()->setContextProperty(QStringLiteral("mpvInstall"), &mpvInstall);
    engine.rootContext()->setContextProperty(QStringLiteral("party"), &party);
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed, &app, [] { QCoreApplication::exit(1); },
                     Qt::QueuedConnection);
#if QT_VERSION >= QT_VERSION_CHECK(6, 5, 0)
    engine.loadFromModule("Suslik", "Main");
#else
    engine.load(QUrl(QStringLiteral("qrc:/qt/qml/Suslik/Main.qml"))); // Qt 6.4 (тесты на Linux)
#endif
    if (engine.rootObjects().isEmpty()) return 1;
    auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first());
    if (window) {
        window->setColor(QColor(0x09, 0x0B, 0x10));
        ui.styleTitleBar(window);
        windowMode.setWindow(window);
        media.attach(window);
        QObject::connect(&single, &app::SingleInstance::showRequested, window, [&ui, window] { ui.raise(window); });
    }

    // Снимок окна для проверки вёрстки без экрана (SM_SHOT=файл.png, SM_SHOT_MS — через сколько мс).
    if (window && qEnvironmentVariableIsSet("SM_SHOT")) {
        const int ms = qEnvironmentVariableIsSet("SM_SHOT_MS") ? qEnvironmentVariableIntValue("SM_SHOT_MS") : 3000;
        QTimer::singleShot(ms, window, [window] {
            window->grabWindow().save(qEnvironmentVariable("SM_SHOT"));
            QCoreApplication::quit();
        });
    }

    sync.start();
    library.start();
    QTimer::singleShot(0, &party, runLink);
    QTimer::singleShot(4000, &library, [&library] { library.prunePosterFiles(); });
    // libmpv нет (самообновление со старой версии без неё) — докачать заранее, не дожидаясь фильма
    QTimer::singleShot(3000, &mpvInstall, [&mpvInstall] { if (!mpvInstall.ready()) mpvInstall.start(); });
    app::PlayerLog::write(QStringLiteral("[sys] Suslik Media Qt %1 started").arg(QStringLiteral(SM_VERSION)));
    const int rc = app.exec();
    core::Store::flushAll();
    app::PlayerLog::write(QStringLiteral("[sys] quit"));
    // Страховка: что-то повисло при закрытии (сеть, потоки запаса) — процесс всё равно уходит. Иначе зависший
    // процесс держал бы «одну копию программы», и новый запуск только будил бы его.
    std::thread([rc] {
        std::this_thread::sleep_for(std::chrono::seconds(8));
        app::PlayerLog::write(QStringLiteral("[sys] quit took > 8 s, terminating"));
        std::_Exit(rc);
    }).detach();
    mpvInstall.cancel();
    player.close();
    app::BrowserBridge::instance().shutdown(); // фоновый браузер RuTracker
    app::Reserve::shutdown();
    return rc;
}
