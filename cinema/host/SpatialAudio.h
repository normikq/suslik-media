#pragma once
#include "GameView.h"

/// 3D-звук экрана (идея TVKit, код не взят): звук фильма идёт от телевизора в мире игры.
///   * громкость с расстоянием: до ref (ширина экрана, не меньше 2 м) — полная, дальше (1 − x)^1.5 до hear
///     (6 ширин, не меньше 20 м), как у TVKit;
///   * направление: разница громкости ушей, у «объёмного» ещё тень головы (дальнее ухо без верхов) и глуше,
///     когда экран за спиной; вблизи большого экрана направление размывается (он вокруг);
///   * за стеной (Static): громкость ×0,4 и фильтр 700 Гц, как «Muffle» у TVKit.
/// Своего HRTF нет: в mpv нет набора HRIR без SOFA-файла, поэтому — уши по отдельности фильтрами lavfi
/// (volume и lowpass меняются на лету через af-command). Без окна и mpv — проверяется тестом SpatialAudioTest.
namespace suslik::cinema {

enum class Audio3D { Off = 0, Stereo = 1, Surround = 2 };

/// Что поставить в фильтры: громкость каждого уха (0…1), срез верхов каждого уха (Гц), общая громкость.
struct SpatialMix {
    double gainL = 1, gainR = 1;
    double lowpassL = kOpen, lowpassR = kOpen;
    double master = 1;
    static constexpr double kOpen = 20000; ///< фильтр «открыт»
    bool operator==(const SpatialMix &) const = default;
};

/// cam — камера игры, center — центр экрана, width — его ширина (м), wall — за стеной 0…1 (сглаженно).
SpatialMix spatialMix(const CameraPose &cam, V3 center, double width, double wall, Audio3D mode, bool muffle);

/// Сглаживание к цели за один шаг (k — доля пути): громкости — линейно, частоты — по логарифму (на слух ровно).
SpatialMix approach(const SpatialMix &cur, const SpatialMix &target, double k);

} // namespace suslik::cinema
