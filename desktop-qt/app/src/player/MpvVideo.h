#pragma once
#include "MpvEngine.h"

#include <QPointer>
#include <QQuickFramebufferObject>

namespace suslik::app {

/// Видео в сцене QML: нативный рендеринг mpv в OpenGL Framebuffer Object Qt Quick.
/// Единый OpenGL контекст исключает междрайверные накладные расходы и гарантирует минимальное потребление ОЗУ.
class MpvVideo : public QQuickFramebufferObject {
    Q_OBJECT
    Q_PROPERTY(suslik::app::MpvEngine *engine READ engine WRITE setEngine NOTIFY engineChanged)

public:
    explicit MpvVideo(QQuickItem *parent = nullptr);

    MpvEngine *engine() const { return m_engine; }
    void setEngine(MpvEngine *e);

    Renderer *createRenderer() const override;

signals:
    void engineChanged();

private:
    QPointer<MpvEngine> m_engine;
};

} // namespace suslik::app
