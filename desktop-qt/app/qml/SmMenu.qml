import QtQuick
import QtQuick.Controls.Basic

// Выпадающее меню в цветах приложения (как DropdownMenu прежней версии).
Menu {
    id: m
    padding: 4
    implicitWidth: Math.max(220, contentItem.childrenRect.width + 8)
    background: Rectangle { implicitWidth: 220; color: Theme.menu; border.color: Theme.menuEdge; radius: 8 }
    enter: Transition { NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 140 } }
    exit: Transition { NumberAnimation { property: "opacity"; from: 1; to: 0; duration: 100 } }
}
