#pragma once
#include <QHash>
#include <QString>
#include <initializer_list>
#include <utility>

/// Строки интерфейса: плоские ключи с подстановками `{имя}` (strings/ru.json, тот же файл, что у Kotlin-версии).
/// Недостающий в переводе ключ берётся из русского; ключа нет и там — показывается сам ключ.
namespace suslik::core {

class Strings {
public:
    /// base — ru.json, current — перевод (может быть пустым). Битый JSON — пустая таблица.
    static void load(const QByteArray &base, const QByteArray &current = {});
    static QString get(const QString &key, std::initializer_list<std::pair<const char *, QString>> params = {});

private:
    static QHash<QString, QString> &base();
    static QHash<QString, QString> &current();
};

/// tx("player.next", {{"title", name}}) ← «Следующая: {title}» (как tr() у Kotlin-версии; tr занят у QObject).
inline QString tx(const QString &key, std::initializer_list<std::pair<const char *, QString>> params = {}) { return Strings::get(key, params); }

} // namespace suslik::core
