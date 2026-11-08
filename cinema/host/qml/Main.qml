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
        // С модом: прозрачность от игры (вне кадра, за стеной, пауза); без мода — ручная калибровка.
        // Пульт без фильма (blank) — прозрачность 0, но не visible: false: рисовальщик mpv должен существовать
        // до открытия файла (иначе mpv выключает видео), а невидимому элементу он не создаётся.
        visible: quad.valid && !host.hidden && (host.blank || opacity > 0.001)
        opacity: host.blank ? 0 : link.live && !overlay.calibrating ? link.opacity : 1
        transform: Matrix4x4 { matrix: quad.matrix }

        // Свет экрана в зале: отсвет на стене, сцене и потолке вокруг полотна, как у настоящего экрана
        // (qml/shaders/spill.frag). Только прибавляет свет: тёмный кадр вокруг ничего не затемняет, яркий — светит.
        // Цвет — край кадра и средний цвет из уменьшенной копии с мипмапами (размытие почти бесплатное).
        // Сила — от света игры (scene, SceneProbe): в тёмном зале заметнее, на свету почти не видно
        ShaderEffectSource {
            id: glowSrc
            sourceItem: video
            textureSize: Qt.size(256, 144)
            mipmap: true
            smooth: true
            hideSource: false
            live: spill.visible
            visible: false
        }
        ShaderEffect {
            id: spill
            // поле вокруг полотна, доля его размера; «Размер отсвета» на телефоне — scene.spillSize
            readonly property real margin: Math.min(0.5, 0.25 * scene.spillSize)
            x: -parent.width * margin
            y: -parent.height * margin
            width: parent.width * (1 + 2 * margin)
            height: parent.height * (1 + 2 * margin)
            visible: host.hasFile && scene.enabled && strength > 0.005
            property variant frame: glowSrc
            property real strength: scene.glow * 1.6
            property real aspect: parent.width / Math.max(1, parent.height)
            property real falloff: 0.09 * scene.spillSize // спад света, доля высоты полотна
            Behavior on strength { NumberAnimation { duration: 600 } }
            fragmentShader: "qrc:/shaders/spill.frag.qsb"
        }

        // Слой с прозрачной каймой 2 px: при перспективе край сглаживается фильтрацией текстуры (мягкий край).
        // Мипмапы: экран на мониторе меньше кадра (около 1230 из 1920 по ширине) и под углом — без них
        // уменьшение берёт каждую вторую точку, на мелких деталях рябь и муар
        Item {
            anchors.fill: parent
            layer.enabled: true
            layer.smooth: true
            layer.mipmap: true
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
                // «Вписать в сцену»: кадр темнее в тёмном зале (полотно отражает, а не светится) и подсвечен светом
                // зала — чёрное у полотна в освещённой сцене не чёрное. Значения плавно идут за светом игры
                Rectangle {
                    anchors.fill: parent
                    visible: video.visible && scene.enabled
                    color: "black"
                    opacity: 1 - scene.dim
                    Behavior on opacity { NumberAnimation { duration: 600 } }
                }
                Rectangle {
                    anchors.fill: parent
                    visible: video.visible && scene.enabled
                    color: scene.lift
                    opacity: scene.liftAmount
                    Behavior on opacity { NumberAnimation { duration: 600 } }
                    Behavior on color { ColorAnimation { duration: 600 } }
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
