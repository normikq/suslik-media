import QtQuick

// «Продолжить просмотр» (LibraryView.kt: ContinueRow): широкий кадр фильма, логотип или название, описание, прогресс,
// «Продолжить»; справа — постеры остальных начатых фильмов. Баннеры сменяются сами раз в 9 с (не под курсором).
Item {
    id: b
    property var page
    property real roomAbove: 0           // сколько места над баннером до верха страницы (свечение не должно упираться)
    readonly property var entries: page ? page.continueEntries : []
    property var chosen: null
    property var lastShown: null
    readonly property var e: {
        for (const x of entries) if (x.id === chosen) return x
        return entries.length > 0 ? entries[0] : lastShown
    }
    onEChanged: if (e) lastShown = e

    readonly property bool shown: entries.length > 0
    height: shown ? 360 + 6 + 22 : 0
    opacity: shown ? 1 : 0
    visible: height > 0
    Behavior on height { NumberAnimation { duration: b.shown ? 320 : 260; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
    Behavior on opacity { NumberAnimation { duration: b.shown ? 300 : 200 } }

    HoverHandler { id: bannerHover }
    Timer {
        id: advance
        interval: 9000
        running: b.entries.length > 1 && !bannerHover.hovered && b.visible
        repeat: true
        onTriggered: {
            let cur = 0
            for (let i = 0; i < b.entries.length; ++i) if (b.entries[i].id === b.chosen) cur = i
            b.chosen = b.entries[(cur + 1) % b.entries.length].id
        }
    }

    Item {
        id: frame
        y: 6
        width: parent.width; height: 360
        readonly property string backdrop: b.e ? b.e.backdrop : ""

        // свечение: тот же кадр, размытый, с тающими краями, выходит за края баннера на фон страницы
        Image {
            id: glow
            visible: frame.backdrop !== "" && status === Image.Ready && !Theme.simple
            source: frame.backdrop !== "" ? "image://suslik/glow:" + frame.backdrop : ""
            asynchronous: true
            smooth: true
            width: frame.width
            readonly property real glowH: implicitWidth > 0 ? frame.width * implicitHeight / implicitWidth : 360
            height: glowH
            // в широком окне свечение выше баннера: тогда оно (не баннер) опускается ровно настолько, чтобы влезть
            readonly property real shift: {
                const above = (glowH - 360) / 2 - glowH * 0.15 + 22
                return Math.max(0, above - b.roomAbove - 6)
            }
            y: (360 - glowH) / 2 + shift
            opacity: 0.55
        }
        Rounded {
            anchors.fill: parent
            radius: 20
            Rectangle { anchors.fill: parent; color: "#0B1226" }
            Image {
                id: sharp
                source: frame.backdrop !== "" ? "image://suslik" + frame.backdrop : ""
                sourceSize.width: 1920
                asynchronous: true
                smooth: true
                mipmap: true
                // кадр выше баннера: двигается вместе со свечением, чтобы они совпадали
                readonly property real fullH: implicitWidth > 0 ? frame.width * implicitHeight / implicitWidth : 0
                readonly property bool tall: fullH >= 360
                x: 0
                width: frame.width
                y: tall ? Math.min(0, Math.max(360 - fullH, (360 - fullH) / 2 + glow.shift)) : 0
                height: tall ? fullH : 360
                fillMode: tall ? Image.Stretch : Image.PreserveAspectCrop
                horizontalAlignment: Image.AlignRight
                opacity: status === Image.Ready ? 1 : 0
                Behavior on opacity { NumberAnimation { duration: 350 } }
            }
            Rectangle {
                anchors.fill: parent
                gradient: Gradient {
                    orientation: Gradient.Horizontal
                    GradientStop { position: 0; color: "#0B1226" }
                    GradientStop { position: 0.42; color: "#F20B1226" }
                    GradientStop { position: 0.78; color: "#330B1226" }
                    GradientStop { position: 1; color: "#000B1226" }
                }
            }
            Rectangle {
                anchors.fill: parent
                gradient: Gradient {
                    GradientStop { position: 0.6; color: "transparent" }
                    GradientStop { position: 1; color: "#AA0B1226" }
                }
            }
        }
        Rectangle { anchors.fill: parent; radius: 20; color: "transparent"; border.color: Theme.glassEdge }

        readonly property real thumbsW: b.entries.length > 1 ? Math.min(420, width * 0.45) : 0
        readonly property real textW: Math.max(260, Math.min(560, width - thumbsW - (b.entries.length > 1 ? 60 : 0)))

        Item {
            x: 28; y: 24
            width: frame.textW - 28
            height: parent.height - 48
            Column {
                width: parent.width
                Text {
                    text: Ui.tx("library.prodolzhit_prosmotr").toUpperCase()
                    color: "#9AA3B8"
                    font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 12; font.letterSpacing: 1.6
                }
                // логотип фильма (прозрачная картинка), как у стриминговых сервисов; без него — название
                Item {
                    width: parent.width
                    height: b.e && b.e.logo ? 76 : titleText.height + 8
                    Image {
                        visible: !!(b.e && b.e.logo)
                        y: 8; height: 68
                        width: Math.min(360, parent.width)
                        source: b.e && b.e.logo ? "image://suslik" + b.e.logo : ""
                        sourceSize.height: 136
                        fillMode: Image.PreserveAspectFit
                        horizontalAlignment: Image.AlignLeft
                        asynchronous: true
                        smooth: true; mipmap: true
                    }
                    Text {
                        id: titleText
                        visible: !(b.e && b.e.logo)
                        y: 8
                        width: parent.width
                        text: b.e ? b.e.title : ""
                        color: "white"
                        font.family: Theme.onest; font.weight: Font.Bold; font.pixelSize: 32
                        lineHeight: 36; lineHeightMode: Text.FixedHeight
                        maximumLineCount: 2; wrapMode: Text.Wrap; elide: Text.ElideRight
                    }
                }
                Flow {
                    topPadding: 10
                    width: parent.width
                    spacing: 8
                    Repeater {
                        model: b.e ? b.e.chips : []
                        Rectangle {
                            required property string modelData
                            width: ct.implicitWidth + 20; height: ct.implicitHeight + 8
                            radius: 8; color: "#1AFFFFFF"; border.color: "#26FFFFFF"
                            Text { id: ct; anchors.centerIn: parent; text: modelData; color: "#D6DBE4"; font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 13 }
                        }
                    }
                    Text {
                        visible: !!(b.e && b.e.rating)
                        height: 26; verticalAlignment: Text.AlignVCenter
                        text: "★ " + (b.e ? b.e.rating : "")
                        color: "#FACC15"
                        font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 13
                    }
                }
                Text {
                    visible: !!(b.e && b.e.overview)
                    topPadding: 12
                    width: parent.width
                    text: b.e ? b.e.overview : ""
                    color: "#C3C9D6"
                    font.family: Theme.onest; font.pixelSize: 15
                    lineHeight: 21; lineHeightMode: Text.FixedHeight
                    maximumLineCount: 3; wrapMode: Text.Wrap; elide: Text.ElideRight
                }
            }
            Column {
                anchors.bottom: parent.bottom
                width: parent.width
                Item {
                    width: parent.width; height: sub.implicitHeight + 6
                    Text {
                        id: sub
                        width: parent.width - (left.visible ? left.implicitWidth + 12 : 0)
                        text: b.e ? (b.e.sub || b.e.label) : ""
                        color: "#C3C9D6"; font.family: Theme.onest; font.pixelSize: 13
                        elide: Text.ElideRight
                    }
                    Text {
                        id: left
                        visible: !!(b.e && b.e.left)
                        anchors.right: parent.right
                        text: b.e && b.e.left ? b.e.left : ""
                        color: Theme.muted; font.family: Theme.onest; font.pixelSize: 13
                    }
                }
                Rectangle {
                    width: parent.width; height: 4; radius: 2
                    color: "#33FFFFFF"
                    Rectangle {
                        width: parent.width * Math.max(0, Math.min(1, b.e ? b.e.fraction : 0)); height: 4; radius: 2
                        gradient: Gradient { orientation: Gradient.Horizontal; GradientStop { position: 0; color: "#2563EB" } GradientStop { position: 1; color: "#38BDF8" } }
                    }
                }
                Item { width: 1; height: 16 }
                Btn {
                    id: play
                    hoverUp: 1
                    width: playRow.implicitWidth + 52; height: 44
                    Rectangle {
                        anchors.fill: parent; radius: 12
                        color: play.hovered ? Theme.accentHover : Theme.accent
                        border.color: Theme.accentEdge
                        Behavior on color { ColorAnimation { duration: 180 } }
                    }
                    Row {
                        id: playRow
                        anchors.centerIn: parent
                        spacing: 10
                        Text { text: "▶"; color: "white"; font.pixelSize: 13; anchors.verticalCenter: parent.verticalCenter }
                        Text { text: Ui.tx("library.prodolzhit"); color: "white"; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 15 }
                    }
                    onClicked: if (b.e) b.page.openContinue(b.e.id, b.e.path)
                }
            }
        }

        // остальные начатые фильмы: щелчок выбирает; показанного в баннере здесь нет
        Item {
            visible: b.entries.length > 1
            anchors { right: parent.right; rightMargin: 14; bottom: parent.bottom; bottomMargin: 18 }
            width: Math.min(frame.thumbsW, thumbs.implicitWidth); height: 150
            clip: true
            Row {
                id: thumbs
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                spacing: 10
                Repeater {
                    model: b.entries
                    Thumb {
                        required property var modelData
                        x_: modelData
                        visible: !b.e || modelData.id !== b.e.id
                        onChoose: { b.chosen = modelData.id; advance.restart() }
                        onRemove: lib.hideContinue([modelData.id])
                    }
                }
            }
        }
    }

    component Thumb: Item {
        id: th
        property var x_
        signal choose()
        signal remove()
        width: 96; height: 147
        HoverHandler { id: th_hover }
        Item {
            width: 96; height: 144
            y: th_hover.hovered ? 0 : 3
            Behavior on y { NumberAnimation { duration: 220; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
            Rounded {
                anchors.fill: parent
                radius: 10
                Rectangle { anchors.fill: parent; color: "#222736" }
                Image {
                    anchors.fill: parent
                    source: th.x_ && th.x_.poster ? "image://suslik" + th.x_.poster : ""
                    sourceSize: Qt.size(192, 288)
                    fillMode: Image.PreserveAspectCrop
                    asynchronous: true
                }
                Rectangle { anchors.bottom: parent.bottom; height: 3; width: parent.width * Math.max(0, Math.min(1, th.x_ ? th.x_.fraction : 0)); color: "#38BDF8" }
            }
            Rectangle { anchors.fill: parent; radius: 10; color: "transparent"; border.color: th_hover.hovered ? "#66FFFFFF" : "#26FFFFFF" }
            MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: th.choose() }
            Btn {
                visible: th_hover.hovered
                anchors { right: parent.right; top: parent.top; margins: 3 }
                width: 20; height: 20; hoverUp: 1
                Rectangle { anchors.fill: parent; radius: 10; color: "#B3000000" }
                Icon { anchors.centerIn: parent; name: "close"; size: 8; color: "white" }
                onClicked: th.remove()
            }
        }
    }
}
