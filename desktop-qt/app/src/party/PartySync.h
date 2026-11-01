#pragma once
#include "Party.h"

#include <QObject>
#include <QTimer>

/// Держит плеер в фильме комнаты (party/PartySync.kt): сам цикл — общий core::PartySyncEngine (тот же, что у Android).
/// Работает, только пока плеер показывает фильм комнаты; иначе ничего не делает. Здесь же: ведущий включил фильм —
/// он становится фильмом комнаты; ведущий перешёл к другой серии — гость идёт за ним; ведущий закрыл фильм — у гостя
/// плеер тоже закрывается.
namespace suslik::app {

class PlayerModel;

class PartySync : public QObject, private core::SyncPlayer {
    Q_OBJECT
public:
    PartySync(PlayerModel &player, Party &party, QObject *parent = nullptr);

private:
    // core::SyncPlayer
    qint64 timeMs() const override;
    bool busy() const override;
    qint64 engineMs() const override;
    qint64 seekStamp() const override;
    bool paused() const override;
    bool running() const override;
    float userRate() const override;
    void applyPaused(bool paused) override;
    void seekTo(qint64 ms) override;
    void setRate(float rate) override;

    void onOpen();
    void onEntry();
    void onRoom();
    void tick();

    PlayerModel &m_player;
    Party &m_party;
    core::PartySyncEngine m_engine;
    QTimer m_timer;
    bool m_watching = false;
    QString m_lastKey;          ///< фильм плеера, когда он закрывался
    QString m_roomKey;          ///< фильм комнаты в прошлый раз (смена — onRoom)
    QString m_episodeNotice;    ///< ведущий включил другую серию: сказать, когда она пойдёт
    bool m_showedRoomFilm = false;
};

} // namespace suslik::app
