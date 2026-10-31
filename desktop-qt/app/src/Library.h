#pragma once
#include "suslik/core/Auth.h"
#include "suslik/core/Model.h"

#include <QHash>
#include <QJsonObject>
#include <QObject>
#include <QSet>
#include <QTimer>
#include <QVariantMap>
#include <deque>
#include <functional>
#include <optional>

namespace suslik::core {
class ApiClient;
class CloudSync;
class Store;
}

/// Медиатека для интерфейса: вход, список раздач, постеры, названия, категории, прогресс. Перенос desktop/.../Library.kt
/// (то же поведение, те же файлы-хранилища). Все вызовы — в главном потоке; ответы сервера приходят колбэками.
namespace suslik::app {

using namespace suslik::core;

/// Категории боковой панели («Коллекция»).
enum class Category { All = 0, Movies, Series, Anime, Cartoons, Other };

class Library : public QObject {
    Q_OBJECT
    /// −1 — ещё не знаем (проверяем сессию), 0 — нужен вход, 1 — вошли.
    Q_PROPERTY(int authenticated READ authenticated NOTIFY authenticatedChanged)
    Q_PROPERTY(bool loading READ loading NOTIFY statusChanged)
    Q_PROPERTY(QString status READ status NOTIFY statusChanged)
    /// 0 — сведения, 1 — готово, 2 — ошибка.
    Q_PROPERTY(int statusKind READ statusKind NOTIFY statusChanged)
    Q_PROPERTY(QString profileName READ profileName NOTIFY profileChanged)
    Q_PROPERTY(QString profileNote READ profileNote NOTIFY profileChanged)
    Q_PROPERTY(QString avatarUrl READ avatarUrl NOTIFY profileChanged)
    /// Код устройства на экране входа (пусто — не ждём подтверждения).
    Q_PROPERTY(QString pairingCode READ pairingCode NOTIFY pairingChanged)
    Q_PROPERTY(QString pairingUrl READ pairingUrl NOTIFY pairingChanged)
    Q_PROPERTY(bool aiBusy READ aiBusy NOTIFY aiChanged)
    /// Растёт при любом изменении того, что показывают плитки (список, прогресс, постеры, названия, категории).
    Q_PROPERTY(int version READ version NOTIFY versionChanged)
    Q_PROPERTY(int count READ count NOTIFY versionChanged)

public:
    Library(ApiClient &api, CloudSync &sync, QObject *parent = nullptr);

    int authenticated() const { return m_auth; }
    bool loading() const { return m_loading; }
    QString status() const { return m_status; }
    int statusKind() const { return m_statusKind; }
    QString profileName() const { return m_profileName; }
    QString profileNote() const { return m_profileNote; }
    QString avatarUrl() const { return m_avatar; }
    QString pairingCode() const { return m_pairing ? m_pairing->userCode : QString(); }
    QString pairingUrl() const { return m_pairing ? m_pairing->url : QString(); }
    bool aiBusy() const { return m_aiBusy; }
    int version() const { return m_version; }
    int count() const { return int(m_items.size()); }
    qint64 aiReadyAt() const { return m_aiReadyAt; }

    const QList<LibraryItem> &items() const { return m_items; }
    const LibraryItem *item(qint64 id) const;
    /// Раздачи только с озвучкой: в медиатеке их нет, предпросмотру «Озвучки» нужны их файлы.
    const QList<Magnet> &voiceOvers() const { return m_voiceOvers; }

    // ---- запуск и вход ----
    /// Сохранённая копия, проверка сессии, загрузка; потом в фоне — категории, отметки, баннеры.
    Q_INVOKABLE void start();
    Q_INVOKABLE void login(const QString &password);
    Q_INVOKABLE void startDeviceAuth();
    Q_INVOKABLE void cancelDeviceAuth();
    Q_INVOKABLE void openPairingPage();
    Q_INVOKABLE void logout();
    Q_INVOKABLE void refresh();

    // ---- действия с раздачами ----
    Q_INVOKABLE void setFavorite(qint64 id, bool on);
    Q_INVOKABLE void setMine(qint64 id, bool on);
    Q_INVOKABLE void remove(qint64 id);

    // ---- «Определить через ИИ» ----
    void askAi(const QList<qint64> &ids);
    /// id → принять (true) или оставить своё название (false). Одним запросом на плитку.
    void saveAi(const QHash<qint64, bool> &decisions);

    // ---- категории и подписи ----
    Category category(const LibraryItem &item) const;
    QString typeLabel(const LibraryItem &item) const;
    QString titleOf(const LibraryItem &item) const;
    bool isCartoon(const LibraryItem &item) const;
    static bool isAnime(const LibraryItem &item);
    /// Отметки для постера: от сервера (ffprobe), а пока он не ответил — по именам.
    QStringList tagsOf(const LibraryItem &item) const;
    /// Сколько фильмов в каждой категории (на странице «Моя медиатека» — среди своих).
    Q_INVOKABLE QVariantMap categoryCounts(bool mine) const;

