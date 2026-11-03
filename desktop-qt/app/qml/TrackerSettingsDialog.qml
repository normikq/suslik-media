import QtQuick
import Suslik.Native

// Окно «Трекеры» (trackers/TrackerSettingsDialog.kt): вкладка «RuTracker (ПК)» — поиск прямо с этого компьютера
// (адрес, вход через браузер, cookie, проверка) и вкладка выбора трекеров. Щелчок мимо, крестик или Esc — сохранить и закрыть.
Item {
    id: d
    property bool open: false
    property int tab: 0
    signal closed()
    anchors.fill: parent
    visible: a > 0.001
    z: 10
    property real a: open ? 1 : 0
    Behavior on a { NumberAnimation { duration: 260; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
    Keys.onEscapePressed: dismiss()

    function show() {
        m.scan()
        open = true
        forceActiveFocus()
    }
    function dismiss() {
        if (!open) return
        m.close()
        open = false
        closed()
    }

    TrackerSettingsModel { id: m }

    Rectangle {
        anchors.fill: parent
        color: Theme.scrim
        opacity: d.a
        MouseArea { anchors.fill: parent; hoverEnabled: true; acceptedButtons: Qt.AllButtons; onClicked: d.dismiss(); onWheel: (w) => w.accepted = true }
    }
    Rectangle {
        id: card
        anchors.centerIn: parent
        anchors.verticalCenterOffset: (1 - d.a) * 24
        width: Math.min(680, d.width * 0.92)
        height: Math.min(body.height + 48, d.height - 48)
        opacity: d.a
        scale: 0.97 + 0.03 * d.a
        radius: 20
        color: Theme.booth
        border.color: Theme.line
        clip: true
        MouseArea { anchors.fill: parent; acceptedButtons: Qt.AllButtons }
        Flickable {
            id: flick
            x: 24; y: 24
            width: parent.width - 48; height: parent.height - 48
            contentHeight: body.height
            boundsBehavior: Flickable.StopAtBounds
            clip: true
            WheelHandler {
                acceptedDevices: PointerDevice.Mouse | PointerDevice.TouchPad
                onWheel: (ev) => {
                    const dy = ev.pixelDelta.y !== 0 ? ev.pixelDelta.y : ev.angleDelta.y / 120 * 110
                    flick.contentY = Math.max(0, Math.min(flick.contentHeight - flick.height, flick.contentY - dy))
                }
            }
            Column {
                id: body
                width: flick.width

                // заголовок и крестик
                Item {
                    width: parent.width; height: head.height
                    Column {
                        id: head
                        width: parent.width - 40
                        Text { text: Ui.tx("trackers.title"); color: Theme.text; font.family: Theme.unbounded; font.weight: Font.DemiBold; font.pixelSize: 20 }
                        Text { width: parent.width; topPadding: 2; text: Ui.tx("trackers.subtitle"); color: Theme.muted; wrapMode: Text.WordWrap; font.family: Theme.onest; font.pixelSize: 14 }
                    }
                    Btn {
                        anchors { right: parent.right; verticalCenter: parent.verticalCenter }
                        width: 28; height: 28
                        Icon { anchors.centerIn: parent; name: "close"; size: 16; color: Theme.muted }
                        onClicked: d.dismiss()
                    }
                }
                Item { width: 1; height: 18 }

                Row {
                    spacing: 8
                    Chip { text: Ui.tx("search.rutracker_pc") + (m.rtEnabled && m.rtCookie.trim() !== "" ? " ✓" : ""); on: d.tab === 0; onClicked: d.tab = 0 }
                    Chip { text: Ui.tx("trackers.selection_title", { enabled: m.enabledCount, total: m.sourceCount }); on: d.tab === 1; onClicked: d.tab = 1 }
                }
                Item { width: 1; height: 18 }

                // ===== RuTracker (ПК) =====
                Column {
                    visible: d.tab === 0
                    width: parent.width
                    Row {
                        spacing: 10
                        Chip {
                            text: m.rtEnabled ? Ui.tx("trackers.direct_search_enabled") : Ui.tx("trackers.direct_search_disabled")
                            on: m.rtEnabled
                            onClicked: m.rtEnabled = !m.rtEnabled
                        }
                        Text {
                            visible: m.rtEnabled && m.rtCookie.trim() === ""
                            anchors.verticalCenter: parent.verticalCenter
                            text: Ui.tx("trackers.need_cookies")
                            color: Theme.lamp
                            font.family: Theme.onest; font.pixelSize: 13
                        }
                    }
                    Item { width: 1; height: 8 }
                    Chip {
                        text: m.rtParty ? Ui.tx("trackers.party_allowed") : Ui.tx("trackers.party_denied")
                        on: m.rtParty
                        onClicked: m.rtParty = !m.rtParty
                    }
                    Note { topPadding: 4; text: Ui.tx("trackers.party_hint"); font.pixelSize: 13 }
                    Item { width: 1; height: 14 }

                    Heading { text: Ui.tx("trackers.address_label") }
                    Item { width: 1; height: 4 }
                    Field {
                        id: urlField
                        width: parent.width
                        placeholder: Ui.tx("trackers.address_hint")
                        text: m.rtUrl
                        input.onTextEdited: m.rtUrl = text
                    }
                    Row {
                        topPadding: 6
                        spacing: 6
                        Chip { text: Ui.tx("trackers.address_main"); on: m.rtUrl.indexOf("rutracker.org") >= 0; onClicked: { m.rtUrl = "https://rutracker.org"; urlField.text = m.rtUrl } }
                        Chip { text: Ui.tx("trackers.address_mirror"); on: m.rtUrl.indexOf("rutracker.net") >= 0; onClicked: { m.rtUrl = "https://rutracker.net"; urlField.text = m.rtUrl } }
                    }
                    Item { width: 1; height: 16 }

                    Heading { text: Ui.tx("trackers.autodetect_title") }
                    Note { topPadding: 2; bottomPadding: 8; text: Ui.tx("trackers.autodetect_desc"); font.pixelSize: 13 }
                    Note { visible: m.browsers.length === 0; text: Ui.tx("trackers.scanning_browsers"); font.pixelSize: 13 }
                    Column {
                        visible: m.browsers.length > 0
                        width: parent.width
                        Flow {
                            width: parent.width
                            spacing: 6
                            Repeater {
                                model: m.browsers
                                delegate: Chip { text: modelData; on: m.selected === index; onClicked: m.selected = index }
                            }
                        }
                        Item { width: 1; height: 10 }
                        Row {
                            width: parent.width
                            spacing: 10
                            OutlineButton {
                                id: captureBtn
                                text: m.busy ? (m.selectedChromium ? Ui.tx("trackers.waiting_login", { browser: m.browserName }) : Ui.tx("trackers.reading"))
                                             : (m.selectedChromium ? Ui.tx("trackers.btn_login_via", { browser: m.browserName })
                                                                   : Ui.tx("trackers.btn_take_from", { browser: m.browserName }))
                                onClicked: m.capture()
                            }
                            Text {
                                visible: m.browserStatus !== ""
                                anchors.verticalCenter: parent.verticalCenter
                                width: parent.width - captureBtn.width - 10
                                text: m.browserStatus
                                color: m.browserStatusKind === 1 ? Theme.ok : m.browserStatusKind === 0 ? Theme.bad : Theme.lamp
                                wrapMode: Text.WordWrap
                                font.family: Theme.onest; font.pixelSize: 13
                            }
                        }
                        Note { visible: m.selectedChromium; topPadding: 6; text: Ui.tx("trackers.v20_hint", { browser: m.browserName }) }
                        Note { visible: m.selectedFirefox; topPadding: 6; text: Ui.tx("trackers.firefox_hint") }
                    }
                    Item { width: 1; height: 16 }

                    Item {
                        width: parent.width; height: cookieLabel.height
                        Heading { id: cookieLabel; text: Ui.tx("trackers.session_cookie_label") }
                        Btn {
                            anchors { right: parent.right; verticalCenter: parent.verticalCenter }
                            width: pasteText.implicitWidth + 8; height: pasteText.implicitHeight + 4
                            Text {
                                id: pasteText
                                anchors.centerIn: parent
                                text: Ui.tx("trackers.btn_paste")
                                color: Theme.accent
                                font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 13
                            }
                            onClicked: { m.paste(Ui.paste()); cookieField.text = m.rtCookie }
                        }
                    }
                    Item { width: 1; height: 4 }
                    Field {
                        id: cookieField
                        width: parent.width
                        placeholder: "bb_session=...; bb_data=...; cf_clearance=..."
                        text: m.rtCookie
                        input.onTextEdited: m.rtCookie = text
                        Connections { target: m; function onChanged() { if (!cookieField.input.activeFocus) cookieField.text = m.rtCookie } }
                    }
                    Item { width: 1; height: 12 }
                    Row {
                        width: parent.width
                        spacing: 10
                        OutlineButton {
                            id: testBtn
                            text: m.testing ? Ui.tx("trackers.checking_via", { browser: m.activeBrowserName }) : Ui.tx("trackers.btn_check_connection")
                            onClicked: m.test()
                        }
                        Text {
                            visible: m.testStatus !== ""
                            anchors.verticalCenter: parent.verticalCenter
                            width: parent.width - testBtn.width - 10
                            text: m.testStatus
                            color: m.testOk ? Theme.ok : Theme.bad
                            wrapMode: Text.WordWrap
                            font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 13
                        }
                    }
                }

                // ===== Выбор трекеров =====
                Column {
                    visible: d.tab === 1
                    width: parent.width
                    Text { text: Ui.tx("trackers.sources_title"); color: Theme.text; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 15 }
                    Note { topPadding: 2; bottomPadding: 12; text: Ui.tx("trackers.sources_desc"); font.pixelSize: 13 }
                    Row {
                        spacing: 8
                        bottomPadding: 14
                        Chip { text: Ui.tx("trackers.btn_enable_all"); on: m.enabledCount === m.sourceCount; onClicked: m.enableAll() }
                        Chip { text: Ui.tx("trackers.btn_only_ru"); on: false; onClicked: m.onlyRussian() }
                    }
                    Column {
                        width: parent.width
                        spacing: 8
                        Repeater {
                            model: m.sources
                            delegate: SourceRow { width: parent.width; src: modelData }
                        }
                    }
                }

                Item { width: 1; height: 24 }
                Item {
                    width: parent.width; height: saveBtn.height
                    LampButton { id: saveBtn; anchors.right: parent.right; text: Ui.tx("trackers.btn_save_close"); onClicked: d.dismiss() }
                }
            }
        }
    }

    component Heading: Text {
        color: Theme.text
        font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 13
    }
    component Note: Text {
        width: parent ? parent.width : 0
        color: Theme.muted
        wrapMode: Text.WordWrap
        font.family: Theme.onest; font.pixelSize: 12
    }

    /// Трекер: метка, название и описание, «Вкл/Выкл»; щелчок по строке переключает.
    component SourceRow: Rectangle {
        id: sr
        property var src
        height: Math.max(srCol.implicitHeight, toggle.height) + 22
        radius: 12
        color: src.on ? "#161A24" : "#11131A"
        border.color: src.on ? "#283144" : Theme.line
        MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: m.toggleSource(sr.src.id) }
        Rectangle {
            id: tag
            x: 14; anchors.verticalCenter: parent.verticalCenter
            width: tagText.implicitWidth + 14; height: tagText.implicitHeight + 6
            radius: 6
            color: sr.src.on ? Qt.rgba(37 / 255, 99 / 255, 235 / 255, 0.15) : "#1B1E28"
            Text { id: tagText; anchors.centerIn: parent; text: sr.src.tag; color: sr.src.on ? Theme.accent : Theme.muted; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 11 }
        }
        Column {
            id: srCol
            anchors { left: tag.right; leftMargin: 12; right: toggle.left; rightMargin: 10; verticalCenter: parent.verticalCenter }
            Text { text: sr.src.name; color: sr.src.on ? Theme.text : Theme.muted; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 14 }
            Text { width: parent.width; topPadding: 1; text: sr.src.desc; color: Theme.muted; wrapMode: Text.WordWrap; font.family: Theme.onest; font.pixelSize: 12 }
        }
        Chip {
            id: toggle
            anchors { right: parent.right; rightMargin: 14; verticalCenter: parent.verticalCenter }
            text: sr.src.on ? Ui.tx("trackers.status_on") : Ui.tx("trackers.status_off")
            on: sr.src.on
            onClicked: m.toggleSource(sr.src.id)
        }
    }
}
