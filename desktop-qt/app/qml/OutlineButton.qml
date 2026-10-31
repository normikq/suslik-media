import QtQuick

// Вторая кнопка: тёмная с рамкой (Controls.kt: OutlineButton).
Btn {
    id: b
    property string text
    hoverUp: 1.02
    implicitWidth: label.implicitWidth + 28
    implicitHeight: Math.max(38, label.implicitHeight + 18)
    opacity: enabled_ ? 1 : 0.55
    Rectangle {
        anchors.fill: parent
        radius: 12
        color: b.hovered ? "#1C202B" : "#141720"
        border.color: b.hovered ? "#3E475C" : "#242A38"
        Behavior on color { ColorAnimation { duration: 200 } }
        Behavior on border.color { ColorAnimation { duration: 200 } }
    }
    Text {
        id: label
        anchors.centerIn: parent
        text: b.text
        color: "white"
        font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 14
    }
}
