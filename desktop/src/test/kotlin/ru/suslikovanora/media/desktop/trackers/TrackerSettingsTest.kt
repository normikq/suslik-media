package ru.suslikovanora.media.desktop.trackers

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TrackerSettingsTest {

    /**
     * Тесты пишут в папку данных приложения. На всякий случай (запуск в обход tasks.test, где задан suslik.appdir)
     * состояние трекеров возвращается ровно таким, каким было: раньше в конце стоял setAll(true) и выбор владельца пропадал.
     */
    private fun <T> keepingSettings(block: () -> T): T {
        val before = TrackerSettings.SOURCES.associate { it.id to TrackerSettings.isEnabled(it.id) }
        try {
            return block()
        } finally {
            before.forEach { (id, on) -> TrackerSettings.setEnabled(id, on) }
        }
    }

    @Test
    fun testTrackerSettingsToggle() = keepingSettings {
        val rutorInitial = TrackerSettings.isEnabled("rutor")
        TrackerSettings.setEnabled("rutor", !rutorInitial)
        assertEquals(!rutorInitial, TrackerSettings.isEnabled("rutor"))

        // Restore
        TrackerSettings.setEnabled("rutor", rutorInitial)
        assertEquals(rutorInitial, TrackerSettings.isEnabled("rutor"))
    }

    @Test
    fun testProviderAndHitFiltering() = keepingSettings {
        TrackerSettings.setEnabled("nyaa", false)
        TrackerSettings.setEnabled("tpb", false)
        TrackerSettings.setEnabled("rutor", true)

        assertFalse(TrackerSettings.isProviderEnabled("Nyaa"))
        assertFalse(TrackerSettings.isProviderEnabled("TPB"))
        assertTrue(TrackerSettings.isProviderEnabled("Rutor"))

        assertFalse(TrackerSettings.isSourceEnabledForHit("Nyaa"))
        assertFalse(TrackerSettings.isSourceEnabledForHit("The Pirate Bay"))
        assertTrue(TrackerSettings.isSourceEnabledForHit("Rutor"))

        // Reset all
        TrackerSettings.setAll(true)
        assertTrue(TrackerSettings.isEnabled("nyaa"))
        assertTrue(TrackerSettings.isEnabled("tpb"))
    }
}
