#pragma once
#include "suslik/core/BurstProxy.h"

namespace suslik::core { class ApiClient; }

/// Запас на диске (общий BurstProxy) для этого приложения (Reserve.kt): папка во временных файлах, загрузка
/// диапазонов через HttpUpstream (Qt Network), журнал плеера, новая подпись зеркала через сервер.
namespace suslik::app::Reserve {

/// Один на программу; при первом обращении — папка и загрузчик. api нужен для новой подписи (12 ч).
core::BurstProxy &proxy(core::ApiClient *api = nullptr);

/// Прочитать первые bytes байт адреса и выбросить (сервер и файловый хостинг готовятся к настоящему запросу).
void warmUp(const QString &url, qint64 bytes);
/// Начало и последние 512 КБ (индекс MKV) внешней озвучки через запас — для всех озвучек сразу (warmSidecar).
void warmSidecar(const QString &localUrl);

} // namespace suslik::app::Reserve
