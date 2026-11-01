import QtQuick

// Кнопка-«таблетка» высотой 36 со значком (Controls.kt: PillButton).
Btn {
    id: b
    property string text
    property string icon
    property bool active: false
    hoverUp: 1.02
    implicitWidth: row.implicitWidth + 28
    implicitHeight: 36
    readonly property color fg: active || hovered ? "white" : "#D6DBE4"
    Rectangle {
        anchors.fill: parent
        radius: 18
        color: b.active ? "#282E3C" : b.hovered ? "#1C202B" : "#141720"
        border.color: b.active || b.hovered ? "#3E475C" : "#242A38"
        Behavior on color { ColorAnimation { duration: 200 } }
        Behavior on border.color { ColorAnimation { duration: 200 } }
    }
    Row {
        id: row
        anchors.centerIn: parent
        spacing: 6
        Icon { visible: b.icon !== ""; name: b.icon; size: 14; color: b.active ? "white" : Theme.muted; anchors.verticalCenter: parent.verticalCenter }
        Text {
            text: b.text
            color: b.fg
            font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 13
            anchors.verticalCenter: parent.verticalCenter
            Behavior on color { ColorAnimation { duration: 200 } }
        }
    }
}
