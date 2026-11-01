#pragma once
#include <QList>
#include <QString>
#include <QStringList>
#include <optional>

/// «Что нового»: changelog.json приложения (новые сверху) — разбор и выбор, что показать. Перенос Changelog.kt.
namespace suslik::core::Changelog {

struct Release {
    QString version;
    QString title;
    QStringList items;
    bool operator==(const Release &) const = default;
};

/// Записи без версии или без пунктов отбрасываются; битый JSON — пустой список.
QList<Release> parse(const QByteArray &json);

/// Последние count выпусков до установленной версии current, новые первыми.
QList<Release> upTo(const QList<Release> &all, const QString &current, int count = 3);

/// Показывать окно после обновления? Только если есть заметки новее уже показанных (lastSeen пусто — ещё не показывали).
bool shouldShow(const QList<Release> &releases, const std::optional<QString> &lastSeen);

} // namespace suslik::core::Changelog
