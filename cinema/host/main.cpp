// Suslik Cinema — хост кинотеатра для Cyberpunk 2077 (этап 2). Устройство — docs/CYBERPUNK.md.
//
//   suslik-cinema.exe                      тестовая картинка на экране телевизора в игре (углы — от мода SuslikCinema;
//                                          без мода — по ручной калибровке поверх окна игры или экрана)
//   suslik-cinema.exe --file <путь|адрес>  то же с видео через mpv (libmpv — от Suslik Media Qt)
//   suslik-cinema.exe --mute-test          приглушить звук игры на 5 с и вернуть (проверка Core Audio)
//
// Клавиши (фокус остаётся у игры): Ctrl+Alt+C калибровка (углы тянутся мышью, S — сохранить),
// Ctrl+Alt+P пауза, Ctrl+Alt+H скрыть экран, Ctrl+Alt+Q выход, Ctrl+Alt+PgUp/PgDn — поправка упреждения ±4 мс,
// Ctrl+Alt+R — запись 10 с по кадрам (cinema-rec.csv).
#include "GameAudio.h"
#include "GameLink.h"
#include "Host.h"
#include "Overlay.h"
#include "ScreenQuad.h"

#include "AppDir.h"
#include "player/MpvEngine.h"
#include "player/MpvVideo.h"
#include "PlayerLog.h"

#include <QCommandLineParser>
#include <QDir>
#include <QGuiApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickWindow>
#include <QSGRendererInterface>
#include <QTimer>

#include <cstdio>
#include <initializer_list>
#include <memory>
#include <utility>

#ifdef Q_OS_WIN
#include <windows.h>
#endif

using namespace suslik;

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

void say(const QString &s) {
    app::PlayerLog::write(s);
    std::fputs((s + QLatin1Char('\n')).toLocal8Bit().constData(), stdout);
    std::fflush(stdout);
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
    cli.addOptions({fileOpt, gameOpt, noGameOpt, noMuteOpt, muteTestOpt, calibrateOpt, modDirOpt, leadOpt, presentOpt});
    cli.process(app);
    const QString gameExe = cli.value(gameOpt);

    say(QStringLiteral("[cinema] старт %1, данные %2").arg(QStringLiteral(SM_VERSION), app::AppDir::dir()));
    if (const int n = cinema::GameAudio::recoverAfterCrash(gameExe); n > 0)
        say(QStringLiteral("[cinema] звук игры возвращён после сбоя: %1 сессий").arg(n));

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
    cinema::Host host(!file.isEmpty());
    cinema::ScreenQuad quad;
    quad.load();
    app::MpvEngine mpv;
    cinema::GameAudio audio(gameExe);
    const bool manageMute = !cli.isSet(noMuteOpt);

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
    };
    syncLink();
    QObject::connect(&overlay, &cinema::Overlay::gameChanged, &app, syncLink);
    QObject::connect(&overlay, &cinema::Overlay::gameActiveChanged, &app, syncLink);
    QObject::connect(&overlay, &cinema::Overlay::calibratingChanged, &app, syncLink);
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
        else if (k == QLatin1String("quit")) app.quit();
        else if (k == QLatin1String("lead+")) link.nudge(4);
        else if (k == QLatin1String("lead-")) link.nudge(-4);
        else if (k == QLatin1String("rec")) link.startRecording();
    });

    // Звук игры приглушён, пока фильм идёт; пауза, конец и выход — звук возвращается
    auto syncMute = [&] {
        if (!manageMute || !overlay.gameFound()) return;
        const bool want = mpv.loaded() && !mpv.paused();
        if (want && !audio.muted()) audio.mute();
        else if (!want && audio.muted()) audio.unmute();
    };
    QObject::connect(&mpv, &app::MpvEngine::stateChanged, &app, syncMute);
    QObject::connect(&overlay, &cinema::Overlay::gameChanged, &app, syncMute);
    QObject::connect(&mpv, &app::MpvEngine::failed, &app, [](const QString &m) { say(QStringLiteral("[cinema] mpv: ") + m); });

    if (!file.isEmpty()) {
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
        auto opened = std::make_shared<int>(0);
        QObject::connect(window, &QQuickWindow::frameSwapped, &app, [&mpv, window, file, opened] {
            if (*opened < 2) window->update();
            if ((*opened)++ != 2) return; // пара кадров: рисовальщик создан и синхронизирован
            say(QStringLiteral("[cinema] открываю %1").arg(file));
            mpv.open(file, 0, false, QStringLiteral("rus,ru"), QStringLiteral("rus,ru"), {}, {});
        }, Qt::QueuedConnection);
        window->update();
    }

    QObject::connect(&app, &QGuiApplication::aboutToQuit, [&] {
        if (audio.muted()) audio.unmute();
        mpv.release();
    });
    return app.exec();
}
