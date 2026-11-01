#include "suslik/core/BurstProxy.h"
#include "suslik/core/HttpUpstream.h"
#include "suslik/core/MirrorLink.h"
#include "suslik/core/UrlCoding.h"
#include "QtWait.h"

#include <QDateTime>
#include <QDir>
#include <QDirIterator>
#include <QFile>
#include <QFileInfo>
#include <QHash>
#include <QSet>
#include <QStorageInfo>
#include <QTcpServer>
#include <QTcpSocket>
#include <QUuid>

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cstring>
#include <future>
#include <map>
#include <thread>

// Перенос shared/.../core/BurstProxy.kt. Поведение и числа те же; комментарии о причинах решений — там и в docs/HISTORY.md.
namespace suslik::core {

// ---- Cancel ----
void Cancel::attach(std::function<void()> action) {
    bool now;
    {
        std::lock_guard g(m_mutex);
        m_actions.push_back(action);
        now = m_cancelled;
    }
    if (now && action) try { action(); } catch (...) {}
}

void Cancel::cancel() {
    std::vector<std::function<void()>> run;
    {
        std::lock_guard g(m_mutex);
        m_cancelled = true;
        run = m_actions;
    }
    for (auto &a : run) if (a) try { a(); } catch (...) {}
}

bool Cancel::cancelled() const {
    std::lock_guard g(m_mutex);
    return m_cancelled;
}

namespace {

constexpr qint64 MB = 1LL << 20;
constexpr int MAX_WINDOWS = 3;              // на поток (фильм + дальняя перемотка / чтение индекса; окно фильма не вытесняется)
constexpr qint64 PROBE_AHEAD = 4 * MB;      // пока окно не доказало, что это воспроизведение
constexpr qint64 PROBE_LIMIT = 6 * MB;      // прочитано подряд столько — воспроизведение, рывки
constexpr qint64 PROBE_IDLE_MS = 500;       // проба, которую плеер не читает столько, стоит на PROBE_AHEAD
constexpr qint64 CHUNK = 256 * 1024;
constexpr qint64 NEAR = 8 * MB;             // запрос так близко к концу окна присоединяется к нему
constexpr qint64 NEAR_MIN = 1 * MB;         // ...но не дальше, чем сеть принесёт за NEAR_SEC
constexpr double NEAR_SEC = 1.5;
constexpr qint64 BRIDGE = 16 * MB;          // запрос так близко перед данными окна качает только разрыв
constexpr qint64 TAIL_CACHE = 512 * 1024;   // конец файла (индекс MKV) в памяти
constexpr double BURST_MARGIN = 1.25;       // ограниченный запрос просит на 25 % больше недостающего
constexpr double SLOW_NET = 1.83;           // сеть ÷ битрейт фильма ниже — рывок не ограничивается
constexpr int MAX_BRIDGES = 4;

qint64 nowMs() { return QDateTime::currentMSecsSinceEpoch(); }

QString fileOf(const QString &url) {
    const QString path = url.section(QLatin1Char('?'), 0, 0);
    return path.mid(path.lastIndexOf(QLatin1Char('/')) + 1);
}

QString fmt(const char *format, double a) { return QString::asprintf(format, a); }

/// Сбой, после которого повторять бессмысленно (4xx, нет диапазонов).
struct FatalUpstream : IoError {
    explicit FatalUpstream(const QString &m) : IoError(m) {}
};
/// Подписанный адрес обновлён (refreshUrl): повторить сразу, это не сбой.
struct Renewed : IoError {
    Renewed() : IoError(QStringLiteral("signature renewed")) {}
};
/// Плеер закрыл соединение.
struct PeerClosed {};

template <class F>
struct Finally {
    F f;
    ~Finally() { f(); }
};
template <class F>
Finally(F) -> Finally<F>;

// ---- локальная сторона: сокет в своём потоке, ожидание — через события (QtWait.h) ----
bool connected(const QTcpSocket &s) { return s.state() == QAbstractSocket::ConnectedState; }

void writeAll(QTcpSocket &s, const char *data, qint64 len) {
    qint64 off = 0;
    while (off < len) {
        if (!connected(s)) throw PeerClosed{};
        const qint64 n = s.write(data + off, len - off);
        if (n < 0) throw PeerClosed{};
        off += n;
        // как блокирующая запись Java: следующая порция — когда эта ушла в систему (медленный читатель держит загрузку)
        while (s.bytesToWrite() > 0) {
            if (!connected(s)) throw PeerClosed{};
            detail::waitFor(&s, 1000, &QIODevice::bytesWritten, &QAbstractSocket::disconnected, &QAbstractSocket::errorOccurred);
        }
    }
}
void writeAll(QTcpSocket &s, const QByteArray &b) { writeAll(s, b.constData(), b.size()); }

void status(QTcpSocket &s, const char *st) {
    writeAll(s, QByteArray("HTTP/1.1 ") + st + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
}

std::optional<QString> readLine(QTcpSocket &s) {
    const qint64 until = nowMs() + 60000;
    while (!s.canReadLine()) {
        if (s.bytesAvailable() > 8192 || !connected(s) || nowMs() > until) return std::nullopt;
        detail::waitFor(&s, 1000, &QIODevice::readyRead, &QAbstractSocket::disconnected, &QAbstractSocket::errorOccurred);
    }
    QByteArray line = s.readLine(8194);
    while (line.endsWith('\n') || line.endsWith('\r')) line.chop(1);
    return QString::fromLatin1(line);
}

/// Плеер закрыл соединение. После запроса он ничего не шлёт, так что сокет закрывается, только когда он ушёл.
/// Запись не скажет: ждущий читатель ничего не пишет.
bool peerGone(QTcpSocket &s) {
    if (!connected(s)) return true;
    detail::waitFor(&s, 1, &QAbstractSocket::disconnected);
    if (s.bytesAvailable() > 0) s.readAll();
    return !connected(s);
}

/// Принимает соединения в своём потоке: дескриптор сразу уходит потоку-обработчику (сокет создаётся там).
class LocalServer : public QTcpServer {
public:
    std::function<void(qintptr)> onConnection;
protected:
    void incomingConnection(qintptr fd) override { onConnection(fd); }
};

} // namespace

namespace burst {
struct Window;

// ---- один поток (один исходный адрес) ----
struct Source : std::enable_shared_from_this<Source> {
    BurstProxy::Impl *p;
    const QString token;
    const QString key; // тот же при новых подписях

    Source(BurstProxy::Impl *proxy, QString t, const QString &u)
        : p(proxy), token(std::move(t)), key(MirrorLink::fileKey(u)), m_url(u) {}

    QString url() const { std::lock_guard g(m_urlMutex); return m_url; }
    void setUrl(const QString &u) { std::lock_guard g(m_urlMutex); m_url = u; }
    /// Заменить адрес, только если он всё ещё old.
    void replaceUrl(const QString &old, const QString &fresh) { std::lock_guard g(m_urlMutex); if (m_url == old) m_url = fresh; }
    QString type() const { std::lock_guard g(m_urlMutex); return m_type; }
    void setType(const QString &t) { std::lock_guard g(m_urlMutex); m_type = t; }

    bool side() const;
    qint64 ringCap() const;

    std::atomic<qint64> total{-1};
    std::atomic<bool> broken{false};
    std::atomic<double> rate{0};    // скорость чтения плеера (байт/с)
    std::atomic<double> avgRate{0}; // средний битрейт файла (размер ÷ длина)
    std::atomic<double> netRate{0}; // скорость сети на последних рывках
    std::atomic<qint64> retiredAt{0}; // плеер ушёл; хранится на диске до другого фильма (0 — в работе)

    double sizing() const {
        const double a = avgRate, r = rate;
        return a > 0 ? std::clamp(r, a * 0.5, a * 4) : r;
    }
    void addNetSample(double v) { const double n = netRate; netRate = n <= 0 ? v : n * 0.5 + v * 0.5; }

    // Окна: копия списка под маленьким замком — загрузчики читают его без замка источника.
    std::vector<std::shared_ptr<Window>> windows() const { std::lock_guard g(m_listMutex); return m_windows; }
    void addWindow(const std::shared_ptr<Window> &w) { std::lock_guard g(m_listMutex); m_windows.push_back(w); }
    void removeWindow(const std::shared_ptr<Window> &w) {
        std::lock_guard g(m_listMutex);
        m_windows.erase(std::remove(m_windows.begin(), m_windows.end(), w), m_windows.end());
    }
    qint64 keptMb() const;

    void storeTail(qint64 abs, const char *buf, qint64 n);
    bool serveTail(QTcpSocket &out, const QString &method, bool range, qint64 from, qint64 to);

