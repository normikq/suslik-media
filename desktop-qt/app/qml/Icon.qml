import QtQuick

// Значки прежней версии (ui/DesktopIcons.kt, Sidebar.kt): рисуются один раз при смене цвета или размера.
Canvas {
    id: c
    property string name
    property color color: "white"
    property bool filled: false
    property real size: 16
    width: size; height: size
    renderTarget: Canvas.Image
    antialiasing: true
    onNameChanged: requestPaint()
    onColorChanged: requestPaint()
    onFilledChanged: requestPaint()
    onSizeChanged: requestPaint()

    onPaint: {
        const g = getContext("2d")
        const w = width, h = height
        g.reset()
        g.strokeStyle = color
        g.fillStyle = color
        g.lineCap = "round"
        g.lineJoin = "round"
        function circle(x, y, r, fill, sw) {
            g.beginPath(); g.arc(x, y, r, 0, Math.PI * 2)
            if (fill) g.fill(); else { g.lineWidth = sw; g.stroke() }
        }
        function line(x1, y1, x2, y2, sw) { g.beginPath(); g.moveTo(x1, y1); g.lineTo(x2, y2); g.lineWidth = sw; g.stroke() }
        function poly(pts, close, fill, sw) {
            g.beginPath(); g.moveTo(pts[0] * w, pts[1] * h)
            for (let i = 2; i < pts.length; i += 2) g.lineTo(pts[i] * w, pts[i + 1] * h)
            if (close) g.closePath()
            if (fill) g.fill(); else { g.lineWidth = sw; g.stroke() }
        }
        function rrect(x, y, rw, rh, r, sw) {
            g.beginPath(); g.roundedRect(x, y, rw, rh, r, r); g.lineWidth = sw; g.stroke()
        }
        // Дуга как у Compose drawArc: углы в градусах по часовой от «трёх часов», в прямоугольнике (x, y, rw, rh)
        function arc(x, y, rw, rh, start, sweep, sw) {
            g.save(); g.translate(x + rw / 2, y + rh / 2); g.scale(1, rh / rw)
            g.beginPath(); g.arc(0, 0, rw / 2, start * Math.PI / 180, (start + sweep) * Math.PI / 180, false)
            g.restore(); g.lineWidth = sw; g.stroke()
        }
        const k = w / 16 // толщины линий заданы для 16 px
        switch (name) {
        case "search": {
            const r = w * 0.32, cx = w * 0.42, cy = h * 0.42
            circle(cx, cy, r, false, 1.8 * k)
            line(cx + r * 0.7071, cy + r * 0.7071, w * 0.88, h * 0.88, 1.8 * k)
            break
        }
        case "refresh": {
            const r = w * 0.35, cx = w / 2, cy = h / 2
            g.beginPath(); g.arc(cx, cy, r, 20 * Math.PI / 180, 270 * Math.PI / 180, false); g.lineWidth = 1.7 * k; g.stroke()
            const tx = cx + 2.5 * k, ty = cy - r, aw = 4 * k, ah = 3.2 * k
            g.beginPath(); g.moveTo(tx, ty); g.lineTo(tx - aw, ty - ah); g.lineTo(tx - aw * 0.7, ty); g.lineTo(tx - aw, ty + ah); g.closePath(); g.fill()
            break
        }
        case "funnel":
            poly([0.15, 0.22, 0.85, 0.22, 0.58, 0.56, 0.58, 0.82, 0.42, 0.74, 0.42, 0.56], true, false, 1.6 * k)
            break
        case "dots":
            for (const y of [0.28, 0.5, 0.72]) circle(w / 2, h * y, 1.6 * k, true)
            break
        case "chevronLeft":
            poly([0.62, 0.22, 0.35, 0.5, 0.62, 0.78], false, false, 1.8 * k)
            break
        case "chevronRight":
            poly([0.38, 0.22, 0.65, 0.5, 0.38, 0.78], false, false, 1.8 * k)
            break
        case "chevronDown":
            poly([0.22, 0.38, 0.5, 0.65, 0.78, 0.38], false, false, 1.8 * k)
            break
        case "play":
            poly([0.18, 0.08, 0.92, 0.5, 0.18, 0.92], true, true)
            break
        case "lightning":
            poly([0.58, 0, 0.16, 0.54, 0.5, 0.54, 0.42, 1, 0.84, 0.44, 0.52, 0.44], true, true)
            break
        case "link": {
            g.save(); g.translate(w / 2, h / 2); g.rotate(-Math.PI / 4); g.translate(-w / 2, -h / 2)
            const rw = w * 0.46, rh = h * 0.32
            rrect(w * 0.10, (h - rh) / 2, rw, rh, rh / 2, 1.6 * k)
            rrect(w * 0.44, (h - rh) / 2, rw, rh, rh / 2, 1.6 * k)
            g.restore()
            break
        }
        case "close": {
            const p = w * 0.22
            line(p, p, w - p, h - p, 1.8 * k); line(w - p, p, p, h - p, 1.8 * k)
            break
        }
        case "info":
            circle(w / 2, h / 2, w * 0.42, false, 1.5 * k)
            circle(w / 2, h * 0.30, 1.1 * k, true)
            line(w / 2, h * 0.44, w / 2, h * 0.72, 1.5 * k)
            break
        case "heart":
            g.beginPath(); g.moveTo(w * 0.5, h * 0.88)
            g.bezierCurveTo(w * 0.08, h * 0.58, w * 0.06, h * 0.30, w * 0.26, h * 0.18)
            g.bezierCurveTo(w * 0.40, h * 0.10, w * 0.50, h * 0.20, w * 0.5, h * 0.30)
            g.bezierCurveTo(w * 0.50, h * 0.20, w * 0.60, h * 0.10, w * 0.74, h * 0.18)
            g.bezierCurveTo(w * 0.94, h * 0.30, w * 0.92, h * 0.58, w * 0.5, h * 0.88)
            g.closePath()
            if (filled) g.fill(); else { g.lineWidth = 1.6 * k; g.stroke() }
            break
        case "bookmark": {
            const r = w * 0.10
            g.beginPath(); g.moveTo(w * 0.22, h * 0.88); g.lineTo(w * 0.22, h * 0.12 + r)
            g.quadraticCurveTo(w * 0.22, h * 0.12, w * 0.22 + r, h * 0.12); g.lineTo(w * 0.78 - r, h * 0.12)
            g.quadraticCurveTo(w * 0.78, h * 0.12, w * 0.78, h * 0.12 + r); g.lineTo(w * 0.78, h * 0.88); g.lineTo(w * 0.5, h * 0.68)
            g.closePath()
            if (filled) g.fill(); else { g.lineWidth = 1.6 * k; g.stroke() }
            break
        }
        case "gear": { // боковая панель и кнопка настроек: восемь зубцов и кольцо
            const r = Math.min(w, h) / 2, cx = w / 2, cy = h / 2
            for (let i = 0; i < 8; ++i) {
                const a = i * Math.PI / 4
                line(cx + Math.cos(a) * r * 0.62, cy + Math.sin(a) * r * 0.62, cx + Math.cos(a) * r * 0.95, cy + Math.sin(a) * r * 0.95, r * 0.28)
            }
            circle(cx, cy, r * 0.62, false, r * 0.22)
            if (filled) circle(cx, cy, r * 0.16, true)
            break
        }
        case "people":
            circle(w * 0.36, w * 0.34, w * 0.17, false, 1.6 * k)
            arc(w * 0.06, w * 0.52, w * 0.60, w * 0.60, 200, 140, 1.6 * k)
            circle(w * 0.70, w * 0.30, w * 0.14, false, 1.6 * k)
            arc(w * 0.46, w * 0.48, w * 0.50, w * 0.50, 240, 100, 1.6 * k)
            break
        case "home":
            poly([0.5, 0.06, 0.96, 0.48, 0.84, 0.48, 0.84, 0.94, 0.62, 0.94, 0.62, 0.62, 0.38, 0.62, 0.38, 0.94, 0.16, 0.94, 0.16, 0.48, 0.04, 0.48], true, true)
            break
        case "film":
            rrect(w * 0.1, w * 0.06, w * 0.8, w * 0.88, w * 0.14, 1.5 * k)
            g.lineCap = "butt"
            line(w * 0.32, w * 0.06, w * 0.32, w * 0.94, 1.5 * k)
            line(w * 0.68, w * 0.06, w * 0.68, w * 0.94, 1.5 * k)
            for (const y of [0.26, 0.5, 0.74]) { circle(w * 0.21, w * y, w * 0.045, true); circle(w * 0.79, w * y, w * 0.045, true) }
            break
        case "screen":
            rrect(w * 0.06, w * 0.12, w * 0.88, w * 0.6, w * 0.1, 1.6 * k)
            line(w * 0.5, w * 0.72, w * 0.5, w * 0.88, 1.6 * k)
            line(w * 0.3, w * 0.9, w * 0.7, w * 0.9, 1.6 * k)
            break
        case "anime": case "cartoon": case "other": {
            const st = 1.5 * k
            if (name !== "other") { circle(w * 0.2, w * 0.2, w * 0.15, false, st); circle(w * 0.8, w * 0.2, w * 0.15, false, st) }
            circle(w * 0.5, w * 0.57, w * 0.38, false, st)
            circle(w * 0.37, w * 0.52, w * 0.045, true); circle(w * 0.63, w * 0.52, w * 0.045, true)
            if (name === "anime") line(w * 0.4, w * 0.72, w * 0.6, w * 0.72, st)
            else arc(w * 0.34, w * 0.5, w * 0.32, w * 0.3, 20, 140, st)
            break
        }
        case "grid": {
            const pad = w * 0.12, gap = w * 0.14, b = (w - pad * 2 - gap) / 2
            for (const x of [pad, pad + b + gap]) for (const y of [pad, pad + b + gap]) { g.beginPath(); g.roundedRect(x, y, b, b, 2 * k, 2 * k); g.fill() }
            break
        }
        case "list":
            for (const y of [0.25, 0.5, 0.75]) line(w * 0.14, h * y, w * 0.86, h * y, 1.8 * k)
            break
        case "check":
            poly([0.2, 0.52, 0.42, 0.74, 0.82, 0.28], false, false, 1.9 * k)
            break
        }
    }
}
