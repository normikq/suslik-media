// Перенос android/core/src/test/.../BurstProxyTest.kt: запас против настоящего HTTP-источника с диапазонами (каким и
// является зеркало). Источник, плеер и прокси — в своих потоках, как в жизни; загрузка — HttpUpstream.
#include "suslik/core/BurstProxy.h"
#include "suslik/core/HttpUpstream.h"
#include "../core/src/QtWait.h"

#include <QDir>
#include <QRegularExpression>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTemporaryDir>
#include <QTest>
#include <QUrl>

#include <atomic>
#include <future>
#include <random>
#include <thread>

#define S QStringLiteral

using namespace suslik::core;
using suslik::core::detail::waitFor;

namespace {

constexpr qint64 MB = 1LL << 20;

void sleepMs(int ms) { std::this_thread::sleep_for(std::chrono::milliseconds(ms)); }

bool connected(const QTcpSocket &s) { return s.state() == QAbstractSocket::ConnectedState; }

bool writeAll(QTcpSocket &s, const char *data, qint64 len) {
    qint64 off = 0;
    while (off < len) {
        if (!connected(s)) return false;
        const qint64 n = s.write(data + off, len - off);
        if (n < 0) return false;
        off += n;
        while (s.bytesToWrite() > 0) {
            if (!connected(s)) return false;
            waitFor(&s, 1000, &QIODevice::bytesWritten, &QAbstractSocket::disconnected);
        }
    }
    return true;
}
bool writeAll(QTcpSocket &s, const QByteArray &b) { return writeAll(s, b.constData(), b.size()); }

std::optional<QByteArray> readLine(QTcpSocket &s, int timeoutMs = 30000) {
    QElapsedTimer t;
    t.start();
    while (!s.canReadLine()) {
        if (!connected(s) && s.bytesAvailable() == 0) return std::nullopt;
        if (t.elapsed() > timeoutMs) return std::nullopt;
        waitFor(&s, 200, &QIODevice::readyRead, &QAbstractSocket::disconnected);
        if (!connected(s) && !s.canReadLine()) return std::nullopt;
    }
    return s.readLine().trimmed();
}

/// Источник: свой поток приёма, поток на соединение, keep-alive.
class Origin {
public:
    QByteArray data;
    std::atomic<int> requests{0};
    std::atomic<int> stallNext{0};       // следующие N ответов шлют заголовки и потом ничего
    std::atomic<bool> slowOrigin{false}; // ответы идут ~2 МБ/с (мобильная сеть медленнее фильма)
    std::mutex rangesMutex;
    QStringList ranges;
    quint16 port = 0;

    explicit Origin(const QByteArray &d) : data(d) {
        std::promise<quint16> ready;
        auto f = ready.get_future();
        m_thread = std::thread([this, &ready] {
            suslik::core::detail::ensureDispatcher();
            Server server;
            server.onConnection = [this](qintptr fd) {
                std::thread([this, fd] { connection(fd); }).detach();
            };
            server.listen(QHostAddress::LocalHost, 0);
            ready.set_value(server.serverPort());
            while (!m_stop) {
                QEventLoop loop;
                QTimer::singleShot(100, &loop, &QEventLoop::quit);
                loop.exec();
            }
        });
        port = f.get();
    }
    ~Origin() {
        m_stop = true;
        m_thread.join();
        while (m_open > 0) sleepMs(20); // соединения дочитываются / рвутся
    }
    QStringList rangesCopy() { std::lock_guard g(rangesMutex); return ranges; }

private:
    class Server : public QTcpServer {
    public:
        std::function<void(qintptr)> onConnection;
    protected:
        void incomingConnection(qintptr fd) override { onConnection(fd); }
    };
    std::thread m_thread;
    std::atomic<bool> m_stop{false};
    std::atomic<int> m_open{0};

    void connection(qintptr fd) {
        ++m_open;
        {
            suslik::core::detail::ensureDispatcher();
            QTcpSocket s;
            s.setSocketDescriptor(fd);
            while (!m_stop && answer(s)) {}
            s.abort();
        }
        --m_open;
    }