    std::shared_ptr<Window> windowFor(qint64 pos);
    std::shared_ptr<Window> windowAt(qint64 pos) const;
    qint64 near() const {
        const double net = netRate;
        return net <= 0 ? NEAR : std::clamp(static_cast<qint64>(net * NEAR_SEC), NEAR_MIN, NEAR);
    }
    void close();
    /// Окно, которое плеер читал последним: качать может только оно.
    std::shared_ptr<Window> current() const;

    std::mutex m; // synchronized(this) Kotlin-версии

private:
    mutable std::mutex m_urlMutex;
    QString m_url;
    QString m_type = QStringLiteral("application/octet-stream");
    mutable std::mutex m_listMutex;
    std::vector<std::shared_ptr<Window>> m_windows;
    std::mutex m_cacheLock;
    QByteArray m_tail = QByteArray(TAIL_CACHE, '\0');
    qint64 m_tailRunStart = -1, m_tailRunEnd = -1;
};

// ---- непрерывный скачанный отрезок в файле-кольце ----
/// limit ≥ 0: мост — качает только start…limit (там начинаются данные другого окна) и останавливается.
struct Window : std::enable_shared_from_this<Window> {
    BurstProxy::Impl *p;
    std::shared_ptr<Source> src;
    const qint64 start;
    const qint64 limit;
    const qint64 CAP; // на всю жизнь окна

    Window(BurstProxy::Impl *proxy, std::shared_ptr<Source> s, qint64 st, qint64 lim = -1);

    bool usable() const { return !hasError && !closed; }

    std::atomic<qint64> written{0}; // байт от start (подряд)
    std::atomic<qint64> readPos;    // где читает плеер
    std::atomic<qint64> sequential{0};
    std::atomic<qint64> consumedTotal{0};
    std::atomic<qint64> lastReadAt{nowMs()};
    std::atomic<bool> eof{false};
    std::atomic<bool> hasError{false};
    QString error; // под lock
    std::atomic<bool> closed{false};
    std::atomic<int> readers{0};
    std::atomic<qint64> heldUntil{0};
    qint64 gateAt = 0; // под lock

    std::mutex lock;
    std::condition_variable cv;

    qint64 end() const { return start + written; }
    qint64 diskBytes() const { return std::min<qint64>(written, CAP); }
    qint64 validFrom() const { return start + std::max<qint64>(0, written - CAP); }
    bool burst() const { return sequential >= PROBE_LIMIT; }
    bool held() const { return nowMs() < heldUntil; }

    void checkStall(qint64 now);
    void kick();
    void wake() { std::lock_guard g(lock); kicked = true; cv.notify_all(); }
    QString describe(qint64 now) const;
    bool abandoned() const;
    void close();
    void download();
    qint64 read(qint64 abs, char *buf, qint64 max, const std::function<bool()> &alive);
    void consumed(qint64 abs, qint64 n);

private:
    QFile m_file;
    std::mutex m_respMutex;
    std::shared_ptr<UpstreamResponse> m_response;
    std::shared_ptr<Cancel> m_cancel;
    QString m_stalledWhy;
    std::atomic<bool> m_downloading{false};
    std::atomic<qint64> m_fetchAt{0}, m_lastByteAt{0};
    std::atomic<bool> m_gotHeaders{false};
    std::atomic<bool> kicked{false};
    std::atomic<int> m_failures{0};
    int m_offlineCycles = 0;

    bool stopped() const;
    double reserveSeconds() const;
    qint64 high() const;
    qint64 low() const;
    qint64 rampAfter() const { return std::min(std::max<qint64>(32 * MB, static_cast<qint64>(src->sizing() * 45)), CAP * 3 / 4); }
    qint64 rampAhead() const { return std::max<qint64>(36 * MB, static_cast<qint64>(src->sizing() * 20)); }
    bool ramping() const;
    void pause(qint64 ms);
    void fetch();
    void ringWrite(qint64 abs, const char *buf, qint64 n);
    void fail(const QString &why) { std::lock_guard g(lock); error = why; hasError = true; cv.notify_all(); }
    void setEof() { std::lock_guard g(lock); eof = true; cv.notify_all(); }
    QString takeStalled() { std::lock_guard g(m_respMutex); QString s = m_stalledWhy; m_stalledWhy.clear(); return s; }
};

} // namespace burst

using burst::Source;
using burst::Window;

// ---- сам прокси ----
struct BurstProxy::Impl : std::enable_shared_from_this<BurstProxy::Impl> {
    std::atomic<qint64> capBytes{96 * MB};
    std::atomic<qint64> recentMs{10000};
    std::atomic<qint64> headerStallMs{8000};
    std::atomic<qint64> bodyStallMs{6000};
    std::atomic<bool> online{true};
    std::atomic<bool> screenOff{false};
    std::atomic<qint64> downloaded{0};
    std::atomic<qint64> ids{0};
    std::atomic<bool> stopping{false};

    mutable std::mutex cfg;
    QString dir;
    Upstream upstream;
    std::function<std::optional<QString>(const QString &)> refreshUrl;
    std::function<void()> resetConnections;
    std::function<void(const QString &)> logFn;
    std::function<void(qint64)> onBytes;
    QSet<QString> sideKeys;

    mutable std::mutex mapMutex;
    QHash<QString, std::shared_ptr<Source>> sources; // token → источник
    QHash<QString, QString> byUrl;                   // ключ файла → token

    std::mutex trimLock;

    std::mutex serverMutex;
    std::thread serverThread;
    quint16 port = 0;

    std::mutex watchMutex;
    std::condition_variable watchCv;
    std::thread watchThread;
    bool watchStop = false;
    int timerIds = 0;
    std::map<int, std::pair<qint64, std::function<void()>>> timers;

    // ---- службы ----
    void log(const QString &m) const {
        std::function<void(const QString &)> f;
        { std::lock_guard g(cfg); f = logFn; }
        if (f) f(m);
    }
    void countBytes(qint64 n) {
        downloaded += n;
        std::function<void(qint64)> f;
        { std::lock_guard g(cfg); f = onBytes; }
        if (f) f(n);
    }
    Upstream up() const { std::lock_guard g(cfg); return upstream; }
    QString directory() const { std::lock_guard g(cfg); return dir; }
    bool isSide(const QString &key) const { std::lock_guard g(cfg); return sideKeys.contains(key); }
    QSet<QString> sides() const { std::lock_guard g(cfg); return sideKeys; }
    void resetNow() const {
        std::function<void()> f;
        { std::lock_guard g(cfg); f = resetConnections; }
        if (f) try { f(); } catch (...) {}
    }
    std::optional<QString> refresh(const QString &old) const {
        std::function<std::optional<QString>(const QString &)> f;
        { std::lock_guard g(cfg); f = refreshUrl; }
        if (!f) return std::nullopt;
        try { return f(old); } catch (...) { return std::nullopt; }
    }

    /// Пул Kotlin-версии: поток на задачу, прокси жив, пока задача идёт.
    void execute(std::function<void()> f) {
        std::thread([self = shared_from_this(), f = std::move(f)] {
            try { f(); } catch (...) {}
            HttpUpstream::releaseThread(); // соединения этого потока — закрыть, пока поток жив
        }).detach();
    }
    /// resetConnections не в потоке вызывающего, потом after.
    void resetConnectionsAsync(std::function<void()> after = {}) {
        execute([this, after] { resetNow(); if (after) after(); });
    }

    std::vector<std::shared_ptr<Source>> allSources() const {
        std::lock_guard g(mapMutex);
        return {sources.cbegin(), sources.cend()};
    }
    std::shared_ptr<Source> sourceOf(const QString &url) const {
        const QString key = MirrorLink::fileKey(url);
        std::lock_guard g(mapMutex);
        const auto it = byUrl.constFind(key);
        return it == byUrl.cend() ? nullptr : sources.value(*it);
    }
    void kickAll() { for (auto &s : allSources()) for (auto &w : s->windows()) w->kick(); }

