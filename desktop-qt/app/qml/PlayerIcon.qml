import QtQuick
import QtQuick.Window

// Значки плеера (PlayerIcons.kt): нарисованы путями (IconProvider, C++), без эмодзи и системных глифов.
// name: audio, subs, gear, volume, full, unfull, next, prev, seekBack, seekFwd, play, cross, pin, mini, back
Image {
    property string name
    property color color: "white"
    property real size: 20
    property int volume: 100      // для volume: волны по громкости
    property bool muted: false
    width: size; height: size
    readonly property int px: Math.ceil(size * Screen.devicePixelRatio)
    // громкость влияет только на число волн: в адресе — ступень, чтобы картинок было три, а не сто
    readonly property int level: name !== "volume" ? 0 : volume > 40 ? 2 : volume > 0 ? 1 : 0
    sourceSize: Qt.size(px, px)
    source: name ? "image://icon/player/" + name + "/" + size + "/" + color.toString().replace("#", "") + "/0/"
                   + (level === 2 ? 100 : level === 1 ? 20 : 0) + "/" + (muted ? 1 : 0) : ""
    smooth: true
    cache: true
}
