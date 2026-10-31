import QtQuick
import QtQuick.Window

// Окно приложения (Main.kt): вход или боковая панель и страницы; страницы остаются живыми — возврат мгновенный.
Window {
    id: win
    width: 1280; height: 860
    minimumWidth: 1005; minimumHeight: 685   // меньше не помещаются заголовок и вкладки
    visible: true
    color: Theme.night
    title: Ui.tx("app.app_mediateka", { app: Ui.diag ? "Suslik Debug" : "Suslik Media" })

    property int tab: 0
    property int prevTab: 0
    function select(t) {
        if (t !== tab) { prevTab = tab; tab = t }
        if (t === 0 || t === 1) lib.refresh()
    }

    Backdrop { anchors.fill: parent }

    // вход ↔ медиатека: плавная смена
    LoginView {
        anchors.fill: parent
        opacity: lib.authenticated === 0 ? 1 : 0
        visible: opacity > 0
        Behavior on opacity { NumberAnimation { duration: 420; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
    }

    Item {
        id: shell
        anchors.fill: parent
        opacity: lib.authenticated === 0 ? 0 : 1
        // под открытым плеером медиатеку не рисуем
        visible: opacity > 0 && !(playerView.opacity === 1 && player.open)
        Behavior on opacity { NumberAnimation { duration: 420; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }

        Sidebar {
            id: sidebar
            height: parent.height
            tab: win.tab
            category: win.tab === 1 ? minePage.category : libraryPage.category
            onSelect: (t) => win.select(t)
            onChooseCategory: (c) => { if (win.tab === 1) minePage.category = c; else libraryPage.category = c }
            onSettings: settingsSheet.open = true
            onLogout: lib.logout()
        }

        Item {
            id: pages
            anchors { left: sidebar.right; right: parent.right; top: parent.top; bottom: parent.bottom }
            clip: true
            Page { index: 0; LibraryView { id: libraryPage; anchors.fill: parent; mine: false; onVersionsRequested: (t, ids) => dialogs.versions(model, t, ids); onPlayRequested: (id, p) => dialogs.play_(id, p); onFlyTo: (tab, r, p) => flight.launch(tab, r, p) } }
            Page { index: 1; LibraryView { id: minePage; anchors.fill: parent; mine: true; onVersionsRequested: (t, ids) => dialogs.versions(model, t, ids); onPlayRequested: (id, p) => dialogs.play_(id, p); onFlyTo: (tab, r, p) => flight.launch(tab, r, p) } }
            Page { index: 3; AddView { id: addPage; anchors.fill: parent; active: win.tab === 3; onPreviewRequested: (video, a, offset, title) => player.startPreview(video, a, offset, title) } }
        }
    }

    // страницы скользят в сторону вкладки, старая сначала гаснет (без вспышек и рывков)
    component Page: Item {
        id: pg
        property int index
        readonly property bool current: win.tab === index
        width: pages.width; height: pages.height
        visible: false
        opacity: 0
        Component.onCompleted: if (current) { visible = true; opacity = 1 }
        onCurrentChanged: {
            const dir = win.tab > win.prevTab ? 1 : -1
            anim.stop()
            if (current) {
                visible = true
                x = Theme.simple ? 0 : dir * width / 14
                anim.toX = 0; anim.toOpacity = 1; anim.fadeMs = 320; anim.delayMs = 70
            } else {
                anim.toX = Theme.simple ? 0 : -dir * width / 14; anim.toOpacity = 0; anim.fadeMs = 180; anim.delayMs = 0
            }
            anim.start()
        }
        ParallelAnimation {
            id: anim
            property real toX; property real toOpacity; property int fadeMs; property int delayMs
            NumberAnimation { target: pg; property: "x"; to: anim.toX; duration: Theme.simple ? 0 : 460; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease }
            SequentialAnimation {
                PauseAnimation { duration: Theme.simple ? 0 : anim.delayMs }
                NumberAnimation { target: pg; property: "opacity"; to: anim.toOpacity; duration: Theme.simple ? 0 : anim.fadeMs }
            }
            onFinished: if (!pg.current) pg.visible = false
        }
    }

    FavFlight { id: flight; anchors.fill: parent; sidebar: sidebar }
    Dialogs { id: dialogs; anchors.fill: parent }
    SettingsSheet { id: settingsSheet; anchors.fill: parent }
    WhatsNew { anchors.fill: parent }
    PlayerView { id: playerView; z: 50 }

    // после входа: есть ли обновление; «Что нового» — когда медиатека уже на экране
    readonly property bool signedIn: lib.authenticated === 1
    onSignedInChanged: if (signedIn) { updater.check(); whatsNewDelay.start() }
    Timer { id: whatsNewDelay; interval: 700; onTriggered: updater.loadWhatsNew() }

    // проверка вёрстки без мыши: SM_OPEN=play:<id>:<путь> | versions:<id>,<id> | settings | add[:film|audio:<magnet>]
    Timer {
        running: Ui.env("SM_OPEN") !== ""
        interval: 2500
        onTriggered: {
            const a = Ui.env("SM_OPEN").split(":")
            if (a[0] === "play") dialogs.play_(Number(a[1]), a.slice(2).join(":"))
            else if (a[0] === "versions") dialogs.versions(libraryPage.model, "Версии", a[1].split(",").map(Number))
            else if (a[0] === "settings") { settingsSheet.open = true; if (a[1]) settingsSheet.scrollTo(Number(a[1])) }
            else if (a[0] === "add") {
                win.select(3)
                if (a[1] === "audio") { addPage.mode = 1; addPage.audioModel.addInput(a.slice(2).join(":")) }
                else if (a[1] === "film") addPage.addLinks(a.slice(2).join(":"))
            }
            else if (a[0] === "mine") win.select(1)
            else if (a[0] === "list") libraryPage.model.viewMode = 1
            else if (a[0] === "video") player.startPreview(a.slice(1).join(":"), "", 0, "Проверка видео")
        }
    }

    Shortcut {
        sequence: "F11"
        enabled: !player.open
        onActivated: windowMode.toggleFullscreen()
    }
}
