import QtQuick

// Боковая панель (Sidebar.kt): логотип, разделы, «Коллекция» со счётчиками, «Сервис», профиль, свернуть / развернуть.
Rectangle {
    id: side
    // 0 — главная, 1 — моя медиатека, 2 — поиск раздач, 3 — magnet / торрент, 4 — совместный просмотр
    property int tab: 0
    property int category: 0          // категория текущей страницы медиатеки
    property var glowTab: -1          // сюда только что прилетел постер
    signal select(int tab)
    signal chooseCategory(int c)
    signal settings()
    signal logout()

    property bool collapsed: Ui.setting("sidebar_collapsed") === "1"
    readonly property bool expanded: !collapsed
    width: collapsed ? 76 : 256
    Behavior on width { NumberAnimation { duration: 300; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
    color: Theme.glassSide
    clip: true

    Rectangle { anchors.right: parent.right; width: 1; height: parent.height; color: "#14FFFFFF" }

    /// Где пункт раздела в окне (туда летит постер); null — не виден.
    function navRect(t) {
        const it = t === 0 ? homeItem : t === 1 ? mineItem : null
        return it ? it.mapToItem(null, 0, 0, it.width, it.height) : null
    }
    /// Постер приземлился: короткая вспышка пункта.
    function land(t) { glowTab = t; glowOff.restart() }
    Timer { id: glowOff; interval: 650; onTriggered: side.glowTab = -1 }

    readonly property bool inLibrary: tab === 0
    readonly property bool inMine: tab === 1
    readonly property var counts: { lib.version; return lib.categoryCounts(inMine) }

    Item {
        anchors { fill: parent; leftMargin: 14; rightMargin: 14; topMargin: 18; bottomMargin: 18 }

        // логотип с медленно «дышащим» свечением
        Item {
            id: brand
            width: parent.width; height: 52
            Item {
                id: mark
                x: 1; width: 46; height: 46
                anchors.verticalCenter: parent.verticalCenter
                property real b: 0
                SequentialAnimation on b {
                    running: !Theme.simple && Qt.application.state === Qt.ApplicationActive
                    loops: Animation.Infinite
                    NumberAnimation { to: 1; duration: 2600; easing.type: Easing.InOutQuad }
                    NumberAnimation { to: 0; duration: 2600; easing.type: Easing.InOutQuad }
                }
                Rectangle {
                    anchors.centerIn: parent
                    width: 46 * 2 * (0.62 + 0.12 * mark.b); height: width; radius: width / 2
                    gradient: null
                    color: "transparent"
                    // радиальное свечение: несколько колец с убывающей прозрачностью (без эффектов)
                    Repeater {
                        model: 6
                        Rectangle {
                            required property int index
                            anchors.centerIn: parent
                            width: parent.width * (1 - index / 6); height: width; radius: width / 2
                            color: "#6D5BF0"
                            opacity: (0.22 + 0.22 * mark.b) / 6
                        }
                    }
                }
                Image { anchors.fill: parent; source: "qrc:/res/icons/brand-mark.png"; sourceSize: Qt.size(92, 92); smooth: true; mipmap: true }
            }
            Column {
                x: mark.x + mark.width + 10
                anchors.verticalCenter: parent.verticalCenter
                opacity: side.expanded ? 1 : 0
                visible: opacity > 0
                Behavior on opacity { NumberAnimation { duration: side.expanded ? 200 : 90 } }
                Text { text: "Suslik"; color: "white"; font.family: Theme.unbounded; font.weight: Font.Bold; font.pixelSize: 17 }
                Row { // «MEDIA» переливом от бирюзового к фиолетовому
                    topPadding: 1
                    spacing: 5
                    Repeater {
                        model: [["M", "#22D3EE"], ["E", "#4AB0F1"], ["D", "#6D8CF5"], ["I", "#847CFA"], ["A", "#9B6BFF"]]
                        Text { required property var modelData; text: modelData[0]; color: modelData[1]; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 11 }
                    }
                }
            }
        }

        Flickable {
            id: navScroll
            anchors { top: brand.bottom; topMargin: 22; bottom: bottomBox.top; bottomMargin: 8 }
            width: parent.width
            contentHeight: nav.height
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            Column {
                id: nav
                width: parent.width
                spacing: 4
                NavItem {
                    id: homeItem
                    width: parent.width; expanded: side.expanded; icon: "home"
                    text: Ui.tx("sidebar.glavnaya"); active: side.inLibrary && side.category === 0; glowing: side.glowTab === 0
                    onClicked: { if (side.inLibrary) side.chooseCategory(0); side.select(0) }
                }
                NavItem {
                    id: mineItem
                    width: parent.width; expanded: side.expanded; icon: "bookmark"; iconFilled: true
                    text: Ui.tx("sidebar.moya_mediateka"); active: side.inMine && side.category === 0; glowing: side.glowTab === 1
                    onClicked: { if (side.inMine) side.chooseCategory(0); side.select(1) }
                }
                // поиск по трекерам и совместный просмотр — позже: вкладки видны, но не нажимаются
                NavItem {
                    width: parent.width; expanded: side.expanded; icon: "search"; enabled_: false; note: Ui.tx("sidebar.skoro")
                    text: Ui.tx("search.poisk_razdach")
                }
                NavItem {
                    width: parent.width; expanded: side.expanded; icon: "link"
                    text: Ui.tx("search.magnet_torrent"); active: side.tab === 3
                    onClicked: side.select(3)
                }
                NavItem {
                    width: parent.width; expanded: side.expanded; icon: "people"; enabled_: false; note: Ui.tx("sidebar.skoro")
                    text: Ui.tx("sidebar.sovmestnyy_prosmotr")
                }
                SectionTitle { text: Ui.tx("sidebar.kollektsiya") }
                Repeater {
                    model: [[1, "film", "library.filmy"], [2, "screen", "library.serialy"], [3, "anime", "library.anime"],
                            [4, "cartoon", "library.multfilmy"], [5, "other", "library.drugoe"]]
                    NavItem {
                        required property var modelData
                        width: nav.width; expanded: side.expanded; icon: modelData[1]
                        text: Ui.tx(modelData[2])
                        count: side.counts[String(modelData[0])] || 0
                        active: (side.inLibrary || side.inMine) && side.category === modelData[0]
                        // остаётся на своей странице (медиатека или «Моя медиатека»)
                        onClicked: { side.chooseCategory(modelData[0]); side.select(side.inMine ? 1 : 0) }
                    }
                }
                SectionTitle { text: Ui.tx("sidebar.servis") }
                NavItem {
                    width: parent.width; expanded: side.expanded; icon: "gear"
                    text: Ui.tx("sidebar.nastroyki")
                    onClicked: side.settings()
                }
            }
        }

        Column {
            id: bottomBox
            anchors.bottom: parent.bottom
            width: parent.width
            spacing: 10
            ProfileCard { width: parent.width; expanded: side.expanded; onLogout: side.logout() }
            // свернуть / развернуть: стрелка показывает, куда уедет панель
            Btn {
                id: fold
                width: parent.width; height: 32
                hoverUp: 1
                Rectangle { anchors.fill: parent; radius: 12; color: fold.hovered ? "#0FFFFFFF" : "transparent" }
                Icon { anchors.centerIn: parent; size: 14; name: side.collapsed ? "chevronRight" : "chevronLeft"; color: fold.hovered ? "white" : Theme.muted }
                onClicked: { side.collapsed = !side.collapsed; Ui.setSetting("sidebar_collapsed", side.collapsed ? "1" : "") }
            }
        }
    }

    // «КОЛЛЕКЦИЯ» / «СЕРВИС»: подпись над группой; у свёрнутой панели — только линия
    component SectionTitle: Column {
        property string text
        width: nav.width
        topPadding: 6
        Rectangle { width: parent.width; height: 1; color: "#1AFFFFFF" }
        Item {
            width: parent.width; height: side.expanded ? 30 : 8
            Behavior on height { NumberAnimation { duration: 220 } }
            Text {
                visible: side.expanded
                x: 15; y: 8
                text: parent.parent.text.toUpperCase()
                color: Theme.muted
                font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 11; font.letterSpacing: 1.4
            }
        }
    }
}
