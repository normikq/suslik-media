import QtQuick

// Вход (LibraryView.kt: LoginView): через Discord кодом устройства или по общему паролю.
Rectangle {
    id: v
    color: Theme.night
    property bool busy: false
    property string error

    Connections {
        target: lib
        function onLoginFinished(err) { v.busy = false; v.error = err; if (err === "") pass.text = "" }
        function onPairingChanged() { v.busy = false }
    }

    Item {
        anchors { fill: parent; margins: 32 }
        // логотип
        Row {
            spacing: 12
            Rectangle {
                width: 36; height: 36; radius: 18
                color: "#141720"; border.color: "#33FFFFFF"
                Icon { anchors.centerIn: parent; name: "play"; size: 13; color: "white" }
            }
            Text { text: "Suslik Media"; color: "white"; font.family: Theme.onest; font.weight: Font.DemiBold; font.pixelSize: 16; anchors.verticalCenter: parent.verticalCenter }
        }
        Column {
            y: 36 + 64
            width: Math.min(640, parent.width)
            Text { text: Ui.tx("library.vse_vashi_ssylki_v"); color: Theme.muted; font.family: Theme.onest; font.pixelSize: 14 }
            Text {
                width: parent.width
                topPadding: 6; bottomPadding: 28
                text: Ui.tx("library.naydite_razblokiruyte_smotrite")
                color: Theme.text
                font.family: Theme.unbounded; font.weight: Font.Bold; font.pixelSize: 40
                lineHeight: 46; lineHeightMode: Text.FixedHeight
                wrapMode: Text.Wrap
            }
            Panel {
                width: parent.width
                // ждём подтверждения в браузере: код устройства
                Column {
                    visible: lib.pairingCode !== ""
                    width: parent.width
                    Text { text: Ui.tx("login.vhod_cherez_discord"); color: Theme.text; font.family: Theme.unbounded; font.weight: Font.Medium; font.pixelSize: 20 }
                    Text { topPadding: 4; bottomPadding: 14; text: Ui.tx("login.podtverdite_v_brauzere"); color: Theme.muted; font.family: Theme.onest; font.pixelSize: 14 }
                    Rectangle {
                        width: parent.width; height: code.implicitHeight + 32
                        radius: 12; color: "#1E2330"; border.color: "#802563EB"
                        Text { id: code; anchors.centerIn: parent; text: lib.pairingCode; color: "white"; font.family: Theme.unbounded; font.weight: Font.Bold; font.pixelSize: 32; font.letterSpacing: 4 }
                    }
                    Item { width: 1; height: 14 }
                    Row {
                        width: parent.width
                        spacing: 10
                        LampButton { width: parent.width - cancel.width - 10; text: Ui.tx("login.otkryt_v_brauzere"); onClicked: lib.openPairingPage() }
                        OutlineButton { id: cancel; text: Ui.tx("login.otmena"); onClicked: lib.cancelDeviceAuth() }
                    }
                }
                Column {
                    visible: lib.pairingCode === ""
                    width: parent.width
                    Text { text: Ui.tx("library.voyti"); color: Theme.text; font.family: Theme.unbounded; font.weight: Font.Medium; font.pixelSize: 20 }
                    Item { width: 1; height: 12 }
                    LampButton {
                        width: parent.width
                        text: Ui.tx("login.voyti_cherez_discord")
                        enabled_: !v.busy
                        // прежняя ошибка (например, истёкший код) к новому коду не относится
                        onClicked: { v.busy = true; v.error = ""; lib.startDeviceAuth() }
                    }
                    Item { width: 1; height: 16 }
                    Row {
                        width: parent.width
                        Rectangle { width: (parent.width - or.width) / 2; height: 1; color: "#1AFFFFFF"; anchors.verticalCenter: parent.verticalCenter }
                        Text { id: or; leftPadding: 10; rightPadding: 10; text: Ui.tx("login.ili_po_parolyu"); color: Theme.muted; font.family: Theme.onest; font.pixelSize: 12 }
                        Rectangle { width: (parent.width - or.width) / 2; height: 1; color: "#1AFFFFFF"; anchors.verticalCenter: parent.verticalCenter }
                    }
                    Item { width: 1; height: 14 }
                    Field { id: pass; width: parent.width; placeholder: Ui.tx("library.parol_dostupa"); password: true; onAccepted: v.submit() }
                    Item { width: 1; height: 12 }
                    OutlineButton { width: parent.width; text: v.busy ? Ui.tx("library.vypolnyaetsya_vhod") : Ui.tx("library.voyti"); onClicked: v.submit() }
                }
                Text {
                    visible: v.error !== ""
                    width: parent.width
                    topPadding: 10
                    text: v.error
                    color: Theme.bad
                    font.family: Theme.onest; font.pixelSize: 14
                    wrapMode: Text.Wrap
                }
            }
        }
    }

    function submit() {
        if (busy || pass.text.trim() === "") return
        busy = true; error = ""
        lib.login(pass.text)
    }
}
