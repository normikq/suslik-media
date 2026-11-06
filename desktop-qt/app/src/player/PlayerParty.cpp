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
    const QString key = activeWatchKey();
    if (!p || !m_open || !p->guest() || key.isEmpty()) return false;
    const auto f = p->file();
    if (f) return f->matches(key, m_canonicalId) || f->matches(watchKey(), m_canonicalId);
    return p->fileKey() == key || p->fileKey() == watchKey();
}

bool PlayerModel::partyFilm() const {
    const Party *p = Party::instance();
    const QString key = activeWatchKey();
    if (!p || !m_open || !p->inRoom() || key.isEmpty()) return false;
    const auto f = p->file();
    if (f) return f->matches(key, m_canonicalId) || f->matches(watchKey(), m_canonicalId);
    return p->fileKey() == key || p->fileKey() == watchKey();
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

std::optional<QString> PlayerModel::edition() const {
    if (!m_lib) return std::nullopt;
    const QString k = activeWatchKey();
    const qint64 magId = k.section(QLatin1Char('|'), 0, 0).toLongLong();
    const core::LibraryItem *it = magId > 0 ? m_lib->item(magId) : m_lib->item(m_req.magnetId);
    if (!it) return std::nullopt;
    if (const auto ai = it->m.acceptedAi(); ai && ai->edition && !ai->edition->isEmpty() && *ai->edition != QLatin1String("default")) {
        return ai->edition;
    }
    const QString parsed = core::parseEdition(it->m.name);
    if (!parsed.isEmpty() && parsed != QLatin1String("default")) return parsed;
    return std::nullopt;
}

int PlayerModel::findEpisodeIndex(const core::PartyFileRef &f) const {
    // Две раздачи, которые медиатека считает версиями одной работы, — та же работа и без canonical_id
    bool hint = false;
    if (m_lib && m_req.magnetId > 0) {
        const qint64 roomMag = f.key.section(QLatin1Char('|'), 0, 0).toLongLong();
        if (roomMag > 0)
            for (const auto &ver : m_lib->versionsOf(m_req.magnetId))
                if (ver.m.id == roomMag) { hint = true; break; }
    }
    return f.resolveIndex(m_req.watchKeys, m_canonicalId, hint);
}

} // namespace suslik::app
