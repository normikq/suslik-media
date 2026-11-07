#pragma once
#include "QuadMath.h"

#include <QMatrix4x4>
#include <QObject>
#include <QSizeF>
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
    /// Размер кадра до преобразования (в нём рисуются видео и тестовая картинка).
    Q_PROPERTY(double sourceWidth READ sourceWidth CONSTANT)
    Q_PROPERTY(double sourceHeight READ sourceHeight CONSTANT)
    Q_PROPERTY(QMatrix4x4 matrix READ matrix NOTIFY changed)
    Q_PROPERTY(bool valid READ valid NOTIFY changed)

public:
    explicit ScreenQuad(QObject *parent = nullptr);

    QVariantList corners() const;
    QSizeF viewSize() const { return m_view; }
    void setViewSize(const QSizeF &s);
    double sourceWidth() const { return 1920; }
    double sourceHeight() const { return 1080; }
    QMatrix4x4 matrix() const { return m_matrix; }
    bool valid() const { return m_valid; }

    /// Угол i (0…3) в долях окна. Вывернутый четырёхугольник не принимается.
    Q_INVOKABLE bool setCorner(int i, double fx, double fy);
    /// Все углы в пикселях окна (команда set_rect от игры).
    bool setCornersPx(const Quad &px);
    /// Прямоугольник по центру с наклоном — чтобы сразу было видно перспективу.
    Q_INVOKABLE void reset();
    Q_INVOKABLE bool save() const;
    bool load();

signals:
    void changed();

private:
    void recompute();

    Quad m_frac;
    QSizeF m_view;
    QMatrix4x4 m_matrix;
    bool m_valid = false;
};

} // namespace suslik::cinema
