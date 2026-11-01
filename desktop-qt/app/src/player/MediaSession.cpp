#include "MediaSession.h"
#include "DiscordRpc.h"
#include "DisplayAwake.h"
#include "MediaControls.h"
#include "PlayerLog.h"
#include "PlayerModel.h"
#include "../AppDir.h"
#include "../Library.h"
#include "../party/Party.h"

#include "suslik/core/Strings.h"

#include <QDateTime>
#include <QDir>
#include <QImage>
#include <QThreadPool>

namespace suslik::app {

namespace {
QString S(const char *s) { return QString::fromUtf8(s); }

/// Открытый адрес постера (TMDB) для Discord из пути /api/poster/img/<файл>.
QString publicPoster(const QString &path) {
    if (path.startsWith(S("https://")) || path.startsWith(S("http://"))) return path;
    const QString file = path.section(QLatin1Char('/'), -1).trimmed();
    if (file.isEmpty() || !file.contains(QLatin1Char('.'))) return {};
    return S("https://image.tmdb.org/t/p/w500/") + file;
}
} // namespace

MediaSession::MediaSession(PlayerModel &player, Library &lib, QObject *parent)
    : QObject(parent), m_player(player), m_lib(lib), m_smtc(new MediaControls(this)), m_discord(new DiscordRpc(this)) {
    m_discordDelay.setSingleShot(true);
    m_discordDelay.setInterval(700);
    connect(&m_discordDelay, &QTimer::timeout, this, &MediaSession::sendDiscord);
    connect(&m_player, &PlayerModel::openChanged, this, &MediaSession::onOpen);
    connect(&m_player, &PlayerModel::entryChanged, this, &MediaSession::onEntry);
    connect(&m_player, &PlayerModel::stateChanged, this, &MediaSession::onState);
    // «Смотрит вместе (N)»: изменился состав комнаты или фильм в ней
    if (Party *party = Party::instance()) {
        connect(party, &Party::changed, this, [this, party] {
            const int n = party->inRoom() && party->fileKey() == m_player.currentWatchKey() ? std::max(1, party->memberCount()) : 0;
            if (n != m_partyCount) { m_partyCount = n; scheduleDiscord(); }
        });
    }
    connect(&m_player, &PlayerModel::seeked, this, [this] {
        m_smtc->setTimeline(m_player.time(), m_player.length());
        scheduleDiscord();
    });
    connect(&m_player, &PlayerModel::timeChanged, this, [this] {
        // раз в целую секунду фильма: системные кнопки всё равно чаще не берут
        const qint64 sec = m_player.time() / 1000;
        if (sec == m_lastSec || !m_player.isOpen()) return;
        m_lastSec = sec;
        m_smtc->setTimeline(m_player.time(), m_player.length());
    });
    connect(m_smtc, &MediaControls::togglePause, &m_player, &PlayerModel::togglePause);
    connect(m_smtc, &MediaControls::next, &m_player, [this] { if (m_player.index() + 1 < m_player.queueSize()) m_player.playEntry(m_player.index() + 1); });
    connect(m_smtc, &MediaControls::previous, &m_player, [this] {
        if (m_player.index() > 0) m_player.playEntry(m_player.index() - 1); else m_player.seekTo(0);
    });
    connect(m_smtc, &MediaControls::seek, &m_player, &PlayerModel::seekTo);
}

void MediaSession::attach(QWindow *w) { m_smtc->attach(w); }

void MediaSession::onOpen() {
    if (m_player.isOpen() && !m_player.closing()) {
        preparePoster();
        onEntry();
        return;
    }
    // закрывается: всё убрать сразу
    DisplayAwake::set(false);
    m_smtc->stop();
    m_discordDelay.stop();
    m_discord->stop();
}

void MediaSession::onEntry() {
    if (!m_player.isOpen()) return;
    const auto [title, subtitle] = m_player.mediaTitles();
    m_smtc->setMetadata(title, subtitle);
    m_smtc->setCapabilities(m_player.index() + 1 < m_player.queueSize(), m_player.index() > 0 || m_player.time() > 5000);
    if (!m_posterFile.isEmpty()) m_smtc->setThumbnail(m_posterFile);
    scheduleDiscord();
}

void MediaSession::onState() {
    const bool active = m_player.isOpen() && !m_player.closing() && m_player.playing() && !m_player.paused();
    DisplayAwake::set(active);
    if (!m_player.isOpen() || m_player.closing()) return;
    m_smtc->setPlaying(!m_player.paused());
    scheduleDiscord();
}

void MediaSession::scheduleDiscord() {
    if (m_player.isOpen() && !m_player.closing() && m_player.started()) m_discordDelay.start();
}

void MediaSession::sendDiscord() {
    if (!m_player.isOpen() || m_player.closing() || !m_player.started()) return;
    const Presence p = m_player.presence();
    const bool paused = m_player.paused();
    DiscordRpc::Activity a;
    a.title = p.title;
    QStringList sub;
    if (p.episode) sub << *p.episode;
    const Party *party = Party::instance();
    const bool inParty = party && party->inRoom() && party->fileKey() == m_player.currentWatchKey();
    if (inParty)
        sub << core::tx(S("discord.smotrit_vmeste"), {{"count", QString::number(std::max(1, party->memberCount()))}}) +
                   (paused ? S(" · ") + core::tx(S("discord.pauza")) : QString());
    else if (paused) sub << core::tx(S("discord.pauza"));
    a.subtitle = sub.isEmpty() ? core::tx(S("discord.film")) : sub.join(S(" · "));
    a.playing = !paused;
    a.timeMs = m_player.time();
    a.lengthMs = m_player.length();
    a.posterUrl = m_posterUrl;
    a.largeText = p.episode ? S("%1 · %2").arg(p.title, *p.episode) : p.title;
    m_discord->update(a);
}

/// Постер карточки: адрес для Discord, файл 320 px для системных кнопок.
void MediaSession::preparePoster() {
    m_posterFile.clear();
    m_posterUrl.clear();
    const QStringList queries = m_player.posterQueries();
    if (queries.isEmpty()) return;
    const QString path = m_lib.posterPath(queries);
    if (path.isEmpty()) return;
    m_posterUrl = publicPoster(path);
    const int gen = ++m_posterGen;
    QPointer<MediaSession> self(this);
    m_lib.image(path, [self, gen](const QByteArray &bytes) {
        if (!self || self->m_posterGen != gen || bytes.isEmpty()) return;
        QImage img = QImage::fromData(bytes);
        if (img.isNull()) return;
        if (img.width() > 320) img = img.scaledToWidth(320, Qt::SmoothTransformation);
        const QString dir = AppDir::file(S("smtc"));
        QDir(dir).removeRecursively();
        QDir().mkpath(dir);
        const QString file = QDir(dir).filePath(S("poster-%1.jpg").arg(QDateTime::currentMSecsSinceEpoch()));
        if (!img.save(file, "JPG", 88)) return;
        self->m_posterFile = QDir::toNativeSeparators(file);
        self->m_smtc->setThumbnail(self->m_posterFile);
        PlayerLog::write(S("[smtc] poster %1").arg(file.section(QLatin1Char('/'), -1)));
    });
}

} // namespace suslik::app
