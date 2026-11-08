#include "CinemaParty.h"
#include "CinemaSession.h"

#include "Library.h"
#include "party/Party.h"
#include "player/MpvEngine.h"
#include "player/PlayerLog.h"

#include "suslik/core/Strings.h"
#include "suslik/core/TimeText.h"

#include <QDateTime>
#include <QJsonArray>

namespace suslik::cinema {

using namespace suslik::core;

namespace {
QString S(const char *s) { return QString::fromUtf8(s); }
} // namespace

CinemaParty::CinemaParty(app::Party &party, CinemaSession &session, app::MpvEngine &mpv, app::Library &lib, QObject *parent)
    : QObject(parent), m_party(party), m_session(session), m_mpv(mpv), m_lib(lib),
      m_engine(party, [](const QString &s) { app::PlayerLog::write(s); }, [this](const PartyNotice &n) {
          // те же подсказки, что в плеере ПК (строки ru.json ПК-версии), — на телефон
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
          if (!text.isEmpty()) m_session.notify(text);
      }) {
    m_timer.setInterval(int(PartySyncEngine::TICK_MS));
    connect(&m_timer, &QTimer::timeout, this, &CinemaParty::tick);
    m_timer.start();
    m_followRetry.setSingleShot(true);
    m_followRetry.setInterval(500);
    connect(&m_followRetry, &QTimer::timeout, this, &CinemaParty::follow);
    connect(&m_party, &app::Party::changed, this, &CinemaParty::onRoom);
    connect(&m_party, &app::Party::refreshLibraryRequested, this, [this] {
        if (!m_party.isHost()) follow();
    });
    connect(&m_session, &CinemaSession::opened, this, &CinemaParty::onOpened);
    connect(&m_lib, &app::Library::versionChanged, this, [this] {
        if (m_party.guest() && m_party.fileKey() != m_followed) follow();
    });
    // вышли из аккаунта — и из комнаты (как у ПК)
    connect(&m_lib, &app::Library::authenticatedChanged, this, [this] {
        if (m_lib.authenticated() == 0 && m_party.inRoom()) m_party.leave();
    });
    m_session.setControlLock([this] { return lockReason(); });
    m_session.setExtraCommands([this](const QString &op, const QJsonObject &c) { return command(op, c); });
    m_session.setExtraStatus([this](QJsonObject &o) { status(o); });
}

// ---- команды и состояние для телефона ----

bool CinemaParty::command(const QString &op, const QJsonObject &c) {
    if (op == QLatin1String("roomCreate")) m_party.create();
    else if (op == QLatin1String("roomJoin")) m_party.join(c.value(S("code")).toString().trimmed());
    else if (op == QLatin1String("roomLeave")) m_party.leave();
    else if (op == QLatin1String("roomFollow")) { // гость остановил фильм и снова идёт за ведущим
        m_followed.clear();
        m_seenKey.clear();
        follow();
    }
    else return false;
    return true;
}

void CinemaParty::status(QJsonObject &o) const {
    QJsonObject r{{S("in"), m_party.inRoom()}, {S("busy"), m_party.busy()}, {S("error"), m_party.error()}};
    if (m_party.inRoom()) {
        QJsonArray names;
        for (const auto &m : m_party.memberList()) names.append(m.name);
        r.insert(S("code"), m_party.roomId());
        r.insert(S("host"), m_party.isHost());
        r.insert(S("hostName"), m_party.hostWho());
        r.insert(S("members"), names);
        r.insert(S("title"), m_party.fileTitle());
        r.insert(S("line"), m_party.stateLine());
        r.insert(S("connected"), m_party.connected());
        r.insert(S("notice"), m_party.notice());
        r.insert(S("watching"), roomFilm());
    }
    o.insert(S("room"), r);
}

QString CinemaParty::lockReason() const {
    if (m_party.guest() && roomFilm()) return S("Паузой и перемоткой управляет %1").arg(m_party.hostWho());
    return {};
}

// ---- плеер для движка синхронизации ----

bool CinemaParty::roomFilm() const {
    const QString key = m_session.playingKey();
    if (!m_party.inRoom() || key.isEmpty()) return false;
    const QString cid = m_session.canonicalId().value_or(QString());
    if (const auto &f = m_party.file()) return f->matches(key, cid);
    return m_party.fileKey() == key;
}

// Время комнаты — шкала раздачи ведущего; гость на раздаче другой длины смотрит со сдвигом (как PartySync у ПК)
qint64 CinemaParty::shiftMs() const {
    if (!m_party.inRoom() || m_party.isHost() || m_mpv.lengthMs() <= 0) return 0;
    return m_party.shiftFor(m_session.playingKey(), m_mpv.lengthMs());
}

qint64 CinemaParty::timeMs() const { return m_session.syncTimeMs() - shiftMs(); }
qint64 CinemaParty::engineMs() const { return m_mpv.timeMs() - shiftMs(); }
qint64 CinemaParty::seekStamp() const { return m_session.seekStamp(); }
bool CinemaParty::paused() const { return m_mpv.paused(); }
bool CinemaParty::running() const { return m_mpv.playing(); }
void CinemaParty::applyPaused(bool p) {
    if (m_mpv.paused() != p) m_mpv.setPaused(p);
}
void CinemaParty::seekTo(qint64 ms) { m_session.roomSeek(std::max<qint64>(0, ms + shiftMs())); }
void CinemaParty::setRate(float r) { m_mpv.setRate(r); }

// ---- фильм комнаты ----

/// Ведущий включил фильм: он становится фильмом комнаты (как PartySync::onOpen у ПК).
void CinemaParty::onOpened(const QString &key) {
    if (!m_party.inRoom() || !m_party.isHost() || key.isEmpty()) return;
    PartyFileRef ref{key, m_session.playingTitle()};
    ref.canonicalId = m_session.canonicalId();
    ref.edition = m_session.edition();
    ref.variants = m_session.variants();
    app::PlayerLog::write(S("[party] фильм комнаты: %1").arg(key));
    m_party.putFilm(ref);
}

void CinemaParty::onRoom() {
    // ведущий создал комнату, когда фильм уже шёл: фильм — в комнату
    if (m_party.inRoom() && m_party.isHost() && m_party.fileKey().isEmpty() && !m_session.playingKey().isEmpty() &&
        m_mpv.loaded() && m_party.watching == 0)
        onOpened(m_session.playingKey());
    follow();
}

/// Гость идёт за ведущим (PartyFollow.qml у ПК): фильм и место комнаты, без выбора релиза.
void CinemaParty::follow() {
    if (!m_party.guest()) {
        m_followed.clear();
        return;
    }
    const QString key = m_party.fileKey();
    if (key.isEmpty()) {
        // ведущий закрыл фильм — у гостя он тоже останавливается
        if (!m_followed.isEmpty() && !m_session.playingKey().isEmpty() && m_session.playingKey() == m_lastKey) {
            app::PlayerLog::write(S("[party] ведущий закрыл фильм"));
            m_session.notify(S("%1 закрыл фильм").arg(m_party.hostWho()));
            m_session.stop();
        }
        m_followed.clear();
        return;
    }
    if (key == m_followed) return;
    if (roomFilm()) {
        m_followed = key;
        return;
    }
    // ведущий только что включил фильм: место комнаты ещё 0, он сообщит своё через пару секунд — ждём до 2,5 с
    if (key != m_seenKey) {
        m_seenKey = key;
        m_seenAt.start();
    }
    if (m_party.roomMs() < 2000 && m_seenAt.elapsed() < 2500) {
        m_followRetry.start();
        return;
    }
    if (!m_session.playKey(key, m_party.roomMs())) {
        if (key != m_refreshed) {
            m_refreshed = key;
            m_session.notify(S("Фильма комнаты нет в медиатеке — обновляю её"));
            m_lib.refresh();
        }
        return;
    }
    m_followed = key;
    m_refreshed.clear();
    m_session.notify(tx(S("party.vklyuchaet"), {{"who", m_party.hostWho()}, {"title", m_party.fileTitle()}}));
}

void CinemaParty::setWatching(bool on) {
    if (on == m_watching) return;
    m_watching = on;
    m_party.watching += on ? 1 : -1;
    // ведущий остановил фильм: комната остаётся без него (если никто его здесь больше не показывает)
    const auto &f = m_party.file();
    if (!on && m_party.inRoom() && m_party.isHost() && !m_lastKey.isEmpty() &&
        (m_party.fileKey() == m_lastKey || (f && f->matches(m_lastKey, m_session.canonicalId().value_or(QString())))))
        m_party.closeIfNobodyWatches();
}

void CinemaParty::tick() {
    const bool film = roomFilm();
    const bool ready = film && m_mpv.loaded();
    if (film) m_lastKey = m_session.playingKey();
    setWatching(film);
    if (!ready) {
        m_engine.idle(*this);
        return;
    }
    const QString key = m_session.playingKey();
    if (m_mpv.lengthMs() > 0) m_party.reportVariant(key, m_mpv.lengthMs(), &m_session.variant());
    m_engine.tick(*this, key, QDateTime::currentMSecsSinceEpoch());
}

} // namespace suslik::cinema