    // ---- постеры ----
    /// Путь картинки сервера (/api/poster/img/…) для плитки, если уже известен; иначе запрашивает и потом повышает version.
    QString posterPath(const QStringList &queries);
    /// Путь постера, по которому одинаковые фильмы с разными названиями сводятся в одну плитку.
    std::optional<QString> posterPathOf(const LibraryItem &item) const;
    /// Картинка сервера: с диска или скачать (и положить на диск). Пусто — нет.
    void image(const QString &path, std::function<void(const QByteArray &)> cb);

    /// «Продолжить просмотр»: широкий кадр, описание, год, оценка, логотип названия.
    struct Hero {
        QString backdrop, overview, year, logo, title;
        double rating = 0;
    };
    std::optional<Hero> cachedHero(const QStringList &queries, int season) const;
    void requestHero(const QStringList &queries, int season);

    // ---- строка о видео («1920×1080 · 12 Мбит/с») ----
    std::optional<QString> cachedMediaLine(qint64 id, const MFile &f) const;
    void requestMediaLine(qint64 id, const MFile &f);

    // ---- прогресс ----
    std::optional<WatchInfo> watchInfo(const QString &key) const;
    std::optional<std::pair<MFile, WatchInfo>> lastWatched(const Magnet &m) const;
    qint64 continueHiddenAt(qint64 id) const;
    Q_INVOKABLE void hideContinue(const QVariantList &ids);
    std::optional<QString> lastEpisode(qint64 id) const;
    void saveEpisode(qint64 id, const QString &path);
    /// Прогресс изменился (плеер, другое устройство): плитки пересчитываются.
    Q_INVOKABLE void bumpWatch() { bump(); }

    /// Чистка папки постеров: старше 7 дней и сверх 100 МБ.
    void prunePosterFiles();

signals:
    void authenticatedChanged();
    void statusChanged();
    void profileChanged();
    void pairingChanged();
    void aiChanged();
    void versionChanged();
    /// Ответ входа по паролю или кода устройства: пусто — удалось.
    void loginFinished(const QString &error);
    /// Ошибка действия с плиткой (избранное, удаление…).
    void actionFailed(const QString &error);
    /// Окно ответов ИИ: эти раздачи (или только сообщение).
    void aiPanelRequested(const QVariantList &ids, const QString &message);
    void heroReady();

private:
    void bump();
    void setStatus(const QString &s, int kind);
    void setAuth(int a);
    void publish(const QList<Magnet> &list);
    void restoreSnapshot();
    void checkSession(std::function<void(std::optional<bool>)> done);
    void applyProfile(const std::optional<UserProfile> &u);
    void pollDeviceAuth();
    void cleanUp();
    void classifyAnimation();
    void refreshTags(int round);
    void afterSignIn();
    void setAi(const QHash<qint64, std::optional<AiTitle>> &answers);
    void noteTitle(const QString &key, const QString &t);
    bool titleKnown(const QString &key);
    void recordTitle(const QString &key, const QJsonObject &j);
    void recordAnimation(const QString &key, const QJsonObject &j);
    void notePoster(const QString &key, const QString &path);
    QString posterFile(const QString &path) const;
    void lookupPoster(const QString &query);
    void runMediaQueue();
    void updateMagnet(qint64 id, const std::function<void(Magnet &)> &f);

    ApiClient &m_api;
    CloudSync &m_sync;
    Store &m_libraryEtag, &m_posterIndex, &m_animIndex, &m_titleIndex, &m_mediaIndex, &m_episodes, &m_watch, &m_hidden;

    QList<LibraryItem> m_items;
    QList<Magnet> m_voiceOvers;
    int m_auth = -1;
    bool m_loading = false;
    QString m_status;
    int m_statusKind = 0;
    QString m_profileName, m_profileNote, m_avatar;
    std::optional<DevicePairing> m_pairing;
    QTimer m_pollTimer;
    qint64 m_aiReadyAt = 0;
    bool m_aiBusy = false;
    int m_version = 0;
    bool m_bumpQueued = false;

    QHash<QString, bool> m_animation;           // запрос постера (нижний регистр) → TMDB: Animation
    QHash<QString, QString> m_posterPaths;      // запрос → путь картинки
    QHash<QString, QString> m_ruTitles;         // запрос → русское название TMDB
    QHash<qint64, QStringList> m_serverTags;    // id → отметки сервера
    QSet<QString> m_posterAsked;                // запрос уже ушёл (или ответ известен в этом запуске)
    QHash<QString, std::vector<std::function<void(const QByteArray &)>>> m_imageWaiters;
    QHash<QString, Hero> m_heroes;
    QSet<QString> m_heroAsked;
    QSet<QString> m_mediaAsked;
    std::deque<std::pair<qint64, MFile>> m_mediaQueue;
    bool m_mediaBusy = false;
    QStringList m_classifyTodo;
    int m_tagsRound = 0;
};

} // namespace suslik::app
