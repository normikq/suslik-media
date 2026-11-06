import QtQuick
import Suslik.Native

// Гость комнаты смотрит файл ведущего (party/TogetherView.kt: PartyFollow): без окна выбора — другая серия или версия
// оставила бы комнату позади, — плеер сам открывается на файле и месте комнаты, с любой страницы. Один раз на файл:
// закрыл гость — ждёт «Смотреть вместе». Прогресс гостя сохраняется как обычно.
Sheet {
    id: d
    maxWidth: 460
    onDismissed: open = false
    property string key: ""

    PlayModel {
        id: m
        library: lib
        onPlayRequested: { d.open = false; player.start(m) }
    }
    // Ведущий только что включил фильм: место комнаты ещё 0 (он сообщит своё через пару секунд). Открыть сейчас значило бы
    // грузить начало файла и тут же перематывать — ждём место не дольше 2,5 с
    property double since: 0
    function go() {
        if (d.open && m.canWatch) m.playAt(party.fileKey === d.key ? party.roomMs() : 0)
    }
    function tryGo() {
        if (!d.open || !m.canWatch) return
        if (party.fileKey === d.key && party.roomMs() < 2000 && Date.now() - since < 2500) settle.start()
        else { settle.stop(); go() }
    }
    Timer { id: settle; interval: 2500; onTriggered: d.go() }
    // ссылки готовы — сразу с места комнаты (в момент готовности, а не когда окно появилось)
    Connections {
        target: m
        function onChanged() { d.tryGo() }
    }
    Connections {
        target: party
        function onChanged() { if (settle.running) d.tryGo() }
    }

    property string lastTriedKey: ""
    function check() {
        const key = party.fileKey
        if (!party.guest || key === "") {
            open = false
            if (key === "") { party.followed = ""; lastTriedKey = "" }
            return
        }
        if (key === party.followed) return
        // ведущий перешёл к серии этой же очереди: плеер идёт за ним сам (PartySync)
        if (player.hasWatchKey(key)) { party.followed = key; return }
        const bar = key.indexOf("|")
        const id = Number(key.substring(0, bar)), path = key.substring(bar + 1)
        if (!lib.hasFile(id, path)) {
            if (key !== lastTriedKey) {
                lastTriedKey = key
                lib.refresh()
            }
            return
        }
        party.followed = key
        lastTriedKey = ""
        d.key = key
        since = Date.now()
        settle.stop()
        m.load(id, path)
        open = true
        tryGo()
    }
    Connections { target: party; function onChanged() { d.check() } }
    Connections { target: lib; function onVersionChanged() { d.check() } }
    Connections { target: player; function onOpenChanged() { d.check() } }

    Item {
        width: parent.width
        height: Math.max(head.implicitHeight, 32)
        Column {
            id: head
            width: parent.width - 44
            Text { text: Ui.tx("party.otkryvaem_film_vedushchego"); color: "#8E96A8"; font.family: Theme.onest; font.pixelSize: 13 }
            Text {
                width: parent.width
                topPadding: 4
                text: m.currentTitle
                color: "white"
                wrapMode: Text.Wrap; maximumLineCount: 2; elide: Text.ElideRight
                font.family: Theme.unbounded; font.weight: Font.Bold; font.pixelSize: 18
            }
        }
        CloseCircle { anchors.right: parent.right; onClicked: d.open = false }
    }
    Text {
        topPadding: 14
        width: parent.width
        text: m.loading || m.canWatch ? Ui.tx("library.gotovim") : m.status
        color: Theme.muted; wrapMode: Text.Wrap
        font.family: Theme.onest; font.pixelSize: 13
    }
}
