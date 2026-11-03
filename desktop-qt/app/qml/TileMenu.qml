import QtQuick

// Меню фильма по правой кнопке — одно для плитки (Tile) и строки списка (ListRow): сезон сериала, «Мои фильмы»,
// избранное, ИИ, удаление.
SmMenu {
    id: menu
    property var t: ({})
    property var page
    // сезоны сериала: плитка показывает и включает выбранный
    Instantiator {
        model: menu.t.seasons || []
        delegate: SmMenuItem { required property int index; required property string modelData; text: modelData; onTriggered: menu.page.chooseSeason(menu.t.id, index) }
        onObjectAdded: (i, o) => menu.insertItem(i, o)
        onObjectRemoved: (i, o) => menu.removeItem(o)
    }
    SmMenuItem { text: menu.t.mineLabel || ""; onTriggered: menu.page.setMineGroup(menu.t.id, !menu.t.mine) }
    SmMenuItem { text: menu.t.favLabel || ""; onTriggered: menu.page.setFavoriteGroup(menu.t.id, !menu.t.favorite) }
    SmMenuItem {
        shown: !!menu.t.aiAsk
        text: menu.t.aiAsk || ""; enabled: !!menu.t.aiAskEnabled
        onTriggered: menu.page.askAi(menu.t.id)
    }
    SmMenuItem {
        shown: !!menu.t.aiKnown
        text: menu.t.aiKnown || ""
        onTriggered: menu.page.showAi(menu.t.id)
    }
    SmMenuItem {
        shown: !!menu.t.canRemove
        text: menu.t.removeLabel || ""; fg: Theme.bad
        onTriggered: lib.remove(menu.t.id)
    }
}
