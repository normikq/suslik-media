import QtQuick

// Круглая «×» в углу окна.
Btn {
    id: c
    width: 32; height: 32; hoverUp: 1
    Rectangle { anchors.fill: parent; radius: 16; color: c.hovered ? "#262F42" : "#181E2B"; border.color: c.hovered ? "#3B4863" : "#262F40" }
    Icon { anchors.centerIn: parent; name: "close"; size: 11; color: c.hovered ? "white" : "#B0B9C9" }
}
