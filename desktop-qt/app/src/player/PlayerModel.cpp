#include "PlayerModel.h"
#include "PlayerLog.h"
#include "Reserve.h"
#include "../Library.h"
#include "../PlayModel.h"
#include "../Settings.h"
#include "../party/Party.h"

#include "suslik/core/ApiClient.h"
#include "suslik/core/CloudSync.h"
#include "suslik/core/MarkersDetector.h"
#include "suslik/core/Store.h"
#include "suslik/core/Strings.h"
#include "suslik/core/TimeText.h"
#include "suslik/core/UrlCoding.h"

#include <QDateTime>
#include <QDir>
#include <QJsonDocument>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QRegularExpression>
#include <QUrl>

namespace suslik::app {

using namespace suslik::core;

namespace {
QString S(const char *s) { return QString::fromUtf8(s); }
qint64 now() { return QDateTime::currentMSecsSinceEpoch(); }
Store &ui() { return Store::of(S("player_ui")); }
QString signedMs(qint64 n) { return n > 0 ? S("+%1").arg(n) : QString::number(n); }
QString num(qint64 v) { return QString::number(v); }

/// Сохранённый выбор субтитров «как решат умные субтитры» (после смены озвучки).
const QString SMART_SUBS = QStringLiteral("__smart__");
const QList<double> RATES{0.5, 0.75, 1, 1.25, 1.5, 1.75, 2};

QList<std::pair<QString, QString>> parseM3u(const QString &text) {
    QList<std::pair<QString, QString>> out;
    QString title;
    QString body = text;
    if (body.startsWith(QChar(0xFEFF))) body.remove(0, 1);
    for (const QString &raw : body.split(QLatin1Char('\n'))) {
        const QString line = raw.trimmed();
        if (line.startsWith(S("#EXTINF"))) {
            const int c = line.indexOf(QLatin1Char(','));
            title = c >= 0 ? line.mid(c + 1).trimmed() : QString();
        } else if (line.startsWith(S("http"))) {
            QString t = title;
            if (t.isEmpty()) t = line.section(QLatin1Char('?'), 0, 0).section(QLatin1Char('/'), -1);
            out.append({t, line});
            title.clear();
        }
    }
    return out;
}

MarkersDetector::Track forSubs(const QVariantMap &t) {
    MarkersDetector::Track r;
    r.id = t.value(S("id")).toInt();
    const QString title = t.value(S("title")).toString(), lang = t.value(S("lang")).toString();
    if (!title.isEmpty()) r.title = title;
    if (!lang.isEmpty()) r.lang = lang;
    r.forced = t.value(S("forced")).toBool();
    return r;
}

int selectedId(const QVariantList &tracks) {
    for (const QVariant &v : tracks)
        if (v.toMap().value(S("selected")).toBool()) return v.toMap().value(S("id")).toInt();
    return -1;
}
} // namespace

PlayerModel::PlayerModel(QObject *parent)
    : QObject(parent), m_engine(new MpvEngine(this)),
      m_supervisor(*this, {}, [this]() -> qint64 {
          if (m_catchingUp) return CatchUp::MAX_MS + 15000;
          return Reserve::proxy().isActive(m_currentUrl) ? 30000 : 12000;
      }) {
    m_catchUp = std::make_unique<CatchUp>(Reserve::proxy(), [](const QString &m) { PlayerLog::write(m); });
    m_volume = ui().get(S("volume")).value_or(S("100")).toInt();
    m_night = ui().get(S("night")) == S("1");
    m_subScale = std::clamp(ui().get(S("subScale")).value_or(S("100")).toInt(), 50, 300);
    m_rate = std::clamp(ui().get(S("rate")).value_or(S("1")).toDouble(), 0.25, 4.0);
    if (m_rate <= 0) m_rate = 1;

    connect(m_engine, &MpvEngine::stateChanged, this, &PlayerModel::onEngineState);
    connect(m_engine, &MpvEngine::timeChanged, this, [this] { if (m_engine->loaded()) evTime(m_engine->timeMs()); });
    connect(m_engine, &MpvEngine::lengthChanged, this, [this] {
        const qint64 l = m_engine->lengthMs();
        if (l > 0 && !m_switching) {
            m_length = l;
            Reserve::proxy().setDuration(m_currentUrl, l);
            emit timeChanged();
        }
    });
    connect(m_engine, &MpvEngine::opened, this, [this] {
        m_switching = false;
        emit stateChanged();
    });
    connect(m_engine, &MpvEngine::tracksChanged, this, [this] {
        if (now() < m_prefsUntil) m_prefsTimer.start(400);
        emit markersChanged();
        if (m_started && !m_markersAsked && m_length > 0) fetchMarkers();
    });
    connect(m_engine, &MpvEngine::finished, this, &PlayerModel::evFinished);
    connect(m_engine, &MpvEngine::failed, this, &PlayerModel::evError);

    m_tickTimer.setInterval(1000);
    connect(&m_tickTimer, &QTimer::timeout, this, &PlayerModel::tick);
    m_superTimer.setInterval(2000);
    connect(&m_superTimer, &QTimer::timeout, this, &PlayerModel::superviseTick);
    m_bufTimer.setInterval(200);
    connect(&m_bufTimer, &QTimer::timeout, this, [this] {
        const qint64 disk = Reserve::proxy().aheadMs(m_currentUrl, m_length);
        const qint64 b = m_length > 0 ? std::clamp(m_time + disk, std::min(m_time, m_length), m_length) : 0;
        if (b != m_bufferedMs) { m_bufferedMs = b; emit bufferedChanged(); }
    });
    m_infoTimer.setInterval(1000);
    connect(&m_infoTimer, &QTimer::timeout, this, &PlayerModel::refreshInfo);
    m_seekTimer.setSingleShot(true);
    m_seekTimer.setInterval(250);
    m_closeTimer.setSingleShot(true);
    m_closeTimer.setInterval(900); // полёт закрытия — 600 мс
    connect(&m_closeTimer, &QTimer::timeout, this, [this] { if (m_closing) finishClose(); });
    connect(&m_seekTimer, &QTimer::timeout, this, [this] {
        if (!m_pendingSeek) return;
        const qint64 t = *m_pendingSeek;
        m_pendingSeek.reset();
        PlayerLog::write(S("seek to %1s").arg(t / 1000));
        m_bufferingShown = 0;
        m_time = m_lastGood = t;
        m_lastSeekAt = now();
        m_supervisor.seeked();
        doSeek(t);
    });
    m_flashTimer.setSingleShot(true);
    m_flashTimer.setInterval(2000);
    connect(&m_flashTimer, &QTimer::timeout, this, [this] { if (m_status == m_flashText) setStatus({}); });
    m_prefsTimer.setSingleShot(true);
    connect(&m_prefsTimer, &QTimer::timeout, this, [this] { applyPrefs(); ensureAudio(); });
    m_ensureAudioTimer.setSingleShot(true);
    m_ensureAudioTimer.setInterval(4500);
    connect(&m_ensureAudioTimer, &QTimer::timeout, this, &PlayerModel::ensureAudio);
    m_autoSkipTimer.setSingleShot(true);
    m_autoSkipTimer.setInterval(int(MarkersDetector::AUTO_SKIP_DELAY_MS));
    connect(&m_autoSkipTimer, &QTimer::timeout, this, [this] {
        const auto r = introNow();
        if (!r || m_closing || paused() || m_introAutoDone == r) return;
        m_introAutoDone = r;
        skipIntroImpl(true);
    });
    m_statsTimer.setInterval(5000);
    connect(&m_statsTimer, &QTimer::timeout, this, [this] {
        if (m_closing || !m_started) return;
        PlayerLog::write(m_engine->statsLine());
        PlayerLog::write(S("[proxy] ") + Reserve::proxy().snapshot());
    });
    // Смена дорожки: буферизация, застрявшая на одном проценте 3 с, лечится перемоткой на то же место (20 с после смены).
    m_trackWatch.setInterval(3000);
    connect(&m_trackWatch, &QTimer::timeout, this, [this] {
        if (--m_trackWatchLeft < 0 || m_closing || paused() || m_scrubbing || m_catchingUp) { m_trackWatch.stop(); return; }
        if (m_bufferingSince == 0) { m_trackWatchShown = 0; return; }
        if (m_bufferingShown > m_trackWatchShown) { m_trackWatchShown = m_bufferingShown; return; }
        m_trackWatch.stop();
        PlayerLog::write(S("[track] buffering stuck at %1% after the switch → seek to %2s").arg(m_bufferingShown).arg(m_time / 1000));
        if (m_time <= 0) return;
        m_lastSeekAt = now();
        m_supervisor.seeked();
        doSeek(m_time);
    });
}

PlayerModel::~PlayerModel() {
    if (m_open && !m_closing) savePos(m_time, m_length);
    ui().put(S("volume"), num(m_volume));
}

// ---- открытие ----

void PlayerModel::start(PlayModel *from) {
    if (!from || !m_lib) return;
    m_req = from->request();
    m_preview.reset();
    m_poster = from->poster();
    begin();
}

void PlayerModel::startPreview(const QString &video, const QString &audio, qint64 offsetMs, const QString &title) {
    PlayRequest r;
    r.url = video;
    r.title = title;
    r.fromStart = true;
    r.startMs = 0;
    r.queue = {MFile{}};
    r.presence = {Presence{title, std::nullopt}};
    m_req = r;
    if (audio.isEmpty()) { m_preview.reset(); m_poster.clear(); begin(); return; }
    CustomTrack t;
    t.id = S("preview");
    t.title = title;
    t.url = audio;
    t.offsetMs = offsetMs;
    m_preview = t;
    m_poster.clear();
    begin();
}

void PlayerModel::retry() {
    if (!m_open || m_closing) return;
    m_retries = 0;
    reopenAt(m_lastGood);
}

void PlayerModel::begin() {
    // новый фильм, пока прежний ещё улетает: прежний сеанс сначала закрывается до конца
    if (m_closing) finishClose();
    Reserve::proxy(m_lib ? &m_lib->api() : nullptr);
    m_engine->handle(); // ядро mpv — до первого кадра: вывод в окне берёт его готовым
    m_open = true;
    m_closing = false;
    m_started = m_playing = m_switching = false;
    m_pausedAt = 0;
    m_muted = false;
    m_crop219 = false;
    m_engine->setPanScan(0.0);
    m_index = m_req.startIndex;
    m_sidecars.clear();
    m_sidecarWaiters.clear();
    m_sidePaths = m_req.sidePaths;
    m_sideTitles.clear();
    m_warmed.clear();
    m_retries = 0;
    m_nextIn = -1;
    m_infoOpen = false;
    // Пока список сезона не пришёл, в очереди только этот файл, а место — уже его номер в сезоне.
    m_queue.clear();
    const int n = std::max<int>(1, int(m_req.queue.size()));
    for (int i = 0; i < n; ++i) m_queue << Entry{i == m_index ? m_req.title : QString(), i == m_index ? m_req.url : QString()};
    m_title = m_req.title;
    m_currentUrl = m_req.url;
    m_sidecars.insert(m_index, Sides{m_req.audioUrls, m_req.subUrls});
    m_tickTimer.start();
    m_superTimer.start();
    m_bufTimer.start();
#ifdef SM_DIAG
    m_statsTimer.start();
#endif
    emit openChanged();
    emit entryChanged();
    emit stateChanged();
    emit soundChanged();
    emit optionsChanged();
    emit nextChanged();
    emit infoChanged();
    PlayerLog::write(S("player open «%1»: %2 in the queue, starts with %3").arg(m_req.title).arg(n).arg(m_index + 1));
    const qint64 at = m_req.startMs ? *m_req.startMs : m_req.fromStart ? 0 : savedPos(m_index);
    open(m_req.url, at, m_sidecars.value(m_index));
    loadPlaylist();
}

void PlayerModel::loadPlaylist() {
    if (!m_req.playlist || !m_lib) return;
    QPointer<PlayerModel> self(this);
    const quint64 gen = m_gen;
    Q_UNUSED(gen)
    const QString url = *m_req.playlist;
    m_lib->api().text(url, [self, url](const ApiResult &r) {
        if (!self || !self->m_open || self->m_req.playlist != url) return;
        const auto entries = r.ok() ? parseM3u(QString::fromUtf8(r.bytes)) : QList<std::pair<QString, QString>>();
        if (entries.size() <= 1) return;
        if (entries.size() != self->m_queue.size())
            PlayerLog::write(S("playlist: %1 entries for %2 files").arg(entries.size()).arg(self->m_queue.size()));
        QList<Entry> q;
        for (const auto &e : entries) q << Entry{e.first, e.second};
        // открытый файл — с тем адресом, с которым он уже играет (подпись та же, запас тот же)
        if (self->m_index < q.size()) q[self->m_index].url = self->m_currentUrl;
        self->m_queue = q;
        if (self->m_index < q.size() && !q[self->m_index].title.isEmpty()) self->m_title = q[self->m_index].title;
        emit self->entryChanged();
    });
}

/// Озвучки и субтитры рядом с файлом очереди i: у открытого — из запроса, у других — у сервера (ссылки зеркала).
void PlayerModel::sidecars(int i, std::function<void(const Sides &)> done) {
    if (const auto it = m_sidecars.constFind(i); it != m_sidecars.cend()) { done(*it); return; }
    auto &waiters = m_sidecarWaiters[i];
    waiters << std::move(done);
    if (waiters.size() > 1) return;
    const LibraryItem *item = m_lib ? m_lib->item(m_req.magnetId) : nullptr;
    auto finish = [this, i](const Sides &s) {
        m_sidecars.insert(i, s);
        const auto list = m_sidecarWaiters.take(i);
        for (const auto &w : list) w(s);
    };
    if (!item || i < 0 || i >= m_req.queue.size()) { finish({}); return; }
    const MFile video = m_req.queue[i];
    QList<MFile> subs = subtitlesFor(item->m, video), audio = externalAudioFor(item->m, video);
    subs.erase(std::remove_if(subs.begin(), subs.end(), [](const MFile &f) { return !f.link; }), subs.end());
    audio.erase(std::remove_if(audio.begin(), audio.end(), [](const MFile &f) { return !f.link; }), audio.end());
    if (subs.isEmpty() && audio.isEmpty()) { finish({}); return; }
    struct State {
        int left = 0;
        QVector<QString> subs, audio;
    };
    auto st = std::make_shared<State>();
    st->left = int(subs.size() + audio.size());
    st->subs.resize(subs.size());
    st->audio.resize(audio.size());
    QPointer<PlayerModel> self(this);
    auto step = [self, st, finish, subs, audio]() {
        if (--st->left > 0 || !self) return;
        Sides s;
        for (int k = 0; k < st->subs.size(); ++k)
            if (!st->subs[k].isEmpty()) { s.subs << st->subs[k]; self->m_sidePaths.insert(st->subs[k], subs[k].path); }
        for (int k = 0; k < st->audio.size(); ++k)
            if (!st->audio[k].isEmpty()) { s.audio << st->audio[k]; self->m_sidePaths.insert(st->audio[k], audio[k].path); }
        finish(s);
    };
    auto path = [](const MFile &f) {
        const QString name = f.name.isEmpty() ? f.path.section(QLatin1Char('/'), -1) : f.name;
        return S("/api/file/mirror-url?link=") + formEncode(*f.link) + S("&name=") + formEncode(name);
    };
    for (int k = 0; k < subs.size(); ++k)
        m_lib->api().get(path(subs[k]), [st, step, k](const ApiResult &r) { st->subs[k] = r.ok() ? r.json.value(S("url")).toString() : QString(); step(); });
    for (int k = 0; k < audio.size(); ++k)
        m_lib->api().get(path(audio[k]), [st, step, k](const ApiResult &r) { st->audio[k] = r.ok() ? r.json.value(S("url")).toString() : QString(); step(); });
}

void PlayerModel::open(const QString &url, qint64 startAt, const Sides &sides) {
    const QStringList audio = preferFirst(sides.audio, pref(S("audio")) ? pref(S("audio")) : ui().get(S("audio:*")), url);
    const QStringList subs = preferFirst(sides.subs, pref(S("spu")) ? pref(S("spu")) : ui().get(S("spu:*")), url);
    const quint64 gen = ++m_gen;
    m_ensureAudioTimer.stop();
    setStatus(tx(S("player.otkryvaem")));
    m_prefsApplied = false;
    m_markersAsked = false;
    m_lastGood = m_time = startAt;
    m_audioDelay = pref(S("adelay")).value_or(S("0")).toLongLong();
    m_subDelay = pref(S("sdelay")).value_or(S("0")).toLongLong();
    m_custom.clear();
    m_activeCustom.reset();
    m_endCut = false;
    m_pausedAt = 0;
    emit optionsChanged();
    emit timeChanged();
    emit tracksExtraChanged();

    BurstProxy &proxy = Reserve::proxy();
    proxy.setCapBytes(qint64(PlaybackSettings::burstMb()) << 20);
    proxy.keepOnly(QStringList{url} + audio);
    m_supervisor.opening(url);
    m_openedAt = now();
    PlayerLog::write(S("open %1 at %2s, voice-overs=%3, buffer=%4ms, reserve=%5MB")
                         .arg(sideFileName(url)).arg(startAt / 1000).arg(audio.size()).arg(PlaybackSettings::cacheMs()).arg(PlaybackSettings::burstMb()));
    for (const QString &a : audio) {
        const QString w = proxy.wrap(a);
        if (w.startsWith(S("http://127.0.0.1"))) Reserve::warmSidecar(w);
    }
    // Субтитры скачиваются сразу, все вместе, пока mpv открывает видео.
    fetchSubtitles(gen, subs, url);

    // Серверные озвучки — после озвучек раздачи; без сети не ждём дольше 3 с.
    if (m_preview) { openWithCustom(gen, url, startAt, Sides{audio, subs}, {*m_preview}); return; }
    const QString key = watchKey();
    const qint64 magnet = key.section(QLatin1Char('|'), 0, 0).toLongLong();
    if (!PlaybackSettings::customAudioOn() || magnet <= 0 || !m_lib) { openWithCustom(gen, url, startAt, Sides{audio, subs}, {}); return; }
    QPointer<PlayerModel> self(this);
    auto fired = std::make_shared<bool>(false);
    auto go = [self, gen, url, startAt, audio, subs, fired](const QList<CustomTrack> &custom) {
        if (*fired || !self || self->m_gen != gen) return;
        *fired = true;
        self->openWithCustom(gen, url, startAt, Sides{audio, subs}, custom);
    };
    QTimer::singleShot(3000, this, [go] { go({}); });
    m_lib->api().get(CustomAudio::tracksPath(magnet), [go, key](const ApiResult &r) {
        if (!r.ok()) { PlayerLog::write(S("[custom-audio] list failed: %1").arg(ApiClient::describe(r.error))); go({}); return; }
        go(CustomAudio::forVideo(CustomAudio::parseTracks(r.json), key));
    });
}

void PlayerModel::openWithCustom(quint64 gen, const QString &url, qint64 startAt, const Sides &sides, const QList<CustomTrack> &custom) {
    if (m_closing || gen != m_gen) return;
    BurstProxy &proxy = Reserve::proxy();
    m_custom = custom;
    QStringList keep{url};
    keep << sides.audio;
    for (const CustomTrack &t : custom) keep << t.url;
    if (!custom.isEmpty()) {
        proxy.keepOnly(keep);
        PlayerLog::write(S("[custom-audio] %1 voice-over(s) from the server").arg(custom.size()));
    }
    QList<MpvEngine::SideTrack> audioTracks;
    for (const QString &a : sides.audio) {
        const auto [title, lang] = sideName(a, url);
        audioTracks << MpvEngine::SideTrack{proxy.wrap(a), title, lang};
    }
    for (const CustomTrack &t : custom) {
        QString title = t.title;
        if (t.lang) title = title.remove(S(" [%1]").arg(*t.lang)).trimmed();
        if (title.isEmpty()) title = t.audioPath.section(QLatin1Char('/'), -1);
        audioTracks << MpvEngine::SideTrack{proxy.wrap(t.url), title, t.lang.value_or(QString())};
    }
    emit tracksExtraChanged();
    m_engine->open(proxy.wrap(url), startAt, m_night, prefLanguage(S("audio")), prefLanguage(S("spu")), audioTracks, {});
    m_engine->setSubScale(m_subScale);
}

void PlayerModel::fetchSubtitles(quint64 gen, const QStringList &urls, const QString &videoUrl) {
    m_subsFetching = int(urls.size());
    emit tracksExtraChanged();
    if (urls.isEmpty()) return;
    // копии прошлых серий удаляет finishClose (mpv может ещё держать их открытыми)
    QDir dir(QDir::temp().filePath(S("suslik-qt-subs")));
    QDir().mkpath(dir.path());
    struct State {
        int left = 0;
        QVector<QString> local;
    };
    auto st = std::make_shared<State>();
    st->left = int(urls.size());
    st->local.resize(urls.size());
    auto *nam = new QNetworkAccessManager(this);
    const qint64 stamp = now();
    QPointer<PlayerModel> self(this);
    for (int i = 0; i < urls.size(); ++i) {
        QNetworkRequest req{QUrl(urls[i])};
        req.setTransferTimeout(20000);
        QNetworkReply *r = nam->get(req);
        connect(r, &QNetworkReply::finished, this, [self, r, st, i, urls, videoUrl, gen, dir, stamp, nam] {
            r->deleteLater();
            const int code = r->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
            if (r->error() == QNetworkReply::NoError && code >= 200 && code < 300 && self) {
                QString ext = self->sideFileName(urls[i]).section(QLatin1Char('.'), -1).left(8);
                if (ext.isEmpty()) ext = S("srt");
                QFile f(dir.filePath(S("%1-%2.%3").arg(stamp).arg(i).arg(ext)));
                if (f.open(QIODevice::WriteOnly)) { f.write(r->readAll()); st->local[i] = f.fileName(); }
            }
            if (--st->left > 0) return;
            nam->deleteLater();
            if (!self || self->m_gen != gen) return;
            QList<MpvEngine::SideTrack> subs;
            for (int k = 0; k < urls.size(); ++k) {
                const auto [title, lang] = self->sideName(urls[k], videoUrl);
                subs << MpvEngine::SideTrack{st->local[k].isEmpty() ? urls[k] : st->local[k], title, lang};
            }
            self->m_subsFetching = 0;
            emit self->tracksExtraChanged();
            self->m_engine->addSubtitles(subs);
            self->m_prefsUntil = std::max(self->m_prefsUntil, now() + 3000);
        });
    }
}

void PlayerModel::reopenAt(qint64 t) {
    const int i = m_index;
    sidecars(i, [this, i, t](const Sides &s) { if (m_index == i && !m_closing) open(m_currentUrl, t, s); });
}

void PlayerModel::playEntry(int i) {
    if (i < 0 || i >= m_queue.size() || m_queue[i].url.isEmpty()) return;
    // гость комнаты: серию выбирает ведущий (плеер гостя идёт за ним сам)
    if (!m_byRoom && partyGuest()) { flash(tx(S("party.seriyu_vybiraet_sozdatel"))); return; }
    const bool finishedHere = i == m_index + 1 && m_nextIn >= 0;
    m_seekTimer.stop();
    m_pendingSeek.reset();
    if (finishedHere && m_length > 0) savePos(m_length, m_length); else savePos(m_time, m_length);
    m_index = i;
    m_nextIn = -1;
    m_currentUrl = m_queue[i].url;
    m_title = m_queue[i].title;
    m_retries = 0;
    m_serverMarkers.reset();
    m_nextDismissed = false;
    m_introAutoDone.reset();
    // Серию включила комната (ведущий перешёл к ней): с места комнаты, а не с места, где здесь её когда-то бросили —
    // иначе сначала грузилось бы чужое место, а потом движок комнаты перематывал бы ещё раз
    const Party *party = Party::instance();
    const qint64 start = m_byRoom && party && party->inRoom() && !party->isHost() ? std::max<qint64>(0, party->roomMs()) : savedPos(i);
    m_playing = m_started = false;
    m_switching = true;
    m_time = m_lastGood = start;
    m_length = 0;
    setStatus(tx(S("player.sleduyuschaya_seriya")));
    emit entryChanged();
    emit stateChanged();
    emit timeChanged();
    emit nextChanged();
    emit markersChanged();
    const QString url = m_currentUrl;
    // до 4 с ждём озвучки новой серии, дальше — без них
    auto fired = std::make_shared<bool>(false);
    QPointer<PlayerModel> self(this);
    auto go = [self, i, url, start, fired](const Sides &s) {
        if (*fired || !self || self->m_index != i || self->m_closing) return;
        *fired = true;
        self->open(url, start, s);
    };
    QTimer::singleShot(4000, this, [go] { go({}); });
    sidecars(i, go);
}

void PlayerModel::playNext() { if (m_index + 1 < m_queue.size()) playEntry(m_index + 1); }

void PlayerModel::cancelNext() {
    m_nextIn = -1;
    m_nextDismissed = true;
    emit nextChanged();
}

qint64 PlayerModel::magnetId() const { return m_req.magnetId; }

void PlayerModel::close() {
    if (!m_open || m_closing) return;
    m_closing = true;
    m_seekTimer.stop();
    m_pendingSeek.reset();
    savePos(m_time, m_length);
    if (m_lib) { m_lib->bumpWatch(); m_lib->sync().kick(); }
    m_nextIn = -1;
    m_supervisor.closed();
    m_engine->setPaused(true);
    emit openChanged();
    emit nextChanged();
    // экран плеера улетает в постер (Main.qml), по концу полёта QML зовёт finishClose(); если полёт не дошёл
    // до конца (окно свёрнуто — анимации стоят), закрытие довершит этот таймер
    m_closeTimer.start();
}

void PlayerModel::finishClose() {
    if (!m_closing) return;
    m_closeTimer.stop();
    for (QTimer *t : {&m_tickTimer, &m_superTimer, &m_bufTimer, &m_infoTimer, &m_flashTimer, &m_prefsTimer, &m_ensureAudioTimer,
                      &m_autoSkipTimer, &m_statsTimer, &m_trackWatch})
        t->stop();
    m_engine->stop();
    m_crop219 = false;
    m_engine->setPanScan(0.0);
    // ядро mpv (кэш демуксера, потоки) — отпустить: в медиатеке плеер не держит память. Вывод кадров отпустит
    // свою ссылку, когда экран плеера уйдёт (Loader в Main.qml)
    m_engine->release();
    Reserve::proxy().clear();
    QDir(QDir::temp().filePath(S("suslik-qt-subs"))).removeRecursively();
    ui().put(S("volume"), num(m_volume));
    m_open = false;
    m_closing = false;
    m_started = m_playing = false;
    ++m_gen;
    m_status.clear();
    m_catchingUp = false;
    // данные сеанса — не подмешивать в следующий фильм
    m_sidecars.clear();
    m_sidePaths.clear();
    m_queue.clear();
    m_custom.clear();
    m_serverMarkers.reset();
    emit openChanged();
    emit entryChanged();
    emit stateChanged();
    emit statusChanged();
    emit closed();
    PlayerLog::write(S("player closed"));
}

// ---- события движка ----

void PlayerModel::onEngineState() {
    if (!m_open || m_closing || m_switching || !m_engine->loaded()) return;
    if (m_engine->buffering()) evBuffering(m_engine->bufferPercent());
    else if (m_bufferingSince > 0 || m_status.startsWith(tx(S("player.buferizaciya_p"), {{"p", QString()}}).section(QLatin1Char(' '), 0, 0)))
        evBuffering(100);
    if (!m_started && m_engine->timeMs() > 0 && !m_engine->paused()) evPlayingNow();
}

void PlayerModel::evPlayingNow() {
    const bool wasStarted = m_started;
    m_playing = true;
    m_started = true;
    m_retries = 0;
    m_bufferingShown = 0;
    if (m_status == tx(S("player.otkryvaem")) || m_status == tx(S("player.sleduyuschaya_seriya")) || m_status == tx(S("player.perepodklyuchenie")))
        setStatus({});
    if (m_bufferingSince > 0) {
        const qint64 ms = now() - m_bufferingSince;
        m_bufferingSince = 0;
        if (ms > 1500) PlayerLog::write(S("buffering took %1 ms at %2s").arg(ms).arg(m_time / 1000));
    }
    if (m_pausedAt == 0) m_supervisor.playing();
    if (!m_prefsApplied) {
        m_prefsApplied = true;
        QTimer::singleShot(2000, this, [this] { if (m_open && !m_closing) PlayerLog::write(m_engine->statsLine()); });
        m_prefsUntil = now() + 8000;
        m_prefsTimer.start(300);
        m_ensureAudioTimer.start();
        if (m_audioDelay != 0) m_engine->setAudioDelayMs(m_audioDelay);
        if (m_subDelay != 0) m_engine->setSubDelayMs(m_subDelay);
        if (m_rate != 1 && !partyFilm()) m_engine->setRate(m_rate); // в фильме комнаты скорость ставит PartySync
    }
    if (m_pausedAt > 0) {
        m_playing = false;
        m_engine->setMute(true);
        m_engine->setPaused(true);
    } else {
        m_engine->setMute(m_muted);
        if (!wasStarted) m_engine->setVolume(m_volume);
    }
    emit stateChanged();
    if (m_length > 0 && !m_markersAsked) fetchMarkers();
}

void PlayerModel::evBuffering(int percent) {
    if (percent < 100) {
        m_bufferingShown = std::max(m_bufferingShown, percent);
        setStatus(tx(S("player.buferizaciya_p"), {{"p", num(m_bufferingShown)}}));
        m_supervisor.buffering(float(m_bufferingShown));
        if (m_bufferingSince == 0) m_bufferingSince = now();
    } else {
        m_bufferingShown = 0;
        if (m_status.startsWith(tx(S("player.buferizaciya_p"), {{"p", QString()}}).trimmed().section(QLatin1Char(' '), 0, 0)))
            setStatus(m_catchingUp ? m_catchUpStatus : QString());
        m_supervisor.buffering(100);
        if (m_bufferingSince > 0) {
            const qint64 ms = now() - m_bufferingSince;
            m_bufferingSince = 0;
            if (ms > 1500) PlayerLog::write(S("buffering took %1 ms at %2s").arg(ms).arg(m_time / 1000));
        }
    }
}

void PlayerModel::evTime(qint64 t) {
    if (m_switching || m_closing) return;
    if (!m_started && t > 0 && !m_engine->paused()) evPlayingNow();
    if (!m_scrubbing && !m_pendingSeek && now() - m_lastSeekAt > 600 && (std::abs(t - m_time) >= 250 || t < m_time)) {
        m_time = t;
        emit timeChanged();
    }
    m_lastGood = t;
    // Серверная озвучка длиннее видео: когда видео кончилось, остаток дорожки не играем.
    if (m_activeCustom && !m_endCut && m_length > 0 && t >= m_length + 300) {
        m_endCut = true;
        PlayerLog::write(S("[custom-audio] video ended at %1s, the rest of the voice-over is cut").arg(m_length / 1000));
        m_engine->setPaused(true);
        evFinished();
    }
    if (t / 10000 != (t - 300) / 10000) savePos(t, m_length);
    // На титрах — карточка следующей серии; отмотали до неё — отсчёт стоп.
    checkCredits();
    // Заставка: автопропуск через 1,5 с внутри неё.
    const auto r = introNow();
    if (r && m_started && PlaybackSettings::autoSkipIntro() && m_introAutoDone != r && !m_autoSkipTimer.isActive()) m_autoSkipTimer.start();
    if (!r) m_autoSkipTimer.stop();
    if (m_length > 0 && m_length - t > 0 && m_length - t <= 60000) warmNext();
}

void PlayerModel::evFinished() {
    if (m_closing || !m_open) return;
    m_supervisor.ended();
    m_playing = false;
    emit stateChanged();
    const bool end = m_length > 0 && m_lastGood >= m_length - 5000;
    if (end) {
        savePos(m_length, m_length);
        // гость комнаты: плеер ведущего перейдёт к следующей, комната — за ним (без отсчёта здесь)
        if (partyGuest()) {
        } else if (m_index < m_queue.size() - 1) {
            if (m_nextIn < 0) {
                m_nextIn = PlaybackSettings::nextCountdown();
                m_nextFrom = std::max<qint64>(0, m_length - 5000);
                emit nextChanged();
            }
        } else setStatus(tx(S("player.konec")));
    } else if (m_retries < 6) {
        ++m_retries;
        setStatus(tx(S("player.perepodklyuchenie")));
        reopenAt(m_lastGood);
    }
}

void PlayerModel::evError(const QString &message) {
    PlayerLog::write(S("[mpv] ") + message);
    if (m_closing || !m_open) return;
    if (!m_engine->available()) { setStatus(tx(S("app.mpv_ne_skachan"))); return; }
    if (m_retries < 6) {
        ++m_retries;
        setStatus(tx(S("player.perepodklyuchenie")));
        reopenAt(m_lastGood);
    } else setStatus(tx(S("player.ne_udalos_vosproizvesti_potok")));
}

// ---- управление ----

void PlayerModel::togglePause() {
    if (!m_open || m_closing) return;
    if (m_pausedAt != 0 && !m_byRoom && playLocked()) { flash(tx(S("party.pauzu_snimaet_sozdatel"))); return; }
    if (m_pausedAt == 0) {
        m_playing = false;
        m_pausedAt = now();
        m_supervisor.paused();
        m_engine->setPaused(true);
        savePos(m_time, m_length);
    } else {
        const qint64 was = now() - m_pausedAt;
        const bool longPause = was > 8000 && !Reserve::proxy().isActive(m_currentUrl);
        PlayerLog::write(S("resume after %1 ms%2").arg(was).arg(longPause ? S(" (re-seek)") : QString()));
        m_supervisor.resumed();
        m_playing = true;
        m_pausedAt = 0;
        m_engine->setMute(m_muted);
        m_engine->setPaused(false);
        if (longPause) reseekHere();
        // конец фильма: «▶» начинает его заново
        if (m_length > 0 && m_lastGood >= m_length - 1500 && m_index >= m_queue.size() - 1) doSeek(0);
    }
    emit stateChanged();
}

void PlayerModel::seekBy(qint64 d) {
    if (!m_started && m_length <= 0) return;
    if (partyGuest()) { flash(tx(S("party.peremativaet_sozdatel"))); return; }
    const qint64 base = m_pendingSeek.value_or(m_time);
    qint64 t = std::max<qint64>(0, base + d);
    if (m_length > 0) t = std::min(t, std::max<qint64>(0, m_length - 2000));
    m_time = m_lastGood = t;
    m_pendingSeek = t;
    emit timeChanged();
    m_seekTimer.start();
}

void PlayerModel::seekTo(qint64 t) {
    m_seekTimer.stop();
    m_pendingSeek.reset();
    m_scrubbing = false;
    if (!m_byRoom && partyGuest()) return; // у гостя полоса — «прямой эфир»
    m_bufferingShown = 0;
    m_time = m_lastGood = std::max<qint64>(0, t);
    m_lastSeekAt = now();
    m_supervisor.seeked();
    PlayerLog::write(S("seek to %1s").arg(t / 1000));
    emit timeChanged();
    doSeek(m_time);
}

void PlayerModel::doSeek(qint64 t) {
    if (t < m_length) m_endCut = false;
    m_lastGood = t;
    m_engine->seekTo(t);
    emit seeked();
}

void PlayerModel::reseekHere() {
    const qint64 t = m_engine->timeMs();
    if (t > 0) m_engine->seekTo(t);
}

void PlayerModel::jumpChapter(int d) {
    const QVariantList ch = m_engine->chapters();
    if (ch.size() < 2) return;
    if (partyGuest()) { flash(tx(S("party.peremativaet_sozdatel"))); return; }
    const int i = std::clamp(chapterAt(m_time) + d, 0, int(ch.size()) - 1);
    const QVariantMap c = ch[i].toMap();
    seekTo(c.value(S("timeMs")).toLongLong());
    QString name = c.value(S("title")).toString();
    if (name.isEmpty()) name = tx(S("player.bez_nazvaniya"));
    flash(tx(S("player.glava_p_p"), {{"n", num(i + 1)}, {"name", name}}));
}

int PlayerModel::chapterAt(qint64 t) const {
    const QVariantList ch = m_engine->chapters();
    int at = -1;
    for (int i = 0; i < ch.size(); ++i)
        if (ch[i].toMap().value(S("timeMs")).toLongLong() <= t + 500) at = i;
    return at;
}

void PlayerModel::selectAudio(int id, const QString &name) {
    m_lastSeekAt = now();
    m_bufferingShown = 0;
    const bool smart = PlaybackSettings::smartSubs();
    m_engine->setAudioTrack(id);
    if (smart) {
        applySmartSubs(id);
        putUi(S("spu:") + seriesKey(), SMART_SUBS);
    }
    m_supervisor.seeked();
    applyCustomDelay(name);
    putUi(S("audio:") + seriesKey(), name);
    putUi(S("audio:*"), name);
    if (m_lib) m_lib->sync().noteTrack(seriesKey(), name, std::nullopt);
    m_trackWatchLeft = 7;
    m_trackWatchShown = 0;
    m_trackWatch.start();
    PlayerLog::write(S("[track] switched at %1s").arg(m_time / 1000));
}

void PlayerModel::selectSub(int id, const QString &name) {
    m_engine->setSubTrack(id);
    const QString v = id == -1 ? S("__off__") : name;
    putUi(S("spu:") + seriesKey(), v);
    putUi(S("spu:*"), v);
    if (m_lib) m_lib->sync().noteTrack(seriesKey(), std::nullopt, v);
}

void PlayerModel::setVolume(int v) {
    m_volume = std::clamp(v, 0, 150);
    const bool unmute = m_muted && m_volume > 0;
    if (unmute) m_muted = false;
    m_engine->setVolume(m_volume);
    if (unmute) m_engine->setMute(false);
    emit soundChanged();
}

void PlayerModel::wheelVolume(int steps) {
    stepVolume(steps * 5);
}

void PlayerModel::stepVolume(int delta) {
    m_volume = std::clamp(m_volume + delta, 0, 150);
    m_engine->setVolume(m_volume);
    if (m_muted) { m_muted = false; m_engine->setMute(false); }
    m_volumeWheelAt = now();
    emit soundChanged();
    flash(tx(S("player.gromkost_v"), {{"v", num(m_volume)}}));
}

void PlayerModel::toggleMute() {
    m_muted = !m_muted;
    m_engine->setMute(m_muted);
    emit soundChanged();
    flash(m_muted ? tx(S("player.bez_zvuka")) : tx(S("player.zvuk_volume"), {{"volume", num(m_volume)}}));
}

void PlayerModel::setSpeed(double r) {
    // В комнате все идут с одной скоростью: своя увела бы этот плеер вперёд или назад от остальных
    if (partyFilm()) { flash(tx(S("party.skorost_v_komnate"))); return; }
    m_rate = r;
    putUi(S("rate"), r == 1 ? std::nullopt : std::optional<QString>(QString::number(r)));
    m_engine->setRate(r);
    emit optionsChanged();
    flash(tx(S("player.skorost_p"), {{"p", rateText(r)}}));
}

void PlayerModel::stepSpeed(int d) {
    int i = 0;
    while (i < RATES.size() && RATES[i] < m_rate - 0.01) ++i;
    setSpeed(RATES[std::clamp(i + d, 0, int(RATES.size()) - 1)]);
}

void PlayerModel::setSubScaleValue(int v) {
    m_subScale = v;
    putUi(S("subScale"), v == 100 ? std::nullopt : std::optional<QString>(num(v)));
    m_engine->setSubScale(v);
    emit optionsChanged();
    flash(S("%1: %2%").arg(tx(S("player.subtitry"))).arg(v));
}

void PlayerModel::shiftSubs(qint64 ms) {
    m_subDelay = std::clamp<qint64>(m_subDelay + ms, -10000, 10000);
    putUi(S("sdelay:") + seriesKey(), m_subDelay == 0 ? std::nullopt : std::optional<QString>(num(m_subDelay)));
    m_engine->setSubDelayMs(m_subDelay);
    emit optionsChanged();
    flash(tx(S("player.subtitry_p_d_ms"), {{"ms", signedMs(m_subDelay)}}));
}

void PlayerModel::shiftAudio(qint64 ms) {
    m_audioDelay = std::clamp<qint64>(m_audioDelay + ms, -60000, 60000);
    const qint64 d = m_audioDelay;
    if (m_activeCustom && m_activeCustom->id == S("preview")) {}
    else if (m_activeCustom) putUi(S("adelay:") + m_activeCustom->id, d == m_activeCustom->offsetMs ? std::nullopt : std::optional<QString>(num(d)));
    else putUi(S("adelay:") + seriesKey(), d == 0 ? std::nullopt : std::optional<QString>(num(d)));
    m_engine->setAudioDelayMs(d);
    if (std::abs(ms) >= 1000) reseekHere();
    emit optionsChanged();
    emit tracksExtraChanged();
    flash(tx(S("player.zaderzhka_zvuka_p_d"), {{"ms", signedMs(d)}}));
}

void PlayerModel::toggleNight() {
    m_night = !m_night;
    putUi(S("night"), m_night ? std::optional<QString>(S("1")) : std::nullopt);
    m_engine->setNightMode(m_night);
    emit optionsChanged();
    flash(m_night ? tx(S("player.nochnoy_zvuk_vklyuchen")) : tx(S("player.nochnoy_zvuk_vyklyuchen")));
}

void PlayerModel::toggleCrop219() {
    setCrop219(!m_crop219);
}

void PlayerModel::setCrop219(bool on) {
    if (m_crop219 == on) return;
    m_crop219 = on;
    m_engine->setPanScan(m_crop219 ? 1.0 : 0.0);
    emit optionsChanged();
    flash(m_crop219 ? QStringLiteral("21:9") : tx(S("player.format_iskhodnyy")));
}

void PlayerModel::toggleInfo() {
    m_infoOpen = !m_infoOpen;
    if (m_infoOpen) { refreshInfo(); m_infoTimer.start(); } else m_infoTimer.stop();
    emit infoChanged();
}

void PlayerModel::refreshInfo() {
    BurstProxy &p = Reserve::proxy();
    const auto ahead = p.aheadMb(m_currentUrl);
    m_infoText = m_engine->statsLine()
        + tx(S("player.skorost_p_zvuk_p"), {{"rate", rateText(m_rate)}, {"audio", signedMs(m_audioDelay)}, {"subs", signedMs(m_subDelay)}})
        + (m_night ? tx(S("player.nochnoy_rezhim")) : QString())
        + tx(S("player.bufer_p_s"), {{"p", QString::number(PlaybackSettings::cacheMs() / 1000.0)}})
        + (ahead ? tx(S("player.zapas_na_diske_f"), {{"p", num(PlaybackSettings::burstMb())}}).replace(S("%.0f"), QString::number(*ahead, 'f', 0))
                 : tx(S("player.bez_zapasa_napryamuyu")))
        + tx(S("player.zhurnal_absolutepath"), {{"absolutePath", QDir::toNativeSeparators(PlayerLog::file())}});
    emit infoChanged();
}

void PlayerModel::mark() {
    static int n = 0;
    ++n;
    PlayerLog::write(S("[mark] #%1 player at %2s").arg(n).arg(m_time / 1000));
    PlayerLog::write(m_engine->statsLine());
    PlayerLog::write(S("[proxy] ") + Reserve::proxy().snapshot());
    flash(tx(S("player.metka_p"), {{"p", num(n)}}));
}

// ---- дорожки: запомненные, умные субтитры, задержки ----

void PlayerModel::applyPrefs() {
    // Предпросмотр озвучки: слушаем именно её
    const std::optional<QString> a = m_preview ? std::optional<QString>(m_preview->menuName()) : pref(S("audio"));
    const std::optional<QString> spu = pref(S("spu"));
    const QVariantList audio = m_engine->audioTracks(), subs = m_engine->subTracks();
    if (a) {
        for (const QVariant &v : audio) {
            const QVariantMap t = v.toMap();
            if (t.value(S("label")).toString() == *a && !t.value(S("selected")).toBool()) {
                m_engine->setAudioTrack(t.value(S("id")).toInt());
                m_supervisor.seeked();
            }
        }
    }
    const int cur = selectedId(m_engine->audioTracks());
    for (const QVariant &v : m_engine->audioTracks())
        if (v.toMap().value(S("id")).toInt() == cur) applyCustomDelay(v.toMap().value(S("label")).toString());
    if (spu == S("__off__")) { if (selectedId(subs) != -1) m_engine->setSubTrack(-1); }
    else if (spu && *spu != SMART_SUBS)
        for (const QVariant &v : subs) {
            const QVariantMap t = v.toMap();
            if (t.value(S("label")).toString() == *spu && !t.value(S("selected")).toBool()) m_engine->setSubTrack(t.value(S("id")).toInt());
        }
    // Субтитры для этого сериала не выбирали сами (или их выбрали умные после смены озвучки): по звуку
    if ((!spu || *spu == SMART_SUBS) && PlaybackSettings::smartSubs()) applySmartSubs();
}

void PlayerModel::applySmartSubs(int audioId) {
    std::optional<QVariantMap> audio;
    for (const QVariant &v : m_engine->audioTracks()) {
        const QVariantMap t = v.toMap();
        if (audioId >= 0 ? t.value(S("id")).toInt() == audioId : t.value(S("selected")).toBool()) { audio = t; break; }
    }
    if (!audio) return;
    QList<MarkersDetector::Track> subs;
    for (const QVariant &v : m_engine->subTracks()) subs << forSubs(v.toMap());
    const auto c = MarkersDetector::pickSubtitle(forSubs(*audio), subs);
    const int cur = selectedId(m_engine->subTracks());
    const QString label = audio->value(S("label")).toString();
    if (c.kind == MarkersDetector::SubChoice::Kind::Pick && cur != c.id) {
        m_engine->setSubTrack(c.id);
        PlayerLog::write(S("[subs] smart: track %1 for «%2»").arg(c.id).arg(label));
    } else if (c.kind == MarkersDetector::SubChoice::Kind::Off && cur != -1) {
        m_engine->setSubTrack(-1);
        PlayerLog::write(S("[subs] smart: off for «%1»").arg(label));
    }
}

/// Выбрана дорожка name: у серверной озвучки — её задержка (своя поправка с этого ПК важнее), у остальных — задержка
/// фильма. Задержка всегда от 00:00 видео: −5000 — дорожка с 00:05 звучит на 00:00.
void PlayerModel::applyCustomDelay(const QString &name) {
    const auto ct = CustomAudio::byMenuName(m_custom, name);
    if (!ct && !m_activeCustom) return;
    m_activeCustom = ct;
    m_deleteArmed.clear();
    const qint64 before = m_audioDelay;
    if (ct) {
        const auto own = ct->id == S("preview") ? std::nullopt : ui().get(S("adelay:") + ct->id);
        m_audioDelay = own ? own->toLongLong() : ct->offsetMs;
    } else m_audioDelay = pref(S("adelay")).value_or(S("0")).toLongLong();
    m_engine->setAudioDelayMs(m_audioDelay);
    // mpv не перечитывает дорожку при новой задержке: перемотка на то же место сразу берёт звук со сдвигом.
    if (std::abs(m_audioDelay - before) >= 1000) reseekHere();
    if (ct && m_audioDelay != 0) PlayerLog::write(S("[custom-audio] %1 delay %2 ms").arg(ct->id).arg(m_audioDelay));
    emit optionsChanged();
    emit tracksExtraChanged();
}

void PlayerModel::ensureAudio() {
    if (!m_open || m_closing) return;
    const QVariantList audio = m_engine->audioTracks();
    if (selectedId(audio) != -1 || audio.isEmpty()) return;
    const QVariantMap first = audio.first().toMap();
    m_engine->setAudioTrack(first.value(S("id")).toInt());
    m_supervisor.seeked();
    applyCustomDelay(first.value(S("label")).toString());
    flash(tx(S("player.zvuk_p"), {{"p", first.value(S("label")).toString()}}));
}

QString PlayerModel::saveDelayText() const {
    if (!m_activeCustom || !m_activeCustom->mine) return {};
    return tx(S("player.zapomnit_zaderzhku"), {{"ms", signedMs(m_audioDelay)}});
}

QString PlayerModel::deleteTrackText() const {
    if (!m_activeCustom || !m_activeCustom->mine) return {};
    return tx(m_deleteArmed == m_activeCustom->id ? S("player.udalit_ozvuchku_tochno") : S("player.udalit_ozvuchku"));
}

void PlayerModel::saveDelay() {
    if (!m_activeCustom || !m_lib) return;
    const CustomTrack ct = *m_activeCustom;
    const qint64 d = m_audioDelay;
    QNetworkRequest req = m_lib->api().request(CustomAudio::offsetPath(ct.id));
    req.setHeader(QNetworkRequest::ContentTypeHeader, S("application/json"));
    QPointer<PlayerModel> self(this);
    m_lib->api().send(req, "PUT", QJsonDocument(CustomAudio::offsetBody(d)).toJson(QJsonDocument::Compact), [self, ct, d](const ApiResult &r) {
        if (!self) return;
        if (!r.ok()) { self->flash(ApiClient::describe(r.error)); return; }
        if (ct.audioMagnetId) Store::of(S("audio_offsets")).put(S("%1|%2").arg(*ct.audioMagnetId).arg(ct.audioPath), num(d));
        for (CustomTrack &t : self->m_custom) if (t.id == ct.id) t.offsetMs = d;
        if (self->m_activeCustom && self->m_activeCustom->id == ct.id) self->m_activeCustom->offsetMs = d;
        self->putUi(S("adelay:") + ct.id, std::nullopt);
        emit self->tracksExtraChanged();
        self->flash(tx(S("player.zaderzhka_sohranena"), {{"ms", signedMs(d)}}));
    });
}

void PlayerModel::deleteTrack() {
    if (!m_activeCustom || !m_lib) return;
    const CustomTrack ct = *m_activeCustom;
    if (m_deleteArmed != ct.id) { m_deleteArmed = ct.id; emit tracksExtraChanged(); return; }
    m_deleteArmed.clear();
    QPointer<PlayerModel> self(this);
    m_lib->api().send(m_lib->api().request(CustomAudio::trackPath(ct.id)), "DELETE", {}, [self, ct](const ApiResult &r) {
        if (!self) return;
        if (!r.ok()) { self->flash(ApiClient::describe(r.error)); return; }
        PlayerLog::write(S("[custom-audio] %1 deleted").arg(ct.id));
        self->putUi(S("adelay:") + ct.id, std::nullopt);
        self->m_custom.erase(std::remove_if(self->m_custom.begin(), self->m_custom.end(), [&](const CustomTrack &t) { return t.id == ct.id; }),
                             self->m_custom.end());
        self->applyCustomDelay(QString()); // задержка фильма вместо задержки озвучки
        const QVariantList list = self->m_engine->audioTracks();
        const int cur = selectedId(list);
        for (const QVariant &v : list) {
            const QVariantMap t = v.toMap();
            if (t.value(S("id")).toInt() == cur) continue;
            self->m_engine->setAudioTrack(t.value(S("id")).toInt());
            self->putUi(S("audio:") + self->seriesKey(), t.value(S("label")).toString());
            break;
        }
        self->m_engine->removeAudioTrack(cur);
        emit self->tracksExtraChanged();
        self->flash(tx(S("player.ozvuchka_udalena")));
    });
    emit tracksExtraChanged();
}

// ---- заставка, титры, следующая серия ----

MediaMarkers PlayerModel::markers() const {
    QList<Chapter> ch;
    for (const QVariant &v : m_engine->chapters()) ch << Chapter{v.toMap().value(S("timeMs")).toLongLong(), v.toMap().value(S("title")).toString()};
    MediaMarkers m = MarkersDetector::merge(MarkersDetector::fromChapters(ch, m_length), m_serverMarkers, m_length);
    if (m_queue.size() <= 1 && !m_req.collection) m.credits.reset();
    return m;
}

std::optional<TimeRange> PlayerModel::introNow() const {
    if (!m_started) return std::nullopt;
    return MarkersDetector::introAt(markers(), m_time);
}

QVariantList PlayerModel::introRange() const {
    const auto r = markers().intro;
    return r ? QVariantList{r->startMs, r->endMs} : QVariantList();
}

QVariantList PlayerModel::creditsRange() const {
    const auto r = markers().credits;
    return r ? QVariantList{r->startMs, r->endMs} : QVariantList();
}

QVariantList PlayerModel::chapters() const { return m_engine->chapters(); }

void PlayerModel::skipIntroImpl(bool autoSkip) {
    const auto r = introNow();
    if (!r) return;
    // гость комнаты: заставку пропускает ведущий за всех
    if (partyGuest()) { if (!autoSkip) flash(tx(S("party.peremativaet_sozdatel"))); return; }
    PlayerLog::write(S("skip intro %1-%2s%3").arg(r->startMs / 1000).arg(r->endMs / 1000).arg(autoSkip ? S(" (auto)") : QString()));
    seekTo(r->endMs);
    if (autoSkip) flash(tx(S("player.zastavka_propuschena")));
}

void PlayerModel::fetchMarkers() {
    if (m_markersAsked || !m_started || m_length <= 0 || m_serverMarkers || !m_lib) return;
    m_markersAsked = true;
    const int i = m_index;
    const MarkerQuery q = i < m_req.markerQueries.size() ? m_req.markerQueries[i] : MarkerQuery{};
    const bool single = m_queue.size() <= 1 && !m_req.collection;
    QList<Chapter> ch;
    for (const QVariant &v : m_engine->chapters()) ch << Chapter{v.toMap().value(S("timeMs")).toLongLong(), v.toMap().value(S("title")).toString()};
    const MediaMarkers file = MarkersDetector::fromChapters(ch, m_length);
    if (file.intro && (single || file.credits)) return;
    const auto path = MarkersDetector::serverPath(q.title, q.alt, q.season, q.episode, m_length);
    if (!path) return;
    auto apply = [this, single](MediaMarkers m, const char *from) {
        if (single) m.credits.reset();
        PlayerLog::write(S("[markers] %1: intro %2, credits %3").arg(QString::fromLatin1(from))
                             .arg(m.intro ? S("%1-%2s").arg(m.intro->startMs / 1000).arg(m.intro->endMs / 1000) : S("-"))
                             .arg(m.credits ? S("%1s").arg(m.credits->startMs / 1000) : S("-")));
        m_serverMarkers = m;
        emit markersChanged();
    };
    // свой кэш: отметки сразу, без сети при каждом открытии
    if (const auto cached = Store::of(S("markers")).get(*path)) {
        const QJsonObject o = QJsonDocument::fromJson(cached->toUtf8()).object();
        if (const auto m = MarkersDetector::parseServer(&o); m && !m->isEmpty()) { apply(*m, "cache"); return; }
    }
    const QString url = m_currentUrl;
    QPointer<PlayerModel> self(this);
    m_lib->api().get(*path, [self, i, url, path, apply](const ApiResult &r) {
        if (!self || !r.ok()) return;
        Store::of(S("markers")).put(*path, QString::fromUtf8(QJsonDocument(r.json).toJson(QJsonDocument::Compact)));
        const auto m = MarkersDetector::parseServer(&r.json);
        if (self->m_closing || self->m_index != i || self->m_currentUrl != url || !m || m->isEmpty()) return;
        apply(*m, "server");
    });
}

void PlayerModel::checkCredits() {
    const auto at = MarkersDetector::nextOfferAt(markers(), m_length);
    const bool inCredits = at && m_started && m_time >= *at && m_time < m_length;
    if (inCredits && m_nextIn < 0 && !m_nextDismissed && !m_closing && m_index + 1 < m_queue.size() && PlaybackSettings::offerNextOnCredits() &&
        !partyGuest()) {
        PlayerLog::write(S("[markers] credits at %1s: next in %2s").arg(m_time / 1000).arg(PlaybackSettings::nextCountdown()));
        m_nextIn = PlaybackSettings::nextCountdown();
        m_nextFrom = *at;
        emit nextChanged();
    }
    if (m_nextIn >= 0 && m_nextFrom >= 0 && m_time < m_nextFrom - 1000) {
        PlayerLog::write(S("[markers] rewound before the next card: countdown stopped"));
        m_nextIn = -1;
        m_nextFrom = -1;
        emit nextChanged();
    }
}

void PlayerModel::warmNext() {
    const int next = m_index + 1;
    if (next >= m_queue.size() || m_queue[next].url.isEmpty() || m_warmed.contains(next)) return;
    m_warmed.insert(next);
    const QString video = m_queue[next].url;
    sidecars(next, [video](const Sides &s) {
        BurstProxy &p = Reserve::proxy();
        Reserve::warmUp(p.wrap(video), 4 << 20);
        for (const QString &a : s.audio) Reserve::warmUp(p.wrap(a), 1 << 20);
    });
}

// ---- раз в секунду: отсчёт, «копим запас»; раз в 2 с — сторож ----

void PlayerModel::tick() {
    if (!m_open || m_closing) return;
    // На паузе отсчёт ждёт (конец файла — не пауза)
    if (m_nextIn > 0 && !paused()) { --m_nextIn; emit nextChanged(); }
    else if (m_nextIn == 0) { playNext(); return; }
    // через 15 с на серии — её озвучки и субтитры следующей
    if (m_started && m_index + 1 < m_queue.size() && now() - m_openedAt > 15000) sidecars(m_index + 1, [](const Sides &) {});

    const qint64 t = now();
    if (m_catchingUp) {
        const int gathered = m_catchUp->progress(m_currentUrl);
        auto end = [this](bool restart) {
            m_catchingUp = false;
            const bool here = m_catchUp->end(m_currentUrl, restart && !m_closing && m_time > 0);
            if (m_status == m_catchUpStatus) setStatus({});
            if (here) { m_lastSeekAt = now(); m_supervisor.seeked(); doSeek(m_time); }
        };
        if (paused() || m_scrubbing || m_lastSeekAt > m_catchUp->startedAt()) end(false);
        else if (gathered < 0) end(true);
        else if (m_bufferingSince > 0) {
            m_catchUpStatus = tx(S("player.set_medlennee_filma_kopim"), {{"p", num(gathered)}});
            setStatus(m_catchUpStatus);
        }
        return;
    }
    if (!m_started || paused() || m_scrubbing || m_time <= 0) return;
    if (t - m_openedAt < 20000 || t - m_lastSeekAt < 20000) return;
    m_catchingUp = m_catchUp->maybeStart(m_currentUrl, true);
}

void PlayerModel::superviseTick() {
    if (!m_started || m_closing || !m_engine->loaded()) return;
    PlaybackSupervisor::Stats s;
    s.timeMs = m_engine->timeMs();
    const QVariantList audio = m_engine->audioTracks();
    const int cur = selectedId(audio);
    const double apts = m_engine->audioPts();
    const qint64 played = cur != -1 ? (apts > 0 ? qint64(apts * 1000) : s.timeMs) : 0;
    s.audioPlayed = s.audioDecoded = played;
    s.pictures = 0; // у mpv нет счётчика показанных кадров: проверки застывшей картинки выключены
    s.expectedFps = m_engine->fps();
    s.audioTracks = int(audio.size());
    s.audioSelected = cur != -1;
    s.videoTracks = m_engine->videoWidth() > 0 ? 1 : 0;
    s.userMuted = m_muted || m_volume == 0;
    m_supervisor.tick(s);
}

void PlayerModel::restartVideo() {
    PlayerLog::write(S("restart video output at %1s").arg(m_time / 1000));
    m_engine->restartVideoOutput();
}

void PlayerModel::fixSound() {
    m_engine->setMute(m_muted);
    m_engine->setVolume(m_volume);
    ensureAudio();
}

void PlayerModel::reopen(bool fresh) {
    PlayerLog::write(S("supervisor: reopen at %1s%2").arg(m_time / 1000).arg(fresh ? S(" with a fresh reserve") : QString()));
    if (fresh) Reserve::proxy().dropAll();
    reopenAt(m_time);
}

void PlayerModel::log(const QString &message) { PlayerLog::write(message); }

// ---- прогресс и настройки ----

QString PlayerModel::watchKey(int i) const {
    if (i < 0) i = m_index;
    return i < m_req.watchKeys.size() ? m_req.watchKeys[i] : QString();
}

void PlayerModel::savePos(qint64 t, qint64 len, int i) {
    const QString key = watchKey(i);
    if (key.isEmpty() || !m_lib || t < 5000) return;
    const bool done = len > 0 && t > len - 15000;
    m_lib->noteWatch(key, done ? len : t, len);
}

qint64 PlayerModel::savedPos(int i) const {
    const QString key = watchKey(i);
    if (key.isEmpty() || !m_lib) return 0;
    const auto w = m_lib->watchInfo(key);
    return !w || w->watched() ? 0 : w->pos;
}

QString PlayerModel::titleKey() const { return m_title.section(S(" · "), 0, 0).toLower(); }

QString PlayerModel::seriesKey() const {
    const QString id = m_req.watchKeys.isEmpty() ? QString() : m_req.watchKeys.first().section(QLatin1Char('|'), 0, 0);
    return id.trimmed().isEmpty() ? titleKey() : S("m") + id;
}

std::optional<QString> PlayerModel::pref(const QString &kind) const {
    if (auto v = ui().get(kind + S(":") + seriesKey())) return v;
    return ui().get(kind + S(":") + titleKey());
}

QString PlayerModel::prefLanguage(const QString &kind) const {
    auto v = pref(kind);
    if (!v) v = ui().get(kind + S(":*"));
    if (!v) return {};
    static const QRegularExpression re(S("\\[([^\\]]+)\\]"));
    const auto m = re.match(*v);
    return m.hasMatch() ? m.captured(1) : QString();
}

void PlayerModel::putUi(const QString &key, const std::optional<QString> &v) { ui().put(key, v); }

void PlayerModel::flash(const QString &text) {
    m_flashText = text;
    setStatus(text);
    m_flashTimer.start();
    emit flashed(text);
}

void PlayerModel::setStatus(const QString &s) {
    if (m_status == s) return;
    m_status = s;
    emit statusChanged();
}

// ---- названия ----

QString PlayerModel::heading() const {
    return m_title + (m_queue.size() > 1 ? S("  ·  %1/%2").arg(m_index + 1).arg(m_queue.size()) : QString());
}

QString PlayerModel::prevTitle() const {
    const int i = m_index - 1;
    return i >= 0 && i < m_queue.size() && !m_queue[i].url.isEmpty() ? m_queue[i].title : QString();
}

QString PlayerModel::nextEntryTitle() const {
    const int i = m_index + 1;
    return i < m_queue.size() && !m_queue[i].url.isEmpty() ? m_queue[i].title : QString();
}

QString PlayerModel::nextTitle() const {
    const int i = m_index + 1;
    if (i >= m_queue.size()) return {};
    const QString fallback = m_queue[i].title;
    if (m_req.collection)
        return tx(S("player.sleduyuschiy_film_p"), {{"p", i < m_req.presence.size() ? m_req.presence[i].title : fallback}});
    std::optional<QString> se;
    if (i < m_req.markerQueries.size() && m_req.markerQueries[i].episode)
        se = S("S%1E%2").arg(m_req.markerQueries[i].season.value_or(1), 2, 10, QLatin1Char('0')).arg(*m_req.markerQueries[i].episode, 2, 10, QLatin1Char('0'));
    if (se || m_req.presence.size() > 1) {
        QString p = se.value_or(QString());
        if (p.isEmpty()) p = i < m_req.presence.size() && m_req.presence[i].episode ? *m_req.presence[i].episode : fallback;
        return tx(S("player.sleduyuschaya_seriya_p"), {{"p", p}});
    }
    return tx(S("player.sleduyuschaya_p"), {{"p", fallback}});
}

std::pair<QString, QString> PlayerModel::mediaTitles() const {
    const QString ep = m_index < m_queue.size() && !m_queue[m_index].title.isEmpty() ? m_queue[m_index].title : m_title;
    return m_queue.size() > 1 ? std::pair{ep, m_req.title} : std::pair{m_req.title, S("Suslik Media")};
}

Presence PlayerModel::presence() const {
    const auto [a, b] = mediaTitles();
    const int i = m_queue.size() > 1 ? m_index : m_req.startIndex;
    if (i >= 0 && i < m_req.presence.size()) return m_req.presence[i];
    return m_queue.size() > 1 ? Presence{b, a} : Presence{a, std::nullopt};
}

QString PlayerModel::clock(qint64 ms) const { return clockText(ms); }

QString PlayerModel::rateText(double r) const {
    QString s = QString::number(r, 'f', 2);
    while (s.contains(QLatin1Char('.')) && (s.endsWith(QLatin1Char('0')) || s.endsWith(QLatin1Char('.')))) s.chop(1);
    return s + S("×");
}

// ---- файлы рядом с видео (SideFiles.kt) ----

QString PlayerModel::sideFileName(const QString &url) const {
    return QUrl::fromPercentEncoding(url.section(QLatin1Char('?'), 0, 0).section(QLatin1Char('/'), -1).toUtf8());
}

/// Название внешнего файла в меню: его папки в раздаче («Chinese Simplified», «English [Netflix]») и то, что его имя
/// добавляет к имени видео («eng Forced»); код языка из этого («eng») — отдельно.
std::pair<QString, QString> PlayerModel::sideName(const QString &sideUrl, const QString &videoUrl) const {
    static const QRegularExpression genericDir(S("^(int\\.?\\s*subs?|subs?|subtitles?|субтитры|сабы)$"), QRegularExpression::CaseInsensitiveOption);
    static const QRegularExpression seps(S("[._]+"));
    const QString full = sideFileName(sideUrl);
    const QString file = full.contains(QLatin1Char('.')) ? full.section(QLatin1Char('.'), 0, -2) : full;
    const QString vfull = sideFileName(videoUrl);
    const QString video = vfull.contains(QLatin1Char('.')) ? vfull.section(QLatin1Char('.'), 0, -2) : vfull;
    QString suffix;
    if (!video.isEmpty() && file.size() > video.size() && file.startsWith(video, Qt::CaseInsensitive))
        suffix = file.mid(video.size()).replace(seps, S(" ")).trimmed();
    const QString first = suffix.section(QLatin1Char(' '), 0, 0);
    QString lang;
    if (first.size() >= 2 && first.size() <= 3 && std::all_of(first.begin(), first.end(), [](QChar c) { return c.isLetter() && c.unicode() < 128; }))
        lang = first.toLower();
    const QString rest = !lang.isEmpty() ? (suffix.contains(QLatin1Char(' ')) ? suffix.section(QLatin1Char(' '), 1) : QString()) : suffix;
    QStringList parts;
    const QString path = m_sidePaths.value(sideUrl);
    if (!path.isEmpty()) {
        QStringList dirs = path.split(QLatin1Char('/'));
        if (!dirs.isEmpty()) dirs.removeLast();   // файл
        if (!dirs.isEmpty()) dirs.removeFirst();  // папка самой раздачи
        for (const QString &d : dirs) if (!genericDir.match(d.trimmed()).hasMatch() && !d.trimmed().isEmpty()) parts << d;
    }
    if (!rest.trimmed().isEmpty()) parts << rest;
    QString title = parts.join(QLatin1Char(' '));
    if (title.trimmed().isEmpty()) {
        if (!lang.isEmpty()) title = lang;
        else if (suffix.isEmpty() && !video.isEmpty() && file.compare(video, Qt::CaseInsensitive) == 0) title = full.section(QLatin1Char('.'), -1);
        else title = file;
    }
    return {title, lang};
}

/// urls с файлами, похожими на запомненный выбор («Flarrow Films [rus]»), первыми: имя в папках или в названии важнее,
/// потом язык. Остальные — в своём порядке.
QStringList PlayerModel::preferFirst(const QStringList &urls, const std::optional<QString> &saved, const QString &videoUrl) const {
    if (!saved || saved->trimmed().isEmpty() || urls.size() < 2) return urls;
    static const QRegularExpression langRe(S("\\[([^\\]]+)\\]\\s*$"));
    const auto m = langRe.match(*saved);
    const QString lang = m.hasMatch() ? m.captured(1).toLower() : QString();
    const QString name = QString(*saved).remove(langRe).trimmed().toLower();
    auto score = [&](const QString &u) {
        const auto [title, l] = sideName(u, videoUrl);
        const QString where = (m_sidePaths.contains(u) ? m_sidePaths.value(u) : sideFileName(u)).toLower();
        const bool byName = name.size() >= 2 && (title.toLower() == name || where.contains(name));
        const bool byLang = !lang.isEmpty() && !l.isEmpty() && (l == lang || l.left(2) == lang.left(2));
        return (byName ? 2 : 0) + (byLang ? 1 : 0);
    };
    QList<std::pair<int, QString>> scored;
    for (const QString &u : urls) scored.append({score(u), u});
    std::stable_sort(scored.begin(), scored.end(), [](const auto &a, const auto &b) { return a.first > b.first; });
    QStringList out;
    for (const auto &p : scored) out << p.second;
    return out;
}

} // namespace suslik::app
