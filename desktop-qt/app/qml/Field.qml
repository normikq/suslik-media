import QtQuick

// Поле ввода: тёмное, рамка светлеет в фокусе, подсказка серым (Controls.kt: Field).
Rectangle {
    id: f
    property alias text: input.text
    property string placeholder
    property bool password: false
    property string icon
    property alias input: input
    signal accepted()
    implicitHeight: 44
    implicitWidth: 280
    radius: 12
    color: "#12151D"
    border.color: input.activeFocus ? "#3E475C" : "#222735"
    Behavior on border.color { ColorAnimation { duration: 150 } }

    Icon { id: ic; visible: f.icon !== ""; name: f.icon; size: 16; color: Theme.muted; x: 14; anchors.verticalCenter: parent.verticalCenter }
    TextInput {
        id: input
        anchors { left: ic.visible ? ic.right : parent.left; leftMargin: ic.visible ? 10 : 14; right: parent.right; rightMargin: 14; verticalCenter: parent.verticalCenter }
        color: Theme.text
        selectionColor: Theme.accent
        selectedTextColor: "white"
        font.family: Theme.onest; font.pixelSize: 15
        echoMode: f.password ? TextInput.Password : TextInput.Normal
        clip: true
        selectByMouse: true
        onAccepted: f.accepted()
        Text {
            visible: input.text.length === 0
            text: f.placeholder
            color: "#6B7280"
            font: input.font
            anchors.verticalCenter: parent.verticalCenter
        }
    }
    MouseArea { anchors.fill: parent; cursorShape: Qt.IBeamCursor; acceptedButtons: Qt.NoButton }
    TapHandler { onTapped: input.forceActiveFocus() }

    // Правая кнопка — «Вырезать / Копировать / Вставить / Выделить всё / Очистить» (TextInput своего меню не даёт).
    // Выделение не сбрасывается: правая кнопка до поля не доходит. textEdited — как при правке с клавиатуры.
    MouseArea {
        anchors.fill: parent
        acceptedButtons: Qt.RightButton
        cursorShape: Qt.IBeamCursor
        onClicked: (m) => {
            input.forceActiveFocus()
            editMenu.popup(f, m.x, m.y)
        }
    }
    SmMenu {
        id: editMenu
        readonly property bool hasSel: input.selectedText.length > 0
        // пароль не копируется и не вырезается
        SmMenuItem { text: Ui.tx("field.vyrezat"); enabled: editMenu.hasSel && !f.password && !input.readOnly; onTriggered: { input.cut(); input.textEdited(); input.forceActiveFocus() } }
        SmMenuItem { text: Ui.tx("field.kopirovat"); enabled: editMenu.hasSel && !f.password; onTriggered: { input.copy(); input.forceActiveFocus() } }
        SmMenuItem { text: Ui.tx("field.vstavit"); enabled: input.canPaste && !input.readOnly; onTriggered: { input.paste(); input.textEdited(); input.forceActiveFocus() } }
        SmMenuSeparator {}
        SmMenuItem { text: Ui.tx("field.vydelit_vse"); enabled: input.text.length > 0; onTriggered: { input.selectAll(); input.forceActiveFocus() } }
        SmMenuItem { text: Ui.tx("field.ochistit"); enabled: input.text.length > 0 && !input.readOnly; onTriggered: { input.clear(); input.textEdited(); input.forceActiveFocus() } }
    }
}
