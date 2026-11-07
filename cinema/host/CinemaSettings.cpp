// MpvEngine из приложения ПК берёт буфер из настроек плеера (Settings.cpp тянет за собой всё приложение).
// У хоста кинотеатра своих настроек пока нет: буфер по умолчанию, как у новой установки ПК-версии.
#include "Settings.h"

#include "suslik/core/PlaybackPresets.h"

namespace suslik::app::PlaybackSettings {

int cacheMs() { return core::PlaybackPresets::CACHE_DEFAULT; }

} // namespace suslik::app::PlaybackSettings
