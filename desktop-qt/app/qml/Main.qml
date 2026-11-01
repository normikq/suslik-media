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
        if (t !== tab) {
            prevTab = tab
            tab = t
            if (t === 0 || t === 1) lib.refresh()
        }
    }

    onTabChanged: trimTimer.restart()
    Timer {
        id: trimTimer
        interval: 500
        repeat: false
        onTriggered: Ui.trimMemory()
    }

    // Когда приложение свёрнуто > 30 секунд: выгрузка страниц процесса в ОС
    readonly property bool isMinimized: win.visibility === Window.Minimized
    onIsMinimizedChanged: {
        if (isMinimized) minimizeTimer.restart(); else minimizeTimer.stop()
    }
    Timer {
        id: minimizeTimer
        interval: 30000
        repeat: false
        onTriggered: if (win.isMinimized) Ui.unloadMemory()
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
        // под открытым плеером медиатеку не рисуем (при закрытии она снова на месте — плеер летит в её постер)
        visible: opacity > 0 && (win.playerPresence < 1 || win.playerLeaving)
        Behavior on opacity { NumberAnimation { duration: 420; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }

        // Main.kt: пока плеер на экране, медиатека отступает (меньше и бледнее) и выходит вперёд при возврате;
        // в упрощённых анимациях просто стоит, пока плеер её не закроет
        Item {
            id: libLayer
            anchors.fill: parent
            readonly property real p: win.playerPresence
            scale: Theme.simple ? 1 : 0.93 + 0.07 * (1 - p)
            opacity: Theme.simple ? (p >= 1 ? 0 : 1) : Math.max(0, Math.min(1, 1 - p * 1.15))

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
                Page { index: 0; LibraryView { id: libraryPage; anchors.fill: parent; mine: false; onVersionsRequested: (t, ids) => dialogs.versions(model, t, ids); onPlayRequested: (id, p) => dialogs.play_(id, p); onFlyTo: (tab, r, p) => flight.launch(tab, r, p); onDiscoverRequested: { win.select(2); searchPage.openAi("") } } }
                Page { index: 1; LibraryView { id: minePage; anchors.fill: parent; mine: true; onVersionsRequested: (t, ids) => dialogs.versions(model, t, ids); onPlayRequested: (id, p) => dialogs.play_(id, p); onFlyTo: (tab, r, p) => flight.launch(tab, r, p); onDiscoverRequested: { win.select(2); searchPage.openAi("") } } }
                Page { index: 2; SearchView { id: searchPage; anchors.fill: parent; active: win.tab === 2; onPlayRequested: (id, p) => dialogs.play_(id, p) } }
                Page { index: 4; TogetherView { anchors.fill: parent; onLibrary: win.select(0); onPlayRequested: (id, path) => dialogs.play_(id, path) } }
                Page { index: 3; AddView { id: addPage; anchors.fill: parent; active: win.tab === 3; onPreviewRequested: (video, a, offset, title) => player.startPreview(video, a, offset, title) } }
            }
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
    // гость комнаты «Вместе»: медиатека закрыта (Sidebar), остаётся страница комнаты; фильм ведущего открывается сам
    readonly property bool partyGuest: party.guest
    onPartyGuestChanged: if (partyGuest && (tab === 0 || tab === 1)) select(4)
    PartyFollow { anchors.fill: parent }
    // Вошли по ссылке из Discord Activity: страница комнаты; ведущему — сразу фильм комнаты (гостю его откроет PartyFollow)
    Connections {
        target: party
        function onRoomLinkOpened() {
            win.select(4)
            const key = party.fileKey
            if (!party.isHost || key === "") return
            const bar = key.indexOf("|")
            const id = Number(key.substring(0, bar)), path = key.substring(bar + 1)
            if (lib.hasFile(id, path)) dialogs.play_(id, path)
        }
    }
    Dialogs { id: dialogs; anchors.fill: parent }
    SettingsSheet { id: settingsSheet; anchors.fill: parent }
    WhatsNew { anchors.fill: parent }
    // Экран плеера: вылет из постера и отлёт обратно (Main.kt + ExitFlight.kt). playerPresence: 0 — медиатека, 1 — плеер.
    property real playerPresence: 0
    property bool playerLeaving: false
    property var playerFilm: 0        // фильм, чей постер ищем
    property var playerFrom: null     // где был постер в момент открытия (медиатека ещё не отступила)
    property var playerHome: null     // куда улетать: читается на каждом кадре закрытия, последнее найденное держится

    Connections {
        target: player
        function onOpenChanged() {
            if (player.open && !player.closing) {
                win.playerLeaving = false
                win.playerHome = null
                win.playerFilm = player.magnetId
                win.playerFrom = Theme.target(win.playerFilm)
                presenceAnim.stop()
                if (Theme.simple) {
                    win.playerPresence = 1
                    Ui.trimMemory(); Ui.dropPosterCache()
                } else {
                    presenceAnim.from = win.playerPresence
                    presenceAnim.to = 1
                    presenceAnim.duration = 560
                    presenceAnim.start()
                }
            } else if (player.closing && !win.playerLeaving) {
                win.playerLeaving = true      // медиатека снова видна: её постеры можно найти
                win.playerHome = Theme.target(win.playerFilm)
                presenceAnim.stop()
                presenceAnim.from = win.playerPresence
                presenceAnim.to = 0
                presenceAnim.duration = 600
                presenceAnim.start()
            }
        }
    }
    // постер ищется заново на каждом кадре: медиатека ещё выходит вперёд, ряд «Продолжить» мог переставиться,
    // окно возвращается из полного экрана; кадр, где его не нашли, держит последнее место
    onPlayerPresenceChanged: if (playerLeaving) { const t = Theme.target(playerFilm); if (t) playerHome = t }

    NumberAnimation {
        id: presenceAnim
        target: win
        property: "playerPresence"
        easing.type: Easing.BezierSpline
        easing.bezierCurve: Theme.fly
        onFinished: {
            if (win.playerPresence === 0 && win.playerLeaving) {
                win.playerLeaving = false
                if (player.closing) player.finishClose()
                Ui.trimMemory()
            } else if (win.playerPresence === 1) {
                // При открытии экрана плеера — лёгкая сборка мусора медиатеки под ним (без сброса страниц ОС)
                Ui.trimMemory(); Ui.dropPosterCache()
            }
        }
    }

    Item {
        id: flightContainer
        z: 50
        anchors.fill: parent
        visible: win.playerPresence > 0 || player.open

        // весь полёт глотает мышь: медиатека под плеером не реагирует (Main.kt: pointerInput)
        MouseArea {
            anchors.fill: parent
            acceptedButtons: Qt.AllButtons
            hoverEnabled: true
            onWheel: (w) => w.accepted = true
        }

        readonly property real p: win.playerPresence
        readonly property real e: Math.max(0.0, Math.min(1.0, 1.0 - p))
        // выход — в живое место постера, вход — из места в момент открытия; нет постера — запасная анимация
        readonly property var target: win.playerLeaving ? win.playerHome : win.playerFrom
        readonly property bool hasTarget: !!target && target.width > 1 && target.height > 1

        readonly property real ww: Math.max(100.0, win.width)
        readonly property real wh: Math.max(100.0, win.height)

        readonly property real sTarget: hasTarget ? 1.0 + (Math.max(target.width / ww, target.height / wh) - 1.0) * e : 1.0
        readonly property real sFallback: win.playerLeaving ? (0.86 + 0.14 * p) : (1.035 - 0.035 * p)
        readonly property real s: Math.max(0.01, hasTarget ? sTarget : sFallback)

        readonly property real flightW: Math.max(1.0, hasTarget ? (ww + (target.width - ww) * e) : (ww * s))
        readonly property real flightH: Math.max(1.0, hasTarget ? (wh + (target.height - wh) * e) : (wh * s))
        readonly property real flightLeft: hasTarget ? (target.x * e) : ((1.0 - s) * ww * 0.5)
        readonly property real flightTop: hasTarget ? (target.y * e) : ((1.0 - s) * wh * 0.5)

        readonly property real lw: flightW / s
        readonly property real lh: flightH / s
        readonly property real lx: (ww - lw) * 0.5
        readonly property real ly: (wh - lh) * 0.5

        // запасной вход — без скругления, просто проявляется (Main.kt)
        readonly property real flightRadius: hasTarget ? 14.0 * e : win.playerLeaving ? 28.0 * e : 0
        readonly property real flightOpacity: hasTarget ? Math.min(1.0, Math.max(0.0, p / 0.28))
                                              : win.playerLeaving ? Math.min(1.0, Math.max(0.0, p * 1.6)) : p

        Item {
            id: flightClipper
            x: flightContainer.flightLeft
            y: flightContainer.flightTop
            width: flightContainer.flightW
            height: flightContainer.flightH
            clip: true
            opacity: flightContainer.flightOpacity
            // улетающий плеер кнопки не нажимает (мышь достаётся MouseArea выше и пропадает)
            enabled: !win.playerLeaving

            layer.enabled: flightContainer.flightRadius > 0.5 && flightClipper.width > 20 && flightClipper.height > 20
            layer.smooth: true
            layer.effect: ShaderEffect {
                property real radius: flightContainer.flightRadius
                property size size: Qt.size(flightClipper.width, flightClipper.height)
                fragmentShader: "qrc:/sm/shaders/rounded.frag.qsb"
            }

            Loader {
                id: playerLoader
                width: win.width
                height: win.height
                x: -flightContainer.s * flightContainer.lx
                y: -flightContainer.s * flightContainer.ly
                scale: flightContainer.s
                transformOrigin: Item.TopLeft
                focus: active
                active: player.open || win.playerPresence > 0
                sourceComponent: Component { PlayerView {} }
            }
        }
    }

    // после входа: есть ли обновление; «Что нового» — когда медиатека уже на экране
    readonly property bool signedIn: lib.authenticated === 1
    onSignedInChanged: if (signedIn) { updater.check(); whatsNewDelay.start() }
    Timer { id: whatsNewDelay; interval: 700; onTriggered: updater.loadWhatsNew() }

    // проверка вёрстки без мыши: SM_OPEN=play:<id>:<путь> | versions:<id>,<id> | settings | add[:film|audio:<magnet>]
    // | search:<запрос> | discover[:запрос] | trackers[:вкладка] | together[:create|<код>]
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
            else if (a[0] === "search") { win.select(2); searchPage.search(a.slice(1).join(":")) }
            else if (a[0] === "discover") { win.select(2); searchPage.openAi(a.slice(1).join(":")) }
            else if (a[0] === "together") { win.select(4); if (a[1] === "create") party.create(); else if (a[1]) party.join(a[1]) }
            else if (a[0] === "trackers") { win.select(2); searchPage.openTrackers(Number(a[1] || 0)) }
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
