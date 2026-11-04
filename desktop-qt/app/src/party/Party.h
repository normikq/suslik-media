#pragma once
#include "suslik/core/Party.h"
#include "suslik/core/Safety.h"

#include <QObject>
#include <QPointer>
#include <QTimer>
#include <QVariantList>

class QNetworkReply;

namespace suslik::core { class ApiClient; struct ApiResult; }

/// Совместный просмотр (party/Party.kt): одна комната на программу. Сервер (`/api/rooms`) хранит, что и где играет:
/// playing, pos (с) в момент anchor_server_ms и скорость; изменения приходят потоком событий (SSE).
/// Фильм комнаты — ключ просмотра `magnetId|путь` (тот же, что у прогресса в медиатеке): у всех одна общая медиатека.
/// Данные комнаты, разбор ответов и потока, права — в ядре (core/Party.h); здесь — сеть и свойства для QML.
namespace suslik::app {

class Party : public QObject, public core::PartyLink {
    Q_OBJECT
    Q_PROPERTY(bool inRoom READ inRoom NOTIFY changed)
    Q_PROPERTY(QString roomId READ roomId NOTIFY changed)
    Q_PROPERTY(bool isHost READ isHost NOTIFY changed)
    /// В комнате и не ведущий: медиатека закрыта, фильм ведущего открывается сам.
    Q_PROPERTY(bool guest READ guest NOTIFY changed)
    Q_PROPERTY(bool connected READ connected NOTIFY changed)
    Q_PROPERTY(QString notice READ notice NOTIFY changed)
    Q_PROPERTY(QString hostName READ hostName NOTIFY changed)
    Q_PROPERTY(bool hostOnly READ hostOnly NOTIFY changed)
    Q_PROPERTY(QString fileKey READ fileKey NOTIFY changed)
    Q_PROPERTY(QString fileTitle READ fileTitle NOTIFY changed)
    Q_PROPERTY(bool playing READ playing NOTIFY changed)
    Q_PROPERTY(bool waiting READ waiting NOTIFY changed)
    Q_PROPERTY(QVariantList members READ members NOTIFY changed)
    Q_PROPERTY(int memberCount READ memberCount NOTIFY changed)
    Q_PROPERTY(QString followed READ followed WRITE setFollowed NOTIFY changed)
    Q_PROPERTY(bool busy READ busy NOTIFY changed)
    Q_PROPERTY(QString error READ error NOTIFY changed)

public:
    explicit Party(core::ApiClient &api, QObject *parent = nullptr);
    ~Party() override;
    /// Одна на программу (создаётся в main); нет — nullptr.
    static Party *instance() { return s_instance; }

    bool inRoom() const { return m_roomId.has_value(); }
    QString roomId() const { return m_roomId.value_or(QString()); }
    bool isHost() const { return m_room.isHost(m_myUserId, m_myKey); }
    bool guest() const { return inRoom() && !isHost(); }
    bool connected() const { return m_connected; }
    QString notice() const { return m_notice; }
    QString hostName() const { return m_room.hostName; }
    bool hostOnly() const { return m_room.hostOnly; }
    QString fileKey() const { return m_room.file ? m_room.file->key : QString(); }
    QString fileTitle() const { return m_room.file ? m_room.file->title : QString(); }
    const std::optional<core::PartyFileRef> &file() const { return m_room.file; }
    bool playing() const { return m_room.state.playing; }
    bool waiting() const { return m_room.state.waiting; }
    QVariantList members() const;
    int memberCount() const { return int(m_room.members.size()); }
    const QList<core::PartyMember> &memberList() const { return m_room.members; }
    QString myKey() const { return m_myKey; }
    QString followed() const { return m_followed; }
    void setFollowed(const QString &k) { if (m_followed != k) { m_followed = k; emit changed(); } }
    bool busy() const { return m_busy; }
    QString error() const { return m_error; }

