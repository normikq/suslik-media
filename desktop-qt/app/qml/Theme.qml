pragma Singleton
import QtQuick

// «Проекторная»: те же цвета, шрифты и плавность, что у сайта, Android и прежней ПК-версии (ui/Theme.kt).
QtObject {
    readonly property color night: "#090B10"
    readonly property color booth: "#11141B"
    readonly property color film: "#181C26"
    readonly property color line: "#252A37"
    readonly property color line2: "#333A4B"
    readonly property color text: "#EEF0F5"
    readonly property color muted: "#8E96A8"
    readonly property color dim: "#5B6272"
    readonly property color ok: "#7FE0B0"
    readonly property color bad: "#FF9B8F"
    readonly property color scrim: "#CC090B10"

    // «Матовое стекло»
    readonly property color backdrop: "#060911"
    readonly property color lamp: "#FFB02E"
    readonly property color lampInk: "#1C1200"
    readonly property color accent: "#2563EB"
    readonly property color accentHover: "#3B74F0"
    readonly property color accentEdge: "#4D60A5FA"
    readonly property color glassSide: "#94080C16"
    readonly property color glassFill: "#4D2A3A5C"
    readonly property color glassFillHover: "#AD1C2844"
    readonly property color glassEdge: "#17FFFFFF"
    readonly property color glassEdgeHover: "#6660A5FA"
    readonly property color sheet: "#F00B1020"
    readonly property color sheetEdge: "#2EFFFFFF"
    readonly property color accentSoft: "#402563EB"
    readonly property color menu: "#181C26"
    readonly property color menuEdge: "#2A3040"

    readonly property string unbounded: "Unbounded"
    readonly property string onest: "Onest"

    // Плавность Sm.ease (0.22, 0.8, 0.24, 1) и Sm.fly (0.45, 0.05, 0.2, 1) для Easing.BezierSpline
    readonly property var ease: [0.22, 0.8, 0.24, 1, 1, 1]
    readonly property var fly: [0.45, 0.05, 0.2, 1, 1, 1]

    // «Упрощённые анимации» (Настройки): без стекла и переливов, без сдвигов страниц
    property bool simple: Ui.setting("simple_motion") === "1"
    function setSimple(on) { simple = on; Ui.setSetting("simple_motion", on ? "1" : "") }

    // Хранилище координат постеров и карточек для анимации вылета/отлёта плеера (ExitFlight)
    property var tiles: ({})
    property var lastRect: null
    property var lastId: 0

    function registerTile(id, item) {
        if (id && item) tiles[id] = item
    }

    function unregisterTile(id, item) {
        if (tiles[id] === item) delete tiles[id]
    }

    function getGlobalRect(item) {
        if (!item) return null
        try {
            const pt = item.mapToItem(null, 0, 0)
            if (!pt || isNaN(pt.x) || isNaN(pt.y)) return null
            const w = item.width
            const h = item.height
            if (!w || !h || isNaN(w) || isNaN(h)) return null
            return { x: pt.x, y: pt.y, width: w, height: h }
        } catch (e) {
            return null
        }
    }

    function getTargetRect(id, win) {
        let it = id ? tiles[id] : null
        let r = getGlobalRect(it)
        if (!r && lastRect) r = lastRect
        if (!r || isNaN(r.x) || isNaN(r.y) || !r.width || !r.height) return null
        // Проверка, что постер не уехал за пределы экрана
        if (win && (r.y + r.height * 0.3 < 0 || r.y + r.height * 0.7 > win.height ||
                    r.x + r.width * 0.3 < 0 || r.x + r.width * 0.7 > win.width)) {
            return null
        }
        return r
    }
}
