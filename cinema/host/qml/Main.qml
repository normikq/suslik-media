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
        // С модом: прозрачность от игры (вне кадра, за стеной, пауза); без мода — ручная калибровка
        visible: quad.valid && !host.hidden && opacity > 0.001
        opacity: link.live && !overlay.calibrating ? link.opacity : 1
        transform: Matrix4x4 { matrix: quad.matrix }

        // Слой с прозрачной каймой 2 px: при перспективе край сглаживается фильтрацией текстуры (мягкий край)
        Item {
            anchors.fill: parent
            layer.enabled: true
            layer.smooth: true
            Item {
                anchors.fill: parent
                anchors.margins: 2
                Rectangle { anchors.fill: parent; color: "black" }
                // Ядро mpv создаётся до первого кадра (main.cpp), файл открывается после: рисовальщик уже готов.
                // update() на смену состояния — чтобы рисовальщик переподключился, если ядро пересоздано
                MpvVideo {
                    id: video
                    anchors.fill: parent
                    visible: host.hasFile
                    engine: mpvEngine
                    Connections {
                        target: mpvEngine
                        function onStateChanged() { video.update() }
                    }
                }
                // Тестовая картинка нарисована под 1920×1080 и масштабируется под размер буфера
                TestPattern {
                    width: 1920
                    height: 1080
                    scale: Math.min(parent.width / 1920, parent.height / 1080)
                    transformOrigin: Item.TopLeft
                    visible: !host.hasFile
                }
            }
        }
    }

    // Подсказка хоста (поправка упреждения): внизу окна, кликов не принимает
    Rectangle {
        visible: link.osd !== ""
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: parent.bottom
        anchors.bottomMargin: 90
        radius: 8
        color: "#cc101018"
        width: osdText.implicitWidth + 32
        height: osdText.implicitHeight + 16
        Text {
            id: osdText
            anchors.centerIn: parent
            color: "white"
            font.pixelSize: 18
            text: link.osd
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
