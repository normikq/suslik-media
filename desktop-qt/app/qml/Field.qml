import QtQuick

// Поле ввода: тёмное, рамка светлеет в фокусе, подсказка серым (Controls.kt: Field).
Rectangle {
    id: f
    property alias text: input.text
    property string placeholder
    property bool password: false
    property string icon
    property alias input: input
    signal accepted()
    implicitHeight: 44
    implicitWidth: 280
    radius: 12
    color: "#12151D"
    border.color: input.activeFocus ? "#3E475C" : "#222735"
    Behavior on border.color { ColorAnimation { duration: 150 } }

    Icon { id: ic; visible: f.icon !== ""; name: f.icon; size: 16; color: Theme.muted; x: 14; anchors.verticalCenter: parent.verticalCenter }
    TextInput {
        id: input
        anchors { left: ic.visible ? ic.right : parent.left; leftMargin: ic.visible ? 10 : 14; right: parent.right; rightMargin: 14; verticalCenter: parent.verticalCenter }
        color: Theme.text
        selectionColor: Theme.accent
        selectedTextColor: "white"
        font.family: Theme.onest; font.pixelSize: 15
        echoMode: f.password ? TextInput.Password : TextInput.Normal
        clip: true
        selectByMouse: true
        onAccepted: f.accepted()
        Text {
            visible: input.text.length === 0
            text: f.placeholder
            color: "#6B7280"
            font: input.font
            anchors.verticalCenter: parent.verticalCenter
        }
    }
    MouseArea { anchors.fill: parent; cursorShape: Qt.IBeamCursor; acceptedButtons: Qt.NoButton }
    TapHandler { onTapped: input.forceActiveFocus() }
}
