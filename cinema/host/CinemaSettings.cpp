// Плеер и запас из приложения ПК (MpvEngine, Reserve) берут настройки отсюда: Settings.cpp ПК-версии тянет за собой
// всё приложение. Хранилище «settings» — в папке данных хоста (%APPDATA%\SuslikCinema), ключи те же, что у ПК-версии.
#include "Settings.h"

#include "suslik/core/PlaybackPresets.h"
#include "suslik/core/Store.h"

#include <QDir>

namespace suslik::app {

namespace {
std::optional<int> intOf(const char *key) {
    bool ok = false;
    const int v = core::Store::of(QStringLiteral("settings")).get(QString::fromLatin1(key)).value_or(QString()).toInt(&ok);
    return ok ? std::optional<int>(v) : std::nullopt;
}
} // namespace

/// Свой запас, отдельно от ПК-версии: обе программы могут работать сразу.
QString reserveDir() { return QDir::temp().filePath(QStringLiteral("SuslikCinema-stream")); }

namespace PlaybackSettings {
int burstMb() { return core::PlaybackPresets::clampBurst(intOf("burst_mb").value_or(core::PlaybackPresets::BURST_DEFAULT)); }
int cacheMs() { return intOf("cache_ms").value_or(core::PlaybackPresets::CACHE_DEFAULT); }
} // namespace PlaybackSettings

} // namespace suslik::app
