import QtQuick
import QtQuick.Window

// Значки прежней версии (ui/DesktopIcons.kt, Sidebar.kt). Рисует C++ (IconProvider) один раз на имя, цвет и размер:
// одинаковые значки делят одну картинку, без Canvas и JS-контекста на каждый.
Image {
    property string name
    property color color: "white"
    property bool filled: false
    property real size: 16
    width: size; height: size
    readonly property int px: Math.ceil(size * Screen.devicePixelRatio)
    sourceSize: Qt.size(px, px)
    source: name ? "image://icon/ui/" + name + "/" + size + "/" + color.toString().replace("#", "") + "/" + (filled ? 1 : 0) : ""
    smooth: true
    cache: true
}
