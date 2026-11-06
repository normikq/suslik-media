import QtQuick

// «Какую версию включить?» (VersionPicker.kt): строка на раздачу — качество, размер, сколько посмотрено.
Sheet {
    id: vp
    property var page
    property string title
    property var ids: []
    readonly property var sections: open && page ? (lib.version, page.versionRows(ids)) : []
    maxWidth: 560
    onDismissed: open = false

    Text { text: Ui.tx("library.vyberite_versiyu"); color: Theme.text; font.family: Theme.unbounded; font.weight: Font.Medium; font.pixelSize: 20 }
    Text {
        width: parent.width
        topPadding: 4; bottomPadding: 14
        text: vp.title + " · " + (vp.page ? vp.page.versionsLabel(vp.ids.length) : "")
        color: Theme.muted; font.family: Theme.onest; font.pixelSize: 14
        elide: Text.ElideRight
    }
    Flickable {
        width: parent.width
        height: Math.min(440, list.height)
        contentHeight: list.height
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        Column {
            id: list
            width: parent.width
            spacing: 8
            Repeater {
                model: vp.sections
                Column {
                    required property var modelData
                    width: list.width
                    spacing: 8
                    Item {
                        visible: modelData.title !== ""
                        width: parent.width; height: 24
                        Text { id: st; anchors.verticalCenter: parent.verticalCenter; text: modelData.title.toUpperCase(); color: Theme.muted; font.family: Theme.onest; font.weight: Font.Bold; font.pixelSize: 12; font.letterSpacing: 1 }
                        Rectangle { anchors { left: st.right; leftMargin: 10; right: parent.right; verticalCenter: parent.verticalCenter } height: 1; color: Theme.line }
                    }
                    Repeater {
                        model: modelData.rows
                        Btn {
                            id: row
                            required property var modelData
                            width: list.width
                            height: rc.implicitHeight + 22
                            hoverUp: 1
                            onClicked: { const id = modelData.id, p = vp.page; vp.open = false; p.chooseVersion(id) }
                            Rectangle {
                                anchors.fill: parent
                                radius: 14
                                color: row.modelData.isLastPlayed ? (row.hovered ? "#332563EB" : "#202563EB") : (Theme.simple ? (row.hovered ? "#1B2640" : "#141B2C") : (row.hovered ? Theme.glassFillHover : Theme.glassFill))
                                border.color: row.modelData.isLastPlayed ? (row.hovered ? "#9960A5FA" : Theme.accentEdge) : (row.hovered ? Theme.glassEdgeHover : Theme.glassEdge)
                                border.width: row.modelData.isLastPlayed ? 1.5 : 1
                            }
                            Column {
                                id: rc
                                x: 14; y: 11; width: parent.width - 28
                                Item {
                                    width: parent.width; height: Math.max(hd.implicitHeight, rightBox.implicitHeight)
                                    Text {
                                        id: hd
                                        anchors.left: parent.left
                                        anchors.right: rightBox.left
                                        anchors.rightMargin: 12
                                        anchors.verticalCenter: parent.verticalCenter
                                        text: row.modelData.heading
                                        color: "white"
                                        font.family: Theme.onest
                                        font.weight: Font.DemiBold
                                        font.pixelSize: 15
                                        wrapMode: Text.Wrap
                                    }
                                    Row {
                                        id: rightBox
                                        anchors.right: parent.right
                                        anchors.verticalCenter: parent.verticalCenter
                                        spacing: 8
                                        Rectangle {
                                            visible: !!row.modelData.isLastPlayed
                                            anchors.verticalCenter: parent.verticalCenter
                                            height: 20
                                            radius: 10
                                            color: "#332563EB"
                                            border.color: Theme.accentEdge
                                            width: lastPlayedRow.width + 14
                                            Row {
                                                id: lastPlayedRow
                                                anchors.centerIn: parent
                                                spacing: 5
                                                Rectangle {
                                                    width: 6; height: 6; radius: 3; color: Theme.accent
                                                    anchors.verticalCenter: parent.verticalCenter
                                                }
                                                Text {
                                                    text: Ui.tx("library.poslednyaya_versiya")
                                                    color: Theme.accent
                                                    font.family: Theme.onest
                                                    font.weight: Font.SemiBold
                                                    font.pixelSize: 11
                                                    anchors.verticalCenter: parent.verticalCenter
                                                }
                                            }
                                        }
                                        Text {
                                            id: sz
                                            anchors.verticalCenter: parent.verticalCenter
                                            text: row.modelData.size
                                            color: Theme.muted
                                            font.family: Theme.onest
                                            font.pixelSize: 13
                                        }
                                    }
                                }
                                Text { topPadding: 3; width: parent.width; text: row.modelData.name; color: Theme.muted; font.family: Theme.onest; font.pixelSize: 12; elide: Text.ElideRight }
                                Item {
                                    visible: row.modelData.fraction > 0
                                    width: parent.width; height: 11
                                    Rectangle {
                                        y: 8; width: parent.width; height: 3; radius: 2; color: "#202636"
                                        Rectangle { width: parent.width * row.modelData.fraction; height: 3; radius: 2; color: Theme.accent }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    Item {
        width: parent.width; height: 16 + 38
        OutlineButton { anchors.right: parent.right; anchors.bottom: parent.bottom; text: Ui.tx("library.zakryt"); onClicked: vp.open = false }
    }
}
