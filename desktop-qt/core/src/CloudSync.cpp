#include "suslik/core/CloudSync.h"
#include "suslik/core/ApiClient.h"
#include "suslik/core/Store.h"

#include <QDateTime>
#include <QHash>
#include <QRegularExpression>
#include <QSet>

namespace suslik::core {

namespace {
Store &watchStore() { return Store::of(QStringLiteral("watch")); }
Store &uiStore() { return Store::of(QStringLiteral("player_ui")); }
Store &syncStore() { return Store::of(QStringLiteral("sync")); }

bool isFilmKey(const QString &k) {
    static const QRegularExpression re(QStringLiteral(R"(\Am\d+\z)"));
    return re.match(k).hasMatch();
}

std::optional<WatchEntry> parseWatch(const QString &key, const std::optional<QString> &v) {
    if (!v) return std::nullopt;
    const QStringList p = v->split(QLatin1Char('|'));
    if (p.size() < 3) return std::nullopt;
    bool ok = false;
    const qint64 pos = p[0].toLongLong(&ok);
    if (!ok) return std::nullopt;
    return WatchEntry{key, pos, p[1].toLongLong(), p[2].toLongLong()};
}
} // namespace

std::optional<WatchEntry> DesktopSyncStore::watch(const QString &key) { return parseWatch(key, watchStore().get(key)); }

void DesktopSyncStore::putWatch(const WatchEntry &e) { watchStore().put(e.key, QStringLiteral("%1|%2|%3").arg(e.pos).arg(e.len).arg(e.at)); }

qint64 DesktopSyncStore::trackAt(const QString &key) { return uiStore().get(QStringLiteral("trackAt:") + key).value_or(QString()).toLongLong(); }

void DesktopSyncStore::putTrack(const TrackEntry &e) {
    if (e.audio) uiStore().put(QStringLiteral("audio:") + e.key, *e.audio);
    if (e.spu) uiStore().put(QStringLiteral("spu:") + e.key, *e.spu);
    if (e.quality) uiStore().put(QStringLiteral("vquality:") + e.key, *e.quality);
    if (e.cid && e.key != QLatin1String(WatchSync::ANY)) {
        uiStore().put(QStringLiteral("cid:") + e.key, *e.cid);
        if (e.audio) uiStore().put(QStringLiteral("audio:c") + *e.cid, *e.audio);
        if (e.spu) uiStore().put(QStringLiteral("spu:c") + *e.cid, *e.spu);
        if (e.quality) uiStore().put(QStringLiteral("vquality:c") + *e.cid, *e.quality);
    }
    uiStore().put(QStringLiteral("trackAt:") + e.key, QString::number(e.at));
}

QList<WatchEntry> DesktopSyncStore::allWatch() {
    QList<WatchEntry> out;
    for (const QString &k : watchStore().keys())
        if (auto e = parseWatch(k, watchStore().get(k))) out << *e;
    return out;
}

QList<TrackEntry> DesktopSyncStore::allTracks() {
    QHash<QString, qint64> seen;
    for (const WatchEntry &w : allWatch())
        if (const auto k = WatchSync::filmKeyOf(w.key); k && w.at > seen.value(*k)) seen[*k] = w.at;
    QSet<QString> keys;
    for (const QString &k : uiStore().keys()) {
        QString rest;
        if (k.startsWith(QLatin1String("audio:"))) rest = k.mid(6);
        else if (k.startsWith(QLatin1String("spu:"))) rest = k.mid(4);
        else if (k.startsWith(QLatin1String("vquality:"))) rest = k.mid(9);
        else continue;
        if (rest == QLatin1String(WatchSync::ANY) || isFilmKey(rest)) keys.insert(rest);
    }
    QList<TrackEntry> out;
    for (const QString &k : keys) {
        qint64 at = trackAt(k);
        if (at <= 0) {
            if (k == QLatin1String(WatchSync::ANY)) {
                for (const qint64 v : seen) at = std::max(at, v);
            } else {
                at = seen.value(k);
            }
        }
        if (at <= 0) continue;
        out << TrackEntry{k, uiStore().get(QStringLiteral("audio:") + k), uiStore().get(QStringLiteral("spu:") + k), std::nullopt,
                          uiStore().get(QStringLiteral("vquality:") + k),
                          k == QLatin1String(WatchSync::ANY) ? std::nullopt : uiStore().get(QStringLiteral("cid:") + k), at};
    }
    return out;
}

std::optional<QString> DesktopSyncStore::cursor() { return syncStore().get(QStringLiteral("cursor")); }

void DesktopSyncStore::setCursor(const std::optional<QString> &c) { syncStore().put(QStringLiteral("cursor"), c); }

CloudSync::CloudSync(ApiClient &api, QObject *parent) : QObject(parent), m_api(api), m_sync(std::make_unique<WatchSync>(m_store)) {
    m_timer.setInterval(60000);
    connect(&m_timer, &QTimer::timeout, this, [this] { m_kicked = false; tick(); });
}

CloudSync::~CloudSync() = default;

void CloudSync::setUserId(const std::optional<QString> &id) {
    m_user = id;
    kick();
}

void CloudSync::noteWatch(const QString &key, qint64 pos, qint64 len, qint64 at) {
    if (WatchSync::eligible(m_user)) m_sync->noteWatch({key, pos, len, at});
}

void CloudSync::noteTrack(const QString &filmKey, const std::optional<QString> &audio, const std::optional<QString> &spu,
                          const std::optional<QString> &cid, const std::optional<QString> &quality) {
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    for (const QString &k : {filmKey, QString::fromLatin1(WatchSync::ANY)}) {
        const bool isAny = (k == QLatin1String(WatchSync::ANY));
        if (!isAny && !isFilmKey(k)) continue; // фильм не из библиотеки
        uiStore().put(QStringLiteral("trackAt:") + k, QString::number(now));
        if (!isAny && cid) uiStore().put(QStringLiteral("cid:") + k, *cid);
        if (WatchSync::eligible(m_user)) {
            m_sync->noteTrack({k, audio, spu, std::nullopt, quality, isAny ? std::nullopt : cid, now});
        }
    }
}

void CloudSync::kick() {
    m_kicked = true;
    QMetaObject::invokeMethod(this, [this] { tick(); }, Qt::QueuedConnection);
}

void CloudSync::start() {
    m_timer.start();
    kick();
}

void CloudSync::tick() {
    if (m_busy) return;
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    // Сразу — по kick(); своё во время просмотра — раз в 2 минуты; чужое — раз в 5 минут
    const bool due = m_kicked || (m_sync->hasPending() && now - m_lastPush > 2 * 60000LL) || now - m_lastPull > 5 * 60000LL;
    m_kicked = false;
    if (!due || !WatchSync::eligible(m_user)) return;
    // Отсчёт двух минут — от раунда, который правда отправил своё: раунд «за чужим» без своих изменений
    // не должен откладывать прогресс, появившийся сразу после него.
    if (m_sync->hasPending()) m_lastPush = now;
    m_busy = true;
    round(*m_user, 0, 0, now);
}

void CloudSync::round(const QString &user, int rounds, int changedSoFar, qint64 startedAt) {
    const WatchSync::Sent sent = m_sync->request(user);
    m_api.post(QString::fromLatin1(WatchSync::PATH), sent.body, [this, sent, user, rounds, changedSoFar, startedAt](const ApiResult &r) {
        if (!r.ok()) {
            emit log(QStringLiteral("[sync] failed: ") + r.error.message);
            m_lastPull = startedAt - 4 * 60000LL; // повтор через минуту, а не через пять
            m_busy = false;
            return;
        }
        const int changed = changedSoFar + m_sync->onResponse(sent, r.json);
        if (sent.more && rounds + 1 < 20 && m_user == user) {
            round(user, rounds + 1, changed, startedAt);
            return;
        }
        m_lastPull = startedAt;
        m_busy = false;
        if (changed > 0) {
            emit log(QStringLiteral("[sync] %1 entries from other devices").arg(changed));
            emit this->changed(changed);
        }
    });
}

} // namespace suslik::core
