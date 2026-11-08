#pragma once
#include "SceneLight.h"

#include <QString>

namespace suslik::app { class MpvEngine; }

/// Эффект кинопроектора в mpv (пресеты — ProjectorLook): шейдер пресета пишется из ресурсов в
/// %APPDATA%\SuslikCinema\shaders\<id>.glsl (mpv берёт шейдеры только файлами) и ставится в glsl-shaders.
/// Шейдеры обычные для mpv (HOOK MAIN/OUTPUT), работают и с vo=libmpv, которым рисует хост.
/// После пресета всегда ставится «в зале» (scene/in-world.glsl): оверлей рисуется поверх готового кадра игры,
/// и без него фильм ярче, контрастнее и резче сцены — выглядит приклеенным.
namespace suslik::cinema {

class Projector {
public:
    explicit Projector(app::MpvEngine &mpv) : m_mpv(mpv) {}

    /// Сменить пресет (0 — выкл). Ставит сразу, если mpv уже создан.
    void setPreset(int preset);
    int preset() const { return m_preset; }
    /// «Вписать в сцену» (пульт): ставить ли шейдер «в зале».
    void setScene(bool on);
    /// Фильм в HDR (PQ/HLG): шейдер «в зале» с другими значениями (sceneShaderDefines).
    void setHdr(bool on);
    /// Подстройка с телефона (вкладка «Картинка»): USER_* шейдера «в зале». Ставит снова, только если они поменялись.
    void setTuning(const SceneTuning &t);
    /// Поставить снова (после открытия файла: на случай нового mpv).
    void install();

private:
    QString shaderFile(int preset) const;
    /// Шейдер «в зале» (scene/in-world.glsl): экспозиция, мягкое плечо светов, подъём чёрного светом зала,
    /// мягкость как у TAA игры — всегда после пресета.
    QString sceneShaderFile() const;

    app::MpvEngine &m_mpv;
    int m_preset = 0;
    bool m_scene = true;
    bool m_hdr = false;
    QList<QPair<QByteArray, QByteArray>> m_user = sceneUserDefines({});
};

} // namespace suslik::cinema
