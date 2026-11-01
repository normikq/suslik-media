#include "suslik/core/ApiClient.h"
#include "suslik/core/Auth.h"
#include "suslik/core/SiteAddress.h"
#include "suslik/core/Store.h"
#include "suslik/core/Strings.h"
#include "suslik/core/UrlCoding.h"

#include <QElapsedTimer>
#include <QJsonDocument>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QRegularExpression>

namespace suslik::core {

namespace {
constexpr int TIMEOUT_MS = 30000;
constexpr int TEXT_TIMEOUT_MS = 20000;
Store &cookies() { return Store::of(QStringLiteral("cookies")); }
Store &auth() { return Store::of(QStringLiteral("auth")); }
} // namespace

ApiClient::ApiClient(const QString &base, QObject *parent) : QObject(parent), m_base(base), m_nam(new QNetworkAccessManager(this)) {
    m_nam->setRedirectPolicy(QNetworkRequest::NoLessSafeRedirectPolicy);
    // Cookie ведём сами (Store), у менеджера своей банки нет
    m_nam->setCookieJar(nullptr);
}

QString ApiClient::host() const { return SiteAddress::host(m_base); }

std::optional<QString> ApiClient::deviceToken() const { return auth().get(QStringLiteral("device_token")); }

void ApiClient::setDeviceToken(const std::optional<QString> &token) { auth().put(QStringLiteral("device_token"), token); }

QString ApiClient::cookieHeader() const {
    QStringList parts;
    Store &c = cookies();
    QStringList keys = c.keys();
    keys.sort();
    for (const QString &k : keys) parts << k + QLatin1Char('=') + c.get(k).value_or(QString());
    return parts.join(QStringLiteral("; "));
}

void ApiClient::storeCookies(const QList<QByteArray> &setCookieValues) {
    static const QRegularExpression expiredRe(QStringLiteral("max-age=0|expires=Thu, 01 Jan 1970"), QRegularExpression::CaseInsensitiveOption);
    for (const QByteArray &raw : setCookieValues) {
        const QString c = QString::fromUtf8(raw);
        const QString pair = c.section(QLatin1Char(';'), 0, 0);
        const auto eq = pair.indexOf(QLatin1Char('='));
        const QString name = (eq < 0 ? pair : pair.left(eq)).trimmed();
        const QString value = eq < 0 ? QString() : pair.mid(eq + 1).trimmed();
        if (name.isEmpty()) continue;
        const bool expired = expiredRe.match(c).hasMatch();
        cookies().put(name, expired || value.isEmpty() ? std::nullopt : std::optional<QString>(value));
    }
}

QNetworkRequest ApiClient::request(const QString &pathOrUrl) const {
    const QString url = pathOrUrl.startsWith(QLatin1String("http")) ? pathOrUrl : m_base + pathOrUrl;
    QNetworkRequest req{QUrl(url)};
    req.setTransferTimeout(TIMEOUT_MS);
    req.setAttribute(QNetworkRequest::CookieLoadControlAttribute, QNetworkRequest::Manual);
    req.setAttribute(QNetworkRequest::CookieSaveControlAttribute, QNetworkRequest::Manual);
    // Сессия, токен устройства и бета-код — только своему серверу (аватар идёт с CDN Discord)
    if (DeviceAuth::isOwnServer(url, m_base)) {
        if (const QString c = cookieHeader(); !c.isEmpty()) req.setRawHeader("Cookie", c.toUtf8());
        if (const auto t = deviceToken()) req.setRawHeader(DeviceAuth::TOKEN_HEADER, t->toUtf8());
        if (m_betaCode)
            if (const auto b = m_betaCode()) req.setRawHeader("X-Beta-Code", b->toUtf8());
    }
    return req;
}

void ApiClient::get(const QString &path, Callback cb) { run(request(path), "GET", {}, Mode::Json, std::move(cb)); }

void ApiClient::post(const QString &path, const QJsonObject &body, Callback cb) {
    QNetworkRequest req = request(path);
    req.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/json"));
    run(req, "POST", QJsonDocument(body).toJson(QJsonDocument::Compact), Mode::Json, std::move(cb));
}

void ApiClient::getIfChanged(const QString &path, const std::optional<QString> &etag, Callback cb) {
    QNetworkRequest req = request(path);
    if (etag) req.setRawHeader("If-None-Match", etag->toUtf8());
    run(req, "GET", {}, Mode::Json, std::move(cb));
}

void ApiClient::postTorrent(const QByteArray &bytes, const QString &filename, Callback cb) {
    QNetworkRequest req = request(QStringLiteral("/api/torrents"));
    req.setHeader(QNetworkRequest::ContentTypeHeader, QStringLiteral("application/x-bittorrent"));
    req.setRawHeader("X-Torrent-Filename", formEncode(filename).replace(QLatin1Char('+'), QStringLiteral("%20")).toUtf8());
    run(req, "POST", bytes, Mode::Json, std::move(cb));
}

void ApiClient::bytes(const QString &path, Callback cb) { run(request(path), "GET", {}, Mode::Bytes, std::move(cb)); }

void ApiClient::text(const QString &url, Callback cb) {
    QNetworkRequest req{QUrl(url)};
    req.setTransferTimeout(TEXT_TIMEOUT_MS);
    req.setAttribute(QNetworkRequest::CookieLoadControlAttribute, QNetworkRequest::Manual);
    req.setAttribute(QNetworkRequest::CookieSaveControlAttribute, QNetworkRequest::Manual);
    run(req, "GET", {}, Mode::Text, std::move(cb));
}

void ApiClient::send(QNetworkRequest req, const QByteArray &verb, const QByteArray &body, Callback cb) {
    run(std::move(req), verb, body, Mode::Json, std::move(cb));
}

void ApiClient::run(QNetworkRequest req, const QByteArray &verb, const QByteArray &body, Mode mode, Callback cb) {
    // gzip QNetworkAccessManager просит и разжимает сам
    QElapsedTimer timer;
    timer.start();
    QNetworkReply *reply = m_nam->sendCustomRequest(req, verb, body);
    const bool ownCookies = mode != Mode::Text;
    connect(reply, &QNetworkReply::finished, this, [this, reply, verb, mode, ownCookies, timer, cb = std::move(cb)] {
        reply->deleteLater();
        ApiResult r;
        const QByteArray data = reply->readAll();
        const QVariant status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute);
        r.code = status.isValid() ? status.toInt() : 0;
        const auto netError = reply->error();
        const QString path = reply->url().path();

        if (r.code == 0) {
            // Нет ответа: сеть, тайм-аут, отмена
            r.error.kind = (netError == QNetworkReply::TimeoutError || netError == QNetworkReply::OperationCanceledError) ? ApiError::Kind::Timeout
                         : netError == QNetworkReply::UnknownNetworkError ? ApiError::Kind::Unknown
                                                                          : ApiError::Kind::Network;
            r.error.message = describe(r.error);
            if (m_log) m_log(QString::fromLatin1(verb), path, 0, timer.elapsed(), 0, reply->errorString());
            cb(r);
            return;
        }
        if (ownCookies) {
            QList<QByteArray> setCookies;
            for (const auto &[name, value] : reply->rawHeaderPairs())
                if (name.compare("set-cookie", Qt::CaseInsensitive) == 0) setCookies << value.split('\n');
            storeCookies(setCookies);
        }
        if (m_log) m_log(QString::fromLatin1(verb), path, r.code, timer.elapsed(), data.size(), QString());

        switch (mode) {
        case Mode::Text:
            if (r.code == 200) r.bytes = data;
            else { r.error.kind = ApiError::Kind::Http; r.error.code = r.code; r.error.message = describe(r.error); }
            break;
        case Mode::Bytes:
            if (r.code == 200) r.bytes = data;
            else if (r.code != 404 && r.code != 410) {
                r.error.kind = ApiError::Kind::Http;
                r.error.code = r.code;
                r.error.message = tx(QStringLiteral("api.oshibka_servera_code"), {{"code", QString::number(r.code)}});
            }
            break;
        case Mode::Json:
            if (r.code == 304) break; // не изменилось: тела нет
            r.json = QJsonDocument::fromJson(data).object();
            if (r.code < 200 || r.code > 299) {
                r.error.kind = ApiError::Kind::Http;
                r.error.code = r.code;
                r.error.body = r.json;
                const QString msg = r.json.value(QLatin1String("error")).toString();
                r.error.message = msg.trimmed().isEmpty() ? tx(QStringLiteral("api.oshibka_servera_code"), {{"code", QString::number(r.code)}}) : msg;
            } else if (reply->hasRawHeader("ETag")) {
                r.etag = QString::fromUtf8(reply->rawHeader("ETag"));
            }
            break;
        }
        cb(r);
    });
}

QString ApiClient::describe(const ApiError &e) {
    switch (e.kind) {
    case ApiError::Kind::Http:
        return e.message.isEmpty() ? tx(QStringLiteral("api.oshibka_servera")) : e.message;
    case ApiError::Kind::Network:
        return tx(QStringLiteral("api.net_svyazi_s_serverom"));
    case ApiError::Kind::Timeout:
        return tx(QStringLiteral("api.server_ne_otvetil_vovremya"));
    case ApiError::Kind::Unknown:
    case ApiError::Kind::None:
        break;
    }
    return e.message.isEmpty() ? tx(QStringLiteral("api.neizvestnaya_oshibka")) : e.message;
}

} // namespace suslik::core
