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

    // Где каждый фильм лежит в медиатеке (ExitFlight.kt: TileBounds): постеры и карточка «Продолжить просмотр».
    // На один фильм может быть несколько элементов (медиатека и «Мои фильмы», плитка и баннер) — хранится список.
    property var posters: ({})
    property var conts: ({})
    // фильм открыли из «Продолжить просмотр»: улетать сначала туда
    property bool fromContinue: false

    function track(map, id, item) {
        if (!id || !item) return
        const l = map[id] || []
        if (l.indexOf(item) < 0) l.push(item)
        map[id] = l
    }
    function untrack(map, id, item) {
        const l = id ? map[id] : null
        if (!l) return
        const i = l.indexOf(item)
        if (i >= 0) l.splice(i, 1)
        if (l.length === 0) delete map[id]
    }

    // Место элемента в окне, если он виден хотя бы на 60 % по каждой стороне в своей области прокрутки
    // (ближайший предок с clip); иначе null — другая вкладка, прокручен, скрыт.
    function visibleRect(item) {
        if (!item || !item.visible || item.width <= 1 || item.height <= 1) return null
        const a = item.mapToItem(null, 0, 0)
        const b = item.mapToItem(null, item.width, item.height)
        const r = { x: a.x, y: a.y, width: b.x - a.x, height: b.y - a.y }
        if (!(r.width > 1 && r.height > 1)) return null
        let vp = item.parent
        while (vp && !vp.clip) vp = vp.parent
        if (vp) {
            const va = vp.mapToItem(null, 0, 0)
            const vb = vp.mapToItem(null, vp.width, vp.height)
            const iw = Math.min(r.x + r.width, vb.x) - Math.max(r.x, va.x)
            const ih = Math.min(r.y + r.height, vb.y) - Math.max(r.y, va.y)
            if (iw < r.width * 0.6 || ih < r.height * 0.6) return null
        }
        return r
    }

    function firstVisible(l) {
        if (!l) return null
        for (const it of l) { const r = visibleRect(it); if (r) return r }
        return null
    }

    // Видимое место фильма в окне (TileBounds.target) или null
    function target(id) {
        if (!id) return null
        const a = firstVisible(conts[id]), b = firstVisible(posters[id])
        return fromContinue ? (a || b) : (b || a)
    }
}
