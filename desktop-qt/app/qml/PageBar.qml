import QtQuick

// «‹ 1 2 3 … 9 ›» под постерами (LibraryView.kt: PageBar).
Item {
    id: bar
    property var page
    implicitHeight: 50
    Row {
        anchors.centerIn: parent
        anchors.verticalCenterOffset: 3
        Cell { label: "‹"; enabled_: bar.page.page > 0; onClicked: bar.page.page = bar.page.page - 1 }
        Repeater {
            model: bar.page.pageNumbers
            Item {
                required property int modelData
                width: modelData < 0 ? dots.implicitWidth + 8 : cell.width
                height: 36
                Text { id: dots; visible: modelData < 0; anchors.centerIn: parent; text: "…"; color: Theme.muted; font.family: Theme.onest; font.pixelSize: 14 }
                Cell { id: cell; visible: modelData >= 0; label: String(modelData + 1); active: modelData === bar.page.page; onClicked: bar.page.page = modelData }
            }
        }
        Cell { label: "›"; enabled_: bar.page.page < bar.page.pageCount - 1; onClicked: bar.page.page = bar.page.page + 1 }
    }

    component Cell: Item {
        id: c
        property string label
        property bool active: false
        property bool enabled_: true
        signal clicked()
        width: Math.max(36, t.implicitWidth + 20) + 6
        height: 36
        Rectangle {
            x: 3; width: parent.width - 6; height: 36
            radius: 10
            color: c.active ? Theme.accent : ma.containsMouse && c.enabled_ ? "#26FFFFFF" : "#12FFFFFF"
            border.color: c.active ? Theme.accentEdge : Theme.glassEdge
            Behavior on color { ColorAnimation { duration: 160 } }
            Text { id: t; anchors.centerIn: parent; text: c.label; color: c.enabled_ ? "white" : Theme.dim; font.family: Theme.onest; font.weight: c.active ? Font.Bold : Font.Medium; font.pixelSize: 14 }
        }
        MouseArea {
            id: ma
            anchors.fill: parent
            hoverEnabled: true
            cursorShape: c.enabled_ && !c.active ? Qt.PointingHandCursor : Qt.ArrowCursor
            onClicked: if (c.enabled_ && !c.active) c.clicked()
        }
    }
}
