#include "SpatialSound.h"
#include "GameLink.h"

#include "player/Mpv.h"
#include "player/MpvEngine.h"
#include "player/PlayerLog.h"

#include <cmath>

namespace suslik::cinema {

namespace {
QString S(const char *s) { return QString::fromUtf8(s); }
} // namespace

// Звук сводится в стерео (экран — один источник), уши — по отдельности, потом общая громкость.
// Имена фильтров (volume@gl …) — цели af-command.
const char *SpatialSound::filterChain() {
    return "@c3d:lavfi=[aformat=channel_layouts=stereo,channelsplit=channel_layout=stereo[L][R];"
           "[L]lowpass@lpl=f=20000,volume@gl=1[l];[R]lowpass@lpr=f=20000,volume@gr=1[r];"
           "[l][r]join=inputs=2:channel_layout=stereo,volume@gm=1]";
}

SpatialSound::SpatialSound(app::MpvEngine &mpv, GameLink &link, QObject *parent) : QObject(parent), m_mpv(mpv), m_link(link) {
    m_timer.setInterval(50);
    connect(&m_timer, &QTimer::timeout, this, &SpatialSound::tick);
    m_timer.start();
}

void SpatialSound::setMode(Audio3D mode, bool muffle) {
    const bool changed = mode != m_mode;
    m_mode = mode;
    m_muffle = muffle;
    if (changed) {
        app::PlayerLog::write(S("[audio3d] режим %1").arg(int(mode)));
        install();
    }
}

void SpatialSound::install() {
    const app::mpv::Handle h = m_mpv.current();
    const app::mpv::Api *a = app::mpv::api();
    if (!h || !a) return;
    // выкл — цепочки нет вовсе: звук как в ПК-версии
    const int rc = a->set_property_string(h.get(), "af", m_mode == Audio3D::Off ? "" : filterChain());
    m_installed = rc >= 0 && m_mode != Audio3D::Off;
    m_cur = m_sent = SpatialMix{};
    if (rc < 0) app::PlayerLog::write(S("[audio3d] цепочка не встала: %1").arg(QString::fromUtf8(a->error_string(rc))));
}

void SpatialSound::send(const char *target, const char *cmd, double value) {
    const app::mpv::Handle h = m_mpv.current();
    const app::mpv::Api *a = app::mpv::api();
    if (!h || !a) return;
    const QByteArray v = QByteArray::number(value, 'f', value >= 100 ? 0 : 4);
    const char *args[] = {"af-command", "c3d", cmd, v.constData(), target, nullptr};
    // асинхронно: команда не ждёт звукового потока mpv (оверлей рисуется в этом же потоке)
    a->command_async(h.get(), 0, args);
}

void SpatialSound::tick() {
    if (!m_installed || !m_mpv.loaded()) return;
    SpatialMix target; // нет камеры (игра не идёт, мода нет) — звук как есть
    CameraPose cam;
    V3 center;
    double width = 0, wall = 0;
    if (m_link.listener(cam, center, width, wall)) target = spatialMix(cam, center, width, wall, m_mode, m_muffle);
    // ~0,15 с до цели: поворот головы слышен сразу, но без ступенек
    m_cur = approach(m_cur, target, 0.3);
    auto moved = [](double a, double b, double eps) { return std::abs(a - b) > eps; };
    auto movedHz = [](double a, double b) { return std::abs(std::log(a / b)) > 0.03; };
    if (moved(m_cur.gainL, m_sent.gainL, 0.005)) { send("volume@gl", "volume", m_cur.gainL); m_sent.gainL = m_cur.gainL; }
    if (moved(m_cur.gainR, m_sent.gainR, 0.005)) { send("volume@gr", "volume", m_cur.gainR); m_sent.gainR = m_cur.gainR; }
    if (moved(m_cur.master, m_sent.master, 0.005)) { send("volume@gm", "volume", m_cur.master); m_sent.master = m_cur.master; }
    if (movedHz(m_cur.lowpassL, m_sent.lowpassL)) { send("lowpass@lpl", "f", m_cur.lowpassL); m_sent.lowpassL = m_cur.lowpassL; }
    if (movedHz(m_cur.lowpassR, m_sent.lowpassR)) { send("lowpass@lpr", "f", m_cur.lowpassR); m_sent.lowpassR = m_cur.lowpassR; }
    if (++m_logTick % 200 == 0) // раз в 10 с — в журнал, для разбора
        app::PlayerLog::write(S("[audio3d] уши %1/%2, верха %3/%4 Гц, общая %5")
                                  .arg(m_cur.gainL, 0, 'f', 2).arg(m_cur.gainR, 0, 'f', 2)
                                  .arg(m_cur.lowpassL, 0, 'f', 0).arg(m_cur.lowpassR, 0, 'f', 0).arg(m_cur.master, 0, 'f', 2));
}

} // namespace suslik::cinema
