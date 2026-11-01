import QtQuick

// Главная кнопка: синяя, белый текст (Controls.kt: LampButton).
Btn {
    id: b
    property string text
    property bool busy: false
    implicitWidth: label.implicitWidth + 32
    implicitHeight: Math.max(38, label.implicitHeight + 18)
    opacity: enabled_ ? 1 : 0.55
    Rectangle {
        anchors.fill: parent
        radius: 12
        color: b.hovered && b.enabled_ ? Theme.accentHover : Theme.accent
        border.color: b.hovered && b.enabled_ ? "#8DB6FF" : Theme.accentEdge
        Behavior on color { ColorAnimation { duration: 200 } }
        Behavior on border.color { ColorAnimation { duration: 240 } }
    }
    Text {
        id: label
        anchors.centerIn: parent
        text: b.text
        color: "white"
        font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 14
    }
}