    /// Один запрос; false — соединение кончено.
    bool answer(QTcpSocket &s) {
        const auto first = readLine(s, 60000);
        if (!first) return false;
        QString range;
        bool haveRange = false;
        while (true) {
            const auto h = readLine(s);
            if (!h) return false;
            if (h->isEmpty()) break;
            if (h->toLower().startsWith("range:")) { range = QString::fromLatin1(h->mid(6).trimmed()); haveRange = true; }
        }
        ++requests;
        const QString target = QString::fromLatin1(first->split(' ').value(1));
        const QUrl u(S("http://x") + target);
        const qint64 size = data.size();
        if (u.path() == S("/signed.mkv")) {
            // зеркало через 12 часов: старая подпись — 401, новая — байты
            if (!u.query().contains(S("sig=fresh"))) return writeAll(s, "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n");
            static const QRegularExpression re(S("bytes=(\\d*)-(\\d*)"));
            const auto m = re.match(haveRange ? range : S("bytes=0-"));
            const qint64 a = m.captured(1).toLongLong();
            const qint64 z = m.captured(2).isEmpty() ? size - 1 : std::min(m.captured(2).toLongLong(), size - 1);
            const QByteArray head = "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes " + QByteArray::number(a) + '-' + QByteArray::number(z) + '/' +
                                    QByteArray::number(size) + "\r\nContent-Length: " + QByteArray::number(z - a + 1) + "\r\n\r\n";
            return writeAll(s, head) && writeAll(s, data.constData() + a, z - a + 1);
        }
        if (u.path() != S("/film.mkv")) return writeAll(s, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");
        { std::lock_guard g(rangesMutex); ranges << (haveRange ? range : QString()); }
        qint64 a = 0, z = size - 1;
        if (haveRange) {
            static const QRegularExpression re(S("bytes=(\\d*)-(\\d*)"));
            const auto m = re.match(range);
            a = m.captured(1).toLongLong();
            if (!m.captured(2).isEmpty()) z = std::min(m.captured(2).toLongLong(), size - 1);
        }
        if (a >= size) return writeAll(s, "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + QByteArray::number(size) + "\r\nContent-Length: 0\r\n\r\n");
        QByteArray head = haveRange ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n";
        head += "Content-Type: video/x-matroska\r\n";
        if (haveRange) head += "Content-Range: bytes " + QByteArray::number(a) + '-' + QByteArray::number(z) + '/' + QByteArray::number(size) + "\r\n";
        head += "Content-Length: " + QByteArray::number(z - a + 1) + "\r\n\r\n";
        if (!writeAll(s, head)) return false;
        if (stallNext.fetch_sub(1) > 0) { sleepMs(6000); return false; } // зависшее соединение
        if (slowOrigin) {
            for (qint64 p = a; p <= z;) {
                const qint64 n = std::min<qint64>(65536, z - p + 1);
                if (!writeAll(s, data.constData() + p, n)) return false;
                p += n;
                sleepMs(32);
            }
            return true;
        }
        return writeAll(s, data.constData() + a, z - a + 1);
    }
};

struct Got {
    int code = 0;
    QByteArray body;
};

/// Плеер: один запрос на своём соединении, ответ до конца.
Got get(const QString &local, const std::optional<QString> &range) {
    suslik::core::detail::ensureDispatcher();
    const QUrl u(local);
    QTcpSocket s;
    s.connectToHost(QHostAddress::LocalHost, static_cast<quint16>(u.port()));
    for (int i = 0; i < 50 && s.state() != QAbstractSocket::ConnectedState; ++i) waitFor(&s, 100, &QAbstractSocket::connected);
    if (!connected(s)) return {};
    QByteArray req = "GET " + u.path(QUrl::FullyEncoded).toLatin1() + " HTTP/1.1\r\nHost: 127.0.0.1\r\n";
    if (range) req += "Range: " + range->toLatin1() + "\r\n";
    req += "\r\n";
    writeAll(s, req);
    const auto status = readLine(s, 60000);
    if (!status) return {};
    Got out;
    out.code = status->split(' ').value(1).toInt();
    qint64 length = -1;
    while (true) {
        const auto h = readLine(s);
        if (!h || h->isEmpty()) break;
        if (h->toLower().startsWith("content-length:")) length = h->mid(15).trimmed().toLongLong();
    }
    if (out.code >= 400) return out;
    QElapsedTimer quiet;
    quiet.start();
    while (length < 0 || out.body.size() < length) {
        if (s.bytesAvailable() > 0) { out.body += s.readAll(); quiet.restart(); continue; }
        if (!connected(s) || quiet.elapsed() > 60000) break;
        waitFor(&s, 200, &QIODevice::readyRead, &QAbstractSocket::disconnected);
    }
    out.body += s.readAll();
    return out;
}

/// Как Thread { … } Kotlin-теста: функция в своём потоке.
template <class F>
std::future<void> background(F f) { return std::async(std::launch::async, std::move(f)); }

bool alive(std::future<void> &f) { return f.wait_for(std::chrono::seconds(0)) != std::future_status::ready; }

} // namespace

class BurstProxyTest : public QObject {
    Q_OBJECT

