#pragma once
#include <QQuickImageProvider>

namespace suslik::app {

/// image://icon/<набор>/<имя>/<размер>/<цвет #AARRGGBB>/<filled 0|1>/<громкость>/<muted 0|1>
/// Значки (Icon.qml — ui/DesktopIcons.kt, PlayerIcon.qml — PlayerIcons.kt) рисуются QPainter'ом один раз:
/// одинаковые значки делят одну картинку в кеше QML, без отдельного Canvas, JS-контекста и растра на каждый.
/// набор: «ui» или «player»; размер — логический (толщины линий заданы для него), картинка — под sourceSize.
class IconProvider : public QQuickImageProvider {
public:
    IconProvider() : QQuickImageProvider(QQuickImageProvider::Image) {}
    QImage requestImage(const QString &id, QSize *size, const QSize &requestedSize) override;
};

} // namespace suslik::app
