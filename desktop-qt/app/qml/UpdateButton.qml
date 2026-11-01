import QtQuick

// Янтарная кнопка, которая мягко «дышит», пока ждёт обновление (Updater.kt: UpdateButton). Наведение — шире и с версией;
// нажатие — качает установщик (кольцо вокруг стрелки), запускает его, приложение закрывается и возвращается обновлённым.
Item {
    id: ub
    readonly property var info: updater.available
    readonly property bool has: info.version !== undefined
    readonly property bool busy: updater.progress >= 0
    readonly property bool failed: updater.error !== "" && !busy
    readonly property bool hovered: ma.containsMouse
    readonly property bool calm: hovered || busy
    property real breath: 0
    implicitHeight: 40
    visible: has || shown > 0.01
    property real shown: has ? 1 : 0
    Behavior on shown { NumberAnimation { duration: has ? 450 : 200; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
    opacity: shown
    scale: (0.6 + 0.4 * shown) * (calm ? 1 : 1 + 0.035 * breath)

    // дыхание: медленный пульс свечения; при наведении и во время загрузки — спокойно
    SequentialAnimation on breath {
        running: ub.has && !ub.calm && !Theme.simple
        loops: Animation.Infinite
        NumberAnimation { to: 1; duration: 1700; easing.type: Easing.InOutSine }
        NumberAnimation { to: 0; duration: 1700; easing.type: Easing.InOutSine }
    }

    readonly property string label: busy ? (updater.progress >= 1 ? Ui.tx("update.zapusk_ustanovschika") : Math.floor(updater.progress * 100) + "%")
                                    : failed && hovered ? Ui.tx("update.oshibka_p_povtorit", { p: updater.error.substring(0, 40) })
                                    : hovered && has ? Ui.tx("update.obnovit_do_version", { version: info.version }) + (info.beta ? Ui.tx("update.beta") : "")
                                                       + (info.size > 0 ? " · " + Ui.tx("update.f_mb").replace("%.1f", (info.size / 1048576).toFixed(1).replace(".", ",")) : "")
                                    : ""
    implicitWidth: 40 + (label !== "" ? labelText.implicitWidth + 14 : 0)
    width: implicitWidth
    Behavior on width { NumberAnimation { duration: 260; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }

    readonly property color accent: failed ? Theme.bad : Theme.lamp
    // мягкий ореол снаружи
    Rectangle {
        readonly property real spread: 7 * (ub.calm ? 0.8 : 0.4 + 0.6 * ub.breath)
        x: -spread; y: -spread
        width: parent.width + 2 * spread; height: parent.height + 2 * spread
        radius: 12 + spread
        color: ub.accent
        opacity: (ub.calm ? 0.55 : 0.18 + 0.5 * ub.breath) * 0.45
    }
    Rectangle {
        anchors.fill: parent
        radius: 12
        clip: true
        color: ub.failed ? "#33FF6B6B" : ub.hovered && !ub.busy ? "#FFC04F" : Theme.lamp
        Behavior on color { ColorAnimation { duration: 200 } }
        Canvas {
            id: glyph
            x: 9; y: 9; width: 22; height: 22
            property real ring: Math.max(0, updater.progress)
            Behavior on ring { NumberAnimation { duration: 300 } }
            onRingChanged: requestPaint()
            property bool busy: ub.busy
            onBusyChanged: requestPaint()
            property bool failed: ub.failed
            onFailedChanged: requestPaint()
            onPaint: {
                const c = getContext("2d"), w = width, st = 2.2
                c.reset()
                c.lineWidth = st; c.lineCap = "round"
                const ink = failed ? Theme.bad : Theme.lampInk
                if (busy) {
                    c.strokeStyle = Qt.rgba(ink.r, ink.g, ink.b, 0.25)
                    c.beginPath(); c.arc(w / 2, w / 2, w / 2 - st, 0, Math.PI * 2); c.stroke()
                    c.strokeStyle = ink
                    c.beginPath(); c.arc(w / 2, w / 2, w / 2 - st, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * ring); c.stroke()
                } else {
                    // стрелка в лоток
                    c.strokeStyle = ink
                    c.beginPath()
                    c.moveTo(w / 2, w * 0.12); c.lineTo(w / 2, w * 0.62)
                    c.moveTo(w * 0.28, w * 0.42); c.lineTo(w / 2, w * 0.64); c.lineTo(w * 0.72, w * 0.42)
                    c.moveTo(w * 0.14, w * 0.86); c.lineTo(w * 0.86, w * 0.86)
                    c.stroke()
                }
            }
        }
        Text {
            id: labelText
            x: 40; anchors.verticalCenter: parent.verticalCenter
            text: ub.label
            visible: text !== ""
            color: ub.failed ? Theme.bad : Theme.lampInk
            font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 13
        }
    }
    MouseArea {
        id: ma
        anchors.fill: parent
        hoverEnabled: true
        cursorShape: ub.busy ? Qt.ArrowCursor : Qt.PointingHandCursor
        onClicked: if (!ub.busy) updater.install()
    }
}
