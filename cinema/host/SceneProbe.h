#pragma once
#include "SceneLight.h"

#include <QColor>
#include <QElapsedTimer>
#include <QObject>
#include <QTimer>
#include <atomic>
#include <memory>

class QWindow;

/// «Вписать в сцену» (пульт, option scene): дважды в секунду снимает уменьшенную область игры вокруг экрана (GDI, в
/// фоновом потоке), меряет свет (SceneLight) и отдаёт QML затемнение кадра, свет зала на полотне и силу отсвета.
/// Сам экран с отсветом в замер не входит (окно-оверлей поверх игры попало бы в снимок). Выключено — свойства
/// нейтральные (кадр как есть, без отсвета), шейдер «в зале» снимает Projector.
namespace suslik::cinema {

class ScreenQuad;

class SceneProbe : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool enabled READ enabled NOTIFY changed)
    Q_PROPERTY(double dim READ dim NOTIFY changed)
    Q_PROPERTY(QColor lift READ lift NOTIFY changed)
    Q_PROPERTY(double liftAmount READ liftAmount NOTIFY changed)
    Q_PROPERTY(double glow READ glow NOTIFY changed)
    /// Множитель размера отсвета (ползунок «Размер отсвета»).
    Q_PROPERTY(double spillSize READ spillSize NOTIFY changed)

public:
    SceneProbe(ScreenQuad *quad, QObject *parent = nullptr);
    ~SceneProbe() override;

    /// Окно-оверлей: снимается область игры под ним.
    void attach(QWindow *overlay) { m_window = overlay; }
    void setEnabled(bool on);
    /// Мерить сейчас (фильм на экране, экран виден, игра не на паузе); иначе держит последний свет.
    void setActive(bool on);

    bool enabled() const { return m_enabled; }
    double dim() const { return m_enabled ? tuned().dim : 1.0; }
    QColor lift() const { return m_look.lift; }
    double liftAmount() const { return m_enabled ? tuned().liftAmount : 0.0; }
    double glow() const { return m_enabled ? tuned().glow : 0.0; }
    double spillSize() const { return tuningValue(m_tuning, "spillSize"); }
    /// Ползунки вкладки «Картинка» (подстройка под зал, отсвет).
    void setTuning(const SceneTuning &t);

signals:
    void changed();

private:
    void tick();
    void apply(const SceneSample &s, double grabMs);
    SceneLook tuned() const { return tunedLook(m_look, m_tuning); }

    ScreenQuad *m_quad;
    QWindow *m_window = nullptr;
    QTimer m_timer;
    QElapsedTimer m_since;
    bool m_enabled = true;
    bool m_active = false;
    std::shared_ptr<std::atomic_bool> m_busy = std::make_shared<std::atomic_bool>(false);
    SceneSample m_scene = defaultScene();
    SceneLook m_look = sceneLookFor(defaultScene());
    int m_logged = 0;
    SceneTuning m_tuning;
    int m_grabs = 0;
    double m_grabSum = 0, m_grabMax = 0;
};

} // namespace suslik::cinema
