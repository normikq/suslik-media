#pragma once
#include <QObject>
#include <QPointer>
#include <QTimer>

class QWindow;

/// Плеер для внешнего мира: системные медиа-кнопки Windows, запрет сна и активность в Discord — по состоянию
/// PlayerModel (PlayerView.kt: эффекты MediaControls, DisplayAwake, DiscordRpc).
namespace suslik::app {

class PlayerModel;
class Library;
class MediaControls;
class DiscordRpc;

class MediaSession : public QObject {
    Q_OBJECT
public:
    MediaSession(PlayerModel &player, Library &lib, QObject *parent = nullptr);
    void attach(QWindow *w);

private:
    void onOpen();
    void onEntry();
    void onState();
    void scheduleDiscord();
    void sendDiscord();
    void preparePoster();

    PlayerModel &m_player;
    Library &m_lib;
    MediaControls *m_smtc;
    DiscordRpc *m_discord;
    QTimer m_discordDelay;
    qint64 m_lastSec = -1;
    QString m_posterFile, m_posterUrl;
    int m_posterGen = 0;
};

} // namespace suslik::app
