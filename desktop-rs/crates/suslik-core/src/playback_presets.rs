//! «Настройки»: пресеты запаса и их подсказки, одинаковые в обоих приложениях. Повторяет `PlaybackPresets.kt`.

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Preset {
    pub value: i32,
    pub title: &'static str,
    pub hint: String,
}

fn p(value: i32, title: &'static str, hint: impl Into<String>) -> Preset {
    Preset { value, title, hint: hint.into() }
}

/// Размер запаса на фильм, МБ (0 — выкл.). `place` — «на телефоне» / «на диске».
pub fn burst(place: &str) -> Vec<Preset> {
    vec![
        p(0, "Выкл", format!("Как раньше: фильм идёт напрямую, без запаса {place}.")),
        p(32, "32 МБ", format!("SD и 720p (до ~4 Мбит/с, файлы до ~2 ГБ). Когда {place} мало места.")),
        p(64, "64 МБ", "Обычный 1080p (до ~8 Мбит/с, файлы 2–6 ГБ). Примерно минута запаса."),
        p(96, "96 МБ", "1080p хорошего качества (до ~15 Мбит/с, файлы 6–12 ГБ). Подходит почти всем."),
        p(128, "128 МБ", "1080p Remux и 4K (от ~15 Мбит/с, файлы от 12 ГБ)."),
        p(160, "160 МБ", "Тяжёлый 4K и Remux (до ~30 Мбит/с, файлы 20–40 ГБ). Большой запас при нестабильной связи."),
        p(
            256,
            "256 МБ",
            "Максимальный запас: файлы от 40–50 ГБ. Качает большими порциями, выдерживает долгие обрывы связи.",
        ),
    ]
}

pub const BURST_DEFAULT: i32 = 96;
pub const BURST_MAX: i32 = 256;

pub fn clamp_burst(mb: i32) -> i32 {
    mb.clamp(0, BURST_MAX)
}

pub const BITRATE_HINT: &str = "Подсказка: битрейт (Мбит/с) ≈ размер файла в ГБ × 2,2 ÷ длительность в часах.";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clamp() {
        assert_eq!(clamp_burst(-5), 0);
        assert_eq!(clamp_burst(0), 0);
        assert_eq!(clamp_burst(96), 96);
        assert_eq!(clamp_burst(160), 160);
        assert_eq!(clamp_burst(256), 256);
        assert_eq!(clamp_burst(999), 256);
        assert!(burst("на диске").iter().any(|p| p.value == BURST_DEFAULT));
    }
}