    Q_INVOKABLE void create();
    Q_INVOKABLE void join(const QString &code);
    /// Ссылка suslik://room/<код> (Discord Activity): войти в эту комнату (из другой — выйдя), гостю — сразу фильм
    /// ведущего, и показать страницу «Вместе» (roomLinkOpened).
    void openLink(const QString &link);
    Q_INVOKABLE void leave();
    /// Где комната сейчас, мс (по якорю сервера и откалиброванным часам).
    Q_INVOKABLE qint64 roomMs() const { return targetMs(); }
    /// Строка состояния фильма для страницы комнаты: «Идёт · 12:34», «Ждём · у кого: …».
    Q_INVOKABLE QString stateLine() const;
    /// Кто ведущий, для подсказок плеера: имя или «Создатель комнаты».
    QString hostWho() const;

    /// Ведущий ставит фильм в комнату (на паузе в начале, как делает сервер); его плеер её запустит.
    void putFilm(const core::PartyFileRef &f);
    /// Плееры, показывающие фильм комнаты здесь (PartySync).
    int watching = 0;
    /// Ведущий закрыл фильм: через 1,5 с, если никто его здесь не показывает, комната остаётся без фильма.
    void closeIfNobodyWatches();

    // core::PartyLink
    core::PartyState state() const override { return m_room.state; }
    bool canControl() const override { return inRoom() && isHost(); }
    bool canSeek() const override { return inRoom() && isHost(); }
    qint64 targetMs() const override;
    bool othersBuffering() const override { return m_room.othersBuffering(m_myKey); }
    bool takeStartedByMe(const QString &key) override;
    void play(qint64 posMs) override { action(core::PartyProtocol::play(posMs)); }
    void pause(qint64 posMs) override { action(core::PartyProtocol::pause(posMs)); }
    void seek(qint64 posMs) override { if (canSeek()) action(core::PartyProtocol::seek(posMs)); }
    void reportBuffering(bool loading) override;
    void reportBuffering(bool loading, const QString &variantKey);

signals:
    void changed();
    /// Вошли по ссылке из Discord: окну — открыть «Вместе» (ведущему — и фильм комнаты, если он уже выбран).
    void roomLinkOpened();
    /// Сервер комнаты сообщил о смене фильма: обновить медиатеку
    void refreshLibraryRequested();

private:
    using Done = std::function<void(const core::ApiResult &)>;
    void timedGet(const QString &path, Done done);
    void timedPost(const QString &path, const QJsonObject &body, Done done);
    void sample(qint64 t0, const core::ApiResult &r);
    void enter(const core::RoomUpdate &u);
    void startStream(const QString &id);
    void stopStream();
    void retryStream(const QString &id);
    void onEvent(const core::PartyEvent &e);
    /// Discord Activity просит этот ПК поискать на RuTracker или добавить найденную раздачу (website/src/services/pc-search.js)
    void onRemoteRequest(const core::RemoteTrackerRequest &rq);
    void action(const QJsonObject &body);
    void pump();
    void setRoom(const core::PartyRoom &r) { m_room = r; emit changed(); }
    void finishRun(const QString &error);
    void joinThen(const QString &code, std::function<void()> after);

    static inline Party *s_instance = nullptr;
    core::ApiClient &m_api;
    core::PartyRoom m_room;
    std::optional<QString> m_roomId;
    std::optional<QString> m_myUserId;
    const QString m_clientId;
    const QString m_myKey;
    core::PartyClock m_clock;
    bool m_connected = false;
    QString m_notice, m_followed;
    std::optional<QString> m_startedByMe;
    bool m_reportedBuffering = false;
    QString m_reportedVariantKey;
    bool m_busy = false;
    QString m_error;

    QList<std::pair<QString, QJsonObject>> m_outbox;
    bool m_sending = false;

    QPointer<QNetworkReply> m_stream;
    QByteArray m_streamBuf;
    int m_failures = 0;
    int m_streamGen = 0;
    QTimer m_clockTimer;
    /// Запросы участников к RuTracker через этот ПК (с его cookie): поиск и .torrent — не чаще, чем живой человек.
    core::Safety::RateLimit m_remoteSearches{10, 10 * 60 * 1000};
    core::Safety::RateLimit m_remoteTorrents{5, 10 * 60 * 1000};
};

} // namespace suslik::app
