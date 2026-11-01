import QtQuick
import Suslik.Native

// Страница медиатеки (LibraryView.kt): заголовок с поиском, «Продолжить просмотр», вид и сортировка, постеры по страницам.
Item {
    id: v
    property bool mine: false
    // Плитки скрытой вкладки создаются не сразу, а через пару секунд простоя (без постеров — они грузятся,
    // когда плитку впервые видно): первый переход на вкладку не создаёт сетку посреди анимации
    property bool wasEverVisible: visible
    onVisibleChanged: if (visible) wasEverVisible = true
    Timer { interval: 2500; running: !v.wasEverVisible && lib.authenticated === 1; onTriggered: v.wasEverVisible = true }
    property alias category: lp.category
    readonly property alias model: lp
    signal versionsRequested(string title, var ids)
    signal playRequested(var magnetId, string path)
    signal flyTo(int tab, var rect, string poster)

    LibraryPage {
        id: lp
        library: lib
        mine: v.mine
        // столько постеров в ряд, сколько влезает (180 px, промежутки 14, поля по 36)
        columns: Math.max(1, Math.floor((v.width - 72 + 14) / (180 + 14)))
        onVersionsRequested: (title, ids) => v.versionsRequested(title, ids)
        onPlayRequested: (id, path) => v.playRequested(id, path)
    }

    // новая страница — с самого верха
    readonly property int curPage: lp.page
    onCurPageChanged: flick.contentY = 0

    Flickable {
        id: flick
        anchors.fill: parent
        contentHeight: content.height + 28 + 40
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        flickableDirection: Flickable.VerticalFlick
        // колесо мыши — ровными шагами, как в прежней версии
        WheelHandler {
            acceptedDevices: PointerDevice.Mouse | PointerDevice.TouchPad
            onWheel: (ev) => {
                const dy = ev.pixelDelta.y !== 0 ? ev.pixelDelta.y : ev.angleDelta.y / 120 * 110
                flick.contentY = Math.max(0, Math.min(flick.contentHeight - flick.height, flick.contentY - dy))
            }
        }

        Column {
            id: content
            x: 36; y: 28
            width: flick.width - 72

            // заголовок: название, сколько всего, поиск, «Обновить»
            Item {
                width: parent.width
                height: headRow.height + (err.visible ? err.height : 0) + 18
                Item {
                    id: headRow
                    width: parent.width; height: 44
                    Text {
                        id: title
                        anchors.verticalCenter: parent.verticalCenter
                        text: v.mine ? Ui.tx("library.moi_filmy") : Ui.tx("library.moya_biblioteka_alldebrid")
                        color: "white"
                        font.family: Theme.unbounded; font.weight: Font.Medium; font.pixelSize: 24
                    }
                    Text {
                        anchors { left: title.right; leftMargin: 12; verticalCenter: parent.verticalCenter; verticalCenterOffset: 3 }
                        text: lp.total
                        color: Theme.muted
                        font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 13
                    }
                    Field {
                        id: search
                        anchors { right: refresh.left; rightMargin: 10; verticalCenter: parent.verticalCenter }
                        width: 280
                        icon: "search"
                        placeholder: Ui.tx("library.poisk_po_nazvaniyu")
                        onTextChanged: lp.query = text
                    }
                    RefreshButton {
                        id: refresh
                        anchors { right: parent.right; verticalCenter: parent.verticalCenter }
                        loading: lib.loading
                        onClicked: lib.refresh()
                    }
                }
                // своя строка — только когда что-то не так
                Text {
                    id: err
                    visible: lib.statusKind === 2
                    anchors.top: headRow.bottom
                    topPadding: 8; leftPadding: 2
                    width: parent.width
                    text: lib.status
                    color: Theme.bad
                    font.family: Theme.onest; font.pixelSize: 13
                    wrapMode: Text.Wrap
                }
            }

            ContinueBanner {
                width: parent.width
                page: lp
                visible: !v.mine && lp.query.trim() === "" && lp.category === 0 && shown
                roomAbove: 28 + y
            }

            // вид, сортировка, «только готовые» — одним меню справа
            Item {
                width: parent.width
                height: 36 + 10
                PillButton {
                    id: viewBtn
                    anchors.right: parent.right
                    y: 6
                    text: Ui.tx("library.vid")
                    icon: "funnel"
                    active: lp.onlyReady || viewMenu.visible
                    onClicked: viewMenu.popup(viewBtn, viewBtn.width - viewMenu.width, viewBtn.height + 4)
                }
                SmMenu {
                    id: viewMenu
                    SmMenuItem { tickColumn: true; tick: lp.viewMode === 0; text: Ui.tx("library.setka"); onTriggered: lp.viewMode = 0 }
                    SmMenuItem { tickColumn: true; tick: lp.viewMode === 1; text: Ui.tx("library.spisok"); onTriggered: lp.viewMode = 1 }
                    SmMenuSeparator {}
                    SmMenuItem { tickColumn: true; tick: lp.sortBy === 0; text: Ui.tx("library.nazvanie"); onTriggered: lp.sortBy = 0 }
                    SmMenuItem { tickColumn: true; tick: lp.sortBy === 1; text: Ui.tx("library.snachala_novye"); onTriggered: lp.sortBy = 1 }
                    SmMenuItem { tickColumn: true; tick: lp.sortBy === 2; text: Ui.tx("library.razmer"); onTriggered: lp.sortBy = 2 }
                    SmMenuItem { tickColumn: true; tick: lp.sortBy === 3; text: Ui.tx("library.izbrannoe"); onTriggered: lp.sortBy = 3 }
                    SmMenuSeparator {}
                    SmMenuItem { tickColumn: true; tick: lp.onlyReady; text: Ui.tx("library.tolko_gotovye"); onTriggered: lp.onlyReady = !lp.onlyReady }
                }
            }

            // «Мои фильмы» пусты
            Column {
                visible: lp.emptyMine
                width: parent.width
                topPadding: 64; bottomPadding: 64
                Icon { anchors.horizontalCenter: parent.horizontalCenter; name: "bookmark"; size: 40; color: Theme.muted }
                Text { anchors.horizontalCenter: parent.horizontalCenter; topPadding: 14; text: Ui.tx("library.izbrannoe_pusto"); color: "white"; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 16 }
                Text { anchors.horizontalCenter: parent.horizontalCenter; topPadding: 4; text: Ui.tx("library.izbrannoe_pusto_podskazka"); color: Theme.muted; font.family: Theme.onest; font.pixelSize: 14 }
            }

            Item { width: 1; height: 4 }

            // Сетка своими силами: ширина и места плиток — целые пиксели (текст и постеры не «плывут» и не двоятся при
            // изменении размера), а когда плитка переходит в другой ряд или столбец, она переезжает плавно (как animateItem)
            Item {
                id: grid
                visible: lp.viewMode === 0
                width: parent.width
                readonly property int cols: Math.max(1, lp.columns)
                readonly property int gap: 14
                readonly property int rowGap: 20
                readonly property int tileW: Math.floor((width - (cols - 1) * gap) / cols)
                readonly property int tileH: Math.round(tileW * 1.5)
                readonly property int count: lp.viewMode === 0 ? lp.tiles.length : 0
                height: count ? Math.ceil(count / cols) * (tileH + rowGap) - rowGap : 0
                Repeater {
                    model: (v.wasEverVisible && lp.viewMode === 0) ? lp.tiles : []
                    Tile {
                        id: cell
                        required property var modelData
                        required property int index
                        readonly property int col: index % grid.cols
                        readonly property int row: Math.floor(index / grid.cols)
                        t: modelData
                        page: lp
                        x: col * (grid.tileW + grid.gap)
                        y: row * (grid.tileH + grid.rowGap)
                        width: grid.tileW
                        height: grid.tileH
                        onFlyTo: (tab, rect, poster) => v.flyTo(tab, rect, poster)

                        // переезд: плитка плавно съезжает на новое место только при смене ряда/столбца.
                        // Обычное растяжение по x/y при ресайзе окна анимировать не нужно — иначе плитки дёргаются.
                        property real placedX: NaN
                        property real placedY: NaN
                        property bool moved: false
                        onColChanged: { moved = true; Qt.callLater(place) }
                        onRowChanged: { moved = true; Qt.callLater(place) }
                        onXChanged: if (!moved) { placedX = x }
                        onYChanged: if (!moved) { placedY = y }
                        function place() {
                            if (moved && !isNaN(placedX) && !Theme.simple) {
                                shift.x += placedX - x
                                shift.y += placedY - y
                                slide.restart()
                            }
                            moved = false
                            placedX = x; placedY = y
                        }
                        Component.onCompleted: { placedX = x; placedY = y }
                        transform: Translate { id: shift }
                        ParallelAnimation {
                            id: slide
                            NumberAnimation { target: shift; property: "x"; to: 0; duration: 420; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease }
                            NumberAnimation { target: shift; property: "y"; to: 0; duration: 420; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease }
                        }
                    }
                }
            }
            Column {
                visible: lp.viewMode === 1
                width: parent.width
                spacing: 12
                Repeater {
                    model: (v.wasEverVisible && lp.viewMode === 1) ? lp.tiles : []
                    ListRow { required property var modelData; t: modelData; page: lp; width: parent.width }
                }
            }
            PageBar {
                visible: lp.pageCount > 1
                width: parent.width
                page: lp
            }
        }
    }
}
