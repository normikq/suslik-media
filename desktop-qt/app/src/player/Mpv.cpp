#include "Mpv.h"
#include "../AppDir.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDir>
#include <QFileInfo>
#include <QLibrary>
#include <QMutex>

namespace suslik::app::mpv {

namespace {
QMutex lock;
Api loaded{};
bool ok = false;
QString file;
qint64 lastMiss = 0; ///< неудачный поиск: следующий не раньше чем через 2 с (QML спрашивает часто)

template <class F>
bool resolve(QLibrary &lib, F &out, const char *name) {
    out = reinterpret_cast<F>(lib.resolve(name));
    return out != nullptr;
}
} // namespace

QString libraryName() {
#ifdef Q_OS_WIN
    return QStringLiteral("libmpv-2.dll");
#elif defined(Q_OS_MACOS)
    return QStringLiteral("libmpv.2.dylib");
#else
    return QStringLiteral("libmpv.so.2");
#endif
}

QStringList searchDirs() {
    QStringList dirs{QCoreApplication::applicationDirPath(), QDir(AppDir::dir()).filePath(QStringLiteral("mpv"))};
    if (const QString env = qEnvironmentVariable("SUSLIK_MPV_DIR"); !env.isEmpty()) dirs << env;
    return dirs;
}

const Api *api() {
    QMutexLocker g(&lock);
    if (ok) return &loaded;
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    if (lastMiss && now - lastMiss < 2000) return nullptr;
    lastMiss = now;
    QStringList tries;
    for (const QString &d : searchDirs())
        if (QFileInfo::exists(QDir(d).filePath(libraryName()))) tries << QDir(d).filePath(libraryName());
    tries << libraryName(); // системные пути (PATH, ld.so)
    for (const QString &path : tries) {
        auto *lib = new QLibrary(path); // не выгружаем до конца работы
        if (!lib->load()) { delete lib; continue; }
        Api a{};
        const bool all = resolve(*lib, a.create, "mpv_create") && resolve(*lib, a.initialize, "mpv_initialize")
            && resolve(*lib, a.terminate_destroy, "mpv_terminate_destroy") && resolve(*lib, a.set_option_string, "mpv_set_option_string")
            && resolve(*lib, a.set_property_string, "mpv_set_property_string") && resolve(*lib, a.set_property_async, "mpv_set_property_async")
            && resolve(*lib, a.get_property_string, "mpv_get_property_string") && resolve(*lib, a.get_property, "mpv_get_property")
            && resolve(*lib, a.free, "mpv_free") && resolve(*lib, a.free_node_contents, "mpv_free_node_contents") && resolve(*lib, a.command, "mpv_command")
            && resolve(*lib, a.command_async, "mpv_command_async") && resolve(*lib, a.observe_property, "mpv_observe_property")
            && resolve(*lib, a.wait_event, "mpv_wait_event") && resolve(*lib, a.set_wakeup_callback, "mpv_set_wakeup_callback")
            && resolve(*lib, a.error_string, "mpv_error_string")
            && resolve(*lib, a.render_context_create, "mpv_render_context_create")
            && resolve(*lib, a.render_context_set_update_callback, "mpv_render_context_set_update_callback")
            && resolve(*lib, a.render_context_update, "mpv_render_context_update")
            && resolve(*lib, a.render_context_render, "mpv_render_context_render")
            && resolve(*lib, a.render_context_report_swap, "mpv_render_context_report_swap")
            && resolve(*lib, a.render_context_free, "mpv_render_context_free");
        if (!all) { lib->unload(); delete lib; continue; }
        loaded = a;
        ok = true;
        lastMiss = 0;
        file = QDir::toNativeSeparators(lib->fileName());
        return &loaded;
    }
    return nullptr;
}

void rescan() {
    QMutexLocker g(&lock);
    lastMiss = 0;
}

QString libraryFile() {
    QMutexLocker g(&lock);
    return file;
}

} // namespace suslik::app::mpv
