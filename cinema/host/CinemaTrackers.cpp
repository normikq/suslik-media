// app::Party из ПК-версии отвечает на просьбы Discord Activity поискать на RuTracker через этот ПК (с его cookie).
// У кинотеатра трекеров нет (браузерный мост ПК-версии — тысяча строк и WebSockets): поиск и .torrent честно отказывают,
// участник видит «ПК не смог…», а не тишину.
#include "trackers/RuTrackerClient.h"

namespace suslik::app::rutracker {

QString browserNote() { return {}; }

void testConnection(QObject *, std::function<void(bool, QString)> cb) {
    cb(false, QStringLiteral("Кинотеатр не ищет на RuTracker"));
}

void search(const QString &, QObject *, std::function<void(QList<core::SearchHit>, QString)> cb) {
    cb({}, QStringLiteral("Этот ПК смотрит фильм в Cyberpunk 2077: поиск RuTracker через него недоступен."));
}

void downloadTorrent(const QString &, QObject *, std::function<void(QByteArray)> cb) { cb({}); }

} // namespace suslik::app::rutracker
