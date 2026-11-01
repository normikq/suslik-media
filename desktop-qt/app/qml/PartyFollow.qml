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
    // ссылки готовы — сразу с места комнаты (в момент готовности, а не когда окно появилось)
    Connections {
        target: m
        function onChanged() {
            if (d.open && m.canWatch) m.playAt(party.fileKey === d.key ? party.roomMs() : 0)
        }
    }

    function check() {
        const key = party.fileKey
        if (!party.guest || key === "") {
            open = false
            if (key === "") party.followed = ""
            return
        }
        if (key === party.followed) return
        // ведущий перешёл к серии этой же очереди: плеер идёт за ним сам (PartySync)
        if (player.hasWatchKey(key)) { party.followed = key; return }
        const bar = key.indexOf("|")
        const id = Number(key.substring(0, bar)), path = key.substring(bar + 1)
        if (!lib.hasFile(id, path)) return
        party.followed = key
        d.key = key
        m.load(id, path)
        open = true
        if (m.canWatch) m.playAt(party.roomMs())
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
