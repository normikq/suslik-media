import QtQuick

// Image в Rounded (layer.enabled) тратит на каждый постер отдельный текстурный слой — при десятках плиток
// видеопамять уходит гигабайтами и Qt начинает мерцать. Здесь постер рисуется одним плоским ShaderEffect:
// углы срезаются прямо из текстуры постера, без промежуточного буфера кадров.
Item {
    id: r
    property real radius: 14
    property alias source: img.source
    property alias status: img.status
    property alias sourceSize: img.sourceSize
    property real zoom: 1          // > 1 — картинка крупнее (наведение), края срезаются
    property bool fadeIn: true     // появляется плавно, когда раскодирована

    // mipmap: постер 360×540 в плитке ~180 px — без него при уменьшении и при наведении (zoom) картинка мерцает и «дёргается»
    Image { id: img; visible: false; asynchronous: true; smooth: true; mipmap: true; cache: true }

    ShaderEffect {
        anchors.fill: parent
        opacity: r.fadeIn && img.status === Image.Ready ? 1 : (r.fadeIn ? 0 : 1)
        Behavior on opacity { NumberAnimation { duration: 200 } }
        property real radius: r.radius
        property size size: Qt.size(r.width, r.height)
        property real zoom: r.zoom
        property var source: img
        fragmentShader: "qrc:/sm/shaders/roundedimage.frag.qsb"
        onStatusChanged: if (status === ShaderEffect.Error) console.warn("roundedimage:", log)
    }
}