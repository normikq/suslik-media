#pragma once
#include "suslik/core/Model.h"

#include <QSet>

/// Что ещё принадлежит медиатеке после обновления: всё, что приложение хранит о пропавших фильмах (постеры, строка
/// о видео, прогресс, последняя серия, запас на диске), можно удалить. Ключи: "magnetId|path", "w:magnetId|path",
/// "magnetId"; постеры — по запросу плитки в нижнем регистре; запас — по ссылке AllDebrid. Ключ без номера раздачи
/// (поток по прямой ссылке) остаётся. Перенос shared/.../LibraryKeep.kt.
namespace suslik::core {

class LibraryKeep {
public:
    explicit LibraryKeep(const QList<LibraryItem> &items) {
        for (const LibraryItem &i : items) {
            ids.insert(i.m.id);
            for (const QString &q : i.posterQueries) posterKeys.insert(q.toLower());
            for (const MFile &f : i.m.files)
                if (f.link) links.insert(*f.link);
        }
    }
    QSet<qint64> ids;
    QSet<QString> posterKeys;
    QSet<QString> links;

    /// Пустому списку при чистке не верим (сбойный или неполный ответ не должен стереть всё).
    bool usable() const { return !ids.isEmpty(); }

    /// Ключ фильма ("magnetId|path", "w:magnetId|path", "magnetId"): оставить?
    bool keepsKey(const QString &key) const {
        QString k = key.startsWith(QLatin1String("w:")) ? key.mid(2) : key;
        const auto bar = k.indexOf(QLatin1Char('|'));
        bool ok = false;
        const qint64 id = (bar < 0 ? k : k.left(bar)).toLongLong(&ok);
        return !ok || ids.contains(id);
    }

    /// Ключ постера (запрос в нижнем регистре): оставить?
    bool keepsPoster(const QString &key) const { return posterKeys.contains(key); }
};

} // namespace suslik::core