    QByteArray data;
    std::unique_ptr<Origin> origin;
    std::unique_ptr<BurstProxy> proxy;
    std::unique_ptr<QTemporaryDir> tmp;
    std::atomic<int> cancelledRequests{0};
    QString url;

    QByteArray part(qint64 a, qint64 n) const { return data.mid(a, n); }

private slots:
    void initTestCase() {
        data.resize(40 * MB);
        std::mt19937 rnd(7);
        for (qsizetype i = 0; i < data.size(); i += 4) {
            const quint32 v = rnd();
            memcpy(data.data() + i, &v, 4);
        }
    }

    void init() {
        cancelledRequests = 0;
        origin = std::make_unique<Origin>(data);
        tmp = std::make_unique<QTemporaryDir>();
        proxy = std::make_unique<BurstProxy>();
        url = S("https://origin.test/film.mkv?sig=1");
        HttpUpstream http;
        const quint16 port = origin->port;
        proxy->init(QDir(tmp->path()).filePath(S("reserve")), [http, port, this](const QString &u, qint64 from, qint64 to, Cancel &cancel) {
            cancel.attach([this] { ++cancelledRequests; });
            const QString local = S("http://127.0.0.1:%1/").arg(port) + u.mid(u.indexOf(S("origin.test/")) + 12);
            return http.open(local, from, to, cancel);
        });
        proxy->setResetConnections([http]() mutable { http.resetConnections(); });
        proxy->setCapBytes(16 * MB); // меньше файла: кольцо проворачивается
        if (qEnvironmentVariableIsSet("SM_TEST_LOG")) proxy->setLog([](const QString &m) { qInfo().noquote() << "[proxy]" << m; });
    }

    void cleanup() {
        proxy.reset();
        origin.reset();
        tmp.reset();
    }

    void servesExactBytes() {
        const QString local = proxy->wrap(url);
        QVERIFY2(local.startsWith(S("http://127.0.0.1:")), qPrintable(local));
        const Got all = get(local, std::nullopt);
        QCOMPARE(all.code, 200);
        QVERIFY(all.body == data);
        const std::pair<qint64, qint64> rs[] = {{0, 99}, {1000, 1999}, {data.size() - 100, data.size() - 1}, {5000000, 5100000}};
        for (auto [a, z] : rs) {
            const Got g = get(local, S("bytes=%1-%2").arg(a).arg(z));
            QCOMPARE(g.code, 206);
            QVERIFY2(g.body == part(a, z - a + 1), qPrintable(S("range %1-%2").arg(a).arg(z)));
        }
        QCOMPARE(get(local, S("bytes=%1-").arg(data.size() + 10)).code, 416);
    }

