#pragma once
#include "suslik/core/Search.h"

#include <QByteArray>
#include <QList>
#include <QObject>
#include <QString>
#include <functional>

/// RuTracker прямо с этого компьютера (LocalTrackers.kt: RuTrackerTracker): сначала фоновый браузер
/// (BrowserBridge — проходит Cloudflare), без него или когда он не ответил — прямой HTTP со своими cookie.
/// Настройки и разбор страниц — core::RuTracker. Ответы приходят в главный поток, пока жив ctx.
namespace suslik::app::rutracker {

/// Приписка к «Авторизован как …»: через какой браузер идут проверка и поиск и из какого взяты cookie.
QString browserNote();

/// Вошли ли на трекер: (true, «Авторизован как …») или (false, причина). Обновлённые браузером cookie сохраняются.
void testConnection(QObject *ctx, std::function<void(bool ok, QString message)> cb);

/// Поиск; пустой запрос или RuTracker не готов — пустой список. error не пуст — трекер не ответил.
/// Ничего не нашлось и в запросе есть год — повтор без года, сначала раздачи этого года.
void search(const QString &query, QObject *ctx, std::function<void(QList<core::SearchHit> hits, QString error)> cb);

/// .torrent раздачи (bencode); пусто — не вышло.
void downloadTorrent(const QString &topicId, QObject *ctx, std::function<void(QByteArray)> cb);

} // namespace suslik::app::rutracker
