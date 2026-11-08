#pragma once
#include "GameView.h"

#include <array>

/// 3D-звук экрана (идея TVKit, код не взят): звук фильма идёт от телевизора в мире игры.
///   * громкость с расстоянием: до ref (ширина экрана, не меньше 2 м) — полная, дальше (1 − x)^1.5 до hear
///     (6 ширин, не меньше 20 м), как у TVKit;
///   * направление: разница громкости ушей, у «объёмного» ещё тень головы (дальнее ухо без верхов) и глуше,
///     когда экран за спиной; вблизи большого экрана направление размывается (он вокруг);
///   * за стеной (Static): громкость ×0,4 и фильтр 700 Гц, как «Muffle» у TVKit.
/// Своего HRTF нет: в mpv нет набора HRIR без SOFA-файла, поэтому — уши по отдельности фильтрами lavfi
/// (volume и lowpass меняются на лету через af-command). Без окна и mpv — проверяется тестом SpatialAudioTest.
namespace suslik::cinema {

/// Speakers — каждый канал фильма (5.1/7.1) звучит из своей колонки, расставленной в моде (раздел «Speakers»).
enum class Audio3D { Off = 0, Stereo = 1, Surround = 2, Speakers = 3 };

/// Что поставить в фильтры: громкость каждого уха (0…1), срез верхов каждого уха (Гц), общая громкость.
struct SpatialMix {
    double gainL = 1, gainR = 1;
    double lowpassL = kOpen, lowpassR = kOpen;
    double master = 1;
    static constexpr double kOpen = 20000; ///< фильтр «открыт»
    bool operator==(const SpatialMix &) const = default;
};

/// cam — камера игры, center — центр экрана, width — его ширина (м), wall — за стеной 0…1 (сглаженно).
/// distance = false — без спада громкости с расстоянием (колонки: громкость считает speakerMix по всей комнате).
SpatialMix spatialMix(const CameraPose &cam, V3 center, double width, double wall, Audio3D mode, bool muffle,
                      bool distance = true);

/// Сглаживание к цели за один шаг (k — доля пути): громкости — линейно, частоты — по логарифму (на слух ровно).
SpatialMix approach(const SpatialMix &cur, const SpatialMix &target, double k);

// ---------- колонки 5.1 / 7.1 ----------

/// Каналы в порядке FFmpeg для 7.1: FL FR FC LFE BL BR SL SR (так их отдаёт channelsplit).
enum Speaker { FL = 0, FR, FC, LFE, BL, BR, SL, SR };
constexpr int kSpeakers = 8;
/// "FL", "FR", … — так же их называет мод в screen.json и state.json.
const char *speakerName(int ch);
/// Уровень канала при сведении в уши (как стерео-сведение ITU): фронт 1, центр и тылы 0,707, сабвуфер 0,5.
double speakerLevel(int ch);

/// Колонки из мода и экран для запасного места.
struct SpeakerSet {
    std::array<bool, kSpeakers> has{};  ///< колонка поставлена
    std::array<V3, kSpeakers> pos{};
    std::array<double, kSpeakers> wall{}; ///< за стеной 0…1 (луч мода от камеры к колонке)
    bool screen = false;                ///< экран есть: на его место встают центр и фронт, если их не поставили
    V3 center;
    double width = 0, screenWall = 0;
};

/// Что поставить в фильтры одного канала: громкость каждого уха (вместе с уровнем канала и расстоянием) и срез верхов.
struct EarMix {
    double gainL = 1, gainR = 1;
    double lowpassL = SpatialMix::kOpen, lowpassR = SpatialMix::kOpen;
    bool operator==(const EarMix &) const = default;
};
struct SpeakerMix {
    std::array<EarMix, kSpeakers> ch{};
    bool operator==(const SpeakerMix &) const = default;
};

/// Откуда звучит канал после подстановок: тылы 5.1 ↔ боковые, центр — между фронтами или экран, фронт и тылы без
/// колонки — экран (onScreen). size — размер источника, м (колонка 0,3, экран — его ширина: вблизи звук вокруг).
/// false — места нет: канал звучит без направления.
bool speakerPlace(const SpeakerSet &set, int ch, V3 &pos, double &wall, double &size, bool &onScreen);
/// Комната колонок: центр поставленных колонок и радиус (до дальней, не меньше 2 м). false — колонок нет.
bool speakerRoom(const SpeakerSet &set, V3 &centre, double &radius);
/// Каждый канал — как отдельный источник «объёмного» режима (уши, тень головы, за спиной, за стеной); сабвуфер —
/// без направления. Громкость — как у настоящей системы: внутри комнаты колонок полная (ближняя колонка немного
/// громче, дальняя тише), дальше от её центра спадает (1 − x)^1.5. Каналы без колонки, ушедшие на экран, — по
/// правилам экрана. cam == nullptr (камеры нет) — сведение без направления.
SpeakerMix speakerMix(const CameraPose *cam, const SpeakerSet &set, bool muffle);
SpeakerMix approach(const SpeakerMix &cur, const SpeakerMix &target, double k);

} // namespace suslik::cinema
