#pragma once
#include "suslik/core/Model.h"
#include "suslik/core/Party.h"

#include <QList>
#include <QString>
#include <QStringList>
#include <optional>

/// Выбор релиза фильма и проверка адреса зеркала (этап 1, docs/CYBERPUNK.md). Без сети и окна — для тестов.
namespace suslik::cinema {

/// Вариант качества фильма: раздача-версия, файл в ней и описание (buildPartyVariant, как в плеере ПК-версии).
struct Choice {
    core::LibraryItem version;
    core::MFile file;
    core::PartyVariant variant;
};

/// Явный id раздачи, явное качество («4K», «1080p» или часть подписи), сохранённое качество пользователя
/// (vquality — то же правило, что у PlayerModel::playEntry), иначе первый (variants отсортированы по score).
/// Пусто — вариантов нет или явный выбор не нашёлся.
std::optional<int> pickVariant(const QList<Choice> &variants, qint64 release, const QString &quality,
                               const QStringList &savedQualities);

/// Адрес годится, только если это наш сервер (https, путь сервера) и /api/file/proxy/: никаких прямых ссылок AllDebrid.
bool isMirrorUrl(const QString &url, const QString &base);

} // namespace suslik::cinema
