#include "PlayModel.h"
#include "Library.h"
#include "LibraryPage.h"

#include "suslik/core/ApiClient.h"
#include "suslik/core/MarkersDetector.h"
#include "suslik/core/Strings.h"
#include "suslik/core/TimeText.h"
#include "suslik/core/UrlCoding.h"

#include <QJsonArray>
#include <QPointer>
#include <algorithm>
#include <memory>

namespace suslik::app {

namespace {
QString S(const char *s) { return QString::fromUtf8(s); }
QString num(qint64 v) { return QString::number(v); }
constexpr int PLAYLIST_MAX = 200; // предел сервера (website routes/mirror.js PLAYLIST_MAX_ITEMS)

QString fileName(const MFile &f) { return f.name.isEmpty() ? f.path.section(QLatin1Char('/'), -1) : f.name; }

QString durationText(qint64 ms) {
    const int min = std::max<int>(1, int(ms / 60000));
    return min >= 60 ? tx(S("library.det_ch_min"), {{"h", num(min / 60)}, {"m", num(min % 60)}}) : tx(S("library.det_min"), {{"m", num(min)}});
}
} // namespace

PlayModel::PlayModel(QObject *parent) : QObject(parent) { m_status = tx(S("library.gotovim")); }

void PlayModel::setLibrary(Library *l) {
    if (m_lib) disconnect(m_lib, nullptr, this, nullptr);
    m_lib = l;
    if (m_lib) {
        connect(m_lib, &Library::versionChanged, this, &PlayModel::changed);
        connect(m_lib, &Library::heroReady, this, &PlayModel::changed);
    }
}

const LibraryItem *PlayModel::item() const { return m_lib ? m_lib->item(m_id) : nullptr; }

QList<Episode> PlayModel::filmList() const {
    QList<Episode> out;
    if (const LibraryItem *it = item(); it && it->playable.isSeries())
        for (const Season &s : it->playable.seasons) out += s.items;
    return out;
}

void PlayModel::load(qint64 magnetId, const QString &path) {
    m_id = magnetId;
    m_startPath = path;
    m_fromStart = false;
    m_film = m_season = m_episode = 0;
    const LibraryItem *it = item();
    m_series = it && it->playable.isSeries() && !it->playable.seasons.isEmpty();
    m_collection = m_series && isCollection(it->playable, &it->m);
    if (m_series) {
        const auto films = filmList();
        for (int i = 0; i < films.size(); ++i)
            if (films[i].file.path == path) m_film = i;
        const auto &seasons = it->playable.seasons;
        for (int s = 0; s < seasons.size(); ++s)
            for (int e = 0; e < seasons[s].items.size(); ++e)
                if (seasons[s].items[e].file.path == path) { m_season = s; m_episode = e; }
    }
    if (it && !m_lib->cachedHero(it->posterQueries, 0)) m_lib->requestHero(it->posterQueries, 0);
    emit changed();
    fetch();
}

MFile PlayModel::currentFile() const {
    const LibraryItem *it = item();
    if (!it) return MFile{0, m_startPath.section(QLatin1Char('/'), -1), m_startPath};
    if (m_collection) {
        const auto films = filmList();
        if (!films.isEmpty()) return films[std::clamp(m_film, 0, int(films.size()) - 1)].file;
    }
    if (m_series && !it->playable.seasons.isEmpty()) {
        const auto &seasons = it->playable.seasons;
        const Season &s = seasons[std::clamp(m_season, 0, int(seasons.size()) - 1)];
        if (!s.items.isEmpty()) return s.items[std::clamp(m_episode, 0, int(s.items.size()) - 1)].file;
    }
    if (it->playable.isMovie()) return it->playable.file;
    for (const MFile &f : it->m.files)
        if (f.path == m_startPath) return f;
    return MFile{};
}

void PlayModel::setFilmIndex(int i) {
    if (i == m_film) return;
    m_film = i; m_fromStart = false;
    emit changed();
    fetch();
}

void PlayModel::setSeasonIndex(int i) {
    if (i == m_season) return;
    m_season = i; m_episode = 0; m_fromStart = false;
    emit changed();
    fetch();
}

void PlayModel::setEpisodeIndex(int i) {
    if (i == m_episode) return;
    m_episode = i; m_fromStart = false;
    emit changed();
    fetch();
}

std::pair<QString, QString> PlayModel::filmInfo(const Episode &ep, int index) const {
    const LibraryItem *it = item();
    const QString base = it ? it->baseTitle : QString();
    const QString raw = fileName(ep.file);
    const auto year = extractYear(raw);
    const QString cleaned = cleanCollectionFilmTitle(raw);
    QString part;
    if (!base.isEmpty() && cleaned.startsWith(base, Qt::CaseInsensitive)) {
        part = cleaned.mid(base.size());
        static const QString trim = S(" -:·.–");
        while (!part.isEmpty() && trim.contains(part.front())) part.remove(0, 1);
        while (!part.isEmpty() && trim.contains(part.back())) part.chop(1);
    }
    const QString title = !part.isEmpty() ? part
                          : ep.title && !ep.title->trimmed().isEmpty() ? cleanCollectionFilmTitle(*ep.title)
                          : !cleaned.isEmpty() ? cleaned : tx(S("library.film_p"), {{"p", num(index + 1)}});
    const auto w = m_lib ? m_lib->watchInfo(watchKey(m_id, ep.file)) : std::nullopt;
    QStringList details;
    if (year) details << num(*year);
    details << (w && w->len > 0 ? clockText(w->len) : formatBytes(ep.file.size));
    return {title, details.join(S(" • "))};
}

QString PlayModel::currentTitle() const {
    const LibraryItem *it = item();
    const QString base = it ? it->baseTitle : m_startPath.section(QLatin1Char('/'), -1);
    if (m_collection) {
        const auto films = filmList();
        if (films.isEmpty()) return base;
        const int i = std::clamp(m_film, 0, int(films.size()) - 1);
        return base + S(" • ") + filmInfo(films[i], i).first;
    }
    // раздачу удалили или пересобрали (m_series — от прошлого load): привязки окна пересчитываются и после этого
    if (m_series && it && !it->playable.seasons.isEmpty()) {
        const auto &seasons = it->playable.seasons;
        const Season &s = seasons[std::clamp(m_season, 0, int(seasons.size()) - 1)];
        if (!s.items.isEmpty()) {
            const Episode &e = s.items[std::clamp(m_episode, 0, int(s.items.size()) - 1)];
            return base + S(" • S%1E%2").arg(s.season, 2, 10, QLatin1Char('0')).arg(e.episode, 2, 10, QLatin1Char('0'));
        }
    }
    return base;
}

QString PlayModel::heading() const {
    const LibraryItem *it = item();
    if (!it) return currentTitle();
    return m_series ? m_lib->titleOf(*it) : currentTitle();
}

QString PlayModel::poster() const {
    const LibraryItem *it = item();
    return it ? m_lib->posterPath(it->posterQueries) : QString();
}

std::optional<WatchInfo> PlayModel::watch() const { return m_lib ? m_lib->watchInfo(watchKey(m_id, currentFile())) : std::nullopt; }

QStringList PlayModel::chips() const {
    const LibraryItem *it = item();
    QStringList out;
    const auto hero = it ? m_lib->cachedHero(it->posterQueries, 0) : std::nullopt;
    if (hero && !hero->year.isEmpty()) out << hero->year;
    else if (it) out << itemYear(*it);
    if (it) out << m_lib->typeLabel(*it);
    if (const auto w = watch(); w && w->len > 0) out << durationText(w->len);
    return out;
}

QString PlayModel::rating() const {
    const LibraryItem *it = item();
    const auto hero = it ? m_lib->cachedHero(it->posterQueries, 0) : std::nullopt;
    return hero && hero->rating > 0 ? QString::asprintf("%.1f", hero->rating) : QString();
}

QString PlayModel::overview() const {
    const LibraryItem *it = item();
    const auto hero = it ? m_lib->cachedHero(it->posterQueries, 0) : std::nullopt;
    return hero ? hero->overview : QString();
}

QString PlayModel::collectionLine() const {
    const LibraryItem *it = item();
    if (!m_collection || !it) return {};
    const auto films = filmList();
    const QString kindName = tx(collectionKindTrKey(collectionKind(int(films.size()), it->m.name)));
    QList<int> years;
    for (const Episode &e : films)
        if (const auto y = extractYear(fileName(e.file))) years << *y;
    QString range;
    if (!years.isEmpty()) {
        const int lo = *std::min_element(years.begin(), years.end()), hi = *std::max_element(years.begin(), years.end());
        range = lo == hi ? S(" • %1").arg(lo) : S(" • %1–%2").arg(lo).arg(hi);
    }
    return kindName + S(" • ") + filmCountRu(int(films.size())) + range;
}

QString PlayModel::collectionHint() const {
    const LibraryItem *it = item();
    if (!m_collection || !it) return {};
    const QString kindName = tx(collectionKindTrKey(collectionKind(int(filmList().size()), it->m.name)));
    return tx(S("library.naydena_p_vyberite_film"), {{"p", kindName.toLower()}});
}

QVariantList PlayModel::films() const {
    QVariantList out;
    const auto list = filmList();
    for (int i = 0; i < list.size(); ++i) {
        const auto [title, details] = filmInfo(list[i], i);
        // свой постер у каждой части: поиск по названию файла (с годом, потом без); нет — постер сборника
        QString poster;
        if (m_lib) {
            const QString raw = fileName(list[i].file);
            const QString name = cleanCollectionFilmTitle(raw);
            const auto year = extractYear(raw);
            QStringList queries;
            if (year) queries << name + QLatin1Char(' ') + num(*year);
            if (!name.isEmpty()) queries << name;
            poster = m_lib->posterPath(queries);
        }
        out << QVariantMap{{S("title"), title}, {S("details"), details}, {S("poster"), poster}};
    }
    return out;
}

QStringList PlayModel::seasons() const {
    QStringList out;
    if (const LibraryItem *it = item(); it && m_series)
        for (const Season &s : it->playable.seasons)
            out << (s.season == 0 ? tx(S("library.spec_materialy")) : tx(S("library.season_sezon"), {{"season", num(s.season)}}));
    return out;
}

QString PlayModel::seasonValue() const {
    const LibraryItem *it = item();
    if (!it || !m_series || it->playable.seasons.isEmpty()) return {};
    const Season &s = it->playable.seasons[std::clamp(m_season, 0, int(it->playable.seasons.size()) - 1)];
    return s.season == 0 ? tx(S("library.spec")) : tx(S("library.season_sezon"), {{"season", num(s.season)}});
}

QStringList PlayModel::episodes() const {
    QStringList out;
    const LibraryItem *it = item();
    if (!it || !m_series || it->playable.seasons.isEmpty()) return out;
    const Season &s = it->playable.seasons[std::clamp(m_season, 0, int(it->playable.seasons.size()) - 1)];
    for (const Episode &e : s.items)
        out << (e.title ? S("%1. %2").arg(e.episode).arg(*e.title) : tx(S("library.episode_seriya"), {{"episode", num(e.episode)}}));
    return out;
}

QString PlayModel::episodeValue() const {
    const LibraryItem *it = item();
    if (!it || !m_series || it->playable.seasons.isEmpty()) return {};
    const Season &s = it->playable.seasons[std::clamp(m_season, 0, int(it->playable.seasons.size()) - 1)];
    if (s.items.isEmpty()) return {};
    return tx(S("library.episode_seriya"), {{"episode", num(s.items[std::clamp(m_episode, 0, int(s.items.size()) - 1)].episode)}});
}

QString PlayModel::resumeLabel() const {
    const auto w = watch();
    if (!w || w->watched() || w->pos < 5000) return {};
    return tx(m_collection ? S("library.s_p") : S("library.det_s_p"), {{"p", clockText(w->pos)}});
}

double PlayModel::resumeFraction() const {
    const auto w = watch();
    return w ? double(w->fraction()) : 0.0;
}

QString PlayModel::status() const {
    QString s = (m_mirror && !m_loading) ? tx(S("library.det_mozhno_smotret")) : m_status;
    if (!m_audio.isEmpty()) s += tx(S("library.ozvuchek_size"), {{"size", num(m_audio.size())}});
    if (!m_subs.isEmpty()) s += tx(S("library.subtitrov_size"), {{"size", num(m_subs.size())}});
    return s;
}

bool PlayModel::mine() const { const LibraryItem *it = item(); return it && it->m.mine; }
bool PlayModel::favorite() const { const LibraryItem *it = item(); return it && it->m.favorite; }
void PlayModel::toggleMine() { if (const LibraryItem *it = item()) m_lib->setMine(m_id, !it->m.mine); }
void PlayModel::toggleFavorite() { if (const LibraryItem *it = item()) m_lib->setFavorite(m_id, !it->m.favorite); }

// Очередь плеера: весь сериал (все сезоны) или сборник — со ссылками, чтобы «предыдущая» и «следующая» шли через сезоны;
// сервер берёт до PLAYLIST_MAX пунктов — у длинного сериала столько вокруг этой серии.
QList<MFile> PlayModel::queueFiles() const {
    QList<MFile> all;
    if (const LibraryItem *it = item(); it && m_series)
        for (const Season &s : it->playable.seasons)
            for (const Episode &e : s.items)
                if (e.file.link) all << e.file;
    const QString cur = currentFile().path;
    int at = -1;
    for (int i = 0; i < all.size(); ++i)
        if (all[i].path == cur) at = i;
    if (all.size() <= PLAYLIST_MAX || at < 0) return all.mid(0, PLAYLIST_MAX);
    const int from = std::clamp(at - PLAYLIST_MAX / 2, 0, int(all.size()) - PLAYLIST_MAX);
    return all.mid(from, PLAYLIST_MAX);
}

// Всё готовится до того, как «Смотреть» включится: щелчок, пока озвучки, субтитры или очередь были в пути,
// открывал фильм без них.
void PlayModel::fetch() {
    const int gen = ++m_gen;
    m_loading = true;
    m_mirror.reset(); m_playlist.reset();
    m_subs.clear(); m_audio.clear(); m_sidePaths.clear();
    m_status = tx(S("library.gotovim"));
    emit changed();
    const MFile file = currentFile();
    const LibraryItem *it = item();
    if (!m_lib || !file.link) {
        m_status = tx(S("library.ssylka_na_fayl_esche"));
        m_loading = false;
        emit changed();
        return;
    }
    ApiClient &api = m_lib->api();
    auto mirrorPath = [](const MFile &f) {
        return S("/api/file/mirror-url?link=") + formEncode(*f.link) + S("&name=") + formEncode(fileName(f));
    };
    QList<MFile> subs, audio;
    if (it) { subs = subtitlesFor(it->m, file); audio = externalAudioFor(it->m, file); }
    subs.erase(std::remove_if(subs.begin(), subs.end(), [](const MFile &f) { return !f.link; }), subs.end());
    audio.erase(std::remove_if(audio.begin(), audio.end(), [](const MFile &f) { return !f.link; }), audio.end());

    struct State {
        int left = 0;
        std::optional<QString> mirror;
        QVector<std::optional<QString>> subs, audio;
    };
    auto st = std::make_shared<State>();
    st->left = 1 + int(subs.size() + audio.size());
    st->subs.resize(subs.size());
    st->audio.resize(audio.size());
    QPointer<PlayModel> self(this);
    const QList<MFile> queue = queueFiles();
    const QString title = currentTitle();
    ApiClient *apiP = &api;
    auto finish = [self, gen, st, queue, title, apiP, subs, audio]() {
        if (!self || self->m_gen != gen) return;
        for (int i = 0; i < st->subs.size(); ++i)
            if (const auto &u = st->subs[i]) { self->m_subs << *u; self->m_sidePaths.insert(*u, subs[i].path); }
        for (int i = 0; i < st->audio.size(); ++i)
            if (const auto &u = st->audio[i]) { self->m_audio << *u; self->m_sidePaths.insert(*u, audio[i].path); }
        auto done = [self, gen, st](std::optional<QString> pl) {
            if (!self || self->m_gen != gen) return;
            self->m_playlist = pl;
            self->m_mirror = st->mirror;
            self->m_loading = false;
            self->m_status = st->mirror ? tx(S("library.mozhno_smotret")) : tx(S("library.ne_udalos_otkryt"));
            emit self->changed();
        };
        if (!st->mirror || queue.size() <= 1) { done(std::nullopt); return; }
        QJsonArray items;
        for (const MFile &f : queue) items.append(QJsonObject{{S("index"), f.index}, {S("link"), *f.link}});
        apiP->post(S("/api/playlist"), QJsonObject{{S("magnetId"), self->m_id}, {S("title"), title}, {S("items"), items}},
                 [done](const ApiResult &r) {
                     const QString u = r.ok() ? r.json.value(S("url")).toString() : QString();
                     done(u.isEmpty() ? std::nullopt : std::optional<QString>(u));
                 });
    };
    auto step = [st, finish]() { if (--st->left == 0) finish(); };
    auto urlOf = [](const ApiResult &r) -> std::optional<QString> {
        const QString u = r.ok() ? r.json.value(S("url")).toString() : QString();
        return u.isEmpty() ? std::nullopt : std::optional<QString>(u);
    };
    api.get(mirrorPath(file), [st, step, urlOf](const ApiResult &r) { st->mirror = urlOf(r); step(); });
    for (int i = 0; i < subs.size(); ++i)
        api.get(mirrorPath(subs[i]), [st, step, urlOf, i](const ApiResult &r) { st->subs[i] = urlOf(r); step(); });
    for (int i = 0; i < audio.size(); ++i)
        api.get(mirrorPath(audio[i]), [st, step, urlOf, i](const ApiResult &r) { st->audio[i] = urlOf(r); step(); });
    m_lib->saveEpisode(m_id, file.path);
}

Presence PlayModel::presenceOf(const MFile &f) const {
    const LibraryItem *it = item();
    const QString base = it ? m_lib->titleOf(*it) : currentTitle();
    if (m_collection) {
        const auto films = filmList();
        for (int i = 0; i < films.size(); ++i)
            if (films[i].file.path == f.path) {
                const QString t = filmInfo(films[i], i).first;
                return {t.trimmed().isEmpty() ? base : t, std::nullopt};
            }
        return {base, std::nullopt};
    }
    if (it && m_series)
        for (const Season &s : it->playable.seasons)
            for (const Episode &e : s.items)
                if (e.file.path == f.path)
                    return {base, s.season == 0 ? tx(S("discord.specvypusk"), {{"e", num(e.episode)}})
                                                : tx(S("discord.sezon_seriya"), {{"s", num(s.season)}, {"e", num(e.episode)}})};
    return {base, std::nullopt};
}

// Заставка и титры из открытых баз (/api/markers): серия — по оригинальному названию (принятому ИИ, иначе раздачи)
// и названию медиатеки; фильм сборника или фильм — по имени и году.
std::optional<MarkerQuery> PlayModel::markerQueryOf(const MFile &f) const {
    const LibraryItem *it = item();
    if (!it) return std::nullopt;
    if (m_collection) {
        for (const Episode &e : filmList())
            if (e.file.path == f.path) return MarkerQuery{MarkersDetector::filmTitle(fileName(f), e.title), std::nullopt, std::nullopt, std::nullopt};
        return std::nullopt;
    }
    const QString original = it->m.acceptedAi() && it->m.aiTitle->original && !it->m.aiTitle->original->trimmed().isEmpty()
                                 ? *it->m.aiTitle->original : cleanTitle(it->m.name);
    const QString libTitle = m_lib->titleOf(*it);
    if (m_series) {
        for (const Season &s : it->playable.seasons)
            for (const Episode &e : s.items)
                if (e.file.path == f.path) return MarkerQuery{original, libTitle, s.season, e.episode};
        return std::nullopt;
    }
    const QString lt = libTitle.trimmed().isEmpty() ? original : libTitle;
    const auto withYear = MarkersDetector::filmTitle(f.name.isEmpty() ? it->m.name : f.name, lt);
    return MarkerQuery{withYear.value_or(original), lt, std::nullopt, std::nullopt};
}

void PlayModel::play() {
    if (!canWatch()) return;
    const LibraryItem *it = item();
    const MFile cur = currentFile();
    PlayRequest r;
    r.url = *m_mirror;
    r.title = currentTitle();
    r.playlist = m_playlist;
    r.subUrls = m_subs;
    r.audioUrls = m_audio;
    r.sidePaths = m_sidePaths;
    r.magnetId = m_id;
    r.fromStart = m_fromStart;
    r.collection = m_collection;
    r.startMs = m_startMs;
    if (it) r.posterQueries = it->posterQueries;
    if (m_playlist) {
        r.queue = queueFiles();
        for (int i = 0; i < r.queue.size(); ++i) {
            const MFile &f = r.queue[i];
            if (f.path == cur.path) r.startIndex = i;
            r.watchKeys << watchKey(m_id, f);
            r.presence << presenceOf(f);
            r.markerQueries << markerQueryOf(f).value_or(MarkerQuery{});
        }
    } else {
        r.queue = {cur};
        r.watchKeys << watchKey(m_id, cur);
        r.presence << presenceOf(cur);
        r.markerQueries << markerQueryOf(cur).value_or(MarkerQuery{});
    }
    m_request = r;
    emit playRequested();
}

void PlayModel::playAt(qint64 startMs) {
    if (!canWatch()) return;
    m_fromStart = false;
    m_startMs = std::max<qint64>(0, startMs);
    play();
    m_startMs.reset();
}

} // namespace suslik::app
