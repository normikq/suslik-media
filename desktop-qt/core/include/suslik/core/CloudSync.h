#pragma once
#include "suslik/core/WatchSync.h"

#include <QObject>
#include <QTimer>
#include <memory>

/// Прогресс и выбранные озвучка/субтитры — на аккаунт Discord (WatchSync, сервер /api/sync): продолжить на телефоне
/// или ТВ с того же места и с той же озвучкой. При входе по общему паролю не работает. Своё уходит при закрытии плеера
/// (во время просмотра — раз в 2 минуты), чужое забирается раз в 5 минут. Перенос desktop/.../CloudSync.kt.
namespace suslik::core {

class ApiClient;

/// Хранилище приложения для WatchSync: Store "watch" ("magnetId|path" → "pos|len|at"), "player_ui" ("audio:m42",
/// "spu:m42", "trackAt:m42"), "sync" (курсор).
class DesktopSyncStore : public SyncStore {
public:
    std::optional<WatchEntry> watch(const QString &key) override;
    void putWatch(const WatchEntry &e) override;
    qint64 trackAt(const QString &key) override;
    void putTrack(const TrackEntry &e) override;
    QList<WatchEntry> allWatch() override;
    /// Выбор, сделанный до синхронизации, без своего времени: считаем его временем последнего просмотра этого фильма.
    QList<TrackEntry> allTracks() override;
    std::optional<QString> cursor() override;
    void setCursor(const std::optional<QString> &c) override;
};

class CloudSync : public QObject {
    Q_OBJECT
public:
    CloudSync(ApiClient &api, QObject *parent = nullptr);
    ~CloudSync() override;

    /// Пользователь сервера ("u_<id>" — вход через Discord).
    void setUserId(const std::optional<QString> &id);
    std::optional<QString> userId() const { return m_user; }

    /// Плеер сохранил прогресс (уже записан в "watch").
    void noteWatch(const QString &key, qint64 pos, qint64 len, qint64 at);
    /// Выбрали дорожку (filmKey — "m<id>"; общий выбор "*" отмечается здесь же). Уже записано в "player_ui".
    void noteTrack(const QString &filmKey, const std::optional<QString> &audio, const std::optional<QString> &spu,
                   const std::optional<QString> &cid = std::nullopt, const std::optional<QString> &quality = std::nullopt);
    /// Отправить сейчас (закрыли плеер, вошли).
    void kick();
    void start();

signals:
    /// Пришло новое с другого устройства.
    void changed(int entries);
    void log(const QString &line);

private:
    void tick();
    void round(const QString &user, int rounds, int changedSoFar, qint64 startedAt);

    ApiClient &m_api;
    DesktopSyncStore m_store;
    std::unique_ptr<WatchSync> m_sync;
    std::optional<QString> m_user;
    QTimer m_timer;
    qint64 m_lastPull = 0;
    qint64 m_lastPush = 0;
    bool m_kicked = true;
    bool m_busy = false;
};

} // namespace suslik::core
