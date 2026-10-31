import QtQuick

// Карточка с отступом 18 (Controls.kt: Panel).
Rectangle {
    default property alias content: col.data
    property alias spacing: col.spacing
    radius: 16
    color: Theme.booth
    border.color: Theme.line
    implicitHeight: col.implicitHeight + 36
    Column {
        id: col
        x: 18; y: 18
        width: parent.width - 36
    }
}
