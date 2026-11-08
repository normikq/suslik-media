#pragma once
#include "SpatialAudio.h"

#include <QObject>
#include <QTimer>
#include <functional>

namespace suslik::app { class MpvEngine; }

/// 3D-звук фильма в mpv (SpatialAudio — расчёт, здесь — фильтры): своя цепочка lavfi «@c3d» — каждое ухо отдельно
/// (lowpass и volume), потом общая громкость; значения меняются на лету af-command раз в 50 мс со сглаживанием,
/// без пересборки цепочки (она пересобирала бы звук и щёлкала). Режим «колонки» — звук в 7.1 (стерео и 5.1 фильма
/// раскладываются по своим каналам), каждый канал — на оба уха со своими volume и lowpass, потом уши складываются.
namespace suslik::cinema {

class GameLink;

class SpatialSound : public QObject {
    Q_OBJECT
public:
    SpatialSound(app::MpvEngine &mpv, GameLink &link, QObject *parent = nullptr);

    /// Режим и «за стеной глуше» (настройки пульта).
    void setMode(Audio3D mode, bool muffle);
    /// Звук плёнки (ProjectorLook::filmSoundFilter): фильтр @film ставится перед 3D-звуком.
    void setFilmSound(int preset);
    /// Поставить цепочку: после каждого открытия файла (MpvEngine::open сбрасывает af).
    void install();

    /// Строка цепочки для mpv (открыто для проверки): стерео — экран одним источником, Speakers — 7.1 по каналам.
    static const char *filterChain();
    static const char *speakerChain();

private:
    void tick();
    void tickSpeakers();
    void send(const char *target, const char *cmd, double value);

    app::MpvEngine &m_mpv;
    GameLink &m_link;
    QTimer m_timer;
    Audio3D m_mode = Audio3D::Surround;
    bool m_muffle = true;
    int m_film = 0;
    bool m_installed = false;
    SpatialMix m_cur, m_sent;
    SpeakerMix m_spkCur, m_spkSent;
    int m_logTick = 0;
};

} // namespace suslik::cinema
