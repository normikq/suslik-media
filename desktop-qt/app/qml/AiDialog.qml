import QtQuick

// Что предложил ИИ для раздач плитки (AiTitleDialog.kt): отмечено — взять название ИИ, снято — оставить своё.
Sheet {
    id: d
    property var ids: []
    property string message
    property var rows: []
    property var ticks: ({})
    property bool saving: false
    readonly property bool editable: rows.some(r => r.canTick)
    maxWidth: 620
    onDismissed: open = false
    function show(ids_, msg) {
        ids = ids_; message = msg || ""
        rows = lib.aiRows(ids_)
        const t = {}
        for (const r of rows) t[r.id] = r.ticked
        ticks = t
        saving = false
        open = true
    }

    Text { text: Ui.tx("library.ai_okno"); color: Theme.text; font.family: Theme.unbounded; font.weight: Font.Medium; font.pixelSize: 20 }
    Text { visible: d.message !== ""; width: parent.width; topPadding: 10; text: d.message; color: Theme.muted; font.family: Theme.onest; font.pixelSize: 14; wrapMode: Text.Wrap }
    Text { visible: d.rows.length > 0 && d.editable; width: parent.width; topPadding: 4; bottomPadding: 12; text: Ui.tx("library.ai_okno_pod"); color: Theme.muted; font.family: Theme.onest; font.pixelSize: 13; wrapMode: Text.Wrap }
    Flickable {
        visible: d.rows.length > 0
        width: parent.width
        height: Math.min(420, col.height)
        contentHeight: col.height
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        Column {
            id: col
            width: parent.width
            spacing: 8
            Repeater {
                model: d.rows
                Rectangle {
                    id: r
                    required property var modelData
                    readonly property bool on: !!d.ticks[modelData.id]
                    width: col.width; height: rcol.implicitHeight + 16
                    radius: 14; color: Theme.glassFill; border.color: Theme.glassEdge
                    Rectangle { // галочка
                        id: box
                        visible: r.modelData.canTick
                        x: 14; anchors.verticalCenter: parent.verticalCenter
                        width: 18; height: 18; radius: 4
                        color: r.on ? Theme.accent : "transparent"
                        border.color: r.on ? Theme.accent : Theme.muted; border.width: 2
                        Icon { anchors.centerIn: parent; visible: r.on; name: "check"; size: 14; color: "white" }
                    }
                    Column {
                        id: rcol
                        x: r.modelData.canTick ? 46 : 12; y: 8
                        width: parent.width - x - 12
                        Text { width: parent.width; text: r.modelData.label; color: Theme.text; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 15; wrapMode: Text.Wrap }
                        Text { width: parent.width; text: r.modelData.name; color: Theme.muted; font.family: Theme.onest; font.pixelSize: 12; elide: Text.ElideRight }
                    }
                    MouseArea {
                        anchors.fill: parent
                        enabled: r.modelData.canTick && !d.saving
                        cursorShape: Qt.PointingHandCursor
                        onClicked: { const t = Object.assign({}, d.ticks); t[r.modelData.id] = !r.on; d.ticks = t }
                    }
                }
            }
        }
    }
    Item {
        width: parent.width; height: 16 + 38
        Row {
            anchors.right: parent.right; anchors.bottom: parent.bottom
            spacing: 10
            OutlineButton { text: d.editable ? Ui.tx("library.ai_pozzhe") : Ui.tx("library.zakryt"); onClicked: d.open = false }
            LampButton {
                visible: d.editable
                text: Ui.tx("library.ai_sohranit")
                enabled_: !d.saving
                onClicked: {
                    d.saving = true
                    const dec = {}
                    for (const r of d.rows) if (r.canTick) dec[String(r.id)] = !!d.ticks[r.id]
                    lib.saveAiDecisions(dec)
                    d.open = false
                }
            }
        }
    }
}
