import QtQuick

// Фильм из «Подбора по настроению»: временный постер TMDB, рейтинг, «В медиатеке», название, год и оригинал,
// причина от ИИ янтарным, внизу — «Смотреть» (уже есть) или «Найти раздачу» (поиск по трекерам этим названием).
// Постер — image://suslik/tmp:…: только в памяти (PosterCache), папка постеров медиатеки от подборов не растёт.
Item {
    id: card
    property var movie: ({})
    signal playRequested(var magnetId, string path)
    signal searchTrackers(string query)

    readonly property bool inLib: !!movie.inLibrary
    readonly property int posterH: Math.round(width * 1.5)
    /// Высота под постер, две строки названия, год, жанры и три строки причины: все карточки ряда одной высоты.
    readonly property int fullHeight: posterH + 202
    implicitHeight: fullHeight
    // наведённая карточка с увеличением и рамкой — поверх соседей
    z: hover.hovered ? 2 : 1

    HoverHandler { id: hover }

    Rectangle {
        id: bg
        anchors.fill: parent
        radius: 16
        color: Theme.film
        border.color: hover.hovered ? Theme.line2 : Theme.line
        Behavior on border.color { ColorAnimation { duration: 200 } }
        scale: hover.hovered && !Theme.simple ? 1.015 : 1
        Behavior on scale { NumberAnimation { duration: 260; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }

        // ---- постер ----
        Item {
            id: posterBox
            x: 1; y: 1
            width: parent.width - 2
            height: card.posterH
            // заглушка, пока постер грузится (или его нет)
            Rectangle {
                anchors.fill: parent
                radius: 15
                color: "#141822"
                visible: poster.status !== Image.Ready
                Icon { anchors.centerIn: parent; name: "film"; size: 34; color: Theme.line2 }
            }
            RoundedImage {
                id: poster
                anchors.fill: parent
                radius: 15
                source: card.movie.poster ? "image://suslik/tmp:" + card.movie.poster : ""
                sourceSize: Qt.size(240, 360)
                zoom: hover.hovered && !Theme.simple ? 1.05 : 1
                Behavior on zoom { NumberAnimation { duration: 320; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
            }
            // тень снизу, чтобы постер мягко переходил в карточку
            Rectangle {
                anchors { left: parent.left; right: parent.right; bottom: parent.bottom }
                height: 46
                gradient: Gradient {
                    GradientStop { position: 0; color: "transparent" }
                    GradientStop { position: 1; color: "#B3181C26" }
                }
            }

            Flow {
                anchors { top: parent.top; left: parent.left; right: parent.right; margins: 8 }
                spacing: 6
                Plate {
                    visible: !!card.movie.rating
                    fg: Theme.lamp
                    icon: "star"
                    text: card.movie.rating || ""
                }
                Plate {
                    visible: card.inLib
                    fg: Theme.ok
                    bg: "#E00F2B1D"
                    edge: "#27533F"
                    icon: "check"
                    text: Ui.tx("discover.in_library")
                }
                Plate {
                    visible: !!card.movie.series
                    text: Ui.tx("discover.series")
                }
            }
        }

        // ---- название, год, причина ----
        Column {
            id: info
            anchors { top: posterBox.bottom; left: parent.left; right: parent.right; bottom: btn.top; topMargin: 10; leftMargin: 12; rightMargin: 12; bottomMargin: 8 }
            // крупный шрифт Windows или длинный текст — обрезается над кнопкой, а не наползает на неё
            clip: true
            spacing: 3
            Text {
                width: parent.width
                text: card.movie.title || ""
                color: Theme.text
                font.family: Theme.unbounded; font.weight: Font.Medium; font.pixelSize: 13
                wrapMode: Text.Wrap; maximumLineCount: 2; elide: Text.ElideRight
            }
            Text {
                width: parent.width
                text: (card.movie.year ? card.movie.year : "") + (card.movie.original ? (card.movie.year ? " · " : "") + card.movie.original : "")
                color: Theme.muted
                font.family: Theme.onest; font.pixelSize: 12
                elide: Text.ElideRight
            }
            Text {
                visible: !!card.movie.genres
                width: parent.width
                text: card.movie.genres || ""
                color: Theme.dim
                font.family: Theme.onest; font.pixelSize: 11
                elide: Text.ElideRight
            }
            // «Почему подходит»: янтарная искра и фраза ИИ
            Item {
                visible: !!card.movie.reason
                width: parent.width
                height: reason.implicitHeight + 6
                Icon { y: 8; name: "sparkle"; size: 11; color: Theme.lamp; opacity: 0.9 }
                Text {
                    id: reason
                    x: 17; y: 6
                    width: parent.width - 17
                    text: card.movie.reason || ""
                    color: Theme.lamp
                    font.family: Theme.onest; font.pixelSize: 12
                    lineHeight: 1.15
                    wrapMode: Text.Wrap; maximumLineCount: 3; elide: Text.ElideRight
                }
            }
        }

        // ---- действие ----
        Btn {
            id: btn
            anchors { left: parent.left; right: parent.right; bottom: parent.bottom; margins: 12 }
            height: 36
            hoverUp: 1.02
            Rectangle {
                anchors.fill: parent
                radius: 10
                color: card.inLib ? (btn.hovered ? Theme.accentHover : Theme.accent) : (btn.hovered ? "#282E3C" : "#1B1F2A")
                border.color: card.inLib ? (btn.hovered ? "#8DB6FF" : Theme.accentEdge) : (btn.hovered ? Theme.line2 : Theme.line)
                Behavior on color { ColorAnimation { duration: 200 } }
            }
            Row {
                anchors.centerIn: parent
                spacing: 7
                Icon { anchors.verticalCenter: parent.verticalCenter; name: card.inLib ? "play" : "lightning"; size: 12; color: "white" }
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: card.inLib ? Ui.tx("discover.watch") : Ui.tx("discover.find")
                    color: "white"
                    font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 13
                }
            }
            onClicked: {
                if (card.inLib) card.playRequested(card.movie.magnetId, card.movie.path || "")
                else card.searchTrackers(card.movie.searchTitle + (card.movie.year ? " " + card.movie.year : ""))
            }
        }
    }

    /// Плашка на постере: значок и подпись на тёмном стекле.
    component Plate: Rectangle {
        id: plate
        property string text
        property string icon
        property color fg: Theme.text
        property color bg: "#D90B0E15"
        property color edge: "#33FFFFFF"
        width: plateRow.width + 14; height: 22
        radius: 11
        color: bg
        border.color: edge
        Row {
            id: plateRow
            anchors.centerIn: parent
            spacing: 4
            Icon { visible: plate.icon !== ""; anchors.verticalCenter: parent.verticalCenter; name: plate.icon; size: 10; filled: true; color: plate.fg }
            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: plate.text
                color: plate.fg
                font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 11
            }
        }
    }
}
