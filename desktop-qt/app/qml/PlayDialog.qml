import QtQuick
import QtQuick.Controls.Basic as C
import Suslik.Native

// «Смотреть» (PlayDialog.kt): фильм — постер, факты, «с места» или «с начала», одна кнопка; сериал — то же и выбор сезона
// и серии; сборник — список фильмов. Ссылки готовит PlayModel, кнопка включается, когда готово всё.
Sheet {
    id: d
    readonly property alias model: m
    signal play()
    maxWidth: m.kind === 2 ? 660 : m.kind === 1 ? 540 : 620
    onDismissed: open = false
    function show(magnetId, path) { m.load(magnetId, path); open = true }

    PlayModel {
        id: m
        library: lib
        onPlayRequested: { d.open = false; d.play() }
    }

    // Содержимое прокручивается: на маленьком экране (1366×768, масштаб 125–150 %) карточка не выше окна,
    // нижние кнопки не вылезают за неё
    Flickable {
        id: scroller
        width: parent.width
        height: Math.min(content.implicitHeight, d.height - 48 - d.pad * 2)
        contentHeight: content.implicitHeight
        clip: true
        interactive: contentHeight > height + 1
        boundsBehavior: Flickable.StopAtBounds
        WheelHandler {
            enabled: scroller.interactive
            acceptedDevices: PointerDevice.Mouse | PointerDevice.TouchPad
            onWheel: (ev) => {
                const dy = ev.pixelDelta.y !== 0 ? ev.pixelDelta.y : ev.angleDelta.y / 120 * 90
                scroller.contentY = Math.max(0, Math.min(scroller.contentHeight - scroller.height, scroller.contentY - dy))
            }
        }
        Connections { target: d; function onOpenChanged() { if (d.open) scroller.contentY = 0 } }
        Column {
            id: content
            width: scroller.width

            // ---- фильм и сериал: постер слева, название, факты, описание ----
            Item {
                visible: m.kind !== 2
                width: parent.width
                height: Math.max(225, info.implicitHeight)
                Rounded {
                    id: poster
                    width: 150; height: 225
                    radius: 14
                    Rectangle { anchors.fill: parent; gradient: Gradient { GradientStop { position: 0; color: Theme.glassEdge } GradientStop { position: 1; color: "#10131B" } } }
                    Image { anchors.fill: parent; source: m.poster ? "image://suslik" + m.poster : ""; sourceSize: Qt.size(300, 450); fillMode: Image.PreserveAspectCrop; asynchronous: true }
                }
                Rectangle { anchors.fill: poster; radius: 14; color: "transparent"; border.color: Theme.glassEdge }
                Column {
                    id: info
                    anchors { left: poster.right; leftMargin: 20; right: parent.right; rightMargin: 40 }
                    Text {
                        width: parent.width
                        text: m.heading
                        color: "white"
                        font.family: Theme.unbounded; font.weight: Font.Bold; font.pixelSize: 22
                        lineHeight: 28; lineHeightMode: Text.FixedHeight
                        maximumLineCount: 3; wrapMode: Text.Wrap; elide: Text.ElideRight
                    }
                    Flow {
                        topPadding: 12
                        width: parent.width
                        spacing: 8
                        Repeater { model: m.chips; DChip { required property string modelData; text: modelData } }
                        Text { visible: m.rating !== ""; height: 26; verticalAlignment: Text.AlignVCenter; text: "★ " + m.rating; color: "#FACC15"; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 13 }
                    }
                    Text {
                        visible: m.overview !== ""
                        topPadding: 14
                        width: parent.width
                        text: m.overview
                        color: "#C3C9D6"; font.family: Theme.onest; font.pixelSize: 14
                        lineHeight: 21; lineHeightMode: Text.FixedHeight
                        maximumLineCount: 5; wrapMode: Text.Wrap; elide: Text.ElideRight
                    }
                }
                CloseCircle { anchors.right: parent.right; onClicked: d.open = false }
            }

            // ---- сборник: постер, название, подсказка, список фильмов ----
            Item {
                visible: m.kind === 2
                width: parent.width
                height: Math.max(245, coll.implicitHeight)
                Rounded {
                    id: cposter
                    width: 155; height: 245
                    radius: 14
                    Rectangle { anchors.fill: parent; gradient: Gradient { GradientStop { position: 0; color: Theme.glassEdge } GradientStop { position: 1; color: "#10131B" } } }
                    Image { anchors.fill: parent; source: m.poster ? "image://suslik" + m.poster : ""; sourceSize: Qt.size(310, 490); fillMode: Image.PreserveAspectCrop; asynchronous: true }
                }
                Rectangle { anchors.fill: cposter; radius: 14; color: "transparent"; border.color: Theme.glassEdge }
                Column {
                    id: coll
                    anchors { left: cposter.right; leftMargin: 18; right: parent.right }
                    Item {
                        width: parent.width; height: ch.implicitHeight
                        Column {
                            id: ch
                            width: parent.width - 44
                            Text { width: parent.width; text: m.heading; color: "white"; font.family: Theme.unbounded; font.weight: Font.Bold; font.pixelSize: 20; wrapMode: Text.Wrap }
                            Text { topPadding: 4; text: m.collectionLine; color: Theme.muted; font.family: Theme.onest; font.pixelSize: 14 }
                        }
                        CloseCircle { anchors.right: parent.right; onClicked: d.open = false }
                    }
                    Item { width: 1; height: 10 }
                    Rectangle {
                        width: parent.width; height: hint.implicitHeight + 16
                        radius: 10; color: Theme.glassFill; border.color: Theme.glassEdge
                        Row {
                            x: 12; anchors.verticalCenter: parent.verticalCenter
                            spacing: 8
                            Icon { name: "info"; size: 15; color: "#60A5FA"; anchors.verticalCenter: parent.verticalCenter }
                            Text { id: hint; text: m.collectionHint; color: "#C0C7D6"; font.family: Theme.onest; font.pixelSize: 13 }
                        }
                    }
                    Item { width: 1; height: 10 }
                    Flickable {
                        id: filmsList
                        width: parent.width
                        height: Math.min(240, filmsCol.height)
                        contentHeight: filmsCol.height
                        clip: true
                        boundsBehavior: Flickable.StopAtBounds
                        Column {
                            id: filmsCol
                            width: parent.width
                            spacing: 7
                            Repeater {
                                model: m.films
                                Btn {
                                    id: fr
                                    required property var modelData
                                    required property int index
                                    readonly property bool sel: index === m.filmIndex
                                    width: filmsCol.width; height: 58
                                    hoverUp: 1
                                    onClicked: m.filmIndex = index
                                    Rectangle { anchors.fill: parent; radius: 12; color: fr.sel ? Theme.accentSoft : Theme.glassFill; border.color: fr.sel ? Theme.accentEdge : Theme.glassEdge }
                                    Row {
                                        x: 10; anchors.verticalCenter: parent.verticalCenter
                                        spacing: 10
                                        Item {
                                            width: 34; height: 44
                                            readonly property string p: fr.modelData.poster || m.poster
                                            Rectangle { anchors.fill: parent; radius: 6; color: "#181D2A" }
                                            RoundedImage { anchors.fill: parent; radius: 6; source: parent.p ? "image://suslik" + parent.p : ""; sourceSize: Qt.size(68, 88) }
                                        }
                                        Rectangle {
                                            width: 24; height: 24; radius: 6; color: "#1D2638"
                                            anchors.verticalCenter: parent.verticalCenter
                                            Text { anchors.centerIn: parent; text: fr.index + 1; color: "white"; font.family: Theme.onest; font.weight: Font.Bold; font.pixelSize: 12 }
                                        }
                                        Column {
                                            width: fr.width - 10 - 34 - 24 - 20 - 30 - 20
                                            anchors.verticalCenter: parent.verticalCenter
                                            Text { width: parent.width; text: fr.modelData.title; color: "white"; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 14; elide: Text.ElideRight }
                                            Text { text: fr.modelData.details; color: "#7E879B"; font.family: Theme.onest; font.pixelSize: 12 }
                                        }
                                    }
                                    Rectangle { // переключатель
                                        anchors { right: parent.right; rightMargin: 12; verticalCenter: parent.verticalCenter }
                                        width: 20; height: 20; radius: 10; color: "transparent"
                                        border.width: 1.5; border.color: fr.sel ? "#4A80F5" : "#384359"
                                        Rectangle { visible: fr.sel; anchors.centerIn: parent; width: 10; height: 10; radius: 5; color: "#4A80F5" }
                                    }
                                }
                            }
                        }
                        Component.onCompleted: if (m.filmIndex > 2) contentY = Math.min(contentHeight - height, m.filmIndex * 65)
                    }
                }
            }

            // сериал: сначала сезон и серия, потом «Смотреть» — выбрал серию, включил
            Row {
                visible: m.kind === 1
                width: parent.width
                topPadding: 18
                spacing: 12
                Selector { width: (parent.width - 12) / 2; label: Ui.tx("library.sezon"); value: m.seasonValue; items: m.seasons; onPicked: (i) => m.seasonIndex = i }
                Selector { width: (parent.width - 12) / 2; label: Ui.tx("library.seriya"); value: m.episodeValue; items: m.episodes; onPicked: (i) => m.episodeIndex = i }
            }
            // ---- состояние, место, «Смотреть» ----
            Text {
                visible: m.kind !== 2
                topPadding: m.kind === 1 ? 14 : 18; bottomPadding: 8
                width: parent.width
                text: m.status
                color: Theme.muted; font.family: Theme.onest; font.pixelSize: 13
                wrapMode: Text.Wrap
            }
            Item { visible: m.kind === 2; width: 1; height: 16 }
            Row {
                visible: m.resumeLabel !== ""
                width: parent.width
                spacing: 12
                bottomPadding: 12
                // обе карточки одной высоты (у «Продолжить» ещё полоска прогресса)
                StartOption {
                    id: resumeOpt
                    width: (parent.width - 12) / 2
                    height: Math.max(natural, againOpt.natural)
                    title: m.resumeLabel
                    hint: Ui.tx("library.prodolzhit_s_proshlogo_mesta")
                    on: !m.fromStart
                    icon: "play"
                    progress: m.resumeFraction
                    onClicked: m.fromStart = false
                }
                StartOption {
                    id: againOpt
                    width: (parent.width - 12) / 2
                    height: Math.max(natural, resumeOpt.natural)
                    title: Ui.tx("library.det_s_nachala")
                    hint: Ui.tx("library.det_smotret_zanovo")
                    on: m.fromStart
                    glyph: "↺"
                    onClicked: m.fromStart = true
                }
            }
            Btn {
                id: watch
                width: parent.width; height: 52
                hoverUp: 1
                enabled_: m.canWatch
                onClicked: m.play()
                Rectangle {
                    anchors.fill: parent; radius: 12
                    color: !watch.enabled_ ? "#161C28" : watch.hovered ? Theme.accentHover : Theme.accent
                    border.color: !watch.enabled_ ? "#202736" : watch.hovered ? "#4D86FF" : Theme.accentEdge
                }
                Row {
                    anchors.centerIn: parent
                    spacing: 8
                    Icon { name: "play"; size: 12; color: watch.enabled_ ? "white" : "#5A6478"; anchors.verticalCenter: parent.verticalCenter }
                    Text { text: m.loading ? Ui.tx("library.gotovim") : Ui.tx("library.smotret"); color: watch.enabled_ ? "white" : "#5A6478"; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 14 }
                }
            }

            // «Моя медиатека» и избранное
            Row {
                visible: m.kind !== 2
                width: parent.width
                topPadding: 12
                spacing: 12
                ShelfButton {
                    width: (parent.width - 12) / 2
                    on: m.mine
                    text: m.mine ? Ui.tx("library.det_iz_moey_mediateki") : Ui.tx("library.det_v_moyu_mediateku")
                    icon: "bookmark"; iconColor: m.mine ? "#7FB2FF" : "#C4CBD8"
                    onClicked: m.toggleMine()
                }
                ShelfButton {
                    width: (parent.width - 12) / 2
                    on: m.favorite
                    text: m.favorite ? Ui.tx("library.iz_izbrannogo") : Ui.tx("library.det_v_izbrannoe")
                    icon: "heart"; iconColor: m.favorite ? "#FF6B8A" : "#C4CBD8"
                    onClicked: m.toggleFavorite()
                }
            }
            // сборник: «Закрыть» внизу
            Item { visible: m.kind === 2; width: 1; height: 14 }
            Btn {
                id: closeBottom
                visible: m.kind === 2
                width: parent.width; height: 44; hoverUp: 1
                onClicked: d.open = false
                Rectangle { anchors.fill: parent; radius: 12; color: closeBottom.hovered ? "#1C2232" : "#141824"; border.color: closeBottom.hovered ? "#323B50" : "#242A3A" }
                Text { anchors.centerIn: parent; text: Ui.tx("library.zakryt"); color: "white"; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 14 }
            }
        }
    }
    // полоса прокрутки — в поле карточки справа, её можно тянуть и щёлкать
    // (обёртка высотой 1: Column не ставит элементы нулевой высоты)
    Item {
        width: parent.width; height: 1
        C.ScrollBar {
            id: vbar
            visible: scroller.interactive
            orientation: Qt.Vertical
            x: parent.width + 4
            y: -1 - scroller.height
            width: 12; height: scroller.height
            padding: 4
            size: scroller.visibleArea.heightRatio
            position: scroller.visibleArea.yPosition
            onPositionChanged: if (active) scroller.contentY = position * scroller.contentHeight
            minimumSize: 24 / Math.max(1, scroller.height)
            policy: C.ScrollBar.AlwaysOn
            background: Item {}
            contentItem: Rectangle {
                implicitWidth: 4; radius: 2
                color: vbar.pressed ? "#66FFFFFF" : vbar.hovered ? "#4DFFFFFF" : "#33FFFFFF"
            }
        }
    }

    component DChip: Rectangle {
        property alias text: t.text
        width: t.implicitWidth + 20; height: t.implicitHeight + 8
        radius: 8; color: "#1AFFFFFF"; border.color: "#26FFFFFF"
        Text { id: t; anchors.centerIn: parent; color: "#D6DBE4"; font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 13 }
    }

    component StartOption: Btn {
        id: so
        property string title
        property string hint
        property bool on: false
        property string icon
        property string glyph
        property real progress: -1
        hoverUp: 1
        readonly property real natural: soCol.implicitHeight + 28
        height: natural
        Rectangle {
            anchors.fill: parent; radius: 16
            color: so.on ? "#12203F" : Theme.glassFill
            border.color: so.on ? "#3B82F6" : Theme.glassEdge; border.width: so.on ? 1.5 : 1
            Behavior on color { ColorAnimation { duration: 220 } }
        }
        Column {
            id: soCol
            x: 14; width: parent.width - 28
            anchors.verticalCenter: parent.verticalCenter
            Row {
                width: parent.width
                spacing: 12
                Rectangle {
                    width: 40; height: 40; radius: 20
                    color: so.on ? Theme.accent : "#1D2638"
                    Icon { visible: so.icon !== ""; anchors.centerIn: parent; name: so.icon; size: 14; color: "white" }
                    Text { visible: so.glyph !== ""; anchors.centerIn: parent; text: so.glyph; rotation: -90; color: "white"; font.pixelSize: 20 }
                }
                Column {
                    width: parent.width - 52
                    anchors.verticalCenter: parent.verticalCenter
                    Text { width: parent.width; text: so.title; color: "white"; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 15; elide: Text.ElideRight }
                    Text { width: parent.width; text: so.hint; color: Theme.muted; font.family: Theme.onest; font.pixelSize: 12; elide: Text.ElideRight }
                }
            }
            Item {
                visible: so.progress >= 0
                width: parent.width; height: 14 + pct.implicitHeight - 4
                Rectangle {
                    y: 10 + (pct.implicitHeight - 4) / 2
                    width: parent.width - pct.implicitWidth - 10; height: 4; radius: 2; color: "#33FFFFFF"
                    Rectangle { width: parent.width * Math.max(0, Math.min(1, so.progress)); height: 4; radius: 2; color: Theme.accent }
                }
                Text { id: pct; y: 10; anchors.right: parent.right; text: Math.floor(so.progress * 100) + "%"; color: "#C3C9D6"; font.family: Theme.onest; font.pixelSize: 12 }
            }
        }
    }

    component ShelfButton: Btn {
        id: sb
        property string text
        property bool on: false
        property string icon
        property color iconColor
        height: 48; hoverUp: 1
        Rectangle {
            anchors.fill: parent; radius: 12
            color: sb.hovered ? "#1C2232" : sb.on ? "#16203A" : "#141824"
            border.color: sb.hovered ? "#323B50" : sb.on ? "#553B82F6" : "#242A3A"
        }
        Row {
            anchors.centerIn: parent
            spacing: 8
            Icon { name: sb.icon; filled: sb.on; size: 16; color: sb.iconColor; anchors.verticalCenter: parent.verticalCenter }
            Text { text: sb.text; color: "white"; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 14 }
        }
    }

    component Selector: Column {
        id: sel
        property string label
        property string value
        property var items: []
        signal picked(int index)
        Text { bottomPadding: 6; text: sel.label; color: Theme.muted; font.family: Theme.onest; font.pixelSize: 13 }
        Btn {
            id: selBtn
            width: sel.width; height: 46; hoverUp: 1
            onClicked: menu.popup(selBtn, 0, selBtn.height + 4)
            Rectangle { anchors.fill: parent; radius: 12; color: selBtn.hovered ? "#161A26" : Theme.glassFill; border.color: selBtn.hovered ? "#323B50" : Theme.glassEdge }
            Text { x: 14; width: parent.width - 48; anchors.verticalCenter: parent.verticalCenter; text: sel.value; color: "white"; font.family: Theme.onest; font.pixelSize: 14; elide: Text.ElideRight }
            Icon { anchors { right: parent.right; rightMargin: 14; verticalCenter: parent.verticalCenter } name: "chevronDown"; size: 13; color: Theme.muted }
            SmMenu {
                id: menu
                width: selBtn.width
                height: Math.min(implicitHeight, 360)
                Instantiator {
                    model: sel.items
                    delegate: SmMenuItem { required property int index; required property string modelData; text: modelData; onTriggered: sel.picked(index) }
                    onObjectAdded: (i, o) => menu.insertItem(i, o)
                    onObjectRemoved: (i, o) => menu.removeItem(o)
                }
            }
        }
    }
}
