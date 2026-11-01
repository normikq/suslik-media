import QtQuick

// Пункт боковой панели (Sidebar.kt: NavItem): значок на одном месте в обоих состояниях, подпись выезжает справа.
Btn {
    id: n
    property string text
    property string icon
    property bool iconFilled: false
    property bool active: false
    property bool expanded: true
    property string note          // мелкая строка под названием («скоро», «вы в комнате»)
    property int count: -1
    property bool glowing: false  // постер только что прилетел сюда
    property real glow: glowing ? 1 : 0
    Behavior on glow { NumberAnimation { duration: 260 } }
    hoverUp: 1
    scale: 1 + 0.05 * glow
    implicitHeight: 42
    readonly property color fg: !enabled_ ? Theme.dim : (active || (hovered && enabled_)) ? "white" : "#CBD2E0"

    Rectangle {
        anchors.fill: parent
        radius: 12
        color: n.active ? Qt.rgba(0x25 / 255, 0x63 / 255, 0xEB / 255, 0.78) : (n.hovered && n.enabled_) ? "#0FFFFFFF" : "transparent"
        border.color: n.active ? Theme.accentEdge : "transparent"
        Behavior on color { ColorAnimation { duration: 200 } }
        Rectangle { anchors.fill: parent; radius: 12; color: Theme.accent; opacity: 0.55 * n.glow }
    }
    Icon {
        id: ic
        x: 15 + 1; anchors.verticalCenter: parent.verticalCenter
        name: n.icon; filled: n.iconFilled; size: 16; color: n.fg
    }
    Column {
        x: 15 + 18 + 12
        anchors.verticalCenter: parent.verticalCenter
        width: parent.width - x - (cnt.visible ? cnt.width + 12 : 8)
        opacity: n.expanded ? 1 : 0
        visible: opacity > 0
        Behavior on opacity { NumberAnimation { duration: n.expanded ? 200 : 90 } }
        Text {
            width: parent.width
            text: n.text
            color: n.fg
            font.family: Theme.onest; font.weight: n.active ? Font.DemiBold : Font.Medium; font.pixelSize: 14
            elide: Text.ElideRight
        }
        Text {
            visible: n.note !== ""
            width: parent.width
            text: n.note
            color: n.active ? "#C7FFFFFF" : Theme.dim
            font.family: Theme.onest; font.pixelSize: 11
            elide: Text.ElideRight
        }
    }
    Text {
        id: cnt
        visible: n.count >= 0 && n.expanded
        anchors { right: parent.right; rightMargin: 12; verticalCenter: parent.verticalCenter }
        text: n.count
        color: n.active ? "white" : Theme.muted
        font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 12
    }
}
