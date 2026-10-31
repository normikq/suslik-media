#pragma once
#include <QJsonObject>
#include <QObject>
#include <QPointer>
#include <optional>

class QLocalSocket;

/// Активность в Discord (DiscordRpc.kt): локальный канал discord-ipc-N без сторонних библиотек. «Смотрит»
/// (старый Discord — «Играет»), название, серия, полоса времени. ID приложения приходит с сервера
/// (discord_client_id в /api/session) и хранится в настройках; без него ничего не показывается.
namespace suslik::app {

class DiscordRpc : public QObject {
    Q_OBJECT
public:
    struct Activity {
        QString title, subtitle, largeText, posterUrl;
        bool playing = true;
        qint64 timeMs = 0, lengthMs = 0;
    };

    explicit DiscordRpc(QObject *parent = nullptr) : QObject(parent) {}
    ~DiscordRpc() override;

    void update(const Activity &a);
    void stop();

private:
    void connectPipe(int n);
    void onReady();
    void send(const QJsonValue &activity);
    void writeFrame(int op, const QJsonObject &json);
    void readFrames();
    void problem(const QString &text);
    void closePipe();
    QJsonObject build(const Activity &a) const;

    QLocalSocket *m_sock = nullptr;
    QString m_id, m_connectedAs;
    bool m_ready = false, m_triedPlain = false;
    QByteArray m_buf;
    std::optional<Activity> m_want;
    QString m_lastProblem;
};

} // namespace suslik::app
