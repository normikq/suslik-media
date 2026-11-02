// Плеер и комната «Вместе»: что комнате видно и что она может (PlayerView.kt: partyGuest, SyncHooks).
#include "../Library.h"
#include "PlayerModel.h"
#include "MpvEngine.h"
#include "../party/Party.h"

#include <QDateTime>

namespace suslik::app {

namespace {
qint64 nowMs() { return QDateTime::currentMSecsSinceEpoch(); }
} // namespace

bool PlayerModel::partyGuest() const {
    const Party *p = Party::instance();
    const QString key = watchKey();
    return p && m_open && p->guest() && !key.isEmpty() && p->fileKey() == key;
}

bool PlayerModel::partyFilm() const {
    const Party *p = Party::instance();
    const QString key = watchKey();
    return p && m_open && p->inRoom() && !key.isEmpty() && p->fileKey() == key;
}

bool PlayerModel::playLocked() const {
    const Party *p = Party::instance();
    return partyGuest() && (!p->playing() || p->waiting());
}

QString PlayerModel::entryLabel() const {
    const int i = m_queue.size() > 1 ? m_index : m_req.startIndex;
    if (i >= 0 && i < m_req.presence.size()) {
        const Presence &pr = m_req.presence[i];
        return pr.episode ? pr.title + QStringLiteral(" · ") + *pr.episode : pr.title;
    }
    return m_title;
}

qint64 PlayerModel::engineMs() const { return m_engine->timeMs(); }

qint64 PlayerModel::syncTimeMs() const {
    if (nowMs() - m_lastSeekAt < 2500) return m_time;
    const qint64 e = m_engine->timeMs();
    return e > 0 ? e : m_time;
}

bool PlayerModel::running() const { return m_playing && m_engine->playing(); }

void PlayerModel::roomSetPaused(bool p) {
    if (paused() == p) return;
    m_byRoom = true;
    togglePause();
    m_byRoom = false;
}

void PlayerModel::roomSeekTo(qint64 t) {
    m_byRoom = true;
    seekTo(t);
    m_byRoom = false;
}

void PlayerModel::roomPlayEntry(int i) {
    m_byRoom = true;
    playEntry(i);
    m_byRoom = false;
}

void PlayerModel::engineRate(double r) { m_engine->setRate(r); }

} // namespace suslik::app
