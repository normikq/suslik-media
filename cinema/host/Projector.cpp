#include "Projector.h"
#include "ProjectorLook.h"

#include "AppDir.h"
#include "player/Mpv.h"
#include "player/MpvEngine.h"
#include "player/PlayerLog.h"

#include <QCryptographicHash>
#include <QDir>
#include <QFile>
#include <QSaveFile>

namespace suslik::cinema {

namespace {

QString writeShader(const QString &name, const QByteArray &text) {
    const QString dir = app::AppDir::file(QStringLiteral("shaders"));
    QDir().mkpath(dir);
    // mpv держит прочитанные шейдеры в кэше по пути до конца работы плеера: файл с тем же именем и новым текстом
    // он не перечитывает (08.11 — ползунки «Картинки» не действовали). Поэтому имя — по содержимому:
    // <name>-<8 знаков SHA-1>.glsl; прежние версии этого шейдера удаляются (mpv их уже прочитал)
    const QString hash = QString::fromLatin1(QCryptographicHash::hash(text, QCryptographicHash::Sha1).toHex().left(8));
    const QString file = name + QLatin1Char('-') + hash + QStringLiteral(".glsl");
    const QString path = QDir(dir).filePath(file);
    const QStringList old = QDir(dir).entryList({name + QStringLiteral(".glsl"), name + QStringLiteral("-????????.glsl")}, QDir::Files);
    for (const QString &f : old)
        if (f != file) QFile::remove(QDir(dir).filePath(f));
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

} // namespace

QString Projector::shaderFile(int preset) const {
    const ProjectorPreset &p = projectorPresets().at(preset);
    if (!*p.base) return {};
    QFile src(QStringLiteral(":/grainlight/") + QString::fromLatin1(p.base));
    if (!src.open(QIODevice::ReadOnly)) {
        app::PlayerLog::write(QStringLiteral("[projector] нет ресурса %1").arg(src.fileName()));
        return {};
    }
    const QByteArray text = hookAtOutput(patchDefines(src.readAll(), p.defines));
    return writeShader(QString::fromLatin1(p.id), text);
}

QString Projector::sceneShaderFile() const {
    QFile src(QStringLiteral(":/scene/in-world.glsl"));
    if (!src.open(QIODevice::ReadOnly)) {
        app::PlayerLog::write(QStringLiteral("[projector] нет ресурса %1").arg(src.fileName()));
        return {};
    }
    return writeShader(m_hdr ? QStringLiteral("in-world-hdr") : QStringLiteral("in-world"),
                       patchDefines(patchDefines(src.readAll(), sceneShaderDefines(m_hdr)), m_user));
}

void Projector::setPreset(int preset) {
    preset = projectorClamp(preset);
    if (preset == m_preset) return;
    m_preset = preset;
    app::PlayerLog::write(QStringLiteral("[projector] %1").arg(QString::fromUtf8(projectorPresets().at(preset).title)));
    install();
}

void Projector::setScene(bool on) {
    if (on == m_scene) return;
    m_scene = on;
    install();
}

void Projector::setHdr(bool on) {
    if (on == m_hdr) return;
    m_hdr = on;
    app::PlayerLog::write(QStringLiteral("[projector] «в зале» для %1").arg(on ? QStringLiteral("HDR") : QStringLiteral("SDR")));
    install();
}

void Projector::setTuning(const SceneTuning &t) {
    auto user = sceneUserDefines(t);
    if (user == m_user) return;
    m_user = std::move(user);
    if (m_scene) install();
}

void Projector::install() {
    const app::mpv::Handle h = m_mpv.current();
    const app::mpv::Api *a = app::mpv::api();
    if (!h || !a) return;

    // очистить и добавить одним элементом (append не делит строку по разделителю списка)
    const char *clr[] = {"change-list", "glsl-shaders", "clr", "", nullptr};
    int rc = a->command(h.get(), clr);

    const QString presetPath = shaderFile(m_preset);
    if (rc >= 0 && !presetPath.isEmpty()) {
        const QByteArray p = presetPath.toUtf8();
        const char *add[] = {"change-list", "glsl-shaders", "append", p.constData(), nullptr};
        rc = a->command(h.get(), add);
    }

    // «в зале» — последним: вписывает кадр (и эффект проектора) в свет игры; выключается с пульта
    const QString scenePath = m_scene ? sceneShaderFile() : QString();
    if (rc >= 0 && !scenePath.isEmpty()) {
        const QByteArray p = scenePath.toUtf8();
        const char *add[] = {"change-list", "glsl-shaders", "append", p.constData(), nullptr};
        rc = a->command(h.get(), add);
    }

    if (rc < 0) app::PlayerLog::write(QStringLiteral("[projector] шейдер не встал: %1").arg(QString::fromUtf8(a->error_string(rc))));
}

} // namespace suslik::cinema
