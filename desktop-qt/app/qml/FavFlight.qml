import QtQuick

// Постер летит в раздел боковой панели, куда его только что перенесли (FavFlight.kt): в «Мои фильмы» при добавлении,
// обратно в «Главную», если убрали на странице «Моих фильмов». Только сдвиг и масштаб — без новой раскладки на кадр.
Item {
    id: f
    property var sidebar
    enabled: false   // мышь проходит насквозь

    function launch(tab, rect, poster) {
        if (!rect) return
        flight.createObject(f, { tab: tab, from: rect, poster: poster })
    }

    Component {
        id: flight
        Item {
            id: one
            property int tab
            property var from
            property string poster
            property real t: 0
            readonly property var dest: {
                const r = f.sidebar.navRect(tab)
                return r ? Qt.point(r.x + 24, r.y + r.height / 2) : Qt.point(from.x + from.width / 2, from.y + from.height / 2)
            }
            readonly property real end: 26 / Math.max(from.width, from.height) // до размера значка, пропорции те же
            x: from.x; y: from.y; width: from.width; height: from.height
            scale: 1 + (end - 1) * t
            transform: Translate {
                x: (one.dest.x - (one.from.x + one.from.width / 2)) * one.t
                y: (one.dest.y - (one.from.y + one.from.height / 2)) * one.t - Math.sin(Math.PI * one.t) * 70 // дугой вверх
            }
            opacity: 1 - Math.max(0, Math.min(1, (t - 0.78) / 0.22))
            Rounded {
                anchors.fill: parent
                radius: 14
                Rectangle { anchors.fill: parent; gradient: Gradient { GradientStop { position: 0; color: "#222838" } GradientStop { position: 1; color: "#10131B" } } }
                Image { anchors.fill: parent; source: one.poster ? "image://suslik" + one.poster : ""; sourceSize: Qt.size(360, 540); fillMode: Image.PreserveAspectCrop }
            }
            NumberAnimation on t {
                from: 0; to: 1; duration: Theme.simple ? 1 : 680
                easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.fly
                onFinished: { f.sidebar.land(one.tab); one.destroy() }
            }
        }
    }
}
