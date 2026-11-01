import QtQuick

// «Стекло»: полупрозрачная заливка и тонкая светлая рамка (Theme.kt: glass). В упрощённом режиме — непрозрачная.
Rectangle {
    property bool hovered: false
    radius: 14
    color: Theme.simple ? (hovered ? "#1B2640" : "#141B2C") : (hovered ? Theme.glassFillHover : Theme.glassFill)
    border.color: hovered ? Theme.glassEdgeHover : Theme.glassEdge
}
