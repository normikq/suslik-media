#pragma once
class QWindow;

/// Системный заголовок окна в цветах приложения (Windows 11: цвет полосы, текста и рамки; Windows 10 — тёмный режим).
/// Заголовок остаётся системным: прилипание к краям, изменение размера и кнопки работают как обычно. Вне Windows — ничего.
namespace suslik::app::TitleBar {
void style(QWindow *w);
}