    void throttledPlaybackComesInBursts() {
        const QString local = proxy->wrap(url);
        const int before = origin->requests;
        // плеер ~16 МБ/с
        suslik::core::detail::ensureDispatcher();
        const QUrl u(local);
        QTcpSocket s;
        s.setReadBufferSize(64 * 1024);
        s.connectToHost(QHostAddress::LocalHost, static_cast<quint16>(u.port()));
        QVERIFY(s.waitForConnected(5000));
        writeAll(s, "GET " + u.path(QUrl::FullyEncoded).toLatin1() + " HTTP/1.1\r\nHost: 127.0.0.1\r\nRange: bytes=0-\r\n\r\n");
        while (true) { const auto h = readLine(s); QVERIFY(h); if (h->isEmpty()) break; }
        QByteArray out;
        while (out.size() < data.size()) {
            if (s.bytesAvailable() > 0) { out += s.read(64 * 1024); sleepMs(4); continue; }
            if (!connected(s)) break;
            waitFor(&s, 200, &QIODevice::readyRead, &QAbstractSocket::disconnected);
        }
        QVERIFY(out == data);
        const int used = origin->requests - before;
        QVERIFY2(used >= 1 && used <= 12, qPrintable(S("upstream requests: %1").arg(used))); // рывки, не запрос на порцию
        // Играющий поток просит ровно недостающее (bytes=a-b): ответ кончается сам, соединение переиспользуется.
        static const QRegularExpression bounded(S("^bytes=\\d+-\\d+$"));
        bool any = false;
        for (const auto &r : origin->rangesCopy()) any = any || bounded.match(r).hasMatch();
        QVERIFY2(any, qPrintable(origin->rangesCopy().join(u',')));
    }

    void anOpenButUnreadStreamStopsAtTheProbeReserve() {
        // Невыбранная внешняя озвучка: mpv читает заголовок и держит соединение, ничего не читая.
        origin->slowOrigin = true;
        const QUrl local(proxy->wrap(url));
        const qint64 before = proxy->downloaded();
        {
            suslik::core::detail::ensureDispatcher();
            QTcpSocket s;
            s.setReadBufferSize(64 * 1024);
            s.connectToHost(QHostAddress::LocalHost, static_cast<quint16>(local.port()));
            QVERIFY(s.waitForConnected(5000));
            writeAll(s, "GET " + local.path(QUrl::FullyEncoded).toLatin1() + " HTTP/1.1\r\nHost: 127.0.0.1\r\nRange: bytes=0-\r\n\r\n");
            qint64 got = 0;
            while (got < 256 * 1024) {
                if (s.bytesAvailable() > 0) { got += s.read(64 * 1024).size(); continue; }
                if (!connected(s)) break;
                waitFor(&s, 200, &QIODevice::readyRead);
            }
            sleepMs(7000); // соединение открыто, никто не читает
            const qint64 fetched = proxy->downloaded() - before;
            QVERIFY2(fetched < 9 * MB, qPrintable(S("fetched %1 MB for an unread stream").arg(fetched >> 20)));
        }
        origin->slowOrigin = false;
    }

    void stalledAnswerIsRepeatedOnANewConnection() {
        proxy->setStallLimits(700, 700);
        origin->stallNext = 1; // первый ответ приходит с заголовками и виснет
        const QString local = proxy->wrap(url);
        QElapsedTimer t;
        t.start();
        const Got g = get(local, S("bytes=0-2999999"));
        QCOMPARE(g.code, 206);
        QVERIFY(g.body == part(0, 3000000));
        QVERIFY2(t.elapsed() < 5500, qPrintable(S("took %1 ms: the hung answer should be given up after ~1-2 s").arg(t.elapsed())));
        QVERIFY(origin->requests >= 2);
    }

    void networkChangeRestartsRequestsInFlight() {
        origin->stallNext = 1;
        const QString local = proxy->wrap(url);
        Got result;
        auto th = background([&] { result = get(local, S("bytes=0-1999999")); });
        sleepMs(600);             // первый ответ висит
        proxy->networkChanged();  // Wi-Fi → мобильная: без ожидания таймаута
        QVERIFY(th.wait_for(std::chrono::seconds(4)) == std::future_status::ready);
        QCOMPARE(result.code, 206);
        QVERIFY(result.body == part(0, 2000000));
    }

