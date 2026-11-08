#pragma once
#include <QString>

namespace suslik::app { class MpvEngine; }

/// Эффект кинопроектора в mpv (пресеты — ProjectorLook): шейдер пресета пишется из ресурсов в
/// %APPDATA%\SuslikCinema\shaders\<id>.glsl (mpv берёт шейдеры только файлами) и ставится в glsl-shaders.
/// Шейдеры обычные для mpv (HOOK MAIN/OUTPUT), работают и с vo=libmpv, которым рисует хост.
namespace suslik::cinema {

class Projector {
public:
    explicit Projector(app::MpvEngine &mpv) : m_mpv(mpv) {}

    /// Сменить пресет (0 — выкл). Ставит сразу, если mpv уже создан.
    void setPreset(int preset);
    int preset() const { return m_preset; }
    /// Поставить снова (после открытия файла: на случай нового mpv).
    void install();

private:
    QString shaderFile(int preset) const;

    app::MpvEngine &m_mpv;
    int m_preset = 0;
};

} // namespace suslik::cinema
