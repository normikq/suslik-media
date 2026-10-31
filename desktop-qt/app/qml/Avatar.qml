import QtQuick

// Круглая картинка Discord; пока грузится (или её нет) — первая буква имени на синем.
Item {
    id: a
    property string url
    property string name
    property real size: 34
    width: size; height: size
    Rectangle {
        anchors.fill: parent
        radius: width / 2
        gradient: Gradient { orientation: Gradient.Horizontal; GradientStop { position: 0; color: Theme.accent } GradientStop { position: 1; color: "#6366F1" } }
        border.color: "#33FFFFFF"
        Text {
            anchors.centerIn: parent
            visible: img.status !== Image.Ready
            text: a.name.trim().substring(0, 1).toUpperCase()
            color: "white"
            font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: a.size * 0.41
        }
    }
    Rounded {
        anchors.fill: parent
        radius: width / 2
        visible: img.status === Image.Ready
        Image {
            id: img
            anchors.fill: parent
            source: a.url
            sourceSize: Qt.size(a.size * 2, a.size * 2)
            fillMode: Image.PreserveAspectCrop
            asynchronous: true
            smooth: true
        }
    }
}
