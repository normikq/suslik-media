import QtQuick

// Плитка-постер (LibraryView.kt: Tile): наведение поднимает и чуть увеличивает, детали появляются снизу,
// в углах — «Мои фильмы» и избранное, справа сверху — версии и отметки (Dolby Vision, HDR, DTS); правая кнопка — меню.
Item {
    id: tile
    property var t: ({})
    property var page
    signal flyTo(int tab, var rect, string poster)   // постер летит в раздел боковой панели

    readonly property bool hovered: hover.hovered
    height: width * 1.5

    HoverHandler { id: hover }

    Item {
        id: card
        anchors.fill: parent
        transform: Translate { y: tile.hovered ? -6 : 0; Behavior on y { NumberAnimation { duration: 280; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } } }
        scale: tile.hovered ? 1.025 : 1
        Behavior on scale { NumberAnimation { duration: 280; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }

        Rounded {
            anchors.fill: parent
            radius: 14
            Rectangle { anchors.fill: parent; color: "#13161F" }
            Rectangle {
                anchors.fill: parent
                visible: poster.status !== Image.Ready
                gradient: Gradient { GradientStop { position: 0; color: "#222838" } GradientStop { position: 1; color: "#10131B" } }
            }
            Image {
                id: poster
                anchors.fill: parent
                source: tile.t.poster ? "image://suslik" + tile.t.poster : ""
                sourceSize: Qt.size(360, 540)
                fillMode: Image.PreserveAspectCrop
                asynchronous: true
                smooth: true
                opacity: status === Image.Ready ? 1 : 0
                Behavior on opacity { NumberAnimation { duration: 200 } }
                scale: tile.hovered ? 1.06 : 1
                Behavior on scale { NumberAnimation { duration: 360; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
            }
            // тень сверху под кнопками в углу
            Rectangle {
                width: parent.width; height: 60
                gradient: Gradient { GradientStop { position: 0; color: "#99000000" } GradientStop { position: 1; color: "transparent" } }
            }
            // низ: тёмный переход, название и (при наведении или пока не готово) детали
            Rectangle {
                id: bottom
                anchors.bottom: parent.bottom
                width: parent.width
                height: info.height + 22
                gradient: Gradient {
                    GradientStop { position: 0; color: "transparent" }
                    GradientStop { position: 0.5; color: "#CC0B0E14" }
                    GradientStop { position: 1; color: "#FD0B0E14" }
                }
            }
            // полоска внизу: прогресс просмотра или загрузки
            Rectangle {
                visible: (tile.t.bar || 0) > 0
                anchors.bottom: parent.bottom
                width: parent.width; height: 3
                color: "#55000000"
                Rectangle {
                    width: parent.width * Math.min(1, tile.t.bar || 0); height: parent.height
                    gradient: Gradient {
                        orientation: Gradient.Horizontal
                        GradientStop { position: 0; color: "#0284C7" }
                        GradientStop { position: 0.5; color: "#38BDF8" }
                        GradientStop { position: 1; color: "#7DD3FC" }
                    }
                }
            }
        }
        Column {
            id: info
            // текст — поверх слоя со скруглением: в слое (текстура) он размывался
            x: 12
            anchors.bottom: parent.bottom; anchors.bottomMargin: 11
            width: parent.width - 24
            Text {
                width: parent.width
                text: tile.t.title || ""
                color: "white"
                font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 14
                lineHeight: 17; lineHeightMode: Text.FixedHeight
                maximumLineCount: 2; wrapMode: Text.Wrap; elide: Text.ElideRight
            }
            Item {
                id: details
                readonly property bool shown: tile.hovered || !tile.t.ready
                width: parent.width
                height: shown ? detailsCol.implicitHeight : 0
                opacity: shown ? 1 : 0
                clip: true
                Behavior on height { NumberAnimation { duration: details.shown ? 240 : 200; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
                Behavior on opacity { NumberAnimation { duration: details.shown ? 200 : 140 } }
                Column {
                    id: detailsCol
                    width: parent.width
                    Text { topPadding: 2; bottomPadding: 5; text: tile.t.subtitle || ""; color: "#9BA4B5"; font.family: Theme.onest; font.pixelSize: 12 }
                    Rectangle {
                        width: st.implicitWidth + 14; height: st.implicitHeight + 4
                        radius: 6; color: "#181B24"; border.color: "#2A3040"
                        Text { id: st; anchors.centerIn: parent; text: tile.t.status || ""; color: "white"; font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 11 }
                    }
                    Text { topPadding: 5; width: parent.width; elide: Text.ElideRight; text: tile.t.specs1 || ""; color: "#C0C7D5"; font.family: Theme.onest; font.pixelSize: 11 }
                    Text { topPadding: 1; width: parent.width; elide: Text.ElideRight; text: tile.t.specs2 || ""; color: "#7A8294"; font.family: Theme.onest; font.pixelSize: 11 }
                }
            }
        }
        Rectangle {
            anchors.fill: parent
            radius: 14
            color: "transparent"
            border.color: tile.hovered ? "#5A6680" : "#202430"
            Behavior on border.color { ColorAnimation { duration: 240 } }
        }

        // большой треугольник с белым свечением по центру при наведении
        Item {
            visible: tile.t.canPlay && opacity > 0.005
            anchors.centerIn: parent
            width: 56; height: 56
            opacity: tile.hovered ? 1 : 0
            scale: tile.hovered ? 1.05 : 0.72
            Behavior on opacity { NumberAnimation { duration: 240; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
            Behavior on scale { NumberAnimation { duration: 260; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
            Repeater {
                model: 5
                Rectangle {
                    required property int index
                    anchors.centerIn: parent
                    width: 76 * (1 - index / 5); height: width; radius: width / 2
                    color: "white"
                    opacity: [0.02, 0.04, 0.06, 0.08, 0.1][index]
                }
            }
            Icon { anchors.centerIn: parent; name: "play"; size: 32; color: "white" }
        }

        MouseArea {
            anchors.fill: parent
            acceptedButtons: Qt.LeftButton | Qt.RightButton
            cursorShape: tile.t.canPlay ? Qt.PointingHandCursor : Qt.ArrowCursor
            onClicked: (m) => {
                if (m.button === Qt.RightButton) menu.popup()
                else if (tile.t.canPlay) tile.page.open(tile.t.id)
            }
        }

        // «Мои фильмы» и избранное: видны всегда, если включены, иначе — при наведении
        Row {
            x: 8; y: 8
            spacing: 6
            CornerButton {
                on: !!tile.t.mine; tileHovered: tile.hovered
                Icon { anchors.centerIn: parent; name: "bookmark"; filled: !!tile.t.mine; size: 15; color: tile.t.mine ? "#7FB2FF" : "white" }
                onClicked: tile.toggleMine()
            }
            CornerButton {
                on: !!tile.t.favorite; tileHovered: tile.hovered
                Icon { anchors.centerIn: parent; name: "heart"; filled: !!tile.t.favorite; size: 16; color: tile.t.favorite ? "#FF6B8A" : "white" }
                onClicked: tile.page.setFavoriteGroup(tile.t.id, !tile.t.favorite)
            }
        }
        Column {
            anchors { right: parent.right; top: parent.top; margins: 8 }
            spacing: 4
            Rectangle {
                visible: !!tile.t.versions
                anchors.right: parent.right
                width: vt.implicitWidth + 16; height: vt.implicitHeight + 8
                radius: 10; color: "#99000000"; border.color: "#33FFFFFF"
                Text { id: vt; anchors.centerIn: parent; text: tile.t.versions || ""; color: "white"; font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 11 }
            }
            Repeater {
                model: tile.t.tags || []
                Rectangle {
                    required property string modelData
                    readonly property color c: modelData.startsWith("Dolby") ? "#B794F6" : modelData === "HDR" ? "#FFC857" : "#7DD3FC"
                    anchors.right: parent.right
                    width: tg.implicitWidth + 18; height: 22
                    radius: 8; color: "#CC0E0B1A"
                    border.color: Qt.rgba(c.r, c.g, c.b, 0.7)
                    Text { id: tg; anchors.centerIn: parent; text: modelData; color: parent.c; font.family: Theme.onest; font.weight: Font.Bold; font.pixelSize: 12 }
                }
            }
        }
    }

    function toggleMine() {
        // добавили — постер летит в «Мои фильмы»; убрали там — обратно в «Библиотеку»
        const to = !t.mine ? 1 : (page.mine ? 0 : -1)
        if (to >= 0) tile.flyTo(to, tile.mapToItem(null, 0, 0, tile.width, tile.height), t.poster || "")
        page.setMineGroup(t.id, !t.mine)
    }

    SmMenu {
        id: menu
        // сезоны сериала: плитка показывает и включает выбранный
        Instantiator {
            model: tile.t.seasons || []
            delegate: SmMenuItem { required property int index; required property string modelData; text: modelData; onTriggered: tile.page.chooseSeason(tile.t.id, index) }
            onObjectAdded: (i, o) => menu.insertItem(i, o)
            onObjectRemoved: (i, o) => menu.removeItem(o)
        }
        SmMenuItem { text: tile.t.mineLabel || ""; onTriggered: tile.page.setMineGroup(tile.t.id, !tile.t.mine) }
        SmMenuItem { text: tile.t.favLabel || ""; onTriggered: tile.page.setFavoriteGroup(tile.t.id, !tile.t.favorite) }
        SmMenuItem {
            visible: !!tile.t.aiAsk; height: visible ? implicitHeight : 0
            text: tile.t.aiAsk || ""; enabled: !!tile.t.aiAskEnabled
            onTriggered: tile.page.askAi(tile.t.id)
        }
        SmMenuItem {
            visible: !!tile.t.aiKnown; height: visible ? implicitHeight : 0
            text: tile.t.aiKnown || ""
            onTriggered: tile.page.showAi(tile.t.id)
        }
        SmMenuItem {
            visible: !!tile.t.canRemove; height: visible ? implicitHeight : 0
            text: tile.t.removeLabel || ""; fg: Theme.bad
            onTriggered: lib.remove(tile.t.id)
        }
    }
}
