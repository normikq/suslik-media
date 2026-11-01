import QtQuick
import Suslik.Native

// «Поиск раздач» (SearchView.kt): запрос по всем трекерам сервера и RuTracker с ПК, фильтры, значки раздачи, страницы,
// «＋ В библиотеку». Окно «Трекеры» — какие источники искать и вход на RuTracker.
// «По настроению»: ИИ подбирает фильмы со всего мира (сервер: Gemini + TMDB), карточки AiDiscoveryCard сверены с
// медиатекой — «Смотреть» сразу или «Найти раздачу» обычным поиском по трекерам.
Item {
    id: v
    property bool active: false
    signal playRequested(var magnetId, string path)

    SearchModel { id: s; library: lib }

    onActiveChanged: {
        if (!active) s.leave()
    }

    function toTop() { flick.contentY = 0 }
    /// Проверка вёрстки (SM_OPEN=search:…): запрос как будто введён и нажат «Найти».
    function search(q) { queryField.text = q; s.run(q) }
    function openTrackers(tab) { trackers.tab = tab; trackers.show() }
    /// Вкладка открыта с подбором по настроению (кнопка медиатеки, SM_OPEN=discover[:запрос]).
    function openAi(q) {
        s.aiMode = true
        toTop()
        if (q) { aiField.text = q; s.runAi(q, false) }
        else Qt.callLater(() => aiField.input.forceActiveFocus())
    }
    /// «Найти раздачу» из карточки: обычный поиск по трекерам оригинальным названием и годом.
    function findOnTrackers(q) {
        s.aiMode = false
        queryField.text = q
        toTop()
        s.run(q)
    }
    function runVibe(q, fresh) { aiField.text = q; s.runAi(q, fresh) }

    Flickable {
        id: flick
        anchors.fill: parent
        contentHeight: content.height + 56
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        flickableDirection: Flickable.VerticalFlick
        WheelHandler {
            acceptedDevices: PointerDevice.Mouse | PointerDevice.TouchPad
            onWheel: (ev) => {
                const dy = ev.pixelDelta.y !== 0 ? ev.pixelDelta.y : ev.angleDelta.y / 120 * 110
                flick.contentY = Math.max(0, Math.min(flick.contentHeight - flick.height, flick.contentY - dy))
            }
        }

        Column {
            id: content
            x: 28; y: 28
            width: flick.width - 56

            Panel {
                width: parent.width
                Item {
                    width: parent.width
                    height: Math.max(pageTitle.implicitHeight, modeSwitch.height)
                    Text {
                        id: pageTitle
                        anchors.verticalCenter: parent.verticalCenter
                        width: parent.width - modeSwitch.width - 16
                        text: s.aiMode ? Ui.tx("discover.title") : Ui.tx("search.poisk_razdach")
                        color: Theme.text
                        elide: Text.ElideRight
                        font.family: Theme.unbounded; font.weight: Font.Medium; font.pixelSize: 22
                    }
                    ModeSwitch { id: modeSwitch; anchors { right: parent.right; verticalCenter: parent.verticalCenter } }
                }
                Text {
                    width: parent.width
                    topPadding: 4; bottomPadding: 14
                    text: s.aiMode ? Ui.tx("discover.subtitle") : Ui.tx("search.odin_zapros_srazu_po")
                    color: Theme.muted; wrapMode: Text.WordWrap
                    font.family: Theme.onest; font.pixelSize: 14
                }

                // ---- по настроению: поле с янтарной искрой, «Подобрать», быстрые настроения ----
                Row {
                    visible: s.aiMode
                    width: parent.width
                    spacing: 10
                    Field {
                        id: aiField
                        width: parent.width - aiBtn.width - 10
                        placeholder: Ui.tx("discover.placeholder")
                        input.anchors.rightMargin: 42
                        onAccepted: s.runAi(text, false)
                        Icon {
                            id: lamp
                            anchors { right: parent.right; rightMargin: 14; verticalCenter: parent.verticalCenter }
                            name: "sparkle"; size: 18; color: Theme.lamp
                            // горит ровно; пока ИИ думает — мерцает искрой
                            SequentialAnimation on opacity {
                                running: s.aiBusy && !Theme.simple
                                loops: Animation.Infinite
                                onRunningChanged: if (!running) lamp.opacity = 1
                                NumberAnimation { to: 0.35; duration: 420; easing.type: Easing.InOutSine }
                                NumberAnimation { to: 1; duration: 420; easing.type: Easing.InOutSine }
                            }
                            SequentialAnimation on scale {
                                running: s.aiBusy && !Theme.simple
                                loops: Animation.Infinite
                                onRunningChanged: if (!running) lamp.scale = 1
                                NumberAnimation { to: 1.22; duration: 420; easing.type: Easing.InOutSine }
                                NumberAnimation { to: 0.92; duration: 420; easing.type: Easing.InOutSine }
                            }
                        }
                    }
                    LampButton {
                        id: aiBtn
                        height: 44
                        text: s.aiBusy ? Ui.tx("discover.running") : Ui.tx("discover.run")
                        enabled_: !s.aiBusy
                        onClicked: s.runAi(aiField.text, false)
                    }
                }
                Flow {
                    visible: s.aiMode
                    width: parent.width
                    topPadding: 12
                    spacing: 8
                    Text {
                        height: 34
                        verticalAlignment: Text.AlignVCenter
                        rightPadding: 2
                        text: Ui.tx("discover.vibes")
                        color: Theme.muted
                        font.family: Theme.onest; font.pixelSize: 13
                    }
                    Repeater {
                        model: [["cup", "cozy"], ["spiral", "mind"], ["lightning", "suspense"], ["smile", "light"],
                                ["planet", "space"], ["heart", "drama"], ["dice", "random"]]
                        delegate: VibeChip {
                            icon: modelData[0]
                            text: Ui.tx("discover.vibe_" + modelData[1])
                            enabled_: !s.aiBusy
                            onClicked: v.runVibe(Ui.tx("discover.vibe_" + modelData[1] + "_q"), modelData[1] === "random")
                        }
                    }
                }
                Text {
                    visible: s.aiMode && s.aiStatus !== ""
                    width: parent.width
                    topPadding: 12
                    text: s.aiStatus
                    color: s.aiStatusOk ? (s.aiBusy ? Theme.muted : Theme.ok) : Theme.bad
                    wrapMode: Text.WordWrap
                    font.family: Theme.onest; font.pixelSize: 14
                }

                // ---- по названию: трекеры ----
                Row {
                    visible: !s.aiMode
                    width: parent.width
                    spacing: 10
                    Field {
                        id: queryField
                        width: parent.width - findBtn.width - trackersBtn.width - 18
                        placeholder: Ui.tx("search.nazvanie_filma_ili_seriala")
                        onAccepted: s.run(text)
                    }
                    LampButton {
                        id: findBtn
                        height: 44
                        text: s.busy ? Ui.tx("search.ischem") : Ui.tx("search.nayti")
                        enabled_: !s.busy
                        onClicked: s.run(queryField.text)
                    }
                    // «Трекеры»: рамка цвета акцента, когда RuTracker с ПК готов
                    Btn {
                        id: trackersBtn
                        height: 44
                        hoverUp: 1.02
                        width: trRow.width + 24
                        Rectangle {
                            anchors.fill: parent
                            radius: 12
                            color: trackersBtn.hovered ? "#282E3C" : "#1B1F2A"
                            border.color: s.localActive ? Qt.rgba(37 / 255, 99 / 255, 235 / 255, 0.7) : trackersBtn.hovered ? Theme.line2 : Theme.line
                            Behavior on color { ColorAnimation { duration: 200 } }
                        }
                        Row {
                            id: trRow
                            anchors.centerIn: parent
                            spacing: 6
                            Icon { anchors.verticalCenter: parent.verticalCenter; name: "gear"; size: 15; color: s.localActive ? Theme.accent : Theme.text }
                            Text {
                                anchors.verticalCenter: parent.verticalCenter
                                text: Ui.tx("search.trackers_button") + (s.localActive ? Ui.tx("search.trackers_pc_badge") : "")
                                color: s.localActive ? Theme.accent : Theme.text
                                font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 13
                            }
                        }
                        onClicked: trackers.show()
                    }
                }

                // ответы трекеров
                Flow {
                    visible: !s.aiMode && (s.providers.length > 0 || s.ruSearching)
                    width: parent.width
                    topPadding: 12
                    spacing: 6
                    Pill { visible: s.ruSearching; text: Ui.tx("search.rutracker_searching"); fg: Theme.muted; bg: "#161A24"; edge: Theme.line }
                    Repeater {
                        model: s.providers
                        delegate: Pill {
                            text: modelData.text
                            fg: modelData.ok ? Theme.ok : Theme.bad
                            bg: modelData.ok ? "#0F1F19" : "#241211"
                            edge: modelData.ok ? "#27533F" : "#6A3330"
                        }
                    }
                }
                Text {
                    visible: !s.aiMode && s.status !== ""
                    width: parent.width
                    topPadding: 10
                    text: s.status
                    color: s.statusOk ? Theme.ok : Theme.bad
                    wrapMode: Text.WordWrap
                    font.family: Theme.onest; font.pixelSize: 14
                }

                // строка 1: что искать, только с сидами, только RU, уточнение
                Item { visible: !s.aiMode && s.hitCount > 0; width: 1; height: 12 }
                Item {
                    id: row1
                    visible: !s.aiMode && s.hitCount > 0
                    width: parent.width
                    // поле «Уточнить» — справа от переключателей, а если там тесно (меньше 220) — строкой ниже
                    readonly property bool wrap: chipsRow.width + 8 + 220 > width
                    height: wrap ? chipsRow.height + 8 + refineField.height : Math.max(chipsRow.height, refineField.height)
                    Row {
                        id: chipsRow
                        y: row1.wrap ? 0 : (row1.height - height) / 2
                        spacing: 8
                        Repeater {
                            model: [["video", "search.filmy_i_serialy"], ["movie", "search.tolko_filmy"], ["cartoon", "search.tolko_multfilmy"],
                                    ["series", "search.tolko_serialy"], ["all", "search.vse"]]
                            delegate: Chip { text: Ui.tx(modelData[1]); on: s.kind === modelData[0]; onClicked: s.kind = modelData[0] }
                        }
                        Chip { text: Ui.tx("search.tolko_s_sidami"); on: s.onlySeeded; onClicked: s.onlySeeded = !s.onlySeeded }
                        Chip { text: Ui.tx("search.only_ru"); on: s.onlyRu; onClicked: s.onlyRu = !s.onlyRu }
                    }
                    Field {
                        id: refineField
                        x: row1.wrap ? 0 : chipsRow.width + 8
                        y: row1.wrap ? chipsRow.height + 8 : 0
                        width: row1.wrap ? row1.width : row1.width - x
                        placeholder: Ui.tx("search.utochnit_p_p_rus")
                        input.onTextEdited: s.refine = text
                    }
                }

                // строка 2: набор, качество, сортировка (не помещается — сортировка ниже)
                Item { visible: !s.aiMode && s.hitCount > 0; width: 1; height: 10 }
                Item {
                    id: row2
                    visible: !s.aiMode && s.hitCount > 0
                    width: parent.width
                    readonly property bool wrap: presetRow.width + sortRow.width + 16 > width
                    height: wrap ? presetRow.height + 8 + sortRow.height : Math.max(presetRow.height, sortRow.height)
                    Row {
                        id: presetRow
                        spacing: 8
                        Label { text: Ui.tx("search.preset") }
                        Repeater {
                            model: s.presets
                            delegate: Chip { anchors.verticalCenter: parent.verticalCenter; text: modelData.title; on: s.preset === modelData.value; onClicked: s.preset = modelData.value }
                        }
                        Item { width: 8; height: 1 }
                        Label { text: Ui.tx("search.quality") }
                        Chip { anchors.verticalCenter: parent.verticalCenter; text: Ui.tx("search.any_quality"); on: s.resolution === -1; onClicked: s.resolution = -1 }
                        // Resolution: UHD_4K = 0, FHD_1080P = 1, HD_720P = 2
                        Repeater {
                            model: [[0, "4K"], [1, "1080p"], [2, "720p"]]
                            delegate: Chip {
                                anchors.verticalCenter: parent.verticalCenter
                                text: modelData[1]
                                on: s.resolution === modelData[0]
                                onClicked: s.resolution = s.resolution === modelData[0] ? -1 : modelData[0]
                            }
                        }
                    }
                    Row {
                        id: sortRow
                        anchors.right: parent.right
                        y: row2.wrap ? presetRow.height + 8 : 0
                        spacing: 8
                        Label { text: Ui.tx("search.sort") }
                        // SearchSort: DEFAULT = 0, SIZE_ASC = 1, SEEDS_DESC = 3
                        Repeater {
                            model: [[0, "search.sort_default"], [1, "search.sort_size_asc"], [3, "search.sort_seeds_desc"]]
                            delegate: Chip { anchors.verticalCenter: parent.verticalCenter; text: Ui.tx(modelData[1]); on: s.sort === modelData[0]; onClicked: s.sort = modelData[0] }
                        }
                    }
                }
                Text {
                    visible: !s.aiMode && s.hitCount !== s.shownCount
                    topPadding: 8
                    text: Ui.tx("search.skryto_filtrami_p", { p: s.hitCount - s.shownCount })
                    color: Theme.muted
                    font.family: Theme.onest; font.pixelSize: 13
                }
            }
            Item { width: 1; height: 10 }

            Pagination { visible: !s.aiMode && s.shownCount > 0 && s.totalPages > 1; width: parent.width }
            Item { visible: !s.aiMode && s.shownCount > 0 && s.totalPages > 1; width: 1; height: 10 }

            Repeater {
                model: s.aiMode ? [] : s.rows
                delegate: HitRow { width: content.width; hit: modelData }
            }

            Item { visible: !s.aiMode && s.shownCount > 0 && s.totalPages > 1; width: 1; height: 14 }
            Pagination { visible: !s.aiMode && s.shownCount > 0 && s.totalPages > 1; width: parent.width }

            // ---- подборка ИИ: сетка карточек (пока ИИ думает — мерцающие заготовки) ----
            Item { visible: s.aiMode; width: 1; height: 8 }
            Item {
                id: aiGrid
                visible: s.aiMode
                width: parent.width
                readonly property int gap: 14
                readonly property int cols: Math.max(1, Math.floor((width + gap) / (196 + gap)))
                readonly property int cardW: Math.floor((width - (cols - 1) * gap) / cols)
                readonly property int cardH: Math.round(cardW * 1.5) + 202
                readonly property int count: s.aiBusy ? 6 : s.aiHits.length
                height: count ? Math.ceil(count / cols) * (cardH + gap) - gap : 0
                Repeater {
                    model: s.aiMode && s.aiBusy ? 6 : 0
                    delegate: Shimmer {
                        x: (index % aiGrid.cols) * (aiGrid.cardW + aiGrid.gap)
                        y: Math.floor(index / aiGrid.cols) * (aiGrid.cardH + aiGrid.gap)
                        width: aiGrid.cardW; height: aiGrid.cardH
                        posterH: Math.round(aiGrid.cardW * 1.5)
                    }
                }
                Repeater {
                    model: s.aiMode && !s.aiBusy ? s.aiHits : []
                    delegate: AiDiscoveryCard {
                        required property var modelData
                        required property int index
                        movie: modelData
                        x: (index % aiGrid.cols) * (aiGrid.cardW + aiGrid.gap)
                        y: Math.floor(index / aiGrid.cols) * (aiGrid.cardH + aiGrid.gap)
                        width: aiGrid.cardW; height: aiGrid.cardH
                        // плавное появление по очереди
                        opacity: 0
                        Component.onCompleted: appear.start()
                        NumberAnimation on opacity { id: appear; running: false; from: 0; to: 1; duration: Theme.simple ? 0 : 320 + Math.min(index, 8) * 45; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease }
                        onPlayRequested: (id, path) => v.playRequested(id, path)
                        onSearchTrackers: (q) => v.findOnTrackers(q)
                    }
                }
            }
            Item { visible: s.aiMode && !s.aiBusy && s.aiHits.length > 0; width: 1; height: 18 }
            OutlineButton {
                visible: s.aiMode && !s.aiBusy && s.aiHits.length > 0
                anchors.horizontalCenter: parent.horizontalCenter
                text: Ui.tx("discover.more")
                onClicked: s.runAi(s.aiQuery, true)
            }
        }
    }

    TrackerSettingsDialog { id: trackers; onClosed: s.trackersChanged() }

    // ---- части ----

    /// «По названию | По настроению»: стеклянный переключатель, активная половина — светлее, у ИИ — янтарная искра.
    component ModeSwitch: Rectangle {
        id: ms
        width: msRow.width + 8; height: 40
        radius: 13
        color: "#12151D"
        border.color: Theme.line
        Rectangle {
            id: knob
            y: 4; height: parent.height - 8
            x: s.aiMode ? aiSeg.x + 4 : trSeg.x + 4
            width: s.aiMode ? aiSeg.width : trSeg.width
            radius: 10
            color: "#282E3C"
            border.color: s.aiMode ? Qt.rgba(1, 176 / 255, 46 / 255, 0.45) : "#3A4356"
            Behavior on x { enabled: !Theme.simple; NumberAnimation { duration: 260; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
            Behavior on width { enabled: !Theme.simple; NumberAnimation { duration: 260; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
            Behavior on border.color { ColorAnimation { duration: 260 } }
        }
        Row {
            id: msRow
            x: 4; anchors.verticalCenter: parent.verticalCenter
            Segment { id: trSeg; icon: "search"; text: Ui.tx("discover.mode_tracker"); on: !s.aiMode; onClicked: s.aiMode = false }
            Segment { id: aiSeg; icon: "sparkle"; text: Ui.tx("discover.mode_ai"); on: s.aiMode; lampIcon: true; onClicked: v.openAi("") }
        }
    }

    component Segment: Btn {
        id: seg
        property string text
        property string icon
        property bool on: false
        property bool lampIcon: false
        hoverUp: 1
        width: segRow.width + 26; height: 32
        Row {
            id: segRow
            anchors.centerIn: parent
            spacing: 7
            Icon { anchors.verticalCenter: parent.verticalCenter; name: seg.icon; size: 14; color: seg.on && seg.lampIcon ? Theme.lamp : seg.on || seg.hovered ? "white" : Theme.muted }
            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: seg.text
                color: seg.on || seg.hovered ? "white" : Theme.muted
                font.family: Theme.onest; font.weight: seg.on ? Font.DemiBold : Font.Medium; font.pixelSize: 13
                Behavior on color { ColorAnimation { duration: 200 } }
            }
        }
    }

    /// Быстрое настроение: белый значок и подпись (как Chip, только со значком).
    component VibeChip: Btn {
        id: vc
        property string text
        property string icon
        hoverUp: 1.03
        opacity: enabled_ ? 1 : 0.5
        width: vcRow.width + 26; height: 34
        Rectangle {
            anchors.fill: parent
            radius: 17
            color: vc.hovered && vc.enabled_ ? "#1F2432" : "#141720"
            border.color: vc.hovered && vc.enabled_ ? "#3E475C" : "#242A38"
            Behavior on color { ColorAnimation { duration: 200 } }
            Behavior on border.color { ColorAnimation { duration: 200 } }
        }
        Row {
            id: vcRow
            anchors.centerIn: parent
            spacing: 7
            Icon { anchors.verticalCenter: parent.verticalCenter; name: vc.icon; size: 14; color: "white" }
            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: vc.text
                color: vc.hovered ? "white" : "#D6DBE4"
                font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 13
            }
        }
    }

    /// Заготовка карточки, пока ИИ подбирает: те же размеры, по постеру и строкам бежит блик.
    component Shimmer: Rectangle {
        id: sh
        property int posterH: 300
        radius: 16
        color: Theme.film
        border.color: Theme.line
        clip: true
        Rectangle { x: 1; y: 1; width: parent.width - 2; height: sh.posterH; radius: 15; color: "#141822" }
        Column {
            x: 12; y: sh.posterH + 14
            spacing: 9
            Rectangle { width: sh.width * 0.7; height: 12; radius: 6; color: "#20242F" }
            Rectangle { width: sh.width * 0.45; height: 10; radius: 5; color: "#1C202A" }
            Rectangle { width: sh.width - 24; height: 9; radius: 4.5; color: "#2A2416" }
            Rectangle { width: sh.width * 0.6; height: 9; radius: 4.5; color: "#2A2416" }
        }
        Rectangle { x: 12; width: parent.width - 24; y: parent.height - 48; height: 36; radius: 10; color: "#1B1F2A" }
        Rectangle {
            id: glare
            visible: !Theme.simple
            width: sh.width * 0.6; height: sh.height * 1.4
            y: -sh.height * 0.2
            rotation: 18
            gradient: Gradient {
                orientation: Gradient.Horizontal
                GradientStop { position: 0; color: "transparent" }
                GradientStop { position: 0.5; color: "#12FFFFFF" }
                GradientStop { position: 1; color: "transparent" }
            }
            NumberAnimation on x { running: glare.visible; loops: Animation.Infinite; from: -sh.width; to: sh.width * 1.4; duration: 1400; easing.type: Easing.InOutQuad }
        }
    }

    component Label: Text {
        anchors.verticalCenter: parent.verticalCenter
        color: Theme.muted
        font.family: Theme.onest; font.pixelSize: 13
    }

    /// Ответ трекера: «✓ Rutor · 12» или «✕ TPB: ошибка».
    component Pill: Rectangle {
        property alias text: pillText.text
        property color fg
        property color bg
        property color edge
        width: pillText.implicitWidth + 20; height: pillText.implicitHeight + 8
        radius: height / 2
        color: bg
        border.color: edge
        Text { id: pillText; anchors.centerIn: parent; color: parent.fg; font.family: Theme.onest; font.pixelSize: 12 }
    }

    /// Значок раздачи (качество, кодек, HDR, рип, озвучка, битрейт с точкой).
    component Badge: Rectangle {
        property var b
        width: badgeRow.width + 14; height: badgeRow.height + 5
        radius: 6
        color: b.bg
        border.color: b.edge
        Row {
            id: badgeRow
            anchors.centerIn: parent
            spacing: 4
            Rectangle { visible: b.dot !== ""; anchors.verticalCenter: parent.verticalCenter; width: 6; height: 6; radius: 3; color: b.dot || "transparent" }
            Text { text: b.text; color: b.fg; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 12 }
        }
    }

    component Info: Text {
        color: Theme.muted
        font.family: Theme.onest; font.pixelSize: 13
    }

    /// Раздача: чистое название, исходное, значки, размер, сиды, источник, дата; справа «＋ В библиотеку».
    component HitRow: Item {
        id: hr
        property var hit
        height: box.height + 4
        HoverHandler { id: hov }
        Rectangle {
            id: box
            y: 2
            width: parent.width
            height: Math.max(col.implicitHeight, addBtn.height) + 28
            radius: 12
            color: hov.hovered ? "#171B25" : Theme.booth
            border.color: hov.hovered ? "#3E475C" : "transparent"
            Behavior on color { ColorAnimation { duration: 200 } }
            Behavior on border.color { ColorAnimation { duration: 200 } }
            Rectangle {
                visible: hr.hit.ru
                x: 14; anchors.verticalCenter: parent.verticalCenter
                width: 3; height: 50; radius: 3
                color: "#3E475C"
            }
            Column {
                id: col
                x: hr.hit.ru ? 29 : 14
                anchors.verticalCenter: parent.verticalCenter
                width: parent.width - x - addBtn.width - 26
                Text {
                    width: parent.width
                    text: hr.hit.title
                    color: Theme.text
                    wrapMode: Text.Wrap; maximumLineCount: 2; elide: Text.ElideRight
                    font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 15
                }
                Text {
                    visible: hr.hit.raw !== ""
                    width: parent.width
                    topPadding: 1
                    text: hr.hit.raw
                    color: Theme.muted; elide: Text.ElideRight
                    font.family: Theme.onest; font.pixelSize: 12
                }
                Flow {
                    visible: hr.hit.badges.length > 0
                    width: parent.width
                    topPadding: 4; bottomPadding: 2
                    spacing: 6
                    Repeater { model: hr.hit.badges; delegate: Badge { b: modelData } }
                }
                Flow {
                    width: parent.width
                    topPadding: 4
                    spacing: 12
                    Text { visible: hr.hit.size !== ""; text: hr.hit.size; color: Theme.text; font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 13 }
                    Text { text: "↑ " + hr.hit.seeders; color: hr.hit.seeders > 0 ? Theme.ok : Theme.muted; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 13 }
                    Info { text: "↓ " + hr.hit.leechers }
                    Rectangle {
                        visible: hr.hit.ru
                        width: ruText.implicitWidth + 12; height: ruText.implicitHeight + 2
                        radius: 6
                        color: "#282E3C"; border.color: "#3A4356"
                        Text { id: ruText; anchors.centerIn: parent; text: "RU"; color: "white"; font.family: Theme.onest; font.weight: Font.Bold; font.pixelSize: 11 }
                    }
                    Info { visible: hr.hit.kind !== ""; text: hr.hit.kind }
                    Info { text: hr.hit.source }
                    Info { visible: hr.hit.date !== ""; text: hr.hit.date }
                }
                Text {
                    visible: hr.hit.note !== ""
                    width: parent.width
                    topPadding: 4
                    text: hr.hit.note
                    color: hr.hit.noteOk ? Theme.ok : Theme.bad
                    wrapMode: Text.WordWrap
                    font.family: Theme.onest; font.pixelSize: 13
                }
            }
            LampButton {
                id: addBtn
                anchors { right: parent.right; rightMargin: 14; verticalCenter: parent.verticalCenter }
                text: Ui.tx("search.v_biblioteku")
                enabled_: hr.hit.canAdd
                onClicked: s.add(hr.hit.id)
            }
        }
    }

    /// Страницы (ui/Pagination.kt): «1–25 из 80», размер страницы, ‹ 1 … 4 5 6 … 10 ›.
    component Pagination: Rectangle {
        height: Math.max(leftRow.height, pageRow.height) + 20
        radius: 14
        color: "#141720"
        border.color: "#242A38"
        Row {
            id: leftRow
            x: 16; anchors.verticalCenter: parent.verticalCenter
            spacing: 10
            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: Ui.tx("pagination.range", { from: s.fromIndex + 1, to: s.toIndex, total: s.shownCount })
                color: Theme.muted
                font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 13
            }
            Text { visible: s.shownCount > 25; anchors.verticalCenter: parent.verticalCenter; text: "·"; color: Theme.line2; font.family: Theme.onest; font.pixelSize: 13 }
            Text { visible: s.shownCount > 25; anchors.verticalCenter: parent.verticalCenter; text: Ui.tx("pagination.page_size"); color: Theme.muted; font.family: Theme.onest; font.pixelSize: 13 }
            Repeater {
                model: s.shownCount > 25 ? [25, 50] : []
                delegate: Rectangle {
                    anchors.verticalCenter: parent.verticalCenter
                    readonly property bool cur: s.pageSize === modelData
                    width: sizeText.implicitWidth + 16; height: sizeText.implicitHeight + 4
                    radius: 6
                    color: cur ? "#282E3C" : "transparent"
                    Text {
                        id: sizeText
                        anchors.centerIn: parent
                        text: modelData
                        color: parent.cur ? Theme.accent : Theme.muted
                        font.family: Theme.onest; font.weight: parent.cur ? Font.Bold : Font.Medium; font.pixelSize: 13
                    }
                    MouseArea {
                        anchors.fill: parent
                        enabled: !parent.cur
                        cursorShape: Qt.PointingHandCursor
                        onClicked: { s.pageSize = modelData; v.toTop() }
                    }
                }
            }
        }
        Row {
            id: pageRow
            anchors { right: parent.right; rightMargin: 16; verticalCenter: parent.verticalCenter }
            spacing: 6
            PageButton { label: Ui.tx("pagination.prev"); enabled_: s.page > 1; onClicked: { s.page = s.page - 1; v.toTop() } }
            Repeater {
                model: s.pageRange
                delegate: Item {
                    anchors.verticalCenter: parent.verticalCenter
                    width: modelData === -1 ? dots.implicitWidth + 6 : pb.width
                    height: pb.height
                    Text { id: dots; visible: modelData === -1; anchors.centerIn: parent; text: "…"; color: Theme.muted; font.family: Theme.onest; font.pixelSize: 13 }
                    PageButton {
                        id: pb
                        visible: modelData !== -1
                        label: String(modelData)
                        selected: modelData === s.page
                        onClicked: { s.page = modelData; v.toTop() }
                    }
                }
            }
            PageButton { label: Ui.tx("pagination.next"); enabled_: s.page < s.totalPages; onClicked: { s.page = s.page + 1; v.toTop() } }
        }
    }

    component PageButton: Btn {
        id: pbtn
        property string label
        property bool selected: false
        anchors.verticalCenter: parent ? parent.verticalCenter : undefined
        width: pText.implicitWidth + 24
        height: Math.max(34, pText.implicitHeight + 12)
        Rectangle {
            anchors.fill: parent
            radius: 8
            color: pbtn.selected ? "#282E3C" : !pbtn.enabled_ ? "transparent" : pbtn.hovered ? "#1F2432" : "#161A24"
            border.color: pbtn.selected ? Qt.rgba(37 / 255, 99 / 255, 235 / 255, 0.85) : !pbtn.enabled_ ? "transparent" : pbtn.hovered ? Theme.line2 : Theme.line
            Behavior on color { ColorAnimation { duration: 180 } }
            Behavior on border.color { ColorAnimation { duration: 180 } }
        }
        Text {
            id: pText
            anchors.centerIn: parent
            text: pbtn.label
            color: pbtn.selected ? Theme.accent : !pbtn.enabled_ ? "#4A5265" : pbtn.hovered ? "white" : "#C0C7D5"
            font.family: Theme.onest; font.weight: pbtn.selected ? Font.Bold : Font.Medium; font.pixelSize: 13
        }
    }
}