    void sameFileKeepsItsReserveAcrossSignedAddressesAndShortAbsence() {
        auto addr = [](const QString &link, int n) { return S("https://origin.test/film.mkv?link=%1&exp=%2&sig=s%2").arg(link).arg(n); };
        const QString a = proxy->wrap(addr(S("L1"), 1));
        QCOMPARE(proxy->wrap(addr(S("L1"), 2)), a);   // тот же файл, новая подпись: один запас
        QVERIFY(proxy->wrap(addr(S("L2"), 2)) != a);  // другой файл: свой
        const Got head = get(a, S("bytes=0-999999"));
        QCOMPARE(head.code, 206);
        QVERIFY(head.body == part(0, 1000000));
        const QString snap = proxy->snapshot();
        QVERIFY2(snap.contains(S("film.mkv")) && snap.contains(S("ahead")) && !snap.contains(S("sig=")), qPrintable(snap));
        proxy->clear(); // плеер закрыт: хранится на диске
        const QString again = proxy->wrap(addr(S("L1"), 3));
        QCOMPARE(again, a);
        const Got mid = get(again, S("bytes=100000-299999"));
        QCOMPARE(mid.code, 206);
        QVERIFY(mid.body == part(100000, 200000));
        proxy->dropAll(); // чистый перезапуск: всё забыто
        QVERIFY(proxy->wrap(addr(S("L1"), 4)) != a);
    }

    void bitrateComesFromSizeAndLength() {
        const QString local = proxy->wrap(url);
        get(local, S("bytes=0-99"));      // первый ответ называет размер файла (40 МиБ)
        proxy->setDuration(url, 40000);   // 40 МиБ за 40 с
        QVERIFY(qAbs(*proxy->bitrateMbit(url) - 8.39) < 0.05);
        proxy->setDuration(url, 20000);   // плеер поправил длину: средний следует
        QVERIFY(qAbs(*proxy->bitrateMbit(url) - 16.78) < 0.05);
    }

    void slowNetworkIsKnownWhileTheFirstBurstStillRuns() {
        origin->slowOrigin = true;
        const QString local = proxy->wrap(url);
        get(local, S("bytes=0-99"));
        proxy->setDuration(url, 10000); // 40 МиБ за 10 с = фильм 4 МиБ/с, сеть даёт ~2
        auto reader = background([&] { get(local, S("bytes=0-")); }); // плеер читает, как приходит
        sleepMs(7500);
        QVERIFY2(alive(reader), "the first burst should still be running");
        QVERIFY2(proxy->slowerThanFilm(url), qPrintable(proxy->snapshot()));
        const double target = proxy->catchUpTargetMb(url);
        QVERIFY2(target >= 1.0 && target <= 16.0 * 0.6 + 0.01, qPrintable(S("target %1").arg(target)));
        origin->slowOrigin = false;
        proxy->dropAll(); // читатель уходит
        reader.wait();
    }

    void holdGathersAReserveThePlayerGetsAfterwards() {
        const QString local = proxy->wrap(url);
        get(local, S("bytes=0-99"));
        proxy->hold(url, 10000);
        Got result;
        auto reader = background([&] { result = get(local, S("bytes=0-5999999")); });
        sleepMs(1500);
        QVERIFY2(alive(reader), "the player must wait while the reserve is gathered");
        const double ahead = *proxy->aheadMb(url);
        QVERIFY2(ahead >= 2.0, qPrintable(S("ahead %1 MB: the reserve should grow meanwhile").arg(ahead)));
        proxy->hold(url, 0); // отпустить
        QVERIFY(reader.wait_for(std::chrono::seconds(5)) == std::future_status::ready);
        QCOMPARE(result.code, 206);
        QVERIFY(result.body == part(0, 6000000));
    }

