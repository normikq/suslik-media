#include "SearchModel.h"
#include "AddModel.h"
#include "trackers/BrowserBridge.h"
#include "trackers/RuTrackerClient.h"

#include "suslik/core/ApiClient.h"
#include "suslik/core/Model.h"
#include "suslik/core/Strings.h"
#include "suslik/core/Trackers.h"

#include <QDateTime>
#include <QJsonArray>
#include <QJsonValue>
#include <QLocale>
#include <QPointer>
#include <QRegularExpression>
#include <QSet>
#include <QTimer>
#include <QUrl>

namespace suslik::app {

using namespace suslik::core;

namespace {
QString S(const char *s) { return QString::fromUtf8(s); }
qint64 nowMs() { return QDateTime::currentMSecsSinceEpoch(); }

QVariantMap badge(const QString &text, const char *bg = "#1C2230", const char *edge = "#2E3850", const char *fg = "#C7D0E0",
                  const QString &dot = {}) {
    return {{S("text"), text}, {S("bg"), S(bg)}, {S("edge"), S(edge)}, {S("fg"), dot.isEmpty() ? S(fg) : dot}, {S("dot"), dot}};
}

/// Значки раздачи — те же цвета, что у SearchView.kt (ReleaseBadges).
QVariantList badges(const ReleaseInfo &r) {
    QVariantList out;
    switch (r.resolution) {
    case Resolution::UHD_4K: out << badge(S("4K"), "#281E10", "#6E501C", "#FFB02E"); break;
    case Resolution::FHD_1080P: out << badge(S("1080p"), "#101B2E", "#243A66", "#7FAFFF"); break;
    case Resolution::HD_720P: out << badge(S("720p"), "#102022", "#1F4145", "#6ED9E6"); break;
    case Resolution::SD: out << badge(S("SD"), "#181C25", "#283144", "#A0AABF"); break;
    default: break;
    }
    switch (r.codec) {
    case VideoCodec::HEVC: out << badge(S("HEVC"), "#0F2218", "#204D36", "#7FE0B0"); break;
    case VideoCodec::AV1: out << badge(S("AV1"), "#0F2218", "#204D36", "#7FE0B0"); break;
    case VideoCodec::AVC: out << badge(S("AVC"), "#161A24", "#272F42", "#94A0B8"); break;
    default: break;
    }
    switch (r.hdr) {
    case HdrType::DV: out << badge(S("Dolby Vision"), "#241618", "#5E2730", "#FF9EA8"); break;
    case HdrType::HDR10_PLUS: out << badge(S("HDR10+"), "#241D14", "#5A4422", "#FFC069"); break;
    case HdrType::HDR10: out << badge(S("HDR"), "#241D14", "#5A4422", "#FFC069"); break;
    default: break;
    }
    switch (r.ripType) {
    case RipType::CAM: out << badge(tx(S("search.badge_cam")), "#2A1010", "#662222", "#FF8B85"); break;
    case RipType::REMUX: out << badge(S("BDRemux"), "#221430", "#4C276E", "#D49BFF"); break;
    case RipType::BLURAY: out << badge(S("Blu-ray"), "#1A152E", "#382B63", "#BCA6F7"); break;
    case RipType::BDRIP: out << badge(S("BDRip")); break;
    case RipType::WEBDL: out << badge(S("WEB-DL")); break;
    case RipType::WEBRIP: out << badge(S("WEBRip")); break;
    case RipType::HDTV: out << badge(S("HDTV")); break;
    case RipType::DVDRIP: out << badge(S("DVDRip")); break;
    default: break;
    }
    if (r.isCollection)
        out << badge(r.collectionCount ? tx(S("search.badge_collection_count"), {{"count", QString::number(*r.collectionCount)}})
                                       : tx(S("search.badge_collection")),
                     "#261D3B", "#533B85", "#D6B4FC");
    switch (r.voice) {
    case VoiceType::DUB: out << badge(tx(S("search.badge_dub")), "#14202B", "#233A50", "#86B8E6"); break;
    case VoiceType::MVO: out << badge(S("MVO")); break;
    case VoiceType::DVO: out << badge(S("DVO")); break;
    case VoiceType::AVO: out << badge(S("AVO")); break;
    case VoiceType::ORIGINAL: out << badge(tx(S("search.badge_original"))); break;
    default: break;
    }
    if (r.estimatedBitrateMbps > 0) {
        const QString mbps = QString::number(double(r.estimatedBitrateMbps), 'f', 1);
        QString dot, text = tx(S("search.bitrate_mbps"), {{"mbps", mbps}});
        switch (r.bitrateTier) {
        case BitrateTier::LIGHT:
            dot = S("#7FE0B0");
            if (r.estimatedBitrateMbps < 1) text = tx(S("search.bitrate_low"));
            break;
        case BitrateTier::MEDIUM: dot = S("#FFB02E"); break;
        case BitrateTier::HEAVY: dot = S("#FF9B8F"); break;
        }
        out << badge(r.isCollection ? tx(S("search.bitrate_per_film"), {{"bitrate", text}}) : text, "#12151D", "#232A3B", "", dot);
    }
    return out;
}

bool visibleHit(const SearchHit &h) {
    return TrackerSettings::isSourceEnabledForHit(h.source) && !ReleaseParser::isNonVideo(h.title);
}
} // namespace

QList<int> SearchModel::computePageRange(int current, int total) {
    QList<int> out;
    if (total <= 7) {
        for (int i = 1; i <= total; ++i) out << i;
        return out;
    }
    if (current <= 4) return {1, 2, 3, 4, 5, -1, total};
    if (current >= total - 3) return {1, -1, total - 4, total - 3, total - 2, total - 1, total};
    return {1, -1, current - 1, current, current + 1, -1, total};
}

QVariantList SearchModel::providers() const {
    QVariantList out;
    for (const auto &p : m_providers) {
        QString text = p.ok ? S("✓ %1 · %2").arg(p.name).arg(p.count) : S("✕ ") + p.name;
        if (!p.ok && !p.error.trimmed().isEmpty()) text += S(": ") + p.error.left(60);
        out << QVariantMap{{S("text"), text}, {S("ok"), p.ok}};
    }
    return out;
}

bool SearchModel::localActive() const { return RuTracker::isReady(); }

QVariantList SearchModel::presets() const {
    QVariantList out;
    for (const auto p : {FilterPreset::ALL, FilterPreset::MOBILE, FilterPreset::BALANCED, FilterPreset::MAX_QUALITY})
        out << QVariantMap{{S("value"), int(p)}, {S("title"), presetTitle(p)}};
    return out;
}

void SearchModel::setResolution(int r) {
    if (r < 0) m_criteria.resolution.reset();
    else m_criteria.resolution = Resolution(r);
    refilter(true);
}

QVariantList SearchModel::pageRange() const {
    QVariantList out;
    for (int p : computePageRange(safePage(), totalPages())) out << p;
    return out;
}

QVariantList SearchModel::rows() const {
    QVariantList out;
    const QString pc = tx(S("search.pc_source_suffix"));
    for (int i = fromIndex(); i < toIndex(); ++i) {
        const SearchHit &h = m_shown[i];
        const QString clean = h.release.cleanTitle.trimmed();
        QString kindText;
        if (h.kind == S("series")) kindText = tx(S("search.serial"));
        else if (h.kind == S("movie")) kindText = tx(S("search.film"));
        else if (h.kind == S("cartoon")) kindText = tx(S("search.multfilm"));
        const auto note = m_notes.constFind(h.id);
        out << QVariantMap{
            {S("id"), h.id},
            {S("title"), clean.isEmpty() ? h.title : clean},
            {S("raw"), !clean.isEmpty() && clean != h.title ? h.title : QString()},
            {S("badges"), badges(h.release)},
            {S("size"), h.size > 0 ? formatBytes(h.size) : QString()},
            {S("seeders"), h.seeders},
            {S("leechers"), h.leechers},
            {S("ru"), h.ru},
            {S("kind"), kindText},
            {S("source"), h.source + (h.localTrackerId && !h.source.contains(pc) ? pc : QString())},
            {S("date"), h.date},
            {S("note"), note != m_notes.constEnd() ? note->first : QString()},
            {S("noteOk"), note != m_notes.constEnd() ? note->second : true},
            {S("canAdd"), note == m_notes.constEnd() || !note->second},
        };
    }
    return out;
}

void SearchModel::refilter(bool resetPage) {
    QList<SearchHit> pre;
    for (const auto &h : m_hits)
        if (visibleHit(h)) pre << h;
    m_shown = m_criteria.filter(pre);
    if (resetPage) m_page = 1;
    m_page = safePage();
    emit changed();
}

void SearchModel::run(const QString &rawQuery) {
    const QString rawQ = rawQuery.trimmed();
    if (rawQ.size() < 2 || m_busy) {
        m_status = tx(S("search.vvedite_minimum_simvola"));
        m_statusOk = false;
        emit changed();
        return;
    }
    QString q = SearchQueryCleaner::clean(rawQ);
    if (q.trimmed().isEmpty()) q = rawQ;
    m_criteria.query = q;
    m_page = 1;
    const int gen = ++m_gen; // новый поиск отменяет недописанный прошлый
    m_busy = true;
    m_ruSearching = false;
    m_status = tx(S("search.oprashivaem_trekery"));
    m_statusOk = true;
    m_t0 = nowMs();
    emit changed();

    m_hasRu = RuTracker::isReady() && TrackerSettings::isEnabled(S("rutracker"));
    // RuTracker с ПК бывает в разы дольше сервера: его ответ ждём отдельно, сервер показываем сразу
    auto ruResult = std::make_shared<std::optional<std::pair<QList<SearchHit>, QString>>>();
    auto serverDone = std::make_shared<bool>(false);
    QPointer<SearchModel> self(this);
    if (m_hasRu) {
        rutracker::search(q, this, [self, gen, ruResult, serverDone](const QList<SearchHit> &hits, const QString &error) {
            if (!self || self->m_gen != gen) return;
            *ruResult = std::make_pair(hits, error);
            if (*serverDone) {
                self->publish(false, *ruResult);
                self->m_ruSearching = false;
                emit self->changed();
            }
        });
    }
    m_lib->api().get(S("/api/search?q=") + QString::fromLatin1(QUrl::toPercentEncoding(q)),
        [self, gen, ruResult, serverDone](const ApiResult &r) {
            if (!self || self->m_gen != gen) return;
            self->m_serverHits.clear();
            self->m_serverProviders.clear();
            self->m_serverFailed = !r.ok();
            self->m_serverError = r.ok() ? QString() : ApiClient::describe(r.error);
            if (r.ok()) {
                for (const auto &p : SearchProviderInfo::parse(r.json.value(S("providers")).toArray()))
                    if (TrackerSettings::isProviderEnabled(p.name)) self->m_serverProviders << p;
                for (const auto &h : SearchHit::parseHits(r.json.value(S("results")).toArray()))
                    if (!ReleaseParser::isNonVideo(h.title) && TrackerSettings::isSourceEnabledForHit(h.source)) self->m_serverHits << h;
            }
            *serverDone = true;
            const bool pending = self->m_hasRu && !*ruResult;
            self->publish(pending, *ruResult);
            self->m_busy = false; // можно искать заново, не дожидаясь RuTracker
            emit self->changed();
        });
}

void SearchModel::publish(bool ruPending, const std::optional<std::pair<QList<SearchHit>, QString>> &ru) {
    QList<SearchHit> hits = m_serverHits;
    QList<SearchProviderInfo> providers = m_serverProviders;
    const bool ruOk = ru && ru->second.isEmpty();
    if (ru) {
        if (ruOk) {
            QList<SearchHit> local;
            for (const auto &h : ru->first)
                if (!ReleaseParser::isNonVideo(h.title)) local << h;
            QSet<QString> topics;
            for (const auto &h : hits)
                if (h.topicId) topics.insert(*h.topicId);
            QList<SearchHit> front;
            for (const auto &h : local)
                if (!h.topicId || !topics.contains(*h.topicId)) front << h;
            hits = front + hits;
            providers << SearchProviderInfo{tx(S("search.rutracker_pc")), true, int(local.size()), {}};
        } else {
            providers << SearchProviderInfo{tx(S("search.rutracker_pc")), false, 0, ru->second};
        }
    }
    m_hits = hits;
    m_providers = providers;
    m_ruSearching = ruPending;
    int failed = 0;
    for (const auto &p : providers)
        if (!p.ok) ++failed;
    const QString took = QLocale().toString((nowMs() - m_t0) / 1000.0, 'f', 1);
    const QString wait = ruPending ? tx(S("search.rutracker_still_searching")) : QString();
    if (m_serverFailed && !ruOk) {
        m_status = (m_serverError.isEmpty() ? tx(S("search.error_searching")) : m_serverError) + wait;
        m_statusOk = false;
    } else {
        m_status = (hits.isEmpty() ? tx(S("search.nichego_ne_naydeno"))
                                   : tx(S("search.naydeno_p_za_p"), {{"total", QString::number(hits.size())}, {"sec", took}}) +
                                         (failed > 0 ? tx(S("search.ne_otvetili_failed"), {{"failed", QString::number(failed)}}) : QString())) +
                   wait;
        m_statusOk = !hits.isEmpty() || ruPending;
    }
    refilter(false);
}

void SearchModel::setNote(const QString &id, const QString &text, bool ok) {
    m_notes[id] = {text, ok};
    emit changed();
}

void SearchModel::add(const QString &hitId) {
    const SearchHit *found = nullptr;
    for (const auto &h : m_hits)
        if (h.id == hitId) { found = &h; break; }
    if (!found || !m_lib) return;
    const SearchHit h = *found;
    setNote(h.id, tx(S("search.dobavlyayu")), true);
    QPointer<SearchModel> self(this);
    auto onAdded = [self, id = h.id](const ApiResult &r) {
        if (!self) return;
        if (!r.ok()) { self->setNote(id, ApiClient::describe(r.error), false); return; }
        self->follow(id, r.json.value(S("id")).toInteger(), r.json.value(S("ready")).toBool());
    };
    if (h.localTrackerId) {
        const QString magnet = h.magnet.value_or(QString()).trimmed();
        const QString tid = h.topicId.value_or(QString()).trimmed();
        if (!magnet.isEmpty()) {
            m_lib->api().post(S("/api/magnets"), {{S("magnet"), magnet}}, onAdded);
        } else if (!tid.isEmpty() && *h.localTrackerId == S("rutracker")) {
            setNote(h.id, tx(S("search.downloading_torrent")), true);
            rutracker::downloadTorrent(tid, this, [self, h, onAdded](const QByteArray &bytes) {
                if (!self) return;
                if (bytes.isEmpty()) { self->setNote(h.id, tx(S("search.failed_download_torrent")), false); return; }
                static const QRegularExpression bad(S("[^a-zA-Z0-9а-яА-ЯёЁ ._-]"));
                QString name = h.release.cleanTitle.trimmed().isEmpty() ? h.title : h.release.cleanTitle;
                name = name.replace(bad, S("_")).left(80) + S(".torrent");
                self->m_lib->api().postTorrent(bytes, name, onAdded);
            });
        } else {
            setNote(h.id, tx(S("search.no_link_or_torrent")), false);
        }
    } else {
        m_lib->api().post(S("/api/search/add"), {{S("id"), h.id}}, onAdded);
    }
}

void SearchModel::ready(const QString &hitId, qint64 id, const QString &note) {
    QPointer<SearchModel> self(this);
    rejectIfNoVideo(*m_lib, id, [self, hitId, note](std::optional<QString> rejected) {
        if (!self) return;
        if (rejected) self->setNote(hitId, *rejected, false);
        else self->setNote(hitId, note, true);
        self->m_lib->refresh();
    });
}

void SearchModel::follow(const QString &hitId, qint64 id, bool readyNow, int round, qint64 started) {
    if (round == 0) {
        if (readyNow) { ready(hitId, id, tx(S("add.gotovo_uzhe_v_biblioteke"))); return; }
        setNote(hitId, tx(S("add.dobavleno_alldebrid_kachaet")), true);
        started = nowMs();
    }
    if (round >= 60) return;
    QPointer<SearchModel> self(this);
    QTimer::singleShot(nowMs() - started < 60000 ? 4000 : 15000, this, [self, hitId, id, round, started] {
        if (!self) return;
        self->m_lib->api().get(S("/api/magnets/%1").arg(id), [self, hitId, id, round, started](const ApiResult &r) {
            if (!self) return;
            if (!r.ok()) { self->follow(hitId, id, false, round + 1, started); return; }
            const QJsonObject &st = r.json;
            const qint64 total = st.value(S("size")).toInteger(), got = st.value(S("downloaded")).toInteger();
            const int code = st.value(S("statusCode")).toInt();
            if (code == 4 || st.value(S("ready")).toBool()) { self->ready(hitId, id, tx(S("add.gotovo_fayly_v_biblioteke"))); return; }
            if (code >= 5) { self->setNote(hitId, tx(S("add.oshibka_p"), {{"p", st.value(S("status")).toString()}}), false); return; }
            const QString s = st.value(S("status")).toString();
            self->setNote(hitId, (s.trimmed().isEmpty() ? tx(S("add.obrabotka")) : s) + (total > 0 ? S(" · %1%").arg(got * 100 / total) : QString()), true);
            self->follow(hitId, id, false, round + 1, started);
        });
    });
}

// ---- «Подбор по настроению» ----

void SearchModel::setLibrary(Library *l) {
    if (m_lib == l) return;
    if (m_lib) disconnect(m_lib, nullptr, this, nullptr);
    m_lib = l;
    // раздача докачалась или удалена — «Смотреть» и «Найти раздачу» пересчитываются
    if (m_lib) connect(m_lib, &Library::versionChanged, this, [this] { if (!m_aiRaw.isEmpty()) rematch(); });
}

QString SearchModel::normTitle(const QString &s) {
    static const QRegularExpression notWord(S("[^\\p{L}\\p{N}]+"));
    QString t = s.toLower();
    t.replace(QChar(0x0451), QChar(0x0435)); // ё → е
    return t.replace(notWord, S(" ")).trimmed();
}

namespace {
/// Что играть по «Смотреть»: фильм — его файл; сериал — последняя смотренная или сохранённая серия, иначе первая.
QString playPath(const Library *lib, const LibraryItem &item) {
    const Playable &p = item.playable;
    if (p.isMovie()) return p.file.path;
    if (!p.isSeries() || p.seasons.isEmpty() || p.seasons.first().items.isEmpty()) return {};
    if (lib) {
        if (const auto last = lib->lastWatched(item.m)) return last->first.path;
        if (const auto ep = lib->lastEpisode(item.m.id)) return *ep;
    }
    return p.seasons.first().items.first().file.path;
}

QString fileOf(const QString &path) { return path.mid(path.lastIndexOf(QLatin1Char('/')) + 1); }
} // namespace

std::optional<SearchModel::LibraryMatch> SearchModel::matchLibrary(
    const QList<LibraryItem> &items, const QVariantMap &film, const std::function<QString(const LibraryItem &)> &titleOf,
    const std::function<std::optional<QString>(const LibraryItem &)> &posterOf) {
    const int year = film.value(S("year")).toInt();
    const QString poster = fileOf(film.value(S("poster")).toString());
    QSet<QString> keys;
    for (const char *f : {"title", "original", "tmdbTitle"})
        if (const QString k = normTitle(film.value(S(f)).toString()); !k.isEmpty()) keys.insert(k);
    if (keys.isEmpty()) return std::nullopt;
    auto yearFits = [year](const std::optional<int> &y, bool needYear) {
        if (!year || !y) return !needYear;
        return std::abs(*y - year) <= 1;
    };
    const LibraryItem *byName = nullptr, *byText = nullptr;
    for (const LibraryItem &item : items) {
        if (!item.m.ready() || item.playable.isNone()) continue;
        // тот же постер TMDB — тот же фильм, как бы ни называлась раздача
        if (!poster.isEmpty() && posterOf) {
            if (const auto p = posterOf(item); p && fileOf(*p) == poster) return LibraryMatch{item.m.id};
        }
        const auto iy = releaseYear(item);
        if (!byName) {
            QStringList names{item.baseTitle};
            if (titleOf) names << titleOf(item);
            if (const AiTitle *ai = item.m.acceptedAi(); ai && ai->original) names << *ai->original;
            for (const QString &n : names)
                if (keys.contains(normTitle(n)) && yearFits(iy, false)) { byName = &item; break; }
        }
        // «Нечто / The Thing (1982) BDRip» — название целыми словами в имени раздачи и тот же год
        if (!byName && !byText && yearFits(iy, true)) {
            const QString text = S(" ") + normTitle(item.m.name) + S(" ");
            for (const QString &k : keys)
                if (k.size() >= 5 && text.contains(S(" ") + k + S(" "))) { byText = &item; break; }
        }
    }
    const LibraryItem *hit = byName ? byName : byText;
    if (!hit) return std::nullopt;
    return LibraryMatch{hit->m.id};
}

int SearchModel::aiInLibrary() const {
    int n = 0;
    for (const auto &v : m_aiHits)
        if (v.toMap().value(S("inLibrary")).toBool()) ++n;
    return n;
}

void SearchModel::rematch() {
    QVariantList out;
    const Library *lib = m_lib;
    auto titleOf = [lib](const LibraryItem &it) -> QString { return lib ? lib->titleOf(it) : it.baseTitle; };
    auto posterOf = [lib](const LibraryItem &it) -> std::optional<QString> { return lib ? lib->posterPathOf(it) : std::nullopt; };
    const QList<LibraryItem> empty;
    const QList<LibraryItem> &items = m_lib ? m_lib->items() : empty;
    for (const auto &v : m_aiRaw) {
        QVariantMap film = v.toMap();
        const auto m = matchLibrary(items, film, titleOf, posterOf);
        const LibraryItem *item = m && m_lib ? m_lib->item(m->id) : nullptr;
        film[S("inLibrary")] = item != nullptr;
        film[S("magnetId")] = item ? item->m.id : 0;
        film[S("path")] = item ? playPath(m_lib, *item) : QString();
        out << film;
    }
    m_aiHits = out;
    const int lib_ = aiInLibrary();
    const int n = int(m_aiHits.size());
    m_aiStatus = tx(S("discover.found"), {{"count", QString::number(n)},
                                          {"word", pluralRu(n, tx(S("discover.word_one")), tx(S("discover.word_few")), tx(S("discover.word_many")))}}) +
                 (lib_ > 0 ? tx(S("discover.found_in_library"), {{"count", QString::number(lib_)}}) : QString());
    m_aiStatusOk = true;
    emit aiChanged();
}

void SearchModel::runAi(const QString &rawQuery, bool fresh) {
    const QString q = rawQuery.simplified();
    if (!m_lib || m_aiBusy) return;
    if (q.size() < 2) {
        m_aiStatus = tx(S("discover.need_query"));
        m_aiStatusOk = false;
        emit aiChanged();
        return;
    }
    const int gen = ++m_aiGen;
    m_aiBusy = true;
    m_aiQuery = q;
    m_aiStatus = tx(S("discover.thinking"));
    m_aiStatusOk = true;
    m_aiRaw.clear();
    m_aiHits.clear();
    emit aiChanged();
    QPointer<SearchModel> self(this);
    QJsonObject body{{S("query"), q.left(300)}};
    if (fresh) body.insert(S("fresh"), true);
    m_lib->api().post(S("/api/search/ai-discovery"), body, [self, gen](const ApiResult &r) {
        if (!self || self->m_aiGen != gen) return;
        self->m_aiBusy = false;
        if (!r.ok()) {
            self->m_aiStatus = r.error.code == 404 ? tx(S("discover.server_old")) : ApiClient::describe(r.error);
            self->m_aiStatusOk = false;
            emit self->aiChanged();
            return;
        }
        QVariantList raw;
        for (const QJsonValue &v : r.json.value(S("items")).toArray()) {
            const QJsonObject o = v.toObject();
            const QString title = o.value(S("title")).toString().trimmed();
            if (title.isEmpty()) continue;
            QStringList genres;
            for (const QJsonValue &g : o.value(S("genres")).toArray())
                if (const QString t = g.toString().trimmed(); !t.isEmpty()) genres << t;
            const double rating = o.value(S("rating")).toDouble();
            const QString original = o.value(S("original")).toString().trimmed();
            raw << QVariantMap{
                {S("title"), title},
                {S("original"), original.compare(title, Qt::CaseInsensitive) == 0 ? QString() : original},
                {S("searchTitle"), original.isEmpty() ? title : original},
                {S("year"), o.value(S("year")).toInt()},
                {S("series"), o.value(S("kind")).toString() == S("series")},
                {S("reason"), o.value(S("reason")).toString().trimmed()},
                {S("genres"), genres.join(S(" · "))},
                {S("poster"), o.value(S("poster")).toString()},
                {S("rating"), rating > 0 ? QLocale().toString(rating, 'f', 1) : QString()},
                {S("tmdbTitle"), o.value(S("tmdbTitle")).toString()},
            };
        }
        self->m_aiRaw = raw;
        if (raw.isEmpty()) {
            self->m_aiHits.clear();
            self->m_aiStatus = tx(S("discover.nothing"));
            self->m_aiStatusOk = false;
            emit self->aiChanged();
            return;
        }
        self->rematch();
    });
}

void SearchModel::leave() {
    BrowserBridge::instance().stopAsync();
}

} // namespace suslik::app
