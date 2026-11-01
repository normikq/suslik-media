#pragma once
#include <QByteArray>
#include <QJsonObject>
#include <QNetworkRequest>
#include <QObject>
#include <QString>
#include <functional>
#include <optional>

class QNetworkAccessManager;
class QNetworkReply;

/// JSON API сайта со своими cookie (сессия и cookie устройства), сохраняемыми между запусками (Store "cookies"),
/// и токеном устройства (Store "auth"). Сессия, токен и бета-код уходят только своему серверу.
/// Перенос desktop/.../Api.kt; ответы — через колбэк в потоке, где живёт клиент.
namespace suslik::core {

struct ApiError {
    enum class Kind { None, Http, Network, Timeout, Unknown };
    Kind kind = Kind::None;
    int code = 0;            ///< HTTP-код (Kind::Http)
    QString message;         ///< для человека (tr)
    QJsonObject body;        ///< ответ сервера при ошибке HTTP
};

struct ApiResult {
    int code = 0;
    QJsonObject json;
    QByteArray bytes;              ///< bytes(): тело; пусто и code 404/410 — у сервера нет файла
    std::optional<QString> etag;
    ApiError error;
    bool ok() const { return error.kind == ApiError::Kind::None; }
};

class ApiClient : public QObject {
    Q_OBJECT
public:
    using Callback = std::function<void(const ApiResult &)>;
    /// Журнал запросов (отладочная сборка): метод, путь, код (0 — нет ответа), мс, размер, ошибка.
    using HttpLog = std::function<void(const QString &, const QString &, int, qint64, qint64, const QString &)>;

    /// base — адрес сервера (SiteAddress::normalize).
    explicit ApiClient(const QString &base, QObject *parent = nullptr);

    QString base() const { return m_base; }
    QString host() const;
    /// Страницы сайта: base + "/media/".
    QString site() const { return m_base + QStringLiteral("/media/"); }

    std::optional<QString> deviceToken() const;
    void setDeviceToken(const std::optional<QString> &token);
    /// Бета-код тестировщика (настройки): заголовок X-Beta-Code.
    void setBetaCodeProvider(std::function<std::optional<QString>()> f) { m_betaCode = std::move(f); }
    void setHttpLog(HttpLog f) { m_log = std::move(f); }

    /// Запрос к пути API или к полному адресу; cookie, токен и бета-код — только своему серверу.
    QNetworkRequest request(const QString &pathOrUrl) const;

    void get(const QString &path, Callback cb);
    void post(const QString &path, const QJsonObject &body, Callback cb);
    /// GET, на который сервер может ответить 304 (не изменилось с etag) — тогда тела нет.
    void getIfChanged(const QString &path, const std::optional<QString> &etag, Callback cb);
    /// .torrent как на сайте: сырое тело, имя в заголовке.
    void postTorrent(const QByteArray &bytes, const QString &filename, Callback cb);
    /// Файл в result.bytes; 404 / 410 — без ошибки и с пустым телом; нет сети или ошибка сервера — ошибка.
    void bytes(const QString &path, Callback cb);
    /// Текст по чужому адресу без cookie (20 с); неудача — пустой bytes и ошибка.
    void text(const QString &url, Callback cb);
    /// Любой запрос, собранный через request() (PUT, сырое тело…), ответ — JSON.
    void send(QNetworkRequest req, const QByteArray &verb, const QByteArray &body, Callback cb);

    /// Текст ошибки для человека.
    static QString describe(const ApiError &e);

    // Cookie (открыто для тестов)
    QString cookieHeader() const;
    void storeCookies(const QList<QByteArray> &setCookieValues);

    QNetworkAccessManager *network() const { return m_nam; }

private:
    enum class Mode { Json, Bytes, Text };
    void run(QNetworkRequest req, const QByteArray &verb, const QByteArray &body, Mode mode, Callback cb);

    QString m_base;
    QNetworkAccessManager *m_nam;
    std::function<std::optional<QString>()> m_betaCode;
    HttpLog m_log;
};

} // namespace suslik::core
