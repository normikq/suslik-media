#include "suslik/core/HttpUpstream.h"
#include "QtWait.h"

#include <QDateTime>
#include <QNetworkAccessManager>
#include <QNetworkProxy>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QPointer>
#include <atomic>

namespace suslik::core {

struct HttpUpstream::State {
    static inline std::atomic<quint64> ids{0};
    const quint64 id = ++ids;
    std::atomic<quint64> generation{0};
    std::atomic<qint64> answerTimeoutMs{20000};
};

namespace {

qint64 nowMs() { return QDateTime::currentMSecsSinceEpoch(); }

/// Менеджер запросов этого потока. Новый — при другом HttpUpstream или после resetConnections.
struct PerThread {
    std::unique_ptr<QNetworkAccessManager> nam;
    quint64 owner = 0;
    quint64 generation = 0;
};
thread_local PerThread t_nam;

QNetworkAccessManager &managerFor(const HttpUpstream::State &s) {
    const quint64 gen = s.generation;
    if (!t_nam.nam || t_nam.owner != s.id || t_nam.generation != gen) {
        t_nam.nam.reset(); // его соединения закрываются
        t_nam.nam = std::make_unique<QNetworkAccessManager>();
        t_nam.nam->setRedirectPolicy(QNetworkRequest::NoLessSafeRedirectPolicy);
        t_nam.nam->setProxy(QNetworkProxy::NoProxy);
        t_nam.owner = s.id;
        t_nam.generation = gen;
    }
    return *t_nam.nam;
}

IoError errorOf(QNetworkReply *r) {
    const QString msg = r->errorString();
    switch (r->error()) {
    case QNetworkReply::HostNotFoundError:
    case QNetworkReply::ConnectionRefusedError:
    case QNetworkReply::NetworkSessionFailedError:
    case QNetworkReply::TemporaryNetworkFailureError:
        return IoError(msg, IoError::Kind::Offline);
    case QNetworkReply::TimeoutError:
        return IoError(msg, IoError::Kind::Timeout);
    default:
        return IoError(msg);
    }
}

class Response : public UpstreamResponse {
public:
    Response(QNetworkReply *r, std::shared_ptr<std::atomic<bool>> aborted) : m_reply(r), m_aborted(std::move(aborted)) {}
    ~Response() override {
        if (m_reply) m_reply->deleteLater(); // close() не вызвали: удалит его поток
    }
    int code() const override { return m_reply ? m_reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt() : 0; }
    std::optional<QString> header(const QString &name) const override {
        const QByteArray n = name.toLatin1();
        if (!m_reply || !m_reply->hasRawHeader(n)) return std::nullopt;
        return QString::fromLatin1(m_reply->rawHeader(n));
    }
    qint64 read(char *buf, qint64 max) override {
        while (true) {
            if (!m_reply) throw IoError(QStringLiteral("closed"));
            if (*m_aborted) {
                m_reply->abort();
                throw IoError(QStringLiteral("cancelled"));
            }
            if (m_reply->bytesAvailable() > 0) return m_reply->read(buf, max);
            if (m_reply->isFinished()) {
                if (m_reply->error() != QNetworkReply::NoError) throw errorOf(m_reply);
                return -1;
            }
            // abort() из другого потока замечается за 0,1 с
            detail::waitFor(m_reply.data(), 100, &QIODevice::readyRead, &QNetworkReply::finished);
        }
    }
    void close() override {
        if (!m_reply) return;
        if (!m_reply->isFinished()) m_reply->abort(); // недочитанный ответ: соединение закрывается
        delete m_reply.data(); // дочитанный: соединение остаётся в пуле менеджера
        m_reply.clear();
    }
    void abort() override { *m_aborted = true; }

private:
    QPointer<QNetworkReply> m_reply;
    std::shared_ptr<std::atomic<bool>> m_aborted;
};

} // namespace

HttpUpstream::HttpUpstream() : d(std::make_shared<State>()) {}

void HttpUpstream::setAnswerTimeoutMs(qint64 ms) { d->answerTimeoutMs = ms; }

void HttpUpstream::resetConnections() { ++d->generation; }

Upstream HttpUpstream::upstream() const {
    HttpUpstream self = *this;
    return [self](const QString &url, qint64 from, qint64 to, Cancel &cancel) { return self.open(url, from, to, cancel); };
}

std::shared_ptr<UpstreamResponse> HttpUpstream::open(const QString &url, qint64 from, qint64 to, Cancel &cancel) const {
    detail::ensureDispatcher();
    auto aborted = std::make_shared<std::atomic<bool>>(false);
    cancel.attach([aborted] { *aborted = true; });
    if (*aborted) throw IoError(QStringLiteral("cancelled"));

    QNetworkRequest req{QUrl(url)};
    req.setRawHeader("Range", "bytes=" + QByteArray::number(from) + '-' + (to >= 0 ? QByteArray::number(to) : QByteArray()));
    req.setRawHeader("Accept-Encoding", "identity"); // без сжатия: байты диапазона = байты файла
    req.setAttribute(QNetworkRequest::Http2AllowedAttribute, false); // nginx без http2
    req.setAttribute(QNetworkRequest::CacheLoadControlAttribute, QNetworkRequest::AlwaysNetwork);
    QNetworkReply *reply = managerFor(*d).get(req);
    reply->setReadBufferSize(1 << 20); // не больше 1 МБ впереди того, что забрал загрузчик

    const qint64 until = nowMs() + d->answerTimeoutMs;
    auto headersIn = [reply] { return reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).isValid(); };
    while (!headersIn() && !reply->isFinished()) {
        if (*aborted) {
            reply->abort();
            delete reply;
            throw IoError(QStringLiteral("cancelled"));
        }
        if (nowMs() > until) {
            reply->abort();
            delete reply;
            throw IoError(QStringLiteral("no answer"), IoError::Kind::Timeout);
        }
        detail::waitFor(reply, 100, &QNetworkReply::metaDataChanged, &QNetworkReply::finished, &QIODevice::readyRead);
    }
    if (!headersIn()) { // кончился без ответа сервера: сбой сети
        const IoError e = errorOf(reply);
        delete reply;
        throw e;
    }
    return std::make_shared<Response>(reply, aborted);
}

} // namespace suslik::core
