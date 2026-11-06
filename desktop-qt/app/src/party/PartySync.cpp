#include "PartySync.h"
#include "../Library.h"
#include "../player/PlayerLog.h"
#include "../player/PlayerModel.h"

#include "suslik/core/Strings.h"
#include "suslik/core/TimeText.h"

#include <QDateTime>

namespace suslik::app {

using namespace suslik::core;

namespace {
QString S(const char *s) { return QString::fromUtf8(s); }
} // namespace

PartySync::PartySync(PlayerModel &player, Party &party, QObject *parent)
    : QObject(parent), m_player(player), m_party(party),
      m_engine(party, [](const QString &s) { PlayerLog::write(s); }, [this](const PartyNotice &n) {
          // настоящее время: имя ничего не говорит о поле, «ставит» подходит всем
          const QString who = m_party.hostWho();
          QString text;
          switch (n.kind) {
          case PartyNotice::Kind::Paused: text = tx(S("party.stavit_pauzu"), {{"who", who}}); break;
          case PartyNotice::Kind::Resumed: text = tx(S("party.prodolzhaet"), {{"who", who}}); break;
          case PartyNotice::Kind::Seeked: text = tx(S("party.peremativaet_na"), {{"who", who}, {"time", clockText(n.posMs)}}); break;
          case PartyNotice::Kind::Waiting: {
              QStringList names;
              for (const auto &m : m_party.memberList())
                  if (m.buffering && m.key != m_party.myKey()) names << m.name;
              if (!names.isEmpty()) text = tx(S("party.zhdyom_zagruzku"), {{"names", names.join(S(", "))}});
              break;
          }
          }
          if (!text.isEmpty()) m_player.showNotice(text);
      }) {
    m_timer.setInterval(int(PartySyncEngine::TICK_MS));
    connect(&m_timer, &QTimer::timeout, this, &PartySync::tick);
    connect(&m_player, &PlayerModel::openChanged, this, &PartySync::onOpen);
    connect(&m_player, &PlayerModel::entryChanged, this, &PartySync::onEntry);
    connect(&m_party, &Party::changed, this, &PartySync::onRoom);
    // гость / не гость, пауза ведущего — у кнопок плеера
    connect(&m_party, &Party::changed, &m_player, &PlayerModel::partyChanged);
    connect(&m_player, &PlayerModel::entryChanged, &m_player, &PlayerModel::partyChanged);
    connect(&m_player, &PlayerModel::openChanged, &m_player, &PlayerModel::partyChanged);
}

// ---- плеер для движка синхронизации ----

// Время комнаты — шкала раздачи ведущего. Гость на раздаче другой длины смотрит со сдвигом (timelineShiftMs):
// движку отдаётся время в шкале комнаты, перемотка комнаты переводится обратно в свою шкалу
qint64 PartySync::shiftMs() const {
    if (!m_party.inRoom() || m_party.isHost()) return 0;
    // Новый файл ещё не сообщил длину: сдвиг прежний (иначе он скакнул бы на длине прежнего файла)
    if (m_player.fileLength() <= 0) return m_loggedShift;
    return m_party.shiftFor(myVariantKey(), m_player.fileLength());
}

QString PartySync::myVariantKey() const {
    return m_player.activeVariantKey().isEmpty() ? m_player.activeWatchKey() : m_player.activeVariantKey();
}

void PartySync::nudge(qint64 deltaMs) {
    if (!canNudge() || deltaMs == 0) return;
    const qint64 t = m_player.syncTimeMs();
    m_party.nudgeShift(myVariantKey(), m_player.fileLength(), deltaMs);
    // Подстройка меньше допуска дрейфа (0,3 с) сама не перемотала бы: место сдвигается сразу на столько же
    m_player.roomSeekTo(std::max<qint64>(0, t + deltaMs));
    emit shiftChanged();
}

bool PartySync::canNudge() const {
    return m_watching && m_party.inRoom() && !m_party.isHost() && !m_party.hostVariantKey().isEmpty() &&
           m_party.hostVariantKey() != myVariantKey();
}

qint64 PartySync::timeMs() const { return m_player.syncTimeMs() - shiftMs(); }
bool PartySync::busy() const { return m_player.syncBusy(); }
qint64 PartySync::engineMs() const { return m_player.engineMs() - shiftMs(); }
qint64 PartySync::seekStamp() const { return m_player.seekStamp(); }
bool PartySync::paused() const { return m_player.paused(); }
bool PartySync::running() const { return m_player.running(); }
float PartySync::userRate() const { return float(m_player.rate()); }
void PartySync::applyPaused(bool p) { m_player.roomSetPaused(p); }
void PartySync::seekTo(qint64 ms) { m_player.roomSeekTo(std::max<qint64>(0, ms + shiftMs())); }
void PartySync::setRate(float r) { m_player.engineRate(r); }

// ---- открыли, сменили серию, закрыли ----

void PartySync::onOpen() {
    const bool showing = m_player.isOpen() && !m_player.closing();
    if (showing && !m_watching) {
        m_watching = true;
        ++m_party.watching;
        m_showedRoomFilm = false;
        m_episodeNotice.clear();
        // в комнате фильм ведущего становится фильмом комнаты (другой фильм гостя играет только у него)
        const QString key = m_player.activeWatchKey();
        if (!key.isEmpty() && m_party.inRoom() && m_party.isHost()) {
            PartyFileRef ref{key, m_player.entryLabel()};
            if (!m_player.canonicalId().isEmpty()) ref.canonicalId = m_player.canonicalId();
            ref.edition = m_player.edition();
            ref.variants = m_player.requestVariants();
            m_party.putFilm(ref);
            if (!m_player.activeVariantKey().isEmpty() && m_player.activeVariantKey() != ref.key) {
                m_party.sendVariantChange(m_player.activeVariantKey());
            }
        }
        m_roomKey = S("\x01"); // onRoom — как при первом показе
        m_timer.start();
        onRoom();
    } else if (!showing && m_watching) {
        m_watching = false;
        --m_party.watching;
        m_timer.stop();
        m_engine.idle(*this);
        // ведущий закрыл фильм: комната остаётся без него (через миг — пересоздание плеера закрытием не считается)
        if (m_party.isHost() && !m_lastKey.isEmpty() && (m_party.fileKey() == m_lastKey || (m_party.file() && m_party.file()->matches(m_lastKey, m_player.canonicalId())))) {
            m_party.closeIfNobodyWatches();
        }
    }
    if (showing) m_lastKey = m_player.activeWatchKey();
}

/// Ведущий перешёл к другой серии этой очереди: комната идёт за ним.
void PartySync::onEntry() {
    if (!m_watching) return;
    const QString key = m_player.activeWatchKey();
    m_lastKey = key;
    const auto &f = m_party.file();
    if (!f || key.isEmpty() || !m_party.isHost()) return;
    if (key == f->key && m_player.currentWatchKey() == f->key) return;
    if (m_player.findEpisodeIndex(*f) < 0 && !m_player.watchKeys().contains(f->key) && !f->matches(key, m_player.canonicalId())) return;
    PartyFileRef ref{key, m_player.entryLabel()};
    if (!m_player.canonicalId().isEmpty()) ref.canonicalId = m_player.canonicalId();
    ref.edition = m_player.edition();
    ref.variants = m_player.requestVariants();
    m_party.putFilm(ref);
    if (!m_player.activeVariantKey().isEmpty() && m_player.activeVariantKey() != ref.key) {
        m_party.sendVariantChange(m_player.activeVariantKey());
    }
}

/// Фильм комнаты сменился: плеер гостя включает серию ведущего, если она в его очереди.
void PartySync::onRoom() {
    if (!m_watching) return;
    const QString rk = m_party.fileKey();
    if (rk == m_roomKey && !m_roomKey.isEmpty()) return;
    m_roomKey = rk;
    if (rk.isEmpty()) return;
    const auto &f = m_party.file();
    const QString key = m_player.activeWatchKey();
    if (f && f->matches(key, m_player.canonicalId())) return;
    const int i = f ? m_player.findEpisodeIndex(*f) : int(m_player.watchKeys().indexOf(rk));
    if (rk != key && i >= 0 && i != m_player.index() && !m_player.closing()) {
        PlayerLog::write(S("[party] room episode -> %1").arg(i));
        m_player.roomPlayEntry(i);
        if (!m_party.isHost()) m_episodeNotice = tx(S("party.vklyuchaet"), {{"who", m_party.hostWho()}, {"title", m_party.fileTitle()}});
    }
}

void PartySync::tick() {
    const QString key = m_player.activeWatchKey();
    const bool inRoom = m_party.inRoom();
    const auto &f = m_party.file();
    const QString rk = m_party.fileKey();
    const bool matches = f ? f->matches(key, m_player.canonicalId()) : (!rk.isEmpty() && rk == key);
    // Ведущий закрыл фильм (в комнате его больше нет): гость, смотревший его, тоже закрывает
    if (inRoom && !key.isEmpty() && matches) m_showedRoomFilm = true;
    else if (!inRoom || !rk.isEmpty()) m_showedRoomFilm = false;
    else if (m_showedRoomFilm && !m_party.isHost() && !m_player.closing()) {
        m_showedRoomFilm = false;
        PlayerLog::write(S("[party] host closed the film: closing the player"));
        m_player.close();
        return;
    }
    const bool ready = m_player.started() && !m_player.closing();
    if (inRoom && !key.isEmpty() && matches && ready) {
        if (!m_episodeNotice.isEmpty()) {
            m_player.showNotice(m_episodeNotice);
            m_episodeNotice.clear();
        }
        // Длина своей раздачи: по ней гости выравнивают время (у ведущего — сервер сдвигает комнату при смене раздачи)
        const QString vk = m_player.activeVariantKey().isEmpty() ? key : m_player.activeVariantKey();
        if (m_player.fileLength() > 0) {
            const auto vars = m_player.requestVariants();
            const core::PartyVariant *mine = nullptr;
            for (const auto &v : vars) if (v.key == vk) { mine = &v; break; }
            m_party.reportVariant(vk, m_player.fileLength(), mine);
        }
        const qint64 shift = shiftMs();
        const bool can = canNudge();
        if (can != m_shownCanNudge) { m_shownCanNudge = can; emit shiftChanged(); }
        if (shift != m_loggedShift) {
            m_loggedShift = shift;
            emit shiftChanged();
            PlayerLog::write(shift == 0 ? S("[party] my version is on the host's timeline")
                                        : S("[party] my version is %1 ms %2 than the host's: time shifted")
                                              .arg(std::abs(shift)).arg(shift > 0 ? S("longer") : S("shorter")));
        }
        m_engine.tick(*this, key, QDateTime::currentMSecsSinceEpoch());
    } else {
        m_engine.idle(*this);
    }
}

} // namespace suslik::app
