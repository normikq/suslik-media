#pragma once
#include <QMatrix4x4>
#include <QPointF>

#include <array>

/// Геометрия экрана кинотеатра: прямоугольник кадра -> произвольный четырёхугольник на окне (гомография).
namespace suslik::cinema {

/// Углы по часовой стрелке от левого верхнего: ЛВ, ПВ, ПН, ЛН.
using Quad = std::array<QPointF, 4>;

/// Матрица для Item.transform: прямоугольник (0,0)-(w,h) переходит в quad с перспективой
/// (третья строка гомографии уходит в w, GPU интерполирует текстуру с поправкой на перспективу).
/// false — четырёхугольник вырожден или не выпуклый: матрица не меняется.
bool rectToQuad(double w, double h, const Quad &quad, QMatrix4x4 &out);

/// Точка через матрицу с делением на w (для проверок и попадания мышью).
QPointF mapPoint(const QMatrix4x4 &m, const QPointF &p);

/// Выпуклый ли четырёхугольник с обходом в одну сторону (иначе картинка вывернется).
bool isConvex(const Quad &quad);

} // namespace suslik::cinema