    // ---- сторож зависаний и таймеры ----
    void startWatchdog() {
        std::lock_guard g(watchMutex);
        if (watchThread.joinable()) return;
        watchThread = std::thread([this] {
            std::unique_lock lk(watchMutex);
            qint64 nextStall = nowMs() + 1000;
            while (!watchStop) {
                qint64 wake = nextStall;
                for (auto &[id, t] : timers) wake = std::min(wake, t.first);
                const qint64 wait = wake - nowMs();
                if (wait > 0) watchCv.wait_for(lk, std::chrono::milliseconds(wait));
                if (watchStop) break;
                const qint64 now = nowMs();
                std::vector<std::function<void()>> due;
                for (auto it = timers.begin(); it != timers.end();) {
                    if (it->second.first <= now) { due.push_back(std::move(it->second.second)); it = timers.erase(it); }
                    else ++it;
                }
                const bool stall = now >= nextStall;
                lk.unlock();
                for (auto &f : due) try { f(); } catch (...) {}
                if (stall) for (auto &s : allSources()) for (auto &w : s->windows()) w->checkStall(now);
                lk.lock();
                if (stall) nextStall = nowMs() + 1000;
            }
        });
    }
    int schedule(qint64 ms, std::function<void()> f) {
        std::lock_guard g(watchMutex);
        const int id = ++timerIds;
        timers[id] = {nowMs() + ms, std::move(f)};
        watchCv.notify_all();
        return id;
    }
    void unschedule(int id) { std::lock_guard g(watchMutex); timers.erase(id); }

    // ---- локальный сервер ----
    quint16 ensureServer() {
        std::lock_guard g(serverMutex);
        if (port) return port;
        if (serverThread.joinable()) return 0; // не поднялся раньше
        std::promise<quint16> ready;
        auto started = ready.get_future();
        serverThread = std::thread([this, &ready] {
            detail::ensureDispatcher();
            LocalServer server;
            server.onConnection = [this](qintptr fd) { execute([this, fd] { serve(fd); }); };
            if (!server.listen(QHostAddress::LocalHost, 0)) { ready.set_value(0); return; }
            ready.set_value(server.serverPort());
            while (!stopping) {
                QEventLoop loop;
                QTimer::singleShot(200, &loop, &QEventLoop::quit);
                loop.exec();
            }
        });
        port = started.get();
        return port;
    }

    void serve(qintptr fd);
    std::optional<std::pair<QByteArray, qint64>> readRange(const QString &url, qint64 from, int len);
    void serveWindow(QTcpSocket &s, const std::shared_ptr<Source> &src, std::shared_ptr<Window> &reading,
                     const QString &method, bool range, qint64 from, qint64 to);
    void trimToBudget();
    QString wrap(const QString &url);
    void keepOnly(const QStringList &urls);
    int forgetRetired(const std::function<bool(const QString &)> &keep);
    static qint64 diskBytesIn(const QString &d);
    void dropAll() {
        std::vector<std::shared_ptr<Source>> gone;
        {
            std::lock_guard g(mapMutex);
            for (auto &s : sources) gone.push_back(s);
            sources.clear();
            byUrl.clear();
        }
        for (auto &s : gone) s->close();
        std::lock_guard g(cfg);
        sideKeys.clear();
    }

