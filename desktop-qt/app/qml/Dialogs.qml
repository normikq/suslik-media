import QtQuick

// Окна поверх медиатеки: «Какую версию включить?», «Смотреть», ответы ИИ.
Item {
    id: d
    property var page   // страница, с которой открыли (выбор версии ведёт обратно в неё)
    signal play(var request)

    function versions(p, title, ids) { versionPicker.page = p; versionPicker.title = title; versionPicker.ids = ids; versionPicker.open = true }
    function play_(magnetId, path) { playDialog.show(magnetId, path) }

    Connections {
        target: lib
        function onAiPanelRequested(ids, message) { aiDialog.show(ids, message) }
    }

    VersionPicker { id: versionPicker }
    PlayDialog { id: playDialog; onPlay: toast.show("Плеер появится на шаге 3 — ссылки уже готовы") }  // временно, до переноса плеера }
    AiDialog { id: aiDialog }

    // Короткая подсказка снизу (пока плеера нет — что он будет в следующем шаге).
    Rectangle {
        id: toast
        function show(text) { label.text = text; opacity = 1; hide.restart() }
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: parent.bottom; anchors.bottomMargin: 28
        width: label.implicitWidth + 32; height: label.implicitHeight + 20
        radius: 12; color: Theme.sheet; border.color: Theme.sheetEdge
        opacity: 0
        visible: opacity > 0
        z: 20
        Behavior on opacity { NumberAnimation { duration: 200 } }
        Text { id: label; anchors.centerIn: parent; color: Theme.text; font.family: Theme.onest; font.pixelSize: 14 }
        Timer { id: hide; interval: 3500; onTriggered: toast.opacity = 0 }
    }
}
