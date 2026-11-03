import QtQuick

// Свой заголовок окна вместо системного: полоса, за которую окно тянут (двойной щелчок — развернуть / восстановить,
// правая кнопка — системное меню), и кнопки «Свернуть», «Развернуть / Восстановить», «Закрыть» в цветах приложения.
// Рамку, тень и прилипание к краям держит система (WindowMode, TitleBar.cpp).
Item {
    id: bar
    property bool shown: true          // в плеере кнопки гаснут вместе с его панелями
    signal activity()                  // мышь двигается по заголовку (плеер показывает панели)
    height: 32

    // полоса перетаскивания: окно поедет, когда мышь сдвинется с места (иначе двойной щелчок не дойдёт)
    MouseArea {
        id: drag
        anchors.fill: parent
        acceptedButtons: Qt.LeftButton | Qt.RightButton
        hoverEnabled: true
        property point pressAt
        property bool moved: false
        onPressed: (m) => { pressAt = Qt.point(m.x, m.y); moved = false; bar.activity() }
        onPositionChanged: (m) => {
            bar.activity()
            if (pressed && (m.buttons & Qt.LeftButton) && !moved && Math.hypot(m.x - pressAt.x, m.y - pressAt.y) > 3) {
                moved = true
                windowMode.startMove()
            }
        }
        onDoubleClicked: (m) => { if (m.button === Qt.LeftButton) windowMode.toggleMaximized() }
        onClicked: (m) => {
            if (m.button !== Qt.RightButton) return
            const p = mapToItem(null, m.x, m.y)
            windowMode.systemMenu(p.x, p.y)
        }
    }

    Row {
        anchors.right: parent.right
        height: parent.height
        opacity: bar.shown ? 1 : 0
        Behavior on opacity { NumberAnimation { duration: 220 } }
        WinButton { kind: "min"; rightRoom: 92; tip: Ui.tx("app.okno_svernut"); onClicked: windowMode.minimize() }
        WinButton {
            rightRoom: 46
            kind: windowMode.maximized ? "restore" : "max"
            tip: windowMode.maximized ? Ui.tx("app.okno_vosstanovit") : Ui.tx("app.okno_razvernut")
            onClicked: windowMode.toggleMaximized()
        }
        WinButton { kind: "close"; tip: Ui.tx("app.okno_zakryt"); onClicked: Window.window.close() }
    }

    component WinButton: Item {
        id: wb
        property string kind
        property string tip
        property real rightRoom: 0       // сколько места до правого края окна (подсказка не выходит за него)
        signal clicked()
        width: 46; height: bar.height
        readonly property bool close: kind === "close"
        readonly property color ink: ma.containsMouse ? "white" : Theme.muted
        Rectangle {
            anchors.fill: parent
            color: wb.close ? (ma.pressed ? "#C63B40" : "#E5484D") : (ma.pressed ? "#26FFFFFF" : "#17FFFFFF")
            opacity: ma.containsMouse ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 120 } }
        }
        // «Свернуть» — черта
        Rectangle {
            visible: wb.kind === "min"
            anchors.centerIn: parent
            width: 10; height: 1
            color: wb.ink
        }
        // «Развернуть» — квадрат
        Rectangle {
            visible: wb.kind === "max"
            anchors.centerIn: parent
            width: 10; height: 10; radius: 1.5
            color: "transparent"; border.color: wb.ink; border.width: 1
        }
        // «Восстановить» — два квадрата
        Item {
            visible: wb.kind === "restore"
            anchors.centerIn: parent
            width: 10; height: 10
            Rectangle { x: 2; y: -2; width: 8; height: 8; radius: 1.5; color: "transparent"; border.color: wb.ink; border.width: 1 }
            Rectangle { x: 0; y: 0; width: 8; height: 8; radius: 1.5; color: Theme.night; border.color: wb.ink; border.width: 1 }
        }
        // «Закрыть» — крест
        Repeater {
            model: wb.close ? [45, -45] : []
            Rectangle {
                required property int modelData
                anchors.centerIn: wb
                width: 13; height: 1
                rotation: modelData
                antialiasing: true
                color: wb.ink
            }
        }
        MouseArea {
            id: ma
            anchors.fill: parent
            hoverEnabled: true
            onClicked: wb.clicked()
            onPositionChanged: bar.activity()
        }
        // подсказка под кнопкой, не выходит за правый край окна
        Rectangle {
            x: Math.min((wb.width - width) / 2, wb.width + wb.rightRoom - 6 - width)
            y: wb.height + 6
            width: tipText.implicitWidth + 16; height: tipText.implicitHeight + 10; radius: 7
            color: "#F0141824"; border.color: "#33FFFFFF"
            opacity: ma.containsMouse && wb.tip !== "" ? 1 : 0
            visible: opacity > 0
            Behavior on opacity { SequentialAnimation { PauseAnimation { duration: 500 } NumberAnimation { duration: 140 } } }
            Text { id: tipText; anchors.centerIn: parent; text: wb.tip; color: Theme.text; font.family: Theme.onest; font.pixelSize: 12 }
        }
    }
}
