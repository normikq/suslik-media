package ru.suslikovanora.media.desktop.trackers

import ru.suslikovanora.media.desktop.Store
import ru.suslikovanora.media.desktop.tr

data class TrackerSourceInfo(
    val id: String,
    val name: String,
    val descKey: String,
    val tagKey: String,
    val isLocal: Boolean = false
) {
    val description: String get() = tr(descKey)
    val tag: String get() = tr(tagKey)
}

object TrackerSettings {
    private val store: Store
        get() = LocalTrackersManager.store

    val SOURCES = listOf(
        TrackerSourceInfo(
            id = "rutor",
            name = "Rutor",
            descKey = "trackers.desc_kinozal",
            tagKey = "trackers.badge_kinozal"
        ),
        TrackerSourceInfo(
            id = "nnmclub",
            name = "NNM-Club",
            descKey = "trackers.desc_rutor",
            tagKey = "trackers.badge_rutor"
        ),
        TrackerSourceInfo(
            id = "tpb",
            name = "The Pirate Bay",
            descKey = "trackers.desc_torrentgalaxy",
            tagKey = "trackers.badge_torrentgalaxy"
        ),
        TrackerSourceInfo(
            id = "nyaa",
            name = "Nyaa",
            descKey = "trackers.desc_nyaa",
            tagKey = "trackers.badge_nyaa"
        ),
        TrackerSourceInfo(
            id = "rutracker",
            name = "RuTracker (ПК)",
            descKey = "trackers.desc_rutracker",
            tagKey = "trackers.badge_rutracker",
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