    void keyframeSearchBackwardsFetchesOnlyTheGaps() {
        const QString local = proxy->wrap(url);
        const qint64 p = 20000000;
        QVERIFY(get(local, S("bytes=%1-%2").arg(p).arg(p + 2999999)).body == part(p, 3000000)); // перемотка: плеер читает немного
        sleepMs(300);
        // VLC ищет ключевой кадр: каждый раз на 1,5 МБ назад, дочитывая уже скачанное
        qint64 start = p;
        for (int i = 0; i < 4; ++i) {
            start -= 1500000;
            const Got g = get(local, S("bytes=%1-%2").arg(start).arg(start + 2999999));
            QCOMPARE(g.code, 206);
            QVERIFY2(g.body == part(start, 3000000), qPrintable(S("step at %1").arg(start)));
        }
        const QStringList ranges = origin->rangesCopy();
        bool step1 = false;
        for (const auto &r : ranges) step1 = step1 || (r.startsWith(S("bytes=%1-").arg(p - 1500000)) && !r.endsWith(u'-'));
        QVERIFY2(step1, qPrintable(ranges.join(u',')));
        for (int k = 2; k <= 4; ++k) {
            const qint64 a = p - k * 1500000;
            QVERIFY2(ranges.contains(S("bytes=%1-%2").arg(a).arg(a + 1499999)), qPrintable(S("step %1: ").arg(k) + ranges.join(u',')));
        }
        QVERIFY2(origin->requests == 5, qPrintable(S("one request per step: ") + ranges.join(u',')));
    }

    void readRangeGivesAPieceAndTheSize() {
        const auto r = proxy->readRange(url, data.size() - 5000, 5000);
        QVERIFY(r);
        QCOMPARE(r->second, qint64(data.size()));
        QVERIFY(r->first == part(data.size() - 5000, 5000));
    }

    void aClosedFilmThatLeftTheLibraryIsForgotten() {
        auto addr = [](const QString &link) { return S("https://origin.test/film.mkv?link=") + QString::fromLatin1(QUrl::toPercentEncoding(link)) + S("&exp=1&sig=s"); };
        const QString gone = S("https://alld.test/f/gone"), kept = S("https://alld.test/f/kept");
        const QString a = proxy->wrap(addr(gone)), b = proxy->wrap(addr(kept));
        get(a, S("bytes=0-99999"));
        get(b, S("bytes=0-99999"));
        proxy->clear(); // оба закрыты: хранятся на диске
        QVERIFY(proxy->diskBytes() > 0);
        QCOMPARE(proxy->forgetRetired([&](const QString &l) { return l == kept; }), 1);
        QCOMPARE(proxy->wrap(addr(kept)), b);  // тот, что ещё в медиатеке, на месте
        QVERIFY(proxy->wrap(addr(gone)) != a); // ушедший начинается с нуля
    }

    void theLastClosedFilmSurvivesASecondClear() {
        auto addr = [](int n) { return S("https://origin.test/film.mkv?link=L1&exp=%1&sig=s%1").arg(n); };
        const QString a = proxy->wrap(addr(1));
        get(a, S("bytes=0-99999"));
        proxy->keepOnly({}); // плеер закрыт
        sleepMs(5);
        proxy->clear();      // и закрыт ещё раз (другой экран)
        QCOMPARE(proxy->wrap(addr(2)), a);
    }

    void openingAnotherFilmDropsTheClosedOne() {
        auto addr = [](const QString &link) { return S("https://origin.test/film.mkv?link=%1&exp=1&sig=s").arg(link); };
        const QString a = proxy->wrap(addr(S("L1")));
        get(a, S("bytes=0-99999"));
        proxy->clear();
        QVERIFY(proxy->diskBytes() > 0);
        proxy->keepOnly({addr(S("L2"))}); // открыт другой фильм
        QCOMPARE(proxy->diskBytes(), 0);
        QVERIFY(proxy->wrap(addr(S("L1"))) != a);
    }

