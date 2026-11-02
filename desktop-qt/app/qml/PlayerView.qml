import QtQuick
import QtQuick.Shapes
import Suslik.Native

// Экран плеера (PlayerView.kt, PlayerOverlays.kt): видео mpv в сцене окна, панели — слои поверх него.
// Логика — PlayerModel (C++), здесь вёрстка, мышь и клавиши.
Item {
    id: pv
    anchors.fill: parent
    visible: true
    Component.onCompleted: keys.forceActiveFocus()
    Connections {
        target: player
        function onOpenChanged() { if (player.open && !player.closing) keys.forceActiveFocus() }
    }

    readonly property bool mini: windowMode.mini
    readonly property bool compact: width < 880
    readonly property bool narrow: width < 680
    readonly property int btn: narrow ? 34 : compact ? 38 : 42
    property string picker: ""          // "", "audio", "spu", "more"
    property bool barVisible: true
    property real hoverAt: -1          // время под мышью на полосе
    property real scrubAt: -1          // тянут ползунок
    readonly property bool hold: !player.started || player.paused || player.nextIn >= 0 || picker !== "" || scrubAt >= 0 || player.infoOpen || (player.inIntro && !player.partyGuest)
                                 || topHover.hovered || colHover.hovered
    readonly property bool barsShown: barVisible || hold || mini && barVisible

    function poke() { barVisible = true; hideTimer.restart() }
    onHoldChanged: { if (hold) barVisible = true; else hideTimer.restart() }
    Timer { id: hideTimer; interval: 2500; onTriggered: if (!pv.hold) pv.barVisible = false }

    function closePlayer() {
        picker = ""
        windowMode.reset()
        player.close()
    }
    function openPicker(kind) { picker = picker === kind ? "" : kind; poke() }
    function toggleFullscreen() { windowMode.toggleFullscreen(); player.surfaceChanged() }
    function setMini(on) {
        const w = player.engine.videoWidth, h = player.engine.videoHeight
        const fileAspect = w > 0 && h > 0 ? w / h : 16 / 9
        const aspect = player.crop219 ? (21 / 9) : fileAspect
        windowMode.setMini(on, aspect)
        picker = ""
        player.surfaceChanged()
    }

    // проверка вёрстки: SM_PLAYER_UI=audio|spu|more|info|bars — меню открыто, панели видны
    Connections {
        target: player
        enabled: Ui.env("SM_PLAYER_UI") !== ""
        function onStateChanged() {
            if (!player.started) return
            const v = Ui.env("SM_PLAYER_UI")
            if (v === "info") { if (!player.infoOpen) player.toggleInfo() }
            else if (v !== "bars") pv.picker = v
            pv.barVisible = true; hideTimer.interval = 600000; hideTimer.restart()
        }
    }
    Connections {
        target: player
        function onOpenChanged() {
            if (player.open && !player.closing) { pv.picker = ""; pv.barVisible = true; hideTimer.restart(); keys.forceActiveFocus() }
        }
    }
    Connections {
        target: player.engine
        function onVideoSizeChanged() {
            const w = player.engine.videoWidth, h = player.engine.videoHeight
            if (w > 0 && h > 0) {
                const fileAspect = w / h
                windowMode.fitMini(player.crop219 ? (21 / 9) : fileAspect)
            }
        }
    }
    Connections {
        target: player
        function onCrop219Changed() {
            if (windowMode.mini) {
                const w = player.engine.videoWidth, h = player.engine.videoHeight
                const fileAspect = w > 0 && h > 0 ? w / h : 16 / 9
                windowMode.fitMini(player.crop219 ? (21 / 9) : fileAspect)
            }
        }
    }

    // ---- клавиши ----
    Item {
        id: keys
        anchors.fill: parent
        focus: player.open
        // Клавиши по месту на клавиатуре, а не по букве: в русской раскладке «К», «А», «Х» работают как K, F, [.
        // Скан-коды набора 1 (Windows; в Linux код клавиши X11/Wayland = evdev + 8, а evdev здесь совпадает с набором 1).
        readonly property var physical: ({
            0x10: Qt.Key_Q, 0x11: Qt.Key_W, 0x12: Qt.Key_E, 0x13: Qt.Key_R, 0x14: Qt.Key_T, 0x15: Qt.Key_Y, 0x16: Qt.Key_U,
            0x17: Qt.Key_I, 0x18: Qt.Key_O, 0x19: Qt.Key_P, 0x1A: Qt.Key_BracketLeft, 0x1B: Qt.Key_BracketRight,
            0x1E: Qt.Key_A, 0x1F: Qt.Key_S, 0x20: Qt.Key_D, 0x21: Qt.Key_F, 0x22: Qt.Key_G, 0x23: Qt.Key_H, 0x24: Qt.Key_J,
            0x25: Qt.Key_K, 0x26: Qt.Key_L, 0x27: Qt.Key_Semicolon, 0x28: Qt.Key_Apostrophe, 0x2B: Qt.Key_Backslash,
            0x2C: Qt.Key_Z, 0x2D: Qt.Key_X, 0x2E: Qt.Key_C, 0x2F: Qt.Key_V, 0x30: Qt.Key_B, 0x31: Qt.Key_N, 0x32: Qt.Key_M,
            0x33: Qt.Key_Comma, 0x34: Qt.Key_Period, 0x35: Qt.Key_Slash
        })
        function keyOf(e) {
            const os = Qt.platform.os
            const sc = os === "windows" ? e.nativeScanCode : (os === "linux" ? e.nativeScanCode - 8 : -1)
            const k = physical[sc]
            if (k === undefined) return e.key
            return k === Qt.Key_Backslash && (e.modifiers & Qt.ShiftModifier) ? Qt.Key_Bar : k
        }
        Keys.onPressed: (e) => {
            pv.poke()
            const shift = e.modifiers & Qt.ShiftModifier
            switch (keyOf(e)) {
            case Qt.Key_Space: if (player.nextIn >= 0) player.playNext(); else player.togglePause(); break
            case Qt.Key_Return: case Qt.Key_Enter: if (player.nextIn >= 0) player.playNext(); else pv.toggleFullscreen(); break
            case Qt.Key_K: player.togglePause(); break
            case Qt.Key_Right: player.seekBy(shift ? 60000 : 10000); break
            case Qt.Key_Left: player.seekBy(shift ? -60000 : -10000); break
            case Qt.Key_Up: player.stepVolume(5); break
            case Qt.Key_Down: player.stepVolume(-5); break
            case Qt.Key_F: case Qt.Key_F11: pv.toggleFullscreen(); break
            case Qt.Key_Escape:
                if (player.nextIn >= 0) player.cancelNext()
                else if (pv.picker !== "") pv.picker = ""
                else if (windowMode.fullscreen) pv.toggleFullscreen()
                else if (windowMode.mini) pv.setMini(false)
                else pv.closePlayer()
                break
            case Qt.Key_Backspace: if (player.nextIn >= 0) player.cancelNext(); else pv.closePlayer(); break
            case Qt.Key_N: player.playEntry(player.index + 1); break
            case Qt.Key_P: player.playEntry(player.index - 1); break
            case Qt.Key_M: player.toggleMute(); break
            case Qt.Key_BracketLeft: if (shift) player.shiftSubs(-100); else player.shiftAudio(-100); break
            case Qt.Key_BraceLeft: player.shiftSubs(-100); break
            case Qt.Key_BracketRight: if (shift) player.shiftSubs(100); else player.shiftAudio(100); break
            case Qt.Key_BraceRight: player.shiftSubs(100); break
            case Qt.Key_Backslash: player.shiftAudio(-player.audioDelay); break
            case Qt.Key_Bar: player.shiftSubs(-player.subDelay); break
            case Qt.Key_Period: case Qt.Key_Greater: player.stepSpeed(1); break
            case Qt.Key_Comma: case Qt.Key_Less: player.stepSpeed(-1); break
            case Qt.Key_PageDown: player.jumpChapter(1); break
            case Qt.Key_PageUp: player.jumpChapter(-1); break
            case Qt.Key_S: player.skipIntro(); break
            case Qt.Key_I: player.toggleInfo(); break
            case Qt.Key_C: player.toggleCrop219(); break
            case Qt.Key_O: pv.setMini(!windowMode.mini); break
            case Qt.Key_T: if (!windowMode.mini) windowMode.setOnTop(!windowMode.onTop); break
            case Qt.Key_F8: if (Ui.diag) player.mark(); else return; break
            default: return
            }
            e.accepted = true
        }
    }

    // ---- видео ----
    Rectangle { anchors.fill: parent; color: "black" }
    Item {
        anchors.fill: parent
        clip: true
        MpvVideo {
            id: video
            width: videoMouse.resizing && videoMouse.resizeStartW > 0 ? videoMouse.resizeStartW : parent.width
            height: videoMouse.resizing && videoMouse.resizeStartH > 0 ? videoMouse.resizeStartH : parent.height
            scale: videoMouse.resizing && videoMouse.resizeStartW > 0 ? (parent.width / videoMouse.resizeStartW) : 1.0
            transformOrigin: Item.TopLeft
            engine: player.engine
            visible: player.open
        }
    }
    // До первого кадра — постер фильма во всю высоту, потом он гаснет поверх видео.
    Image {
        anchors.fill: parent
        source: player.poster !== "" ? "image://suslik" + player.poster : ""
        sourceSize.height: 1080   // постер во всю высоту: больше не нужно (без размера — исходник целиком)
        fillMode: Image.PreserveAspectFit
        asynchronous: true
        smooth: true
        opacity: !player.started || player.engine.videoWidth <= 0 ? 1 : 0
        visible: opacity > 0
        Behavior on opacity { NumberAnimation { duration: 380; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
    }
    Text {
        visible: !player.started && !player.closing
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: player.poster !== "" ? parent.bottom : undefined
        anchors.bottomMargin: 36
        anchors.verticalCenter: player.poster !== "" ? undefined : parent.verticalCenter
        text: player.status !== "" ? player.status : Ui.tx("player.otkryvaem")
        color: player.poster !== "" ? "#D9FFFFFF" : Theme.muted
        font.family: Theme.onest; font.pixelSize: 15
        style: Text.Raised; styleColor: "#80000000"
    }
    // libmpv нет: докачиваем (или ошибка с «Повторить»)
    Column {
        anchors.centerIn: parent
        spacing: 12
        visible: player.open && !player.engine.available
        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            text: mpvInstall.progress >= 0 ? Ui.tx("app.mpv_ne_skachan") + " · " + Math.round(mpvInstall.progress * 100) + " %"
                  : mpvInstall.error !== "" ? Ui.tx("app.mpv_ne_skachan_tekst", { err: mpvInstall.error }) : Ui.tx("app.mpv_ne_skachan")
            color: Theme.text; font.family: Theme.onest; font.pixelSize: 15
            width: Math.min(implicitWidth, pv.width - 80); wrapMode: Text.WordWrap; horizontalAlignment: Text.AlignHCenter
        }
        Chip_ {
            anchors.horizontalCenter: parent.horizontalCenter
            visible: mpvInstall.progress < 0
            label: Ui.tx("app.povtorit")
            onClicked: mpvInstall.start()
        }
    }
    Connections {
        target: mpvInstall
        function onFinished(ok) { if (ok && player.open) player.retry() }
    }

    // курсор ушёл из окна — панели прячутся. HoverHandler не перехватывает наведение, поэтому переход с видео на панель
    // (у неё своя MouseArea) не считается уходом: раньше панели от этого дёргались
    HoverHandler { id: pvHover; onHoveredChanged: if (!hovered && !pv.hold) pv.barVisible = false }

    // мышь над видео: щелчок — пауза, двойной — полный экран, колесо — громкость; в мини-плеере — тянуть окно
    MouseArea {
        id: videoMouse
        anchors.fill: parent
        hoverEnabled: true
        acceptedButtons: Qt.LeftButton
        property int cornerShape: Qt.ArrowCursor
        cursorShape: pv.mini ? cornerShape : pv.barsShown ? Qt.ArrowCursor : Qt.BlankCursor
        property point pressAt
        property bool moved: false
        property bool resizing: false
        property real resizeStartW: 1
        property real resizeStartH: 1
        onReleased: { if (resizing) windowMode.endResize(); resizing = false }
        onCanceled: { if (resizing) windowMode.endResize(); resizing = false }
        onPositionChanged: (m) => {
            pv.poke()
            if (pv.mini && !pressed) cornerShape = cornerCursor(m.x, m.y)
            if (resizing) { windowMode.resizeStep(); return }
            if (pv.mini && pressed && !moved && Math.hypot(m.x - pressAt.x, m.y - pressAt.y) > 4) { moved = true; windowMode.startMove() }
        }
        onPressed: (m) => {
            pressAt = Qt.point(m.x, m.y); moved = false
            pv.poke()
            if (pv.picker !== "") pv.picker = ""
            if (pv.mini) {
                const e = edgeAt(m.x, m.y)
                if (e) {
                    resizeStartW = width
                    resizeStartH = height
                    windowMode.beginResize(e)
                    resizing = true
                    moved = true
                }
            }
        }
        onClicked: if (!moved) clickTimer.restart()
        onDoubleClicked: {
            clickTimer.stop()
            if (pv.mini) pv.setMini(false); else pv.toggleFullscreen()
        }
        onWheel: (w) => { if (w.angleDelta.y !== 0) { player.stepVolume(w.angleDelta.y > 0 ? 5 : -5); pv.poke() } }
        Timer { id: clickTimer; interval: 260; onTriggered: player.togglePause() }
        // мини-плеер масштабируется за любые края и углы (пропорции держит WindowMode)
        function edgeAt(x, y) {
            const m = 14
            let h = 0, v = 0
            if (x < m) h = Qt.LeftEdge; else if (x > width - m) h = Qt.RightEdge
            if (y < m) v = Qt.TopEdge; else if (y > height - m) v = Qt.BottomEdge
            return h | v
        }
        function cornerCursor(x, y) {
            const e = edgeAt(x, y)
            if (!e) return Qt.ArrowCursor
            if (e === (Qt.LeftEdge | Qt.TopEdge) || e === (Qt.RightEdge | Qt.BottomEdge)) return Qt.SizeFDiagCursor
            if (e === (Qt.RightEdge | Qt.TopEdge) || e === (Qt.LeftEdge | Qt.BottomEdge)) return Qt.SizeBDiagCursor
            if (e & (Qt.LeftEdge | Qt.RightEdge)) return Qt.SizeHorCursor
            if (e & (Qt.TopEdge | Qt.BottomEdge)) return Qt.SizeVerCursor
            return Qt.ArrowCursor
        }
    }

    // ---- верхняя панель ----
    Item {
        id: topBar
        visible: !pv.mini
        width: parent.width; height: 75
        y: pv.barsShown ? 0 : -90
        opacity: pv.barsShown ? 1 : 0
        Behavior on y { NumberAnimation { duration: 280; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
        Behavior on opacity { NumberAnimation { duration: 260 } }
        Rectangle {
            anchors.fill: parent
            gradient: Gradient {
                GradientStop { position: 0; color: "#CC090B10" }
                GradientStop { position: 0.5; color: "#66090B10" }
                GradientStop { position: 1; color: "transparent" }
            }
        }
        MouseArea { anchors.fill: parent; hoverEnabled: true; onPositionChanged: pv.poke(); onPressed: pv.poke() }
        // курсор на панели — панели не прячутся
        HoverHandler { id: topHover; enabled: pv.barsShown && !pv.mini }
        Row {
            anchors.left: parent.left; anchors.leftMargin: 24
            anchors.verticalCenter: parent.verticalCenter
            spacing: 10
            GlassBtn { size: 42; tip: Ui.tx("player.v_biblioteku_esc"); onClicked: pv.closePlayer(); PlayerIcon { anchors.centerIn: parent; name: "back"; size: 18 } }
            GlassBtn {
                size: 42; active: windowMode.onTop
                tip: windowMode.onTop ? Ui.tx("player.otkrepit_okno_t") : Ui.tx("player.poverh_vseh_okon_t")
                onClicked: windowMode.setOnTop(!windowMode.onTop)
                PlayerIcon { anchors.centerIn: parent; name: "pin"; size: 17 }
            }
            Item { width: 6; height: 1 }
            Text {
                anchors.verticalCenter: parent.verticalCenter
                width: topBar.width - 24 - 42 * 2 - 30 - 24
                text: player.heading
                color: "#E6EEF0F5"; font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 14
                elide: Text.ElideRight
            }
        }
    }

    // ---- нижняя панель ----
    Item {
        id: bottom
        anchors.left: parent.left; anchors.right: parent.right
        height: parent.height
        y: pv.barsShown ? 0 : pv.mini ? 110 : 300
        opacity: pv.barsShown ? 1 : 0
        visible: opacity > 0.01
        Behavior on y { NumberAnimation { duration: 280; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
        Behavior on opacity { NumberAnimation { duration: 260 } }

        Rectangle {
            anchors.bottom: parent.bottom
            width: parent.width; height: pv.mini ? 100 : 200
            gradient: Gradient {
                GradientStop { position: 0; color: "transparent" }
                GradientStop { position: 0.33; color: "#33090B10" }
                GradientStop { position: 0.66; color: "#B3090B10" }
                GradientStop { position: 1; color: "#F0090B10" }
            }
        }
        // клики мимо кнопок по нижней полосе не уходят видео (с открытым меню — вся панель)
        MouseArea {
            anchors.bottom: parent.bottom
            width: parent.width; height: pv.picker !== "" || player.infoOpen ? col.height + 20 : Math.min(130, col.height + 20)
            hoverEnabled: true
            enabled: !pv.mini
            onPositionChanged: pv.poke()
            onPressed: { pv.poke(); if (pv.picker !== "") pv.picker = "" }
            onWheel: (w) => { if (w.angleDelta.y !== 0) player.stepVolume(w.angleDelta.y > 0 ? 5 : -5) }
        }

        Column {
            id: col
            HoverHandler { id: colHover; enabled: pv.barsShown }
            anchors.bottom: parent.bottom; anchors.bottomMargin: 14
            anchors.left: parent.left; anchors.right: parent.right
            anchors.leftMargin: pv.narrow ? 12 : 24; anchors.rightMargin: pv.narrow ? 12 : 24
            spacing: 0

            // сведения (I)
            Panel_ {
                shown: player.infoOpen
                radius: 10; padH: 14; padV: 7
                Text {
                    width: parent.width
                    text: player.infoText !== "" ? player.infoText : Ui.tx("player.sobiraem_dannye")
                    color: "#D9FFFFFF"; font.family: Theme.onest; font.pixelSize: 12; lineHeight: 1.3
                    wrapMode: Text.WrapAnywhere
                }
            }

            // «Пропустить заставку (S)»
            Item {
                width: parent.width; height: player.inIntro && !player.partyGuest && !pv.mini ? 46 : 0
                visible: height > 0
                Pill {
                    anchors.right: parent.right; anchors.top: parent.top
                    text: Ui.tx("player.propustit_zastavku_s")
                    onClicked: player.skipIntro()
                }
            }

            // настройки воспроизведения
            Panel_ {
                shown: pv.picker === "more"
                Column {
                    width: parent.width
                    spacing: 8
                    Item {
                        width: parent.width; height: 30
                        Text { anchors.verticalCenter: parent.verticalCenter; text: Ui.tx("player.nastroyki_vosproizvedeniya"); color: "white"; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 14 }
                        GlassBtn { anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; size: 26; onClicked: pv.picker = ""; PlayerIcon { anchors.centerIn: parent; name: "cross"; size: 11; color: "#99FFFFFF" } }
                    }
                    MoreRow {
                        label: Ui.tx("player.skorost")
                        Repeater {
                            model: [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2]
                            Chip_ { required property real modelData; label: player.rateText(modelData); on: Math.abs(modelData - (player.partyFilm ? 1 : player.rate)) < 0.01; onClicked: player.setSpeed(modelData) }
                        }
                    }
                    MoreRow {
                        label: Ui.tx("player.subtitry")
                        Repeater {
                            model: [75, 100, 125, 150, 200]
                            Chip_ { required property int modelData; label: modelData + "%"; on: modelData === player.subScale; onClicked: if (!on) player.setSubScaleValue(modelData) }
                        }
                        Item { width: 4; height: 1 }
                        Chip_ { label: Ui.tx("player.s"); onClicked: player.shiftSubs(-100) }
                        Text { height: 32; verticalAlignment: Text.AlignVCenter; text: Ui.tx("player.p_subdelay_ms", { ms: (player.subDelay > 0 ? "+" : "") + player.subDelay }); color: "white"; font.family: Theme.onest; font.pixelSize: 13 }
                        Chip_ { label: Ui.tx("player.s_2"); onClicked: player.shiftSubs(100) }
                        Chip_ { visible: player.subDelay !== 0; label: Ui.tx("player.sbros"); onClicked: player.shiftSubs(-player.subDelay) }
                    }
                    // задержка звука: раньше — только клавишами [ ] и \\
                    MoreRow {
                        label: Ui.tx("player.zaderzhka_zvuka")
                        Chip_ { label: Ui.tx("player.s"); onClicked: player.shiftAudio(-100) }
                        Text { height: 32; verticalAlignment: Text.AlignVCenter; text: Ui.tx("player.p_subdelay_ms", { ms: (player.audioDelay > 0 ? "+" : "") + player.audioDelay }); color: "white"; font.family: Theme.onest; font.pixelSize: 13 }
                        Chip_ { label: Ui.tx("player.s_2"); onClicked: player.shiftAudio(100) }
                        Chip_ { visible: player.audioDelay !== 0; label: Ui.tx("player.sbros"); onClicked: player.shiftAudio(-player.audioDelay) }
                        Chip_ { visible: player.saveDelayText !== ""; label: player.saveDelayText; onClicked: player.saveDelay() }
                    }
                    MoreRow {
                        label: Ui.tx("player.rezhimy")
                        Chip_ { label: Ui.tx("player.nochnoy_zvuk"); on: player.nightMode; onClicked: player.toggleNight() }
                        Chip_ { label: Ui.tx("player.poverh_okon_t"); on: windowMode.onTop; onClicked: windowMode.setOnTop(!windowMode.onTop) }
                        Chip_ { label: Ui.tx("player.mini_pleer_o"); on: windowMode.mini; onClicked: pv.setMini(!windowMode.mini) }
                        Chip_ { label: "21:9"; on: player.crop219; onClicked: player.toggleCrop219() }
                        Chip_ { label: Ui.tx("player.informaciya_i"); on: player.infoOpen; onClicked: player.toggleInfo() }
                    }
                    MoreRow {
                        visible: player.chapters.length > 1
                        label: Ui.tx("player.glavy")
                        Repeater {
                            model: player.chapters.slice(0, 40)
                            Chip_ {
                                required property var modelData
                                required property int index
                                label: (index + 1) + ". " + (modelData.title || Ui.tx("player.glava")).substring(0, 28)
                                on: index === player.chapterIndex
                                onClicked: player.jumpChapter(index - player.chapterIndex)
                            }
                        }
                    }
                    Text { width: parent.width; wrapMode: Text.WordWrap; text: Ui.tx("player.razmer_subtitrov_i_nochnoy"); color: "#73FFFFFF"; font.family: Theme.onest; font.pixelSize: 12 }
                }
            }

            // аудиодорожки и субтитры
            Panel_ {
                id: tracks
                shown: pv.picker === "audio" || pv.picker === "spu"
                property string kind: "audio"
                Connections { target: pv; function onPickerChanged() { if (pv.picker === "audio" || pv.picker === "spu") tracks.kind = pv.picker } }
                readonly property bool audio: kind === "audio"
                readonly property var items: audio ? player.engine.audioTracks
                    : [{ id: -1, label: Ui.tx("player.vyklyuchit"), selected: !player.engine.subTracks.some(t => t.selected) }].concat(player.engine.subTracks)
                readonly property int pending: audio ? player.engine.pendingAudio : player.engine.pendingSubs + player.subsFetching
                Column {
                    width: parent.width
                    spacing: 10
                    Item {
                        width: parent.width; height: 28
                        Text { anchors.verticalCenter: parent.verticalCenter; text: tracks.audio ? Ui.tx("player.audiodorozhki") : Ui.tx("player.subtitry"); color: "white"; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 14 }
                        GlassBtn { anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; size: 26; onClicked: pv.picker = ""; PlayerIcon { anchors.centerIn: parent; name: "cross"; size: 11; color: "#99FFFFFF" } }
                    }
                    Flow {
                        width: parent.width
                        spacing: 6
                        Text {
                            visible: tracks.items.filter(t => t.id !== -1).length === 0 && tracks.pending === 0 && !player.engine.loaded
                            text: Ui.tx("player.dorozhki_esche_ne_opredeleny"); color: "#80FFFFFF"; font.family: Theme.onest; font.pixelSize: 13
                        }
                        Repeater {
                            model: tracks.items
                            Chip_ {
                                required property var modelData
                                label: modelData.label; on: modelData.selected
                                onClicked: {
                                    if (tracks.audio) player.selectAudio(modelData.id, modelData.label)
                                    else player.selectSub(modelData.id, modelData.label)
                                    pv.picker = ""
                                }
                            }
                        }
                        Repeater {
                            model: tracks.pending
                            Rectangle {
                                width: 70 + (index % 3) * 22; height: 32; radius: 16
                                color: "#14FFFFFF"; border.color: "#1FFFFFFF"
                                SequentialAnimation on opacity { loops: Animation.Infinite; NumberAnimation { to: 0.45; duration: 700 } NumberAnimation { to: 1; duration: 700 } }
                            }
                        }
                    }
                    Chip_ { visible: tracks.audio && player.saveDelayText !== ""; label: player.saveDelayText; onClicked: player.saveDelay() }
                    Chip_ { visible: tracks.audio && player.deleteTrackText !== ""; label: player.deleteTrackText; onClicked: player.deleteTrack() }
                }
            }

            // карточка следующей серии (на титрах или в конце)
            Item {
                width: parent.width; height: player.nextIn >= 0 && player.index < player.queueSize - 1 ? 52 : 0
                visible: height > 0
                Rectangle {
                    anchors.right: parent.right; anchors.top: parent.top
                    height: 42; radius: 21
                    width: nextRow.width + 24
                    color: "#CC161B26"; border.color: "#33FFFFFF"
                    Row {
                        id: nextRow
                        anchors.verticalCenter: parent.verticalCenter
                        x: 16
                        spacing: 10
                        Text { anchors.verticalCenter: parent.verticalCenter; text: player.nextTitle; color: Theme.text; font.family: Theme.onest; font.pixelSize: 13 }
                        Rectangle {
                            anchors.verticalCenter: parent.verticalCenter
                            width: cnt.implicitWidth + 14; height: cnt.implicitHeight + 5; radius: 6
                            color: "#29FFFFFF"; border.color: "#2EFFFFFF"
                            Text { id: cnt; anchors.centerIn: parent; text: Ui.tx("player.cherez_nextin_s", { nextIn: player.nextIn }); color: "white"; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 13 }
                        }
                        GlassBtn { size: 32; tip: Ui.tx("player.vklyuchit_seychas"); onClicked: player.playNext(); PlayerIcon { anchors.centerIn: parent; name: "play"; size: 12.5 } }
                        GlassBtn { size: 32; tip: Ui.tx("player.otmena"); onClicked: player.cancelNext(); PlayerIcon { anchors.centerIn: parent; name: "cross"; size: 12 } }
                    }
                }
            }

            // время и полоса
            Item {
                width: parent.width; height: 30
                Rectangle {
                    id: nowBox
                    anchors.verticalCenter: parent.verticalCenter
                    width: Math.max(52, (player.partyGuest ? 13 + liveWidth.implicitWidth : nowText.implicitWidth) + 12); height: nowText.implicitHeight + 4; radius: 6
                    TextMetrics { id: liveWidth; text: Ui.tx("party.vmeste"); font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 13 }
                    color: pv.hoverAt >= 0 || pv.scrubAt >= 0 ? "#29FFFFFF" : "transparent"
                    // гость комнаты «Вместе»: полоса — «прямой эфир», ведёт ведущий
                    Row {
                        visible: player.partyGuest
                        anchors.centerIn: parent
                        spacing: 6
                        Rectangle { anchors.verticalCenter: parent.verticalCenter; width: 7; height: 7; radius: 3.5; color: "#E5484D" }
                        Text { text: Ui.tx("party.vmeste"); color: "white"; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 13 }
                    }
                    Text {
                        id: nowText
                        visible: !player.partyGuest
                        anchors.centerIn: parent
                        text: player.clock(pv.hoverAt >= 0 ? pv.hoverAt : pv.scrubAt >= 0 ? pv.scrubAt : player.time)
                        color: "white"; font.family: Theme.onest; font.pixelSize: 13
                        font.weight: pv.hoverAt >= 0 || pv.scrubAt >= 0 ? Font.Bold : Font.Medium
                    }
                }
                // как у стрима: полоса заполнена до конца, перемотки нет
                Rectangle {
                    visible: player.partyGuest
                    anchors.left: nowBox.right; anchors.right: lenText.left
                    anchors.leftMargin: 8; anchors.rightMargin: 8
                    anchors.verticalCenter: parent.verticalCenter
                    height: 4; radius: 2
                    color: "#E5484D"
                }
                SeekBar_ {
                    visible: !player.partyGuest
                    anchors.left: nowBox.right; anchors.right: lenText.left
                    anchors.leftMargin: 8; anchors.rightMargin: 8
                    anchors.verticalCenter: parent.verticalCenter
                }
                Text {
                    id: lenText
                    anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                    width: Math.max(52, implicitWidth + 12); horizontalAlignment: Text.AlignRight
                    text: player.partyGuest ? player.clock(player.time) : player.length > 0 ? player.clock(player.length) : "--:--"
                    color: Theme.muted; font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 13
                }
            }

            // кнопки: мини-плеер
            Item {
                visible: pv.mini
                width: parent.width; height: visible ? 50 : 0
                Row {
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 10
                    PlayButton { size: 42 }
                    GlassBtn { visible: player.prevTitle !== "" && !player.partyGuest; size: 42; tip: Ui.tx("player.predyduschaya"); onClicked: player.playEntry(player.index - 1); PlayerIcon { anchors.centerIn: parent; name: "prev"; size: 16 } }
                    GlassBtn { visible: player.nextEntryTitle !== "" && !player.partyGuest; size: 42; tip: Ui.tx("player.sleduyuschaya"); onClicked: player.playEntry(player.index + 1); PlayerIcon { anchors.centerIn: parent; name: "next"; size: 16 } }
                }
                Row {
                    anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                    spacing: 10
                    GlassBtn {
                        size: 42
                        active: player.crop219
                        tip: player.crop219 ? Ui.tx("player.format_iskhodnyy") : Ui.tx("player.format_21_9")
                        onClicked: player.toggleCrop219()
                        Text {
                            anchors.centerIn: parent
                            text: "21:9"
                            color: player.crop219 ? "#FFFFFF" : "#B3FFFFFF"
                            font.family: Theme.onest
                            font.pixelSize: 11
                            font.weight: Font.DemiBold
                        }
                    }
                    GlassBtn { size: 42; tip: Ui.tx("player.obychnyy_rezhim_o"); onClicked: pv.setMini(false); PlayerIcon { anchors.centerIn: parent; name: "mini"; size: 16 } }
                    GlassBtn { size: 42; tip: Ui.tx("player.zakryt_esc"); onClicked: pv.closePlayer(); PlayerIcon { anchors.centerIn: parent; name: "cross"; size: 11 } }
                }
            }

            // кнопки: обычный вид
            Item {
                visible: !pv.mini
                width: parent.width; height: visible ? 56 : 0
                Row {
                    id: leftGroup
                    anchors.left: parent.left; anchors.leftMargin: 4
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: pv.narrow ? 4 : pv.compact ? 6 : 10
                    GlassBtn {
                        visible: player.prevTitle !== "" && !player.partyGuest; size: pv.btn
                        tip: Ui.tx("player.predyduschaya_p", { p: player.prevTitle })
                        onClicked: player.playEntry(player.index - 1)
                        PlayerIcon { anchors.centerIn: parent; name: "prev"; size: pv.compact ? 14 : 16 }
                    }
                    GlassBtn {
                        visible: player.nextEntryTitle !== "" && !player.partyGuest; size: pv.btn
                        tip: Ui.tx("player.sleduyuschaya_p", { p: player.nextEntryTitle })
                        onClicked: player.playEntry(player.index + 1)
                        PlayerIcon { anchors.centerIn: parent; name: "next"; size: pv.compact ? 14 : 16 }
                    }
                    Text {
                        anchors.verticalCenter: parent.verticalCenter
                        width: Math.max(0, centerGroup.x - leftGroup.x - 120)
                        text: player.started ? player.status : ""
                        color: "white"; font.family: Theme.onest; font.pixelSize: 12
                        elide: Text.ElideRight
                    }
                }
                Row {
                    id: centerGroup
                    anchors.centerIn: parent
                    spacing: pv.compact ? 8 : 12
                    GlassBtn { visible: !player.partyGuest; size: pv.compact ? 38 : 42; tip: Ui.tx("player.nazad_na_sek"); onClicked: player.seekBy(-10000); SeekIcon { forward: false } }
                    PlayButton { size: pv.narrow ? 40 : pv.compact ? 44 : 48 }
                    GlassBtn { visible: !player.partyGuest; size: pv.compact ? 38 : 42; tip: Ui.tx("player.vpered_na_sek"); onClicked: player.seekBy(10000); SeekIcon { forward: true } }
                }
                Row {
                    anchors.right: parent.right; anchors.rightMargin: 4
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: pv.narrow ? 4 : pv.compact ? 6 : 10
                    layoutDirection: Qt.LeftToRight
                    Rectangle { anchors.verticalCenter: parent.verticalCenter; width: 1; height: 18; color: "#33FFFFFF" }
                    GlassBtn { size: pv.btn; active: pv.picker === "audio"; onClicked: pv.openPicker("audio"); PlayerIcon { anchors.centerIn: parent; name: "audio"; size: pv.compact ? 18 : 20 } }
                    GlassBtn { size: pv.btn; active: pv.picker === "spu"; onClicked: pv.openPicker("spu"); PlayerIcon { anchors.centerIn: parent; name: "subs"; size: pv.compact ? 20 : 22 } }
                    GlassBtn {
                        size: pv.btn; active: pv.picker === "more"; onClicked: pv.openPicker("more")
                        width: player.rate !== 1 && !player.partyFilm && !pv.narrow ? pv.btn + 22 : pv.btn
                        radius: height / 2
                        Row {
                            anchors.centerIn: parent
                            PlayerIcon { anchors.verticalCenter: parent.verticalCenter; name: "gear"; size: pv.compact ? 18 : 20 }
                            Text { visible: player.rate !== 1 && !player.partyFilm && !pv.narrow; anchors.verticalCenter: parent.verticalCenter; text: " " + player.rateText(player.rate); color: "white"; font.family: Theme.onest; font.pixelSize: 10; font.bold: true }
                        }
                    }
                    VolumeCapsule { vertical: pv.compact }
                    GlassBtn { size: pv.btn; onClicked: pv.toggleFullscreen(); PlayerIcon { anchors.centerIn: parent; name: windowMode.fullscreen ? "unfull" : "full"; size: pv.compact ? 18 : 20 } }
                }
            }
        }
    }

    // уголок для изменения размера мини-плеера
    Canvas {
        visible: pv.mini && pv.barsShown
        anchors.right: parent.right; anchors.bottom: parent.bottom
        anchors.margins: 4
        width: 12; height: 12
        onPaint: {
            const g = getContext("2d"); g.reset()
            g.strokeStyle = "#73FFFFFF"; g.lineWidth = 1.5
            g.beginPath(); g.moveTo(12, 3); g.lineTo(3, 12); g.stroke()
            g.beginPath(); g.moveTo(12, 7); g.lineTo(7, 12); g.stroke()
        }
    }

    // ---- части ----

    // Круглая стеклянная кнопка с мягким ореолом при наведении (GlassIconButton)
    component GlassBtn: Btn {
        id: gb
        property real size: 42
        property bool active: false
        property string tip: ""
        property real radius: size / 2
        width: size; height: size
        hoverUp: 1.05
        readonly property real glow: pressed ? 0.48 : active ? 0.42 : hovered ? 0.30 : 0
        Shape {
            anchors.centerIn: parent
            width: gb.width * 1.16; height: gb.height * 1.16
            opacity: gb.glow > 0 ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 160 } }
            ShapePath {
                strokeWidth: 0; strokeColor: "transparent"
                fillGradient: RadialGradient {
                    centerX: gb.width * 0.58; centerY: gb.height * 0.58; centerRadius: gb.width * 0.58
                    focalX: centerX; focalY: centerY
                    GradientStop { position: 0; color: Qt.rgba(1, 1, 1, Math.max(gb.glow, 0.3)) }
                    GradientStop { position: 0.45; color: Qt.rgba(1, 1, 1, Math.max(gb.glow, 0.3) * 0.5) }
                    GradientStop { position: 0.8; color: Qt.rgba(1, 1, 1, Math.max(gb.glow, 0.3) * 0.15) }
                    GradientStop { position: 1; color: "transparent" }
                }
                startX: 0; startY: gb.height * 0.58
                PathArc { x: gb.width * 1.16; y: gb.height * 0.58; radiusX: gb.width * 0.58; radiusY: gb.height * 0.58 }
                PathArc { x: 0; y: gb.height * 0.58; radiusX: gb.width * 0.58; radiusY: gb.height * 0.58 }
            }
        }
        Tip { text: gb.tip; shown: gb.hovered && gb.tip !== "" }
    }

    component PlayButton: GlassBtn {
        readonly property bool showPause: player.playing && !player.paused
        // гость: пока ведущий держит паузу, снять её нельзя
        readonly property bool locked: player.playLocked && !showPause
        enabled_: !locked
        opacity: locked ? 0.45 : 1
        tip: locked ? Ui.tx("party.pauzu_snimaet_sozdatel") : showPause ? Ui.tx("player.pauza_probel") : Ui.tx("player.vosproizvedenie_probel")
        onClicked: player.togglePause()
        Row {
            anchors.centerIn: parent
            spacing: 4.5
            visible: parent.showPause
            Rectangle { width: 4.2; height: 18; radius: 1.5; color: "white" }
            Rectangle { width: 4.2; height: 18; radius: 1.5; color: "white" }
        }
        PlayerIcon { visible: !parent.showPause; anchors.centerIn: parent; anchors.horizontalCenterOffset: 1.5; name: "play"; size: 20 }
    }

    component SeekIcon: Item {
        property bool forward
        anchors.centerIn: parent
        width: 28; height: 28
        PlayerIcon { anchors.fill: parent; size: 28; name: parent.forward ? "seekFwd" : "seekBack" }
        Text { anchors.centerIn: parent; text: "10"; color: "white"; font.family: Theme.onest; font.bold: true; font.pixelSize: 10 }
    }

    // Подсказка над кнопкой
    component Tip: Rectangle {
        property string text
        property bool shown
        anchors.bottom: parent.top; anchors.bottomMargin: 8
        anchors.horizontalCenter: parent.horizontalCenter
        width: tipText.implicitWidth + 16; height: tipText.implicitHeight + 10; radius: 7
        color: "#F0141824"; border.color: "#33FFFFFF"
        opacity: shown ? 1 : 0
        visible: opacity > 0
        Behavior on opacity { SequentialAnimation { PauseAnimation { duration: 350 } NumberAnimation { duration: 140 } } }
        Text { id: tipText; anchors.centerIn: parent; text: parent.text; color: Theme.text; font.family: Theme.onest; font.pixelSize: 12 }
    }

    // Пункт меню дорожек и настроек (PlayerChip)
    component Chip_: Btn {
        id: ch
        property string label
        property bool on: false
        width: chipText.implicitWidth + 28; height: 32
        hoverUp: 1.03
        Rectangle {
            anchors.fill: parent; radius: 16
            color: ch.on ? "#F2FFFFFF" : ch.hovered ? "#2EFFFFFF" : "#17FFFFFF"
            border.color: ch.on ? "white" : "#29FFFFFF"
            Behavior on color { ColorAnimation { duration: 140 } }
        }
        Text { id: chipText; anchors.centerIn: parent; text: ch.label; color: ch.on ? "#0B0E14" : "white"; font.family: Theme.onest; font.pixelSize: 13; font.weight: ch.on ? Font.DemiBold : Font.Normal }
    }

    component Pill: Btn {
        property string text
        width: pillText.implicitWidth + 32; height: 36
        Rectangle { anchors.fill: parent; radius: 18; color: "#CC161B26"; border.color: "#4DFFFFFF" }
        Text { id: pillText; anchors.centerIn: parent; text: parent.text; color: "white"; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 13 }
    }

    // Строка «Ещё»: подпись слева, пункты потоком
    component MoreRow: Item {
        default property alias content: flow.data
        property string label
        width: parent ? parent.width : 0
        height: Math.max(32, flow.implicitHeight)
        Text { width: 118; height: 32; verticalAlignment: Text.AlignVCenter; text: parent.label; color: "#99FFFFFF"; font.family: Theme.onest; font.pixelSize: 13 }
        Flow { id: flow; x: 118; width: parent.width - 118; spacing: 6 }
    }

    // Панель над полосой (меню, сведения): появляется сдвигом снизу
    component Panel_: Item {
        id: pn
        default property alias content: inner.data
        property bool shown: false
        property real radius: 14
        property real padH: 16
        property real padV: 16
        width: parent ? parent.width : 0
        height: shown ? box.height + 12 : 0
        visible: opacity > 0.01
        opacity: shown ? 1 : 0
        clip: true
        Behavior on opacity { NumberAnimation { duration: 180 } }
        Rectangle {
            id: box
            width: parent.width
            height: inner.childrenRect.height + pn.padV * 2
            radius: pn.radius
            color: "#F20E1118"; border.color: "#26FFFFFF"
            y: pn.shown ? 0 : 24
            Behavior on y { NumberAnimation { duration: 220; easing.type: Easing.BezierSpline; easing.bezierCurve: [0.16, 1, 0.3, 1, 1, 1] } }
            MouseArea { anchors.fill: parent; hoverEnabled: true; onPositionChanged: pv.poke() } // клики внутри не закрывают меню
            Item { id: inner; x: pn.padH; y: pn.padV; width: parent.width - pn.padH * 2; height: childrenRect.height }
        }
    }

    // Полоса времени: запас, заставка (янтарная), титры, главы; ползунок растёт при наведении
    component SeekBar_: Item {
        id: sb
        height: 24
        readonly property real len: player.length
        readonly property bool active: ma.containsMouse || ma.pressed
        property real grow: active ? 1 : 0
        Behavior on grow { NumberAnimation { duration: 160 } }
        readonly property real pos: pv.scrubAt >= 0 ? pv.scrubAt : player.time
        readonly property int th: Math.round(4 + 3 * grow)
        readonly property int lineY: Math.round((sb.height - th) / 2)
        readonly property int dotX: Math.round(sb.width * sb.frac(sb.pos))
        readonly property int r: Math.round(5 + 3 * grow)
        function frac(t) { return len > 0 ? Math.max(0, Math.min(1, t / len)) : 0 }

        // Фоновая полоса
        Rectangle { y: sb.lineY; width: sb.width; height: sb.th; radius: sb.th / 2; color: Theme.line2 }
        // Буфер
        Rectangle {
            visible: player.bufferedMs > 0 && sb.len > 0
            y: sb.lineY; width: Math.round(sb.width * sb.frac(player.bufferedMs)); height: sb.th; radius: sb.th / 2
            color: "#80B4B8C4"
        }
        // Заставка
        Rectangle {
            visible: player.introRange.length === 2 && sb.len > 0
            x: Math.round(visible ? sb.width * sb.frac(player.introRange[0]) : 0)
            width: Math.round(visible ? sb.width * (sb.frac(player.introRange[1]) - sb.frac(player.introRange[0])) : 0)
            y: sb.lineY; height: sb.th; radius: sb.th / 2; color: "#80F5A623"
        }
        // Титры
        Rectangle {
            visible: player.creditsRange.length === 2 && sb.len > 0
            x: Math.round(visible ? sb.width * sb.frac(player.creditsRange[0]) : 0)
            width: Math.round(visible ? sb.width * (sb.frac(player.creditsRange[1]) - sb.frac(player.creditsRange[0])) : 0)
            y: sb.lineY; height: sb.th; radius: sb.th / 2; color: "#808E96A8"
        }
        // Воспроизведённая часть
        Rectangle { y: sb.lineY; width: sb.dotX; height: sb.th; radius: sb.th / 2; color: "white" }
        // Главы
        Repeater {
            model: player.chapters
            Rectangle {
                required property var modelData
                visible: modelData.timeMs > 0 && modelData.timeMs < sb.len
                x: Math.round(sb.width * sb.frac(modelData.timeMs) - 1.5)
                y: sb.lineY - 1; width: 3; height: sb.th + 2; color: Theme.booth
            }
        }
        // точка под мышью
        Rectangle {
            visible: ma.containsMouse && !ma.pressed && Math.abs(ma.mouseX - sb.dotX) > 10
            x: Math.round(ma.mouseX - 3); y: Math.round(sb.height / 2 - 3); width: 6; height: 6; radius: 3
            color: Qt.rgba(1, 1, 1, 0.75 * sb.grow)
        }
        // ползунок
        Rectangle {
            x: sb.dotX - sb.r; y: Math.round(sb.height / 2 - sb.r); width: sb.r * 2; height: sb.r * 2; radius: sb.r
            color: "white"
            Rectangle { anchors.centerIn: parent; width: parent.width + Math.round(16 * sb.grow); height: width; radius: width / 2; color: Qt.rgba(1, 1, 1, 0.22 * sb.grow); z: -1 }
        }
        MouseArea {
            id: ma
            anchors.fill: parent
            anchors.topMargin: -6; anchors.bottomMargin: -6
            hoverEnabled: true
            cursorShape: Qt.PointingHandCursor
            function at(x) { return sb.len <= 0 ? 0 : Math.max(0, Math.min(1, x / sb.width)) * sb.len }
            onPositionChanged: (m) => {
                pv.poke()
                pv.hoverAt = containsMouse && !pressed ? at(m.x) : -1
                if (pressed && sb.len > 0) { pv.scrubAt = at(m.x); player.setScrubbing(true) }
            }
            onExited: pv.hoverAt = -1
            onPressed: (m) => { if (sb.len > 0) { pv.scrubAt = at(m.x); pv.hoverAt = -1; player.setScrubbing(true) } }
            onReleased: (m) => {
                if (pv.scrubAt >= 0) { const t = at(m.x); pv.scrubAt = -1; player.seekTo(t) }
            }
            onCanceled: { pv.scrubAt = -1; player.setScrubbing(false) }
        }
    }

    // Громкость: кнопка, при наведении — ползунок рядом (узкое окно — над кнопкой)
    component VolumeCapsule: Item {
        id: vc
        property bool vertical: false
        readonly property bool open: hover.hovered || popHover.hovered || drag.pressed || wheelShow.running
        width: pv.btn + (open && !vertical ? 118 : 0)
        height: pv.btn
        Behavior on width { NumberAnimation { duration: 200; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
        HoverHandler { id: hover }
        Connections { target: player; function onSoundChanged() { if (player.volumeWheelAt > 0) wheelShow.restart() } }
        Timer { id: wheelShow; interval: 1200 }
        Rectangle {
            anchors.fill: parent; radius: height / 2
            color: vc.open && !vc.vertical ? "#2E2A3A5C" : "transparent"
            border.color: vc.open && !vc.vertical ? "#26FFFFFF" : "transparent"
        }
        GlassBtn {
            size: pv.btn
            onClicked: player.toggleMute()
            PlayerIcon { anchors.centerIn: parent; name: "volume"; size: pv.compact ? 19 : 21; volume: player.volume; muted: player.muted }
        }
        // горизонтальный
        Item {
            visible: !vc.vertical && vc.width > pv.btn + 20
            x: pv.btn; width: vc.width - pv.btn - 14; height: parent.height
            clip: true
            Text { id: volText; anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter; width: 34; horizontalAlignment: Text.AlignRight
                text: player.volume + "%"; color: player.volume > 100 ? Theme.lamp : Theme.text; font.family: Theme.onest; font.bold: true; font.pixelSize: 10 }
            Item {
                id: track
                anchors.left: parent.left; anchors.right: volText.left; anchors.rightMargin: 6
                height: parent.height
                Rectangle { anchors.verticalCenter: parent.verticalCenter; width: parent.width; height: 4; radius: 2; color: "#33FFFFFF" }
                Rectangle { anchors.verticalCenter: parent.verticalCenter; width: parent.width * player.volume / 150; height: 4; radius: 2; color: "white" }
                Rectangle { x: parent.width * player.volume / 150 - 5.5; anchors.verticalCenter: parent.verticalCenter; width: 11; height: 11; radius: 5.5; color: "white" }
                MouseArea {
                    id: drag
                    anchors.fill: parent
                    function set(x) { player.setVolume(Math.round(Math.max(0, Math.min(1, x / width)) * 150)) }
                    onPressed: (m) => set(m.x)
                    onPositionChanged: (m) => { if (pressed) set(m.x) }
                }
            }
        }
        // вертикальный — над кнопкой
        Rectangle {
            visible: vc.vertical && vc.open
            anchors.bottom: parent.top; anchors.bottomMargin: 6
            anchors.horizontalCenter: parent.horizontalCenter
            width: 38; height: 134; radius: 19
            color: "#F2141824"; border.color: "#4DFFFFFF"
            HoverHandler { id: popHover }
            Text { anchors.top: parent.top; anchors.topMargin: 8; anchors.horizontalCenter: parent.horizontalCenter
                text: player.volume + "%"; color: player.volume > 100 ? Theme.lamp : Theme.text; font.family: Theme.onest; font.bold: true; font.pixelSize: 10 }
            Item {
                anchors.top: parent.top; anchors.topMargin: 28; anchors.bottom: parent.bottom; anchors.bottomMargin: 10
                anchors.horizontalCenter: parent.horizontalCenter
                width: 28
                Rectangle { anchors.horizontalCenter: parent.horizontalCenter; width: 4; height: parent.height; radius: 2; color: "#33FFFFFF" }
                Rectangle { anchors.horizontalCenter: parent.horizontalCenter; anchors.bottom: parent.bottom; width: 4; height: parent.height * player.volume / 150; radius: 2; color: "white" }
                MouseArea {
                    anchors.fill: parent
                    function set(y) { player.setVolume(Math.round(Math.max(0, Math.min(1, 1 - y / height)) * 150)) }
                    onPressed: (m) => set(m.y)
                    onPositionChanged: (m) => { if (pressed) set(m.y) }
                }
            }
        }
    }
}
