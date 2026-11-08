// Suslik Cinema — хост кинотеатра для Cyberpunk 2077 (этапы 1–2). Устройство — docs/CYBERPUNK.md.
//
//   suslik-cinema.exe --login              вход по коду устройства (код — в консоли, страница — в браузере)
//   suslik-cinema.exe --list [слова]       медиатека: id, название, релизы с качеством
//   suslik-cinema.exe --play <id|название> [--quality 4K|1080p|…] [--release <id>] [--season N] [--episode N]
//                                          [--from-start]  фильм из медиатеки через зеркало и запас (BurstProxy)
//   suslik-cinema.exe                      тестовая картинка на экране телевизора в игре (углы — от мода SuslikCinema;
//                                          без мода — по ручной калибровке поверх окна игры или экрана)
//   suslik-cinema.exe --file <путь|адрес>  то же с видео через mpv (libmpv — от Suslik Media Qt)
//   suslik-cinema.exe --mute-test          приглушить звук игры на 5 с и вернуть (проверка Core Audio)
//
// Клавиши (фокус остаётся у игры): Ctrl+Alt+C калибровка (углы тянутся мышью, S — сохранить),
// Ctrl+Alt+P пауза, Ctrl+Alt+H скрыть экран, Ctrl+Alt+Q выход, Ctrl+Alt+PgUp/PgDn — поправка упреждения ±4 мс,
// Ctrl+Alt+R — запись 10 с по кадрам (cinema-rec.csv); пульт: Ctrl+Alt+←/→ — 10 с, Ctrl+Alt+↑/↓ — 10 мин,
// Ctrl+Alt+A — озвучка, Ctrl+Alt+S — субтитры, Ctrl+Alt+I — сводка mpv в журнал.
#include "CinemaParty.h"
#include "CinemaSession.h"
#include "GameAudio.h"
#include "GameLink.h"
#include "Host.h"
#include "Overlay.h"
#include "RemoteLink.h"
#include "SpatialSound.h"
#include "ScreenQuad.h"

#include "AppDir.h"
#include "Library.h"
#include "party/Party.h"
#include "SingleInstance.h"
#include "player/Reserve.h"
#include "player/MpvEngine.h"
#include "player/MpvVideo.h"
#include "PlayerLog.h"

#include "suslik/core/ApiClient.h"
#include "suslik/core/CloudSync.h"
#include "suslik/core/SiteAddress.h"
#include "suslik/core/Store.h"
#include "suslik/core/Strings.h"

#include <QCommandLineParser>
#include <QDir>
#include <QFile>
#include <QLockFile>
#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickWindow>
#include <QSGRendererInterface>
#include <QThread>
#include <QTimer>

#include <cstdio>
#include <initializer_list>
#include <memory>
#include <utility>

#ifdef Q_OS_WIN
#include <windows.h>
#endif

using namespace suslik;
using cinema::say;

namespace {

/// Запуск из консоли: вывод --mute-test виден в ней (у WIN32-программы своей консоли нет).
void attachConsole() {
#ifdef Q_OS_WIN
    if (AttachConsole(ATTACH_PARENT_PROCESS)) {
        FILE *f = nullptr;
        freopen_s(&f, "CONOUT$", "w", stdout);
    }
#endif
}

QByteArray resource(const QString &path) {
    QFile f(path);
    return f.open(QIODevice::ReadOnly) ? f.readAll() : QByteArray();
}

} // namespace

