package ru.suslikovanora.media.desktop.discord

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscordRpcTest {

    @Test
    fun partyModePlayingStatus() {
        val now = 1_700_000_000_000L
        val activity = DiscordRpc.buildActivity(
            title = "Интерстеллар",
            subtitle = "Смотрит вместе (участников: 3)",
            playing = true,
            timeMs = 15_000L,
            lengthMs = 120_000L,
            posterUrl = "https://example.com/poster.jpg",
            largeText = "Интерстеллар",
            nowMs = now
        )

        assertEquals(3, activity.getInt("type"))
        assertEquals("Suslik Media", activity.getString("name"))
        assertEquals("Интерстеллар", activity.getString("details"))
        assertEquals("Смотрит вместе (участников: 3)", activity.getString("state"))

        val assets = activity.getJSONObject("assets")
        assertEquals("https://example.com/poster.jpg", assets.getString("large_image"))
        assertEquals("Интерстеллар", assets.getString("large_text"))
        assertEquals("play", assets.getString("small_image"))
        assertEquals("Воспроизведение", assets.getString("small_text"))

        val ts = activity.getJSONObject("timestamps")
        assertEquals((now - 15_000L) / 1000L, ts.getLong("start"))
        assertEquals((now + 105_000L) / 1000L, ts.getLong("end"))
    }

    @Test
    fun partyModeSeriesPaused() {
        val activity = DiscordRpc.buildActivity(
            title = "Во все тяжкие · 1x02",
            subtitle = "Смотрит вместе (участников: 2) · Пауза",
            playing = false,
            timeMs = 30_000L,
            lengthMs = 200_000L,
            posterUrl = "https://example.com/series.jpg",
            largeText = "Во все тяжкие: 1x02"
        )

        assertEquals("Во все тяжкие · 1x02", activity.getString("details"))
        assertEquals("Смотрит вместе (участников: 2) · Пауза", activity.getString("state"))

        val assets = activity.getJSONObject("assets")
        assertEquals("https://example.com/series.jpg", assets.getString("large_image"))
        assertEquals("Во все тяжкие: 1x02", assets.getString("large_text"))
        assertEquals("pause", assets.getString("small_image"))
        assertEquals("Пауза", assets.getString("small_text"))

        // When paused, timestamps are not added so progress bar stays still
        assertFalse(activity.has("timestamps"))
    }

    @Test
    fun soloPlaybackTitlesAndFallbackAssets() {
        val playingActivity = DiscordRpc.buildActivity(
            title = "Матрица",
            subtitle = "Фильм",
            playing = true,
            posterUrl = null
        )
        assertEquals("Матрица", playingActivity.getString("details"))
        assertEquals("Фильм", playingActivity.getString("state"))
        assertEquals("brand-mark", playingActivity.getJSONObject("assets").getString("large_image"))
        assertEquals("play", playingActivity.getJSONObject("assets").getString("small_image"))

        val pausedActivity = DiscordRpc.buildActivity(
            title = "Матрица",
            subtitle = "Пауза",
            playing = false,
            posterUrl = null
        )
        assertEquals("Матрица", pausedActivity.getString("details"))
        assertEquals("Пауза", pausedActivity.getString("state"))
        assertEquals("pause", pausedActivity.getJSONObject("assets").getString("small_image"))
    }
}
