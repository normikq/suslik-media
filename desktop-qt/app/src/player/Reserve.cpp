#include "Reserve.h"
#include "PlayerLog.h"
#include "../Settings.h"

#include "suslik/core/ApiClient.h"
#include "suslik/core/HttpUpstream.h"
#include "suslik/core/MirrorLink.h"

#include <QCoreApplication>
#include <QEventLoop>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QPointer>
#include <QThreadPool>
#include <QTimer>

#include <future>

namespace suslik::app::Reserve {

using namespace suslik::core;

namespace {
QString S(const char *s) { return QString::fromUtf8(s); }

/// Новая подписанная ссылка: запрос идёт в главном потоке (ApiClient живёт там), поток запаса ждёт ответа до 20 с.
std::optional<QString> renew(QPointer<ApiClient> api, const QString &old) {
    const auto path = MirrorLink::renewPath(old);
    if (!path || !api) return std::nullopt;
    auto promise = std::make_shared<std::promise<std::optional<QString>>>();
    auto future = promise->get_future();
    QMetaObject::invokeMethod(api, [api, path = *path, promise] {
        if (!api) { promise->set_value(std::nullopt); return; }
        api->get(path, [promise](const ApiResult &r) {
            const QString u = r.ok() ? r.json.value(QStringLiteral("url")).toString() : QString();
            promise->set_value(u.isEmpty() ? std::nullopt : std::optional<QString>(u));
        });
    }, Qt::QueuedConnection);
    if (future.wait_for(std::chrono::seconds(20)) != std::future_status::ready) return std::nullopt;
    return future.get();
}

/// Читает диапазон до конца в потоке пула (свой менеджер сети); размер всего файла — из Content-Range.
std::optional<qint64> readSome(const QString &url, const QString &range) {
    QNetworkAccessManager nam;
    QNetworkRequest req{QUrl(url)};
    req.setRawHeader("Range", range.toUtf8());
    req.setTransferTimeout(30000);
    QNetworkReply *r = nam.get(req);
    QEventLoop loop;
    QObject::connect(r, &QNetworkReply::readyRead, r, [r] { r->readAll(); });
    QObject::connect(r, &QNetworkReply::finished, &loop, &QEventLoop::quit);
    loop.exec();
    r->readAll();
    const QString cr = QString::fromLatin1(r->rawHeader("Content-Range"));
    r->deleteLater();
    const int slash = cr.lastIndexOf(QLatin1Char('/'));
    bool ok = false;
    const qint64 total = slash >= 0 ? cr.mid(slash + 1).toLongLong(&ok) : 0;
    return ok ? std::optional<qint64>(total) : std::nullopt;
}
} // namespace

namespace {
// В куче, а не статиком: закрывается явно (shutdown) до выхода из main, пока Qt ещё жив. Статик разбирался бы
// после QGuiApplication — его потоки дёргали бы уже закрытый Qt, и программа могла не завершиться.
BurstProxy *g_proxy = nullptr;
HttpUpstream *g_up = nullptr;
QPointer<ApiClient> g_api;
} // namespace

BurstProxy &proxy(ApiClient *api) {
    if (api) g_api = api;
    if (!g_proxy) {
        g_proxy = new BurstProxy;
        if (!g_up) g_up = new HttpUpstream; // не удаляется: держит только соединения, ОС закроет их сама
    }
    BurstProxy &p = *g_proxy;
    if (!p.initialized()) {
        p.setLog([](const QString &m) { PlayerLog::write(m); });
        p.setResetConnections([] { g_up->resetConnections(); });
        p.setRefreshUrl([](const QString &old) { return renew(g_api, old); });
        p.init(reserveDir(), g_up->upstream());
    }
    return p;
}

int dropRetired() { return g_proxy ? g_proxy->dropRetired() : 0; }

void shutdown() {
    delete g_proxy;
    g_proxy = nullptr;
}

void warmUp(const QString &url, qint64 bytes) {
    QThreadPool::globalInstance()->start([url, bytes] { readSome(url, S("bytes=0-%1").arg(bytes - 1)); });
}

void warmSidecar(const QString &localUrl) {
    QThreadPool::globalInstance()->start([localUrl] {
        const auto total = readSome(localUrl, S("bytes=0-262143"));
        if (total && *total > (512 << 10)) readSome(localUrl, S("bytes=%1-%2").arg(*total - (512 << 10)).arg(*total - 1));
    });
}

} // namespace suslik::app::Reserve