    void theFilmStaysWithinTheReserveSetting() {
        proxy->setRecentMs(0);      // без поблажки после перемотки
        origin->slowOrigin = true;  // ~2 МБ/с: первое окно само до перемотки не доходит
        const QString local = proxy->wrap(url);
        get(local, S("bytes=0-7999999"));          // с начала
        get(local, S("bytes=30000000-38999999"));  // далеко вперёд: второе окно, 8 + 9 МБ > 16 МБ
        sleepMs(500);
        origin->slowOrigin = false;
        const QString snap = proxy->snapshot();
        QVERIFY2(!snap.contains(S("@0MB")), qPrintable(snap)); // место, которое плеер покинул, ушло
        QVERIFY2(proxy->diskBytes() <= 16 * MB, qPrintable(S("disk %1: ").arg(proxy->diskBytes()) + snap));
    }

    void anExpiredSignatureIsRenewedInsteadOfFailing() {
        const qint64 now = QDateTime::currentSecsSinceEpoch();
        std::atomic<int> renewed{0};
        proxy->setRefreshUrl([&](const QString &old) -> std::optional<QString> {
            ++renewed;
            if (old.contains(S("link=L9"))) return S("https://origin.test/signed.mkv?link=L9&exp=%1&sig=fresh").arg(now + 3600);
            return std::nullopt;
        });
        const QString local = proxy->wrap(S("https://origin.test/signed.mkv?link=L9&exp=%1&sig=old").arg(now - 10));
        const Got g = get(local, S("bytes=0-999999"));
        QCOMPARE(g.code, 206);
        QVERIFY(g.body == part(0, 1000000));
        QCOMPARE(renewed.load(), 1);
        proxy->setRefreshUrl({});
    }

    void offWhenReserveIsZero() {
        proxy->setCapBytes(0);
        QCOMPARE(proxy->wrap(S("https://origin.test/other.mkv")), S("https://origin.test/other.mkv"));
    }

    void offlineServesBufferedRangeWithoutNetworkAndResumesOnNetworkChanged() {
        const QString local = proxy->wrap(url);
        const Got g1 = get(local, S("bytes=0-3999999")); // запас: первые 4 МБ
        QCOMPARE(g1.code, 206);
        QVERIFY(g1.body == part(0, 4000000));
        const int before = origin->requests;
        proxy->networkLost(); // сети нет совсем
        QVERIFY(!proxy->isNetworkOnline());
        // скачанное читается без сети (не с самого начала: кольцо могло уйти за первые ~1,9 МБ)
        const Got g2 = get(local, S("bytes=2500000-3999999"));
        QCOMPARE(g2.code, 206);
        QVERIFY(g2.body == part(2500000, 1500000));
        QCOMPARE(origin->requests.load(), before);
        proxy->networkChanged(); // сеть вернулась
        QVERIFY(proxy->isNetworkOnline());
        const Got g3 = get(local, S("bytes=30000000-30999999"));
        QCOMPARE(g3.code, 206);
        QVERIFY(g3.body == part(30000000, 1000000));
        QVERIFY(origin->requests > before);
    }

    void farSeekCancelsInFlightUpstreamAndStartsNewWindow() {
        const QString local = proxy->wrap(url);
        origin->slowOrigin = true;
        auto reader = background([&] { get(local, S("bytes=0-9999999")); });
        sleepMs(400); // медленный первый запрос пошёл
        const qint64 seekPos = 25000000;
        const Got g = get(local, S("bytes=%1-%2").arg(seekPos).arg(seekPos + 999999));
        QCOMPARE(g.code, 206);
        QVERIFY(g.body == part(seekPos, 1000000));
        sleepMs(600);
        QVERIFY(cancelledRequests > 0 || origin->requests >= 2);
        origin->slowOrigin = false;
        proxy->dropAll();
        reader.wait();
    }

