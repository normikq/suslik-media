#include "CinemaSession.h"

#include "Library.h"
#include "Settings.h"
#include "player/MpvEngine.h"
#include "player/PlayerLog.h"
#include "player/Reserve.h"

#include "suslik/core/ApiClient.h"
#include "suslik/core/CloudSync.h"
#include "suslik/core/MirrorLink.h"
#include "suslik/core/Store.h"
#include "suslik/core/UrlCoding.h"

#include <QJsonArray>
#include <QJsonObject>
#include <QPointer>
#include <cmath>
#include <QUrl>
#include <algorithm>
#include <cstdio>
#include <memory>

namespace suslik::cinema {

using namespace suslik::core;

namespace {
QString S(const char *s) { return QString::fromUtf8(s); }
QString fileName(const MFile &f) { return f.name.isEmpty() ? f.path.section(QLatin1Char('/'), -1) : f.name; }
QString minutes(qint64 ms) { return S("%1:%2").arg(ms / 60000).arg((ms / 1000) % 60, 2, 10, QLatin1Char('0')); }

std::optional<MFile> movieOf(const LibraryItem &v) {
    if (auto f = v.playable.movieFile()) return f;
    return mainVideo(v.m);
}

/// Номер следующей дорожки после выбранной (allowOff — с «выкл.» после последней: −1).
int nextTrack(const QVariantList &tracks, bool allowOff) {
    QList<int> ids;
    int cur = -1;
    for (const QVariant &t : tracks) {
        const QVariantMap m = t.toMap();
        ids << m.value(S("id")).toInt();
        if (m.value(S("selected")).toBool()) cur = ids.last();
    }
    if (allowOff) ids << -1;
    if (ids.isEmpty()) return -1;
    const qsizetype at = ids.indexOf(cur);
    return ids[(at + 1) % ids.size()];
}
} // namespace

void say(const QString &s) {
    app::PlayerLog::write(s);
    std::fputs((s + QLatin1Char('\n')).toLocal8Bit().constData(), stdout);
    std::fflush(stdout);
}

CinemaSession::CinemaSession(ApiClient &api, CloudSync &sync, app::Library &lib, QObject *parent)
    : QObject(parent), m_api(api), m_sync(sync), m_lib(lib) {
    m_saveTimer.setInterval(10000);
    connect(&m_saveTimer, &QTimer::timeout, this, [this] {
        savePos();
        if (m_mpv && m_mpv->loaded())
            app::PlayerLog::write(m_mpv->statsLine() + S(" avsync=") + m_mpv->property(S("avsync")) + S(" ") +
                                  S("[proxy] ") + app::Reserve::proxy().snapshot());
    });
}

// ---- вход ----------------------------------------------------------------------------------------------------

void CinemaSession::login() {
    auto finished = std::make_shared<bool>(false);
    auto finish = [this, finished](int code) {
        if (*finished) return;
        *finished = true;
        emit done(code);
    };
    connect(&m_lib, &app::Library::pairingChanged, this, [this] {
        if (m_lib.pairingCode().isEmpty()) return;
        say(S("Код устройства: %1").arg(m_lib.pairingCode()));
        say(S("Подтвердите его на сайте: %1 (страница открыта в браузере). Код живёт 10 минут.").arg(m_lib.pairingUrl()));
    });
    connect(&m_lib, &app::Library::loginFinished, this, [finish](const QString &err) {
        if (err.isEmpty()) return;
        say(S("Вход не удался: ") + err);
        finish(1);
    });
    auto signedIn = [this, finish] {
        m_lib.refreshThen([this, finish] {
            say(S("Вход выполнен: %1 (%2). В медиатеке %3.").arg(m_lib.profileName(), m_lib.profileNote()).arg(m_lib.count()));
            m_sync.kick();
            finish(0);
        });
    };
    connect(&m_lib, &app::Library::authenticatedChanged, this, [this, signedIn] {
        if (m_lib.authenticated() == 1) signedIn();
        else if (m_lib.authenticated() == 0) {
            say(S("Нужен вход. Запрашиваю код устройства…"));
            m_lib.startDeviceAuth();
        }
    });
    QTimer::singleShot(20000, this, [this, finished, finish] {
        if (!*finished && m_lib.authenticated() < 0) { say(S("Сервер не отвечает.")); finish(1); }
    });
    QTimer::singleShot(11 * 60 * 1000, this, [finished, finish] {
        if (*finished) return;
        say(S("Код не подтверждён."));
        finish(1);
    });
    m_lib.start();
}

void CinemaSession::whenLibrary(std::function<void()> then) {
    // связи — на время одного вызова (пульт зовёт его на каждый фильм)
    auto *ctx = new QObject(this);
    auto fired = std::make_shared<bool>(false);
    auto finish = [ctx, fired] {
        *fired = true;
        ctx->deleteLater();
    };
    auto go = [fired, finish, then] {
        if (*fired) return;
        finish();
        then();
    };
    auto check = [this, go, fired, finish] {
        if (*fired) return;
        if (m_lib.authenticated() == 1) m_lib.refreshThen(go);
        else if (m_lib.authenticated() == 0) {
            finish();
            note(m_remote ? S("Нужен вход: «Войти» в пульте") : S("Нужен вход: suslik-cinema.exe --login"));
            fail(3);
        }
    };
    connect(&m_lib, &app::Library::authenticatedChanged, ctx, check);
    QTimer::singleShot(20000, ctx, [this, go, fired, finish] {
        if (*fired) return;
        if (m_lib.count() > 0) { note(S("Сервер не ответил: беру сохранённую копию медиатеки.")); go(); return; }
        finish();
        note(S("Сервер не отвечает, сохранённой медиатеки нет."));
        fail(1);
    });
    if (!m_libStarted) {
        m_libStarted = true;
        m_lib.start();
    }
    check();
}

// ---- медиатека -----------------------------------------------------------------------------------------------

QList<std::pair<LibraryItem, QList<LibraryItem>>> CinemaSession::tiles() const {
    const auto grouped = groupVersions(m_lib.items(), [this](const LibraryItem &i) { return m_lib.posterPathOf(i); });
    QList<std::pair<LibraryItem, QList<LibraryItem>>> out;
    for (const LibraryItem &t : grouped.first) {
        QList<LibraryItem> versions = m_lib.versionsOf(t.m.id);
        if (versions.isEmpty()) versions = grouped.second.value(t.m.id);
        if (versions.isEmpty()) versions = {t};
        out.append({t, versions});
    }
    return out;
}

std::optional<MFile> CinemaSession::fileIn(const LibraryItem &item, const PlayArgs &args, int *season, int *episode) const {
    *season = -1;
    *episode = -1;
    if (isCollection(item.playable, &item.m)) {
        const QList<Episode> films = collectionFilms(item);
        if (films.isEmpty()) return std::nullopt;
        int idx = args.episode > 0 ? args.episode - 1 : -1;
        if (idx < 0)
            if (const auto last = m_lib.lastEpisode(item.m.id))
                for (int i = 0; i < films.size(); ++i)
                    if (films[i].file.path == *last) idx = i;
        idx = std::clamp(idx, 0, int(films.size()) - 1);
        *season = 0;
        *episode = idx + 1;
        return films[idx].file;
    }
    if (item.playable.isSeries()) {
        const auto &seasons = item.playable.seasons;
        auto pick = [&](const Season &s, const Episode &e) {
            *season = s.season;
            *episode = e.episode;
            return std::optional<MFile>(e.file);
        };
        if (args.season >= 0) {
            for (const Season &s : seasons) {
                if (s.season != args.season || s.items.isEmpty()) continue;
                for (const Episode &e : s.items)
                    if (args.episode < 0 || e.episode == args.episode) return pick(s, e);
            }
            return std::nullopt;
        }
        if (const auto last = m_lib.lastEpisode(item.m.id))
            for (const Season &s : seasons)
                for (const Episode &e : s.items)
                    if (e.file.path == *last) return pick(s, e);
        for (const Season &s : seasons)
            if (!s.items.isEmpty()) return pick(s, s.items.first());
        return std::nullopt;
    }
    return movieOf(item);
}

QList<Choice> CinemaSession::choicesFor(const LibraryItem &tile, const QList<LibraryItem> &versions, const PlayArgs &args) const {
    int season = -1, episode = -1;
    const auto tileFile = fileIn(tile, args, &season, &episode);
    QList<Choice> out;
    if (!tileFile) return out;
    for (const LibraryItem &ver : versions) {
        if (!ver.m.ready()) continue;
        std::optional<MFile> f;
        if (ver.m.id == tile.m.id) f = tileFile;
        else if (season == 0 && isCollection(ver.playable, &ver.m)) {
            const QList<Episode> films = collectionFilms(ver);
            if (episode >= 1 && episode <= films.size()) f = films[episode - 1].file;
        } else if (season >= 0 && ver.playable.isSeries()) {
            for (const Season &s : ver.playable.seasons)
                if (s.season == season)
                    for (const Episode &e : s.items)
                        if (e.episode == episode && !e.file.path.isEmpty()) f = e.file;
        } else if (season < 0) f = movieOf(ver);
        if (!f || !f->link) continue;
        const auto [w, h] = m_lib.cachedMediaDimensions(ver.m.id, f->path);
        out << Choice{ver, *f, buildPartyVariant(ver, *f, m_lib.serverProfile(ver.m.id), w, h)};
    }
    std::stable_sort(out.begin(), out.end(), [](const Choice &a, const Choice &b) { return a.variant.score > b.variant.score; });
    return out;
}

void CinemaSession::list(const QString &filter) {
    whenLibrary([this, filter] {
        int shown = 0;
        for (const auto &[tile, versions] : tiles()) {
            const QString title = m_lib.titleOf(tile);
            bool named = filter.isEmpty() || title.contains(filter, Qt::CaseInsensitive);
            for (const LibraryItem &v : versions) named = named || v.m.name.contains(filter, Qt::CaseInsensitive);
            if (!named) continue;
            ++shown;
            QString kind = tile.playable.isSeries() ? seriesLine(tile.playable) : S("фильм");
            const auto year = releaseYear(tile);
            say(S("%1  %2%3 — %4%5").arg(tile.m.id).arg(title, year ? S(" (%1)").arg(*year) : QString(), kind,
                                          tile.m.ready() ? QString() : S(" [%1]").arg(statusRu(tile.m))));
            for (const Choice &c : choicesFor(tile, versions, {}))
                say(S("      релиз %1  %2  (счёт %3)").arg(c.version.m.id).arg(c.variant.label).arg(c.variant.score));
        }
        say(S("Показано %1 из %2.").arg(shown).arg(m_lib.count()));
        emit done(0);
    });
}

// ---- воспроизведение -----------------------------------------------------------------------------------------

void CinemaSession::play(const PlayArgs &argsIn) {
    savePos(); // прежний фильм (пульт: другой фильм поверх идущего)
    m_busy = S("Готовлю…");
    emit statusChanged();
    whenLibrary([this, argsIn] {
        PlayArgs args = argsIn;
        const auto all = tiles();
        QList<int> hits;
        bool isId = false;
        const qint64 id = args.query.toLongLong(&isId);
        for (int i = 0; i < all.size(); ++i) {
            const auto &[tile, versions] = all[i];
            if (isId) {
                for (const LibraryItem &v : versions)
                    if (v.m.id == id) hits = {i};
                continue;
            }
            const QString title = m_lib.titleOf(tile);
            if (title.compare(args.query, Qt::CaseInsensitive) == 0 || tile.baseTitle.compare(args.query, Qt::CaseInsensitive) == 0) {
                hits = {i};
                break;
            }
            // название плитки — от одной раздачи; искать и по именам остальных релизов («Upgrade» у «Апгрейда»)
            bool named = title.contains(args.query, Qt::CaseInsensitive);
            for (const LibraryItem &v : versions) named = named || v.m.name.contains(args.query, Qt::CaseInsensitive);
            if (named) hits << i;
        }
        if (hits.isEmpty()) { note(S("Не нашёл «%1». Список: --list").arg(args.query)); fail(2); return; }
        if (hits.size() > 1) {
            note(S("Под «%1» подходит несколько, укажите id:").arg(args.query));
            for (int i : hits) say(S("  %1  %2").arg(all[i].first.m.id).arg(m_lib.titleOf(all[i].first)));
            fail(2);
            return;
        }
        const auto &[tile, versions] = all[hits.first()];
        // id конкретной версии — значит, именно её
        if (isId && id != tile.m.id && args.release == 0 && args.quality.isEmpty()) args.release = id;

        const QList<Choice> choices = choicesFor(tile, versions, args);
        if (choices.isEmpty()) { note(S("У «%1» нет готового файла для просмотра.").arg(m_lib.titleOf(tile))); fail(2); return; }
        const std::optional<QString> cid = tile.m.acceptedAi() ? tile.m.acceptedAi()->canonicalId : std::nullopt;

        // Выбор пользователя в плеерах (синхронизируется по аккаунту): та же раздача, потом то же качество
        Store &ui = Store::of(S("player_ui"));
        QStringList saved;
        if (args.release == 0 && args.quality.isEmpty()) {
            for (const Choice &c : choices)
                if (const auto mag = ui.get(S("vmagnet:m%1").arg(tile.m.id)); mag && mag->toLongLong() == c.version.m.id)
                    args.release = c.version.m.id;
            for (const QString &k : {S("vquality:m%1").arg(tile.m.id), cid ? S("vquality:c") + *cid : QString(), S("vquality:*")})
                if (!k.isEmpty())
                    if (const auto q = ui.get(k)) saved << *q;
        }
        const auto pick = pickVariant(choices, args.release, args.quality, saved);
        if (!pick) { note(S("Такого релиза нет. Варианты: --list %1").arg(tile.m.id)); fail(2); return; }
        const Choice &c = choices[*pick];
        for (int i = 0; i < choices.size(); ++i)
            say(S("%1 релиз %2  %3  (счёт %4)").arg(i == *pick ? S("▶") : S(" ")).arg(choices[i].version.m.id)
                    .arg(choices[i].variant.label).arg(choices[i].variant.score));

        openChoice(tile, choices, *pick, args, std::nullopt);
    });
}

void CinemaSession::openChoice(const LibraryItem &tile, const QList<Choice> &choices, int pick, const PlayArgs &args,
                               std::optional<qint64> startAt) {
    const Choice &c = choices[pick];
    int season = -1, episode = -1;
    std::optional<MFile> tileFile = fileIn(tile, args, &season, &episode);
    if (c.version.m.id == tile.m.id) tileFile = c.file;
    if (season < 0)
        if (const auto se = episodePair(c.file.path)) { season = se->first; episode = se->second; }
    const std::optional<QString> cid = tile.m.acceptedAi() ? tile.m.acceptedAi()->canonicalId : std::nullopt;
    m_key = watchKey(c.version.m.id, c.file);
    m_tileKey = tileFile && c.version.m.id != tile.m.id ? watchKey(tile.m.id, *tileFile) : QString();
    m_cid = cid;
    m_variants.clear();
    for (const Choice &x : choices) m_variants << x.variant;
    m_variant = c.variant;
    m_edition.reset();
    if (const auto ai = c.version.m.acceptedAi(); ai && ai->edition && !ai->edition->isEmpty() && *ai->edition != QLatin1String("default"))
        m_edition = ai->edition;
    else if (const QString e = parseEdition(c.version.m.name); !e.isEmpty() && e != QLatin1String("default"))
        m_edition = e;
    qint64 start = startAt.value_or(0);
    if (!startAt && !args.fromStart) {
        auto w = m_lib.watchInfoWithFallback(m_key, cid);
        if (!w && !m_tileKey.isEmpty()) w = m_lib.watchInfoWithFallback(m_tileKey, cid);
        if (w && !w->watched()) start = w->pos;
    }
    say(S("«%1»%2, файл %3, с %4").arg(m_lib.titleOf(tile),
                                      season > 0 ? S(" S%1E%2").arg(season).arg(episode) : QString(), fileName(c.file), minutes(start)));
    m_title = m_lib.titleOf(tile) + (season > 0 ? S(" · S%1E%2").arg(season).arg(episode) : season == 0 ? S(" · фильм %1").arg(episode) : QString());
    m_release = c.variant.label;
    m_busy = S("Открываю «%1»…").arg(m_lib.titleOf(tile));
    emit statusChanged();
    m_lib.saveEpisode(c.version.m.id, c.file.path);
    if (tileFile) m_lib.saveEpisode(tile.m.id, tileFile->path);

    const QString path = S("/api/file/mirror-url?link=") + formEncode(*c.file.link) + S("&name=") + formEncode(fileName(c.file));
    QPointer<CinemaSession> self(this);
    const QString key = m_key;
    m_api.get(path, [self, start, key](const ApiResult &r) {
        if (!self || self->m_key != key) return; // пока ждали адрес, включили другой фильм
        const QString url = r.ok() ? r.json.value(S("url")).toString() : QString();
        if (url.isEmpty()) { self->note(S("Сервер не дал адрес файла: ") + ApiClient::describe(r.error)); self->fail(4); return; }
        // Только зеркало нашего сервера: AllDebrid в РФ заблокирован, прямые ссылки не открываем
        if (!isMirrorUrl(url, self->m_api.base())) { self->note(S("Сервер дал адрес не через зеркало — не открываю.")); self->fail(4); return; }
        self->m_mirror = url;
        BurstProxy &proxy = app::Reserve::proxy(&self->m_api);
        proxy.setCapBytes(qint64(app::PlaybackSettings::burstMb()) << 20);
        proxy.keepOnly({url});
        app::PlayerLog::write(S("[cinema] зеркало %1, запас %2 МБ").arg(MirrorLink::fileName(url)).arg(app::PlaybackSettings::burstMb()));
        self->m_startMs = start;
        self->m_userSeeked = false;
        self->m_lastSaved = -1;
        self->m_busy.clear();
        self->noteSeek(start);
        emit self->statusChanged();
        emit self->ready(proxy.wrap(url), start);
        emit self->opened(key);
    });
}

bool CinemaSession::playKey(const QString &key, qint64 startMs) {
    const qsizetype bar = key.indexOf(QLatin1Char('|'));
    if (bar <= 0) return false;
    const qint64 id = key.left(bar).toLongLong();
    const QString path = key.mid(bar + 1);
    const LibraryItem *item = m_lib.item(id);
    if (!item || !item->m.ready()) return false;
    std::optional<MFile> file;
    for (const MFile &f : item->m.files)
        if (f.path == path && f.link) file = f;
    if (!file) return false;
    // плитка, в которой этот релиз (название, «последняя серия»)
    LibraryItem tile = *item;
    for (const auto &[t, versions] : tiles())
        for (const LibraryItem &v : versions)
            if (v.m.id == id) tile = t;
    savePos();
    const auto [w, h] = m_lib.cachedMediaDimensions(id, path);
    const QList<Choice> one{Choice{*item, *file, buildPartyVariant(*item, *file, m_lib.serverProfile(id), w, h)}};
    PlayArgs args;
    if (const auto se = episodePair(path)) { args.season = se->first; args.episode = se->second; }
    openChoice(tile, one, 0, args, std::max<qint64>(0, startMs));
    return true;
}

void CinemaSession::stop() {
    flush();
    m_key.clear();
    m_tileKey.clear();
    m_title.clear();
    m_release.clear();
    m_variants.clear();
    emit stopRequested();
    emit statusChanged();
    emit stopped();
}

void CinemaSession::noteSeek(qint64 toMs) {
    m_seekTarget = toMs;
    m_seekAt.start();
    ++m_seekStamp;
}

void CinemaSession::roomSeek(qint64 ms) {
    if (!m_mpv || !m_mpv->loaded()) return;
    noteSeek(ms);
    m_mpv->seekTo(ms);
}

qint64 CinemaSession::syncTimeMs() const {
    if (!m_mpv) return 0;
    if (m_seekAt.isValid() && m_seekAt.elapsed() < 2500) return m_seekTarget;
    const qint64 e = m_mpv->timeMs();
    return e > 0 ? e : m_seekTarget;
}

void CinemaSession::attach(app::MpvEngine &mpv) {
    m_mpv = &mpv;
    connect(&mpv, &app::MpvEngine::lengthChanged, this, [this] {
        if (!m_mirror.isEmpty() && m_mpv->lengthMs() > 0) app::Reserve::proxy().setDuration(m_mirror, m_mpv->lengthMs());
    });
    connect(&mpv, &app::MpvEngine::stateChanged, this, [this] {
        if (m_mpv->loaded() && !m_saveTimer.isActive()) m_saveTimer.start();
        if (m_mpv->paused()) savePos();
    });
    connect(&mpv, &app::MpvEngine::seekDone, this, &CinemaSession::savePos);
    connect(&mpv, &app::MpvEngine::finished, this, [this] {
        if (m_key.isEmpty() || m_mpv->lengthMs() <= 0) return;
        m_lib.noteWatch(m_key, m_mpv->lengthMs(), m_mpv->lengthMs(), m_cid);
        if (!m_tileKey.isEmpty()) m_lib.noteWatch(m_tileKey, m_mpv->lengthMs(), m_mpv->lengthMs(), m_cid);
        m_sync.kick();
        say(S("[cinema] фильм кончился"));
    });
    // Облако догоняет старт: место с телефона или ТВ пришло в первые 30 с, а смотрим ещё с начального места —
    // переходим туда (если человек сам не перематывал).
    connect(&m_sync, &CloudSync::changed, this, [this] {
        if (m_key.isEmpty() || !m_mpv->loaded() || !m_openedAt.isValid() || m_openedAt.elapsed() > 30000 || m_userSeeked) return;
        const auto w = m_lib.watchInfoWithFallback(m_key, m_cid);
        if (!w || w->watched() || std::abs(w->pos - m_mpv->timeMs()) < 30000 || w->pos <= m_startMs) return;
        say(S("[cinema] место с другого устройства: %1").arg(minutes(w->pos)));
        m_mpv->seekTo(w->pos);
    });
    connect(&mpv, &app::MpvEngine::opened, this, [this] { m_openedAt.start(); });
}

void CinemaSession::command(const QString &cmd) {
    if (!m_mpv || !m_mpv->loaded()) return;
    const qint64 t = m_mpv->timeMs(), len = m_mpv->lengthMs();
    auto seek = [&](qint64 d) {
        const qint64 to = std::clamp<qint64>(t + d, 0, len > 0 ? len - 1000 : t + d);
        if (const QString why = m_lock ? m_lock() : QString(); !why.isEmpty()) { note(why); return; }
        say(S("[cinema] перемотка %1 → %2").arg(minutes(t), minutes(to)));
        m_userSeeked = true;
        noteSeek(to);
        m_mpv->seekTo(to);
    };
    if (cmd == QLatin1String("seek+")) seek(10000);
    else if (cmd == QLatin1String("seek-")) seek(-10000);
    else if (cmd == QLatin1String("jump+")) seek(600000);
    else if (cmd == QLatin1String("jump-")) seek(-600000);
    else if (cmd == QLatin1String("audio")) {
        const int id = nextTrack(m_mpv->audioTracks(), false);
        if (id < 0) return;
        m_mpv->setAudioTrack(id);
        for (const QVariant &v : m_mpv->audioTracks())
            if (v.toMap().value(S("id")).toInt() == id) say(S("[cinema] озвучка %1: %2").arg(id).arg(v.toMap().value(S("label")).toString()));
    } else if (cmd == QLatin1String("sub")) {
        const int id = nextTrack(m_mpv->subTracks(), true);
        m_mpv->setSubTrack(id);
        QString label = S("выкл.");
        for (const QVariant &v : m_mpv->subTracks())
            if (v.toMap().value(S("id")).toInt() == id) label = v.toMap().value(S("label")).toString();
        say(S("[cinema] субтитры %1").arg(label));
    } else if (cmd == QLatin1String("stats")) {
        say(m_mpv->statsLine() + S(" avsync=") + m_mpv->property(S("avsync")));
    }
}

void CinemaSession::savePos() {
    if (!m_mpv || m_key.isEmpty() || !m_mpv->loaded()) return;
    const qint64 t = m_mpv->timeMs(), len = m_mpv->lengthMs();
    if (t < 5000 || t == m_lastSaved) return;
    m_lastSaved = t;
    const qint64 pos = len > 0 && t > len - 15000 ? len : t;
    m_lib.noteWatch(m_key, pos, len, m_cid);
    if (!m_tileKey.isEmpty()) m_lib.noteWatch(m_tileKey, pos, len, m_cid);
}

void CinemaSession::flush() {
    m_lastSaved = -1;
    savePos();
    m_sync.kick();
    Store::flushAll();
}

// ---- пульт VAO -----------------------------------------------------------------------------------------------

void CinemaSession::note(const QString &text) {
    say(text);
    m_msg = text;
    ++m_msgN;
    emit statusChanged();
}

void CinemaSession::fail(int code) {
    m_busy.clear();
    emit statusChanged();
    if (!m_remote) emit done(code);
}

void CinemaSession::startRemote() {
    m_remote = true;
    m_lib.setOpenPairingPage(false); // код — на телефоне; браузер поверх игры не нужен (кнопка на телефоне откроет)
    connect(&m_lib, &app::Library::authenticatedChanged, this, [this] {
        emit statusChanged();
        if (m_lib.authenticated() == 1) m_lib.refreshThen([this] { emit libraryChanged(); });
    });
    connect(&m_lib, &app::Library::pairingChanged, this, &CinemaSession::statusChanged);
    connect(&m_lib, &app::Library::profileChanged, this, &CinemaSession::statusChanged);
    connect(&m_lib, &app::Library::versionChanged, this, &CinemaSession::libraryChanged);
    connect(&m_lib, &app::Library::loginFinished, this, [this](const QString &err) {
        if (!err.isEmpty()) note(S("Вход не удался: ") + err);
    });
    if (!m_libStarted) {
        m_libStarted = true;
        m_lib.start();
    }
}

bool CinemaSession::option(const QString &key) const {
    const auto v = Store::of(S("settings")).get(S("remote_") + key);
    return v ? *v == QLatin1String("1") : true; // по умолчанию обе включены
}

void CinemaSession::remote(const QJsonObject &c) {
    const QString op = c.value(S("op")).toString();
    if (op == QLatin1String("login")) {
        if (m_lib.authenticated() != 1) m_lib.startDeviceAuth();
    } else if (op == QLatin1String("cancelLogin")) {
        m_lib.cancelDeviceAuth();
    } else if (op == QLatin1String("logout")) {
        m_lib.logout();
    } else if (op == QLatin1String("refresh")) {
        m_lib.refreshThen([this] { emit libraryChanged(); });
    } else if (op == QLatin1String("play")) {
        PlayArgs a;
        a.query = QString::number(c.value(S("id")).toInteger());
        a.release = c.value(S("release")).toInteger(0);
        a.season = c.value(S("season")).toInt(-1);
        a.episode = c.value(S("episode")).toInt(-1);
        a.fromStart = c.value(S("fromStart")).toBool();
        play(a);
    } else if (op == QLatin1String("stop")) {
        stop();
    } else if (op == QLatin1String("opt")) {
        const QString key = c.value(S("key")).toString();
        if (key != QLatin1String("muteGame") && key != QLatin1String("pauseWithGame")) return;
        Store::of(S("settings")).put(S("remote_") + key, c.value(S("on")).toBool() ? S("1") : S("0"));
        emit optionChanged(key);
        emit statusChanged();
    } else if (op == QLatin1String("quit")) {
        emit quitRequested();
    } else if (m_extra && m_extra(op, c)) {
        // команды комнаты «Вместе» (CinemaParty)
    } else if (m_mpv && m_mpv->loaded()) {
        const QString why = m_lock ? m_lock() : QString();
        if (!why.isEmpty() && (op == QLatin1String("toggle") || op == QLatin1String("seek") || op == QLatin1String("seekBy"))) {
            note(why);
            return;
        }
        if (op == QLatin1String("toggle")) m_mpv->togglePause();
        else if (op == QLatin1String("seek")) {
            const qint64 len = m_mpv->lengthMs();
            m_userSeeked = true;
            const qint64 to = std::clamp<qint64>(c.value(S("ms")).toInteger(), 0, len > 0 ? len - 1000 : 0);
            noteSeek(to);
            m_mpv->seekTo(to);
        } else if (op == QLatin1String("seekBy")) {
            const qint64 d = c.value(S("ms")).toInteger();
            command(d == 600000 ? S("jump+") : d == -600000 ? S("jump-") : d > 0 ? S("seek+") : S("seek-"));
        } else if (op == QLatin1String("audio")) {
            if (c.contains(S("id"))) m_mpv->setAudioTrack(c.value(S("id")).toInt());
            else command(S("audio"));
        } else if (op == QLatin1String("sub")) {
            if (c.contains(S("id"))) m_mpv->setSubTrack(c.value(S("id")).toInt());
            else command(S("sub"));
        } else if (op == QLatin1String("volume")) {
            m_volume = std::clamp(c.value(S("v")).toInt(100), 0, 150);
            m_mpv->setVolume(m_volume);
        }
        emit statusChanged();
    }
}

QJsonObject CinemaSession::status() const {
    QJsonObject o{{S("auth"), m_lib.authenticated()}, {S("user"), m_lib.profileName()},
                  {S("code"), m_lib.pairingCode()}, {S("url"), m_lib.pairingUrl()},
                  {S("site"), !m_api.base().isEmpty()}, {S("busy"), m_busy}, {S("msg"), m_msg}, {S("msgN"), m_msgN},
                  {S("muteGame"), option(S("muteGame"))}, {S("pauseWithGame"), option(S("pauseWithGame"))}};
    if (m_extraStatus) m_extraStatus(o);
    if (m_mpv && m_mpv->loaded() && !m_key.isEmpty()) {
        auto tracks = [](const QVariantList &list) {
            QJsonArray a;
            for (const QVariant &v : list) {
                const QVariantMap m = v.toMap();
                a.append(QJsonObject{{S("id"), m.value(S("id")).toInt()}, {S("l"), m.value(S("label")).toString()},
                                     {S("sel"), m.value(S("selected")).toBool()}});
            }
            return a;
        };
        o.insert(S("play"), QJsonObject{{S("title"), m_title}, {S("rel"), m_release},
                                         // секунды: файл переписывается раз в секунду, а не на каждый кадр
                                         {S("pos"), m_mpv->timeMs() / 1000}, {S("dur"), m_mpv->lengthMs() / 1000},
                                         {S("paused"), m_mpv->paused()}, {S("buf"), m_mpv->buffering()}, {S("vol"), m_volume},
                                         {S("audio"), tracks(m_mpv->audioTracks())}, {S("subs"), tracks(m_mpv->subTracks())}});
    }
    return o;
}

QJsonArray CinemaSession::library() const {
    QJsonArray out;
    for (const auto &[tile, versions] : tiles()) {
        QJsonObject t{{S("id"), tile.m.id}, {S("t"), m_lib.titleOf(tile)}, {S("ok"), tile.m.ready()}};
        if (const auto y = releaseYear(tile)) t.insert(S("y"), *y);
        if (!tile.m.ready()) t.insert(S("st"), statusRu(tile.m));
        if (isCollection(tile.playable, &tile.m)) {
            t.insert(S("k"), S("c"));
            t.insert(S("n"), int(collectionFilms(tile).size()));
        } else if (tile.playable.isSeries()) {
            t.insert(S("k"), S("s"));
            QJsonArray seasons;
            for (const Season &se : tile.playable.seasons) {
                QJsonArray eps;
                for (const Episode &e : se.items) eps.append(e.episode);
                seasons.append(QJsonObject{{S("n"), se.season}, {S("e"), eps}});
            }
            t.insert(S("s"), seasons);
        } else {
            t.insert(S("k"), S("m"));
        }
        QJsonArray rel;
        for (const Choice &c : choicesFor(tile, versions, {}))
            rel.append(QJsonObject{{S("id"), c.version.m.id}, {S("l"), c.variant.label}});
        t.insert(S("rel"), rel);
        out.append(t);
    }
    return out;
}

} // namespace suslik::cinema
