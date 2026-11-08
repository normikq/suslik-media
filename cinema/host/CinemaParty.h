#pragma once
#include "suslik/core/Party.h"

#include <QElapsedTimer>
#include <QJsonObject>
#include <QObject>
#include <QString>
#include <QTimer>

namespace suslik::app {
class Library;
class MpvEngine;
class Party;
}

/// Комната «Вместе» в кинотеатре: те же комнаты, что у ПК, Android и ТВ. Сеть, поток событий и права — app::Party
/// из ПК-версии, синхронизация места и паузы — общий core::PartySyncEngine (тот же, что у Android и ПК). Здесь, как
/// app::PartySync у ПК-версии, только связь с плеером хоста:
///   * ведущий включил фильм (с телефона или ключом) — он становится фильмом комнаты;
///   * гость сразу открывает фильм ведущего с места комнаты (как PartyFollow.qml у ПК), ведущий сменил фильм или
///     серию — гость за ним, ведущий закрыл — у гостя фильм тоже останавливается;
///   * пауза и перемотка у гостя заперты: ими управляет ведущий.
namespace suslik::cinema {

class CinemaSession;

class CinemaParty : public QObject, private core::SyncPlayer {
    Q_OBJECT
public:
    CinemaParty(app::Party &party, CinemaSession &session, app::MpvEngine &mpv, app::Library &lib, QObject *parent = nullptr);

    /// Команды пульта: roomCreate, roomJoin{code}, roomLeave, roomFollow (гость снова за ведущим). false — не команда комнаты.
    bool command(const QString &op, const QJsonObject &c);
    /// Поле "room" состояния для телефона.
    void status(QJsonObject &o) const;
    /// Почему пауза и перемотка недоступны (гость в фильме комнаты), иначе пусто.
    QString lockReason() const;

private:
    // core::SyncPlayer — плеер хоста в шкале раздачи ведущего (сдвиг shiftMs, как у ПК)
    qint64 timeMs() const override;
    bool busy() const override;
    qint64 engineMs() const override;
    qint64 seekStamp() const override;
    bool paused() const override;
    bool running() const override;
    float userRate() const override { return 1.f; }
    void applyPaused(bool paused) override;
    void seekTo(qint64 ms) override;
    void setRate(float rate) override;

    bool roomFilm() const;
    qint64 shiftMs() const;
    void onOpened(const QString &key);
    void onRoom();
    void follow();
    void tick();
    void setWatching(bool on);

    app::Party &m_party;
    CinemaSession &m_session;
    app::MpvEngine &m_mpv;
    app::Library &m_lib;
    core::PartySyncEngine m_engine;
    QTimer m_timer;
    QTimer m_followRetry;
    bool m_watching = false;
    QString m_lastKey;    ///< свой файл, когда он последний раз был фильмом комнаты (для «закрыл фильм»)
    QString m_followed;   ///< фильм комнаты, за которым гость уже пошёл
    QString m_refreshed;  ///< фильм комнаты, ради которого уже перечитывали медиатеку
    QString m_seenKey;
    qint64 m_seenSeq = -1; ///< seq комнаты, когда фильм увидели: изменился — ведущий сообщил своё место
    QElapsedTimer m_seenAt;
};

} // namespace suslik::cinema
