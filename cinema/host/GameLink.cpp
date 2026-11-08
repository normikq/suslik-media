#include "GameLink.h"
#include "ScreenQuad.h"

#include "AppDir.h"
#include "PlayerLog.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QSaveFile>

#include <algorithm>
#include <cmath>

#ifdef Q_OS_WIN
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <dwmapi.h>
#endif

namespace suslik::cinema {

namespace {

constexpr double kStaleSec = 0.3;      // кадров игры нет дольше — пауза, меню, загрузка: экран прячется
// Быстрый поворот — экран полупрозрачный (упреждение не успевает). С запасом по порогу и плавно: иначе при скорости
// около порога (обход экрана со взглядом на него) экран мигал 100 % -> 50 % -> 100 % (08.11).
constexpr double kFastTurnOnDeg = 150;
constexpr double kFastTurnOffDeg = 100;
constexpr double kMaxLead = 0.10;      // предел упреждения, с
// Картинка игры на мониторе старше камеры, прочитанной модом: очередь кадров и генерация кадров DLSS (×3 у владельца:
// 168 к/с на экране при 56 настоящих). Поэтому по умолчанию экран показывает камеру «в прошлом» (замер 08.11).
constexpr int kDefaultAdjustMs = -24;
constexpr double kMinLead = -0.12;

double qpcFreq() {
#ifdef Q_OS_WIN
    static const double f = [] {
        LARGE_INTEGER v;
        QueryPerformanceFrequency(&v);
        return double(v.QuadPart);
    }();
    return f;
#else
    return 1e9;
#endif
}

/// Часы хоста — QPC в секундах (в тех же единицах, что и время vblank у DWM).
double hostNow() {
#ifdef Q_OS_WIN
    LARGE_INTEGER v;
    QueryPerformanceCounter(&v);
    return double(v.QuadPart) / qpcFreq();
#else
    static QElapsedTimer t;
    if (!t.isValid()) t.start();
    return t.nsecsElapsed() / 1e9;
#endif
}

V3 v3(const QJsonValue &v) {
    const QJsonArray a = v.toArray();
    return {a.at(0).toDouble(), a.at(1).toDouble(), a.at(2).toDouble()};
}

/// Файл мода целиком; читаем с разрешением на запись и удаление, чтобы не мешать моду переписывать его.
QByteArray readShared(const QString &path) {
#ifdef Q_OS_WIN
    HANDLE h = CreateFileW(reinterpret_cast<LPCWSTR>(path.utf16()), GENERIC_READ,
                           FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING,
                           FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) return {};
    char buf[4096];
    DWORD n = 0;
    const BOOL ok = ReadFile(h, buf, sizeof buf, &n, nullptr);
    CloseHandle(h);
    return ok ? QByteArray(buf, int(n)) : QByteArray();
#else
    QFile f(path);
    return f.open(QIODevice::ReadOnly) ? f.read(4096) : QByteArray();
#endif
}

QString settingsFile() { return app::AppDir::file(QStringLiteral("cinema.json")); }

} // namespace

GameLink::GameLink(ScreenQuad *quad, QObject *parent) : QObject(parent), m_quad(quad) {
    QFile f(settingsFile());
    if (f.open(QIODevice::ReadOnly))
        m_adjustMs = QJsonDocument::fromJson(f.readAll()).object().value(QStringLiteral("adjustMs")).toInt(kDefaultAdjustMs);
}

void GameLink::setGameExePath(const QString &exePath) {
    if (exePath.isEmpty()) return;
    setModDir(QDir(QFileInfo(exePath).absolutePath())
                  .filePath(QStringLiteral("plugins/cyber_engine_tweaks/mods/SuslikCinema")));
}

void GameLink::setModDir(const QString &dir) {
    const QString d = QDir::toNativeSeparators(QDir::cleanPath(dir));
    if (d == m_modDir) return;
    m_modDir = d;
    m_statePath = QDir(d).filePath(QStringLiteral("state.json"));
    m_screenPath = QDir(d).filePath(QStringLiteral("screen.json"));
    m_state = State();
    m_screenRev = -1;
    m_pred.reset();
    app::PlayerLog::write(QStringLiteral("[cinema] мод: %1 (%2)")
                              .arg(d, QFileInfo::exists(d) ? QStringLiteral("есть") : QStringLiteral("папки нет")));
}

bool GameLink::listener(CameraPose &cam, V3 &center, double &width, double &wall) const {
    if (!m_live || !m_state.cam || !m_hasScreen) return false;
    cam = m_state.pose;
    center = (m_corners[0] + m_corners[1] + m_corners[2] + m_corners[3]) * 0.25;
    const V3 top = m_corners[1] - m_corners[0];
    width = std::sqrt(dot(top, top));
    // occl: стена (Static) — 1, предмет (Dynamic) — 0,5; глушит только стена
    wall = std::clamp((m_occlSmooth - 0.5) * 2, 0.0, 1.0);
    return true;
}

void GameLink::setAdjustMs(int ms) {
    ms = std::clamp(ms, -100, 100);
    if (ms == m_adjustMs) {
        showOsd(QStringLiteral("Упреждение: поправка %1 мс").arg(ms));
        return;
    }
    m_adjustMs = ms;
    QSaveFile f(settingsFile());
    if (f.open(QIODevice::WriteOnly)) {
        f.write(QJsonDocument(QJsonObject{{QStringLiteral("adjustMs"), ms}}).toJson());
        f.commit();
    }
    app::PlayerLog::write(QStringLiteral("[cinema] поправка упреждения %1 мс").arg(ms));
    showOsd(QStringLiteral("Упреждение: поправка %1%2 мс").arg(ms > 0 ? QStringLiteral("+") : QString()).arg(ms));
}

void GameLink::startRecording() {
    if (m_rec.isOpen()) return;
    m_rec.setFileName(app::AppDir::file(QStringLiteral("cinema-rec.csv")));
    if (!m_rec.open(QIODevice::WriteOnly | QIODevice::Truncate | QIODevice::Text)) return;
    m_rec.write("host,seq,game_t,game_host,lead,adjust,speed,opacity,hide,x0,y0,x1,y1,x2,y2,x3,y3\n");
    m_recUntil = hostNow() + 10;
    app::PlayerLog::write(QStringLiteral("[cinema] запись 10 с: ") + m_rec.fileName());
    showOsd(QStringLiteral("Запись 10 с"));
}

bool GameLink::readState(State &s) {
    const QByteArray raw = readShared(m_statePath);
    if (raw.isEmpty()) return false;
    const QJsonObject o = QJsonDocument::fromJson(raw).object();
    const qint64 seq = o.value(QStringLiteral("seq")).toInteger(-1);
    if (seq < 0 || o.value(QStringLiteral("end")).toInteger(-2) != seq) {
        ++m_statTorn; // мод переписывает файл прямо сейчас
        return false;
    }
    s.seq = seq;
    s.rev = o.value(QStringLiteral("rev")).toInteger(-1);
    s.pose.t = o.value(QStringLiteral("t")).toDouble();
    s.cam = o.value(QStringLiteral("cam")).toBool(true) && o.contains(QStringLiteral("pos"));
    if (!s.cam) return true;
    s.pose.pos = v3(o.value(QStringLiteral("pos")));
    s.pose.f = v3(o.value(QStringLiteral("f")));
    s.pose.r = v3(o.value(QStringLiteral("r")));
    s.pose.u = v3(o.value(QStringLiteral("u")));
    s.pose.kx = o.value(QStringLiteral("kx")).toDouble(1);
    s.pose.ky = o.value(QStringLiteral("ky")).toDouble(1);
    s.pose.ox = o.value(QStringLiteral("ox")).toDouble();
    s.pose.oy = o.value(QStringLiteral("oy")).toDouble();
    s.W = o.value(QStringLiteral("W")).toInt();
    s.H = o.value(QStringLiteral("H")).toInt();
    s.visible = o.value(QStringLiteral("visible")).toBool();
    s.paused = o.value(QStringLiteral("paused")).toBool();
    s.cal = o.value(QStringLiteral("cal")).toBool();
    s.occl = o.value(QStringLiteral("occl")).toDouble();
    s.hide = o.value(QStringLiteral("hide")).toString();
    s.fade = std::clamp(o.value(QStringLiteral("fade")).toDouble(1), 0.0, 1.0);
    return true;
}

void GameLink::loadScreen() {
    const QJsonObject o = QJsonDocument::fromJson(readShared(m_screenPath)).object();
    if (o.isEmpty()) return; // недописан — попробуем в следующем кадре
    m_screenRev = o.value(QStringLiteral("rev")).toInteger(-1);
    const QJsonArray cs = o.value(QStringLiteral("corners")).toArray();
    m_hasScreen = cs.size() == 4;
    if (!m_hasScreen) {
        app::PlayerLog::write(QStringLiteral("[cinema] экран в игре не задан (rev %1)").arg(m_screenRev));
        return;
    }
    for (int i = 0; i < 4; ++i) m_corners[i] = v3(cs.at(i));
    m_normal = normalized(v3(o.value(QStringLiteral("n"))));
    const double w = o.value(QStringLiteral("w")).toDouble(), h = o.value(QStringLiteral("h")).toDouble();
    m_aspect = (w > 0 && h > 0) ? w / h : 16.0 / 9.0;
    QString fit = o.value(QStringLiteral("fit")).toString(QStringLiteral("fit"));
    if (fit != QLatin1String("fill") && fit != QLatin1String("stretch")) fit = QStringLiteral("fit");
    if (fit != m_fit) {
        m_fit = fit;
        emit fitChanged(fit);
    }
    m_srcW = 0; // пересчитать буфер кадра под новый размер
    app::PlayerLog::write(QStringLiteral("[cinema] экран rev %1: %2 × %3 м").arg(m_screenRev).arg(w, 0, 'f', 2).arg(h, 0, 'f', 2));
}

double GameLink::presentTime(double now) const {
#ifdef Q_OS_WIN
    DWM_TIMING_INFO ti{};
    ti.cbSize = sizeof ti;
    if (SUCCEEDED(DwmGetCompositionTimingInfo(nullptr, &ti)) && ti.qpcRefreshPeriod > 0) {
        const double period = double(ti.qpcRefreshPeriod) / qpcFreq();
        const double vblank = double(ti.qpcVBlank) / qpcFreq();
        // Ближайший vblank после «сейчас», затем ещё m_presentFrames периодов (рисование и композиция DWM)
        const double k = std::floor((now - vblank) / period) + 1;
        return vblank + (k + m_presentFrames) * period;
    }
#endif
    return now + (1 + m_presentFrames) / 60.0;
}

void GameLink::frame(const QSizeF &view) {
    if (m_modDir.isEmpty()) return;
    const double now = hostNow();
    if (!m_osd.isEmpty() && m_osdTimer.elapsed() > 1500) {
        m_osd.clear();
        emit osdChanged();
    }

    State s;
    if (readState(s) && s.seq != m_state.seq) {
        m_clock.sample(now, s.pose.t);
        if (s.cam) m_pred.push(s.pose);
        else m_pred.reset();
        const bool wasPaused = m_state.paused;
        m_state = s;
        m_lastNewSec = now;
        if (wasPaused != s.paused) emit gamePausedChanged();
        ++m_statStates;
        setLive(true);
    }
    if (m_live && m_state.rev != m_screenRev) loadScreen();
    ++m_statFrames;
    if (m_lastFrame > 0) {
        const double gap = now - m_lastFrame;
        if (gap > 0.025) ++m_statLong; // кадр оверлея опоздал: экран на это время стоит, пока камера движется
        m_statMaxGap = std::max(m_statMaxGap, gap);
        m_frameDt = std::clamp(gap, 0.001, 0.1);
    }
    m_lastFrame = now;
    logStats(now);
    if (!m_live || m_paused) return;

    // Почему экран не показываем (для журнала) — или пусто
    QString hide;
    if (now - m_lastNewSec > kStaleSec) hide = QStringLiteral("нет кадров игры");
    else if (!m_state.cam || !m_pred.valid()) hide = QStringLiteral("нет камеры");
    else if (m_state.paused) hide = QStringLiteral("пауза игры");
    // Оверлей рисуется поверх оружия и интерфейса игры: мод просит спрятать экран, когда они на экране
    else if (m_state.hide == QLatin1String("weapon")) hide = QStringLiteral("оружие в руках");
    else if (m_state.hide == QLatin1String("wheel")) hide = QStringLiteral("колесо оружия или замедление");
    else if (!m_state.hide.isEmpty()) hide = QStringLiteral("скрыт в моде");
    else if (!m_gameActive) hide = QStringLiteral("игра не на переднем плане");
    else if (!m_hasScreen) hide = QStringLiteral("экран не задан");

    Quad q{};          // для журнала и записи — рамка видимой части в долях окна
    QTransform xf;
    QRectF bounds;
    double lead = 0;
    CameraPose cam;
    if (hide.isEmpty()) {
        const double target = presentTime(now) + m_adjustMs / 1000.0;
        lead = std::clamp(target - m_clock.toHost(m_state.pose.t), kMinLead, kMaxLead);
        cam = m_pred.predict(lead);
        m_statLead += lead;
        m_statAge += now - m_clock.toHost(m_state.pose.t);
        m_statSpeedMax = std::max(m_statSpeedMax, m_pred.angularSpeedDeg());
        if (!screenTransform(cam, m_corners, m_normal, m_quad->sourceWidth(), m_quad->sourceHeight(), xf, bounds))
            hide = QStringLiteral("экран вне кадра");
        else
            q = {bounds.topLeft(), bounds.topRight(), bounds.bottomRight(), bounds.bottomLeft()};
    }
    if (m_rec.isOpen()) {
        m_rec.write(QStringLiteral("%1,%2,%3,%4,%5,%6,%7,%8,%9")
                        .arg(now, 0, 'f', 5).arg(m_state.seq).arg(m_state.pose.t, 0, 'f', 4)
                        .arg(m_clock.toHost(m_state.pose.t), 0, 'f', 5).arg(lead * 1000, 0, 'f', 2).arg(m_adjustMs)
                        .arg(m_pred.angularSpeedDeg(), 0, 'f', 1).arg(m_opacity, 0, 'f', 2)
                        .arg(hide.isEmpty() ? QStringLiteral("-") : QStringLiteral("h"))
                        .toUtf8());
        for (const QPointF &p : q) m_rec.write(QStringLiteral(",%1,%2").arg(p.x(), 0, 'f', 5).arg(p.y(), 0, 'f', 5).toUtf8());
        m_rec.write("\n");
        if (now > m_recUntil) {
            m_rec.close();
            app::PlayerLog::write(QStringLiteral("[cinema] запись готова"));
            showOsd(QStringLiteral("Запись готова"));
        }
    }
    if (hide != m_hideReason) {
        app::PlayerLog::write(hide.isEmpty() ? QStringLiteral("[cinema] экран виден")
                                             : QStringLiteral("[cinema] экран скрыт: ") + hide);
        m_hideReason = hide;
    }
    if (!hide.isEmpty()) {
        setOpacity(0); // геометрии нет — без плавного угасания, иначе экран на миг висит не на месте
        return;
    }

    // Видимая часть на мониторе (обрезанная окном): по ней — размер буфера кадра. Сменился размер — преобразование
    // заново под новый кадр, иначе в этом кадре экран будет не того размера
    const QRectF onScreen = bounds.intersected(QRectF(0, 0, 1, 1));
    const double srcW = m_quad->sourceWidth();
    updateSourceSize(std::max(onScreen.width() * view.width(), onScreen.height() * view.height() * m_aspect));
    if (m_quad->sourceWidth() != srcW)
        screenTransform(cam, m_corners, m_normal, m_quad->sourceWidth(), m_quad->sourceHeight(), xf, bounds);
    m_quad->setLiveTransform(xf);
    if (m_statShown == 0)
        app::PlayerLog::write(QStringLiteral("[cinema] видимая часть %1,%2 – %3,%4, вид %5x%6, прозр %7")
                                  .arg(bounds.left(), 0, 'f', 3).arg(bounds.top(), 0, 'f', 3)
                                  .arg(bounds.right(), 0, 'f', 3).arg(bounds.bottom(), 0, 'f', 3)
                                  .arg(view.width()).arg(view.height()).arg(m_opacity, 0, 'f', 2));
    ++m_statShown;

    // Загораживание и множитель мода (дистанция, NPC перед экраном) — плавно, ~150 мс; быстрый поворот — полупрозрачно
    const double dt = m_frameDt;
    const double k = 1 - std::exp(-dt / 0.15);
    m_occlSmooth += (m_state.occl - m_occlSmooth) * k;
    m_fadeSmooth += (m_state.fade - m_fadeSmooth) * k;
    double o = (1 - m_occlSmooth) * m_fadeSmooth;
    const double speed = m_pred.angularSpeedDeg();
    if (speed > kFastTurnOnDeg) m_fastTurn = true;
    else if (speed < kFastTurnOffDeg) m_fastTurn = false;
    // Затемнение за ~0,12 с, возврат за ~0,3 с
    const double turnTarget = m_fastTurn ? 0.5 : 1.0;
    m_turnDim += (turnTarget - m_turnDim) * (1 - std::exp(-dt / (m_fastTurn ? 0.12 : 0.3)));
    o *= m_turnDim;
    if (m_opacity < o) o = std::min(o, m_opacity + dt / 0.1); // появление за ~0,1 с
    setOpacity(o);
}

void GameLink::updateSourceSize(double) {
    // Буфер кадра mpv постоянного размера: 1920 по ширине (или родная ширина видео, если меньше), высота — по
    // пропорциям экрана. Меняется только при смене фильма или пропорций экрана.
    // Почему не по размеру на мониторе (08.11): пересоздание буфера под 4K задерживало кадры оверлея на 50–320 мс,
    // а ступени 960/1280/1920 при обходе экрана давали мигание — новый буфер пуст, пока mpv не нарисует
    // следующий кадр фильма (до 40 мс).
    const double w = std::min(1920.0, m_videoWidth > 0 ? double(m_videoWidth) : 1920.0);
    if (w == m_srcW) return;
    m_srcW = w;
    m_quad->setSourceSize(std::round(w), std::round(w / m_aspect));
}

void GameLink::setOpacity(double o) {
    if (std::abs(o - m_opacity) < 1e-3 && !(o == 0 && m_opacity != 0)) return;
    m_opacity = o;
    emit opacityChanged();
}

void GameLink::setLive(bool on) {
    if (on == m_live) return;
    m_live = on;
    app::PlayerLog::write(QStringLiteral("[cinema] связь с модом %1").arg(on ? QStringLiteral("есть") : QStringLiteral("нет")));
    emit liveChanged();
}

void GameLink::showOsd(const QString &s) {
    m_osd = s;
    m_osdTimer.restart();
    emit osdChanged();
}

void GameLink::logStats(double now) {
    if (m_statT == 0) m_statT = now;
    const double span = now - m_statT;
    if (span < 10) return;
    if (m_statStates > 0 || m_live) {
        const int n = std::max(1, m_statShown);
        app::PlayerLog::write(QStringLiteral("[cinema] 10 с: кадров %1/с, игры %2/с, недописано %3, показан %4%, "
                                             "упреждение %5 мс (возраст %6 мс, поправка %7), поворот до %8°/с, буфер %9, "
                                             "долгих кадров %10 (до %11 мс)")
                                  .arg(m_statFrames / span, 0, 'f', 1)
                                  .arg(m_statStates / span, 0, 'f', 1)
                                  .arg(m_statTorn)
                                  .arg(100 * m_statShown / std::max(1, m_statFrames))
                                  .arg(1000 * m_statLead / n, 0, 'f', 1)
                                  .arg(1000 * m_statAge / n, 0, 'f', 1)
                                  .arg(m_adjustMs)
                                  .arg(m_statSpeedMax, 0, 'f', 0)
                                  .arg(m_srcW, 0, 'f', 0)
                                  .arg(m_statLong)
                                  .arg(1000 * m_statMaxGap, 0, 'f', 0));
    }
    m_statT = now;
    m_statFrames = m_statStates = m_statTorn = m_statShown = m_statLong = 0;
    m_statMaxGap = 0;
    m_statLead = m_statAge = m_statSpeedMax = 0;
}

} // namespace suslik::cinema