    void aPlayerThatLeftWhileWaitingNoLongerCountsAsReading() {
        const QUrl local(proxy->wrap(url));
        get(local.toString(), S("bytes=0-99"));
        proxy->hold(url, 15000); // плеер ничего не получает: его соединение ждёт в прокси
        {
            suslik::core::detail::ensureDispatcher();
            QTcpSocket s;
            s.connectToHost(QHostAddress::LocalHost, static_cast<quint16>(local.port()));
            QVERIFY(s.waitForConnected(5000));
            writeAll(s, "GET " + local.path(QUrl::FullyEncoded).toLatin1() + " HTTP/1.1\r\nHost: 127.0.0.1\r\nRange: bytes=0-\r\n\r\n");
            sleepMs(800);
            QVERIFY2(proxy->snapshot().contains(S("readers 1")), qPrintable(proxy->snapshot()));
            s.abort(); // плеер закрывает соединение, пока ждёт
        }
        sleepMs(1200);
        QVERIFY2(proxy->snapshot().contains(S("readers 0")), qPrintable(S("the gone player still counts: ") + proxy->snapshot()));
        proxy->hold(url, 0);
    }

    void aJumpJustPastTheReserveOnASlowNetworkOpensItsOwnRequest() {
        origin->slowOrigin = true; // ~2 МБ/с: прыжок на 1,5 с этой сети и дальше быстрее новым запросом
        const QString local = proxy->wrap(url);
        const qint64 before = proxy->downloaded();
        auto reader = background([&] {
            suslik::core::detail::ensureDispatcher();
            const QUrl u(local);
            QTcpSocket s;
            s.connectToHost(QHostAddress::LocalHost, static_cast<quint16>(u.port()));
            if (!s.waitForConnected(5000)) return;
            writeAll(s, "GET " + u.path(QUrl::FullyEncoded).toLatin1() + " HTTP/1.1\r\nHost: 127.0.0.1\r\nRange: bytes=0-\r\n\r\n");
            QElapsedTimer t;
            t.start();
            while (t.elapsed() < 6500 && connected(s)) {
                if (s.bytesAvailable() > 0) { s.readAll(); continue; }
                waitFor(&s, 100, &QIODevice::readyRead);
            }
            s.abort();
        });
        QVERIFY(reader.wait_for(std::chrono::seconds(10)) == std::future_status::ready);
        sleepMs(800); // никто не читает: окно перестаёт качать
        const qint64 end = proxy->downloaded() - before; // одно окно от 0: где кончаются его данные
        const qint64 pos = end + 5500000; // в пределах старых 8 МБ «рядом», но много дальше 1,5 с этой сети
        const Got g = get(local, S("bytes=%1-%2").arg(pos).arg(pos + 99999));
        QCOMPARE(g.code, 206);
        QVERIFY(g.body == part(pos, 100000));
        bool own = false;
        for (const auto &r : origin->rangesCopy()) own = own || r.startsWith(S("bytes=%1-").arg(pos));
        QVERIFY2(own, qPrintable(origin->rangesCopy().join(u',') + S(" ") + proxy->snapshot()));
        origin->slowOrigin = false;
    }

    void fastScrubbingDoesNotExceedMaxWindows() {
        const QString local = proxy->wrap(url);
        for (const qint64 pos : {1000000, 10000000, 20000000, 28000000, 35000000}) {
            const Got g = get(local, S("bytes=%1-%2").arg(pos).arg(pos + 99999));
            QCOMPARE(g.code, 206);
            QVERIFY2(g.body == part(pos, 100000), qPrintable(S("failed at pos %1").arg(pos)));
        }
        const QString snap = proxy->snapshot();
        static const QRegularExpression win(S("@\\d+MB"));
        int count = 0;
        for (auto it = win.globalMatch(snap); it.hasNext(); it.next()) ++count;
        QVERIFY2(count <= 3, qPrintable(snap));
    }

    void ringBufferWraparoundAllowsReFetchOfOverwrittenData() {
        const QString local = proxy->wrap(url);
        const Got g1 = get(local, S("bytes=0-19999999")); // 20 МБ при кольце 16 МБ: начало перезаписано
        QCOMPARE(g1.code, 206);
        QVERIFY(g1.body == part(0, 20000000));
        const Got g2 = get(local, S("bytes=0-99999"));    // начало снова
        QCOMPARE(g2.code, 206);
        QVERIFY(g2.body == part(0, 100000));
    }
};

QTEST_GUILESS_MAIN(BurstProxyTest)
#include "BurstProxyTest.moc"
