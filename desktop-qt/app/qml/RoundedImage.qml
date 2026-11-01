import QtQuick

// Картинка со скруглёнными углами и кадрированием «заполнить» (PreserveAspectCrop) без слоя: шейдер берёт текстуру
// Image напрямую. Rounded (layer.enabled) заводит на каждый элемент отдельную текстуру-слой — для десятков плиток
// это десятки FBO в памяти видеокарты.
Item {
    id: ri
    property alias source: img.source
    property alias sourceSize: img.sourceSize
    readonly property alias status: img.status
    property real radius: 14
    property real zoom: 1          // > 1 — картинка крупнее (наведение), края срезаются
    property bool fadeIn: true     // появляется плавно, когда раскодирована

    // mipmap: постер 360×540 в плитке ~180 px — без него при уменьшении и при наведении (zoom) картинка мерцает и «дёргается»
    Image { id: img; visible: false; asynchronous: true; smooth: true; mipmap: true }

    ShaderEffect {
        anchors.fill: parent
        visible: img.status === Image.Ready
        opacity: !ri.fadeIn || img.status === Image.Ready ? 1 : 0
        Behavior on opacity { enabled: ri.fadeIn; NumberAnimation { duration: 200 } }
        property variant source: img
        property size size: Qt.size(width, height)
        property real radius: ri.radius
        property vector4d uvRect: {
            const iw = img.implicitWidth, ih = img.implicitHeight
            if (iw <= 0 || ih <= 0 || width <= 0 || height <= 0) return Qt.vector4d(0, 0, 1, 1)
            const a = width / height, b = iw / ih
            let sx = 1, sy = 1
            if (b > a) sx = a / b; else sy = b / a
            sx /= ri.zoom; sy /= ri.zoom
            return Qt.vector4d((1 - sx) / 2, (1 - sy) / 2, sx, sy)
        }
        fragmentShader: "qrc:/sm/shaders/roundedimage.frag.qsb"
        onStatusChanged: if (status === ShaderEffect.Error) console.warn("roundedimage:", log)
    }
}
