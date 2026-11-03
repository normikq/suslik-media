import QtQuick

// «Совместный просмотр» (party/TogetherView.kt): создать комнату, войти по коду, открыть фильм комнаты.
// Гость ничего не выбирает: фильм ведущего открывается сам (PartyFollow), «Смотреть вместе» — открыть его снова.
Item {
    id: v
    signal library()                          // к медиатеке (ведущий выбирает фильм)
    signal playRequested(var magnetId, string path)

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

            Text {
                text: Ui.tx("party.sovmestnyy_prosmotr")
                color: Theme.text
                font.family: Theme.unbounded; font.weight: Font.Medium; font.pixelSize: 22
            }
            Text {
                width: parent.width
                topPadding: 4; bottomPadding: 16
                text: Ui.tx("party.odin_film_odna_pauza")
                color: Theme.muted; wrapMode: Text.WordWrap
                font.family: Theme.onest; font.pixelSize: 14
            }

            // ---- не в комнате: создать или войти ----
            Column {
                visible: !party.inRoom
                width: parent.width
                Text {
                    visible: party.notice !== ""
                    width: parent.width
                    bottomPadding: 12
                    text: party.notice
                    color: "#F2B84B"; wrapMode: Text.WordWrap
                    font.family: Theme.onest; font.pixelSize: 14
                }
                Row {
                    width: parent.width
                    spacing: 14
                    Panel {
                        id: createPanel
                        width: (parent.width - 14) / 2
                        height: Math.max(implicitHeight, joinPanel.implicitHeight)
                        Heading { text: Ui.tx("party.novaya_komnata") }
                        Hint { text: Ui.tx("party.sozdayte_komnatu") }
                        LampButton {
                            width: parent.width; height: 44
                            text: party.busy ? Ui.tx("party.sozdaem") : Ui.tx("party.sozdat_komnatu")
                            enabled_: !party.busy
                            onClicked: party.create()
                        }
                    }
                    Panel {
                        id: joinPanel
                        width: (parent.width - 14) / 2
                        height: Math.max(implicitHeight, createPanel.implicitHeight)
                        Heading { text: Ui.tx("party.voyti_po_kodu") }
                        Hint { text: Ui.tx("party.kod_prishlet") }
                        Field {
                            id: codeField
                            width: parent.width
                            placeholder: Ui.tx("party.kod_komnaty")
                            onAccepted: if (text.trim() !== "") party.join(text)
                        }
                        Item { width: 1; height: 10 }
                        OutlineButton {
                            width: parent.width; height: 44
                            text: party.busy ? Ui.tx("party.vhodim") : Ui.tx("party.voyti")
                            onClicked: if (codeField.text.trim() !== "") party.join(codeField.text)
                        }
                    }
                }
            }

            // ---- в комнате ----
            Column {
                visible: party.inRoom
                width: parent.width
                Panel {
                    width: parent.width
                    Item {
                        width: parent.width; height: Math.max(codeCol.height, roomBtns.height)
                        Column {
                            id: codeCol
                            anchors.verticalCenter: parent.verticalCenter
                            Text { text: Ui.tx("party.kod_komnaty"); color: Theme.muted; font.family: Theme.onest; font.pixelSize: 13 }
                            Text {
                                topPadding: 2
                                text: party.roomId
                                color: "white"
                                font.family: Theme.unbounded; font.weight: Font.Bold; font.pixelSize: 26; font.letterSpacing: 2
                            }
                        }
                        Row {
                            id: roomBtns
                            anchors { right: parent.right; verticalCenter: parent.verticalCenter }
                            spacing: 10
                            OutlineButton {
                                id: copyBtn
                                property bool copied: false
                                text: copied ? Ui.tx("party.skopirovano") : Ui.tx("party.skopirovat")
                                onClicked: { Ui.copy(party.roomId); copied = true; copiedOff.restart() }
                                Timer { id: copiedOff; interval: 1500; onTriggered: copyBtn.copied = false }
                            }
                            OutlineButton { text: Ui.tx("party.vyyti_iz_komnaty"); onClicked: party.leave() }
                        }
                    }
                    Text {
                        width: parent.width
                        topPadding: 8
                        text: (party.connected ? Ui.tx("party.na_svyazi") : Ui.tx("party.podklyuchaemsya")) +
                              (party.hostName.trim() !== "" ? Ui.tx("party.sozdal", { name: party.hostName }) : "") +
                              (party.hostOnly ? Ui.tx("party.upravlyaet_sozdatel") : "")
                        color: party.connected ? Theme.muted : "#F2B84B"
                        wrapMode: Text.WordWrap
                        font.family: Theme.onest; font.pixelSize: 13
                    }
                }
                Item { width: 1; height: 14 }
                Row {
                    width: parent.width
                    spacing: 14
                    // фильм комнаты
                    Panel {
                        id: filmPanel
                        width: (parent.width - 14) * 2 / 3
                        height: Math.max(implicitHeight, membersPanel.implicitHeight)
                        readonly property string key: party.fileKey
                        readonly property var magnetId: key === "" ? 0 : Number(key.split("|")[0])
                        readonly property string path: key === "" ? "" : key.substring(key.indexOf("|") + 1)
                        readonly property bool known: { lib.version; return key !== "" && lib.hasFile(magnetId, path) }
                        readonly property int magnetCode: { lib.version; return key !== "" ? lib.magnetStatusCode(magnetId) : -1 }
                        readonly property bool failed: magnetCode >= 5
                        property int pollCount: 0
                        onKeyChanged: { pollCount = 0; if (key !== "" && !known) lib.refresh() }
                        Timer {
                            interval: 4000
                            repeat: true
                            running: party.inRoom && filmPanel.key !== "" && !filmPanel.known && !filmPanel.failed && filmPanel.pollCount < 15
                            onTriggered: {
                                filmPanel.pollCount++
                                lib.refresh()
                            }
                        }
                        Heading { text: Ui.tx("party.film") }
                        // нет фильма
                        Hint {
                            visible: filmPanel.key === ""
                            topPadding: 6; bottomPadding: 12
                            text: party.isHost ? Ui.tx("party.ne_vybran_host")
                                               : Ui.tx("party.ne_vybran_gost", { who: party.hostName.trim() !== "" ? party.hostName : Ui.tx("party.sozdatel_komnaty_mal") })
                        }
                        LampButton { visible: filmPanel.key === "" && party.isHost; text: Ui.tx("party.k_mediateke"); onClicked: v.library() }
                        // фильм есть
                        Text {
                            visible: filmPanel.key !== ""
                            width: parent.width
                            topPadding: 6
                            text: party.fileTitle.trim() !== "" ? party.fileTitle : filmPanel.path.split("/").pop()
                            color: Theme.text
                            wrapMode: Text.Wrap; maximumLineCount: 2; elide: Text.ElideRight
                            font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 15
                        }
                        Text {
                            id: stateText
                            visible: filmPanel.key !== ""
                            width: parent.width
                            topPadding: 2; bottomPadding: 12
                            // время комнаты идёт, пока она играет: раз в секунду
                            property int tick: 0
                            Timer { interval: 1000; repeat: true; running: party.inRoom && filmPanel.key !== ""; onTriggered: stateText.tick++ }
                            text: { stateText.tick; party.playing; party.waiting; return party.stateLine() }
                            color: party.playing && party.waiting ? "#F2B84B" : Theme.muted
                            font.family: Theme.onest; font.pixelSize: 13
                        }
                        Text {
                            visible: filmPanel.key !== "" && !filmPanel.known
                            width: parent.width
                            bottomPadding: 10
                            text: filmPanel.failed
                                  ? (lib.magnetStatus(filmPanel.magnetId) !== ""
                                     ? Ui.tx("add.oshibka_p", { p: lib.magnetStatus(filmPanel.magnetId) })
                                     : Ui.tx("search.oshibka"))
                                  : Ui.tx("party.net_v_mediateke")
                            color: Theme.bad; wrapMode: Text.WordWrap
                            font.family: Theme.onest; font.pixelSize: 13
                        }
                        // «Другой фильм» — и когда этого нет: иначе создателю пришлось бы пересоздавать комнату
                        Row {
                            visible: filmPanel.key !== ""
                            spacing: 10
                            LampButton {
                                visible: filmPanel.known
                                text: Ui.tx("party.smotret_vmeste")
                                onClicked: {
                                    if (party.isHost) v.playRequested(filmPanel.magnetId, filmPanel.path)
                                    else party.followed = "" // гость: фильм ведущего откроется сам
                                }
                            }
                            OutlineButton {
                                visible: !filmPanel.known
                                text: Ui.tx("library.obnovit")
                                onClicked: { filmPanel.pollCount = 0; lib.refresh() }
                            }
                            OutlineButton { visible: party.isHost; text: Ui.tx("party.drugoy_film"); onClicked: v.library() }
                        }
                    }
                    // кто в комнате
                    Panel {
                        id: membersPanel
                        width: (parent.width - 14) / 3
                        height: Math.max(implicitHeight, filmPanel.implicitHeight)
                        Heading { text: Ui.tx("party.v_komnate", { n: party.memberCount }); bottomPadding: 8 }
                        Repeater {
                            model: party.members
                            delegate: Item {
                                width: membersPanel.width - 36; height: 34
                                Avatar { id: av; anchors.verticalCenter: parent.verticalCenter; url: modelData.avatar; name: modelData.name; size: 26 }
                                Text {
                                    anchors { left: av.right; leftMargin: 10; right: parent.right; verticalCenter: parent.verticalCenter }
                                    text: modelData.label
                                    horizontalAlignment: Text.AlignLeft // явно: строка с именем справа налево не уезжает вправо
                                    maximumLineCount: 1
                                    color: Theme.text; elide: Text.ElideRight
                                    font.family: Theme.onest; font.pixelSize: 14
                                }
                            }
                        }
                    }
                }
                Text {
                    width: parent.width
                    topPadding: 10
                    text: Ui.tx("party.podskazka_sinhron")
                    color: Theme.muted; wrapMode: Text.WordWrap
                    font.family: Theme.onest; font.pixelSize: 13
                }
            }

            Text {
                visible: party.error !== ""
                width: parent.width
                topPadding: 12
                text: party.error
                color: Theme.bad; wrapMode: Text.WordWrap
                font.family: Theme.onest; font.pixelSize: 14
            }
        }
    }

    component Heading: Text {
        color: Theme.text
        font.family: Theme.unbounded; font.weight: Font.Medium; font.pixelSize: 17
    }
    component Hint: Text {
        width: parent ? parent.width : 0
        topPadding: 4; bottomPadding: 14
        color: Theme.muted; wrapMode: Text.WordWrap
        font.family: Theme.onest; font.pixelSize: 14
    }
}
