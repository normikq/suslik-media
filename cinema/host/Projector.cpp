#include "Projector.h"
#include "ProjectorLook.h"

#include "AppDir.h"
#include "player/Mpv.h"
#include "player/MpvEngine.h"
#include "player/PlayerLog.h"

#include <QDir>
#include <QFile>
#include <QSaveFile>

namespace suslik::cinema {

QString Projector::shaderFile(int preset) const {
    const ProjectorPreset &p = projectorPresets().at(preset);
    if (!*p.base) return {};
    QFile src(QStringLiteral(":/grainlight/") + QString::fromLatin1(p.base));
    if (!src.open(QIODevice::ReadOnly)) {
        app::PlayerLog::write(QStringLiteral("[projector] нет ресурса %1").arg(src.fileName()));
        return {};
    }
    const QByteArray text = patchDefines(src.readAll(), p.defines);
    const QString dir = app::AppDir::file(QStringLiteral("shaders"));
    QDir().mkpath(dir);
    const QString path = QDir(dir).filePath(QString::fromLatin1(p.id) + QStringLiteral(".glsl"));
    // пишем, только если изменился: mpv перечитывает файл при каждой установке
    QFile cur(path);
    if (cur.open(QIODevice::ReadOnly) && cur.readAll() == text) return QDir::toNativeSeparators(path);
    cur.close();
    QSaveFile out(path);
    if (!out.open(QIODevice::WriteOnly) || out.write(text) != text.size() || !out.commit()) {
        app::PlayerLog::write(QStringLiteral("[projector] не записать %1").arg(path));
        return {};
    }
    return QDir::toNativeSeparators(path);
}

void Projector::setPreset(int preset) {
    preset = projectorClamp(preset);
    if (preset == m_preset) return;
    m_preset = preset;
    app::PlayerLog::write(QStringLiteral("[projector] %1").arg(QString::fromUtf8(projectorPresets().at(preset).title)));
    install();
}

void Projector::install() {
    const app::mpv::Handle h = m_mpv.current();
    const app::mpv::Api *a = app::mpv::api();
    if (!h || !a) return;
    const QString path = shaderFile(m_preset);
    // очистить и добавить одним элементом (append не делит строку по разделителю списка)
    const char *clr[] = {"change-list", "glsl-shaders", "clr", "", nullptr};
    int rc = a->command(h.get(), clr);
    if (rc >= 0 && !path.isEmpty()) {
        const QByteArray p = path.toUtf8();
        const char *add[] = {"change-list", "glsl-shaders", "append", p.constData(), nullptr};
        rc = a->command(h.get(), add);
    }
    if (rc < 0) app::PlayerLog::write(QStringLiteral("[projector] шейдер не встал: %1").arg(QString::fromUtf8(a->error_string(rc))));
}

} // namespace suslik::cinema
