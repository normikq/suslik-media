import QtQuick
import QtQuick.Controls.Basic

// Выпадающее меню в цветах приложения (как DropdownMenu прежней версии).
// Ширина — по самому длинному пункту, считается до показа (fit): меню, выровненное по правому краю кнопки,
// не уезжает в сторону, когда пункты дорисовались.
Menu {
    id: m
    padding: 4
    implicitWidth: 220
    function fit() {
        let w = 220
        for (let i = 0; i < count; ++i) {
            const it = itemAt(i)    // скрытые (shown: false) не в счёт; visible до показа меню всегда false
            if (it && it.shown !== false) w = Math.max(w, Math.ceil(it.implicitWidth))
        }
        implicitWidth = w + leftPadding + rightPadding
    }
    onAboutToShow: fit()
    background: Rectangle { implicitWidth: 220; color: Theme.menu; border.color: Theme.menuEdge; radius: 8 }
    enter: Transition { NumberAnimation { property: "opacity"; from: 0; to: 1; duration: 140 } }
    exit: Transition { NumberAnimation { property: "opacity"; from: 1; to: 0; duration: 100 } }
}
