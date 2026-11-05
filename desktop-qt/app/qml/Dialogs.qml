import QtQuick

// Окна поверх медиатеки: «Какую версию включить?», «Смотреть», ответы ИИ.
Item {
    id: d
    property var page   // страница, с которой открыли (выбор версии ведёт обратно в неё)
    property var defaultPage
    signal play(var request)

    function versions(p, title, ids) { const targetPage = p || d.page || d.defaultPage; d.page = targetPage; versionPicker.page = targetPage; versionPicker.title = title; versionPicker.ids = ids; versionPicker.open = true }
    // Гость комнаты смотрит фильм ведущего: свой фильм (из поиска раздач) — только выйдя из комнаты
    function play_(magnetId, path) {
        if (party.guest) { say(Ui.tx("party.svoy_film_gostyu")); return }
        playDialog.show(magnetId, path)
    }

    Connections {
        target: lib
        function onAiPanelRequested(ids, message) { aiDialog.show(ids, message) }
    }

    function say(text) { toast.show(text) }

    VersionPicker { id: versionPicker }
    PlayDialog {
        id: playDialog
        onPlay: player.start(playDialog.model)
        onChangeVersion: (title, ids) => d.versions(d.page || d.defaultPage, title, ids)
    }
    AiDialog { id: aiDialog }

    // Короткая подсказка снизу.
    Rectangle {
        id: toast
        function show(text) { label.text = text; opacity = 1; hide.restart() }
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: parent.bottom; anchors.bottomMargin: 28
        // длинный текст (ошибка сети, трекера) переносится, плашка не шире окна
        width: Math.min(label.implicitWidth, Math.min(640, d.width - 48) - 32) + 32; height: label.height + 20
        radius: 12; color: Theme.sheet; border.color: Theme.sheetEdge
        opacity: 0
        visible: opacity > 0
        z: 20
        Behavior on opacity { NumberAnimation { duration: 200 } }
        Text {
            id: label
            anchors.centerIn: parent
            width: toast.width - 32
            wrapMode: Text.Wrap
            horizontalAlignment: Text.AlignHCenter
            maximumLineCount: 6; elide: Text.ElideRight
            color: Theme.text; font.family: Theme.onest; font.pixelSize: 14
        }
        // длинное сообщение висит дольше
        Timer { id: hide; interval: Math.min(8000, 3500 + label.text.length * 25); onTriggered: toast.opacity = 0 }
    }
}
