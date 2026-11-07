package ru.suslikovanora.media.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** mpv took VLC's place (2.8.0): «vlc» in the «Плеер» setting and in EngineChoice's remembered files means mpv. */
class EngineKindTest {
    @Test fun currentTags() {
        assertEquals(EngineKind.MPV, EngineKind.ofTag("mpv"))
        assertEquals(EngineKind.MEDIA3, EngineKind.ofTag("media3"))
    }

    @Test fun vlcIsMpvNow() {
        assertEquals(EngineKind.MPV, EngineKind.ofTag("vlc"))
    }

    @Test fun unknownTags() {
        assertNull(EngineKind.ofTag(null))
        assertNull(EngineKind.ofTag(""))
        assertNull(EngineKind.ofTag("exo"))
    }

    /** EngineChoice stores "<tag>|<when>|<why>": the tag is what comes before the first '|'. */
    @Test fun rememberedRecord() {
        val v = "vlc|1730000000000|decoder gives no picture"
        assertEquals(EngineKind.MPV, EngineKind.ofTag(v.substringBefore('|')))
        assertEquals("decoder gives no picture", v.substringAfter('|').substringAfter('|'))
    }
}
