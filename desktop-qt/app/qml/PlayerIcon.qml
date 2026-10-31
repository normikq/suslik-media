import QtQuick

// Значки плеера (PlayerIcons.kt): нарисованы путями, без эмодзи и системных глифов.
// name: audio, subs, gear, volume, full, unfull, next, prev, seekBack, seekFwd, play, cross, pin, mini
Canvas {
    id: c
    property string name
    property color color: "white"
    property real size: 20
    property int volume: 100      // для volume: волны по громкости
    property bool muted: false
    width: size; height: size
    renderTarget: Canvas.Image
    antialiasing: true
    onNameChanged: requestPaint()
    onColorChanged: requestPaint()
    onSizeChanged: requestPaint()
    onVolumeChanged: if (name === "volume") requestPaint()
    onMutedChanged: if (name === "volume") requestPaint()

    onPaint: {
        const g = getContext("2d")
        const w = width, h = height
        g.reset()
        g.strokeStyle = color
        g.fillStyle = color
        g.lineCap = "round"
        g.lineJoin = "round"
        function line(x1, y1, x2, y2, sw) { g.beginPath(); g.moveTo(x1, y1); g.lineTo(x2, y2); g.lineWidth = sw; g.stroke() }
        // дуга как у Compose drawArc: градусы по часовой от «трёх часов»
        function arc(cx, cy, r, start, sweep, sw) {
            g.beginPath()
            g.arc(cx, cy, r, start * Math.PI / 180, (start + sweep) * Math.PI / 180, sweep < 0)
            g.lineWidth = sw; g.stroke()
        }
        function tri(x1, y1, x2, y2, x3, y3) { g.beginPath(); g.moveTo(x1, y1); g.lineTo(x2, y2); g.lineTo(x3, y3); g.closePath(); g.fill() }
        switch (name) {
        case "audio": { // нота
            const hx = w * 0.38, hy = h * 0.70, rx = w * 0.22, ry = h * 0.16
            g.save(); g.translate(hx, hy); g.rotate(-22 * Math.PI / 180); g.scale(1, ry / rx)
            g.beginPath(); g.arc(0, 0, rx, 0, Math.PI * 2); g.restore(); g.fill()
            const sx = hx + rx * 0.72, top = h * 0.18
            line(sx, hy, sx, top, 1.8)
            g.beginPath(); g.moveTo(sx, top)
            g.bezierCurveTo(sx + w * 0.26, top + h * 0.08, sx + w * 0.32, top + h * 0.28, sx + w * 0.12, top + h * 0.44)
            g.bezierCurveTo(sx + w * 0.24, top + h * 0.26, sx + w * 0.18, top + h * 0.14, sx, top + h * 0.10)
            g.closePath(); g.fill()
            break
        }
        case "subs": { // рамка и «CC»
            const px = w * 0.08, py = h * 0.18, rw = w - px * 2, rh = h - py * 2
            g.beginPath(); g.roundedRect(px, py, rw, rh, 3, 3); g.lineWidth = 1.4; g.stroke()
            const cr = rh * 0.21, cy = py + rh * 0.5, d = 2 + 1.707 * cr, cx = px + rw * 0.5 + 0.3
            arc(cx - d / 2, cy, cr, 45, 270, 1.5)
            arc(cx + d / 2, cy, cr, 45, 270, 1.5)
            break
        }
        case "gear": {
            const cx = w / 2, cy = h / 2, ro = Math.min(w, h) * 0.45, ri = Math.min(w, h) * 0.31, rh = Math.min(w, h) * 0.15
            g.beginPath()
            for (let i = 0; i < 8; i++) {
                const b = i * 45
                const pts = [[ro, b - 10], [ro, b + 10], [ri, b + 14.5], [ri, b + 30.5]]
                for (let k = 0; k < 4; k++) {
                    const a = pts[k][1] * Math.PI / 180, x = cx + pts[k][0] * Math.cos(a), y = cy + pts[k][0] * Math.sin(a)
                    if (i === 0 && k === 0) g.moveTo(x, y); else g.lineTo(x, y)
                }
            }
            g.closePath()
            g.moveTo(cx + rh, cy); g.arc(cx, cy, rh, 0, Math.PI * 2, true)
            g.fill()
            break
        }
        case "volume": {
            const cy = h * 0.5
            g.beginPath(); g.moveTo(w * 0.08, cy - h * 0.18); g.lineTo(w * 0.30, cy - h * 0.18); g.lineTo(w * 0.48, cy - h * 0.38)
            g.lineTo(w * 0.48, cy + h * 0.38); g.lineTo(w * 0.30, cy + h * 0.18); g.lineTo(w * 0.08, cy + h * 0.18); g.closePath(); g.fill()
            if (muted) line(w * 0.14, h * 0.16, w * 0.86, h * 0.84, 1.9)
            else if (volume > 0) {
                arc(w * 0.30, cy, w * 0.30, -42, 84, 1.8)
                if (volume > 40) arc(w * 0.30, cy, w * 0.48, -42, 84, 1.8)
            }
            break
        }
        case "full": case "unfull": {
            const p = w * 0.18, x1 = p, x2 = w - p, y1 = p, y2 = h - p, a = w * 0.25, sw = 1.8
            if (name === "full") {
                line(x1, y1 + a, x1, y1, sw); line(x1, y1, x1 + a, y1, sw); line(x2 - a, y1, x2, y1, sw); line(x2, y1, x2, y1 + a, sw)
                line(x1, y2 - a, x1, y2, sw); line(x1, y2, x1 + a, y2, sw); line(x2 - a, y2, x2, y2, sw); line(x2, y2, x2, y2 - a, sw)
            } else {
                line(x1 + a, y1, x1 + a, y1 + a, sw); line(x1 + a, y1 + a, x1, y1 + a, sw); line(x2 - a, y1, x2 - a, y1 + a, sw); line(x2 - a, y1 + a, x2, y1 + a, sw)
                line(x1 + a, y2, x1 + a, y2 - a, sw); line(x1 + a, y2 - a, x1, y2 - a, sw); line(x2 - a, y2, x2 - a, y2 - a, sw); line(x2 - a, y2 - a, x2, y2 - a, sw)
            }
            break
        }
        case "next": case "prev": {
            if (name === "prev") { g.translate(w, 0); g.scale(-1, 1) }
            const cy = h * 0.5, hh = h * 0.28
            tri(w * 0.16, cy - hh, w * 0.46, cy, w * 0.16, cy + hh)
            tri(w * 0.46, cy - hh, w * 0.76, cy, w * 0.46, cy + hh)
            line(w * 0.82, cy - hh, w * 0.82, cy + hh, 1.7)
            break
        }
        case "seekBack": case "seekFwd": { // круговая стрелка 10 с (цифры — текстом поверх)
            const cx = w / 2, cy = h / 2, r = w * 9.4 / 28, hl = w * 4.4 / 28, hh = w * 3.3 / 28
            if (name === "seekBack") {
                const bx = cx + w * 0.8 / 28, tx = cx - hl, ty = cy - r
                tri(tx, ty, bx, ty - hh, bx, ty + hh)
                arc(cx, cy, r, -86, 265, 1.9)
            } else {
                const bx = cx - w * 0.8 / 28, tx = cx + hl, ty = cy - r
                tri(tx, ty, bx, ty - hh, bx, ty + hh)
                arc(cx, cy, r, -94, -265, 1.9)
            }
            break
        }
        case "play":
            tri(w * 0.18, h * 0.12, w * 0.88, h * 0.5, w * 0.18, h * 0.88)
            break
        case "cross": {
            const p = w * 0.14
            line(p, p, w - p, h - p, 1.6); line(w - p, p, p, h - p, 1.6)
            break
        }
        case "pin": { // два окна: «поверх всех окон»
            g.lineWidth = 1.5
            g.beginPath(); g.roundedRect(w * 0.14, w * 0.30, w * 0.52, h * 0.52, 2, 2); g.stroke()
            g.beginPath(); g.roundedRect(w * 0.36, w * 0.14, w * 0.50, h * 0.50, 2, 2); g.fill()
            break
        }
        case "mini": { // «⤢»: обычный режим из мини-плеера
            line(w * 0.2, h * 0.8, w * 0.8, h * 0.2, 1.7)
            line(w * 0.5, h * 0.2, w * 0.8, h * 0.2, 1.7); line(w * 0.8, h * 0.2, w * 0.8, h * 0.5, 1.7)
            line(w * 0.2, h * 0.5, w * 0.2, h * 0.8, 1.7); line(w * 0.2, h * 0.8, w * 0.5, h * 0.8, 1.7)
            break
        }
        case "back": // «‹» в библиотеку
            g.beginPath(); g.moveTo(w * 0.62, h * 0.2); g.lineTo(w * 0.36, h * 0.5); g.lineTo(w * 0.62, h * 0.8); g.lineWidth = 2.2; g.stroke()
            break
        }
    }
}