int main(int argc, char *argv[]) {
    // Своя папка данных; libmpv — та же, что докачала ПК-версия (если не задано иначе)
    const QString appData = qEnvironmentVariable("APPDATA");
    if (!qEnvironmentVariableIsSet("SUSLIK_APPDIR") && !appData.isEmpty())
        qputenv("SUSLIK_APPDIR", QDir(appData).filePath(QStringLiteral("SuslikCinema")).toLocal8Bit());
    if (!qEnvironmentVariableIsSet("SUSLIK_MPV_DIR") && !appData.isEmpty())
        qputenv("SUSLIK_MPV_DIR", QDir(appData).filePath(QStringLiteral("SuslikMediaQt/mpv")).toLocal8Bit());

    // mpv рисует через OpenGL в FBO сцены (как ПК-версия); окно с альфа-каналом — прозрачное поверх игры
    QQuickWindow::setGraphicsApi(QSGRendererInterface::OpenGL);
    QQuickWindow::setDefaultAlphaBuffer(true);
    QGuiApplication app(argc, argv);
    QGuiApplication::setApplicationName(QStringLiteral("Suslik Cinema"));
    attachConsole();

    QCommandLineParser cli;
    cli.addHelpOption();
    QCommandLineOption fileOpt(QStringLiteral("file"), QStringLiteral("Видео для экрана"), QStringLiteral("path"));
    QCommandLineOption gameOpt(QStringLiteral("game"), QStringLiteral("Процесс игры"), QStringLiteral("exe"),
                               QStringLiteral("Cyberpunk2077.exe"));
    QCommandLineOption noGameOpt(QStringLiteral("no-game"), QStringLiteral("Не искать окно игры, весь экран"));
    QCommandLineOption noMuteOpt(QStringLiteral("no-mute"), QStringLiteral("Не трогать звук игры"));
    QCommandLineOption muteTestOpt(QStringLiteral("mute-test"), QStringLiteral("Приглушить игру на 5 с и вернуть"));
    QCommandLineOption calibrateOpt(QStringLiteral("calibrate"), QStringLiteral("Начать в режиме калибровки"));
    QCommandLineOption modDirOpt(QStringLiteral("mod-dir"), QStringLiteral("Папка мода SuslikCinema (иначе — рядом с игрой)"),
                                 QStringLiteral("dir"));
    QCommandLineOption leadOpt(QStringLiteral("lead-ms"), QStringLiteral("Поправка упреждения, мс (сохраняется)"), QStringLiteral("ms"));
    QCommandLineOption presentOpt(QStringLiteral("present-frames"),
                                  QStringLiteral("Кадров от vblank до показа оверлея (по умолчанию 1)"), QStringLiteral("n"));
    QCommandLineOption loginOpt(QStringLiteral("login"), QStringLiteral("Вход по коду устройства"));
    QCommandLineOption listOpt(QStringLiteral("list"), QStringLiteral("Медиатека (слова после ключа — фильтр)"));
    QCommandLineOption playOpt(QStringLiteral("play"), QStringLiteral("Фильм из медиатеки: id или название"), QStringLiteral("query"));
    QCommandLineOption qualityOpt(QStringLiteral("quality"), QStringLiteral("Качество релиза: 4K, 1080p или часть подписи"),
                                  QStringLiteral("q"));
    QCommandLineOption releaseOpt(QStringLiteral("release"), QStringLiteral("id раздачи-релиза из --list"), QStringLiteral("id"));
    QCommandLineOption seasonOpt(QStringLiteral("season"), QStringLiteral("Сезон сериала"), QStringLiteral("n"));
    QCommandLineOption episodeOpt(QStringLiteral("episode"), QStringLiteral("Серия (у сборника — фильм)"), QStringLiteral("n"));
    QCommandLineOption fromStartOpt(QStringLiteral("from-start"), QStringLiteral("С начала, а не с сохранённого места"));
    QCommandLineOption siteOpt(QStringLiteral("site"), QStringLiteral("Адрес сервера (сохраняется)"), QStringLiteral("url"));
    QCommandLineOption remoteOpt(QStringLiteral("remote"), QStringLiteral("Запуск с телефона VAO: без фильма, уже запущенный хост не трогать"));
    QCommandLineOption patternOpt(QStringLiteral("pattern"), QStringLiteral("Тестовая картинка вместо плеера (калибровка)"));
    QCommandLineOption volumeOpt(QStringLiteral("volume"), QStringLiteral("Громкость фильма 0–150"), QStringLiteral("v"));
    QCommandLineOption unmuteOpt(QStringLiteral("unmute-game"), QStringLiteral("Вернуть звук игре, чьё бы ни было приглушение"));
    QCommandLineOption reserveOpt(QStringLiteral("reserve-mb"), QStringLiteral("Запас на фильм, МБ (сохраняется; 0 — без запаса)"),
                                  QStringLiteral("mb"));
    cli.addOptions({fileOpt, gameOpt, noGameOpt, noMuteOpt, muteTestOpt, calibrateOpt, modDirOpt, leadOpt, presentOpt, loginOpt,
                    listOpt, playOpt, qualityOpt, releaseOpt, seasonOpt, episodeOpt, fromStartOpt, siteOpt, reserveOpt, unmuteOpt, remoteOpt, patternOpt, volumeOpt});
    cli.process(app);
    const QString gameExe = cli.value(gameOpt);

    say(QStringLiteral("[cinema] старт %1, данные %2").arg(QStringLiteral(SM_VERSION), app::AppDir::dir()));
    core::Store::setDirectory(app::AppDir::dir());
    core::Strings::load(resource(QStringLiteral(":/res/strings/ru.json")));

    // Сервер: SUSLIK_SITE, --site (запоминается), адрес сборки (SITE_URL). В репозитории адреса нет.
    core::Store &settings = core::Store::of(QStringLiteral("settings"));
    if (cli.isSet(siteOpt)) settings.put(QStringLiteral("site"), core::SiteAddress::normalize(cli.value(siteOpt)));
    if (cli.isSet(reserveOpt)) settings.put(QStringLiteral("burst_mb"), QString::number(cli.value(reserveOpt).toInt()));
    QString site = core::SiteAddress::normalize(qEnvironmentVariable("SUSLIK_SITE"));
    if (site.isEmpty()) site = settings.get(QStringLiteral("site")).value_or(QString());
    if (site.isEmpty()) site = core::SiteAddress::normalize(QStringLiteral(SM_SITE_URL));
    const bool needServer = cli.isSet(loginOpt) || cli.isSet(listOpt) || cli.isSet(playOpt);
    if (needServer && site.isEmpty()) {
        say(QStringLiteral("Адрес сервера не задан: suslik-cinema.exe --site <адрес> --login"));
        return 1;
    }
    core::ApiClient api(site);
    api.setHttpLog([](const QString &verb, const QString &path, int code, qint64 ms, qint64 size, const QString &err) {
        // путь без запроса: в link и sig — ссылка на файл и подпись
        app::PlayerLog::write(QStringLiteral("[http] %1 %2 → %3, %4 мс, %5 Б%6")
                                  .arg(verb, path.section(QLatin1Char('?'), 0, 0)).arg(code).arg(ms).arg(size)
                                  .arg(err.isEmpty() ? QString() : QStringLiteral(", ") + err));
    });
    core::CloudSync sync(api);
    QObject::connect(&sync, &core::CloudSync::log, &app, [](const QString &m) { app::PlayerLog::write(m); });
    app::Library library(api, sync);
    cinema::CinemaSession session(api, sync, library);

    // Вход и список — без окна: ответ в консоль, потом выход
    if (cli.isSet(loginOpt) || cli.isSet(listOpt)) {
        QObject::connect(&session, &cinema::CinemaSession::done, &app, [&app](int code) {
            core::Store::flushAll();
            QTimer::singleShot(300, &app, [&app, code] { app.exit(code); });
        });
        if (cli.isSet(loginOpt)) session.login();
        else session.list(cli.positionalArguments().join(QLatin1Char(' ')));
        sync.start();
        const int code = app.exec();
        core::Store::flushAll();
        app::Reserve::shutdown();
        return code;
    }
    if (cli.isSet(unmuteOpt)) {
        const int n = cinema::GameAudio::unmuteAll(gameExe);
        say(n < 0 ? QStringLiteral("Core Audio недоступен") : QStringLiteral("Звук %1 возвращён: %2 сессий").arg(gameExe).arg(n));
        return n < 0 ? 1 : 0;
    }

    // Хост — один: новый запуск (другой фильм) просит работающий выйти и ждёт, пока тот вернёт звук и сохранит место.
    // Две копии рисовали бы два оверлея, клавиши получала бы только первая, а звук игры они путали бы друг у друга.
    if (cli.isSet(remoteOpt)) {
        // helper телефона запускает хост «на всякий случай»: идущий фильм не прерываем
        QLockFile probe(app::AppDir::file(QStringLiteral("instance.lock")));
        probe.setStaleLockTime(0);
        if (!probe.tryLock(0) && probe.error() == QLockFile::LockFailedError) {
            say(QStringLiteral("[cinema] хост уже запущен"));
            return 0;
        }
    }
    auto single = std::make_unique<app::SingleInstance>();
    if (!cli.isSet(muteTestOpt) && !single->acquire()) {
        say(QStringLiteral("[cinema] хост уже запущен — прошу его закрыться"));
        bool got = false;
        for (int i = 0; i < 40 && !got; ++i) {
            QThread::msleep(250);
            single = std::make_unique<app::SingleInstance>();
            got = single->acquire();
        }
        if (!got) {
            say(QStringLiteral("[cinema] прежний хост не закрылся (Ctrl+Alt+Q)"));
            return 1;
        }
    }

    if (const int n = cinema::GameAudio::recoverAfterCrash(gameExe); n > 0)
        say(QStringLiteral("[cinema] звук игры возвращён после прошлого сеанса: %1 сессий").arg(n));

    if (cli.isSet(muteTestOpt)) {
        auto *audio = new cinema::GameAudio(gameExe);
        const int n = audio->mute();
        say(n < 0 ? QStringLiteral("Core Audio недоступен")
                  : QStringLiteral("Приглушено сессий %1: %2. Через 5 с звук вернётся.").arg(gameExe).arg(n));
        QTimer::singleShot(5000, &app, [&app, audio] {
            say(QStringLiteral("Возвращено сессий: %1").arg(audio->unmute()));
            delete audio;
            app.quit();
        });
        return app.exec();
    }

    const QString file = cli.value(fileOpt);
    const bool fromLibrary = cli.isSet(playOpt);
    // Плеер на экране всегда (пульт VAO может включить фильм в любой момент); тестовая картинка — только --pattern
    const bool pattern = cli.isSet(patternOpt) && file.isEmpty() && !fromLibrary;
    cinema::Host host(!pattern);
    host.setBlank(!pattern && file.isEmpty() && !fromLibrary);
    cinema::ScreenQuad quad;
    quad.load();
    app::MpvEngine mpv;
    cinema::GameAudio audio(gameExe);
    const bool manageMute = !cli.isSet(noMuteOpt);
    cinema::RemoteLink remote;

    qmlRegisterType<app::MpvVideo>("SuslikCinema.Native", 1, 0, "MpvVideo");
    qmlRegisterUncreatableType<app::MpvEngine>("SuslikCinema.Native", 1, 0, "MpvEngine", QStringLiteral("one"));

    cinema::Overlay overlay(cli.isSet(noGameOpt) ? QString() : gameExe);
    cinema::GameLink link(&quad);
    if (cli.isSet(leadOpt)) link.setAdjustMs(cli.value(leadOpt).toInt());
    if (cli.isSet(presentOpt)) link.setPresentFrames(cli.value(presentOpt).toInt());
    if (cli.isSet(modDirOpt)) link.setModDir(cli.value(modDirOpt));
    QQmlApplicationEngine engine;
    engine.rootContext()->setContextProperty(QStringLiteral("host"), &host);
    engine.rootContext()->setContextProperty(QStringLiteral("overlay"), &overlay);
    engine.rootContext()->setContextProperty(QStringLiteral("quad"), &quad);
    engine.rootContext()->setContextProperty(QStringLiteral("link"), &link);
    engine.rootContext()->setContextProperty(QStringLiteral("mpvEngine"), &mpv);
    engine.load(QUrl(QStringLiteral("qrc:/qt/qml/SuslikCinema/Main.qml")));
    if (engine.rootObjects().isEmpty()) return 1;
    auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().constFirst());
    if (!window) return 1;

    overlay.attach(window);
    overlay.registerHotkeys();
    if (cli.isSet(calibrateOpt)) overlay.setCalibrating(true);

    // Мод: папка — рядом с exe игры; углы экрана — в каждом кадре оверлея, до синхронизации сцены
    auto syncLink = [&] {
        if (!cli.isSet(modDirOpt) && overlay.gameFound()) link.setGameExePath(overlay.gameExePath());
        link.setGameActive(overlay.gameActive());
        link.setPaused(overlay.calibrating());
        remote.setModDir(link.modDir());
    };
    syncLink();
    QObject::connect(&overlay, &cinema::Overlay::gameChanged, &app, syncLink);
    QObject::connect(&overlay, &cinema::Overlay::gameActiveChanged, &app, syncLink);
    QObject::connect(&overlay, &cinema::Overlay::calibratingChanged, &app, syncLink);
    // Фильм на экране других пропорций (21:9, 2.39:1 на стене кинозала): вписать с полями, заполнить с обрезкой
    // краёв или растянуть — по выбору в моде
    QObject::connect(&link, &cinema::GameLink::fitChanged, &app, [&](const QString &fit) {
        mpv.setKeepAspect(fit != QLatin1String("stretch"));
        mpv.setPanScan(fit == QLatin1String("fill") ? 1.0 : 0.0);
        say(QStringLiteral("[cinema] картинка на экране: ") + fit);
    });
    QObject::connect(&mpv, &app::MpvEngine::videoSizeChanged, &app, [&] {
        link.setVideoWidth(mpv.videoWidth());
        say(QStringLiteral("[cinema] видео %1×%2").arg(mpv.videoWidth()).arg(mpv.videoHeight()));
    });
    QObject::connect(&mpv, &app::MpvEngine::stateChanged, &app, [&] {
        static QString last;
        const QString st = QStringLiteral("[cinema] mpv: загружен %1, пауза %2, буферизация %3")
                               .arg(mpv.loaded()).arg(mpv.paused()).arg(mpv.buffering());
        if (st != last) say(last = st);
    });
    QObject::connect(window, &QQuickWindow::afterAnimating, &link, [&link, window] { link.frame(window->size()); });
    // Камера игры меняется каждый кадр: оверлей рисуется непрерывно (с vsync), пока есть мод
    QObject::connect(window, &QQuickWindow::frameSwapped, &link, [&link, window] {
        if (!link.modDir().isEmpty()) window->update();
    }, Qt::QueuedConnection);
    window->update();

    QObject::connect(&overlay, &cinema::Overlay::hotkey, &app, [&](const QString &k) {
        if (k == QLatin1String("pause")) mpv.togglePause();
        else if (k == QLatin1String("hide")) host.setHidden(!host.hidden());
        else if (k == QLatin1String("quit")) {
            // место — на сервер до выхода (отправка занимает доли секунды)
            session.flush();
            QTimer::singleShot(1500, &app, &QGuiApplication::quit);
        }
        else if (k == QLatin1String("lead+")) link.nudge(4);
        else if (k == QLatin1String("lead-")) link.nudge(-4);
        else if (k == QLatin1String("rec")) link.startRecording();
        else session.command(k);
    });

    // Звук игры приглушён, пока фильм идёт; пауза, конец и выход — звук возвращается
    auto syncMute = [&] {
        if (!manageMute || !session.option(QStringLiteral("muteGame"))) {
            if (audio.muted()) audio.unmute();
            return;
        }
        if (!overlay.gameFound()) return;
        const bool want = mpv.loaded() && !mpv.paused();
        if (want && !audio.muted()) audio.mute();
        else if (!want && audio.muted()) audio.unmute();
    };
    QObject::connect(&mpv, &app::MpvEngine::stateChanged, &app, syncMute);
    QObject::connect(&overlay, &cinema::Overlay::gameChanged, &app, syncMute);
    // Игру закрыли во время фильма: звук ей вернётся, когда она снова запустится и заведёт сессию звука
    QTimer pendingAudio;
    pendingAudio.setInterval(3000);
    QObject::connect(&pendingAudio, &QTimer::timeout, &app, [&] {
        if (overlay.gameFound() && audio.hasPending() && !audio.muted()) audio.restorePending();
        syncMute(); // игра завела сессию звука позже начала фильма
    });
    pendingAudio.start();
    // Запустили хост ещё раз: место — на сервер, звук — игре, потом выход (новый ждёт до 10 с)
    QObject::connect(single.get(), &app::SingleInstance::showRequested, &app, [&] {
        say(QStringLiteral("[cinema] запущен новый хост — закрываюсь"));
        session.flush();
        if (audio.muted()) audio.unmute();
        QTimer::singleShot(1500, &app, &QGuiApplication::quit);
    });
    QObject::connect(&mpv, &app::MpvEngine::failed, &app, [](const QString &m) { say(QStringLiteral("[cinema] mpv: ") + m); });

    if (!pattern) {
        if (!mpv.available()) say(QStringLiteral("[cinema] libmpv не найдена: ") + app::mpv::searchDirs().join(QStringLiteral("; ")));
        // Порядок важен (vo=libmpv): ядро mpv -> рисовальщик MpvVideo берёт его в первом кадре сцены -> файл.
        // Если открыть файл раньше, чем есть render context, mpv выключает видео (звук при этом идёт).
        // Экран в игре — около 1000 px, а видеокарту делит игра (DLSS, генерация кадров): дорогие фильтры mpv
        // (lanczos, линейное уменьшение, замер пика HDR) на 4K задерживали кадры оверлея до 50–260 мс,
        // и экран на это время «прилипал» к камере. Быстрые фильтры — только в хосте, ПК-версия не меняется.
        if (const app::mpv::Handle h = mpv.handle()) {
            const app::mpv::Api *a = app::mpv::api();
            for (const auto &[k, v] : std::initializer_list<std::pair<const char *, const char *>>{
                     {"scale", "bilinear"}, {"dscale", "bilinear"}, {"cscale", "bilinear"},
                     {"correct-downscaling", "no"}, {"linear-downscaling", "no"}, {"sigmoid-upscaling", "no"},
                     {"deband", "no"}, {"dither-depth", "no"}, {"hdr-compute-peak", "no"}})
                a->set_property_string(h.get(), k, v);
        }
    }
    // Фильм открывается, когда готово и то и другое: рисовальщик (пара кадров сцены) и адрес (сразу или от сервера)
    struct Pending {
        int frames = 0;
        bool opened = false;
        QString url;
        qint64 startMs = 0;
    };
    auto pending = std::make_shared<Pending>();
    if (!file.isEmpty()) pending->url = file;
    // 3D-звук (идея TVKit): звук фильма — от экрана в мире игры; цепочка фильтров — после каждого открытия
    cinema::SpatialSound spatial(mpv, link);
    auto syncAudio3d = [&] { spatial.setMode(cinema::Audio3D(session.audio3d()), session.option(QStringLiteral("muffle"))); };
    syncAudio3d();
    QObject::connect(&session, &cinema::CinemaSession::optionChanged, &app, syncAudio3d);
    const int startVolume = cli.isSet(volumeOpt) ? std::clamp(cli.value(volumeOpt).toInt(), 0, 150) : -1;
    auto tryOpen = [&mpv, &host, &spatial, pending, startVolume] {
        if (pending->opened || pending->frames < 3 || pending->url.isEmpty()) return;
        pending->opened = true;
        host.setBlank(false);
        say(QStringLiteral("[cinema] открываю %1 с %2 с").arg(pending->url.section(QLatin1Char('?'), 0, 0)).arg(pending->startMs / 1000));
        mpv.open(pending->url, pending->startMs, false, QStringLiteral("rus,ru"), QStringLiteral("rus,ru"), {}, {});
        spatial.install();
        if (startVolume >= 0) mpv.setVolume(startVolume);
    };
    QObject::connect(window, &QQuickWindow::frameSwapped, &app, [window, pending, tryOpen] {
        if (pending->frames < 3) window->update();
        if (pending->frames++ == 2) tryOpen(); // пара кадров: рисовальщик создан и синхронизирован
    }, Qt::QueuedConnection);

    session.attach(mpv);
    QObject::connect(&session, &cinema::CinemaSession::ready, &app, [pending, tryOpen](const QString &url, qint64 startMs) {
        pending->url = url;
        pending->startMs = startMs;
        pending->opened = false; // пульт: следующий фильм поверх прежнего
        tryOpen();
    });

    // ---- пульт VAO (этап 3): команды из remote.json мода, состояние и медиатека — в host.json / library.json ----
    QObject::connect(&remote, &cinema::RemoteLink::command, &session, &cinema::CinemaSession::remote);
    QTimer statusTimer;
    statusTimer.setInterval(300);
    QObject::connect(&statusTimer, &QTimer::timeout, &app, [&] { remote.setStatus(session.status()); });
    statusTimer.start();
    QTimer libraryTimer; // медиатека меняется пачками (постеры, названия): на телефон — не чаще раза в секунду
    libraryTimer.setSingleShot(true);
    libraryTimer.setInterval(1000);
    QObject::connect(&libraryTimer, &QTimer::timeout, &app, [&] { remote.setLibrary(session.library()); });
    QObject::connect(&session, &cinema::CinemaSession::libraryChanged, &app, [&] {
        if (!libraryTimer.isActive()) libraryTimer.start();
    });
    QObject::connect(&session, &cinema::CinemaSession::stopRequested, &app, [&] {
        mpv.stop();
        host.setBlank(true);
    });
    QObject::connect(&session, &cinema::CinemaSession::quitRequested, &app, [&] {
        session.flush();
        QTimer::singleShot(1500, &app, &QGuiApplication::quit);
    });
    QObject::connect(&session, &cinema::CinemaSession::optionChanged, &app, syncMute);
    // Пауза фильма вместе с игрой (меню игры) и когда игру закрыли; продолжаем, только если паузу ставили мы
    auto autoPaused = std::make_shared<bool>(false);
    auto syncPause = [&, autoPaused] {
        const bool gameAway = !cli.isSet(noGameOpt) && !overlay.gameFound();
        const bool want = gameAway || (link.gamePaused() && session.option(QStringLiteral("pauseWithGame")));
        if (want && mpv.loaded() && !mpv.paused()) {
            mpv.setPaused(true);
            *autoPaused = true;
            say(gameAway ? QStringLiteral("[cinema] игры нет — пауза") : QStringLiteral("[cinema] пауза игры — пауза фильма"));
        } else if (!want && *autoPaused) {
            *autoPaused = false;
            if (mpv.loaded() && mpv.paused()) mpv.setPaused(false);
        }
    };
    QObject::connect(&link, &cinema::GameLink::gamePausedChanged, &app, syncPause);
    QObject::connect(&overlay, &cinema::Overlay::gameChanged, &app, syncPause);

    // Комната «Вместе»: те же комнаты, что у ПК, Android и ТВ (app::Party и общий движок синхронизации)
    app::Party party(api);
    cinema::CinemaParty cinemaParty(party, session, mpv, library);
    if (!site.isEmpty()) {
        session.startRemote();
        sync.start();
    }
    if (fromLibrary) {
        cinema::PlayArgs args;
        args.query = cli.value(playOpt);
        args.quality = cli.value(qualityOpt);
        args.release = cli.value(releaseOpt).toLongLong();
        if (cli.isSet(seasonOpt)) args.season = cli.value(seasonOpt).toInt();
        if (cli.isSet(episodeOpt)) args.episode = cli.value(episodeOpt).toInt();
        args.fromStart = cli.isSet(fromStartOpt);
        session.play(args);
    }
    window->update();

    QObject::connect(&app, &QGuiApplication::aboutToQuit, [&] {
        if (audio.muted()) audio.unmute();
        session.flush();
        mpv.release();
        app::Reserve::shutdown();
    });
    return app.exec();
}
