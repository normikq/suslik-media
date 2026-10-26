package ru.suslikovanora.media.desktop.trackers

import ru.suslikovanora.media.desktop.Store

data class TrackerSourceInfo(
    val id: String,
    val name: String,
    val description: String,
    val tag: String,
    val isLocal: Boolean = false
)

object TrackerSettings {
    private val store: Store
        get() = LocalTrackersManager.store

    val SOURCES = listOf(
        TrackerSourceInfo(
            id = "rutor",
            name = "Rutor",
            description = "Открытый русскоязычный трекер · Фильмы и сериалы",
            tag = "RU · Сервер"
        ),
        TrackerSourceInfo(
            id = "nnmclub",
            name = "NNM-Club",
            description = "Фильмы, сериалы, классика кино, редкие и авторские релизы",
            tag = "RU · Сервер"
        ),
        TrackerSourceInfo(
            id = "tpb",
            name = "The Pirate Bay",
            description = "Международный открытый трекер · Оригинальные дорожки, 4K UHD",
            tag = "EN · Сервер"
        ),
        TrackerSourceInfo(
            id = "nyaa",
            name = "Nyaa",
            description = "Аниме, дорамы, азиатские фильмы и сериалы",
            tag = "ANIME · Сервер"
        ),
        TrackerSourceInfo(
            id = "rutracker",
            name = "RuTracker (ПК)",
            description = "Прямой поиск с вашего компьютера в обход Cloudflare и блокировок",
            tag = "ПК · Локально",
            isLocal = true
        )
    )

    fun isEnabled(id: String): Boolean {
        if (id == "rutracker") return RuTrackerTracker.enabled
        return store.get("tracker.$id.enabled") != "false"
    }

    fun setEnabled(id: String, enabled: Boolean) {
        if (id == "rutracker") {
            RuTrackerTracker.enabled = enabled
        } else {
            store.put("tracker.$id.enabled", enabled.toString())
        }
        Store.flushAll()
    }

    fun setAll(enabled: Boolean) {
        for (s in SOURCES) {
            setEnabled(s.id, enabled)
        }
    }

    fun setOnlyRussian() {
        setEnabled("rutor", true)
        setEnabled("nnmclub", true)
        setEnabled("rutracker", true)
        setEnabled("tpb", false)
        setEnabled("nyaa", false)
    }

    fun isProviderEnabled(providerName: String): Boolean {
        val p = providerName.lowercase()
        return when {
            p.contains("rutor") -> isEnabled("rutor")
            p.contains("nnm") -> isEnabled("nnmclub")
            p.contains("tpb") || p.contains("pirate") -> isEnabled("tpb")
            p.contains("nyaa") -> isEnabled("nyaa")
            p.contains("rutracker") -> isEnabled("rutracker")
            else -> true
        }
    }

    fun isSourceEnabledForHit(source: String): Boolean {
        val s = source.lowercase()
        val fromRutor = s.contains("rutor")
        val fromNnm = s.contains("nnm")
        val fromTpb = s.contains("tpb") || s.contains("pirate")
        val fromNyaa = s.contains("nyaa")
        val fromRutracker = s.contains("rutracker")

        if (fromRutor || fromNnm || fromTpb || fromNyaa || fromRutracker) {
            return (fromRutor && isEnabled("rutor")) ||
                   (fromNnm && isEnabled("nnmclub")) ||
                   (fromTpb && isEnabled("tpb")) ||
                   (fromNyaa && isEnabled("nyaa")) ||
                   (fromRutracker && isEnabled("rutracker"))
        }

        return true
    }
}
