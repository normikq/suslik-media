import QtQuick
import QtQuick.Controls.Basic

MenuItem {
    id: it
    property color fg: Theme.text
    property bool tick: false     // «✓» слева (вид, сортировка)
    property bool tickColumn: false
    implicitHeight: 36
    contentItem: Text {
        leftPadding: 8
        text: (it.tickColumn ? (it.tick ? "✓  " : "     ") : "") + it.text
        color: it.enabled ? (it.tick ? "white" : it.fg) : Theme.muted
        font.family: Theme.onest; font.pixelSize: 14
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
    }
    background: Rectangle { radius: 6; color: it.highlighted && it.enabled ? "#232838" : "transparent" }
}
