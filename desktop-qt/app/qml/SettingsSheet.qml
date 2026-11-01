import QtQuick

// «Настройки» поверх медиатеки (Settings.kt): то же стекло и появление, что у «Что нового». Действуют со следующего фильма.
Sheet {
    id: s
    maxWidth: 620
    pad: 0
    onDismissed: open = false
    onOpenChanged: if (open) { prefs.resetState(); prefs.measureStorage(); betaPicked = prefs.beta; codeField.text = prefs.betaCode }

    property bool betaPicked: prefs.beta
    function scrollTo(part) { flick.contentY = Math.max(0, Math.min(flick.contentHeight - flick.height, flick.contentHeight * part)) }

    Item { width: 1; height: 20 }
    Column {
        x: 18; width: parent.width - 36
        Text {
            text: Ui.tx("settings.nastroyki")
            color: Theme.text
            font.family: Theme.unbounded; font.weight: Font.Medium; font.pixelSize: 21
        }
        Text {
            width: parent.width
            topPadding: 4; bottomPadding: 12
            text: Ui.tx("settings.primenyayutsya_pri_sleduyusche")
            color: Theme.muted; wrapMode: Text.WordWrap
            font.family: Theme.onest; font.pixelSize: 13
        }
    }
    Flickable {
        id: flick
        x: 18; width: parent.width - 36
        height: Math.min(form.height, 620, s.height - 48 - 20 - 16 - 70 - 60)
        contentHeight: form.height
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        WheelHandler {
            acceptedDevices: PointerDevice.Mouse | PointerDevice.TouchPad
            onWheel: (ev) => {
                const dy = ev.pixelDelta.y !== 0 ? ev.pixelDelta.y : ev.angleDelta.y / 120 * 90
                flick.contentY = Math.max(0, Math.min(flick.contentHeight - flick.height, flick.contentY - dy))
            }
        }
        Column {
            id: form
            width: flick.width - 6

            Section { title: Ui.tx("settings.zapas_filma_na_diske"); text: Ui.tx("settings.zapas_filma_opisanie"); gap: 0 }
            Repeater {
                model: prefs.burstPresets
                delegate: PresetRow { width: form.width; preset: modelData; on: prefs.burstMb === modelData.value; onClicked: prefs.burstMb = modelData.value }
            }
            Section { title: Ui.tx("settings.bufer_pleera"); text: Ui.tx("settings.skolko_sekund_video_pleer") }
            Repeater {
                model: prefs.cachePresets
                delegate: PresetRow { width: form.width; preset: modelData; on: prefs.cacheMs === modelData.value; onClicked: prefs.cacheMs = modelData.value }
            }

            Section { title: Ui.tx("settings.animatsii"); text: Ui.tx("settings.animatsii_tekst") }
            Pair {
                a: Ui.tx("settings.animatsii_polnye"); b: Ui.tx("settings.animatsii_prostye")
                first: !Theme.simple
                onPicked: (first) => Theme.setSimple(!first)
            }

            Section { title: Ui.tx("settings.dvizhok_pleera"); text: Ui.tx("settings.dvizhok_opisanie") }
            EngineOption {
                width: form.width
                title: Ui.tx("settings.dvizhok_mpv")
                subtitle: mpvInstall.path !== "" ? Ui.tx("settings.dvizhok_mpv_hint") : Ui.tx("settings.dvizhok_mpv_nedostupen")
            }
            Text {
                width: form.width
                topPadding: 6
                text: mpvInstall.path !== "" ? Ui.tx("settings.dvizhok_mpv_put", { path: mpvInstall.path }) : Ui.tx("settings.dvizhok_mpv_otsutstvuet")
                color: mpvInstall.path !== "" ? Theme.muted : Theme.bad
                wrapMode: Text.WrapAnywhere
                font.family: Theme.onest; font.pixelSize: 12
            }

            Section { title: Ui.tx("settings.discord_rpc"); text: Ui.tx("settings.discord_rpc_opisanie") }
            Pair { a: Ui.tx("settings.vklyucheno"); b: Ui.tx("settings.vyklyucheno"); first: prefs.discordOn; onPicked: (first) => prefs.discordOn = first }

            Section { title: Ui.tx("settings.ozvuchki_polzovateley"); text: Ui.tx("settings.ozvuchki_polzovateley_tekst") }
            Pair { a: Ui.tx("settings.pokazyvat"); b: Ui.tx("settings.skryt"); first: prefs.customAudioOn; onPicked: (first) => prefs.customAudioOn = first }

            Section { title: Ui.tx("settings.zastavki_i_titry"); text: Ui.tx("settings.zastavki_i_titry_tekst") }
            Label { text: Ui.tx("settings.propuskat_zastavki"); topPadding: 0 }
            Pair { a: Ui.tx("settings.avtomaticheski"); b: Ui.tx("settings.knopkoy"); first: prefs.autoSkipIntro; onPicked: (first) => prefs.autoSkipIntro = first }
            Label { text: Ui.tx("settings.sleduyuschaya_na_titrah") }
            Pair { a: Ui.tx("settings.predlagat"); b: Ui.tx("settings.posle_kontsa"); first: prefs.offerNextOnCredits; onPicked: (first) => prefs.offerNextOnCredits = first }
            Label { text: Ui.tx("settings.obratnyy_otschet") }
            Row {
                width: form.width
                spacing: 8
                Repeater {
                    model: prefs.countdownChoices
                    delegate: ChannelOption {
                        width: (form.width - 8 * (prefs.countdownChoices.length - 1)) / prefs.countdownChoices.length
                        title: modelData.title
                        on: prefs.nextCountdown === modelData.value
                        onClicked: prefs.nextCountdown = modelData.value
                    }
                }
            }
            Label { text: Ui.tx("settings.umnye_subtitry"); bottomPadding: 2 }
            Text {
                width: form.width
                bottomPadding: 6
                text: Ui.tx("settings.umnye_subtitry_tekst")
                color: Theme.muted; wrapMode: Text.WordWrap; lineHeight: 1.1
                font.family: Theme.onest; font.pixelSize: 13
            }
            Pair { a: Ui.tx("settings.vklyucheno"); b: Ui.tx("settings.vyklyucheno"); first: prefs.smartSubs; onPicked: (first) => prefs.smartSubs = first }

            Section { title: Ui.tx("settings.pamyat"); text: Ui.tx("settings.pamyat_opisanie") }
            Item {
                width: form.width
                height: Math.max(sizes.implicitHeight, clearBtn.height)
                Text {
                    id: sizes
                    anchors { left: parent.left; right: clearBtn.left; rightMargin: 10; verticalCenter: parent.verticalCenter }
                    text: prefs.storageText !== "" ? prefs.storageText : Ui.tx("settings.schitaem")
                    color: Theme.text; lineHeight: 1.3
                    font.family: Theme.onest; font.pixelSize: 14
                }
                OutlineButton {
                    id: clearBtn
                    anchors { right: parent.right; verticalCenter: parent.verticalCenter }
                    text: prefs.clearing ? Ui.tx("settings.ochistka") : Ui.tx("settings.ochistit_kesh")
                    onClicked: prefs.clearCache()
                }
            }
            Text {
                visible: prefs.storageState !== ""
                topPadding: 6
                text: prefs.storageState
                color: Theme.muted
                font.family: Theme.onest; font.pixelSize: 13
            }

            // отладочная сборка: вместо канала обновлений — где журнал
            Section {
                visible: Ui.diag
                title: Ui.tx("settings.otladochnaya_sborka")
                text: Ui.tx("settings.podrobnyy_zhurnal_absolutepath", { absolutePath: Ui.appDir + "/player.log" }) + Ui.tx("settings.problemy_eta_sborka_ne")
            }
            OutlineButton {
                visible: Ui.diag
                text: Ui.tx("settings.papka_zhurnala")
                onClicked: Ui.openUrl("file:///" + Ui.appDir)
            }

            // канал обновлений
            Column {
                visible: !Ui.diag
                width: form.width
                Section { title: Ui.tx("settings.kanal_obnovleniy"); text: Ui.tx("settings.kanal_obnovleniy_opisanie") }
                Pair {
                    a: Ui.tx("settings.reliz"); b: Ui.tx("settings.beta")
                    first: !s.betaPicked
                    onPicked: (first) => { if (first === !s.betaPicked) return; s.betaPicked = !first; prefs.pickChannel(!first) }
                }
                Item {
                    width: form.width
                    height: s.betaPicked ? 54 : 0
                    clip: true
                    Behavior on height { NumberAnimation { duration: 260; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
                    Row {
                        y: 10
                        width: parent.width
                        spacing: 10
                        Field {
                            id: codeField
                            width: parent.width - saveBtn.width - 10
                            password: true
                            placeholder: Ui.tx("settings.kod_testirovschika")
                            onAccepted: prefs.saveBetaCode(text)
                        }
                        OutlineButton { id: saveBtn; height: 44; text: Ui.tx("settings.sohranit"); onClicked: prefs.saveBetaCode(codeField.text) }
                    }
                }
                Item {
                    width: form.width
                    height: Math.max(stateText.implicitHeight, checkBtn.height) + 8
                    Text {
                        id: stateText
                        y: 8
                        anchors { left: parent.left; right: checkBtn.left; rightMargin: 10 }
                        text: prefs.channelState !== "" ? prefs.channelState : Ui.tx("settings.ustanovlena_versiya_current", { current: updater.current })
                        color: Theme.muted; wrapMode: Text.WordWrap
                        font.family: Theme.onest; font.pixelSize: 13
                    }
                    Btn {
                        id: checkBtn
                        y: 4
                        anchors.right: parent.right
                        width: checkText.implicitWidth + 16; height: 30
                        Rectangle { anchors.fill: parent; radius: 8; color: checkBtn.hovered ? "#14FFFFFF" : "transparent" }
                        Text { id: checkText; anchors.centerIn: parent; text: Ui.tx("settings.proverit"); color: "white"; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 13 }
                        onClicked: prefs.checkUpdates()
                    }
                }
            }
            Item { width: 1; height: 4 }
        }
    }
    Item {
        width: parent.width; height: 70
        LampButton {
            anchors { right: parent.right; rightMargin: 18; top: parent.top; topMargin: 14 }
            text: Ui.tx("settings.gotovo")
            onClicked: s.open = false
        }
    }

    // ---- части ----

    component Section: Column {
        property string title
        property string text
        property real gap: 18
        width: form.width
        topPadding: gap
        bottomPadding: 8
        Text {
            text: parent.title
            color: Theme.text
            font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 16
        }
        Text {
            width: parent.width
            topPadding: 3
            text: parent.text
            color: Theme.muted; wrapMode: Text.WordWrap; lineHeight: 1.1
            font.family: Theme.onest; font.pixelSize: 13
        }
    }

    component Label: Text {
        topPadding: 12; bottomPadding: 6
        color: Theme.text
        font.family: Theme.onest; font.pixelSize: 14
    }

    /// Выбор из двух: слева first, справа — нет.
    component Pair: Row {
        id: pair
        property string a
        property string b
        property bool first
        signal picked(bool first)
        width: form.width
        spacing: 8
        ChannelOption { width: (pair.width - 8) / 2; title: pair.a; on: pair.first; onClicked: pair.picked(true) }
        ChannelOption { width: (pair.width - 8) / 2; title: pair.b; on: !pair.first; onClicked: pair.picked(false) }
    }

    /// Выбранный вариант — синяя рамка, мягкая подсветка и лёгкое свечение вокруг.
    component Choice: Btn {
        id: ch
        property bool on
        hoverUp: 1.01
        Rectangle {
            anchors.fill: parent
            anchors.margins: -3
            radius: 17
            color: "#0DFFFFFF"
            opacity: ch.on ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 220 } }
        }
        Rectangle {
            anchors.fill: parent
            radius: 14
            color: ch.on ? Theme.accentSoft : ch.hovered ? Theme.glassFillHover : Theme.glassFill
            border.color: ch.on ? Theme.accent : Theme.glassEdge
            Behavior on color { ColorAnimation { duration: 220 } }
            Behavior on border.color { ColorAnimation { duration: 220 } }
        }
    }

    component ChannelOption: Choice {
        id: co
        property string title
        height: 44
        Text {
            anchors.centerIn: parent
            text: co.title
            color: co.on ? "white" : Theme.text
            font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 15
        }
    }

    component Radio: Rectangle {
        property bool on
        width: 18; height: 18; radius: 9
        color: "transparent"
        border.width: 2
        border.color: on ? "white" : Theme.line2
        Rectangle { anchors.centerIn: parent; width: 8; height: 8; radius: 4; color: "white"; visible: parent.on }
    }

    component PresetRow: Item {
        id: pr
        property var preset
        property bool on
        signal clicked()
        height: choice.height + 6
        Choice {
            id: choice
            y: 3
            width: parent.width
            height: presetText.implicitHeight + 20
            on: pr.on
            onClicked: pr.clicked()
            Radio { id: radio; x: 14; anchors.verticalCenter: parent.verticalCenter; on: pr.on }
            Column {
                id: presetText
                anchors { left: radio.right; leftMargin: 12; right: parent.right; rightMargin: 14; verticalCenter: parent.verticalCenter }
                Text { text: pr.preset.title; color: pr.on ? "white" : Theme.text; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 14 }
                Text { width: parent.width; text: pr.preset.hint; color: Theme.muted; wrapMode: Text.WordWrap; font.family: Theme.onest; font.pixelSize: 13 }
            }
        }
    }

    component EngineOption: Choice {
        id: eo
        property string title
        property string subtitle
        on: true
        height: engineText.implicitHeight + 24
        Radio { id: er; x: 14; anchors.verticalCenter: parent.verticalCenter; on: true }
        Column {
            id: engineText
            anchors { left: er.right; leftMargin: 10; right: parent.right; rightMargin: 14; verticalCenter: parent.verticalCenter }
            Text { text: eo.title; color: "white"; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 14 }
            Text { width: parent.width; topPadding: 2; text: eo.subtitle; color: Theme.muted; wrapMode: Text.WordWrap; font.family: Theme.onest; font.pixelSize: 12 }
        }
    }
}
