import QtQuick

// Тестовая картинка 16:9: шахматка видна в перспективе, номера углов совпадают с ручками калибровки.
Item {
    id: root
    readonly property int cols: 16
    readonly property int rows: 9

    Grid {
        anchors.fill: parent
        columns: root.cols
        Repeater {
            model: root.cols * root.rows
            delegate: Rectangle {
                required property int index
                width: root.width / root.cols
                height: root.height / root.rows
                color: ((index % root.cols) + Math.floor(index / root.cols)) % 2 ? "#2b2f45" : "#d9dbe8"
            }
        }
    }
    Rectangle {
        anchors.fill: parent
        color: "transparent"
        border.color: "#ff3355"
        border.width: 12
    }
    Rectangle {
        anchors.centerIn: parent
        width: label.implicitWidth + 80
        height: label.implicitHeight + 40
        radius: 24
        color: "#e0101018"
        Text {
            id: label
            anchors.centerIn: parent
            text: "Suslik Cinema · " + host.version
            color: "white"
            font.pixelSize: 72
            font.bold: true
        }
    }
    Repeater {
        model: [Qt.point(0, 0), Qt.point(1, 0), Qt.point(1, 1), Qt.point(0, 1)]
        delegate: Text {
            required property var modelData
            required property int index
            text: index + 1
            color: "#ff3355"
            font.pixelSize: 120
            font.bold: true
            x: modelData.x * (root.width - width - 60) + 30
            y: modelData.y * (root.height - height - 60) + 30
        }
    }
}
