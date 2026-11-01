import QtQuick

// «Что нового» поверх медиатеки, один раз на установленную версию (WhatsNew.kt). Каждый блок выезжает чуть позже предыдущего.
Sheet {
    id: w
    maxWidth: 560
    pad: 0
    open: updater.whatsNew.length > 0
    onDismissed: updater.whatsNewSeen()

    Item { width: 1; height: 24 }
    Column {
        x: 26; width: parent.width - 52
        Text {
            text: Ui.tx("whatsnew.chto_novogo")
            color: Theme.text
            font.family: Theme.unbounded; font.weight: Font.Medium; font.pixelSize: 22
        }
        Text {
            topPadding: 4; bottomPadding: 16
            text: Ui.tx("whatsnew.suslik_media_obnovilsya_do", { current: updater.current })
            color: Theme.muted
            font.family: Theme.onest; font.pixelSize: 14
        }
    }
    Flickable {
        id: flick
        x: 26; width: parent.width - 52
        height: Math.min(list.height, 460, w.height - 48 - 24 - 60 - 76)
        contentHeight: list.height
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        Column {
            id: list
            width: flick.width
            spacing: 18
            Repeater {
                model: updater.whatsNew
                delegate: Column {
                    id: rel
                    required property var modelData
                    required property int index
                    readonly property bool latest: index === 0
                    width: list.width
                    spacing: 9
                    opacity: 0
                    transform: Translate { id: shift; y: 22 }
                    // каждый следующий — на 110 мс позже
                    SequentialAnimation {
                        running: w.open
                        PauseAnimation { duration: Theme.simple ? 0 : 180 + rel.index * 110 }
                        ParallelAnimation {
                            NumberAnimation { target: rel; property: "opacity"; to: 1; duration: Theme.simple ? 0 : 460; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease }
                            NumberAnimation { target: shift; property: "y"; to: 0; duration: Theme.simple ? 0 : 460; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease }
                        }
                    }
                    Row {
                        spacing: 10
                        Rectangle {
                            anchors.verticalCenter: parent.verticalCenter
                            width: ver.implicitWidth + 18; height: ver.implicitHeight + 6
                            radius: 8
                            color: rel.latest ? Theme.accent : Theme.glassFill
                            Text {
                                id: ver
                                anchors.centerIn: parent
                                text: rel.modelData.version
                                color: rel.latest ? "white" : Theme.muted
                                font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 12
                            }
                        }
                        Text {
                            anchors.verticalCenter: parent.verticalCenter
                            text: rel.modelData.title
                            color: Theme.text
                            font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 16
                        }
                    }
                    Repeater {
                        model: rel.modelData.items
                        delegate: Item {
                            width: list.width
                            height: line.implicitHeight
                            Rectangle {
                                y: 7; width: 5; height: 5; radius: 3
                                color: rel.latest ? "#60A5FA" : Theme.line2
                            }
                            Text {
                                id: line
                                x: 15; width: parent.width - 15
                                text: modelData
                                color: rel.latest ? Theme.text : Theme.muted
                                wrapMode: Text.WordWrap; lineHeight: 1.15
                                font.family: Theme.onest; font.pixelSize: 14
                            }
                        }
                    }
                }
            }
        }
    }
    Item {
        width: parent.width; height: 76
        LampButton {
            anchors { right: parent.right; rightMargin: 26; top: parent.top; topMargin: 20 }
            text: Ui.tx("whatsnew.ponyatno")
            onClicked: updater.whatsNewSeen()
        }
    }
}
