#include "suslik/core/WatchSync.h"
#include "JsonOpt.h"

#include <QDateTime>
#include <QJsonArray>
#include <QRegularExpression>
#include <algorithm>

namespace suslik::core {

using namespace json;

std::optional<QString> WatchSync::filmKeyOf(const std::optional<QString> &watchKey) {
    if (!watchKey) return std::nullopt;
    const auto i = watchKey->indexOf(QLatin1Char('|'));
    if (i < 0) return std::nullopt;
    bool ok = false;
    const qint64 id = watchKey->left(i).toLongLong(&ok);
    return ok ? std::optional<QString>(filmKey(id)) : std::nullopt;
}

bool WatchSync::eligible(const std::optional<QString> &userId) {
    static const QRegularExpression re(QStringLiteral(R"(\Au_\d{1,25}\z)"));
    return userId && re.match(*userId).hasMatch();
}

void WatchSync::noteWatch(const WatchEntry &e) {
    QMutexLocker l(&m_lock);
    m_watch.put(e.key, e);
}

void WatchSync::noteTrack(const TrackEntry &e) {
    QMutexLocker l(&m_lock);
    const TrackEntry *old = m_tracks.get(e.key);
    if (!old) { m_tracks.put(e.key, e); return; }
    const TrackEntry merged{e.key,
                            e.audio ? e.audio : old->audio,
                            e.spu ? e.spu : old->spu,
                            e.sidecar ? e.sidecar : old->sidecar,
                            e.quality ? e.quality : old->quality,
                            e.cid ? e.cid : old->cid,
                            std::max(e.at, old->at)};
    m_tracks.put(e.key, merged);
}

bool WatchSync::hasPending() const {
    QMutexLocker l(&m_lock);
    return !m_watch.empty() || !m_tracks.empty();
}

WatchSync::Cursor WatchSync::cursor(const QString &user) {
    const auto c = m_store.cursor();
    if (!c) return {-1, 0};
    const QStringList p = c->split(QLatin1Char('|'));
    if (p.value(0) != user) return {-1, 0};
    bool ok = false;
    const qint64 since = p.value(1).toLongLong(&ok);
    if (!ok) return {-1, 0};
    bool okMark = false;
    const qint64 mark = p.value(2).toLongLong(&okMark);
    return {since, okMark ? mark : 0};
}

static QJsonValue nullable(const std::optional<QString> &s) { return s ? QJsonValue(*s) : QJsonValue(QJsonValue::Null); }

WatchSync::Sent WatchSync::request(const QString &user) {
    QMutexLocker l(&m_lock);
    const qint64 startedAt = QDateTime::currentMSecsSinceEpoch();
    const Cursor c = cursor(user);
    if (m_loaded != user) {
        m_loaded = user;
        QList<WatchEntry> watch = m_store.allWatch();
        QList<TrackEntry> tracks = m_store.allTracks();
        // Свежее — первым: больше BATCH уйдёт следующими запросами, а давнее сервер всё равно не примет
        std::stable_sort(watch.begin(), watch.end(), [](const WatchEntry &a, const WatchEntry &b) { return a.at > b.at; });
        std::stable_sort(tracks.begin(), tracks.end(), [](const TrackEntry &a, const TrackEntry &b) { return a.at > b.at; });
        if (c.since < 0) {
            // Очередь заводится заново: в хранилище уже всё, что в неё попадало
            m_watch.clear();
            m_tracks.clear();
            for (const auto &e : watch) m_watch.put(e.key, e);
            for (const auto &e : tracks) m_tracks.put(e.key, e);
        } else {
            // Перезапуск: то, что не успело уйти, лежало только в памяти; уже замеченное в этом процессе не трогаем
            for (const auto &e : watch)
                if (e.at > c.mark) m_watch.putIfAbsent(e.key, e);
            for (const auto &e : tracks)
                if (e.at > c.mark) m_tracks.putIfAbsent(e.key, e);
        }
    }
    Sent s;
    s.user = user;
    s.startedAt = startedAt;
    s.watch = m_watch.take(BATCH);
    s.tracks = m_tracks.take(BATCH);
    QJsonArray wa, ta;
    for (const auto &e : s.watch)
        wa.append(QJsonObject{{QStringLiteral("key"), e.key}, {QStringLiteral("pos"), e.pos}, {QStringLiteral("len"), e.len}, {QStringLiteral("at"), e.at}});
    for (const auto &e : s.tracks) {
        QJsonObject o{{QStringLiteral("key"), e.key}, {QStringLiteral("audio"), nullable(e.audio)}, {QStringLiteral("spu"), nullable(e.spu)},
                      {QStringLiteral("sidecar"), nullable(e.sidecar)}, {QStringLiteral("at"), e.at}};
        if (e.quality) o.insert(QStringLiteral("quality"), *e.quality);
        if (e.cid) o.insert(QStringLiteral("cid"), *e.cid);
        ta.append(o);
    }
    s.body = QJsonObject{{QStringLiteral("since"), std::max<qint64>(c.since, 0)}, {QStringLiteral("watch"), wa}, {QStringLiteral("tracks"), ta}};
    s.more = m_watch.size() > BATCH || m_tracks.size() > BATCH;
    return s;
}

static std::optional<QString> nonEmpty(const QJsonObject &o, const QString &k) {
    if (!hasValue(o, k)) return std::nullopt;
    const QString v = optString(o, k);
    return v.isEmpty() ? std::nullopt : std::optional<QString>(v);
}

int WatchSync::onResponse(const Sent &sent, const QJsonObject &res) {
    QMutexLocker l(&m_lock);
    for (const auto &e : sent.watch)
        if (const auto *p = m_watch.get(e.key); p && *p == e) m_watch.remove(e.key);
    for (const auto &e : sent.tracks)
        if (const auto *p = m_tracks.get(e.key); p && *p == e) m_tracks.remove(e.key);
    int changed = 0;
    for (const QJsonValue &v : res.value(QLatin1String("watch")).toArray()) {
        if (!v.isObject()) continue;
        const QJsonObject o = v.toObject();
        const WatchEntry e{optString(o, QStringLiteral("key")), optLong(o, QStringLiteral("pos"), -1), optLong(o, QStringLiteral("len"), 0),
                           optLong(o, QStringLiteral("at"), 0)};
        if (!e.key.contains(QLatin1Char('|')) || e.pos < 0 || e.at <= 0) continue;
        if (const auto mine = m_store.watch(e.key); mine && mine->at >= e.at) continue;
        if (const auto *p = m_watch.get(e.key); p && p->at >= e.at) continue;
        m_watch.remove(e.key); // старее пришедшего: не должно уйти обратно
        m_store.putWatch(e);
        ++changed;
    }
    for (const QJsonValue &v : res.value(QLatin1String("tracks")).toArray()) {
        if (!v.isObject()) continue;
        const QJsonObject o = v.toObject();
        const TrackEntry e{optString(o, QStringLiteral("key")), nonEmpty(o, QStringLiteral("audio")), nonEmpty(o, QStringLiteral("spu")),
                           nonEmpty(o, QStringLiteral("sidecar")), nonEmpty(o, QStringLiteral("quality")), nonEmpty(o, QStringLiteral("cid")),
                           optLong(o, QStringLiteral("at"), 0)};
        if (e.key.isEmpty() || e.at <= 0) continue;
        if (m_store.trackAt(e.key) >= e.at) continue;
        if (const auto *p = m_tracks.get(e.key); p && p->at >= e.at) continue;
        m_tracks.remove(e.key); // иначе следующий выбор склеится со старым и затрёт пришедшее
        m_store.putTrack(e);
        ++changed;
    }
    // Курсор — только когда ушло всё: оборвавшаяся выгрузка при следующем запуске начнётся заново
    const qint64 now = optLong(res, QStringLiteral("now"), 0);
    if (now > 0 && !sent.more) {
        const Cursor old = cursor(sent.user);
        const bool drained = m_watch.empty() && m_tracks.empty();
        const qint64 mark = drained ? sent.startedAt : old.mark;
        m_store.setCursor(QStringLiteral("%1|%2|%3").arg(sent.user).arg(now).arg(mark));
    }
    return changed;
}

} // namespace suslik::core
