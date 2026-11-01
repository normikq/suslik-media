import QtQuick

// Содержимое со скруглёнными углами (обычный clip в Qt Quick режет только прямоугольником). Свой слой: рисуется в текстуру,
// углы срезает маленький шейдер.
Item {
    id: r
    property real radius: 14
    default property alias content: holder.data
    Item {
        id: holder
        anchors.fill: parent
        layer.enabled: r.width > 0 && r.height > 0
        layer.smooth: true
        layer.effect: ShaderEffect {
            property real radius: r.radius
            property size size: Qt.size(r.width, r.height)
            fragmentShader: "qrc:/sm/shaders/rounded.frag.qsb"
            onStatusChanged: if (status === ShaderEffect.Error) console.warn("rounded:", log)
        }
    }
}
