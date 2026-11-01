import QtQuick

// Маленький переключатель (фильтры, дорожки). Controls.kt: Chip.
Btn {
    id: b
    property string text
    property bool on: false
    implicitWidth: label.implicitWidth + 24
    implicitHeight: label.implicitHeight + 14
    Rectangle {
        anchors.fill: parent
        radius: 10
        color: b.on ? "#282E3C" : b.hovered ? "#1C202B" : "#141720"
        border.color: b.on ? "#3A4356" : b.hovered ? "#2C3344" : "#222735"
        Behavior on color { ColorAnimation { duration: 200 } }
    }
    Text {
        id: label
        anchors.centerIn: parent
        text: b.text
        color: b.on ? "white" : b.hovered ? "#D6DBE4" : Theme.muted
        font.family: Theme.onest; font.weight: b.on ? Font.DemiBold : Font.Medium; font.pixelSize: 13
    }
}
