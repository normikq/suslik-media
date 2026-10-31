import QtQuick

// Мягкое синее, фиолетовое и бирюзовое свечение за всем окном (Theme.kt: ambientBackdrop).
// Рисуется один раз на размер окна в четверть разрешения и растягивается: свечение мягкое, разницы не видно.
Rectangle {
    color: Theme.backdrop
    Canvas {
        id: glow
        readonly property int k: 4
        width: Math.max(1, Math.ceil(parent.width / k))
        height: Math.max(1, Math.ceil(parent.height / k))
        scale: k
        transformOrigin: Item.TopLeft
        smooth: true
        renderTarget: Canvas.Image
        renderStrategy: Canvas.Threaded
        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
        onPaint: {
            const g = getContext("2d")
            const w = width, h = height, m = Math.min(w, h)
            g.reset()
            function spot(c, cx, cy, r) {
                const gr = g.createRadialGradient(cx, cy, 0, cx, cy, r)
                gr.addColorStop(0, c); gr.addColorStop(1, "transparent")
                g.fillStyle = gr; g.fillRect(cx - r, cy - r, r * 2, r * 2)
            }
            spot(Qt.rgba(0x25 / 255, 0x63 / 255, 0xEB / 255, 0x30 / 255), w * 0.25, h * 0.22, m * 0.8)
            spot(Qt.rgba(0x8B / 255, 0x5C / 255, 0xF6 / 255, 0x2E / 255), w * 0.95, h * 0.95, m * 0.75)
            spot(Qt.rgba(0x06 / 255, 0xB6 / 255, 0xD4 / 255, 0x24 / 255), w * 0.55, h * 0.45, m * 0.6)
        }
    }
    // сверху переходит в цвет заголовка окна: системная полоса и окно читаются одним целым
    Rectangle {
        width: parent.width; height: 150
        gradient: Gradient { GradientStop { position: 0; color: Theme.backdrop } GradientStop { position: 1; color: "transparent" } }
    }
}
