#pragma once
#include <QByteArray>
#include <QColor>
#include <QHash>
#include <QImage>
#include <QList>
#include <QPair>
#include <QString>
#include <QRect>
#include <QVector3D>

/// «Вписать в сцену»: свет игры вокруг экрана -> как показать на нём кадр. Только математика (без окна и
/// захвата экрана — его делает SceneProbe), тест SceneLightTest.
///
/// Оверлей рисуется поверх готового кадра игры, поэтому подстраивается сам: SceneProbe дважды в секунду снимает
/// уменьшенную область вокруг экрана, measureScene() считает средний свет (без самого экрана с отсветом и без самых
/// ярких точек — HUD, лампы), smoothScene() сглаживает, sceneLookFor() даёт затемнение кадра, цвет и долю света зала
/// на полотне и силу отсвета. Постоянная часть (плечо светов, мягкость) — шейдер scene/in-world.glsl.
namespace suslik::cinema {

struct SceneSample {
    QVector3D lin;      ///< средний цвет в линейном свете (0…1)
    double luma = 0;    ///< яркость (Rec.709) в линейном свете
    int pixels = 0;     ///< сколько точек посчитано; 0 — замер негодный
    bool valid() const { return pixels > 0; }
};

struct SceneLook {
    double dim = 1.0;        ///< множитель яркости кадра (в гамме), 1 — как есть
    QColor lift;             ///< цвет света зала на полотне (sRGB)
    double liftAmount = 0;   ///< доля подмешивания света зала (0…1)
    double glow = 0;         ///< прозрачность отсвета экрана вокруг полотна
};

/// Средний свет снимка вне exclude (в пикселях img). Самые яркие 10 % точек не считаются.
/// Меньше 16 точек вне exclude — замер негодный.
SceneSample measureScene(const QImage &img, const QRect &exclude);

/// Сглаживание к next за dt секунд с постоянной tau (экспонента). Негодный next — prev без изменений,
/// негодный prev — сразу next.
SceneSample smoothScene(const SceneSample &prev, const SceneSample &next, double dt, double tau);

/// Как показать кадр при таком свете: тёмный зал — кадр приглушён, отсвет сильный, свет зала почти не виден;
/// светлая сцена — кадр как есть, отсвет слабый, полотно заметно «засвечено» светом вокруг.
SceneLook sceneLookFor(const SceneSample &s);

/// До первого замера (и если захват не вышел): тёмный зал.
SceneSample defaultScene();

/// Подстройка картинки с телефона (вкладка «Картинка» пульта): ползунок -> множитель или сдвиг поверх значений
/// шейдера «в зале» (USER_* в scene/in-world.glsl, для SDR и HDR одинаково) и подстройки под зал (SceneProbe, Main.qml).
struct SceneKnob {
    const char *id;     ///< ключ в настройках (remote_look_<id>), команде opt{key: look, id, v} и статусе
    const char *title;  ///< подпись на телефоне
    double min, max, def;
    const char *define; ///< USER_* в шейдере или пусто (подстройка в хосте)
};
const QList<SceneKnob> &sceneKnobs();
/// Ползунок по ключу или nullptr.
const SceneKnob *sceneKnob(const QString &id);
/// Значение в пределах ползунка; не число / неизвестный ключ — значение по умолчанию (неизвестный — 0).
double sceneKnobClamp(const QString &id, double v);

/// Значения ползунков (ключ -> число; нет ключа — по умолчанию).
using SceneTuning = QHash<QString, double>;
double tuningValue(const SceneTuning &t, const char *id);
/// #define USER_* для шейдера «в зале» (все ползунки с define, по три знака после запятой).
QList<QPair<QByteArray, QByteArray>> sceneUserDefines(const SceneTuning &t);
/// Подстройка под зал с ползунков: adapt — глубина затемнения (1 — как есть, 0 — без него), lift — свет зала,
/// spill — сила отсвета. Остальные поля look — как были.
SceneLook tunedLook(const SceneLook &look, const SceneTuning &t);

} // namespace suslik::cinema
