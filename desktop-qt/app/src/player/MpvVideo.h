#pragma once
#include "MpvEngine.h"

#include <QPointer>
#include <QQuickFramebufferObject>

/// Видео в сцене QML (MpvVideo.kt): mpv рисует кадр прямо в кадровый буфер этого элемента (render API, OpenGL),
/// панели и оверлеи — обычные элементы поверх. Окно рисуется через OpenGL (main.cpp).
namespace suslik::app {

class MpvVideo : public QQuickFramebufferObject {
    Q_OBJECT
    Q_PROPERTY(suslik::app::MpvEngine *engine READ engine WRITE setEngine NOTIFY engineChanged)

public:
    explicit MpvVideo(QQuickItem *parent = nullptr);
    Renderer *createRenderer() const override;

    MpvEngine *engine() const { return m_engine; }
    void setEngine(MpvEngine *e);

signals:
    void engineChanged();

private:
    QPointer<MpvEngine> m_engine;
};

} // namespace suslik::app
