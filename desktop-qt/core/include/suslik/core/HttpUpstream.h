#pragma once
#include "suslik/core/BurstProxy.h"

/// Загрузка диапазонов с зеркала для BurstProxy (на ПК в Kotlin-версии это делал java.net.http, Reserve.kt).
/// Запрос идёт в потоке вызывающего и ждёт событиями этого потока; у каждого потока свой QNetworkAccessManager,
/// так что рывки одного окна идут по одному живому соединению (HTTP/1.1, keep-alive). Перенаправления — как у
/// java.net.http NORMAL (не с https на http). Сжатие выключено: байты диапазона должны совпадать с файлом.
namespace suslik::core {

class HttpUpstream {
public:
    HttpUpstream();
    /// Сколько ждать ответа (соединение, TLS, заголовки), мс; дальше — IoError(Timeout).
    void setAnswerTimeoutMs(qint64 ms);
    /// Для BurstProxy::init. Держит состояние этого объекта: живёт, пока жив хоть один Upstream.
    Upstream upstream() const;
    std::shared_ptr<UpstreamResponse> open(const QString &url, qint64 from, qint64 to, Cancel &cancel) const;
    /// Бросить соединения пула во всех потоках (BurstProxy::setResetConnections): каждый поток берёт новый
    /// менеджер при следующем запросе.
    void resetConnections();

    struct State;
private:
    std::shared_ptr<State> d;
};

} // namespace suslik::core
