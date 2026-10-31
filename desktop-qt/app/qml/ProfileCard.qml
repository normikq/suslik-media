import QtQuick

// Чья это медиатека: картинка Discord, имя, «Выйти» (Sidebar.kt: ProfileCard).
Glass {
    id: card
    property bool expanded: true
    signal logout()
    implicitHeight: expanded ? full.implicitHeight + 24 : small.implicitHeight + 20
    Behavior on implicitHeight { NumberAnimation { duration: 220; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
    clip: true

    Column {
        id: full
        visible: card.expanded
        x: 12; y: 12; width: parent.width - 24
        spacing: 10
        Row {
            width: parent.width
            spacing: 10
            Avatar { url: lib.avatarUrl; name: lib.profileName; size: 34 }
            Column {
                width: parent.width - 44
                anchors.verticalCenter: parent.verticalCenter
                Text { width: parent.width; text: lib.profileName; color: "white"; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 14; elide: Text.ElideRight }
                Text { width: parent.width; text: lib.profileNote; color: Theme.muted; font.family: Theme.onest; font.pixelSize: 12; elide: Text.ElideRight }
            }
        }
        PillButton { width: parent.width; text: Ui.tx("library.vyyti"); onClicked: card.logout() }
    }
    Column {
        id: small
        visible: !card.expanded
        anchors.horizontalCenter: parent.horizontalCenter
        y: 10
        spacing: 8
        Avatar { url: lib.avatarUrl; name: lib.profileName; size: 34; anchors.horizontalCenter: parent.horizontalCenter }
        Btn {
            id: out
            width: 30; height: 30
            anchors.horizontalCenter: parent.horizontalCenter
            Rectangle { anchors.fill: parent; radius: 15; color: out.hovered ? "#26FFFFFF" : "#0DFFFFFF" }
            Icon { anchors.centerIn: parent; name: "close"; size: 11; color: out.hovered ? "white" : Theme.muted }
            onClicked: card.logout()
        }
    }
}
