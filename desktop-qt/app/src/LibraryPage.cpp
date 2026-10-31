#include "LibraryPage.h"

#include "suslik/core/Strings.h"

#include <QDateTime>
#include <QRegularExpression>
#include <algorithm>
#include <cmath>

namespace suslik::app {

namespace {
QString S(const char *s) { return QString::fromUtf8(s); }
constexpr int PAGE_ROWS = 3; // страница — столько рядов постеров

QString versionsWord(int n) {
    return tx(S(n % 10 >= 2 && n % 10 <= 4 && !(n % 100 >= 12 && n % 100 <= 14) ? "library.versii_2" : "library.versiy_5"), {{"n", QString::number(n)}});
}

QString plural(int n, const QString &one, const QString &few, const QString &many) {
    const int t = n % 100, u = n % 10;
    return QString::number(n) + QLatin1Char(' ') + (t >= 11 && t <= 14 ? many : u == 1 ? one : (u >= 2 && u <= 4) ? few : many);
}

/// Что открыть для этого файла: фильм или серия с остальными сериями сезона. Есть ли такой файл.
bool hasTarget(const LibraryItem &item, const QString &path) {
    if (item.playable.isMovie()) return item.playable.file.path == path;
    for (const Season &s : item.playable.seasons)
        for (const Episode &e : s.items)
            if (e.file.path == path) return true;
    return false;
}

std::optional<MFile> nextEpisode(const Playable &p, const MFile &file) {
    QList<Episode> all;
    for (const Season &s : p.seasons) all += s.items;
    for (int i = 0; i + 1 < all.size(); ++i)
        if (all[i].file.path == file.path) return all[i + 1].file;
    return std::nullopt;
}

QString filmSub(const LibraryItem &item, const MFile &file) {
    const auto films = collectionFilms(item);
    int idx = -1;
    for (int i = 0; i < films.size(); ++i)
        if (films[i].file.path == file.path) idx = i;
    const QString title = collectionFileTitle(item.m, file, item.baseTitle);
    return films.size() > 1 && idx >= 0 ? tx(S("library.film_p"), {{"p", QString::number(idx + 1)}}) + S(" · ") + title : title;
}

/// Подпись серии или фильма сборника (episodeLabel без названия).
QString targetLabel(const LibraryItem &item, const MFile &file) {
    if (item.playable.isSeries())
        for (const Season &s : item.playable.seasons)
            for (const Episode &e : s.items)
                if (e.file.path == file.path) return episodeLabel(item.baseTitle, s, e);
    return item.baseTitle;
}

QString seasonChip(const Season &s) {
    return (s.season == 0 ? tx(S("library.specvypuski")) : tx(S("library.sezon_season"), {{"season", QString::number(s.season)}})) + S(" • ") +
           plural(int(s.items.size()), tx(S("common.plural_seriya_1")), tx(S("common.plural_seriya_2")), tx(S("common.plural_seriya_5")));
}
} // namespace

QString itemYear(const LibraryItem &item) {
    static const QRegularExpression re(S("\\b(19\\d{2}|20\\d{2})\\b"));
    auto m = re.match(item.m.name);
    if (m.hasMatch()) return m.captured(0);
    for (const MFile &f : item.m.files) {
        m = re.match(f.name);
        if (m.hasMatch()) return m.captured(0);
    }
    return S("2024");
}

std::pair<QString, QString> itemSpecs(const LibraryItem &item, const std::optional<QString> &media) {
    const QString name = item.m.name.toLower();
    const QString str = media.value_or(QString()).toLower();
    auto has = [&](const char *a) { return str.contains(S(a)); };
    auto hasN = [&](const char *a) { return name.contains(S(a)); };
    QString quality;
    if (has("3840") || has("2160") || hasN("2160p") || hasN("4k")) quality = S("4K");
    else if (has("1920") || has("1080") || hasN("1080p")) quality = S("1080p");
    else if (has("1280") || has("720") || hasN("720p")) quality = S("720p");
    else quality = S("1080p");
    QString codec;
    if (has("hevc") || has("h.265") || has("h265") || hasN("x265") || hasN("hevc") || hasN("h265") || hasN("h.265")) codec = S("H265");
    else if (has("avc") || has("h.264") || has("h264") || hasN("x264") || hasN("h264") || hasN("h.264")) codec = S("H264");
    else if (has("av1") || hasN("av1")) codec = S("AV1");
    else codec = quality == QLatin1String("4K") ? S("H265") : S("H264");
    static const QRegularExpression fpsRe(S("(\\d+(\\.\\d+)?)\\s*fps"));
    auto fm = fpsRe.match(str);
    if (!fm.hasMatch()) fm = fpsRe.match(name);
    const QString fps = fm.hasMatch() ? fm.captured(0) : S("23.98 fps");
    static const QRegularExpression brRe(S("(\\d+(\\.\\d+)?)\\s*мбит/с"));
    const auto bm = brRe.match(str);
    QString bitrate;
    if (bm.hasMatch()) bitrate = bm.captured(0);
    else if (item.m.size > 0) {
        const double approx = std::clamp(item.m.size * 8.0 / (7200.0 * 1000000.0), 8.0, 75.0);
        bitrate = QString::asprintf(tx(S("library.f_mbit_s")).toUtf8().constData(), approx);
    } else bitrate = tx(S("library.mbit_s_2"));
    return {quality + S(" • ") + codec + S(" • ") + fps, bitrate + S(" • ") + formatBytes(item.m.size)};
}

QStringList collapseTags(const QStringList &all) {
    QStringList out;
    bool dolby = false, hdr = false, dts = false;
    for (const QString &t : all) {
        dolby = dolby || t.startsWith(QLatin1String("Dolby"));
        hdr = hdr || t == QLatin1String("HDR");
        dts = dts || t.startsWith(QLatin1String("DTS"));
    }
    if (dolby) out << S("Dolby Vision");
    else if (hdr) out << S("HDR");
    if (dts) out << S("DTS");
    return out;
}

LibraryPage::LibraryPage(QObject *parent) : QObject(parent) {}

void LibraryPage::setLibrary(Library *l) {
    if (m_lib == l) return;
    if (m_lib) disconnect(m_lib, nullptr, this, nullptr);
    m_lib = l;
    if (m_lib) {
        connect(m_lib, &Library::versionChanged, this, &LibraryPage::changed);
        connect(m_lib, &Library::heroReady, this, &LibraryPage::changed);
    }
    emit libraryChanged();
    changed();
}

void LibraryPage::setPage(int v) {
    v = std::clamp(v, 0, std::max(0, m_pageCount - 1));
    if (m_page == v) return;
    m_page = v;
    rebuild();
}

void LibraryPage::changed() {
    emit settingsChanged();
    if (m_queued) return;
    m_queued = true;
    QMetaObject::invokeMethod(this, [this] { m_queued = false; rebuild(); }, Qt::QueuedConnection);
}

std::optional<MFile> LibraryPage::currentFile(const LibraryItem &item) const {
    const Playable &p = item.playable;
    if (p.isMovie()) return p.file;
    if (!p.isSeries() || p.seasons.isEmpty()) return std::nullopt;
    int si = 0, ei = 0;
    if (const auto it = m_season.constFind(item.m.id); it != m_season.cend()) {
        si = it->first;
        ei = it->second;
    } else {
        // последняя смотренная серия (или сохранённая): плитка показывает её
        const auto last = m_lib->lastWatched(item.m);
        const QString saved = last ? last->first.path : m_lib->lastEpisode(item.m.id).value_or(QString());
        for (int s = 0; s < p.seasons.size(); ++s)
            for (int e = 0; e < p.seasons[s].items.size(); ++e)
                if (p.seasons[s].items[e].file.path == saved) { si = s; ei = e; }
    }
    const Season &s = p.seasons.value(si, p.seasons.first());
    return s.items.value(ei, s.items.first()).file;
}

QVariantMap LibraryPage::tileOf(const LibraryItem &item, const QList<LibraryItem> &versions) const {
    const Magnet &m = item.m;
    QVariantMap t;
    t[S("id")] = m.id;
    t[S("title")] = m_lib->titleOf(item);
    t[S("subtitle")] = itemYear(item) + S(" • ") + m_lib->typeLabel(item);
    t[S("poster")] = const_cast<Library *>(m_lib)->posterPath(item.posterQueries);
    t[S("ready")] = m.ready();
    t[S("downloading")] = m.downloading();
    t[S("progress")] = m.progress();
    t[S("status")] = m.ready() ? tx(S("library.gotovo")) : m.downloading() ? S("%1% • %2").arg(int(m.progress() * 100)).arg(statusRu(m)) : statusRu(m);
    t[S("listStatus")] = m.ready() ? tx(S("library.gotovo")) : statusRu(m);
    t[S("canPlay")] = m.ready() && !item.playable.isNone();
    const QList<LibraryItem> grp = versions.isEmpty() ? QList<LibraryItem>{item} : versions;
    bool isMine = false, isFav = false;
    QStringList tags;
    for (const auto &v : grp) {
        isMine = isMine || v.m.mine;
        isFav = isFav || v.m.favorite;
        tags += m_lib->tagsOf(v);
    }
    t[S("mine")] = isMine;
    t[S("favorite")] = isFav;
    t[S("ownMine")] = m.mine;
    t[S("ownFavorite")] = m.favorite;
    t[S("versions")] = versions.size() > 1 ? versionsWord(int(versions.size())) : QString();
    t[S("tags")] = collapseTags(tags);
    const auto cur = currentFile(item);
    std::optional<QString> media;
    if (cur) {
        media = m_lib->cachedMediaLine(m.id, *cur);
        if (!media && m.ready()) const_cast<Library *>(m_lib)->requestMediaLine(m.id, *cur);
    }
    const auto specs = itemSpecs(item, media);
    t[S("specs1")] = specs.first;
    t[S("specs2")] = specs.second;
    // полоска внизу: прогресс просмотра или загрузки
    double bar = 0;
    if (cur && m.ready()) {
        if (const auto w = m_lib->watchInfo(watchKey(m.id, *cur)); w && w->fraction() > 0.01f) bar = w->watched() ? 1.0 : w->fraction();
    } else if (m.downloading() && m.size > 0) bar = m.progress();
    t[S("bar")] = bar;
    // меню правой кнопки
    QVariantList seasons;
    if (item.playable.isSeries())
        for (const Season &s : item.playable.seasons)
            seasons << tx(S("library.sezon_season_size_ser"), {{"season", QString::number(s.season)}, {"size", QString::number(s.items.size())}});
    t[S("seasons")] = seasons;
    t[S("canRemove")] = m.canRemove && (!m_mine || m.downloading()); // «Удалить» стирает у всех: в «Моих фильмах» только отмена
    t[S("removeLabel")] = m.downloading() ? tx(S("library.otmenit")) : tx(S("library.udalit"));
    QList<qint64> aiAsk, aiKnown;
    int waiting = 0;
    for (const auto &v : grp) {
        const Magnet &g = v.m;
        if (g.canAiTitle && (!g.aiTitle || g.aiTitle->stale)) aiAsk << g.id;
        if (g.aiTitle && (g.canAiTitle || g.aiTitle->status == QLatin1String("suggested") || g.aiTitle->accepted())) aiKnown << g.id;
        if (g.canAiTitle && g.aiTitle && g.aiTitle->status == QLatin1String("suggested")) ++waiting;
    }
    if (!aiAsk.isEmpty()) {
        const qint64 left = m_lib->aiReadyAt() - QDateTime::currentMSecsSinceEpoch();
        t[S("aiAsk")] = left > 0 ? tx(S("library.ai_cherez"), {{"min", QString::number((left + 59999) / 60000)}})
                        : aiAsk.size() > 1 ? tx(S("library.ai_opredelit_n"), {{"n", QString::number(aiAsk.size())}})
                                           : tx(S("library.ai_opredelit"));
        t[S("aiAskEnabled")] = left <= 0 && !m_lib->aiBusy();
    }
    if (!aiKnown.isEmpty())
        t[S("aiKnown")] = waiting > 0 ? tx(S("library.ai_predlozheniya_n"), {{"n", QString::number(waiting)}}) : tx(S("library.ai_nazvanie"));
    t[S("mineLabel")] = isMine ? tx(S("library.iz_moih_filmov")) : tx(S("library.v_moi_filmy"));
    t[S("favLabel")] = isFav ? tx(S("library.iz_izbrannogo")) : tx(S("library.v_izbrannoe"));
    return t;
}

QList<LibraryItem> LibraryPage::group(qint64 id) const {
    const auto it = m_versionsOf.constFind(id);
    if (it != m_versionsOf.cend() && !it->isEmpty()) return *it;
    const LibraryItem *i = m_lib ? m_lib->item(id) : nullptr;
    return i ? QList<LibraryItem>{*i} : QList<LibraryItem>{};
}

void LibraryPage::rebuild() {
    if (!m_lib) return;
    const QList<LibraryItem> &all = m_lib->items();
    // поиск, «только готовые», категория
    const QString q = m_query.trimmed().toLower();
    QList<LibraryItem> list;
    for (const auto &i : all) {
        if (m_mine && !i.m.mine) continue;
        if (!q.isEmpty() && !i.searchText.contains(q) && !m_lib->titleOf(i).toLower().contains(q)) continue; // «дюна» находит «Dune.2021»
        if (m_onlyReady && !i.m.ready()) continue;
        if (m_category != 0 && int(m_lib->category(i)) != m_category) continue;
        list << i;
    }
    m_emptyMine = m_mine && list.isEmpty();
    // избранные первыми, дальше по выбранному порядку
    QHash<qint64, QString> titles;
    if (m_sortBy == 0)
        for (const auto &i : list) titles.insert(i.m.id, m_lib->titleOf(i).toLower());
    std::stable_sort(list.begin(), list.end(), [&](const LibraryItem &a, const LibraryItem &b) {
        if (a.m.favorite != b.m.favorite) return a.m.favorite;
        switch (m_sortBy) {
        case 0: return titles.value(a.m.id) < titles.value(b.m.id);
        case 1: return a.m.id > b.m.id;
        case 2: return a.m.size > b.m.size;
        default: return false;
        }
    });
    // Один фильм, добавленный несколько раз, — одна плитка.
    auto [shown, versionsOf] = groupVersions(list, [this](const LibraryItem &i) { return m_lib->posterPathOf(i); });
    m_versionsOf = versionsOf;
    m_total = int(shown.size());
    const int pageSize = m_viewMode == 0 ? m_columns * PAGE_ROWS : PAGE_ROWS * 4;
    m_pageCount = std::max(1, (m_total + pageSize - 1) / pageSize);
    m_page = std::clamp(m_page, 0, m_pageCount - 1);
    m_tiles.clear();
    for (int i = m_page * pageSize; i < std::min<int>(m_total, (m_page + 1) * pageSize); ++i)
        m_tiles << tileOf(shown[i], m_versionsOf.value(shown[i].m.id));
    m_pageNumbers.clear();
    QList<int> nums;
    for (int n = 0; n < m_pageCount; ++n)
        if (n == 0 || n == m_pageCount - 1 || std::abs(n - m_page) <= 2) nums << n;
    for (int i = 0; i < nums.size(); ++i) {
        if (i > 0 && nums[i] - nums[i - 1] > 1) m_pageNumbers << -1;
        m_pageNumbers << nums[i];
    }

    // «Продолжить просмотр»: только на «Главной», без поиска и категории
    m_continue.clear();
    if (!m_mine && q.isEmpty() && m_category == 0) {
        struct E { QVariantMap map; qint64 at; };
        std::vector<E> entries;
        const qint64 now = QDateTime::currentMSecsSinceEpoch();
        for (const auto &item : all) {
            if (!item.m.ready()) continue;
            const auto lw = m_lib->lastWatched(item.m);
            if (!lw) continue;
            const auto &[file, w] = *lw;
            if (now - w.at > 60LL * 24 * 3600 * 1000) continue;
            if (m_lib->continueHiddenAt(item.m.id) >= w.at) continue;
            const bool coll = isCollection(item.playable, &item.m);
            MFile target = file;
            QString label;
            double fraction = 0;
            qint64 pos = 0, len = 0;
            if (!w.watched()) {
                if (!hasTarget(item, file.path)) continue;
                label = coll ? filmSub(item, file) : [&] {
                    QString l = targetLabel(item, file);
                    if (l.startsWith(item.baseTitle)) l = l.mid(item.baseTitle.size());
                    if (l.startsWith(S(" · "))) l = l.mid(3);
                    return l.trimmed().isEmpty() ? tx(S("library.film")) : l;
                }();
                fraction = w.fraction();
                pos = w.pos;
                len = w.len;
            } else {
                if (!item.playable.isSeries()) continue;
                const auto next = nextEpisode(item.playable, file);
                if (!next) continue;
                if (const auto nw = m_lib->watchInfo(watchKey(item.m.id, *next)); nw && nw->watched()) continue;
                target = *next;
                QString l = targetLabel(item, target);
                if (l.startsWith(item.baseTitle)) l = l.mid(item.baseTitle.size());
                if (l.startsWith(S(" · "))) l = l.mid(3);
                label = tx(S("library.dalshe")) + (coll ? filmSub(item, target) : l);
            }
            QVariantMap e;
            e[S("id")] = item.m.id;
            e[S("path")] = target.path;
            e[S("label")] = label;
            e[S("fraction")] = fraction;
            const QStringList queries = continueItemPosterQueries(item, target);
            e[S("poster")] = m_lib->posterPath(queries);
            const auto season = watchedSeason(item, target.path);
            const int heroSeason = season ? season->season : 0;
            const auto hero = m_lib->cachedHero(queries, heroSeason);
            if (!hero) m_lib->requestHero(queries, heroSeason);
            e[S("backdrop")] = hero ? hero->backdrop : QString();
            e[S("logo")] = hero ? hero->logo : QString();
            e[S("overview")] = hero ? hero->overview : QString();
            e[S("rating")] = hero && hero->rating > 0 ? QString::asprintf("%.1f", hero->rating) : QString();
            e[S("title")] = coll ? (hero && !hero->title.isEmpty() ? hero->title : continueItemTitle(item, target)) : m_lib->titleOf(item);
            QStringList chips;
            if (hero && !hero->year.isEmpty()) chips << hero->year;
            chips << m_lib->typeLabel(item);
            const auto films = collectionFilms(item);
            if (!films.isEmpty()) chips << filmCountRu(int(films.size()));
            else if (season) chips << seasonChip(*season);
            else if (item.playable.isSeries()) {
                int eps = 0;
                for (const Season &s : item.playable.seasons) eps += s.items.size();
                chips << plural(int(item.playable.seasons.size()), tx(S("common.plural_sezon_1")), tx(S("common.plural_sezon_2")), tx(S("common.plural_sezon_5"))) +
                             S(" • ") + plural(eps, tx(S("common.plural_seriya_1")), tx(S("common.plural_seriya_2")), tx(S("common.plural_seriya_5")));
            }
            e[S("chips")] = chips;
            // «Сезон 1, серия 4» для серии; для фильма — подпись
            QString sub = label;
            if (item.playable.isSeries())
                for (const Season &s : item.playable.seasons)
                    for (const Episode &ep : s.items)
                        if (ep.file.path == target.path && !ep.title) {
                            const QString text = s.season == 0 ? tx(S("library.specvypusk_n"), {{"episode", QString::number(ep.episode)}})
                                                               : tx(S("library.sezon_seriya"), {{"season", QString::number(s.season)}, {"episode", QString::number(ep.episode)}});
                            sub = label.startsWith(tx(S("library.dalshe"))) ? tx(S("library.dalshe")) + text : text;
                        }
            e[S("sub")] = sub;
            QString left;
            if (len > 0 && pos > 0) {
                const int min = std::max(1, int((len - pos) / 60000));
                left = min >= 60 ? tx(S("library.ostalos_ch_min"), {{"hours", QString::number(min / 60)}, {"minutes", QString::number(min % 60)}})
                                 : tx(S("library.ostalos_min"), {{"minutes", QString::number(min)}});
            }
            e[S("left")] = left;
            entries.push_back({e, w.at});
        }
        std::stable_sort(entries.begin(), entries.end(), [](const E &a, const E &b) { return a.at > b.at; });
        for (size_t i = 0; i < std::min<size_t>(4, entries.size()); ++i) m_continue << entries[i].map;
    }
    emit resultChanged();
}

void LibraryPage::chooseSeason(qint64 id, int seasonIndex) {
    m_season.insert(id, {seasonIndex, 0});
    rebuild();
}

void LibraryPage::open(qint64 id) {
    const auto vs = m_versionsOf.value(id);
    if (vs.size() > 1) {
        QVariantList ids;
        for (const auto &v : vs) ids << v.m.id;
        emit versionsRequested(vs.first().baseTitle, ids);
        return;
    }
    const LibraryItem *item = m_lib->item(id);
    if (!item) return;
    const auto cur = currentFile(*item);
    if (!cur) return;
    if (item->playable.isSeries()) m_lib->saveEpisode(id, cur->path);
    emit playRequested(id, cur->path);
}

void LibraryPage::openContinue(qint64 id, const QString &path) {
    m_lib->saveEpisode(id, path);
    emit playRequested(id, path);
}

void LibraryPage::setMineGroup(qint64 id, bool on) {
    for (const auto &v : group(id)) m_lib->setMine(v.m.id, on);
}

void LibraryPage::setFavoriteGroup(qint64 id, bool on) {
    for (const auto &v : group(id)) m_lib->setFavorite(v.m.id, on);
}

void LibraryPage::askAi(qint64 id) {
    QList<qint64> ask;
    for (const auto &v : group(id))
        if (v.m.canAiTitle && (!v.m.aiTitle || v.m.aiTitle->stale)) ask << v.m.id;
    m_lib->askAi(ask);
}

void LibraryPage::showAi(qint64 id) {
    QVariantList known;
    for (const auto &v : group(id))
        if (v.m.aiTitle && (v.m.canAiTitle || v.m.aiTitle->status == QLatin1String("suggested") || v.m.aiTitle->accepted())) known << v.m.id;
    emit m_lib->aiPanelRequested(known, QString());
}

} // namespace suslik::app
