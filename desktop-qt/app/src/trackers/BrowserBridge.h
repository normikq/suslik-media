#pragma once
#include "suslik/core/Trackers.h"

#include <QObject>
#include <QPointer>
#include <QString>
#include <functional>
#include <optional>

class QThread;

/// RuTracker с ПК через браузер, который уже стоит на компьютере (Edge, Chrome, Яндекс…). Перенос
/// desktop/.../trackers/BrowserDetector.kt и EdgeBrowserBridge.kt.
///
/// Cloudflare пропускает настоящий браузер, а не HTTP-клиент: фоновый Chromium со своим профилем запускается на
/// скрытом рабочем столе Windows (ни окна, ни значка в панели задач), команды идут по Chrome DevTools Protocol.
/// Процесс браузера — в Job Object: закрывается вместе с программой, даже если она упала. Через 15 с без
/// поиска браузер закрывается сам. Вход на трекер — в видимом окне того же профиля; cookie забираются сами.
namespace suslik::app {

enum class BrowserType { FIREFOX, EDGE, CHROME, YANDEX, OPERA, OPERA_GX, BRAVE, VIVALDI };

struct Browsers {
    static QList<BrowserType> all();
    /// Имя как у Kotlin-версии ("EDGE"): хранится в настройках.
    static QString key(BrowserType t);
    static std::optional<BrowserType> fromKey(const QString &k);
    static QString displayName(BrowserType t);
    static QString icon(BrowserType t);
    static bool isChromium(BrowserType t) { return t != BrowserType::FIREFOX; }
    /// Путь к программе браузера (обычные папки, потом App Paths в реестре); пусто — нет.
    static QString executable(BrowserType t);
};

/// Найденный браузер и профиль с cookie.
struct DetectedBrowser {
    BrowserType type = BrowserType::EDGE;
    QString profileName, cookieFile, localStateFile;
    QString title() const;
};

/// Браузеры и их профили на этом компьютере.
QList<DetectedBrowser> detectBrowsers();

class BrowserBridge : public QObject {
    Q_OBJECT
public:
    static BrowserBridge &instance();
    ~BrowserBridge() override;

    static BrowserType preferredType();
    static void setPreferredType(BrowserType t);
    static bool isAvailable(BrowserType t) { return !Browsers::executable(t).isEmpty(); }
    static bool isAvailable() { return isAvailable(preferredType()); }
    static bool isAnyAvailable();
    static QString activeBrowserName() { return Browsers::displayName(preferredType()); }
    /// Свой профиль браузера в папке данных (cookie трекера и пройденная проверка Cloudflare живут там).
    static QString profileDir(BrowserType t);

    /// Ответы приходят в главный поток, пока жив ctx.
    using Done = std::function<void()>;

    /// Поиск в разделах кино и сериалов; JSON таблицы или пусто (браузер не ответил за 25 с). error — вход или Cloudflare.
    void searchHtml(const QString &baseUrl, const QString &query, const core::CookieList &cookies, QObject *ctx,
                    std::function<void(std::optional<QString> json, QString error)> cb);
    /// .torrent через fetch() в странице раздачи; пусто — не вышло.
    void downloadTorrent(const QString &baseUrl, const QString &topicId, const core::CookieList &cookies, QObject *ctx,
                         std::function<void(QByteArray)> cb);
    /// Вошли ли (имя пользователя); обновлённые cookie — в updatedCookie.
    void checkConnection(const QString &baseUrl, const core::CookieList &cookies, QObject *ctx,
                         std::function<void(bool ok, QString message, QString updatedCookie)> cb);
    /// Сессия трекера, уже сохранённая в профиле этого браузера.
    void existingCookies(BrowserType t, const QString &baseUrl, QObject *ctx, std::function<void(std::optional<QString>)> cb);
    /// Окно браузера со страницей входа; cookie забираются, как только появится bb_session (до 3 мин).
    void loginAndCapture(BrowserType t, const QString &baseUrl, QObject *ctx, std::function<void(QString status)> onStatus,
                         std::function<void(bool ok, QString cookieOrError)> cb);
    /// Закрыть фоновый браузер, когда он больше не нужен (окно «Трекеры» закрыто).
    void stopAsync();
    /// Выход из программы: браузер закрывается сразу.
    void shutdown();

private:
    BrowserBridge();
    void post(std::function<void()> job);

    class Worker;
    QThread *m_thread = nullptr;
    Worker *m_worker = nullptr;
};

} // namespace suslik::app
