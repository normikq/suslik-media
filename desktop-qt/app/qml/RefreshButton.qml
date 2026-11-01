import QtQuick

// Крутится, пока медиатека грузится (полный оборот, потом мягко встаёт), при наведении наклоняется и светится.
Btn {
    id: r
    property bool loading: false
    width: 42; height: 42
    hoverUp: 1.06
    enabled_: !loading
    property real rot: 0
    property real glow: loading ? 1 : hovered ? 0.6 : 0.25
    Behavior on glow { NumberAnimation { duration: 300 } }

    NumberAnimation on rot {
        id: spin
        running: r.loading
        from: 0; to: 360; duration: 900; loops: Animation.Infinite
    }
    NumberAnimation { id: settle; target: r; property: "rot"; to: 360; duration: 520; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease; onFinished: r.rot = 0 }
    onLoadingChanged: if (!loading && rot !== 0) settle.start()

    Repeater { // свечение
        model: 4
        Rectangle {
            required property int index
            anchors.centerIn: parent
            width: 72 * (1 - index / 4); height: width; radius: width / 2
            color: "#60A5FA"
            opacity: 0.35 * r.glow * [0.12, 0.2, 0.3, 0.38][index]
        }
    }
    Rectangle {
        anchors.fill: parent
        radius: 12
        color: r.pressed ? "#3860A5FA" : r.hovered ? "#2E60A5FA" : r.loading ? "#2460A5FA" : "#1460A5FA"
        border.color: r.hovered || r.loading ? "#8060A5FA" : "#4060A5FA"
        Behavior on color { ColorAnimation { duration: 200 } }
    }
    Icon {
        anchors.centerIn: parent
        name: "refresh"; size: 16
        color: r.hovered && !r.loading ? "#93C5FD" : "#60A5FA"
        rotation: (r.rot % 360) + (r.hovered && !r.loading ? 90 : 0)
        Behavior on rotation { enabled: !r.loading && !settle.running; NumberAnimation { duration: 380; easing.type: Easing.BezierSpline; easing.bezierCurve: Theme.ease } }
    }
}
