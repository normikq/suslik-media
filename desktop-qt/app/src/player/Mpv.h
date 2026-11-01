#pragma once
#include <mpv/client.h>
#include <mpv/render_gl.h>

#include <QString>
#include <memory>

/// libmpv, загруженная при запуске фильма (MpvLib.kt): libmpv-2.dll не входит в программу — её кладёт установщик или
/// докачивает MpvInstall. Ищем рядом с программой, в папке данных (mpv\), в SUSLIK_MPV_DIR и по системным путям.
namespace suslik::app::mpv {

struct Api {
    decltype(&::mpv_create) create;
    decltype(&::mpv_initialize) initialize;
    decltype(&::mpv_terminate_destroy) terminate_destroy;
    decltype(&::mpv_set_option_string) set_option_string;
    decltype(&::mpv_set_property_string) set_property_string;
    decltype(&::mpv_set_property_async) set_property_async;
    decltype(&::mpv_get_property_string) get_property_string;
    decltype(&::mpv_get_property) get_property;
    decltype(&::mpv_free) free;
    decltype(&::mpv_free_node_contents) free_node_contents;
    decltype(&::mpv_command) command;
    decltype(&::mpv_command_async) command_async;
    decltype(&::mpv_observe_property) observe_property;
    decltype(&::mpv_wait_event) wait_event;
    decltype(&::mpv_set_wakeup_callback) set_wakeup_callback;
    decltype(&::mpv_error_string) error_string;
    decltype(&::mpv_render_context_create) render_context_create;
    decltype(&::mpv_render_context_set_update_callback) render_context_set_update_callback;
    decltype(&::mpv_render_context_update) render_context_update;
    decltype(&::mpv_render_context_render) render_context_render;
    decltype(&::mpv_render_context_report_swap) render_context_report_swap;
    decltype(&::mpv_render_context_free) render_context_free;
};

/// nullptr — библиотеки нет (каждый вызов ищет снова: её могли докачать).
const Api *api();
/// Библиотеку только что положили: следующий api() ищет сразу, без паузы между поисками.
void rescan();
/// Где лежит загруженная библиотека (пусто — не загружена).
QString libraryFile();
/// Папки, где ищем, по порядку.
QStringList searchDirs();
/// Имя файла библиотеки на этой системе.
QString libraryName();

/// Ядро mpv: освобождается, когда его отпустят все (движок и вывод кадров). Render context обязан уйти раньше ядра —
/// иначе mpv роняет процесс (0xC0000409), поэтому вывод держит свою ссылку, пока не освободит его.
using Handle = std::shared_ptr<mpv_handle>;

} // namespace suslik::app::mpv
