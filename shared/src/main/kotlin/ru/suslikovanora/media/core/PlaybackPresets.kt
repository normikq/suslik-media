package ru.suslikovanora.media.core

/** «Настройки»: the presets and their hints, the same in both apps (each app only words «where» differently). */
object PlaybackPresets {
    data class Preset(val value: Int, val title: String, val hint: String)

    /** Size of the reserve per film, in MB (0 = off: VLC reads the mirror directly). [where] = «на телефоне» / «на диске». */
    fun burst(where: String) = listOf(
        Preset(0, "Выкл", "Как раньше: фильм идёт напрямую, без запаса $where."),
        Preset(32, "32 МБ", "SD и 720p (до ~4 Мбит/с, файлы до ~2 ГБ). Когда $where мало места."),
        Preset(64, "64 МБ", "Обычный 1080p (до ~8 Мбит/с, файлы 2–6 ГБ). Примерно минута запаса."),
        Preset(96, "96 МБ", "1080p хорошего качества (до ~15 Мбит/с, файлы 6–12 ГБ). Подходит почти всем."),
        Preset(128, "128 МБ", "1080p Remux и 4K (от ~15 Мбит/с, файлы от 12 ГБ). Больше запас почти ничего не даёт.")
    )
    const val BURST_DEFAULT = 96
    /**
     * Never more than this per film: a bigger reserve barely helps (the network refills 128 MB in seconds) and is
     * mostly thrown away after a seek — only more load on the server. Older settings of 160–512 MB come down to it.
     */
    const val BURST_MAX = 128
    fun clampBurst(mb: Int) = mb.coerceIn(0, BURST_MAX)

    /** VLC's own buffer, ms: how much it holds before playing (start / after a seek). */
    val cache = listOf(
        Preset(1500, "1,5 с", "Самый быстрый старт и перемотка. Только со стабильным интернетом или запасом."),
        Preset(3000, "3 с", "Рекомендуется вместе с запасом: быстро стартует, провалы сети гасит запас."),
        Preset(6000, "6 с", "Без запаса: выдерживает короткие провалы сети."),
        Preset(10000, "10 с", "Слабый или нестабильный интернет: дольше старт, зато меньше остановок.")
    )
    const val CACHE_DEFAULT = 3000

    const val BITRATE_HINT = "Подсказка: битрейт (Мбит/с) ≈ размер файла в ГБ × 2,2 ÷ длительность в часах."
}
