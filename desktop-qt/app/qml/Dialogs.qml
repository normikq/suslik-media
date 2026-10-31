import QtQuick

// Окна поверх медиатеки: «Какую версию включить?», «Смотреть», ответы ИИ. Наполняются в следующих частях шага 2.
Item {
    id: d
    function versions(title, ids) { console.log("versions", title, ids) }
    function play(magnetId, path) { console.log("play", magnetId, path) }
}
