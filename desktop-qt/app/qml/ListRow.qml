import QtQuick

// Строка вида «Список» (LibraryView.kt: ListRow).
Btn {
    id: row
    // постер — когда строку впервые видно (список скрытой вкладки создают заранее)
    property bool everShown: false
    onVisibleChanged: if (visible) everShown = true
    Component.onCompleted: everShown = visible
    property var t: ({})
    property var page
    hoverUp: 1
    height: 96
    onClicked: if (t.canPlay) page.open(t.id)
    Rectangle {
        anchors.fill: parent
        radius: 12
        color: row.hovered ? "#181B26" : "#141720"
        border.color: row.hovered ? "#40485C" : "#242A38"
        Behavior on color { ColorAnimation { duration: 200 } }
    }
    Item {
        id: thumb
        x: 14; y: 10; width: 52; height: parent.height - 20
        Rectangle { anchors.fill: parent; radius: 8; color: "#222735" }
        Text { anchors.centerIn: parent; visible: img.status !== Image.Ready; text: "▶"; color: Theme.muted; font.pixelSize: 16 }
        RoundedImage {
            id: img
            anchors.fill: parent
            radius: 8
            source: row.everShown && row.t.poster ? "image://suslik" + row.t.poster : ""
            sourceSize: Qt.size(104, 152)
        }
    }
    Column {
        anchors { left: thumb.right; leftMargin: 16; right: badge.left; rightMargin: 16; verticalCenter: parent.verticalCenter }
        Text { width: parent.width; elide: Text.ElideRight; text: row.t.title || ""; color: "white"; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 17 }
        Text { topPadding: 2; text: row.t.subtitle || ""; color: Theme.muted; font.family: Theme.onest; font.pixelSize: 14 }
        Text { topPadding: 3; width: parent.width; elide: Text.ElideRight; text: row.t.specs1 || ""; color: "#C0C7D5"; font.family: Theme.onest; font.pixelSize: 13 }
        Text { topPadding: 1; width: parent.width; elide: Text.ElideRight; text: row.t.specs2 || ""; color: "#7A8294"; font.family: Theme.onest; font.pixelSize: 12 }
    }
    Rectangle {
        id: badge
        anchors { right: mineBtn.left; rightMargin: 16; verticalCenter: parent.verticalCenter }
        width: bt.implicitWidth + 18; height: bt.implicitHeight + 8
        radius: 6; color: "#181B24"; border.color: "#2A3040"
        Text { id: bt; anchors.centerIn: parent; text: row.t.listStatus || ""; color: "white"; font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 12 }
    }
    Btn {
        id: mineBtn
        anchors { right: favBtn.left; rightMargin: 4; verticalCenter: parent.verticalCenter }
        width: 38; height: 38; hoverUp: 1.12
        Icon { anchors.centerIn: parent; name: "bookmark"; filled: !!row.t.ownMine; size: 18; color: row.t.ownMine ? "#7FB2FF" : mineBtn.hovered ? "white" : Theme.muted }
        onClicked: lib.setMine(row.t.id, !row.t.ownMine)
    }
    GlowButton {
        id: favBtn
        anchors { right: playBtn.left; rightMargin: 12; verticalCenter: parent.verticalCenter }
        Text { anchors.centerIn: parent; text: row.t.ownFavorite ? "★" : "☆"; color: row.t.ownFavorite || favBtn.hovered ? "white" : Theme.muted; font.pixelSize: 21 }
        onClicked: lib.setFavorite(row.t.id, !row.t.ownFavorite)
    }
    GlowButton {
        id: playBtn
        anchors { right: parent.right; rightMargin: 14; verticalCenter: parent.verticalCenter }
        Icon { anchors.centerIn: parent; name: "play"; size: 20; color: playBtn.hovered ? "white" : "#C0C7D5" }
        onClicked: if (row.t.canPlay) row.page.open(row.t.id)
    }

    // круглая кнопка без круга: белое свечение при наведении
    component GlowButton: Btn {
        id: g
        width: 38; height: 38; hoverUp: 1.12
        Repeater {
            model: 4
            Rectangle {
                required property int index
                anchors.centerIn: parent
                width: 34 * (1 - index / 4); height: width; radius: width / 2
                color: "white"
                opacity: g.hovered ? [0.04, 0.07, 0.12, 0.2][index] : 0
                Behavior on opacity { NumberAnimation { duration: 180 } }
            }
        }
    }
}
