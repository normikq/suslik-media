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
                            Glass { anchors.fill: parent; hovered: row.hovered }
                            Column {
                                id: rc
                                x: 14; y: 11; width: parent.width - 28
                                Item {
                                    width: parent.width; height: hd.implicitHeight
                                    Text { id: hd; width: parent.width - sz.implicitWidth - 12; text: row.modelData.heading; color: "white"; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 15; wrapMode: Text.Wrap }
                                    Text { id: sz; anchors.right: parent.right; text: row.modelData.size; color: Theme.muted; font.family: Theme.onest; font.pixelSize: 13 }
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
