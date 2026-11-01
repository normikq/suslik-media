import QtQuick

// Окно поверх страницы: затемнение, карточка «тёмного стекла» выезжает снизу и проявляется. Щелчок мимо или Esc — закрыть.
Item {
    id: sh
    property bool open: false
    property real maxWidth: 560
    property real pad: 22
    default property alias content: body.data
    signal dismissed()
    anchors.fill: parent
    visible: a > 0.001
    z: 10
    property real a: open ? 1 : 0
    Behavior on a { NumberAnimation { duration: 260; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
    onOpenChanged: if (open) sh.forceActiveFocus()
    Keys.onEscapePressed: sh.dismissed()

    Rectangle {
        anchors.fill: parent
        color: Theme.scrim
        opacity: sh.a
        // все кнопки мыши: правая иначе проходила насквозь и открывала меню плитки под окном
        MouseArea { anchors.fill: parent; hoverEnabled: true; acceptedButtons: Qt.AllButtons; onClicked: sh.dismissed(); onWheel: (w) => w.accepted = true }
    }
    Rectangle {
        id: card
        anchors.centerIn: parent
        anchors.verticalCenterOffset: (1 - sh.a) * 24
        width: Math.min(sh.maxWidth, sh.width - 48)
        height: Math.min(body.implicitHeight + sh.pad * 2, sh.height - 48)
        opacity: sh.a
        scale: 0.97 + 0.03 * sh.a
        radius: 22
        color: Theme.sheet
        border.color: Theme.sheetEdge
        MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons; onWheel: (w) => w.accepted = true } // щелчки по карточке её не закрывают
        Column {
            id: body
            x: sh.pad; y: sh.pad
            width: parent.width - sh.pad * 2
        }
    }
}
