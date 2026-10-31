import QtQuick

// Заглушка до шага 2: цвета — токены Sm из desktop/.../ui/Theme.kt.
Window {
    width: 1280
    height: 800
    minimumWidth: 960
    minimumHeight: 600
    visible: true
    color: "#060911"
    title: "Suslik Media"

    Column {
        anchors.centerIn: parent
        spacing: 8
        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            text: "Suslik Media"
            color: "#EEF0F5"
            font.pixelSize: 32
            font.weight: Font.DemiBold
        }
        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            text: "Версия на Qt " + appVersion + (siteConfigured ? "" : " · адрес сервера не задан")
            color: "#8E96A8"
            font.pixelSize: 15
        }
    }
}
