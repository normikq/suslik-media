#pragma once
#include "GameView.h"

#include <QElapsedTimer>
#include <QFile>
#include <QObject>
#include <QSizeF>
#include <QString>

namespace suslik::cinema {

class ScreenQuad;

/// Связь с модом SuslikCinema (docs/CYBERPUNK.md, этап 2): в каждом кадре оверлея читает state.json мода,
/// прогнозирует камеру игры на момент показа кадра и ставит углы экрана своей проекцией.
/// Работает в потоке интерфейса (QQuickWindow::afterAnimating — перед синхронизацией сцены).
class GameLink : public QObject {
    Q_OBJECT
    /// Прозрачность экрана: 0 — скрыт (вне кадра, за стеной, пауза, игра не на переднем плане).
    Q_PROPERTY(double opacity READ opacity NOTIFY opacityChanged)
    /// Связь с модом есть (кадры приходят): углы ставит игра, ручная калибровка не нужна.
    Q_PROPERTY(bool live READ live NOTIFY liveChanged)
    /// Короткая подсказка на экране (смена поправки упреждения).
    Q_PROPERTY(QString osd READ osd NOTIFY osdChanged)

public:
    explicit GameLink(ScreenQuad *quad, QObject *parent = nullptr);

    /// Папка мода: <папка exe игры>\plugins\cyber_engine_tweaks\mods\SuslikCinema.
    void setGameExePath(const QString &exePath);
    void setModDir(const QString &dir);
    QString modDir() const { return m_modDir; }

    /// Ручная поправка упреждения, мс (+ — дальше вперёд). Хранится в cinema.json.
    void setAdjustMs(int ms);
    int adjustMs() const { return m_adjustMs; }
    void nudge(int deltaMs) { setAdjustMs(m_adjustMs + deltaMs); }
    /// Сколько периодов обновления после ближайшего vblank кадр оверлея идёт до экрана (DWM).
    void setPresentFrames(int n) { m_presentFrames = n; }

    /// Окно игры на переднем плане (иначе экран прячется: оверлей «всегда сверху»).
    void setGameActive(bool on) { m_gameActive = on; }
    /// Ручная калибровка мышью: углы не трогаем.
    void setPaused(bool on) { m_paused = on; }
    /// Родное разрешение видео (ширина), 0 — тестовая картинка.
    void setVideoWidth(int w) {
        m_videoWidth = w;
        m_srcW = 0; // буфер под новый фильм
    }

    /// Запись 10 с по кадрам оверлея в cinema-rec.csv папки данных (разбор рывков экрана).
    void startRecording();

    /// Кадр оверлея: прочитать мод, поставить углы. viewSize — логический размер окна.
    void frame(const QSizeF &viewSize);

    double opacity() const { return m_opacity; }
    /// Игра на паузе (меню игры) — по state.json мода.
    bool gamePaused() const { return m_live && m_state.paused; }
    bool live() const { return m_live; }
    QString osd() const { return m_osd; }

signals:
    void opacityChanged();
    void liveChanged();
    void osdChanged();
    void gamePausedChanged();

private:
    struct State {
        qint64 seq = -1;
        bool cam = false;
        CameraPose pose;
        int W = 0, H = 0;
        bool visible = false, paused = false, cal = false;
        double occl = 0;
        double fade = 1;
        QString hide; ///< мод просит спрятать экран: weapon, wheel, user
        qint64 rev = -1;
    };
    bool readState(State &s);
    void loadScreen();
    double presentTime(double nowSec) const;
    void setOpacity(double o);
    void setLive(bool on);
    void showOsd(const QString &s);
    void logStats(double now);
    void updateSourceSize(double longestPx);

    ScreenQuad *m_quad;
    QString m_modDir, m_statePath, m_screenPath;
    State m_state;
    MotionPredictor m_pred;
    ClockMap m_clock;
    double m_lastNewSec = 0;     ///< когда пришёл последний новый кадр игры (часы хоста)
    double m_occlSmooth = 0;
    double m_fadeSmooth = 1; ///< множитель от мода: дистанция выключения, NPC перед экраном
    double m_frameDt = 1.0 / 60;
    bool m_fastTurn = false;
    double m_turnDim = 1;
    double m_opacity = 0;
    bool m_live = false;
    bool m_gameActive = true;
    bool m_paused = false;
    int m_adjustMs = -24;
    int m_presentFrames = 1;
    int m_videoWidth = 0;
    double m_srcW = 0;

    std::array<V3, 4> m_corners{};
    V3 m_normal;
    double m_aspect = 16.0 / 9.0;
    bool m_hasScreen = false;
    qint64 m_screenRev = -1;

    QString m_osd;
    QElapsedTimer m_osdTimer;

    // Статистика за 10 с для журнала: подбор упреждения по нему
    double m_statT = 0;
    int m_statFrames = 0, m_statStates = 0, m_statTorn = 0, m_statShown = 0, m_statLong = 0;
    double m_lastFrame = 0, m_statMaxGap = 0;
    double m_statLead = 0, m_statAge = 0, m_statSpeedMax = 0;
    QString m_hideReason;

    QFile m_rec;
    double m_recUntil = 0;
};

} // namespace suslik::cinema
