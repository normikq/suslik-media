#pragma once
#include <QList>
#include <QString>
#include <algorithm>

/// «Настройки»: варианты и подсказки, одинаковые во всех приложениях. Перенос shared/.../PlaybackPresets.kt.
namespace suslik::core::PlaybackPresets {

struct Preset {
    int value;
    QString title;
    QString hint;
};

/// Размер запаса на фильм, МБ (0 — выкл.: плеер читает зеркало напрямую). where — «на телефоне» / «на диске».
inline QList<Preset> burst(const QString &where) {
    return {
        {0, QStringLiteral("Выкл"), QStringLiteral("Как раньше: фильм идёт напрямую, без запаса %1.").arg(where)},
        {32, QStringLiteral("32 МБ"), QStringLiteral("SD и 720p (до ~4 Мбит/с, файлы до ~2 ГБ). Когда %1 мало места.").arg(where)},
        {64, QStringLiteral("64 МБ"), QStringLiteral("Обычный 1080p (до ~8 Мбит/с, файлы 2–6 ГБ). Примерно минута запаса.")},
        {96, QStringLiteral("96 МБ"), QStringLiteral("1080p хорошего качества (до ~15 Мбит/с, файлы 6–12 ГБ). Подходит почти всем.")},
        {128, QStringLiteral("128 МБ"), QStringLiteral("1080p Remux и 4K (от ~15 Мбит/с, файлы от 12 ГБ).")},
        {160, QStringLiteral("160 МБ"), QStringLiteral("Тяжёлый 4K и Remux (до ~30 Мбит/с, файлы 20–40 ГБ). Большой запас при нестабильной связи.")},
        {256, QStringLiteral("256 МБ"), QStringLiteral("Максимальный запас: файлы от 40–50 ГБ. Качает большими порциями, выдерживает долгие обрывы связи.")},
    };
}
inline constexpr int BURST_DEFAULT = 96;
/// Не больше этого на фильм.
inline constexpr int BURST_MAX = 256;
inline int clampBurst(int mb) { return std::clamp(mb, 0, BURST_MAX); }

/// Собственный буфер плеера, мс: сколько он держит перед началом (старт / после перемотки).
inline QList<Preset> cache() {
    return {
        {1500, QStringLiteral("1,5 с"), QStringLiteral("Самый быстрый старт и перемотка. Только со стабильным интернетом или запасом.")},
        {3000, QStringLiteral("3 с"), QStringLiteral("Рекомендуется вместе с запасом: быстро стартует, провалы сети гасит запас.")},
        {6000, QStringLiteral("6 с"), QStringLiteral("Без запаса: выдерживает короткие провалы сети.")},
        {10000, QStringLiteral("10 с"), QStringLiteral("Слабый или нестабильный интернет: дольше старт, зато меньше остановок.")},
    };
}
inline constexpr int CACHE_DEFAULT = 3000;

inline QString bitrateHint() { return QStringLiteral("Подсказка: битрейт (Мбит/с) ≈ размер файла в ГБ × 2,2 ÷ длительность в часах."); }

} // namespace suslik::core::PlaybackPresets
