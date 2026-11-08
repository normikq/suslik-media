#pragma once
#include "QuadMath.h"

#include <QMatrix4x4>
#include <QObject>
#include <QSizeF>
#include <QTransform>
#include <QVariantList>

namespace suslik::cinema {

/// Углы экрана на окне-оверлее и матрица для кадра. Углы хранятся в долях окна (0…1): переживают смену разрешения.
/// Сохраняются в screens.json папки данных (на этапе 2 углы будет присылать мод из игры).
class ScreenQuad : public QObject {
    Q_OBJECT
    /// [QPointF] в долях окна, ЛВ, ПВ, ПН, ЛН.
    Q_PROPERTY(QVariantList corners READ corners NOTIFY changed)
    /// Размер окна в логических пикселях (его задаёт QML).
    Q_PROPERTY(QSizeF viewSize READ viewSize WRITE setViewSize NOTIFY changed)
    /// Размер кадра до преобразования (в нём рисуется видео; буфер mpv того же размера). С игрой его подбирает
    /// GameLink по размеру экрана на мониторе, без игры — 1920×1080.
    Q_PROPERTY(double sourceWidth READ sourceWidth NOTIFY sourceSizeChanged)
    Q_PROPERTY(double sourceHeight READ sourceHeight NOTIFY sourceSizeChanged)
    Q_PROPERTY(QMatrix4x4 matrix READ matrix NOTIFY changed)
    Q_PROPERTY(bool valid READ valid NOTIFY changed)

public:
    explicit ScreenQuad(QObject *parent = nullptr);

    QVariantList corners() const;
    QSizeF viewSize() const { return m_view; }
    void setViewSize(const QSizeF &s);
    double sourceWidth() const { return m_srcW; }
    double sourceHeight() const { return m_srcH; }
    void setSourceSize(double w, double h);
    QMatrix4x4 matrix() const { return m_matrix; }
    bool valid() const { return m_valid; }

    /// Угол i (0…3) в долях окна. Вывернутый четырёхугольник не принимается.
    Q_INVOKABLE bool setCorner(int i, double fx, double fy);
    /// Все углы в пикселях окна (команда set_rect от игры).
    bool setCornersPx(const Quad &px);
    /// Углы от игры (доли окна) в каждом кадре: не сохраняются, вывернутый четырёхугольник не принимается.
    bool setLiveFrac(const Quad &frac);
    /// Преобразование от игры: кадр (0,0)-(sourceWidth,sourceHeight) -> доли окна (однородное, w — глубина).
    void setLiveTransform(const QTransform &toFrac);
    /// Прямоугольник по центру с наклоном — чтобы сразу было видно перспективу.
    Q_INVOKABLE void reset();
    Q_INVOKABLE bool save() const;
    bool load();

signals:
    void changed();
    void sourceSizeChanged();

private:
    void recompute();

    Quad m_frac;
    QSizeF m_view;
    double m_srcW = 1920, m_srcH = 1080;
    QMatrix4x4 m_matrix;
    bool m_valid = false;
};

} // namespace suslik::cinema
