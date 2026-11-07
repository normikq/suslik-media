import QtQuick
import QtQuick.Window
import SuslikCinema.Native

// Оверлей поверх игры: прозрачное окно, в нём кадр 1920×1080, натянутый на четырёхугольник экрана телевизора.
// Окно ставит и двигает C++ (Overlay): клиентская область окна игры или весь экран.
Window {
    id: win
    visible: true
    color: "transparent"
    title: "Suslik Cinema"
    flags: Qt.Tool | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint | Qt.WindowDoesNotAcceptFocus
    width: 1280
    height: 720

    onWidthChanged: quad.viewSize = Qt.size(width, height)
    onHeightChanged: quad.viewSize = Qt.size(width, height)
    Component.onCompleted: quad.viewSize = Qt.size(width, height)

    Item {
        id: screen
        width: quad.sourceWidth
        height: quad.sourceHeight
        visible: quad.valid && !host.hidden
        transform: Matrix4x4 { matrix: quad.matrix }

        Rectangle { anchors.fill: parent; color: "black" }
        MpvVideo {
            anchors.fill: parent
            visible: host.hasFile
            engine: mpvEngine
        }
        TestPattern {
            anchors.fill: parent
            visible: !host.hasFile
        }
    }

    // Калибровка: окно принимает мышь, углы тянутся. S — сохранить, R — сбросить, Esc — выйти из калибровки.
    Item {
        anchors.fill: parent
        visible: overlay.calibrating
        focus: overlay.calibrating
        Keys.onPressed: (e) => {
            if (e.key === Qt.Key_S) { quad.save(); hint.text = "Углы сохранены" }
            else if (e.key === Qt.Key_R) quad.reset()
            else if (e.key === Qt.Key_Escape) overlay.calibrating = false
        }

        // Тонкая подложка: без неё прозрачные пиксели пропускают мышь в игру
        Rectangle { anchors.fill: parent; color: "#01000000" }

        Repeater {
            model: 4
            delegate: Rectangle {
                required property int index
                readonly property point c: quad.corners[index]
                x: c.x * win.width - width / 2
                y: c.y * win.height - height / 2
                width: 28; height: 28; radius: 14
                color: drag.pressed ? "#ffcc00" : "#e0ff3355"
                border.color: "white"; border.width: 2
                Text { anchors.centerIn: parent; text: index + 1; color: "white"; font.bold: true }
                MouseArea {
                    id: drag
                    anchors.fill: parent
                    anchors.margins: -10
                    cursorShape: Qt.SizeAllCursor
                    onPositionChanged: (m) => {
                        if (!pressed) return
                        const p = mapToItem(null, m.x, m.y)
                        quad.setCorner(index, p.x / win.width, p.y / win.height)
                    }
                }
            }
        }

        Rectangle {
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.bottom: parent.bottom
            anchors.bottomMargin: 40
            radius: 8
            color: "#cc101018"
            width: hint.implicitWidth + 32
            height: hint.implicitHeight + 16
            Text {
                id: hint
                anchors.centerIn: parent
                color: "white"
                font.pixelSize: 16
                text: "Калибровка: тяните углы 1–4. S — сохранить, R — сбросить, Esc или Ctrl+Alt+C — готово"
            }
        }
    }
}
