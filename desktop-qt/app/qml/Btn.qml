import QtQuick

// Основа кнопок: наведение (лёгкий подъём), нажатие (лёгкое проседание), рука-курсор. Controls.kt: hoverClick + hoverScale.
Item {
    id: b
    property bool enabled_: true
    property real hoverUp: 1.03
    readonly property alias hovered: ma.containsMouse
    readonly property alias pressed: ma.pressed
    property alias cursorShape: ma.cursorShape
    property alias acceptedButtons: ma.acceptedButtons
    signal clicked()
    signal rightClicked(real x, real y)

    scale: !enabled_ ? 1 : ma.pressed ? 0.96 : ma.containsMouse ? hoverUp : 1
    Behavior on scale { NumberAnimation { duration: 200; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }

    MouseArea {
        id: ma
        anchors.fill: parent
        hoverEnabled: true
        cursorShape: b.enabled_ ? Qt.PointingHandCursor : Qt.ArrowCursor
        onClicked: (m) => {
            if (m.button === Qt.RightButton) b.rightClicked(m.x, m.y)
            else if (b.enabled_) b.clicked()
        }
    }
}
