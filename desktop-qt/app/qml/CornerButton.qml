import QtQuick

// Круглая кнопка в углу постера: видна всегда, если включена, иначе — только при наведении на плитку.
Btn {
    id: c
    property bool on: false
    property bool tileHovered: false
    width: 30; height: 30
    hoverUp: 1.12
    opacity: on || tileHovered ? 1 : 0
    enabled_: opacity > 0.05
    visible: opacity > 0
    Behavior on opacity { NumberAnimation { duration: 200 } }
    Rectangle { anchors.fill: parent; radius: 15; color: c.hovered ? "#99000000" : "#55000000"; z: -1 }
}
