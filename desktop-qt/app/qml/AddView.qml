import QtQuick
import QtQuick.Dialogs
import Suslik.Native

// «Добавить» (AddView.kt + AudioAttachView.kt): фильм — magnet-ссылки и .torrent; озвучка — раздачи с .mka к фильму в медиатеке.
Item {
    id: v
    property int mode: 0            // 0 — фильм, 1 — озвучка
    property bool active: false     // вкладка открыта; ушли — не привязанные озвучки удаляются
    signal previewRequested(string video, string audio, var offsetMs, string title)

    onActiveChanged: if (!active) audio.leave()

    AddModel { id: add; library: lib }
    readonly property alias audioModel: audio
    AudioAttachModel {
        id: audio
        library: lib
        onPreviewRequested: (video, a, offset, title) => v.previewRequested(video, a, offset, title)
    }

    /// Ссылки, пришедшие извне (браузер, другое приложение): сразу добавляются.
    function addLinks(text) { mode = 0; add.submit(text) }

    FileDialog {
        id: picker
        title: Ui.tx("add.vyberite_torrent")
        fileMode: FileDialog.OpenFiles
        nameFilters: [".torrent (*.torrent)"]
        property int target: 0
        onAccepted: {
            if (target === 0) add.addTorrents(selectedFiles)
            else audio.addTorrents(selectedFiles)
        }
    }
    function pick(target) { picker.target = target; picker.open() }

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
                Text {
                    text: Ui.tx("add.dobavit_razdachu")
                    color: Theme.text
                    font.family: Theme.unbounded; font.weight: Font.Medium; font.pixelSize: 22
                }
                Text {
                    width: parent.width
                    topPadding: 4; bottomPadding: 16
                    text: Ui.tx("add.magnet_ssylka_ili_fayl")
                    color: Theme.muted; wrapMode: Text.WordWrap
                    font.family: Theme.onest; font.pixelSize: 14
                }
                Text {
                    bottomPadding: 8
                    text: Ui.tx("add.vyberite_tip_vvoda")
                    color: Theme.muted
                    font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 13
                }
                Row {
                    width: parent.width
                    spacing: 12
                    ModeCard { width: (parent.width - 12) / 2; glyph: "▶"; title: Ui.tx("add.film"); hint: Ui.tx("add.film_podskazka"); selected: v.mode === 0; onClicked: { v.mode = 0; add.setStatus("", true) } }
                    ModeCard { width: (parent.width - 12) / 2; glyph: "♪"; title: Ui.tx("audio.ozvuchka"); hint: Ui.tx("audio.k_filmu_v_biblioteke"); selected: v.mode === 1; onClicked: { v.mode = 1; add.setStatus("", true) } }
                }
                Item { width: 1; height: 16 }

                // поле ввода скользит в сторону выбранной карточки, высота меняется плавно
                Item {
                    id: panes
                    width: parent.width
                    height: v.mode === 0 ? filmPane.height : audioPane.height
                    Behavior on height { enabled: !Theme.simple; NumberAnimation { duration: 320; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
                    FilmPane { id: filmPane; width: parent.width; shown: v.mode === 0; dir: -1 }
                    AudioPane { id: audioPane; width: parent.width; shown: v.mode === 1; dir: 1 }
                }

                Text {
                    visible: v.mode === 0 && add.status !== ""
                    width: parent.width
                    topPadding: 12
                    text: add.status
                    color: add.statusOk ? Theme.ok : Theme.bad
                    wrapMode: Text.WordWrap
                    font.family: Theme.onest; font.pixelSize: 14
                }
            }

            Text {
                visible: add.added.length > 0
                topPadding: 26; bottomPadding: 10
                text: Ui.tx("add.dobavleno_seychas")
                color: Theme.text
                font.family: Theme.unbounded; font.weight: Font.Medium; font.pixelSize: 16
            }
            Repeater {
                model: add.added
                delegate: AddedRow { width: content.width; entry: modelData }
            }
            Text {
                visible: add.added.length === 0
                width: Math.min(parent.width, 640)
                topPadding: 22
                text: Ui.tx("add.skachannoe_poyavitsya_vo_vklad")
                color: Theme.muted; wrapMode: Text.WordWrap
                font.family: Theme.onest; font.pixelSize: 14
            }
        }
    }

    // ---- части ----

    /// Карточка типа ввода, как на сайте: кружок со значком, название и подсказка; выбранная — темнее и светлее рамка.
    component ModeCard: Btn {
        id: mc
        property string glyph
        property string title
        property string hint
        property bool selected
        hoverUp: 1
        height: Math.max(78, cardCol.implicitHeight + 28)
        transform: Translate { y: mc.hovered && !mc.selected ? -2 : 0; Behavior on y { NumberAnimation { duration: 220; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } } }
        Rectangle {
            anchors.fill: parent
            radius: 14
            color: mc.selected ? "#1C202B" : mc.hovered ? "#181B24" : "#141720"
            border.color: mc.selected ? "#3E475C" : mc.hovered ? "#2C3344" : "#222735"
            Behavior on color { ColorAnimation { duration: 240 } }
            Behavior on border.color { ColorAnimation { duration: 240 } }
        }
        Rectangle {
            id: disc
            x: 14; anchors.verticalCenter: parent.verticalCenter
            width: 40; height: 40; radius: 12
            color: mc.selected ? "#282E3C" : "#1E222D"
            Behavior on color { ColorAnimation { duration: 240 } }
            Text {
                anchors.centerIn: parent
                text: mc.glyph
                color: mc.selected ? "white" : "#8E96A8"
                font.pixelSize: 18; font.bold: true
                Behavior on color { ColorAnimation { duration: 240 } }
            }
        }
        Column {
            id: cardCol
            anchors { left: disc.right; leftMargin: 12; right: parent.right; rightMargin: 14; verticalCenter: parent.verticalCenter }
            Text { width: parent.width; text: mc.title; color: "white"; elide: Text.ElideRight; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 15 }
            Text { width: parent.width; topPadding: 3; text: mc.hint; color: "#8E96A8"; elide: Text.ElideRight; font.family: Theme.onest; font.pixelSize: 13 }
        }
    }

    /// Панель, выезжающая со стороны своей карточки.
    component Slide: Item {
        id: sl
        property bool shown
        property int dir: 1
        visible: opacity > 0
        opacity: shown ? 1 : 0
        x: shown || Theme.simple ? 0 : dir * width / 10
        Behavior on opacity { NumberAnimation { duration: sl.shown ? 280 : 160 } }
        Behavior on x { NumberAnimation { duration: 380; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
    }

    /// Строка ввода: поле, «.torrent», главная кнопка; под ней «Вставить из буфера». Перетащенные .torrent — onFiles.
    component InputBlock: Column {
        id: ib
        property string hint
        property string action
        property alias text: field.text
        signal submit()
        signal pickFiles()
        width: parent ? parent.width : 0
        spacing: 10
        Text {
            width: parent.width
            text: ib.hint
            color: Theme.muted; wrapMode: Text.WordWrap
            font.family: Theme.onest; font.pixelSize: 13
        }
        Row {
            width: parent.width
            spacing: 10
            Field {
                id: field
                width: parent.width - torrentBtn.width - mainBtn.width - 20
                placeholder: Ui.tx("audio.magnet_ili_torrent")
                onAccepted: ib.submit()
            }
            OutlineButton { id: torrentBtn; height: 44; text: Ui.tx("audio.torrent"); onClicked: ib.pickFiles() }
            LampButton { id: mainBtn; height: 44; text: ib.action; enabled_: field.text.trim() !== ""; onClicked: ib.submit() }
        }
        Row {
            spacing: 12
            OutlineButton {
                text: Ui.tx("add.vstavit_iz_bufera")
                onClicked: {
                    const t = Ui.paste().trim()
                    if (t === "") ib.noClipboard()
                    else field.text = t
                }
            }
            Text {
                anchors.verticalCenter: parent.verticalCenter
                text: Ui.tx("add.mozhno_neskolko_ssylok_cherez")
                color: Theme.muted
                font.family: Theme.onest; font.pixelSize: 13
            }
        }
        signal noClipboard()
    }

    component FilmPane: Slide {
        height: filmCol.height
        DropArea {
            anchors.fill: parent
            keys: ["text/uri-list"]
            onDropped: (d) => { if (d.hasUrls) add.addTorrents(d.urls) }
        }
        InputBlock {
            id: filmCol
            hint: Ui.tx("add.film_tekst")
            action: Ui.tx("add.dobavit_magnet")
            onSubmit: { add.submit(text); text = "" }
            onPickFiles: v.pick(0)
            onNoClipboard: add.setStatus(Ui.tx("add.v_bufere_obmena_net"), false)
        }
    }

    component AudioPane: Slide {
        height: audioCol.height
        DropArea {
            anchors.fill: parent
            keys: ["text/uri-list"]
            onDropped: (d) => { if (d.hasUrls) audio.addTorrents(d.urls) }
        }
        Column {
            id: audioCol
            width: parent.width
            InputBlock {
                hint: Ui.tx("audio.podskazka")
                action: Ui.tx("audio.proverit")
                onSubmit: { audio.setNote(""); audio.addInput(text); if (audio.note === "") text = "" }
                onPickFiles: v.pick(1)
                onNoClipboard: audio.setNote(Ui.tx("add.v_bufere_obmena_net"))
            }
            Text {
                visible: audio.note !== ""
                width: parent.width
                topPadding: 8
                text: audio.note
                color: Theme.bad; wrapMode: Text.WordWrap
                font.family: Theme.onest; font.pixelSize: 13
            }
            Repeater {
                model: audio.sources
                delegate: SourceCard { width: audioCol.width; src: modelData }
            }
            Item {
                visible: audio.readyCount > 1
                width: parent.width; height: visible ? 60 : 0
                LampButton {
                    y: 16; width: parent.width; height: 44
                    text: Ui.tx("audio.privyazat_vse", { n: audio.readyCount })
                    onClicked: audio.attachAll()
                }
            }
        }
    }

    /// Одна раздача с озвучкой: проверка, выбор фильма, таблица пар с задержками, «Привязать».
    component SourceCard: Item {
        id: sc
        property var src
        readonly property var key: src.key
        // готовность зависит от введённых задержек: пересчёт по readyChanged, без пересборки таблицы
        property bool ready: { audio.readyCount; return audio.isReady(sc.key) }
        height: card.height + 14
        Rectangle {
            id: card
            y: 14
            width: parent.width
            height: cardCol.implicitHeight + 28
            radius: 14
            color: Theme.booth
            border.color: Theme.line
            Column {
                id: cardCol
                x: 14; y: 14
                width: parent.width - 28
                Item {
                    width: parent.width; height: 38
                    Text {
                        anchors { left: parent.left; right: btns.left; rightMargin: 8; verticalCenter: parent.verticalCenter }
                        text: (sc.src.torrent ? "📄 " : "🧲 ") + sc.src.name
                        color: Theme.text; elide: Text.ElideRight
                        font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 14
                    }
                    Row {
                        id: btns
                        anchors.right: parent.right
                        spacing: 8
                        OutlineButton { visible: sc.src.canCheck; text: Ui.tx("audio.proverit"); onClicked: audio.check(sc.key) }
                        OutlineButton { text: "×"; onClicked: audio.remove(sc.key) }
                    }
                }
                Text {
                    visible: sc.src.valid !== -1
                    topPadding: 10
                    text: sc.src.valid === 1 ? "[✓] " + Ui.tx("audio.fayly_validny") : "[✗] " + Ui.tx("audio.est_lishnie_fayly")
                    color: sc.src.valid === 1 ? Theme.ok : Theme.bad
                    font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 14
                }
                Text {
                    visible: sc.src.targetCount > 1
                    topPadding: 6
                    width: parent.width
                    text: "[✓] Привяжется ко всем раздачам фильма (" + sc.src.targetCount + ")" + (sc.src.targetNames ? ": " + sc.src.targetNames : "")
                    color: Theme.ok
                    wrapMode: Text.WordWrap
                    font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 13
                }
                Text {
                    visible: sc.src.note !== ""
                    width: parent.width
                    topPadding: 6
                    text: sc.src.note
                    color: sc.src.noteOk ? Theme.muted : Theme.bad
                    wrapMode: Text.WordWrap
                    font.family: Theme.onest; font.pixelSize: 13
                }
                Text {
                    visible: sc.src.candidates.length > 0
                    topPadding: 10; bottomPadding: 6
                    text: Ui.tx("audio.vyberite_film")
                    color: Theme.text
                    font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 13
                }
                Flow {
                    visible: sc.src.candidates.length > 0
                    width: parent.width
                    spacing: 8
                    Repeater {
                        model: sc.src.candidates
                        delegate: OutlineButton { text: modelData.name; onClicked: audio.chooseMovie(sc.key, modelData.id) }
                    }
                }

                // таблица пар
                Item { visible: table.visible; width: 1; height: 12 }
                Rectangle {
                    id: table
                    visible: sc.src.rows.length > 0 && !sc.src.done
                    width: parent.width
                    height: tableCol.height + 2
                    radius: 12
                    color: "transparent"
                    border.color: Theme.line2
                    clip: true
                    Column {
                        id: tableCol
                        x: 1; y: 1
                        width: parent.width - 2
                        Rectangle {
                            width: parent.width; height: 32
                            color: "#11141D"
                            radius: 11
                            Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 11; color: parent.color }
                            Row {
                                x: 12; anchors.verticalCenter: parent.verticalCenter
                                width: parent.width - 24
                                Repeater {
                                    model: [["audio.kol_fayl", 1.3], ["audio.kol_film", 1.6], ["audio.kol_dorozhka", 1], ["audio.kol_zaderzhka", 0.9]]
                                    delegate: Text {
                                        width: (tableCol.width - 24) * modelData[1] / 4.8
                                        text: Ui.tx(modelData[0])
                                        color: Theme.muted
                                        font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 12
                                    }
                                }
                            }
                        }
                        Repeater {
                            model: sc.src.rows
                            delegate: PairRow { width: tableCol.width; row: modelData; rowIndex: index; key: sc.key; poster: sc.src.moviePoster }
                        }
                    }
                }

                // одна задержка на все серии
                Row {
                    visible: table.visible && sc.src.rows.length > 1
                    topPadding: 10
                    spacing: 10
                    Text { anchors.verticalCenter: parent.verticalCenter; text: Ui.tx("audio.zaderzhka_dlya_vseh"); color: Theme.muted; font.family: Theme.onest; font.pixelSize: 13 }
                    Row {
                        anchors.verticalCenter: parent.verticalCenter
                        Field { id: allField; width: 110; placeholder: "0"; input.maximumLength: 7; input.validator: RegularExpressionValidator { regularExpression: /[-+−]?\d{0,6}/ } }
                        Text { anchors.verticalCenter: parent.verticalCenter; text: Ui.tx("common.ms"); color: Theme.muted; font.family: Theme.onest; font.pixelSize: 12 }
                    }
                    OutlineButton {
                        anchors.verticalCenter: parent.verticalCenter
                        text: Ui.tx("audio.primenit_ko_vsem", { n: sc.src.rows.length })
                        onClicked: if (audio.offsetValid(allField.text)) audio.applyAll(sc.key, allField.text)
                    }
                }
                Item {
                    visible: table.visible
                    width: parent.width; height: visible ? 56 : 0
                    LampButton {
                        y: 12; width: parent.width; height: 44
                        text: Ui.tx("audio.privyazat")
                        enabled_: sc.ready
                        onClicked: audio.attach(sc.key)
                    }
                }
            }
        }
    }

    /// Строка таблицы: файл озвучки, фильм (постер, название, серия), дорожка, ▶ и задержка.
    component PairRow: Item {
        id: pr
        property var row
        property int rowIndex
        property var key
        property string poster
        height: Math.max(56, cells.implicitHeight + 16)
        readonly property real unit: (width - 24) / 4.8
        Row {
            id: cells
            x: 12; anchors.verticalCenter: parent.verticalCenter
            Text {
                width: pr.unit * 1.3; rightPadding: 8
                anchors.verticalCenter: parent.verticalCenter
                text: pr.row.audio
                color: Theme.text; wrapMode: Text.WrapAnywhere; maximumLineCount: 2; elide: Text.ElideRight
                font.family: Theme.onest; font.pixelSize: 13
            }
            Item {
                width: pr.unit * 1.6; height: 40
                anchors.verticalCenter: parent.verticalCenter
                Rectangle {
                    id: thumb
                    width: 28; height: 40; radius: 5
                    color: "#1E222D"
                    clip: true
                    Image {
                        anchors.fill: parent
                        visible: pr.poster !== ""
                        source: pr.poster !== "" ? "image://suslik/" + pr.poster : ""
                        sourceSize: Qt.size(56, 80)
                        fillMode: Image.PreserveAspectCrop
                        asynchronous: true
                    }
                }
                Column {
                    anchors { left: thumb.right; leftMargin: 8; right: parent.right; rightMargin: 8; verticalCenter: parent.verticalCenter }
                    Text { width: parent.width; text: pr.row.videoTitle; color: Theme.text; elide: Text.ElideRight; font.family: Theme.onest; font.weight: Font.Medium; font.pixelSize: 13 }
                    Text { width: parent.width; text: pr.row.video; color: Theme.muted; elide: Text.ElideRight; font.family: Theme.onest; font.pixelSize: 12 }
                }
            }
            Text {
                width: pr.unit; rightPadding: 8
                anchors.verticalCenter: parent.verticalCenter
                text: pr.row.voice
                color: Theme.text; elide: Text.ElideRight
                font.family: Theme.onest; font.pixelSize: 13
            }
            Row {
                width: pr.unit * 0.9
                anchors.verticalCenter: parent.verticalCenter
                spacing: 6
                OutlineButton { id: playBtn; text: "▶"; onClicked: audio.preview(pr.key, pr.rowIndex) }
                Field {
                    id: off
                    anchors.verticalCenter: parent.verticalCenter
                    width: Math.max(56, parent.width - playBtn.width - msLabel.width - 12)
                    placeholder: "0"
                    text: pr.row.offset
                    input.maximumLength: 7
                    input.validator: RegularExpressionValidator { regularExpression: /[-+−]?\d{0,6}/ }
                    Connections { target: off.input; function onTextEdited() { audio.setOffset(pr.key, pr.rowIndex, off.text) } }
                }
                Text {
                    id: msLabel
                    anchors.verticalCenter: parent.verticalCenter
                    text: Ui.tx("common.ms")
                    color: audio.offsetValid(off.text) ? Theme.muted : Theme.bad
                    font.family: Theme.onest; font.pixelSize: 12
                }
            }
        }
        Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Theme.line }
    }

    /// Добавленная раздача и её состояние в AllDebrid; пока качается — полоска.
    component AddedRow: Item {
        id: ar
        property var entry
        height: box.height + 6
        HoverHandler { id: hov }
        Rectangle {
            id: box
            y: 3
            width: parent.width
            height: rowCol.implicitHeight + 28
            radius: 12
            color: Theme.booth
            border.color: hov.hovered ? "#3E475C" : Theme.line
            Behavior on border.color { ColorAnimation { duration: 200 } }
            Column {
                id: rowCol
                x: 14; y: 14
                width: parent.width - 28
                spacing: 10
                Item {
                    width: parent.width; height: Math.max(nameText.implicitHeight, noteText.implicitHeight)
                    Text {
                        id: nameText
                        anchors { left: parent.left; right: noteText.left; rightMargin: 12 }
                        text: ar.entry.name
                        color: Theme.text; elide: Text.ElideRight
                        font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 15
                    }
                    Text {
                        id: noteText
                        anchors.right: parent.right
                        width: Math.min(implicitWidth, parent.width * 0.6)
                        horizontalAlignment: Text.AlignRight
                        text: ar.entry.note
                        color: !ar.entry.ok ? Theme.bad : ar.entry.done ? Theme.ok : "white"
                        elide: Text.ElideRight
                        font.family: Theme.onest; font.pixelSize: 13
                    }
                }
                Rectangle {
                    visible: ar.entry.progress >= 0 && !ar.entry.done
                    width: parent.width; height: 5; radius: 3
                    color: Theme.line
                    clip: true
                    Rectangle {
                        height: parent.height; radius: 3
                        color: "white"
                        width: parent.width * Math.max(0, ar.entry.progress)
                        Behavior on width { NumberAnimation { duration: 600 } }
                    }
                }
            }
        }
    }
}