    void shutdown() {
        stopping = true;
        {
            std::lock_guard g(cfg);
            logFn = {}; onBytes = {}; refreshUrl = {}; resetConnections = {};
        }
        dropAll();
        {
            std::lock_guard g(watchMutex);
            watchStop = true;
            timers.clear();
            watchCv.notify_all();
        }
        if (watchThread.joinable()) watchThread.join();
        if (serverThread.joinable()) serverThread.join();
    }
};

namespace burst {
// ---- Source ----
bool Source::side() const { return p->isSide(key); }

qint64 Source::ringCap() const {
    const qint64 cap = p->capBytes;
    const QSet<QString> sides = p->sides();
    if (sides.isEmpty()) return std::max(cap, 16 * MB);
    const qint64 sideCap = std::clamp(cap / 8, 8 * MB, 32 * MB);
    return sides.contains(key) ? sideCap : std::max(cap - sideCap, 16 * MB);
}

qint64 Source::keptMb() const {
    qint64 sum = 0;
    for (auto &w : windows()) sum += std::min<qint64>(w->written, w->CAP);
    return sum >> 20;
}

void Source::storeTail(qint64 abs, const char *buf, qint64 n) {
    const qint64 tot = total;
    if (tot <= 0) return;
    const qint64 tailStart = std::max<qint64>(0, tot - TAIL_CACHE);
    const qint64 e = std::min(abs + n, tot);
    if (e <= tailStart) return;
    std::lock_guard g(m_cacheLock);
    const qint64 from = std::max(abs, tailStart);
    if (from != m_tailRunEnd) { m_tailRunStart = from; m_tailRunEnd = from; } // не продолжение — новый отрезок
    memcpy(m_tail.data() + (from - tailStart), buf + (from - abs), static_cast<size_t>(e - from));
    m_tailRunEnd = e;
}

bool Source::serveTail(QTcpSocket &out, const QString &method, bool range, qint64 from, qint64 to) {
    const qint64 tot = total;
    if (tot <= 0 || from >= tot) return false;
    const qint64 last = (to >= from && to < tot) ? to : tot - 1;
    QByteArray body;
    {
        std::lock_guard g(m_cacheLock);
        if (m_tailRunStart < 0 || from < m_tailRunStart || last >= m_tailRunEnd) return false;
        const qint64 tailStart = std::max<qint64>(0, tot - TAIL_CACHE);
        body = m_tail.mid(from - tailStart, last - from + 1); // пишется без замка: медленный читатель не держит загрузчик
    }
    QByteArray head = range ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n";
    head += "Content-Type: " + type().toUtf8() + "\r\nAccept-Ranges: bytes\r\nContent-Length: " + QByteArray::number(body.size()) + "\r\n";
    if (range) head += "Content-Range: bytes " + QByteArray::number(from) + '-' + QByteArray::number(last) + '/' + QByteArray::number(tot) + "\r\n";
    head += "Connection: close\r\n\r\n";
    writeAll(out, head);
    if (method != QLatin1String("HEAD")) writeAll(out, body);
    return true;
}

std::shared_ptr<Window> Source::windowAt(qint64 pos) const {
    const qint64 nr = near();
    for (auto &w : windows()) {
        if (!w->usable() || pos < w->validFrom()) continue;
        if (w->limit >= 0 ? pos < w->limit : pos <= w->end() + nr) return w;
    }
    return nullptr;
}

std::shared_ptr<Window> Source::windowFor(qint64 pos) {
    std::lock_guard g(m);
    if (auto w = windowAt(pos)) { w->lastReadAt = nowMs(); return w; } // прочитано сразу: trimToBudget его оставит
    const auto ws = windows();
    // Чуть раньше данных, которые уже есть (поиск ключевого кадра назад): качается только разрыв.
    std::shared_ptr<Window> next;
    for (auto &w : ws) {
        const qint64 vf = w->validFrom();
        if (w->usable() && pos < vf && vf - pos <= BRIDGE && (!next || vf - pos < next->validFrom() - pos)) next = w;
    }
    if (next) {
        std::vector<std::shared_ptr<Window>> bridges;
        for (auto &w : ws) if (w->limit >= 0) bridges.push_back(w);
        if (static_cast<int>(bridges.size()) >= MAX_BRIDGES) {
            auto oldest = *std::min_element(bridges.begin(), bridges.end(), [](auto &a, auto &b) { return a->lastReadAt < b->lastReadAt; });
            removeWindow(oldest);
            oldest->close();
        }
        p->log(QStringLiteral("reserve: bridge at %1 MB: only %2 KB up to what is already here").arg(pos / MB).arg((next->validFrom() - pos) / 1024));
        auto w = std::make_shared<Window>(p, shared_from_this(), pos, next->validFrom());
        addWindow(w);
        p->execute([w] { w->download(); });
        return w;
    }
    std::vector<std::shared_ptr<Window>> full;
    for (auto &w : ws) if (w->limit < 0) full.push_back(w);
    if (static_cast<int>(full.size()) >= MAX_WINDOWS) {
        // Окно фильма (доказавшее воспроизведение и прочитанное последним) не уходит никогда.
        std::shared_ptr<Window> mainW;
        for (auto &w : full) if (w->burst() && (!mainW || w->lastReadAt > mainW->lastReadAt)) mainW = w;
        std::shared_ptr<Window> victim;
        for (auto &w : full) if (w != mainW && (!victim || w->lastReadAt < victim->lastReadAt)) victim = w;
        if (victim) { removeWindow(victim); victim->close(); }
    }
    p->log(QStringLiteral("reserve: new window at %1 MB of %2").arg(pos / MB).arg(fileOf(url())));
    auto w = std::make_shared<Window>(p, shared_from_this(), pos);
    addWindow(w);
    p->execute([w, proxy = p] { proxy->trimToBudget(); w->download(); });
    return w;
}

void Source::close() {
    std::lock_guard g(m);
    std::vector<std::shared_ptr<Window>> ws;
    {
        std::lock_guard l(m_listMutex);
        ws.swap(m_windows);
    }
    for (auto &w : ws) w->close();
}

std::shared_ptr<Window> Source::current() const {
    std::shared_ptr<Window> best;
    for (auto &w : windows()) if (!best || w->lastReadAt > best->lastReadAt) best = w;
    return best;
}

// ---- Window ----
Window::Window(BurstProxy::Impl *proxy, std::shared_ptr<Source> s, qint64 st, qint64 lim)
    : p(proxy), src(std::move(s)), start(st), limit(lim), CAP(src->ringCap()), readPos(st) {
    m_file.setFileName(QDir(p->directory()).filePath(QStringLiteral("%1.bin").arg(++p->ids)));
    if (!m_file.open(QIODevice::ReadWrite | QIODevice::Truncate)) { error = QStringLiteral("no reserve file"); hasError = true; }
}

void Window::checkStall(qint64 now) {
    std::shared_ptr<Cancel> c;
    std::shared_ptr<UpstreamResponse> r;
    {
        std::lock_guard g(m_respMutex);
        c = m_cancel;
        r = m_response;
    }
    if (!c || closed) return;
    const bool starving = (end() - readPos) < 2 * MB;
    // сеть, которая раз за разом сбоит, получает больше терпения, только когда впереди есть запас
    const qint64 head = starving ? p->headerStallMs.load() : p->headerStallMs + 4000LL * std::min(m_failures.load(), 2);
    QString why;
    if (!m_gotHeaders && now - m_fetchAt > head) why = fmt("no answer for %.0f s", (now - m_fetchAt) / 1000.0);
    else if (m_gotHeaders && now - m_lastByteAt > p->bodyStallMs) why = fmt("no data for %.0f s", (now - m_lastByteAt) / 1000.0);
    if (why.isEmpty()) return;
    { std::lock_guard g(m_respMutex); m_stalledWhy = why; }
    p->execute([c, r] { c->cancel(); if (r) r->abort(); });
}

void Window::kick() {
    { std::lock_guard g(lock); kicked = true; cv.notify_all(); }
    std::shared_ptr<Cancel> c;
    std::shared_ptr<UpstreamResponse> r;
    {
        std::lock_guard g(m_respMutex);
        c = m_cancel;
        r = m_response;
    }
    if (c) c->cancel();
    if (r) r->abort();
}

bool Window::stopped() const { return p->screenOff || src->retiredAt != 0 || !p->online; }

QString Window::describe(qint64 now) const {
    QString state;
    if (closed) state = QStringLiteral("closed");
    else if (hasError) state = QStringLiteral("error(%1)").arg(error);
    else if (eof) state = QStringLiteral("eof");
    else if (m_downloading) state = QStringLiteral("downloading");
    else state = QStringLiteral("resting");
    QString phase;
    if (limit >= 0) phase = QStringLiteral("bridge to %1MB").arg(limit >> 20);
    else if (!burst()) phase = QStringLiteral("probe");
    else if (ramping()) phase = QStringLiteral("ramp");
    else phase = QStringLiteral("full");
    return QStringLiteral("@%1MB ahead %2MB (low %3 high %4) kept %5MB %6 %7 readers %8 quiet %9s")
        .arg(start >> 20)
        .arg(QString::asprintf("%.1f", std::max<qint64>(0, end() - readPos) / 1048576.0))
        .arg(low() >> 20).arg(high() >> 20)
        .arg(std::min<qint64>(written, CAP) >> 20)
        .arg(phase, state)
        .arg(readers.load())
        .arg((now - lastReadAt) / 1000);
}

bool Window::abandoned() const {
    if (p->screenOff || src->retiredAt != 0) return true;
    if (held()) return false;
    const qint64 quiet = nowMs() - lastReadAt;
    // плеер закрыл соединение к этому месту (короткое чтение индекса или прыжок): стоп сразу
    return (readers == 0 && quiet > 400) || (quiet > 1500 && src->current().get() != this);
}

double Window::reserveSeconds() const {
    const double film = src->sizing(), net = src->netRate;
    if (film <= 0 || net <= 0) return 110.0;
    const double ratio = net / film;
    if (ratio < 2.0) return 55.0;
    if (ratio >= 3.2) return 130.0;
    return 55.0 + (ratio - 2.0) / 1.2 * 75.0;
}

qint64 Window::high() const {
    if (!burst()) return PROBE_AHEAD;
    const qint64 full = std::clamp(static_cast<qint64>(src->sizing() * reserveSeconds()), std::min(24 * MB, CAP * 3 / 4), CAP * 7 / 8);
    return ramping() ? std::min(full, rampAhead()) : full;
}

qint64 Window::low() const {
    if (!burst()) return PROBE_AHEAD / 2;
    const qint64 h = high();
    return std::clamp(static_cast<qint64>(src->sizing() * 25), std::min(6 * MB, h / 3), h / 2);
}

bool Window::ramping() const {
    if (consumedTotal >= rampAfter()) return false;
    // сеть медленнее 3× битрейта фильма короткого запаса не получает
    const double net = src->netRate, film = src->sizing();
    if (net > 0 && film > 0 && net < film * 3) return false;
    return true;
}

void Window::close() {
    closed = true;
    std::shared_ptr<Cancel> c;
    std::shared_ptr<UpstreamResponse> r;
    {
        std::lock_guard g(m_respMutex);
        c = m_cancel;
        r = m_response;
    }
    if (c) c->cancel();
    if (r) r->abort();
    // Под замком: чтение или запись, прошедшие проверку closed, заканчиваются раньше, чем файл уходит.
    std::lock_guard g(lock);
    cv.notify_all();
    m_file.close();
    m_file.remove();
}

void Window::download() {
    while (!closed) {
        if (limit >= 0 && end() >= limit) return; // мост готов: дальше — следующее окно
        {
            std::unique_lock lk(lock);
            while (!closed && !eof) {
                const bool off = stopped();
                const bool idle = off || (!held() && (nowMs() - lastReadAt > 60000 || abandoned()));
                if (!idle && end() - readPos < low()) break;
                cv.wait_for(lk, std::chrono::milliseconds(off ? 5000 : 1000));
            }
        }
        if (closed || eof) return;
        if (stopped()) continue;
        try {
            fetch();
            m_failures = 0;
        } catch (const IoError &e) {
            if (closed) return;
            if (stopped()) { kicked = false; takeStalled(); continue; } // остановлено нарочно, не сбой
            if (dynamic_cast<const Renewed *>(&e)) { p->log(QStringLiteral("reserve: signature expired → new address, repeating")); continue; }
            const QString stalled = takeStalled();
            const bool netChange = kicked.exchange(false);
            const bool isConnError = !stalled.isEmpty() || e.kind == IoError::Kind::Timeout;
            if (!stalled.isEmpty()) p->resetNow();
            p->log(QStringLiteral("reserve: request failed (%1), attempt %2%3")
                       .arg(stalled.isEmpty() ? e.message() : stalled)
                       .arg(m_failures + 1)
                       .arg(!stalled.isEmpty() ? QStringLiteral(" → new connection")
                            : netChange        ? QStringLiteral(" (network changed) → new connection")
                                               : QString()));
            if (dynamic_cast<const FatalUpstream *>(&e)) { fail(e.message()); return; }
            if (netChange) { m_failures = 0; p->resetNow(); continue; } // новая сеть: сразу ещё раз
            if (e.kind == IoError::Kind::Offline) {
                // Сети нет совсем: это не сбой сервера, попытки не расходуются. 1 → 5 → 15 → 30 с; смена сети будит сразу.
                ++m_offlineCycles;
                const qint64 backoff = !p->online ? 30000 : m_offlineCycles == 1 ? 1000 : m_offlineCycles == 2 ? 5000 : m_offlineCycles == 3 ? 15000 : 30000;
                pause(backoff);
                if (kicked.exchange(false)) { m_failures = 0; m_offlineCycles = 0; }
                continue;
            }
            m_offlineCycles = 0;
            if (++m_failures > 8) { fail(e.message().isEmpty() ? QStringLiteral("network") : e.message()); return; }
            const bool starving = (end() - readPos) < 2 * MB;
            if (starving) std::this_thread::sleep_for(std::chrono::milliseconds(150)); // плеер ждёт данных: повтор быстро
            else if (isConnError) std::this_thread::sleep_for(std::chrono::milliseconds(200));
            else pause(std::min<qint64>(8000, 500LL << std::min(m_failures.load(), 4))); // смена сети будит
            if (kicked.exchange(false)) { m_failures = 0; m_offlineCycles = 0; }
        }
    }
}

void Window::pause(qint64 ms) {
    const qint64 until = nowMs() + ms;
    std::unique_lock lk(lock);
    while (!kicked && !closed) {
        const qint64 left = until - nowMs();
        if (left <= 0) break;
        cv.wait_for(lk, std::chrono::milliseconds(left));
    }
}

void Window::fetch() {
    const qint64 from = end();
    {
        const qint64 tot = src->total;
        if (tot >= 0 && tot <= from) { setEof(); return; }
    }
    // Журнал: как прошёл каждый рывок — ответ, первые данные, скорость, почему остановился.
    const qint64 t0 = nowMs();
    qint64 tFirst = 0, got = 0;
    // Скорость сети меряется каждые ~5 с, пока идут данные, а не только в конце рывка.
    qint64 mAt = 0, mGot = 0;
    bool sampled = false;
    QString why = QStringLiteral("error");
    qint64 to;
    {
        const qint64 tot = src->total;
        const double film = src->sizing(), net = src->netRate;
        if (limit >= 0) to = limit - 1; // мост: ровно разрыв
        else if (burst() && tot > 0 && film > 0 && net >= 0.001 && net <= film * SLOW_NET) to = -1; // сеть едва быстрее фильма: один открытый запрос
        else if (burst() && tot > 0) {
            // Доказанный поток просит ровно недостающее (с запасом на то, что плеер съест за время рывка): ответ кончается
            // сам, соединение остаётся для следующего рывка.
            const qint64 need = std::max(high() - std::max<qint64>(0, end() - readPos), 1 * MB);
            const double margin = (film > 0 && net > film * 1.2) ? std::clamp(net / (net - film), BURST_MARGIN, 2.2) : BURST_MARGIN;
            to = std::max(std::min(tot - 1, from + static_cast<qint64>(need * margin) - 1), from);
        } else to = -1;
    }
    kicked = false;
    auto c = std::make_shared<Cancel>();
    {
        std::lock_guard g(m_respMutex);
        m_stalledWhy.clear();
        m_cancel = c;
    }
    m_gotHeaders = false;
    m_fetchAt = t0;
    m_lastByteAt = t0;
    std::shared_ptr<UpstreamResponse> res;
    try {
        const Upstream up = p->up();
        if (!up) throw FatalUpstream(QStringLiteral("no upstream"));
        res = up(src->url(), from, to, *c);
        if (!res) throw IoError(QStringLiteral("no answer"));
    } catch (const IoError &e) {
        { std::lock_guard g(m_respMutex); m_cancel.reset(); }
        p->log(QStringLiteral("reserve: burst at %1 MB: no answer after %2 ms (%3)").arg(from / MB).arg(nowMs() - t0).arg(e.message()));
        throw;
    }
    const qint64 tAnswer = nowMs() - t0;
    m_gotHeaders = true;
    m_lastByteAt = nowMs();
    { std::lock_guard g(m_respMutex); m_response = res; }
    m_downloading = true;
    Finally done{[&] {
        res->close();
        {
            std::lock_guard g(m_respMutex);
            m_response.reset();
            m_cancel.reset();
        }
        m_downloading = false;
        const qint64 ms = nowMs() - t0;
        const qint64 dataMs = std::max<qint64>(1, ms - tFirst);
        if (!sampled) {
            if (got >= 3 * MB && dataMs >= 200) src->addNetSample(got * 1000.0 / dataMs); // короткий рывок — один замер
        } else {
            const qint64 tailMs = t0 + ms - mAt; // хвост после последнего замера
            if (got - mGot >= 3 * MB && tailMs >= 200) src->addNetSample((got - mGot) * 1000.0 / tailMs);
        }
        const double ahead = std::max<qint64>(0, end() - readPos) / 1048576.0;
        const QString rt = src->rate > 0 ? QString::asprintf(" · film %.1f (avg %.1f)", src->rate * 8 / 1e6, src->avgRate * 8 / 1e6) : QString();
        p->log(QString::asprintf("reserve: burst at %lld MB: %.1f MB in %.1f s, %.1f Mbit/s", static_cast<long long>(from / MB), got / 1048576.0,
                                 ms / 1000.0, got > 0 ? got * 8.0 / 1000 / dataMs : 0.0) +
               rt + QString::asprintf(" · answer %lld ms, first data %lld ms · ahead %.0f MB · ", static_cast<long long>(tAnswer),
                                      static_cast<long long>(tFirst), ahead) + why);
    }};

    const int code = res->code();
    if (code == 416) { setEof(); return; }
    if (code == 200 && from > 0) { src->broken = true; throw FatalUpstream(QStringLiteral("no ranges")); }
    const QString old = src->url();
    if ((code == 401 || code == 403) && MirrorLink::isExpired(old)) {
        // Нет нового адреса (нет сети, вышли): обычная неудачная попытка, через несколько кончится.
        const auto fresh = p->refresh(old);
        if (!fresh || MirrorLink::fileKey(*fresh) != MirrorLink::fileKey(old) || MirrorLink::isExpired(*fresh))
            throw IoError(QStringLiteral("HTTP %1, signature expired").arg(code));
        src->replaceUrl(old, *fresh);
        throw Renewed();
    }
    if (code >= 400 && code <= 499) throw FatalUpstream(QStringLiteral("HTTP %1").arg(code));
    if (code < 200 || code > 299) throw IoError(QStringLiteral("HTTP %1").arg(code));
    if (auto t = res->header(QStringLiteral("content-type"))) src->setType(*t);
    if (src->total < 0) {
        qint64 tot = -1;
        if (auto cr = res->header(QStringLiteral("content-range"))) {
            bool ok = false;
            const qint64 v = cr->mid(cr->lastIndexOf(QLatin1Char('/')) + 1).toLongLong(&ok);
            if (ok) tot = v;
        }
        if (tot < 0) {
            if (auto cl = res->header(QStringLiteral("content-length"))) {
                bool ok = false;
                const qint64 v = cl->toLongLong(&ok);
                if (ok) tot = v + from;
            }
        }
        src->total = tot;
    }
    { std::lock_guard g(lock); cv.notify_all(); }
    QByteArray buffer(CHUNK, '\0');
    char *buf = buffer.data();
    while (!closed) {
        // Никто здесь больше не читает — закрыть запрос сразу.
        if (abandoned()) { why = QStringLiteral("player left"); return; }
        const qint64 ahead = end() - readPos;
        // Воспроизведение: рывок кончается, когда впереди достаточно (ограниченный запрос дочитывается до конца сам).
        if (burst() && to < 0 && ahead >= high()) { why = QStringLiteral("reserve full"); return; }
        if (ahead >= CAP * 7 / 8) { why = QStringLiteral("ring full"); return; }
        // ...или плеер перестал брать данные: невыбранная озвучка (mpv прочитал заголовок и держит соединение).
        if (!burst() && !held() && ahead >= PROBE_AHEAD && nowMs() - lastReadAt > PROBE_IDLE_MS) { why = QStringLiteral("player idle"); return; }
        const qint64 n = res->read(buf, CHUNK);
        if (n < 0) {
            // Конец ответа. Конец файла — только если пришло всё до размера; полный ограниченный ответ — конец рывка;
            // короче — соединение оборвалось.
            const qint64 tot = src->total;
            if (tot > 0 && end() < tot && to >= 0 && end() > to) { why = QStringLiteral("burst done"); return; }
            if (tot > 0 && end() < tot) throw IoError(QStringLiteral("answer ended at %1 of %2 MB").arg(end() >> 20).arg(tot >> 20));
            why = QStringLiteral("end of file");
            setEof();
            return;
        }
        if (n == 0) continue;
        m_lastByteAt = nowMs();
        if (tFirst == 0) tFirst = nowMs() - t0;
        got += n;
        if (mAt == 0) { mAt = m_lastByteAt; mGot = got; }
        else if (m_lastByteAt - mAt >= 5000) {
            if (got - mGot >= 1 * MB) src->addNetSample((got - mGot) * 1000.0 / (m_lastByteAt - mAt));
            mAt = m_lastByteAt; mGot = got; sampled = true;
        }
        p->countBytes(n);
        {
            std::lock_guard g(lock);
            if (closed) { why = QStringLiteral("closed"); return; }
            src->storeTail(end(), buf, n);
            ringWrite(end(), buf, n);
            written += n;
            cv.notify_all();
        }
        p->trimToBudget();
    }
    why = QStringLiteral("closed");
}

void Window::ringWrite(qint64 abs, const char *buf, qint64 n) {
    qint64 off = 0, pos = abs;
    while (off < n) {
        const qint64 at = (pos - start) % CAP;
        const qint64 len = std::min(n - off, CAP - at);
        m_file.seek(at);
        m_file.write(buf + off, len);
        off += len;
        pos += len;
    }
}

qint64 Window::read(qint64 abs, char *buf, qint64 max, const std::function<bool()> &alive) {
    if (limit >= 0 && limit <= abs) return -1; // за мостом: читатель переходит в следующее окно
    std::unique_lock lk(lock);
    qint64 waitStart = nowMs();
    while (!closed && !hasError) {
        if (src->retiredAt != 0) return -1; // фильм закрыт
        if (held() && abs >= gateAt) {
            // копим запас: плеер ждёт (это не 45-секундный предел «ничего не идёт»)
            if (!alive()) return -1;
            readPos = abs;
            lastReadAt = nowMs();
            cv.wait_for(lk, std::chrono::milliseconds(std::min<qint64>(500, std::max<qint64>(1, heldUntil - nowMs()))));
            waitStart = nowMs();
            continue;
        }
        if (abs < end() || eof) break;
        if (!alive()) return -1;
        readPos = abs;
        lastReadAt = nowMs();
        cv.notify_all();
        if (nowMs() - waitStart > 45000) return -1;
        cv.wait_for(lk, std::chrono::milliseconds(500));
    }
    if (closed || abs >= end() || abs < validFrom()) return -1;
    qint64 n = std::min(max, end() - abs);
    if (held() && gateAt > abs) n = std::min(n, gateAt - abs);
    qint64 off = 0, pos = abs;
    while (off < n) {
        const qint64 at = (pos - start) % CAP;
        const qint64 len = std::min(n - off, CAP - at);
        m_file.seek(at);
        qint64 r = 0;
        while (r < len) {
            const qint64 k = m_file.read(buf + off + r, len - r);
            if (k <= 0) return -1;
            r += k;
        }
        off += len;
        pos += len;
    }
    return n;
}

void Window::consumed(qint64 abs, qint64 n) {
    std::lock_guard g(lock);
    if (abs == readPos) sequential += n;
    consumedTotal += n;
    readPos = abs + n;
    lastReadAt = nowMs();
    cv.notify_all();
}

} // namespace burst

// ---- Impl ----
void BurstProxy::Impl::trimToBudget() {
    const qint64 budget = capBytes;
    if (budget <= 0) return;
    std::lock_guard g(trimLock);
    std::vector<std::pair<std::shared_ptr<Source>, std::shared_ptr<Window>>> all;
    for (auto &s : allSources()) for (auto &w : s->windows()) all.emplace_back(s, w);
    qint64 used = 0;
    for (auto &[s, w] : all) used += w->diskBytes();
    if (used <= budget) return;
    qint64 filmLast = 0;
    for (auto &[s, w] : all) if (s->retiredAt == 0) filmLast = std::max<qint64>(filmLast, w->lastReadAt);
    const qint64 recent = recentMs;
    auto stays = [&](const std::shared_ptr<Source> &s, const std::shared_ptr<Window> &w) {
        return s->retiredAt == 0 && (w->readers > 0 || filmLast - w->lastReadAt < recent || (!s->side() && w == s->current()));
    };
    decltype(all) drop;
    for (auto &x : all) if (!stays(x.first, x.second)) drop.push_back(x);
    std::stable_sort(drop.begin(), drop.end(), [](auto &a, auto &b) { return a.second->lastReadAt < b.second->lastReadAt; });
    for (auto &[s, w] : drop) {
        if (used <= budget) break;
        const qint64 freed = w->diskBytes();
        { std::lock_guard l(s->m); s->removeWindow(w); }
        w->close();
        used -= freed;
        log(QStringLiteral("reserve: window at %1 MB dropped (%2 MB): the film's reserve is limited to %3 MB").arg(w->start >> 20).arg(freed >> 20).arg(budget >> 20));
    }
}

QString BurstProxy::Impl::wrap(const QString &url) {
    const QString d = directory();
    if (d.isEmpty() || !url.startsWith(QLatin1String("https://"))) return url;
    const qint64 cap = capBytes;
    if (cap <= 0) return url;
    // Файлы запаса (закрытый раньше фильм) освобождают место для нового: см. keepOnly.
    const qint64 free = std::max<qint64>(0, QStorageInfo(d).bytesAvailable());
    if (free + diskBytesIn(d) < cap + 300 * MB) return url;
    const quint16 port = ensureServer();
    if (!port) return url;
    const QString key = MirrorLink::fileKey(url);
    std::shared_ptr<Source> src;
    QString token;
    {
        std::lock_guard g(mapMutex);
        token = byUrl.value(key);
        if (token.isEmpty()) {
            token = QUuid::createUuid().toString(QUuid::Id128);
            sources.insert(token, std::make_shared<Source>(this, token, url));
            byUrl.insert(key, token);
        }
        src = sources.value(token);
    }
    if (src) {
        src->setUrl(url); // самая новая подпись: старая могла истечь
        if (src->retiredAt.exchange(0) != 0) log(QStringLiteral("reserve: back to a film kept on disk (%1 MB)").arg(src->keptMb()));
        if (src->broken) return url; // этот поток через прокси не пошёл: напрямую
    }
    QString name = fileOf(url);
    if (name.trimmed().isEmpty()) name = QStringLiteral("stream");
    const QString decoded = formDecode(name).value_or(name);
    return QStringLiteral("http://127.0.0.1:%1/s/%2/%3").arg(port).arg(token, formEncode(decoded).replace(QLatin1Char('+'), QLatin1String("%20")));
}

void BurstProxy::Impl::keepOnly(const QStringList &urls) {
    std::vector<std::shared_ptr<Source>> gone;
    if (!urls.isEmpty()) {
        QStringList keys;
        for (const auto &u : urls) keys << MirrorLink::fileKey(u);
        QSet<QString> sideSet(keys.begin() + 1, keys.end());
        sideSet.remove(keys.first());
        { std::lock_guard g(cfg); sideKeys = sideSet; }
        const QSet<QString> keep(keys.begin(), keys.end());
        {
            std::lock_guard g(mapMutex);
            for (auto it = byUrl.begin(); it != byUrl.end();) {
                if (!keep.contains(it.key())) {
                    if (auto s = sources.take(it.value())) gone.push_back(s);
                    it = byUrl.erase(it);
                } else ++it;
            }
        }
        for (auto &s : gone) s->close();
        return;
    }
    const qint64 stamp = nowMs();
    std::vector<std::shared_ptr<Source>> listed;
    {
        std::lock_guard g(mapMutex);
        for (const auto &t : std::as_const(byUrl)) if (auto s = sources.value(t)) listed.push_back(s);
    }
    for (auto &s : listed) {
        qint64 expected = 0;
        if (s->retiredAt.compare_exchange_strong(expected, stamp)) for (auto &w : s->windows()) w->kick(); // закрытый фильм перестаёт качать сразу
    }
    // Старые закрытые фильмы уходят: хватит одного. Последний закрытый остаётся, и при повторном clear().
    qint64 newest = 0;
    for (auto &s : listed) newest = std::max<qint64>(newest, s->retiredAt);
    {
        std::lock_guard g(mapMutex);
        for (auto it = byUrl.begin(); it != byUrl.end();) {
            auto s = sources.value(it.value());
            if (s && s->retiredAt != 0 && s->retiredAt < newest) {
                sources.remove(it.value());
                gone.push_back(s);
                it = byUrl.erase(it);
            } else ++it;
        }
    }
    for (auto &s : gone) s->close();
}

int BurstProxy::Impl::forgetRetired(const std::function<bool(const QString &)> &keep) {
    std::vector<std::pair<QString, std::shared_ptr<Source>>> retired;
    {
        std::lock_guard g(mapMutex);
        for (auto it = byUrl.cbegin(); it != byUrl.cend(); ++it)
            if (auto s = sources.value(it.value()); s && s->retiredAt != 0) retired.emplace_back(it.key(), s);
    }
    int n = 0;
    for (auto &[key, s] : retired) {
        const QString link = MirrorLink::param(key, QStringLiteral("link")).value_or(QString());
        if (link.isEmpty() || keep(link)) continue;
        {
            std::lock_guard g(mapMutex);
            byUrl.remove(key);
            sources.remove(s->token);
        }
        s->close();
        ++n;
    }
    return n;
}

qint64 BurstProxy::Impl::diskBytesIn(const QString &d) {
    if (d.isEmpty()) return 0;
    qint64 sum = 0;
    QDirIterator it(d, QDir::Files | QDir::Hidden | QDir::NoDotAndDotDot, QDirIterator::Subdirectories);
    while (it.hasNext()) { it.next(); sum += it.fileInfo().size(); }
    return sum;
}

void BurstProxy::Impl::serve(qintptr fd) {
    detail::ensureDispatcher();
    QTcpSocket s;
    if (!s.setSocketDescriptor(fd)) return;
    try {
        const auto requestLine = readLine(s);
        if (!requestLine) return;
        std::optional<QString> range;
        bool fromBrowser = false;
        while (true) {
            const auto h = readLine(s);
            if (!h) return;
            if (h->isEmpty()) break;
            if (h->startsWith(QLatin1String("range:"), Qt::CaseInsensitive)) range = h->mid(h->indexOf(QLatin1Char(':')) + 1).trimmed();
            // С этим сервером говорит только плеер. Страница в браузере на том же компьютере шлёт Origin — отказ сразу
            // (в адресе ещё и случайный 128-битный токен, которого никто больше не знает).
            if (h->startsWith(QLatin1String("origin:"), Qt::CaseInsensitive)) fromBrowser = true;
        }
        const QStringList parts = requestLine->split(QLatin1Char(' '));
        const QString method = parts.value(0);
        QString token = parts.value(1);
        if (token.startsWith(QLatin1String("/s/"))) token = token.mid(3);
        token = token.section(QLatin1Char('/'), 0, 0);
        std::shared_ptr<Source> src;
        { std::lock_guard g(mapMutex); src = sources.value(token); }
        if (!src || fromBrowser || (method != QLatin1String("GET") && method != QLatin1String("HEAD"))) {
            status(s, "404 Not Found");
        } else {
            qint64 from = 0, to = -1;
            if (range) {
                QString r = *range;
                if (r.startsWith(QLatin1String("bytes="))) r = r.mid(6);
                const QStringList ab = r.split(QLatin1Char('-'));
                bool ok = false;
                const qint64 a = ab.value(0).toLongLong(&ok);
                from = ok ? a : 0;
                const qint64 z = ab.value(1).toLongLong(&ok);
                to = ok ? z : -1;
            }
            if (!src->serveTail(s, method, range.has_value(), from, to)) { // конец файла — из памяти, без окна
                std::shared_ptr<Window> reading = src->windowFor(from); // мост передаёт чтение следующему окну
                { std::lock_guard g(reading->lock); ++reading->readers; reading->lastReadAt = nowMs(); reading->cv.notify_all(); }
                try { serveWindow(s, src, reading, method, range.has_value(), from, to); } catch (...) {}
                { std::lock_guard g(reading->lock); --reading->readers; reading->cv.notify_all(); }
            }
        }
    } catch (...) {
    }
    // Всё записанное уже ушло в систему (writeAll): закрытие отправляет FIN.
    s.close();
}

void BurstProxy::Impl::serveWindow(QTcpSocket &s, const std::shared_ptr<Source> &src, std::shared_ptr<Window> &reading,
                                   const QString &method, bool range, qint64 from, qint64 to) {
    std::shared_ptr<Window> w = reading;
    // Длина известна после первого ответа зеркала (fetch будит читателей, когда заголовки пришли).
    const qint64 waitStart = nowMs();
    while (src->total < 0 && w->usable() && !src->broken && nowMs() - waitStart < 30000) {
        std::unique_lock lk(w->lock);
        if (src->total < 0 && w->usable()) w->cv.wait_for(lk, std::chrono::milliseconds(200));
    }
    const qint64 total = src->total;
    if (total < 0) { status(s, "502 Bad Gateway"); return; }
    if (from >= total) {
        writeAll(s, "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + QByteArray::number(total) + "\r\nConnection: close\r\n\r\n");
        return;
    }
    const qint64 last = (to >= from && to < total) ? to : total - 1;
    QByteArray head = range ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n";
    head += "Content-Type: " + src->type().toUtf8() + "\r\nAccept-Ranges: bytes\r\nContent-Length: " + QByteArray::number(last - from + 1) + "\r\n";
    if (range) head += "Content-Range: bytes " + QByteArray::number(from) + '-' + QByteArray::number(last) + '/' + QByteArray::number(total) + "\r\n";
    head += "Connection: close\r\n\r\n";
    writeAll(s, head);
    if (method == QLatin1String("HEAD")) return;
    QByteArray buffer(CHUNK, '\0');
    qint64 pos = from;
    const qint64 t0 = nowMs();
    qint64 tm = 0, pm = 0; // база замера битрейта, после стартового чтения плеера
    // Смотрится, пока ждём данных, не чаще раза в 0,4 с (каждый взгляд ждёт до 1 мс на сокете).
    qint64 checkedAt = 0;
    bool gone = false;
    auto alive = [&] {
        const qint64 now = nowMs();
        if (!gone && now - checkedAt >= 400) { checkedAt = now; gone = peerGone(s); }
        return !gone;
    };
    while (pos <= last) {
        const qint64 n = w->read(pos, buffer.data(), std::min(CHUNK, last - pos + 1), alive);
        if (n <= 0) {
            // конец моста: дальше в окно, чьи данные начинаются здесь (или новое, если его нет)
            if (w->limit < 0 || pos < w->limit) break;
            auto next = src->windowFor(pos);
            { std::lock_guard g(w->lock); --w->readers; w->cv.notify_all(); }
            { std::lock_guard g(next->lock); ++next->readers; next->lastReadAt = nowMs(); next->cv.notify_all(); }
            w = next;
            reading = next;
            continue;
        }
        writeAll(s, buffer.constData(), n);
        w->consumed(pos, n);
        pos += n;
        // Битрейт фильма ≈ скорость чтения плеера, интервалами по 20 с после первых 15 с. Интервал с паузой или
        // остановкой (сильно ниже среднего файла) пропускается.
        const qint64 now = nowMs();
        if (tm == 0) {
            if (now - t0 > 15000) { tm = now; pm = pos; }
        } else if (now - tm >= 20000) {
            const double m = (pos - pm) * 1000.0 / (now - tm);
            if (m >= src->avgRate * 0.4) src->rate = src->rate > 0 ? src->rate * 0.7 + m * 0.3 : m;
            tm = now;
            pm = pos;
        }
    }
}

// ---- BurstProxy ----
BurstProxy::BurstProxy() : d(std::make_shared<Impl>()) {}
BurstProxy::~BurstProxy() { d->shutdown(); }

void BurstProxy::setCapBytes(qint64 v) { d->capBytes = v; }
qint64 BurstProxy::capBytes() const { return d->capBytes; }
void BurstProxy::setRefreshUrl(std::function<std::optional<QString>(const QString &)> f) { std::lock_guard g(d->cfg); d->refreshUrl = std::move(f); }
void BurstProxy::setResetConnections(std::function<void()> f) { std::lock_guard g(d->cfg); d->resetConnections = std::move(f); }
void BurstProxy::setStallLimits(qint64 headerStallMs, qint64 bodyStallMs) { d->headerStallMs = headerStallMs; d->bodyStallMs = bodyStallMs; }
void BurstProxy::setLog(std::function<void(const QString &)> f) { std::lock_guard g(d->cfg); d->logFn = std::move(f); }
void BurstProxy::setRecentMs(qint64 ms) { d->recentMs = ms; }
void BurstProxy::setOnBytes(std::function<void(qint64)> f) { std::lock_guard g(d->cfg); d->onBytes = std::move(f); }

void BurstProxy::init(const QString &directory, Upstream up) {
    {
        std::lock_guard g(d->cfg);
        d->upstream = std::move(up);
    }
    d->startWatchdog();
    if (!d->directory().isEmpty()) return;
    // Старая папка только переименовывается, удаляется в фоне (вместе с остатками прошлых запусков).
    const QFileInfo info(directory);
    const QString parent = info.absolutePath();
    const QString name = info.fileName();
    QDir dirObj(info.absoluteFilePath());
    if (dirObj.exists()) {
        const QString trash = QDir(parent).filePath(name + QStringLiteral(".old-") + QString::number(QDateTime::currentMSecsSinceEpoch()) +
                                                    QString::number(QUuid::createUuid().data1, 16));
        if (!QDir().rename(info.absoluteFilePath(), trash)) dirObj.removeRecursively();
    }
    QDir().mkpath(info.absoluteFilePath());
    d->execute([parent, name] {
        const QStringList old = QDir(parent).entryList({name + QStringLiteral(".old-*")}, QDir::Dirs | QDir::NoDotAndDotDot);
        for (const auto &o : old) QDir(QDir(parent).filePath(o)).removeRecursively();
    });
    std::lock_guard g(d->cfg);
    d->dir = info.absoluteFilePath();
}

bool BurstProxy::initialized() const { return !d->directory().isEmpty(); }
QString BurstProxy::wrap(const QString &url) { return d->wrap(url); }
void BurstProxy::keepOnly(const QStringList &urls) { d->keepOnly(urls); }
int BurstProxy::forgetRetired(const std::function<bool(const QString &)> &keep) { return d->forgetRetired(keep); }
qint64 BurstProxy::diskBytes(const QString &directory) const { return Impl::diskBytesIn(directory.isEmpty() ? d->directory() : directory); }
void BurstProxy::dropAll() { d->dropAll(); }

void BurstProxy::networkChanged() {
    d->online = true;
    d->log(QStringLiteral("reserve: network changed → restarting the requests"));
    d->resetConnectionsAsync(); // окно, которое пнули, само сбрасывает их в своём потоке перед повтором
    d->kickAll();
}

void BurstProxy::networkLost() {
    if (!d->online.exchange(false)) return;
    d->log(QStringLiteral("reserve: network lost → suspending requests until connection returns"));
    d->kickAll();
}

bool BurstProxy::isNetworkOnline() const { return d->online; }

void BurstProxy::onScreenOff() {
    d->screenOff = true;
    d->log(QStringLiteral("reserve: screen off → downloads stopped"));
    d->kickAll();
    d->resetConnectionsAsync();
}

void BurstProxy::onScreenOn() {
    if (!d->screenOff.exchange(false)) return;
    d->log(QStringLiteral("reserve: screen on → downloads resume"));
    // Будим, только когда пул пуст: иначе возьмут мёртвые сокеты.
    auto impl = d.get();
    d->resetConnectionsAsync([impl] { for (auto &s : impl->allSources()) for (auto &w : s->windows()) w->wake(); });
}

void BurstProxy::setDuration(const QString &url, qint64 ms) {
    if (ms <= 0) return;
    auto src = d->sourceOf(url);
    if (!src) return;
    const qint64 total = src->total;
    if (total <= 0) return;
    const double avg = total * 1000.0 / ms;
    const bool first = src->avgRate <= 0;
    src->avgRate = avg;
    if (src->rate <= 0) src->rate = avg;
    if (first) d->log(QString::asprintf("reserve: bitrate from size/length ≈ %.1f Mbit/s", avg * 8 / 1000000));
}

bool BurstProxy::isActive(const QString &url) const {
    if (d->capBytes <= 0) return false;
    auto src = d->sourceOf(url);
    return src && !src->broken;
}

std::optional<double> BurstProxy::aheadMb(const QString &url) const {
    auto src = d->sourceOf(url);
    if (!src) return std::nullopt;
    auto w = src->current();
    if (!w) return 0.0;
    std::lock_guard g(w->lock);
    return std::max<qint64>(0, w->end() - w->readPos) / 1048576.0;
}

qint64 BurstProxy::aheadMs(const QString &url, qint64 durationMs) const {
    auto src = d->sourceOf(url);
    if (!src) return 0;
    auto w = src->current();
    if (!w) return 0;
    qint64 bytesAhead;
    {
        std::lock_guard g(w->lock);
        bytesAhead = std::max<qint64>(0, w->end() - w->readPos);
    }
    if (bytesAhead <= 0) return 0;
    const qint64 total = src->total;
    if (total > 0 && durationMs > 0) return static_cast<qint64>(static_cast<double>(bytesAhead) / total * durationMs);
    const double filmRate = src->avgRate > 0 ? src->avgRate.load() : src->rate.load();
    if (filmRate > 0) return static_cast<qint64>(bytesAhead / filmRate * 1000.0);
    return 0;
}

std::optional<double> BurstProxy::bitrateMbit(const QString &url) const {
    auto src = d->sourceOf(url);
    if (!src) return std::nullopt;
    const double r = src->avgRate > 0 ? src->avgRate.load() : src->rate.load();
    if (r <= 0) return std::nullopt;
    return r * 8 / 1000000;
}

double BurstProxy::downloadedMb() const { return d->downloaded / 1048576.0; }
qint64 BurstProxy::downloaded() const { return d->downloaded; }

std::optional<std::pair<QByteArray, qint64>> BurstProxy::Impl::readRange(const QString &url, qint64 from, int len) {
    const Upstream upFn = this->up();
    if (!upFn || len <= 0) return std::nullopt;
    auto c = std::make_shared<Cancel>();
    const int timer = schedule(7000, [this, c] { execute([c] { c->cancel(); }); });
    Finally untimer{[&] { unschedule(timer); }};
    try {
        auto r = upFn(url, from, from + len - 1, *c);
        if (!r) return std::nullopt;
        Finally closeIt{[&] { r->close(); }};
        const int code = r->code();
        if (code < 200 || code > 299) return std::nullopt;
        qint64 total = -1;
        bool ok = false;
        if (auto cr = r->header(QStringLiteral("content-range"))) {
            const qint64 v = cr->mid(cr->lastIndexOf(QLatin1Char('/')) + 1).toLongLong(&ok);
            if (ok) total = v;
        }
        if (total < 0 && code == 200) {
            if (auto cl = r->header(QStringLiteral("content-length"))) {
                const qint64 v = cl->toLongLong(&ok);
                if (ok) total = v;
            }
        }
        QByteArray out(len, '\0');
        qint64 have = 0;
        while (have < len) {
            const qint64 n = r->read(out.data() + have, std::min<qint64>(64 * 1024, len - have));
            if (n < 0) break;
            have += n;
        }
        out.truncate(have);
        countBytes(have);
        return std::make_pair(out, total);
    } catch (...) {
        return std::nullopt;
    }
}

std::optional<std::pair<QByteArray, qint64>> BurstProxy::readRange(const QString &url, qint64 from, int len) {
    // В отдельном потоке: у запроса свои события и свой менеджер соединений, поток вызывающего (часто главный) их не держит.
    auto impl = d;
    // MSVC берёт поток для std::async из пула Windows: менеджер запросов закрыть здесь, иначе он живёт с потоком пула
    return std::async(std::launch::async, [impl, url, from, len] {
        auto r = impl->readRange(url, from, len);
        HttpUpstream::releaseThread();
        return r;
    }).get();
}

bool BurstProxy::slowerThanFilm(const QString &url) const {
    auto src = d->sourceOf(url);
    if (!src) return false;
    const double film = src->avgRate > 0 ? src->avgRate.load() : src->rate.load();
    const double net = src->netRate;
    return net > 0 && film > 0 && net < film * 1.15;
}

double BurstProxy::catchUpTargetMb(const QString &url) const {
    auto src = d->sourceOf(url);
    if (!src) return 0.0;
    const double film = src->avgRate > 0 ? src->avgRate.load() : src->rate.load();
    return std::min(film * 30, d->capBytes * 0.5) / 1048576.0;
}

double BurstProxy::keptBehindSec(const QString &url) const {
    auto src = d->sourceOf(url);
    if (!src) return 0.0;
    auto w = src->current();
    if (!w) return 0.0;
    const double film = src->avgRate > 0 ? src->avgRate.load() : src->rate.load();
    if (film <= 0) return 0.0;
    std::lock_guard g(w->lock);
    return std::max<qint64>(0, w->readPos - w->validFrom()) / film;
}

void BurstProxy::hold(const QString &url, qint64 ms) {
    auto src = d->sourceOf(url);
    if (!src) return;
    auto w = src->current();
    if (!w) return;
    std::lock_guard g(w->lock);
    if (ms > 0) {
        if (!w->held()) w->gateAt = w->readPos;
        w->heldUntil = nowMs() + ms;
    } else {
        w->heldUntil = 0;
    }
    w->cv.notify_all();
}

QString BurstProxy::snapshot() const {
    const auto all = d->allSources();
    if (all.empty()) return QStringLiteral("no streams");
    const qint64 now = nowMs();
    QStringList out;
    for (auto &s : all) {
        const QString name = fileOf(s->url()).left(40);
        const QString retired = s->retiredAt > 0 ? QStringLiteral(" retired %1s ago").arg((now - s->retiredAt) / 1000) : QString();
        QStringList ws;
        for (auto &w : s->windows()) ws << w->describe(now);
        out << name + QStringLiteral(" %1MB").arg(std::max<qint64>(s->total, 0) >> 20) +
                   QString::asprintf(" · film %.1f avg %.1f net %.1f Mbit/s", s->rate * 8 / 1e6, s->avgRate * 8 / 1e6, s->netRate * 8 / 1e6) +
                   retired + QStringLiteral(" · ") + (ws.isEmpty() ? QStringLiteral("no windows") : ws.join(QStringLiteral(", ")));
    }
    return out.join(QStringLiteral(" | "));
}

} // namespace suslik::core
